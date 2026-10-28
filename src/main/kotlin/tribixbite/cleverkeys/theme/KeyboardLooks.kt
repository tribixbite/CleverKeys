package tribixbite.cleverkeys.theme

/**
 * The built-in keyboard looks: iOS-, Gboard-, SwiftKey- and Samsung-style (plus the previous
 * iOS 18 look), each with a light and a dark palette that follow the system. The four
 * familiar-style layouts bind to the first four
 * (`src/main/layouts/latn_qwerty_us_*_style.xml`, `theme="look_…"`), so with those layouts
 * enabled a space-bar swipe switches the whole look.
 *
 * Values, sources and confidence: docs/research/2026-10-27-familiar-keyboard-looks.md. Every look
 * is drawn with CleverKeys' own renderer and the system sans-serif: no product artwork, icons or
 * fonts (SF Pro, Google Sans, Samsung One) are bundled. CleverKeys is not affiliated with or
 * endorsed by Apple, Google, Microsoft or Samsung; iOS, Gboard, SwiftKey and Samsung Keyboard
 * are their owners' trademarks.
 *
 * Pure JVM: `KeyboardLooksTest` pins the registry, light-vs-dark and WCAG contrast.
 */
object KeyboardLooks {

    /** Id prefix of every look (`ConfigSnapshot.isRuntimeThemeName`, `ThemeProvider.getTheme`). */
    const val ID_PREFIX = "look_"

    const val IOS = "look_ios"
    const val GBOARD = "look_gboard"
    const val SWIFTKEY = "look_swiftkey"
    const val SAMSUNG = "look_samsung"
    const val IOS18 = "look_ios18"

    private fun argb(rgb: Long): Int = (0xFF000000L or rgb).toInt()

    /** iOS key-pop: head 12 pt wider than the key on each side, ~50 pt over a 43 pt key, 12 pt corners. */
    private val IOS_BALLOON = LookPreview(
        LookPreviewShape.BALLOON, widthScale = 1.75f, heightScale = 1.16f, labelScale = 1.6f, cornerScale = 0.28f,
    )

    /** A separator colour with alpha 0: no separators are drawn. */
    private const val NO_SEPARATOR = 0x00FFFFFF

    /**
     * iOS 26 (default since 2025): ONE colour for every key over the "Liquid Glass" background
     * (drawn as its composited flat colour), 8 pt corners, no shadow, return a plain key with an
     * icon, blue (#007AFF) only in Go / Search / Send / Done fields. Research §1a.
     */
    val iosLook = KeyboardLook(
        id = IOS,
        light = LookPalette(
            keyboardBackground = argb(0xE2E4E8), key = argb(0xFFFFFF), keyPressed = argb(0xC4C5C8),
            keyLocked = argb(0xFFFFFF), lockedLabel = argb(0x000000),
            modifierKey = argb(0xFFFFFF), enterKey = argb(0xFFFFFF), enterLabel = argb(0x000000),
            actionKey = argb(0x007AFF), actionLabel = argb(0xFFFFFF),
            label = argb(0x000000), modifierLabel = argb(0x000000), hint = argb(0x6E6E73),
            keyShadow = 0, keyBorder = argb(0xFFFFFF),
            barBackground = argb(0xE2E4E8), barText = argb(0x000000), barHighlight = argb(0x000000),
            barSeparator = 0x4D3C3C43, swipeTrail = argb(0x007AFF),
            popupSelected = argb(0x007AFF), popupSelectedLabel = argb(0xFFFFFF),
        ),
        dark = LookPalette(
            keyboardBackground = argb(0x171717), key = argb(0x3D3D3D), keyPressed = argb(0x7D7D7D),
            keyLocked = argb(0x3D3D3D), lockedLabel = argb(0xFFFFFF),
            modifierKey = argb(0x3D3D3D), enterKey = argb(0x3D3D3D), enterLabel = argb(0xFFFFFF),
            actionKey = argb(0x007AFF), actionLabel = argb(0xFFFFFF),
            label = argb(0xFFFFFF), modifierLabel = argb(0xFFFFFF), hint = argb(0x98989F),
            keyShadow = 0, keyBorder = argb(0x3D3D3D),
            barBackground = argb(0x171717), barText = argb(0xFFFFFF), barHighlight = argb(0xFFFFFF),
            barSeparator = 0x4DEBEBF5, swipeTrail = argb(0x0A84FF),
            popup = argb(0x505050), popupSelected = argb(0x007AFF), popupSelectedLabel = argb(0xFFFFFF),
        ),
        geometry = LookGeometry(
            keyGapHorizontal = 0.15f, keyGapVertical = 0.20f, cornerRadiusDp = 8f,
            borderWidthDp = 0f, shadowDyDp = 0f, labelScale = 1.25f, labelWeight = 400,
        ),
        legends = LookLegends.NONE,
        preview = IOS_BALLOON,
        hintStyle = LookHintStyle(sizeFraction = 0.38f, weight = 400),
        // QuickType: three slots, autocorrection centred, the typed word quoted on the left (§10).
        bar = LookBar(slots = 3, quoteTyped = true),
    )

    /**
     * iOS 18: white letter keys, grey function keys and return, 5 pt corners and a 1 pt shadow
     * straight down under every key. Research §1b (KeyboardKit 8.9.0 values).
     */
    val ios18Look = KeyboardLook(
        id = IOS18,
        light = LookPalette(
            keyboardBackground = argb(0xD5D6DD), key = argb(0xFFFFFF), keyPressed = argb(0xC3C7CE),
            keyLocked = argb(0xFFFFFF), lockedLabel = argb(0x000000),
            modifierKey = argb(0xABB1BA), enterKey = argb(0xABB1BA), enterLabel = argb(0x000000),
            actionKey = argb(0x007AFF), actionLabel = argb(0xFFFFFF),
            label = argb(0x000000), modifierLabel = argb(0x000000), hint = argb(0x6E6E73),
            keyShadow = 0x4D000000, keyBorder = argb(0xFFFFFF),
            barBackground = argb(0xD5D6DD), barText = argb(0x000000), barHighlight = argb(0x000000),
            barSeparator = 0x4D3C3C43, swipeTrail = argb(0x007AFF),
            popupSelected = argb(0x007AFF), popupSelectedLabel = argb(0xFFFFFF),
        ),
        dark = LookPalette(
            keyboardBackground = argb(0x2C2C2C), key = argb(0x6B6B6B), keyPressed = argb(0x555555),
            keyLocked = argb(0xFFFFFF), lockedLabel = argb(0x000000),
            modifierKey = argb(0x474747), enterKey = argb(0x474747), enterLabel = argb(0xFFFFFF),
            actionKey = argb(0x007AFF), actionLabel = argb(0xFFFFFF),
            label = argb(0xFFFFFF), modifierLabel = argb(0xFFFFFF), hint = argb(0xE5E5EA),
            keyShadow = 0xB3000000.toInt(), keyBorder = argb(0x6B6B6B),
            barBackground = argb(0x2C2C2C), barText = argb(0xFFFFFF), barHighlight = argb(0xFFFFFF),
            barSeparator = 0x4DEBEBF5, swipeTrail = argb(0x0A84FF),
            popupSelected = argb(0x007AFF), popupSelectedLabel = argb(0xFFFFFF),
        ),
        geometry = LookGeometry(
            keyGapHorizontal = 0.15f, keyGapVertical = 0.22f, cornerRadiusDp = 5f,
            borderWidthDp = 0f, shadowDyDp = 1f, labelScale = 1.25f, labelWeight = 300,
        ),
        legends = LookLegends.NONE,
        preview = IOS_BALLOON,
        hintStyle = LookHintStyle(sizeFraction = 0.38f, weight = 300),
        bar = LookBar(slots = 3, quoteTyped = true),
    )

    /**
     * Gboard with Material You (dynamic colour) on the system's default seed #1B6EF3: white
     * letter keys, one tonal colour for shift, delete, ?123, comma, period and Enter, primary
     * for Caps Lock and the highlighted Enter of search/send fields. Research §2a–2b.
     */
    val gboardLook = KeyboardLook(
        id = GBOARD,
        light = LookPalette(
            keyboardBackground = argb(0xEEEDF4), key = argb(0xFFFFFF), keyPressed = argb(0xE2E2E9),
            keyLocked = argb(0x475D92), lockedLabel = argb(0xFFFFFF),
            modifierKey = argb(0xDCE2F9), enterKey = argb(0xDCE2F9), enterLabel = argb(0x404659),
            actionKey = argb(0x475D92), actionLabel = argb(0xFFFFFF),
            label = argb(0x1A1B20), modifierLabel = argb(0x404659), hint = argb(0x5C5C5C),
            keyShadow = 0x1A000000, keyBorder = argb(0xFFFFFF),
            barBackground = argb(0xEEEDF4), barText = argb(0x1A1B20), barHighlight = argb(0x1A1B20),
            barSeparator = argb(0xC5C6D0), swipeTrail = argb(0x475D92),
            // Auto-cap / one-shot Shift keeps the function-key fill (Pixel 2026-10-27).
            latchedKey = argb(0xDCE2F9), latchedLabel = argb(0x404659),
        ),
        dark = LookPalette(
            keyboardBackground = argb(0x1E1F25), key = argb(0x38393F), keyPressed = argb(0x4B4C52),
            keyLocked = argb(0xB0C6FF), lockedLabel = argb(0x152E60),
            modifierKey = argb(0x404659), enterKey = argb(0x404659), enterLabel = argb(0xDCE2F9),
            actionKey = argb(0xB0C6FF), actionLabel = argb(0x152E60),
            label = argb(0xE2E2E9), modifierLabel = argb(0xDCE2F9), hint = argb(0xC6C6CC),
            keyShadow = 0, keyBorder = argb(0x38393F),
            barBackground = argb(0x1E1F25), barText = argb(0xE2E2E9), barHighlight = argb(0xE2E2E9),
            barSeparator = argb(0x44464F), swipeTrail = argb(0xB0C6FF),
            latchedKey = argb(0x404659), latchedLabel = argb(0xDCE2F9),
        ),
        geometry = LookGeometry(
            keyGapHorizontal = 0.13f, keyGapVertical = 0.17f, cornerRadiusDp = 8f,
            borderWidthDp = 0f, shadowDyDp = 0.6f, labelScale = 1.2f, labelWeight = 400,
        ),
        functionChars = ",.",
        legends = LookLegends.DIGITS,
        pillKeys = setOf(LookPillKey.ENTER, LookPillKey.ACTION, LookPillKey.NUMBERS),
        // The real Gboard draws its emoji key like a letter key (Pixel 2026-10-28).
        letterKeys = setOf(LookLetterKey.EMOJI),
        preview = LookPreview(
            LookPreviewShape.BUBBLE, widthScale = 1.3f, heightScale = 1.25f, labelScale = 1.2f, shadow = true,
        ),
        // Gboard's top-right digits: small, regular weight.
        hintStyle = LookHintStyle(sizeFraction = 0.40f, weight = 400),
        dynamicColor = true,
        // Three slots, best centred, separators between them (research §10).
        bar = LookBar(slots = 3),
    )

    /**
     * Microsoft SwiftKey "Fluent Adaptive" (the default look since 2023): near-identical letter
     * and function keys, Enter an icon in the label colour, symbol hints above the letters, a
     * soft drop shadow, no suggestion separators. Research §3a. The light hint is darkened from
     * #B7B7B7 (2.0:1) to #888888 to reach 3:1 on the keys and the bar.
     */
    val swiftkeyLook = KeyboardLook(
        id = SWIFTKEY,
        light = LookPalette(
            keyboardBackground = argb(0xF1F1F1), key = argb(0xFFFFFF), keyPressed = argb(0xE3E3E3),
            keyLocked = argb(0xFAFAFA), lockedLabel = argb(0x292929),
            modifierKey = argb(0xFAFAFA), enterKey = argb(0xFAFAFA), enterLabel = argb(0x292929),
            actionKey = argb(0xFAFAFA), actionLabel = argb(0x292929),
            label = argb(0x292929), modifierLabel = argb(0x292929), hint = argb(0x888888),
            keyShadow = 0x29000000, keyBorder = argb(0xFFFFFF),
            barBackground = argb(0xF1F1F1), barText = argb(0x292929), barHighlight = argb(0x292929),
            barSeparator = NO_SEPARATOR, swipeTrail = argb(0x3961ED),
            popup = argb(0xE3E3E3), popupSelected = argb(0x3961ED), popupSelectedLabel = argb(0xFFFFFF),
        ),
        dark = LookPalette(
            keyboardBackground = argb(0x141414), key = argb(0x232323), keyPressed = argb(0x404040),
            keyLocked = argb(0x232323), lockedLabel = argb(0xFFFFFF),
            modifierKey = argb(0x232323), enterKey = argb(0x232323), enterLabel = argb(0xFFFFFF),
            actionKey = argb(0x232323), actionLabel = argb(0xFFFFFF),
            label = argb(0xFFFFFF), modifierLabel = argb(0xFFFFFF), hint = argb(0x777777),
            keyShadow = 0x29000000, keyBorder = argb(0x232323),
            barBackground = argb(0x141414), barText = argb(0xFFFFFF), barHighlight = argb(0xFFFFFF),
            barSeparator = NO_SEPARATOR, swipeTrail = argb(0x9DC3FF),
            popup = argb(0x404040), popupSelected = argb(0x9DC3FF), popupSelectedLabel = argb(0x141414),
        ),
        geometry = LookGeometry(
            keyGapHorizontal = 0.12f, keyGapVertical = 0.10f, cornerRadiusDp = 8f,
            borderWidthDp = 0f, shadowDyDp = 0.7f, labelScale = 1.2f, labelWeight = 400,
        ),
        legends = LookLegends.NONE,
        // A key-wide column rising about one row (Pixel 2026-10-28).
        preview = LookPreview(LookPreviewShape.COLUMN, widthScale = 1f, heightScale = 2.1f, labelScale = 1.15f),
        // Fluent draws its symbol hints small and light.
        hintStyle = LookHintStyle(sizeFraction = 0.45f, weight = 300),
        // "Press the spacebar to insert the middle prediction"; verbatim text on the left (§10).
        // The centre is always the emphasised slot, sides dimmed, nothing bold (Pixel 2026-10-27).
        bar = LookBar(slots = 3, emphasis = BarEmphasis.CENTRE),
    )

    /**
     * Samsung Keyboard (One UI 8, HoneyBoard 5.9): near-white letter keys, darker function keys
     * and Enter (an icon), a blue shift icon when engaged, blue auto-replace suggestion, a small
     * key shadow in light mode only, 6 dp corners. Research §4. The light hint is darkened from
     * #908F92 (the label at 50 %, 2.8:1 on the bar) to #7E7E82 to reach 3:1 on keys and bar.
     */
    val samsungLook = KeyboardLook(
        id = SAMSUNG,
        light = LookPalette(
            keyboardBackground = argb(0xE4E4E7), key = argb(0xFCFCFF), keyPressed = argb(0xA6A6A9),
            keyLocked = argb(0xC9C9CC), lockedLabel = argb(0x2E65D4),
            modifierKey = argb(0xC9C9CC), enterKey = argb(0xC9C9CC), enterLabel = argb(0x252528),
            actionKey = argb(0xC9C9CC), actionLabel = argb(0x252528),
            label = argb(0x252528), modifierLabel = argb(0x252528), hint = argb(0x7E7E82),
            keyShadow = 0x24000000, keyBorder = argb(0xFCFCFF),
            barBackground = argb(0xE4E4E7), barText = argb(0x252528), barHighlight = argb(0x387AFF),
            barSeparator = NO_SEPARATOR, swipeTrail = argb(0x2793FC),
            popup = argb(0xFCFCFF), popupLabel = argb(0x252528),
            popupSelected = argb(0x376FDE), popupSelectedLabel = argb(0xFFFFFF),
            // Shift engaged: the icon turns blue on the unchanged function-key fill.
            latchedKey = argb(0xC9C9CC), latchedLabel = argb(0x2E65D4),
        ),
        dark = LookPalette(
            keyboardBackground = argb(0x010102), key = argb(0x3A3A3D), keyPressed = argb(0x47474A),
            keyLocked = argb(0x17171A), lockedLabel = argb(0x578FFF),
            modifierKey = argb(0x17171A), enterKey = argb(0x17171A), enterLabel = argb(0xFAFAFF),
            actionKey = argb(0x17171A), actionLabel = argb(0xFAFAFF),
            label = argb(0xFAFAFF), modifierLabel = argb(0xFAFAFF), hint = argb(0x9A9A9E),
            keyShadow = 0, keyBorder = argb(0x3A3A3D),
            barBackground = argb(0x010102), barText = argb(0xFAFAFF), barHighlight = argb(0x387AFF),
            barSeparator = NO_SEPARATOR, swipeTrail = argb(0x2793FC),
            popup = argb(0x2D2D30), popupLabel = argb(0xFAFAFF),
            popupSelected = argb(0x376FDE), popupSelectedLabel = argb(0xFFFFFF),
            latchedKey = argb(0x17171A), latchedLabel = argb(0x578FFF),
        ),
        geometry = LookGeometry(
            keyGapHorizontal = 0.16f, keyGapVertical = 0.14f, cornerRadiusDp = 6f,
            borderWidthDp = 0f, shadowDyDp = 1f, labelScale = 1.1f, labelWeight = 400,
        ),
        legends = LookLegends.NONE,
        // One UI's preview plate (`preview_*` colours, research §4).
        preview = LookPreview(LookPreviewShape.PLATE, widthScale = 1.15f, heightScale = 1.2f, labelScale = 1.3f, cornerScale = 0.18f),
        // "Alternative characters": top-right, the label at 50 %.
        hintStyle = LookHintStyle(sizeFraction = 0.42f, weight = 400),
        bar = LookBar(slots = 3),
    )

    /** Every look, in Theme Manager order. */
    val ALL: List<KeyboardLook> = listOf(iosLook, gboardLook, swiftkeyLook, samsungLook, ios18Look)

    /** Every look id, in Theme Manager order. */
    val IDS: List<String> = ALL.map { it.id }

    private val BY_ID: Map<String, KeyboardLook> = ALL.associateBy { it.id }

    /** The look stored as [id], or null (not a look, or a look this build does not have). */
    fun byId(id: String): KeyboardLook? = BY_ID[id]
}
