package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcEmissionModel
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcFuzzyRescue
import tribixbite.cleverkeys.swipe.ctc.CtcScriptSupport
import java.io.File

/**
 * The SHIPPING CTC decoder, wired up for pure-JVM replay.
 *
 * ## Why this can exist at all
 *
 * The `onnxruntime` JAR bundles a GLIBC-linked native that cannot load in Termux's bionic JVM.
 * That is a property of the bundled `.so`, not of ONNX — `build.gradle`'s `extractOrtNative`
 * unpacks the **bionic** arm64 natives from the `onnxruntime-android` AAR this project already
 * depends on, and `runPureTests` points `onnxruntime.native.path` at them. So the real encoder
 * runs here, against the real shipped model.
 *
 * ## Why the CTC arm matters more than the geometric one
 *
 * CTC is the DEFAULT swipe engine, and the rank-1 displacement guard is expressed as a ratio of
 * ITS scores — a softmax over final beam scores scaled to 0..1000. The geometric engine's slate
 * has a different distribution, so a rescoring result measured there does not transfer. Any
 * decision to flip `swipe_context_rescoring` on needs numbers from this path.
 *
 * ## Fidelity, and where it stops
 *
 * Everything below the adapter is the shipped code: `CtcFeaturizer`, the real ONNX session, the
 * real `CtcBeamDecoder`, the shipped EN_JSON strip-loaded lexicon and the shipped `presetFor`
 * parameters. `CtcEngineAdapter` itself is skipped because it needs an Android `Context`.
 *
 * **Known boundary** (audited 2026-08-23; adapter changes must update this list):
 *
 *  1. **Display overlays** — contraction rewriting and canonical accents. The rescorer acts on
 *     slate ORDER and `ContractionOverlay` appends variants at the end without reordering engine
 *     candidates, so this cannot change order — only membership. Its cost is MEASURED by the
 *     replay's `apostropheMissed` counter (0 on the 2026-08-23 run).
 * Alias-key injection is mirrored here; the bounded fuzzy rescue is not a mirror at all — this
 * calls the shipped [CtcFuzzyRescue.find] and [CtcFuzzyRescue.Companion.mergeIntoBeam] directly,
 * so the merge cannot drift from the adapter's. Secondary-language rank merging
 * is intentionally outside this English-only context corpus. Beam width (100), topK (8), the
 * softmax→0..1000 scale and its rounding, model asset, layout frame and provider setup are mirrored.
 *
 * The execution provider actually obtained is recorded in [executionProvider] rather than assumed,
 * because an EP difference can reorder near-tied beams.
 *
 * The layout comes from the committed golden fixture, which is the canonical geometry the model
 * was trained against — not a phone's live key rects.
 */
class CtcReplayEngine private constructor(
    private val decoder: CtcSwipeDecoder,
    private val fuzzyRescue: CtcFuzzyRescue,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    // ── diagnostic-only state (issue #162 replay instrument) ──────────────────
    // Held so [forcedDecode]/[layoutGeometry]/[frequencyOf] can rebuild a CONSTRAINED
    // decoder over the same model/layout/params without duplicating build() in a test.
    private val layout: CtcLayout,
    private val model: CtcEmissionModel,
    private val params: CtcScoringParams,
    private val frequencies: Map<String, Double>,
    // Held so [decoderFor] can re-bind the SAME trie/model/params to a different key
    // geometry (issue #75 replay instrument) without rebuilding the lexicon.
    private val trie: CtcLexiconTrie,
) : AutoCloseable {

    /** Decoded slate: display words plus the 0..1000 scores the rank-1 guard compares. */
    data class Slate(val words: List<String>, val scores: List<Int>)

    /**
     * Decode one trace.
     *
     * @param px,py normalized [0,1] coordinates over the layout's own frame.
     * @param pt timestamps in milliseconds.
     */
    fun decode(px: DoubleArray, py: DoubleArray, pt: DoubleArray): Slate {
        val decoded = decoder.decodeDetailed(px, py, pt)
        val candidates = decoded.candidates
        val beamWords = candidates.map { it.word }
        val rescued = fuzzyRescue.find(decoded.greedy, beamWords.toHashSet())
        // Shipping can recover a bounded dictionary match even when the constrained beam
        // produced no candidates. Keep that behaviour in replay instead of returning early.
        // The merge is the SHIPPED one (CtcFuzzyRescue.mergeIntoBeam) — there is no hand-copy
        // here to drift out of sync, and the empty-beam fill is that function's own branch.
        if (candidates.isEmpty()) {
            val (words, scores) =
                CtcFuzzyRescue.mergeIntoBeam(emptyList(), emptyList(), rescued, TOP_K)
            return Slate(words = words, scores = scores)
        }
        // Mirrors CtcEngineAdapter: a softmax over final beam scores, scaled to 0..1000 and
        // rounded. The rank-1 guard is a RATIO of these, so reproducing the scale matters — a
        // linear rescale of raw beam scores would change which promotions the guard permits.
        //
        // AUDIT D3: `.roundToInt().coerceIn(0, 1000)`, matching CtcEngineAdapter exactly.
        // Truncating with `.toInt()` instead shifts scores down by up to 1, and the rank-1
        // guard is an integer comparison `scores[i] >= R_MIN * scores[0]` — so a ±1 drift can
        // flip a knife-edge promotion. Reproducing the scale is the whole point of this class.
        val max = candidates.maxOf { it.finalScore }
        val exps = candidates.map { Math.exp(it.finalScore - max) }
        val sum = exps.sum()
        val beamScores = exps.map { Math.round((it / sum) * 1000.0).toInt().coerceIn(0, 1000) }
        val (words, scores) = CtcFuzzyRescue.mergeIntoBeam(beamWords, beamScores, rescued, TOP_K)
        return Slate(words = words, scores = scores)
    }

    // ── diagnostic hooks (issue #162 replay instrument; not used by the eval replays) ──

    /**
     * The raw beam decode with score components ([CtcCandidate.finalScore] /
     * [CtcCandidate.ctcScore] / [CtcCandidate.logFreq]) plus the unconstrained greedy
     * surface — everything [decode] collapses into the 0..1000 slate.
     */
    fun decodeDetailed(px: DoubleArray, py: DoubleArray, pt: DoubleArray): CtcSwipeDecoder.DecodeResult =
        decoder.decodeDetailed(px, py, pt)

    /** The golden layout geometry the engine decodes against — for trace synthesis in tests. */
    val layoutGeometry: CtcLayout get() = layout

    /** The shipped lexicon's frequency byte for [word] (en_enhanced.json scale), or null. */
    fun frequencyOf(word: String): Double? = frequencies[word]

    /**
     * FORCED decode: the shipped beam over a trie containing ONLY [words], each at its
     * SHIPPED frequency (missing words get 1.0). With a handful of words the per-frame
     * hypothesis count (≤ 2·nodes+1) never reaches `beamWidth`, so pruning cannot fire and
     * each returned [CtcCandidate.ctcScore] is the true Viterbi max-path log-score of that
     * word over these emissions — i.e. what the word WOULD have scored had the full-lexicon
     * beam kept its prefix alive. Comparing this against the open-vocabulary winners
     * separates "the beam pruned it" (search artifact) from "the emissions never supported
     * it" (model limitation).
     */
    fun forcedDecode(words: List<String>, px: DoubleArray, py: DoubleArray, pt: DoubleArray): List<CtcCandidate> {
        val constrained = LinkedHashMap<String, Double>()
        for (w in words) constrained[w] = frequencies[w] ?: 1.0
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(layout.alphabet, constrained)
        return CtcSwipeDecoder(model, layout, trie, params).decode(px, py, pt)
    }

    /**
     * The SHIPPED decode stack re-bound to a DIFFERENT key geometry (issue #75 replay
     * instrument). Same model, same trie (contraction alias keys included), same shipped
     * params — only [altLayout]'s key centers differ. This is exactly what
     * `CtcEngineAdapter.buildMappedLayout` does per board at runtime (key geometry is a
     * model INPUT, the encoder is layout-agnostic), so decoding a trace synthesized over a
     * non-QWERTY board's real centers through this decoder replays what a user of that
     * board gets.
     *
     * The alphabet must match the engine's — the trie and the emission slots are built over
     * it, and re-binding a different alphabet would silently permute every decode.
     */
    fun decoderFor(altLayout: CtcLayout): CtcSwipeDecoder {
        require(altLayout.alphabet.contentEquals(layout.alphabet)) {
            "decoderFor is same-alphabet only: trie/emission slots are built over " +
                String(layout.alphabet)
        }
        return CtcSwipeDecoder(model, altLayout, trie, params)
    }

    /**
     * The shipped base lexicon as `(word, frequency)` pairs in load order — the `basePairs`
     * input a `CtcLexiconMerge.merge` call gets in `CtcEngineAdapter.lexiconFor`, for
     * replaying user-dictionary merges (wave U2 custom-word calibration instrument).
     */
    fun baseLexiconPairs(): List<Pair<String, Double>> = frequencies.map { it.key to it.value }

    /**
     * The SHIPPED decode stack over a DIFFERENT (e.g. user-merged) lexicon — same model,
     * same golden layout, same shipped params; only the trie is rebuilt from [merged]
     * via the shipped EN_JSON STRIP loader (wave U2 custom-word calibration instrument).
     * This is exactly the trie build `CtcEngineAdapter.lexiconFor` performs after
     * `CtcLexiconMerge.merge`, minus the contraction alias-key injection — fine for
     * measuring custom words that collide with no contraction alias, which is what the
     * calibration replay decodes.
     */
    fun decoderWithLexicon(merged: LinkedHashMap<String, Double>): CtcSwipeDecoder {
        val customTrie = CtcLexiconTrie.loadStrippingNonAlphabet(layout.alphabet, merged)
        return CtcSwipeDecoder(model, layout, customTrie, params)
    }

    /** The shipped bounded rescue for a greedy surface — exposed for rescue-eligibility analysis. */
    fun rescueFor(greedy: String, existing: Set<String>): List<String> =
        fuzzyRescue.find(greedy, existing.toHashSet())

    override fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }

    companion object {
        const val MODEL_ASSET = "src/main/assets/models/ctc_swipe_encoder.onnx"
        const val GOLDEN_LAYOUT = "src/test/resources/ctc/ctc_golden.json"
        const val EN_LEXICON = "src/main/assets/dictionaries/en_enhanced.json"
        const val EN_CONTRACTIONS = "src/main/assets/dictionaries/contractions_en.json"
        const val EN_PAIRINGS = "src/main/assets/dictionaries/contraction_pairings.json"

        /** Mirrors `ModelLoader`'s default `xnnpackThreads = 2`. */
        const val XNNPACK_THREADS = 2

        /** Mirrors `CtcEngineAdapter.TOP_K` — the decoded slate size and the rescue merge budget. */
        const val TOP_K = 8

        /** True when the bionic ONNX natives are reachable — call before building. */
        fun ortAvailable(): Boolean = runCatching { OrtEnvironment.getEnvironment() }.isSuccess

        /** Which execution provider the last [build] actually obtained — reported, not assumed. */
        @Volatile
        var executionProvider: String = "unknown"
            private set

        /**
         * Build the decoder from committed artefacts only. **English only.**
         *
         * The English lexicon is `en_enhanced.json`, NOT a CKDT `.bin`, and it is loaded through
         * the STRIP loader with no accent folding — the shipped `EN_JSON` branch
         * (`CtcEngineAdapter.kt:489-497`). That asymmetry is deliberate and load-bearing: en's
         * lambda is fitted to this vocabulary, so "unifying" the two loaders would silently change
         * English decode ranking. A CKDT language would need the `CtcAzProjection` branch instead;
         * passing one here would build the wrong trie, which is why this is en-only.
         */
        fun build(language: String = "en"): CtcReplayEngine {
            require(language == "en") {
                "CtcReplayEngine is en-only: it hard-codes the EN_JSON strip-loader branch. A CKDT " +
                    "language needs CtcAzProjection + loadFromFrequencyMap (CtcEngineAdapter:498)."
            }
            val env = OrtEnvironment.getEnvironment()
            // AUDIT D4: mirror `ModelLoader.createOptimizedSessionOptions` +
            // `tryEnableHardwareAcceleration` — ALL_OPT, then XNNPACK-first at 2 threads with a
            // CPU fallback. An execution-provider difference can reorder near-tied beams, so the
            // replay must not quietly run a different one than the app. Whichever is obtained is
            // recorded in [executionProvider] and printed by the replay report.
            val options = OrtSession.SessionOptions()
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            executionProvider = try {
                options.addXnnpack(mapOf("intra_op_num_threads" to XNNPACK_THREADS.toString()))
                options.setIntraOpNumThreads(XNNPACK_THREADS)
                "xnnpack(${XNNPACK_THREADS})"
            } catch (t: Throwable) {
                // The bionic AAR native may not carry the XNNPACK EP. Falling back is correct;
                // silently pretending it was used would not be.
                options.setIntraOpNumThreads(0)
                "cpu (xnnpack unavailable: ${t::class.java.simpleName})"
            }
            val session = env.createSession(File(MODEL_ASSET).readBytes(), options)

            val goldenLayout = JSONObject(File(GOLDEN_LAYOUT).readText()).getJSONObject("layout")
            // `letters` is a STRING in the fixture ("abcdefghijklmnopqrstuvwxyz"), not an array.
            val letters = goldenLayout.getString("letters")
            val cxArr = goldenLayout.getJSONArray("cx")
            val cyArr = goldenLayout.getJSONArray("cy")
            val layout = CtcLayout.of(
                letters.toList(),
                (0 until cxArr.length()).map { cxArr.getDouble(it).toFloat() },
                (0 until cyArr.length()).map { cyArr.getDouble(it).toFloat() },
            )

            val base = JSONObject(File(EN_LEXICON).readText())
            val canonical = LinkedHashMap<String, Double>(base.length() * 2)
            val keys = base.keys()
            while (keys.hasNext()) {
                val w = keys.next()
                canonical[w] = base.optInt(w, 1).toDouble()
            }
            // AUDIT D1 — the fidelity defect this class existed to avoid, committed by this class.
            //
            // The shipped EN_JSON branch uses `loadStrippingNonAlphabet` with **no accent
            // folding** (`CtcEngineAdapter.kt:489-497`), an asymmetry that file marks "deliberate,
            // do NOT unify" because en's λ is fitted to exactly this vocabulary. The replay used
            // the CKDT branch's `CtcAzProjection` instead, which FOLDS accents — so "café"
            // contributed the surface "cafe" here but "caf" in shipping.
            //
            // Measured divergence: 148 surfaces reachable only in shipping, 26 only in the
            // projection, and 45 shared surfaces at different frequencies (the trie's insert takes
            // the max on duplicates). None of them touched this run's outcome words — but a
            // fidelity claim that is not true of the code is exactly the failure class the prior
            // six errors belonged to.
            val trie = CtcLexiconTrie.loadStrippingNonAlphabet(layout.alphabet, canonical)
            val aliasKeys = LinkedHashSet<String>()
            for (path in listOf(EN_CONTRACTIONS, EN_PAIRINGS)) {
                val aliases = JSONObject(File(path).readText()).keys()
                while (aliases.hasNext()) aliasKeys.add(aliases.next())
            }
            CtcContractionKeys.inject(
                trie,
                aliasKeys,
                CtcContractionKeys.derivedFloor(canonical.values),
            )

            val params = CtcScoringParams.presetFor(language, topK = TOP_K)
            val model = OnnxCtcEmissionModel(env, session)
            return CtcReplayEngine(
                CtcSwipeDecoder(model, layout, trie, params),
                // Alphabet-scoped, mirroring CtcEngineAdapter: the replay harness must build the
                // SAME rescue index the app does or its measurements describe a different engine.
                CtcFuzzyRescue.fromFrequencies(canonical, CtcScriptSupport.alphabetFor(language).toHashSet()),
                env, session,
                layout, model, params, canonical, trie,
            )
        }
    }
}
