package tribixbite.cleverkeys.theme

import android.content.Context
import android.view.ContextThemeWrapper
import tribixbite.cleverkeys.Config
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
     * Returns a color scheme based on the theme's style.
     */
    fun getColorScheme(themeId: String): KeyboardColorScheme? {
        return when {
            themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId)?.colors
            themeId.startsWith("decorative_") -> getDecorativeColorScheme(themeId)
            else -> getBuiltInColorScheme(themeId)
        }
    }

    /**
     * Get approximate color scheme for built-in XML themes.
     * Used when creating custom themes based on a built-in theme.
     *
     * These are approximations of the XML theme colors - the actual keyboard
     * uses XML attributes, but this provides starting colors for the theme creator.
     */
    private fun getBuiltInColorScheme(themeId: String): KeyboardColorScheme {
        return when (themeId) {
            "cleverkeysdark" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF2A1845),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF1E1030),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFC0C0C0),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFFC0C0C0) // Silver trail
            )
            "cleverkeyslight" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFD8D8D8),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFC0C0C0),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF2C3E50),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFFC0C0C0) // Silver trail
            )
            "dark" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF33B5E5)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF333333),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF1B1B1B),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF33B5E5)
            )
            "light" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF33B5E5)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFCCCCCC),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFE3E3E3),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF33B5E5)
            )
            "black", "altblack" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF009DFF)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF000000),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF000000),
                keyboardSurface = androidx.compose.ui.graphics.Color(0xFF000000),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFEEEEEE),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF009DFF)
            )
            "white" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF0066CC)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                keyboardSurface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF0066CC)
            )
            "rosepine" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFC4A7E7)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF26233A),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF191724),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFE0DEF4),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFFC4A7E7)
            )
            "cobalt" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF78BFFF)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF000000),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF000000),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF95C9FF),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF78BFFF)
            )
            "pine" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF74CC8A)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF000000),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF000000),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF91D7A6),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF74CC8A)
            )
            "desert" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFE65100)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFFFF3E0),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFFFE0B2),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFFE65100)
            )
            "jungle" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF00695C)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFE0F2F1),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF4DB6AC),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF00695C)
            )
            "epaper" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF000000)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF000000)
            )
            "epaperblack" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF000000),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF000000),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
            )
            "everforestlight" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF828812)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFE5E2D1),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFF8F5E4),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF5C6A72),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF828812)
            )
            "monet", "monetdark" -> darkKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFF333333),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFF1B1B1B),
                keyLabel = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            )
            "monetlight" -> lightKeyboardColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            ).copy(
                keyDefault = androidx.compose.ui.graphics.Color(0xFFCCCCCC),
                keyboardBackground = androidx.compose.ui.graphics.Color(0xFFE3E3E3),
                keyLabel = androidx.compose.ui.graphics.Color(0xFF000000),
                swipeTrail = androidx.compose.ui.graphics.Color(0xFF9B59B6)
            )
            else -> darkKeyboardColorScheme() // Fallback
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

        // Decorative themes (from PredefinedThemes) - IDs must match getDecorativeColorScheme()
        themes["Gemstone"] = mutableListOf("decorative_gemstone_ruby", "decorative_gemstone_sapphire", "decorative_gemstone_emerald")
        themes["Neon"] = mutableListOf("decorative_neon_electric_blue", "decorative_neon_hot_pink", "decorative_neon_cyberpunk")
        themes["Pastel"] = mutableListOf("decorative_pastel_soft_pink", "decorative_pastel_sky_blue", "decorative_pastel_mint_green")
        themes["Nature"] = mutableListOf("decorative_nature_forest", "decorative_nature_ocean", "decorative_nature_desert")
        themes["Utilitarian"] = mutableListOf("decorative_utilitarian_charcoal", "decorative_utilitarian_slate", "decorative_utilitarian_concrete")
        themes["Modern"] = mutableListOf("decorative_modern_midnight", "decorative_modern_sunrise", "decorative_modern_aurora")

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
        // Theme names match PredefinedThemes IDs (e.g., "gemstone_ruby", "neon_electric_blue")
        val themeName = themeId.removePrefix("decorative_")

        return when (themeName) {
            // Gemstone themes
            "gemstone_ruby" -> themeGemstoneRuby().colorScheme
            "gemstone_sapphire" -> themeGemstoneSapphire().colorScheme
            "gemstone_emerald" -> themeGemstoneEmerald().colorScheme

            // Neon themes
            "neon_electric_blue" -> themeNeonElectricBlue().colorScheme
            "neon_hot_pink" -> themeNeonHotPink().colorScheme
            "neon_cyberpunk" -> themeNeonCyberpunk().colorScheme

            // Pastel themes
            "pastel_soft_pink" -> themePastelSoftPink().colorScheme
            "pastel_sky_blue" -> themePastelSkyBlue().colorScheme
            "pastel_mint_green" -> themePastelMintGreen().colorScheme

            // Nature themes
            "nature_forest" -> themeNatureForest().colorScheme
            "nature_ocean" -> themeNatureOcean().colorScheme
            "nature_desert" -> themeNatureDesert().colorScheme

            // Utilitarian themes
            "utilitarian_charcoal" -> themeUtilitarianCharcoal().colorScheme
            "utilitarian_slate" -> themeUtilitarianSlate().colorScheme
            "utilitarian_concrete" -> themeUtilitarianConcrete().colorScheme

            // Modern themes
            "modern_midnight" -> themeModernMidnight().colorScheme
            "modern_sunrise" -> themeModernSunrise().colorScheme
            "modern_aurora" -> themeModernAurora().colorScheme

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
