package tribixbite.keyboard2

import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import kotlinx.coroutines.*
import tribixbite.keyboard2.Config

/**
 * Automatic capitalization system for CleverKeys.
 * Intelligently manages shift state based on cursor position, text content, and editor type.
 *
 * Features:
 * - Context-aware capitalization (sentences, words)
 * - Cursor movement detection
 * - Editor type validation
 * - Reactive shift state management
 * - Coroutine-based asynchronous processing
 */
class Autocapitalisation(
    private val handler: Handler,
    private val callback: Callback
) {
    companion object {
        /** Supported caps modes for auto-capitalization */
        const val SUPPORTED_CAPS_MODES = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                        InputType.TYPE_TEXT_FLAG_CAP_WORDS

        /** Delay for caps mode update to allow editor processing */
        private const val CALLBACK_DELAY_MS = 50L

        /** Characters that trigger auto-capitalization */
        private val TRIGGER_CHARACTERS = setOf(' ', '.', '!', '?', '\n')

        /** Input types that support auto-capitalization updates */
        private val SUPPORTED_INPUT_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        )
    }

    // State management
    private var isEnabled = false
    private var shouldEnableShift = false
    private var shouldDisableShift = false
    private var shouldUpdateCapsMode = false

    // Input connection and configuration
    private var inputConnection: InputConnection? = null
    private var capsMode = 0
    private var cursor = 0

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Callback interface for shift state updates.
     */
    interface Callback {
        /**
         * Update shift state based on auto-capitalization logic.
         * @param shouldEnable Whether shift should be enabled
         * @param shouldDisable Whether shift should be disabled
         */
        fun updateShiftState(shouldEnable: Boolean, shouldDisable: Boolean)
    }

    /**
     * Initialize auto-capitalization for a new input session.
     * Must be called before any other operations.
     *
     * @param editorInfo Editor information from the input method
     * @param inputConnection Connection to the target editor
     */
    fun started(editorInfo: EditorInfo, inputConnection: InputConnection) {
        this.inputConnection = inputConnection
        this.capsMode = editorInfo.inputType and TextUtils.CAP_MODE_SENTENCES

        val config = Config.globalConfig()
        if (!config.autocapitalisation || capsMode == 0) {
            isEnabled = false
            return
        }

        isEnabled = true
        shouldEnableShift = editorInfo.initialCapsMode != 0
        shouldUpdateCapsMode = shouldUpdateStateOnStart(editorInfo.inputType)

        callbackNow(true)
    }

    /**
     * Process typed characters for auto-capitalization.
     * @param text Character sequence that was typed
     */
    fun typed(text: CharSequence) {
        if (!isEnabled) return

        for (i in text.indices) {
            typeOneChar(text[i])
        }
        scheduleCallback(false)
    }

    /**
     * Handle key events that affect capitalization.
     * @param keyCode Key code of the event
     * @param metaState Meta state of the key event
     */
    fun eventSent(keyCode: Int, metaState: Int) {
        if (!isEnabled) return

        if (metaState != 0) {
            shouldEnableShift = false
            shouldUpdateCapsMode = false
            return
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (cursor > 0) cursor--
                shouldUpdateCapsMode = true
            }
            KeyEvent.KEYCODE_ENTER -> {
                shouldUpdateCapsMode = true
            }
        }

        scheduleCallback(true)
    }

    /**
     * Stop auto-capitalization and reset state.
     */
    fun stop() {
        shouldEnableShift = false
        shouldUpdateCapsMode = false
        callbackNow(true)
    }

    /**
     * Temporarily pause auto-capitalization.
     * @return Previous enabled state for restoration
     */
    fun pause(): Boolean {
        val wasEnabled = isEnabled
        stop()
        isEnabled = false
        return wasEnabled
    }

    /**
     * Resume auto-capitalization after pause.
     * @param wasEnabled Previous enabled state from pause()
     */
    fun unpause(wasEnabled: Boolean) {
        isEnabled = wasEnabled
        shouldUpdateCapsMode = true
        callbackNow(true)
    }

    /**
     * Handle cursor position updates.
     * @param oldCursor Previous cursor position
     * @param newCursor New cursor position
     */
    fun selectionUpdated(oldCursor: Int, newCursor: Int) {
        if (!isEnabled) return

        // If cursor hasn't moved, it's just typing
        if (newCursor == cursor) return

        // Check if input was cleared
        if (newCursor == 0 && inputConnection != null) {
            val textAfterCursor = inputConnection?.getTextAfterCursor(1, 0)
            if (textAfterCursor?.isEmpty() == true) {
                shouldUpdateCapsMode = true
            }
        }

        cursor = newCursor
        shouldEnableShift = false
        scheduleCallback(true)
    }

    /**
     * Process a single typed character.
     */
    private fun typeOneChar(char: Char) {
        cursor++

        if (isTriggerCharacter(char)) {
            shouldUpdateCapsMode = true
        } else {
            shouldEnableShift = false
        }
    }

    /**
     * Check if character triggers auto-capitalization.
     */
    private fun isTriggerCharacter(char: Char): Boolean {
        return char in TRIGGER_CHARACTERS
    }

    /**
     * Check if input type should update caps mode on start.
     */
    private fun shouldUpdateStateOnStart(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass != InputType.TYPE_CLASS_TEXT) {
            return false
        }

        return variation in SUPPORTED_INPUT_VARIATIONS
    }

    /**
     * Schedule delayed callback to update shift state.
     */
    private fun scheduleCallback(mightDisable: Boolean) {
        shouldDisableShift = mightDisable

        // Cancel any pending callback
        handler.removeCallbacks(delayedCallback)

        // Schedule new callback with delay
        handler.postDelayed(delayedCallback, CALLBACK_DELAY_MS)
    }

    /**
     * Execute callback immediately without delay.
     */
    private fun callbackNow(mightDisable: Boolean) {
        shouldDisableShift = mightDisable
        delayedCallback.run()
    }

    /**
     * Delayed callback for updating caps mode and notifying callback.
     */
    private val delayedCallback = Runnable {
        scope.launch {
            try {
                if (shouldUpdateCapsMode && inputConnection != null) {
                    val currentCapsMode = inputConnection?.getCursorCapsMode(capsMode) ?: 0
                    shouldEnableShift = isEnabled && currentCapsMode != 0
                    shouldUpdateCapsMode = false
                }

                callback.updateShiftState(shouldEnableShift, shouldDisableShift)
            } catch (e: Exception) {
                // Handle any input connection errors gracefully
                android.util.Log.w("Autocapitalisation", "Error updating caps mode", e)
            }
        }
    }

    /**
     * Cleanup resources when autocapitalization is no longer needed.
     */
    fun cleanup() {
        scope.cancel()
        handler.removeCallbacks(delayedCallback)
        inputConnection = null
    }
}