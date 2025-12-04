package tribixbite.cleverkeys.theme

import android.content.Context
import android.view.ContextThemeWrapper
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Theme

/**
 * Unified theme provider for CleverKeys keyboard.
 *
 * This class is the single point of access for loading themes from any source:
 * - Built-in XML themes (dark, light, rosepine, cobalt, etc.)
 * - Decorative themes (Ruby, Sapphire, Emerald, Neon themes)
 * - Custom user-created themes
 *
 * All themes are converted to a common Theme object that the keyboard renderer
 * can use, enabling a modular architecture where new theme types can be added
 * without modifying the rendering pipeline.
 */
class ThemeProvider(
    private val context: Context,
    private val customThemeManager: CustomThemeManager
) {

    /**
     * Load a theme by its ID and return a Theme object for rendering.
     *
     * Theme ID conventions:
     * - Built-in: "dark", "light", "rosepine", "cobalt", etc.
     * - Decorative: "decorative_ruby", "decorative_sapphire", etc.
     * - Custom: "custom_<uuid>"
     *
     * @param themeId The unique identifier for the theme
     * @return A Theme object configured with the theme's colors
     */
    fun getTheme(themeId: String): Theme {
        return when {
            themeId.startsWith("custom_") -> loadCustomTheme(themeId)
            themeId.startsWith("decorative_") -> loadDecorativeTheme(themeId)
            else -> loadBuiltInTheme(themeId)
        }
    }

    /**
     * Get the KeyboardColorScheme for a theme (used by Theme Creator for defaults).
     * Returns null if the theme doesn't have a color scheme (XML themes).
     */
    fun getColorScheme(themeId: String): KeyboardColorScheme? {
        return when {
            themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId)?.colors
            themeId.startsWith("decorative_") -> getDecorativeColorScheme(themeId)
            else -> null // XML themes don't have KeyboardColorScheme
        }
    }

    /**
     * Check if a theme ID refers to a runtime (non-XML) theme.
     * Runtime themes use KeyboardColorScheme and can be customized.
     */
    fun isRuntimeTheme(themeId: String): Boolean {
        return themeId.startsWith("custom_") || themeId.startsWith("decorative_")
    }

    /**
     * Get all available theme IDs organized by category.
     */
    fun getAllThemeIds(): Map<String, List<String>> {
        val themes = mutableMapOf<String, MutableList<String>>()

        // Built-in XML themes
        themes["Built-in"] = mutableListOf(
            "cleverkeysdark", "cleverkeyslight", "dark", "light", "black", "altblack",
            "white", "rosepine", "cobalt", "pine", "desert", "jungle", "epaper",
            "epaperblack", "everforestlight", "monet", "monetlight", "monetdark"
        )

        // Decorative themes (from PredefinedThemes)
        themes["Gemstone"] = mutableListOf("decorative_ruby", "decorative_sapphire", "decorative_emerald")
        themes["Neon"] = mutableListOf("decorative_electric_blue", "decorative_hot_pink", "decorative_lime_green")
        themes["Pastel"] = mutableListOf("decorative_soft_pink", "decorative_sky_blue", "decorative_mint_green")
        themes["Nature"] = mutableListOf("decorative_forest", "decorative_ocean", "decorative_desert")
        themes["Utilitarian"] = mutableListOf("decorative_charcoal", "decorative_slate", "decorative_concrete")
        themes["Modern"] = mutableListOf("decorative_midnight", "decorative_sunrise", "decorative_aurora")

        // Custom themes
        val customIds = customThemeManager.getAllCustomThemeIds()
        if (customIds.isNotEmpty()) {
            themes["Custom"] = customIds.toMutableList()
        }

        return themes
    }

    // --- Private loaders ---

    private fun loadBuiltInTheme(themeId: String): Theme {
        val styleResId = Config.getThemeStyleId(themeId)
        val themedContext = ContextThemeWrapper(context, styleResId)
        return Theme(themedContext, null)
    }

    private fun loadDecorativeTheme(themeId: String): Theme {
        val colorScheme = getDecorativeColorScheme(themeId)
            ?: throw IllegalArgumentException("Unknown decorative theme: $themeId")
        return Theme(context, colorScheme)
    }

    private fun loadCustomTheme(themeId: String): Theme {
        val customTheme = customThemeManager.getCustomTheme(themeId)
            ?: throw IllegalStateException("Custom theme not found: $themeId")
        return Theme(context, customTheme.colors)
    }

    private fun getDecorativeColorScheme(themeId: String): KeyboardColorScheme? {
        // Strip "decorative_" prefix to get the actual theme name
        val themeName = themeId.removePrefix("decorative_")

        return when (themeName) {
            // Gemstone themes
            "ruby" -> themeGemstoneRuby().colorScheme
            "sapphire" -> themeGemstoneSapphire().colorScheme
            "emerald" -> themeGemstoneEmerald().colorScheme

            // Neon themes
            "electric_blue" -> themeNeonElectricBlue().colorScheme
            "hot_pink" -> themeNeonHotPink().colorScheme
            "lime_green" -> themeNeonLimeGreen().colorScheme

            // Pastel themes
            "soft_pink" -> themePastelSoftPink().colorScheme
            "sky_blue" -> themePastelSkyBlue().colorScheme
            "mint_green" -> themePastelMintGreen().colorScheme

            // Nature themes
            "forest" -> themeNatureForest().colorScheme
            "ocean" -> themeNatureOcean().colorScheme
            "desert" -> themeNatureDesert().colorScheme

            // Utilitarian themes
            "charcoal" -> themeUtilitarianCharcoal().colorScheme
            "slate" -> themeUtilitarianSlate().colorScheme
            "concrete" -> themeUtilitarianConcrete().colorScheme

            // Modern themes
            "midnight" -> themeModernMidnight().colorScheme
            "sunrise" -> themeModernSunrise().colorScheme
            "aurora" -> themeModernAurora().colorScheme

            else -> null
        }
    }

    companion object {
        @Volatile
        private var instance: ThemeProvider? = null

        /**
         * Get singleton instance of ThemeProvider.
         */
        fun getInstance(context: Context): ThemeProvider {
            return instance ?: synchronized(this) {
                instance ?: ThemeProvider(
                    context.applicationContext,
                    CustomThemeManager(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
}
