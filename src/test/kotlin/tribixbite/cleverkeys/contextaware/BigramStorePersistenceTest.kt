package tribixbite.cleverkeys.contextaware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM persistence tests for the language-keyed singleton [BigramStore]
 * (audit 2026-08-06 §1: the P0 "learns in RAM, forgets on restart" fix).
 *
 * Uses [InMemoryLearnedStorage]; constructing a NEW store over the same storage
 * simulates process death + restart.
 */
class BigramStorePersistenceTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    // Long debounce by default so tests control flushing explicitly.
    private fun newStore(
        storage: InMemoryLearnedStorage,
        debounceMs: Long = 60_000,
        maxDelayMs: Long = 120_000
    ) = BigramStore(storage, debounceMs, maxDelayMs, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    // ---------------------------------------------------------------- round-trip

    @Test
    fun `record then flush survives process restart`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)

        repeat(3) { store.recordBigram("en", "i", "want") }
        repeat(2) { store.recordBigram("en", "want", "to") }
        store.recordBigram("en", "want", "food")
        store.flush()

        // "Process restart": fresh store over the same storage
        val revived = newStore(storage)
        assertEquals(3, revived.getTotalBigramCount("en"))
        assertEquals(store.getProbability("en", "i", "want"), revived.getProbability("en", "i", "want"), 1e-6f)
        assertEquals(store.getProbability("en", "want", "to"), revived.getProbability("en", "want", "to"), 1e-6f)

        val predictions = revived.getPredictions("en", "want", minProbability = 0f)
        assertEquals("to", predictions.first().word2) // freq 2 beats freq 1
        assertEquals(2, predictions.first().frequency)
    }

    @Test
    fun `unflushed records are LOST on process death - the P0 bug scenario`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage) // debounce far in the future, never fires

        repeat(5) { store.recordBigram("en", "hello", "world") }
        assertTrue(store.isDirty())

        // No flush → process dies → restart sees nothing. This is exactly why the
        // lifecycle flush sites exist.
        val revived = newStore(storage)
        assertEquals(0, revived.getTotalBigramCount("en"))
    }

    @Test
    fun `debounced write-back persists without explicit flush`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage, debounceMs = 60, maxDelayMs = 2000)

        repeat(4) { store.recordBigram("en", "good", "morning") }
        waitUntil { !store.isDirty() && storage.getString("bigrams_json_en") != null }

        val revived = newStore(storage)
        assertEquals(4, revived.getAllBigrams("en", "good").first().frequency)
    }

    // ------------------------------------------------------------ debounce coalescing

    @Test
    fun `many records coalesce into a bounded number of storage writes`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage, debounceMs = 80, maxDelayMs = 5000)

        // 50 rapid records — a per-keystroke saver would issue ~50 writes
        repeat(50) { i ->
            store.recordBigram("en", "w$i", "next")
        }
        // Wait for the completed write (isDirty clears before the write lands)
        waitUntil { storage.getString("bigrams_json_en")?.contains("w49") == true }

        assertTrue(
            "expected coalesced writes (got ${storage.putCount.get()})",
            storage.putCount.get() <= 2
        )
    }

    // ---------------------------------------------------------------- language keying

    @Test
    fun `languages are isolated - en records invisible to fr`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)

        repeat(2) { store.recordBigram("en", "the", "cat") }
        repeat(2) { store.recordBigram("fr", "le", "chat") }
        store.flush()

        assertEquals(0f, store.getProbability("fr", "the", "cat"))
        assertEquals(0f, store.getProbability("en", "le", "chat"))
        assertTrue(store.getPredictions("fr", "the", minProbability = 0f).isEmpty())
        assertEquals(1, store.getTotalBigramCount("en"))
        assertEquals(1, store.getTotalBigramCount("fr"))

        // Isolation survives restart via per-language keys
        val revived = newStore(storage)
        assertEquals("cat", revived.getPredictions("en", "the", minProbability = 0f).first().word2)
        assertEquals("chat", revived.getPredictions("fr", "le", minProbability = 0f).first().word2)
        assertTrue(storage.keys().contains("bigrams_json_en"))
        assertTrue(storage.keys().contains("bigrams_json_fr"))
        assertEquals(setOf("en", "fr"), revived.getKnownLanguages())
    }

    @Test
    fun `blank language normalizes to en`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        store.recordBigram("", "a", "b")
        store.recordBigram("EN ", "a", "b")
        assertEquals(2, store.getAllBigrams("en", "a").first().frequency)
    }

    // ---------------------------------------------------------------- legacy migration

    @Test
    fun `legacy un-keyed blob migrates into first-loaded language and is deleted`() {
        val storage = InMemoryLearnedStorage()
        storage.seed(
            "bigrams_json",
            """[{"word1":"i","word2":"am","frequency":7,"probability":1.0}]"""
        )

        val store = newStore(storage)
        // First language to load (the primary in production) receives the legacy data
        assertEquals(7, store.getAllBigrams("en", "i").first().frequency)
        assertNull("legacy blob must be deleted after migration", storage.getString("bigrams_json"))

        // Migration itself marks dirty so the data survives even with no new records
        store.flush()
        val revived = newStore(storage)
        assertEquals(7, revived.getAllBigrams("en", "i").first().frequency)

        // A second language does NOT re-import the (deleted) legacy data
        assertEquals(0, revived.getTotalBigramCount("fr"))
    }

    // ---------------------------------------------------------------- corruption fallback

    @Test
    fun `corrupted persisted JSON falls back to empty store`() {
        val storage = InMemoryLearnedStorage()
        storage.seed("bigrams_json_en", "{not valid json][")

        val store = newStore(storage)
        assertEquals(0, store.getTotalBigramCount("en"))
        // Still usable for new learning afterwards
        store.recordBigram("en", "a", "b")
        assertEquals(1, store.getTotalBigramCount("en"))
    }

    // ---------------------------------------------------------------- clear semantics

    @Test
    fun `clear removes one language RAM and storage, others untouched`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        store.recordBigram("en", "a", "b")
        store.recordBigram("fr", "c", "d")
        store.flush()

        store.clear("en")
        assertEquals(0, store.getTotalBigramCount("en"))
        assertNull(storage.getString("bigrams_json_en"))
        assertEquals(1, store.getTotalBigramCount("fr"))

        val revived = newStore(storage)
        assertEquals(0, revived.getTotalBigramCount("en"))
        assertEquals(1, revived.getTotalBigramCount("fr"))
    }

    @Test
    fun `clearAll wipes every language and persisted key`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        store.recordBigram("en", "a", "b")
        store.recordBigram("fr", "c", "d")
        store.flush()

        store.clearAll()
        assertEquals(0, store.getTotalBigramCount("en"))
        assertEquals(0, store.getTotalBigramCount("fr"))
        assertTrue(storage.keys().none { it.startsWith("bigrams_json") })
    }

    // ---------------------------------------------------------------- removeBigram (learned-data manager)

    @Test
    fun `removeBigram deletes entry, rescales siblings, and persists`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        repeat(3) { store.recordBigram("en", "want", "to") }
        repeat(1) { store.recordBigram("en", "want", "food") }

        assertTrue(store.removeBigram("en", "want", "food"))
        assertFalse(store.removeBigram("en", "want", "food")) // already gone

        val remaining = store.getAllBigrams("en", "want")
        assertEquals(1, remaining.size)
        assertEquals("to", remaining.first().word2)
        // 3 of 3 remaining occurrences → probability rescales to 1.0
        assertEquals(1.0f, remaining.first().probability, 1e-6f)

        store.flush()
        val revived = newStore(storage)
        assertEquals(1, revived.getAllBigrams("en", "want").size)
    }

    // ---------------------------------------------------------------- import/export

    @Test
    fun `export import round-trip via direct merge`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        repeat(5) { store.recordBigram("en", "i", "am") }
        repeat(2) { store.recordBigram("en", "i", "was") }
        val exported = store.exportToJson("en")

        val storage2 = InMemoryLearnedStorage()
        val imported = newStore(storage2)
        imported.importFromJson("en", exported)

        assertEquals(5, imported.getAllBigrams("en", "i").first { it.word2 == "am" }.frequency)
        assertEquals(2, imported.getAllBigrams("en", "i").first { it.word2 == "was" }.frequency)
        // Import recomputes true conditional probabilities from merged totals
        // (recordBigram leaves sibling probabilities stale, so compare against the
        // exact conditional, not the live store's value): P(am|i) = 5/7.
        assertEquals(5f / 7f, imported.getProbability("en", "i", "am"), 1e-6f)

        // Import persists immediately (user-initiated action)
        val revived = newStore(storage2)
        assertEquals(5, revived.getAllBigrams("en", "i").first { it.word2 == "am" }.frequency)
    }

    @Test
    fun `import merges frequencies with existing data`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        repeat(2) { store.recordBigram("en", "i", "am") }

        store.importFromJson(
            "en",
            """[{"word1":"i","word2":"am","frequency":3,"probability":1.0}]"""
        )
        assertEquals(5, store.getAllBigrams("en", "i").first().frequency)
        assertEquals(1.0f, store.getProbability("en", "i", "am"), 1e-6f)
    }

    @Test
    fun `invalid import JSON is ignored`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        store.recordBigram("en", "a", "b")
        store.importFromJson("en", "not json at all")
        assertEquals(1, store.getTotalBigramCount("en"))
    }

    // ---------------------------------------------------------------- stats

    @Test
    fun `statistics are per-language`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        store.recordBigram("en", "a", "b")
        store.recordBigram("en", "a", "c")
        store.recordBigram("fr", "x", "y")

        val en = store.getStatistics("en")
        assertEquals(2, en.totalBigrams)
        assertEquals(1, en.uniqueContextWords)
        assertEquals("a" to 2, en.topContextWords.first())

        val fr = store.getStatistics("fr")
        assertEquals(1, fr.totalBigrams)
    }
}
