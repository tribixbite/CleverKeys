package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [RotationPaneRestore]: the pane open at a rotation comes back with the keyboard only when the
 * system closed it (the rotated app's new window finishing the input view), never one the user
 * closed or changed after the rotation (pre-tag review S3, 2026-11-25). Pure tier.
 */
class RotationPaneRestoreTest {

    private enum class Pane { EMOJI, CLIPBOARD }

    private fun armedWith(pane: Pane) = RotationPaneRestore<Pane>().apply { onRotation(pane, guardArmed = true) }

    @Test
    fun aPaneTheSystemClosedComesBackOnce() {
        val restore = armedWith(Pane.CLIPBOARD)
        restore.onInputViewFinishing(Pane.CLIPBOARD)
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isEqualTo(Pane.CLIPBOARD)
        assertWithMessage("at most once").that(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()
    }

    @Test
    fun aLaterInputViewFinishAfterTheSystemsCloseKeepsIt() {
        // The relaunch can finish the input view twice (old window, then the fallback connection).
        val restore = armedWith(Pane.EMOJI)
        restore.onInputViewFinishing(Pane.EMOJI)
        restore.onInputViewFinishing(null)
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isEqualTo(Pane.EMOJI)
    }

    @Test
    fun aPaneTheUserClosedAfterTheRotationStaysClosed() {
        val restore = armedWith(Pane.CLIPBOARD)
        // The pane's own close button: no key touch, the keyboard stays up, then the app hides it.
        restore.onInputViewFinishing(null)
        assertThat(restore.pendingPane).isNull()
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()
    }

    @Test
    fun aPaneClosedByTheUserBeforeTheWindowCameBackStaysClosed() {
        // Closed by hand while the keyboard stayed up and no input view finished in between.
        val restore = armedWith(Pane.CLIPBOARD)
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()
        assertThat(restore.pendingPane).isNull()
    }

    @Test
    fun switchingToAnotherPaneDropsTheRestore() {
        val restore = armedWith(Pane.CLIPBOARD)
        restore.onInputViewFinishing(Pane.EMOJI)
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()

        val shown = armedWith(Pane.CLIPBOARD)
        assertThat(shown.paneToReopen(guardArmed = true, openPane = Pane.EMOJI)).isNull()
        assertThat(shown.pendingPane).isNull()
    }

    @Test
    fun aPaneStillOpenWaitsForTheSystemsClose() {
        val restore = armedWith(Pane.EMOJI)
        assertWithMessage("the system kept the keyboard and the pane")
            .that(restore.paneToReopen(guardArmed = true, openPane = Pane.EMOJI)).isNull()
        assertThat(restore.pendingPane).isEqualTo(Pane.EMOJI)
        restore.onInputViewFinishing(Pane.EMOJI)
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isEqualTo(Pane.EMOJI)
    }

    @Test
    fun userInputDropsThePendingPane() {
        val restore = armedWith(Pane.EMOJI)
        restore.onInputViewFinishing(Pane.EMOJI)
        restore.onUserInput()
        assertThat(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()
    }

    @Test
    fun aDisarmedGuardReopensNothing() {
        val restore = armedWith(Pane.EMOJI)
        restore.onInputViewFinishing(Pane.EMOJI)
        assertThat(restore.paneToReopen(guardArmed = false, openPane = null)).isNull()
        assertWithMessage("given up, not kept for a later show").that(restore.pendingPane).isNull()
    }

    @Test
    fun aRotationTheGuardDoesNotArmOrWithoutAPaneHoldsNothing() {
        val unguarded = RotationPaneRestore<Pane>().apply { onRotation(Pane.EMOJI, guardArmed = false) }
        assertThat(unguarded.pendingPane).isNull()
        val noPane = RotationPaneRestore<Pane>().apply { onRotation(null, guardArmed = true) }
        assertThat(noPane.pendingPane).isNull()
    }

    @Test
    fun aNewRotationReplacesTheEarlierState() {
        val restore = armedWith(Pane.EMOJI)
        restore.onInputViewFinishing(Pane.EMOJI)
        restore.onRotation(Pane.CLIPBOARD, guardArmed = true)
        assertWithMessage("the earlier close does not count for the new rotation's pane")
            .that(restore.paneToReopen(guardArmed = true, openPane = null)).isNull()
    }

    /**
     * The show guard side of S3: a submit disarms it, so an app that hides the keyboard right
     * after the user typed and submitted keeps its hide (typing alone does not: follow-up
     * 2026-11-25, [RotationShowGuardTest.typingWhileRotatingStillGetsTheKeyboardBack]). The
     * service wiring is pinned by [RotationUserInputWiringTest]. Name kept for RELEASE_RECORD.
     */
    @Test
    fun userInputDisarmsTheShowGuard() {
        val editor = EditorRef("com.google.android.googlequicksearchbox", fieldId = 2131435733, typeNull = false)
        val guard = RotationShowGuard().apply { onOrientationChanged(windowVisible = true, editor = editor, nowMs = 0) }
        guard.onUserInput(RotationShowGuard.UserInput.SUBMIT)
        assertThat(guard.isArmed).isFalse()
        assertWithMessage("a hide after the user's submit stands")
            .that(guard.onWindowHidden(byUser = false, nowMs = 500)).isFalse()
    }
}
