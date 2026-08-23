package tribixbite.cleverkeys.swipe

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import tribixbite.cleverkeys.swipe.ctc.CtcAzProjection
import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie
import tribixbite.cleverkeys.swipe.ctc.CtcScoringParams
import tribixbite.cleverkeys.swipe.ctc.CtcSwipeDecoder
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
 * Everything below the adapter is the shipped code: `CtcFeaturizer`, the real ONNX session,
 * `CtcBeamDecoder`, the real projected lexicon and the shipped `presetFor` parameters.
 * `CtcEngineAdapter` itself is skipped because it needs an Android `Context` — so the display
 * overlays it applies (contraction rewriting, canonical accents) are NOT applied here. For
 * measuring rescoring that is the right cut: the rescorer acts on the slate's ORDER, and the
 * overlays rewrite labels without reordering.
 *
 * The layout comes from the committed golden fixture, which is the canonical geometry the model
 * was trained against — not a phone's live key rects.
 */
class CtcReplayEngine private constructor(
    private val decoder: CtcSwipeDecoder,
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
        val candidates = decoder.decode(px, py, pt)
        if (candidates.isEmpty()) return Slate(emptyList(), emptyList())
        // Mirrors CtcEngineAdapter: a softmax over final beam scores, scaled to 0..1000 and
        // rounded. The rank-1 guard is a RATIO of these, so reproducing the scale matters — a
        // linear rescale of raw beam scores would change which promotions the guard permits.
        val max = candidates.maxOf { it.finalScore }
        val exps = candidates.map { Math.exp(it.finalScore - max) }
        val sum = exps.sum()
        return Slate(
            words = candidates.map { it.word },
            scores = exps.map { ((it / sum) * 1000.0).toInt() },
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

        /** True when the bionic ONNX natives are reachable — call before building. */
        fun ortAvailable(): Boolean = runCatching { OrtEnvironment.getEnvironment() }.isSuccess

        /**
         * Build the decoder from committed artefacts only.
         *
         * The English lexicon is `en_enhanced.json`, NOT a CKDT `.bin`. That asymmetry is
         * deliberate and load-bearing: en's lambda is fitted to this file's byte scale, so
         * "unifying" the two formats would silently change English decode ranking.
         */
        fun build(language: String = "en"): CtcReplayEngine {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(File(MODEL_ASSET).readBytes(), OrtSession.SessionOptions())

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
            val projected = CtcAzProjection.projectLexicon(canonical)
            val trie = CtcLexiconTrie.loadFromFrequencyMap(layout.alphabet, projected.freqs)

            val params = CtcScoringParams.presetFor(language, topK = 8)
            return CtcReplayEngine(
                CtcSwipeDecoder(OnnxCtcEmissionModel(env, session), layout, trie, params),
                env, session,
            )
        }
    }
}
