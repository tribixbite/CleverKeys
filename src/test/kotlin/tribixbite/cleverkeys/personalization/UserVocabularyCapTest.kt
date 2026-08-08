package tribixbite.cleverkeys.personalization

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pure-JVM tests for the user-configurable vocabulary size cap
 * (`personalization_max_words`, 2026-08-08).
 *
 * The cap is threaded into [UserVocabulary] as a dynamic provider (like the
 * other injectable tunables) so the singleton picks up preference changes
 * without reconstruction. Coverage:
 *  - default cap equals [Defaults.PERSONALIZATION_MAX_WORDS] (5000)
 *  - at capacity, adding a word evicts the least-valuable word first
 *  - lowering the cap + [UserVocabulary.enforceCap] evicts down to the new
 *    cap keeping the highest-value words (and persists)
 *  - raising the cap allows growth
 *  - an over-cap persisted vocabulary is trimmed on load
 *  - import respects the cap
 *  - a pathologically low provider value is clamped to the floor
 */
class UserVocabularyCapTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    /** Mutable cap the provider reads — simulates a live preference change. */
    private var capValue: Int = Defaults.PERSONALIZATION_MAX_WORDS

    private fun newVocab(
        storage: InMemoryLearnedStorage,
        maxWords: (() -> Int)? = { capValue }
    ): UserVocabulary {
        return if (maxWords == null) {
            // Default-provider construction (exercises the Defaults fallback)
            UserVocabulary(storage, 60_000, 120_000, scheduler)
        } else {
            UserVocabulary(storage, 60_000, 120_000, scheduler, maxWords = maxWords)
        }
    }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** Record [word] [count] times at a recent timestamp so recency score is 1.0. */
    private fun record(vocab: UserVocabulary, word: String, count: Int, now: Long) {
        repeat(count) { vocab.recordWordUsage(word, timestamp = now) }
    }

    @Test
    fun `default cap is 5000 from Defaults`() {
        assertEquals(5000, Defaults.PERSONALIZATION_MAX_WORDS)
        val vocab = newVocab(InMemoryLearnedStorage(), maxWords = null)
        assertEquals(5000, vocab.currentCap())
    }

    @Test
    fun `provider changes are picked up without reconstruction`() {
        val vocab = newVocab(InMemoryLearnedStorage())
        capValue = 1234
        assertEquals(1234, vocab.currentCap())
        capValue = 20000
        assertEquals(20000, vocab.currentCap())
    }

    @Test
    fun `absurdly low cap is clamped to floor`() {
        val vocab = newVocab(InMemoryLearnedStorage())
        capValue = 1
        assertEquals(UserVocabulary.MIN_VOCABULARY_CAP, vocab.currentCap())
        capValue = -5
        assertEquals(UserVocabulary.MIN_VOCABULARY_CAP, vocab.currentCap())
    }

    @Test
    fun `at capacity the least valuable word is evicted before adding`() {
        capValue = 105 // floor is 100; use a small over-floor cap
        val vocab = newVocab(InMemoryLearnedStorage())
        val now = System.currentTimeMillis()

        // words w1..w105 with strictly increasing usage counts 2..106
        for (i in 1..105) record(vocab, "word$i", i + 1, now)
        assertEquals(105, vocab.size())

        vocab.recordWordUsage("fresh", timestamp = now)
        assertEquals(105, vocab.size())
        assertTrue(vocab.hasWord("fresh"))
        assertFalse("lowest-value word1 should be evicted", vocab.hasWord("word1"))
        assertTrue("high-value word105 must survive", vocab.hasWord("word105"))
    }

    @Test
    fun `lowering cap evicts down to new cap keeping highest-value words`() {
        capValue = 200
        val vocab = newVocab(InMemoryLearnedStorage())
        val now = System.currentTimeMillis()
        for (i in 1..110) record(vocab, "word$i", i + 1, now)
        assertEquals(110, vocab.size())

        capValue = 104
        val removed = vocab.enforceCap(now)
        assertEquals(6, removed)
        assertEquals(104, vocab.size())
        // The 6 lowest-value words (word1..word6) are gone, the rest survive
        for (i in 1..6) assertFalse("word$i should be evicted", vocab.hasWord("word$i"))
        for (i in 7..110) assertTrue("word$i should survive", vocab.hasWord("word$i"))
    }

    @Test
    fun `enforceCap is a no-op when under cap and raising cap allows growth`() {
        capValue = 103
        val vocab = newVocab(InMemoryLearnedStorage())
        val now = System.currentTimeMillis()
        for (i in 1..103) record(vocab, "word$i", 2, now)
        assertEquals(103, vocab.size())

        capValue = 110
        assertEquals(0, vocab.enforceCap(now))
        for (i in 104..110) record(vocab, "word$i", 2, now)
        assertEquals(110, vocab.size())
    }

    @Test
    fun `enforceCap persists the trimmed vocabulary`() {
        capValue = 120
        val storage = InMemoryLearnedStorage()
        val vocab = newVocab(storage)
        val now = System.currentTimeMillis()
        for (i in 1..110) record(vocab, "word$i", i + 1, now)
        vocab.flush()

        capValue = 104
        vocab.enforceCap(now)
        vocab.flush()

        val revived = newVocab(storage)
        assertEquals(104, revived.size())
        assertFalse(revived.hasWord("word1"))
        assertTrue(revived.hasWord("word110"))
    }

    @Test
    fun `over-cap persisted vocabulary is trimmed on load`() {
        capValue = 120
        val storage = InMemoryLearnedStorage()
        val writer = newVocab(storage)
        val now = System.currentTimeMillis()
        for (i in 1..110) record(writer, "word$i", i + 1, now)
        writer.flush()

        // Simulate the user having lowered the cap before restart
        capValue = 102
        val reloaded = newVocab(storage)
        assertEquals(102, reloaded.size())
        assertTrue("highest-value word must survive load-trim", reloaded.hasWord("word110"))
        assertFalse("lowest-value word must be trimmed on load", reloaded.hasWord("word1"))
    }

    @Test
    fun `import respects the cap keeping highest-value words`() {
        capValue = 200
        val source = newVocab(InMemoryLearnedStorage())
        val now = System.currentTimeMillis()
        for (i in 1..110) record(source, "word$i", i + 1, now)
        val json = source.exportToJson() // sorted by boost descending

        capValue = 103
        val target = newVocab(InMemoryLearnedStorage())
        assertEquals(103, target.importFromJson(json))
        assertEquals(103, target.size())
        assertTrue(target.hasWord("word110"))
        assertFalse(target.hasWord("word1"))
    }
}
