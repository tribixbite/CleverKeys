package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The two-tap undo state behind the tappable "Added “x” to dictionary" confirmation
 * ([UndoableBarMessage]; the bar renders it, `DictionaryAddUndoTest` drives the handler side).
 */
class UndoableBarMessageTest {

    private fun message() = UndoableBarMessage("Added “git” to dictionary", "Tap again to remove “git”") {}

    @Test
    fun theFirstTapArmsWithTheConfirmText() {
        val m = message()
        val result = m.onTap()

        assertThat(result).isEqualTo(
            UndoableBarMessage.TapResult.Confirm("Tap again to remove “git”", UndoableBarMessage.CONFIRM_DURATION_MS)
        )
        assertThat(m.phase).isEqualTo(UndoableBarMessage.Phase.CONFIRMING)
    }

    @Test
    fun theSecondTapUndoesOnce() {
        val m = message()
        m.onTap()

        assertThat(m.onTap()).isEqualTo(UndoableBarMessage.TapResult.Undo)
        assertWithMessage("a third tap is late — the undo already ran")
            .that(m.onTap()).isEqualTo(UndoableBarMessage.TapResult.Ignored)
        assertThat(m.phase).isEqualTo(UndoableBarMessage.Phase.FINISHED)
    }

    @Test
    fun aTimeoutBeforeTheSecondTapUndoesNothing() {
        val m = message()
        m.onTap()

        assertThat(m.finish()).isTrue()
        assertThat(m.onTap()).isEqualTo(UndoableBarMessage.TapResult.Ignored)
    }

    @Test
    fun finishingBeforeAnyTapLeavesItInert() {
        val m = message()
        assertThat(m.finish()).isTrue()
        assertWithMessage("finishing twice reports it was already over").that(m.finish()).isFalse()
        assertThat(m.onTap()).isEqualTo(UndoableBarMessage.TapResult.Ignored)
    }
}
