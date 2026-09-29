package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Next-word contraction continuations (2026-09-29 follow-up to the multilingual static LM).
 *
 * The shipped context models emit contraction DISPLAY forms as continuations ("don't",
 * "c'est"), but every bundled dictionary stores contractions apostrophe-free ("dont", "cest"
 * — contraction-system skill §1). [NextWordPredictor.candidatesFor]'s dictionary filter asked
 * only about the display form, so every contraction continuation was dropped before it could
 * reach the bar. These cases pin that an apostrophe form is admitted through its
 * apostrophe-free dictionary key — and that the admission is exactly as strict as for any
 * other word: the Dictionary Manager disable of the key blocks the display form too, and an
 * apostrophe form whose key is unknown is still rejected.
 */
class NextWordContractionAllowTest {

    private val staticOnly = NextWordPredictor.TierGate(
        showStatic = true, useLearned = false, fieldAllowsPersonalizedLearning = true
    )

    /** Dictionary holds apostrophe-free keys only, as every shipped dictionary does. */
    private fun predictor(
        dictionary: Set<String>,
        seed: List<String>,
        disabled: Set<String> = emptySet(),
    ): Predictor = mockk(relaxed = true) {
        every { isInDictionary(any(), any()) } answers { firstArg<String>() in dictionary }
        every { isInDictionary(any()) } answers { firstArg<String>() in dictionary }
        every { isWordDisabled(any()) } answers { firstArg<String>() in disabled }
        every { getStaticNextWordSeed(any(), any()) } returns
            seed.mapIndexed { i, w -> StaticBigramSeed.Continuation(w, (seed.size - i).toFloat()) }
    }

    private fun words(p: Predictor) =
        NextWordPredictor.candidatesFor(staticOnly, listOf("i"), p).map { it.word }

    @Test
    fun `a contraction continuation is admitted through its apostrophe-free dictionary key`() {
        val p = predictor(dictionary = setOf("dont", "am", "have"), seed = listOf("don't", "am", "have"))
        assertThat(words(p)).contains("don't")
    }

    @Test
    fun `a curly-apostrophe continuation is admitted the same way`() {
        val p = predictor(dictionary = setOf("cest", "est"), seed = listOf("c’est", "est"))
        assertThat(words(p)).contains("c’est")
    }

    @Test
    fun `an apostrophe form whose key is not in the dictionary is still rejected`() {
        val p = predictor(dictionary = setOf("am"), seed = listOf("zq'x", "am"))
        assertThat(words(p)).doesNotContain("zq'x")
    }

    @Test
    fun `disabling the apostrophe-free key in Dictionary Manager blocks the display form`() {
        val p = predictor(
            dictionary = setOf("dont", "am"), seed = listOf("don't", "am"), disabled = setOf("dont")
        )
        assertThat(words(p)).doesNotContain("don't")
    }
}
