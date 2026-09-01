package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented tests for WordPredictor.
 * Tests word prediction, autocomplete, and autocorrection functionality.
 * Uses TestDictionaryHelper for a small test dictionary to avoid OOM.
 *
 * ARC-044: strengthened from liveness to behavior. The incident this class must
 * not repeat: autoCorrect() short-circuits to the input whenever setConfig() was
 * never called (config == null → `config?.autocorrect_enabled != true`), so the
 * old assertions were green without ever exercising autocorrect. setup() now
 * calls setConfig() with autocorrect explicitly enabled (restored in teardown),
 * and the dictionary-injection precondition is asserted so a silent fallback to
 * the full binary dictionary fails loudly instead of skewing every expectation.
 */
@RunWith(AndroidJUnit4::class)
class WordPredictorTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor
    private lateinit var config: Config
    private var savedAutocorrectEnabled = false

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()

        predictor = WordPredictor()
        predictor.setContext(context)

        // AutocorrectTest incident guard: WITHOUT setConfig, autoCorrect()
        // short-circuits and every autocorrect assertion passes vacuously.
        // Force the gate on deterministically and restore it in teardown.
        savedAutocorrectEnabled = config.autocorrect_enabled
        config.autocorrect_enabled = true
        predictor.setConfig(config)

        // Inject small test dictionary via reflection to avoid OOM from
        // loading full en_enhanced.bin (1.3MB file → ~150MB in-memory HashMap + prefix index)
        injectTestDictionary()
    }

    @After
    fun teardown() {
        config.autocorrect_enabled = savedAutocorrectEnabled
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
        // Harness precondition (incident guard): the reflective injection must
        // have engaged. A silent fallback to the full dictionary would make
        // every prefix/size expectation below meaningless.
        assertEquals(
            "Injected test dictionary must be the active dictionary",
            TestDictionaryHelper.getTestWords().size, predictor.getDictionarySize()
        )
        assertTrue("Predictor must be ready after injection", predictor.isReady())
    }

    @Test
    fun testSetContext() {
        // Must be idempotent — production calls it on every service rebind
        predictor.setContext(context)
        assertTrue("Predictor must remain ready after re-setting context", predictor.isReady())
    }

    @Test
    fun testSetLanguage() {
        predictor.setLanguage("en")
        assertEquals("en", predictor.getCurrentLanguage())
    }

    @Test
    fun testGetCurrentLanguage() {
        val language = predictor.getCurrentLanguage()
        assertTrue("Language must be a non-blank code", language.isNotBlank())
        assertEquals(
            "Language must be stable across reads",
            language, predictor.getCurrentLanguage()
        )
    }

    @Test
    fun testIsLanguageSupportedDoesNotCrash() {
        // isLanguageSupported depends on BigramModel, not dictionary —
        // may return false when BigramModel has no data loaded
        val result = predictor.isLanguageSupported("en")
        assertEquals(
            "isLanguageSupported must be deterministic for the same input",
            result, predictor.isLanguageSupported("en")
        )
        assertFalse(
            "A nonsense language code must never be supported",
            predictor.isLanguageSupported("zz-nonexistent")
        )
    }

    // =========================================================================
    // Prediction tests with real dictionary
    // =========================================================================

    @Test
    fun testPredictWordsReturnsResults() {
        val predictions = predictor.predictWords("hel")
        assertTrue("Should have predictions for 'hel'", predictions.isNotEmpty())
        // Candidates come from the strict prefix index: every result must
        // actually extend the typed prefix.
        predictions.forEach {
            assertTrue(
                "Prediction '$it' must start with the typed prefix 'hel'",
                it.lowercase().startsWith("hel")
            )
        }
        assertTrue(
            "'hello' must be predicted for 'hel' (it is in the test dictionary)",
            predictions.any { it.equals("hello", ignoreCase = true) }
        )
    }

    @Test
    fun testPredictEmptyString() {
        val predictions = predictor.predictWords("")
        assertTrue("Empty input should return empty predictions", predictions.isEmpty())
    }

    @Test
    fun testPredictWordsWithScores() {
        val result = predictor.predictWordsWithScores("th")
        // "th" should match "the", "that", "this", "they", "their", "there"
        assertTrue("Should have predictions for 'th'", result.words.isNotEmpty())
        assertEquals("Words and scores should have same size", result.words.size, result.scores.size)
        assertTrue(
            "'the' must be among the 'th' predictions",
            result.words.any { it.equals("the", ignoreCase = true) }
        )
        result.words.forEach {
            assertTrue(
                "Prediction '$it' must start with the typed prefix 'th'",
                it.lowercase().startsWith("th")
            )
        }
        assertEquals(
            "Predictions must not contain duplicates",
            result.words.size, result.words.map { it.lowercase() }.toSet().size
        )
    }

    @Test
    fun testPredictWordsWithContext() {
        val contextWords = listOf("I", "am")
        val result = predictor.predictWordsWithContext("hap", contextWords)
        assertTrue("Should have predictions for 'hap'", result.words.isNotEmpty())
        assertTrue(
            "'happy' must be predicted for 'hap' (only test word with that prefix)",
            result.words.any { it.equals("happy", ignoreCase = true) }
        )
        assertEquals(
            "Words and scores must stay parallel with context applied",
            result.words.size, result.scores.size
        )
    }

    @Test
    fun testPredictWordsWithEmptyContext() {
        val result = predictor.predictWordsWithContext("th", emptyList())
        assertTrue("Should have predictions for 'th'", result.words.isNotEmpty())
        assertTrue(
            "'the' must be predicted for 'th' with empty context",
            result.words.any { it.equals("the", ignoreCase = true) }
        )
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
    // AutoCorrect tests (config gate is ON — see setup)
    // =========================================================================

    @Test
    fun testAutoCorrectInDictionaryTypoIsKept() {
        // "teh" IS in the test dictionary (freq 100), so the in-dictionary
        // short-circuit must return it untouched — autocorrect never rewrites
        // a word the dictionary knows, no matter how typo-like it looks.
        val correction = predictor.autoCorrect("teh")
        assertEquals(
            "In-dictionary word must not be corrected even if typo-like",
            "teh", correction
        )
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

    @Test
    fun testAutoCorrectReturnsInputOrKnownWord() {
        // Structural invariant of the sweep: the output is either the typed
        // word itself or a word the predictor can vouch for. (Exact winner for
        // "helo" depends on adjacency constants — not pinned here.)
        val correction = predictor.autoCorrect("helo")
        assertTrue("Correction must never be blank", correction.isNotBlank())
        assertTrue(
            "autoCorrect must return the input or a dictionary word, got '$correction'",
            correction == "helo" || predictor.isInDictionary(correction)
        )
    }

    // =========================================================================
    // Dictionary tests
    // =========================================================================

    @Test
    fun testIsInDictionary() {
        assertTrue("'the' should be in dictionary", predictor.isInDictionary("the"))
        assertTrue(
            "isInDictionary must be case-insensitive",
            predictor.isInDictionary("THE")
        )
    }

    @Test
    fun testIsNotInDictionary() {
        assertFalse(
            "Nonsense word should not be in dictionary",
            predictor.isInDictionary("xyznonexistent123")
        )
        assertFalse("Empty word must not be in dictionary", predictor.isInDictionary(""))
    }

    @Test
    fun testDictionarySizePositive() {
        assertEquals(
            "Dictionary size must equal the injected test word count",
            TestDictionaryHelper.getTestWords().size, predictor.getDictionarySize()
        )
    }

    // =========================================================================
    // Context management tests
    // =========================================================================

    @Test
    fun testAddWordToContext() {
        predictor.addWordToContext("test")
        predictor.addWordToContext("word")

        val recentWords = predictor.getRecentWords()
        assertEquals("Exactly the two added words must be in context", 2, recentWords.size)
        assertTrue("Recent words should contain 'test'", recentWords.contains("test"))
        assertTrue("Recent words should contain 'word'", recentWords.contains("word"))
    }

    @Test
    fun testClearContext() {
        predictor.addWordToContext("test")
        assertTrue(
            "Context must contain the word before clearing",
            predictor.getRecentWords().contains("test")
        )
        predictor.clearContext()

        val recentWords = predictor.getRecentWords()
        assertTrue("Context should be empty after clear", recentWords.isEmpty())
    }

    @Test
    fun testGetRecentWords() {
        // A freshly constructed predictor has an empty context window
        assertTrue(
            "Fresh predictor must have no recent words",
            predictor.getRecentWords().isEmpty()
        )
    }

    // =========================================================================
    // Case preservation tests (Issue #72)
    // =========================================================================

    @Test
    fun testApplyUserWordCase() {
        // No user dictionary entries exist, so the word must pass through verbatim
        assertEquals(
            "Without a user-case entry the word must be unchanged",
            "hello", predictor.applyUserWordCase("hello")
        )
    }

    @Test
    fun testApplyUserWordCaseToList() {
        val words = listOf("hello", "world")
        val result = predictor.applyUserWordCaseToList(words)
        assertEquals(
            "Without user-case entries the list must pass through verbatim",
            words, result
        )
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
        // reset() is documented as keeping the dictionary loaded; it does NOT
        // clear the recent-words window (clearContext does that).
        predictor.addWordToContext("test")
        predictor.reset()
        assertTrue("Reset must keep the predictor ready", predictor.isReady())
        assertTrue(
            "Reset must not clear the recent-words context (that is clearContext's job)",
            predictor.getRecentWords().contains("test")
        )
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun testVeryLongInput() {
        // No test word starts with "aaa", so the prefix index yields nothing
        val longInput = "a".repeat(100)
        val predictions = predictor.predictWords(longInput)
        assertTrue(
            "100-char nonsense input must produce no predictions",
            predictions.isEmpty()
        )
    }

    @Test
    fun testSpecialCharacters() {
        // No test word starts with "don" — the apostrophe input must not crash
        // and must not invent candidates outside the dictionary
        val predictions = predictor.predictWords("don't")
        predictions.forEach {
            assertTrue(
                "Any prediction for \"don't\" must extend the typed prefix",
                it.lowercase().startsWith("don")
            )
        }
    }

    @Test
    fun testNumericInput() {
        // No dictionary word starts with a digit
        val predictions = predictor.predictWords("123")
        assertTrue("Numeric input must produce no predictions", predictions.isEmpty())
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
