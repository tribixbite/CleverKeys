package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-6 ablation suite (spec Phase 6: "Ablations asserted: frequency prior (with-prior
 * top-1 > without, measured on the ordinal-rank prior which actually has dynamic range),
 * corner-anchor bonus, short-word shape fade").
 *
 * Each ablation toggles ONE scoring component off (via a `config.copy(...)`), re-runs the
 * SAME synthetic grid against the FULL dictionary, and asserts the component earns its
 * place — i.e. removing it does not IMPROVE accuracy (and, for the frequency prior, that
 * keeping it is strictly better). This proves the shipping defaults are the tuning-optimal
 * config, not that every knob is individually decisive (a bounded sanity penalty may
 * legitimately tie on a clean synthetic grid — the assertion is "does not regress").
 *
 * Decode is ALWAYS against the FULL 98k English dictionary. Determinism (NFR-4): the
 * sample + seeds are fixed, so the with/without comparison is apples-to-apples.
 */
class GeoAblationTest {

    private val config = GeometricEngineConfig()
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val en = GeoTestFixtures.englishCkdt()

    // A shared TYPICAL sample (the tier where the prior + penalties actually bite —
    // CLEAN is too easy to separate the components, SLOPPY too noisy).
    private val sample: List<GeoAccuracyHarness.SampleWord> by lazy {
        GeoAccuracyHarness(qwerty, en, "ablation", config).stratifiedSample(200)
    }

    // ── Frequency prior: with-prior top-1 STRICTLY beats without ──────────────────

    @Test
    fun frequencyPrior_withPrior_beatsWithoutPrior_top1() {
        // The ordinal-rank prior (−λ_f·ln(1+r)) breaks template collisions in favor of the
        // more-frequent word. Removing it (λ_f = 0) lets a rarer collision partner tie and
        // win by the lexicographic fallback — so the with-prior top-1 must be STRICTLY
        // higher (the prior has real dynamic range on the ordinal rank, spec §5).
        val withPrior = gridTop1(config, GeoTraceSynthesizer.Tier.TYPICAL)
        val noPrior = gridTop1(config.copy(frequencyWeight = 0f), GeoTraceSynthesizer.Tier.TYPICAL)
        println("[ablation] frequency prior TYPICAL top-1: with=${pct(withPrior)} without=${pct(noPrior)} " +
            "delta=${pct(withPrior - noPrior)}")
        assertWithMessage("the ordinal-rank frequency prior must strictly improve TYPICAL top-1 " +
            "(common words win template collisions)")
            .that(withPrior).isGreaterThan(noPrior)
    }

    // ── Corner-anchor bonus: keeping it does not regress accuracy ──────────────────

    @Test
    fun cornerAnchorBonus_keeping_doesNotRegress() {
        // The bounded corner bonus rewards a template whose corners a gesture corner
        // matches. On the synthetic grid it is a small, bounded (≤ cornerAnchorBonus) tie
        // breaker — removing it must not IMPROVE accuracy (if it did, the bonus would be
        // mis-signed / a bias). Assert with-bonus ≥ without-bonus (helps or ties).
        val withBonus = gridTop1(config, GeoTraceSynthesizer.Tier.TYPICAL)
        val noBonus = gridTop1(config.copy(cornerAnchorBonus = 0f), GeoTraceSynthesizer.Tier.TYPICAL)
        println("[ablation] corner bonus TYPICAL top-1: with=${pct(withBonus)} without=${pct(noBonus)} " +
            "delta=${pct(withBonus - noBonus)}")
        assertWithMessage("removing the corner-anchor bonus must not improve accuracy (it is a valid, " +
            "bounded tie-breaker, not a bias)")
            .that(withBonus).isAtLeast(noBonus - 1e-9)
    }

    // ── Short-word shape fade: keeping it does not regress accuracy ────────────────

    @Test
    fun shortWordShapeFade_keeping_doesNotRegress() {
        // The shape-weight fade down-weights the shape channel for short-PATH words (where
        // bbox-normalized σ_s over-weights jitter, OQ3). Disabling it (floor → ~0 so
        // w_shape saturates to 1) must not IMPROVE accuracy — proving the fade is a safe
        // mitigation, not a regression. Measured over the whole TYPICAL grid (short words
        // are a stratum of it), so the fade's effect on short words shows through.
        val withFade = gridTop1(config, GeoTraceSynthesizer.Tier.TYPICAL)
        val noFade = gridTop1(config.copy(shortWordShapeFloorKw = 0.001f), GeoTraceSynthesizer.Tier.TYPICAL)
        println("[ablation] short-word fade TYPICAL top-1: with=${pct(withFade)} without=${pct(noFade)} " +
            "delta=${pct(withFade - noFade)}")
        assertWithMessage("removing the short-word shape fade must not improve accuracy (it is a safe " +
            "mitigation for the short-span shape weakness)")
            .that(withFade).isAtLeast(noFade - 1e-9)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** TYPICAL/CLEAN grid top-1 over [sample] under [cfg] (fresh engine + synthesizer). */
    private fun gridTop1(cfg: GeometricEngineConfig, tier: GeoTraceSynthesizer.Tier): Double {
        val synth = GeoTraceSynthesizer(cfg)
        val engine = GeometricSwipeEngine(cfg)
        engine.warmUp(qwerty, en)
        var decodes = 0; var t1 = 0
        for (sw in sample) {
            for (s in 0 until 3) {
                val seed = 0xAB1A_00L xor (sw.ordinal.toLong() shl 8) xor s.toLong()
                val trace = synth.synthesize(
                    sw.word, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                    tier, GeoTraceSynthesizer.DoubleLetterMode.NONE, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
                decodes++
                if (GeoIdealTrace.rankOf(sw.word, result) == 0) t1++
            }
        }
        return if (decodes > 0) t1.toDouble() / decodes else 0.0
    }

    private fun pct(x: Double) = "%.2f%%".format(x * 100)
}
