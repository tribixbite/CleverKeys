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
}
