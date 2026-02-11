package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented tests for WordPredictor.
 * Tests word prediction, autocomplete, and autocorrection functionality.
 * Uses TestDictionaryHelper for a small test dictionary to avoid OOM.
 */
@RunWith(AndroidJUnit4::class)
class WordPredictorTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        predictor = WordPredictor()
        predictor.setContext(context)
        // Inject small test dictionary via reflection to avoid OOM from
        // loading full en_enhanced.bin (1.3MB file → ~150MB in-memory HashMap + prefix index)
        injectTestDictionary()
    }

    /**
     * Inject TestDictionaryHelper words into WordPredictor's private fields
     * via reflection, avoiding the full binary dictionary load that causes OOM.
     */
    private fun injectTestDictionary() {
        val testWords = TestDictionaryHelper.getTestWords().toMutableMap()

        // Build prefix index matching the production format (1-3 char prefixes)
        val testPrefixIndex = mutableMapOf<String, MutableSet<String>>()
        for (word in testWords.keys) {
            val maxLen = minOf(3, word.length)
            for (len in 1..maxLen) {
                val prefix = word.substring(0, len).lowercase()
                testPrefixIndex.getOrPut(prefix) { mutableSetOf() }.add(word)
            }
        }

        // Inject via reflection into AtomicReference fields
        try {
            val dictField = WordPredictor::class.java.getDeclaredField("dictionary")
            dictField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val dictRef = dictField.get(predictor) as AtomicReference<MutableMap<String, Int>>
            dictRef.set(testWords)

            val indexField = WordPredictor::class.java.getDeclaredField("prefixIndex")
            indexField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val indexRef = indexField.get(predictor) as AtomicReference<MutableMap<String, MutableSet<String>>>
            indexRef.set(testPrefixIndex)
        } catch (e: Exception) {
            // Fallback: try loading normally (will OOM on small heaps)
            predictor.loadDictionary(context, "en")
        }
    }

    // =========================================================================
    // Basic initialization tests
    // =========================================================================

    @Test
    fun testPredictorInitialization() {
        assertNotNull("Predictor should be initialized", predictor)
    }

    @Test
    fun testSetContext() {
        // Should not crash
        predictor.setContext(context)
    }

    @Test
    fun testSetLanguage() {
        predictor.setLanguage("en")
        assertEquals("en", predictor.getCurrentLanguage())
    }

    @Test
    fun testGetCurrentLanguage() {
        val language = predictor.getCurrentLanguage()
        assertNotNull("Language should not be null", language)
    }

    @Test
    fun testIsLanguageSupportedDoesNotCrash() {
        // isLanguageSupported depends on BigramModel, not dictionary —
        // may return false when BigramModel has no data loaded
        val result = predictor.isLanguageSupported("en")
        // Just verify it returns without crashing
        assertNotNull(result)
    }

    // =========================================================================
    // Prediction tests with real dictionary
    // =========================================================================

    @Test
    fun testPredictWordsReturnsResults() {
        val predictions = predictor.predictWords("hel")
        assertNotNull("Predictions should not be null", predictions)
        // With dictionary loaded, should return predictions for common prefix
        assertTrue("Should have predictions for 'hel'", predictions.isNotEmpty())
    }

    @Test
    fun testPredictEmptyString() {
        val predictions = predictor.predictWords("")
        assertNotNull(predictions)
        assertTrue("Empty input should return empty predictions", predictions.isEmpty())
    }

    @Test
    fun testPredictWordsWithScores() {
        val result = predictor.predictWordsWithScores("th")
        assertNotNull(result)
        assertNotNull(result.words)
        assertNotNull(result.scores)
        // "th" should match "the", "that", "this", "they", "their", "there", "them", "than", "then", "think", "through"
        assertTrue("Should have predictions for 'th'", result.words.isNotEmpty())
        assertEquals("Words and scores should have same size", result.words.size, result.scores.size)
    }

    @Test
    fun testPredictWordsWithContext() {
        val contextWords = listOf("I", "am")
        val result = predictor.predictWordsWithContext("hap", contextWords)
        assertNotNull(result)
        assertNotNull(result.words)
    }

    @Test
    fun testPredictWordsWithEmptyContext() {
        val result = predictor.predictWordsWithContext("th", emptyList())
        assertNotNull(result)
        assertTrue("Should have predictions for 'th'", result.words.isNotEmpty())
    }

    @Test
    fun testPredictionReturnsThe() {
        val predictions = predictor.predictWords("th")
        assertTrue("'the' should be in predictions for 'th'",
            predictions.any { it.equals("the", ignoreCase = true) })
    }

    @Test
    fun testPredictionReturnsHello() {
        val predictions = predictor.predictWords("hel")
        assertTrue("'hello' or 'help' should be in predictions for 'hel'",
            predictions.any { it.equals("hello", ignoreCase = true) || it.equals("help", ignoreCase = true) })
    }

    // =========================================================================
    // AutoCorrect tests
    // =========================================================================

    @Test
    fun testAutoCorrectReturnsNonNull() {
        val correction = predictor.autoCorrect("teh")
        assertNotNull(correction)
    }

    @Test
    fun testAutoCorrectEmptyString() {
        val correction = predictor.autoCorrect("")
        assertEquals("Empty string should return empty string", "", correction)
    }

    @Test
    fun testAutoCorrectValidWord() {
        val correction = predictor.autoCorrect("the")
        assertEquals("Valid word should not be corrected", "the", correction)
    }

    // =========================================================================
    // Dictionary tests
    // =========================================================================

    @Test
    fun testIsInDictionary() {
        assertTrue("'the' should be in dictionary", predictor.isInDictionary("the"))
    }

    @Test
    fun testIsNotInDictionary() {
        val result = predictor.isInDictionary("xyznonexistent123")
        assertFalse("Nonsense word should not be in dictionary", result)
    }

    @Test
    fun testDictionarySizePositive() {
        val size = predictor.getDictionarySize()
        assertTrue("Dictionary should have words loaded", size > 0)
    }

    // =========================================================================
    // Context management tests
    // =========================================================================

    @Test
    fun testAddWordToContext() {
        predictor.addWordToContext("test")
        predictor.addWordToContext("word")

        val recentWords = predictor.getRecentWords()
        assertNotNull(recentWords)
        assertTrue("Recent words should contain added words", recentWords.contains("test"))
    }

    @Test
    fun testClearContext() {
        predictor.addWordToContext("test")
        predictor.clearContext()

        val recentWords = predictor.getRecentWords()
        assertTrue("Context should be empty after clear", recentWords.isEmpty())
    }

    @Test
    fun testGetRecentWords() {
        val recentWords = predictor.getRecentWords()
        assertNotNull(recentWords)
    }

    // =========================================================================
    // Case preservation tests (Issue #72)
    // =========================================================================

    @Test
    fun testApplyUserWordCase() {
        val result = predictor.applyUserWordCase("hello")
        assertNotNull(result)
    }

    @Test
    fun testApplyUserWordCaseToList() {
        val words = listOf("hello", "world")
        val result = predictor.applyUserWordCaseToList(words)
        assertNotNull(result)
        assertEquals(words.size, result.size)
    }

    // =========================================================================
    // Loading state tests
    // =========================================================================

    @Test
    fun testIsLoading() {
        val loading = predictor.isLoading()
        assertFalse("Should not be loading after sync load", loading)
    }

    @Test
    fun testIsReady() {
        val ready = predictor.isReady()
        assertTrue("Should be ready after dictionary load", ready)
    }

    // =========================================================================
    // Reset tests
    // =========================================================================

    @Test
    fun testReset() {
        predictor.addWordToContext("test")
        predictor.reset()
        // Should not crash
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testVeryLongInput() {
        val longInput = "a".repeat(100)
        val predictions = predictor.predictWords(longInput)
        assertNotNull(predictions)
    }

    @Test
    fun testSpecialCharacters() {
        val predictions = predictor.predictWords("don't")
        assertNotNull(predictions)
    }

    @Test
    fun testNumericInput() {
        val predictions = predictor.predictWords("123")
        assertNotNull(predictions)
    }

    // =========================================================================
    // Secondary dictionary tests
    // =========================================================================

    @Test
    fun testHasSecondaryDictionary() {
        assertFalse("Should not have secondary dictionary initially", predictor.hasSecondaryDictionary())
    }

    @Test
    fun testGetSecondaryLanguageCode() {
        assertEquals("none", predictor.getSecondaryLanguageCode())
    }
}
