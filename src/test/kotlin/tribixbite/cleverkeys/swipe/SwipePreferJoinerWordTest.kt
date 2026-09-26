package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipeCorrectionPolicy
import tribixbite.cleverkeys.SwipeCorrectionTracker
import tribixbite.cleverkeys.UserWordFrequency
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconTrie

/**
 * Why the "Prefer “Y” when swiping?" offer never offers an apostrophe or hyphen word
 * (`SwipeCorrectionPolicy` rule 2; learning-system audit 2026-09-26, Resolution → task 3).
 *
 * The offer's whole mechanism is "a personal-dictionary entry at the user frequency makes the
 * swipe produce that word". On the EN CTC path (the default engine and the only one whose
 * paired-variant placement compares frequencies) that is FALSE for a joiner word, and composing
 * the real pure pieces exactly as `CtcEngineAdapter` wires them shows how:
 *
 *  1. `CtcLexiconTrie.loadStrippingNonAlphabet` files the user word `she'd` under the a–z surface
 *     `shed` — and the trie keeps the MAX frequency per surface, so the entry lifts `shed` (the
 *     word the user was correcting AWAY from) to the user ceiling.
 *  2. `ContractionOverlay` then decides the display of the decoded `shed` from the pairing
 *     frequency of `she'd` against the base frequency of `shed` looked up in the merged lexicon —
 *     a lookup the user word `she'd` does not touch — so `shed` keeps rank 0 and is auto-inserted.
 *  3. A hyphen word has no overlay entry at all and the EN branch keeps no display map
 *     (`display = emptyMap()`), so `co-op` can only ever surface as `coop`.
 *
 * Accepting the offer would therefore make the unwanted word MORE likely. Making it work needs
 * decoder-side changes outside the offer (a user-word term in the overlay's promotion rule, and
 * a display map for joiner user words on the EN branch) — recorded as follow-ups in the audit doc.
 * If those land, this test is the one to flip.
 */
class SwipePreferJoinerWordTest {

    private val alphabet = ('a'..'z').toList().toCharArray()

    /** en_enhanced.json byte scale: floor 134, `shed` 188, `she` 250 (skill §6c table). */
    private val base = listOf("the" to 255.0, "she" to 250.0, "shed" to 188.0, "coop" to 150.0, "zebra" to 134.0)

    private fun merged(custom: List<Pair<String, Int>>) = CtcLexiconMerge.merge(base, custom, emptySet())

    /** `CtcEngineAdapter.applyContractionDisplay` over a decoded slate, base freqs from [merged]. */
    private fun display(slate: List<String>, merged: LinkedHashMap<String, Double>): List<String> {
        val ordinals = CtcLexiconMerge.ordinals(merged)
        return ContractionOverlay.apply(
            words = slate,
            scores = slate.indices.map { 100 - it },
            pairedVariants = { if (it == "shed") listOf("she'd") else null },
            nonPairedMapping = { null },
            wordOrdinal = { ordinals[it] },
            pairedVariantFrequency = { b, v -> if (b == "shed" && v == "she'd") 189 else null },
            baseFrequency = { w -> merged[w]?.toInt() },
        ).first
    }

    @Test
    fun theOfferNeverConsidersAJoinerWord() {
        val swipe = SwipeCorrectionTracker.SwipeRecord("shed", listOf("shed", "she'd"), null)
        assertThat(SwipeCorrectionPolicy.isPlausible(swipe, "she'd") { true }).isFalse()
        val hyphen = SwipeCorrectionTracker.SwipeRecord("coop", listOf("coop", "co-op"), null)
        assertThat(SwipeCorrectionPolicy.isPlausible(hyphen, "co-op") { true }).isFalse()
    }

    @Test
    fun anApostropheUserWordLiftsTheApostropheFreeSurfaceInstead() {
        val without = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, merged(emptyList()))
        val with = CtcLexiconTrie.loadStrippingNonAlphabet(
            alphabet, merged(listOf("she'd" to UserWordFrequency.DEFAULT))
        )

        assertWithMessage("the apostrophe form is not a trie word").that(with.contains("she'd")).isFalse()
        assertWithMessage("it is filed under — and raises — the surface the user corrected away from")
            .that(with.logFrequencyOf("shed")!!).isGreaterThan(without.logFrequencyOf("shed")!!)
    }

    @Test
    fun theOverlayStillAutoInsertsTheBaseWhenTheVariantIsAUserWord() {
        val slate = display(listOf("shed", "she"), merged(listOf("she'd" to UserWordFrequency.DEFAULT)))

        assertWithMessage("preferring she'd leaves shed at rank 0 (the auto-insert)").that(slate.first()).isEqualTo("shed")
        assertThat(slate).contains("she'd")
    }

    @Test
    fun aHyphenUserWordSurfacesOnlyWithoutItsHyphen() {
        val merged = merged(listOf("co-op" to UserWordFrequency.DEFAULT))
        val trie = CtcLexiconTrie.loadStrippingNonAlphabet(alphabet, merged)

        assertThat(trie.contains("coop")).isTrue()
        assertWithMessage("no overlay entry and no EN display map: the decoded surface is shown as is")
            .that(display(listOf("coop"), merged)).containsExactly("coop")
    }
}
