package tribixbite.cleverkeys.contextaware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for the language-aware [ContextModel] over the singleton
 * [BigramStore] (audit 2026-08-06 §1.3-C) plus the next-word generation API
 * ([ContextModel.getTopPredictions] / [ContextModel.getNextWordCandidates], §4).
 */
class ContextModelLanguageTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val storage = InMemoryLearnedStorage()
    private val store = BigramStore(storage, 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `recordSequence records all adjacent bigrams into the model language`() {
        val model = ContextModel(store, "en")
        model.recordSequence(listOf("i", "want", "to", "go"))

        assertEquals(1, store.getAllBigrams("en", "i").size)
        assertEquals("want", store.getAllBigrams("en", "i").first().word2)
        assertEquals("to", store.getAllBigrams("en", "want").first().word2)
        assertEquals("go", store.getAllBigrams("en", "to").first().word2)
        // Nothing leaked into another language
        assertTrue(store.getAllBigrams("fr", "i").isEmpty())
    }

    @Test
    fun `two models on different languages share the store without cross-talk`() {
        val en = ContextModel(store, "en")
        val fr = ContextModel(store, "fr")

        repeat(3) { en.recordSequence(listOf("the", "cat")) }
        repeat(3) { fr.recordSequence(listOf("the", "chat")) }

        // Each language only sees its own learned pairs
        assertEquals(1.0f, en.getProbability("the", "cat"), 1e-6f)
        assertEquals(0.0f, en.getProbability("the", "chat"), 1e-6f)
        assertEquals(1.0f, fr.getProbability("the", "chat"), 1e-6f)
        assertEquals(0.0f, fr.getProbability("the", "cat"), 1e-6f)
    }

    @Test
    fun `language switch redirects learning and lookups`() {
        val model = ContextModel(store, "en")
        repeat(2) { model.recordSequence(listOf("guten", "morgen")) }

        model.language = "de"
        repeat(2) { model.recordSequence(listOf("guten", "abend")) }

        assertEquals(0.0f, model.getProbability("guten", "morgen"), 1e-6f) // en data invisible from de
        assertEquals(1.0f, model.getProbability("guten", "abend"), 1e-6f)
        assertTrue(model.hasContextFor("guten"))

        model.language = "en"
        assertEquals(1.0f, model.getProbability("guten", "morgen"), 1e-6f)
    }

    @Test
    fun `getContextBoost uses most recent previous word and is neutral without data`() {
        val model = ContextModel(store, "en")
        repeat(4) { model.recordSequence(listOf("want", "to")) }

        val boost = model.getContextBoost("to", listOf("i", "want"))
        assertTrue("expected boost > 1 for learned pair, got $boost", boost > 1.0f)
        assertEquals(1.0f, model.getContextBoost("banana", listOf("i", "want")), 1e-6f)
        assertEquals(1.0f, model.getContextBoost("to", emptyList()), 1e-6f)
    }

    @Test
    fun `getTopPredictions ranks by learned probability`() {
        val model = ContextModel(store, "en")
        repeat(5) { model.recordSequence(listOf("want", "to")) }
        repeat(2) { model.recordSequence(listOf("want", "more")) }

        val predictions = model.getTopPredictions(listOf("i", "want"), 10)
        assertEquals("to", predictions[0].first)
        assertEquals("more", predictions[1].first)
        assertTrue(predictions[0].second > predictions[1].second)
        assertTrue(model.getTopPredictions(emptyList()).isEmpty())
    }

    @Test
    fun `getNextWordCandidates exposes raw frequency for confidence floors`() {
        val model = ContextModel(store, "en")
        repeat(3) { model.recordSequence(listOf("good", "morning")) }

        val candidates = model.getNextWordCandidates(listOf("good"))
        assertEquals(1, candidates.size)
        assertEquals("morning", candidates.first().word)
        assertEquals(3, candidates.first().frequency)
        assertFalse(candidates.first().fromTrigram)
    }

    @Test
    fun `save flushes learned data for restart survival`() {
        val model = ContextModel(store, "en")
        repeat(2) { model.recordSequence(listOf("hello", "world")) }
        model.saveBlocking()

        val revived = BigramStore(storage, 60_000, 120_000, scheduler)
        assertEquals(2, revived.getAllBigrams("en", "hello").first().frequency)
    }

    @Test
    fun `clear only wipes the active language`() {
        val en = ContextModel(store, "en")
        val fr = ContextModel(store, "fr")
        en.recordSequence(listOf("a", "b"))
        fr.recordSequence(listOf("c", "d"))

        en.clear()
        assertFalse(en.hasContextFor("a"))
        assertTrue(fr.hasContextFor("c"))
    }

    @Test
    fun `statistics reflect only the active language`() {
        val en = ContextModel(store, "en")
        val fr = ContextModel(store, "fr")
        en.recordSequence(listOf("a", "b", "c"))
        fr.recordSequence(listOf("x", "y"))

        assertEquals(2, en.getStatistics().bigramStats.totalBigrams)
        assertEquals(1, fr.getStatistics().bigramStats.totalBigrams)
    }

    @Test
    fun `export import round-trip through the model`() {
        val en = ContextModel(store, "en")
        repeat(3) { en.recordSequence(listOf("see", "you")) }
        val json = en.exportToJson()

        val otherStorage = InMemoryLearnedStorage()
        val otherStore = BigramStore(otherStorage, 60_000, 120_000, scheduler)
        val other = ContextModel(otherStore, "en")
        other.importFromJson(json)

        assertEquals(1.0f, other.getProbability("see", "you"), 1e-6f)
        assertEquals(3, otherStore.getAllBigrams("en", "see").first().frequency)
    }
}
