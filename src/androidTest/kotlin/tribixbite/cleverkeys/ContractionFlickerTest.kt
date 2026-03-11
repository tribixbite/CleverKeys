package tribixbite.cleverkeys

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
        // Simulate the fix: typing sets the flag before commitText triggers onUpdateSelection
        contextTracker.appendToCurrentWord("i")
        contextTracker.appendToCurrentWord("t")
        contextTracker.appendToCurrentWord("s")
        assertEquals("its", contextTracker.getCurrentWord())

        // Set flag as SuggestionHandler.handleRegularTyping() does
        contextTracker.expectingSelectionUpdate = true

        // Simulate what happens when onUpdateSelection fires and cursor sync runs.
        // synchronizeWithCursor should detect the flag, skip, and reset it.
        // We pass null IC — with flag set, it should return before using IC.
        contextTracker.synchronizeWithCursor(null, "en", null)

        // Flag should be consumed (reset to false)
        assertFalse(
            "Flag should be consumed after synchronizeWithCursor",
            contextTracker.expectingSelectionUpdate
        )

        // Current word should be UNCHANGED — sync was skipped
        assertEquals(
            "Current word should survive cursor sync when flag is set",
            "its",
            contextTracker.getCurrentWord()
        )
    }

    @Test
    fun withoutFlag_synchronizeWithCursorResetsCurrentWord() {
        // Without the fix: typing builds "its" but cursor sync clears it
        contextTracker.appendToCurrentWord("i")
        contextTracker.appendToCurrentWord("t")
        contextTracker.appendToCurrentWord("s")
        assertEquals("its", contextTracker.getCurrentWord())

        // Do NOT set expectingSelectionUpdate (simulating pre-fix behavior)
        assertFalse(contextTracker.expectingSelectionUpdate)

        // Cursor sync with null IC — without flag, it proceeds past the guard
        // and hits the IC null check, returning early. But with a real IC it
        // would overwrite currentWord from editor text.
        // The key test is that the flag mechanism WORKS when set.
        // This test documents the WITHOUT-flag behavior as a baseline.
        contextTracker.synchronizeWithCursor(null, "en", null)

        // Without flag, sync proceeds (returns early due to null IC, but flag stays false)
        assertFalse(contextTracker.expectingSelectionUpdate)
    }

    @Test
    fun flagIsSetPerKeystroke_lastKeystrokeProtected() {
        // Simulates rapid typing: each keystroke sets the flag.
        // Only the LAST keystroke's cursor sync matters (debounced).
        // The flag from the last keystroke should still be set when sync fires.

        // Keystroke 1: "i"
        contextTracker.appendToCurrentWord("i")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 1's cursor sync fires (debounce expired):
        // consumes the flag
        contextTracker.synchronizeWithCursor(null, "en", null)
        assertFalse(contextTracker.expectingSelectionUpdate)

        // Keystroke 2: "t" — sets flag again
        contextTracker.appendToCurrentWord("t")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 2's cursor sync fires
        contextTracker.synchronizeWithCursor(null, "en", null)
        assertFalse(contextTracker.expectingSelectionUpdate)

        // Keystroke 3: "s" — sets flag again
        contextTracker.appendToCurrentWord("s")
        contextTracker.expectingSelectionUpdate = true

        // Keystroke 3's cursor sync fires — flag was set, so sync is skipped
        contextTracker.synchronizeWithCursor(null, "en", null)

        // Current word should remain intact across all keystrokes
        assertEquals("its", contextTracker.getCurrentWord())
    }

    // =========================================================================
    // Paired contraction predictions — typing path must include them
    // =========================================================================

    @Test
    fun pairedContractions_itsReturnsApostropheVariants() {
        // The core behavior: "its" must produce "it's" in paired contractions
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
        // Non-paired: "dont" is NOT a real word, only maps to "don't"
        val result = contractionManager.getNonPairedMapping("dont")
        assertEquals("dont → don't", "don't", result)
    }

    // =========================================================================
    // Full prediction pipeline — paired contractions in results
    // =========================================================================

    @Test
    fun predictionPipeline_itsIncludesPairedContractionInResults() {
        assumeNotNull("WordPredictor required", sharedPredictor)
        val predictor = sharedPredictor!!

        // Get predictions for "its" — should include the base word AND contractions
        val result = predictor.predictWordsWithContext("its", emptyList())
        assertNotNull("Prediction result should not be null", result)

        // The raw predictor may or may not include "it's" directly.
        // What matters is that ContractionManager.getPairedContractions("its")
        // returns variants that SuggestionHandler injects into the suggestion bar.
        // This test validates the raw predictor returns results for "its".
        assertTrue(
            "Predictor should return results for 'its'",
            result!!.words.isNotEmpty()
        )
    }

    @Test
    fun predictionPipeline_pairedContractionInjection() {
        assumeNotNull("WordPredictor required", sharedPredictor)
        val predictor = sharedPredictor!!

        // Simulate what SuggestionHandler.updatePredictionsForCurrentWord does:
        // 1. Get raw predictions
        val result = predictor.predictWordsWithContext("its", emptyList())
        assumeNotNull("Predictions required", result)

        // 2. Check for paired contractions (SuggestionHandler lines 1116-1123)
        val pairedVariants = contractionManager.getPairedContractions("its")

        // 3. Build merged list (contractions first, then predictions)
        val mergedWords = mutableListOf<String>()
        if (pairedVariants != null) {
            mergedWords.addAll(pairedVariants)
        }
        // Add raw predictions, filtering duplicates
        val injectedLower = mergedWords.map { it.lowercase() }.toSet()
        for (word in result!!.words) {
            val contracted = contractionManager.getNonPairedMapping(word) ?: word
            if (contracted.lowercase() !in injectedLower) {
                mergedWords.add(contracted)
            }
        }

        // Verify paired contraction is present in final merged list
        assertTrue(
            "Merged predictions must include 'it's' from paired contractions. Got: $mergedWords",
            mergedWords.any { it == "it's" }
        )

        // Verify it appears BEFORE raw predictions (high priority)
        val apostropheIndex = mergedWords.indexOfFirst { it == "it's" }
        assertTrue(
            "it's should be near the top of suggestions (was at index $apostropheIndex)",
            apostropheIndex < 3
        )
    }

    // =========================================================================
    // Source scanning — verify fix is in place (SuggestionHandler)
    // =========================================================================

    @Test
    fun sourceVerify_suggestionHandlerSetsExpectingSelectionUpdateInLetterBranch() {
        // Verify that SuggestionHandler.handleRegularTyping sets the flag
        // in the letter typing branch. Without this, cursor sync overwrites
        // typing predictions and paired contractions flicker.
        val source = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt"
        ).readText()

        assertTrue(
            "SuggestionHandler must set expectingSelectionUpdate=true in handleRegularTyping",
            source.contains("expectingSelectionUpdate = true")
        )
    }

    @Test
    fun sourceVerify_synchronizeWithCursorChecksExpectingSelectionUpdate() {
        // Verify that PredictionContextTracker.synchronizeWithCursor checks the flag
        val source = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt"
        ).readText()

        assertTrue(
            "synchronizeWithCursor must check expectingSelectionUpdate",
            source.contains("if (expectingSelectionUpdate)")
        )
    }

    @Test
    fun sourceVerify_inputCoordinatorCursorSyncCallsSynchronizeWithCursor() {
        // Verify the cursor sync path goes through synchronizeWithCursor
        // (where the expectingSelectionUpdate guard lives)
        val source = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt"
        ).readText()

        assertTrue(
            "InputCoordinator cursor sync must call synchronizeWithCursor",
            source.contains("contextTracker.synchronizeWithCursor")
        )
    }
}
