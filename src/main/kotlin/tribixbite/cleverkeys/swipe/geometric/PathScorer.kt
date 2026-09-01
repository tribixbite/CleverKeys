package tribixbite.cleverkeys.swipe.geometric

import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The per-candidate scoring core (Algorithm Specification §3–§6): a log-domain SHARK2
 * Bayes blend of a scale/translation-invariant **shape channel** and an absolute,
 * α-end-weighted **location channel**, minus an ordinal-rank frequency prior and two
 * bounded sanity penalties, plus a bounded corner-anchor bonus.
 *
 * ```
 * S(w) = − w_shape · d_shape² / (2σ_s²)   (shape channel, short-word-faded)
 *        − d_loc²   / (2σ_l²)             (location channel)
 *        − λ_f · ln(1 + r(w))             (ordinal-rank Zipf prior)
 *        − lengthRatioPenalty(w)          (bounded)
 *        − endpointPenalty(w)             (bounded)
 *        + cornerBonus(w)                 (bounded, capped at cornerAnchorBonus)
 * ```
 *
 * Higher S is better (all terms are ≤ 0 except the corner bonus). A word with two
 * template variants (plain + loop) is scored per variant and the BETTER variant's
 * score is returned. Every path is fail-safe: clamped shape-normalization scale
 * (never div-by-0), the loop-primary rule (never a 1-point template), and finite
 * checks mean `S(w).isFinite()` always holds (asserted in tests).
 *
 * The α end-weighting satisfies `Σα = 1`, is minimal at the midpoint `i = N/2`, and
 * increases linearly toward both endpoints — so start/end fidelity dominates the
 * location cost (users hit endpoints precisely, drift mid-gesture).
 *
 * Purity (NFR-3): `kotlin.*` only.
 */
class PathScorer(private val config: GeometricEngineConfig) {

    private val n: Int = config.resamplePoints

    /** 2σ_s² — shape channel denominator (normalized-shape units). */
    private val twoSigmaShapeSq: Float = 2f * config.shapeSigma * config.shapeSigma

    /** 2σ_l² — location channel denominator (kw units). */
    private val twoSigmaLocSq: Float = 2f * config.locationSigma * config.locationSigma

    /**
     * α end-weights: `Σα = 1`, minimum at the midpoint, linearly increasing toward
     * both endpoints. Built once (depends only on N). `w(i) = 1 + |i − mid| / mid`
     * (a symmetric tent, min 1 at the center, ~2 at the ends), then normalized to sum 1.
     */
    private val alpha: FloatArray = buildAlpha(n)

    /**
     * Scratch buffers reused across every scored candidate on one decode's thread.
     * A [PathScorer] instance is single-decode-scoped (the facade allocates one per
     * `decode`), so these are not shared across threads — keeping the hot path
     * allocation-light without synchronization.
     */
    private val templateBuf = FloatArray(n * 2)

    /**
     * Score [template] against the preprocessed [gesture] on [layout]. Returns the
     * best variant's combined score `S(w)` (higher is better); always finite.
     *
     * @param gesture the resampled swipe (its `points` are the N-point gesture polyline).
     * @param template the candidate word's variant-encoded template.
     * @param layout drives `d_kw` (location channel) and the kw shape-scale clamp.
     * @param rank the word's ordinal frequency rank r(w) (0 = most frequent).
     */
    fun score(
        gesture: ProcessedGesture,
        template: WordTemplate,
        layout: LayoutGeometry,
        rank: Int,
    ): Float {
        var best = Float.NEGATIVE_INFINITY
        for (v in 0 until template.variantCount) {
            template.copyVariantInto(v, templateBuf)
            val s = scoreVariant(gesture, template, v, layout, rank)
            if (s > best) best = s
        }
        return best
    }

    /**
     * Score one materialized variant (its coords already in [templateBuf]).
     * Split out so the multi-variant loop reuses one buffer.
     */
    private fun scoreVariant(
        gesture: ProcessedGesture,
        template: WordTemplate,
        variant: Int,
        layout: LayoutGeometry,
        rank: Int,
    ): Float {
        val g = gesture.points // 2N interleaved
        val t = templateBuf    // 2N interleaved

        // ── Shape channel (§3): centroid-translate + independent bbox-scale, then
        //    proportional (index-aligned) matching. Scale-clamped in kw units.
        val dShape = shapeDistance(g, t, layout)

        // Short-word shape fade (§3): the shape channel's weight fades toward 0 for
        // short templates so the location channel + prior dominate 2–3 letter words.
        val templateLenKw = template.pathLengthsKw[variant]
        val wShape = (templateLenKw / config.shortWordShapeFloorKw).coerceIn(0f, 1f)

        // ── Location channel (§4): α-end-weighted absolute kw distance, unnormalized.
        val dLoc = locationDistance(g, t, layout)

        // ── Combined score (§5) — log-domain Bayes; all channel terms ≤ 0.
        var s = -wShape * dShape * dShape / twoSigmaShapeSq
        s -= dLoc * dLoc / twoSigmaLocSq
        s -= config.frequencyWeight * ln(1.0 + rank).toFloat()

        // ── Bounded sanity penalties (§6).
        s -= lengthRatioPenalty(gesture.pathLengthKw, templateLenKw)
        s -= endpointPenalty(gesture, t, layout)

        // ── Bounded corner-anchor bonus (§6).
        s += cornerBonus(gesture, t, layout)

        // ── Direction/tangent channel (OQ-8, default-off) — bounded, noise-averaging.
        if (config.directionPenaltyWeight > 0f) s -= directionPenalty(g, t)

        return s
    }

    // ── Shape channel (§3, SHARK2 Eq. 1) ─────────────────────────────────────────

    /**
     * `d_shape = (1/N) Σ ‖ûᵢ − t̂ᵢ‖` in normalized-shape units, where each path is
     * centroid-translated to the origin and scaled by
     * `s = max(bboxLongestSide, minShapeScaleKw · kw)` (the clamp kills jitter
     * amplification and div-by-0 for degenerate short spans). Points are compared
     * index-aligned (proportional matching — the only implemented mode; `dtwBand`
     * is a reserved knob enforced to 0 by the config init).
     */
    internal fun shapeDistance(g: FloatArray, t: FloatArray, layout: LayoutGeometry): Float {
        // Centroids.
        var gcx = 0f; var gcy = 0f; var tcx = 0f; var tcy = 0f
        for (i in 0 until n) {
            gcx += g[2 * i]; gcy += g[2 * i + 1]
            tcx += t[2 * i]; tcy += t[2 * i + 1]
        }
        gcx /= n; gcy /= n; tcx /= n; tcy /= n

        // Longest-bbox-side scale for each path, clamped to ≥ minShapeScaleKw·kw.
        val clamp = config.minShapeScaleKw * layout.meanKeyWidth
        val gScale = max(longestSide(g, gcx, gcy), clamp)
        val tScale = max(longestSide(t, tcx, tcy), clamp)

        var acc = 0f
        for (i in 0 until n) {
            val gu = (g[2 * i] - gcx) / gScale
            val gv = (g[2 * i + 1] - gcy) / gScale
            val tu = (t[2 * i] - tcx) / tScale
            val tv = (t[2 * i + 1] - tcy) / tScale
            val du = gu - tu; val dv = gv - tv
            acc += sqrt(du * du + dv * dv)
        }
        return acc / n
    }

    /** Longest bbox side of a centroid-translated interleaved polyline (normalized units). */
    private fun longestSide(poly: FloatArray, cx: Float, cy: Float): Float {
        var minU = Float.POSITIVE_INFINITY; var minV = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val u = poly[2 * i] - cx; val v = poly[2 * i + 1] - cy
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        val w = maxU - minU; val h = maxV - minV
        return if (w > h) w else h
    }

    // ── Location channel (§4) ────────────────────────────────────────────────────

    /**
     * `d_loc = Σ α(i) · d_kw(uᵢ, tᵢ)` (kw units, unnormalized points). The α weights
     * emphasize the endpoints (Σα = 1, min at the midpoint).
     *
     * With the SHARK2 location tunnel enabled (`locationTunnelHalfWidth = W > 0`), each
     * gesture point `uᵢ` matches the CLOSEST template point within a ±W index window
     * instead of the strictly index-aligned `tᵢ`:
     * `d_loc(i) = α(i) · min over j∈[i−W, i+W] d_kw(uᵢ, t_j)` (research doc §2 Step 1b).
     * This relaxes the mid-gesture penalty that SLOPPY per-point jitter inflates on tight
     * same-row arcs. `W = 0` is the strict path (a single j = i term) — bit-identical to
     * the pre-tunnel behavior, so the default cannot move any floor.
     *
     * DESIGN NOTE: at the endpoints (i = 0, i = N−1) the ±W window is clamped one-sided, so
     * the min still relaxes location exactly where the α end-weights say fidelity matters
     * MOST — one reason the tunnel regressed CLEAN. This is partially compensated by
     * [endpointPenalty], which anchors the RAW (untunneled) endpoints; but it is a core
     * reason the tunnel is OFF by default (see GeometricEngineConfig.locationTunnelHalfWidth).
     */
    internal fun locationDistance(g: FloatArray, t: FloatArray, layout: LayoutGeometry): Float {
        val w = config.locationTunnelHalfWidth
        var acc = 0f
        if (w <= 0) {
            // Strict index-aligned location term (default) — single j = i comparison.
            for (i in 0 until n) {
                acc += alpha[i] * layout.dKw(g[2 * i], g[2 * i + 1], t[2 * i], t[2 * i + 1])
            }
            return acc
        }
        // Tunnel: minimum over the ±W template-index window around i (clamped to [0, N)).
        for (i in 0 until n) {
            val gu = g[2 * i]; val gv = g[2 * i + 1]
            val lo = if (i - w > 0) i - w else 0
            val hi = if (i + w < n - 1) i + w else n - 1
            var best = Float.POSITIVE_INFINITY
            var j = lo
            while (j <= hi) {
                val d = layout.dKw(gu, gv, t[2 * j], t[2 * j + 1])
                if (d < best) best = d
                j++
            }
            acc += alpha[i] * best
        }
        return acc
    }

    // ── Direction / tangent channel (OQ-8) ───────────────────────────────────────

    /**
     * Bounded, α-end-weighted per-segment DIRECTION penalty (research doc Rank 3, ASK
     * `1 + k·(1 − cosθ)` precedent). For each of the N−1 index-aligned segments, compare
     * the gesture tangent `(gᵢ₊₁ − gᵢ)` to the template tangent `(tᵢ₊₁ − tᵢ)` by cosine
     * similarity; accumulate `(1 − cosθ)` ∈ [0, 2], weighted so mid/approach segments
     * count (endpoints already governed by [endpointPenalty]). The raw sum is scaled by
     * [GeometricEngineConfig.directionPenaltyWeight] and CAPPED at
     * [GeometricEngineConfig.directionPenaltyCap] so it can never dominate S(w).
     *
     * Why a NEW channel and not a σ re-tune: aggregate tangent direction is
     * noise-AVERAGING (zero-mean per-point jitter cancels in a segment vector) whereas
     * the positional shape sum is noise-ACCUMULATING — so this moves numbers the
     * saturated σ sweep provably could not, on exactly the key-boundary-adjacent
     * ambiguity that swamps absolute geometry at SLOPPY.
     *
     * Zero-length segments (coincident resampled points — possible on short/degenerate
     * templates) contribute nothing (no defined tangent), so the penalty stays finite.
     */
    internal fun directionPenalty(g: FloatArray, t: FloatArray): Float {
        val segCount = n - 1
        if (segCount < 1) return 0f
        var acc = 0f
        var contributing = 0
        for (i in 0 until segCount) {
            val gdx = g[2 * (i + 1)] - g[2 * i]
            val gdy = g[2 * (i + 1) + 1] - g[2 * i + 1]
            val tdx = t[2 * (i + 1)] - t[2 * i]
            val tdy = t[2 * (i + 1) + 1] - t[2 * i + 1]
            val gn = sqrt(gdx * gdx + gdy * gdy)
            val tn = sqrt(tdx * tdx + tdy * tdy)
            if (gn <= 0f || tn <= 0f) continue // coincident points → no tangent
            var cos = (gdx * tdx + gdy * tdy) / (gn * tn)
            if (cos > 1f) cos = 1f
            if (cos < -1f) cos = -1f
            acc += 1f - cos // ∈ [0, 2]
            contributing++
        }
        if (contributing == 0) return 0f
        // Mean (1−cosθ) so the penalty is length-invariant (never a long-word bias),
        // scaled by the weight, capped to bound its influence like the corner bonus.
        val mean = acc / contributing
        val raw = config.directionPenaltyWeight * mean
        return if (raw > config.directionPenaltyCap) config.directionPenaltyCap else raw
    }

    // ── Bounded penalties / bonus (§6) ───────────────────────────────────────────

    /**
     * Length-ratio penalty `((ratio − 1)/lengthRatioSigma)²` with
     * `ratio = gesturePathLenKw / templatePathLenKw`. A finger whose travel matches
     * the template pays nothing; large over/undershoot is quadratically penalized.
     * Guarded against a zero-length template (the loop-primary rule guarantees > 0,
     * but stay total).
     */
    internal fun lengthRatioPenalty(gestureLenKw: Float, templateLenKw: Float): Float {
        if (templateLenKw <= 0f) return 0f
        val ratio = gestureLenKw / templateLenKw
        val z = (ratio - 1f) / config.lengthRatioSigma
        return z * z
    }

    /**
     * Endpoint-anchor penalty (§6): a quadratic soft penalty for the gesture start /
     * end being beyond `startNeighborRadius` / `endNeighborRadius` (kw) of the first /
     * last TEMPLATE point. Ends are looser than starts (ASK precedent — users overshoot
     * ends). Within the radius the penalty is 0.
     *
     * With the OQ-9 direction-aware overshoot clamp enabled
     * (`endOvershootCostScale < 1`), the END distance is replaced by
     * [endAnchorDistanceKw], which discounts the along-travel overshoot component of
     * the end error before the radius slack. At the default `1.0` the symmetric
     * `d_kw` path is taken verbatim (bit-identical no-op).
     */
    internal fun endpointPenalty(gesture: ProcessedGesture, t: FloatArray, layout: LayoutGeometry): Float {
        val g = gesture.points
        val dStart = layout.dKw(g[0], g[1], t[0], t[1])
        val dEnd = endAnchorDistanceKw(g, t, layout)
        val overStart = (dStart - config.startNeighborRadius).coerceAtLeast(0f)
        val overEnd = (dEnd - config.endNeighborRadius).coerceAtLeast(0f)
        return overStart * overStart + overEnd * overEnd
    }

    /**
     * Effective END-anchor distance (kw) for [endpointPenalty] — the OQ-9 / ARC-027
     * direction-aware overshoot clamp.
     *
     * The end error vector (template end → gesture end) is expressed in PHYSICAL kw
     * coordinates (the same anisotropy correction as [LayoutGeometry.dKw]: Δv is
     * divided by `aspect` before the kw scale) and decomposed against the gesture's
     * final travel direction (the last non-degenerate resampled segment, same frame):
     *
     *  - `along > 0` (the gesture ended PAST the template end, continuing its travel —
     *    classic momentum overshoot): that component is scaled by
     *    `endOvershootCostScale` before recombining, so pure overshoot is cheaper
     *    than an orthogonal miss of the same magnitude.
     *  - `along ≤ 0` (undershoot / lateral miss): full symmetric distance, unchanged —
     *    stopping short of the last key remains fully suspicious (ASK asymmetry).
     *
     * `endOvershootCostScale == 1` returns the plain `d_kw` end distance via an early
     * exit (bit-identical to the pre-OQ-9 scorer). A degenerate gesture with no
     * non-zero segment (all points coincident) has no travel direction and also falls
     * back to the symmetric distance. Always finite.
     */
    internal fun endAnchorDistanceKw(g: FloatArray, t: FloatArray, layout: LayoutGeometry): Float {
        val last = n - 1
        val dEnd = layout.dKw(g[2 * last], g[2 * last + 1], t[2 * last], t[2 * last + 1])
        val scale = config.endOvershootCostScale
        if (scale >= 1f) return dEnd // symmetric default — bit-identical no-op
        // End error vector in physical kw units (matches d_kw's metric exactly, so
        // sqrt(eu² + ev²) == dEnd and the scale==1 recombination is the identity).
        val kw = layout.meanKeyWidth
        val eu = (g[2 * last] - t[2 * last]) / kw
        val ev = ((g[2 * last + 1] - t[2 * last + 1]) / layout.aspect) / kw
        // Final travel direction: last non-degenerate resampled segment (physical frame).
        var i = last
        var du = 0f
        var dv = 0f
        while (i >= 1) {
            du = g[2 * i] - g[2 * (i - 1)]
            dv = (g[2 * i + 1] - g[2 * (i - 1) + 1]) / layout.aspect
            if (du != 0f || dv != 0f) break
            i--
        }
        val dn = sqrt(du * du + dv * dv)
        if (dn <= 0f) return dEnd // no travel direction (degenerate) → symmetric
        val ux = du / dn
        val uy = dv / dn
        val along = eu * ux + ev * uy
        if (along <= 0f) return dEnd // undershoot / behind travel → full cost
        val errSq = eu * eu + ev * ev
        val perpSq = (errSq - along * along).coerceAtLeast(0f) // float-slop guard
        val discounted = along * scale
        return sqrt(discounted * discounted + perpSq)
    }

    /**
     * Corner-anchor bonus (§6): rewards a template whose corners are matched by a
     * gesture corner nearby — NORMALIZED by the template corner count and CAPPED at
     * `cornerAnchorBonus` so it can never become a length/complexity bias (an unbounded
     * per-corner bonus would favor jagged long words).
     *
     * ```
     * cornerBonus = cornerAnchorBonus · matchedCorners / max(1, templateCornerCount)
     * ```
     *
     * A template corner (a resampled-index turn ≥ threshold on the template polyline)
     * counts as matched iff some gesture corner index sits within ±cornerMatchWindow
     * points of it (index proximity — both are on the SAME arc-length resampling, so
     * matching indices means matching arc positions). With no template corners the
     * bonus is 0 (collinear words rely on the shape/location channels).
     */
    internal fun cornerBonus(gesture: ProcessedGesture, t: FloatArray, layout: LayoutGeometry): Float {
        // Free early-out FIRST: a corner-less gesture (straight/collinear words —
        // common) can never earn a bonus, so skip the O(N) template corner detection
        // entirely for it.
        val gestureCorners = gesture.cornerIndices
        if (gestureCorners.isEmpty()) return 0f
        val templateCorners = templateCornerIndices(t)
        if (templateCorners.isEmpty()) return 0f
        var matched = 0
        for (tc in templateCorners) {
            var hit = false
            for (gc in gestureCorners) {
                if (gc >= tc - CORNER_MATCH_WINDOW && gc <= tc + CORNER_MATCH_WINDOW) { hit = true; break }
            }
            if (hit) matched++
        }
        return config.cornerAnchorBonus * matched / templateCorners.size
    }

    /**
     * Corner indices of a resampled TEMPLATE polyline — the same turn-angle rule as
     * [GesturePreprocessor.detectCorners] (a pin test asserts the two stay identical;
     * corner matching is index-proximity-based, so asymmetric edits would silently
     * break the bonus). `internal` for that pin test.
     */
    internal fun templateCornerIndices(t: FloatArray): IntArray {
        if (n < 3) return IntArray(0)
        val thresholdRad = Math.toRadians(config.cornerAngleThresholdDeg.toDouble())
        val out = ArrayList<Int>()
        for (i in 1 until n - 1) {
            val ax = t[2 * i] - t[2 * (i - 1)]
            val ay = t[2 * i + 1] - t[2 * (i - 1) + 1]
            val bx = t[2 * (i + 1)] - t[2 * i]
            val by = t[2 * (i + 1) + 1] - t[2 * i + 1]
            val na = sqrt(ax * ax + ay * ay)
            val nb = sqrt(bx * bx + by * by)
            if (na <= 0f || nb <= 0f) continue
            var cos = (ax * bx + ay * by) / (na * nb)
            if (cos > 1f) cos = 1f
            if (cos < -1f) cos = -1f
            val turn = acos(cos.toDouble())
            if (turn >= thresholdRad) out.add(i)
        }
        return out.toIntArray()
    }

    companion object {
        /** Index tolerance for matching a gesture corner to a template corner (§6). */
        const val CORNER_MATCH_WINDOW = 2

        /**
         * Build the α end-weight table for N points: symmetric tent minimal at the
         * midpoint, increasing linearly toward both endpoints, normalized so Σα = 1.
         */
        internal fun buildAlpha(n: Int): FloatArray {
            require(n >= 2) { "alpha needs N >= 2, was $n" }
            val a = FloatArray(n)
            val mid = (n - 1) / 2f
            var sum = 0f
            for (i in 0 until n) {
                // Distance from the midpoint, normalized so the endpoints are ~1.0 above
                // the midpoint's baseline of 1.0 (weight ranges [1, 2]).
                val w = 1f + (if (mid > 0f) kotlin.math.abs(i - mid) / mid else 0f)
                a[i] = w
                sum += w
            }
            for (i in 0 until n) a[i] /= sum
            return a
        }
    }
}
