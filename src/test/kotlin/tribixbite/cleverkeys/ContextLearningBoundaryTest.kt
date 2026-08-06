package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.personalization.UserVocabulary
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Behavioral tests for the 2026-08-06 review fixes around the LEARN WINDOW:
 *
 * - **H1 — cross-field/app leak**: the rolling recent-words window is cleared at
 *   every input-session boundary (`WordPredictor.clearContext`, now wired from
 *   `PredictionCoordinator.flushLearnedData` and password-mode entry), so no
 *   bigram/trigram can join text from app A to text from app B. Simulated here
 *   through the exact production funnel (`LearningGate.learnCommittedWord` over
 *   the real stores) with the boundary clear in between.
 * - **M3 — real "seen ≥2×" floor**: the per-commit sink is
 *   [ContextModel.recordCommit] (newest n-gram only), so ONE typing of a
 *   sentence yields frequency 1 per pair — below the confidence floors — and a
 *   pair only clears the floor once the user actually typed it twice.
 * - **M5 — incognito fields**: `IME_FLAG_NO_PERSONALIZED_LEARNING` suppresses
 *   the whole funnel AND next-word surfacing.
 *
 * Stores are the production classes over [InMemoryLearnedStorage] — only the
 * lambda glue differs from the device (same substrate as
 * [OnDeviceLearningPrivacyTest]).
 */
class ContextLearningBoundaryTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    // Generous debounce so nothing auto-flushes mid-test; explicit flush() only.
    private val bigramStorage = InMemoryLearnedStorage()
    private val trigramStorage = InMemoryLearnedStorage()
    private val vocabStorage = InMemoryLearnedStorage()
    private val bigramStore = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
    private val contextModel = ContextModel(bigramStore, trigramStore, "en")
    private val vocabulary = UserVocabulary(vocabStorage, 60_000, 120_000, scheduler)

    /**
     * Twin of `WordPredictor`'s recentWords buffer + learn funnel: same window
     * cap, same gate wiring, same newest-only sink (recordCommit).
     */
    private inner class PredictorTwin(private val fieldAllows: Boolean = true) {
        val recentWords = mutableListOf<String>()

        fun commit(word: String) {
            recentWords.add(word.lowercase().trim())
            while (recentWords.size > 10) recentWords.removeAt(0)
            LearningGate.learnCommittedWord(
                recentWords = recentWords,
                committedWord = recentWords.last(),
                onDeviceLearningEnabled = true,
                contextAwareEnabled = true,
                personalizedLearningEnabled = true,
                recordSequence = { sequence -> contextModel.recordCommit(sequence) },
                recordWordUsage = { w -> vocabulary.recordWordUsage(w) },
                fieldAllowsPersonalizedLearning = fieldAllows
            )
        }

        /** H1 boundary: what flushLearnedData/setPasswordMode(true) now do. */
        fun endInputSession() {
            recentWords.clear()
        }
    }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------- H1

    @Test
    fun `H1 - field boundary clears the window - no cross-app bigram is learned or persisted`() {
        val twin = PredictorTwin()

        // App A: user types a private tail …
        twin.commit("meet")
        twin.commit("me")
        twin.commit("tonight")

        // … field ends (onFinishInputView → flushLearnedData → clearContext) …
        twin.endInputSession()

        // … app B: first committed word of the new field.
        twin.commit("hello")
        twin.commit("world")

        // The boundary pair "tonight→hello" must not exist in RAM …
        assertEquals(0f, bigramStore.getProbability("en", "tonight", "hello"), 0f)
        assertTrue(bigramStore.getAllEntries("en").none { it.word1 == "tonight" })
        // … nor any trigram straddling the boundary.
        assertEquals(0f, trigramStore.getProbability("en", "me", "tonight", "hello"), 0f)
        assertEquals(0f, trigramStore.getProbability("en", "tonight", "hello", "world"), 0f)

        // Legitimate SAME-field pairs on both sides still learned.
        assertTrue(bigramStore.getProbability("en", "meet", "me") > 0f)
        assertTrue(bigramStore.getProbability("en", "hello", "world") > 0f)

        // And nothing cross-boundary can be persisted either: a fresh store
        // revived from the flushed storage has no trace of the boundary pair.
        bigramStore.flush()
        trigramStore.flush()
        val revived = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
        assertEquals(0f, revived.getProbability("en", "tonight", "hello"), 0f)
        assertTrue(revived.getProbability("en", "meet", "me") > 0f)
    }

    @Test
    fun `H1 - without the boundary clear the leak WOULD happen - pins why the wiring matters`() {
        val twin = PredictorTwin()
        twin.commit("secret")
        // NO endInputSession() — the pre-fix behavior.
        twin.commit("hello")
        assertTrue(
            "control: absent the clear, the cross-field pair IS learned (this is the leak H1 fixed)",
            bigramStore.getProbability("en", "secret", "hello") > 0f
        )
    }

    // ------------------------------------------------------------------- M3

    @Test
    fun `M3 - one typing of a sentence records every pair exactly once`() {
        val twin = PredictorTwin()
        listOf("the", "cat", "sat", "on", "mats").forEach { twin.commit(it) }

        // Pre-fix, the full-window replay yielded freq 2-3 for earlier pairs.
        val entries = bigramStore.getAllEntries("en")
        assertEquals(4, entries.size)
        entries.forEach { assertEquals("(${it.word1},${it.word2})", 1, it.frequency) }

        // Below the ≥2 confidence floor ⇒ not a prediction candidate…
        assertTrue(bigramStore.getPredictions("en", "cat").isEmpty())
        // …and next-word generation surfaces nothing.
        assertTrue(contextModel.getNextWordCandidates(listOf("the", "cat")).isEmpty())
        // …and the context boost is neutral (L2: confident probability floor).
        assertEquals(1.0f, contextModel.getContextBoost("sat", listOf("the", "cat")), 1e-6f)
    }

    @Test
    fun `M3 - typing the same pair twice crosses the floor honestly`() {
        val twin = PredictorTwin()
        listOf("want", "to").forEach { twin.commit(it) }
        twin.endInputSession()
        listOf("want", "to").forEach { twin.commit(it) }

        assertEquals(2, bigramStore.getAllBigrams("en", "want").single { it.word2 == "to" }.frequency)
        assertEquals(listOf("to"), bigramStore.getPredictions("en", "want").map { it.word2 })
        assertTrue(contextModel.getContextBoost("to", listOf("i", "want")) > 1.0f)
    }

    // ------------------------------------------------------------------- M5

    @Test
    fun `M5 - incognito field suppresses ALL learning despite every pref being on`() {
        val twin = PredictorTwin(fieldAllows = false)
        listOf("private", "browsing", "words", "here").forEach { twin.commit(it) }

        assertEquals(0, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
        assertEquals(0, vocabulary.size())
        assertFalse(bigramStore.isDirty() || trigramStore.isDirty() || vocabulary.isDirty())

        bigramStore.flush(); trigramStore.flush(); vocabulary.flush()
        assertEquals(0, bigramStorage.putCount.get())
        assertEquals(0, trigramStorage.putCount.get())
        assertEquals(0, vocabStorage.putCount.get())
    }

    @Test
    fun `M5 - incognito field suppresses next-word surfacing too`() {
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = true, onDeviceLearningEnabled = true,
                wordPredictionEnabled = true, isPasswordMode = false,
                specialPromptActive = false, inTermuxApp = false, hasContext = true,
                fieldAllowsPersonalizedLearning = false
            )
        )
    }

    @Test
    fun `M5 - the flag constant matches the platform EditorInfo value`() {
        // android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // is 0x1000000 (API 26+). LearningGate mirrors it to stay pure JVM.
        assertEquals(0x1000000, LearningGate.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(LearningGate.fieldAllowsPersonalizedLearning(0x1000000))
        assertFalse(LearningGate.fieldAllowsPersonalizedLearning(0x1000000 or 0x2)) // with action bits
        assertTrue(LearningGate.fieldAllowsPersonalizedLearning(0))
        assertTrue(LearningGate.fieldAllowsPersonalizedLearning(0x2))
    }
}
