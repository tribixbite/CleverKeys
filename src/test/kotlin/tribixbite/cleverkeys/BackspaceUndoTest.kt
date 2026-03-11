package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * #110: Pure JVM tests for backspace undo swipe fix + backspace undo autocorrect feature.
 *
 * State discrimination table:
 *   | State            | lastAutoInsertedWord | lastAutocorrectOriginalWord |
 *   |------------------|---------------------|-----------------------------|
 *   | Swipe undo       | non-null            | null                        |
 *   | Autocorrect undo | non-null            | non-null                    |
 *   | Neither          | null                | null                        |
 *
 * Tests are split into two groups:
 * 1. PredictionContextTracker tests — pure JVM (no Android deps)
 * 2. Source-scanning tests — verify override/delegation presence in files that can't
 *    be loaded on JVM (depend on android.content.Context, InputConnection, etc.)
 *
 * Full integration tests are in BackspaceUndoInstrumentedTest.
 */
class BackspaceUndoTest {

    // =========================================================================
    // Config.Defaults — backspace_undo_autocorrect should exist
    // =========================================================================

    @Test
    fun `BACKSPACE_UNDO_AUTOCORRECT default exists and is true`() {
        val field = try {
            Defaults::class.java.getField("BACKSPACE_UNDO_AUTOCORRECT")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertThat(field).isNotNull()
        assertThat(field!!.get(null)).isEqualTo(true)
    }

    @Test
    fun `BACKSPACE_UNDO_SWIPE default exists and is true`() {
        assertThat(Defaults.BACKSPACE_UNDO_SWIPE).isTrue()
    }

    // =========================================================================
    // Config field — backspace_undo_autocorrect should exist on the class
    // =========================================================================

    @Test
    fun `Config has backspace_undo_autocorrect field`() {
        val field = try {
            Config::class.java.getField("backspace_undo_autocorrect")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertThat(field).isNotNull()
    }

    // =========================================================================
    // PredictionContextTracker — state discrimination logic (pure JVM)
    // =========================================================================

    @Test
    fun `swipe state - lastAutoInsertedWord set, autocorrectOriginal null`() {
        val tracker = PredictionContextTracker()
        tracker.setLastAutoInsertedWord("hello")
        tracker.setWasLastInputSwipe(true)

        assertThat(tracker.getLastAutoInsertedWord()).isEqualTo("hello")
        assertThat(tracker.getLastAutocorrectOriginalWord()).isNull()
    }

    @Test
    fun `autocorrect state - both lastAutoInsertedWord and autocorrectOriginal set`() {
        val tracker = PredictionContextTracker()
        tracker.setLastAutoInsertedWord("the")
        tracker.setLastAutocorrectOriginalWord("teh")

        assertThat(tracker.getLastAutoInsertedWord()).isEqualTo("the")
        assertThat(tracker.getLastAutocorrectOriginalWord()).isEqualTo("teh")
    }

    @Test
    fun `neither state - both null after clear`() {
        val tracker = PredictionContextTracker()
        tracker.setLastAutoInsertedWord("hello")
        tracker.setLastAutocorrectOriginalWord("helo")
        tracker.clearLastAutoInsertedWord()
        tracker.clearAutocorrectTracking()

        assertThat(tracker.getLastAutoInsertedWord()).isNull()
        assertThat(tracker.getLastAutocorrectOriginalWord()).isNull()
    }

    @Test
    fun `clearAll resets all backspace undo state`() {
        val tracker = PredictionContextTracker()
        tracker.setLastAutoInsertedWord("the")
        tracker.setLastAutocorrectOriginalWord("teh")
        tracker.setWasLastInputSwipe(true)
        tracker.clearAll()

        assertThat(tracker.getLastAutoInsertedWord()).isNull()
        assertThat(tracker.getLastAutocorrectOriginalWord()).isNull()
        assertThat(tracker.wasLastInputSwipe()).isFalse()
    }

    @Test
    fun `swipe flag cleared does not affect lastAutoInsertedWord`() {
        // The original bug: wasLastInputSwipe is cleared by onSuggestionSelected,
        // but lastAutoInsertedWord survives — this is what the fix relies on.
        val tracker = PredictionContextTracker()
        tracker.setWasLastInputSwipe(true)
        tracker.setLastAutoInsertedWord("hello")
        // Simulate what onSuggestionSelected does:
        tracker.setWasLastInputSwipe(false)

        assertThat(tracker.getLastAutoInsertedWord()).isEqualTo("hello")
        assertThat(tracker.wasLastInputSwipe()).isFalse()
    }

    // =========================================================================
    // Source-scanning tests — verify IReceiver, KeyboardReceiver, and
    // KeyEventReceiverBridge all declare the required backspace undo methods.
    // These classes can't be loaded on pure JVM (android.content.Context dep).
    // =========================================================================

    // --- IReceiver interface must declare the methods ---

    @Test
    fun `IReceiver declares getLastAutoInsertedWord`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("fun getLastAutoInsertedWord(): String?")
    }

    @Test
    fun `IReceiver declares getLastAutocorrectOriginalWord`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("fun getLastAutocorrectOriginalWord(): String?")
    }

    @Test
    fun `IReceiver declares clearAutocorrectUndoState`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("fun clearAutocorrectUndoState()")
    }

    @Test
    fun `IReceiver declares clearSwipeUndoState`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("fun clearSwipeUndoState()")
    }

    @Test
    fun `IReceiver declares wasLastInputSwipe`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("fun wasLastInputSwipe(): Boolean")
    }

    // --- KeyboardReceiver must override all backspace undo methods ---

    @Test
    fun `KeyboardReceiver overrides getLastAutoInsertedWord`() {
        val source = readSource("KeyboardReceiver.kt")
        assertThat(source).contains("override fun getLastAutoInsertedWord()")
    }

    @Test
    fun `KeyboardReceiver overrides wasLastInputSwipe`() {
        val source = readSource("KeyboardReceiver.kt")
        assertThat(source).contains("override fun wasLastInputSwipe()")
    }

    @Test
    fun `KeyboardReceiver overrides clearSwipeUndoState`() {
        val source = readSource("KeyboardReceiver.kt")
        assertThat(source).contains("override fun clearSwipeUndoState()")
    }

    @Test
    fun `KeyboardReceiver overrides getLastAutocorrectOriginalWord`() {
        val source = readSource("KeyboardReceiver.kt")
        assertThat(source).contains("override fun getLastAutocorrectOriginalWord()")
    }

    @Test
    fun `KeyboardReceiver overrides clearAutocorrectUndoState`() {
        val source = readSource("KeyboardReceiver.kt")
        assertThat(source).contains("override fun clearAutocorrectUndoState()")
    }

    // --- KeyEventReceiverBridge must delegate ALL backspace undo methods ---
    // This is the exact production bug: KeyEventHandler uses the bridge (not
    // KeyboardReceiver directly), but bridge doesn't override IReceiver defaults.
    // Result: getLastAutoInsertedWord() returns null, wasLastInputSwipe() returns
    // false, and both undo features are dead code at runtime.

    @Test
    fun `KeyEventReceiverBridge delegates getLastAutoInsertedWord`() {
        val source = readSource("KeyEventReceiverBridge.kt")
        assertThat(source).contains("override fun getLastAutoInsertedWord()")
    }

    @Test
    fun `KeyEventReceiverBridge delegates wasLastInputSwipe`() {
        val source = readSource("KeyEventReceiverBridge.kt")
        assertThat(source).contains("override fun wasLastInputSwipe()")
    }

    @Test
    fun `KeyEventReceiverBridge delegates clearSwipeUndoState`() {
        val source = readSource("KeyEventReceiverBridge.kt")
        assertThat(source).contains("override fun clearSwipeUndoState()")
    }

    @Test
    fun `KeyEventReceiverBridge delegates getLastAutocorrectOriginalWord`() {
        val source = readSource("KeyEventReceiverBridge.kt")
        assertThat(source).contains("override fun getLastAutocorrectOriginalWord()")
    }

    @Test
    fun `KeyEventReceiverBridge delegates clearAutocorrectUndoState`() {
        val source = readSource("KeyEventReceiverBridge.kt")
        assertThat(source).contains("override fun clearAutocorrectUndoState()")
    }

    // --- handleBackspaceUndoSwipe must NOT check wasLastInputSwipe (#110 bug fix) ---

    @Test
    fun `handleBackspaceUndoSwipe does not check wasLastInputSwipe`() {
        // The root cause of the swipe undo bug: wasLastInputSwipe() is checked
        // but always false because onSuggestionSelected clears it first.
        val source = readSource("KeyEventHandler.kt")
        val methodStart = source.indexOf("private fun handleBackspaceUndoSwipe()")
        assertThat(methodStart).isGreaterThan(-1)
        val methodEnd = source.indexOf("private fun handleBackspaceUndoAutocorrect()")
        assertThat(methodEnd).isGreaterThan(methodStart)
        val methodBody = source.substring(methodStart, methodEnd)
        // Must not call recv.wasLastInputSwipe() as executable code (comments are ok)
        val codeLines = methodBody.lines().filter { !it.trimStart().startsWith("//") }
        val codeOnly = codeLines.joinToString("\n")
        assertThat(codeOnly).doesNotContain("wasLastInputSwipe()")
    }

    // --- handleBackspaceUndoAutocorrect must exist ---

    @Test
    fun `handleBackspaceUndoAutocorrect method exists`() {
        val source = readSource("KeyEventHandler.kt")
        assertThat(source).contains("private fun handleBackspaceUndoAutocorrect(): Boolean")
    }

    // --- Backspace chain must include both undo handlers ---

    @Test
    fun `backspace chain includes swipe undo then autocorrect undo`() {
        val source = readSource("KeyEventHandler.kt")
        val swipeUndoPos = source.indexOf("handleBackspaceUndoSwipe()")
        val autocorrectUndoPos = source.indexOf("handleBackspaceUndoAutocorrect()")
        // Both must exist in backspace chain
        assertThat(swipeUndoPos).isGreaterThan(-1)
        assertThat(autocorrectUndoPos).isGreaterThan(-1)
        // Swipe undo must come BEFORE autocorrect undo in the chain
        assertThat(swipeUndoPos).isLessThan(autocorrectUndoPos)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    // =========================================================================
    // Contraction flicker fix — verify expectingSelectionUpdate suppression
    // =========================================================================

    @Test
    fun `SuggestionHandler sets expectingSelectionUpdate in letter typing branch`() {
        val source = readSource("SuggestionHandler.kt")
        // The fix: SuggestionHandler.handleRegularTyping must set the flag
        // to suppress cursor sync that would overwrite contraction predictions
        assertThat(source).contains("expectingSelectionUpdate = true")
    }

    @Test
    fun `SuggestionHandler sets expectingSelectionUpdate in handleBackspace`() {
        val source = readSource("SuggestionHandler.kt")
        // Count occurrences: should appear in letter branch, non-letter branch, and backspace
        val count = "expectingSelectionUpdate = true".toRegex()
            .findAll(source).count()
        assertThat(count).isAtLeast(3) // letter, non-letter, backspace
    }

    @Test
    fun `synchronizeWithCursor checks expectingSelectionUpdate flag`() {
        val source = readSource("PredictionContextTracker.kt")
        assertThat(source).contains("if (expectingSelectionUpdate)")
    }

    @Test
    fun `InputCoordinator cursor sync calls synchronizeWithCursor`() {
        val source = readSource("InputCoordinator.kt")
        assertThat(source).contains("contextTracker.synchronizeWithCursor")
    }

    /** Read a source file from the main source tree */
    private fun readSource(filename: String): String {
        val projectDir = System.getProperty("user.dir") ?: "."
        val file = File(projectDir, "src/main/kotlin/tribixbite/cleverkeys/$filename")
        assertThat(file.exists()).isTrue()
        return file.readText()
    }
}
