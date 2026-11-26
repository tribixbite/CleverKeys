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
}
