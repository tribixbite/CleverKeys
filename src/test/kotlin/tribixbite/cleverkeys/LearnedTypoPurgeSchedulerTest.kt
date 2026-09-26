package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * WHEN the typo-hygiene purge may run, and WHAT it judges against (adversarial review of
 * `59bd4159`, 2026-09-26):
 *
 * 1. **Bilingual users** (HIGH). The purge was scheduled from the PRIMARY dictionary's
 *    publication, with the secondary lexicon read at that instant — but the secondary loads
 *    asynchronously and usually publishes later. Everything a bilingual user commits lands in
 *    the primary language's store, so every secondary-language n-gram observed ≤ 2 times was
 *    judged "unknown" and deleted, and the 7-day stamp was written. The purge must wait until
 *    every configured lexicon source is published, and run from whichever publishes last.
 * 2. **Frozen snapshot** (MEDIUM). The "snapshot" was the LIVE dictionary map, which the main
 *    thread mutates (user-dictionary observer, custom-word reload, a synchronous reload that
 *    refills the map) while the purge iterates on the persistence thread. The purge must judge
 *    against a copy taken when it is scheduled.
 *
 * Drives the real [ContextModel.purgeUnlearnable] over real stores on in-memory storage; the
 * persistence thread is a manual queue so the test controls when the purge body runs.
 */
class LearnedTypoPurgeSchedulerTest {

    private companion object {
        const val NOW = 10_000_000L
    }

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
    private val contextModel = ContextModel(bigramStore, trigramStore, "en")

    /** The persistence thread, run by hand. */
    private val queued = ArrayDeque<Runnable>()
    private val persistenceThread = Executor { queued.addLast(it) }
    private fun drainPersistenceThread() { while (queued.isNotEmpty()) queued.removeFirst().run() }

    private val purgeScheduler = LearnedTypoPurgeScheduler(
        executor = persistenceThread,
        isDue = { lang -> LearnableWordPolicy.isPurgeDue(bigramStore.getTypoPurgeStamp(lang), NOW) },
        runPurge = { lang, learnable -> contextModel.purgeUnlearnable(lang, learnable, nowMs = NOW) }
    )

    private val english = mutableMapOf("i" to 200, "am" to 200, "the" to 220, "cat" to 190)
    private val spanish = setOf("hola", "amigo", "que")

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun state(
        configuredSecondary: String? = null,
        publishedSecondary: String? = null,
        lexicon: Map<String, Int> = english,
        language: String = "en"
    ) = LearnedTypoPurgeScheduler.LexiconState(
        language = language,
        lexicon = lexicon,
        userWords = emptySet(),
        configuredSecondary = configuredSecondary,
        publishedSecondary = publishedSecondary,
        secondaryContains = if (publishedSecondary == "es") ({ w: String -> w in spanish }) else null,
        observationCount = { 0 }
    )

    private fun hasBigram(w1: String, w2: String) =
        bigramStore.getAllBigrams("en", w1).any { it.word2 == w2 }

    /** A bilingual user's history in the PRIMARY store: Spanish pairs + one real typo. */
    private fun seedBilingualHistory() {
        bigramStore.recordBigram("en", "hola", "amigo")
        bigramStore.recordBigram("en", "the", "cat")
        bigramStore.recordBigram("en", "the", "cta") // a genuine typo
    }

    // --------------------------------------------------------------- 1. bilingual

    @Test
    fun `primary published before the configured secondary - nothing is purged and no stamp is written`() {
        seedBilingualHistory()

        purgeScheduler.onPrimaryPublished(state(configuredSecondary = "es", publishedSecondary = null))
        drainPersistenceThread()

        assertTrue("Spanish pair deleted before the Spanish lexicon was published", hasBigram("hola", "amigo"))
        assertNull("stamp written by a purge that could not judge the secondary language",
            bigramStore.getTypoPurgeStamp("en"))
    }

    @Test
    fun `the deferred purge runs when the secondary publishes, and keeps the secondary language's words`() {
        seedBilingualHistory()
        purgeScheduler.onPrimaryPublished(state(configuredSecondary = "es", publishedSecondary = null))
        drainPersistenceThread()

        purgeScheduler.onSecondaryChanged(state(configuredSecondary = "es", publishedSecondary = "es"))
        drainPersistenceThread()

        assertTrue("Spanish pair must survive", hasBigram("hola", "amigo"))
        assertTrue(hasBigram("the", "cat"))
        assertTrue("the typo is still purged", !hasBigram("the", "cta"))
        assertEquals(NOW, bigramStore.getTypoPurgeStamp("en"))
    }

    @Test
    fun `secondary published first - the primary publication runs the purge with both lexicons`() {
        seedBilingualHistory()
        purgeScheduler.onSecondaryChanged(state(configuredSecondary = "es", publishedSecondary = "es"))
        drainPersistenceThread()
        assertNull("no primary lexicon yet - nothing to run", bigramStore.getTypoPurgeStamp("en"))

        purgeScheduler.onPrimaryPublished(state(configuredSecondary = "es", publishedSecondary = "es"))
        drainPersistenceThread()

        assertTrue(hasBigram("hola", "amigo"))
        assertTrue(!hasBigram("the", "cta"))
        assertNotNull(bigramStore.getTypoPurgeStamp("en"))
    }

    @Test
    fun `a secondary switch in flight defers the purge too`() {
        seedBilingualHistory()
        // Serving "fr" but the user just picked "es": the "es" index is not published yet.
        purgeScheduler.onPrimaryPublished(state(configuredSecondary = "es", publishedSecondary = "fr"))
        drainPersistenceThread()
        assertTrue(hasBigram("hola", "amigo"))
        assertNull(bigramStore.getTypoPurgeStamp("en"))
    }

    @Test
    fun `a deferred purge is dropped when a different primary language is published meanwhile`() {
        seedBilingualHistory()
        purgeScheduler.onPrimaryPublished(state(configuredSecondary = "es", publishedSecondary = null))
        // The user switched the primary to German before Spanish arrived; its own
        // publication decides for "de". Spanish then publishes: nothing may run for "en".
        purgeScheduler.onPrimaryPublished(
            state(configuredSecondary = "es", publishedSecondary = null, language = "de")
        )
        purgeScheduler.onSecondaryChanged(
            state(configuredSecondary = "es", publishedSecondary = "es", language = "de")
        )
        drainPersistenceThread()
        assertNull("en purge ran against the de lexicon", bigramStore.getTypoPurgeStamp("en"))
    }

    @Test
    fun `monolingual - the purge runs at primary publication as before`() {
        seedBilingualHistory()
        purgeScheduler.onPrimaryPublished(state())
        drainPersistenceThread()
        assertTrue(!hasBigram("the", "cta"))
        assertTrue("no secondary configured: the Spanish pair is unknown and purged", !hasBigram("hola", "amigo"))
        assertEquals(NOW, bigramStore.getTypoPurgeStamp("en"))
    }

    // ------------------------------------------------------------ 2. frozen snapshot

    @Test
    fun `the purge judges the lexicon as it was when scheduled, not the live map`() {
        seedBilingualHistory()
        val live = HashMap(english)
        purgeScheduler.onPrimaryPublished(state(lexicon = live))

        // Main thread mutates the live map before the persistence thread gets to the purge
        // (a synchronous reload clears and refills it; the observer removes words).
        live.clear()

        drainPersistenceThread()
        assertTrue("the->cat purged because the live map was emptied mid-purge", hasBigram("the", "cat"))
    }

    @Test
    fun `nothing is copied or queued while the purge is not due`() {
        bigramStore.setTypoPurgeStamp("en", NOW - 1)
        purgeScheduler.onPrimaryPublished(state())
        assertTrue("a purge that is not due must not queue work", queued.isEmpty())
    }

    // ------------------------------------------------------------ production wiring

    @Test
    fun `WordPredictor routes both publications through the scheduler with a configured-secondary read`() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt").readText()
        assertTrue(src.contains("typoPurgeScheduler().onPrimaryPublished("))
        // Both secondary publication paths (async + blocking) and the unload re-evaluate.
        assertEquals(3, Regex("""notifySecondaryLexiconChanged\(\)""").findAll(src).count() - 1)
        assertTrue(src.contains("\"pref_enable_multilang\""))
    }
}
