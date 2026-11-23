package tribixbite.cleverkeys

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText

/**
 * Test-only host for instrumented tests that drive the real keyboard: one text field with sentence
 * capitalisation, focused, with the keyboard requested. Debug APK only, never in a release build.
 *
 * It declares no `configChanges`, so a rotation recreates it as it does most apps: the field (a
 * fixed id, so its text is restored) comes back focused in a NEW window and the keyboard starts
 * input for it afresh. [ImeFieldKeepWindowTestActivity] handles the rotation itself instead.
 */
open class ImeFieldTestActivity : Activity() {

    lateinit var field: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply {
            // Stable across recreation: the editor's fieldId the keyboard sees stays the same.
            id = android.R.id.edit
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        setContentView(field)
        field.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}

/**
 * [ImeFieldTestActivity] that declares the rotation's configuration changes (debug manifest), so
 * its window and field survive a rotation, as in an app that handles rotation itself: only the
 * keyboard's own restart runs.
 */
class ImeFieldKeepWindowTestActivity : ImeFieldTestActivity()
