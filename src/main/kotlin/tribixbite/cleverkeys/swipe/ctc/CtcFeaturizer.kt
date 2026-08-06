package tribixbite.cleverkeys.swipe.ctc

/**
 * Touch-path featurizer for the FUTO-style CTC encoder — a faithful port of
 * `swipe-library/src/resampler.cpp` and the Python port's `featurize`
 * ([scripts/futo_decoder_eval.py]).
 *
 * The encoder consumes a fixed-length `[2, 64]` path tensor (x-row then y-row,
 * interleaved as `[x0..x63, y0..y63]`) plus a layout key-center tensor + mask (see
 * [buildPaddedLayout]). This class produces the path tensor via FUTO's two-stage
 * resample and provides the coordinate-frame helpers the geometry-parameterized encoder
 * expects.
 *
 * ## Precision contract
 * All interpolation runs in [Double] and the result is cast to [Float] only at the final
 * step — exactly mirroring the Python port (float64 lerp, `np.float32` output). This makes
 * [featurize]'s output bit-identical to the port's, which is what the golden featurizer
 * test asserts. (`resampler.cpp` itself uses `float` intermediates, a sub-ULP difference.)
 */
object CtcFeaturizer {

    /** Fixed encoder input length T (`resampler.hpp`). */
    const val RESAMPLE_LENGTH = 64

    /** Export-time key capacity (FUTO's `max_keys`); layout tensors pad to this. */
    const val MAX_KEYS = 64

    /** 60 Hz sample interval in ms (`1000/60`, float64 to match the Python port). */
    const val HZ_INTERVAL_MS = 1000.0 / 60.0

    /** FUTO's vertical aspect correction factor (integration study §3c / §4c). */
    const val VERTICAL_ASPECT = 4.0 / 3.0

    private const val EPS = 1e-12

    /** `std::lower_bound` / `bisect_left`: leftmost index `i` with `t[i] >= target`. */
    private fun lowerBound(t: DoubleArray, n: Int, target: Double): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    /**
     * Stage 1 — normalize to ~60 Hz (`resampler.cpp::resample_to_60hz`).
     *
     * Output count `max(2, round(duration/16.667) + 1)`, target times
     * `linspace(0, duration, count)`, values by lower-bound segment lerp. The `round` is
     * round-half-to-even ([Math.rint]) to match Python's `int(round(...))`.
     *
     * @return interleaved `[outX, outY]` as a pair of equal-length arrays.
     */
    fun resampleTo60Hz(x: DoubleArray, y: DoubleArray, t: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        if (n == 0) return Pair(DoubleArray(0), DoubleArray(0))
        if (n == 1) return Pair(doubleArrayOf(x[0], x[0]), doubleArrayOf(y[0], y[0]))

        val t0 = t[0]
        val duration = t[n - 1] - t0
        if (duration <= EPS) {
            return Pair(doubleArrayOf(x[0], x[n - 1]), doubleArrayOf(y[0], y[n - 1]))
        }

        val numOutput = maxOf(2, Math.rint(duration / HZ_INTERVAL_MS).toInt() + 1)
        val step = duration / (numOutput - 1)
        val outX = DoubleArray(numOutput)
        val outY = DoubleArray(numOutput)
        for (i in 0 until numOutput) {
            var targetT = t0 + i * step
            if (targetT > t[n - 1]) targetT = t[n - 1]
            val idx1 = lowerBound(t, n, targetT)
            when {
                idx1 == 0 -> { outX[i] = x[0]; outY[i] = y[0] }
                idx1 >= n -> { outX[i] = x[n - 1]; outY[i] = y[n - 1] }
                else -> {
                    val idx0 = idx1 - 1
                    val seg = t[idx1] - t[idx0]
                    val frac = if (seg > EPS) (targetT - t[idx0]) / seg else 0.0
                    outX[i] = lerp(x[idx0], x[idx1], frac)
                    outY[i] = lerp(y[idx0], y[idx1], frac)
                }
            }
        }
        return Pair(outX, outY)
    }

    /**
     * Stage 2 — fixed length via index-uniform interpolation + clip to [0,1]
     * (`resampler.cpp::resample_path`, index-based branch used because stage 1 already
     * made the samples time-uniform).
     */
    fun resampleFixed(
        x: DoubleArray,
        y: DoubleArray,
        length: Int = RESAMPLE_LENGTH,
    ): Pair<DoubleArray, DoubleArray> {
        val outX = DoubleArray(length)
        val outY = DoubleArray(length)
        val n = x.size
        if (n == 0) return Pair(outX, outY)
        if (n == 1) {
            val cx = x[0].coerceIn(0.0, 1.0)
            val cy = y[0].coerceIn(0.0, 1.0)
            outX.fill(cx); outY.fill(cy)
            return Pair(outX, outY)
        }
        for (i in 0 until length) {
            val fracPos = i.toDouble() / (length - 1)
            val idx = fracPos * (n - 1)
            val idx0 = idx.toInt()
            val idx1 = minOf(idx0 + 1, n - 1)
            val frac = idx - idx0
            outX[i] = lerp(x[idx0], x[idx1], frac).coerceIn(0.0, 1.0)
            outY[i] = lerp(y[idx0], y[idx1], frac).coerceIn(0.0, 1.0)
        }
        return Pair(outX, outY)
    }

    /**
     * Full featurization of a normalized ([0,1]) touch path into the encoder's `[2, 64]`
     * tensor, flattened row-major as `[x0..x63, y0..y63]` (float32).
     *
     * Input points must already be in the layout's [0,1] coordinate frame — apply
     * [normalizeRawX]/[normalizeRawY] first for raw device pixels.
     *
     * @param px normalized x per raw sample.
     * @param py normalized y per raw sample.
     * @param pt timestamps (ms) per raw sample; must match [px]/[py] length.
     */
    fun featurize(px: DoubleArray, py: DoubleArray, pt: DoubleArray): FloatArray {
        require(px.size == py.size && px.size == pt.size) {
            "px/py/pt must have equal length (${px.size}/${py.size}/${pt.size})"
        }
        val (hx, hy) = resampleTo60Hz(px, py, pt)
        val (rx, ry) = resampleFixed(hx, hy, RESAMPLE_LENGTH)
        val out = FloatArray(2 * RESAMPLE_LENGTH)
        for (i in 0 until RESAMPLE_LENGTH) {
            out[i] = rx[i].toFloat()
            out[RESAMPLE_LENGTH + i] = ry[i].toFloat()
        }
        return out
    }

    /** [Float] convenience overload; promotes to [Double] before the exact path. */
    fun featurize(px: FloatArray, py: FloatArray, pt: FloatArray): FloatArray =
        featurize(
            DoubleArray(px.size) { px[it].toDouble() },
            DoubleArray(py.size) { py[it].toDouble() },
            DoubleArray(pt.size) { pt[it].toDouble() },
        )

    /**
     * Map a raw device x-pixel into the model's [0,1] frame:
     * `rawX / keyboardWidth * sx + ox` (integration study §4c). `sx`/`ox` are the layout
     * affine from FUTO's `SpecialDecoder.matchLayout`; identity (`1,0`) for a layout that
     * matches the canonical frame directly.
     */
    fun normalizeRawX(rawX: Double, keyboardWidth: Double, sx: Double = 1.0, ox: Double = 0.0): Double =
        rawX / keyboardWidth * sx + ox

    /**
     * Map a raw device y-pixel into the model's [0,1] frame WITH the 4/3 vertical aspect
     * correction, clamped to 1: `min(1, rawY / keyboardHeight * (4/3) * sy + oy)`
     * (integration study §3c / §4c — the "tapping Q must pass Q's center" contract).
     */
    fun normalizeRawY(rawY: Double, keyboardHeight: Double, sy: Double = 1.0, oy: Double = 0.0): Double =
        minOf(1.0, rawY / keyboardHeight * VERTICAL_ASPECT * sy + oy)

    /**
     * Build the encoder's layout tensors from a [CtcLayout]: key centers written into
     * slots `[0..K)` as `(cx, cy)` (rest `(0,0)`), and a boolean mask true for the K real
     * keys (`engine.cpp::build_padded_layout_inputs`). Both are padded to [MAX_KEYS].
     *
     * @return [PaddedLayout] with `keys` (length `MAX_KEYS*2`, interleaved cx,cy) and
     *   `mask` (length `MAX_KEYS`).
     */
    fun buildPaddedLayout(layout: CtcLayout): PaddedLayout {
        val k = layout.alphabet.size
        require(k <= MAX_KEYS) { "layout has $k keys, exceeds MAX_KEYS=$MAX_KEYS" }
        val keys = FloatArray(MAX_KEYS * 2)
        val mask = BooleanArray(MAX_KEYS)
        for (i in 0 until k) {
            keys[i * 2] = layout.keyCentersX[i]
            keys[i * 2 + 1] = layout.keyCentersY[i]
            mask[i] = true
        }
        return PaddedLayout(keys, mask)
    }

    /** Padded encoder layout tensors: interleaved key centers + real-key mask. */
    class PaddedLayout(val keys: FloatArray, val mask: BooleanArray)
}
