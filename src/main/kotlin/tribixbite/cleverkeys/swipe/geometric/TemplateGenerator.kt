package tribixbite.cleverkeys.swipe.geometric

import kotlin.math.sqrt

/**
 * Turns a dictionary word into its resampled, variant-encoded [WordTemplate] — the
 * geometric decoder's per-word "ideal swipe" model (SHARK2 §2).
 *
 * Pipeline (per word, lazy — called only for pruning survivors on the hot path):
 *  1. [LayoutProjection.project] → pre-collapse key-id sequence, or skip (FR-4).
 *  2. Collapse consecutive duplicate key ids.
 *  3. **collapsed length ≥ 2**: variant 0 = polyline through the collapsed centroids,
 *     resampled to N. If the *pre-collapse* sequence had a doubled letter, variant 1
 *     inserts a small square loop (side `doubleLetterLoopRadius`·kw) at each doubled
 *     letter, also resampled to N. The scorer takes whichever variant scores better.
 *  4. **collapsed length == 1** (single-key word — "её"/"ее" on ЙЦУКЕН where ё aliases
 *     to the е key): the loop variant is the PRIMARY and ONLY template — a square loop
 *     at the key centroid. It has nonzero path length + bbox so every downstream
 *     formula stays finite (FR-6, avoids a degenerate 1-point template).
 *
 * All geometry is normalized [0,1]²; loop sizes are converted from kw units to
 * normalized-u units via [LayoutGeometry.meanKeyWidth]. Purity (NFR-3): `kotlin.*`
 * only.
 */
class TemplateGenerator(
    private val config: GeometricEngineConfig,
) {
    /** Resample target N (points per variant). */
    private val n: Int = config.resamplePoints

    /** Loop side length in NORMALIZED-u units (kw units × mean key width). */
    private fun loopSideNorm(layout: LayoutGeometry): Float =
        config.doubleLetterLoopRadius * layout.meanKeyWidth

    /**
     * Generate the [WordTemplate] for [word] on [layout], or `null` if the word is
     * untypeable (per-layout vocabulary filter, FR-4).
     *
     * @param wordIndex ordinal frequency rank of the word (stored in the template).
     */
    fun generate(word: String, wordIndex: Int, layout: LayoutGeometry): WordTemplate? {
        val preCollapse = LayoutProjection.project(word, layout) ?: return null
        return fromKeyIds(preCollapse, wordIndex, layout)
    }

    /**
     * Build a template directly from a PRE-COLLAPSE key-id sequence. Exposed for
     * tests that want to feed a hand-built sequence without the projection step; the
     * production path goes through [generate].
     */
    fun fromKeyIds(preCollapse: IntArray, wordIndex: Int, layout: LayoutGeometry): WordTemplate {
        require(preCollapse.isNotEmpty()) { "cannot build a template from an empty key sequence" }
        val keys = layout.keys

        // Collapse consecutive duplicate key ids; remember whether any run had length
        // ≥ 2 (a doubled letter, which triggers the loop variant).
        val collapsed = ArrayList<Int>(preCollapse.size)
        var hadDouble = false
        for (id in preCollapse) {
            if (collapsed.isEmpty() || collapsed[collapsed.size - 1] != id) {
                collapsed.add(id)
            } else {
                hadDouble = true // consecutive repeat of the same key
            }
        }

        val loopSide = loopSideNorm(layout)

        return if (collapsed.size == 1) {
            // Single-key word: loop-primary rule. The loop gives nonzero span/length.
            val loop = buildLoopPolyline(keys[collapsed[0]].cx, keys[collapsed[0]].cy, loopSide)
            val resampled = resampleArcLength(loop, n)
            val len = polylineLengthKw(resampled, layout)
            packVariants(wordIndex, listOf(resampled), floatArrayOf(len))
        } else {
            // ≥ 2 distinct keys: plain polyline (variant 0).
            val plain = centroidPolyline(collapsed, keys)
            val plainResampled = resampleArcLength(plain, n)
            val plainLen = polylineLengthKw(plainResampled, layout)

            if (!hadDouble) {
                packVariants(wordIndex, listOf(plainResampled), floatArrayOf(plainLen))
            } else {
                // variant 1: same polyline with a square loop inserted at each doubled
                // letter (identified from the pre-collapse runs).
                val looped = loopedPolyline(preCollapse, keys, loopSide)
                val loopedResampled = resampleArcLength(looped, n)
                val loopedLen = polylineLengthKw(loopedResampled, layout)
                packVariants(
                    wordIndex,
                    listOf(plainResampled, loopedResampled),
                    floatArrayOf(plainLen, loopedLen),
                )
            }
        }
    }

    // ── polyline construction ──────────────────────────────────────────────────

    /** Interleaved (u,v) polyline through the collapsed key centroids. */
    private fun centroidPolyline(ids: List<Int>, keys: List<SwipeKey>): FloatArray {
        val out = FloatArray(ids.size * 2)
        for ((k, id) in ids.withIndex()) {
            out[2 * k] = keys[id].cx
            out[2 * k + 1] = keys[id].cy
        }
        return out
    }

    /**
     * A closed square loop centred at (cx, cy) with side [side] (normalized-u units),
     * as an 8-point interleaved polyline that returns near its start so the template
     * for a single-key or doubled-letter word has nonzero bbox and path length. The
     * vertical extent uses the same normalized [side]; anisotropy is folded into the
     * kw-length computation via [LayoutGeometry.dKw], not the point coordinates.
     */
    private fun buildLoopPolyline(cx: Float, cy: Float, side: Float): FloatArray {
        val r = side * 0.5f
        // Start at the centre, trace a small square, and return to the centre so the
        // path is a genuine there-and-back-with-a-loop, not a bare point.
        return floatArrayOf(
            cx, cy,
            cx - r, cy - r,
            cx + r, cy - r,
            cx + r, cy + r,
            cx - r, cy + r,
            cx, cy,
        )
    }

    /**
     * Build the looped polyline for the ≥ 2-key case: walk the PRE-COLLAPSE sequence,
     * emitting each key centroid; when a key repeats consecutively, emit a small loop
     * at that centroid instead of a duplicate point. This mirrors how a user swipes a
     * doubled letter — a little circle over the key rather than a dwell.
     */
    private fun loopedPolyline(preCollapse: IntArray, keys: List<SwipeKey>, side: Float): FloatArray {
        val r = side * 0.5f
        val pts = ArrayList<Float>(preCollapse.size * 2 + 8)
        var i = 0
        while (i < preCollapse.size) {
            val id = preCollapse[i]
            val cx = keys[id].cx
            val cy = keys[id].cy
            // Count the run length of this key id.
            var run = 1
            while (i + run < preCollapse.size && preCollapse[i + run] == id) run++
            if (run == 1) {
                pts.add(cx); pts.add(cy)
            } else {
                // Doubled (or more) letter: enter the key, trace a loop, leave. One
                // loop covers the repeat regardless of run length (dictionaries never
                // triple a key; extra repeats collapse into the same loop).
                pts.add(cx); pts.add(cy)
                pts.add(cx - r); pts.add(cy - r)
                pts.add(cx + r); pts.add(cy - r)
                pts.add(cx + r); pts.add(cy + r)
                pts.add(cx - r); pts.add(cy + r)
                pts.add(cx); pts.add(cy)
            }
            i += run
        }
        val out = FloatArray(pts.size)
        for (k in pts.indices) out[k] = pts[k]
        return out
    }

    // ── arc-length resampling (ported from SwipeResampler's family, not imported) ─

    /**
     * Arc-length UNIFORM resample of an interleaved (u,v) polyline to exactly [target]
     * points: the first and last input points are preserved, and the interior points
     * are placed at equal fractions of total path length. This is the "port, don't
     * import" of `SwipeResampler` — its modes truncate/merge by index, whereas the
     * geometric template must be arc-length uniform so d_shape/d_loc compare like
     * against like.
     *
     * Degenerate zero-length input (all points coincident — impossible after the
     * loop-primary rule, but guarded) collapses to [target] copies of the point.
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
            // All coincident: fill with the single point.
            for (k in 0 until target) { out[2 * k] = poly[0]; out[2 * k + 1] = poly[1] }
            return out
        }

        // Walk target equally-spaced arc-length positions, interpolating within the
        // segment that contains each position.
        var seg = 1
        for (k in 0 until target) {
            val d = total * (k.toFloat() / (target - 1))
            while (seg < p - 1 && cum[seg] < d) seg++
            val d0 = cum[seg - 1]
            val d1 = cum[seg]
            val segLen = d1 - d0
            val t = if (segLen > 0f) ((d - d0) / segLen).coerceIn(0f, 1f) else 0f
            val ux = poly[2 * (seg - 1)]
            val uy = poly[2 * (seg - 1) + 1]
            val vx = poly[2 * seg]
            val vy = poly[2 * seg + 1]
            out[2 * k] = ux + (vx - ux) * t
            out[2 * k + 1] = uy + (vy - uy) * t
        }
        return out
    }

    /** Total polyline arc length in kw units (uses [LayoutGeometry.dKw] anisotropy). */
    internal fun polylineLengthKw(poly: FloatArray, layout: LayoutGeometry): Float {
        val p = poly.size / 2
        var total = 0f
        for (k in 1 until p) {
            total += layout.dKw(poly[2 * (k - 1)], poly[2 * (k - 1) + 1], poly[2 * k], poly[2 * k + 1])
        }
        return total
    }

    // ── packing ──────────────────────────────────────────────────────────────

    /** Quantize [variants] (each a length-2N interleaved array) into a [WordTemplate]. */
    private fun packVariants(
        wordIndex: Int,
        variants: List<FloatArray>,
        pathLengthsKw: FloatArray,
    ): WordTemplate {
        val variantCount = variants.size
        val n2 = n * 2
        val packed = ShortArray(variantCount * n2)
        for (v in 0 until variantCount) {
            val src = variants[v]
            val base = v * n2
            for (k in 0 until n2) packed[base + k] = WordTemplate.quantize(src[k])
        }
        return WordTemplate(
            wordIndex = wordIndex,
            variantCount = variantCount,
            points = packed,
            pathLengthsKw = pathLengthsKw,
        )
    }
}
