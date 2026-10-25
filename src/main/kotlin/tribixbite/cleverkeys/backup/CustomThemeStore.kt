package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.theme.CustomThemeManager

/**
 * Injection seam between the backup code and the DIY-theme store. Production wraps the
 * process-wide `ThemeProvider.customThemeManager` ([RealCustomThemeStore]); tests inject a fake.
 *
 * Both directions use the stored JSON array of `CustomTheme.toJson` objects, so the backup
 * code stays pure and the store keeps its own (org.json) serialization.
 */
interface CustomThemeStore {
    /** Every stored DIY theme, as a JSON array. */
    fun exportThemesJson(): String

    /**
     * Make the stored set exactly [themesArrayJson] (validated by [CustomThemeBackup]) and
     * persist it synchronously.
     *
     * @return how many themes are stored afterwards.
     * @throws Exception when the set could not be written; the stored set is then unchanged.
     */
    fun replaceAll(themesArrayJson: String): Int
}

/**
 * The real store. [manager] must be `ThemeProvider.getInstance(context).customThemeManager`:
 * a second `CustomThemeManager` would leave the provider's in-memory list — the one the keyboard
 * renders from — stale (theme-system.md rule 4, audit H-4).
 */
class RealCustomThemeStore(private val manager: CustomThemeManager) : CustomThemeStore {
    override fun exportThemesJson(): String = manager.exportAllThemesJson()

    override fun replaceAll(themesArrayJson: String): Int = manager.replaceAllThemes(themesArrayJson)
}
