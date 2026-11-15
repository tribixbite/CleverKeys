package tribixbite.cleverkeys.theme

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.toArgb
import tribixbite.cleverkeys.R

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
     * The swipe-trail pref written with [ThemeProvider.FALLBACK_THEME_ID]: the untouched
     * default (`Defaults.SWIPE_TRAIL_COLOR`), so the fallback look draws its own light/dark trail
     * instead of yielding to the deleted theme's (`LookUserYield.trailColor`). Pinned by
     * CustomThemePrefPolicyTest.
     */
    const val FALLBACK_SWIPE_TRAIL_COLOR = tribixbite.cleverkeys.Defaults.SWIPE_TRAIL_COLOR

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
     * What [saveCustomTheme] did (review 2026-11-14 M21). A single Boolean ("saved AND active")
     * made "saved, not active" and "not saved" look the same, so the Theme Creator closed on a
     * failed save (the 50-theme limit, or a store write that threw) and the theme was lost.
     *
     * @property saved the store kept the theme.
     * @property activeTrailSynced it is the active theme and `swipe_trail_color` was re-synced.
     */
    data class SaveResult(val saved: Boolean, val activeTrailSynced: Boolean)

    /**
     * H-4: save [theme]; when it is the ACTIVE theme, re-sync `swipe_trail_color` from
     * its (possibly just-edited) scheme — otherwise the one wired scheme field goes
     * stale until the theme is re-selected.
     */
    @SuppressLint("ApplySharedPref")
    fun saveCustomTheme(
        prefs: SharedPreferences,
        defaultPrefs: SharedPreferences?,
        themeManager: CustomThemeManager,
        theme: CustomTheme,
    ): SaveResult {
        val saved = themeManager.saveCustomTheme(theme)
        val isActive = prefs.getString(THEME_PREF_KEY, null) == themePrefId(theme.id)
        if (saved && isActive) {
            val trail = theme.colors.swipeTrail.toArgb()
            prefs.edit().putInt(SWIPE_TRAIL_PREF_KEY, trail).commit()
            defaultPrefs?.edit()?.putInt(SWIPE_TRAIL_PREF_KEY, trail)?.commit()
        }
        return SaveResult(saved = saved, activeTrailSynced = saved && isActive)
    }

    /** Review 2026-11-14 M21: Theme Manager offers "+" only while the store can take another theme. */
    fun canCreate(customThemeCount: Int): Boolean = customThemeCount < CustomThemeManager.MAX_CUSTOM_THEMES

    /**
     * The message for a save the store refused (review 2026-11-14 M21): a NEW theme at the limit
     * names the limit; anything else (an edit replaces its own entry, so it never hits the limit)
     * is a failed write.
     */
    @StringRes
    fun saveFailureMessage(isNew: Boolean, customThemeCount: Int): Int =
        if (isNew && !canCreate(customThemeCount)) R.string.theme_limit_reached else R.string.theme_save_failed

    /**
     * The name a Theme Creator draft is saved under: trimmed, or null when blank (low, review
     * 2026-11-14: a blank name was stored and showed as a card with no title). Save is disabled
     * while this is null.
     */
    fun normalizedName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    @SuppressLint("ApplySharedPref")
    private fun resetToFallback(prefs: SharedPreferences) {
        prefs.edit()
            .putString(THEME_PREF_KEY, ThemeProvider.FALLBACK_THEME_ID)
            .putInt(SWIPE_TRAIL_PREF_KEY, FALLBACK_SWIPE_TRAIL_COLOR)
            .commit()
    }
}
