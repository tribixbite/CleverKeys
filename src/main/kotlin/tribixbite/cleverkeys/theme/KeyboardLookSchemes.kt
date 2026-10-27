package tribixbite.cleverkeys.theme

import androidx.compose.ui.graphics.Color

/**
 * Maps a [LookPalette] onto the 17-field [KeyboardColorScheme] every runtime theme renders
 * through, so a look's colours reach the same consumers as a decorative or DIY theme (keys,
 * labels, suggestion bar, clipboard/emoji panes, magnifier, Theme Creator starting colours).
 * The look-only roles (action key, modifier labels, shadow, separators, trail) travel beside the
 * scheme as the palette itself (`Theme(context, scheme, density, palette, geometry)`).
 */
object KeyboardLookSchemes {

    fun colorScheme(palette: LookPalette): KeyboardColorScheme = KeyboardColorScheme(
        keyDefault = Color(palette.key),
        keyActivated = Color(palette.keyPressed),
        keyLocked = Color(palette.keyLocked),
        keyModifier = Color(palette.modifierKey),
        keySpecial = Color(palette.modifierKey),
        keyLabel = Color(palette.label),
        keySubLabel = Color(palette.hint),
        keySecondaryLabel = Color(palette.hint),
        keyBorder = Color(palette.keyBorder),
        keyBorderActivated = Color(palette.keyBorder),
        swipeTrail = Color(palette.swipeTrail),
        // Press feedback on bar chips: the pressed-key colour, as the keys themselves.
        ripple = Color(palette.keyPressed),
        suggestionText = Color(palette.barText),
        suggestionBackground = Color(palette.barBackground),
        suggestionHighConfidence = Color(palette.barHighlight),
        keyboardBackground = Color(palette.keyboardBackground),
        keyboardSurface = Color(palette.key),
    )

    /** The scheme of [look] for the current system mode. */
    fun colorScheme(look: KeyboardLook, systemLight: Boolean): KeyboardColorScheme =
        colorScheme(look.palette(systemLight))
}
