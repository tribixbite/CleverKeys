package tribixbite.keyboard2

import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class Autocapitalisation(
    private val handler: Handler,
    private val callback: Callback
) {
    private var enabled = false
    private var shouldEnableShift = false
    private var shouldDisableShift = false
    private var shouldUpdateCapsMode = false

    private var ic: InputConnection? = null
    private var capsMode = 0

    /** Keep track of the cursor to recognize cursor movements from typing. */
    private var cursor = 0

    /**
     * The events are: started, typed, event sent, selection updated
     * [started] does initialisation work and must be called before any other
     * event.
     */
    fun started(info: EditorInfo, ic: InputConnection) {
        this.ic = ic
        capsMode = info.inputType and TextUtils.CAP_MODE_SENTENCES
        if (!Config.globalConfig().autocapitalisation || capsMode == 0) {
            enabled = false
            return
        }
        enabled = true
        shouldEnableShift = info.initialCapsMode != 0
        shouldUpdateCapsMode = started_should_update_state(info.inputType)
        callback_now(true)
    }

    fun typed(c: CharSequence) {
        for (i in c.indices) {
            type_one_char(c[i])
        }
        callback(false)
    }

    fun event_sent(code: Int, meta: Int) {
        if (meta != 0) {
            shouldEnableShift = false
            shouldUpdateCapsMode = false
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DEL -> {
                if (cursor > 0) cursor--
                shouldUpdateCapsMode = true
            }
            KeyEvent.KEYCODE_ENTER -> {
                shouldUpdateCapsMode = true
            }
        }
        callback(true)
    }

    fun stop() {
        shouldEnableShift = false
        shouldUpdateCapsMode = false
        callback_now(true)
    }

    /** Pause auto capitalisation until [unpause] is called. */
    fun pause(): Boolean {
        val wasEnabled = enabled
        stop()
        enabled = false
        return wasEnabled
    }

    /**
     * Continue auto capitalisation after [pause] was called. Argument is the
     * output of [pause].
     */
    fun unpause(wasEnabled: Boolean) {
        enabled = wasEnabled
        shouldUpdateCapsMode = true
        callback_now(true)
    }

    fun interface Callback {
        fun update_shift_state(should_enable: Boolean, should_disable: Boolean)
    }

    /** Returns [true] if shift might be disabled. */
    fun selection_updated(old_cursor: Int, new_cursor: Int) {
        if (new_cursor == cursor) { // Just typing
            return
        }
        if (new_cursor == 0 && ic != null) {
            // Detect whether the input box has been cleared
            val t = ic?.getTextAfterCursor(1, 0)
            if (t != null && t.toString() == "") {
                shouldUpdateCapsMode = true
            }
        }
        cursor = new_cursor
        shouldEnableShift = false
        callback(true)
    }

    private val delayed_callback = Runnable {
        if (shouldUpdateCapsMode && ic != null) {
            shouldEnableShift = enabled && (ic?.getCursorCapsMode(capsMode) != 0)
            shouldUpdateCapsMode = false
        }
        callback.update_shift_state(shouldEnableShift, shouldDisableShift)
    }

    /**
     * Update the shift state if [shouldUpdateCapsMode] is true, then call
     * [callback.update_shift_state]. This is done after a short delay to wait
     * for the editor to handle the events, as this might be called before the
     * corresponding event is sent.
     */
    private fun callback(might_disable: Boolean) {
        shouldDisableShift = might_disable
        // The callback must be delayed because [getCursorCapsMode] would sometimes
        // be called before the editor finished handling the previous event.
        handler.postDelayed(delayed_callback, 50)
    }

    /** Like [callback] but runs immediately. */
    private fun callback_now(might_disable: Boolean) {
        shouldDisableShift = might_disable
        delayed_callback.run()
    }

    private fun type_one_char(c: Char) {
        cursor++
        if (is_trigger_character(c)) {
            shouldUpdateCapsMode = true
        } else {
            shouldEnableShift = false
        }
    }

    private fun is_trigger_character(c: Char): Boolean {
        return when (c) {
            ' ', '.', '!', '?', '\n' -> true
            else -> false
        }
    }

    /**
     * Whether the caps state should be updated when input starts. [inputType]
     * is the field from the editor info object.
     */
    private fun started_should_update_state(inputType: Int): Boolean {
        val class_ = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (class_ != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> true
            else -> false
        }
    }

    companion object {
        @JvmField
        val SUPPORTED_CAPS_MODES =
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
}
