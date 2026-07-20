package tribixbite.cleverkeys.swipe.geometric

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * TEST-ONLY synthetic swipe-trace generator (Phase 5 accuracy harness).
 *
 * Turns a dictionary word into a *plausible human* raw [TracePoint] trace on a
 * [LayoutGeometry] — a controllable stand-in for the real FUTO/Yandex corpora we may
 * never wire in — so the accuracy classes can measure top-K over the FULL dictionary
 * without any recorded data (NON-Goal 4: corpora are evaluation-only, never training).
 *
 * Carries NO `Test` suffix so `TestRunnerListDriftTest` skips it; purity (NFR-3):
 * `kotlin.*` + `java.util.Random` only. `android.graphics.PointF` is deliberately
 * absent (stubbed JVM landmine).
 *
 * ## Pipeline (spec Phase 5)
 * A trace is built from the word's own template anchors (the collapsed key centroids,
 * with a loop/dwell at doubled letters) and progressively degraded:
 *  1. **Anchor polyline** — the plain-variant centroid path (or the loop variant, per
 *     [DoubleLetterMode]) in normalized [0,1]² space; the strongest, noise-free signal.
 *  2. **Bézier corner cutting** (κ ∈ [0, 0.45]) — replace each interior anchor corner
 *     with a quadratic-Bézier fillet so the path rounds through the turn the way a
 *     finger arcs, rather than hitting a hard vertex. κ is the fraction of each
 *     adjacent segment consumed by the fillet.
 *  3. **Overshoot** (o ∈ [0, 0.4]·kw, probability p_over) — with probability p_over,
 *     extend past the final anchor along the last segment's direction (users overshoot
 *     the end; ASK precedent — the endpoint anchor slack in [PathScorer] is looser at
 *     the end for exactly this reason).
 *  4. **Endpoint Gaussian offset** (σ_end·kw) — perturb the start and end points by an
 *     isotropic Gaussian; users start/end near-but-not-on the key.
 *  5. **Per-point jitter** N(0, σ_noise·kw) — independent isotropic jitter on every
 *     densified point (hand tremor / capacitive noise).
 *  6. **Curvature-slowed velocity + sampling** — walk the degraded polyline emitting
 *     samples at a nominal 60–120 Hz with ±20% inter-sample interval jitter and random
 *     drops, slowing the finger through high-curvature regions (velocity ∝ 1/(1+curv))
 *     so corners get MORE samples than straightaways (matching real capture density).
 *
 * All offsets/overshoot are expressed in **kw units** (converted to normalized-u via
 * [LayoutGeometry.meanKeyWidth], with the vertical component divided by aspect so a
 * kw offset is physically isotropic, consistent with [LayoutGeometry.dKw]).
 *
 * ## Determinism (NFR-4)
 * Every source of randomness is a single seeded [Random]. Same `(word, seed, tier,
 * mode)` → byte-identical trace. The accuracy harness seeds per `(word, seedIndex)` so
 * a failing decode is exactly reproducible.
 */
class GeoTraceSynthesizer(
    private val config: GeometricEngineConfig = GeometricEngineConfig(),
) {

    /** Reuses the production template math so the anchor polyline matches the decoder. */
    private val generator = TemplateGenerator(config)

    /**
     * A degradation tier: the four knobs `(σ_noise, σ_end, κ_max, p_over)` in kw units
     * (except p_over ∈ [0,1] and κ_max ∈ [0,0.45] which are dimensionless fractions).
     * Monotone in difficulty CLEAN < TYPICAL < SLOPPY on every knob (asserted in tests).
     */
    enum class Tier(
        /** σ of the per-point isotropic jitter (kw). */
        val noiseSigmaKw: Float,
        /** σ of the start/end Gaussian offset (kw). */
        val endSigmaKw: Float,
        /** Maximum Bézier corner-cut fraction κ ∈ [0, 0.45]. */
        val cornerCutMax: Float,
        /** Probability the trace overshoots the final anchor. */
        val overshootProb: Float,
    ) {
        CLEAN(0.02f, 0.05f, 0.10f, 0.0f),
        TYPICAL(0.08f, 0.15f, 0.30f, 0.3f),
        SLOPPY(0.15f, 0.30f, 0.45f, 0.6f),
    }

    /**
     * How a doubled letter (or a single-key word) is realized in the synthetic trace:
     *  - [LOOP]: a small circular loop at the key (the [WordTemplate] loop variant).
     *  - [DWELL]: linger on the key (extra collinear samples, no loop) — the finger
     *    pauses instead of circling.
     *  - [NONE]: no special handling — the doubled letter collapses to a single pass
     *    (a user who swiped straight through "fell" without marking the double-l).
     *
     * For single-key words ("её"/"ее") the loop-primary template has no plain variant,
     * so [LOOP] uses the loop variant while [DWELL]/[NONE] dwell on / pass through the
     * single centroid — all three yield a finite, decodable trace (FR-6).
     */
    enum class DoubleLetterMode { LOOP, DWELL, NONE }

    /**
     * Synthesize a raw [TracePoint] trace for [word] on [layout], or `null` if the word
     * is untypeable (no template — FR-4), so the harness can skip it cleanly.
     *
     * @param keyAreaWidthPx pixel width of the frame the returned points are in.
     * @param keyAreaHeightPx pixel height of that frame (aspect = w/h must match the
     *   layout's build-time aspect for a faithful px→norm round-trip).
     * @param seed the RNG seed — same seed ⇒ identical trace (NFR-4).
     */
    fun synthesize(
        word: String,
        layout: LayoutGeometry,
        keyAreaWidthPx: Float,
        keyAreaHeightPx: Float,
        tier: Tier,
        mode: DoubleLetterMode = DoubleLetterMode.NONE,
        seed: Long,
    ): List<TracePoint>? {
        require(keyAreaWidthPx > 0f && keyAreaHeightPx > 0f) {
            "key-area extents must be positive: ${keyAreaWidthPx}x$keyAreaHeightPx"
        }
        val anchors = anchorPolyline(word, layout, mode) ?: return null
        val rng = Random(seed)
        val kw = layout.meanKeyWidth
        val aspect = layout.aspect

        // 2) Bézier corner cutting on the anchor vertices → a densified rounded polyline.
        val kappa = tier.cornerCutMax * rng.nextFloat() // κ ∈ [0, κ_max]
        var poly = bezierCornerCut(anchors, kappa)

        // 3) Overshoot the final anchor with probability p_over.
        if (anchors.size >= 4 && rng.nextFloat() < tier.overshootProb) {
            poly = appendOvershoot(poly, kw, aspect, rng)
        }

        // 4) Endpoint Gaussian offset (start + end) in kw units.
        offsetEndpoint(poly, 0, tier.endSigmaKw, kw, aspect, rng)
        offsetEndpoint(poly, poly.size / 2 - 1, tier.endSigmaKw, kw, aspect, rng)

        // 5) Per-point isotropic jitter N(0, σ_noise·kw).
        addJitter(poly, tier.noiseSigmaKw, kw, aspect, rng)

        // 6) Curvature-slowed velocity + Hz-jittered sampling with drops → raw points.
        return sampleWithVelocity(poly, keyAreaWidthPx, keyAreaHeightPx, rng)
    }

    // ── 1) anchor polyline ───────────────────────────────────────────────────────

    /**
     * The word's noise-free anchor polyline in normalized space, chosen by [mode]:
     *  - the plain-variant centroid path for a multi-key word,
     *  - the LOOP variant when [mode] == LOOP (or the single-key loop-primary variant),
     *  - a DWELL (duplicated point at each doubled letter) for DWELL,
     *  - the plain collapsed path for NONE.
     *
     * Returns a raw (un-resampled) interleaved (u,v) vertex list — corner cutting and
     * the velocity model operate on these vertices, not on a fixed-N resample. `null`
     * when the word is untypeable.
     */
    internal fun anchorPolyline(
        word: String, layout: LayoutGeometry, mode: DoubleLetterMode,
    ): FloatArray? {
        val pre = LayoutProjection.project(word, layout) ?: return null
        val keys = layout.keys

        // Collapsed distinct-key sequence + doubled-letter positions (pre-collapse runs
        // of length >= 2). We build vertices directly from the pre-collapse sequence so
        // DWELL/LOOP can act at the exact doubled key.
        val loopSide = config.doubleLetterLoopRadius * layout.meanKeyWidth
        val r = loopSide * 0.5f
        val pts = ArrayList<Float>(pre.size * 2 + 8)
        var i = 0
        while (i < pre.size) {
            val id = pre[i]
            val cx = keys[id].cx
            val cy = keys[id].cy
            var run = 1
            while (i + run < pre.size && pre[i + run] == id) run++
            val isDouble = run >= 2
            when {
                isDouble && mode == DoubleLetterMode.LOOP -> {
                    // Enter, trace a small square loop, leave (matches the loop variant).
                    pts.add(cx); pts.add(cy)
                    pts.add(cx - r); pts.add(cy - r)
                    pts.add(cx + r); pts.add(cy - r)
                    pts.add(cx + r); pts.add(cy + r)
                    pts.add(cx - r); pts.add(cy + r)
                    pts.add(cx); pts.add(cy)
                }
                isDouble && mode == DoubleLetterMode.DWELL -> {
                    // Dwell: emit the point twice so the velocity model lingers here.
                    pts.add(cx); pts.add(cy)
                    pts.add(cx); pts.add(cy)
                }
                else -> {
                    pts.add(cx); pts.add(cy)
                }
            }
            i += run
        }

        // Single-key word (whole word collapses to one key). The plain path would be a
        // bare point → degenerate. A single-key word's only template is the loop-primary
        // one (a square loop, FR-6). Physically, ANY realization — LOOP (a clean circle),
        // DWELL (linger + wobble), or NONE (a quick touch) — is a small closed motion over
        // that one key, so all three trace the SAME loop-primary geometry: a there-and-back
        // of half a loop side would be ~4× shorter than the template loop and fall below
        // the length-ratio prune (making the word UNdecodable), which contradicts FR-6
        // ("её decodable in all modes"). Reusing the loop-primary template for every mode
        // keeps the synthetic trace length-matched to the only template that exists.
        if (distinctKeyCount(pre) == 1) {
            val t = generator.fromKeyIds(pre, 0, layout)
            val buf = FloatArray(t.pointCount * 2)
            t.copyVariantInto(0, buf)
            return buf
        }
        return pts.toFloatArray()
    }

    /** Number of distinct consecutive keys after collapse in [pre]. */
    private fun distinctKeyCount(pre: IntArray): Int {
        var c = 0
        var prev = -1
        for (id in pre) { if (id != prev) c++; prev = id }
        return c
    }

    // ── 2) Bézier corner cutting ─────────────────────────────────────────────────

    /**
     * Round every interior corner of [poly] with a quadratic-Bézier fillet consuming
     * fraction [kappa] of each adjacent segment: for interior vertex `B` between `A` and
     * `C`, the fillet runs `A + κ(B−A) → B → C + κ(B−C)` sampled by a quadratic Bézier
     * with control point `B`. κ=0 leaves the hard corner; κ→0.45 rounds it heavily.
     * The endpoints are preserved exactly (before the later endpoint offset). Straight
     * runs stay straight (a Bézier with collinear control points is a line).
     */
    internal fun bezierCornerCut(poly: FloatArray, kappa: Float): FloatArray {
        val n = poly.size / 2
        if (n < 3 || kappa <= 0f) return poly.copyOf()
        val out = ArrayList<Float>(poly.size * 3)
        // Start vertex verbatim.
        out.add(poly[0]); out.add(poly[1])
        for (i in 1 until n - 1) {
            val ax = poly[2 * (i - 1)]; val ay = poly[2 * (i - 1) + 1]
            val bx = poly[2 * i]; val by = poly[2 * i + 1]
            val cx = poly[2 * (i + 1)]; val cy = poly[2 * (i + 1) + 1]
            // Fillet entry/exit points along the two adjacent segments.
            val p0x = bx + (ax - bx) * kappa; val p0y = by + (ay - by) * kappa
            val p2x = bx + (cx - bx) * kappa; val p2y = by + (cy - by) * kappa
            // Sample the quadratic Bézier p0→(control B)→p2. STEPS points along the arc.
            val steps = BEZIER_STEPS
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val mt = 1f - t
                val x = mt * mt * p0x + 2f * mt * t * bx + t * t * p2x
                val y = mt * mt * p0y + 2f * mt * t * by + t * t * p2y
                out.add(x); out.add(y)
            }
        }
        // End vertex verbatim.
        out.add(poly[2 * (n - 1)]); out.add(poly[2 * (n - 1) + 1])
        return out.toFloatArray()
    }

    // ── 3) overshoot ─────────────────────────────────────────────────────────────

    /**
     * Extend [poly] past its last point along the final segment's direction by a random
     * `o ∈ [0, OVERSHOOT_MAX_KW]·kw` (kw units, aspect-corrected). Models the finger
     * flying past the last key before lifting.
     */
    private fun appendOvershoot(
        poly: FloatArray, kw: Float, aspect: Float, rng: Random,
    ): FloatArray {
        val n = poly.size / 2
        if (n < 2) return poly
        val lx = poly[2 * (n - 1)]; val ly = poly[2 * (n - 1) + 1]
        val px = poly[2 * (n - 2)]; val py = poly[2 * (n - 2) + 1]
        var dx = lx - px; var dy = ly - py
        val d = sqrt(dx * dx + dy * dy)
        if (d <= 0f) return poly
        dx /= d; dy /= d
        val oKw = OVERSHOOT_MAX_KW * rng.nextFloat()
        // kw → normalized-u (horizontal), normalized-v uses aspect so the offset is
        // physically isotropic (a kw is a fraction of key WIDTH in both channels).
        val ex = lx + dx * oKw * kw
        val ey = ly + dy * oKw * kw * aspect
        val out = poly.copyOf(poly.size + 2)
        out[poly.size] = ex; out[poly.size + 1] = ey
        return out
    }

    // ── 4/5) Gaussian offsets ────────────────────────────────────────────────────

    /** Offset the point at [idx] by an isotropic Gaussian of σ [sigmaKw] (kw units). */
    private fun offsetEndpoint(
        poly: FloatArray, idx: Int, sigmaKw: Float, kw: Float, aspect: Float, rng: Random,
    ) {
        if (sigmaKw <= 0f) return
        val (du, dv) = gaussianOffset(sigmaKw, kw, aspect, rng)
        poly[2 * idx] += du
        poly[2 * idx + 1] += dv
    }

    /** Add independent isotropic jitter N(0, σ·kw) to every point of [poly]. */
    private fun addJitter(poly: FloatArray, sigmaKw: Float, kw: Float, aspect: Float, rng: Random) {
        if (sigmaKw <= 0f) return
        val n = poly.size / 2
        for (i in 0 until n) {
            val (du, dv) = gaussianOffset(sigmaKw, kw, aspect, rng)
            poly[2 * i] += du
            poly[2 * i + 1] += dv
        }
    }

    /**
     * A single isotropic Gaussian offset of σ [sigmaKw] kw, returned as a
     * `(Δu, Δv)` normalized pair. The horizontal Δu is `σ·kw`; the vertical Δv is
     * `σ·kw·aspect` so the *physical* offset is isotropic in key-width units (a kw is a
     * fraction of key WIDTH, and normalized-v is compressed by aspect — matching
     * [LayoutGeometry.dKw]).
     */
    private fun gaussianOffset(sigmaKw: Float, kw: Float, aspect: Float, rng: Random): Pair<Float, Float> {
        val gu = rng.nextGaussian().toFloat() * sigmaKw * kw
        val gv = rng.nextGaussian().toFloat() * sigmaKw * kw * aspect
        return gu to gv
    }

    // ── 6) velocity model + sampling ─────────────────────────────────────────────

    /**
     * Walk [poly] (dense normalized polyline) emitting raw [TracePoint]s in the pixel
     * frame `[keyAreaWidthPx] × [keyAreaHeightPx]`, at a nominal 60–120 Hz with ±20%
     * inter-sample interval jitter and random drops, slowing the finger through
     * high-curvature regions.
     *
     * The finger's arc-length position advances by `v · dt` per emitted sample, where
     * the nominal speed is constant but the effective advance is scaled DOWN near
     * corners (curvature high) so those regions accumulate more samples — the density
     * pattern of a real capture. Timestamps are strictly monotone (asserted in tests).
     */
    internal fun sampleWithVelocity(
        poly: FloatArray, keyAreaWidthPx: Float, keyAreaHeightPx: Float, rng: Random,
    ): List<TracePoint> {
        val n = poly.size / 2
        if (n == 0) return emptyList()
        if (n == 1) {
            // Degenerate: a single point → three coincident samples so the caller's
            // >= 3-point guard is satisfied and timestamps are monotone.
            return listOf(
                TracePoint(poly[0] * keyAreaWidthPx, poly[1] * keyAreaHeightPx, 0L),
                TracePoint(poly[0] * keyAreaWidthPx, poly[1] * keyAreaHeightPx, 8L),
                TracePoint(poly[0] * keyAreaWidthPx, poly[1] * keyAreaHeightPx, 16L),
            )
        }

        // Cumulative arc length per vertex + per-vertex curvature weight (slowdown).
        val cum = FloatArray(n)
        var total = 0f
        for (k in 1 until n) {
            val dx = poly[2 * k] - poly[2 * (k - 1)]
            val dy = poly[2 * k + 1] - poly[2 * (k - 1) + 1]
            total += sqrt(dx * dx + dy * dy)
            cum[k] = total
        }
        if (total <= 0f) {
            // All coincident (should not happen post-jitter) → emit three copies.
            val x = poly[0] * keyAreaWidthPx; val y = poly[1] * keyAreaHeightPx
            return listOf(TracePoint(x, y, 0L), TracePoint(x, y, 8L), TracePoint(x, y, 16L))
        }

        // Nominal sampling: pick a base rate in [60, 120] Hz, constant nominal finger
        // speed such that the whole path takes a natural ~total/speed seconds. We use a
        // normalized-per-second speed so denser paths (long words) take longer.
        val baseHz = 60f + 60f * rng.nextFloat()          // 60..120 Hz
        val baseDtMs = 1000f / baseHz                      // nominal inter-sample ms
        val nominalSpeed = SPEED_NORM_PER_SEC              // normalized units / s

        val out = ArrayList<TracePoint>(64)
        var arc = 0f
        var tMs = 0.0
        var seg = 1
        var guard = 0
        val maxSamples = 4096 // hard bound so a pathological path can't loop forever
        while (arc <= total && guard < maxSamples) {
            guard++
            // Interpolate the current arc position within its segment.
            while (seg < n - 1 && cum[seg] < arc) seg++
            val d0 = cum[seg - 1]; val d1 = cum[seg]
            val segLen = d1 - d0
            val f = if (segLen > 0f) ((arc - d0) / segLen).coerceIn(0f, 1f) else 0f
            val ax = poly[2 * (seg - 1)]; val ay = poly[2 * (seg - 1) + 1]
            val bx = poly[2 * seg]; val by = poly[2 * seg + 1]
            val u = ax + (bx - ax) * f
            val v = ay + (by - ay) * f

            // Random drop (skip emitting this sample) — a real capture drops points.
            if (rng.nextFloat() >= DROP_PROB) {
                out.add(TracePoint(u * keyAreaWidthPx, v * keyAreaHeightPx, tMs.toLong()))
            }

            // Advance time with ±20% jitter.
            val dt = baseDtMs * (1f + INTERVAL_JITTER * (2f * rng.nextFloat() - 1f))
            tMs += max(1.0, dt.toDouble()) // strictly monotone (>= 1 ms per step)

            // Advance arc length by v·dt, slowed near corners (curvature high ⇒ slower).
            val curv = curvatureAt(poly, n, seg, f)
            val speed = nominalSpeed / (1f + CURV_SLOWDOWN * curv)
            arc += speed * (dt / 1000f)
        }
        // Guarantee the true endpoint is present (the loop may stop a hair short).
        val lastU = poly[2 * (n - 1)]; val lastV = poly[2 * (n - 1) + 1]
        val ex = lastU * keyAreaWidthPx; val ey = lastV * keyAreaHeightPx
        val lastPt = out.lastOrNull()
        if (lastPt == null || abs(lastPt.x - ex) > 0.5f || abs(lastPt.y - ey) > 0.5f) {
            val t = ((out.lastOrNull()?.tMillis ?: 0L) + 8L)
            out.add(TracePoint(ex, ey, t))
        }
        // Guarantee the true start is present at t=0 (the loop starts at arc=0 already,
        // but a drop could remove it — ensure a start anchor for the endpoint test).
        val firstPt = out.firstOrNull()
        val sx = poly[0] * keyAreaWidthPx; val sy = poly[1] * keyAreaHeightPx
        if (firstPt == null || abs(firstPt.x - sx) > 0.5f || abs(firstPt.y - sy) > 0.5f) {
            out.add(0, TracePoint(sx, sy, 0L))
            // Nudge the second point's timestamp if it collided at 0.
            if (out.size > 1 && out[1].tMillis <= 0L) {
                out[1] = TracePoint(out[1].x, out[1].y, 1L)
            }
        }
        return out
    }

    /**
     * A cheap local curvature proxy at the interpolated position (segment [seg],
     * fraction [f]): the turn angle magnitude between the incoming and outgoing segment
     * directions at the nearer vertex, in [0,1] (0 = straight, ~1 = sharp reversal).
     */
    private fun curvatureAt(poly: FloatArray, n: Int, seg: Int, f: Float): Float {
        // Use the vertex the position is closest to.
        val vtx = if (f < 0.5f) seg - 1 else seg
        if (vtx <= 0 || vtx >= n - 1) return 0f
        val ax = poly[2 * vtx] - poly[2 * (vtx - 1)]
        val ay = poly[2 * vtx + 1] - poly[2 * (vtx - 1) + 1]
        val bx = poly[2 * (vtx + 1)] - poly[2 * vtx]
        val by = poly[2 * (vtx + 1) + 1] - poly[2 * vtx + 1]
        val na = sqrt(ax * ax + ay * ay); val nb = sqrt(bx * bx + by * by)
        if (na <= 0f || nb <= 0f) return 0f
        var cos = (ax * bx + ay * by) / (na * nb)
        if (cos > 1f) cos = 1f
        if (cos < -1f) cos = -1f
        // Map cos∈[-1,1] (straight→reversed) to a slowdown weight [0,1]: (1−cos)/2.
        return (1f - cos) * 0.5f
    }

    companion object {
        /** Points sampled per Bézier fillet (denser = smoother rounded corner). */
        const val BEZIER_STEPS = 6

        /** Max overshoot past the final anchor, kw units (spec: o ∈ [0, 0.4]·kw). */
        const val OVERSHOOT_MAX_KW = 0.4f

        /** ±fraction jitter on the inter-sample interval (spec: 60–120 Hz ±20%). */
        const val INTERVAL_JITTER = 0.2f

        /** Probability a given sample is dropped (real captures drop points). */
        const val DROP_PROB = 0.05f

        /** Nominal finger speed, normalized units / second (a full-width swipe ~0.4 s). */
        const val SPEED_NORM_PER_SEC = 2.5f

        /** Curvature slowdown strength: effective speed = nominal / (1 + k·curv). */
        const val CURV_SLOWDOWN = 3.0f
    }
}
