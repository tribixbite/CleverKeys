package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-scan drift test for haptic/vibration routing — guards against
 * re-introducing the bug from GitHub issue #154 ("Vibration delay on keypress").
 *
 * Root cause of #154: `updateConfigFromSettings()` in SettingsPersistence.kt
 * contained `vibrate_custom = vibrationEnabled`, which meant every call to
 * `saveSetting()` while vibration was on would set `vibrate_custom = true`,
 * bypassing the low-latency `performHapticFeedback` path entirely and forcing
 * all users into the slow `VibrationEffect.createOneShot` path.
 *
 * Also guarded: the master toggle in AccessibilitySection must not force
 * `vibrate_custom = true` on toggle (only slider interaction should do that).
 *
 * These are structural invariants that the compiler cannot enforce, so we
 * use source scanning (like [AutocorrectDefaultsDriftTest]) to catch regressions.
 */
class HapticsBehaviorDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun read(rel: String) = File(srcRoot, rel).readText()

    /**
     * `updateConfigFromSettings()` must NOT set `vibrate_custom = vibrationEnabled`.
     * That assignment short-circuits to the slow Vibrator path for all users regardless
     * of whether they have customized the duration.
     */
    @Test
    fun updateConfigFromSettings_doesNotMap_vibrationEnabled_to_vibrateCustom() {
        val src = read("ui/settings/SettingsPersistence.kt")

        // Find the updateConfigFromSettings function body
        val fnStart = src.indexOf("fun SettingsActivity.updateConfigFromSettings()")
        assertWithMessage("updateConfigFromSettings function must exist in SettingsPersistence.kt")
            .that(fnStart).isGreaterThan(-1)

        // Extract everything up to the closing brace of the function's config.apply block
        // (conservative: just search for the pattern in the whole function area)
        val relevantSection = src.substring(fnStart, minOf(fnStart + 3000, src.length))

        assertWithMessage(
            "updateConfigFromSettings() must not assign `vibrate_custom = vibrationEnabled`.\n" +
            "This assignment forces all users into the slow VibrationEffect.createOneShot path\n" +
            "instead of the zero-latency performHapticFeedback path (bug #154).\n" +
            "vibrate_custom should only be set to true when the user explicitly drags the duration slider."
        ).that(
            relevantSection.contains("vibrate_custom = vibrationEnabled")
        ).isFalse()
    }

    /**
     * The vibration master toggle handler in AccessibilitySection must not force
     * `vibrate_custom = true` on toggle (only the slider's onValueChange should do that).
     * Forcing it in the toggle bypasses performHapticFeedback for all users (#154).
     */
    @Test
    fun accessibilitySection_masterToggle_doesNotForce_vibrateCustom() {
        val src = read("ui/settings/sections/AccessibilitySection.kt")

        // Find the onCheckedChange block for the vibration master switch.
        // Heuristic: the block that saves "vibration_enabled" is the master toggle.
        val toggleIdx = src.indexOf("saveSetting(\"vibration_enabled\"")
        assertWithMessage("Master vibration toggle must exist in AccessibilitySection.kt")
            .that(toggleIdx).isGreaterThan(-1)

        // Look for vibrate_custom being set within ~500 chars AFTER the "vibration_enabled" save.
        // The slider handler is further down and is NOT in scope here.
        val nearToggle = src.substring(toggleIdx, minOf(toggleIdx + 600, src.length))

        assertWithMessage(
            "The vibration master toggle (saveSetting(\"vibration_enabled\", ...)) must not " +
            "also set vibrate_custom = true within the same handler block.\n" +
            "That forces the slow Vibrator path instead of performHapticFeedback (bug #154).\n" +
            "Only the duration slider's onValueChange should enable custom mode."
        ).that(
            nearToggle.contains("vibrate_custom = true")
        ).isFalse()
    }

    /**
     * The duration slider's onValueChange IS allowed to set vibrate_custom = true —
     * that's the intentional "user opted in to custom mode" path.
     * Guard that this path still exists so the slider still functions.
     */
    @Test
    fun accessibilitySection_durationSlider_setsVibrateCustom_whenDragged() {
        val src = read("ui/settings/sections/AccessibilitySection.kt")

        // The slider saves "vibrate_duration" and sets vibrate_custom = true.
        val sliderIdx = src.indexOf("saveSetting(\"vibrate_duration\"")
        assertWithMessage("Duration slider must save \"vibrate_duration\" in AccessibilitySection.kt")
            .that(sliderIdx).isGreaterThan(-1)

        // vibrate_custom = true must appear near the slider's onValueChange.
        // Window = 800 chars to accommodate inline comments added around the assignment.
        val nearSlider = src.substring(sliderIdx, minOf(sliderIdx + 800, src.length))
        assertWithMessage(
            "Duration slider onValueChange must set vibrate_custom = true " +
            "(so slider-using users still get custom duration)."
        ).that(
            nearSlider.contains("vibrate_custom = true")
        ).isTrue()
    }
}
