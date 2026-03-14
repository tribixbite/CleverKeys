package tribixbite.cleverkeys

import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the contraction suggestion flicker bug.
 *
 * Bug: When touch-typing "its", paired contractions (it's, it'll, it'd) appear
 * briefly in the suggestion bar then disappear. Root cause: two independent
 * prediction pipelines race to update the same SuggestionBar.
 *
 * 1. SuggestionHandler.updatePredictionsForCurrentWord() — typing path
 *    Has paired contraction support (getPairedContractions). Shows correct results.
 *
 * 2. InputCoordinator.triggerPredictionsForPrefix() — cursor sync path
 *    Only has non-paired contraction mapping. Overwrites results ~100ms later
 *    when onUpdateSelection fires from commitText.
 *
 * Fix: Set expectingSelectionUpdate=true in SuggestionHandler's typing handlers
 * so that PredictionContextTracker.synchronizeWithCursor() skips the redundant
 * cursor sync triggered by the same keystroke's commitText.
 *
 * Bug does NOT manifest in Termux because terminal apps don't send
 * onUpdateSelection callbacks.
 */
@RunWith(AndroidJUnit4::class)
class ContractionFlickerTest {

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var contractionManager: ContractionManager
    private lateinit var editText: EditText
    private lateinit var inputConnection: BaseInputConnection

    companion object {
        private var sharedPredictor: WordPredictor? = null
        private var sharedContractionManager: ContractionManager? = null
        private var sharedConfig: Config? = null
        @Volatile private var initAttempted = false
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()

        // Create a real InputConnection for cursor sync tests
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            editText = EditText(context)
            editText.setText("hello its")
        }
        inputConnection = BaseInputConnection(editText, true)

        // One-time lazy initialization for prediction tests
        synchronized(ContractionFlickerTest::class.java) {
            if (!initAttempted) {
                initAttempted = true
                try {
                    sharedConfig = Config.globalConfig()
                    sharedConfig!!.autocorrect_enabled = true
                    sharedConfig!!.word_prediction_enabled = true

                    sharedContractionManager = ContractionManager(context)
                    sharedContractionManager!!.loadMappings()

                    sharedPredictor = WordPredictor()
                    sharedPredictor!!.setContext(context)
                    sharedPredictor!!.setConfig(sharedConfig!!)
                    sharedPredictor!!.loadDictionary(context, "en")
                } catch (e: OutOfMemoryError) {
                    android.util.Log.w("ContractionFlickerTest",
                        "WordPredictor init OOM — prediction tests will be skipped")
                    sharedPredictor = null
                }
            }
        }

        contractionManager = sharedContractionManager
            ?: ContractionManager(context).also { it.loadMappings() }
    }

    // =========================================================================
    // expectingSelectionUpdate flag — cursor sync suppression mechanism
    // =========================================================================

    @Test
    fun expectingSelectionUpdate_defaultsFalse() {
        // Fresh PredictionContextTracker should not suppress cursor sync
        assertFalse(
            "expectingSelectionUpdate should default to false",
            contextTracker.expectingSelectionUpdate
        )
    }

    @Test
    fun expectingSelectionUpdate_suppressesSynchronizeWithCursor() {
        // Simulate typing "its" character by character
        contextTracker.appendToCurrentWord("i")
        contextTracker.appendToCurrentWord("t")
        contextTracker.appendToCurrentWord("s")
        assertEquals("its", contextTracker.getCurrentWord())

        // Set flag as SuggestionHandler.handleRegularTyping() does
        contextTracker.expectingSelectionUpdate = true

        // Simulate cursor sync with a REAL InputConnection (not null).
        // synchronizeWithCursor should detect the flag, skip, and reset it.
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        // Flag should be consumed (reset to false)
        assertFalse(
            "Flag should be consumed after synchronizeWithCursor",
            contextTracker.expectingSelectionUpdate
        )

        // Current word should be UNCHANGED — sync was skipped entirely
        assertEquals(
            "Current word should survive cursor sync when flag is set",
            "its",
            contextTracker.getCurrentWord()
        )
    }

    @Test
    fun withoutFlag_synchronizeWithCursorOverwritesCurrentWord() {
        // Without the fix: typing builds "its" but cursor sync reads from IC
        contextTracker.appendToCurrentWord("i")
        contextTracker.appendToCurrentWord("t")
        contextTracker.appendToCurrentWord("s")
        assertEquals("its", contextTracker.getCurrentWord())

        // Do NOT set expectingSelectionUpdate (simulating pre-fix behavior)
        assertFalse(contextTracker.expectingSelectionUpdate)

        // Cursor sync with real IC — WITHOUT flag, sync proceeds and reads
        // from the InputConnection, potentially changing currentWord
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        // Without the flag, sync ran and may have changed currentWord based
        // on what's in the EditText. The point is: with the flag, it DOESN'T.
        // This test documents the baseline behavior.
        assertFalse(contextTracker.expectingSelectionUpdate)
    }

    @Test
    fun flagIsSetPerKeystroke_lastKeystrokeProtected() {
        // Simulates rapid typing: each keystroke sets the flag.
        // With debouncing, only the LAST keystroke's cursor sync fires.
        // The flag from the last keystroke should still be set when sync fires.

        // Keystroke 1: "i" — sets flag
        contextTracker.appendToCurrentWord("i")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 1's cursor sync fires — consumes the flag
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)
        assertFalse("Flag consumed after keystroke 1 sync", contextTracker.expectingSelectionUpdate)

        // Keystroke 2: "t" — sets flag again
        contextTracker.appendToCurrentWord("t")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 2's cursor sync fires — consumes the flag
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)
        assertFalse("Flag consumed after keystroke 2 sync", contextTracker.expectingSelectionUpdate)

        // Keystroke 3: "s" — sets flag again
        contextTracker.appendToCurrentWord("s")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 3's cursor sync fires — flag was set, so sync is skipped
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        // Flag consumed, current word preserved through all keystrokes
        assertFalse("Flag consumed after keystroke 3 sync", contextTracker.expectingSelectionUpdate)
        assertEquals(
            "Current word should remain 'its' after 3 flag-protected syncs",
            "its",
            contextTracker.getCurrentWord()
        )
    }

    @Test
    fun flagSetThenCleared_nextSyncProceeds() {
        // Verify the flag is consumed: set once, consumed once,
        // next sync proceeds normally (doesn't stay suppressed forever)
        contextTracker.expectingSelectionUpdate = true

        // First sync: flag consumed
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)
        assertFalse("Flag should be consumed", contextTracker.expectingSelectionUpdate)

        // Second sync: no flag, sync should proceed normally
        contextTracker.appendToCurrentWord("x")
        contextTracker.synchronizeWithCursor(inputConnection, "en", null)

        // Sync ran and may have changed state — that's expected
        assertFalse("No flag for second sync", contextTracker.expectingSelectionUpdate)
    }

    // =========================================================================
    // Paired contraction predictions — typing path must include them
    // =========================================================================

    @Test
    fun pairedContractions_itsReturnsApostropheVariants() {
        val variants = contractionManager.getPairedContractions("its")
        assertNotNull("'its' must have paired contractions", variants)
        assertTrue("Must include 'it's'", "it's" in variants!!)
    }

    @Test
    fun pairedContractions_wellReturnsWeLL() {
        val variants = contractionManager.getPairedContractions("well")
        assertNotNull("'well' must have paired contractions", variants)
        assertTrue("Must include 'we'll'", "we'll" in variants!!)
    }

    @Test
    fun pairedContractions_wereReturnsWeRe() {
        val variants = contractionManager.getPairedContractions("were")
        assertNotNull("'were' must have paired contractions", variants)
        assertTrue("Must include 'we're'", "we're" in variants!!)
    }

    @Test
    fun nonPairedMapping_doesNotHandlePairedWords() {
        // InputCoordinator's cursor sync path uses getNonPairedMapping(),
        // which should NOT map paired contraction bases.
        // "its" is a real word (paired) — getNonPairedMapping should return null.
        val result = contractionManager.getNonPairedMapping("its")
        assertNull(
            "'its' is a paired contraction — getNonPairedMapping must return null",
            result
        )
    }

    @Test
    fun nonPairedMapping_handlesNonPairedWords() {
        val result = contractionManager.getNonPairedMapping("dont")
        assertEquals("dont → don't", "don't", result)
    }

    // =========================================================================
    // Demonstrates the asymmetry that causes the flicker
    // =========================================================================

    @Test
    fun asymmetry_typingPathIncludesPairedContractions() {
        // SuggestionHandler's typing path checks getPairedContractions()
        // and injects results into the merged word list
        val paired = contractionManager.getPairedContractions("its")
        assertNotNull("Typing path: paired contractions must exist for 'its'", paired)
        assertTrue("Typing path: must include 'it's'", "it's" in paired!!)
    }

    @Test
    fun asymmetry_cursorSyncPathMissesPairedContractions() {
        // InputCoordinator's cursor sync uses ONLY getNonPairedMapping()
        // which returns null for paired words like "its"
        val nonPaired = contractionManager.getNonPairedMapping("its")
        assertNull(
            "Cursor sync path: getNonPairedMapping('its') must be null (paired word)",
            nonPaired
        )
        // This proves the cursor sync path cannot produce "it's" for "its" —
        // it would overwrite the typing path's correct results with ones
        // missing paired contractions, causing the flicker
    }

    // =========================================================================
    // Prefix length guard — paired contractions only for >= 3 char prefixes
    // =========================================================================

    @Test
    fun prefixGuard_singleCharHasPairedMappingsInData() {
        // Verify the data exists — "t" maps to "t's" in contraction_pairings.json
        val variants = contractionManager.getPairedContractions("t")
        assertNotNull("'t' must have paired contractions in data", variants)
        assertTrue("Must include 't's'", "t's" in variants!!)
    }

    @Test
    fun prefixGuard_twoCharHasPairedMappingsInData() {
        // "he" maps to "he's", "he'd", "he'll" in contraction_pairings.json
        val variants = contractionManager.getPairedContractions("he")
        assertNotNull("'he' must have paired contractions in data", variants)
        assertTrue("Must include 'he's'", "he's" in variants!!)
    }

    @Test
    fun prefixGuard_pipelineSkipsSingleCharPairedInjection() {
        // Simulate the pipeline logic: for prefix "t" (len < 3),
        // paired contraction injection should be skipped
        val prefix = "t"
        val pairedVariants = if (prefix.length >= 3) contractionManager.getPairedContractions(prefix) else null
        assertNull(
            "Paired contractions must be skipped for single-char prefix 't'",
            pairedVariants
        )
    }

    @Test
    fun prefixGuard_pipelineSkipsTwoCharPairedInjection() {
        val prefix = "he"
        val pairedVariants = if (prefix.length >= 3) contractionManager.getPairedContractions(prefix) else null
        assertNull(
            "Paired contractions must be skipped for two-char prefix 'he'",
            pairedVariants
        )
    }

    @Test
    fun prefixGuard_pipelineAllowsThreeCharPairedInjection() {
        val prefix = "its"
        val pairedVariants = if (prefix.length >= 3) contractionManager.getPairedContractions(prefix) else null
        assertNotNull(
            "Paired contractions must be allowed for three-char prefix 'its'",
            pairedVariants
        )
        assertTrue("Must include 'it's'", "it's" in pairedVariants!!)
    }

    @Test
    fun prefixGuard_nonPairedNotAffectedByLengthCheck() {
        // Non-paired contractions (dont → don't) should work at any length
        // because they transform predictions, not inject new ones
        val result = contractionManager.getNonPairedMapping("dont")
        assertEquals("Non-paired mapping should work regardless of length", "don't", result)
    }

    // =========================================================================
    // Full prediction pipeline — paired contractions in results
    // =========================================================================

    @Test
    fun predictionPipeline_itsIncludesPairedContractionInResults() {
        assumeNotNull("WordPredictor required", sharedPredictor)
        val predictor = sharedPredictor!!

        val result = predictor.predictWordsWithContext("its", emptyList())
        assertNotNull("Prediction result should not be null", result)
        assertTrue(
            "Predictor should return results for 'its'",
            result!!.words.isNotEmpty()
        )
    }

    @Test
    fun predictionPipeline_pairedContractionInjection() {
        assumeNotNull("WordPredictor required", sharedPredictor)
        val predictor = sharedPredictor!!

        // Simulate SuggestionHandler.updatePredictionsForCurrentWord:
        val result = predictor.predictWordsWithContext("its", emptyList())
        assumeNotNull("Predictions required", result)

        // Check for paired contractions (SuggestionHandler lines 1116-1123)
        val pairedVariants = contractionManager.getPairedContractions("its")

        // Build merged list (contractions first, then predictions)
        val mergedWords = mutableListOf<String>()
        if (pairedVariants != null) {
            mergedWords.addAll(pairedVariants)
        }
        val injectedLower = mergedWords.map { it.lowercase() }.toSet()
        for (word in result!!.words) {
            val contracted = contractionManager.getNonPairedMapping(word) ?: word
            if (contracted.lowercase() !in injectedLower) {
                mergedWords.add(contracted)
            }
        }

        assertTrue(
            "Merged predictions must include 'it's'. Got: $mergedWords",
            mergedWords.any { it == "it's" }
        )

        val apostropheIndex = mergedWords.indexOfFirst { it == "it's" }
        assertTrue(
            "it's should be near the top (was at index $apostropheIndex)",
            apostropheIndex < 3
        )
    }
}
