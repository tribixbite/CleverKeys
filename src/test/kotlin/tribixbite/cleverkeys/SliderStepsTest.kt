package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
