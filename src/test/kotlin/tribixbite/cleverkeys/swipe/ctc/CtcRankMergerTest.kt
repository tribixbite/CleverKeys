package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CtcRankMergerTest {
    @Test
    fun secondaryTopWordIsReachableWithoutComparingRawScores() {
        val merged = CtcRankMerger.merge(
            "fr", listOf("bonjour", "bonsoir", "bonne"),
            "en", listOf("hello", "help", "held"),
            limit = 6,
        )

        assertThat(merged.map { it.word })
            .containsExactly("bonjour", "hello", "bonsoir", "help", "bonne", "held")
            .inOrder()
        assertThat(merged[1].language).isEqualTo("en")
    }

    @Test
    fun duplicateCanonicalWordKeepsPrimarySlot() {
        val merged = CtcRankMerger.merge(
            "fr", listOf("menu", "salut"),
            "en", listOf("menu", "hello"),
            limit = 4,
        )

        assertThat(merged.map { it.word }).containsExactly("menu", "salut", "hello").inOrder()
        assertThat(merged.first().language).isEqualTo("fr")
    }

    @Test
    fun anEarnedRankGoesAheadOfThePrimaryWordAtThatRank() {
        // Secondary index 2 (`parcero`, a user word) earned primary rank 1.
        val merged = CtcRankMerger.merge(
            "en", listOf("parked", "parcel", "pardo"),
            "es", listOf("paredes", "parcela", "parcero"),
            limit = 6,
            secondaryEarnedRanks = mapOf(2 to 1),
        )

        assertThat(merged.map { it.word })
            .containsExactly("parked", "paredes", "parcero", "parcel", "parcela", "pardo").inOrder()
        assertThat(merged[2]).isEqualTo(CtcRankMerger.Item("parcero", 500, "es"))
    }

    @Test
    fun anEarnedRankAtZeroTakesRankOne() {
        val merged = CtcRankMerger.merge(
            "en", listOf("parcel", "pardo"), "es", listOf("parcero"), limit = 3,
            secondaryEarnedRanks = mapOf(0 to 0),
        )

        assertThat(merged.map { it.word }).containsExactly("parcero", "parcel", "pardo").inOrder()
    }

    @Test
    fun anEarnedRankNeverDemotesBelowThePlainSecondarySlot() {
        val plain = CtcRankMerger.merge("en", listOf("parcel", "pardo"), "es", listOf("parcero"), limit = 3)
        val earnedLow = CtcRankMerger.merge(
            "en", listOf("parcel", "pardo"), "es", listOf("parcero"), limit = 3,
            secondaryEarnedRanks = mapOf(0 to 5),
        )

        assertThat(earnedLow).isEqualTo(plain)
    }

    @Test
    fun noEarnedRanksIsThePlainMerge() {
        val words = listOf("bonjour", "bonsoir")
        val other = listOf("hello", "help")
        assertThat(CtcRankMerger.merge("fr", words, "en", other, 4, emptyMap()))
            .isEqualTo(CtcRankMerger.merge("fr", words, "en", other, 4))
    }

    @Test
    fun aPlacedWordThatIsAlsoAPrimaryWordKeepsThePrimaryLabel() {
        // Audit C7: the placed Spanish copy ties the English copy and sorts first; the merged entry
        // keeps the English label (and spelling), or the English possessive augment skips it.
        val tie = CtcRankMerger.merge(
            "en", listOf("parcel", "Parcero"), "es", listOf("parada", "parcela", "parcero"), limit = 4,
            secondaryEarnedRanks = mapOf(2 to 1),
        )
        assertThat(tie).containsExactly(
            CtcRankMerger.Item("parcel", 1000, "en"), CtcRankMerger.Item("parada", 920, "es"),
            CtcRankMerger.Item("Parcero", 500, "en"), CtcRankMerger.Item("parcela", 460, "es"),
        ).inOrder()

        val ahead = CtcRankMerger.merge(
            "en", listOf("parcel", "parcero"), "es", listOf("parcero"), limit = 3,
            secondaryEarnedRanks = mapOf(0 to 0),
        )
        assertThat(ahead.map { it.word to it.language })
            .containsExactly("parcero" to "en", "parcel" to "en").inOrder()
    }

    @Test
    fun aUserWordInBothListsAboveItsPrimaryCopyKeepsThePrimaryLabel() {
        // Its plain Spanish slot (920) beats both its earned rank and its English copy (500).
        val merged = CtcRankMerger.merge(
            "en", listOf("parcel", "Parcero"), "es", listOf("parcero"), limit = 3,
            secondaryEarnedRanks = mapOf(0 to 1),
        )

        assertThat(merged).containsExactly(
            CtcRankMerger.Item("parcel", 1000, "en"), CtcRankMerger.Item("Parcero", 920, "en"),
        ).inOrder()
    }

    @Test
    fun anOrdinaryWordInBothSlatesKeepsTheSecondaryLabelAboveThePrimaryCopy() {
        // Unchanged by audit C7: only the user's own words take the primary label.
        val merged = CtcRankMerger.merge(
            "fr", listOf("bonjour", "salut", "menu"), "en", listOf("menu"), limit = 3,
            secondaryEarnedRanks = emptyMap(),
        )

        assertThat(merged.map { it.word to it.language })
            .containsExactly("bonjour" to "fr", "menu" to "en", "salut" to "fr").inOrder()
    }
}
