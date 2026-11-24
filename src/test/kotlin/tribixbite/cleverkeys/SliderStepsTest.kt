package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [sliderSteps]: Compose `Slider.steps` is the number of positions strictly between the two
 * ends, so a slider moving by `increment` over `range` needs `span / increment − 1`. About 40
 * settings sliders passed `span / increment` instead (`steps = 100` on 0..100), which made every
 * stop fall between two integers (audit 2026-10-20 leftover); `SettingsSlider` now takes the
 * increment and calls this.
 */
class SliderStepsTest {

    @Test
    fun integerRangesGetOneStopPerValue() {
        assertEquals(99, sliderSteps(0f..100f, 1f))                                   // opacity
        assertEquals(19, sliderSteps(SettingsRanges.CUSTOM_BORDER_RADIUS.toSliderRange(), 1f))
        assertEquals(39, sliderSteps(SettingsRanges.KEYBOARD_HEIGHT.toSliderRange(), 1f))
    }

    @Test
    fun coarserIncrementsDivideTheSpan() {
        assertEquals(18, sliderSteps(SettingsRanges.VIBRATE_DURATION.toSliderRange(), 5f)) // 5, 10, … 100
        assertEquals(99, sliderSteps(0f..500f, 5f))                                    // key margins
        assertEquals(14, sliderSteps(SettingsRanges.CLIPBOARD_MAX_ITEM_SIZE_KB.toSliderRange(), 64f))
    }

    @Test
    fun fractionalIncrementsRoundTheIntervalCount() {
        // 0.9 / 0.05 is 17.999998 in float; the count must still be 18 intervals.
        assertEquals(17, sliderSteps(0.1f..1.0f, 0.05f))
        assertEquals(7, sliderSteps(0.5f..0.9f, 0.05f))
        assertEquals(44, sliderSteps(0.5f..5.0f, 0.1f))
    }

    @Test
    fun everyStopLandsOnTheIncrementGrid() {
        // Compose places stop i at start + span * i / (steps + 1); with sliderSteps that is
        // start + i * increment, so a stored integer setting never falls between two values.
        val range = 0f..100f
        val steps = sliderSteps(range, 1f)
        for (i in 0..steps + 1) {
            val stop = range.start + (range.endInclusive - range.start) * i / (steps + 1)
            assertEquals("stop $i", i.toFloat(), stop, 1e-4f)
        }
    }

    @Test
    fun degenerateRangesHaveNoSteps() {
        assertEquals(0, sliderSteps(0f..1f, 1f))   // a two-position slider: just the ends
        assertEquals(0, sliderSteps(0f..0f, 1f))   // never negative
    }

    @Test
    fun nonPositiveIncrementIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { sliderSteps(0f..10f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { sliderSteps(0f..10f, -1f) }
    }

    /**
     * Maintainer 2026-11-24: "i cant reach 1.26 (default noise?) with the slider". The noise filter
     * moves in 0.5 px stops over 0.5..10 px and its default, 1.26 px, lies between the 1.0 and 1.5
     * stops: once moved, the slider could never give the default back.
     */
    @Test
    fun aDefaultBetweenTwoStopsIsNotAStop() {
        assertFalse(isSliderStop(Defaults.SWIPE_NOISE_THRESHOLD, NOISE_RANGE, NOISE_INCREMENT))
        assertTrue(isSliderStop(1.5f, NOISE_RANGE, NOISE_INCREMENT))
        assertTrue(isSliderStop(10f, NOISE_RANGE, NOISE_INCREMENT))
        assertTrue("float rounding: 0.5 + 3 x 0.05", isSliderStop(0.65f, 0.5f..0.9f, 0.05f))
        assertFalse("outside the range", isSliderStop(12f, NOISE_RANGE, NOISE_INCREMENT))
    }

    @Test
    fun aDefaultBetweenStopsBecomesOneMoreStop() {
        val default = Defaults.SWIPE_NOISE_THRESHOLD
        assertEquals("nearer 1.26 than 1.0 or 1.5", default, snapToSliderStop(1.2f, NOISE_RANGE, NOISE_INCREMENT, default), 0f)
        assertEquals(default, snapToSliderStop(1.35f, NOISE_RANGE, NOISE_INCREMENT, default), 0f)
        assertEquals(1.0f, snapToSliderStop(1.05f, NOISE_RANGE, NOISE_INCREMENT, default), 1e-6f)
        assertEquals(1.5f, snapToSliderStop(1.45f, NOISE_RANGE, NOISE_INCREMENT, default), 1e-6f)
        assertEquals("the other stops are where they were", 7.5f, snapToSliderStop(7.6f, NOISE_RANGE, NOISE_INCREMENT, default), 1e-6f)
        assertEquals("clamped to the range", 10f, snapToSliderStop(11f, NOISE_RANGE, NOISE_INCREMENT, default), 1e-6f)
        assertEquals(0.5f, snapToSliderStop(0f, NOISE_RANGE, NOISE_INCREMENT, default), 1e-6f)
    }

    @Test
    fun withoutAnExtraStopTheGridIsUnchanged() {
        assertEquals(1.5f, snapToSliderStop(1.3f, NOISE_RANGE, NOISE_INCREMENT, null), 1e-6f)
        assertEquals(1.0f, snapToSliderStop(1.2f, NOISE_RANGE, NOISE_INCREMENT, null), 1e-6f)
        assertEquals(0.65f, snapToSliderStop(0.66f, 0.5f..0.9f, 0.05f, null), 1e-6f)
    }

    @Test
    fun theNoiseSliderOffersItsDefault() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/GestureTuningSection.kt").readText()
        val call = settingsSliderCallSaving(src, "swipe_noise_threshold")
        assertTrue("valueRange = 0.5f..10f" in call && "increment = 0.5f" in call)
        assertTrue("the default is an extra stop", "defaultValue = Defaults.SWIPE_NOISE_THRESHOLD" in call)
        assertTrue("two decimals: the default reads 1.26 px, not 1.3 px", "\"%.2f px\"" in call)
        // The shared slider turns an off-grid default into a stop (steps = 0 and its own snap).
        val controls = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsControls.kt").readText()
        assertTrue(controls.contains("snapToSliderStop(") && controls.contains("isSliderStop("))
    }

    /** The argument list of the `SettingsSlider(` call in [src] that saves [key]. */
    private fun settingsSliderCallSaving(src: String, key: String): String {
        var from = 0
        while (true) {
            val start = src.indexOf("SettingsSlider(", from)
            check(start >= 0) { "no SettingsSlider saves $key" }
            var i = start + "SettingsSlider(".length
            var depth = 1
            while (depth > 0) {
                when (src[i]) { '(' -> depth++; ')' -> depth-- }
                i++
            }
            val call = src.substring(start, i)
            if ("saveSetting(\"$key\"" in call) return call
            from = i
        }
    }

    private companion object {
        val NOISE_RANGE = 0.5f..10f
        const val NOISE_INCREMENT = 0.5f
    }
}
