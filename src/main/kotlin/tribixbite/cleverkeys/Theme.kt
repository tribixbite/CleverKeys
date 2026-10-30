package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.compositeOver
import tribixbite.cleverkeys.theme.KeyboardColorScheme
import tribixbite.cleverkeys.theme.KeyboardLookGeometry
import tribixbite.cleverkeys.theme.LookGeometry
import tribixbite.cleverkeys.theme.LookHintStyle
import tribixbite.cleverkeys.theme.LookLetterKey
import tribixbite.cleverkeys.theme.LookLegends
import tribixbite.cleverkeys.theme.LookPillKey
import tribixbite.cleverkeys.theme.LookPalette
import tribixbite.cleverkeys.theme.LookPopupColors
import tribixbite.cleverkeys.theme.LookPreview
import tribixbite.cleverkeys.theme.LookTypeface
import kotlin.math.min

/**
 * Theme class for keyboard rendering.
 *
 * Supports two modes:
 * 1. XML-based themes: Created via constructor(Context, AttributeSet) - reads from res/values/themes.xml
 * 2. Runtime themes: Created via constructor(Context, KeyboardColorScheme) - uses Compose color scheme
 *
 * This unified approach allows both built-in XML themes and user-defined/decorative themes
 * to work with the same rendering pipeline.
 */
class Theme {
    // Key colors
    val colorKey: Int
    val colorKeyActivated: Int

    // Label colors
    @JvmField
    val lockedColor: Int
    @JvmField
    val activatedColor: Int
    @JvmField
    val labelColor: Int
    @JvmField
    val subLabelColor: Int
    @JvmField
    val secondaryLabelColor: Int
    @JvmField
    val greyedLabelColor: Int

    // Key borders
    val keyBorderRadius: Float
    val keyBorderWidth: Float
    val keyBorderWidthActivated: Float
    val keyBorderColorLeft: Int
    val keyBorderColorTop: Int
    val keyBorderColorRight: Int
    val keyBorderColorBottom: Int

    @JvmField
    val colorNavBar: Int
    @JvmField
    val isLightNavBar: Boolean

    /** Keyboard container background color — used to apply custom/decorative backgrounds
     *  that can't be resolved from XML theme attributes. */
    @JvmField
    val colorKeyboardBackground: Int

    // ---------------------------------------------------------------------------------
    // Audit 2026-09-06 H-4 (maintainer fork resolved: WIRE): the nine Theme-Creator
    // fields below used to be collected and persisted but consumed by nothing. Each now
    // has a Theme representation with a consumer:
    //  - colorKeyLocked/Modifier/Special → per-role key backgrounds (Computed.keyForRole,
    //    selected in Keyboard2View.onDraw via Computed.roleOf)
    //  - keyBorderColorActivated → the ACTIVATED key frame's border paint (Computed.Key)
    //  - rippleColor → press feedback on SuggestionBar chips / icon buttons
    //  - suggestionTextColor/BackgroundColor/HighConfidenceColor → SuggestionBar text,
    //    bar background and first-suggestion highlight
    //  - colorKeyboardSurface → elevated surfaces (SuggestionBar provenance popup)
    // XML themes have no attrs for these; their values default to the exact colours the
    // consumers used before the wiring, so built-in themes render pixel-identically.
    // ---------------------------------------------------------------------------------

    /** Background of a LOCKED modifier key (caps lock etc.). */
    @JvmField
    val colorKeyLocked: Int

    /** Background of modifier keys (shift/ctrl/alt) at rest. */
    @JvmField
    val colorKeyModifier: Int

    /** Background of special action keys (enter, backspace, switchers) at rest. */
    @JvmField
    val colorKeySpecial: Int

    /** Border colour of the ACTIVATED (pressed/locked) key frame. */
    val keyBorderColorActivated: Int

    /** Press-ripple colour; 0 = keep the platform default ripple (XML themes). */
    @JvmField
    val rippleColor: Int

    /** Suggestion-bar text colour. */
    @JvmField
    val suggestionTextColor: Int

    /** Suggestion-bar background colour. */
    @JvmField
    val suggestionBackgroundColor: Int

    /** Highlight colour for the top / high-confidence suggestion. */
    @JvmField
    val suggestionHighConfidenceColor: Int

    /**
     * Prompt chips ("+word" exact-add, "Don't ask"): the theme's sub-label colour, unless it is
     * unreadable on the bar. All three suggestion text roles pass through
     * [SuggestionBarPalette.resolve] against [suggestionBackgroundColor].
     */
    @JvmField
    val suggestionSecondaryTextColor: Int

    /** Surface colour for elevated elements (popups) hosted by keyboard views. */
    @JvmField
    val colorKeyboardSurface: Int

    // ---------------------------------------------------------------------------------
    // Keyboard looks (2026-10-27, theme/KeyboardLook.kt). A look carries more than colours:
    // an Enter/action-key fill and label, modifier-key labels, a key shadow, suggestion
    // separators, a trail colour and geometry. Every other theme takes the values that keep
    // its rendering unchanged (action fill = special fill, 0 = "not defined", null geometry).
    // ---------------------------------------------------------------------------------

    /** Background of plain Enter at rest (other themes: [colorKeySpecial]). */
    @JvmField
    val colorKeyEnter: Int

    /** Main label colour on plain Enter; 0 = the ordinary label colour. */
    @JvmField
    val enterLabelColor: Int

    /**
     * Background of the editor-action key (the Enter replacement in Go / Search / Send / Done
     * fields) at rest (other themes: [colorKeySpecial]).
     */
    @JvmField
    val colorKeyAction: Int

    /** Main label colour on the editor-action key; 0 = the ordinary label colour. */
    @JvmField
    val actionLabelColor: Int

    /** Character keys a look draws as function keys (Gboard's `,` and `.`); empty otherwise. */
    @JvmField
    val functionChars: String

    /** A look's corner-label policy; null for every non-look theme (all labels drawn). */
    @JvmField
    val lookLegends: LookLegends?

    /** Keys a look draws as pills; empty for every non-look theme. */
    @JvmField
    val lookPillKeys: Set<LookPillKey>

    /** Main label colour on modifier and function keys; 0 = the ordinary label colour. */
    @JvmField
    val modifierLabelColor: Int

    /** Function keys a look draws as letter keys (Gboard's emoji key); empty otherwise. */
    @JvmField
    val lookLetterKeys: Set<LookLetterKey>

    /** Label colour of a function key drawn as a letter key ([lookLetterKeys]); 0 = none. */
    @JvmField
    val letterKeyLabelColor: Int

    /** A look's key-press preview; null for every non-look theme (none drawn). */
    @JvmField
    val lookPreview: LookPreview?

    /** Colours of a look's key-press preview and long-press popover; null for other themes. */
    @JvmField
    val lookPopupColors: LookPopupColors?

    /**
     * Fill of a latched modifier at rest (LookPalette.latchedKey: Gboard, Samsung); 0 for every
     * other theme, which draws it with the activated frame.
     */
    @JvmField
    val colorKeyLatched: Int

    /** Label colour on [colorKeyLatched] (LookPalette.latchedLabel); 0 = the ordinary label. */
    @JvmField
    val latchedLabelColor: Int

    /** How a look draws corner labels (size, weight); null for every non-look theme. */
    @JvmField
    val lookHintStyle: LookHintStyle?

    /**
     * A look's suggestion-bar arrangement (three slots, best centred — `theme/LookBar.kt`); null
     * for every non-look theme, whose bar keeps its scrolling chips.
     */
    @JvmField
    val lookBar: tribixbite.cleverkeys.theme.LookBar?

    /**
     * A look's editor-action key style (icon per action, which actions take the action colours —
     * `theme/LookActionKeys.kt`); null for every non-look theme (the action's text, action colours).
     */
    @JvmField
    val lookAction: tribixbite.cleverkeys.theme.LookActionStyle?

    /** A look prints the active language on the space bar (Samsung-style); false elsewhere. */
    @JvmField
    val lookSpaceLanguage: Boolean

    /**
     * A look's per-key legends (SwiftKey's mic over the comma, ",!?" over the period —
     * `theme/LookKeyMarks.kt`); empty for every non-look theme.
     */
    @JvmField
    val lookKeyLegends: List<tribixbite.cleverkeys.theme.LookKeyLegend>

    /** A look draws a filled Shift arrow while shifted (Gboard, `LookShiftGlyph`); false otherwise. */
    @JvmField
    val lookShiftFilled: Boolean

    /** Colour of the shadow under each key; 0 = none (needs [lookGeometry] `shadowDyDp`). */
    @JvmField
    val keyShadowColor: Int

    /** Full ARGB of the suggestion-bar separators (alpha 0 = none drawn). */
    @JvmField
    val suggestionSeparatorColor: Int

    /** Swipe trail colour this theme imposes; 0 = the user's `swipe_trail_color`. */
    @JvmField
    val swipeTrailColor: Int

    /** Geometry of a look; null for every non-look theme (the user's settings apply). */
    @JvmField
    val lookGeometry: LookGeometry?

    /**
     * Pixels per dp: converts [lookGeometry] and a layout's key-gap percentages and border width
     * (LayoutKeyGeometry.applyTo, `percent × density / 100` exactly as Config converts the global
     * gap). The display density for every theme, XML ones included — a fixed 1 made a layout's
     * 5 % gap draw like a global 2 % on the 420 dpi Saga (2026-10-27).
     */
    @JvmField
    val density: Float

    /**
     * Primary constructor for XML-based themes.
     * Reads colors from styled attributes defined in res/values/themes.xml
     */
    constructor(context: Context, attrs: AttributeSet?) {
        getKeyFont(context) // _key_font will be accessed
        val s = context.theme.obtainStyledAttributes(attrs, R.styleable.keyboard, 0, 0)
        colorKey = s.getColor(R.styleable.keyboard_colorKey, 0)
        colorKeyActivated = s.getColor(R.styleable.keyboard_colorKeyActivated, 0)
        colorNavBar = s.getColor(R.styleable.keyboard_navigationBarColor, 0)
        isLightNavBar = s.getBoolean(R.styleable.keyboard_windowLightNavigationBar, false)
        labelColor = s.getColor(R.styleable.keyboard_colorLabel, 0)
        activatedColor = s.getColor(R.styleable.keyboard_colorLabelActivated, 0)
        lockedColor = s.getColor(R.styleable.keyboard_colorLabelLocked, 0)
        subLabelColor = s.getColor(R.styleable.keyboard_colorSubLabel, 0)
        secondaryLabelColor = adjustLight(
            labelColor,
            s.getFloat(R.styleable.keyboard_secondaryDimming, 0.25f)
        )
        greyedLabelColor = adjustLight(
            labelColor,
            s.getFloat(R.styleable.keyboard_greyedDimming, 0.5f)
        )
        keyBorderRadius = s.getDimension(R.styleable.keyboard_keyBorderRadius, 0f)
        keyBorderWidth = s.getDimension(R.styleable.keyboard_keyBorderWidth, 0f)
        keyBorderWidthActivated = s.getDimension(R.styleable.keyboard_keyBorderWidthActivated, 0f)
        keyBorderColorLeft = s.getColor(R.styleable.keyboard_keyBorderColorLeft, colorKey)
        keyBorderColorTop = s.getColor(R.styleable.keyboard_keyBorderColorTop, colorKey)
        keyBorderColorRight = s.getColor(R.styleable.keyboard_keyBorderColorRight, colorKey)
        keyBorderColorBottom = s.getColor(R.styleable.keyboard_keyBorderColorBottom, colorKey)
        colorKeyboardBackground = s.getColor(R.styleable.keyboard_colorKeyboard, 0)
        s.recycle()

        // H-4: no XML attrs exist for the Theme-Creator fields — default each to the
        // colour its consumer used before the wiring (behaviour-preserving for XML themes).
        colorKeyLocked = colorKeyActivated
        colorKeyModifier = colorKey
        colorKeySpecial = colorKey
        keyBorderColorActivated = keyBorderColorTop
        rippleColor = 0 // platform-default ripple
        suggestionBackgroundColor = colorKey
        // colorLabelActivated was designed for colorKeyActivated, not the bar (colorKey):
        // readable only after SuggestionBarPalette (Cobalt's was the bar colour itself).
        val bar = SuggestionBarPalette.resolve(labelColor, activatedColor, subLabelColor, colorKey)
        suggestionTextColor = bar.text
        suggestionHighConfidenceColor = bar.highlight
        suggestionSecondaryTextColor = bar.secondary
        colorKeyboardSurface = colorKey
        colorKeyEnter = colorKeySpecial
        enterLabelColor = 0
        colorKeyAction = colorKeySpecial
        actionLabelColor = 0
        functionChars = ""
        lookLegends = null
        lookPillKeys = emptySet()
        modifierLabelColor = 0
        lookLetterKeys = emptySet()
        letterKeyLabelColor = 0
        lookPreview = null
        lookPopupColors = null
        lookHintStyle = null
        lookBar = null
        lookAction = null
        lookSpaceLanguage = false
        lookKeyLegends = emptyList()
        lookShiftFilled = false
        colorKeyLatched = 0
        latchedLabelColor = 0
        keyShadowColor = 0
        suggestionSeparatorColor = defaultSeparator(subLabelColor)
        swipeTrailColor = 0
        lookGeometry = null
        density = context.resources.displayMetrics.density
    }

    /**
     * Secondary constructor for runtime themes.
     * Accepts a KeyboardColorScheme (from decorative themes or custom user themes)
     * and maps it to the rendering properties.
     *
     * This enables decorative themes (Ruby, Sapphire, etc.) and user-created themes
     * to work with the actual keyboard renderer.
     *
     * A keyboard look ([tribixbite.cleverkeys.theme.KeyboardLook]) passes its current-mode
     * [lookPalette] (action key, modifier labels, shadow, separators, trail) and its
     * [lookGeometry]; [colorScheme] is then the look's palette mapped by `KeyboardLookSchemes`.
     */
    constructor(
        context: Context,
        colorScheme: KeyboardColorScheme,
        density: Float = context.resources.displayMetrics.density,
        lookPalette: LookPalette? = null,
        lookGeometry: LookGeometry? = null,
        lookFunctionChars: String = "",
        lookLegends: LookLegends? = null,
        lookPillKeys: Set<LookPillKey> = emptySet(),
        lookLetterKeys: Set<LookLetterKey> = emptySet(),
        lookPreview: LookPreview? = null,
        lookHintStyle: LookHintStyle? = null,
        /**
         * Pop-up colours of the preview and popover; by default a look's (from [lookPalette]).
         * A theme spec (`ThemeSpecs.render`) passes null unless it defines pop-ups, so the
         * popover keeps its non-look colours.
         */
        lookPopupColors: LookPopupColors? = lookPalette?.let { LookPopupColors.of(it) },
        /**
         * The theme's own key frame (radius, width, pressed width in px) for the case where the
         * user's custom border is off; null = the runtime default (8 dp, 1 dp, 2 dp). An embedded
         * layout theme over an XML theme passes the XML theme's frame so it is inherited.
         */
        baseFrame: FloatArray? = null,
        /** Label colour of a pressed key; 0 = the label colour (the runtime default). */
        pressedLabelColor: Int = 0,
        /** A look's suggestion-bar arrangement; null = scrolling chips. */
        lookBar: tribixbite.cleverkeys.theme.LookBar? = null,
        /** A look's editor-action key style; null = text on the action colours. */
        lookAction: tribixbite.cleverkeys.theme.LookActionStyle? = null,
        /** A look prints the language name on the space bar. */
        lookSpaceLanguage: Boolean = false,
        /** A look's per-key legends. */
        lookKeyLegends: List<tribixbite.cleverkeys.theme.LookKeyLegend> = emptyList(),
        /** A look draws a filled Shift arrow while shifted. */
        lookShiftFilled: Boolean = false,
    ) {
        getKeyFont(context) // Initialize font

        // Map KeyboardColorScheme to Theme properties
        colorKey = colorScheme.keyDefault.toArgb()
        colorKeyActivated = colorScheme.keyActivated.toArgb()
        labelColor = colorScheme.keyLabel.toArgb()
        activatedColor = pressedLabelColor.takeIf { it != 0 } ?: colorScheme.keyLabel.toArgb() // Use same for activated
        // A look sets its own Caps Lock label (Gboard: onPrimary on primary; Samsung: blue icon).
        lockedColor = lookPalette?.lockedLabel ?: colorScheme.keyLabel.toArgb()
        subLabelColor = colorScheme.keySubLabel.toArgb()
        secondaryLabelColor = colorScheme.keySecondaryLabel.toArgb()
        greyedLabelColor = adjustLight(labelColor, 0.5f)

        // Border colors - use the scheme's border color for all sides
        val borderColor = colorScheme.keyBorder.toArgb()
        keyBorderColorLeft = borderColor
        keyBorderColorTop = borderColor
        keyBorderColorRight = borderColor
        keyBorderColorBottom = borderColor

        // Default border dimensions (can be customized later); an inherited XML frame wins.
        keyBorderRadius = baseFrame?.getOrNull(0) ?: (8f * density)  // 8dp default
        keyBorderWidth = baseFrame?.getOrNull(1) ?: (1f * density)   // 1dp default
        keyBorderWidthActivated = baseFrame?.getOrNull(2) ?: (2f * density)  // 2dp when activated

        // Nav bar - derive from keyboard background
        colorNavBar = colorScheme.keyboardBackground.toArgb()
        isLightNavBar = isColorLight(colorNavBar)

        // Keyboard container background — applied programmatically by Keyboard2View
        // for runtime themes (custom/decorative), overriding the XML ?attr/colorKeyboard
        colorKeyboardBackground = colorScheme.keyboardBackground.toArgb()

        // H-4: the nine Theme-Creator fields, straight from the scheme. The per-role key
        // backgrounds are pre-composited over keyDefault because the shipped schemes
        // express them as translucent TINTS (e.g. primary at 20% alpha) while the key
        // paint's alpha channel is owned by the user's keyOpacity setting — compositing
        // at colour level preserves the intended appearance under any opacity.
        colorKeyLocked = colorScheme.keyLocked
            .compositeOver(colorScheme.keyDefault).toArgb()
        colorKeyModifier = colorScheme.keyModifier
            .compositeOver(colorScheme.keyDefault).toArgb()
        colorKeySpecial = colorScheme.keySpecial
            .compositeOver(colorScheme.keyDefault).toArgb()
        keyBorderColorActivated = colorScheme.keyBorderActivated.toArgb()
        rippleColor = colorScheme.ripple.toArgb()
        suggestionBackgroundColor = colorScheme.suggestionBackground.toArgb()
        val bar = SuggestionBarPalette.resolve(
            colorScheme.suggestionText.toArgb(),
            colorScheme.suggestionHighConfidence.toArgb(),
            subLabelColor,
            suggestionBackgroundColor,
        )
        suggestionTextColor = bar.text
        suggestionHighConfidenceColor = bar.highlight
        suggestionSecondaryTextColor = bar.secondary
        colorKeyboardSurface = colorScheme.keyboardSurface.toArgb()

        // 0 = not defined (a theme spec without Enter / action colours): the special fill.
        colorKeyEnter = lookPalette?.enterKey?.takeIf { it != 0 } ?: colorKeySpecial
        enterLabelColor = lookPalette?.enterLabel ?: 0
        colorKeyAction = lookPalette?.actionKey?.takeIf { it != 0 } ?: colorKeySpecial
        actionLabelColor = lookPalette?.actionLabel ?: 0
        functionChars = lookFunctionChars
        this.lookLegends = lookLegends
        this.lookPillKeys = lookPillKeys
        modifierLabelColor = lookPalette?.modifierLabel ?: 0
        this.lookLetterKeys = lookLetterKeys
        letterKeyLabelColor = if (lookLetterKeys.isNotEmpty()) lookPalette?.label ?: 0 else 0
        this.lookPreview = lookPreview?.takeIf { lookPalette != null }
        this.lookPopupColors = lookPopupColors?.takeIf { lookPalette != null }
        this.lookHintStyle = lookHintStyle?.takeIf { lookPalette != null }
        this.lookBar = lookBar?.takeIf { lookPalette != null }
        this.lookAction = lookAction?.takeIf { lookPalette != null }
        this.lookSpaceLanguage = lookSpaceLanguage && lookPalette != null
        this.lookKeyLegends = if (lookPalette != null) lookKeyLegends else emptyList()
        this.lookShiftFilled = lookShiftFilled && lookPalette != null
        colorKeyLatched = lookPalette?.latchedKey ?: 0
        latchedLabelColor = if (colorKeyLatched != 0) lookPalette?.latchedLabel ?: 0 else 0
        keyShadowColor = lookPalette?.keyShadow ?: 0
        suggestionSeparatorColor = lookPalette?.barSeparator ?: defaultSeparator(subLabelColor)
        swipeTrailColor = lookPalette?.swipeTrail ?: 0
        this.lookGeometry = lookGeometry
        this.density = density
    }

    /** Interpolate the 'value' component toward its opposite by 'alpha'. */
    private fun adjustLight(color: Int, alpha: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val v = hsv[2]
        hsv[2] = alpha - (2 * alpha - 1) * v
        return Color.HSVToColor(hsv)
    }

    /** The separator the suggestion bar always drew: the sub-label colour at alpha 100/255. */
    private fun defaultSeparator(subLabel: Int): Int = (subLabel and 0x00FFFFFF) or (100 shl 24)

    /** Determine if a color is light (for nav bar icon color) */
    private fun isColorLight(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness < 0.5
    }

    /** Convert Compose Color to Android ARGB Int */
    private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
        return android.graphics.Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt()
        )
    }

    fun initIndicationPaint(align: Paint.Align, font: Typeface?): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = align
            if (font != null) {
                typeface = font
            }
        }
    }

    class Computed(
        private val theme: Theme,
        private val config: Config,
        private val keyWidth: Float,
        layout: KeyboardData
    ) {
        @JvmField
        val vertical_margin: Float
        @JvmField
        val horizontal_margin: Float
        @JvmField
        val margin_top: Float
        @JvmField
        val margin_left: Float
        @JvmField
        val row_height: Float
        @JvmField
        val indication_paint: Paint

        @JvmField
        val key: Key
        @JvmField
        val key_activated: Key
        // H-4: per-role key frames so the Theme-Creator's Key Locked / Key Modifier /
        // Key Special colours render. For XML themes their colours equal the defaults
        // (see Theme's XML constructor), so these draw identically to key/key_activated.
        @JvmField
        val key_locked: Key
        @JvmField
        val key_modifier: Key
        @JvmField
        val key_special: Key
        /** Plain Enter at rest (other themes: same fill as [key_special]). */
        @JvmField
        val key_enter: Key
        /** The editor-action key at rest (other themes: same fill as [key_special]). */
        @JvmField
        val key_action: Key
        /** A function key a look draws as a letter key (Gboard's emoji key): letter fill and label. */
        @JvmField
        val key_letter: Key
        /** A latched modifier at rest on a look that defines its fill ([Theme.colorKeyLatched]). */
        @JvmField
        val key_latched: Key
        /** Multiplier on the main label size (a look's `labelScale`; 1 otherwise). */
        @JvmField
        val label_scale: Float
        /**
         * Multiplier on function-key TEXT labels such as "!#1" (a look's `functionLabelScale`;
         * 1 otherwise; `LookLabelSizing`).
         */
        @JvmField
        val function_label_scale: Float
        /** Key geometry after the effective look's precedence ([KeyboardLookGeometry.resolve]). */
        @JvmField
        val geometry: KeyboardLookGeometry.Effective
        /** The current layout's key-geometry overrides (user choice over XML `key_*` defaults). */
        private val layoutOverrides: Map<KeyGeometryField, Float>

        init {
            // Rows height is proportional to the keyboard height, meaning it doesn't
            // change for layouts with more or less rows. 3.95 is the usual height of
            // a layout in KeyboardData unit. The keyboard will be higher if the
            // layout has more rows and smaller if it has less because rows stay the
            // same height.
            //
            // Only layouts explicitly opting into numpad_height use full-height scaling.
            // bottom_row=false also describes compact custom text/macro layouts (#90).
            // With scale_numpad_height enabled,
            // use the actual keysHeight as divisor so rows scale up to fill the full
            // keyboard height, making keys easier to hit on small screens (#58).
            val heightDivisor = if (config.scale_numpad_height && layout.numpad_height) {
                layout.keysHeight
            } else {
                3.95f
            }
            row_height = min(
                config.screenHeightPixels * config.keyboardHeightPercent / 100 / heightDivisor,
                config.screenHeightPixels / layout.keysHeight
            )
            // The current layout's overrides (LayoutKeyGeometry: user per-layout choice, else its
            // XML defaults); per-key styling adds its own on top (styledFrame).
            layoutOverrides = config.currentKeyGeometry()
            geometry = geometryFor(layoutOverrides)
            vertical_margin = geometry.verticalMargin
            horizontal_margin = geometry.horizontalMargin
            label_scale = geometry.labelScale
            function_label_scale = geometry.functionLabelScale
            // Add half of the key margin on the left and on the top as it's also
            // added on the right and on the bottom of every keys.
            margin_top = config.marginTop + vertical_margin / 2
            margin_left = horizontal_margin / 2
            key = Key(theme, config, keyWidth, false, geometry = geometry)
            key_activated = Key(theme, config, keyWidth, true, geometry = geometry)
            // Locked keys keep the activated border/width treatment (they ARE held down),
            // only the fill colour differs; modifier/special/action are at-rest variants.
            key_locked = Key(theme, config, keyWidth, true, theme.colorKeyLocked, geometry)
            key_modifier = Key(theme, config, keyWidth, false, theme.colorKeyModifier, geometry, theme.modifierLabelColor)
            key_special = Key(theme, config, keyWidth, false, theme.colorKeySpecial, geometry, theme.modifierLabelColor)
            key_enter = Key(theme, config, keyWidth, false, theme.colorKeyEnter, geometry, theme.enterLabelColor)
            key_action = Key(theme, config, keyWidth, false, theme.colorKeyAction, geometry, theme.actionLabelColor)
            key_letter = Key(theme, config, keyWidth, false, theme.colorKey, geometry, theme.letterKeyLabelColor)
            // 0 = undefined: the activated frame's fill (roleOf never picks LATCHED then).
            key_latched = Key(
                theme, config, keyWidth, false,
                theme.colorKeyLatched.takeIf { it != 0 } ?: theme.colorKeyActivated, geometry, theme.latchedLabelColor,
            )
            indication_paint = init_label_paint(config, null).apply {
                color = theme.subLabelColor
            }
        }

        /**
         * The geometry for [overrides]: the user's geometry exactly as it was always derived, the
         * effective look's attributes over it (KeyboardLookGeometry.resolve), then [overrides] —
         * most specific wins (LayoutKeyGeometry). The user layer's border switch follows the
         * overrides, so a layout (or key) may switch the custom border off (theme/look frame) or
         * on independently of the global switch.
         */
        private fun geometryFor(overrides: Map<KeyGeometryField, Float>): KeyboardLookGeometry.Effective {
            val borderOn = LayoutKeyGeometry.effectiveBorderOn(overrides, config.borderConfig)
            val user = KeyboardLookGeometry.UserGeometry(
                verticalMargin = config.key_vertical_margin * row_height,
                horizontalMargin = config.key_horizontal_margin * keyWidth,
                borderRadius = if (borderOn) config.customBorderRadius * keyWidth else theme.keyBorderRadius,
                borderWidth = if (borderOn) config.customBorderLineWidth else theme.keyBorderWidth,
                borderWidthActivated = if (borderOn) config.customBorderLineWidth else theme.keyBorderWidthActivated,
            )
            return LayoutKeyGeometry.applyTo(
                KeyboardLookGeometry.resolve(theme.lookGeometry, user, keyWidth, row_height, theme.density),
                overrides = overrides,
                globals = LayoutKeyGeometry.Globals(
                    borderOn = config.borderConfig,
                    borderRadiusFraction = config.customBorderRadius,
                    borderWidthPx = config.customBorderLineWidth,
                ),
                keyWidth = keyWidth,
                rowHeight = row_height,
                density = theme.density,
            )
        }

        /**
         * The frame a key with per-key styling ([KeyboardData.Key.style]) draws in [role], and its
         * own gaps. Precedence: the key's (row-merged) attributes > the layout overrides > the
         * look > the global settings; colours: the key's > the role's. Built once per
         * (style, role) and cached for this Computed's lifetime (one keyboard size and config).
         */
        class StyledFrame(
            @JvmField val key: Key,
            @JvmField val horizontalMargin: Float,
            @JvmField val verticalMargin: Float,
        )

        private val styledFrames = HashMap<KeyStyle, Array<StyledFrame?>>()

        fun styledFrame(role: KeyRole, style: KeyStyle): StyledFrame {
            val byRole = styledFrames.getOrPut(style) { arrayOfNulls(KeyRole.entries.size) }
            byRole[role.ordinal]?.let { return it }
            val g = if (style.geometry.isEmpty()) geometry else geometryFor(layoutOverrides + style.geometry)
            val activated = role == KeyRole.ACTIVATED || role == KeyRole.LOCKED
            val fill = (if (activated) style.backgroundPressed else style.background) ?: roleFill(role)
            val frame = StyledFrame(
                key = Key(
                    theme, config, keyWidth, activated, fill, g,
                    labelColorOverride = style.labelColor ?: roleLabelColor(role),
                    borderColorOverride = style.borderColor ?: 0,
                    pressedLabelColorOverride = style.labelColorPressed ?: 0,
                ),
                horizontalMargin = g.horizontalMargin,
                verticalMargin = g.verticalMargin,
            )
            byRole[role.ordinal] = frame
            return frame
        }

        /** The fill of [role]'s frame (what [keyForRole] draws), before key opacity. */
        private fun roleFill(role: KeyRole): Int = when (role) {
            KeyRole.NORMAL, KeyRole.LETTER -> theme.colorKey
            KeyRole.ACTIVATED -> theme.colorKeyActivated
            KeyRole.LOCKED -> theme.colorKeyLocked
            KeyRole.MODIFIER -> theme.colorKeyModifier
            KeyRole.SPECIAL -> theme.colorKeySpecial
            KeyRole.ENTER -> theme.colorKeyEnter
            KeyRole.ACTION -> theme.colorKeyAction
            KeyRole.LATCHED -> theme.colorKeyLatched.takeIf { it != 0 } ?: theme.colorKeyActivated
        }

        /** The look label colour of [role]'s frame (0 = none), as [keyForRole]'s frames carry it. */
        private fun roleLabelColor(role: KeyRole): Int = when (role) {
            KeyRole.MODIFIER, KeyRole.SPECIAL -> theme.modifierLabelColor
            KeyRole.ENTER -> theme.enterLabelColor
            KeyRole.ACTION -> theme.actionLabelColor
            KeyRole.LETTER -> theme.letterKeyLabelColor
            KeyRole.LATCHED -> theme.latchedLabelColor
            else -> 0
        }

        /**
         * H-4: which computed key frame a key renders with — pure so the mapping is
         * pinnable off-device (the draw loop feeds it kind + pointer state).
         */
        enum class KeyRole { NORMAL, ACTIVATED, LOCKED, MODIFIER, SPECIAL, ENTER, ACTION, LETTER, LATCHED }

        /** The computed frame for [role] (see [roleOf]). */
        fun keyForRole(role: KeyRole): Key = when (role) {
            KeyRole.NORMAL -> key
            KeyRole.ACTIVATED -> key_activated
            KeyRole.LOCKED -> key_locked
            KeyRole.MODIFIER -> key_modifier
            KeyRole.SPECIAL -> key_special
            KeyRole.ENTER -> key_enter
            KeyRole.ACTION -> key_action
            KeyRole.LETTER -> key_letter
            KeyRole.LATCHED -> key_latched
        }

        class Key(
            theme: Theme,
            config: Config,
            keyWidth: Float,
            activated: Boolean,
            /**
             * H-4: per-role background override (0 = none — same "unset colour"
             * convention the rest of the theme plumbing uses). Border treatment still
             * follows [activated].
             */
            bgColorOverride: Int = 0,
            /**
             * Border radius/width and shadow to draw with. Null (direct construction, e.g. the
             * subkey popover tests) derives them from the user's settings as before.
             */
            geometry: KeyboardLookGeometry.Effective? = null,
            /** Main label colour of this frame at rest; 0 = the theme's label colour. */
            @JvmField val labelColorOverride: Int = 0,
            /** Border colour of this frame (per-key `key_border_color`); 0 = the theme's. */
            borderColorOverride: Int = 0,
            /** Main label colour while the key is pressed (per-key style); 0 = the theme's. */
            @JvmField val pressedLabelColorOverride: Int = 0,
        ) {
            @JvmField
            val bg_paint = Paint()
            @JvmField
            val border_paint: Paint
            @JvmField
            val border_width: Float
            @JvmField
            val border_radius: Float
            /** Shadow under the key (a look's), or null; drawn [shadow_dy] px below the frame. */
            @JvmField
            val shadow_paint: Paint?
            @JvmField
            val shadow_dy: Float
            private val _label_paint: Paint
            private val _special_label_paint: Paint
            private val _sublabel_paint: Paint
            private val _special_sublabel_paint: Paint
            private val _label_alpha_bits: Int

            init {
                bg_paint.color = when {
                    bgColorOverride != 0 -> bgColorOverride
                    activated -> theme.colorKeyActivated
                    else -> theme.colorKey
                }

                if (geometry != null) {
                    border_radius = geometry.borderRadius
                    border_width = if (activated) geometry.borderWidthActivated else geometry.borderWidth
                } else if (config.borderConfig) {
                    border_radius = config.customBorderRadius * keyWidth
                    border_width = config.customBorderLineWidth
                } else {
                    border_radius = theme.keyBorderRadius
                    border_width = if (activated) theme.keyBorderWidthActivated else theme.keyBorderWidth
                }
                shadow_dy = geometry?.shadowDy ?: 0f
                shadow_paint = if (shadow_dy > 0f && theme.keyShadowColor != 0) {
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = theme.keyShadowColor
                        // Same opacity contract as the key fill (keyOpacity owns the alpha).
                        alpha = (((theme.keyShadowColor ushr 24) and 0xFF) * config.keyOpacity) / 255
                    }
                } else null

                bg_paint.alpha = if (activated) config.keyActivatedOpacity else config.keyOpacity
                // H-4: the activated frame's border uses the Theme-Creator's "Border
                // Activated" colour (XML themes default it to keyBorderColorTop).
                border_paint = init_border_paint(
                    config,
                    border_width,
                    when {
                        borderColorOverride != 0 -> borderColorOverride
                        activated -> theme.keyBorderColorActivated
                        else -> theme.keyBorderColorTop
                    }
                )
                _label_paint = init_label_paint(config, LookTypeface.forWeight(geometry?.labelWeight))
                _special_label_paint = init_label_paint(config, _key_font)
                // A look's hint weight (Fluent's light hints); null = the default face.
                _sublabel_paint = init_label_paint(config, LookTypeface.forWeight(theme.lookHintStyle?.weight))
                _special_sublabel_paint = init_label_paint(config, _key_font)
                _label_alpha_bits = (config.labelBrightness and 0xFF) shl 24
            }

            fun label_paint(special_font: Boolean, color: Int, text_size: Float): Paint {
                val p = if (special_font) _special_label_paint else _label_paint
                p.color = (color and 0x00FFFFFF) or _label_alpha_bits
                p.textSize = text_size
                return p
            }

            fun sublabel_paint(
                special_font: Boolean,
                color: Int,
                text_size: Float,
                align: Paint.Align
            ): Paint {
                val p = if (special_font) _special_sublabel_paint else _sublabel_paint
                p.color = (color and 0x00FFFFFF) or _label_alpha_bits
                p.textSize = text_size
                p.textAlign = align
                return p
            }
        }

        companion object {
            /**
             * H-4: map a key's [KeyValue.Kind] + pointer state to its frame role.
             * Pressed state wins (locked > activated); at rest, modifiers get the
             * Key Modifier fill and action keys (key events, IME events, editing
             * actions — enter, backspace, switchers, copy/paste) the Key Special fill.
             */
            @JvmStatic
            fun roleOf(kind: KeyValue.Kind?, isKeyDown: Boolean, isLocked: Boolean): KeyRole = when {
                isKeyDown && isLocked -> KeyRole.LOCKED
                isKeyDown -> KeyRole.ACTIVATED
                kind == KeyValue.Kind.Modifier -> KeyRole.MODIFIER
                kind == KeyValue.Kind.Keyevent ||
                    kind == KeyValue.Kind.Event ||
                    kind == KeyValue.Kind.Editing -> KeyRole.SPECIAL
                else -> KeyRole.NORMAL
            }

            /**
             * [roleOf] for a key value, as the kind-based mapping except, at rest:
             *  - plain Enter (`KEYCODE_ENTER`) → [KeyRole.ENTER];
             *  - the editor-action key (`Event.ACTION`: the Enter replacement in Go / Search /
             *    Send / Done fields, LayoutModifier) → [KeyRole.ACTION];
             *  - a character in [functionChars] (a look's, e.g. Gboard's `,.`) → [KeyRole.MODIFIER];
             *  - a function key in [letterKeys] (a look's, e.g. Gboard's emoji key) → [KeyRole.LETTER].
             * Every non-look theme gives the ENTER and ACTION frames the special fill and has no
             * function characters, so it draws exactly as before.
             */
            @JvmStatic
            @JvmOverloads
            fun roleOf(
                kv: KeyValue?, isKeyDown: Boolean, isLocked: Boolean, functionChars: String = "",
                letterKeys: Set<LookLetterKey> = emptySet(),
                /** [isRestingLatch]: draw a latched key with the [KeyRole.LATCHED] frame (locked still wins). */
                restingLatch: Boolean = false,
            ): KeyRole {
                if (restingLatch && isKeyDown && !isLocked) return KeyRole.LATCHED
                val role = roleOf(kv?.getKind(), isKeyDown, isLocked)
                if (kv == null) return role
                return when {
                    role == KeyRole.SPECIAL && kv.getKind() == KeyValue.Kind.Keyevent &&
                        kv.getKeyevent() == android.view.KeyEvent.KEYCODE_ENTER -> KeyRole.ENTER
                    role == KeyRole.SPECIAL && kv.getKind() == KeyValue.Kind.Event &&
                        kv.getEvent() == KeyValue.Event.ACTION -> KeyRole.ACTION
                    role == KeyRole.NORMAL && functionChars.isNotEmpty() &&
                        kv.getKind() == KeyValue.Kind.Char && functionChars.indexOf(kv.getChar()) >= 0 -> KeyRole.MODIFIER
                    role == KeyRole.SPECIAL && LookLetterKey.EMOJI in letterKeys &&
                        kv.getKind() == KeyValue.Kind.Event && kv.getEvent() == KeyValue.Event.SWITCH_EMOJI -> KeyRole.LETTER
                    else -> role
                }
            }

            /**
             * True when a key's pointer [flags] (`Pointers.getKeyFlags`, -1 = none) say it is
             * latched but neither held nor locked — a one-shot or auto-capitalised Shift — and
             * the theme defines a fill for that ([hasLatchedFill]: `Theme.colorKeyLatched` != 0).
             * The real Gboard keeps Shift's function-key fill then (Pixel 2026-10-27).
             */
            @JvmStatic
            fun isRestingLatch(flags: Int, hasLatchedFill: Boolean): Boolean =
                hasLatchedFill && flags != -1 &&
                    (flags and Pointers.FLAG_P_LATCHED) != 0 && (flags and Pointers.FLAG_P_LOCKED) == 0

            /**
             * The main-label colour of a key drawn with a frame whose look label colour is
             * [override] (0 = none): the override replaces the plain label colour AND the
             * secondary colour — function keys such as shift, backspace and 123 carry
             * FLAG_SECONDARY, so their labels are normally drawn in the dimmed secondary colour
             * (Samsung light, 2026-10-27: "!#1" and the shift arrow came out hint-grey). Pressed,
             * locked and greyed labels (space) keep their colours.
             */
            @JvmStatic
            fun frameLabelColor(base: Int, override: Int, theme: Theme): Int =
                if (override != 0 && (base == theme.labelColor || base == theme.secondaryLabelColor)) override else base

            private fun init_border_paint(config: Config, border_width: Float, color: Int): Paint {
                return Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = border_width
                    // Audit H-6: colour FIRST, then alpha — setColor writes the full ARGB
                    // (the colour's own alpha included), so the reverse order discarded
                    // keyOpacity and borders stayed opaque on a translucent keyboard.
                    // Same contract as bg_paint in Key.init (color = …, then alpha = …).
                    setColor(color)
                    alpha = config.keyOpacity
                }
            }

            private fun init_label_paint(config: Config, font: Typeface?): Paint {
                return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    if (font != null) {
                        typeface = font
                    }
                }
            }
        }
    }

    companion object {
        private var _key_font: Typeface? = null

        @JvmStatic
        fun getKeyFont(context: Context): Typeface {
            if (_key_font == null) {
                _key_font = Typeface.createFromAsset(context.assets, "special_font.ttf")
            }
            return _key_font!!
        }
    }
}

/**
 * The suggestion bar's text colours, resolved against the bar background.
 *
 * Android-free (pure JVM testable), so it lives beside [Theme] rather than in it.
 */
object SuggestionBarPalette {
    /** Minimum contrast of any suggestion text role against the bar (WCAG 1.4.11). */
    const val MIN_CONTRAST = 3.0

    /** The three text roles the bar draws, all on the same background. */
    data class Colors(val text: Int, val highlight: Int, val secondary: Int)

    /**
     * Resolve the colours [Theme] hands the bar: each role keeps its theme colour when it reaches
     * [MIN_CONTRAST] against [background]; otherwise the highlight and prompt roles fall back to
     * the (resolved) text colour — the highlight is still told apart by its bold face — and the
     * text itself falls back to black or white, whichever contrasts more. Readable colours are
     * returned unchanged, so a theme that already works renders exactly as before.
     *
     * Needed because XML themes have no suggestion attrs: [Theme] borrows `colorLabelActivated`,
     * which those themes designed for `colorKeyActivated`, not for the bar's `colorKey`
     * (Cobalt/Pine/ePaper Black: the highlight equalled the background).
     */
    fun resolve(text: Int, highlight: Int, secondary: Int, background: Int): Colors {
        val readableText = if (readable(text, background)) text else {
            val black = 0xFF000000.toInt()
            val white = 0xFFFFFFFF.toInt()
            if (contrastRatio(black, background) >= contrastRatio(white, background)) black else white
        }
        return Colors(
            text = readableText,
            highlight = if (readable(highlight, background)) highlight else readableText,
            secondary = if (readable(secondary, background)) secondary else readableText,
        )
    }

    private fun readable(color: Int, background: Int): Boolean =
        contrastRatio(color, background) >= MIN_CONTRAST

    /** WCAG 2.x contrast ratio of two colours (alpha ignored), 1.0..21.0. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
