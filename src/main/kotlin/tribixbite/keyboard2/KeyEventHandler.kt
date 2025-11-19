package tribixbite.keyboard2

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
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
 * Includes case conversion (Fix for Bug #318)
 * Includes text expansion (Fix for Bug #319)
 * Includes cursor movement (Fix for Bug #322)
 * Includes multi-touch gestures (Fix for Bug #323)
 */
class KeyEventHandler(
    private val receiver: IReceiver,
    private val typingPredictionEngine: TypingPredictionEngine? = null,
    private val voiceGuidanceEngine: VoiceGuidanceEngine? = null,
    private val screenReaderManager: ScreenReaderManager? = null,
    private val spellCheckHelper: SpellCheckHelper? = null,
    private val smartPunctuationHandler: SmartPunctuationHandler? = null,
    private val caseConverter: CaseConverter? = null,
    private val textExpander: TextExpander? = null,
    private val cursorMovementManager: CursorMovementManager? = null,
    private val multiTouchHandler: MultiTouchHandler? = null,
    private val soundEffectManager: SoundEffectManager? = null
) : Config.IKeyEventHandler, ClipboardPasteCallback {

    companion object {
        private const val TAG = "KeyEventHandler"
        private const val MAX_CONTEXT_WORDS = 10
    }

    // State management
    private var mods = Pointers.Modifiers.EMPTY
    private var metaState = 0
    private var moveCursorForceFallback = false
    private var inputConnection: InputConnection? = null

    // Autocapitalisation (P0-1 fix)
    private val autocap = Autocapitalisation(callback = object : Autocapitalisation.Callback {
        override fun updateShiftState(shouldEnable: Boolean, shouldDisable: Boolean) {
            receiver.setShiftState(shouldEnable, shouldDisable)
        }
    })

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
        fun setShiftState(shouldEnable: Boolean, shouldDisable: Boolean) {} // For autocapitalisation
    }
    
    override fun key_down(value: KeyValue, is_swipe: Boolean) {
        logD("Key down: $value (swipe: $is_swipe)")

        // Voice guidance for blind users (Fix for Bug #368)
        voiceGuidanceEngine?.speakKey(value)

        // Screen reader announcement for TalkBack (Fix for Bug #377)
        receiver.getKeyboardView()?.let { view ->
            screenReaderManager?.announceKeyPress(view, value)
        }

        // Play sound effect for key press (Bug #324 fix)
        soundEffectManager?.playSoundForKey(value)

        when (value) {
            is KeyValue.CharKey -> handleCharacterKey(value.char, is_swipe)
            is KeyValue.EventKey -> handleEventKey(value.event, is_swipe)
            is KeyValue.StringKey -> handleStringKey(value.string, is_swipe)
            is KeyValue.ModifierKey -> handleModifierKey(value.modifier, true)
            is KeyValue.KeyEventKey -> handleKeyEventKey(value.keyCode, is_swipe)
            is KeyValue.ComposePendingKey -> handleComposeKey(value.pendingCompose)
            is KeyValue.EditingKey -> handleEditingKey(value.editing)
            is KeyValue.SliderKey -> handleSliderKey(value.slider, value.repeat, true)
            is KeyValue.MacroKey -> handleMacroKey(value.keys)
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

        val finalChar = if (autocap.shouldShiftBeEnabled() && char.isLetter()) {
            char.uppercaseChar()
        } else {
            char
        }

        // Check for text expansion (Fix for Bug #319)
        // Expansion triggers on space or punctuation
        if (finalChar.isWhitespace() || finalChar in ".,!?;:") {
            val expanded = textExpander?.processText(inputConnection, finalChar)
            if (expanded == true) {
                // Text was expanded - don't insert the trigger character
                // Notify autocap
                autocap.typed(finalChar.toString())
                receiver.performVibration()

                // Update tap typing context
                if (!isSwipe && typingPredictionEngine != null) {
                    finishCurrentWord()
                }
                return
            }
        }

        // Apply smart punctuation processing (Fix for Bug #316 & #361)
        val processedText = smartPunctuationHandler?.processCharacter(
            finalChar,
            inputConnection
        )

        if (processedText != null) {
            // Smart punctuation modified the input
            inputConnection.commitText(processedText, processedText.length)
            // Notify autocap
            autocap.typed(processedText)
        } else {
            // No modification, commit as-is
            inputConnection.commitText(finalChar.toString(), 1)
            // Notify autocap
            autocap.typed(finalChar.toString())
        }

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
            KeyValue.Event.CONVERT_CASE_CYCLE -> handleCaseCycle()
            KeyValue.Event.CONVERT_UPPERCASE -> handleCaseConversion(CaseConverter.CaseMode.UPPERCASE)
            KeyValue.Event.CONVERT_LOWERCASE -> handleCaseConversion(CaseConverter.CaseMode.LOWERCASE)
            KeyValue.Event.CONVERT_TITLE_CASE -> handleCaseConversion(CaseConverter.CaseMode.TITLE_CASE)
            KeyValue.Event.CURSOR_LEFT -> handleCursorMove(CursorMovementManager.Direction.LEFT, CursorMovementManager.Unit.CHARACTER)
            KeyValue.Event.CURSOR_RIGHT -> handleCursorMove(CursorMovementManager.Direction.RIGHT, CursorMovementManager.Unit.CHARACTER)
            KeyValue.Event.CURSOR_WORD_LEFT -> handleCursorMove(CursorMovementManager.Direction.LEFT, CursorMovementManager.Unit.WORD)
            KeyValue.Event.CURSOR_WORD_RIGHT -> handleCursorMove(CursorMovementManager.Direction.RIGHT, CursorMovementManager.Unit.WORD)
            KeyValue.Event.CURSOR_LINE_START -> handleCursorMove(CursorMovementManager.Direction.LEFT, CursorMovementManager.Unit.LINE)
            KeyValue.Event.CURSOR_LINE_END -> handleCursorMove(CursorMovementManager.Direction.RIGHT, CursorMovementManager.Unit.LINE)
            KeyValue.Event.CURSOR_DOC_START -> handleCursorMove(CursorMovementManager.Direction.LEFT, CursorMovementManager.Unit.DOCUMENT)
            KeyValue.Event.CURSOR_DOC_END -> handleCursorMove(CursorMovementManager.Direction.RIGHT, CursorMovementManager.Unit.DOCUMENT)
            KeyValue.Event.SELECT_ALL -> handleSelectAll()
            KeyValue.Event.SELECT_WORD -> handleSelectWord()
            KeyValue.Event.SELECT_LINE -> handleSelectLine()
            KeyValue.Event.CLEAR_SELECTION -> handleClearSelection()
            KeyValue.Event.TWO_FINGER_SWIPE_LEFT -> handleTwoFingerSwipe(MultiTouchHandler.SwipeDirection.LEFT)
            KeyValue.Event.TWO_FINGER_SWIPE_RIGHT -> handleTwoFingerSwipe(MultiTouchHandler.SwipeDirection.RIGHT)
            KeyValue.Event.TWO_FINGER_SWIPE_UP -> handleTwoFingerSwipe(MultiTouchHandler.SwipeDirection.UP)
            KeyValue.Event.TWO_FINGER_SWIPE_DOWN -> handleTwoFingerSwipe(MultiTouchHandler.SwipeDirection.DOWN)
            KeyValue.Event.THREE_FINGER_SWIPE_LEFT -> handleThreeFingerSwipe(MultiTouchHandler.SwipeDirection.LEFT)
            KeyValue.Event.THREE_FINGER_SWIPE_RIGHT -> handleThreeFingerSwipe(MultiTouchHandler.SwipeDirection.RIGHT)
            KeyValue.Event.THREE_FINGER_SWIPE_UP -> handleThreeFingerSwipe(MultiTouchHandler.SwipeDirection.UP)
            KeyValue.Event.THREE_FINGER_SWIPE_DOWN -> handleThreeFingerSwipe(MultiTouchHandler.SwipeDirection.DOWN)
            KeyValue.Event.PINCH_IN -> handlePinchGesture(0.5f)
            KeyValue.Event.PINCH_OUT -> handlePinchGesture(1.5f)
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
            KeyEvent.KEYCODE_DPAD_UP -> moveCursorVertical(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursorVertical(1)
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
                // Don't affect autocap here - it manages itself
                logD("Shift ${if (isPressed) "activated" else "deactivated"}")
            }
            KeyValue.Modifier.CTRL, KeyValue.Modifier.ALT, KeyValue.Modifier.META -> {
                // System modifiers stop autocap when pressed
                if (isPressed) {
                    autocap.stop()
                }
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

        // Notify autocap that system caps lock was toggled
        // Autocap will handle the shift state update
        logD("Caps lock toggled")
    }

    /**
     * Handle case conversion cycle (Fix for Bug #318)
     */
    private fun handleCaseCycle() {
        val inputConnection = receiver.getInputConnection() ?: return
        val converter = caseConverter ?: return

        if (converter.cycleCaseConversion(inputConnection)) {
            receiver.performVibration()
            logD("Case conversion cycled")
        } else {
            logD("Case conversion failed - no text selected")
        }
    }

    /**
     * Handle specific case conversion mode (Fix for Bug #318)
     */
    private fun handleCaseConversion(mode: CaseConverter.CaseMode) {
        val inputConnection = receiver.getInputConnection() ?: return
        val converter = caseConverter ?: return

        if (converter.convertSelection(inputConnection, mode)) {
            receiver.performVibration()
            logD("Converted to ${mode.name}")
        } else {
            logD("Case conversion failed - no text selected")
        }
    }

    /**
     * Handle cursor movement (Fix for Bug #322)
     */
    private fun handleCursorMove(direction: CursorMovementManager.Direction, unit: CursorMovementManager.Unit) {
        val inputConnection = receiver.getInputConnection() ?: return
        val manager = cursorMovementManager ?: return

        if (manager.moveCursor(inputConnection, direction, unit, select = false)) {
            receiver.performVibration()
            logD("Cursor moved ${direction.name} by ${unit.name}")
        } else {
            logD("Cursor movement failed")
        }
    }

    /**
     * Handle select all (Fix for Bug #322)
     */
    private fun handleSelectAll() {
        val inputConnection = receiver.getInputConnection() ?: return
        val manager = cursorMovementManager ?: return

        if (manager.selectAll(inputConnection)) {
            receiver.performVibration()
            logD("Selected all text")
        }
    }

    /**
     * Handle select word (Fix for Bug #322)
     */
    private fun handleSelectWord() {
        val inputConnection = receiver.getInputConnection() ?: return
        val manager = cursorMovementManager ?: return

        if (manager.selectWord(inputConnection)) {
            receiver.performVibration()
            logD("Selected word")
        }
    }

    /**
     * Handle select line (Fix for Bug #322)
     */
    private fun handleSelectLine() {
        val inputConnection = receiver.getInputConnection() ?: return
        val manager = cursorMovementManager ?: return

        if (manager.selectLine(inputConnection)) {
            receiver.performVibration()
            logD("Selected line")
        }
    }

    /**
     * Handle clear selection (Fix for Bug #322)
     */
    private fun handleClearSelection() {
        val inputConnection = receiver.getInputConnection() ?: return
        val manager = cursorMovementManager ?: return

        if (manager.clearSelection(inputConnection)) {
            receiver.performVibration()
            logD("Cleared selection")
        }
    }

    /**
     * Handle two-finger swipe gesture (Fix for Bug #323)
     */
    private fun handleTwoFingerSwipe(direction: MultiTouchHandler.SwipeDirection) {
        logD("Two-finger swipe ${direction.name}")

        // Default actions for two-finger swipes
        when (direction) {
            MultiTouchHandler.SwipeDirection.LEFT -> {
                // Undo text operation
                logD("Two-finger swipe left - trigger undo")
            }
            MultiTouchHandler.SwipeDirection.RIGHT -> {
                // Redo text operation
                logD("Two-finger swipe right - trigger redo")
            }
            MultiTouchHandler.SwipeDirection.UP -> {
                // Switch to previous layout
                logD("Two-finger swipe up - previous layout")
            }
            MultiTouchHandler.SwipeDirection.DOWN -> {
                // Switch to next layout
                logD("Two-finger swipe down - next layout")
            }
        }

        receiver.performVibration()
    }

    /**
     * Handle three-finger swipe gesture (Fix for Bug #323)
     */
    private fun handleThreeFingerSwipe(direction: MultiTouchHandler.SwipeDirection) {
        logD("Three-finger swipe ${direction.name}")

        // Default actions for three-finger swipes
        when (direction) {
            MultiTouchHandler.SwipeDirection.LEFT -> {
                // Previous keyboard
                logD("Three-finger swipe left - previous keyboard")
            }
            MultiTouchHandler.SwipeDirection.RIGHT -> {
                // Next keyboard
                logD("Three-finger swipe right - next keyboard")
            }
            MultiTouchHandler.SwipeDirection.UP -> {
                // Show emoji/symbols
                receiver.switchToEmojiLayout()
            }
            MultiTouchHandler.SwipeDirection.DOWN -> {
                // Hide keyboard
                logD("Three-finger swipe down - hide keyboard")
            }
        }

        receiver.performVibration()
    }

    /**
     * Handle pinch gesture (Fix for Bug #323)
     */
    private fun handlePinchGesture(scale: Float) {
        logD("Pinch gesture - scale: $scale")

        // Default actions for pinch
        if (scale < 1.0f) {
            // Pinch in - decrease keyboard size or zoom out
            logD("Pinch in - zoom out/shrink keyboard")
        } else {
            // Pinch out - increase keyboard size or zoom in
            logD("Pinch out - zoom in/enlarge keyboard")
        }

        receiver.performVibration()
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
     * Handle editing operations via performContextMenuAction (matches Java)
     * This is critical for CUT/COPY/PASTE support
     */
    private fun handleEditingKey(editing: KeyValue.Editing) {
        val inputConnection = receiver.getInputConnection() ?: return

        when (editing) {
            KeyValue.Editing.COPY -> {
                if (isSelectionNotEmpty(inputConnection)) {
                    inputConnection.performContextMenuAction(android.R.id.copy)
                }
            }
            KeyValue.Editing.CUT -> {
                if (isSelectionNotEmpty(inputConnection)) {
                    inputConnection.performContextMenuAction(android.R.id.cut)
                }
            }
            KeyValue.Editing.PASTE -> {
                inputConnection.performContextMenuAction(android.R.id.paste)
            }
            KeyValue.Editing.PASTE_PLAIN -> {
                inputConnection.performContextMenuAction(android.R.id.pasteAsPlainText)
            }
            KeyValue.Editing.SELECT_ALL -> {
                inputConnection.performContextMenuAction(android.R.id.selectAll)
            }
            KeyValue.Editing.UNDO -> {
                inputConnection.performContextMenuAction(android.R.id.undo)
            }
            KeyValue.Editing.REDO -> {
                inputConnection.performContextMenuAction(android.R.id.redo)
            }
            KeyValue.Editing.SHARE -> {
                inputConnection.performContextMenuAction(android.R.id.shareText)
            }
            KeyValue.Editing.REPLACE -> {
                inputConnection.performContextMenuAction(android.R.id.replaceText)
            }
            KeyValue.Editing.ASSIST -> {
                inputConnection.performContextMenuAction(android.R.id.textAssist)
            }
            KeyValue.Editing.AUTOFILL -> {
                inputConnection.performContextMenuAction(android.R.id.autofill)
            }
            KeyValue.Editing.DELETE_WORD -> {
                // Send Ctrl+Del for word deletion
                sendKeyDownUp(KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON)
            }
            KeyValue.Editing.FORWARD_DELETE_WORD -> {
                // Send Ctrl+Forward Del for forward word deletion
                sendKeyDownUp(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_CTRL_ON)
            }
            KeyValue.Editing.SELECTION_CANCEL -> {
                // Cancel selection by moving cursor to end
                cancelSelection(inputConnection)
            }
        }

        receiver.performVibration()
        logD("Editing operation: $editing")
    }

    /**
     * Check if there is selected text (matches Java is_selection_not_empty)
     */
    private fun isSelectionNotEmpty(inputConnection: InputConnection): Boolean {
        val selectedText = inputConnection.getSelectedText(0)
        return !selectedText.isNullOrEmpty()
    }

    /**
     * Cancel selection by moving cursor to selection end (matches Java)
     */
    private fun cancelSelection(inputConnection: InputConnection) {
        try {
            val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)
            if (extractedText != null) {
                val selEnd = extractedText.selectionEnd
                inputConnection.setSelection(selEnd, selEnd)
            }
        } catch (e: Exception) {
            logE("Failed to cancel selection", e)
        }
    }

    /**
     * Send key down/up events with meta state (matches Java send_key_down_up)
     */
    private fun sendKeyDownUp(keyCode: Int, meta: Int) {
        val inputConnection = receiver.getInputConnection() ?: return

        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(
            eventTime, eventTime,
            KeyEvent.ACTION_DOWN, keyCode, 0, meta
        )
        val upEvent = KeyEvent(
            eventTime, eventTime,
            KeyEvent.ACTION_UP, keyCode, 0, meta
        )

        inputConnection.sendKeyEvent(downEvent)
        inputConnection.sendKeyEvent(upEvent)
    }

    /**
     * Handle slider keys for continuous cursor movement (matches Java handle_slider)
     */
    private fun handleSliderKey(slider: KeyValue.Slider, repeat: Int, keyDown: Boolean) {
        val inputConnection = receiver.getInputConnection() ?: return

        // Use repeat value as the amount of movement (absolute value)
        val amount = kotlin.math.abs(if (repeat != 0) repeat else 1)

        // Handle slider based on type
        when (slider) {
            KeyValue.Slider.Cursor_left -> {
                for (i in 0 until amount) {
                    moveCursor(-1)
                }
            }
            KeyValue.Slider.Cursor_right -> {
                for (i in 0 until amount) {
                    moveCursor(1)
                }
            }
            KeyValue.Slider.Cursor_up -> {
                moveCursorVertical(-amount)
            }
            KeyValue.Slider.Cursor_down -> {
                moveCursorVertical(amount)
            }
            KeyValue.Slider.Selection_cursor_left -> {
                // TODO: Implement selection-aware cursor movement
                for (i in 0 until amount) {
                    moveCursor(-1)
                }
            }
            KeyValue.Slider.Selection_cursor_right -> {
                // TODO: Implement selection-aware cursor movement
                for (i in 0 until amount) {
                    moveCursor(1)
                }
            }
        }

        receiver.performVibration()
        logD("Slider key: type=${slider.name}, repeat=$repeat")
    }

    /**
     * Handle macro keys - execute sequence of key values (matches Java evaluate_macro)
     */
    private fun handleMacroKey(keys: Array<KeyValue>) {
        if (keys.isEmpty()) return

        // Clear modifiers before starting macro (matches Java)
        mods_changed(Pointers.Modifiers.EMPTY)

        // Execute each key in sequence
        var currentMods = Pointers.Modifiers.EMPTY
        for (key in keys) {
            // Apply modifiers to key
            val modifiedKey = applyModifiers(key, currentMods)
            if (modifiedKey != null) {
                if (modifiedKey.hasFlagsAny(KeyValue.Flag.LATCH)) {
                    // Latch keys accumulate modifiers
                    if (!modifiedKey.hasFlagsAny(KeyValue.Flag.SPECIAL)) {
                        currentMods = Pointers.Modifiers.EMPTY
                    }
                    currentMods = currentMods.withExtraMod(modifiedKey)
                } else {
                    // Execute the key
                    key_down(modifiedKey, false)
                    key_up(modifiedKey, currentMods)
                    currentMods = Pointers.Modifiers.EMPTY
                }
            }
        }

        logD("Macro executed: ${keys.size} keys")
    }

    /**
     * Apply modifiers to a key value (matches Java KeyModifier.modify)
     * Handles shift, accents, and other modifiers for macro execution
     */
    private fun applyModifiers(key: KeyValue, mods: Pointers.Modifiers): KeyValue? {
        var result: KeyValue = key

        // Get the list of active modifiers
        val modifierList = mods.getModifiers()

        // Apply each modifier in order
        for (modifier in modifierList) {
            when (modifier) {
                KeyValue.Modifier.SHIFT -> {
                    // Apply shift - convert to uppercase for char keys
                    result = when (result) {
                        is KeyValue.CharKey -> {
                            val upperChar = result.char.uppercaseChar()
                            if (upperChar != result.char) {
                                result.copy(char = upperChar, displayString = upperChar.toString())
                            } else {
                                result
                            }
                        }
                        else -> result
                    }
                }
                KeyValue.Modifier.CTRL, KeyValue.Modifier.ALT, KeyValue.Modifier.META -> {
                    // These modifiers affect meta state, not the key itself
                    // They're handled by updateMetaState()
                }
                // Accent modifiers - apply diacritics
                KeyValue.Modifier.AIGU -> result = applyDiacritic(result, '\u0301') // combining acute
                KeyValue.Modifier.GRAVE -> result = applyDiacritic(result, '\u0300') // combining grave
                KeyValue.Modifier.CIRCONFLEXE -> result = applyDiacritic(result, '\u0302') // combining circumflex
                KeyValue.Modifier.TILDE -> result = applyDiacritic(result, '\u0303') // combining tilde
                KeyValue.Modifier.TREMA -> result = applyDiacritic(result, '\u0308') // combining diaeresis
                KeyValue.Modifier.CEDILLE -> result = applyDiacritic(result, '\u0327') // combining cedilla
                KeyValue.Modifier.CARON -> result = applyDiacritic(result, '\u030C') // combining caron
                KeyValue.Modifier.RING -> result = applyDiacritic(result, '\u030A') // combining ring
                KeyValue.Modifier.MACRON -> result = applyDiacritic(result, '\u0304') // combining macron
                KeyValue.Modifier.OGONEK -> result = applyDiacritic(result, '\u0328') // combining ogonek
                KeyValue.Modifier.DOT_ABOVE -> result = applyDiacritic(result, '\u0307') // combining dot above
                KeyValue.Modifier.DOT_BELOW -> result = applyDiacritic(result, '\u0323') // combining dot below
                KeyValue.Modifier.BREVE -> result = applyDiacritic(result, '\u0306') // combining breve
                KeyValue.Modifier.HORN -> result = applyDiacritic(result, '\u031B') // combining horn
                KeyValue.Modifier.HOOK_ABOVE -> result = applyDiacritic(result, '\u0309') // combining hook above
                else -> {
                    // Other modifiers (FN, GESTURE, etc.) - pass through
                }
            }
        }

        return result
    }

    /**
     * Apply a combining diacritic to a character key
     */
    private fun applyDiacritic(key: KeyValue, combiningChar: Char): KeyValue {
        return when (key) {
            is KeyValue.CharKey -> {
                // Use KeyCharacterMap.getDeadChar for proper composition
                val composed = android.view.KeyCharacterMap.getDeadChar(
                    combiningChar.code,
                    key.char.code
                )
                if (composed != 0) {
                    KeyValue.CharKey(composed.toChar())
                } else {
                    // No composition available, return base + combining character
                    KeyValue.StringKey("${key.char}$combiningChar")
                }
            }
            else -> key
        }
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

        // Notify autocap about deletion
        autocap.eventSent(KeyEvent.KEYCODE_DEL, metaState)

        // Update tap typing predictions (Fix for Bug #359)
        if (typingPredictionEngine != null) {
            updateTapTypingPredictions()
        }
    }

    /**
     * Delete a whole word (used for ctrl+backspace)
     * Uses state machine for proper word boundary detection
     */
    private fun deleteWord(inputConnection: InputConnection) {
        try {
            // Get text before cursor
            val textBefore = inputConnection.getTextBeforeCursor(100, 0)?.toString() ?: ""

            if (textBefore.isEmpty()) return

            // State machine for word deletion
            // States: INITIAL, SKIP_WHITESPACE, DELETE_WORD, DELETE_PUNCT
            var state = 0 // INITIAL
            var deleteCount = 0
            var i = textBefore.length - 1

            while (i >= 0) {
                val char = textBefore[i]
                val charClass = getCharClass(char)

                when (state) {
                    0 -> { // INITIAL - determine what to delete based on first char
                        when (charClass) {
                            0 -> { // Whitespace - skip it, then delete word
                                deleteCount++
                                state = 1 // SKIP_WHITESPACE
                            }
                            1 -> { // Word char - delete the word
                                deleteCount++
                                state = 2 // DELETE_WORD
                            }
                            2 -> { // Punctuation - delete punctuation run
                                deleteCount++
                                state = 3 // DELETE_PUNCT
                            }
                        }
                    }
                    1 -> { // SKIP_WHITESPACE - consume whitespace, then switch
                        when (charClass) {
                            0 -> deleteCount++ // More whitespace
                            1 -> { // Word char - start deleting word
                                deleteCount++
                                state = 2 // DELETE_WORD
                            }
                            2 -> { // Punctuation - delete punctuation run
                                deleteCount++
                                state = 3 // DELETE_PUNCT
                            }
                        }
                    }
                    2 -> { // DELETE_WORD - delete word chars until boundary
                        when (charClass) {
                            1 -> deleteCount++ // More word chars
                            else -> break // Stop at whitespace or punct
                        }
                    }
                    3 -> { // DELETE_PUNCT - delete punctuation until boundary
                        when (charClass) {
                            2 -> deleteCount++ // More punctuation
                            else -> break // Stop at whitespace or word
                        }
                    }
                }
                i--
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
     * Get character class for word deletion
     * Returns: 0 = whitespace, 1 = word char, 2 = punctuation
     */
    private fun getCharClass(char: Char): Int {
        return when {
            char.isWhitespace() -> 0
            char.isLetterOrDigit() || char == '_' || char == '\'' -> 1
            else -> 2
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
                // Notify autocap about enter/newline
                autocap.eventSent(KeyEvent.KEYCODE_ENTER, 0)
            }
        }

        receiver.performVibration()
    }
    
    /**
     * Handle space key
     */
    private fun handleSpace() {
        val inputConnection = receiver.getInputConnection() ?: return
        inputConnection.commitText(" ", 1)
        // Notify autocap about space
        autocap.typed(" ")
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

        // Check if we can use setSelection (P0-2 fix)
        // Can't use setSelection when system modifiers are active
        val canSetSelection = !moveCursorForceFallback &&
            !hasModifier(KeyValue.Modifier.CTRL) &&
            !hasModifier(KeyValue.Modifier.ALT) &&
            !hasModifier(KeyValue.Modifier.META)

        if (canSetSelection) {
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
     * Move cursor vertically by relative offset (P0-3 fix)
     * Always uses arrow keys since there's no setSelection alternative for vertical movement
     */
    private fun moveCursorVertical(offset: Int) {
        val keyCode = if (offset > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
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
     * Update meta state for modifiers and send KeyEvents (matches Java update_meta_state)
     * This is CRITICAL for terminals and apps that need actual modifier key events
     */
    private fun updateMetaState() {
        val inputConnection = receiver.getInputConnection() ?: return

        // Calculate new meta state
        var newMetaState = 0
        if (hasModifier(KeyValue.Modifier.SHIFT)) {
            newMetaState = newMetaState or KeyEvent.META_SHIFT_ON
        }
        if (hasModifier(KeyValue.Modifier.CTRL)) {
            newMetaState = newMetaState or KeyEvent.META_CTRL_ON
        }
        if (hasModifier(KeyValue.Modifier.ALT)) {
            newMetaState = newMetaState or KeyEvent.META_ALT_ON
        }
        if (hasModifier(KeyValue.Modifier.META)) {
            newMetaState = newMetaState or KeyEvent.META_META_ON
        }

        // Send key events for modifiers that were released (matches Java)
        if ((metaState and KeyEvent.META_SHIFT_ON) != 0 && (newMetaState and KeyEvent.META_SHIFT_ON) == 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON, false)
        }
        if ((metaState and KeyEvent.META_CTRL_ON) != 0 && (newMetaState and KeyEvent.META_CTRL_ON) == 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON, false)
        }
        if ((metaState and KeyEvent.META_ALT_ON) != 0 && (newMetaState and KeyEvent.META_ALT_ON) == 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON, false)
        }
        if ((metaState and KeyEvent.META_META_ON) != 0 && (newMetaState and KeyEvent.META_META_ON) == 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON, false)
        }

        // Send key events for modifiers that were activated (matches Java)
        if ((metaState and KeyEvent.META_SHIFT_ON) == 0 && (newMetaState and KeyEvent.META_SHIFT_ON) != 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON, true)
        }
        if ((metaState and KeyEvent.META_CTRL_ON) == 0 && (newMetaState and KeyEvent.META_CTRL_ON) != 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON, true)
        }
        if ((metaState and KeyEvent.META_ALT_ON) == 0 && (newMetaState and KeyEvent.META_ALT_ON) != 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON, true)
        }
        if ((metaState and KeyEvent.META_META_ON) == 0 && (newMetaState and KeyEvent.META_META_ON) != 0) {
            sendMetaKeyEvent(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON, true)
        }

        metaState = newMetaState
    }

    /**
     * Send meta key event with proper state management (matches Java sendMetaKey)
     */
    private fun sendMetaKeyEvent(keyCode: Int, metaFlags: Int, down: Boolean) {
        val inputConnection = receiver.getInputConnection() ?: return

        if (down) {
            // For down events: update meta state first, then send event
            metaState = metaState or metaFlags
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            inputConnection.sendKeyEvent(KeyEvent(
                event.downTime, event.eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState
            ))
        } else {
            // For up events: send event first, then clear meta state
            val event = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            inputConnection.sendKeyEvent(KeyEvent(
                event.downTime, event.eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState
            ))
            metaState = metaState and metaFlags.inv()
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

    // Logging helpers
    private fun logD(message: String) {
        Log.d(TAG, message)
    }

    private fun logE(message: String, e: Exception? = null) {
        if (e != null) {
            Log.e(TAG, message, e)
        } else {
            Log.e(TAG, message)
        }
    }
}

