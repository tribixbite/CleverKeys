package tribixbite.cleverkeys

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import tribixbite.cleverkeys.personalization.UserVocabulary
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Typo hygiene for the learned stores (learning-system audit 2026-09-26; user report:
 * "I've noticed typos in the learned bigrams").
 *
 * Before: every committed word fed the context LM, and the next-word allow-list accepted any
 * word present in the personalization vocabulary — which tallies every committed word — so a
 * single typo could both be learned and surface.
 *
 * Drives the production funnel ([LearningGate.learnCommittedWord] with the exact sink lambdas
 * `WordPredictor.addWordToContext` wires) over the REAL stores on in-memory storage, with a
 * [LearnableWordPolicy] whose lexicon is a fixed word set.
 */
class LearnedTypoHygieneTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)
    private val bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
    private val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
    private val contextModel = ContextModel(bigramStore, trigramStore, "en")
    private val vocabulary = UserVocabulary(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)

    private val lexicon = setOf(
        "the", "cat", "sat", "on", "mat", "i", "use", "git", "every", "day", "dont",
        "well", "known", "co", "op", "rock", "n", "roll", "a"
    )
    private var lexiconLoaded = true
    private val policy = LearnableWordPolicy(
        lexiconReady = { lexiconLoaded },
        isKnownWord = { it in lexicon },
        observationCount = { vocabulary.getWordUsage(it)?.usageCount ?: 0 }
    )

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    /** The production learn loop, one sentence per list (window cleared between them). */
    private fun type(vararg sentence: String) {
        val window = mutableListOf<String>()
        for (word in sentence) {
            val normalized = word.lowercase().trim()
            window.add(normalized)
            LearningGate.learnCommittedWord(
                recentWords = window,
                committedWord = normalized,
                onDeviceLearningEnabled = true,
                contextAwareEnabled = true,
                personalizedLearningEnabled = true,
                recordSequence = { seq -> contextModel.recordCommit(seq, policy::isLearnable) },
                recordWordUsage = { w -> vocabulary.recordWordUsage(w) }
            )
        }
    }

    private fun allBigramWords() = bigramStore.getAllEntries("en").flatMap { listOf(it.word1, it.word2) }.toSet()

    // ------------------------------------------------------------- the predicate

    @Test
    fun `known words are learnable and a one-off unknown word is not`() {
        assertTrue(policy.isLearnable("Cat"))
        assertFalse(policy.isLearnable("teh"))
        assertFalse(policy.isLearnable(""))
    }

    @Test
    fun `apostrophe forms resolve against their base`() {
        assertTrue("contraction alias key", policy.isLearnable("don't"))
        assertTrue("typographic apostrophe", policy.isLearnable("don’t"))
        assertTrue("possessive of a known word", policy.isLearnable("git's"))
        assertFalse("possessive of a typo", policy.isLearnable("gti's"))
    }

    /**
     * Review of 59bd4159 (MEDIUM): W7 learns a hyphenated token whole ("well-known"), and the
     * shipped lexicon has no hyphenated entries — so without this every compound was judged a
     * typo until typed three times, while its parts were no longer learned either.
     */
    @Test
    fun `a hyphenated compound is known when every part is known`() {
        assertTrue(policy.isLearnable("well-known"))
        assertTrue("case-insensitive", policy.isLearnable("Well-Known"))
        assertTrue("three parts", policy.isLearnable("a-well-known"))
        assertTrue("apostrophe handling applies per part", policy.isLearnable("rock-'n'-roll"))
        assertTrue("possessive of a known compound", policy.isLearnable("well-known's"))
        assertTrue("empty parts are ignored", policy.isLearnable("well--known"))
        assertFalse("one misspelled part", policy.isLearnable("well-knwon"))
        assertFalse("no known part at all", policy.isLearnable("-"))
        assertFalse(policy.isLearnable("--"))
    }

    @Test
    fun `a hyphenated compound of known parts enters the store on its first commit`() {
        type("a", "well-known", "cat")
        assertEquals(1, bigramStore.getAllBigrams("en", "a").single { it.word2 == "well-known" }.frequency)
        assertEquals(1, bigramStore.getAllBigrams("en", "well-known").single { it.word2 == "cat" }.frequency)
    }

    @Test
    fun `an unknown word becomes learnable on its third commit`() {
        repeat(2) { vocabulary.recordWordUsage("kubectl") }
        assertFalse(policy.isLearnable("kubectl"))
        vocabulary.recordWordUsage("kubectl")
        assertTrue(policy.isLearnable("kubectl"))
        assertEquals(3, LearnableWordPolicy.REPEAT_OBSERVATIONS_TO_LEARN)
    }

    @Test
    fun `nothing is judged while the lexicon is not loaded`() {
        lexiconLoaded = false
        assertTrue(policy.isLearnable("teh"))
    }

    // ------------------------------------------------------------ the write gate

    @Test
    fun `a one-off typo never enters the bigram or trigram store`() {
        type("the", "teh", "cat", "sat")

        assertFalse("typo learned: ${bigramStore.getAllEntries("en")}", "teh" in allBigramWords())
        // The clean pair after the typo still learns.
        assertEquals(1, bigramStore.getAllBigrams("en", "cat").single { it.word2 == "sat" }.frequency)
        // Trigram (teh, cat, sat) has a typo in position 1 — blocked; nothing else formed.
        assertEquals(0, trigramStore.getTotalTrigramCount("en"))
    }

    @Test
    fun `a genuinely new word the user keeps typing still becomes learnable and servable`() {
        repeat(4) { type("i", "use", "kubectl") }

        val entry = bigramStore.getAllBigrams("en", "use").single { it.word2 == "kubectl" }
        // Commits 3 and 4 were learned (the tally ran first each time); 1 and 2 were not.
        assertEquals(2, entry.frequency)
        assertTrue("kubectl" in bigramStore.getPredictions("en", "use").map { it.word2 })
    }

    @Test
    fun `rollback does not decrement an n-gram the write gate skipped`() {
        // "the cat" learned twice legitimately.
        repeat(2) { type("the", "cat") }
        // A typo commit is skipped by the gate; rolling it back must not touch "the cat".
        val window = listOf("the", "cta")
        contextModel.recordCommit(window, policy::isLearnable)
        contextModel.rollbackCommit(window, policy::isLearnable)
        assertEquals(2, bigramStore.getAllBigrams("en", "the").single { it.word2 == "cat" }.frequency)
    }

    // ------------------------------------------------------------------ the purge

    @Test
    fun `purge removes low-frequency typo n-grams and keeps frequent jargon`() {
        // Pre-gate history: typos and one frequently typed out-of-lexicon phrase.
        bigramStore.recordBigram("en", "the", "teh")
        repeat(2) { bigramStore.recordBigram("en", "teh", "cat") }
        repeat(5) { bigramStore.recordBigram("en", "the", "cat") }
        repeat(7) { bigramStore.recordBigram("en", "use", "kubectl") }
        repeat(2) { trigramStore.recordTrigram("en", "the", "teh", "cat") }
        repeat(3) { trigramStore.recordTrigram("en", "the", "cat", "sat") }

        val removed = contextModel.purgeUnlearnable("en", policy::isLearnable, nowMs = 1_000L)

        assertEquals(3, removed) // the->teh, teh->cat, (the teh)->cat
        assertFalse("teh" in allBigramWords())
        assertTrue("frequent jargon must survive", "kubectl" in bigramStore.getAllBigrams("en", "use").map { it.word2 })
        // Denominator lost the purged observation: P(cat | the) is now 5/5.
        assertEquals(1.0f, bigramStore.getProbability("en", "the", "cat"), 1e-6f)
        assertEquals(1, trigramStore.getTotalTrigramCount("en"))
    }

    @Test
    fun `purge runs once, then only after the interval`() {
        bigramStore.recordBigram("en", "the", "teh")
        assertEquals(1, contextModel.purgeUnlearnable("en", policy::isLearnable, nowMs = 1_000L))

        bigramStore.recordBigram("en", "the", "teh") // e.g. restored from a backup
        assertNull("not due again within the interval",
            contextModel.purgeUnlearnable("en", policy::isLearnable, nowMs = 2_000L))
        assertEquals(1, contextModel.purgeUnlearnable(
            "en", policy::isLearnable, nowMs = 1_000L + LearnableWordPolicy.PURGE_INTERVAL_MS
        ))
    }

    @Test
    fun `purge schedule decision`() {
        assertTrue(LearnableWordPolicy.isPurgeDue(null, 5L))
        assertFalse(LearnableWordPolicy.isPurgeDue(5L, 6L))
        assertTrue(LearnableWordPolicy.isPurgeDue(5L, 5L + LearnableWordPolicy.PURGE_INTERVAL_MS))
        assertTrue("clock moved backwards", LearnableWordPolicy.isPurgeDue(10L, 5L))
    }

    // ------------------------------------------------------ production wiring (drift)

    @Test
    fun `WordPredictor wires the one policy into every consumer`() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt").readText()
        assertTrue(src.contains("contextModel?.recordCommit(sequence, learnableWordPolicy::isLearnable)"))
        assertTrue(src.contains("learnableWordPolicy::isLearnable)"))
        assertTrue(src.contains("return learnableWordPolicy.isRepeatedlyObserved(word)"))
        // The purge is triggered from BOTH dictionary publication paths.
        assertEquals(2, Regex("""\n\s+scheduleLearnedTypoPurge\(language\)""").findAll(src).count())
    }
}
