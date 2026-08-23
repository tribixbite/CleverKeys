package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CtcFuzzyRescueTest {
    private val rescue = CtcFuzzyRescue.fromFrequencies(linkedMapOf(
        "proximity" to 220.0,
        "property" to 210.0,
        "proximal" to 100.0,
        "hello" to 255.0,
    ))

    @Test
    fun recoversNearestDictionarySurface() {
        assertThat(rescue.find("proxity", emptySet())).contains("proximity")
    }

    @Test
    fun neverReturnsExistingBeamWordOrDifferentPrefix() {
        assertThat(rescue.find("proxity", setOf("proximity"))).doesNotContain("proximity")
        assertThat(rescue.find("jello", emptySet())).isEmpty()
    }

    @Test
    fun editDistanceHonorsCap() {
        assertThat(CtcFuzzyRescue.editDistanceAtMost("proxity", "proximity", 2)).isEqualTo(2)
        assertThat(CtcFuzzyRescue.editDistanceAtMost("abc", "xyz", 1)).isGreaterThan(1)
    }

    @Test
    fun scanBudgetIsDeterministicAndHardBounded() {
        // The two closer-length candidates are visited first; the matching length+2 entry
        // is deterministically the third and remains reachable at an exact budget of three.
        assertThat(rescue.find("proxity", emptySet(), scanBudget = 3))
            .containsExactly("proximity")
        assertThat(rescue.find("proxity", emptySet(), scanBudget = 0)).isEmpty()
    }
}
