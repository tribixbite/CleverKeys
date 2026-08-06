package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.ContextContinuation

/**
 * Pure-JVM tests for [NextWordPredictor] (audit 2026-08-06 §4): candidate
 * generation, filters/floors, dedup, and the show-gate that inherits the
 * existing suggestion-bar guards — including the MASTER on-device-learning
 * gate (Task A) and trigram-sourced continuations (Task C).
 */
class NextWordPredictorTest {

    private fun entry(w2: String, freq: Int, prob: Float, fromTrigram: Boolean = false) =
        ContextContinuation(w2, freq, prob, fromTrigram)

    private val allowAll: (String) -> Boolean = { true }
    private val noBoost: (String) -> Float = { 0f }

    // ------------------------------------------------------------------ gating

    @Test
    fun `disabled feature never shows - the opt-in default`() {
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = false, onDeviceLearningEnabled = true,
                wordPredictionEnabled = true,
                isPasswordMode = false, specialPromptActive = false,
                inTermuxApp = false, hasContext = true
            )
        )
    }

    @Test
    fun `master on-device-learning gate blocks next-word surfacing`() {
        // Task A: next-word reads the learned store, so the master privacy gate
        // must make it go dark even when the feature itself is enabled.
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = true, onDeviceLearningEnabled = false,
                wordPredictionEnabled = true,
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
            wordPrediction: Boolean = true,
            master: Boolean = true,
            fieldAllows: Boolean = true
        ) = NextWordPredictor.shouldShow(
            featureEnabled = true, onDeviceLearningEnabled = master,
            wordPredictionEnabled = wordPrediction,
            isPasswordMode = password, specialPromptActive = prompt,
            inTermuxApp = termux, hasContext = context,
            fieldAllowsPersonalizedLearning = fieldAllows
        )

        assertTrue(show())
        assertFalse(show(password = true))
        assertFalse(show(prompt = true))
        assertFalse(show(termux = true))
        assertFalse(show(context = false))
        assertFalse(show(wordPrediction = false))
        assertFalse(show(master = false))
        // M5 (review 2026-08-06): incognito fields suppress next-word surfacing.
        assertFalse(show(fieldAllows = false))
    }

    // ------------------------------------------------------------- generation

    @Test
    fun `generates ranked candidates from learned continuations`() {
        val learned = listOf(
            entry("to", 10, 0.7f),
            entry("more", 4, 0.2f),
            entry("food", 2, 0.1f)
        )
        val out = NextWordPredictor.generate(learned, "want", noBoost, allowAll)

        assertEquals(listOf("to", "more", "food"), out.map { it.word })
        assertTrue(out[0].score > out[1].score && out[1].score > out[2].score)
    }

    @Test
    fun `confidence floor drops low-frequency and low-probability entries`() {
        val learned = listOf(
            entry("cat", 5, 0.5f),
            entry("hapax", 1, 0.5f),       // freq < 2
            entry("unlikely", 5, 0.04f)    // prob < 5%
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(listOf("cat"), out.map { it.word })
    }

    @Test
    fun `empty result is acceptable - nothing beats noise`() {
        val learned = listOf(entry("b", 1, 0.01f))
        assertTrue(NextWordPredictor.generate(learned, null, noBoost, allowAll).isEmpty())
        assertTrue(NextWordPredictor.generate(emptyList(), null, noBoost, allowAll).isEmpty())
    }

    @Test
    fun `self-repetition of the just-committed word is dropped`() {
        val learned = listOf(
            entry("very", 6, 0.6f),
            entry("good", 4, 0.4f)
        )
        val out = NextWordPredictor.generate(learned, "Very", noBoost, allowAll)
        assertEquals(listOf("good"), out.map { it.word })
    }

    @Test
    fun `disallowed words are filtered - disabled or non-dictionary garbage`() {
        val learned = listOf(
            entry("teh", 5, 0.5f),   // absorbed typo, not in dictionary
            entry("am", 5, 0.4f)
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, { it != "teh" })
        assertEquals(listOf("am"), out.map { it.word })
    }

    @Test
    fun `dedup keeps first occurrence`() {
        val learned = listOf(
            entry("dup", 5, 0.5f),
            entry("dup", 3, 0.3f),
            entry("other", 3, 0.2f)
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(listOf("dup", "other"), out.map { it.word })
        assertEquals((0.5f * 1000).toInt(), out[0].score)
    }

    @Test
    fun `max suggestions caps output`() {
        val learned = (1..10).map { entry("c$it", 5, 0.5f) }
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(NextWordPredictor.MAX_SUGGESTIONS, out.size)

        val two = NextWordPredictor.generate(learned, null, noBoost, allowAll, maxSuggestions = 2)
        assertEquals(2, two.size)
    }

    @Test
    fun `personalization boost reorders candidates`() {
        val learned = listOf(
            entry("phone", 5, 0.30f),
            entry("keyboard", 5, 0.25f)
        )
        // User types "keyboard" constantly → boost 4 → multiplier 2.0
        val boost: (String) -> Float = { if (it == "keyboard") 4f else 0f }
        val out = NextWordPredictor.generate(learned, null, boost, allowAll)

        assertEquals("keyboard", out[0].word)
        assertEquals((0.25f * 2.0f * 1000).toInt(), out[0].score)
        assertEquals("phone", out[1].word)
    }

    // -------------------------------------------------- trigram continuations

    @Test
    fun `trigram-sourced continuations flow through with their statistics`() {
        val learned = listOf(
            entry("go", 6, 0.6f, fromTrigram = true),
            entry("see", 3, 0.3f)
        )
        val out = NextWordPredictor.generate(learned, null, noBoost, allowAll)
        assertEquals(2, out.size)
        assertTrue(out[0].fromTrigram)
        assertEquals(6, out[0].frequency)
        assertFalse(out[1].fromTrigram)
    }

    // ------------------------------------------------------- provenance notes

    @Test
    fun `provenance note shows the effective context and learned stats`() {
        val bigramNote = NextWordPredictor.provenanceNote(
            NextWordPredictor.Candidate("to", 700, 14, 0.63f, fromTrigram = false),
            listOf("i", "want")
        )
        assertTrue(bigramNote.contains("want"))
        assertFalse(bigramNote.contains("i want"))
        assertTrue(bigramNote.contains("14×"))
        assertTrue(bigramNote.contains("63%"))

        val trigramNote = NextWordPredictor.provenanceNote(
            NextWordPredictor.Candidate("go", 700, 5, 0.5f, fromTrigram = true),
            listOf("i", "want", "to")
        )
        assertTrue(trigramNote.contains("want to"))
    }
}
