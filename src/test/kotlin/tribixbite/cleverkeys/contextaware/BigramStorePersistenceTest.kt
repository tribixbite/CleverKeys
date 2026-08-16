package tribixbite.cleverkeys.contextaware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.persist.LearnedDataStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

    // ------------------------------------------------- concurrent first touch

    /**
     * The lazy per-language table must be built exactly ONCE even when several
     * threads touch a brand-new language simultaneously.
     *
     * Guards the API-21 replacement for `ConcurrentHashMap#computeIfAbsent`
     * (API 24, `NoSuchMethodError` on Android 5.0–6.0): Kotlin's `getOrPut` is
     * the tempting one-liner and is a get-then-`put`, so a second builder
     * REPLACES the table a first thread is already recording into and those
     * records vanish from the store.
     */
    @Test
    fun `concurrent first touch of a new language loses no records`() {
        val storage = InMemoryLearnedStorage()
        val store = newStore(storage)
        val threads = 4
        val perThread = 15 // < MAX_BIGRAMS_PER_WORD (20), so nothing is capped away
        val rounds = 10    // 10 never-seen languages ⇒ 10 independent first-touch races
        val barrier = CyclicBarrier(threads)

        val workers = (0 until threads).map { t ->
            thread {
                for (r in 0 until rounds) {
                    barrier.await() // all threads enter language "l$r" together
                    repeat(perThread) { i -> store.recordBigram("l$r", "w$t", "x$i") }
                }
            }
        }
        workers.forEach { it.join(30_000) }

        for (r in 0 until rounds) {
            assertEquals("language l$r", threads * perThread, store.getTotalBigramCount("l$r"))
            assertEquals("language l$r", threads, store.getContextWordCount("l$r"))
        }
    }

    /**
     * Same race, on the side-effecting path: the legacy un-keyed blob is loaded
     * and then DELETED during construction, so a duplicated construction could
     * lose it entirely (one thread deletes the key, the other's empty table wins).
     */
    /**
     * The construction of the "en" table has SIDE EFFECTS — it loads the legacy
     * un-keyed blob and then DELETES it — so running it twice can lose the
     * migration outright: the second builder reads a legacy key that is already
     * gone and its empty table can win the publish race.
     *
     * [ConstructionRaceGate] forces that interleaving deterministically instead
     * of hoping a thread scheduler produces it: with a correct once-only
     * construction the second thread never reaches storage at all, which is what
     * `keyedReads == 1` asserts.
     */
    @Test
    fun `a forced construction race still migrates the legacy blob exactly once`() {
        val backing = InMemoryLearnedStorage()
        backing.seed(
            BigramStore.LEGACY_KEY_BIGRAMS,
            """[{"word1":"i","word2":"am","frequency":4,"probability":1.0}]"""
        )
        val gate = ConstructionRaceGate(backing, "bigrams_json_en")
        val store = BigramStore(gate, 60_000, 120_000, scheduler)
        val start = CountDownLatch(1)

        val workers = (0 until 2).map {
            thread {
                start.await()
                // getContextWordCount reaches forLanguage WITHOUT holding the data
                // lock — the same unsynchronized entry recordBigram/getPredictions
                // use, and therefore the path where the construction can race.
                store.getContextWordCount("en")
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }

        assertEquals("the language table must be built exactly once", 1, gate.keyedReads.get())
        assertEquals(1, store.getTotalBigramCount("en"))
        assertEquals(4, store.getAllBigrams("en", "i").first().frequency)
        assertNull(backing.getString(BigramStore.LEGACY_KEY_BIGRAMS))
    }

    /**
     * Holds every thread that reaches [gatedKey] until a second one arrives (or
     * [gateMs] elapses), turning the lazy-construction race from "maybe, if the
     * scheduler cooperates" into a deterministic interleaving. A correct
     * implementation makes the second arrival impossible, so the gate times out.
     */
    private class ConstructionRaceGate(
        private val delegate: InMemoryLearnedStorage,
        private val gatedKey: String,
        private val gateMs: Long = 400
    ) : LearnedDataStorage {
        /** How many threads reached storage for the gated language's own key. */
        val keyedReads = AtomicInteger(0)
        private val bothInside = CountDownLatch(2)

        override fun getString(key: String): String? {
            if (key == gatedKey) {
                keyedReads.incrementAndGet()
                bothInside.countDown()
                bothInside.await(gateMs, TimeUnit.MILLISECONDS)
            }
            return delegate.getString(key)
        }

        override fun putString(key: String, value: String) = delegate.putString(key, value)
        override fun remove(key: String) = delegate.remove(key)
        override fun keys(): Set<String> = delegate.keys()
    }
}
