package tribixbite.keyboard2

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import tribixbite.keyboard2.R

/**
 * Key event handler for processing keyboard input
 * Kotlin implementation with null safety and modern patterns
 */
class KeyEventHandler(private val receiver: IReceiver) : Config.IKeyEventHandler {
    
    companion object {
        private const val TAG = "KeyEventHandler"
    }
    
    // State management
    private var shouldCapitalizeNext = true
    private var mods = Pointers.Modifiers(0)
    private var metaState = 0
    private var moveCursorForceFallback = false
    
    /**
     * Receiver interface for key events
     */
    interface IReceiver {
        fun getInputConnection(): InputConnection?
        fun getCurrentInputEditorInfo(): EditorInfo?
        fun performVibration()
        fun commitText(text: String)
        fun performAction(action: Int)
    }
    
    override fun key_down(value: KeyValue, is_swipe: Boolean) {
        logD("Key down: $value (swipe: $is_swipe)")
        
        when (value.kind) {
            KeyValue.Kind.Char -> handleCharacterKey(value.char, is_swipe)
            KeyValue.Kind.Event -> handleEventKey(value.eventCode, is_swipe)
            KeyValue.Kind.String -> handleStringKey(value.string, is_swipe)
            KeyValue.Kind.Modifier -> handleModifierKey(value.eventCode, true)
            else -> logD("Unhandled key kind: ${value.kind}")
        }
    }
    
    override fun key_up(value: KeyValue, mods: Pointers.Modifiers) {
        this.mods = mods
        
        when (value.kind) {
            KeyValue.Kind.Modifier -> handleModifierKey(value.eventCode, false)
            else -> {} // Most keys only handle down events
        }
    }
    
    override fun mods_changed(mods: Pointers.Modifiers) {
        this.mods = mods
        updateMetaState()
    }
    
    /**
     * Handle character key input
     */
    private fun handleCharacterKey(char: Char, isSwipe: Boolean) {
        val inputConnection = receiver.getInputConnection() ?: return
        
        val finalChar = if (shouldCapitalizeNext && char.isLetter()) {
            char.uppercaseChar()
        } else {
            char
        }

        inputConnection.commitText(finalChar.toString(), 1)

        // Update capitalization state
        shouldCapitalizeNext = finalChar in ".!?"
        receiver.performVibration()
    }
    
    /**
     * Handle special event keys
     */
    private fun handleEventKey(eventCode: Int, isSwipe: Boolean) {
        when (eventCode) {
            KeyEvent.KEYCODE_DEL -> handleBackspace()
            KeyEvent.KEYCODE_ENTER -> handleEnter()
            KeyEvent.KEYCODE_SPACE -> handleSpace()
            KeyEvent.KEYCODE_TAB -> handleTab()
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1)
            else -> sendKeyEvent(eventCode)
        }
    }
    
    /**
     * Handle string keys (multiple characters)
     */
    private fun handleStringKey(string: String, isSwipe: Boolean) {
        val inputConnection = receiver.getInputConnection() ?: return
        inputConnection.commitText(string, 1)
        receiver.performVibration()
    }
    
    /**
     * Handle modifier keys (shift, ctrl, etc.)
     */
    private fun handleModifierKey(modifier: Int, isDown: Boolean) {
        // Update modifier state
        logD("Modifier ${if (isDown) "down" else "up"}: $modifier")
        updateMetaState()
    }
    
    /**
     * Handle backspace
     */
    private fun handleBackspace() {
        val inputConnection = receiver.getInputConnection() ?: return
        
        // Try to delete selected text first
        val selectedText = inputConnection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            inputConnection.commitText("", 1)
        } else {
            inputConnection.deleteSurroundingText(1, 0)
        }
        
        // Note: backspace logic could be more sophisticated
        receiver.performVibration()
    }
    
    /**
     * Handle enter key
     */
    private fun handleEnter() {
        val editorInfo = receiver.getCurrentInputEditorInfo()
        
        when (editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
            EditorInfo.IME_ACTION_SEND -> receiver.performAction(EditorInfo.IME_ACTION_SEND)
            EditorInfo.IME_ACTION_GO -> receiver.performAction(EditorInfo.IME_ACTION_GO)
            EditorInfo.IME_ACTION_SEARCH -> receiver.performAction(EditorInfo.IME_ACTION_SEARCH)
            EditorInfo.IME_ACTION_DONE -> receiver.performAction(EditorInfo.IME_ACTION_DONE)
            else -> {
                receiver.getInputConnection()?.commitText("\n", 1)
            }
        }
        
        shouldCapitalizeNext = true
        receiver.performVibration()
    }
    
    /**
     * Handle space key
     */
    private fun handleSpace() {
        val inputConnection = receiver.getInputConnection() ?: return
        inputConnection.commitText(" ", 1)
        // Don't change capitalization state after space
        receiver.performVibration()
    }
    
    /**
     * Handle tab key
     */
    private fun handleTab() {
        val inputConnection = receiver.getInputConnection() ?: return
        inputConnection.commitText("\t", 1)
        receiver.performVibration()
    }
    
    /**
     * Move cursor by relative offset
     */
    private fun moveCursor(offset: Int) {
        val inputConnection = receiver.getInputConnection() ?: return
        
        if (!moveCursorForceFallback) {
            // Try using setSelection for better performance
            try {
                val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
                if (extractedText != null) {
                    val newPosition = (extractedText.selectionStart + offset).coerceAtLeast(0)
                    if (inputConnection.setSelection(newPosition, newPosition)) {
                        return
                    }
                }
            } catch (e: Exception) {
                logE("Failed to use setSelection", e)
            }
        }
        
        // Fallback to arrow key events
        val keyCode = if (offset > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(offset)) {
            sendKeyEvent(keyCode)
        }
    }
    
    /**
     * Send raw key event
     */
    private fun sendKeyEvent(keyCode: Int) {
        val inputConnection = receiver.getInputConnection() ?: return
        
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        
        inputConnection.sendKeyEvent(downEvent)
        inputConnection.sendKeyEvent(upEvent)
    }
    
    /**
     * Update meta state for modifiers
     */
    private fun updateMetaState() {
        metaState = 0
        
        if (mods.isShift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON
        }
        if (mods.isCtrl) {
            metaState = metaState or KeyEvent.META_CTRL_ON
        }
        if (mods.isAlt) {
            metaState = metaState or KeyEvent.META_ALT_ON
        }
    }
    
    /**
     * Check if should force fallback for cursor movement
     */
    private fun shouldMoveCursorForceFallback(info: EditorInfo?): Boolean {
        // Simplified logic - original has complex input type checking
        return info?.inputType?.let { inputType ->
            (inputType and InputType.TYPE_CLASS_TEXT) == 0
        } ?: false
    }
}

