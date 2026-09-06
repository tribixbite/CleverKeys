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
        // "floorfill" pins the base floor at 1 so the wave-U2 calibration is the
        // identity here and this test keeps exercising ONLY the clamp + ordering.
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 200.0, "floorfill" to 1.0),
            custom = listOf("zebra" to 1000, "quiet" to 0, " " to 50),
            disabled = emptySet(),
        )
        // Blank custom word skipped; 1000 clamps down to 255, 0 clamps up to 1.
        assertThat(merged.keys.toList())
            .containsExactly("zebra", "quiet", "hello", "floorfill").inOrder()
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
        // "floorfill" (also disabled) keeps the base floor at 1 → identity calibration,
        // so the assertion stays about override-disabled semantics, not the scale map.
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 250.0, "world" to 240.0, "floorfill" to 1.0),
            custom = listOf("hello" to 42),
            disabled = setOf("hello", "world", "floorfill"),
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
        // "floorfill" keeps the base floor at 1 → identity calibration (see above).
        val merged = CtcLexiconMerge.merge(
            base = listOf("hello" to 250.0, "floorfill" to 1.0),
            custom = listOf("Hello" to 30),
            disabled = emptySet(),
        )
        // Without the L3 fold both entries would enter and the trie's max-retention
        // rule would silently resurrect the base frequency for the lowercased word.
        assertThat(merged.keys.toList()).containsExactly("Hello", "floorfill").inOrder()
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

    // ── merge: custom-frequency calibration onto the base lexicon's scale ───────────
    //
    // Wave U2 (maintainer report: "custom words are harder to swipe than they should
    // be"). The en base lexicon's byte scale is 134..255 — a compressed floor, not 1.
    // The old merge clamped the user's stored value into 1..255 RAW, so the historical
    // dialog default of 100 landed BELOW the entire English dictionary in the
    // λ-weighted lexicon prior (λ_en = 4.0 × ln f: ln 100 = 4.61 < ln 134 = 4.90).
    // The calibrated merge interprets the stored 1..255 value RELATIVE to the active
    // base scale: [1..255] maps linearly onto [base_floor..255], where base_floor is
    // derived from the merge's own base iterable. CKDT scales already span 1..255, so
    // their floor is 1 and the map degenerates to identity.

    @Test
    fun `custom word at the legacy default 100 maps above the en-scale floor`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("common" to 255.0, "rare" to 134.0),
            custom = listOf("flurble" to 100),
            disabled = emptySet(),
        )
        // Never below the whole dictionary: the mapped value must clear the base floor.
        assertThat(merged["flurble"]).isGreaterThan(134.0)
        // Exact linear map [1..255] -> [134..255]: 134 + (99/254)*121.
        assertThat(merged["flurble"]!!).isWithin(1e-9).of(134.0 + (99.0 / 254.0) * 121.0)
    }

    @Test
    fun `custom stored bounds map to the base floor and the 255 cap`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("common" to 255.0, "rare" to 134.0),
            custom = listOf("floorword" to 1, "capword" to 255, "overword" to 9999),
            disabled = emptySet(),
        )
        // stored 1 = "as rare as the rarest base word", never rarer.
        assertThat(merged["floorword"]).isEqualTo(134.0)
        // stored 255 = top of the scale; out-of-range still saturates at the cap.
        assertThat(merged["capword"]).isEqualTo(255.0)
        assertThat(merged["overword"]).isEqualTo(255.0)
    }

    @Test
    fun `custom frequency ordering is preserved by the scale map`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("common" to 255.0, "rare" to 134.0),
            custom = listOf("lo" to 50, "mid" to 100, "hi" to 200),
            disabled = emptySet(),
        )
        assertThat(merged["lo"]!!).isLessThan(merged["mid"]!!)
        assertThat(merged["mid"]!!).isLessThan(merged["hi"]!!)
    }

    @Test
    fun `CKDT full-range base leaves stored custom values unchanged`() {
        // CKDT lexicons (freq = max(1, 255 - rank)) reach a floor of 1, so the
        // calibration must be an identity there — their λ preset was fitted to the
        // full 1..255 range.
        val merged = CtcLexiconMerge.merge(
            base = listOf("common" to 255.0, "rarest" to 1.0),
            custom = listOf("myword" to 100),
            disabled = emptySet(),
        )
        assertThat(merged["myword"]).isEqualTo(100.0)
    }

    @Test
    fun `custom word at the legacy default ordinal-ranks above every floor base word`() {
        val merged = CtcLexiconMerge.merge(
            base = listOf("common" to 255.0, "rare" to 134.0, "rarer" to 134.0),
            custom = listOf("flurble" to 100),
            disabled = emptySet(),
        )
        val ordinals = CtcLexiconMerge.ordinals(merged)
        // The user's word must not sit behind the entire base dictionary.
        assertThat(ordinals["flurble"]!!).isLessThan(ordinals["rare"]!!)
        assertThat(ordinals["flurble"]!!).isLessThan(ordinals["rarer"]!!)
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
