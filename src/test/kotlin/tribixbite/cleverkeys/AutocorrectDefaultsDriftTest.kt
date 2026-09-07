package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.autocorrect.FrequencyFloor
import tribixbite.cleverkeys.backup.SettingsValidation
import java.io.File

/**
 * Drift detection for autocorrect setting defaults & slider ranges (AC-1).
 *
 * The single source of truth is `Config.Defaults` (the values `Config.refresh`
 * reads into the live config) and `FrequencyFloor.SLIDER_{MIN,MAX}` (the domain
 * the min-frequency slider is actually mapped over). The historical drift:
 * `AutoCorrectionSection` + `SettingsValidation` used a `100..5000` slider
 * range while `FrequencyFloor.SLIDER_MAX = 2000`, so values 2001-5000 silently
 * clamped to the same floor as 2000. (A second drifted surface, the standalone
 * `AutoCorrectionSettingsActivity`, was unreachable dead code and was DELETED
 * in audit item F-9, 2026-09-06 — `SettingsSurfaceDriftTest` now pins that
 * every SettingsNavigation helper has a caller so a dead screen cannot rot
 * unnoticed again.)
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

    /** F-9: the dead activity stays dead — no file may reintroduce it. */
    @Test
    fun autoCorrectionSettingsActivity_staysDeleted() {
        assertWithMessage("AutoCorrectionSettingsActivity was deleted in F-9 (unreachable, drifted ranges)")
            .that(File(srcRoot, "activities/AutoCorrectionSettingsActivity.kt").exists())
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
        assertWithMessage("SettingsValidation min-frequency accepted range upper bound")
            .that(SettingsValidation.intRangeFor("autocorrect_confidence_min_frequency")?.last)
            .isEqualTo(FrequencyFloor.SLIDER_MAX)
    }
}
