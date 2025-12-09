package tribixbite.cleverkeys

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

class KeyEventHandler(
    private val recv: IReceiver
) : Config.IKeyEventHandler, ClipboardHistoryService.ClipboardPasteCallback {

    private val autocap: Autocapitalisation = Autocapitalisation(
        recv.getHandler(),
        AutocapitalisationCallback()
    )

    /** State of the system modifiers. It is updated whether a modifier is down
     * or up and a corresponding key event is sent. */
    private var mods: Pointers.Modifiers = Pointers.Modifiers.EMPTY

    /** Consistent with [mods]. This is a mutable state rather than computed
     * from [mods] to ensure that the meta state is correct while up and down
     * events are sent for the modifier keys. */
    private var metaState = 0

    /** Whether to force sending arrow keys to move the cursor when
     * [setSelection] could be used instead. */
    private var moveCursorForceFallback = false

    /** Track last typed character and timestamp for double-space-to-period feature */
    private var lastTypedChar: Char = '\u0000'
    private var lastTypedTimestamp: Long = 0L
    // Use configurable threshold from settings
    private val doubleSpaceThresholdMs: Long
        get() = Config.globalConfig().double_space_threshold

    /** Editing just started. */
    fun started(info: EditorInfo) {
        val conn = recv.getCurrentInputConnection()
        if (conn != null) {
            autocap.started(info, conn)
        }
        moveCursorForceFallback = shouldMoveCursorForceFallback(info)
    }

    /** Selection has been updated. */
    fun selection_updated(oldSelStart: Int, newSelStart: Int) {
        autocap.selection_updated(oldSelStart, newSelStart)
    }

    /** A key is being pressed. There will not necessarily be a corresponding
     * [keyUp] event. */
    override fun key_down(key: KeyValue?, isSwipe: Boolean) {
        if (key == null) return

        // Stop auto capitalisation when pressing some keys
        when (key.getKind()) {
            KeyValue.Kind.Modifier -> {
                when (key.getModifier()) {
                    KeyValue.Modifier.CTRL,
                    KeyValue.Modifier.ALT,
                    KeyValue.Modifier.META -> autocap.stop()
                    else -> {}
                }
            }
            KeyValue.Kind.Compose_pending -> autocap.stop()
            KeyValue.Kind.Slider -> {
                // Don't wait for the next key_up and move the cursor right away. This
                // is called after the trigger distance have been travelled.
                handleSlider(key.getSlider(), key.getSliderRepeat(), true)
            }
            else -> {}
        }
    }

    /** A key has been released. */
    override fun key_up(key: KeyValue?, mods: Pointers.Modifiers) {
        if (key == null) return

        val oldMods = this.mods
        updateMetaState(mods)

        when (key.getKind()) {
            KeyValue.Kind.Char -> sendText(key.getChar().toString())
            KeyValue.Kind.String -> sendText(key.getString())
            KeyValue.Kind.Event -> recv.handle_event_key(key.getEvent())
            KeyValue.Kind.Keyevent -> {
                // Handle backspace in clipboard search mode
                if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isClipboardSearchMode()) {
                    recv.backspaceClipboardSearch()
                } else {
                    send_key_down_up(key.getKeyevent())
                    // Handle backspace for word prediction
                    if (key.getKeyevent() == KeyEvent.KEYCODE_DEL) {
                        recv.handle_backspace()
                    }
                }
            }
            KeyValue.Kind.Modifier -> {}
            KeyValue.Kind.Editing -> handleEditingKey(key.getEditing())
            KeyValue.Kind.Compose_pending -> recv.set_compose_pending(true)
            KeyValue.Kind.Slider -> handleSlider(key.getSlider(), key.getSliderRepeat(), false)
            KeyValue.Kind.Macro -> evaluateMacro(key.getMacro())
            else -> {} // Handle Hangul_initial, Hangul_medial, Placeholder
        }

        updateMetaState(oldMods)
    }

    override fun mods_changed(mods: Pointers.Modifiers) {
        updateMetaState(mods)
    }

    override fun paste_from_clipboard_pane(content: String) {
        // Exit clipboard search mode before pasting to target field
        // Otherwise send_text routes to search box instead of target
        if (recv.isClipboardSearchMode()) {
            recv.exitClipboardSearchMode()
        }
        sendText(content)
    }

    /** Update [mods] to be consistent with the [mods], sending key events if needed. */
    private fun updateMetaState(mods: Pointers.Modifiers) {
        // Released modifiers
        var it = this.mods.diff(mods)
        while (it.hasNext()) {
            sendMetaKeyForModifier(it.next(), false)
        }
        // Activated modifiers
        it = mods.diff(this.mods)
        while (it.hasNext()) {
            sendMetaKeyForModifier(it.next(), true)
        }
        this.mods = mods
    }

    private fun sendMetaKey(eventCode: Int, metaFlags: Int, down: Boolean) {
        if (down) {
            metaState = metaState or metaFlags
            sendKeyevent(KeyEvent.ACTION_DOWN, eventCode, metaState)
        } else {
            sendKeyevent(KeyEvent.ACTION_UP, eventCode, metaState)
            metaState = metaState and metaFlags.inv()
        }
    }

    private fun sendMetaKeyForModifier(kv: KeyValue, down: Boolean) {
        when (kv.getKind()) {
            KeyValue.Kind.Modifier -> {
                when (kv.getModifier()) {
                    KeyValue.Modifier.CTRL -> sendMetaKey(
                        KeyEvent.KEYCODE_CTRL_LEFT,
                        KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON,
                        down
                    )
                    KeyValue.Modifier.ALT -> sendMetaKey(
                        KeyEvent.KEYCODE_ALT_LEFT,
                        KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON,
                        down
                    )
                    KeyValue.Modifier.SHIFT -> sendMetaKey(
                        KeyEvent.KEYCODE_SHIFT_LEFT,
                        KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON,
                        down
                    )
                    KeyValue.Modifier.META -> sendMetaKey(
                        KeyEvent.KEYCODE_META_LEFT,
                        KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_ON,
                        down
                    )
                    else -> {}
                }
            }
            else -> {}
        }
    }

    fun send_key_down_up(keyCode: Int) {
        send_key_down_up(keyCode, metaState)
    }

    /** Ignores currently pressed system modifiers. */
    fun send_key_down_up(keyCode: Int, metaState: Int) {
        sendKeyevent(KeyEvent.ACTION_DOWN, keyCode, metaState)
        sendKeyevent(KeyEvent.ACTION_UP, keyCode, metaState)
    }

    private fun sendKeyevent(eventAction: Int, eventCode: Int, metaState: Int) {
        val conn = recv.getCurrentInputConnection() ?: return
        conn.sendKeyEvent(
            KeyEvent(
                1, 1, eventAction, eventCode, 0,
                metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        if (eventAction == KeyEvent.ACTION_UP) {
            autocap.event_sent(eventCode, metaState)
        }
    }

    private fun sendText(text: CharSequence) {
        // Route to clipboard search box if in search mode
        if (recv.isClipboardSearchMode()) {
            recv.appendToClipboardSearch(text.toString())
            return
        }

        val conn = recv.getCurrentInputConnection() ?: return

        // Double-space-to-period: If typing space after space within threshold, replace with ". "
        val currentTime = System.currentTimeMillis()
        var textToCommit = text
        if (text.length == 1 && text[0] == ' ' && lastTypedChar == ' ' &&
            (currentTime - lastTypedTimestamp) < doubleSpaceThresholdMs) {
            // Delete the previous space and insert ". "
            conn.deleteSurroundingText(1, 0)
            textToCommit = ". "
            // Reset tracking to prevent triple-space weirdness
            lastTypedChar = '.'
        } else if (text.length == 1) {
            val char = text[0]

            // Smart punctuation: If typing punctuation and previous char is space, delete the space
            // This attaches punctuation to the end of the previous word (e.g., "word ." -> "word.")
            val smartPuncEnabled = Config.globalConfig().smart_punctuation
            val isPunctChar = isSmartPunctuationChar(char)
            val isQuote = isQuoteChar(char)

            if (smartPuncEnabled && (isPunctChar || isQuote)) {
                val textBefore = conn.getTextBeforeCursor(10, 0)  // Get more context for quote logic
                val lastCharIsSpace = textBefore?.lastOrNull() == ' '

                if (isPunctChar && lastCharIsSpace) {
                    // Regular punctuation: always attach to previous word
                    conn.deleteSurroundingText(1, 0)
                } else if (isQuote && lastCharIsSpace) {
                    // Quote handling: only attach if it's a CLOSING quote, not apostrophe
                    if (!isLikelyApostrophe(char, textBefore?.dropLast(1))) {
                        // Not an apostrophe - check if closing quote
                        if (isClosingQuote(char, textBefore)) {
                            // Closing quote: delete space to attach to word
                            conn.deleteSurroundingText(1, 0)
                        }
                        // Opening quote: keep the space (do nothing)
                    }
                    // Apostrophe: just insert normally (handled by contraction logic elsewhere)
                }
            }

            lastTypedChar = char
        } else {
            lastTypedChar = '\u0000' // Reset on multi-char input
        }
        lastTypedTimestamp = currentTime

        conn.commitText(textToCommit, 1)
        autocap.typed(textToCommit)
        recv.handle_text_typed(textToCommit.toString())
    }

    /** Characters that should attach to the previous word (smart punctuation). */
    private fun isSmartPunctuationChar(c: Char): Boolean {
        return when (c) {
            '.', ',', '!', '?', ';', ':', ')', ']', '}' -> true
            // Quotes handled separately by isClosingQuote()
            '\'', '"' -> false
            else -> false
        }
    }

    /**
     * Determines if a quote character is likely an apostrophe (contractions/possessives).
     * Apostrophes should NOT trigger smart punctuation spacing logic.
     * Examples: don't, it's, John's, 90's
     */
    private fun isLikelyApostrophe(quote: Char, textBefore: CharSequence?): Boolean {
        if (quote != '\'') return false
        if (textBefore.isNullOrEmpty()) return false
        val lastChar = textBefore.last()
        // Apostrophe if preceded by letter or digit (contractions, possessives, decades)
        return lastChar.isLetterOrDigit()
    }

    /**
     * Determines if a quote should attach to the previous word (closing quote).
     * Opening quotes should have space before them; closing quotes should not.
     *
     * Heuristic: A quote is closing if preceded by a letter/digit (possibly with trailing space).
     * Example: 'hello "' + '"' → closing (delete space, attach quote)
     * Example: 'said "' + opening quote already there, now typing word...
     */
    private fun isClosingQuote(quote: Char, textBefore: CharSequence?): Boolean {
        if (quote != '\'' && quote != '"') return false
        if (textBefore.isNullOrEmpty()) return false

        val trimmed = textBefore.trimEnd()
        if (trimmed.isEmpty()) return false

        val lastChar = trimmed.last()
        // Closing if preceded by letter/digit (the word being quoted)
        return lastChar.isLetterOrDigit()
    }

    /** Check if quote character needs smart punctuation handling. */
    private fun isQuoteChar(c: Char): Boolean = c == '\'' || c == '"'

    /** See {!InputConnection.performContextMenuAction}. */
    private fun sendContextMenuAction(id: Int) {
        val conn = recv.getCurrentInputConnection() ?: return
        conn.performContextMenuAction(id)
    }

    @SuppressLint("InlinedApi")
    private fun handleEditingKey(ev: KeyValue.Editing) {
        when (ev) {
            KeyValue.Editing.COPY -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.copy)
            KeyValue.Editing.PASTE -> sendContextMenuAction(android.R.id.paste)
            KeyValue.Editing.CUT -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.cut)
            KeyValue.Editing.SELECT_ALL -> sendContextMenuAction(android.R.id.selectAll)
            KeyValue.Editing.SHARE -> sendContextMenuAction(android.R.id.shareText)
            KeyValue.Editing.PASTE_PLAIN -> sendContextMenuAction(android.R.id.pasteAsPlainText)
            KeyValue.Editing.UNDO -> sendContextMenuAction(android.R.id.undo)
            KeyValue.Editing.REDO -> sendContextMenuAction(android.R.id.redo)
            KeyValue.Editing.REPLACE -> sendContextMenuAction(android.R.id.replaceText)
            KeyValue.Editing.ASSIST -> sendContextMenuAction(android.R.id.textAssist)
            KeyValue.Editing.AUTOFILL -> sendContextMenuAction(android.R.id.autofill)
            KeyValue.Editing.DELETE_WORD -> send_key_down_up(
                KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            KeyValue.Editing.FORWARD_DELETE_WORD -> send_key_down_up(
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            KeyValue.Editing.SELECTION_CANCEL -> cancelSelection()
            KeyValue.Editing.DELETE_LAST_WORD -> recv.handle_delete_last_word()
            KeyValue.Editing.CURSOR_DOC_START -> send_key_down_up(
                KeyEvent.KEYCODE_MOVE_HOME,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
            KeyValue.Editing.CURSOR_DOC_END -> send_key_down_up(
                KeyEvent.KEYCODE_MOVE_END,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            )
        }
    }

    /** Query the cursor position. The extracted text is empty. Returns [null] if
     * the editor doesn't support this operation. */
    private fun getCursorPos(conn: InputConnection): ExtractedText? {
        if (moveCursorReq == null) {
            moveCursorReq = ExtractedTextRequest()
            moveCursorReq!!.hintMaxChars = 0
        }
        return conn.getExtractedText(moveCursorReq, 0)
    }

    /** [r] might be negative, in which case the direction is reversed. */
    private fun handleSlider(s: KeyValue.Slider, r: Int, keyDown: Boolean) {
        when (s) {
            KeyValue.Slider.Cursor_left -> moveCursor(-r)
            KeyValue.Slider.Cursor_right -> moveCursor(r)
            KeyValue.Slider.Cursor_up -> moveCursorVertical(-r)
            KeyValue.Slider.Cursor_down -> moveCursorVertical(r)
            KeyValue.Slider.Selection_cursor_left -> moveCursorSel(r, true, keyDown)
            KeyValue.Slider.Selection_cursor_right -> moveCursorSel(r, false, keyDown)
        }
    }

    /** Move the cursor right or left, if possible without sending key events.
     * Unlike arrow keys, the selection is not removed even if shift is not on.
     * Falls back to sending arrow keys events if the editor do not support
     * moving the cursor or a modifier other than shift is pressed. */
    private fun moveCursor(d: Int) {
        val conn = recv.getCurrentInputConnection() ?: return
        val et = getCursorPos(conn)

        if (et != null && canSetSelection(conn)) {
            var selStart = et.selectionStart
            var selEnd = et.selectionEnd

            // Continue expanding the selection even if shift is not pressed
            if (selEnd != selStart) {
                selEnd += d
                if (selEnd == selStart) { // Avoid making the selection empty
                    selEnd += d
                }
            } else {
                selEnd += d
                // Leave 'selStart' where it is if shift is pressed
                if ((metaState and KeyEvent.META_SHIFT_ON) == 0) {
                    selStart = selEnd
                }
            }

            if (conn.setSelection(selStart, selEnd)) {
                return // Fallback to sending key events if [setSelection] failed
            }
        }
        moveCursorFallback(d)
    }

    /** Move one of the two side of a selection. If [selLeft] is true, the left
     * position is moved, otherwise the right position is moved. */
    private fun moveCursorSel(d: Int, selLeft: Boolean, keyDown: Boolean) {
        val conn = recv.getCurrentInputConnection() ?: return
        val et = getCursorPos(conn)

        if (et != null && canSetSelection(conn)) {
            var selStart = et.selectionStart
            var selEnd = et.selectionEnd

            // Reorder the selection when the slider has just been pressed. The
            // selection might have been reversed if one end crossed the other end
            // with a previous slider.
            if (keyDown && selStart > selEnd) {
                selStart = et.selectionEnd
                selEnd = et.selectionStart
            }

            do {
                if (selLeft) {
                    selStart += d
                } else {
                    selEnd += d
                }
                // Move the cursor twice if moving it once would make the selection
                // empty and stop selection mode.
            } while (selStart == selEnd)

            if (conn.setSelection(selStart, selEnd)) {
                return // Fallback to sending key events if [setSelection] failed
            }
        }
        moveCursorFallback(d)
    }

    /** Returns whether the selection can be set using [conn.setSelection()].
     * This can happen on Termux or when system modifiers are activated for example. */
    private fun canSetSelection(conn: InputConnection): Boolean {
        val systemMods = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
        return !moveCursorForceFallback && (metaState and systemMods) == 0
    }

    private fun moveCursorFallback(d: Int) {
        if (d < 0) {
            sendKeyDownUpRepeat(KeyEvent.KEYCODE_DPAD_LEFT, -d)
        } else {
            sendKeyDownUpRepeat(KeyEvent.KEYCODE_DPAD_RIGHT, d)
        }
    }

    /** Move the cursor up and down. This sends UP and DOWN key events that might
     * make the focus exit the text box. */
    private fun moveCursorVertical(d: Int) {
        if (d < 0) {
            sendKeyDownUpRepeat(KeyEvent.KEYCODE_DPAD_UP, -d)
        } else {
            sendKeyDownUpRepeat(KeyEvent.KEYCODE_DPAD_DOWN, d)
        }
    }

    private fun evaluateMacro(keys: Array<KeyValue>) {
        if (keys.isEmpty()) return

        // Ignore modifiers that are activated at the time the macro is evaluated
        mods_changed(Pointers.Modifiers.EMPTY)
        evaluateMacroLoop(keys, 0, Pointers.Modifiers.EMPTY, autocap.pause())
    }

    /** Evaluate the macro asynchronously to make sure event are processed in the right order. */
    private fun evaluateMacroLoop(
        keys: Array<KeyValue>,
        i: Int,
        mods: Pointers.Modifiers,
        autocapPaused: Boolean
    ) {
        var currentI = i
        var currentMods = mods
        var shouldDelay = false

        val kv = KeyModifier.modify(keys[currentI], currentMods)
        if (kv != null) {
            if (kv.hasFlagsAny(KeyValue.FLAG_LATCH)) {
                // Non-special latchable keys clear latched modifiers
                if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL)) {
                    currentMods = Pointers.Modifiers.EMPTY
                }
                currentMods = currentMods.with_extra_mod(kv)
            } else {
                key_down(kv, false)
                key_up(kv, currentMods)
                currentMods = Pointers.Modifiers.EMPTY
            }
            shouldDelay = waitAfterMacroKey(kv)
        }

        currentI++
        when {
            currentI >= keys.size -> {
                // Stop looping
                autocap.unpause(autocapPaused)
            }
            shouldDelay -> {
                // Add a delay before sending the next key to avoid race conditions
                // causing keys to be handled in the wrong order. Notably, KeyEvent keys
                // handling is scheduled differently than the other edit functions.
                recv.getHandler().postDelayed({
                    evaluateMacroLoop(keys, currentI, currentMods, autocapPaused)
                }, 1000 / 30)
            }
            else -> {
                evaluateMacroLoop(keys, currentI, currentMods, autocapPaused)
            }
        }
    }

    private fun waitAfterMacroKey(kv: KeyValue): Boolean {
        return when (kv.getKind()) {
            KeyValue.Kind.Keyevent,
            KeyValue.Kind.Editing,
            KeyValue.Kind.Event -> true
            KeyValue.Kind.Slider -> moveCursorForceFallback
            else -> false
        }
    }

    /** Repeat calls to [send_key_down_up]. */
    private fun sendKeyDownUpRepeat(eventCode: Int, repeat: Int) {
        var remaining = repeat
        while (remaining-- > 0) {
            send_key_down_up(eventCode)
        }
    }

    private fun cancelSelection() {
        val conn = recv.getCurrentInputConnection() ?: return
        val et = getCursorPos(conn) ?: return
        val curs = et.selectionStart

        // Notify the receiver as Android's [onUpdateSelection] is not triggered.
        if (conn.setSelection(curs, curs)) {
            recv.selection_state_changed(false)
        }
    }

    private fun isSelectionNotEmpty(): Boolean {
        val conn = recv.getCurrentInputConnection() ?: return false
        return conn.getSelectedText(0) != null
    }

    /** Workaround some apps which answers to [getExtractedText] but do not react
     * to [setSelection] while returning [true]. */
    private fun shouldMoveCursorForceFallback(info: EditorInfo): Boolean {
        // This catch Acode: which sets several variations at once.
        if ((info.inputType and InputType.TYPE_MASK_VARIATION and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
            return true
        }
        // Godot editor: Doesn't handle setSelection() but returns true.
        return info.packageName.startsWith("org.godotengine.editor")
    }

    /**
     * Notify auto-capitalization system that text was typed/inserted.
     * Call this when inserting text from sources other than sendText() (e.g., swipe predictions).
     * This ensures auto-cap triggers after period, exclamation, etc.
     */
    fun notifyTextTyped(text: CharSequence) {
        autocap.typed(text)
    }

    interface IReceiver {
        fun handle_event_key(ev: KeyValue.Event)
        fun set_shift_state(state: Boolean, lock: Boolean)
        fun set_compose_pending(pending: Boolean)
        fun selection_state_changed(selectionIsOngoing: Boolean)
        fun getCurrentInputConnection(): InputConnection?
        fun getHandler(): Handler
        fun handle_text_typed(text: String)
        fun handle_backspace() {} // Default implementation for backward compatibility
        fun handle_delete_last_word() {} // Delete last auto-inserted or typed word
        fun isClipboardSearchMode(): Boolean = false // Check if clipboard search mode is active
        fun appendToClipboardSearch(text: String) {} // Append text to clipboard search box
        fun backspaceClipboardSearch() {} // Handle backspace in clipboard search
        fun exitClipboardSearchMode() {} // Exit clipboard search mode (clear search box and mode)
    }

    private inner class AutocapitalisationCallback : Autocapitalisation.Callback {
        override fun update_shift_state(shouldEnable: Boolean, shouldDisable: Boolean) {
            when {
                shouldEnable -> recv.set_shift_state(true, false)
                shouldDisable -> recv.set_shift_state(false, false)
            }
        }
    }

    companion object {
        private var moveCursorReq: ExtractedTextRequest? = null
    }
}
