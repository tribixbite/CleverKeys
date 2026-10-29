package tribixbite.cleverkeys.stt.moonshine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/** [MoonshineTokenizer]: the `tokenizer.bin` record format and the decode rules. */
class MoonshineTokenizerTest {

    /** Encode [tokens] the way `tokenizer.bin` stores them. */
    private fun bin(tokens: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (t in tokens) {
            if (t.isEmpty()) {
                out.write(0)
            } else if (t.size < 128) {
                out.write(t.size)
                out.write(t)
            } else {
                // two-byte length: first = (n % 128) + 128, second = n / 128
                out.write(t.size % 128 + 128)
                out.write(t.size / 128)
                out.write(t)
            }
        }
        return out.toByteArray()
    }

    private fun s(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun decodeJoinsPiecesMapsTheSpaceMarkAndSkipsSpecials() {
        val tok = MoonshineTokenizer.parse(bin(listOf(s("<unk>"), s("<s>"), s("</s>"), s("▁Hello"), s(","), s("▁wor"), s("ld"), ByteArray(0))))
        assertThat(tok.size).isEqualTo(8)
        assertThat(tok.decode(intArrayOf(1, 3, 4, 5, 6, 2))).isEqualTo("Hello, world")
        // Unknown and empty ids are skipped rather than crashing a dictation.
        assertThat(tok.decode(intArrayOf(3, 7, 999))).isEqualTo("Hello")
    }

    @Test
    fun twoByteLengthsAndMultiByteUtf8() {
        val long = s("▁" + "x".repeat(200))
        val tok = MoonshineTokenizer.parse(bin(listOf(long, s("▁caf"), s("é"))))
        assertThat(tok.decode(intArrayOf(0))).isEqualTo("x".repeat(200))
        assertThat(tok.decode(intArrayOf(1, 2))).isEqualTo("café")
    }

    @Test
    fun truncatedDataIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { MoonshineTokenizer.parse(byteArrayOf(5, 'a'.code.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { MoonshineTokenizer.parse(byteArrayOf(200.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { MoonshineTokenizer.parse(ByteArray(0)) }
    }
}
