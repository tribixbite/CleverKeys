package tribixbite.cleverkeys.theme

import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import org.junit.Test

/**
 * Audit 2026-09-06, H-2 (activity layer) + H-4 (mechanical half): custom-theme mutations
 * in ThemeSettingsActivity must keep the `theme`/`swipe_trail_color` prefs coherent.
 *
 *  - H-2: the delete-confirm button called `themeManager.deleteCustomTheme(themeId)`
 *    directly, with no active-theme guard — deleting the selected theme left
 *    `theme=custom_<uuid>` dangling (IME crash loop pre-ThemeProviderFallback, silent
 *    wrong-theme state after). The delete path must route through
 *    [CustomThemePrefPolicy.deleteCustomTheme], which resets the theme pref to the
 *    fallback BEFORE deleting when (and only when) the deleted theme is active.
 *  - H-4 (un-deferred half): the `swipe_trail_color` pref sync ran only in
 *    `onThemeSelected`, so editing the ACTIVE theme's swipe-trail colour was a silent
 *    no-op until the theme was re-selected. Both save paths (create + edit) must route
 *    through [CustomThemePrefPolicy.saveCustomTheme], which re-syncs the trail pref when
 *    the saved theme is the active one.
 *
 * The wiring is pinned by source scan (the delete/save sites live inside composable
 * lambdas, which cannot be driven on the JVM); the policy behaviour itself is pinned by
 * the mock-backed tests below.
 */
class CustomThemePrefPolicyTest {

    private val activitySource =
        File("src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt")

    // ------------------------------------------------------------------ wiring (source scan)

    /** H-2 red: pre-fix the confirm button calls `themeManager.deleteCustomTheme` directly. */
    @Test
    fun deleteConfirm_routesThroughTheGuardedPolicy() {
        val src = activitySource.readText()
        assertWithMessage(
            "ThemeSettingsActivity's delete-confirm path must call " +
                "CustomThemePrefPolicy.deleteCustomTheme — a direct " +
                "themeManager.deleteCustomTheme() leaves the `theme` pref dangling when " +
                "the active custom theme is deleted (audit H-2, IME crash loop)."
        ).that(src.contains("CustomThemePrefPolicy.deleteCustomTheme(")).isTrue()
        assertWithMessage(
            "the unguarded direct delete call must be gone from ThemeSettingsActivity"
        ).that(src.contains("themeManager.deleteCustomTheme(")).isFalse()
    }

    /** H-4 red: pre-fix both dialog onSave lambdas call `themeManager.saveCustomTheme`. */
    @Test
    fun themeSaves_routeThroughTheTrailSyncingPolicy() {
        val src = activitySource.readText()
        assertWithMessage(
            "ThemeSettingsActivity's create/edit save paths must call " +
                "CustomThemePrefPolicy.saveCustomTheme so editing the ACTIVE theme " +
                "re-syncs swipe_trail_color (audit H-4 — sync previously ran only at " +
                "select time, so active-theme edits went stale)."
        ).that(src.contains("CustomThemePrefPolicy.saveCustomTheme(")).isTrue()
        assertWithMessage(
            "the direct themeManager.saveCustomTheme() calls must be gone"
        ).that(src.contains("themeManager.saveCustomTheme(")).isFalse()
    }

    /**
     * Review 2026-11-14 M21 wiring (source scan; the dialogs' lambdas cannot be driven on the
     * JVM). Before the fix the create path closed the dialog whatever the store answered and the
     * edit path ignored the result. Covers: both paths look up a failure message, "+" is gated on
     * [CustomThemePrefPolicy.canCreate], Save on the normalized name. Does NOT cover that the
     * toast actually shows or that the dialog stays composed — that needs a device.
     */
    @Test
    fun bothSavePaths_reportAFailureAndKeepTheDialog() {
        val src = activitySource.readText()
        assertWithMessage("create AND edit must each show CustomThemePrefPolicy.saveFailureMessage on failure")
            .that(Regex(Regex.escape("CustomThemePrefPolicy.saveFailureMessage(")).findAll(src).count())
            .isEqualTo(2)
        assertWithMessage("the create (+) button must be disabled at the custom-theme limit")
            .that(src.contains("enabled = CustomThemePrefPolicy.canCreate(customThemes.size)")).isTrue()
        assertWithMessage("the creator's Save must use the normalized (non-blank, trimmed) name")
            .that(src.contains("CustomThemePrefPolicy.normalizedName(themeName)")).isTrue()
    }

    /** Low (review 2026-11-14): the decorative cards' toast passed the English `name`. */
    @Test
    fun decorativeThemeToast_usesTheLocalizedName() {
        val src = activitySource.readText()
        assertWithMessage("onThemeSelected for a decorative theme must pass displayName(resources), not the English name")
            .that(src.contains("onThemeSelected(decorativeId, themeInfo.name)")).isFalse()
    }

    // ------------------------------------------------------------------ fixtures

    /** A recording SharedPreferences whose editor chains onto itself. */
    private class PrefsFixture(activeThemeValue: String?) {
        val editor: SharedPreferences.Editor = mockk {
            every { putString(any(), any()) } returns this@mockk
            every { putInt(any(), any()) } returns this@mockk
            every { commit() } returns true
        }
        val prefs: SharedPreferences = mockk {
            every { getString("theme", null) } returns activeThemeValue
            every { edit() } returns editor
        }
    }

    private fun customTheme(id: String, trail: Long = 0xFF112233): CustomTheme = CustomTheme(
        id = id,
        name = "t-$id",
        colors = darkKeyboardColorScheme().copy(
            swipeTrail = androidx.compose.ui.graphics.Color(trail)
        ),
    )

    // ------------------------------------------------------------------ H-2: delete guard

    @Test
    fun deletingTheActiveTheme_resetsTheThemePrefBeforeDeleting() {
        val fx = PrefsFixture("custom_abc")
        val manager = mockk<CustomThemeManager> { every { deleteCustomTheme("abc") } returns true }

        val wasActive = CustomThemePrefPolicy.deleteCustomTheme(fx.prefs, null, manager, "abc")

        assertThat(wasActive).isTrue()
        // The reset must land BEFORE the store delete — no window with a dangling pref.
        verifyOrder {
            fx.editor.putString("theme", ThemeProvider.FALLBACK_THEME_ID)
            fx.editor.commit()
            manager.deleteCustomTheme("abc")
        }
        verify { fx.editor.putInt("swipe_trail_color", CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR) }
    }

    @Test
    fun deletingAnInactiveTheme_touchesNoPrefs() {
        val fx = PrefsFixture("custom_other")
        val manager = mockk<CustomThemeManager> { every { deleteCustomTheme("abc") } returns true }

        val wasActive = CustomThemePrefPolicy.deleteCustomTheme(fx.prefs, null, manager, "abc")

        assertThat(wasActive).isFalse()
        verify(exactly = 0) { fx.prefs.edit() }
        verify(exactly = 1) { manager.deleteCustomTheme("abc") }
    }

    @Test
    fun deletingTheActiveTheme_alsoResetsTheDefaultPrefsCopy() {
        val fx = PrefsFixture("custom_abc")
        val defaults = PrefsFixture(null)
        val manager = mockk<CustomThemeManager> { every { deleteCustomTheme("abc") } returns true }

        CustomThemePrefPolicy.deleteCustomTheme(fx.prefs, defaults.prefs, manager, "abc")

        verify { defaults.editor.putString("theme", ThemeProvider.FALLBACK_THEME_ID) }
    }

    // ------------------------------------------------------------------ H-4: save sync

    @Test
    fun savingTheActiveTheme_resyncsTheSwipeTrailPref() {
        val theme = customTheme("abc", trail = 0xFF112233)
        val fx = PrefsFixture("custom_abc")
        val manager = mockk<CustomThemeManager> { every { saveCustomTheme(theme) } returns true }

        val result = CustomThemePrefPolicy.saveCustomTheme(fx.prefs, null, manager, theme)

        assertThat(result.saved).isTrue()
        assertThat(result.activeTrailSynced).isTrue()
        verify { fx.editor.putInt("swipe_trail_color", theme.colors.swipeTrail.toArgb()) }
        verify { fx.editor.commit() }
    }

    @Test
    fun savingAnInactiveTheme_touchesNoPrefs() {
        val theme = customTheme("abc")
        val fx = PrefsFixture("custom_other")
        val manager = mockk<CustomThemeManager> { every { saveCustomTheme(theme) } returns true }

        val result = CustomThemePrefPolicy.saveCustomTheme(fx.prefs, null, manager, theme)

        // Review 2026-11-14 M21: "saved, not active" must read differently from "not saved".
        assertThat(result.saved).isTrue()
        assertThat(result.activeTrailSynced).isFalse()
        verify(exactly = 0) { fx.prefs.edit() }
        verify(exactly = 1) { manager.saveCustomTheme(theme) }
    }

    @Test
    fun aFailedSave_neverSyncsThePref() {
        val theme = customTheme("abc")
        val fx = PrefsFixture("custom_abc")
        val manager = mockk<CustomThemeManager> { every { saveCustomTheme(theme) } returns false }

        val result = CustomThemePrefPolicy.saveCustomTheme(fx.prefs, null, manager, theme)

        assertThat(result.saved).isFalse()
        assertThat(result.activeTrailSynced).isFalse()
        verify(exactly = 0) { fx.prefs.edit() }
    }

    // ------------------------------------------------------------------ review 2026-11-14 M21

    /** A failed save of an INACTIVE theme used to read like a successful one (both `false`). */
    @Test
    fun aFailedSaveOfAnInactiveTheme_reportsNotSaved() {
        val theme = customTheme("abc")
        val fx = PrefsFixture("custom_other")
        val manager = mockk<CustomThemeManager> { every { saveCustomTheme(theme) } returns false }

        assertThat(CustomThemePrefPolicy.saveCustomTheme(fx.prefs, null, manager, theme).saved).isFalse()
    }

    @Test
    fun createIsOfferedOnlyBelowTheStoreLimit() {
        assertThat(CustomThemePrefPolicy.canCreate(0)).isTrue()
        assertThat(CustomThemePrefPolicy.canCreate(CustomThemeManager.MAX_CUSTOM_THEMES - 1)).isTrue()
        assertThat(CustomThemePrefPolicy.canCreate(CustomThemeManager.MAX_CUSTOM_THEMES)).isFalse()
    }

    @Test
    fun failureMessage_namesTheLimitOnlyForANewThemeAtTheLimit() {
        val max = CustomThemeManager.MAX_CUSTOM_THEMES
        assertThat(CustomThemePrefPolicy.saveFailureMessage(isNew = true, customThemeCount = max))
            .isEqualTo(tribixbite.cleverkeys.R.string.theme_limit_reached)
        assertThat(CustomThemePrefPolicy.saveFailureMessage(isNew = true, customThemeCount = max - 1))
            .isEqualTo(tribixbite.cleverkeys.R.string.theme_save_failed)
        // An edit replaces its own entry: the limit never applies to it.
        assertThat(CustomThemePrefPolicy.saveFailureMessage(isNew = false, customThemeCount = max))
            .isEqualTo(tribixbite.cleverkeys.R.string.theme_save_failed)
    }

    /** Low (review 2026-11-14): a blank name was stored as a nameless card. */
    @Test
    fun blankNamesAreNotSaveable_otherNamesAreTrimmed() {
        assertThat(CustomThemePrefPolicy.normalizedName("")).isNull()
        assertThat(CustomThemePrefPolicy.normalizedName("   ")).isNull()
        assertThat(CustomThemePrefPolicy.normalizedName("  Ocean ")).isEqualTo("Ocean")
    }

    // ------------------------------------------------------------------ drift pin

    /** The hardcoded fallback trail colour must track the fallback theme's scheme. */
    /**
     * The fallback is the CleverKeys look (2026-11-06), which yields to a trail colour that
     * differs from the default: resetting to the default lets the look draw its own trail.
     */
    @Test
    fun fallbackTrailColour_letsTheFallbackLookDrawItsOwnTrail() {
        assertThat(ThemeProvider.FALLBACK_THEME_ID).isEqualTo(KeyboardLooks.CLEVERKEYS)
        assertThat(KeyboardLooks.cleverkeysLook.yieldsToUserSettings).isTrue()
        assertThat(CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR)
            .isEqualTo(tribixbite.cleverkeys.Defaults.SWIPE_TRAIL_COLOR)
        assertThat(LookUserYield.trailColor(0xFF123456.toInt(), CustomThemePrefPolicy.FALLBACK_SWIPE_TRAIL_COLOR,
            tribixbite.cleverkeys.Defaults.SWIPE_TRAIL_COLOR)).isEqualTo(0xFF123456.toInt())
    }
}
