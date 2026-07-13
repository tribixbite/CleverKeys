package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import java.io.File

/**
 * Drift detection for autocorrect setting defaults & slider ranges (AC-1).
 *
 * The single source of truth is `Config.Defaults` (the values `Config.refresh`
 * reads into the live config) and `FrequencyFloor.SLIDER_{MIN,MAX}` (the domain
 * the min-frequency slider is actually mapped over). Two classes drifted:
 *   - `AutoCorrectionSettingsActivity` (a standalone, manifest-exported screen)
 *     hardcoded 500/3/0.67 as prefs read-defaults and Reset values — so it
 *     showed 500 when the real default is 100, and Reset wrote a value 5× the
 *     default.
 *   - `AutoCorrectionSection` (the main Settings screen) + `SettingsValidation`
 *     used a `100..5000` slider range while `FrequencyFloor.SLIDER_MAX = 2000`,
 *     so values 2001-5000 silently clamp to the same floor as 2000.
 *
 * This test scans the real sources and fails on any such drift.
 */
class AutocorrectDefaultsDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun read(rel: String) = File(srcRoot, rel).readText()

    @Test
    fun frequencyFloorSliderMin_matchesConfigDefault() {
        assertWithMessage("FrequencyFloor.SLIDER_MIN must equal the min-frequency default")
            .that(FrequencyFloor.SLIDER_MIN)
            .isEqualTo(Defaults.AUTOCORRECT_MIN_FREQUENCY)
    }

    @Test
    fun autoCorrectionSettingsActivity_prefDefaults_matchConfigDefaults() {
        val src = read("AutoCorrectionSettingsActivity.kt")
        // Every prefs read-default and Reset literal for these keys must equal
        // the corresponding Config.Defaults value.
        val intChecks = mapOf(
            "autocorrect_confidence_min_frequency" to Defaults.AUTOCORRECT_MIN_FREQUENCY,
            "autocorrect_min_word_length" to Defaults.AUTOCORRECT_MIN_WORD_LENGTH,
        )
        for ((key, expected) in intChecks) {
            Regex("""getInt\("$key",\s*(\d+)\)""").findAll(src).forEach { m ->
                assertWithMessage("$key read-default in AutoCorrectionSettingsActivity")
                    .that(m.groupValues[1].toInt()).isEqualTo(expected)
            }
        }
        // No bare `= 500` / `500` reset for the frequency remains.
        assertWithMessage("AutoCorrectionSettingsActivity must not hardcode 500 for min frequency")
            .that(src.contains("minFrequency = 500") || src.contains("\"autocorrect_confidence_min_frequency\", 500"))
            .isFalse()
    }

    @Test
    fun minFrequencySliderRange_upperBound_isSliderMax() {
        // The visible slider must not offer values the floor mapping clamps away.
        val section = read("ui/settings/sections/AutoCorrectionSection.kt")
        val m = Regex("""valueRange\s*=\s*100f\.\.(\d+)f""").find(section)
        assertWithMessage("AutoCorrectionSection min-frequency slider upper bound")
            .that(m?.groupValues?.get(1)?.toInt())
            .isEqualTo(FrequencyFloor.SLIDER_MAX)
    }

    @Test
    fun settingsValidation_minFrequencyRange_upperBound_isSliderMax() {
        val validation = read("backup/SettingsValidation.kt")
        val m = Regex("""autocorrect_confidence_min_frequency"\s*->\s*value in 100\.\.(\d+)""").find(validation)
        assertWithMessage("SettingsValidation min-frequency accepted range upper bound")
            .that(m?.groupValues?.get(1)?.toInt())
            .isEqualTo(FrequencyFloor.SLIDER_MAX)
    }
}
