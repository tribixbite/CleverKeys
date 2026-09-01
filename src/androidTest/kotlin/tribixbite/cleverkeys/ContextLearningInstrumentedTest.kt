package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextContinuation
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.SharedPrefsLearnedStorage
import tribixbite.cleverkeys.personalization.UserVocabulary
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Instrumented coverage for the 2026-08-06 context-LM/privacy work that pure-JVM
 * tests cannot reach — the pieces that need REAL Android substrates:
 *
 * 1. Persistence round-trip over real [android.content.SharedPreferences]
 *    (record → flush → a fresh store instance, as after process death, reads the
 *    learned data back) for BigramStore, TrigramStore, and UserVocabulary.
 * 2. Master-privacy-gate + incognito-field write suppression measured at the
 *    real prefs files: with the gate off, NOTHING reaches storage.
 * 3. Next-word candidates surfacing in a real [SuggestionBar] with NEXT_WORD
 *    provenance metas (tap routing + long-press sheet source) and the M6
 *    bar-generation bump.
 *
 * The pure logic (gating truth table, generation filters, debounce semantics)
 * is covered by NextWordPredictorTest / LearningGateTest /
 * OnDeviceLearningPrivacyTest and the store persistence tests; this class stays
 * focused on the Android-only integration.
 *
 * Isolation: dedicated test-only prefs files, cleared with commit() in
 * setup/teardown (ew-cli orchestrator rule: apply() restores can be lost to
 * process death and leak into later tests).
 */
@RunWith(AndroidJUnit4::class)
class ContextLearningInstrumentedTest {

    companion object {
        // Dedicated prefs files — never the production store files, so a test
        // failure can't corrupt (or be corrupted by) real learned data.
        private const val BIGRAM_PREFS = "test_context_lm_bigram_store"
        private const val TRIGRAM_PREFS = "test_context_lm_trigram_store"
        private const val VOCAB_PREFS = "test_context_lm_user_vocabulary"

        private const val BIGRAM_KEY_EN = "bigrams_json_en"
        private const val TRIGRAM_KEY_EN = "trigrams_json_en"
    }

    private lateinit var context: Context
    private lateinit var bigramPrefs: SharedPreferences
    private lateinit var trigramPrefs: SharedPreferences
    private lateinit var vocabPrefs: SharedPreferences
    private lateinit var scheduler: ScheduledThreadPoolExecutor

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        bigramPrefs = context.getSharedPreferences(BIGRAM_PREFS, Context.MODE_PRIVATE)
        trigramPrefs = context.getSharedPreferences(TRIGRAM_PREFS, Context.MODE_PRIVATE)
        vocabPrefs = context.getSharedPreferences(VOCAB_PREFS, Context.MODE_PRIVATE)
        clearAllTestPrefs()
        scheduler = ScheduledThreadPoolExecutor(1)
    }

    @After
    fun tearDown() {
        clearAllTestPrefs()
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun clearAllTestPrefs() {
        // commit(), not apply(): the orchestrator kills the process right after
        // the test — an async restore can be lost and leak into later tests.
        bigramPrefs.edit().clear().commit()
        trigramPrefs.edit().clear().commit()
        vocabPrefs.edit().clear().commit()
    }

    // Long debounce so flushing is explicit and deterministic in every test.
    private fun newBigramStore() =
        BigramStore(SharedPrefsLearnedStorage(bigramPrefs), 60_000, 120_000, scheduler)

    private fun newTrigramStore() =
        TrigramStore(SharedPrefsLearnedStorage(trigramPrefs), 60_000, 120_000, scheduler)

    private fun newUserVocabulary() =
        UserVocabulary(SharedPrefsLearnedStorage(vocabPrefs), 60_000, 120_000, scheduler)

    // ================== 1. Real-SharedPreferences persistence round-trip

    @Test
    fun bigramStore_recordFlush_freshStoreOverRealPrefsReadsItBack() {
        val store = newBigramStore()
        repeat(2) { store.recordBigram("en", "i", "want") }
        repeat(2) { store.recordBigram("en", "want", "to") }
        store.flush()

        // The data actually reached the real prefs file (not just store RAM).
        assertNotNull(
            "flushed bigrams must be in SharedPreferences",
            bigramPrefs.getString(BIGRAM_KEY_EN, null)
        )

        // "Process restart": a FRESH store over the same prefs lazily reloads.
        val revived = newBigramStore()
        val predictions = revived.getPredictions("en", "i")
        assertEquals(1, predictions.size)
        assertEquals("want", predictions[0].word2)
        assertEquals(2, predictions[0].frequency)
        assertEquals(1.0f, predictions[0].probability, 1e-4f)
    }

    @Test
    fun trigramStore_recordFlush_freshStoreOverRealPrefsReadsItBack() {
        val store = newTrigramStore()
        repeat(3) { store.recordTrigram("en", "i", "want", "to") }
        store.flush()

        assertNotNull(
            "flushed trigrams must be in SharedPreferences",
            trigramPrefs.getString(TRIGRAM_KEY_EN, null)
        )

        val revived = newTrigramStore()
        assertEquals(1.0f, revived.getProbability("en", "i", "want", "to"), 1e-4f)
        assertEquals(1, revived.getTotalTrigramCount("en"))
    }

    @Test
    fun userVocabulary_recordFlush_freshInstanceOverRealPrefsReadsItBack() {
        val vocab = newUserVocabulary()
        repeat(3) { vocab.recordWordUsage("cleverkeys") }
        vocab.flush()

        assertTrue("flushed vocabulary must be in SharedPreferences", vocabPrefs.all.isNotEmpty())

        val revived = newUserVocabulary()
        assertTrue(revived.hasWord("cleverkeys"))
        assertEquals(3, revived.getWordUsage("cleverkeys")?.usageCount)
        assertTrue(revived.getPersonalizationBoost("cleverkeys") > 0f)
    }

    @Test
    fun bigramStore_languageKeyedBlobs_persistIndependently() {
        val store = newBigramStore()
        store.recordBigram("en", "i", "want")
        store.recordBigram("fr", "je", "veux")
        store.flush()

        assertNotNull(bigramPrefs.getString("bigrams_json_en", null))
        assertNotNull(bigramPrefs.getString("bigrams_json_fr", null))

        val revived = newBigramStore()
        assertEquals(1, revived.getTotalBigramCount("en"))
        assertEquals(1, revived.getTotalBigramCount("fr"))
        // Language isolation: en data never answers fr lookups.
        assertEquals(0f, revived.getProbability("fr", "i", "want"), 0f)
    }

    // ================== 2. Master-gate / incognito write suppression at the prefs layer

    /** Wire the REAL learn funnel onto real-prefs-backed stores. */
    private fun runLearnFunnel(
        masterEnabled: Boolean,
        fieldAllows: Boolean,
        bigrams: BigramStore,
        trigrams: TrigramStore,
        vocab: UserVocabulary
    ) {
        val model = tribixbite.cleverkeys.contextaware.ContextModel(bigrams, trigrams, "en")
        val window = listOf("i", "want", "to")
        LearningGate.learnCommittedWord(
            recentWords = window,
            committedWord = "to",
            onDeviceLearningEnabled = masterEnabled,
            contextAwareEnabled = true,
            personalizedLearningEnabled = true,
            recordSequence = { sequence -> model.recordCommit(sequence) },
            recordWordUsage = { word -> vocab.recordWordUsage(word) },
            fieldAllowsPersonalizedLearning = fieldAllows
        )
        // Even an (incorrect) in-RAM mutation would be surfaced by these:
        bigrams.flush()
        trigrams.flush()
        vocab.flush()
    }

    @Test
    fun masterGateOff_learnFunnelWritesNothingToRealPrefs() {
        runLearnFunnel(
            masterEnabled = false, fieldAllows = true,
            bigrams = newBigramStore(), trigrams = newTrigramStore(), vocab = newUserVocabulary()
        )

        assertTrue("bigram prefs must stay empty with master off", bigramPrefs.all.isEmpty())
        assertTrue("trigram prefs must stay empty with master off", trigramPrefs.all.isEmpty())
        assertTrue("vocabulary prefs must stay empty with master off", vocabPrefs.all.isEmpty())
    }

    @Test
    fun incognitoField_learnFunnelWritesNothingToRealPrefs() {
        // M5: IME_FLAG_NO_PERSONALIZED_LEARNING outranks every enabled user pref.
        runLearnFunnel(
            masterEnabled = true, fieldAllows = false,
            bigrams = newBigramStore(), trigrams = newTrigramStore(), vocab = newUserVocabulary()
        )

        assertTrue("bigram prefs must stay empty for incognito fields", bigramPrefs.all.isEmpty())
        assertTrue("trigram prefs must stay empty for incognito fields", trigramPrefs.all.isEmpty())
        assertTrue("vocabulary prefs must stay empty for incognito fields", vocabPrefs.all.isEmpty())
    }

    @Test
    fun masterGateOn_learnFunnelPersistsToRealPrefs() {
        // Positive control: the suppression tests above are only meaningful if
        // the identical wiring DOES write when the gates pass.
        runLearnFunnel(
            masterEnabled = true, fieldAllows = true,
            bigrams = newBigramStore(), trigrams = newTrigramStore(), vocab = newUserVocabulary()
        )

        assertNotNull(bigramPrefs.getString(BIGRAM_KEY_EN, null))
        assertNotNull(trigramPrefs.getString(TRIGRAM_KEY_EN, null))
        assertTrue(vocabPrefs.all.isNotEmpty())
    }

    // ================== 3. Next-word surfacing in a real SuggestionBar

    @Test
    fun nextWordCandidates_surfaceInRealSuggestionBar_withProvenanceMetas() {
        // Learned continuations → ranked candidates via the production generator.
        val learned = listOf(
            ContextContinuation("to", 14, 0.63f, fromTrigram = false),
            ContextContinuation("more", 4, 0.20f, fromTrigram = false)
        )
        val candidates = NextWordPredictor.generate(
            learned = learned,
            lastCommittedWord = "want",
            personalizationBoost = { 0f },
            isWordAllowed = { true }
        )
        assertEquals(listOf("to", "more"), candidates.map { it.word })

        val contextWords = listOf("i", "want")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = SuggestionBar(context)
            val generationBefore = bar.contentGeneration()

            bar.setSuggestionsWithScores(
                candidates.map { it.word },
                candidates.map { it.score },
                candidates.map {
                    SuggestionMeta(
                        SuggestionOrigin.NEXT_WORD,
                        note = NextWordPredictor.provenanceNote(it, contextWords)
                    )
                }
            )

            // Surfaced: content, ranking, and tap-routable NEXT_WORD metas.
            assertTrue(bar.hasSuggestions())
            assertEquals("to", bar.getTopSuggestion())
            val meta = bar.getMetaForSuggestion("to")
            assertEquals(SuggestionOrigin.NEXT_WORD, meta?.origin)
            // Structured provenance carries the learned statistics to the localized sheet.
            val note = meta?.note as? ProvenanceNote.NextWord
            assertEquals(14, note?.frequency)
            assertEquals("want", note?.context)

            // M6 substrate: posting content bumps the bar generation, which is
            // what invalidates stale queued next-word posts.
            assertTrue(bar.contentGeneration() != generationBefore)
        }
    }

    @Test
    fun nextWordCandidates_doNotSurfaceInPasswordMode_onRealBar() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = SuggestionBar(context)
            bar.setPasswordMode(true)

            bar.setSuggestionsWithScores(
                listOf("to"), listOf(630),
                listOf(SuggestionMeta(SuggestionOrigin.NEXT_WORD))
            )

            // The bar itself refuses content in password mode — belt-and-braces
            // beneath NextWordPredictor.shouldShow's isPasswordMode gate.
            assertFalse(bar.hasSuggestions())
        }
    }
}
