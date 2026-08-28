package tribixbite.cleverkeys.contextaware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pure-JVM tests for [TrigramStore] (Task C trigram activation): the same
 * persistence/singleton/language-keying discipline as [BigramStore] —
 * round-trip through storage, process-death survival, language isolation,
 * debounce coalescing, corruption fallback, caps, and clear semantics.
 */
class TrigramStorePersistenceTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val storage = InMemoryLearnedStorage()
    private val store = TrigramStore(storage, 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun record(times: Int, lang: String, w1: String, w2: String, w3: String) {
        repeat(times) { store.recordTrigram(lang, w1, w2, w3) }
    }

    // ------------------------------------------------------------- recording

    @Test
    fun `records trigrams with conditional probability over the prefix`() {
        record(3, "en", "i", "want", "to")
        record(1, "en", "i", "want", "food")

        assertEquals(2, store.getTotalTrigramCount("en"))
        assertEquals(0.75f, store.getProbability("en", "i", "want", "to"), 1e-6f)
        assertEquals(0.25f, store.getProbability("en", "i", "want", "food"), 1e-6f)
    }

    @Test
    fun `normalizes case and skips empty or self-repeating words`() {
        store.recordTrigram("en", "I", "Want", "To")
        assertTrue(store.getProbability("en", "i", "want", "to") > 0f)

        store.recordTrigram("en", "a", "very", "very") // w2 == w3 skipped
        store.recordTrigram("en", "", "x", "y")
        assertEquals(1, store.getTotalTrigramCount("en"))
    }

    @Test
    fun `getPredictions ranks by probability and honors the frequency floor`() {
        record(4, "en", "want", "to", "go")
        record(2, "en", "want", "to", "see")
        record(1, "en", "want", "to", "hapax") // below DEFAULT_MIN_FREQUENCY

        val predictions = store.getPredictions("en", "want", "to")
        assertEquals(listOf("go", "see"), predictions.map { it.word3 })
        assertTrue(predictions[0].probability > predictions[1].probability)
    }

    // ------------------------------------- learned-phrase delete cascade (ARC-004)

    @Test
    fun `removeContinuationsOf removes the deleted bigram's trigram echoes across all prefixes`() {
        // Two different prefixes both ending in "want", both continuing to "to".
        record(3, "en", "i", "want", "to")
        record(2, "en", "we", "want", "to")
        record(2, "en", "i", "want", "food")

        val removed = store.removeContinuationsOf("en", "want", "to")

        assertEquals(2, removed)
        assertEquals(0f, store.getProbability("en", "i", "want", "to"), 0f)
        assertEquals(0f, store.getProbability("en", "we", "want", "to"), 0f)
        // The sibling survives and is renormalized to own the whole prefix mass.
        assertEquals(1.0f, store.getProbability("en", "i", "want", "food"), 1e-6f)
        // The deleted continuation no longer appears in predictions for either prefix.
        assertTrue(store.getPredictions("en", "i", "want", minProbability = 0f)
            .none { it.word3 == "to" })
        assertTrue(store.getPredictions("en", "we", "want", minProbability = 0f).isEmpty())
    }

    @Test
    fun `removeContinuationsOf keeps trigrams where the pair is the PREFIX, not the tail`() {
        // (want, to) -> go predicts what follows the phrase; it does not suggest "to".
        record(3, "en", "want", "to", "go")
        record(2, "en", "i", "want", "to")

        store.removeContinuationsOf("en", "want", "to")

        assertEquals(0f, store.getProbability("en", "i", "want", "to"), 0f)
        assertTrue(store.getProbability("en", "want", "to", "go") > 0f)
    }

    @Test
    fun `removeContinuationsOf is a no-op for unknown pairs and normalizes case`() {
        record(3, "en", "i", "want", "to")

        assertEquals(0, store.removeContinuationsOf("en", "need", "to"))
        assertEquals(1, store.removeContinuationsOf("en", "Want", "To"))
        assertEquals(0f, store.getProbability("en", "i", "want", "to"), 0f)
    }

    @Test
    fun `removeContinuationsOf persists - a fresh store over the same storage stays clean`() {
        record(3, "en", "i", "want", "to")
        store.removeContinuationsOf("en", "want", "to")
        store.flush()

        val revived = TrigramStore(storage, 60_000, 120_000, scheduler)
        assertEquals(0f, revived.getProbability("en", "i", "want", "to"), 0f)
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun `round-trip - flush then a fresh store over the same storage sees the data`() {
        record(3, "en", "good", "morning", "sunshine")
        record(2, "en", "see", "you", "tomorrow")
        store.flush()

        val revived = TrigramStore(storage, 60_000, 120_000, scheduler)
        assertEquals(2, revived.getTotalTrigramCount("en"))
        assertEquals(
            store.getProbability("en", "good", "morning", "sunshine"),
            revived.getProbability("en", "good", "morning", "sunshine"),
            1e-6f
        )
        assertEquals(3, revived.getPredictions("en", "good", "morning").single().frequency)
    }

    @Test
    fun `debounce coalescing - many records produce zero writes until flush`() {
        repeat(100) { i -> store.recordTrigram("en", "w$i", "x$i", "y$i") }
        assertEquals(0, storage.putCount.get())
        assertTrue(store.isDirty())

        store.flush()
        assertEquals(1, storage.putCount.get())
        assertFalse(store.isDirty())

        // Clean flush is a no-op
        store.flush()
        assertEquals(1, storage.putCount.get())
    }

    @Test
    fun `corrupted persisted JSON falls back to an empty table`() {
        storage.seed(TrigramStore.storageKey("en"), "{not json[")
        assertEquals(0, store.getTotalTrigramCount("en"))
        // And the store remains usable
        record(2, "en", "a", "b", "c")
        assertEquals(1, store.getTotalTrigramCount("en"))
    }

    // ------------------------------------------------------ language keying

    @Test
    fun `languages are isolated in RAM and in storage`() {
        record(2, "en", "the", "big", "cat")
        record(2, "fr", "le", "grand", "chat")
        store.flush()

        assertEquals(0f, store.getProbability("en", "le", "grand", "chat"), 1e-6f)
        assertEquals(0f, store.getProbability("fr", "the", "big", "cat"), 1e-6f)
        assertTrue(storage.keys().contains(TrigramStore.storageKey("en")))
        assertTrue(storage.keys().contains(TrigramStore.storageKey("fr")))
        assertEquals(setOf("en", "fr"), store.getKnownLanguages())
    }

    @Test
    fun `blank language normalizes to en`() {
        record(2, "", "a", "b", "c")
        assertTrue(store.getProbability("en", "a", "b", "c") > 0f)
    }

    // ----------------------------------------------------------------- clear

    @Test
    fun `clear wipes one language including its persisted blob`() {
        record(2, "en", "a", "b", "c")
        record(2, "fr", "x", "y", "z")
        store.flush()

        store.clear("en")
        assertEquals(0, store.getTotalTrigramCount("en"))
        assertEquals(1, store.getTotalTrigramCount("fr"))
        assertFalse(storage.keys().contains(TrigramStore.storageKey("en")))
        assertTrue(storage.keys().contains(TrigramStore.storageKey("fr")))
    }

    @Test
    fun `clearAll wipes every language and every persisted blob`() {
        record(2, "en", "a", "b", "c")
        record(2, "de", "x", "y", "z")
        store.flush()

        store.clearAll()
        assertEquals(0, store.getTotalTrigramCount("en"))
        assertEquals(0, store.getTotalTrigramCount("de"))
        assertTrue(storage.keys().none { it.startsWith("trigrams_json_") })
    }

    // ------------------------------------------------------------------ caps

    @Test
    fun `per-prefix cap keeps the most probable continuations`() {
        // A dominant continuation first, then 15 fillers — the cap (10 per
        // prefix, sorted by probability) must hold and the dominant entry must
        // survive. (Like BigramStore, capping is probability-ranked, so a brand
        // new continuation entering a saturated prefix starts at the bottom.)
        repeat(20) { store.recordTrigram("en", "fixed", "prefix", "top") }
        for (i in 1..15) {
            repeat(3) { store.recordTrigram("en", "fixed", "prefix", "word$i") }
        }
        val entries = store.getPredictions("en", "fixed", "prefix", maxResults = 50, minProbability = 0f)
        assertTrue("cap enforced, got ${entries.size}", entries.size <= 10)
        assertEquals("top", entries.first().word3)
        assertEquals(20, entries.first().frequency)
    }

    // ------------------------------------------------- concurrent first touch

    /**
     * The lazy per-language table must be built exactly ONCE even when several
     * threads touch a brand-new language simultaneously.
     *
     * Guards the API-21 replacement for `ConcurrentHashMap#computeIfAbsent`
     * (API 24, `NoSuchMethodError` on Android 5.0–6.0): a plain
     * get-then-`putIfAbsent` race would hand the losing threads a table that is
     * then discarded, silently dropping everything they recorded into it.
     */
    @Test
    fun `concurrent first touch of a new language loses no records`() {
        val threads = 6
        val perThread = 8 // < MAX_TRIGRAMS_PER_PREFIX (10), so nothing is capped away
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { i -> store.recordTrigram("sv", "w$t", "v$t", "x$i") }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals(threads * perThread, store.getTotalTrigramCount("sv"))
    }
}
