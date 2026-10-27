package tribixbite.cleverkeys.theme

import tribixbite.cleverkeys.SuggestionBarPalette

/**
 * Wallpaper ("Material You" / Monet) colours for a [KeyboardLook] whose
 * [KeyboardLook.dynamicColor] is set (Gboard-style).
 *
 * Gboard's dynamic theme resolves its stylesheet variables to Material 3 colour ROLES
 * (research `docs/research/2026-10-27-familiar-keyboard-looks.md` §2a). This object maps the
 * same roles onto a [LookPalette], so CleverKeys' Gboard-style look follows the wallpaper the
 * way Gboard does. Checked on the Pixel 8 Pro (Android 16, seed `#5D646B`, Tonal Spot,
 * 2026-10-28): Gboard light = background `#EAEEF4` (surfaceContainer), letter keys `#FFFFFF`
 * (surfaceContainerLowest), function keys + Enter `#D3E5F5` (secondaryContainer, exact),
 * popover selection `#346384` (primary); dark = `#151A1F` / `#262D33` / `#2D3D4A` / `#A8CBE8`.
 *
 * Pure JVM: the roles come in as ARGB ints ([MaterialRoles]); `ThemeProvider` reads them from
 * the system (`android.R.color.system_*` role colours on API 34+, the tonal palettes on 31–33).
 * Below API 31, or when a mapped palette misses a contrast floor ([LookContrast]), the look's
 * fixed palette is used instead.
 */
object LookDynamicColors {

    /** The Material 3 roles Gboard's dynamic theme uses, for one system mode, as ARGB. */
    data class MaterialRoles(
        val surfaceContainer: Int,
        val surfaceContainerLowest: Int,
        val surfaceContainerHighest: Int,
        val surfaceBright: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val primary: Int,
        val onPrimary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outlineVariant: Int,
    )

    /**
     * The Gboard role mapping (research §2a) for [roles] in light ([light] true) or dark mode.
     * [fixed] is the look's fixed palette for the same mode: it supplies what the roles do not
     * decide (the key shadow) and is returned unchanged when the mapped palette would miss a
     * contrast floor.
     */
    fun gboardPalette(roles: MaterialRoles, light: Boolean, fixed: LookPalette): LookPalette {
        val r = roles.opaque()
        // Letter keys: surfaceContainerLowest (white) in light mode, surfaceBright in dark.
        val key = if (light) r.surfaceContainerLowest else r.surfaceBright
        // Pressed: surfaceContainerHighest in light; the label at 10 % over the key in dark
        // (surfaceContainerHighest is DARKER than surfaceBright there).
        val pressed = if (light) r.surfaceContainerHighest else blend(r.onSurface, key, PRESSED_OVERLAY)
        val mapped = LookPalette(
            keyboardBackground = r.surfaceContainer,
            key = key,
            keyPressed = pressed,
            keyLocked = r.primary,
            lockedLabel = r.onPrimary,
            modifierKey = r.secondaryContainer,
            enterKey = r.secondaryContainer,
            enterLabel = r.onSecondaryContainer,
            actionKey = r.primary,
            actionLabel = r.onPrimary,
            label = r.onSurface,
            modifierLabel = r.onSecondaryContainer,
            hint = r.onSurfaceVariant,
            keyShadow = fixed.keyShadow,
            keyBorder = key,
            barBackground = r.surfaceContainer,
            barText = r.onSurface,
            barHighlight = r.onSurface,
            barSeparator = r.outlineVariant,
            swipeTrail = r.primary,
            popup = key,
            popupLabel = r.onSurface,
            popupSelected = r.primary,
            popupSelectedLabel = r.onPrimary,
            latchKeepsFill = fixed.latchKeepsFill,
        )
        return if (LookContrast.meetsFloors(mapped)) mapped else fixed
    }

    /** Alpha of the label overlay that marks a pressed key in dark mode. */
    const val PRESSED_OVERLAY = 0.10f

    /** [top] at [alpha] composited over opaque [bottom]. */
    fun blend(top: Int, bottom: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val t = (top ushr shift) and 0xFF
            val b = (bottom ushr shift) and 0xFF
            return (b + (t - b) * alpha + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** System role colours are opaque; force it so a malformed value cannot make keys see-through. */
    private fun MaterialRoles.opaque(): MaterialRoles {
        fun o(c: Int) = c or (0xFF shl 24)
        return MaterialRoles(
            o(surfaceContainer), o(surfaceContainerLowest), o(surfaceContainerHighest), o(surfaceBright),
            o(secondaryContainer), o(onSecondaryContainer), o(primary), o(onPrimary), o(onSurface),
            o(onSurfaceVariant), o(outlineVariant),
        )
    }
}

/**
 * The contrast floors every look palette meets (WCAG 1.4.3 / 1.4.11), shared by the dynamic
 * mapping's fallback decision and `KeyboardLooksTest`: letter and modifier labels and bar text
 * 4.5:1; Enter / action / Caps Lock glyphs, pressed labels, hints (on keys and on the bar) and
 * the bar highlight 3:1; the popup label on the popup 4.5:1 and the selected popup label 3:1.
 */
object LookContrast {
    const val TEXT = 4.5
    const val GRAPHIC = 3.0

    private fun ratio(a: Int, b: Int) = SuggestionBarPalette.contrastRatio(a, b)

    /** Every floor the palette misses, as readable names (empty = all met). */
    fun failures(p: LookPalette): List<String> {
        val popup = LookPopupColors.of(p)
        return buildList {
            if (ratio(p.label, p.key) < TEXT) add("letter label")
            if (ratio(p.modifierLabel, p.modifierKey) < TEXT) add("modifier label")
            if (ratio(p.enterLabel, p.enterKey) < GRAPHIC) add("Enter glyph")
            if (ratio(p.actionLabel, p.actionKey) < GRAPHIC) add("action glyph")
            if (ratio(p.label, p.keyPressed) < GRAPHIC) add("pressed label")
            if (ratio(p.lockedLabel, p.keyLocked) < GRAPHIC) add("Caps Lock glyph")
            if (ratio(p.hint, p.key) < GRAPHIC) add("hint on key")
            if (ratio(p.hint, p.barBackground) < GRAPHIC) add("hint on bar")
            if (ratio(p.barText, p.barBackground) < TEXT) add("bar text")
            if (ratio(p.barHighlight, p.barBackground) < GRAPHIC) add("bar highlight")
            if (ratio(popup.label, popup.fill) < TEXT) add("popup label")
            if (ratio(popup.selectedLabel, popup.selected) < GRAPHIC) add("selected popup label")
        }
    }

    fun meetsFloors(p: LookPalette): Boolean = failures(p).isEmpty()
}
