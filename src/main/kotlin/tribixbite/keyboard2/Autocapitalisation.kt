package tribixbite.keyboard2

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Smart auto-capitalization for sentences and words.
 *
 * Features:
 * - Sentence capitalization (after periods, newlines)
 * - Word capitalization (for proper names)
 * - Cursor position tracking
 * - Input type detection (messages, names, emails)
 * - Delayed callbacks to wait for editor updates
 *
 * Fix for Bug #361 (partial): SmartPunctuation - Autocapitalisation component
 */
class Autocapitalisation(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val callback: Callback
) {

    companion object {
        private val SUPPORTED_CAPS_MODES =
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }

    private var enabled = false
    private var shouldEnableShift = false
    private var shouldDisableShift = false
    private var shouldUpdateCapsMode = false

    private var inputConnection: InputConnection? = null
    private var capsMode = 0

    /** Keep track of the cursor to recognize cursor movements from typing */
    private var cursor = 0

    /**
     * Callback interface for shift state updates
     */
    interface Callback {
        fun updateShiftState(shouldEnable: Boolean, shouldDisable: Boolean)
    }

    /**
     * Start autocapitalisation for a new input session
     * [started] does initialisation work and must be called before any other event
     */
    fun started(info: EditorInfo, ic: InputConnection) {
        inputConnection = ic
        capsMode = info.inputType and TextUtils.CAP_MODE_SENTENCES

        val config = Config.globalConfig()
        if (!config.autocapitalisation || capsMode == 0) {
            enabled = false
            return
        }

        enabled = true
        shouldEnableShift = (info.initialCapsMode != 0)
        shouldUpdateCapsMode = startedShouldUpdateState(info.inputType)
        callbackNow(true)
    }

    /**
     * Handle text typed by the user
     */
    fun typed(text: CharSequence) {
        if (!enabled) return
        for (i in text.indices) {
            typeOneChar(text[i])
        }
        callback(false)
    }

    /**
     * Handle key events sent to the editor
     */
    fun eventSent(code: Int, meta: Int) {
        if (!enabled) return

        // Stop autocap if system modifiers are active
        if (meta != 0) {
            stop()
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

    /**
     * Stop autocapitalisation
     */
    fun stop() {
        if (!enabled) return
        enabled = false
        shouldEnableShift = false
        shouldDisableShift = true
        shouldUpdateCapsMode = false
        callbackNow(true)
    }

    /**
     * Pause auto capitalisation until [unpause()] is called
     * Returns whether autocapitalisation was enabled before pausing
     */
    fun pause(): Boolean {
        val wasEnabled = enabled
        stop()
        enabled = false
        return wasEnabled
    }

    /**
     * Continue auto capitalisation after [pause()] was called
     * Argument is the output of [pause()]
     */
    fun unpause(wasEnabled: Boolean) {
        enabled = wasEnabled
        shouldUpdateCapsMode = true
        callbackNow(true)
    }

    /**
     * Handle selection/cursor updates
     * Called when the cursor position changes
     */
    fun selectionUpdated(oldCursor: Int, newCursor: Int) {
        if (!enabled) return
        if (newCursor == cursor) { // Just typing
            return
        }

        if (newCursor == 0) {
            // Detect whether the input box has been cleared
            val ic = inputConnection
            if (ic != null) {
                val textAfter = ic.getTextAfterCursor(1, 0)
                if (textAfter != null && textAfter.toString() == "") {
                    shouldUpdateCapsMode = true
                }
            }
        }

        cursor = newCursor
        shouldEnableShift = false
        callback(true)
    }

    /**
     * Delayed callback to update shift state
     * Runs after a short delay to wait for the editor to handle events
     */
    private val delayedCallback = Runnable {
        if (shouldUpdateCapsMode) {
            val ic = inputConnection
            if (ic != null) {
                shouldEnableShift = enabled && (ic.getCursorCapsMode(capsMode) != 0)
                shouldUpdateCapsMode = false
            }
        }
        callback.updateShiftState(shouldEnableShift, shouldDisableShift)
    }

    /**
     * Update the shift state if [shouldUpdateCapsMode] is true, then call callback
     * This is done after a short delay to wait for the editor to handle events
     */
    private fun callback(mightDisable: Boolean) {
        shouldDisableShift = mightDisable
        // The callback must be delayed because getCursorCapsMode would sometimes
        // be called before the editor finished handling the previous event
        handler.removeCallbacks(delayedCallback)
        handler.postDelayed(delayedCallback, 1)
    }

    /**
     * Like [callback] but runs immediately
     */
    private fun callbackNow(mightDisable: Boolean) {
        shouldDisableShift = mightDisable
        delayedCallback.run()
    }

    /**
     * Handle typing a single character
     */
    private fun typeOneChar(c: Char) {
        cursor++
        if (isTriggerCharacter(c)) {
            shouldUpdateCapsMode = true
        } else {
            shouldEnableShift = false
        }
    }

    /**
     * Check if character should trigger capitalization update
     * Currently only space triggers (after sentence-ending punctuation)
     */
    private fun isTriggerCharacter(c: Char): Boolean {
        return when (c) {
            ' ' -> true
            else -> false
        }
    }

    /**
     * Whether the caps state should be updated when input starts
     * [inputType] is the field from the editor info object
     */
    private fun startedShouldUpdateState(inputType: Int): Boolean {
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

    /**
     * Check if autocapitalisation is enabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Get current shift state
     */
    fun shouldShiftBeEnabled(): Boolean = shouldEnableShift

    /**
     * Cleanup resources
     */
    fun cleanup() {
        handler.removeCallbacks(delayedCallback)
        inputConnection = null
    }
}
