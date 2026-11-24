package tribixbite.cleverkeys.ui.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.isSliderStop
import java.io.File

/**
 * The swipe sensitivity presets in Gesture Tuning (2026-11-24). The Medium preset is labelled as
 * the default values, but it set 50 px / 40 px / 15 ms while the shipped defaults are 72 / 15 / 7:
 * choosing Medium raised the key dwell to 15 ms, double the frame gap of a 120 Hz screen, which
 * drops the second key of fast short swipes (`in`, `is`, `was`; measured 2026-11-23 at 9 ms). And
 * the defaults themselves showed as "Custom". High (more sensitive) also asked for a 25 px key
 * distance, more than the 15 px default.
 */
class SwipeSensitivityPresetsTest {

    private val defaults = SwipeThresholds(
        Defaults.SWIPE_MIN_DISTANCE, Defaults.SWIPE_MIN_KEY_DISTANCE, Defaults.SWIPE_MIN_DWELL_TIME
    )

    private fun of(preset: SwipeSensitivityPreset) = SwipeSensitivityPresets.thresholds(preset)!!

    @Test
    fun mediumIsTheShippedDefaults() {
        assertThat(of(SwipeSensitivityPreset.MEDIUM)).isEqualTo(defaults)
        assertThat(SwipeSensitivityPresets.presetFor(defaults)).isEqualTo(SwipeSensitivityPreset.MEDIUM)
    }

    @Test
    fun eachThresholdGoesDownFromLowToHigh() {
        val low = of(SwipeSensitivityPreset.LOW)
        val medium = of(SwipeSensitivityPreset.MEDIUM)
        val high = of(SwipeSensitivityPreset.HIGH)
        assertThat(low.minDistance).isAtLeast(medium.minDistance)
        assertThat(medium.minDistance).isAtLeast(high.minDistance)
        assertThat(low.minKeyDistance).isAtLeast(medium.minKeyDistance)
        assertThat(medium.minKeyDistance).isAtLeast(high.minKeyDistance)
        assertThat(low.minDwellMs).isAtLeast(medium.minDwellMs)
        assertThat(medium.minDwellMs).isAtLeast(high.minDwellMs)
        assertThat(low).isNotEqualTo(medium)
        assertThat(high).isNotEqualTo(medium)
    }

    @Test
    fun everyPresetValueIsAStopOfItsSlider() {
        for (preset in SwipeSensitivityPreset.entries) {
            val t = SwipeSensitivityPresets.thresholds(preset) ?: continue
            assertWithMessage("$preset distance").that(
                isSliderStop(t.minDistance, SwipeSensitivityPresets.MIN_DISTANCE_RANGE, SwipeSensitivityPresets.MIN_DISTANCE_STEP)
            ).isTrue()
            assertWithMessage("$preset key distance").that(
                isSliderStop(t.minKeyDistance, SwipeSensitivityPresets.MIN_KEY_DISTANCE_RANGE, SwipeSensitivityPresets.MIN_KEY_DISTANCE_STEP)
            ).isTrue()
            assertWithMessage("$preset dwell").that(
                isSliderStop(t.minDwellMs.toFloat(), SwipeSensitivityPresets.MIN_DWELL_RANGE, SwipeSensitivityPresets.MIN_DWELL_STEP)
            ).isTrue()
        }
    }

    @Test
    fun anythingElseIsCustom() {
        assertThat(SwipeSensitivityPresets.presetFor(defaults.copy(minDwellMs = 9)))
            .isEqualTo(SwipeSensitivityPreset.CUSTOM)
        assertThat(SwipeSensitivityPresets.thresholds(SwipeSensitivityPreset.CUSTOM)).isNull()
    }

    @Test
    fun theSlidersAndTheDropdownUseThisTable() {
        val section = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/GestureTuningSection.kt").readText()
        assertThat(section).contains("SwipeSensitivityPresets.MIN_DISTANCE_RANGE")
        assertThat(section).contains("SwipeSensitivityPresets.MIN_KEY_DISTANCE_RANGE")
        assertThat(section).contains("SwipeSensitivityPresets.MIN_DWELL_RANGE")
        val presets = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsResetPresets.kt").readText()
        val apply = presets.substringAfter("fun SettingsActivity.applySwipeSensitivityPreset")
            .substringBefore("internal fun SettingsActivity.resetAllSettings")
        assertThat(apply).contains("SwipeSensitivityPresets.thresholds(")
        assertThat(apply).doesNotContainMatch("""swipeMinDwellTime = \d""")
    }
}
