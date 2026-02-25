package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full typing simulation tests — validates the complete prediction pipeline end-to-end.
 *
 * These tests exercise the real production code paths that run when a user types
 * on the keyboard, catching composition bugs that unit tests miss. Each test
 * simulates a user scenario by driving WordPredictor, ContractionManager, and
 * the DictionaryDataSource stack with real dictionaries and SharedPreferences.
 *
 * Covers:
 * - Tap-typing predictions (prefix completion with contractions)
 * - Autocorrect on space (contraction expansion, typo correction)
 * - Custom word override of disabled dictionary entries
 * - Dictionary toggle UI cache coherence
 * - Paired contraction injection (its → it's alongside "its")
 * - I-contraction capitalization (im → I'm, ill → I'll)
 *
 * Runs on emulator.wtf via: ew-cli --app app.apk --test test.apk ...
 */
@RunWith(AndroidJUnit4::class)
class TypingSimulationTest {

    private lateinit var context: Context
    private lateinit var predictor: WordPredictor
    private lateinit var contractionManager: ContractionManager
    private lateinit var config: Config
    private lateinit var prefs: SharedPreferences

    // Test-specific SharedPreferences keys to clean up
    private val testCustomWords = mutableListOf<String>()
    private val testDisabledWords = mutableListOf<String>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        config = Config.globalConfig()

        // Initialize ContractionManager with real binary/JSON data
        contractionManager = ContractionManager(context)
        contractionManager.loadMappings()

        // Initialize WordPredictor with full production dictionary
        predictor = WordPredictor()
        predictor.setContext(context)
        predictor.loadDictionary(context, "en")

        // Ensure autocorrect is enabled
        config.autocorrect_enabled = true

        // SharedPreferences for custom word / disabled word manipulation
        prefs = DirectBootAwarePreferences.get_shared_preferences(context)
    }

    @After
    fun cleanup() {
        // Remove any custom words added during tests
        if (testCustomWords.isNotEmpty()) {
            val key = LanguagePreferenceKeys.customWordsKey("en")
            val json = prefs.getString(key, "{}") ?: "{}"
            try {
                val obj = org.json.JSONObject(json)
                for (word in testCustomWords) {
                    obj.remove(word)
                }
                prefs.edit().putString(key, obj.toString()).apply()
            } catch (_: Exception) {}
        }

        // Remove any disabled words added during tests
        if (testDisabledWords.isNotEmpty()) {
            val key = LanguagePreferenceKeys.disabledWordsKey("en")
            val disabled = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            disabled.removeAll(testDisabledWords.toSet())
            prefs.edit().putStringSet(key, disabled).apply()
        }
    }

    // =========================================================================
    // Contraction Manager — Paired Contractions (Bug 22)
    // =========================================================================

    @Test
    fun pairedContractionLookupReturnsVariants() {
        // "its" is a paired contraction base → should return ["it's"]
        val variants = contractionManager.getPairedContractions("its")
        assertNotNull("'its' should have paired contractions", variants)
        assertTrue("Should include 'it's'", variants!!.any { it == "it's" })
    }

    @Test
    fun pairedContractionLookupForWell() {
        val variants = contractionManager.getPairedContractions("well")
        assertNotNull("'well' should have paired contractions", variants)
        assertTrue("Should include 'we'll'", variants!!.any { it == "we'll" })
    }

    @Test
    fun pairedContractionLookupForWere() {
        val variants = contractionManager.getPairedContractions("were")
        assertNotNull("'were' should have paired contractions", variants)
        assertTrue("Should include 'we're'", variants!!.any { it == "we're" })
    }

    @Test
    fun pairedContractionLookupForHell() {
        val variants = contractionManager.getPairedContractions("hell")
        assertNotNull("'hell' should have paired contractions", variants)
        assertTrue("Should include 'he'll'", variants!!.any { it == "he'll" })
    }

    @Test
    fun nonPairedWordReturnsNullForPaired() {
        // "hello" is NOT a paired contraction base
        val variants = contractionManager.getPairedContractions("hello")
        assertNull("'hello' should not have paired contractions", variants)
    }

    @Test
    fun pairedContractionIsCaseInsensitive() {
        val lower = contractionManager.getPairedContractions("its")
        val upper = contractionManager.getPairedContractions("ITS")
        val mixed = contractionManager.getPairedContractions("Its")
        assertEquals("Case should not affect result", lower, upper)
        assertEquals("Case should not affect result", lower, mixed)
    }

    // =========================================================================
    // Non-Paired Contractions (existing, regression tests)
    // =========================================================================

    @Test
    fun nonPairedContractionMappingDont() {
        val result = contractionManager.getNonPairedMapping("dont")
        assertEquals("dont → don't", "don't", result)
    }

    @Test
    fun nonPairedContractionMappingCant() {
        val result = contractionManager.getNonPairedMapping("cant")
        assertEquals("cant → can't", "can't", result)
    }

    @Test
    fun nonPairedContractionMappingIm() {
        val result = contractionManager.getNonPairedMapping("im")
        assertEquals("im → i'm", "i'm", result)
    }

    @Test
    fun nonPairedContractionMappingWont() {
        val result = contractionManager.getNonPairedMapping("wont")
        assertEquals("wont → won't", "won't", result)
    }

    // =========================================================================
    // Autocorrect — Contraction Expansion (Bug 23)
    // =========================================================================

    @Test
    fun autocorrectExpandsImToIm() {
        val result = predictor.autoCorrect("im")
        assertEquals("'im' should autocorrect to 'I'm'", "I'm", result)
    }

    @Test
    fun autocorrectExpandsDontToContraction() {
        val result = predictor.autoCorrect("dont")
        assertEquals("'dont' should autocorrect to 'don't'", "don't", result)
    }

    @Test
    fun autocorrectExpandsCantToContraction() {
        val result = predictor.autoCorrect("cant")
        assertEquals("'cant' should autocorrect to 'can't'", "can't", result)
    }

    @Test
    fun autocorrectExpandsWontToContraction() {
        val result = predictor.autoCorrect("wont")
        assertEquals("'wont' should autocorrect to 'won't'", "won't", result)
    }

    @Test
    fun autocorrectPreservesIllAsValidWord() {
        val result = predictor.autoCorrect("ill")
        // "ill" is a valid English word (sick) AND a paired contraction base (I'll)
        // Since "ill" is in paired contractions (not non-paired), it's NOT in
        // contractionAliases, so autocorrect should preserve it as-is
        assertEquals("'ill' is a valid word, should not autocorrect", "ill", result)
    }

    @Test
    fun autocorrectPreservesCapitalizationForContractions() {
        // Title case: "Dont" → "Don't"
        val result = predictor.autoCorrect("Dont")
        assertEquals("'Dont' should autocorrect to 'Don't'", "Don't", result)
    }

    @Test
    fun autocorrectPreservesAllCapsForContractions() {
        // All caps: "DONT" → "DON'T"
        val result = predictor.autoCorrect("DONT")
        assertEquals("'DONT' should autocorrect to 'DON'T'", "DON'T", result)
    }

    @Test
    fun autocorrectDoesNotChangeValidWords() {
        // "hello" is a real word — should not be autocorrected
        val result = predictor.autoCorrect("hello")
        assertEquals("Valid word should not change", "hello", result)
    }

    @Test
    fun autocorrectDoesNotChangeValidWordThe() {
        val result = predictor.autoCorrect("the")
        assertEquals("'the' should not change", "the", result)
    }

    @Test
    fun autocorrectDoesNotChangeWellToContraction() {
        // "well" is a common English word AND in the contractions file as "we'll"
        // Autocorrect should NOT change it because "well" pre-existed in the dictionary
        val result = predictor.autoCorrect("well")
        assertEquals("'well' is a real word, should not autocorrect to 'we'll'", "well", result)
    }

    @Test
    fun autocorrectDoesNotChangeWereToContraction() {
        // "were" is a common English word AND in the contractions file as "we're"
        val result = predictor.autoCorrect("were")
        assertEquals("'were' is a real word, should not autocorrect to 'we're'", "were", result)
    }

    // =========================================================================
    // Tap-Typing Predictions — Prefix Completions
    // =========================================================================

    @Test
    fun tapTypingProducesPredictions() {
        val result = predictor.predictWordsWithContext("hel", emptyList())
        assertNotNull("Should produce prediction result", result)
        assertTrue("Should have predictions", result.words.isNotEmpty())
    }

    @Test
    fun tapTypingPrefixMatchesWord() {
        val result = predictor.predictWordsWithContext("the", emptyList())
        assertNotNull(result)
        // "the" should appear in predictions (exact match or prefix match)
        val hasThe = result.words.any { it.equals("the", ignoreCase = true) }
        assertTrue("'the' should appear in predictions for prefix 'the'", hasThe)
    }

    @Test
    fun tapTypingReturnsMultiplePredictions() {
        val result = predictor.predictWordsWithContext("th", emptyList())
        assertNotNull(result)
        assertTrue("Should return multiple predictions for common prefix", result.words.size >= 2)
    }

    // =========================================================================
    // Custom Word Override (Bug 21 — Boston bug)
    // =========================================================================

    @Test
    fun customWordOverridesDisabledWord() {
        val testWord = "xyztest"
        val testWordCapital = "Xyztest"

        // 1. Add to dictionary as disabled
        disableWord(testWord)

        // 2. Add as custom word with original case
        addCustomWord(testWordCapital, 1000)

        // 3. Reload predictor to pick up changes
        predictor.reloadDisabledWords()
        predictor.reloadCustomAndUserWords()

        // 4. Verify word is NOT disabled (custom overrides disabled)
        val result = predictor.predictWordsWithContext(testWord.substring(0, 3), emptyList())
        // The custom word should be findable via isInDictionary
        val isInDict = predictor.isInDictionary(testWord)
        assertTrue("Custom word should be in dictionary even if base is disabled", isInDict)
    }

    @Test
    fun disabledWordWithoutCustomStaysDisabled() {
        val testWord = "xyzonly"

        // Disable without adding as custom
        disableWord(testWord)
        predictor.reloadDisabledWords()

        // Should not appear in predictions
        val result = predictor.predictWordsWithContext("xyz", emptyList())
        val hasWord = result?.words?.any { it.equals(testWord, ignoreCase = true) } ?: false
        assertFalse("Disabled word without custom override should not appear", hasWord)
    }

    // =========================================================================
    // Dictionary Toggle Cache Coherence (Bug 4/20)
    // =========================================================================

    @Test
    fun dictionaryToggleCacheUpdatesInPlace() {
        // Test that MainDictionarySource.toggleWord() updates cached entries
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val mainSource = MainDictionarySource(context, disabledSource, "en")

        kotlinx.coroutines.runBlocking {
            // Load dictionary (populates cache)
            val allWords = mainSource.getAllWords()
            assertTrue("Should have words loaded", allWords.isNotEmpty())

            // Find a word that is currently enabled
            val targetWord = allWords.first { it.enabled }
            val wordText = targetWord.word

            // Toggle it off
            mainSource.toggleWord(wordText, false)

            // Re-fetch without reloading — cache should be updated
            val afterToggle = mainSource.getAllWords()
            val toggledWord = afterToggle.find { it.word == wordText }
            assertNotNull("Word should still exist in list", toggledWord)
            assertFalse("Word should be disabled after toggle", toggledWord!!.enabled)

            // Toggle back on
            mainSource.toggleWord(wordText, true)

            val afterRestore = mainSource.getAllWords()
            val restoredWord = afterRestore.find { it.word == wordText }
            assertTrue("Word should be re-enabled after toggle", restoredWord!!.enabled)
        }
    }

    @Test
    fun dictionaryToggleSearchAlsoReflectsChange() {
        val disabledSource = DisabledDictionarySource(prefs, "en")
        val mainSource = MainDictionarySource(context, disabledSource, "en")

        kotlinx.coroutines.runBlocking {
            val allWords = mainSource.getAllWords()
            val targetWord = allWords.first { it.enabled && it.word.length >= 3 }
            val searchPrefix = targetWord.word.substring(0, 3)

            // Toggle off
            mainSource.toggleWord(targetWord.word, false)

            // Search should reflect updated state (uses same cached objects via prefixIndex)
            val searchResults = mainSource.searchWords(searchPrefix)
            val found = searchResults.find { it.word == targetWord.word }
            if (found != null) {
                assertFalse("Search result should show disabled state", found.enabled)
            }

            // Restore
            mainSource.toggleWord(targetWord.word, true)
        }
    }

    // =========================================================================
    // I-Contraction Capitalization
    // =========================================================================

    @Test
    fun iContractionCapitalization() {
        // I-contractions should auto-capitalize the I
        assertEquals("I'm", predictor.autoCorrect("im"))
    }

    @Test
    fun iContractionIllIsPreserved() {
        // "ill" is a paired contraction (valid word), so NOT autocorrected
        assertEquals("ill", predictor.autoCorrect("ill"))
    }

    @Test
    fun iContractionId() {
        // "id" might be a valid word (identification), check if contraction alias exists
        val result = predictor.autoCorrect("id")
        // If "id" is a contraction alias → "I'd", otherwise stays "id"
        // This test documents the current behavior
        assertTrue("Should be 'I'd' or 'id'", result == "I'd" || result == "id")
    }

    // =========================================================================
    // End-to-End Typing Scenarios
    // =========================================================================

    @Test
    fun scenarioTypingCommonSentence() {
        // Simulate: "i dont think its right"
        // After autocorrect on each word:
        // "i" → stays "i" (or "I" if capitalization enabled)
        // "dont" → "don't"
        // "think" → "think" (valid word)
        // "its" → "its" (valid word — NOT autocorrected, but "it's" should be in predictions)
        // "right" → "right"

        val dontResult = predictor.autoCorrect("dont")
        assertEquals("don't", dontResult)

        val thinkResult = predictor.autoCorrect("think")
        assertEquals("think", thinkResult)

        val itsResult = predictor.autoCorrect("its")
        // "its" is a valid word — should NOT be autocorrected
        // (it's a paired contraction, both forms are valid)
        assertEquals("its", itsResult)
    }

    @Test
    fun scenarioTypingContractionHeavySentence() {
        // "im cant wont dont" → each should autocorrect
        assertEquals("I'm", predictor.autoCorrect("im"))
        assertEquals("can't", predictor.autoCorrect("cant"))
        assertEquals("won't", predictor.autoCorrect("wont"))
        assertEquals("don't", predictor.autoCorrect("dont"))
    }

    @Test
    fun scenarioTypingWithUserWordCase() {
        // Add "Boston" as custom word, verify case preservation
        addCustomWord("Boston", 5000)
        predictor.reloadCustomAndUserWords()

        val applied = predictor.applyUserWordCase("boston")
        assertEquals("Should restore case to 'Boston'", "Boston", applied)
    }

    // =========================================================================
    // Prediction Pipeline Integration
    // =========================================================================

    @Test
    fun predictionPipelineReturnsScores() {
        val result = predictor.predictWordsWithContext("hel", emptyList())
        assertNotNull(result)
        assertTrue("Predictions should have scores", result.scores.isNotEmpty())
        assertEquals("Words and scores should have same length",
            result.words.size, result.scores.size)
    }

    @Test
    fun predictionPipelineScoresDescending() {
        val result = predictor.predictWordsWithContext("th", emptyList())
        assertNotNull(result)
        if (result.scores.size >= 2) {
            for (i in 0 until result.scores.size - 1) {
                assertTrue("Scores should be descending",
                    result.scores[i] >= result.scores[i + 1])
            }
        }
    }

    @Test
    fun predictionPipelineEmptyInputReturnsEmpty() {
        val result = predictor.predictWordsWithContext("", emptyList())
        assertTrue("Empty input should return empty or null predictions",
            result == null || result.words.isEmpty())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun addCustomWord(word: String, frequency: Int) {
        val key = LanguagePreferenceKeys.customWordsKey("en")
        val json = prefs.getString(key, "{}") ?: "{}"
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
        obj.put(word, frequency)
        prefs.edit().putString(key, obj.toString()).apply()
        testCustomWords.add(word) // Track for cleanup
    }

    private fun disableWord(word: String) {
        val key = LanguagePreferenceKeys.disabledWordsKey("en")
        val disabled = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabled.add(word.lowercase())
        prefs.edit().putStringSet(key, disabled).apply()
        testDisabledWords.add(word.lowercase()) // Track for cleanup
    }
}
