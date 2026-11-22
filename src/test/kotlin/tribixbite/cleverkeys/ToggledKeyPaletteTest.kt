package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Maintainer 2026-11-22 (S25U screenshot): a toggled Ctrl on the true black themes showed only a
 * subtle label colour, and on the CleverKeys theme a toggled Shift and Ctrl drew a dark label on a
 * dark key. [ToggledKeyPalette] gives a latched or locked modifier a fill that stands apart from the
 * key at rest and a label readable on that fill. The colours below are the shipped themes'
 * (res/values/themes.xml, KeyboardLooks.cleverkeysLook).
 */
class ToggledKeyPaletteTest {

    private fun c(rgb: Long): Int = (0xFF000000 or rgb).toInt()
    private fun contrast(a: Int, b: Int) = SuggestionBarPalette.contrastRatio(a, b)

    private fun assertReadable(name: String, t: ToggledKeyPalette.Toggled, resting: Int) {
        assertWithMessage("$name latched fill stands apart from the key at rest")
            .that(contrast(t.latchedFill, resting)).isAtLeast(ToggledKeyPalette.MIN_FILL_CONTRAST)
        assertWithMessage("$name locked fill stands apart from the key at rest")
            .that(contrast(t.lockedFill, resting)).isAtLeast(ToggledKeyPalette.MIN_FILL_CONTRAST)
        assertWithMessage("$name latched label on its fill")
            .that(contrast(t.latchedLabel, t.latchedFill)).isAtLeast(ToggledKeyPalette.MIN_LABEL_CONTRAST)
        assertWithMessage("$name locked label on its fill")
            .that(contrast(t.lockedLabel, t.lockedFill)).isAtLeast(ToggledKeyPalette.MIN_LABEL_CONTRAST)
        assertWithMessage("$name fills are opaque").that(t.latchedFill ushr 24).isEqualTo(0xFF)
        assertWithMessage("$name fills are opaque").that(t.lockedFill ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun trueBlackTogglesFillWithTheThemesStateColours() {
        // Black: key #000000, pressed #333333 (1.66:1 on black — the "no obvious indicator").
        val t = ToggledKeyPalette.forTheme(
            restingFill = c(0x000000), pressedFill = c(0x333333), lockedFill = c(0x333333),
            activatedLabel = c(0x009DFF), lockedLabel = c(0x00FF26), label = c(0xEEEEEE),
            labelsAreAccents = true,
        )
        assertThat(t.latchedFill).isEqualTo(c(0x009DFF))
        assertThat(t.lockedFill).isEqualTo(c(0x00FF26))
        assertReadable("black", t, c(0x000000))
    }

    @Test
    fun theCleverKeysLookKeepsItsLockedDesignAndGainsALatchedFill() {
        val dark = tribixbite.cleverkeys.theme.KeyboardLooks.cleverkeysLook.dark
        val t = ToggledKeyPalette.forTheme(
            restingFill = dark.modifierKey, pressedFill = dark.keyPressed, lockedFill = dark.keyLocked,
            activatedLabel = dark.label, lockedLabel = dark.lockedLabel, label = dark.label,
            labelsAreAccents = false,
        )
        assertWithMessage("Caps Lock: the look's own light-purple fill and dark label")
            .that(t.lockedFill).isEqualTo(dark.keyLocked)
        assertThat(t.lockedLabel).isEqualTo(dark.lockedLabel)
        assertWithMessage("a latched Ctrl no longer keeps the at-rest fill")
            .that(t.latchedFill).isNotEqualTo(dark.modifierKey)
        assertWithMessage("latched reads lighter than at rest and darker than locked")
            .that(contrast(t.latchedFill, dark.keyLocked)).isGreaterThan(1.0)
        assertReadable("cleverkeys dark", t, dark.modifierKey)

        val light = tribixbite.cleverkeys.theme.KeyboardLooks.cleverkeysLook.light
        assertReadable(
            "cleverkeys light",
            ToggledKeyPalette.forTheme(
                restingFill = light.modifierKey, pressedFill = light.keyPressed, lockedFill = light.keyLocked,
                activatedLabel = light.label, lockedLabel = light.lockedLabel, label = light.label,
                labelsAreAccents = false,
            ),
            light.modifierKey,
        )
    }

    @Test
    fun ePaperTogglesInBlackAndWhite() {
        // ePaper's activated label is black, the same as its text: still its designed accent.
        val t = ToggledKeyPalette.forTheme(
            restingFill = c(0xFFFFFF), pressedFill = c(0xFFFFFF), lockedFill = c(0xFFFFFF),
            activatedLabel = c(0x000000), lockedLabel = c(0x33CC33), label = c(0x000000),
            labelsAreAccents = true,
        )
        assertThat(t.latchedFill).isEqualTo(c(0x000000))
        assertThat(t.latchedLabel).isEqualTo(c(0xFFFFFF))
    }

    @Test
    fun aThemeWhoseToggleAlreadyStandsOutIsUnchanged() {
        // Cobalt: key = keyboard (#000000 family), pressed #78bfff, label activated = the key colour.
        val t = ToggledKeyPalette.forTheme(
            restingFill = c(0x000000), pressedFill = c(0x78BFFF), lockedFill = c(0x78BFFF),
            activatedLabel = c(0x000000), lockedLabel = c(0xFFFFFF), label = c(0x95C9FF),
            labelsAreAccents = true,
        )
        assertThat(t.latchedFill).isEqualTo(c(0x78BFFF))
        assertThat(t.latchedLabel).isEqualTo(c(0x000000))
        assertReadable("cobalt", t, c(0x000000))
    }

    @Test
    fun everyShippedXmlThemeGetsAReadableToggle() {
        // (key, pressed, label, activated label, locked label) from res/values/themes.xml.
        val themes = mapOf(
            "Dark" to listOf(0x333333L, 0x1B1B1BL, 0xFFFFFFL, 0x3399FFL, 0x33CC33L),
            "Light" to listOf(0xCCCCCCL, 0xE3E3E3L, 0x000000L, 0x0066CCL, 0x33CC33L),
            "Black" to listOf(0x000000L, 0x333333L, 0xEEEEEEL, 0x009DFFL, 0x00FF26L),
            "White" to listOf(0xFFFFFFL, 0xFFFFFFL, 0x000000L, 0x0066CCL, 0x33CC33L),
            "ePaper" to listOf(0xFFFFFFL, 0xFFFFFFL, 0x000000L, 0x000000L, 0x33CC33L),
            "Desert" to listOf(0xFFF3E0L, 0xFFCC80L, 0x000000L, 0xFFFFFFL, 0xE65100L),
            "Jungle" to listOf(0xE0F2F1L, 0x00695CL, 0x000000L, 0xFFFFFFL, 0x64FFDAL),
            "RosePine" to listOf(0x26233AL, 0x403D52L, 0xE0DEF4L, 0xC4A7E7L, 0x31748FL),
            "EverforestLight" to listOf(0xE5E2D1L, 0xDBE0B6L, 0x5C6A72L, 0x828812L, 0x325E64L),
            "Jewel" to listOf(0xD8D8D8L, 0x9B59B6L, 0x2C3E50L, 0xFFFFFFL, 0x8E44ADL),
            "CleverKeysDark" to listOf(0x2A1845L, 0x4A2070L, 0xC0C0C0L, 0xE8E8E8L, 0x9B59B6L),
        )
        for ((name, v) in themes) {
            val (key, pressed, label, activated, locked) = v.map { c(it) }
            // XML themes: Key Locked = the pressed fill, modifiers rest on the key fill, and the
            // activated / locked label colours are the theme's designed state accents.
            assertReadable(name, ToggledKeyPalette.forTheme(key, pressed, pressed, activated, locked, label, true), key)
        }
    }

    @Test
    fun withNoUsableAccentTheFillIsAToneOfTheRestingKey() {
        // Every colour the same grey: nothing to borrow, so the fill is derived from the key.
        val grey = c(0x777777)
        for (accents in listOf(true, false)) {
            assertReadable("all grey", ToggledKeyPalette.forTheme(grey, grey, grey, grey, grey, grey, accents), grey)
        }
    }
}
