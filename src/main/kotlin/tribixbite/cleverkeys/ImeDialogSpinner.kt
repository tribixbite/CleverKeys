package tribixbite.cleverkeys

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
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
 */
class ImeDialogSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatSpinner(context, attrs) {

    private var choices: AlertDialog? = null

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

    override fun onDetachedFromWindow() {
        // The containing dialog closed: its choice list must not outlive it.
        choices?.dismiss()
        choices = null
        super.onDetachedFromWindow()
    }
}
