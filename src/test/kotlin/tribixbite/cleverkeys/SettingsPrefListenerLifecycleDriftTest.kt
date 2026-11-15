package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Review 2026-11-14 M24: the keyboard's `toggle_scene_motion` / `toggle_true_black` keys (and
 * `theme_next`) write their prefs while Settings and Theme Manager sit paused behind it. Settings
 * unregistered its preference listener in `onPause` and `onResume` re-read only some state, and
 * Theme Manager read `theme` / `look_scene_true_black` once: both showed the old switch state.
 *
 * Source scan (activity lifecycles cannot be driven on the JVM). Covers: Settings keeps its
 * listener from create to destroy; Theme Manager's screen listens to the two prefs it shows. Not
 * covered: the delivery itself (SharedPreferences' own listener contract).
 */
class SettingsPrefListenerLifecycleDriftTest {

    private val settings = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt")
    private val themeSettings = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt")

    @Test
    fun settings_listensFromCreateToDestroy() {
        val onCreate = SourceScan.functionBody(settings, "override fun onCreate")
        val onPause = SourceScan.functionBody(settings, "override fun onPause")
        val onDestroy = SourceScan.functionBody(settings, "override fun onDestroy")
        assertWithMessage("onCreate must register the preference listener")
            .that(onCreate.contains("registerOnSharedPreferenceChangeListener(this)")).isTrue()
        assertWithMessage("onPause must not unregister it: changes made while paused were missed")
            .that(onPause.contains("unregisterOnSharedPreferenceChangeListener")).isFalse()
        assertWithMessage("onDestroy must unregister it")
            .that(onDestroy.contains("unregisterOnSharedPreferenceChangeListener(this)")).isTrue()
    }

    @Test
    fun themeManager_followsThemeAndTrueBlackChangedElsewhere() {
        val screen = SourceScan.functionBody(themeSettings, "fun ThemeSettingsScreen(")
        assertWithMessage("ThemeSettingsScreen must listen for preference changes")
            .that(screen.contains("registerOnSharedPreferenceChangeListener(")).isTrue()
        assertWithMessage("…and unregister on dispose")
            .that(screen.contains("unregisterOnSharedPreferenceChangeListener(")).isTrue()
        assertWithMessage("…re-reading the theme selection")
            .that(screen.contains("CustomThemePrefPolicy.THEME_PREF_KEY ->")).isTrue()
        assertWithMessage("…and True Black Scenes")
            .that(screen.contains("\"look_scene_true_black\" ->")).isTrue()
    }
}
