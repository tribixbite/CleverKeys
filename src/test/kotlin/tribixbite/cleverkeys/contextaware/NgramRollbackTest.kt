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
 * Pure-JVM tests for the autocorrect-undo learning rollback (2026-08-06):
 * [BigramStore.unrecordBigram], [TrigramStore.unrecordTrigram], and
 * [ContextModel.rollbackCommit] — the exact inverse of the per-commit learn
 * path ([ContextModel.recordCommit]). When the user rejects an autocorrect,
 * the learned stores must return to their pre-commit state: frequency
 * decremented (not entry-deleted), siblings renormalized, once-seen entries
 * removed entirely, unknown n-grams a safe no-op.
 */
class NgramRollbackTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    // Long debounce so tests control flushing explicitly.
    private fun newBigramStore(storage: InMemoryLearnedStorage = InMemoryLearnedStorage()) =
        BigramStore(storage, 60_000, 120_000, scheduler)

    private fun newTrigramStore(storage: InMemoryLearnedStorage = InMemoryLearnedStorage()) =
        TrigramStore(storage, 60_000, 120_000, scheduler)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ BigramStore

    @Test
    fun `unrecord decrements frequency and renormalizes siblings`() {
        val store = newBigramStore()
        repeat(3) { store.recordBigram("en", "want", "to") }
        store.recordBigram("en", "want", "food")
        // Totals: want=4; to=3 (p=0.75), food=1 (p=0.25)

        assertTrue(store.unrecordBigram("en", "want", "to"))

        // Totals: want=3; to=2 (p≈0.667), food=1 (p≈0.333)
        val entries = store.getAllBigrams("en", "want")
        assertEquals(2, entries.size)
        val to = entries.first { it.word2 == "to" }
        val food = entries.first { it.word2 == "food" }
        assertEquals(2, to.frequency)
        assertEquals(2f / 3f, to.probability, 1e-4f)
        assertEquals(1, food.frequency)
        assertEquals(1f / 3f, food.probability, 1e-4f)
    }

    @Test
    fun `unrecord of a once-seen pair removes the entry entirely`() {
        val store = newBigramStore()
        store.recordBigram("en", "the", "cat")

        assertTrue(store.unrecordBigram("en", "the", "cat"))

        assertEquals(0, store.getTotalBigramCount("en"))
        assertEquals(0f, store.getProbability("en", "the", "cat"), 0f)
    }

    @Test
    fun `record then unrecord round-trips to the exact pre-commit state`() {
        val store = newBigramStore()
        repeat(2) { store.recordBigram("en", "i", "want") }
        store.recordBigram("en", "i", "am")
        val before = store.getAllBigrams("en", "i").map { it.word2 to it.frequency to it.probability }

        // The autocorrect commit... and its undo.
        store.recordBigram("en", "i", "wasnt")
        assertTrue(store.unrecordBigram("en", "i", "wasnt"))

        val after = store.getAllBigrams("en", "i").map { it.word2 to it.frequency to it.probability }
        assertEquals(before.toSet(), after.toSet())
    }

    @Test
    fun `unrecord of an unknown pair is a safe no-op`() {
        val store = newBigramStore()
        store.recordBigram("en", "want", "to")

        // Unknown context word AND unknown continuation both no-op.
        assertFalse(store.unrecordBigram("en", "never", "seen"))
        assertFalse(store.unrecordBigram("en", "want", "unseen"))
        // Empty input no-ops.
        assertFalse(store.unrecordBigram("en", "", "to"))

        assertEquals(1, store.getTotalBigramCount("en"))
    }

    @Test
    fun `unrecord is language-isolated and marks the store dirty for persistence`() {
        val storage = InMemoryLearnedStorage()
        val store = newBigramStore(storage)
        store.recordBigram("en", "want", "to")
        store.recordBigram("fr", "je", "veux")
        store.flush()
        assertFalse(store.isDirty())

        assertTrue(store.unrecordBigram("en", "want", "to"))
        assertTrue(store.isDirty())
        store.flush()

        // "Process restart": en's removal persisted, fr untouched.
        val revived = newBigramStore(storage)
        assertEquals(0, revived.getTotalBigramCount("en"))
        assertEquals(1, revived.getTotalBigramCount("fr"))
    }

    // ----------------------------------------------------------- TrigramStore

    @Test
    fun `trigram unrecord decrements and renormalizes, removing at zero`() {
        val store = newTrigramStore()
        repeat(2) { store.recordTrigram("en", "i", "want", "to") }
        store.recordTrigram("en", "i", "want", "food")
        // prefix "i want"=3; to=2, food=1

        assertTrue(store.unrecordTrigram("en", "i", "want", "to"))
        assertEquals(1f / 2f, store.getProbability("en", "i", "want", "to"), 1e-4f)
        assertEquals(1f / 2f, store.getProbability("en", "i", "want", "food"), 1e-4f)

        assertTrue(store.unrecordTrigram("en", "i", "want", "to"))
        assertEquals(0f, store.getProbability("en", "i", "want", "to"), 0f)
        assertEquals(1, store.getTotalTrigramCount("en"))

        assertFalse(store.unrecordTrigram("en", "i", "want", "to")) // already gone
    }

    // ----------------------------------------------------- ContextModel glue

    @Test
    fun `rollbackCommit is the exact inverse of recordCommit`() {
        val bigrams = newBigramStore()
        val trigrams = newTrigramStore()
        val model = ContextModel(bigrams, trigrams, "en")

        // Prior legitimate history: "i want to" typed twice.
        repeat(2) {
            model.recordCommit(listOf("i", "want"))
            model.recordCommit(listOf("i", "want", "to"))
        }
        val bigramBefore = bigrams.getAllBigrams("en", "want").map { it.word2 to it.frequency }
        val trigramBefore = trigrams.getProbability("en", "i", "want", "to")

        // Autocorrect commits a rejected word, then the undo rolls it back with
        // the SAME window recordCommit consumed.
        val window = listOf("i", "want", "ti")
        model.recordCommit(window)
        model.rollbackCommit(window)

        assertEquals(
            bigramBefore.toSet(),
            bigrams.getAllBigrams("en", "want").map { it.word2 to it.frequency }.toSet()
        )
        assertEquals(trigramBefore, trigrams.getProbability("en", "i", "want", "to"), 1e-4f)
        assertEquals(0f, bigrams.getProbability("en", "want", "ti"), 0f)
        assertEquals(0f, trigrams.getProbability("en", "i", "want", "ti"), 0f)
    }

    @Test
    fun `rollbackCommit no-ops on short windows and gate-suppressed records`() {
        val bigrams = newBigramStore()
        val model = ContextModel(bigrams, "en") // bigram-only test view

        model.rollbackCommit(emptyList())
        model.rollbackCommit(listOf("solo"))
        // Nothing was ever recorded (simulates a gate-suppressed learn) — the
        // rollback of an unknown pair must not corrupt anything.
        model.rollbackCommit(listOf("never", "recorded"))

        assertEquals(0, bigrams.getTotalBigramCount("en"))
    }
}
