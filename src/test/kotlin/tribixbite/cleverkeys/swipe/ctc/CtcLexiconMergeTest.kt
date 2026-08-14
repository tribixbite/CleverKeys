package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * G5 audit — [CtcLexiconMerge], the pure user-dictionary merge + ordinal ranking
 * behind `CtcEngineAdapter.lexiconFor` (the trie-merge oracle owed by the CTC
 * integration audit, mirroring the geometric adapter's merge semantics):
 *
 *  - custom words first, frequency clamped onto the 1..255 AOSP-like scale;
 *  - custom overrides disabled (WordPredictor customAndUserWords semantics);
 *  - case-folded dedupe (audit L3: custom "Hello" must shadow base "hello" —
 *    the trie lowercases at insert, so a case-dupe would otherwise let the base
 *    frequency win via the trie's max-retention rule);
 *  - disabled filtering of the base is case-insensitive;
 *  - apostrophe aliases stay reachable through the STRIP trie loader;
 *  - [CtcLexiconMerge.ordinals] ranks frequency-descending with a stable
 *    insertion-order tie-break (custom words rank ahead of equal-frequency base
 *    words — the geometric "user words get favorable rank" bias).
 */
class CtcLexiconMergeTest {

    private val alphabet = CharArray(26) { ('a' + it) }

    // ── merge: custom-first + clamp ─────────────────────────────────────────────────

    @Test
    fun `custom words come first with frequency clamped to 1-255`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 200.0),
            custom = listOf("zebra" to 1000, "quiet" to 0, " " to 50),
            disabled = emptySet(),
        )
        // Blank custom word skipped; 1000 clamps down to 255, 0 clamps up to 1.
        assertThat(merged.keys.toList()).containsExactly("zebra", "quiet", "hello").inOrder()
        assertThat(merged["zebra"]).isEqualTo(255.0)
        assertThat(merged["quiet"]).isEqualTo(1.0)
        assertThat(merged["hello"]).isEqualTo(200.0)
    }

    @Test
    fun `base frequency is floored to 1`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("weird" to 0.0),
            custom = emptyList(),
            disabled = emptySet(),
        )
        assertThat(merged["weird"]).isEqualTo(1.0)
    }

    // ── merge: custom overrides disabled ────────────────────────────────────────────

    @Test
    fun `custom overrides disabled — the word survives with the custom frequency`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 250.0, "world" to 240.0),
            custom = listOf("hello" to 42),
            disabled = setOf("hello", "world"),
        )
        assertThat(merged.keys.toList()).containsExactly("hello")
        assertThat(merged["hello"]).isEqualTo(42.0)
    }

    @Test
    fun `disabled filtering of the base is case-insensitive`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("Bad" to 200.0, "fine" to 100.0),
            custom = emptyList(),
            disabled = setOf("bAD"),
        )
        assertThat(merged.keys.toList()).containsExactly("fine")
    }

    // ── merge: case-folded dedupe (audit L3) ────────────────────────────────────────

    @Test
    fun `custom Hello shadows base hello — case-folded dedupe`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 250.0),
            custom = listOf("Hello" to 30),
            disabled = emptySet(),
        )
        // Without the L3 fold both entries would enter and the trie's max-retention
        // rule would silently resurrect the base frequency for the lowercased word.
        assertThat(merged.keys.toList()).containsExactly("Hello")
        assertThat(merged["Hello"]).isEqualTo(30.0)
    }

    @Test
    fun `base case-duplicates keep only the first occurrence`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("Boston" to 180.0, "boston" to 160.0),
            custom = emptyList(),
            disabled = emptySet(),
        )
        assertThat(merged.keys.toList()).containsExactly("Boston")
        assertThat(merged["Boston"]).isEqualTo(180.0)
    }

    // ── alias reachability through the STRIP loader ─────────────────────────────────

    @Test
    fun `apostrophe words remain reachable as their a-z alias surface`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("don't" to 199.0, "aaron's" to 150.0),
            custom = emptyList(),
            disabled = emptySet(),
        )
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, merged)
        assertThat(trie.wordCount).isEqualTo(2)
        assertThat(trie.contains("dont")).isTrue()
        assertThat(trie.contains("aarons")).isTrue()
        assertThat(trie.contains("don't")).isFalse() // apostrophe is not a trie edge
    }

    // ── ordinals: frequency-descending, stable, case-folded ─────────────────────────

    @Test
    fun `ordinals rank by frequency descending with stable insertion tie-break`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("low" to 10.0, "highA" to 50.0, "highB" to 50.0),
            custom = emptyList(),
            disabled = emptySet(),
        )
        val ordinals = CtcLexiconMerge.ordinals(merged)
        assertThat(ordinals["higha"]).isEqualTo(0)
        assertThat(ordinals["highb"]).isEqualTo(1) // tie broken by insertion order
        assertThat(ordinals["low"]).isEqualTo(2)
    }

    @Test
    fun `clamped custom words outrank equal-frequency base words`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("the" to 255.0),
            custom = listOf("mycorp" to 1000), // clamps to 255 — ties with "the"
            disabled = emptySet(),
        )
        val ordinals = CtcLexiconMerge.ordinals(merged)
        // Custom insertion precedes base, so the stable tie-break favors the user word
        // (the geometric merge gets the same bias by prepending custom words).
        assertThat(ordinals["mycorp"]).isEqualTo(0)
        assertThat(ordinals["the"]).isEqualTo(1)
    }

    @Test
    fun `ordinal keys are lowercase with first case-variant winning`() {
        val merged = LinkedHashMap<String, Double>()
        merged["Hello"] = 50.0
        merged["world"] = 10.0
        val ordinals = CtcLexiconMerge.ordinals(merged)
        assertThat(ordinals).containsEntry("hello", 0)
        assertThat(ordinals).containsEntry("world", 1)
        assertThat(ordinals).doesNotContainKey("Hello")
    }
}
