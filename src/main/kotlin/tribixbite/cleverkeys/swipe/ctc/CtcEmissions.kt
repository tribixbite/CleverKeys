package tribixbite.cleverkeys.swipe.ctc

/**
 * Per-frame log-emission matrix that the CTC beam decodes.
 *
 * Shape is `[frames][numClasses]` where `numClasses == alphabetSize + 1`; the last
 * column (index [blankIndex]) is the CTC blank. Each row is a log-distribution over the
 * active alphabet plus blank for one output frame (FUTO's encoder emits ~T/2 frames for
 * a T=64 input path — see the integration study §1a).
 *
 * ## Where these come from (why this is an INPUT, not something we compute)
 * In production the emissions are produced by the CleverKeys-trained CTC encoder that
 * ships in the APK — `models/ctc_swipe_encoder.onnx`, run through ORT by
 * `swipe/OnnxCtcEmissionModel` — which is the DEFAULT swipe engine (`swipe_engine_mode`
 * = `"ctc"` since 2026-08-18). That graph's head is **64 geometry-conditioned key slots
 * plus one blank**, never letters: emission column `c` means "the key the caller placed in
 * slot `c` of `layout_keys`". [sliceFromHead] narrows that fixed 65-wide head to this
 * type's `[frames][alphabetSize + 1]` contract view, relocating blank from column
 * `maxKeys` to column `numLetters`.
 *
 * Keeping the matrix an INPUT is what makes the whole decode path testable with no model
 * at all: the golden tests feed matrices frozen from the Python port. See
 * `docs/specs/ctc-architecture-and-multiscript-guide.md` §1 for the graph contract.
 *
 * @property values row-major log-emissions, length `frames * numClasses`.
 * @property frames number of output time frames (T').
 * @property numClasses alphabet size + 1 (the +1 is blank).
 */
class CtcEmissions(
    val values: FloatArray,
    val frames: Int,
    val numClasses: Int,
) {
    init {
        require(frames >= 0) { "frames must be >= 0, was $frames" }
        require(numClasses >= 2) { "numClasses must be >= 2 (alphabet + blank), was $numClasses" }
        require(values.size == frames * numClasses) {
            "values length ${values.size} != frames*numClasses (${frames * numClasses})"
        }
    }

    /** Blank is always the last class column. */
    val blankIndex: Int get() = numClasses - 1

    /** Alphabet size (number of real key classes, excluding blank). */
    val alphabetSize: Int get() = numClasses - 1

    /** Log-emission for class [c] at frame [t] (no bounds re-check on the hot path). */
    fun at(t: Int, c: Int): Float = values[t * numClasses + c]

    /** The full row for frame [t] as a fresh [FloatArray] of length [numClasses]. */
    fun row(t: Int): FloatArray = values.copyOfRange(t * numClasses, t * numClasses + numClasses)

    companion object {
        /**
         * Slice a raw encoder head `[frames][maxKeys + 1]` down to the active-alphabet
         * view `[frames][numLetters + 1]`, moving blank from the full head's last column
         * (index `maxKeys`) into slot `numLetters`.
         *
         * This is the exact port of `engine.cpp::predict_segment`'s emission-slice block
         * (integration study §2, "Emission slicing"): the universal encoder emits over
         * `maxKeys + 1` classes (FUTO's `maxKeys = 64`), and the engine copies the K real
         * key columns `[0..numLetters)` then relocates blank from `maxKeys` to `numLetters`.
         *
         * @param fullHead row-major `[frames][maxKeys + 1]` log-emissions.
         * @param frames number of time frames.
         * @param maxKeys export-time key capacity (blank lives at column `maxKeys`).
         * @param numLetters count of active alphabet columns to keep (`[0..numLetters)`).
         */
        fun sliceFromHead(
            fullHead: FloatArray,
            frames: Int,
            maxKeys: Int,
            numLetters: Int,
        ): CtcEmissions {
            require(numLetters in 1..maxKeys) { "numLetters $numLetters out of range 1..$maxKeys" }
            val headWidth = maxKeys + 1
            require(fullHead.size == frames * headWidth) {
                "fullHead length ${fullHead.size} != frames*(maxKeys+1) (${frames * headWidth})"
            }
            val outWidth = numLetters + 1
            val out = FloatArray(frames * outWidth)
            for (t in 0 until frames) {
                val srcBase = t * headWidth
                val dstBase = t * outWidth
                System.arraycopy(fullHead, srcBase, out, dstBase, numLetters)
                out[dstBase + numLetters] = fullHead[srcBase + maxKeys] // blank column
            }
            return CtcEmissions(out, frames, outWidth)
        }
    }
}
