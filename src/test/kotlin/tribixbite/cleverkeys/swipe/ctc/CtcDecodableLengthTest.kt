package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Pins the CTC frame budget and the duplicate-letter rule behind it.
 *
 * The failure this guards is silent: a word too long for the 32-frame emission head has no valid
 * CTC alignment, so the beam can never emit it — but it merges into the lexicon, occupies trie
 * nodes, and looks exactly like a word the user swiped badly.
 */
class CtcDecodableLengthTest {

    @Test
    fun `frames counts one per character plus a blank between duplicates`() {
        assertThat(CtcDecodableLength.framesRequired("")).isEqualTo(0)
        assertThat(CtcDecodableLength.framesRequired("a")).isEqualTo(1)
        assertThat(CtcDecodableLength.framesRequired("ab")).isEqualTo(2)
        // The whole point: `bb` cannot be emitted as two adjacent frames — CTC would collapse
        // them into one `b` — so it costs a separating blank.
        assertThat(CtcDecodableLength.framesRequired("abb")).isEqualTo(4)
        assertThat(CtcDecodableLength.framesRequired("class")).isEqualTo(6)
        // Non-adjacent repeats are free: only ADJACENT identicals collapse.
        assertThat(CtcDecodableLength.framesRequired("banana")).isEqualTo(6)
        // Case-insensitive, because the trie stores lowercased surfaces.
        assertThat(CtcDecodableLength.framesRequired("aA")).isEqualTo(3)
    }

    @Test
    fun `the ceiling is 32 plain letters and lower with duplicates`() {
        assertThat(CtcDecodableLength.isDecodable("a".repeat(32))).isFalse() // 32 doubles = 63
        assertThat(CtcDecodableLength.isDecodable("ab".repeat(16))).isTrue() // 32 chars, no dups
        assertThat(CtcDecodableLength.isDecodable("ab".repeat(16) + "c")).isFalse() // 33

        // A doubled-letter word costs a frame per duplicate pair, so the practical ceiling is
        // word-specific — this is why the warning must report headroom rather than a constant.
        assertThat(CtcDecodableLength.framesRequired("aaaa")).isEqualTo(7)
        assertThat(CtcDecodableLength.isDecodable("a".repeat(16))).isTrue() // exactly 31
        assertThat(CtcDecodableLength.isDecodable("a".repeat(17))).isFalse() // 33
    }

    @Test
    fun `every bundled English word is decodable`() {
        // If the shipped lexicon itself contained an undecodable entry, the ceiling would be a
        // live defect rather than a guard for user-added words. Verified against the real asset.
        val json = File("src/main/assets/dictionaries/en_enhanced.json")
        assertWithMessage("shipped dictionary must exist (run from project root)")
            .that(json.isFile).isTrue()

        val undecodable = Regex("\"([a-zA-Z']{20,})\"\\s*:").findAll(json.readText())
            .map { it.groupValues[1] }
            .filterNot { CtcDecodableLength.isDecodable(it.replace("'", "")) }
            .toList()

        assertWithMessage(
            "these shipped words can never be produced by the CTC beam — if this ever fires, " +
                "the dictionary and the model disagree and the DICTIONARY is what to fix"
        ).that(undecodable).isEmpty()
    }
}
