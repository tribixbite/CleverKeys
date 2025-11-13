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
 * Includes tap typing prediction integration (Fix for Bug #359)
 * Includes voice guidance for blind users (Fix for Bug #368)
 * Includes screen reader integration for TalkBack (Fix for Bug #377)
 * Includes spell checking integration (Fix for Bug #311)
 * Includes smart punctuation (Fix for Bug #316 & #361)
 */
class KeyEventHandler(
    private val receiver: IReceiver,
    private val typingPredictionEngine: TypingPredictionEngine? = null,
    private val voiceGuidanceEngine: VoiceGuidanceEngine? = null,
    private val screenReaderManager: ScreenReaderManager? = null,
    private val spellCheckHelper: SpellCheckHelper? = null,
    private val smartPunctuationHandler: SmartPunctuationHandler? = null
) : Config.IKeyEventHandler, ClipboardPasteCallback {

    companion object {
        private const val TAG = "KeyEventHandler"
        private const val MAX_CONTEXT_WORDS = 10
    }

    // State management
    private var shouldCapitalizeNext = true
    private var mods = Pointers.Modifiers.EMPTY
    private var metaState = 0
    private var moveCursorForceFallback = false
    private var inputConnection: InputConnection? = null

    // Tap typing prediction state (Fix for Bug #359)
    private var currentWord = StringBuilder()
    private val contextWords = mutableListOf<String>()
    private var lastPredictionUpdate = 0L
    
    /**
     * Receiver interface for key events
     */
    interface IReceiver {
        fun getInputConnection(): InputConnection?
        fun getCurrentInputEditorInfo(): EditorInfo?
        fun getKeyboardView(): android.view.View? // For screen reader announcements
        fun performVibration()
        fun commitText(text: String)
        fun performAction(action: Int)
        fun switchToMainLayout()
        fun switchToNumericLayout()
        fun switchToEmojiLayout()
        fun openSettings()
        fun updateSuggestions(suggestions: List<String>) {} // Default empty implementation for tap typing predictions
    }
    
    override fun key_down(value: KeyValue, is_swipe: Boolean) {
        logD("Key down: $value (swipe: $is_swipe)")

        // Voice guidance for blind users (Fix for Bug #368)
        voiceGuidanceEngine?.speakKey(value)

        // Screen reader announcement for TalkBack (Fix for Bug #377)
        receiver.getKeyboardView()?.let { view ->
            screenReaderManager?.announceKeyPress(view, value)
        }

        when (value) {
            is KeyValue.CharKey -> handleCharacterKey(value.char, is_swipe)
            is KeyValue.EventKey -> handleEventKey(value.event, is_swipe)
            is KeyValue.StringKey -> handleStringKey(value.string, is_swipe)
            is KeyValue.ModifierKey -> handleModifierKey(value.modifier, true)
            is KeyValue.KeyEventKey -> handleKeyEventKey(value.keyCode, is_swipe)
            is KeyValue.ComposePendingKey -> handleComposeKey(value.pendingCompose)
            else -> logD("Unhandled key type: ${value::class.simpleName}")
        }
    }
    
    override fun key_up(value: KeyValue, mods: Pointers.Modifiers) {
        this.mods = mods

        when (value) {
            is KeyValue.ModifierKey -> handleModifierKey(value.modifier, false)
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

        // Apply smart punctuation processing (Fix for Bug #316 & #361)
        val processedText = smartPunctuationHandler?.processCharacter(
            finalChar,
            inputConnection
        )

        if (processedText != null) {
            // Smart punctuation modified the input
            inputConnection.commitText(processedText, processedText.length)
        } else {
            // No modification, commit as-is
            inputConnection.commitText(finalChar.toString(), 1)
        }

        // Update capitalization state
        shouldCapitalizeNext = finalChar in ".!?"
        receiver.performVibration()

        // Update tap typing predictions (Fix for Bug #359)
        if (!isSwipe && typingPredictionEngine != null) {
            if (finalChar.isLetterOrDigit() || finalChar == '\'') {
                // Add to current word
                currentWord.append(finalChar.lowercaseChar())
                updateTapTypingPredictions()
            } else if (finalChar.isWhitespace() || finalChar in ".,!?;:") {
                // Word completed - add to context and check spelling (Fix for Bug #311)
                finishCurrentWord()
                spellCheckHelper?.checkLastWord(inputConnection)
            }
        }
    }
    
    /**
     * Handle special event keys
     */
    private fun handleEventKey(event: KeyValue.Event, isSwipe: Boolean) {
        when (event) {
            KeyValue.Event.ACTION -> handleEnter()
            KeyValue.Event.SWITCH_TEXT -> receiver.switchToMainLayout()
            KeyValue.Event.SWITCH_NUMERIC -> receiver.switchToNumericLayout()
            KeyValue.Event.SWITCH_EMOJI -> receiver.switchToEmojiLayout()
            KeyValue.Event.CONFIG -> receiver.openSettings()
            KeyValue.Event.CAPS_LOCK -> toggleCapsLock()
            else -> logD("Unhandled event: $event")
        }
    }

    /**
     * Handle KeyEvent key input
     */
    private fun handleKeyEventKey(keyCode: Int, isSwipe: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> handleBackspace()
            KeyEvent.KEYCODE_ENTER -> handleEnter()
            KeyEvent.KEYCODE_SPACE -> handleSpace()
            KeyEvent.KEYCODE_TAB -> handleTab()
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1)
            else -> sendKeyEvent(keyCode)
        }
    }

    /**
     * Handle modifier key state changes
     */
    private fun handleModifierKey(modifier: KeyValue.Modifier, isPressed: Boolean) {
        logD("Modifier key: $modifier (pressed: $isPressed)")

        // Modifier state is managed by Pointers, just update meta state
        updateMetaState()

        // Handle special modifiers
        when (modifier) {
            KeyValue.Modifier.SHIFT -> {
                // Shift state handled by Pointers for latching/locking
                shouldCapitalizeNext = isPressed
            }
            KeyValue.Modifier.CTRL, KeyValue.Modifier.ALT, KeyValue.Modifier.META -> {
                // Control modifiers - state tracked in mods
                logD("Control modifier ${modifier.name} ${if (isPressed) "activated" else "deactivated"}")
            }
            else -> {
                // Other modifiers (accents, etc.)
                logD("Text modifier ${modifier.name} ${if (isPressed) "activated" else "deactivated"}")
            }
        }
    }

    /**
     * Handle compose key sequences for diacritics
     */
    private fun handleComposeKey(pendingCompose: Int) {
        logD("Compose key: $pendingCompose")

        // Compose keys are handled by the KeyValue composition system
        // The pending compose state is managed in Pointers
        // When the next character is typed, it will be modified with the compose

        // Get the input connection
        val inputConnection = receiver.getInputConnection() ?: return

        // Show compose indicator (dead key)
        // The actual composition happens when next key is pressed
        logD("Compose sequence initiated, waiting for next character")
    }

    /**
     * Toggle caps lock state
     */
    private fun toggleCapsLock() {
        // Caps lock is handled through the CAPS_LOCK event
        // This sends the actual caps lock key event to the system
        val inputConnection = receiver.getInputConnection() ?: return

        // Send caps lock key event
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK)

        inputConnection.sendKeyEvent(downEvent)
        inputConnection.sendKeyEvent(upEvent)

        // Toggle the shouldCapitalizeNext state
        shouldCapitalizeNext = !shouldCapitalizeNext

        logD("Caps lock toggled, capitalize=$shouldCapitalizeNext")
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
     * Handle backspace with ctrl modifier support
     */
    private fun handleBackspace() {
        val inputConnection = receiver.getInputConnection() ?: return

        // Try to delete selected text first
        val selectedText = inputConnection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            inputConnection.commitText("", 1)
            receiver.performVibration()
            // Clear current word on selection delete
            currentWord.clear()
            updateTapTypingPredictions()
            return
        }

        // Check for ctrl modifier to delete whole word
        if (hasModifier(KeyValue.Modifier.CTRL)) {
            deleteWord(inputConnection)
            currentWord.clear()
        } else {
            // Smart punctuation handles paired characters (Fix for Bug #316 & #361)
            smartPunctuationHandler?.handleBackspace(inputConnection)

            inputConnection.deleteSurroundingText(1, 0)
            // Remove last character from current word
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
            }
        }

        receiver.performVibration()

        // Update tap typing predictions (Fix for Bug #359)
        if (typingPredictionEngine != null) {
            updateTapTypingPredictions()
        }
    }

    /**
     * Delete a whole word (used for ctrl+backspace)
     */
    private fun deleteWord(inputConnection: InputConnection) {
        try {
            // Get text before cursor
            val textBefore = inputConnection.getTextBeforeCursor(100, 0)?.toString() ?: ""

            if (textBefore.isEmpty()) return

            // Find the start of the current word
            var deleteCount = 0
            var foundNonWhitespace = false

            for (i in textBefore.length - 1 downTo 0) {
                val char = textBefore[i]

                if (char.isWhitespace()) {
                    if (foundNonWhitespace) {
                        // Stop at whitespace after finding a word
                        break
                    }
                    // Skip leading whitespace
                    deleteCount++
                } else {
                    foundNonWhitespace = true
                    if (char.isLetterOrDigit() || char == '_') {
                        deleteCount++
                    } else {
                        // Stop at punctuation
                        if (foundNonWhitespace && deleteCount > 0) {
                            break
                        }
                        deleteCount++
                        break
                    }
                }
            }

            if (deleteCount > 0) {
                inputConnection.deleteSurroundingText(deleteCount, 0)
            }
        } catch (e: Exception) {
            logE("Failed to delete word", e)
            // Fallback to single character delete
            inputConnection.deleteSurroundingText(1, 0)
        }
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

        // Finish current word and update predictions (Fix for Bug #359)
        if (typingPredictionEngine != null) {
            finishCurrentWord()
        }

        // Check spelling of the last word (Fix for Bug #311)
        spellCheckHelper?.checkLastWord(inputConnection)
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
     * Check if modifiers contain a specific modifier
     */
    private fun hasModifier(modifier: KeyValue.Modifier): Boolean {
        return mods.contains(modifier)
    }

    /**
     * Update meta state for modifiers
     */
    private fun updateMetaState() {
        metaState = 0

        if (hasModifier(KeyValue.Modifier.SHIFT)) {
            metaState = metaState or KeyEvent.META_SHIFT_ON
        }
        if (hasModifier(KeyValue.Modifier.CTRL)) {
            metaState = metaState or KeyEvent.META_CTRL_ON
        }
        if (hasModifier(KeyValue.Modifier.ALT)) {
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

    override fun pasteFromClipboardPane(content: String) {
        val inputConnection = receiver.getInputConnection()
        inputConnection?.commitText(content, 1)
    }

    override fun started(info: android.view.inputmethod.EditorInfo?) {
        // Initialize keyboard state for new input session
        inputConnection = receiver.getInputConnection()
    }

    // Tap typing prediction helpers (Fix for Bug #359)

    /**
     * Update tap typing predictions based on current input
     */
    private fun updateTapTypingPredictions() {
        val engine = typingPredictionEngine ?: return

        // Throttle updates (max once per 50ms to avoid lag)
        val now = System.currentTimeMillis()
        if (now - lastPredictionUpdate < 50) return
        lastPredictionUpdate = now

        val predictions = if (currentWord.isNotEmpty()) {
            // Get autocomplete + context predictions
            val contextString = contextWords.takeLast(2).joinToString(" ")
            engine.predictWithPrefix(contextString, currentWord.toString(), 5)
        } else {
            // Get next-word predictions based on context
            val contextString = contextWords.takeLast(2).joinToString(" ")
            engine.predictNextWords(contextString, 5)
        }

        // Update suggestion bar
        val suggestionWords = predictions.map { it.word }
        receiver.updateSuggestions(suggestionWords)

        // Announce suggestions for blind users (Fix for Bug #368)
        voiceGuidanceEngine?.announceSuggestions(suggestionWords)

        // Announce suggestions for screen reader users (Fix for Bug #377)
        receiver.getKeyboardView()?.let { view ->
            screenReaderManager?.announceSuggestions(view, suggestionWords)
        }
    }

    /**
     * Finish current word and add to context
     */
    private fun finishCurrentWord() {
        if (currentWord.isNotEmpty()) {
            // Add completed word to context
            contextWords.add(currentWord.toString())

            // Limit context size
            if (contextWords.size > MAX_CONTEXT_WORDS) {
                contextWords.removeAt(0)
            }

            // Clear current word
            currentWord.clear()
        }

        // Update predictions for next word
        updateTapTypingPredictions()
    }

    /**
     * Accept a prediction suggestion
     * Includes user adaptation tracking (Fix for Bug #312)
     */
    fun acceptSuggestion(suggestion: String) {
        val inputConnection = receiver.getInputConnection() ?: return

        // Delete current word
        if (currentWord.isNotEmpty()) {
            inputConnection.deleteSurroundingText(currentWord.length, 0)
            currentWord.clear()
        }

        // Commit suggestion
        inputConnection.commitText(suggestion, 1)

        // Record user selection for adaptation (Fix for Bug #312)
        typingPredictionEngine?.recordUserSelection(suggestion)

        // Add to context
        contextWords.add(suggestion)
        if (contextWords.size > MAX_CONTEXT_WORDS) {
            contextWords.removeAt(0)
        }

        // Update predictions for next word
        updateTapTypingPredictions()
    }
}

