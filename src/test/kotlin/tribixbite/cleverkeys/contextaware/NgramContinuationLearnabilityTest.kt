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
 * W3 (learning-system audit 2026-09-26): once a context held its full per-context quota
 * (bigram 20, trigram 10), a NEW continuation entered at frequency 1, sorted last and was
 * truncated in the same call — so it could never reach the >=2 query floor. A busy context
 * word ("the") stopped learning forever.
 *
 * These drive the REAL stores over in-memory storage and assert user-visible behaviour: a
 * continuation typed twice is served, however full its context already is.
 */
class NgramContinuationLearnabilityTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun bigrams() = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
    private fun trigrams() = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)

    private fun BigramStore.served(word1: String) = getPredictions("en", word1, maxResults = 100, minProbability = 0f).map { it.word2 }
    private fun TrigramStore.served(w1: String, w2: String) =
        getPredictions("en", w1, w2, maxResults = 100, minProbability = 0f).map { it.word3 }

    /** Fill `the -> *` with [count] established continuations, [each] observations apiece. */
    private fun BigramStore.saturate(count: Int, each: Int) {
        for (i in 1..count) repeat(each) { recordBigram("en", "the", "fill$i") }
    }

    private fun TrigramStore.saturate(count: Int, each: Int) {
        for (i in 1..count) repeat(each) { recordTrigram("en", "i", "want", "fill$i") }
    }

    @Test
    fun `a new bigram continuation typed twice is served even when its context is full`() {
        val store = bigrams()
        store.saturate(count = 20, each = 3)

        repeat(2) { store.recordBigram("en", "the", "git") }

        assertTrue("the -> git was typed twice but is not served: ${store.served("the")}",
            "git" in store.served("the"))
    }

    @Test
    fun `a new trigram continuation typed twice is served even when its prefix is full`() {
        val store = trigrams()
        store.saturate(count = 10, each = 3)

        repeat(2) { store.recordTrigram("en", "i", "want", "git") }

        assertTrue("i want -> git was typed twice but is not served: ${store.served("i", "want")}",
            "git" in store.served("i", "want"))
    }

    @Test
    fun `two new continuations typed alternately both become servable`() {
        // The naive "never evict the newcomer" fix still thrashes here: A evicts an old
        // entry, then B evicts A (the weakest), then A evicts B ... neither reaches 2.
        val store = bigrams()
        store.saturate(count = 24, each = 3)

        repeat(2) {
            store.recordBigram("en", "the", "alpha")
            store.recordBigram("en", "the", "beta")
        }

        val served = store.served("the")
        assertTrue("alpha not served: $served", "alpha" in served)
        assertTrue("beta not served: $served", "beta" in served)
    }

    @Test
    fun `per-context storage stays bounded under a stream of one-off continuations`() {
        val store = bigrams()
        store.saturate(count = 20, each = 3)
        for (i in 1..500) store.recordBigram("en", "the", "oneoff$i")

        val retained = store.getAllBigrams("en", "the").size
        assertTrue("retained $retained continuations for one context", retained <= 24)
        // The 20 established continuations were never displaced by one-offs.
        for (i in 1..20) assertTrue("fill$i lost to one-offs", "fill$i" in store.served("the"))
    }

    @Test
    fun `a stale continuation yields its slot before a fresh one of equal weight`() {
        val store = bigrams()
        // "old" is established first, then never typed again.
        repeat(3) { store.recordBigram("en", "the", "old") }
        // 23 more continuations keep the context busy afterwards.
        store.saturate(count = 23, each = 3)
        assertEquals(24, store.getAllBigrams("en", "the").size)

        // A newcomer forces one eviction among the established entries: all tie on
        // frequency (3), so recency must decide — the one nobody has typed for longest.
        store.recordBigram("en", "the", "fresh")

        val kept = store.getAllBigrams("en", "the").map { it.word2 }
        assertFalse("the stalest entry should have been evicted: $kept", "old" in kept)
        assertTrue("fresh newcomer must be retained: $kept", "fresh" in kept)
    }

    @Test
    fun `the language-wide cap never evicts the continuation recorded in the same call`() {
        val store = bigrams()
        // 1000 contexts x 10 continuations x 2 observations = exactly the 10 000-entry cap.
        for (c in 0 until 1000) for (w in 0 until 10) repeat(2) { store.recordBigram("en", "c$c", "w$w") }

        repeat(2) { store.recordBigram("en", "c0", "newcomer") }

        assertTrue("c0 -> newcomer typed twice but not served: ${store.served("c0")}",
            "newcomer" in store.served("c0"))
        assertTrue(store.getTotalBigramCount("en") <= 10_000)
    }

    @Test
    fun `retention recency survives a restart`() {
        val storage = InMemoryLearnedStorage()
        val live = BigramStore(storage, 60_000, 120_000, scheduler)
        repeat(3) { live.recordBigram("en", "the", "old") }
        live.saturate(count = 23, each = 3)
        live.flush()

        // After a restart the staleness information must still say "old" is stalest.
        val revived = BigramStore(storage, 60_000, 120_000, scheduler)
        revived.recordBigram("en", "the", "fresh")
        val kept = revived.getAllBigrams("en", "the").map { it.word2 }
        assertFalse("recency was lost across the restart: $kept", "old" in kept)
    }
}
