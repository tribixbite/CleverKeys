package tribixbite.cleverkeys.theme

import android.annotation.SuppressLint
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
    /**
     * Audit H-4: exposed so editor surfaces (ThemeSettingsActivity) mutate the SAME
     * store instance this provider reads. A separate CustomThemeManager writes the same
     * prefs file but leaves this one's in-memory StateFlow stale — active-theme edits
     * then keep rendering the old colours until the process dies.
     */
    val customThemeManager: CustomThemeManager
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
            themeId.startsWith(KeyboardLooks.ID_PREFIX) -> loadLook(themeId)
            themeId.startsWith(EmbeddedThemes.ID_PREFIX) -> loadEmbedded(themeId)
            else -> loadBuiltInTheme(themeId)
        }
    }

    /**
     * Get the KeyboardColorScheme for a theme (used by Theme Creator for defaults).
     * Returns a color scheme based on the theme's style.
     */
    fun getColorScheme(themeId: String): KeyboardColorScheme? {
        return when {
            // A version-2 custom theme: its colours for the current mode (light/dark variants).
            themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId)?.let { custom ->
                if (custom.extension == null) custom.colors
                else renderedFor(ThemeSpecs.flatten(custom.fullSpec(), systemLight())).scheme
            }
            themeId.startsWith(EmbeddedThemes.ID_PREFIX) -> embeddedFlat(themeId)?.let { renderedFor(it.first).scheme }
            themeId.startsWith("decorative_") -> getDecorativeColorScheme(themeId)
            // A look's scheme for the CURRENT system mode (it follows light/dark like Monet Auto).
            // Wallpaper colours included (Gboard-style), so KeyboardViewThemeTracker sees a
            // wallpaper/palette change as a colour change and the next keyboard show re-inflates.
            themeId.startsWith(KeyboardLooks.ID_PREFIX) -> KeyboardLooks.byId(themeId)
                ?.let { KeyboardLookSchemes.colorScheme(lookPalette(it)) }
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
    fun isRuntimeTheme(themeId: String): Boolean =
        tribixbite.cleverkeys.prefs.ConfigSnapshot.isRuntimeThemeName(themeId)

    /**
     * Whether [themeId] names a theme that exists on this device — a built-in theme, a known
     * decorative theme, or a custom theme still in the store. Used to resolve per-layout theme
     * bindings (`Config.themeAvailability`): a binding to a missing theme renders the user's
     * global theme instead of [getTheme]'s generic fallback.
     */
    fun isAvailable(themeId: String): Boolean = when {
        themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId) != null
        themeId.startsWith("decorative_") -> getDecorativeColorScheme(themeId) != null
        themeId.startsWith(KeyboardLooks.ID_PREFIX) -> KeyboardLooks.byId(themeId) != null
        themeId.startsWith(EmbeddedThemes.ID_PREFIX) -> EmbeddedThemes.parse(themeId) != null
        else -> themeId in BUILT_IN_THEME_IDS
    }

    /**
     * What a keyboard view of [themeId] is built from beyond its colours — the resolved spec of
     * a version-2 custom theme or of an embedded layout theme — for `KeyboardViewThemeStamp`, so
     * an edit of geometry or hints alone re-inflates the view. Null for every other theme.
     */
    fun appearanceOf(themeId: String): Any? = when {
        themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId)
            ?.takeIf { it.extension != null }?.let { ThemeSpecs.flatten(it.fullSpec(), systemLight()) }
        themeId.startsWith(EmbeddedThemes.ID_PREFIX) -> embeddedFlat(themeId)?.first
        else -> null
    }

    /**
     * The appearance [themeId] draws with in the current system mode, as a flat (one-mode)
     * spec, plus the theme's own key frame in px for built-in XML themes (radius, width,
     * pressed width; null otherwise). What a layout's embedded `<theme>` inherits from: its
     * unset fields take these values. Total, like [getTheme]: an unknown id gives the fallback.
     */
    fun baseAppearance(themeId: String): Pair<ThemeSpec, FloatArray?> {
        val light = systemLight()
        return when {
            themeId.startsWith("custom_") -> customThemeManager.getCustomTheme(themeId)?.let {
                ThemeSpecs.flatten(it.fullSpec(), light) to null
            }
            themeId.startsWith("decorative_") -> getDecorativeColorScheme(themeId)?.let { ThemeSpecs.fromScheme(it) to null }
            themeId.startsWith(KeyboardLooks.ID_PREFIX) -> KeyboardLooks.byId(themeId)?.let {
                ThemeSpecs.fromLook(it, lookPalette(it)) to null
            }
            // The global theme is never an embedded one; guard against a loop anyway.
            themeId.startsWith(EmbeddedThemes.ID_PREFIX) -> null
            else -> {
                val xml = loadBuiltInTheme(themeId)
                xmlThemeSpec(xml) to floatArrayOf(xml.keyBorderRadius, xml.keyBorderWidth, xml.keyBorderWidthActivated)
            }
        } ?: (ThemeSpecs.fromScheme(getBuiltInColorScheme(FALLBACK_THEME_ID)) to null)
    }

    /** An XML theme's rendered colours as role colours (its trail follows the user: not set). */
    private fun xmlThemeSpec(t: Theme): ThemeSpec = ThemeSpec(
        colors = linkedMapOf(
            ThemeColorRole.KEY to t.colorKey,
            ThemeColorRole.KEY_PRESSED to t.colorKeyActivated,
            ThemeColorRole.KEY_LOCKED to t.colorKeyLocked,
            ThemeColorRole.KEY_MODIFIER to t.colorKeyModifier,
            ThemeColorRole.KEY_SPECIAL to t.colorKeySpecial,
            ThemeColorRole.LABEL to t.labelColor,
            ThemeColorRole.SUB_LABEL to t.subLabelColor,
            ThemeColorRole.SECONDARY_LABEL to t.secondaryLabelColor,
            ThemeColorRole.KEY_BORDER to t.keyBorderColorTop,
            ThemeColorRole.KEY_BORDER_PRESSED to t.keyBorderColorActivated,
            ThemeColorRole.RIPPLE to t.rippleColor,
            ThemeColorRole.BAR_TEXT to t.suggestionTextColor,
            ThemeColorRole.BAR_BACKGROUND to t.suggestionBackgroundColor,
            ThemeColorRole.BAR_HIGHLIGHT to t.suggestionHighConfidenceColor,
            ThemeColorRole.KEYBOARD_BACKGROUND to t.colorKeyboardBackground,
            ThemeColorRole.SURFACE to t.colorKeyboardSurface,
            ThemeColorRole.LABEL_PRESSED to t.activatedColor,
            ThemeColorRole.LOCKED_LABEL to t.lockedColor,
            ThemeColorRole.BAR_SEPARATOR to t.suggestionSeparatorColor,
        ),
    )

    /** An embedded id's spec over its base, flattened for the current mode, and the base's frame. */
    private fun embeddedFlat(themeId: String): Pair<ThemeSpec, FloatArray?>? {
        val parsed = EmbeddedThemes.parse(themeId) ?: return null
        val (base, frame) = baseAppearance(parsed.baseThemeId)
        return ThemeSpecs.resolve(parsed.spec, base, systemLight()) to frame
    }

    private fun systemLight(): Boolean = Config.isSystemLight(context.resources)

    /** [flat] rendered, with wallpaper colours applied when it asks for them ([dynamicPalette]). */
    private fun renderedFor(flat: ThemeSpec): RenderedThemeSpec {
        val r = ThemeSpecs.render(flat)
        if (!r.dynamicColor) return r
        val palette = dynamicPalette(r.palette)
        return if (palette === r.palette) r else r.copy(palette = palette, scheme = KeyboardLookSchemes.colorScheme(palette))
    }

    /** A Theme from a flat spec ([ThemeSpecs.render]); [frame] = an inherited XML key frame. */
    private fun specTheme(flat: ThemeSpec, frame: FloatArray?): Theme {
        val r = renderedFor(flat)
        return Theme(
            context,
            r.scheme,
            lookPalette = r.palette,
            lookGeometry = r.geometry,
            lookFunctionChars = r.functionChars,
            lookLegends = r.legends,
            lookPillKeys = r.pillKeys,
            lookLetterKeys = r.letterKeys,
            lookPreview = r.preview,
            lookHintStyle = r.hintStyle,
            lookPopupColors = if (r.popups) LookPopupColors.of(r.palette) else null,
            baseFrame = frame,
            pressedLabelColor = r.labelPressed,
            lookBar = r.bar,
        )
    }

    /** A layout's embedded `<theme>` over the global theme it names (EmbeddedThemes). */
    private fun loadEmbedded(themeId: String): Theme {
        val flat = embeddedFlat(themeId)
        if (flat == null) {
            android.util.Log.w(TAG, "Unknown embedded layout theme '$themeId' — falling back to $FALLBACK_THEME_ID")
            return fallbackTheme()
        }
        return specTheme(flat.first, flat.second)
    }

    /**
     * Get all available theme IDs organized by category.
     */
    fun getAllThemeIds(): Map<String, List<String>> {
        val themes = mutableMapOf<String, MutableList<String>>()

        // Built-in XML themes
        themes["Built-in"] = BUILT_IN_THEME_IDS.toMutableList()

        // Keyboard looks (colours + geometry, light/dark follow the system)
        themes["Looks"] = KeyboardLooks.IDS.toMutableList()

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
        // The same map and the same system light/dark cue as the keyboard view's own theme,
        // so "Monet (Auto)" previews match the keyboard in light mode (audit H2).
        val styleResId = Config.builtInThemeStyleId(themeId, Config.isSystemLight(context.resources))
        val themedContext = ContextThemeWrapper(context, styleResId)
        return Theme(themedContext, null)
    }

    private fun loadDecorativeTheme(themeId: String): Theme {
        val colorScheme = getDecorativeColorScheme(themeId)
        if (colorScheme == null) {
            // Audit H-2: a stale decorative id (e.g. a backup restored from a build with
            // different decorative IDs) must never throw into Keyboard2View's constructor —
            // that is an IME crash loop. Fall back to the default palette instead.
            android.util.Log.w(TAG, "Unknown decorative theme '$themeId' — falling back to $FALLBACK_THEME_ID")
            return fallbackTheme()
        }
        return Theme(context, colorScheme)
    }

    /**
     * A keyboard look: its palette for the current system mode plus its geometry. The light/dark
     * cue is the same one Monet Auto and the keyboard view's base style read, so a mode switch
     * changes the style id and re-inflates the view with the other palette.
     */
    private fun loadLook(themeId: String): Theme {
        val look = KeyboardLooks.byId(themeId)
        if (look == null) {
            android.util.Log.w(TAG, "Unknown keyboard look '$themeId' — falling back to $FALLBACK_THEME_ID")
            return fallbackTheme()
        }
        return lookTheme(look, systemLight())
    }

    /**
     * [look]'s renderer theme for one system mode ([systemLight]); the keyboard passes the current
     * mode ([loadLook]), the instrumented showcase both modes.
     */
    fun lookTheme(look: KeyboardLook, systemLight: Boolean): Theme {
        val palette = lookPalette(look, systemLight)
        return Theme(
            context,
            KeyboardLookSchemes.colorScheme(palette),
            lookPalette = palette,
            lookGeometry = look.geometry,
            lookFunctionChars = look.functionChars,
            lookLegends = look.legends,
            lookPillKeys = look.pillKeys,
            lookLetterKeys = look.letterKeys,
            lookPreview = look.preview,
            lookHintStyle = look.hintStyle,
            lookBar = look.bar,
        )
    }

    /**
     * The palette [look] draws with now: its fixed light/dark palette by `Config.isSystemLight`,
     * or — for a [KeyboardLook.dynamicColor] look on API 31+ — the system's wallpaper colours
     * mapped by [LookDynamicColors] (which falls back to the fixed palette if a contrast floor
     * would be missed). Read fresh every time: the system updates these resources when the
     * wallpaper or palette changes, so the stale-view stamp picks the change up.
     */
    private fun lookPalette(look: KeyboardLook, light: Boolean = systemLight()): LookPalette {
        val fixed = look.palette(light)
        if (!look.dynamicColor) return fixed
        return dynamicPalette(fixed, light)
    }

    /**
     * The system's wallpaper colours mapped onto a palette ([LookDynamicColors]) with [fixed] as
     * the fallback: below API 31, on a ROM without the system palette, or when a contrast floor
     * would be missed, [fixed] itself is returned.
     */
    private fun dynamicPalette(fixed: LookPalette, light: Boolean = systemLight()): LookPalette {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return fixed
        val roles = try {
            systemMaterialRoles(light)
        } catch (e: Exception) {
            // Resources.NotFoundException on a ROM without the system palette: keep the fixed one.
            android.util.Log.w(TAG, "System dynamic colours unavailable — using the fixed palette", e)
            return fixed
        }
        return LookDynamicColors.gboardPalette(roles, light, fixed)
    }

    /**
     * The Material 3 roles of the system's dynamic palette. API 34+ exposes the roles themselves
     * (`system_surface_container_light`, …), computed by the system with its own scheme spec —
     * the values Gboard draws with. API 31–33 expose only the tonal palettes; the roles are
     * their standard tones (Material 3 tonal-spot mapping, tones 100/99/95/90/80/…/10/0 =
     * shades 0/10/50/100/200/…/900/1000; tones without a shade take the nearest one).
     */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.S)
    private fun systemMaterialRoles(light: Boolean): LookDynamicColors.MaterialRoles {
        val res = context.resources
        fun c(id: Int): Int = res.getColor(id, null)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return if (light) LookDynamicColors.MaterialRoles(
                surfaceContainer = c(android.R.color.system_surface_container_light),
                surfaceContainerLowest = c(android.R.color.system_surface_container_lowest_light),
                surfaceContainerHighest = c(android.R.color.system_surface_container_highest_light),
                surfaceBright = c(android.R.color.system_surface_bright_light),
                secondaryContainer = c(android.R.color.system_secondary_container_light),
                onSecondaryContainer = c(android.R.color.system_on_secondary_container_light),
                primary = c(android.R.color.system_primary_light),
                onPrimary = c(android.R.color.system_on_primary_light),
                onSurface = c(android.R.color.system_on_surface_light),
                onSurfaceVariant = c(android.R.color.system_on_surface_variant_light),
                outlineVariant = c(android.R.color.system_outline_variant_light),
            ) else LookDynamicColors.MaterialRoles(
                surfaceContainer = c(android.R.color.system_surface_container_dark),
                surfaceContainerLowest = c(android.R.color.system_surface_container_lowest_dark),
                surfaceContainerHighest = c(android.R.color.system_surface_container_highest_dark),
                surfaceBright = c(android.R.color.system_surface_bright_dark),
                secondaryContainer = c(android.R.color.system_secondary_container_dark),
                onSecondaryContainer = c(android.R.color.system_on_secondary_container_dark),
                primary = c(android.R.color.system_primary_dark),
                onPrimary = c(android.R.color.system_on_primary_dark),
                onSurface = c(android.R.color.system_on_surface_dark),
                onSurfaceVariant = c(android.R.color.system_on_surface_variant_dark),
                outlineVariant = c(android.R.color.system_outline_variant_dark),
            )
        }
        return if (light) LookDynamicColors.MaterialRoles(
            surfaceContainer = c(android.R.color.system_neutral1_50),        // tone 94 ≈ 95
            surfaceContainerLowest = c(android.R.color.system_neutral1_0),   // tone 100
            surfaceContainerHighest = c(android.R.color.system_neutral1_100), // tone 90
            surfaceBright = c(android.R.color.system_neutral1_10),           // tone 98 ≈ 99
            secondaryContainer = c(android.R.color.system_accent2_100),      // tone 90
            onSecondaryContainer = c(android.R.color.system_accent2_900),    // tone 10
            primary = c(android.R.color.system_accent1_600),                 // tone 40
            onPrimary = c(android.R.color.system_accent1_0),                 // tone 100
            onSurface = c(android.R.color.system_neutral1_900),              // tone 10
            onSurfaceVariant = c(android.R.color.system_neutral2_700),       // tone 30
            outlineVariant = c(android.R.color.system_neutral2_200),         // tone 80
        ) else LookDynamicColors.MaterialRoles(
            surfaceContainer = c(android.R.color.system_neutral1_900),       // tone 12 ≈ 10
            surfaceContainerLowest = c(android.R.color.system_neutral1_1000), // tone 4 ≈ 0
            surfaceContainerHighest = c(android.R.color.system_neutral1_800), // tone 22 ≈ 20
            surfaceBright = c(android.R.color.system_neutral1_700),          // tone 24 ≈ 30
            secondaryContainer = c(android.R.color.system_accent2_700),      // tone 30
            onSecondaryContainer = c(android.R.color.system_accent2_100),    // tone 90
            primary = c(android.R.color.system_accent1_200),                 // tone 80
            onPrimary = c(android.R.color.system_accent1_800),               // tone 20
            onSurface = c(android.R.color.system_neutral1_100),              // tone 90
            onSurfaceVariant = c(android.R.color.system_neutral2_200),       // tone 80
            outlineVariant = c(android.R.color.system_neutral2_700),         // tone 30
        )
    }

    private fun loadCustomTheme(themeId: String): Theme {
        val customTheme = customThemeManager.getCustomTheme(themeId)
        if (customTheme == null) {
            // Audit H-2: the `theme` pref can dangle (active custom theme deleted; the pref
            // and the theme store live in different prefs files). Throwing here propagated
            // out of Keyboard2View's constructor and crash-looped the IME on every
            // inflation until the pref changed — with no working keyboard to change it.
            android.util.Log.w(TAG, "Custom theme not found '$themeId' — falling back to $FALLBACK_THEME_ID")
            return fallbackTheme()
        }
        // Version 2 (a saved layout <theme> or an edited extension): the full spec; version 1
        // renders exactly as it always did.
        return themeForCustom(customTheme)
    }

    /**
     * The renderer theme of [customTheme]: version 2 (a saved layout `<theme>` or an edited
     * extension) through its full spec in the current mode; version 1 exactly as it always
     * rendered. Also the Theme Creator preview's path, so preview and keyboard cannot differ.
     */
    fun themeForCustom(customTheme: CustomTheme): Theme {
        if (customTheme.extension != null) return specTheme(ThemeSpecs.flatten(customTheme.fullSpec(), systemLight()), null)
        return Theme(context, customTheme.colors)
    }

    /**
     * Crash-loop escape hatch for dangling theme ids ([getTheme] must be TOTAL — it is
     * called from Keyboard2View's constructor). Built from the in-code approximation of
     * the default theme's palette rather than XML style resolution so the fallback has no
     * failure modes of its own; the user can reselect a theme normally afterwards.
     */
    private fun fallbackTheme(): Theme =
        Theme(context, getBuiltInColorScheme(FALLBACK_THEME_ID))

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
        private const val TAG = "ThemeProvider"

        /** The theme every dangling-id fallback resolves to (audit H-2). Also the id the
         *  delete-active-theme guard resets the `theme` pref to (CustomThemePrefPolicy). */
        const val FALLBACK_THEME_ID = "cleverkeysdark"

        /**
         * Ids of the built-in XML themes — the `theme` values Theme Manager offers in its
         * Built-in section. Drift-pinned against `ThemeSettingsActivity.BUILTIN_THEMES` by
         * LayoutThemeBindingConfigTest.
         */
        val BUILT_IN_THEME_IDS: List<String> = listOf(
            "cleverkeysdark", "cleverkeyslight", "dark", "light", "black", "altblack",
            "white", "rosepine", "cobalt", "pine", "desert", "jungle", "epaper",
            "epaperblack", "everforestlight", "monet", "monetlight", "monetdark"
        )

        // Process-lifetime singleton holding only the applicationContext (see getInstance),
        // so it never leaks an Activity/Service. The reference lives as long as the process.
        @SuppressLint("StaticFieldLeak")
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
