package tribixbite.cleverkeys.theme

/**
 * A keyboard LOOK: a theme that carries geometry as well as colours, with a light and a dark
 * palette that follow the system (like Monet Auto). The four built-in looks reproduce the
 * default appearance of popular keyboards ([KeyboardLooks]); the familiar-style layouts bind to
 * them through their XML `theme="look_…"` default, so switching layouts switches the whole look.
 *
 * A look is a FOURTH theme kind beside built-in XML, `decorative_` and `custom_` themes, told
 * apart by the [KeyboardLooks.ID_PREFIX] id prefix. It renders through the runtime-theme path
 * (`Theme(context, scheme, …)`), so per-layout binding, Theme Manager selection, the backup's
 * `layouts` blob and the stale-view tracker need no parallel machinery.
 *
 * Pure JVM (ARGB ints, no Android or Compose types) so the definitions, their precedence rules
 * and their contrast run in `runPureTests`. Spec: docs/specs/layout-system.md "Keyboard looks";
 * research and sources: docs/research/2026-10-27-familiar-keyboard-looks.md.
 */
data class KeyboardLook(
    /** Stored theme id (`theme` preference / layout binding), always [KeyboardLooks.ID_PREFIX]-prefixed. */
    val id: String,
    /** Colours while the system is in light mode. */
    val light: LookPalette,
    /** Colours while the system is in dark mode. */
    val dark: LookPalette,
    /** Geometry the look defines; undefined attributes keep the user's appearance settings. */
    val geometry: LookGeometry,
    /**
     * Character keys this look draws as FUNCTION keys ([LookPalette.modifierKey] fill and label),
     * e.g. Gboard's comma and period. Empty for most looks.
     */
    val functionChars: String = "",
    /**
     * Which corner labels (gesture hints) the look draws ([LookLegendPolicy]): the products show
     * none (iOS, SwiftKey, Samsung) or only the top-row digits (Gboard). Drawing only — every
     * gesture, TalkBack label and accessibility short-swipe action is unchanged.
     */
    val legends: LookLegends = LookLegends.ALL,
    /** Keys drawn as full pills (radius = half the shorter side), e.g. Gboard's ?123 and Enter. */
    val pillKeys: Set<LookPillKey> = emptySet(),
    /**
     * Function keys this look draws as LETTER keys ([LookPalette.key] fill, [LookPalette.label]
     * glyph), e.g. Gboard's emoji key (Pixel 2026-10-28: the real Gboard draws it like a letter).
     */
    val letterKeys: Set<LookLetterKey> = emptySet(),
    /** The key-press preview this look's product shows ([LookPreviewShape.NONE] = none). */
    val preview: LookPreview = LookPreview.NONE,
    /** How corner labels look when the user shows them on this look ([LookHintStyle]). */
    val hintStyle: LookHintStyle = LookHintStyle.DEFAULT,
    /**
     * Whether the look's palette follows the system's dynamic (wallpaper) colours on API 31+
     * ([LookDynamicColors]); the fixed [light] / [dark] palettes are the fallback.
     */
    val dynamicColor: Boolean = false,
    /**
     * How the suggestion bar is arranged while the look is effective ([LookBar]: three slots,
     * best candidate centred); null = CleverKeys' scrolling chips.
     */
    val bar: LookBar? = null,
    /**
     * How the editor-action key looks per action ([LookActionStyle]: icon or text, action or
     * Enter colours); null = the action's text on the action colours.
     */
    val action: LookActionStyle? = null,
    /**
     * Whether the space bar shows the active language's name ("English (US)", Samsung Keyboard;
     * [SpaceLanguageLabel]) while the look is effective. Appearance → "Language name on the space
     * bar" (`look_space_language`) turns it off.
     */
    val spaceLanguage: Boolean = false,
    /**
     * Marks the product draws on particular keys in place of their gesture hints
     * ([LookKeyLegend]: SwiftKey's mic over the comma, ",!?" over the period). Drawn only while
     * the look hides corner labels ([LookKeyLegends.legendFor]); empty for most looks.
     */
    val keyLegends: List<LookKeyLegend> = emptyList(),
    /**
     * Whether Shift draws a FILLED arrow while shifted, and a filled arrow over a bar in Caps
     * Lock (Gboard, [LookShiftGlyph]); the outline glyph at rest either way.
     */
    val shiftFilled: Boolean = false,
) {
    /** The palette for the current system mode ([systemLight] = `Config.isSystemLight`). */
    fun palette(systemLight: Boolean): LookPalette = if (systemLight) light else dark
}

/**
 * One system mode's colours of a [KeyboardLook], as opaque ARGB ints.
 *
 * Roles follow `Theme.Computed.roleOf`: letter keys (and space, comma, period: character keys)
 * use [key]; modifiers (shift) and function keys (backspace, 123, emoji, switchers) use
 * [modifierKey]; plain Enter uses [enterKey]; the editor-action key that replaces Enter in a
 * Go / Search / Send field uses [actionKey] (iOS's blue return, Gboard's highlighted Enter).
 */
data class LookPalette(
    val keyboardBackground: Int,
    /** Letter-key fill. */
    val key: Int,
    /** Any key's fill while pressed. */
    val keyPressed: Int,
    /** Caps Lock (locked modifier) fill. */
    val keyLocked: Int,
    /** Label colour on a locked modifier ([keyLocked]). */
    val lockedLabel: Int,
    /** Modifier and function-key fill (shift, backspace, 123, emoji, switchers). */
    val modifierKey: Int,
    /** Plain Enter (new line) fill. */
    val enterKey: Int,
    /** Label / icon colour on [enterKey]. */
    val enterLabel: Int,
    /** Editor-action key fill (Go / Search / Send / Done fields). */
    val actionKey: Int,
    /** Letter label colour. */
    val label: Int,
    /** Label colour on [modifierKey] keys. */
    val modifierLabel: Int,
    /** Label / icon colour on [actionKey]. */
    val actionLabel: Int,
    /** Corner hint (secondary character) colour. */
    val hint: Int,
    /** Colour of the shadow drawn under each key; 0 = no shadow. */
    val keyShadow: Int,
    /** Key outline colour; only drawn when [LookGeometry.borderWidthDp] is positive. */
    val keyBorder: Int,
    val barBackground: Int,
    val barText: Int,
    /** The highlighted (commit-on-space) suggestion. */
    val barHighlight: Int,
    /** Separator between suggestions; a colour with alpha 0 draws none. */
    val barSeparator: Int,
    /** Swipe trail colour while this look is the effective theme. */
    val swipeTrail: Int,
    /**
     * Fill of the key-press preview and of the long-press popover panel; 0 = [key]
     * ([LookPopupColors] resolves the defaults).
     */
    val popup: Int = 0,
    /** Label colour on [popup]; 0 = [label]. */
    val popupLabel: Int = 0,
    /** Fill of the selected popover alternative; 0 = [keyLocked]. */
    val popupSelected: Int = 0,
    /** Label colour on [popupSelected]; 0 = [lockedLabel]. */
    val popupSelectedLabel: Int = 0,
    /**
     * Fill of a latched modifier at rest (one-shot or auto-capitalised Shift); 0 = [keyPressed],
     * as every non-look theme. Gboard and Samsung keep the function-key fill and change only the
     * glyph (`Theme.Computed.isRestingLatch`).
     */
    val latchedKey: Int = 0,
    /**
     * Label / icon colour on [latchedKey] (Samsung's blue Shift icon); 0 = the ordinary pressed
     * label. Only drawn when [latchedKey] is defined.
     */
    val latchedLabel: Int = 0,
)

/**
 * The geometry a [KeyboardLook] defines. A `null` attribute is NOT defined by the look: the
 * user's own appearance setting applies to it ([KeyboardLookGeometry.resolve]).
 *
 * Gaps are fractions so they survive any keyboard height or screen width: [keyGapHorizontal] of
 * one key unit's width (the width of a `width="1"` key slot), [keyGapVertical] of the row height —
 * the same bases `Theme.Computed` uses for the user's key margins.
 */
data class LookGeometry(
    val keyGapHorizontal: Float? = null,
    val keyGapVertical: Float? = null,
    val cornerRadiusDp: Float? = null,
    /** Key outline width; 0 = no outline. */
    val borderWidthDp: Float? = null,
    /** Downward offset of the key shadow ([LookPalette.keyShadow]); 0 = no shadow. */
    val shadowDyDp: Float? = null,
    /** Multiplier on the user's main label size (relative, so the user's size setting still counts). */
    val labelScale: Float? = null,
    /** Main label font weight, 100..900 (400 = regular); applied on Android 9+. */
    val labelWeight: Int? = null,
    /**
     * Multiplier on the FUNCTION-key text labels ("!#1", "1/2", "ABC", "123"), which CleverKeys
     * draws at [LookLabelSizing.SMALLER_FONT] of the main size; One UI draws them about as large
     * as its letters ([LookLabelSizing.mainLabelSize], research §12 and §13). Icons are not affected.
     */
    val functionLabelScale: Float? = null,
)

/** Which corner labels a look draws. */
enum class LookLegends {
    /** No corner labels at all. */
    NONE,
    /** Only single-digit corner labels (Gboard's 1–0 over the top row). */
    DIGITS,
    /** Every corner label, as every non-look theme draws. */
    ALL,
}

/** Keys a look can draw as pills. */
enum class LookPillKey {
    /** Plain Enter (`KEYCODE_ENTER`). */
    ENTER,
    /** The editor-action key of Go / Search / Send / Done fields (`Event.ACTION`). */
    ACTION,
    /** The numbers-pane switch (`switch_numeric`: 123 / ?123 / !#1). */
    NUMBERS,
}

/**
 * Corner-label (gesture hint) drawing while a look is effective.
 *
 * Precedence (docs/specs/layout-system.md "Keyboard looks"): no look → every label (as always);
 * a look → its [KeyboardLook.legends], unless the user turned on Appearance → "Show gesture
 * hints on familiar looks" (`look_show_hints`, default off), which restores every label. Only
 * drawing changes: gestures, TalkBack labels and accessibility short-swipe actions are not
 * affected, and the existing hint preferences (secondary-label size, dimming) still apply to
 * whatever is drawn.
 */
object LookLegendPolicy {
    fun effective(legends: LookLegends?, showHints: Boolean): LookLegends =
        if (legends == null || showHints) LookLegends.ALL else legends

    /** Whether a corner label with text [label] is drawn under [effective]. */
    fun drawsCornerLabel(effective: LookLegends, label: String): Boolean = when (effective) {
        LookLegends.ALL -> true
        LookLegends.NONE -> false
        LookLegends.DIGITS -> label.length == 1 && label[0] in '0'..'9'
    }

    /** CleverKeys' space-bar glyph is a hint too: none of the copied keyboards draws one. */
    fun drawsSpaceBarGlyph(effective: LookLegends): Boolean = effective == LookLegends.ALL
}

/** Function keys a look can draw as letter keys. */
enum class LookLetterKey {
    /** The emoji-pane switch (`switch_emoji`). */
    EMOJI,
}

/** Per-key shapes of a look. */
object LookShapes {
    /** Whether a key is one of the look's [pills] (the key's identity, pressed or not). */
    fun isPill(pills: Set<LookPillKey>, isEnter: Boolean, isAction: Boolean, isNumbers: Boolean): Boolean =
        pills.isNotEmpty() && (
            (isEnter && LookPillKey.ENTER in pills) ||
                (isAction && LookPillKey.ACTION in pills) ||
                (isNumbers && LookPillKey.NUMBERS in pills)
            )

    /** A pill's radius is half its shorter side; any other key keeps [base]. */
    fun cornerRadius(pill: Boolean, base: Float, keyW: Float, keyH: Float): Float =
        if (pill) minOf(keyW, keyH) / 2f else base
}

/**
 * Which appearance values a keyboard view uses for key geometry: the effective look's where it
 * defines them, otherwise the user's.
 *
 * Precedence (documented in docs/specs/layout-system.md "Keyboard looks"): while a look is the
 * EFFECTIVE theme — selected in Theme Manager or bound to the current layout — every attribute
 * it defines wins over the user's global setting, including a custom border radius/width
 * (`border_config`), because choosing or binding a look is an explicit choice of that
 * appearance. Attributes the look leaves `null` keep the user's value, and the label size stays
 * relative to the user's size setting. The user's preferences are never written: switching to a
 * non-look theme or layout restores them exactly.
 */
object KeyboardLookGeometry {

    /** The user's (non-look) geometry, already in pixels, as `Theme.Computed` derives it today. */
    data class UserGeometry(
        val verticalMargin: Float,
        val horizontalMargin: Float,
        val borderRadius: Float,
        val borderWidth: Float,
        val borderWidthActivated: Float,
    )

    /** The geometry a keyboard view draws with, in pixels. */
    data class Effective(
        val verticalMargin: Float,
        val horizontalMargin: Float,
        val borderRadius: Float,
        val borderWidth: Float,
        val borderWidthActivated: Float,
        /** Key shadow offset; 0 = none. */
        val shadowDy: Float,
        /** Multiplier on the main label size (1 = the user's size). */
        val labelScale: Float,
        /** Main label weight, or null for the default face. */
        val labelWeight: Int?,
        /** Multiplier on function-key text labels ([LookLabelSizing]; 1 = CleverKeys' size). */
        val functionLabelScale: Float = 1f,
    )

    /**
     * Resolve the effective geometry. [keyWidth] is one key unit in px, [rowHeight] one row in px,
     * [density] px per dp. With [look] null (every non-look theme) the result is exactly [user].
     */
    fun resolve(look: LookGeometry?, user: UserGeometry, keyWidth: Float, rowHeight: Float, density: Float): Effective {
        if (look == null) {
            return Effective(
                user.verticalMargin, user.horizontalMargin, user.borderRadius, user.borderWidth,
                user.borderWidthActivated, shadowDy = 0f, labelScale = 1f, labelWeight = null,
            )
        }
        val borderWidth = look.borderWidthDp?.let { it.coerceAtLeast(0f) * density }
        return Effective(
            verticalMargin = look.keyGapVertical?.let { it.coerceIn(0f, MAX_GAP) * rowHeight } ?: user.verticalMargin,
            horizontalMargin = look.keyGapHorizontal?.let { it.coerceIn(0f, MAX_GAP) * keyWidth } ?: user.horizontalMargin,
            borderRadius = look.cornerRadiusDp?.let { it.coerceAtLeast(0f) * density } ?: user.borderRadius,
            borderWidth = borderWidth ?: user.borderWidth,
            // A look draws one outline width in both states (pressed keys change fill, not frame).
            borderWidthActivated = borderWidth ?: user.borderWidthActivated,
            shadowDy = (look.shadowDyDp ?: 0f).coerceAtLeast(0f) * density,
            labelScale = (look.labelScale ?: 1f).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
            labelWeight = look.labelWeight?.coerceIn(100, 900),
            functionLabelScale = (look.functionLabelScale ?: 1f).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
        )
    }

    /** A gap above half the key would leave keys thinner than their gaps. */
    const val MAX_GAP = 0.5f
    const val MIN_LABEL_SCALE = 0.5f
    const val MAX_LABEL_SCALE = 2f
}

/**
 * The size a key's MAIN label is drawn at. CleverKeys draws multi-character and function-key
 * labels (`FLAG_SMALLER_FONT`: "!#1", "ABC", "1/2", the action key's text, key-font icons such
 * as emoji) at [SMALLER_FONT] of the main size. A look's [LookGeometry.functionLabelScale]
 * enlarges the TEXT ones only — never a key-font icon — so Samsung-style draws "!#1", "1/2" and
 * "ABC" about as large as One UI does. Pure; `KeyboardLookGeometryTest`.
 */
object LookLabelSizing {
    /** CleverKeys' size for `FLAG_SMALLER_FONT` labels, relative to the main label. */
    const val SMALLER_FONT = 0.75f

    /**
     * [main] (the main label size) for a label that is [smaller] (`FLAG_SMALLER_FONT`) and drawn
     * in the key font ([keyFont]: icons), under a look's function-label multiplier [functionScale].
     */
    fun mainLabelSize(main: Float, smaller: Boolean, keyFont: Boolean, functionScale: Float): Float = when {
        !smaller -> main
        keyFont -> main * SMALLER_FONT
        else -> main * SMALLER_FONT * functionScale
    }
}
