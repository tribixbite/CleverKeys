package tribixbite.cleverkeys.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * One colour role of a [ThemeSpec] — the union of the Theme Creator's 17 DIY colours
 * ([KeyboardColorScheme]) and the look-only roles of [LookPalette] (Enter / action / modifier
 * labels, key shadow, bar separators, pop-ups) plus the pressed-label colour XML themes carry.
 *
 * @property xmlName the attribute on a layout's `<theme>`, `<light>` and `<dark>` elements.
 * @property jsonKey the key inside a custom theme's JSON (`colors` keeps the DIY keys of
 *   [KeyboardColorScheme.toJson]; the look-only ones live in the `look` extension).
 * @property diy whether the role is one of the 17 [KeyboardColorScheme] fields.
 */
enum class ThemeColorRole(val xmlName: String, val jsonKey: String, val diy: Boolean) {
    KEY("key", "keyDefault", true),
    KEY_PRESSED("key_pressed", "keyActivated", true),
    KEY_LOCKED("key_locked", "keyLocked", true),
    KEY_MODIFIER("key_modifier", "keyModifier", true),
    KEY_SPECIAL("key_special", "keySpecial", true),
    LABEL("label", "keyLabel", true),
    SUB_LABEL("sub_label", "keySubLabel", true),
    SECONDARY_LABEL("secondary_label", "keySecondaryLabel", true),
    KEY_BORDER("border_color", "keyBorder", true),
    KEY_BORDER_PRESSED("border_color_pressed", "keyBorderActivated", true),
    SWIPE_TRAIL("swipe_trail", "swipeTrail", true),
    RIPPLE("ripple", "ripple", true),
    BAR_TEXT("bar_text", "suggestionText", true),
    BAR_BACKGROUND("bar_background", "suggestionBackground", true),
    BAR_HIGHLIGHT("bar_highlight", "suggestionHighConfidence", true),
    KEYBOARD_BACKGROUND("keyboard_background", "keyboardBackground", true),
    SURFACE("surface", "keyboardSurface", true),

    /** Label of a pressed key (XML themes' `colorLabelActivated`; others: [LABEL]). */
    LABEL_PRESSED("label_pressed", "labelPressed", false),
    /** Label of a locked modifier (Caps Lock). */
    LOCKED_LABEL("locked_label", "lockedLabel", false),
    /** Plain Enter fill (default: [KEY_SPECIAL]). */
    ENTER_KEY("enter_key", "enterKey", false),
    /** Label on plain Enter (default: the ordinary label colours). */
    ENTER_LABEL("enter_label", "enterLabel", false),
    /** Fill of the editor-action key of Go / Search / Send / Done fields (default: [KEY_SPECIAL]). */
    ACTION_KEY("action_key", "actionKey", false),
    ACTION_LABEL("action_label", "actionLabel", false),
    /** Label on modifier and function keys (default: the ordinary label colours). */
    MODIFIER_LABEL("modifier_label", "modifierLabel", false),
    /** Shadow under each key (drawn when `shadow_dy` > 0). */
    KEY_SHADOW("key_shadow", "keyShadow", false),
    /** Separator between suggestions (alpha 0 = none; default: sub-label at alpha 100). */
    BAR_SEPARATOR("bar_separator", "barSeparator", false),
    /** Key-press preview and long-press popover fill (default: [KEY]). */
    POPUP("popup", "popup", false),
    POPUP_LABEL("popup_label", "popupLabel", false),
    /** The selected popover alternative (default: [KEY_LOCKED]). */
    POPUP_SELECTED("popup_selected", "popupSelected", false),
    POPUP_SELECTED_LABEL("popup_selected_label", "popupSelectedLabel", false),
    /**
     * Fill of a latched modifier at rest — one-shot or auto-capitalised Shift (default: the
     * pressed fill [KEY_PRESSED], as every non-look theme). Gboard and Samsung keep the
     * function-key fill (LookPalette.latchedKey).
     */
    LATCHED_KEY("latched_key", "latchedKey", false),
    /** Label / icon colour on [LATCHED_KEY] (Samsung's blue Shift); only drawn with it. */
    LATCHED_LABEL("latched_label", "latchedLabel", false);

    companion object {
        private val BY_XML = entries.associateBy { it.xmlName }
        private val BY_JSON = entries.associateBy { it.jsonKey }
        fun ofXml(name: String): ThemeColorRole? = BY_XML[name]
        fun ofJson(key: String): ThemeColorRole? = BY_JSON[key]
        val DIY: List<ThemeColorRole> = entries.filter { it.diy }
        val LOOK_ONLY: List<ThemeColorRole> = entries.filter { !it.diy }
    }
}

/**
 * A complete or PARTIAL keyboard appearance: every DIY colour, every look colour, look geometry
 * and policies, with optional light / dark colour variants. A field left null (or a role absent
 * from a colour map) is not defined by this spec and is inherited from the theme below it.
 *
 * Three producers, one model (docs/specs/layout-system.md "Layout themes"):
 *  - a layout XML's `<theme>` element ([ThemeSpecXml]) — the EMBEDDED theme, inheriting from the
 *    user's global theme;
 *  - a custom theme's `look` extension (`CustomTheme.extension`, JSON [ThemeSpecJson]);
 *  - a keyboard look ([ThemeSpecs.ofLook]) for export.
 *
 * Colours are ARGB ints. Geometry uses [LookGeometry]'s units (gaps as fractions of key width /
 * row height, dp, label scale, weight). Pure JVM: runs in `runPureTests` (`ThemeSpecTest`).
 *
 * @property colors colours for both system modes.
 * @property light colours that replace [colors] while the system is in light mode.
 * @property dark colours that replace [colors] while the system is in dark mode.
 * @property pillKeys null = not defined; empty = defined as "no pills".
 * @property bar the suggestion-bar arrangement ([LookBar]); null = not defined (scrolling chips).
 * @property action the editor-action key's style ([LookActionStyle]); null = not defined.
 * @property spaceLanguage whether the space bar shows the language name; null = not defined (no).
 */
data class ThemeSpec(
    val name: String? = null,
    val colors: Map<ThemeColorRole, Int> = emptyMap(),
    val light: Map<ThemeColorRole, Int> = emptyMap(),
    val dark: Map<ThemeColorRole, Int> = emptyMap(),
    val geometry: LookGeometry = LookGeometry(),
    val functionChars: String? = null,
    val legends: LookLegends? = null,
    val pillKeys: Set<LookPillKey>? = null,
    val letterKeys: Set<LookLetterKey>? = null,
    val preview: LookPreview? = null,
    val hintStyle: LookHintStyle? = null,
    val dynamicColor: Boolean? = null,
    val bar: LookBar? = null,
    val action: LookActionStyle? = null,
    val spaceLanguage: Boolean? = null,
) {
    /** True when the spec defines nothing at all (a `<theme/>` with no attributes). */
    val isEmpty: Boolean
        get() = colors.isEmpty() && light.isEmpty() && dark.isEmpty() && geometry == LookGeometry() &&
            functionChars == null && legends == null && pillKeys == null && letterKeys == null &&
            preview == null && hintStyle == null && dynamicColor == null && bar == null && action == null &&
            spaceLanguage == null

    /** The colours for one system mode: [colors] with that mode's variant on top. */
    fun colorsFor(systemLight: Boolean): Map<ThemeColorRole, Int> = colors + (if (systemLight) light else dark)
}

/**
 * Everything the keyboard view needs from a resolved (flattened, complete) spec: the 17-field
 * scheme every runtime theme renders through, the look palette for the look-only roles, and the
 * look geometry and policies. `ThemeProvider` turns it into a `Theme`.
 *
 * @property popups whether the look pop-up colours apply (a preview shape or a pop-up colour is
 *   defined); otherwise the long-press popover keeps its non-look colours.
 * @property labelPressed label colour of a pressed key; 0 = the label colour (runtime default).
 */
data class RenderedThemeSpec(
    val scheme: KeyboardColorScheme,
    val palette: LookPalette,
    val geometry: LookGeometry,
    val functionChars: String,
    val legends: LookLegends?,
    val pillKeys: Set<LookPillKey>,
    val letterKeys: Set<LookLetterKey>,
    val preview: LookPreview?,
    val hintStyle: LookHintStyle?,
    val popups: Boolean,
    val dynamicColor: Boolean,
    val labelPressed: Int,
    /** The suggestion-bar arrangement; null = CleverKeys' scrolling chips. */
    val bar: LookBar? = null,
    /** The editor-action key's style; null = text on the action colours. */
    val action: LookActionStyle? = null,
    /** Whether the space bar shows the language name. */
    val spaceLanguage: Boolean = false,
)

/** Resolution of specs: flatten a mode, layer a spec over its base, render, convert. Pure. */
object ThemeSpecs {

    /** [spec] for one system mode: the variant folded into [ThemeSpec.colors], no variants left. */
    fun flatten(spec: ThemeSpec, systemLight: Boolean): ThemeSpec =
        spec.copy(colors = spec.colorsFor(systemLight), light = emptyMap(), dark = emptyMap())

    /**
     * [top] over [base], field by field, for one system mode (both flattened first, so a base's
     * light variant can never beat the top's common colour). Unset fields of [top] keep [base]'s.
     */
    fun resolve(top: ThemeSpec, base: ThemeSpec, systemLight: Boolean): ThemeSpec {
        val t = flatten(top, systemLight)
        val b = flatten(base, systemLight)
        return ThemeSpec(
            name = t.name ?: b.name,
            colors = b.colors + t.colors,
            geometry = LookGeometry(
                keyGapHorizontal = t.geometry.keyGapHorizontal ?: b.geometry.keyGapHorizontal,
                keyGapVertical = t.geometry.keyGapVertical ?: b.geometry.keyGapVertical,
                cornerRadiusDp = t.geometry.cornerRadiusDp ?: b.geometry.cornerRadiusDp,
                borderWidthDp = t.geometry.borderWidthDp ?: b.geometry.borderWidthDp,
                shadowDyDp = t.geometry.shadowDyDp ?: b.geometry.shadowDyDp,
                labelScale = t.geometry.labelScale ?: b.geometry.labelScale,
                labelWeight = t.geometry.labelWeight ?: b.geometry.labelWeight,
            ),
            functionChars = t.functionChars ?: b.functionChars,
            legends = t.legends ?: b.legends,
            pillKeys = t.pillKeys ?: b.pillKeys,
            letterKeys = t.letterKeys ?: b.letterKeys,
            preview = t.preview ?: b.preview,
            hintStyle = t.hintStyle ?: b.hintStyle,
            dynamicColor = t.dynamicColor ?: b.dynamicColor,
            bar = t.bar ?: b.bar,
            action = t.action ?: b.action,
            spaceLanguage = t.spaceLanguage ?: b.spaceLanguage,
        )
    }

    /**
     * Render a FLATTENED spec. DIY roles it lacks fall back to [fallback] (the default dark
     * scheme; a base from `ThemeProvider` always defines all 17). Look-only roles left unset take
     * the values that make the keyboard draw exactly as a runtime theme without them: Enter and
     * action fills = the special fill (0 here; `Theme` substitutes its composited special colour),
     * label overrides 0 (none), no shadow, the usual sub-label separator, no pop-up colours.
     */
    fun render(flat: ThemeSpec, fallback: KeyboardColorScheme = darkKeyboardColorScheme()): RenderedThemeSpec {
        val c = flat.colors
        val fb = fromScheme(fallback).colors
        fun diy(role: ThemeColorRole): Int = c[role] ?: fb.getValue(role)
        fun color(role: ThemeColorRole) = Color(diy(role))
        val scheme = KeyboardColorScheme(
            keyDefault = color(ThemeColorRole.KEY),
            keyActivated = color(ThemeColorRole.KEY_PRESSED),
            keyLocked = color(ThemeColorRole.KEY_LOCKED),
            keyModifier = color(ThemeColorRole.KEY_MODIFIER),
            keySpecial = color(ThemeColorRole.KEY_SPECIAL),
            keyLabel = color(ThemeColorRole.LABEL),
            keySubLabel = color(ThemeColorRole.SUB_LABEL),
            keySecondaryLabel = color(ThemeColorRole.SECONDARY_LABEL),
            keyBorder = color(ThemeColorRole.KEY_BORDER),
            keyBorderActivated = color(ThemeColorRole.KEY_BORDER_PRESSED),
            swipeTrail = color(ThemeColorRole.SWIPE_TRAIL),
            ripple = color(ThemeColorRole.RIPPLE),
            suggestionText = color(ThemeColorRole.BAR_TEXT),
            suggestionBackground = color(ThemeColorRole.BAR_BACKGROUND),
            suggestionHighConfidence = color(ThemeColorRole.BAR_HIGHLIGHT),
            keyboardBackground = color(ThemeColorRole.KEYBOARD_BACKGROUND),
            keyboardSurface = color(ThemeColorRole.SURFACE),
        )
        val key = diy(ThemeColorRole.KEY)
        val subLabel = diy(ThemeColorRole.SUB_LABEL)
        val palette = LookPalette(
            keyboardBackground = diy(ThemeColorRole.KEYBOARD_BACKGROUND),
            key = key,
            keyPressed = diy(ThemeColorRole.KEY_PRESSED),
            keyLocked = compositeOver(diy(ThemeColorRole.KEY_LOCKED), key),
            lockedLabel = c[ThemeColorRole.LOCKED_LABEL] ?: diy(ThemeColorRole.LABEL),
            modifierKey = compositeOver(diy(ThemeColorRole.KEY_MODIFIER), key),
            enterKey = c[ThemeColorRole.ENTER_KEY] ?: 0,
            enterLabel = c[ThemeColorRole.ENTER_LABEL] ?: 0,
            actionKey = c[ThemeColorRole.ACTION_KEY] ?: 0,
            label = diy(ThemeColorRole.LABEL),
            modifierLabel = c[ThemeColorRole.MODIFIER_LABEL] ?: 0,
            actionLabel = c[ThemeColorRole.ACTION_LABEL] ?: 0,
            hint = subLabel,
            keyShadow = c[ThemeColorRole.KEY_SHADOW] ?: 0,
            keyBorder = diy(ThemeColorRole.KEY_BORDER),
            barBackground = diy(ThemeColorRole.BAR_BACKGROUND),
            barText = diy(ThemeColorRole.BAR_TEXT),
            barHighlight = diy(ThemeColorRole.BAR_HIGHLIGHT),
            barSeparator = c[ThemeColorRole.BAR_SEPARATOR] ?: defaultSeparator(subLabel),
            // 0 = the user's trail colour (an XML base defines none).
            swipeTrail = c[ThemeColorRole.SWIPE_TRAIL] ?: 0,
            popup = c[ThemeColorRole.POPUP] ?: 0,
            popupLabel = c[ThemeColorRole.POPUP_LABEL] ?: 0,
            popupSelected = c[ThemeColorRole.POPUP_SELECTED] ?: 0,
            popupSelectedLabel = c[ThemeColorRole.POPUP_SELECTED_LABEL] ?: 0,
            latchedKey = c[ThemeColorRole.LATCHED_KEY] ?: 0,
            latchedLabel = c[ThemeColorRole.LATCHED_LABEL] ?: 0,
        )
        val preview = flat.preview?.takeIf { it.shape != LookPreviewShape.NONE }
        val popupRoles = listOf(
            ThemeColorRole.POPUP, ThemeColorRole.POPUP_LABEL, ThemeColorRole.POPUP_SELECTED,
            ThemeColorRole.POPUP_SELECTED_LABEL,
        )
        return RenderedThemeSpec(
            scheme = scheme,
            palette = palette,
            geometry = flat.geometry,
            functionChars = flat.functionChars ?: "",
            legends = flat.legends,
            pillKeys = flat.pillKeys ?: emptySet(),
            letterKeys = flat.letterKeys ?: emptySet(),
            preview = preview,
            hintStyle = flat.hintStyle,
            popups = preview != null || popupRoles.any { it in c },
            dynamicColor = flat.dynamicColor == true,
            labelPressed = c[ThemeColorRole.LABEL_PRESSED] ?: 0,
            bar = flat.bar,
            action = flat.action,
            spaceLanguage = flat.spaceLanguage == true,
        )
    }

    /** The 17 DIY roles of [scheme] as a spec (a decorative or DIY theme, or the fallback). */
    fun fromScheme(scheme: KeyboardColorScheme, name: String? = null): ThemeSpec =
        ThemeSpec(name = name, colors = schemeColors(scheme))

    /** The DIY role → ARGB map of [scheme] (exact: `Color(int).toArgb()` round-trips). */
    fun schemeColors(scheme: KeyboardColorScheme): Map<ThemeColorRole, Int> = linkedMapOf(
        ThemeColorRole.KEY to argb(scheme.keyDefault),
        ThemeColorRole.KEY_PRESSED to argb(scheme.keyActivated),
        ThemeColorRole.KEY_LOCKED to argb(scheme.keyLocked),
        ThemeColorRole.KEY_MODIFIER to argb(scheme.keyModifier),
        ThemeColorRole.KEY_SPECIAL to argb(scheme.keySpecial),
        ThemeColorRole.LABEL to argb(scheme.keyLabel),
        ThemeColorRole.SUB_LABEL to argb(scheme.keySubLabel),
        ThemeColorRole.SECONDARY_LABEL to argb(scheme.keySecondaryLabel),
        ThemeColorRole.KEY_BORDER to argb(scheme.keyBorder),
        ThemeColorRole.KEY_BORDER_PRESSED to argb(scheme.keyBorderActivated),
        ThemeColorRole.SWIPE_TRAIL to argb(scheme.swipeTrail),
        ThemeColorRole.RIPPLE to argb(scheme.ripple),
        ThemeColorRole.BAR_TEXT to argb(scheme.suggestionText),
        ThemeColorRole.BAR_BACKGROUND to argb(scheme.suggestionBackground),
        ThemeColorRole.BAR_HIGHLIGHT to argb(scheme.suggestionHighConfidence),
        ThemeColorRole.KEYBOARD_BACKGROUND to argb(scheme.keyboardBackground),
        ThemeColorRole.SURFACE to argb(scheme.keyboardSurface),
    )

    /**
     * One mode of a look as a flat spec: [palette] (the look's palette for that mode, wallpaper
     * colours already applied) mapped the way `KeyboardLookSchemes` maps it, plus the look's
     * geometry and policies. [ThemeSpecs.render] of it draws the look as `ThemeProvider.loadLook`.
     */
    fun fromLook(look: KeyboardLook, palette: LookPalette): ThemeSpec = ThemeSpec(
        colors = paletteColors(palette),
        geometry = look.geometry,
        functionChars = look.functionChars,
        legends = look.legends,
        pillKeys = look.pillKeys,
        letterKeys = look.letterKeys,
        preview = look.preview,
        hintStyle = look.hintStyle,
        bar = look.bar,
        action = look.action,
        spaceLanguage = look.spaceLanguage.takeIf { it },
    )

    /**
     * A look as a two-mode spec for export (Theme Manager → "Export as layout XML"): roles equal
     * in both palettes go to [ThemeSpec.colors], the rest to [ThemeSpec.light] / [ThemeSpec.dark].
     */
    fun ofLook(look: KeyboardLook, name: String? = null): ThemeSpec {
        val l = paletteColors(look.light)
        val d = paletteColors(look.dark)
        val common = LinkedHashMap<ThemeColorRole, Int>()
        val light = LinkedHashMap<ThemeColorRole, Int>()
        val dark = LinkedHashMap<ThemeColorRole, Int>()
        for (role in ThemeColorRole.entries) {
            val lv = l[role]
            val dv = d[role]
            when {
                lv != null && lv == dv -> common[role] = lv
                else -> {
                    lv?.let { light[role] = it }
                    dv?.let { dark[role] = it }
                }
            }
        }
        return fromLook(look, look.light).copy(
            name = name, colors = common, light = light, dark = dark,
            dynamicColor = look.dynamicColor.takeIf { it },
        )
    }

    /** [palette] as role colours; pop-up and latched roles only where the look defines them (non-zero). */
    fun paletteColors(palette: LookPalette): Map<ThemeColorRole, Int> {
        val out = linkedMapOf(
            ThemeColorRole.KEY to palette.key,
            ThemeColorRole.KEY_PRESSED to palette.keyPressed,
            ThemeColorRole.KEY_LOCKED to palette.keyLocked,
            ThemeColorRole.KEY_MODIFIER to palette.modifierKey,
            ThemeColorRole.KEY_SPECIAL to palette.modifierKey,
            ThemeColorRole.LABEL to palette.label,
            ThemeColorRole.SUB_LABEL to palette.hint,
            ThemeColorRole.SECONDARY_LABEL to palette.hint,
            ThemeColorRole.KEY_BORDER to palette.keyBorder,
            ThemeColorRole.KEY_BORDER_PRESSED to palette.keyBorder,
            ThemeColorRole.SWIPE_TRAIL to palette.swipeTrail,
            ThemeColorRole.RIPPLE to palette.keyPressed,
            ThemeColorRole.BAR_TEXT to palette.barText,
            ThemeColorRole.BAR_BACKGROUND to palette.barBackground,
            ThemeColorRole.BAR_HIGHLIGHT to palette.barHighlight,
            ThemeColorRole.KEYBOARD_BACKGROUND to palette.keyboardBackground,
            ThemeColorRole.SURFACE to palette.key,
            ThemeColorRole.LOCKED_LABEL to palette.lockedLabel,
            ThemeColorRole.ENTER_KEY to palette.enterKey,
            ThemeColorRole.ENTER_LABEL to palette.enterLabel,
            ThemeColorRole.ACTION_KEY to palette.actionKey,
            ThemeColorRole.ACTION_LABEL to palette.actionLabel,
            ThemeColorRole.MODIFIER_LABEL to palette.modifierLabel,
            ThemeColorRole.KEY_SHADOW to palette.keyShadow,
            ThemeColorRole.BAR_SEPARATOR to palette.barSeparator,
        )
        if (palette.popup != 0) out[ThemeColorRole.POPUP] = palette.popup
        if (palette.popupLabel != 0) out[ThemeColorRole.POPUP_LABEL] = palette.popupLabel
        if (palette.popupSelected != 0) out[ThemeColorRole.POPUP_SELECTED] = palette.popupSelected
        if (palette.popupSelectedLabel != 0) out[ThemeColorRole.POPUP_SELECTED_LABEL] = palette.popupSelectedLabel
        if (palette.latchedKey != 0) out[ThemeColorRole.LATCHED_KEY] = palette.latchedKey
        if (palette.latchedLabel != 0) out[ThemeColorRole.LATCHED_LABEL] = palette.latchedLabel
        return out
    }

    /** The separator a non-look theme draws: the sub-label colour at alpha 100/255. */
    fun defaultSeparator(subLabel: Int): Int = (subLabel and 0x00FFFFFF) or (100 shl 24)

    /** [over] composited over an opaque [under] (source-over), as an opaque ARGB int. */
    fun compositeOver(over: Int, under: Int): Int {
        val a = (over ushr 24) and 0xFF
        if (a == 0xFF) return over
        fun ch(shift: Int): Int {
            val o = (over ushr shift) and 0xFF
            val u = (under ushr shift) and 0xFF
            return ((o * a + u * (255 - a)) + 127) / 255
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun argb(c: Color): Int {
        // Exact inverse of Color(Int) for sRGB colours (Compose stores them packed).
        val v = c.value.toLong() ushr 32
        return v.toInt()
    }
}

/** Shared value parsing for theme and per-key style attributes (pure; lenient callers). */
object ThemeValues {

    /** `#RRGGBB` (opaque) or `#AARRGGBB`; null when malformed. */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = hex.toLong(16)
        return if (hex.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /** `#RRGGBB` when opaque, else `#AARRGGBB` (upper case). */
    fun formatColor(argb: Int): String =
        if ((argb ushr 24) == 0xFF) String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
        else String.format(Locale.ROOT, "#%08X", argb)

    /** A float in [range], or null. */
    fun parseFloat(raw: String?, range: ClosedFloatingPointRange<Float>): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return v.takeIf { it in range }
    }

    /** `true` / `false` (any case), or null. */
    fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Shortest text that parses back to exactly [v] (`8` rather than `8.0`). */
    fun formatFloat(v: Float): String =
        if (v == Math.rint(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e7f) v.toLong().toString() else v.toString()
}
