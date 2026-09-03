package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Release-record guard for "**Nav bar icons on Android 8-9 light themes (#1116)**", published
 * in v1.2.6 and re-published in v1.2.8 (`docs/RELEASE_RECORD.md`).
 *
 * The IME window draws behind the system bars, so with a TRANSPARENT navigation-bar colour a
 * light theme leaves white icons on a white background. API 29+ handles this itself
 * (contrast enforcement off, `isAppearanceLightNavigationBars`); API 26-28 has only the legacy
 * `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR` bit; below API 26 the flag does not exist at all, so
 * the only remedy there is to stop being transparent.
 *
 * `Keyboard2View.refresh_navigation_bar` cannot run off-device (`Window`, `decorView`,
 * `systemUiVisibility`), so the decision table it applies lives in [NavBarAppearance] and is
 * pinned here.
 */
class ReleaseClaimNavBarTest {

    /** Android 8.0, 8.1 and 9 — the versions named in the release note. */
    private val android8to9 = listOf(26, 27, 28)

    // ------------------------------------------------------------------- the #1116 fix itself

    @Test
    fun `Android 8-9 with a light theme paints the theme colour and asks for dark icons`() {
        for (sdk in android8to9) {
            val decision = NavBarAppearance.decide(sdk, isLightNavBar = true)

            assertWithMessage("API $sdk light theme must abandon the transparent nav bar")
                .that(decision.useThemeNavBarColor).isTrue()
            assertWithMessage(
                "API $sdk light theme must set SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR so the " +
                    "icons render dark — this is #1116"
            ).that(decision.legacyLightNavBarFlag).isTrue()

            assertThat(decision.disableContrastEnforcement).isFalse()
            assertThat(decision.useInsetsController).isFalse()
        }
    }

    @Test
    fun `Android 8-9 with a dark theme keeps the transparent bar and clears the light flag`() {
        for (sdk in android8to9) {
            val decision = NavBarAppearance.decide(sdk, isLightNavBar = false)

            assertWithMessage("API $sdk dark theme keeps the transparent nav bar")
                .that(decision.useThemeNavBarColor).isFalse()
            assertWithMessage(
                "API $sdk must actively CLEAR the light flag — systemUiVisibility is sticky, " +
                    "so a stale flag from a previously-selected light theme would leave dark " +
                    "icons on a dark bar"
            ).that(decision.legacyLightNavBarFlag).isFalse()
        }
    }

    // ------------------------------------------------------------------------ the other eras

    @Test
    fun `below Android 8 there is no light-navigation-bar flag to touch`() {
        for (sdk in 21..25) {
            for (light in listOf(true, false)) {
                assertWithMessage("API $sdk (light=$light) predates the flag")
                    .that(NavBarAppearance.decide(sdk, light).legacyLightNavBarFlag).isNull()
            }
            assertWithMessage("API $sdk light theme still avoids a transparent bar")
                .that(NavBarAppearance.decide(sdk, true).useThemeNavBarColor).isTrue()
            assertThat(NavBarAppearance.decide(sdk, false).useThemeNavBarColor).isFalse()
        }
    }

    @Test
    fun `Android 10 and up go transparent and let the framework handle contrast`() {
        for (sdk in listOf(29, 30, 33, 36)) {
            for (light in listOf(true, false)) {
                val decision = NavBarAppearance.decide(sdk, light)
                assertWithMessage("API $sdk (light=$light) is transparent again")
                    .that(decision.useThemeNavBarColor).isFalse()
                assertWithMessage("API $sdk (light=$light) leaves the legacy flags alone")
                    .that(decision.legacyLightNavBarFlag).isNull()
                assertWithMessage("API $sdk (light=$light) disables contrast enforcement")
                    .that(decision.disableContrastEnforcement).isTrue()
            }
        }
    }

    @Test
    fun `the insets controller takes over from Android 11`() {
        for (sdk in 21..29) {
            assertWithMessage("API $sdk has no WindowInsetsController")
                .that(NavBarAppearance.decide(sdk, true).useInsetsController).isFalse()
        }
        for (sdk in listOf(30, 31, 34, 36)) {
            assertWithMessage("API $sdk uses WindowInsetsController")
                .that(NavBarAppearance.decide(sdk, true).useInsetsController).isTrue()
        }
    }

    // --------------------------------------------------------------------- whole-range sweep

    @Test
    fun `across every supported API exactly one mechanism owns the icon appearance`() {
        for (sdk in 21..36) {
            for (light in listOf(true, false)) {
                val decision = NavBarAppearance.decide(sdk, light)

                val legacyOwns = decision.legacyLightNavBarFlag != null
                assertWithMessage("API $sdk (light=$light): legacy flags only on 26..28")
                    .that(legacyOwns).isEqualTo(sdk in 26..28)

                assertWithMessage("API $sdk (light=$light): contrast enforcement only from 29")
                    .that(decision.disableContrastEnforcement).isEqualTo(sdk >= 29)

                assertWithMessage("API $sdk (light=$light): insets controller only from 30")
                    .that(decision.useInsetsController).isEqualTo(sdk >= 30)

                assertWithMessage(
                    "API $sdk (light=$light): the theme colour is the pre-29 light-theme " +
                        "workaround and nothing else"
                ).that(decision.useThemeNavBarColor).isEqualTo(sdk < 29 && light)
            }
        }
    }

    @Test
    fun `the API boundaries match the platform features they gate`() {
        assertThat(NavBarAppearance.FIRST_LEGACY_LIGHT_NAV_BAR_API)
            .isEqualTo(android.os.Build.VERSION_CODES.O)
        assertThat(NavBarAppearance.FIRST_SELF_CONTRASTING_API)
            .isEqualTo(android.os.Build.VERSION_CODES.Q)
        assertThat(NavBarAppearance.FIRST_INSETS_CONTROLLER_API)
            .isEqualTo(android.os.Build.VERSION_CODES.R)
    }
}
