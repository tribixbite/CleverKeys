package tribixbite.cleverkeys.personalization

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for [UserVocabulary]'s debounced persistence
 * (audit 2026-08-06 §1.1 / P0: the per-word raw-Thread Gson dump is replaced by
 * dirty-flag + debounced write-back) and the learned-data-manager edit APIs.
 */
class UserVocabularyPersistenceTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    private fun newVocab(
        storage: InMemoryLearnedStorage,
        debounceMs: Long = 60_000,
        maxDelayMs: Long = 120_000
    ) = UserVocabulary(storage, debounceMs, maxDelayMs, scheduler)

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

    @Test
    fun `record then flush survives process restart`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)

        repeat(3) { vocab.recordWordUsage("kotlin", timestamp = 1000L + it) }
        vocab.recordWordUsage("gradle", timestamp = 2000L)
        vocab.flush()

        val revived = newVocab(storage)
        assertEquals(2, revived.size())
        assertEquals(3, revived.getWordUsage("kotlin")?.usageCount)
        assertEquals(1, revived.getWordUsage("gradle")?.usageCount)
        assertTrue(revived.hasWord("kotlin"))
    }

    @Test
    fun `unflushed words are lost on process death`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage) // debounce never fires in test time

        vocab.recordWordUsage("ephemeral")
        assertTrue(vocab.isDirty())

        val revived = newVocab(storage)
        assertEquals(0, revived.size())
    }

    @Test
    fun `per-word save storm coalesces into bounded storage writes`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage, debounceMs = 80, maxDelayMs = 5000)

        // 40 committed words in a burst — the old implementation would have
        // spawned 40 threads each serializing the whole vocabulary.
        repeat(40) { i -> vocab.recordWordUsage("word$i") }
        // Wait for the completed write (isDirty clears before the write lands)
        waitUntil { storage.getString("vocabulary_data")?.contains("word39") == true }

        assertTrue(
            "expected coalesced writes (got ${storage.putCount.get()})",
            storage.putCount.get() <= 2
        )

        val revived = newVocab(storage)
        assertEquals(40, revived.size())
    }

    @Test
    fun `debounced write-back persists without explicit flush`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage, debounceMs = 60, maxDelayMs = 2000)

        vocab.recordWordUsage("background")
        waitUntil { storage.getString("vocabulary_data")?.contains("background") == true }

        val revived = newVocab(storage)
        assertTrue(revived.hasWord("background"))
    }

    @Test
    fun `removeWord deletes, marks dirty, and survives flush`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        vocab.recordWordUsage("keepme")
        vocab.recordWordUsage("deleteme")
        vocab.flush()

        assertTrue(vocab.removeWord("deleteme"))
        assertFalse(vocab.removeWord("deleteme")) // already gone
        assertFalse(vocab.removeWord("neverexisted"))
        assertTrue(vocab.isDirty())
        vocab.flush()

        val revived = newVocab(storage)
        assertTrue(revived.hasWord("keepme"))
        assertFalse(revived.hasWord("deleteme"))
    }

    @Test
    fun `clearAll wipes RAM and storage immediately`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        vocab.recordWordUsage("something")
        vocab.flush()

        vocab.clearAll()
        assertEquals(0, vocab.size())

        val revived = newVocab(storage)
        assertEquals(0, revived.size())
    }

    @Test
    fun `export import round-trip preserves usage data`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        repeat(5) { vocab.recordWordUsage("favorite", timestamp = 10_000L + it) }
        vocab.recordWordUsage("rare", timestamp = 20_000L)

        val json = vocab.exportToJson()

        val storage2 = InMemoryLearnedStorage()
        val imported = newVocab(storage2)
        assertEquals(2, imported.importFromJson(json))
        assertEquals(5, imported.getWordUsage("favorite")?.usageCount)

        // Import persists immediately (user-initiated action)
        val revived = newVocab(storage2)
        assertEquals(2, revived.size())
    }

    @Test
    fun `corrupted persisted JSON falls back to empty vocabulary`() {
        val storage = InMemoryLearnedStorage()
        storage.seed("vocabulary_data", "]]]not json")

        val vocab = newVocab(storage)
        assertEquals(0, vocab.size())
        vocab.recordWordUsage("recovers")
        assertTrue(vocab.hasWord("recovers"))
    }

    @Test
    fun `short words are skipped`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        vocab.recordWordUsage("a")
        vocab.recordWordUsage("")
        assertEquals(0, vocab.size())
        assertFalse(vocab.isDirty())
    }

    @Test
    fun `getTopWords ranks by personalization boost`() {
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        val now = System.currentTimeMillis()
        repeat(10) { vocab.recordWordUsage("frequent", timestamp = now) }
        vocab.recordWordUsage("single", timestamp = now)

        val top = vocab.getTopWords(10)
        assertEquals("frequent", top.first().word)
    }
}
