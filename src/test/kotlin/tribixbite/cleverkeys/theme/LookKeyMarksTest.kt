package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Per-key marks of the keyboard looks (2026-10-30): SwiftKey-style's mic over the comma and
 * ",!?" over the period (the real SwiftKey, Pixel 8 Pro 2026-10-27,
 * `build/oct27-night-pixel/showcase/compare-swiftkey-*.png`), Gboard-style's filled Shift while
 * shifted, and Samsung-style's larger function-key text (One UI screenshots 1–3.jpg: "!#1",
 * "1/2" and "ABC" 35–40 px against 40 px digits). Pure JVM.
 */
class LookKeyMarksTest {

    private val mic = LookKeyLegend(',', glyph = LookLegendGlyph.MIC)
    private val punct = LookKeyLegend('.', text = ",!?")

    @Test
    fun swiftkeyStyleCarriesTheProductsCommaAndPeriodLegendsAndNoOtherLookDoes() {
        assertThat(KeyboardLooks.swiftkeyLook.keyLegends).containsExactly(mic, punct).inOrder()
        for (look in KeyboardLooks.ALL - KeyboardLooks.swiftkeyLook) {
            assertWithMessage("${look.id} draws no key legends").that(look.keyLegends).isEmpty()
        }
    }

    @Test
    fun legendsReplaceHiddenHintsAndOnlyPromiseWhatTheKeyDoes() {
        val legends = listOf(mic, punct)
        assertThat(LookKeyLegends.legendFor(legends, LookLegends.NONE, ',', hasVoiceSwipe = true, hasSwipe = true)).isEqualTo(mic)
        assertThat(LookKeyLegends.legendFor(legends, LookLegends.NONE, '.', hasVoiceSwipe = false, hasSwipe = true)).isEqualTo(punct)
        assertWithMessage("no mic on a comma without a voice key (voice_typing not placed)")
            .that(LookKeyLegends.legendFor(legends, LookLegends.NONE, ',', hasVoiceSwipe = false, hasSwipe = true)).isNull()
        assertWithMessage("no punctuation mark on a period without swipes")
            .that(LookKeyLegends.legendFor(legends, LookLegends.NONE, '.', hasVoiceSwipe = false, hasSwipe = false)).isNull()
        assertWithMessage("gesture hints shown: the real corner labels are drawn instead")
            .that(LookKeyLegends.legendFor(legends, LookLegends.ALL, ',', hasVoiceSwipe = true, hasSwipe = true)).isNull()
        assertThat(LookKeyLegends.legendFor(legends, LookLegends.DIGITS, '.', false, true)).isEqualTo(punct)
        assertThat(LookKeyLegends.legendFor(legends, LookLegends.NONE, 'a', true, true)).isNull()
        assertThat(LookKeyLegends.legendFor(legends, LookLegends.NONE, null, true, true)).isNull()
        assertThat(LookKeyLegends.legendFor(emptyList(), LookLegends.NONE, ',', true, true)).isNull()
    }

    @Test
    fun theTextFormRoundTripsAndRefusesMalformedEntries() {
        val text = LookKeyLegends.format(listOf(mic, punct))
        assertThat(text).isEqualTo(",:@mic .:,!?")
        assertThat(LookKeyLegends.parse(text)).containsExactly(mic, punct).inOrder()
        assertThat(LookKeyLegends.parse("")).isEmpty()
        for (bad in listOf(",", ",;x", ",:@trumpet", ",:toolong", ",:a .:b ,:c", ",:@")) {
            assertWithMessage("\"$bad\" refused").that(LookKeyLegends.parse(bad)).isNull()
        }
        assertWithMessage("the mic is CleverKeys' own voice-key icon (key font)")
            .that(LookLegendGlyph.MIC.fontChar).isEqualTo('')
    }

    @Test
    fun gboardStyleFillsShiftWhileShiftedAndAddsABarInCapsLock() {
        assertThat(KeyboardLooks.gboardLook.shiftFilled).isTrue()
        for (look in KeyboardLooks.ALL - KeyboardLooks.gboardLook) {
            assertWithMessage("${look.id} keeps the outline Shift").that(look.shiftFilled).isFalse()
        }
        assertThat(LookShiftGlyph.state(down = false, locked = false)).isEqualTo(ShiftDrawState.REST)
        assertThat(LookShiftGlyph.state(down = true, locked = false)).isEqualTo(ShiftDrawState.SHIFTED)
        assertThat(LookShiftGlyph.state(down = true, locked = true)).isEqualTo(ShiftDrawState.LOCKED)

        assertWithMessage("at rest the key font's outline glyph is drawn")
            .that(LookShiftGlyph.parts(filled = true, ShiftDrawState.REST)).isNull()
        assertWithMessage("a look without the filled Shift never replaces the glyph")
            .that(LookShiftGlyph.parts(filled = false, ShiftDrawState.LOCKED)).isNull()
        val shifted = LookShiftGlyph.parts(filled = true, ShiftDrawState.SHIFTED)!!
        assertThat(shifted).hasSize(1)
        val arrow = shifted.single() as IconPart.Path
        assertThat(arrow.closed && arrow.filled).isTrue()
        val locked = LookShiftGlyph.parts(filled = true, ShiftDrawState.LOCKED)!!
        assertWithMessage("Caps Lock: the arrow and a bar under it").that(locked).hasSize(2)
        val lockedArrowBottom = (locked[0] as IconPart.Path).points.maxOf { it.second }
        val barTop = (locked[1] as IconPart.Path).points.minOf { it.second }
        assertThat(barTop).isGreaterThan(lockedArrowBottom)
        for (part in shifted + locked) for ((x, y) in (part as IconPart.Path).points) {
            assertThat(x).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(y).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun samsungStyleDrawsItsFunctionKeyTextAboutAsLargeAsOneUi() {
        val scale = KeyboardLooks.samsungLook.geometry.functionLabelScale
        assertWithMessage("Samsung-style defines a function-key text size").that(scale).isNotNull()
        // One UI (1.jpg–3.jpg, ink heights): "!#1" 38, "1/2" 40, "ABC" 35 px against 40 px digits;
        // CleverKeys at HEAD drew them at 0.75 (27–29 px against 37 px digits).
        val ratio = LookLabelSizing.mainLabelSize(1f, smaller = true, keyFont = false, functionScale = scale!!)
        assertThat(ratio).isIn(com.google.common.collect.Range.closed(0.875f, 1f))
        assertWithMessage("icons (key font) keep CleverKeys' size")
            .that(LookLabelSizing.mainLabelSize(1f, smaller = true, keyFont = true, functionScale = scale)).isEqualTo(0.75f)
        assertWithMessage("letters are untouched")
            .that(LookLabelSizing.mainLabelSize(1f, smaller = false, keyFont = false, functionScale = scale)).isEqualTo(1f)
        for (look in KeyboardLooks.ALL - KeyboardLooks.samsungLook) {
            assertWithMessage("${look.id} keeps CleverKeys' function-key text size")
                .that(look.geometry.functionLabelScale).isNull()
        }
    }
}
