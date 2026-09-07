package tribixbite.cleverkeys.theme

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb

/**
 * Pref-coherence policy for custom-theme mutations (audit 2026-09-06, H-2 + H-4).
 *
 * The `theme`/`swipe_trail_color` preferences and the custom-theme store live in
 * DIFFERENT prefs files (`custom_keyboard_themes` vs the DirectBootAware settings), so
 * mutating a custom theme fires no config listener and nothing keeps the two coherent.
 * Two incoherences shipped:
 *
 *  - **H-2 (P1)**: deleting the ACTIVE custom theme left `theme=custom_<uuid>` dangling;
 *    the next Keyboard2View inflation threw from ThemeProvider — an IME crash loop until
 *    the pref changed, with no working keyboard to change it. [deleteCustomTheme] resets
 *    the pref to [ThemeProvider.FALLBACK_THEME_ID] BEFORE deleting, so no dangling
 *    window exists. Deletion always proceeds (kinder than blocking: the user is never
 *    forced to select another theme first; the keyboard simply falls back to the
 *    default). ThemeProvider's own fallback covers ids that dangle by other routes
 *    (backup restore, direct pref edits).
 *
 *  - **H-4 (un-deferred mechanical half)**: `swipe_trail_color` synced only in
 *    `onThemeSelected`, so editing the ACTIVE theme's swipe-trail colour was a silent
 *    no-op until the theme was re-selected. [saveCustomTheme] re-syncs the pref when the
 *    saved theme is the active one.
 *
 * Writes use commit() (not apply()): the caller surface (ThemeSettingsActivity) kills
 * its process right after theme-selection writes, and these writes must never be lost
 * to a kill that lands between apply() and the async flush.
 */
object CustomThemePrefPolicy {

    const val THEME_PREF_KEY = "theme"
    const val SWIPE_TRAIL_PREF_KEY = "swipe_trail_color"

    /**
     * [ThemeProvider.FALLBACK_THEME_ID]'s swipe-trail colour (getBuiltInColorScheme
     * "cleverkeysdark": silver). Drift-pinned against getColorScheme by
     * CustomThemePrefPolicyTest.
     */
    const val FALLBACK_SWIPE_TRAIL_COLOR = 0xFFC0C0C0.toInt()

    /** The `theme` pref value that selects the custom theme with raw store id [rawThemeId]. */
    fun themePrefId(rawThemeId: String): String = "custom_$rawThemeId"

    /**
     * H-2: delete the custom theme [rawThemeId] (raw store id, no `custom_` prefix).
     * When it is the active theme, the `theme`/`swipe_trail_color` prefs are reset to the
     * fallback FIRST, so `theme` never dangles on a `custom_<uuid>` without a store entry.
     *
     * @return true when the deleted theme WAS the active one (caller refreshes UI state).
     */
    @SuppressLint("ApplySharedPref")
    fun deleteCustomTheme(
        prefs: SharedPreferences,
        defaultPrefs: SharedPreferences?,
        themeManager: CustomThemeManager,
        rawThemeId: String,
    ): Boolean {
        val wasActive = prefs.getString(THEME_PREF_KEY, null) == themePrefId(rawThemeId)
        if (wasActive) {
            resetToFallback(prefs)
            defaultPrefs?.let { resetToFallback(it) }
        }
        themeManager.deleteCustomTheme(rawThemeId)
        return wasActive
    }

    /**
     * H-4: save [theme]; when it is the ACTIVE theme, re-sync `swipe_trail_color` from
     * its (possibly just-edited) scheme — otherwise the one wired scheme field goes
     * stale until the theme is re-selected.
     *
     * @return true when the save succeeded AND the trail pref was re-synced (active edit).
     */
    @SuppressLint("ApplySharedPref")
    fun saveCustomTheme(
        prefs: SharedPreferences,
        defaultPrefs: SharedPreferences?,
        themeManager: CustomThemeManager,
        theme: CustomTheme,
    ): Boolean {
        val saved = themeManager.saveCustomTheme(theme)
        val isActive = prefs.getString(THEME_PREF_KEY, null) == themePrefId(theme.id)
        if (saved && isActive) {
            val trail = theme.colors.swipeTrail.toArgb()
            prefs.edit().putInt(SWIPE_TRAIL_PREF_KEY, trail).commit()
            defaultPrefs?.edit()?.putInt(SWIPE_TRAIL_PREF_KEY, trail)?.commit()
        }
        return saved && isActive
    }

    @SuppressLint("ApplySharedPref")
    private fun resetToFallback(prefs: SharedPreferences) {
        prefs.edit()
            .putString(THEME_PREF_KEY, ThemeProvider.FALLBACK_THEME_ID)
            .putInt(SWIPE_TRAIL_PREF_KEY, FALLBACK_SWIPE_TRAIL_COLOR)
            .commit()
    }
}
