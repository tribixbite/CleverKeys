package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
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
}
