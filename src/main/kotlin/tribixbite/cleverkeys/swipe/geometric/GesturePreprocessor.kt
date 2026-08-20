package tribixbite.cleverkeys.swipe.geometric

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Turns a raw touch trace into the normalized, resampled [ProcessedGesture] the
 * pruner and scorer consume (Algorithm Specification §1).
 *
 * Pipeline (per swipe, O(P + N)):
 *  1. **Normalize** each raw key-area-local pixel to `u = x / keyAreaWidthPx`,
 *     `v = y / keyAreaHeightPx` ∈ [0,1]² (the Coordinate contract).
 *  2. **Arc-length uniform resample** to N points (the algorithm family of the pure
 *     resampling is ARC-LENGTH based. The neural engine's index-based resampler truncated/merged by
 *     index, but the geometric decoder needs equidistant arc-length points so that
 *     the proportional shape/location matching compares like against like).
 *  3. **Corners** — interior resampled points whose turn angle ≥ `cornerAngleThresholdDeg`.
 *     Corners are a SOFT scoring feature only, NEVER a hard filter (smooth real turns
 *     and collinear words like "ash"/"ask" produce zero detectable corners).
 *  4. **Path length** (kw units via [LayoutGeometry.dKw]), **bbox**, and the
 *     **k-nearest start/end key ids** (extremity-bucket lookup keys).
 *
 * Gating (duration / minimum length) is the CALLER's job (Error Handling); the
 * preprocessor decodes what it is given. Degenerate input (< 2 raw points, or a
 * zero-length trace) still yields a structurally-valid [ProcessedGesture] whose N
 * points all coincide — the engine facade short-circuits before calling this on the
 * < 3-point / zero-length cases, but the resampler itself never produces NaN.
 *
 * Purity (NFR-3): `kotlin.*` only. `android.graphics.PointF` is deliberately absent
 * (it is a stubbed JVM landmine — every coord reads back (0,0)); the pipeline works
 * in plain interleaved floats.
 */
class GesturePreprocessor(private val config: GeometricEngineConfig) {

    /** Resample target N (points in the output polyline). */
    private val n: Int = config.resamplePoints

    /**
     * Extremity-neighbor count; widened on dense layouts so the pruner has data.
     * Shared with [CandidatePruner] via [GeometricEngineConfig.effectiveExtremityNeighbors]
     * (both sides MUST agree — see that KDoc).
     */
    private fun neighborCount(layout: LayoutGeometry): Int =
        config.effectiveExtremityNeighbors(layout)

    /**
     * Preprocess [rawPoints] (key-area-local pixels) against [layout].
     *
     * @param rawPoints the raw trace samples; length may be any P ≥ 1 (the facade
     *   guards the < 3-point empty-result case before calling this).
     * @param keyAreaWidthPx pixel width of the frame `rawPoints` were measured in.
     * @param keyAreaHeightPx pixel height of that frame.
     * @param layout the geometry (drives `d_kw` anisotropy + nearest-key extremities).
     */
    fun process(
        rawPoints: List<TracePoint>,
        keyAreaWidthPx: Float,
        keyAreaHeightPx: Float,
        layout: LayoutGeometry,
    ): ProcessedGesture {
        require(keyAreaWidthPx > 0f && keyAreaHeightPx > 0f) {
            "key-area extents must be positive: ${keyAreaWidthPx}x$keyAreaHeightPx"
        }
        val p = rawPoints.size
        require(p >= 1) { "cannot preprocess an empty trace" }

        // 1) Normalize into an interleaved [0,1]² polyline.
        val norm = FloatArray(p * 2)
        for (k in 0 until p) {
            norm[2 * k] = rawPoints[k].x / keyAreaWidthPx
            norm[2 * k + 1] = rawPoints[k].y / keyAreaHeightPx
        }

        // 2) Arc-length uniform resample to N.
        val resampled = resampleArcLength(norm, n)

        // 3) Corners (soft feature).
        val corners = detectCorners(resampled)

        // 4) Path length (kw), bbox.
        val pathLenKw = polylineLengthKw(resampled, layout)
        val bbox = boundingBox(resampled)

        // Extremity keys from the FIRST / LAST RAW points (the Coordinate contract:
        // nearest-key ranking is on normalized coords; the raw endpoints are the most
        // faithful start/end anchors — resampling can nudge the endpoints slightly, but
        // the arc-length resampler preserves the first/last input points exactly, so
        // using the resampled endpoints is equivalent and avoids re-normalizing).
        val k = neighborCount(layout)
        val startNearest = layout.nearestKeys(resampled[0], resampled[1], k)
        val endNearest = layout.nearestKeys(resampled[(n - 1) * 2], resampled[(n - 1) * 2 + 1], k)

        // Endpoint-inset dual anchors (research doc §2 Step 1a). Back off each endpoint
        // ALONG the path by `endpointInsetKw` to undo SLOPPY overshoot + endpoint-Gaussian
        // jitter before a SECOND nearest-key lookup; the pruner unions the raw AND inset
        // buckets so a noisy endpoint that lands on an adjacent key still enrolls the true
        // word's bucket. At `endpointInsetKw == 0` (default) [pointAlongPath] returns the
        // endpoint itself, so the inset arrays are bit-identical to the raw arrays and the
        // pruner union is a no-op — the change is fully opt-in.
        val insetKw = config.endpointInsetKw
        val startNearestInset: IntArray
        val endNearestInset: IntArray
        if (insetKw <= 0f) {
            startNearestInset = startNearest
            endNearestInset = endNearest
        } else {
            val (sx, sy) = pointAlongPath(resampled, fromStart = true, insetKw = insetKw, layout = layout)
            val (ex, ey) = pointAlongPath(resampled, fromStart = false, insetKw = insetKw, layout = layout)
            startNearestInset = layout.nearestKeys(sx, sy, k)
            endNearestInset = layout.nearestKeys(ex, ey, k)
        }

        return ProcessedGesture(
            points = resampled,
            pathLengthKw = pathLenKw,
            bbox = bbox,
            cornerIndices = corners,
            startNearest = startNearest,
            endNearest = endNearest,
            startNearestInset = startNearestInset,
            endNearestInset = endNearestInset,
        )
    }

    /**
     * Walk the resampled polyline inward from one endpoint, accumulating arc length in kw
     * units (via [LayoutGeometry.dKw]) until [insetKw] is reached, and return that
     * normalized (u, v) point (linear interpolation on the crossing segment).
     *
     * Used to derive the endpoint-inset dual anchors (Step 1a): the START inset walks
     * forward from index 0; the END inset walks backward from index N−1. If the whole path
     * is shorter than [insetKw] the walk clamps at the far endpoint (a degenerate short
     * gesture — the inset point coincides with the opposite endpoint, harmless: the pruner
     * merely probes one extra bucket).
     *
     * DESIGN NOTE (asymmetry): in the synthetic noise model the END anchor is the targeted
     * one — SLOPPY adds end overshoot (p=0.6) that the inset undoes. The START anchor
     * undoes nothing specific there (no start-overshoot term; the endpoint Gaussian is
     * isotropic), so the start-side inset is untargeted widening — it measured harmless
     * (CLEAN/TYPICAL floors unmoved) and is kept symmetric for simplicity and for real
     * traces where a start hook is possible; a future refinement could inset the end only.
     *
     * @param poly interleaved normalized (u,v) resampled polyline (length `2 * n`).
     * @param fromStart true → walk forward from point 0; false → walk backward from N−1.
     * @param insetKw distance to back off along the path, in kw units (> 0 by caller).
     * @param layout drives the kw-unit arc-length metric (anisotropy via `dKw`).
     * @return the inset point as `(u, v)`.
     */
    internal fun pointAlongPath(
        poly: FloatArray,
        fromStart: Boolean,
        insetKw: Float,
        layout: LayoutGeometry,
    ): Pair<Float, Float> {
        val count = poly.size / 2
        // Endpoint index and the inward step direction.
        val startIdx = if (fromStart) 0 else count - 1
        val step = if (fromStart) 1 else -1
        var ux = poly[2 * startIdx]
        var uy = poly[2 * startIdx + 1]
        if (insetKw <= 0f || count < 2) return ux to uy

        var remaining = insetKw
        var i = startIdx
        while (i + step in 0 until count) {
            val nx = poly[2 * (i + step)]
            val ny = poly[2 * (i + step) + 1]
            val segLen = layout.dKw(ux, uy, nx, ny)
            if (segLen <= 0f) {
                // Coincident neighbor — no progress; advance the anchor and continue.
                ux = nx; uy = ny; i += step; continue
            }
            if (segLen >= remaining) {
                // The inset point lands inside this segment; linear-interpolate.
                val f = (remaining / segLen).coerceIn(0f, 1f)
                return (ux + (nx - ux) * f) to (uy + (ny - uy) * f)
            }
            remaining -= segLen
            ux = nx; uy = ny // advance the running anchor to the next vertex
            i += step
        }
        // Path shorter than insetKw: clamp at the far endpoint.
        return ux to uy
    }

    // ── arc-length resampling (own implementation; see the class KDoc for why) ─────

    /**
     * Arc-length UNIFORM resample of an interleaved (u,v) polyline to exactly [target]
     * points: the first and last input points are preserved and interior points sit at
     * equal fractions of total path length. Identical algorithm to
     * [TemplateGenerator.resampleArcLength] so a gesture and a template resampled the
     * same way are index-comparable (proportional matching). Kept private here so the
     * preprocessor is self-contained; a degenerate zero-length input (all coincident)
     * collapses to [target] copies of the single point (finite, never NaN).
     */
    internal fun resampleArcLength(poly: FloatArray, target: Int): FloatArray {
        require(target >= 2) { "resample target must be >= 2, was $target" }
        val p = poly.size / 2
        val out = FloatArray(target * 2)
        if (p == 0) return out
        if (p == 1) {
            for (k in 0 until target) { out[2 * k] = poly[0]; out[2 * k + 1] = poly[1] }
            return out
        }

        // Cumulative arc length at each input vertex.
        val cum = FloatArray(p)
        var total = 0f
        for (k in 1 until p) {
            val dx = poly[2 * k] - poly[2 * (k - 1)]
            val dy = poly[2 * k + 1] - poly[2 * (k - 1) + 1]
            total += sqrt(dx * dx + dy * dy)
            cum[k] = total
        }
        if (total <= 0f) {
            for (k in 0 until target) { out[2 * k] = poly[0]; out[2 * k + 1] = poly[1] }
            return out
        }

        var seg = 1
        for (k in 0 until target) {
            val d = total * (k.toFloat() / (target - 1))
            while (seg < p - 1 && cum[seg] < d) seg++
            val d0 = cum[seg - 1]
            val d1 = cum[seg]
            val segLen = d1 - d0
            val t = if (segLen > 0f) ((d - d0) / segLen).coerceIn(0f, 1f) else 0f
            val ax = poly[2 * (seg - 1)]
            val ay = poly[2 * (seg - 1) + 1]
            val bx = poly[2 * seg]
            val by = poly[2 * seg + 1]
            out[2 * k] = ax + (bx - ax) * t
            out[2 * k + 1] = ay + (by - ay) * t
        }
        return out
    }

    // ── corner detection ───────────────────────────────────────────────────────

    /**
     * Interior resampled-point indices where the incoming/outgoing segment turn angle
     * is at least `cornerAngleThresholdDeg`. Turn angle is measured between the vectors
     * `(pᵢ − pᵢ₋₁)` and `(pᵢ₊₁ − pᵢ)`; a straight line has 0°, a sharp reversal ~180°.
     * Zero-length segments (coincident points) contribute no corner. This is a SOFT
     * feature (bounded corner-anchor bonus in [PathScorer]) — never a filter.
     */
    internal fun detectCorners(poly: FloatArray): IntArray {
        val count = poly.size / 2
        if (count < 3) return IntArray(0)
        val thresholdRad = Math.toRadians(config.cornerAngleThresholdDeg.toDouble())
        val out = ArrayList<Int>()
        for (i in 1 until count - 1) {
            val ax = poly[2 * i] - poly[2 * (i - 1)]
            val ay = poly[2 * i + 1] - poly[2 * (i - 1) + 1]
            val bx = poly[2 * (i + 1)] - poly[2 * i]
            val by = poly[2 * (i + 1) + 1] - poly[2 * i + 1]
            val na = sqrt(ax * ax + ay * ay)
            val nb = sqrt(bx * bx + by * by)
            if (na <= 0f || nb <= 0f) continue // coincident neighbor → no turn
            // cos θ of the turn; clamp for float slop before acos.
            var cos = (ax * bx + ay * by) / (na * nb)
            if (cos > 1f) cos = 1f
            if (cos < -1f) cos = -1f
            val turn = acos(cos.toDouble())
            if (turn >= thresholdRad) out.add(i)
        }
        return out.toIntArray()
    }

    // ── geometry helpers ───────────────────────────────────────────────────────

    /** Total polyline arc length in kw units (anisotropy via [LayoutGeometry.dKw]). */
    internal fun polylineLengthKw(poly: FloatArray, layout: LayoutGeometry): Float {
        val count = poly.size / 2
        var total = 0f
        for (k in 1 until count) {
            total += layout.dKw(poly[2 * (k - 1)], poly[2 * (k - 1) + 1], poly[2 * k], poly[2 * k + 1])
        }
        return total
    }

    /** Axis-aligned bbox `[minU, minV, maxU, maxV]` of an interleaved polyline. */
    internal fun boundingBox(poly: FloatArray): FloatArray {
        var minU = Float.POSITIVE_INFINITY; var minV = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
        val count = poly.size / 2
        for (k in 0 until count) {
            val u = poly[2 * k]; val v = poly[2 * k + 1]
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        return floatArrayOf(minU, minV, maxU, maxV)
    }
}
