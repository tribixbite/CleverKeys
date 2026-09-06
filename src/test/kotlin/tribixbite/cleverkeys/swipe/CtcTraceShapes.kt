package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.swipe.ctc.CtcLayout
import kotlin.math.sin

/**
 * Deterministic synthetic trace shapes over a [CtcLayout]'s key centers — the gesture
 * generator shared by the CTC replay tests ([CtcDecodeReplayTest]'s issue-#162 pins and
 * [CtcCustomWordCalibrationReplayTest]'s custom-word calibration measurement).
 *
 * Extracted VERBATIM from `CtcDecodeReplayTest` so both replays measure the same
 * generator: [straight] is the golden-fixture shape (`CtcMultiLanguageInstrumentedTest.
 * traceFor`: 12 steps/segment, 16 ms/step), [wobbled] adds a perpendicular sinusoid
 * whose envelope is pinned to zero at both endpoints. No RNG anywhere — replays built
 * on these cannot flake.
 */
object CtcTraceShapes {

    /**
     * Straight-line trace through [word]'s letter centers: [stepsPerSegment] steps per
     * segment at [stepMs] ms per step.
     */
    fun straight(
        word: String,
        layout: CtcLayout,
        stepsPerSegment: Int = 12,
        stepMs: Double = 16.0,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val cx = DoubleArray(word.length)
        val cy = DoubleArray(word.length)
        for (i in word.indices) {
            val k = layout.alphabet.indexOf(word[i])
            require(k >= 0) { "'${word[i]}' not on layout" }
            cx[i] = layout.keyCentersX[k].toDouble()
            cy[i] = layout.keyCentersY[k].toDouble()
        }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until word.length - 1) {
            for (s in 0 until stepsPerSegment) {
                val f = s / stepsPerSegment.toDouble()
                xs.add(cx[i] + (cx[i + 1] - cx[i]) * f)
                ys.add(cy[i] + (cy[i + 1] - cy[i]) * f)
                ts.add(t)
                t += stepMs
            }
        }
        xs.add(cx.last()); ys.add(cy.last()); ts.add(t)
        return Triple(xs.toDoubleArray(), ys.toDoubleArray(), ts.toDoubleArray())
    }

    /**
     * [straight] plus a deterministic perpendicular sinusoidal wobble of amplitude
     * [amp] (normalized units), envelope-pinned to zero at both endpoints so the first
     * and last keys are still hit.
     */
    fun wobbled(
        word: String,
        layout: CtcLayout,
        amp: Double,
        cycles: Double,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val (x, y, t) = straight(word, layout)
        val n = x.size
        for (i in 0 until n) {
            val j = if (i < n - 1) i else i - 1
            val dx = x[j + 1] - x[j]
            val dy = y[j + 1] - y[j]
            val len = Math.hypot(dx, dy).coerceAtLeast(1e-9)
            val w = amp * sin(2.0 * Math.PI * cycles * i / (n - 1)) *
                sin(Math.PI * i / (n - 1)) // envelope: zero at both ends
            x[i] = (x[i] + w * (-dy / len)).coerceIn(0.0, 1.0)
            y[i] = (y[i] + w * (dx / len)).coerceIn(0.0, 1.0)
        }
        return Triple(x, y, t)
    }
}
