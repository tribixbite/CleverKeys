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
 * Pure-JVM tests for [ContextModel]'s activated trigram path (Task C, audit
 * §1.3-D/§4.2): recordSequence feeds both stores, boost and next-word
 * generation prefer the trigram model with bigram backoff, and lifecycle
 * (clear/save) covers both stores.
 */
class ContextModelTrigramTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val bigramStorage = InMemoryLearnedStorage()
    private val trigramStorage = InMemoryLearnedStorage()
    private val bigramStore = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
    private val model = ContextModel(bigramStore, trigramStore, "en")

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `recordSequence records trigrams alongside bigrams`() {
        model.recordSequence(listOf("i", "want", "to", "go"))

        // Bigrams: (i,want) (want,to) (to,go)
        assertEquals(3, bigramStore.getTotalBigramCount("en"))
        // Trigrams: (i,want,to) (want,to,go)
        assertEquals(2, trigramStore.getTotalTrigramCount("en"))
        assertTrue(trigramStore.getProbability("en", "i", "want", "to") > 0f)
        assertTrue(trigramStore.getProbability("en", "want", "to", "go") > 0f)
    }

    @Test
    fun `two-word sequences record no trigrams`() {
        model.recordSequence(listOf("hello", "world"))
        assertEquals(1, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
    }

    @Test
    fun `getContextBoost prefers the sharper trigram signal`() {
        // Interleaved so the bigram store's last-write probability for (to, go)
        // reflects the mixed 50/50 continuation (after "to": go OR be), while
        // the trigram model still knows P(go | want, to) = 1.0.
        repeat(3) {
            model.recordSequence(listOf("want", "to", "go"))
            model.recordSequence(listOf("have", "to", "be"))
        }

        // With two words of context the trigram model disambiguates: P(go|want,to)=1.0
        val trigramBoost = model.getContextBoost("go", listOf("want", "to"))
        // With one word of context only the diluted bigram is available
        val bigramBoost = model.getContextBoost("go", listOf("to"))

        assertTrue("trigram boost $trigramBoost should exceed bigram boost $bigramBoost",
            trigramBoost > bigramBoost)
    }

    @Test
    fun `getContextBoost backs off to bigram when the trigram is unknown`() {
        repeat(4) { model.recordSequence(listOf("good", "morning")) }

        // Two context words but no matching trigram → bigram backoff still boosts
        val boost = model.getContextBoost("morning", listOf("a", "good"))
        assertTrue(boost > 1.0f)
    }

    @Test
    fun `getNextWordCandidates puts trigram continuations first with bigram backoff`() {
        // Trigram: after (want, to) → "go"
        repeat(3) { model.recordSequence(listOf("want", "to", "go")) }
        // Bigram-only continuation: after "to" → "eat" (recorded from another context)
        repeat(3) { model.recordSequence(listOf("need", "to", "eat")) }

        val candidates = model.getNextWordCandidates(listOf("want", "to"), maxResults = 10)
        assertTrue(candidates.isNotEmpty())
        assertEquals("go", candidates.first().word)
        assertTrue(candidates.first().fromTrigram)
        // Bigram backoff filled the remaining slots (dedup by word)
        assertTrue(candidates.any { it.word == "eat" && !it.fromTrigram })
        assertEquals(candidates.map { it.word }.toSet().size, candidates.size)
    }

    @Test
    fun `single context word yields bigram-only candidates`() {
        repeat(3) { model.recordSequence(listOf("want", "to", "go")) }
        val candidates = model.getNextWordCandidates(listOf("to"))
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.none { it.fromTrigram })
    }

    @Test
    fun `clear wipes both stores for the active language`() {
        model.recordSequence(listOf("a", "b", "c"))
        assertTrue(trigramStore.getTotalTrigramCount("en") > 0)

        model.clear()
        assertEquals(0, bigramStore.getTotalBigramCount("en"))
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
    }

    @Test
    fun `saveBlocking persists both stores for restart survival`() {
        repeat(2) { model.recordSequence(listOf("see", "you", "tomorrow")) }
        model.saveBlocking()

        val revivedBigrams = BigramStore(bigramStorage, 60_000, 120_000, scheduler)
        val revivedTrigrams = TrigramStore(trigramStorage, 60_000, 120_000, scheduler)
        assertTrue(revivedBigrams.getTotalBigramCount("en") > 0)
        assertTrue(revivedTrigrams.getTotalTrigramCount("en") > 0)
        assertEquals(2, revivedTrigrams.getPredictions("en", "see", "you").single().frequency)
    }

    @Test
    fun `language switch keys trigram data too`() {
        model.recordSequence(listOf("guten", "morgen", "welt"))
        model.language = "de"
        assertEquals(0f, trigramStore.getProbability("de", "guten", "morgen", "welt"), 1e-6f)
        model.recordSequence(listOf("guten", "abend", "welt"))
        assertTrue(trigramStore.getProbability("de", "guten", "abend", "welt") > 0f)
        assertFalse(model.getNextWordCandidates(listOf("guten", "morgen")).any { it.fromTrigram })
    }

    @Test
    fun `statistics include the trigram count`() {
        model.recordSequence(listOf("one", "two", "three", "four"))
        assertEquals(2, model.getStatistics().trigramCount)
    }
}
