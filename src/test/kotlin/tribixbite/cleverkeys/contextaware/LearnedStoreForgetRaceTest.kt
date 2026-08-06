package tribixbite.cleverkeys.contextaware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.persist.LearnedDataStorage
import tribixbite.cleverkeys.personalization.UserVocabulary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 2026-08-06 review fixes at the store layer:
 *
 * - **M1 — forget resurrection race**: serialize + write now happen under the
 *   SAME lock as `clear`/`clearAll`'s storage removal, so an in-flight flush can
 *   never re-persist just-forgotten data (the privacy-critical path: it fired
 *   exactly on a master-off forget). An empty table flushes as key REMOVAL.
 * - **M4 — sibling renormalization**: `recordBigram` recomputes EVERY sibling's
 *   conditional probability against the updated denominator (previously only
 *   the touched entry, freezing stale ranking inversions forever).
 * - **L2 — confident probability**: the boost path's probability read returns 0
 *   below the min-frequency floor; the raw accessor stays raw.
 * - **L4 — prompt flush on user delete**: `removeBigram`/`removeWord` request an
 *   immediate flush instead of leaving the deletion in the debounce window.
 * - **L9 — failed flush retry**: a language drained from the dirty set before a
 *   failing write is re-added, so the persister's dirty-restore actually
 *   retries it.
 * - **L10 — legacy blob migrates to "en" explicitly**, not to whichever
 *   language happens to load first.
 */
class LearnedStoreForgetRaceTest {

    private val scheduler = ScheduledThreadPoolExecutor(2)

    private fun newBigramStore(storage: LearnedDataStorage = InMemoryLearnedStorage()) =
        BigramStore(storage, 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------- M1

    @Test
    fun `M1 - clear then flush cannot resurrect forgotten bigrams`() {
        val storage = InMemoryLearnedStorage()
        val store = newBigramStore(storage)
        repeat(3) { store.recordBigram("en", "secret", "word") }
        assertTrue(store.isDirty())

        // Forget while a flush is still pending (dirty). The pre-fix code could
        // serialize before the clear and putString after it — resurrecting the
        // data. Post-fix: the post-clear flush maps the empty table to REMOVAL.
        store.clear("en")
        store.flush()

        assertEquals(0, store.getTotalBigramCount("en"))
        assertTrue("no persisted bigram key may survive a forget", storage.keys().isEmpty())

        val revived = newBigramStore(storage)
        assertEquals(0f, revived.getProbability("en", "secret", "word"), 0f)
    }

    @Test
    fun `M1 - flush then clear leaves storage empty too - both orderings safe`() {
        val storage = InMemoryLearnedStorage()
        val store = newBigramStore(storage)
        repeat(3) { store.recordBigram("en", "secret", "word") }
        store.flush()
        assertTrue(storage.keys().contains(BigramStore.storageKey("en")))

        store.clear("en")
        assertTrue(storage.keys().isEmpty())
    }

    @Test
    fun `M1 - trigram store - clear interleaved with flush persists nothing`() {
        val storage = InMemoryLearnedStorage()
        val store = TrigramStore(storage, 60_000, 120_000, scheduler)
        repeat(3) { store.recordTrigram("en", "a", "b", "c") }
        assertTrue(store.isDirty())

        store.clear("en")
        store.flush()

        assertEquals(0, store.getTotalTrigramCount("en"))
        assertTrue(storage.keys().isEmpty())
    }

    @Test
    fun `M1 - vocabulary - clearAll interleaved with flush persists nothing`() {
        val storage = InMemoryLearnedStorage()
        val vocab = UserVocabulary(storage, 60_000, 120_000, scheduler)
        repeat(3) { vocab.recordWordUsage("secretword") }
        assertTrue(vocab.isDirty())

        vocab.clearAll()
        vocab.flush()

        assertEquals(0, vocab.size())
        assertTrue(storage.keys().isEmpty())
    }

    @Test
    fun `M1 - concurrent record+flush storm then forget always ends empty`() {
        val storage = InMemoryLearnedStorage()
        val store = newBigramStore(storage)
        val start = CountDownLatch(1)

        val writer = thread {
            start.await()
            repeat(200) { i ->
                store.recordBigram("en", "w$i", "next")
                if (i % 20 == 0) store.flush()
            }
        }
        val flusher = thread {
            start.await()
            repeat(50) { store.flush() }
        }
        start.countDown()
        writer.join(10_000)
        flusher.join(10_000)

        // The forget happens LAST — whatever raced before, it must win.
        store.clearAll()
        store.flush()
        assertEquals(0, store.getTotalBigramCount("en"))
        assertTrue(storage.keys().none { it.startsWith("bigrams_json_") })
    }

    // ------------------------------------------------------------------- M4

    @Test
    fun `M4 - recordBigram renormalizes siblings against the new denominator`() {
        val store = newBigramStore()
        repeat(3) { store.recordBigram("en", "the", "cat") }
        repeat(2) { store.recordBigram("en", "the", "dog") }

        val entries = store.getAllBigrams("en", "the")
        val pCat = entries.single { it.word2 == "cat" }.probability
        val pDog = entries.single { it.word2 == "dog" }.probability

        // Conditional probabilities over the CURRENT total (5): 3/5 and 2/5.
        // Pre-fix, "cat" kept its stale 3/3=1.0 from before "dog" existed.
        assertEquals(0.6f, pCat, 1e-6f)
        assertEquals(0.4f, pDog, 1e-6f)
        assertEquals(1.0f, pCat + pDog, 1e-6f)
    }

    @Test
    fun `M4 - a later sibling can overtake an earlier one - no frozen ranking`() {
        val store = newBigramStore()
        repeat(2) { store.recordBigram("en", "my", "cat") }
        repeat(6) { store.recordBigram("en", "my", "dog") }

        val ranked = store.getPredictions("en", "my", minProbability = 0f)
        assertEquals(listOf("dog", "cat"), ranked.map { it.word2 })
        assertTrue(ranked[0].probability > ranked[1].probability)
    }

    // ------------------------------------------------------------------- L2

    @Test
    fun `L2 - confident probability floors once-seen pairs to zero`() {
        val store = newBigramStore()
        store.recordBigram("en", "once", "seen")

        // Raw accessor stays raw (export/statistics)…
        assertEquals(1.0f, store.getProbability("en", "once", "seen"), 1e-6f)
        // …but the boost path's read is floored.
        assertEquals(0f, store.getConfidentProbability("en", "once", "seen"), 0f)

        store.recordBigram("en", "once", "seen")
        assertEquals(1.0f, store.getConfidentProbability("en", "once", "seen"), 1e-6f)
    }

    @Test
    fun `L2 - trigram confident probability floors once-seen trigrams`() {
        val store = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        store.recordTrigram("en", "i", "want", "to")

        assertEquals(1.0f, store.getProbability("en", "i", "want", "to"), 1e-6f)
        assertEquals(0f, store.getConfidentProbability("en", "i", "want", "to"), 0f)

        store.recordTrigram("en", "i", "want", "to")
        assertEquals(1.0f, store.getConfidentProbability("en", "i", "want", "to"), 1e-6f)
    }

    @Test
    fun `L2 - once-seen trigram no longer grants a context boost`() {
        val model = ContextModel(newBigramStore(), TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler), "en")
        model.recordSequence(listOf("i", "want", "to"))
        // Single observation everywhere ⇒ neutral boost (was ~4x pre-fix).
        assertEquals(1.0f, model.getContextBoost("to", listOf("i", "want")), 1e-6f)
    }

    // ------------------------------------------------------------------- L4

    @Test
    fun `L4 - user-initiated removeBigram flushes promptly`() {
        val storage = InMemoryLearnedStorage()
        val store = newBigramStore(storage)
        repeat(2) { store.recordBigram("en", "oops", "typo") }
        repeat(2) { store.recordBigram("en", "keep", "this") }
        store.flush()

        assertTrue(store.removeBigram("en", "oops", "typo"))
        // NO explicit lifecycle flush here — the remove itself must request one.
        // The flush is async on the scheduler, so poll the backing STORAGE until
        // the removal has landed (the dirty flag clears before the write does).
        waitUntil { storage.getString(BigramStore.storageKey("en"))?.contains("oops") == false }

        val revived = newBigramStore(storage)
        assertEquals(0f, revived.getProbability("en", "oops", "typo"), 0f)
        assertTrue(revived.getProbability("en", "keep", "this") > 0f)
    }

    @Test
    fun `L4 - user-initiated removeWord flushes promptly`() {
        val storage = InMemoryLearnedStorage()
        val vocab = UserVocabulary(storage, 60_000, 120_000, scheduler)
        repeat(2) { vocab.recordWordUsage("embarrassing") }
        repeat(2) { vocab.recordWordUsage("ordinary") }
        vocab.flush()

        assertTrue(vocab.removeWord("embarrassing"))
        // As above: poll the backing storage until the async removal write lands.
        waitUntil { storage.getString("vocabulary_data")?.contains("embarrassing") == false }

        val revived = UserVocabulary(storage, 60_000, 120_000, scheduler)
        assertFalse(revived.hasWord("embarrassing"))
        assertTrue(revived.hasWord("ordinary"))
    }

    // ------------------------------------------------------------------- L9

    @Test
    fun `L9 - failed write re-adds the language so retry actually rewrites it`() {
        val failures = intArrayOf(1) // fail the first write, succeed after
        val storage = object : LearnedDataStorage {
            val delegate = InMemoryLearnedStorage()
            override fun getString(key: String) = delegate.getString(key)
            override fun putString(key: String, value: String) {
                if (failures[0] > 0) {
                    failures[0]--
                    throw RuntimeException("simulated storage failure")
                }
                delegate.putString(key, value)
            }
            override fun remove(key: String) = delegate.remove(key)
            override fun keys() = delegate.keys()
        }
        val store = BigramStore(storage, 60_000, 120_000, scheduler)
        repeat(2) { store.recordBigram("en", "retry", "me") }

        store.flush() // first attempt fails; dirty must be restored WITH the language
        assertTrue("dirty restored after failed flush", store.isDirty())

        store.flush() // retry succeeds — pre-fix this wrote nothing (drained set)
        assertFalse(store.isDirty())
        val revived = BigramStore(storage.delegate, 60_000, 120_000, scheduler)
        assertTrue(revived.getProbability("en", "retry", "me") > 0f)
    }

    // ------------------------------------------------------------------ L10

    @Test
    fun `L10 - legacy un-keyed blob migrates to en - not the first loaded language`() {
        val storage = InMemoryLearnedStorage()
        val seeded = newBigramStore(InMemoryLearnedStorage()).let { tmp ->
            repeat(3) { tmp.recordBigram("en", "hello", "there") }
            tmp.exportToJson("en")
        }
        storage.seed("bigrams_json", seeded)

        val store = newBigramStore(storage)
        // A non-en-primary user's first predictor loads e.g. German…
        assertEquals(0, store.getTotalBigramCount("de"))
        assertEquals(0f, store.getProbability("de", "hello", "there"), 0f)
        // …the legacy (implicitly-English) data belongs to "en".
        assertTrue(store.getProbability("en", "hello", "there") > 0f)
        // Legacy key consumed after the en load.
        assertFalse(storage.keys().contains("bigrams_json"))
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }
}
