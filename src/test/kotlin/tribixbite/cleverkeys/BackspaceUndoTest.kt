package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #110: Pure JVM tests for backspace undo swipe fix + backspace undo autocorrect feature.
 *
 * These tests validate the expected API surface AFTER implementation.
 * They are EXPECTED TO FAIL until the plan is implemented:
 *   - Fix: Remove wasLastInputSwipe() check from handleBackspaceUndoSwipe()
 *   - Feature: Add handleBackspaceUndoAutocorrect() + Config.backspace_undo_autocorrect
 *
 * State discrimination table (from plan):
 *   | State            | lastAutoInsertedWord | lastAutocorrectOriginalWord |
 *   |------------------|---------------------|-----------------------------|
 *   | Swipe undo       | non-null            | null                        |
 *   | Autocorrect undo | non-null            | non-null                    |
 *   | Neither          | null                | null                        |
 *
 * Note: KeyEventHandler and IReceiver tests are in BackspaceUndoInstrumentedTest
 * because they depend on Android framework classes not available on pure JVM.
 */
class BackspaceUndoTest {

    // =========================================================================
    // Config.Defaults — backspace_undo_autocorrect should exist
    // =========================================================================

    @Test
    fun `BACKSPACE_UNDO_AUTOCORRECT default exists and is true`() {
        // #110: New default constant for autocorrect undo via backspace
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
        // Existing default — should remain true
        assertThat(Defaults.BACKSPACE_UNDO_SWIPE).isTrue()
    }

    // =========================================================================
    // Config field — backspace_undo_autocorrect should exist on the class
    // =========================================================================

    @Test
    fun `Config has backspace_undo_autocorrect field`() {
        // #110: Config should expose backspace_undo_autocorrect setting
        val field = try {
            Config::class.java.getField("backspace_undo_autocorrect")
        } catch (e: NoSuchFieldException) {
            null
        }
        assertThat(field).isNotNull()
    }
}
