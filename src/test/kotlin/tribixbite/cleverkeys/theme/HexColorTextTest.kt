package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.text.Bidi
import org.junit.Test

/**
 * Saga 2026-10-25: the Theme Creator's hex field rewrote typed input part way through a value
 * and left the cursor in the middle. A complete 6-digit value applied the colour, the colour
 * then re-formatted the text as 8 digits (`#FF0000` → `#FFFF0000`), and the user's next digits
 * landed inside it. These pin the rule that the field keeps what the user typed whenever it
 * already denotes the current colour.
 */
class HexColorTextTest {

    @Test
    fun sixDigitsAreOpaque() {
        assertThat(HexColorText.parse("#FF0000")).isEqualTo(0xFFFF0000.toInt())
        assertThat(HexColorText.parse("ff0000")).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun eightDigitsCarryAlpha() {
        assertThat(HexColorText.parse("#80FF0000")).isEqualTo(0x80FF0000.toInt())
        assertThat(HexColorText.parse("  #12345678 ")).isEqualTo(0x12345678)
    }

    @Test
    fun incompleteOrInvalidTextAppliesNothing() {
        for (text in listOf("", "#", "#FF00", "#FF000", "#FFF0000", "#FF00000000", "#GG0000", "#FF 0000")) {
            assertThat(HexColorText.parse(text)).isNull()
        }
    }

    @Test
    fun formatIsCanonicalEightDigitUpperCase() {
        assertThat(HexColorText.format(0x0a0b0c0d)).isEqualTo("#0A0B0C0D")
        assertThat(HexColorText.format(0xFFFF0000.toInt())).isEqualTo("#FFFF0000")
    }

    @Test
    fun aTypedSixDigitValueIsNotRewritten() {
        // The bug: the colour #FF0000 just applied re-formatted the field as #FFFF0000.
        assertThat(HexColorText.resyncedText("#FF0000", 0xFFFF0000.toInt())).isNull()
    }

    @Test
    fun typedSpellingsOfTheCurrentColourAreKept() {
        val red = 0xFFFF0000.toInt()
        for (text in listOf("#ff0000", "ff0000", "FF0000", "#FFFF0000", "#ffff0000", "ffff0000", " #FF0000 ")) {
            assertThat(HexColorText.resyncedText(text, red)).isNull()
        }
    }

    @Test
    fun aDifferentColourReplacesTheText() {
        // A slider or a preset moved the colour: the field follows it.
        assertThat(HexColorText.resyncedText("#FF0000", 0xFF00FF00.toInt())).isEqualTo("#FF00FF00")
        assertThat(HexColorText.resyncedText("#FF00", 0xFF00FF00.toInt())).isEqualTo("#FF00FF00")
    }

    /**
     * Saga 2026-10-27, Persian: the Theme Creator's colour rows read `FFFFFFFF#` and `E8EEF4#`
     * (while `#102A43` looked right). In a right-to-left paragraph a leading `#` (a weak
     * "terminator") that is followed by a LETTER takes the paragraph's direction and is drawn
     * at the far end; followed by a digit it joins the number. [HexColorText.display] must read
     * `#…` first whatever the paragraph direction. Checked with the JDK's Unicode Bidirectional
     * Algorithm, the same rules Android's text layout applies.
     */
    @Test
    fun aDisplayedHexValueKeepsItsHashFirstInARightToLeftParagraph() {
        for ((argb, alpha, expected) in listOf(
            Triple(0xFFFFFFFF.toInt(), true, "#FFFFFFFF"),
            Triple(0xFFE8EEF4.toInt(), false, "#E8EEF4"),
            Triple(0xFF102A43.toInt(), false, "#102A43"),
            Triple(0x33102A43, true, "#33102A43"),
        )) {
            val shown = HexColorText.display(argb, alpha)
            assertWithMessage("visible text of ${expected}").that(invisibleStripped(shown)).isEqualTo(expected)
            for (direction in listOf(Bidi.DIRECTION_RIGHT_TO_LEFT, Bidi.DIRECTION_LEFT_TO_RIGHT)) {
                assertWithMessage("$expected drawn in a paragraph of direction $direction")
                    .that(invisibleStripped(visualOrder(shown, direction))).isEqualTo(expected)
            }
        }
    }

    private fun invisibleStripped(s: String) = s.filterNot { it == '\u200E' || it == '\u200F' || it in '\u2066'..'\u2069' }

    /** [text] in the left-to-right order its characters are drawn (UBA rules L1–L2). */
    private fun visualOrder(text: String, direction: Int): String {
        val bidi = Bidi(text, direction)
        val runs = bidi.runCount
        val levels = ByteArray(runs) { bidi.getRunLevel(it).toByte() }
        val order = arrayOfNulls<Any>(runs).also { for (i in 0 until runs) it[i] = i }
        Bidi.reorderVisually(levels, 0, order, 0, runs)
        return buildString {
            for (o in order) {
                val i = o as Int
                val run = text.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
                append(if (bidi.getRunLevel(i) % 2 == 1) run.reversed() else run)
            }
        }
    }
}
