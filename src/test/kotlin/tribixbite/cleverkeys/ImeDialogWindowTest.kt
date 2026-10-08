package tribixbite.cleverkeys

import android.app.AlertDialog
import android.os.IBinder
import android.view.Window
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.objenesis.ObjenesisStd
import java.io.File

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

    // ── ImeDialogSpinner (2026-10-07 filter-dialog teardown by long-press / press-and-drag) ──

    private val down = SpinnerTapTracker.ACTION_DOWN
    private val move = SpinnerTapTracker.ACTION_MOVE
    private val up = SpinnerTapTracker.ACTION_UP
    private val cancel = 3 // MotionEvent.ACTION_CANCEL
    private val pointerDown = 5 // MotionEvent.ACTION_POINTER_DOWN

    private fun gesture(vararg steps: Pair<Int, Boolean>): List<SpinnerTapTracker.Outcome> {
        val tracker = SpinnerTapTracker()
        return steps.map { (action, inside) -> tracker.onEvent(action, inside) }
    }

    @Test
    fun spinnerTouch_aTapOrALongPressReleasedInPlaceIsOneClick() {
        assertThat(gesture(down to true, up to true).last()).isEqualTo(SpinnerTapTracker.Outcome.CLICK)
        // Duration is irrelevant: a long press (many in-place MOVEs) is still one tap on release.
        assertThat(gesture(down to true, move to true, move to true, up to true))
            .containsExactly(SpinnerTapTracker.Outcome.NONE, SpinnerTapTracker.Outcome.NONE,
                SpinnerTapTracker.Outcome.NONE, SpinnerTapTracker.Outcome.CLICK).inOrder()
    }

    @Test
    fun spinnerTouch_pressAndDragOutCancelOrSecondPointerNeverClick() {
        // Press-and-drag (the dropdown "drag to an item" gesture) leaves the view: no click,
        // even when the finger comes back before release.
        assertThat(gesture(down to true, move to false, up to false)).doesNotContain(SpinnerTapTracker.Outcome.CLICK)
        assertThat(gesture(down to true, move to false, move to true, up to true)).doesNotContain(SpinnerTapTracker.Outcome.CLICK)
        assertThat(gesture(down to true, cancel to true, up to true)).doesNotContain(SpinnerTapTracker.Outcome.CLICK)
        assertThat(gesture(down to true, pointerDown to true, up to true)).doesNotContain(SpinnerTapTracker.Outcome.CLICK)
        assertThat(gesture(up to true)).doesNotContain(SpinnerTapTracker.Outcome.CLICK)
    }

    /**
     * Source/XML guards: the class forces dialog mode in code, never hands touches to a
     * superclass (whose forwarding listener opens a focusable popup), and every layout use also
     * declares spinnerMode="dialog" so the framework Spinner builds no dropdown popup.
     */
    @Test
    fun spinnerTouch_neverReachesASuperclassForwardingListener() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/ImeDialogSpinner.kt").readText()
        assertThat(source).contains("AppCompatSpinner(context, attrs, defStyleAttr, Spinner.MODE_DIALOG)")
        val onTouch = source.substringAfter("override fun onTouchEvent").substringBefore("override fun onDetachedFromWindow")
        assertThat(onTouch).doesNotContain("super.onTouchEvent")
        val uses = File("res/layout").listFiles()!!.filter { it.name.endsWith(".xml") }
            .flatMap { f -> Regex("<tribixbite\\.cleverkeys\\.ImeDialogSpinner[^>]*>").findAll(f.readText()).map { f.name to it.value }.toList() }
        assertThat(uses).isNotEmpty()
        uses.forEach { (file, tag) ->
            assertWithMessage(file).that(tag).contains("android:spinnerMode=\"dialog\"")
        }
    }
}
