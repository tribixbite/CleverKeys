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
        // 2026-05-20: previously omitted — `WordPredictor.config` defaulted
        // to null, causing `autoCorrect` to short-circuit and return the
        // typed word unchanged. Existing assertions used `assertNotNull`
        // which masked the bug. Wire the global config in so corrections
        // actually run.
        predictor.setConfig(config)

        // 2026-05-21: lock down ALL autocorrect knobs so tests don't pick
        // up stale prefs state from prior runs / other test classes on the
        // emulator. The earlier failures of `wuestion → question` etc.
        // were caused in part by `autocorrect_max_length_diff` defaulting
        // to whatever the emulator had cached, letting unrelated longer
        // words compete on the frequency tiebreaker.
        config.autocorrect_enabled = true
        config.autocorrect_min_word_length = 2
        config.autocorrect_char_match_threshold = 0.65f
        config.autocorrect_max_length_diff = 2
        config.autocorrect_confidence_min_frequency = 100
        config.autocorrect_prefix_length = 0
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
    fun testAutocorrectFirstCharTypo_wuestionToQuestion() {
        // Reported 2026-05-20: "wuestion" should correct to "question" (w↔q
        // are adjacent on QWERTY). With prefix_length=0 (default since
        // 7463c9f61) the prefix-match guard must NOT block this — the bug
        // was that `WordPredictor.autoCorrect` hard-coded a 2-char prefix
        // check that ignored the config field, so any typo on the first
        // character was uncorrectable.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("wuestion")
        assertEquals("First-char typo must autocorrect with prefix_length=0",
            "question", result)
    }

    @Test
    fun testAutocorrectPrefixLength_two_enforcesPrefix() {
        // With prefix_length=2, "wuestion" must NOT correct to "question"
        // (no shared "wu" prefix). Verifies the config knob still works at
        // the legacy default.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 2
        val result = predictor.autoCorrect("wuestion")
        assertEquals("With prefix_length=2, first-char typo is NOT corrected",
            "wuestion", result)
    }

    // =========================================================================
    // Issue #101 — keyboard-adjacency-weighted scoring (KeyAdjacency module)
    // Reported 2026-05-20: user mistypes "tge"/"tfe" → "the" and "yiu" → "you"
    // and the keyboard never corrects them. Tier A.1+A.2 introduces physical-
    // adjacency-aware scoring so adjacent-key typos rank above distant ones.
    // =========================================================================

    @Test
    fun testAutocorrect_adjacencyTypo_tgeToThe() {
        // g and h are home-row-adjacent. Score for "tge" vs "the":
        //   t-t=1.0, g-h=~0.86, e-e=1.0 → sum 2.86 / 3 = 0.953
        // Comfortably above the 0.65 threshold.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("tge")
        assertEquals("tge → the (g/h adjacent)", "the", result)
    }

    @Test
    fun testAutocorrect_adjacencyTypo_tfeToThe() {
        // f and h are 2 home-row keys apart — slightly farther than g/h.
        // Score: t-t=1.0, f-h=~0.73, e-e=1.0 → sum 2.73 / 3 = 0.91
        // Still above threshold.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        val result = predictor.autoCorrect("tfe")
        assertEquals("tfe → the", "the", result)
    }

    // NOTE: removed `testAutocorrect_adjacencyTypo_yiuToYou` 2026-05-21
    // because `yiu` IS in the bundled English dictionary (Chinese surname),
    // so autoCorrect correctly skips it via the "already a valid word" guard.
    // The user's issue-101 comment used yiu as an illustrative example, not
    // a real reported case. The tge/tfe/wuestion cases below cover the same
    // adjacency-weighted-scoring behavior using genuine non-dict typos.

    @Test
    fun testAutocorrect_lengthDiffOne_insertion() {
        // "questin" missing the 'o' → "question". Length diff = 1, requires
        // autocorrect_max_length_diff >= 1.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("questin")
        assertEquals("questin → question (deletion typo)", "question", result)
    }

    @Test
    fun testAutocorrect_lengthDiffOne_extraChar() {
        // "quuestion" has duplicate 'u' → "question". Length diff = 1.
        config.autocorrect_enabled = true
        config.autocorrect_prefix_length = 0
        config.autocorrect_max_length_diff = 2
        val result = predictor.autoCorrect("quuestion")
        assertEquals("quuestion → question (extra-char typo)", "question", result)
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
