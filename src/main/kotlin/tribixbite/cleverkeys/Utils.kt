package tribixbite.cleverkeys

import android.app.AlertDialog
import android.os.IBinder
import android.view.WindowManager
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

object Utils {
    /** Turn the first letter of a string uppercase. */
    @JvmStatic
    fun capitalize_string(s: String): String {
        if (s.length < 1) return s
        // Make sure not to cut a code point in half
        val i = s.offsetByCodePoints(0, 1)
        return s.substring(0, i).uppercase(Locale.getDefault()) + s.substring(i)
    }

    /**
     * Like [dialog.show()] but properly configure layout params when called from an IME.
     * [token] is the input view's [getWindowToken()] (an IME window, never another dialog's:
     * a sub-window cannot parent a sub-window). The window flags come from
     * [ImeDialogWindowPolicy]: the dialog must never take window focus from the app being typed
     * into (device report 2026-10-07).
     */
    @JvmStatic
    fun show_dialog_on_ime(dialog: AlertDialog, token: IBinder) {
        val win = dialog.window
        val lp = win!!.attributes
        lp.token = token
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        win.attributes = lp
        win.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        win.addFlags(ImeDialogWindowPolicy.ADDED_FLAGS)
        dialog.show()
    }

    /**
     * The whole of [inp] decoded as UTF-8. Closes [inp] (through its reader) on success and on
     * failure — audit L14: the reader was never closed, and `LayoutsPreference` passed it a raw
     * resource stream without `use {}`, leaking one stream per custom-layout dialog. Callers that
     * also wrap the stream in `use {}` are harmless (closing twice is a no-op).
     */
    @JvmStatic
    @Throws(Exception::class)
    fun read_all_utf8(inp: InputStream): String =
        InputStreamReader(inp, Charsets.UTF_8).use { it.readText() }
}
