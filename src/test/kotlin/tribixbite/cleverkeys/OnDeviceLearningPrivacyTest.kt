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
 * THE mandatory Task A privacy test: with the MASTER `on_device_learning_enabled`
 * gate OFF, simulating many committed words through the production learn funnel
 * ([LearningGate.learnCommittedWord] — the exact code path
 * `WordPredictor.addWordToContext` runs) must leave the REAL bigram store,
 * trigram store, and user vocabulary EMPTY, and must persist NOTHING to their
 * backing storage. With the gate ON, learning works end-to-end and persists.
 *
 * The stores are the production classes over [InMemoryLearnedStorage] (the same
 * substrate the persistence round-trip tests use), so this exercises production
 * gate logic + production stores; only the lambda glue differs from the device.
 */
class OnDeviceLearningPrivacyTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    // Generous debounce so nothing auto-flushes mid-test; explicit flush() only.
    private val bigramStorage = InMemoryLearnedStorage()
    private val trigramStorage = InMemoryLearnedStorage()
    private val vocabStorage = InMemoryLearnedStorage()
    private val bigramStore = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
    private val contextModel = ContextModel(bigramStore, trigramStore, "en")
    private val vocabulary = UserVocabulary(vocabStorage, 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /**
     * Simulate the production typing loop: WordPredictor.addWordToContext keeps
     * a rolling recent-words window and funnels each committed word through
     * LearningGate with the current gate states.
     */
    private fun simulateTyping(
        sentences: List<List<String>>,
        master: Boolean,
        contextAware: Boolean = true,
        personalized: Boolean = true
    ) {
        for (sentence in sentences) {
            val recentWords = mutableListOf<String>()
            for (word in sentence) {
                val normalized = word.lowercase().trim()
                recentWords.add(normalized)
                LearningGate.learnCommittedWord(
                    recentWords = recentWords,
                    committedWord = normalized,
                    onDeviceLearningEnabled = master,
                    contextAwareEnabled = contextAware,
                    personalizedLearningEnabled = personalized,
                    recordSequence = { sequence -> contextModel.recordSequence(sequence) },
                    recordWordUsage = { w -> vocabulary.recordWordUsage(w) }
                )
            }
        }
    }

    private val corpus = List(20) { i ->
        listOf("this", "is", "sentence", "number", "$i", "about", "keyboards", "and", "typing")
    }

    private fun flushEverything() {
        bigramStore.flush()
        trigramStore.flush()
        vocabulary.flush()
    }

    // ------------------------------------------------------- MASTER GATE OFF

    @Test
    fun `master off - nothing recorded in RAM and nothing persisted`() {
        simulateTyping(corpus, master = false)

        // In-RAM stores stay EMPTY
        assertEquals(0, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, bigramStore.getContextWordCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
        assertEquals(0, vocabulary.size())
        assertFalse(bigramStore.isDirty())
        assertFalse(trigramStore.isDirty())
        assertFalse(vocabulary.isDirty())

        // Even an explicit flush persists NOTHING — the backing storage never
        // saw a single write.
        flushEverything()
        assertEquals(0, bigramStorage.putCount.get())
        assertEquals(0, trigramStorage.putCount.get())
        assertEquals(0, vocabStorage.putCount.get())
        assertTrue(bigramStorage.keys().isEmpty())
        assertTrue(trigramStorage.keys().isEmpty())
        assertTrue(vocabStorage.keys().isEmpty())
    }

    @Test
    fun `master off - next-word surfacing is dark even with data preloaded`() {
        // Learn with the gate ON first (data exists) …
        simulateTyping(corpus, master = true)
        assertTrue(bigramStore.getTotalBigramCount("en") > 0)

        // … then the surfacing gate must still block with the master off
        // (WordPredictor.getNextWordCandidates checks canUseLearnedContext).
        assertFalse(LearningGate.canUseLearnedContext(false, true))
        assertFalse(
            NextWordPredictor.shouldShow(
                featureEnabled = true, onDeviceLearningEnabled = false,
                wordPredictionEnabled = true, isPasswordMode = false,
                specialPromptActive = false, inTermuxApp = false, hasContext = true
            )
        )
    }

    // -------------------------------------------------------- MASTER GATE ON

    @Test
    fun `master on - all three stores learn and persist`() {
        simulateTyping(corpus, master = true)

        assertTrue("bigrams learned", bigramStore.getTotalBigramCount("en") > 0)
        assertTrue("trigrams learned", trigramStore.getTotalTrigramCount("en") > 0)
        assertTrue("vocabulary learned", vocabulary.size() > 0)

        flushEverything()
        assertTrue(bigramStorage.keys().contains(BigramStore.storageKey("en")))
        assertTrue(trigramStorage.keys().contains(TrigramStore.storageKey("en")))
        assertTrue(vocabStorage.keys().isNotEmpty())

        // Restart survival: fresh stores over the same storage see the data.
        val revivedBigrams = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
        val revivedTrigrams = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
        val revivedVocab = UserVocabulary(vocabStorage, 60_000, 120_000, scheduler)
        assertEquals(bigramStore.getTotalBigramCount("en"), revivedBigrams.getTotalBigramCount("en"))
        assertEquals(trigramStore.getTotalTrigramCount("en"), revivedTrigrams.getTotalTrigramCount("en"))
        assertEquals(vocabulary.size(), revivedVocab.size())
    }

    // -------------------------------------------------- PER-FEATURE GATES

    @Test
    fun `context-aware off - n-gram stores stay empty while vocabulary learns`() {
        simulateTyping(corpus, master = true, contextAware = false)

        assertEquals(0, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
        assertTrue(vocabulary.size() > 0)

        flushEverything()
        assertTrue(bigramStorage.keys().isEmpty())
        assertTrue(trigramStorage.keys().isEmpty())
    }

    @Test
    fun `personalization off - vocabulary stays empty while n-grams learn`() {
        simulateTyping(corpus, master = true, personalized = false)

        assertTrue(bigramStore.getTotalBigramCount("en") > 0)
        assertTrue(trigramStore.getTotalTrigramCount("en") > 0)
        assertEquals(0, vocabulary.size())

        flushEverything()
        assertTrue(vocabStorage.keys().isEmpty())
    }

    @Test
    fun `forget APIs erase persisted learned data - the master-off cleanup path`() {
        simulateTyping(corpus, master = true)
        flushEverything()
        assertTrue(bigramStorage.keys().isNotEmpty())
        assertTrue(trigramStorage.keys().isNotEmpty())

        // The PrivacySection "Delete learned data" confirmation runs exactly these.
        bigramStore.clearAll()
        trigramStore.clearAll()
        vocabulary.clearAll()

        assertEquals(0, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
        assertEquals(0, vocabulary.size())
        assertTrue(bigramStorage.keys().none { it.startsWith("bigrams_json_") })
        assertTrue(trigramStorage.keys().none { it.startsWith("trigrams_json_") })
    }
}
