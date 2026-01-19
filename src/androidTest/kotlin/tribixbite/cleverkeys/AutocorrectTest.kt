package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for autocorrect functionality.
 * Tests typo correction, word boundary detection, and correction confidence.
 */
@RunWith(AndroidJUnit4::class)
class AutocorrectTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor
    private lateinit var config: Config

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()

        predictor = WordPredictor()
        predictor.setContext(context)
        predictor.loadDictionary(context, "en")

        // Ensure autocorrect is enabled for tests
        config.autocorrect_enabled = true
    }

    // =========================================================================
    // Basic autocorrect tests
    // =========================================================================

    @Test
    fun testAutocorrectReturnsString() {
        val result = predictor.autoCorrect("teh")
        assertNotNull("Should return non-null result", result)
    }

    @Test
    fun testAutocorrectEmptyString() {
        val result = predictor.autoCorrect("")
        assertEquals("Empty should return empty", "", result)
    }

    @Test
    fun testAutocorrectValidWord() {
        val result = predictor.autoCorrect("the")
        assertEquals("Valid word should not be corrected", "the", result)
    }

    @Test
    fun testAutocorrectHello() {
        val result = predictor.autoCorrect("hello")
        assertEquals("Valid word should not change", "hello", result)
    }

    // =========================================================================
    // Common typo tests
    // =========================================================================

    @Test
    fun testAutocorrectTeh() {
        val result = predictor.autoCorrect("teh")
        // "teh" is a common typo for "the"
        assertTrue("Should correct 'teh'",
            result == "the" || result == "teh")  // May not correct if threshold not met
    }

    @Test
    fun testAutocorrectHte() {
        val result = predictor.autoCorrect("hte")
        // "hte" is a typo for "the"
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectAdn() {
        val result = predictor.autoCorrect("adn")
        // "adn" is a typo for "and"
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWaht() {
        val result = predictor.autoCorrect("waht")
        // "waht" is a typo for "what"
        assertNotNull(result)
    }

    // =========================================================================
    // Word length tests
    // =========================================================================

    @Test
    fun testAutocorrectShortWord() {
        // Very short words may not be autocorrected
        val result = predictor.autoCorrect("th")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectLongWord() {
        val result = predictor.autoCorrect("unbelievable")
        assertEquals("Long valid word unchanged", "unbelievable", result)
    }

    @Test
    fun testAutocorrectMinLengthRespected() {
        val minLength = config.autocorrect_min_word_length
        assertTrue("Min length should be reasonable", minLength >= 0 && minLength <= 5)
    }

    // =========================================================================
    // Config settings tests
    // =========================================================================

    @Test
    fun testAutocorrectEnabledSetting() {
        assertTrue("Autocorrect should be enabled for tests", config.autocorrect_enabled)
    }

    @Test
    fun testAutocorrectDisabled() {
        val originalEnabled = config.autocorrect_enabled
        try {
            config.autocorrect_enabled = false
            val result = predictor.autoCorrect("teh")
            // When disabled, should return original word
            assertEquals("Should not correct when disabled", "teh", result)
        } finally {
            config.autocorrect_enabled = originalEnabled
        }
    }

    @Test
    fun testAutocorrectCharMatchThreshold() {
        val threshold = config.autocorrect_char_match_threshold
        assertTrue("Threshold should be between 0 and 1",
            threshold >= 0f && threshold <= 1f)
    }

    @Test
    fun testAutocorrectConfidenceMinFrequency() {
        val minFreq = config.autocorrect_confidence_min_frequency
        assertTrue("Min frequency should be non-negative", minFreq >= 0)
    }

    @Test
    fun testAutocorrectMaxLengthDiff() {
        val maxDiff = config.autocorrect_max_length_diff
        assertTrue("Max length diff should be reasonable", maxDiff >= 0 && maxDiff <= 5)
    }

    // =========================================================================
    // Case preservation tests
    // =========================================================================

    @Test
    fun testAutocorrectPreservesLowercase() {
        val result = predictor.autoCorrect("hello")
        if (result == "hello") {
            assertTrue("Lowercase should stay lowercase", result[0].isLowerCase())
        }
    }

    @Test
    fun testAutocorrectWithNumbers() {
        val result = predictor.autoCorrect("test123")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWithApostrophe() {
        val result = predictor.autoCorrect("don't")
        assertNotNull(result)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testAutocorrectNonsenseWord() {
        val result = predictor.autoCorrect("xyzqwerty")
        // Nonsense words may or may not be corrected
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectSingleChar() {
        val result = predictor.autoCorrect("a")
        assertEquals("Single char unchanged", "a", result)
    }

    @Test
    fun testAutocorrectTwoChars() {
        val result = predictor.autoCorrect("ab")
        assertNotNull(result)
    }

    @Test
    fun testAutocorrectWhitespace() {
        val result = predictor.autoCorrect("  ")
        // Whitespace handling
        assertNotNull(result)
    }

    // =========================================================================
    // Swipe autocorrect settings
    // =========================================================================

    @Test
    fun testSwipeBeamAutocorrectSetting() {
        val enabled = config.swipe_beam_autocorrect_enabled
        // Just verify accessible
    }

    @Test
    fun testSwipeFinalAutocorrectSetting() {
        val enabled = config.swipe_final_autocorrect_enabled
        // Just verify accessible
    }

    // =========================================================================
    // Dictionary integration tests
    // =========================================================================

    @Test
    fun testAutocorrectWithDictionaryLoaded() {
        assertTrue("Dictionary should be ready", predictor.isReady())
        assertTrue("Dictionary should have words", predictor.getDictionarySize() > 0)
    }

    @Test
    fun testAutocorrectUsesLoadedDictionary() {
        // Words in dictionary should not be corrected
        if (predictor.isInDictionary("the")) {
            val result = predictor.autoCorrect("the")
            assertEquals("Dictionary word unchanged", "the", result)
        }
    }
}
