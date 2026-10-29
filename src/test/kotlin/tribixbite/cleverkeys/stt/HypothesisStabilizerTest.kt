package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [HypothesisStabilizer]: the two-pass agreement rule and its "commits never change" guarantee. */
class HypothesisStabilizerTest {

    @Test
    fun aWordIsStableOnlyOnceTwoConsecutivePassesAgree() {
        val s = HypothesisStabilizer()
        val p1 = s.update("hello", isFinal = false)
        assertThat(p1.stableWordCount).isEqualTo(0)
        assertThat(p1.text).isEqualTo("hello")
        val p2 = s.update("hello world", isFinal = false)
        assertThat(p2.stableWords).containsExactly("hello")
        assertThat(p2.pendingWords).containsExactly("world")
        val p3 = s.update("hello world how", isFinal = false)
        assertThat(p3.stableWords).containsExactly("hello", "world").inOrder()
    }

    @Test
    fun agreementIgnoresCaseAndSurroundingPunctuationButKeepsTheNewerSurfaceForm() {
        val s = HypothesisStabilizer()
        s.update("Hello world", isFinal = false)
        val p = s.update("hello, world. And", isFinal = false)
        assertThat(p.stableWords).containsExactly("hello,", "world.").inOrder()
    }

    @Test
    fun aRevisedPendingWordIsNotCommitted() {
        val s = HypothesisStabilizer()
        s.update("I scream", isFinal = false)
        val p = s.update("ice cream", isFinal = false)
        assertThat(p.stableWordCount).isEqualTo(0)
        assertThat(p.text).isEqualTo("ice cream")
    }

    @Test
    fun committedWordsNeverChangeEvenIfALaterPassRevisesThem() {
        val s = HypothesisStabilizer()
        s.update("the cat sat", isFinal = false)
        s.update("the cat sat on", isFinal = false) // commits the cat sat
        val p = s.update("a cat sat on the mat", isFinal = false)
        assertThat(p.text).isEqualTo("the cat sat on the mat")
        // Agreement past the committed boundary: both passes say "on" after "… sat".
        assertThat(p.stableWords).containsExactly("the", "cat", "sat", "on").inOrder()
    }

    @Test
    fun theFinalPassMakesEveryWordStable() {
        val s = HypothesisStabilizer()
        s.update("good morning", isFinal = false)
        val f = s.update("good morning everyone.", isFinal = true)
        assertThat(f.isFinal).isTrue()
        assertThat(f.stableWordCount).isEqualTo(3)
        assertThat(f.text).isEqualTo("good morning everyone.")
    }

    @Test
    fun stableCountIsMonotone() {
        val s = HypothesisStabilizer()
        val passes = listOf("one", "one two", "one two three", "one", "one two three four")
        var last = 0
        for (p in passes) {
            val h = s.update(p, isFinal = false)
            assertThat(h.stableWordCount).isAtLeast(last)
            last = h.stableWordCount
        }
    }

    @Test
    fun normaliseStripsOnlyTheOutside() {
        assertThat(HypothesisStabilizer.normalise("Don't,")).isEqualTo("don't")
        assertThat(HypothesisStabilizer.normalise("\"E-mail\"")).isEqualTo("e-mail")
        assertThat(HypothesisStabilizer.normalise("...")).isEqualTo("")
    }
}
