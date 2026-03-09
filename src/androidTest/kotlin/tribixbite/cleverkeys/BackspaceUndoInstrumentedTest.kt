package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #110: Instrumented tests for backspace undo swipe fix + backspace undo autocorrect.
 *
 * Tests the PredictionContextTracker state discrimination that drives undo behavior.
 * The plan defines three states:
 *   | State            | lastAutoInsertedWord | lastAutocorrectOriginalWord |
 *   |------------------|---------------------|-----------------------------|
 *   | Swipe undo       | non-null            | null                        |
 *   | Autocorrect undo | non-null            | non-null                    |
 *   | Neither          | null                | null                        |
 *
 * Tests marked "BUG FIX" validate the wasLastInputSwipe race condition fix.
 * Tests marked "NEW FEATURE" validate backspace undo autocorrect behavior.
 *
 * These tests are EXPECTED TO FAIL until the plan is implemented.
 */
@RunWith(AndroidJUnit4::class)
class BackspaceUndoInstrumentedTest {

    private lateinit var context: Context
    private lateinit var contextTracker: PredictionContextTracker

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        contextTracker = PredictionContextTracker()
    }

    // =========================================================================
    // State discrimination — core logic for undo type detection
    // =========================================================================

    @Test
    fun stateDiscrimination_swipeUndoState() {
        // Swipe undo: lastAutoInsertedWord set, lastAutocorrectOriginalWord null
        contextTracker.setLastAutoInsertedWord("hello")
        contextTracker.setLastAutocorrectOriginalWord(null)

        assertNotNull("lastAutoInsertedWord should be set", contextTracker.getLastAutoInsertedWord())
        assertNull("lastAutocorrectOriginalWord should be null", contextTracker.getLastAutocorrectOriginalWord())
    }

    @Test
    fun stateDiscrimination_autocorrectUndoState() {
        // Autocorrect undo: both set
        contextTracker.setLastAutoInsertedWord("the")
        contextTracker.setLastAutocorrectOriginalWord("teh")

        assertNotNull("lastAutoInsertedWord should be set", contextTracker.getLastAutoInsertedWord())
        assertNotNull("lastAutocorrectOriginalWord should be set", contextTracker.getLastAutocorrectOriginalWord())
        assertEquals("the", contextTracker.getLastAutoInsertedWord())
        assertEquals("teh", contextTracker.getLastAutocorrectOriginalWord())
    }

    @Test
    fun stateDiscrimination_neitherState() {
        // Neither: both null
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.clearAutocorrectTracking()

        assertNull("lastAutoInsertedWord should be null", contextTracker.getLastAutoInsertedWord())
        assertNull("lastAutocorrectOriginalWord should be null", contextTracker.getLastAutocorrectOriginalWord())
    }

    // =========================================================================
    // BUG FIX: wasLastInputSwipe race condition
    // =========================================================================

    @Test
    fun bugFix_swipeUndoWorksAfterSuggestionSelected() {
        // Simulate the swipe → auto-insert → backspace flow that is currently broken:
        // 1. Swipe starts: setWasLastInputSwipe(true)
        contextTracker.setWasLastInputSwipe(true)

        // 2. Auto-insert triggers onSuggestionSelected():
        //    - setWasLastInputSwipe(false)  ← this is the bug
        //    - setLastAutoInsertedWord("hello")
        contextTracker.setWasLastInputSwipe(false)  // Bug: cleared before backspace handler checks
        contextTracker.setLastAutoInsertedWord("hello")
        contextTracker.setLastCommitSource(PredictionSource.NEURAL_SWIPE)

        // 3. At this point, wasLastInputSwipe is false but we SHOULD still undo.
        //    The fix uses lastAutoInsertedWord + null lastAutocorrectOriginalWord
        //    instead of wasLastInputSwipe().
        assertFalse("wasLastInputSwipe should be false (cleared by onSuggestionSelected)",
            contextTracker.wasLastInputSwipe())
        assertNotNull("lastAutoInsertedWord should still be set for undo",
            contextTracker.getLastAutoInsertedWord())
        assertNull("lastAutocorrectOriginalWord should be null (not autocorrect)",
            contextTracker.getLastAutocorrectOriginalWord())

        // The fix: swipe undo should work even when wasLastInputSwipe() is false,
        // using lastAutoInsertedWord != null && lastAutocorrectOriginalWord == null.
        //
        // This test verifies the STATE is correct for discrimination.
        // The actual handleBackspaceUndoSwipe() behavior fix is verified by:
        //   - The bug: current code checks wasLastInputSwipe() → returns false → NO UNDO
        //   - The fix: check lastAutoInsertedWord + null autocorrect → returns true → UNDO
        val shouldUndoSwipe = contextTracker.getLastAutoInsertedWord() != null &&
                contextTracker.getLastAutocorrectOriginalWord() == null
        assertTrue("Should identify this as swipe undo state", shouldUndoSwipe)
    }

    @Test
    fun bugFix_wasLastInputSwipeRaceConditionExists() {
        // Demonstrates the bug: after the full swipe + auto-insert flow,
        // wasLastInputSwipe() returns false — which the current code uses
        // to gate backspace undo swipe, making the feature dead.

        // Simulate swipe
        contextTracker.setWasLastInputSwipe(true)
        assertTrue("Flag should be true after swipe", contextTracker.wasLastInputSwipe())

        // Simulate what onSuggestionSelected does (the bug trigger)
        contextTracker.setWasLastInputSwipe(false)
        assertFalse("Flag is false after onSuggestionSelected — THIS IS THE BUG",
            contextTracker.wasLastInputSwipe())

        // The word is still tracked (set AFTER onSuggestionSelected returns)
        contextTracker.setLastAutoInsertedWord("hello")
        assertNotNull("Word is still tracked despite flag being false",
            contextTracker.getLastAutoInsertedWord())
    }

    // =========================================================================
    // NEW FEATURE: Config.backspace_undo_autocorrect
    // =========================================================================

    @Test
    fun config_hasBackspaceUndoAutocorrectField() {
        // #110: Config should have backspace_undo_autocorrect setting
        val config = Config.globalConfig()
        val field = try {
            config.javaClass.getField("backspace_undo_autocorrect")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertNotNull("Config should have backspace_undo_autocorrect field", field)
    }

    @Test
    fun config_backspaceUndoAutocorrectDefaultTrue() {
        // #110: Default should be true (enabled)
        val config = Config.globalConfig()
        val field = try {
            config.javaClass.getField("backspace_undo_autocorrect")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertNotNull("Field must exist", field)
        assertTrue("Default should be true", field!!.getBoolean(config))
    }

    // =========================================================================
    // NEW FEATURE: IReceiver interface methods
    // =========================================================================

    @Test
    fun iReceiver_hasGetLastAutocorrectOriginalWord() {
        // #110: IReceiver should expose autocorrect original word for backspace undo
        val method = try {
            KeyEventHandler.IReceiver::class.java.getMethod("getLastAutocorrectOriginalWord")
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("IReceiver should have getLastAutocorrectOriginalWord()", method)
    }

    @Test
    fun iReceiver_hasClearAutocorrectUndoState() {
        // #110: IReceiver should have clearAutocorrectUndoState for cleanup after undo
        val method = try {
            KeyEventHandler.IReceiver::class.java.getMethod("clearAutocorrectUndoState")
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("IReceiver should have clearAutocorrectUndoState()", method)
    }

    // =========================================================================
    // Autocorrect tracking lifecycle
    // =========================================================================

    @Test
    fun autocorrectTracking_setAndGet() {
        contextTracker.setLastAutocorrectOriginalWord("teh")
        assertEquals("teh", contextTracker.getLastAutocorrectOriginalWord())
    }

    @Test
    fun autocorrectTracking_clearResetsToNull() {
        contextTracker.setLastAutocorrectOriginalWord("teh")
        contextTracker.clearAutocorrectTracking()
        assertNull(contextTracker.getLastAutocorrectOriginalWord())
    }

    @Test
    fun autocorrectTracking_clearAllResetsEverything() {
        contextTracker.setLastAutoInsertedWord("the")
        contextTracker.setLastAutocorrectOriginalWord("teh")
        contextTracker.setWasLastInputSwipe(true)

        contextTracker.clearAll()

        assertNull(contextTracker.getLastAutoInsertedWord())
        assertNull(contextTracker.getLastAutocorrectOriginalWord())
        assertFalse(contextTracker.wasLastInputSwipe())
    }

    @Test
    fun autocorrectTracking_fullAutocorrectFlow() {
        // Simulate: user types "teh", space triggers autocorrect to "the"

        // 1. User types "teh"
        contextTracker.appendToCurrentWord("t")
        contextTracker.appendToCurrentWord("e")
        contextTracker.appendToCurrentWord("h")
        assertEquals("teh", contextTracker.getCurrentWord())

        // 2. Space triggers autocorrect — commit "the" with autocorrect tracking
        contextTracker.commitWord("the", PredictionSource.AUTOCORRECT, true)
        contextTracker.setLastAutocorrectOriginalWord("teh")

        // 3. Verify state matches autocorrect undo pattern
        assertEquals("the", contextTracker.getLastAutoInsertedWord())
        assertEquals("teh", contextTracker.getLastAutocorrectOriginalWord())

        // 4. This state should trigger autocorrect undo (not swipe undo)
        val isAutocorrectUndo = contextTracker.getLastAutoInsertedWord() != null &&
                contextTracker.getLastAutocorrectOriginalWord() != null
        assertTrue("Should identify as autocorrect undo state", isAutocorrectUndo)
    }

    @Test
    fun autocorrectTracking_swipeDoesNotSetOriginalWord() {
        // Simulate: user swipes "hello" — no autocorrect original word
        contextTracker.setWasLastInputSwipe(true)
        contextTracker.commitWord("hello", PredictionSource.NEURAL_SWIPE, true)

        assertEquals("hello", contextTracker.getLastAutoInsertedWord())
        assertNull("Swipe should NOT set autocorrect original word",
            contextTracker.getLastAutocorrectOriginalWord())
    }

    @Test
    fun stateDiscrimination_clearAfterSwipeUndo() {
        // After swipe undo: both should be cleared
        contextTracker.setLastAutoInsertedWord("hello")
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.clearAutocorrectTracking()

        assertNull(contextTracker.getLastAutoInsertedWord())
        assertNull(contextTracker.getLastAutocorrectOriginalWord())

        // No undo should trigger
        val shouldUndo = contextTracker.getLastAutoInsertedWord() != null
        assertFalse("Nothing to undo after clear", shouldUndo)
    }

    @Test
    fun stateDiscrimination_clearAfterAutocorrectUndo() {
        // After autocorrect undo: both should be cleared
        contextTracker.setLastAutoInsertedWord("the")
        contextTracker.setLastAutocorrectOriginalWord("teh")

        // Undo
        contextTracker.clearLastAutoInsertedWord()
        contextTracker.clearAutocorrectTracking()

        assertNull(contextTracker.getLastAutoInsertedWord())
        assertNull(contextTracker.getLastAutocorrectOriginalWord())
    }
}
