package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-key Customize dialog's leading label (Saga 2026-10-30: Enter's NE row read "Acti" — the
 * row cut every label to 4 UTF-16 chars, so the `action` key's "Action" lost its end and an emoji
 * sequence could be split in half). [SubkeySlotLabel.of] never cuts a label.
 */
class SubkeySlotLabelTest {

    @Test
    fun aWordLabelIsShownWhole() {
        val label = SubkeySlotLabel.of("Action", keyFont = false)
        assertThat(label?.text).isEqualTo("Action")
        // Longer than a key glyph: the row lets it wrap instead of cutting it.
        assertThat(label?.wraps).isTrue()
    }

    @Test
    fun aShortLabelOrAGlyphStaysOnOneLine() {
        assertThat(SubkeySlotLabel.of("zap", keyFont = false)).isEqualTo(SubkeySlotLabel.Text("zap", keyFont = false, wraps = false))
        assertThat(SubkeySlotLabel.of("", keyFont = true)).isEqualTo(SubkeySlotLabel.Text("", keyFont = true, wraps = false))
    }

    @Test
    fun anEmojiSequenceIsNeverSplit() {
        val family = "👨‍👩‍👧" // man ZWJ woman ZWJ girl, 8 chars
        assertThat(SubkeySlotLabel.of(family, keyFont = false)?.text).isEqualTo(family)
        val flag = "🇺🇸🇨🇦" // two flags, 8 chars
        assertThat(SubkeySlotLabel.of(flag, keyFont = false)?.text).isEqualTo(flag)
    }

    @Test
    fun aBlankLabelShowsNothing() {
        assertThat(SubkeySlotLabel.of("", keyFont = false)).isNull()
        assertThat(SubkeySlotLabel.of("  ", keyFont = false)).isNull()
    }
}
