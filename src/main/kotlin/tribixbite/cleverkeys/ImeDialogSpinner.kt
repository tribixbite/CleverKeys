package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.Spinner
import androidx.appcompat.widget.AppCompatSpinner

/**
 * A [Spinner] for dialogs shown over the keyboard ([Utils.show_dialog_on_ime]).
 *
 * A stock dropdown Spinner opens a modal, focusable popup window. Over an IME that popup takes
 * window focus from the app being typed into, the app hides the keyboard, and the dialog closes
 * (see [ImeDialogWindowPolicy]). This subclass shows its choices as a single-choice list in a
 * non-focusable IME dialog instead, keyed to the keyboard's own window (a sub-window may not
 * parent another sub-window, so the containing dialog's token cannot be used). Selection,
 * listeners and accessibility behave like a normal Spinner.
 *
 * ## Why click alone was not enough (2026-10-07 filter-dialog teardown)
 *
 * Overriding [performClick] covers a tap, but a dropdown-mode spinner ALSO opens its popup from
 * the touch stream: both `AppCompatSpinner` and the framework `Spinner` install a
 * `ForwardingListener` in dropdown mode, and a long press or press-and-drag on the spinner makes
 * it call `showPopup()` — a focusable `ListPopupWindow`, i.e. exactly the window this class
 * exists to avoid. The host app then hid the keyboard and the filter dialog was torn down.
 *
 * Two independent guards, so the class is safe whatever its XML says:
 *  1. The AppCompat constructor is called with [Spinner.MODE_DIALOG], so AppCompat
 *     never creates its dropdown popup or forwarding listener (an explicit mode overrides
 *     `android:spinnerMode` and the theme's spinner style).
 *  2. [onTouchEvent] never reaches either superclass: it turns the touch stream into a plain
 *     tap ([SpinnerTapTracker]) and calls [performClick] on release inside the view. The framework
 *     Spinner's own forwarding listener (it resolves its mode from the XML/theme, which this
 *     class cannot override) is therefore unreachable too.
 * The layouts also declare `android:spinnerMode="dialog"`, which keeps the framework side from
 * building a dropdown popup at all.
 *
 * ## Style (2026-10-08 native regression)
 *
 * The dialogs inflate this view under the FRAMEWORK `Theme.DeviceDefault.Dialog`, where
 * AppCompat's `R.attr.spinnerStyle` (AppCompatSpinner's default) resolves to nothing. With no
 * style the view had no spinner background and, crucially, was not clickable, so a tap was
 * dropped before performClick and the choice list never opened (ew-cli:
 * `ClipboardFilterDialogTest` "Missing size option: 10 kB" after the switch from `Spinner` to
 * `AppCompatSpinner`). The default style is therefore the framework `android:spinnerStyle`
 * (what the plain `Spinner` used), clickability is set explicitly, and AppCompat's
 * "not a Theme.AppCompat" log line from its constructor is expected and harmless.
 */
class ImeDialogSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.spinnerStyle,
) : AppCompatSpinner(context, attrs, defStyleAttr, Spinner.MODE_DIALOG) {

    private var choices: AlertDialog? = null
    private val tap = SpinnerTapTracker()

    init {
        // A tap must always reach performClick, whatever style resolved (see the KDoc).
        isClickable = true
    }

    override fun performClick(): Boolean {
        val adapter = adapter ?: return false
        val token = applicationWindowToken ?: return false
        if (choices?.isShowing == true) return true
        val labels = Array<CharSequence>(adapter.count) { adapter.getItem(it)?.toString().orEmpty() }
        val dialog = AlertDialog.Builder(context)
            .setTitle(contentDescription)
            .setSingleChoiceItems(labels, selectedItemPosition) { d, which ->
                setSelection(which)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (choices === dialog) choices = null }
        choices = dialog
        Utils.show_dialog_on_ime(dialog, token)
        return true
    }

    /**
     * A tap opens the choices; nothing else does. Long press and press-and-drag are deliberately
     * inert (see the class KDoc): neither superclass sees the event, so no forwarding listener
     * can open a focusable popup.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            // A disabled clickable view still consumes touches (View contract) without acting.
            tap.cancel()
            isPressed = false
            return isClickable
        }
        val inside = event.x >= 0f && event.y >= 0f && event.x < width && event.y < height
        val outcome = tap.onEvent(event.actionMasked, inside)
        isPressed = tap.pressed
        if (outcome == SpinnerTapTracker.Outcome.CLICK) performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        // The containing dialog closed: its choice list must not outlive it.
        choices?.dismiss()
        choices = null
        tap.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * Android-free tap recogniser for [ImeDialogSpinner.onTouchEvent]. It is top-level and uses no
 * View types, so pure JVM tests can drive it without loading the View subclass. A click is a
 * DOWN followed by an UP with the pointer never having left the view; leaving the view, CANCEL
 * or a second pointer abandon the gesture. Duration is irrelevant: a long press released in
 * place is still just a tap (and opens the non-focusable choice list, never a popup window).
 */
class SpinnerTapTracker {
    enum class Outcome { NONE, CLICK }

    /** True while a gesture that can still become a click is in progress. */
    var pressed: Boolean = false
        private set

    fun onEvent(actionMasked: Int, inside: Boolean): Outcome {
        when (actionMasked) {
            ACTION_DOWN -> pressed = inside
            ACTION_MOVE -> if (!inside) pressed = false
            ACTION_UP -> {
                val click = pressed && inside
                pressed = false
                return if (click) Outcome.CLICK else Outcome.NONE
            }
            // CANCEL, a second pointer, or anything unexpected abandons the gesture.
            else -> pressed = false
        }
        return Outcome.NONE
    }

    fun cancel() {
        pressed = false
    }

    companion object {
        // MotionEvent constants, duplicated so this class never loads MotionEvent.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
