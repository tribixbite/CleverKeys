package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.BigramEntry

/**
 * Pure-JVM tests for [NextWordPredictor] (audit 2026-08-06 §4): candidate
 * generation, filters/floors, dedup, and the show-gate that inherits the
 * existing suggestion-bar guards.
 */
class NextWordPredictorTest {

    private fun entry(w1: String, w2: String, freq: Int, prob: Float) =
        BigramEntry(w1, w2, freq, prob)

    private val allowAll: (String) -> Boolean = { true }
    private val noBoost: (String) -> Float = { 0f }

    // ------------------------------------------------------------------ gating

    @Test
    fun `disabled feature never shows - the opt-in default`() {
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = false, wordPredictionEnabled = true,
                isPasswordMode = false, specialPromptActive = false,
                inTermuxApp = false, hasContext = true
            )
        )
    }

    @Test
    fun `guards block password, special prompt, termux, and empty context`() {
        fun show(
            password: Boolean = false,
            prompt: Boolean = false,
            termux: Boolean = false,
            context: Boolean = true,
            wordPrediction: Boolean = true
        ) = NextWordPredictor.shouldShow(
            featureEnabled = true, wordPredictionEnabled = wordPrediction,
            isPasswordMode = password, specialPromptActive = prompt,
            inTermuxApp = termux, hasContext = context
        )

        assertTrue(show())
        assertFalse(show(password = true))
        assertFalse(show(prompt = true))
        assertFalse(show(termux = true))
        assertFalse(show(context = false))
        assertFalse(show(wordPrediction = false))
    }

    // ------------------------------------------------------------- generation

    @Test
    fun `generates ranked candidates from learned continuations`() {
        val learned = listOf(
            entry("want", "to", 10, 0.7f),
            entry("want", "more", 4, 0.2f),
            entry("want", "food", 2, 0.1f)
        )
        val out = NextWordPredictor.generate(learned, "want", noBoost, allowAll)

        assertEquals(listOf("to", "more", "food"), out.map { it.word })
        assertTrue(out[0].score > out[1].score && out[1].score > out[2].score)
    }

    @Test
    fun `confidence floor drops low-frequency and low-probability entries`() {
        val learned = listOf(
            entry("the", "cat", 5, 0.5f),
            entry("the", "hapax", 1, 0.5f),       // freq < 2
            entry("the", "unlikely", 5, 0.04f)    // prob < 5%
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(listOf("cat"), out.map { it.word })
    }

    @Test
    fun `empty result is acceptable - nothing beats noise`() {
        val learned = listOf(entry("a", "b", 1, 0.01f))
        assertTrue(NextWordPredictor.generate(learned, null, noBoost, allowAll).isEmpty())
        assertTrue(NextWordPredictor.generate(emptyList(), null, noBoost, allowAll).isEmpty())
    }

    @Test
    fun `self-repetition of the just-committed word is dropped`() {
        val learned = listOf(
            entry("very", "very", 6, 0.6f),
            entry("very", "good", 4, 0.4f)
        )
        val out = NextWordPredictor.generate(learned, "Very", noBoost, allowAll)
        assertEquals(listOf("good"), out.map { it.word })
    }

    @Test
    fun `disallowed words are filtered - disabled or non-dictionary garbage`() {
        val learned = listOf(
            entry("i", "teh", 5, 0.5f),   // absorbed typo, not in dictionary
            entry("i", "am", 5, 0.4f)
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, { it != "teh" })
        assertEquals(listOf("am"), out.map { it.word })
    }

    @Test
    fun `dedup keeps first occurrence`() {
        val learned = listOf(
            entry("a", "dup", 5, 0.5f),
            entry("a", "dup", 3, 0.3f),
            entry("a", "other", 3, 0.2f)
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(listOf("dup", "other"), out.map { it.word })
        assertEquals((0.5f * 1000).toInt(), out[0].score)
    }

    @Test
    fun `max suggestions caps output`() {
        val learned = (1..10).map { entry("w", "c$it", 5, 0.5f) }
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(NextWordPredictor.MAX_SUGGESTIONS, out.size)

        val two = NextWordPredictor.generate(learned, null, noBoost, allowAll, maxSuggestions = 2)
        assertEquals(2, two.size)
    }

    @Test
    fun `personalization boost reorders candidates`() {
        val learned = listOf(
            entry("my", "phone", 5, 0.30f),
            entry("my", "keyboard", 5, 0.25f)
        )
        // User types "keyboard" constantly → boost 4 → multiplier 2.0
        val boost: (String) -> Float = { if (it == "keyboard") 4f else 0f }
        val out = NextWordPredictor.generate(learned, null, boost, allowAll)

        assertEquals("keyboard", out[0].word)
        assertEquals((0.25f * 2.0f * 1000).toInt(), out[0].score)
        assertEquals("phone", out[1].word)
    }
}
