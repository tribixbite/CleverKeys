package tribixbite.cleverkeys.theme

import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * What one keyboard view's colours were built from. Two stamps are equal exactly when a view
 * inflated now would draw the same colours as the view that carries the older stamp.
 */
data class KeyboardViewThemeStamp(
    /** The style id `Config.theme` resolved to (0 = config not loaded yet). */
    val styleId: Int,
    /** The EFFECTIVE theme name (`Config.themeName`, after per-layout bindings). */
    val themeName: String,
    /**
     * Runtime (`custom_` / `decorative_`) themes only: the colours ThemeProvider holds for
     * [themeName] — `null` when that theme no longer exists (the view falls back). Always null
     * for XML themes, whose colours come from the style [styleId] names.
     */
    val runtimeColors: KeyboardColorScheme?,
) {
    companion object {
        /**
         * Stamp the theme a view inflated now would use. [runtimeColors] is consulted only for
         * runtime theme names (`ThemeProvider.getColorScheme`, an in-memory lookup).
         */
        fun of(
            styleId: Int,
            themeName: String,
            runtimeColors: (String) -> KeyboardColorScheme?,
        ): KeyboardViewThemeStamp = KeyboardViewThemeStamp(
            styleId = styleId,
            themeName = themeName,
            runtimeColors = if (ConfigSnapshot.isRuntimeThemeName(themeName)) runtimeColors(themeName) else null,
        )
    }
}

/**
 * Decides when the IME's keyboard view must be re-inflated because its theme changed
 * (Pixel 2026-10-25).
 *
 * The keyboard view bakes its colours in at inflation, so every appearance change needs a new
 * view. Style id and theme name changes reach `CleverKeysService.onThemeChanged` through
 * `ConfigurationManager`; an EDIT of a custom theme changes neither (the store is another prefs
 * file), so the stamp also carries the displayed runtime theme's colours. The service:
 *  - stamps every keyboard view in `inflate_view` ([onKeyboardViewInflated]);
 *  - on `ACTION_THEME_CHANGED` rebuilds at once only while the keyboard is shown and the view
 *    is stale ([shouldRebuildOnThemeBroadcast]) — a hidden keyboard waits;
 *  - in `onStartInputView` rebuilds whatever is stale ([shouldRebuildOnShow]).
 * No theme-version counter is needed and nothing is rebuilt when the displayed colours did not
 * change (a save of another theme, a broadcast after `onThemeChanged` already re-inflated).
 */
class KeyboardViewThemeTracker(private val currentStamp: () -> KeyboardViewThemeStamp) {

    /** The stamp of the keyboard view currently in use; null before the first inflation. */
    private var viewStamp: KeyboardViewThemeStamp? = null

    /** Record what the keyboard view that was just inflated is built from. */
    fun onKeyboardViewInflated() {
        viewStamp = currentStamp()
    }

    /** True when a view inflated now would differ from the current one. */
    fun isViewStale(): Boolean {
        val now = currentStamp()
        return now.styleId != 0 && now != viewStamp
    }

    /**
     * `ACTION_THEME_CHANGED`, after the config refresh: rebuild now? Hidden → false; the next
     * [shouldRebuildOnShow] sees the same staleness, so nothing has to be remembered.
     */
    fun shouldRebuildOnThemeBroadcast(inputViewShown: Boolean): Boolean = inputViewShown && isViewStale()

    /** `onStartInputView`: rebuild before showing? */
    fun shouldRebuildOnShow(): Boolean = isViewStale()
}
