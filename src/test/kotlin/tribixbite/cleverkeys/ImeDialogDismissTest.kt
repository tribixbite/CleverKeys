package tribixbite.cleverkeys

import android.app.AlertDialog
import android.os.IBinder
import android.view.Window
import android.view.WindowManager
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Review 2026-11-14 (untracked IME dialogs): a dialog shown over the keyboard is a child window of
 * the IME, so one left open when the keyboard hid (Back goes to the app: the dialog is not
 * focusable) came back with the keyboard in the next app. Every [Utils.show_dialog_on_ime] dialog
 * is now dismissed when the input view finishes.
 */
class ImeDialogDismissTest {

    private fun dialog(showing: Boolean): AlertDialog {
        val window = mockk<Window>(relaxed = true)
        every { window.attributes } returns mockk<WindowManager.LayoutParams>(relaxed = true)
        return mockk(relaxed = true) {
            every { this@mockk.window } returns window
            every { isShowing } returns showing
        }
    }

    @Test
    fun everyShownImeDialogIsDismissedWhenTheInputViewFinishes() {
        val open = dialog(showing = true)
        val alreadyClosed = dialog(showing = false)
        Utils.show_dialog_on_ime(open, mockk<IBinder>())
        Utils.show_dialog_on_ime(alreadyClosed, mockk<IBinder>())

        Utils.dismiss_ime_dialogs()

        verify(exactly = 1) { open.dismiss() }
        verify(exactly = 0) { alreadyClosed.dismiss() }
        // Nothing is held after the sweep.
        Utils.dismiss_ime_dialogs()
        verify(exactly = 1) { open.dismiss() }
    }

    @Test
    fun theServiceSweepsOnFinishInputViewAndOnDestroy() {
        val service = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt")
        for (signature in listOf("override fun onFinishInputView(", "override fun onDestroy(")) {
            assertWithMessage(signature).that(SourceScan.functionBody(service, signature))
                .contains("Utils.dismiss_ime_dialogs()")
        }
    }
}
