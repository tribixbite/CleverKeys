package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Code-quality audit 2026-10-20, H2: there were two theme-name → style maps.
 * `Config.getThemeId` (the keyboard view's context theme) resolved "Monet (Auto)" from the
 * system light/dark mode, while `Config.getThemeStyleId` (ThemeProvider: key magnifier,
 * Short Swipes preview) always answered MonetDark — so in light mode the previews were dark
 * under a light keyboard. Unset and unknown names also fell back differently.
 *
 * Now both read [Config.builtInThemeStyleId]; this pins its behaviour. The SDK is injected
 * because host tests can leave `Build.VERSION.SDK_INT` at any value (CLAUDE.md).
 *
 * Fail-first: this class did not compile before the fix (no light/dark input existed on the
 * ThemeProvider path).
 */
class ThemeStyleMapTest {

    private val api31 = 31
    private val api30 = 30

    @Test
    fun monetAuto_followsTheSystem_onAndroid12AndLater() {
        assertWithMessage("Monet (Auto) in light mode")
            .that(Config.builtInThemeStyleId("monet", systemLight = true, sdk = api31)).isEqualTo(R.style.MonetLight)
        assertWithMessage("Monet (Auto) in dark mode")
            .that(Config.builtInThemeStyleId("monet", systemLight = false, sdk = api31)).isEqualTo(R.style.MonetDark)
    }

    @Test
    fun monet_fallsBackToPlainLightAndDark_beforeAndroid12() {
        assertThat(Config.builtInThemeStyleId("monet", systemLight = true, sdk = api30)).isEqualTo(R.style.Light)
        assertThat(Config.builtInThemeStyleId("monet", systemLight = false, sdk = api30)).isEqualTo(R.style.Dark)
        assertThat(Config.builtInThemeStyleId("monetlight", systemLight = false, sdk = api30)).isEqualTo(R.style.Light)
        assertThat(Config.builtInThemeStyleId("monetdark", systemLight = true, sdk = api30)).isEqualTo(R.style.Dark)
    }

    @Test
    fun everyExplicitBuiltInTheme_ignoresTheSystemMode() {
        for (id in ThemeProvider.BUILT_IN_THEME_IDS - "monet") {
            for (sdk in listOf(api30, api31)) {
                assertWithMessage("$id on API $sdk must not depend on light/dark mode")
                    .that(Config.builtInThemeStyleId(id, systemLight = true, sdk = sdk))
                    .isEqualTo(Config.builtInThemeStyleId(id, systemLight = false, sdk = sdk))
            }
        }
        assertThat(Config.builtInThemeStyleId("monetlight", systemLight = false, sdk = api31)).isEqualTo(R.style.MonetLight)
        assertThat(Config.builtInThemeStyleId("monetdark", systemLight = true, sdk = api31)).isEqualTo(R.style.MonetDark)
    }

    @Test
    fun unsetAndUnknownNames_followTheSystem() {
        assertThat(Config.builtInThemeStyleId("", systemLight = true, sdk = api31)).isEqualTo(R.style.CleverKeysLight)
        assertThat(Config.builtInThemeStyleId("", systemLight = false, sdk = api31)).isEqualTo(R.style.CleverKeysDark)
        assertThat(Config.builtInThemeStyleId("no_such_theme", systemLight = true, sdk = api31)).isEqualTo(R.style.Light)
        assertThat(Config.builtInThemeStyleId("no_such_theme", systemLight = false, sdk = api31)).isEqualTo(R.style.Dark)
    }

    @Test
    fun runtimeThemes_takeTheBaseStyle() {
        for (light in listOf(true, false)) {
            assertThat(Config.builtInThemeStyleId("decorative_neon_hot_pink", light, api31)).isEqualTo(R.style.CleverKeysDark)
            assertThat(Config.builtInThemeStyleId("custom_1234", light, api31)).isEqualTo(R.style.CleverKeysDark)
        }
    }
}
