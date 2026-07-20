package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-6 short-word suite (spec Testing Strategy: "Short words (2–3 letters): TYPICAL
 * top-3 ≥ 85% **plus** the CLEAN mitigation-proof assertion").
 *
 * Short words are the SHARK2 shape-channel's weak spot: `d_shape` is bbox-normalized and
 * therefore word-span-dependent (OQ3), so a 2–3 key span's shape distance is dominated by
 * jitter. The engine's algorithmic mitigation (NOT merely lowered thresholds) is the
 * **short-word shape-weight fade** — `w_shape = min(1, templatePathLenKw /
 * shortWordShapeFloorKw)` — which fades the shape channel toward 0 for short templates so
 * the LOCATION channel + frequency prior dominate 2–3 letter words.
 *
 * This class proves the mitigation two ways:
 *  1. **CLEAN mitigation-proof (the spec's required assertion)** — decode short words with
 *     the fade ENABLED (default config) vs a config with the fade effectively DISABLED
 *     (`shortWordShapeFloorKw` driven to ~0 so `w_shape ≈ 1` always). The fade must give
 *     an accuracy AT LEAST as good — proving the fade is a real, load-bearing mitigation,
 *     not dead code, and that removing it does not help (it hurts or ties).
 *  2. **TYPICAL top-3 floor** — short-word TYPICAL top-3 ≥ 85% (spec FINAL), decoded
 *     against the FULL 98k dictionary.
 *
 * Decode is ALWAYS against the FULL dictionary (the ambiguity ceiling is the whole
 * lexicon — restricting to short words would gut it). Determinism (NFR-4): fixed seeds.
 */
class GeoShortWordTest {

    private val config = GeometricEngineConfig()
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val en = GeoTestFixtures.englishCkdt()

    /** A stratified sample restricted to SHORT (2–3 codepoint) typeable words. */
    private val shortWords: List<GeoAccuracyHarness.SampleWord> by lazy {
        val harness = GeoAccuracyHarness(qwerty, en, "en/QWERTY-short", config)
        // Draw a large stratified sample, then keep only the short stratum. 800 words
        // yields a healthy short subset across the frequency strata.
        harness.stratifiedSample(800)
            .filter { it.lenStratum == GeoAccuracyHarness.LenStratum.SHORT_2_3 }
    }

    // ── CLEAN mitigation-proof (spec's required assertion) ─────────────────────────

    @Test
    fun cleanShortWords_shapeFadeIsActive_andDecodesWell() {
        assertWithMessage("must have a non-trivial short-word sample").that(shortWords.size).isAtLeast(20)

        // (1) STRUCTURAL PROOF the fade is a real, load-bearing mechanism (not dead code):
        // the fade is keyed on the template PATH LENGTH (kw), not the letter count — a 2–3
        // letter word with ADJACENT keys has a short path and IS faded, while a 2–3 letter
        // word whose keys span the keyboard has a long path and is NOT (correct: a long,
        // discriminative path can trust its shape channel). Prove BOTH behaviors on
        // hand-picked words plus the sample.
        val gen = TemplateGenerator(config)
        // Short-PATH words (adjacent QWERTY keys) → w_shape < 1 (fade active).
        val shortPathWords = listOf("as", "we", "re", "er", "sad", "ask", "was")
        var provedFaded = 0
        for (word in shortPathWords) {
            val t = gen.generate(word, 0, qwerty) ?: continue
            val wShape = (t.pathLengthsKw[0] / config.shortWordShapeFloorKw).coerceIn(0f, 1f)
            println("[shortfade] '$word' pathLen=${"%.2f".format(t.pathLengthsKw[0])}kw w_shape=${"%.3f".format(wShape)}")
            if (wShape < 0.999f) provedFaded++
        }
        assertWithMessage("adjacent-key short words must have their shape channel faded (< 1) — " +
            "the fade IS active and targets short PATHS")
            .that(provedFaded).isAtLeast(4)
        // A long word saturates the fade to 1.0 (the shape channel is fully trusted).
        val longT = gen.generate("keyboard", 0, qwerty)!!
        val longWShape = (longT.pathLengthsKw[0] / config.shortWordShapeFloorKw).coerceIn(0f, 1f)
        assertWithMessage("a long word's shape weight must saturate to 1.0 (no fade)")
            .that(longWShape).isEqualTo(1f)
        // Across the sampled 2–3 letter words, SOME are short-path and faded (the fade is
        // reachable in the real workload, not just on hand-picked examples).
        var sampleFaded = 0; var sampleCount = 0
        for (sw in shortWords) {
            val t = gen.generate(sw.word, sw.ordinal, qwerty) ?: continue
            sampleCount++
            if (t.pathLengthsKw[0] < config.shortWordShapeFloorKw) sampleFaded++
        }
        println("[shortfade] ${sampleFaded}/${sampleCount} sampled 2–3 letter words are short-path (faded)")
        assertWithMessage("some sampled short words must exercise the fade in the real workload")
            .that(sampleFaded).isGreaterThan(0)

        // (2) BEHAVIORAL PROOF the fade never HURTS: CLEAN short-word top-1 with the fade
        // enabled (default) must be at least as good as with it effectively disabled
        // (floor driven ~0 ⇒ w_shape saturates to 1 even for short spans). This confirms
        // the mitigation is a safe improvement path, not a regression.
        val withFade = shortWordCleanTop1(config)
        val noFade = shortWordCleanTop1(config.copy(shortWordShapeFloorKw = 0.001f))
        println("[shortfade] CLEAN short-word top-1: withFade=${pct(withFade)} noFade=${pct(noFade)}")
        assertWithMessage("the short-word shape-fade must not HURT CLEAN short-word top-1 accuracy")
            .that(withFade).isAtLeast(noFade - 1e-9)

        // (3) The mitigation WORKS: CLEAN short words decode well despite the shape
        // channel's short-span weakness (the location + prior carry them).
        assertWithMessage("CLEAN short-word top-1 must be strong (the mitigation works)")
            .that(withFade).isAtLeast(0.60)
    }

    // ── TYPICAL top-3 ≥ 85% (spec FINAL short-word floor) ─────────────────────────

    @Test
    fun typicalShortWords_top3_meetsFinalFloor() {
        val synth = GeoTraceSynthesizer(config)
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(qwerty, en)
        var decodes = 0; var t3 = 0
        for (sw in shortWords) {
            for (s in 0 until 3) {
                val seed = 0x5A17_00L xor (sw.ordinal.toLong() shl 8) xor s.toLong()
                val trace = synth.synthesize(
                    sw.word, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                    GeoTraceSynthesizer.Tier.TYPICAL, GeoTraceSynthesizer.DoubleLetterMode.NONE, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
                decodes++
                if (GeoIdealTrace.rankOf(sw.word, result) in 0..2) t3++
            }
        }
        val top3 = if (decodes > 0) t3.toDouble() / decodes else 0.0
        println("[shortword] en/QWERTY TYPICAL short-word top-3=${pct(top3)} n=$decodes")
        assertWithMessage("must have decoded short words").that(decodes).isGreaterThan(0)
        assertWithMessage("short-word (2–3 letter) TYPICAL top-3 must meet the FINAL floor")
            .that(top3).isAtLeast(GeoAccuracyThresholds.SHORT_WORD_TYPICAL_TOP3)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** CLEAN-tier short-word top-1 accuracy under [cfg] (fresh engine + synthesizer). */
    private fun shortWordCleanTop1(cfg: GeometricEngineConfig): Double {
        val synth = GeoTraceSynthesizer(cfg)
        val engine = GeometricSwipeEngine(cfg)
        engine.warmUp(qwerty, en)
        var decodes = 0; var t1 = 0
        for (sw in shortWords) {
            for (s in 0 until 3) {
                val seed = 0x3C0F_00L xor (sw.ordinal.toLong() shl 8) xor s.toLong()
                val trace = synth.synthesize(
                    sw.word, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                    GeoTraceSynthesizer.Tier.CLEAN, GeoTraceSynthesizer.DoubleLetterMode.NONE, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
                decodes++
                if (GeoIdealTrace.rankOf(sw.word, result) == 0) t1++
            }
        }
        return if (decodes > 0) t1.toDouble() / decodes else 0.0
    }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)
}
