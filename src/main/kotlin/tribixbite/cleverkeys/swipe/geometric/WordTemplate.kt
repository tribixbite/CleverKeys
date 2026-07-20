package tribixbite.cleverkeys.swipe.geometric

/**
 * A fully materialized swipe template for one dictionary word.
 *
 * Variants are encoded EXPLICITLY ([variantCount] + [pathLengthsKw]) — a single
 * flat array with no count is undecodable. A word has:
 *  - **1 variant** when the collapsed key sequence has no doubled letter, OR when
 *    the whole word collapses to a single key (the loop-primary rule, FR-6/§2).
 *  - **2 variants** when the pre-collapse sequence had a doubled letter: variant 0
 *    is the plain polyline, variant 1 inserts a small square loop at each doubled
 *    letter. The scorer takes whichever variant scores better.
 *
 * [points] packs `variantCount * 2N` u15-quantized normalized coords (see
 * [WordTemplate.quantize] / [WordTemplate.dequantize]) to keep the Tier-B memo
 * footprint honest (128 B per variant at N=32).
 *
 * @param wordIndex index into [GeometricDictionary] — equal to the word's ordinal
 *   frequency rank r(w) (0 = most frequent).
 * @param variantCount 1 or 2.
 * @param points variantCount * 2N u15-quantized interleaved normalized coords.
 * @param pathLengthsKw per-variant polyline arc length (kw units); size == variantCount.
 */
class WordTemplate(
    val wordIndex: Int,
    val variantCount: Int,
    val points: ShortArray,
    val pathLengthsKw: FloatArray,
) {
    init {
        require(variantCount == 1 || variantCount == 2) {
            "variantCount must be 1 or 2, was $variantCount"
        }
        require(pathLengthsKw.size == variantCount) {
            "pathLengthsKw size ${pathLengthsKw.size} != variantCount $variantCount"
        }
        require(points.size % (variantCount * 2) == 0) {
            "points size ${points.size} not a multiple of variantCount*2 (${variantCount * 2})"
        }
    }

    /** Number of resampled points N per variant. */
    val pointCount: Int get() = points.size / (variantCount * 2)

    /**
     * Dequantize [variant]'s coords into [out] (interleaved u,v, length 2N).
     * [out] is caller-supplied to keep the scorer allocation-light on the hot path.
     */
    fun copyVariantInto(variant: Int, out: FloatArray) {
        val n2 = pointCount * 2
        val base = variant * n2
        for (i in 0 until n2) out[i] = dequantize(points[base + i])
    }

    companion object {
        /**
         * u15 quantization: a normalized coordinate ∈ [0,1] maps to a signed
         * short in [0, 32767]. Granularity ≈ 1/32767 of the key area — far finer
         * than any threshold, and lossless for fingerprint/scoring purposes.
         * Values are clamped to [0,1] because loop overshoot can push a coord a
         * hair outside the unit square.
         */
        fun quantize(coord: Float): Short {
            val clamped = if (coord < 0f) 0f else if (coord > 1f) 1f else coord
            return (clamped * 32767f + 0.5f).toInt().toShort()
        }

        /** Inverse of [quantize]. */
        fun dequantize(q: Short): Float = (q.toInt() and 0xFFFF).let {
            // Shorts stored 0..32767 never set the sign bit, but mask defensively.
            it.coerceAtMost(32767) / 32767f
        }
    }
}
