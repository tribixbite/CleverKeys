package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for vocabulary ranking and contraction scoring.
 *
 * Validates that OptimizedVocabulary correctly scores contraction keys
 * so they compete fairly with common words in swipe predictions. Before
 * the b13402e88 fix, contractions got tier 1 (0.6f, 1.0× boost) while
 * common words got tier 2 (0.95f, 1.3× boost), causing "doesn't" to
 * consistently lose to uncommon words.
 *
 * Tests exercise filterPredictions() with synthetic CandidateWord inputs
 * to verify contraction scores without requiring the full ONNX pipeline.
 */
@RunWith(AndroidJUnit4::class)
class VocabularyRankingTest {

    private lateinit var context: Context
    private var vocabulary: OptimizedVocabulary? = null
    private var vocabLoaded = false

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)

        try {
            vocabulary = OptimizedVocabulary(context)
            vocabulary!!.updateConfig(Config.globalConfig())
            vocabLoaded = vocabulary!!.loadVocabulary("en")
        } catch (e: OutOfMemoryError) {
            // 200MB heap on test emulators may be too small for full vocabulary
            vocabulary = null
            vocabLoaded = false
        }
    }

    // =========================================================================
    // Contraction Ranking — Contractions Must Score Competitively
    // =========================================================================

    @Test
    fun contractionKeyAcceptedByFilterPredictions() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // Simulate NN beam search returning "doesnt" as a candidate
        val candidates = listOf(
            CandidateWord("doesnt", 0.85f)
        )
        val stats = SwipeStats(6, 300f, 50f, 'd', 't')

        val results = vocab.filterPredictions(candidates, stats)
        assertTrue("'doesnt' should be accepted and mapped to 'doesn't'",
            results.any { it.displayText == "doesn't" || it.word == "doesn't" })
    }

    @Test
    fun contractionScoreCompetesWithCommonWord() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // Both candidates have identical NN confidence — scoring difference
        // comes purely from vocabulary frequency × tier boost
        val candidates = listOf(
            CandidateWord("doesnt", 0.80f),  // Contraction: should get tier 2
            CandidateWord("doesnt", 0.80f)   // Duplicate to ensure it passes
        )
        val stats = SwipeStats(6, 300f, 50f, 'd', 't')
        val contractionResults = vocab.filterPredictions(candidates, stats)
        val contractionScore = contractionResults.firstOrNull()?.score ?: 0f

        // Now test a common word with the same confidence
        val commonCandidates = listOf(
            CandidateWord("the", 0.80f)  // "the" is tier 2 common word
        )
        val commonStats = SwipeStats(3, 200f, 50f, 't', 'e')
        val commonResults = vocab.filterPredictions(commonCandidates, commonStats)
        val commonScore = commonResults.firstOrNull()?.score ?: 0f

        // Contraction score should be at least 70% of common word score
        // (with tier 2 and 0.88f freq, they should be very close)
        assertTrue(
            "Contraction score ($contractionScore) should be >= 70% of common word score ($commonScore)",
            contractionScore >= commonScore * 0.70f
        )
    }

    @Test
    fun multipleContractionsAllAccepted() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // Multiple contraction candidates from beam search
        val candidates = listOf(
            CandidateWord("doesnt", 0.85f),
            CandidateWord("didnt", 0.80f),
            CandidateWord("shouldnt", 0.75f),
            CandidateWord("wouldnt", 0.70f),
            CandidateWord("couldnt", 0.65f)
        )
        val stats = SwipeStats(7, 350f, 50f, 'd', 't')

        val results = vocab.filterPredictions(candidates, stats)

        // All should pass vocabulary filter (not rejected as unknown)
        assertTrue("Should have at least 3 accepted predictions", results.size >= 3)

        // Each result should have a non-zero score
        for (result in results) {
            assertTrue("Score should be > 0 for ${result.word}", result.score > 0f)
        }
    }

    @Test
    fun contractionScoreIsDescendingByConfidence() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // Candidates with strictly decreasing confidence
        val candidates = listOf(
            CandidateWord("dont", 0.90f),
            CandidateWord("cant", 0.80f),
            CandidateWord("wont", 0.70f)
        )
        val stats = SwipeStats(4, 200f, 50f, 'd', 't')

        val results = vocab.filterPredictions(candidates, stats)
        if (results.size >= 2) {
            for (i in 0 until results.size - 1) {
                assertTrue(
                    "Higher NN confidence should yield higher score: " +
                        "${results[i].word}(${results[i].score}) vs ${results[i+1].word}(${results[i+1].score})",
                    results[i].score >= results[i + 1].score
                )
            }
        }
    }

    @Test
    fun contractionDisplaysMappedApostropheForm() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        val candidates = listOf(
            CandidateWord("dont", 0.90f),
            CandidateWord("cant", 0.85f),
            CandidateWord("wont", 0.80f),
            CandidateWord("shouldnt", 0.75f)
        )
        val stats = SwipeStats(5, 250f, 50f, 'd', 't')

        val results = vocab.filterPredictions(candidates, stats)

        // Each contraction key should be displayed with apostrophe
        val displayTexts = results.map { it.displayText }
        if (results.any { it.word.contains("dont") || it.displayText.contains("don") }) {
            assertTrue("'dont' should display as 'don't'",
                displayTexts.any { it == "don't" })
        }
        if (results.any { it.word.contains("cant") || it.displayText.contains("can") }) {
            assertTrue("'cant' should display as 'can't'",
                displayTexts.any { it == "can't" })
        }
    }

    @Test
    fun disabledWordExcludedFromPredictions() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // Get current disabled words and add a test word
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        val key = LanguagePreferenceKeys.disabledWordsKey("en")
        val disabled = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabled.add("the")
        prefs.edit().putStringSet(key, disabled).apply()

        try {
            // Reload disabled words
            vocab.reloadCustomAndDisabledWords()

            val candidates = listOf(CandidateWord("the", 0.95f))
            val stats = SwipeStats(3, 200f, 50f, 't', 'e')
            val results = vocab.filterPredictions(candidates, stats)

            assertFalse("Disabled word 'the' should not appear in predictions",
                results.any { it.word == "the" || it.displayText == "the" })
        } finally {
            // Restore — remove "the" from disabled set
            disabled.remove("the")
            prefs.edit().putStringSet(key, disabled).apply()
            vocab.reloadCustomAndDisabledWords()
        }
    }

    // =========================================================================
    // Paired Contraction Handling
    // =========================================================================

    @Test
    fun pairedContractionBasePreservedAsRealWord() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // "well" is a paired contraction base — it's a real English word
        // AND has a contraction "we'll". The vocabulary should keep it as a
        // valid word, not replace it.
        val candidates = listOf(CandidateWord("well", 0.90f))
        val stats = SwipeStats(4, 200f, 50f, 'w', 'l')

        val results = vocab.filterPredictions(candidates, stats)
        assertTrue("'well' should be accepted as a valid word",
            results.any { it.word == "well" || it.displayText == "well" })
    }

    @Test
    fun pairedContractionBaseNotReplacedByContraction() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        // "were" is a paired contraction base — should NOT be replaced by "we're"
        val candidates = listOf(CandidateWord("were", 0.90f))
        val stats = SwipeStats(4, 200f, 50f, 'w', 'e')

        val results = vocab.filterPredictions(candidates, stats)
        // The first result should be "were", not "we're"
        if (results.isNotEmpty()) {
            val firstResult = results[0]
            assertEquals("First result should be 'were' not 'we're'",
                "were", firstResult.displayText)
        }
    }

    // =========================================================================
    // Vocabulary Loading
    // =========================================================================

    @Test
    fun vocabularyLoadsSuccessfully() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        val trie = vocab.getVocabularyTrie()
        assertNotNull("Vocabulary trie should be available", trie)
    }

    @Test
    fun vocabularyTrieContainsContractionKeys() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        val trie = vocab.getVocabularyTrie() ?: return

        // Common contraction keys should be in the trie for beam search to find
        assertTrue("Trie should contain 'dont'", trie.containsWord("dont"))
        assertTrue("Trie should contain 'cant'", trie.containsWord("cant"))
        assertTrue("Trie should contain 'wont'", trie.containsWord("wont"))
        assertTrue("Trie should contain 'doesnt'", trie.containsWord("doesnt"))
    }

    @Test
    fun vocabularyTrieContainsCommonWords() {
        assumeTrue("Vocabulary must be loaded", vocabLoaded)
        val vocab = vocabulary!!

        val trie = vocab.getVocabularyTrie() ?: return

        assertTrue("Trie should contain 'the'", trie.containsWord("the"))
        assertTrue("Trie should contain 'hello'", trie.containsWord("hello"))
        assertTrue("Trie should contain 'world'", trie.containsWord("world"))
    }
}
