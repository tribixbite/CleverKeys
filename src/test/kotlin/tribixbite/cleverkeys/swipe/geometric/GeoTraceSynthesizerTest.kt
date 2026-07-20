package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.sqrt
import org.junit.Test

/**
 * Phase-5 tests for [GeoTraceSynthesizer] — the synthetic swipe-trace generator that
 * feeds the accuracy harness.
 *
 * Coverage (per the spec's Phase-5 checklist):
 *  - **Endpoint proximity** — a synthetic trace's first/last points land near the
 *    first/last key centroids (within the tier's endpoint slack), so the extremity
 *    buckets can recover the true word.
 *  - **Monotone timestamps** — sample timestamps are strictly non-decreasing (the
 *    caller's velocity/gating logic assumes a monotone `tMillis`).
 *  - **Seed determinism** — the same `(word, seed, tier, mode)` produces a byte-
 *    identical trace (NFR-4), and different seeds produce different traces.
 *  - **Tier monotonicity** — CLEAN traces hug the ideal path more tightly than
 *    TYPICAL, which is tighter than SLOPPY (mean deviation from the anchor polyline
 *    increases with tier difficulty).
 *  - Double-letter modes (LOOP/DWELL/NONE) and single-key words all synthesize finite,
 *    ≥ 3-point traces.
 */
class GeoTraceSynthesizerTest {

    private val config = GeometricEngineConfig()
    private val synth = GeoTraceSynthesizer(config)
    private val gen = TemplateGenerator(config)

    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val jcuken = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")

    private val widthPx = 1000f
    private val heightPx = 500f // aspect 2.0 matches the fixture DEFAULT_ASPECT

    // ── endpoint proximity ────────────────────────────────────────────────────────

    @Test
    fun synthesize_endpoints_landNearFirstAndLastKeyCentroids() {
        // The first/last synthetic points must sit near the word's first/last key
        // centroids. Even at SLOPPY the endpoint offset σ is 0.30 kw, so a generous
        // multi-kw tolerance holds (proves the extremity buckets can recover the word).
        for (tier in GeoTraceSynthesizer.Tier.values()) {
            val word = "keyboard"
            val trace = synth.synthesize(word, qwerty, widthPx, heightPx, tier, seed = 123L)!!
            assertWithMessage("$tier: trace must have >= 3 points").that(trace.size).isAtLeast(3)

            val pre = LayoutProjection.project(word, qwerty)!!
            val firstKey = qwerty.keys[pre.first()]
            val lastKey = qwerty.keys[pre.last()]

            val startKw = kwDist(trace.first(), firstKey, qwerty)
            val endKw = kwDist(trace.last(), lastKey, qwerty)
            // Tolerance scales with the tier's endpoint σ (plus overshoot headroom).
            val tol = 1.5f + 4f * tier.endSigmaKw + GeoTraceSynthesizer.OVERSHOOT_MAX_KW
            assertWithMessage("$tier '$word': start must be within $tol kw of the first key (was $startKw)")
                .that(startKw).isLessThan(tol)
            assertWithMessage("$tier '$word': end must be within $tol kw of the last key (was $endKw)")
                .that(endKw).isLessThan(tol)
        }
    }

    // ── monotone timestamps ───────────────────────────────────────────────────────

    @Test
    fun synthesize_timestamps_areMonotonicNonDecreasing() {
        for (tier in GeoTraceSynthesizer.Tier.values()) {
            val trace = synth.synthesize("language", qwerty, widthPx, heightPx, tier, seed = 7L)!!
            var prev = Long.MIN_VALUE
            for (p in trace) {
                assertWithMessage("$tier: timestamps must be non-decreasing")
                    .that(p.tMillis).isAtLeast(prev)
                prev = p.tMillis
            }
            // The trace must span a positive duration (non-degenerate).
            assertWithMessage("$tier: trace must span a positive duration")
                .that(trace.last().tMillis).isGreaterThan(trace.first().tMillis)
        }
    }

    // ── seed determinism ──────────────────────────────────────────────────────────

    @Test
    fun synthesize_sameSeed_producesIdenticalTrace() {
        val a = synth.synthesize("computer", qwerty, widthPx, heightPx,
            GeoTraceSynthesizer.Tier.TYPICAL, seed = 42L)!!
        val b = synth.synthesize("computer", qwerty, widthPx, heightPx,
            GeoTraceSynthesizer.Tier.TYPICAL, seed = 42L)!!
        assertWithMessage("same seed must produce the same number of points")
            .that(b.size).isEqualTo(a.size)
        for (i in a.indices) {
            assertWithMessage("point $i x must match (NFR-4 determinism)").that(b[i].x).isEqualTo(a[i].x)
            assertWithMessage("point $i y must match").that(b[i].y).isEqualTo(a[i].y)
            assertWithMessage("point $i t must match").that(b[i].tMillis).isEqualTo(a[i].tMillis)
        }
    }

    @Test
    fun synthesize_differentSeeds_produceDifferentTraces() {
        val a = synth.synthesize("computer", qwerty, widthPx, heightPx,
            GeoTraceSynthesizer.Tier.TYPICAL, seed = 1L)!!
        val b = synth.synthesize("computer", qwerty, widthPx, heightPx,
            GeoTraceSynthesizer.Tier.TYPICAL, seed = 2L)!!
        // At TYPICAL noise, two different seeds must diverge somewhere (not identical).
        var differs = a.size != b.size
        if (!differs) {
            for (i in a.indices) {
                if (a[i].x != b[i].x || a[i].y != b[i].y) { differs = true; break }
            }
        }
        assertWithMessage("different seeds must produce different traces").that(differs).isTrue()
    }

    // ── tier monotonicity ─────────────────────────────────────────────────────────

    @Test
    fun synthesize_deviationFromIdeal_increasesWithTierDifficulty() {
        // Mean deviation of the synthetic samples from the ideal anchor polyline must
        // grow CLEAN < TYPICAL < SLOPPY (averaged over several seeds to kill variance).
        val words = listOf("keyboard", "language", "because", "program")
        val clean = meanDeviationKw(words, GeoTraceSynthesizer.Tier.CLEAN)
        val typical = meanDeviationKw(words, GeoTraceSynthesizer.Tier.TYPICAL)
        val sloppy = meanDeviationKw(words, GeoTraceSynthesizer.Tier.SLOPPY)
        println("[synth-dev] clean=${"%.3f".format(clean)} typical=${"%.3f".format(typical)} " +
            "sloppy=${"%.3f".format(sloppy)} (kw)")
        assertWithMessage("TYPICAL must deviate more than CLEAN").that(typical).isGreaterThan(clean)
        assertWithMessage("SLOPPY must deviate more than TYPICAL").that(sloppy).isGreaterThan(typical)
    }

    // ── double-letter modes + single-key words ────────────────────────────────────

    @Test
    fun synthesize_doubleLetterModes_allProduceFiniteTraces() {
        // "fell" has a doubled letter; every mode must produce a valid >= 3-point trace.
        for (mode in GeoTraceSynthesizer.DoubleLetterMode.values()) {
            val trace = synth.synthesize("fell", qwerty, widthPx, heightPx,
                GeoTraceSynthesizer.Tier.TYPICAL, mode, seed = 99L)!!
            assertWithMessage("mode=$mode: >= 3 points").that(trace.size).isAtLeast(3)
            for (p in trace) {
                assertWithMessage("mode=$mode: finite x").that(p.x.isFinite()).isTrue()
                assertWithMessage("mode=$mode: finite y").that(p.y.isFinite()).isTrue()
            }
        }
    }

    @Test
    fun synthesize_singleKeyWord_producesFiniteTrace_allModes() {
        // "её" collapses to a single key on JCUKEN (loop-primary). All modes must yield
        // a finite, >= 3-point trace (FR-6 — no degenerate single-point trace).
        for (mode in GeoTraceSynthesizer.DoubleLetterMode.values()) {
            val trace = synth.synthesize("её", jcuken, widthPx, heightPx,
                GeoTraceSynthesizer.Tier.CLEAN, mode, seed = 5L)
            assertWithMessage("single-key trace must not be null (typeable)").that(trace).isNotNull()
            assertWithMessage("mode=$mode: single-key trace >= 3 points").that(trace!!.size).isAtLeast(3)
            // It must have a positive extent (loop / there-and-back), never a bare point.
            var maxD = 0f
            for (p in trace) {
                val d = sqrt((p.x - trace[0].x) * (p.x - trace[0].x) +
                    (p.y - trace[0].y) * (p.y - trace[0].y))
                if (d > maxD) maxD = d
            }
            assertWithMessage("mode=$mode: single-key trace must have positive extent")
                .that(maxD).isGreaterThan(0f)
        }
    }

    @Test
    fun synthesize_untypeableWord_returnsNull() {
        // A word with a codepoint absent from QWERTY (Cyrillic) is untypeable → null.
        val trace = synth.synthesize("привет", qwerty, widthPx, heightPx,
            GeoTraceSynthesizer.Tier.CLEAN, seed = 1L)
        assertWithMessage("untypeable word must synthesize null (FR-4)").that(trace).isNull()
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Mean deviation (kw) of every synthetic sample of every [words] entry from the
     * ideal anchor polyline of that word, averaged over [SEEDS] seeds. Uses the nearest
     * point on the ideal polyline as the reference (so a CLEAN trace that merely runs
     * FASTER than the ideal is not counted as deviated — only lateral drift counts).
     */
    private fun meanDeviationKw(words: List<String>, tier: GeoTraceSynthesizer.Tier): Float {
        var total = 0.0
        var count = 0L
        for (word in words) {
            val ideal = idealNormPolyline(word) ?: continue
            for (s in 0 until SEEDS) {
                val trace = synth.synthesize(word, qwerty, widthPx, heightPx, tier, seed = s.toLong())
                    ?: continue
                for (p in trace) {
                    val u = p.x / widthPx; val v = p.y / heightPx
                    total += nearestPolylineDistKw(u, v, ideal).toDouble()
                    count++
                }
            }
        }
        return if (count > 0) (total / count).toFloat() else 0f
    }

    /** The word's plain-variant template polyline in normalized [0,1]² (or null). */
    private fun idealNormPolyline(word: String): FloatArray? {
        val t = gen.generate(word, 0, qwerty) ?: return null
        val buf = FloatArray(t.pointCount * 2)
        t.copyVariantInto(0, buf)
        return buf
    }

    /** Min kw distance from normalized (u,v) to any point of the ideal polyline. */
    private fun nearestPolylineDistKw(u: Float, v: Float, ideal: FloatArray): Float {
        var best = Float.POSITIVE_INFINITY
        val n = ideal.size / 2
        for (k in 0 until n) {
            val d = qwerty.dKw(u, v, ideal[2 * k], ideal[2 * k + 1])
            if (d < best) best = d
        }
        return best
    }

    /** kw distance from a trace point to a key centroid (px → normalized first). */
    private fun kwDist(p: TracePoint, key: SwipeKey, layout: LayoutGeometry): Float =
        layout.dKw(p.x / widthPx, p.y / heightPx, key.cx, key.cy)

    private companion object {
        const val SEEDS = 4
    }
}
