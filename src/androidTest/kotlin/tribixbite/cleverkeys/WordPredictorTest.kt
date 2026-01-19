package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WordPredictor.
 * Tests word prediction, autocomplete, and autocorrection functionality.
 * Note: Full dictionary loading is skipped to avoid OOM on emulator.
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
        // Note: Skipping loadDictionary to avoid OOM on cloud emulator
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
    fun testIsLanguageSupported() {
        assertTrue("English should be supported", predictor.isLanguageSupported("en"))
    }

    // =========================================================================
    // Prediction tests (without full dictionary)
    // =========================================================================

    @Test
    fun testPredictWordsReturnsNonNull() {
        val predictions = predictor.predictWords("hel")
        assertNotNull("Predictions should not be null", predictions)
    }

    @Test
    fun testPredictEmptyString() {
        val predictions = predictor.predictWords("")
        assertNotNull(predictions)
    }

    @Test
    fun testPredictWordsWithScores() {
        val result = predictor.predictWordsWithScores("th")
        assertNotNull(result)
    }

    @Test
    fun testPredictWordsWithContext() {
        val contextWords = listOf("I", "am")
        val result = predictor.predictWordsWithContext("hap", contextWords)
        assertNotNull(result)
    }

    @Test
    fun testPredictWordsWithEmptyContext() {
        val result = predictor.predictWordsWithContext("th", emptyList())
        assertNotNull(result)
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
        // Should not crash
    }

    // =========================================================================
    // Dictionary tests
    // =========================================================================

    @Test
    fun testIsInDictionary() {
        // Without dictionary loaded, should return false
        val result = predictor.isInDictionary("hello")
        // Just verify no crash
    }

    @Test
    fun testIsNotInDictionary() {
        val result = predictor.isInDictionary("xyznonexistent123")
        assertFalse("Nonsense word should not be in dictionary", result)
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
        // Should return boolean without crashing
    }

    @Test
    fun testIsReady() {
        val ready = predictor.isReady()
        // Should return boolean without crashing
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
}
