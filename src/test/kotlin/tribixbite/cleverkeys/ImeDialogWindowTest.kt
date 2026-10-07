package tribixbite.cleverkeys

import android.app.AlertDialog
import android.os.IBinder
import android.view.Window
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Device report 2026-10-07 (Saga, Chrome): tapping an IME-attached dialog gave its window input
 * focus; Chrome lost window focus, requested HIDE_SOFT_INPUT, the input view finished, and the
 * confirmation was torn down before its button handler ran — nothing was deleted, and the
 * filter dialog closed itself the same way. IME dialogs must never take window focus.
 */
class ImeDialogWindowTest {

    @Test
    fun imeDialogsAreNonFocusableAttachedDialogsThatCloseOnOutsideTouch() {
        val dialog = mockk<AlertDialog>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        val params = ObjenesisStd().newInstance(WindowManager.LayoutParams::class.java)
        val token = mockk<IBinder>()
        every { dialog.window } returns window
        every { window.attributes } returns params

        Utils.show_dialog_on_ime(dialog, token)

        assertThat(params.token).isSameInstanceAs(token)
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        verify { window.addFlags(ImeDialogWindowPolicy.ADDED_FLAGS) }
        verify { window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) }
        verify(exactly = 1) { dialog.show() }
    }

    @Test
    fun thePolicyNeverLetsTheDialogTakeFocusOrTheImeTarget() {
        val flags = ImeDialogWindowPolicy.ADDED_FLAGS
        assertThat(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).isNotEqualTo(0)
        // NOT_FOCUSABLE without ALT_FOCUSABLE_IM = "may not use the input method": the window
        // neither steals focus from the app's editor nor becomes an IME target.
        assertThat(flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM).isEqualTo(0)
    }
}
