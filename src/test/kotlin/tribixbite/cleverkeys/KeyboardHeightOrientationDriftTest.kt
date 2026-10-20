package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-scan drift test for orientation-scoped keyboard height — guards against
 * re-introducing the bug from GitHub issue #161 ("changing the portrait mode height
 * changes the height for both landscape and portrait modes").
 *
 * Root cause of #161: `updateConfigFromSettings()` in SettingsPersistence.kt contained
 * `keyboardHeightPercent = keyboardHeight`, which runs after EVERY `saveSetting()` call.
 * `keyboardHeight` is the Settings activity's PORTRAIT slider state, so the assignment
 * stomped the orientation-resolved value that `Config.refresh()` (fired synchronously by
 * the ConfigurationManager SharedPreferences listener during the same `apply()`) had just
 * computed. Net effect while the device was in landscape: the portrait slider visibly
 * resized the keyboard and the landscape slider appeared dead — its pref write was
 * correct, but the portrait stomp always won the race by running last.
 *
 * This is the same bug shape as #154 (`vibrate_custom = vibrationEnabled` in the same
 * function, pinned by [HapticsBehaviorDriftTest]); the authoritative writer for
 * orientation-scoped fields is `Config.refresh()`, never the settings-side mirror.
 *
 * Structural invariants the compiler cannot enforce, so source scanning it is
 * (idiom of [AutocorrectDefaultsDriftTest] / [HapticsBehaviorDriftTest]).
 */
class KeyboardHeightOrientationDriftTest {

    private val srcRoot = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun read(rel: String) = File(srcRoot, rel).readText()

    /**
     * THE #161 pin: `updateConfigFromSettings()` must NOT assign the portrait slider
     * state into `keyboardHeightPercent`. That field is orientation-resolved (portrait /
     * landscape / their unfolded variants) and only `Config.refresh()` knows which of the
     * four pref keys applies right now.
     */
    @Test
    fun updateConfigFromSettings_doesNotStompOrientedKeyboardHeight() {
        val src = read("ui/settings/SettingsPersistence.kt")

        val fnStart = src.indexOf("fun SettingsActivity.updateConfigFromSettings()")
        assertWithMessage("updateConfigFromSettings function must exist in SettingsPersistence.kt")
            .that(fnStart).isGreaterThan(-1)

        val relevantSection = src.substring(fnStart, minOf(fnStart + 3000, src.length))

        assertWithMessage(
            "updateConfigFromSettings() must not assign `keyboardHeightPercent = keyboardHeight`.\n" +
            "keyboardHeight is the PORTRAIT slider state; keyboardHeightPercent is the\n" +
            "orientation-resolved value Config.refresh() just derived from the correct pref key\n" +
            "(keyboard_height / keyboard_height_landscape / *_unfolded). The assignment runs after\n" +
            "the pref listener's refresh on every saveSetting() call, so in landscape it made the\n" +
            "portrait slider control the keyboard and the landscape slider appear dead (bug #161)."
        ).that(
            relevantSection.contains("keyboardHeightPercent = keyboardHeight")
        ).isFalse()
    }

    /**
     * Green pin: Config.refresh()'s landscape branch resolves the landscape height and the
     * portrait branch the portrait height, through the one shared rule
     * ([KeyboardHeightPrefs.percent], whose key mapping `KeyboardHeightPrefsTest` pins) with the
     * live fold state — the two orientations stay independent stores (#161), and an unset
     * inner-screen key follows the outer screen (foldable report, 2026-10-20).
     */
    @Test
    fun configRefresh_resolvesOrientationScopedHeightThroughTheSharedRule() {
        val src = read("Config.kt")

        val landscapeBranch = src.indexOf("if (orientation_landscape) {")
        assertWithMessage("Config.refresh must branch on orientation_landscape")
            .that(landscapeBranch).isGreaterThan(-1)
        val section = src.substring(landscapeBranch, minOf(landscapeBranch + 600, src.length))
        assertWithMessage("landscape branch must resolve landscape = true with the fold state")
            .that(section.contains("KeyboardHeightPrefs.percent(_prefs, landscape = true, unfolded = this.foldable_unfolded)"))
            .isTrue()
        assertWithMessage("portrait branch must resolve landscape = false with the fold state")
            .that(section.contains("KeyboardHeightPrefs.percent(_prefs, landscape = false, unfolded = this.foldable_unfolded)"))
            .isTrue()
        assertWithMessage("KeyboardDimensionsHelper must use the shared rule, not its own key literals")
            .that(read("KeyboardDimensionsHelper.kt").contains("\"keyboard_height")).isFalse()
    }

    /**
     * Green pin: the two Appearance sliders persist to two distinct pref keys. If they ever
     * collapse onto one key the orientations stop being independent at the store level.
     */
    @Test
    fun appearanceSliders_writeDistinctOrientationKeys() {
        val src = read("ui/settings/sections/AppearanceSection.kt")

        assertWithMessage("portrait slider must save \"keyboard_height\"")
            .that(src.contains("saveSetting(\"keyboard_height\", keyboardHeight)")).isTrue()
        assertWithMessage("landscape slider must save \"keyboard_height_landscape\"")
            .that(src.contains("saveSetting(\"keyboard_height_landscape\", keyboardHeightLandscape)")).isTrue()
        assertWithMessage("inner-screen portrait slider must save keyboard_height_unfolded")
            .that(src.contains("saveSetting(KeyboardHeightPrefs.PORTRAIT_UNFOLDED, keyboardHeightUnfolded)")).isTrue()
        assertWithMessage("inner-screen landscape slider must save keyboard_height_landscape_unfolded")
            .that(src.contains("saveSetting(KeyboardHeightPrefs.LANDSCAPE_UNFOLDED, keyboardHeightLandscapeUnfolded)")).isTrue()
    }
}
