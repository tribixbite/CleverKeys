package tribixbite.cleverkeys.upgradetest

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * A focused text field. The system binds the selected IME only when an editor
 * has focus (after `pm install -r` the IME process is gone until then), so the
 * orchestrator opens this to prove the 2.0 IME service really binds.
 */
class ImeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "upgrade IME probe"
        }
        setContentView(field)
        field.requestFocus()
        field.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 500)
    }
}
