package tribixbite.keyboard2

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

    /** Like [dialog.show()] but properly configure layout params when called
        from an IME. [token] is the input view's [getWindowToken()]. */
    @JvmStatic
    fun show_dialog_on_ime(dialog: AlertDialog, token: IBinder) {
        val win = dialog.window
        val lp = win!!.attributes
        lp.token = token
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        win.attributes = lp
        win.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.show()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun read_all_utf8(inp: InputStream): String {
        val reader = InputStreamReader(inp, "UTF-8")
        val out = StringBuilder()
        val buffLength = 8000
        val buff = CharArray(buffLength)
        var l: Int
        while (reader.read(buff, 0, buffLength).also { l = it } != -1) {
            out.append(buff, 0, l)
        }
        return out.toString()
    }
}
