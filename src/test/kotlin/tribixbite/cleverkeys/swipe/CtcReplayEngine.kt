package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder
import tribixbite.cleverkeys.swipe.ctc.CtcContractionKeys
import tribixbite.cleverkeys.swipe.ctc.CtcFuzzyRescue
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
 * Alias-key injection and bounded fuzzy rescue are mirrored here. Secondary-language rank merging
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
        if (candidates.isEmpty()) {
            return Slate(
                words = rescued.take(8),
                scores = rescued.indices.map { 1000 / (it + 1) },
            )
        }
        // Mirrors CtcEngineAdapter: a softmax over final beam scores, scaled to 0..1000 and
        // rounded. The rank-1 guard is a RATIO of these, so reproducing the scale matters — a
        // linear rescale of raw beam scores would change which promotions the guard permits.
        val max = candidates.maxOf { it.finalScore }
        val exps = candidates.map { Math.exp(it.finalScore - max) }
        val sum = exps.sum()
        val words = buildList {
            add(beamWords.first())
            addAll(rescued)
            addAll(beamWords.drop(1).filterNot { it in this })
        }.take(8)
        val beamScores = exps.map { Math.round((it / sum) * 1000.0).toInt().coerceIn(0, 1000) }
        val scores = if (rescued.isEmpty()) {
            beamScores
        } else {
            val second = beamScores.getOrElse(1) { 0 }
            val firstRescue = maxOf(second + 1, beamScores.first() / 2)
                .coerceAtMost(beamScores.first() - 1)
            buildList {
                add(beamScores.first())
                rescued.indices.forEach { add((firstRescue - it).coerceAtLeast(second + 1)) }
                addAll(beamScores.drop(1))
            }.take(words.size)
        }
        return Slate(
            words = words,
            // AUDIT D3: `.roundToInt().coerceIn(0, 1000)`, matching CtcEngineAdapter exactly.
            // Truncating with `.toInt()` instead shifts scores down by up to 1, and the rank-1
            // guard is an integer comparison `scores[i] >= R_MIN * scores[0]` — so a ±1 drift can
            // flip a knife-edge promotion. Reproducing the scale is the whole point of this class.
            scores = scores,
        )
    }

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

            val params = CtcScoringParams.presetFor(language, topK = 8)
            return CtcReplayEngine(
                CtcSwipeDecoder(OnnxCtcEmissionModel(env, session), layout, trie, params),
                CtcFuzzyRescue.fromFrequencies(canonical),
                env, session,
            )
        }
    }
}
