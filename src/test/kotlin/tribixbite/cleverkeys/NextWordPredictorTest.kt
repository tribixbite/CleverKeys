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
                contextAwareEnabled = true,
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
                contextAwareEnabled = true,
                wordPredictionEnabled = true,
                isPasswordMode = false, specialPromptActive = false,
                inTermuxApp = false, hasContext = true
            )
        )
    }

    @Test
    fun `context-LM pref off blocks next-word even when its own toggle is stale-on`() {
        // Audit 2026-08-26: the Settings UI HIDES the next-word toggle when
        // `context_aware_predictions_enabled` is off, so the feature pref can sit
        // true with no visible control. The gate — not just a downstream store
        // check — must say no in that state, because the cursor-park path uses
        // the gate's cheap prerequisites to decide whether it may READ the
        // editor text at all.
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = true, onDeviceLearningEnabled = true,
                contextAwareEnabled = false,
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
            contextAware: Boolean = true,
            fieldAllows: Boolean = true
        ) = NextWordPredictor.shouldShow(
            featureEnabled = true, onDeviceLearningEnabled = master,
            contextAwareEnabled = contextAware,
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
        assertFalse(show(contextAware = false))
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

    // --------------------------------------------- static cold-start seed (ARC-020)

    private fun seed(vararg pairs: Pair<String, Float>) =
        pairs.map { StaticBigramSeed.Continuation(it.first, it.second) }

    @Test
    fun `cold store falls back to the shipped seed - the day-one case`() {
        // Before ARC-020 this returned an empty list until the user typed a
        // phrase twice at >=5% conditional probability, so a freshly enabled
        // next-word feature was dead for days.
        val out = NextWordPredictor.generate(
            learned = emptyList(),
            lastCommittedWord = "the",
            personalizationBoost = noBoost,
            isWordAllowed = allowAll,
            staticSeed = seed("same" to 0.85f, "best" to 0.83f, "first" to 0.82f)
        )
        assertEquals(listOf("same", "best", "first"), out.map { it.word })
        assertTrue(out.all { it.fromStaticSeed })
        // No fabricated learned statistics ride along.
        assertTrue(out.all { it.frequency == 0 && it.probability == 0f && !it.fromTrigram })
    }

    @Test
    fun `learned evidence outranks the seed and the seed only fills what is left`() {
        val learned = listOf(entry("cat", 9, 0.60f))
        val out = NextWordPredictor.generate(
            learned = learned,
            lastCommittedWord = null,
            personalizationBoost = noBoost,
            isWordAllowed = allowAll,
            staticSeed = seed("same" to 0.85f, "best" to 0.83f)
        )
        assertEquals(listOf("cat", "same", "best"), out.map { it.word })
        assertFalse(out[0].fromStaticSeed)
        assertTrue(out[1].fromStaticSeed && out[2].fromStaticSeed)
        // Every seeded score sits below the learned floor, so the debug-score
        // column reads monotonically with the displayed order.
        assertTrue(out[0].score > out[1].score)
        assertTrue(out[1].score <= NextWordPredictor.STATIC_SEED_SCORE_CEILING)
        assertTrue(out[2].score >= 1)
    }

    @Test
    fun `a seed rank never beats a weak learned candidate`() {
        // The seed's top rank (0.94) is numerically larger than the weakest
        // learned probability (0.05), so the tiers MUST be concatenated rather
        // than merged and re-sorted.
        val out = NextWordPredictor.generate(
            learned = listOf(entry("weak", 2, 0.05f)),
            lastCommittedWord = null,
            personalizationBoost = noBoost,
            isWordAllowed = allowAll,
            staticSeed = seed("strong" to 0.94f)
        )
        assertEquals(listOf("weak", "strong"), out.map { it.word })
        assertTrue(out[0].score > out[1].score)
    }

    @Test
    fun `a full learned slate never consults the seed`() {
        val learned = (1..3).map { entry("c$it", 5, 0.5f) }
        val out = NextWordPredictor.generate(
            learned = learned,
            lastCommittedWord = null,
            personalizationBoost = noBoost,
            isWordAllowed = allowAll,
            staticSeed = seed("unused" to 0.9f)
        )
        assertEquals(listOf("c1", "c2", "c3"), out.map { it.word })
        assertFalse(out.any { it.fromStaticSeed })
    }

    @Test
    fun `the seed inherits dedup, self-repetition, and the allow filter`() {
        val out = NextWordPredictor.generate(
            learned = listOf(entry("best", 9, 0.6f)),
            lastCommittedWord = "same",
            personalizationBoost = noBoost,
            isWordAllowed = { it != "garbage" },
            staticSeed = seed(
                "best" to 0.83f,     // already surfaced by the learned tier
                "same" to 0.85f,     // self-repetition of the committed word
                "garbage" to 0.80f,  // not allowed (disabled / non-dictionary)
                "first" to 0.78f
            )
        )
        assertEquals(listOf("best", "first"), out.map { it.word })
        assertFalse(out[0].fromStaticSeed)
        assertTrue(out[1].fromStaticSeed)
    }

    @Test
    fun `personalization does not reorder the shipped seed`() {
        // Personalization is a learned signal; letting it reorder shipped
        // content would blur the two tiers the provenance sheet distinguishes.
        val boost: (String) -> Float = { if (it == "second") 6f else 0f }
        val out = NextWordPredictor.generate(
            learned = emptyList(),
            lastCommittedWord = null,
            personalizationBoost = boost,
            isWordAllowed = allowAll,
            staticSeed = seed("first" to 0.90f, "second" to 0.80f)
        )
        assertEquals(listOf("first", "second"), out.map { it.word })
    }

    @Test
    fun `no learned data and no seed still yields nothing`() {
        assertTrue(
            NextWordPredictor.generate(emptyList(), null, noBoost, allowAll, emptyList()).isEmpty()
        )
        assertTrue(
            NextWordPredictor.generate(
                learned = emptyList(),
                lastCommittedWord = null,
                personalizationBoost = noBoost,
                isWordAllowed = allowAll,
                staticSeed = seed("x" to 0.9f),
                maxSuggestions = 0
            ).isEmpty()
        )
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

    // ------------------------------------- editor-context tokenizer (L5 fix)

    @Test
    fun `editor context extracts trailing words oldest-first`() {
        assertEquals(
            listOf("i", "want", "to"),
            NextWordPredictor.contextFromEditorText("i want to ")
        )
        // No trailing space: park branch guarantees no partial word, so the
        // token touching the cursor is complete and must be included.
        assertEquals(
            listOf("i", "want", "to"),
            NextWordPredictor.contextFromEditorText("i want to")
        )
    }

    @Test
    fun `editor context is capped at the learn window, keeping the newest words`() {
        assertEquals(
            listOf("two", "three", "four", "five"),
            NextWordPredictor.contextFromEditorText("one two three four five ")
        )
        assertEquals(
            listOf("five"),
            NextWordPredictor.contextFromEditorText("one two three four five ", maxWords = 1)
        )
        assertEquals(
            emptyList<String>(),
            NextWordPredictor.contextFromEditorText("one two ", maxWords = 0)
        )
    }

    @Test
    fun `editor context stops at the last sentence boundary`() {
        // Mirrors WordPredictor.onSentenceBoundary: learned context never spans
        // a sentence boundary, so neither may park-derived context.
        assertEquals(
            listOf("i", "want"),
            NextWordPredictor.contextFromEditorText("Hello there. i want ")
        )
        assertEquals(
            emptyList<String>(),
            NextWordPredictor.contextFromEditorText("All done! ")
        )
        assertEquals(
            emptyList<String>(),
            NextWordPredictor.contextFromEditorText("Really? ")
        )
        // Line breaks are paragraph boundaries too.
        assertEquals(
            listOf("new", "para"),
            NextWordPredictor.contextFromEditorText("line one\nnew para ")
        )
    }

    @Test
    fun `editor context normalizes case and keeps word-internal apostrophes and hyphens`() {
        assertEquals(
            listOf("can't", "stop"),
            NextWordPredictor.contextFromEditorText("Can't STOP ")
        )
        assertEquals(
            listOf("co-op", "board"),
            NextWordPredictor.contextFromEditorText("co-op board ")
        )
        // Edge quotes are trimmed; the quoted word still matches store keys.
        assertEquals(
            listOf("hello", "world"),
            NextWordPredictor.contextFromEditorText("'hello' world ")
        )
    }

    @Test
    fun `editor context treats digits and symbols as separators`() {
        // The typing tracker only accumulates letters into committed words, so
        // learned keys never contain digits — digit runs must not glue tokens.
        assertEquals(
            listOf("call", "now"),
            NextWordPredictor.contextFromEditorText("call 911 now ")
        )
        assertEquals(
            listOf("see", "you"),
            NextWordPredictor.contextFromEditorText("see @ you ")
        )
    }

    @Test
    fun `editor context of an empty or blank field is empty`() {
        assertEquals(emptyList<String>(), NextWordPredictor.contextFromEditorText(""))
        assertEquals(emptyList<String>(), NextWordPredictor.contextFromEditorText("   "))
        assertEquals(emptyList<String>(), NextWordPredictor.contextFromEditorText("--- '' "))
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

    @Test
    fun `a seeded candidate says built-in instead of faking learned stats`() {
        val note = NextWordPredictor.provenanceNote(
            NextWordPredictor.Candidate(
                "best", 42, frequency = 0, probability = 0f,
                fromTrigram = false, fromStaticSeed = true
            ),
            listOf("i", "want", "the")
        )
        assertTrue(note.contains("the"))
        assertTrue(note.contains("built-in"))
        // "seen 0×, 0%" would read as real evidence that does not exist.
        assertFalse(note.contains("seen"))
        assertFalse(note.contains("0%"))
    }
}
