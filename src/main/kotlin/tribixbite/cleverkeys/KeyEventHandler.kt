package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import tribixbite.cleverkeys.clipboard.PrivateCopyDispatch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /**
     * Learning-bookkeeping sink (learning-system audit 2026-09-26, W2/W5), wired by
     * `CleverKeysService.onCreate` to the SuggestionHandler. Null (tests, early startup) means
     * the key paths behave exactly as before and nothing is reported.
     */
    var learningHooks: LearningHooks? = null

    /** Track last typed character and timestamp for double-space-to-period feature */
    private var lastTypedChar: Char = '\u0000'
    private var lastTypedTimestamp: Long = 0L
    // Use configurable threshold from settings
    private val doubleSpaceThresholdMs: Long
        get() = Config.globalConfig().double_space_threshold

    private var editorEpoch = 0L

    /** Editing just started. */
    fun started(info: EditorInfo) {
        editorEpoch++
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
    override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean) {
        if (key == null) return

        val oldMods = this.mods
        updateMetaState(mods)

        when (key.getKind()) {
            KeyValue.Kind.Char -> sendText(key.getChar().toString(), isKeyRepeat)
            KeyValue.Kind.String -> sendText(key.getString(), isKeyRepeat)
            KeyValue.Kind.Event -> {
                // W5: the IME action (send/go/search/done) ends the typed word without ever
                // passing through handle_text_typed — let the learn funnel see it first.
                if (key.getEvent() == KeyValue.Event.ACTION) {
                    learningHooks?.onEditorWordBoundary(recv.getCurrentInputConnection())
                    // The action submits/advances the editor like Enter (terminals: see below).
                    recv.handle_non_text_input()
                }
                recv.handle_event_key(key.getEvent())
            }
            KeyValue.Kind.Keyevent -> {
                // Audit A-5: backspace invalidates the double-space-to-period memory — the
                // char that was "last typed" is no longer the char before the cursor.
                if (key.getKeyevent() == KeyEvent.KEYCODE_DEL) {
                    lastTypedChar = '\u0000'
                }
                // Backspace right after a swipe into an inline field removes that word there
                // (the inline twin of #110's swipe undo; never touches the app behind the panel).
                if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && isInlineFieldActive() &&
                    learningHooks?.undoInlineSwipe() == true) {
                    // Consumed by the inline swipe undo.
                // Tag dialog mode — highest priority modal overlay
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isClipboardTagMode()) {
                    recv.backspaceClipboardTag()
                // Bug #1 fix: check edit mode BEFORE search mode for backspace too
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isClipboardEditMode()) {
                    recv.backspaceClipboardEdit()
                // Handle backspace in clipboard search mode
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isClipboardSearchMode()) {
                    recv.backspaceClipboardSearch()
                // #41 v5: Handle backspace in emoji search
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isEmojiPaneOpen()) {
                    recv.backspaceEmojiSearch()
                // Handle backspace in GIF search
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isGifPaneOpen()) {
                    recv.backspaceGifSearch()
                // Arrow keys and Enter in clipboard edit mode — dispatch to inline EditText
                } else if (recv.isClipboardEditMode() && key.getKeyevent() in EDIT_MODE_DISPATCH_KEYS) {
                    recv.dispatchKeyToClipboardEdit(key.getKeyevent())
                // A Ctrl/Alt/Meta-modified letter arrives as a key event (KeyModifier.applyCtrl),
                // so the text routing chain never sees it: keep shortcuts inside an active inline
                // field instead of sending them to the app behind the panel (Saga 2026-10-13).
                } else if (isInlineFieldActive() && (metaState and SYSTEM_META) != 0) {
                    handleInlineShortcut(key.getKeyevent())
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && learningHooks?.undoSuffix() == true) {
                    // Suffix-only undo consumes even a failed edit; never fall through to word undo.
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && handleBackspaceUndoSwipe()) {
                    // #110: Backspace after swipe deletes entire swiped word
                } else if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && handleBackspaceUndoAutocorrect()) {
                    // #110: Backspace after autocorrect reverts to original word
                } else {
                    // W5: Enter is a key event, not typed text, so the word it ends never reached
                    // the learn funnel. Report the boundary BEFORE the newline/send goes out, while
                    // the editor still ends with the word.
                    if (key.getKeyevent() == KeyEvent.KEYCODE_ENTER) {
                        learningHooks?.onEditorWordBoundary(recv.getCurrentInputConnection())
                    }
                    send_key_down_up(key.getKeyevent())
                    // Handle backspace for word prediction
                    if (key.getKeyevent() == KeyEvent.KEYCODE_DEL) {
                        // SAS-1: backspace invalidates the pending auto-space swallow
                        recv.setLastSpaceAutoInserted(false)
                        recv.handle_backspace()
                    } else {
                        // Enter, Tab, Esc, arrows, Home/End, Page keys and Ctrl/Alt chords (a
                        // modified letter arrives here as a key event, KeyModifier.applyCtrl)
                        // never reach handle_text_typed. A terminal sends no selection update
                        // after them, so the receiver must hear about them to end the typed word.
                        recv.handle_non_text_input()
                    }
                }
            }
            KeyValue.Kind.Modifier -> {}
            KeyValue.Kind.Editing -> handleEditingKey(key.getEditing())
            KeyValue.Kind.Compose_pending -> recv.set_compose_pending(true)
            KeyValue.Kind.Slider -> handleSlider(key.getSlider(), key.getSliderRepeat(), false)
            KeyValue.Kind.Macro -> evaluateMacro(key.getMacro())
            KeyValue.Kind.Template -> execute_template(key.getTemplateFormat().template)
            KeyValue.Kind.Timestamp -> handleTimestampKey(key.getTimestampFormat())
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
        // Paste from clipboard pane always targets the app's InputConnection, even
        // during edit mode. Use sendTextDirect() to bypass edit/search/emoji routing.
        sendTextDirect(content)
    }

    /**
     * Paste media content via InputConnection.commitContent (API 25+).
     * Uses FileProvider to generate a content:// URI from the internal media file path,
     * then sends it to the target app via commitContent. Falls back to placing
     * the media URI on the system clipboard if commitContent fails.
     */
    override fun paste_media_from_clipboard_pane(
        mimeType: String,
        mediaPath: String,
        isPrivate: Boolean,
    ): Boolean {
        if (recv.isClipboardSearchMode()) {
            recv.exitClipboardSearchMode()
        }
        val context = recv.getContext() ?: return false
        val editorInfo = recv.getCurrentEditorInfo() ?: return false
        val conn = recv.getCurrentInputConnection() ?: return false

        return try {
            val mediaManager = ClipboardMediaManager(context)
            val file = mediaManager.getMediaFile(mediaPath)
            if (!file.exists()) {
                Log.w(TAG, "Media file not found for paste: $mediaPath")
                return false
            }

            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            if (Build.VERSION.SDK_INT >= 25) {
                // commitContent is the standard IME→app media insertion API (API 25+)
                val description = android.content.ClipDescription(mediaPath, arrayOf(mimeType))
                val inputContentInfo = androidx.core.view.inputmethod.InputContentInfoCompat(
                    contentUri, description, null
                )
                val flags = androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                val committed = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
                    conn, editorInfo, inputContentInfo, flags, null
                )
                if (committed) {
                    Log.d(TAG, "commitContent succeeded for $mimeType")
                    return true
                }
                Log.d(TAG, "commitContent returned false, falling back to system clipboard")
            }

            // #156 §5.6 / ARC-001: a PRIVATE entry must FAIL here, never fall back — the
            // whole point of private copy is that the OS clipboard (readable by the
            // foreground app, system processes and other clipboard managers) never sees
            // the content. The caller surfaces "can't paste here" feedback.
            if (isPrivate) {
                Log.w(TAG, "commitContent unavailable for private media entry; refusing system-clipboard fallback")
                return false
            }

            // Fallback: place media URI on system clipboard with read permission
            val clip = android.content.ClipData.newUri(
                context.contentResolver, mediaPath, contentUri
            )
            // ClipDescription#setExtras requires API 24 — always available since minSdk 24
            // (ARC-113), so the guard below is vestigial (always true) and retained as-is.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putString("android.content.extra.MIME_TYPES", mimeType)
                }
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(clip)
            Log.d(TAG, "Media placed on system clipboard as fallback")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to paste media: ${e.message}", e)
            false
        }
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

    private fun sendText(text: CharSequence, isKeyRepeat: Boolean = false) {
        // Tag dialog mode — highest priority modal overlay
        if (recv.isClipboardTagMode()) {
            recv.insertToClipboardTag(text.toString())
            return
        }

        // Bug #1 fix: check edit mode BEFORE search mode — edit takes priority.
        // Primary defense is mutual exclusion (entering edit clears search), but
        // checking edit first guards against timing races.
        if (recv.isClipboardEditMode()) {
            recv.insertToClipboardEdit(text.toString())
            return
        }

        // Route to clipboard search box if in search mode
        if (recv.isClipboardSearchMode()) {
            recv.appendToClipboardSearch(text.toString())
            return
        }

        // #41 v5: Route to emoji search EditText when emoji pane is open
        if (recv.isEmojiPaneOpen()) {
            recv.appendToEmojiSearch(text.toString())
            return
        }

        // Route to GIF search EditText when GIF pane is open
        if (recv.isGifPaneOpen()) {
            recv.appendToGifSearch(text.toString())
            return
        }

        val conn = recv.getCurrentInputConnection() ?: return

        // #151 TSR: consult-and-clear the owed-trailing-space state on EVERY keystroke.
        // Non-null means a composing-less editor dropped the trailing space of the last
        // suggestion commit (dropped-space cursor signature, see
        // PredictionContextTracker.markTrailingSpaceWatch) — only an alphanumeric
        // single char earns the repair (below); any other keystroke just cleared it.
        val owedTrailingSpaceWord = recv.takeOwedTrailingSpace()

        // Double-space-to-period: If typing space after space within threshold, replace with ". "
        // Skip if: feature disabled, key repeat (long press), or previous char wasn't alphanumeric
        val currentTime = System.currentTimeMillis()
        var textToCommit = text
        // SAS-1: set when sentence-ending punctuation swallows the auto-space and
        // re-adds one — the fresh space is re-marked pending AFTER the commit below.
        var reMarkAutoSpaceAfterCommit = false
        val config = Config.globalConfig()
        if (config.double_space_to_period && !isKeyRepeat &&
            text.length == 1 && text[0] == ' ' && lastTypedChar == ' ' &&
            (currentTime - lastTypedTimestamp) < doubleSpaceThresholdMs) {
            // Only trigger if the character before the first space was alphanumeric
            // This prevents ". ." or ", ." sequences.
            // Audit A-5: verify at use that a space ACTUALLY precedes the cursor.
            // `lastTypedChar` is handler-local memory — backspace, cursor moves, and
            // swipe/suggestion commits never reset it, so trusting it alone made
            // space→backspace→space delete a LETTER ("hix"→"hi. ") and turned the
            // auto-space after a swipe commit into an unrequested period.
            val textBefore = conn.getTextBeforeCursor(2, 0)
            val spacePrecedesCursor = textBefore?.length == 2 && textBefore[1] == ' '
            val charBeforeSpace = textBefore?.getOrNull(0)
            // Shared word-character rule (WordChars): a letter/digit, or a Persian half-space
            // joining Arabic letters — which cannot sit right before a space, so this is the
            // letter/digit check it always was.
            if (spacePrecedesCursor && charBeforeSpace != null && WordChars.isWordCharAt(textBefore, 0)) {
                // Delete the previous space and insert ". "
                deleteBeforeCursor(conn, 1)
                textToCommit = ". "
                // Reset tracking to prevent triple-space weirdness
                lastTypedChar = '.'
            }
        } else if (text.length == 1) {
            val char = text[0]

            // Smart punctuation: If typing punctuation and previous char is space, delete the space
            // This attaches punctuation to the end of the previous word (e.g., "word ." -> "word.")
            // v1.2.7: Only attach if space was auto-inserted (respects manual space+punctuation)
            // SAS-1: eligibility is verified at use — the actual editor text must show
            // a plain space before the cursor AND the cursor must sit exactly at the
            // position stamped when the auto-space was committed. A manually typed
            // space or a cursor move can therefore never be swallowed.
            val smartPuncEnabled = Config.globalConfig().smart_punctuation
            val isPunctChar = isSmartPunctuationChar(char)
            val isQuote = isQuoteChar(char)

            if (smartPuncEnabled && (isPunctChar || isQuote)) {
                val textBefore = conn.getTextBeforeCursor(500, 0)  // Get enough context for quote counting
                val selection = PredictionContextTracker.currentSelection(conn)
                // A range can start at the old auto-space stamp. Replacing that range
                // must preserve the space before it; cursor-start equality is insufficient.
                val collapsedSelection = if (selection != null) selection.first == selection.second
                    else try { conn.getSelectedText(0).isNullOrEmpty() } catch (_: Exception) { false }
                val eligible = collapsedSelection && SmartAutoSpace.isSwallowEligible(
                    autoSpacePending = recv.wasLastSpaceAutoInserted(),
                    stampedPosition = recv.getAutoSpaceStampedPosition(),
                    actualPrevChar = textBefore?.lastOrNull(),
                    actualPosition = selection?.first ?: -1
                )

                if (isPunctChar && eligible) {
                    // Closing punctuation: swallow the automatic space to attach to the word
                    deleteBeforeCursor(conn, 1)
                    // v1.2.8: For sentence-ending punctuation, add space after so autocap triggers
                    // "hello " + "." → "hello. " (enables getCursorCapsMode to detect sentence end)
                    if (isSentenceEndingPunctuation(char)) {
                        textToCommit = "$char "
                        // SAS-1: re-mark the re-added space as auto-inserted (with a
                        // fresh position stamp) AFTER the commit below — chaining.
                        reMarkAutoSpaceAfterCommit = true
                    }
                } else if (isQuote && eligible) {
                    // Straight double quote: parity disambiguation — an odd count of
                    // prior " means an unmatched opener, so this one is CLOSING.
                    // (Apostrophes are handled by isSmartPunctuationChar above.)
                    if (isClosingQuote(char, textBefore)) {
                        // Closing quote: delete space to attach to word
                        deleteBeforeCursor(conn, 1)
                    }
                    // Opening quote: keep the space (do nothing)
                }
            }

            // v1.2.7/SAS-1: Any other single-char input invalidates the pending
            // auto-space (flag + position stamp), cleared AFTER the swallow check.
            // This ensures: swipe→":"→attaches, but swipe→" "→":"→doesn't attach
            recv.setLastSpaceAutoInserted(false)

            lastTypedChar = char
        } else {
            // SAS-1: multi-char input (macros, emoji strings) invalidates too
            recv.setLastSpaceAutoInserted(false)
            lastTypedChar = '\u0000' // Reset on multi-char input
        }
        lastTypedTimestamp = currentTime

        // SAS-1: capture the pre-commit cursor position for the chaining stamp
        // (after the swallow deletion above, right before the commit)
        val preCommitPos =
            if (reMarkAutoSpaceAfterCommit) PredictionContextTracker.currentCursorPosition(conn)
            else -1

        // #151 TSR: repair the editor-dropped trailing space. Verify at use — the text
        // before the cursor must still read exactly the committed word (no space after
        // it, cursor still there); then the owed space rides in the SAME commit as the
        // char ("<space><char>") because a separate " " commit ends in a space and
        // would be mangled by the very editors this repairs. The typed-word tracking
        // below (autocap/handle_text_typed) still sees the bare char — the space is an
        // editor-text repair, not a typed character (the word context was already
        // committed at suggestion time).
        val repairOwedSpace = owedTrailingSpaceWord != null &&
            text.length == 1 && text[0].isLetterOrDigit() &&
            conn.getTextBeforeCursor(owedTrailingSpaceWord.length, 0)?.toString() ==
            owedTrailingSpaceWord
        if (owedTrailingSpaceWord != null && BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(
                TAG,
                if (repairOwedSpace) {
                    "TSR: inserting owed trailing space before keystroke (verified at use)"
                } else {
                    "TSR: owed space taken but NOT inserted (alnum=" +
                        "${text.length == 1 && text[0].isLetterOrDigit()}, verify failed?)"
                }
            )
        }

        conn.commitText(if (repairOwedSpace) " $textToCommit" else textToCommit, 1)
        if (reMarkAutoSpaceAfterCommit) {
            // SAS-1: fixes the v1.2.7 clobber where the chain flag was set BEFORE the
            // unconditional clear above and thus never survived — mark AFTER commit.
            recv.markAutoSpacePending(
                if (preCommitPos >= 0) preCommitPos + textToCommit.length else -1
            )
        }
        autocap.typed(textToCommit)
        recv.handle_text_typed(textToCommit.toString())
    }

    /** Characters that should attach to the previous word (smart punctuation).
     * SAS-1: full closer set — . , ; : ! ? ) ] } ” ’ … ' — via SmartAutoSpace.
     * Straight double quote `"` is excluded here and parity-resolved through
     * isClosingQuote(). Straight apostrophe `'` IS a closer: mid-word
     * contractions (don't) never reach the swallow path because the char
     * before the cursor there is a letter, not the pending auto-space, so an
     * apostrophe typed right after an auto-space reads as possessive/closing
     * (`kids ` + `'` → `kids'`). */
    private fun isSmartPunctuationChar(c: Char): Boolean = SmartAutoSpace.isClosingPunctuation(c)

    /** v1.2.8: Sentence-ending punctuation that should trigger autocap for next word. */
    private fun isSentenceEndingPunctuation(c: Char): Boolean {
        return when (c) {
            '.', '!', '?' -> true
            else -> false
        }
    }

    /**
     * Determines if a quote should attach to the previous word (closing quote).
     * Opening quotes should have space before them; closing quotes should not.
     *
     * Heuristic: Count quotes of the same type in textBefore. If odd count,
     * there's an unmatched opening quote, so this is a closing quote.
     * If even count (or zero), this is an opening quote.
     *
     * Example: 'He said "hello ' + '"' → one " found (odd) → closing
     * Example: 'He said ' + '"' → zero " found (even) → opening
     */
    private fun isClosingQuote(quote: Char, textBefore: CharSequence?): Boolean {
        if (quote != '"') return false
        if (textBefore.isNullOrEmpty()) return false

        // Count occurrences of this quote type in the text before cursor
        val quoteCount = textBefore.count { it == quote }

        // Odd count means there's an unmatched opening quote → this is closing
        // Even count (including 0) means no unmatched quote → this is opening
        return quoteCount % 2 == 1
    }

    /** Check if quote character needs parity-based smart punctuation handling.
     * SAS-1: only the straight double quote — `'` moved to the closer set
     * (isSmartPunctuationChar) and curly quotes are unambiguous. */
    private fun isQuoteChar(c: Char): Boolean = c == '"'

    /**
     * Send text directly to the app's InputConnection, bypassing all mode routing
     * (edit, search, emoji, GIF). Used for clipboard pane paste which always targets
     * the app regardless of current keyboard panel mode.
     */
    private fun sendTextDirect(text: CharSequence) {
        val conn = recv.getCurrentInputConnection() ?: return
        conn.beginBatchEdit()
        conn.commitText(text, 1)
        conn.endBatchEdit()
    }

    /**
     * See {!InputConnection.performContextMenuAction}. Fire-and-forget: the IME-side connection
     * (RemoteInputConnection → IRemoteInputConnectionInvoker) returns true whenever the IPC call
     * did not throw, NOT the editor's answer, so a refused id (Compose's RecordingInputConnection
     * and Chrome return false app-side for ids they lack) cannot be detected from here. Do not
     * add "unavailable" feedback on its result — it would never fire on a device (2026-10-21).
     */
    private fun sendContextMenuAction(id: Int) {
        val conn = recv.getCurrentInputConnection() ?: return
        conn.performContextMenuAction(id)
    }

    /**
     * App-facing undo/redo as the Ctrl+Z / Ctrl+Shift+Z key events, NOT
     * `performContextMenuAction(undo/redo)`: only a stock EditText honours those ids, while
     * Chrome/WebView (ImeAdapterImpl) and Compose (StatelessInputConnection) return false. The
     * shortcuts are mapped by TextView.onKeyShortcut, Blink's editing_behavior and Compose's
     * KeyMapping alike (docs/research/2026-10-15-ime-editor-actions.md §1.3, §1.4, §2.1).
     * Ctrl+Shift+Z rather than Ctrl+Y for redo: all three map it, and it is the Ctrl+Z family.
     * Never send both forms — an EditText would undo twice.
     *
     * A terminal-style field gets neither: Ctrl+Z there is SIGTSTP (suspends the foreground job,
     * locally or in a remote shell), so report the action as unavailable instead.
     */
    private fun sendUndoRedoShortcut(redo: Boolean) {
        if (isTerminalStyleEditor()) {
            recv.getContext()?.let { recv.showPrivateCopyFeedback(it.getString(R.string.dynamic_action_unavailable)) }
            return
        }
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val meta = if (redo) ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else ctrl
        send_key_down_up(KeyEvent.KEYCODE_Z, meta)
    }

    /**
     * True when the current editor takes terminal-style input: a terminal or a BLIND field
     * (remote desktop such as RustDesk, any TYPE_NULL editor) — [TerminalUtils.editorKind] is
     * the one detector. Such a field cannot be read back or rewritten in place.
     */
    private fun isTerminalStyleEditor(): Boolean =
        TerminalUtils.isTerminalApp(recv.getCurrentEditorInfo(), Config.globalConfigOrNull()?.custom_terminal_packages)

    /**
     * Delete [count] chars before the cursor. Ordinary editors: one `deleteSurroundingText`.
     * Terminal-style editors: [count] KEYCODE_DEL key events — the only deletion a pty or a
     * remote screen receives (ARC-007). RustDesk forwards a LENGTH-ONLY diff of its hidden
     * field, so `deleteSurroundingText(n)` reaches the remote as ONE Backspace whatever n is,
     * and its terminal view's empty field ignores the call altogether (2026-10-13).
     */
    private fun deleteBeforeCursor(conn: InputConnection, count: Int) {
        if (count <= 0) return
        if (isTerminalStyleEditor()) {
            repeat(count) { send_key_down_up(KeyEvent.KEYCODE_DEL, 0) }
        } else {
            conn.deleteSurroundingText(count, 0)
        }
    }

    /**
     * #110: If backspace_undo_swipe is enabled and last input was a swipe,
     * delete the entire swiped word + trailing auto-space in one backspace press.
     * Returns true if the undo was handled, false to fall through to normal backspace.
     */
    private fun handleBackspaceUndoSwipe(): Boolean {
        val config = Config.globalConfig() ?: return false
        if (!config.backspace_undo_swipe) return false
        // #110 fix: Don't check wasLastInputSwipe() — it's cleared by onSuggestionSelected().
        // Instead, defer to autocorrect handler when autocorrect state is present.
        if (recv.getLastAutocorrectOriginalWord() != null) return false

        val swipedWord = recv.getLastAutoInsertedWord() ?: return false
        if (swipedWord.isEmpty()) return false

        val conn = recv.getCurrentInputConnection() ?: return false

        // Read text before cursor to verify the swiped word is still there
        // and check for trailing auto-space (inserted by auto_space_after_suggestion)
        val beforeCursor = conn.getTextBeforeCursor(swipedWord.length + 1, 0)?.toString() ?: ""
        val charsToDelete = when {
            // Word + trailing auto-space: "hello " → delete 6 chars
            beforeCursor.length > swipedWord.length &&
                beforeCursor.endsWith(" ") &&
                beforeCursor.dropLast(1).endsWith(swipedWord) -> swipedWord.length + 1
            // Word without trailing space: "hello" → delete 5 chars
            beforeCursor.endsWith(swipedWord) -> swipedWord.length
            // Text changed since swipe (cursor moved, user typed) — don't undo
            else -> return false
        }

        deleteBeforeCursor(conn, charsToDelete)

        // Clear swipe tracking and reset prediction state
        // SAS-1: the undone commit's auto-space is gone — invalidate the swallow
        recv.setLastSpaceAutoInserted(false)
        recv.clearSwipeUndoState()
        // W2: the swiped word was rejected — roll its learn back. The connection rides along so
        // the swipe-correction tracker can note where the replacement word must appear.
        learningHooks?.onSwipeWordUndone(swipedWord, conn)
        recv.handle_backspace()

        return true
    }

    /**
     * #110: If backspace_undo_autocorrect is enabled and an autocorrect just happened,
     * revert to the original word on immediate backspace (GBoard-style).
     * Does NOT add the original word to dictionary — that's the suggestion bar undo's role.
     */
    private fun handleBackspaceUndoAutocorrect(): Boolean {
        val config = Config.globalConfig() ?: return false
        if (!config.backspace_undo_autocorrect) return false

        val originalWord = recv.getLastAutocorrectOriginalWord() ?: return false
        val correctedWord = recv.getLastAutoInsertedWord() ?: return false
        if (correctedWord.isEmpty()) return false

        val conn = recv.getCurrentInputConnection() ?: return false

        // Verify the corrected word is still at cursor position (handles cursor-move edge case)
        val beforeCursor = conn.getTextBeforeCursor(correctedWord.length + 1, 0)?.toString() ?: ""
        val charsToDelete = when {
            // Word + trailing auto-space: "the " → delete 4 chars
            beforeCursor.length > correctedWord.length &&
                beforeCursor.endsWith(" ") &&
                beforeCursor.dropLast(1).endsWith(correctedWord) -> correctedWord.length + 1
            // Word without trailing space: "the" → delete 3 chars
            beforeCursor.endsWith(correctedWord) -> correctedWord.length
            // Text changed since autocorrect (cursor moved, user typed more) — don't undo
            else -> return false
        }

        // Replace corrected word with original, preserving trailing space if present
        deleteBeforeCursor(conn, charsToDelete)
        val replacement = if (charsToDelete > correctedWord.length) "$originalWord " else originalWord
        conn.commitText(replacement, 1)

        // Clear all undo state to prevent double-undo
        // SAS-1: text was rewritten by the undo — invalidate the pending auto-space
        recv.setLastSpaceAutoInserted(false)
        recv.clearAutocorrectUndoState()
        // W2: the correction was rejected. Roll it back and — when the restored word is complete
        // (trailing space kept) — learn the original instead, as the bar-tap undo does.
        learningHooks?.onAutocorrectUndone(
            correctedWord, originalWord, originalCompleted = charsToDelete > correctedWord.length
        )
        recv.handle_backspace()
        return true
    }

    /**
     * #113: Terminal-aware paste. Terminal emulators (Termux, ConnectBot, etc.)
     * don't implement performContextMenuAction, and Ctrl+V via sendKeyEvent()
     * isn't intercepted by their input layer. Instead, read the system clipboard
     * directly and commit the text via InputConnection — the same approach the
     * clipboard popup uses (paste_from_clipboard_pane → sendText → commitText).
     */
    private fun handlePaste(menuId: Int = android.R.id.paste) {
        // Terminal-style editors include BLIND remote fields (2026-10-13): RustDesk's terminal
        // view has no clipboard path, and a Ctrl+V key event would paste the REMOTE clipboard
        // (RustDesk syncs the phone's clip only when its app resumes). Committed text is the one
        // route that reaches every such field; a clip is plain text there anyway.
        if (isTerminalStyleEditor()) {
            pasteFromSystemClipboard()
        } else {
            sendContextMenuAction(menuId)
        }
    }

    /**
     * Read system clipboard and commit text directly via InputConnection.
     * Works in terminal emulators where performContextMenuAction and Ctrl+V
     * key events are not handled.
     */
    private fun pasteFromSystemClipboard() {
        val conn = recv.getCurrentInputConnection() ?: return
        readSystemClipText()?.let { conn.commitText(it, 1) }
    }

    /** The system clip as plain text, or null when there is none (or it cannot be read). */
    private fun readSystemClipText(): String? {
        val ctx = recv.getContext() ?: return null
        return try {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Log.d(TAG, "No clipboard data available")
                return null
            }
            clip.getItemAt(0).coerceToText(ctx).toString().ifEmpty {
                Log.d(TAG, "Clipboard is empty")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read the system clipboard", e)
            null
        }
    }

    /** True while one of the IME's own inline fields owns typing (the five modal input modes). */
    private fun isInlineFieldActive(): Boolean = !canUseEditorActions()

    /**
     * The inline field that owns typing now, in [sendText]'s priority order (tag > edit >
     * search > emoji > GIF), or null when typing goes to the app. The swipe pipeline
     * (SuggestionHandler) commits swiped words and bar taps through it instead of the app's
     * InputConnection.
     */
    fun activeInlineField(): InlineField? = when {
        recv.isClipboardTagMode() -> InlineField.CLIPBOARD_TAG
        recv.isClipboardEditMode() -> InlineField.CLIPBOARD_EDIT
        recv.isClipboardSearchMode() -> InlineField.CLIPBOARD_SEARCH
        recv.isEmojiPaneOpen() -> InlineField.EMOJI_SEARCH
        recv.isGifPaneOpen() -> InlineField.GIF_SEARCH
        else -> null
    }

    /** [activeInlineField] as a writable target, or null when no inline field is active. */
    fun activeInlineTarget(): InlineTextTarget? {
        val active = activeInlineField() ?: return null
        return object : InlineTextTarget {
            override val field: InlineField = active
            override fun state(): InlineFieldState? = recv.inlineFieldState(active)
            override fun replace(start: Int, end: Int, text: String): Boolean =
                recv.replaceInInlineField(active, start, end, text)
        }
    }

    /**
     * A Ctrl/Alt/Meta shortcut pressed while an inline field is active. In the clipboard entry
     * editor the standard text-field shortcuts act on the editor: Ctrl+V/X/C/A paste, cut, copy
     * and select all; Ctrl+Z undoes; Ctrl+Shift+Z and Ctrl+Y redo (Pixel 2026-10-14). In the other
     * inline fields only Ctrl+V does anything (pastes into the field). Every other shortcut is
     * swallowed — never sent to the app behind the panel.
     */
    private fun handleInlineShortcut(keyCode: Int) {
        val shifted = (metaState and KeyEvent.META_SHIFT_ON) != 0
        val editing = when (keyCode) {
            KeyEvent.KEYCODE_V -> KeyValue.Editing.PASTE
            KeyEvent.KEYCODE_X -> KeyValue.Editing.CUT
            KeyEvent.KEYCODE_C -> KeyValue.Editing.COPY
            KeyEvent.KEYCODE_A -> KeyValue.Editing.SELECT_ALL
            KeyEvent.KEYCODE_Z -> if (shifted) KeyValue.Editing.REDO else KeyValue.Editing.UNDO
            KeyEvent.KEYCODE_Y -> KeyValue.Editing.REDO
            else -> null
        } ?: return
        if (recv.isClipboardEditMode() || editing == KeyValue.Editing.PASTE) handleEditingKey(editing)
    }

    /**
     * The ONE implementation of every [KeyValue.Editing] command. Layout keys reach it through
     * [key_up]; custom short-swipe / popover / palette COMMAND mappings are pressed into [key_up]
     * by `Keyboard2View.onCustomShortSwipe` (CommandRouting.editingValue), so both surfaces share
     * inline-editor routing, terminal paste and typed-word bookkeeping (todo 12, 2026-10-12;
     * pinned by EditingCommandParityTest).
     */
    @SuppressLint("InlinedApi")
    private fun handleEditingKey(ev: KeyValue.Editing) {
        if (ev == KeyValue.Editing.APPEND_POSSESSIVE || ev == KeyValue.Editing.APPEND_APOSTROPHE) {
            val suffix = if (ev == KeyValue.Editing.APPEND_POSSESSIVE) "'s" else "'"
            execute_suffix(suffix)
            return
        }
        // Removed command (maintainer decision 2026-10-17): android.R.id.textAssist is handled by
        // no editor (EditText ignores it in onTextContextMenuItem; Chrome and Compose too), so
        // it was always a silent no-op. Saved mappings, backups and custom layouts that still
        // name `textAssistMenu` resolve here and get a visible refusal; nothing reaches the app
        // and the typed-word state is untouched (docs/research/2026-10-15-ime-editor-actions.md §4.1).
        if (ev == KeyValue.Editing.ASSIST_MENU) {
            showFeedback(R.string.text_assist_menu_removed)
            return
        }
        // A system action must work even while an inline clipboard field owns text routing.
        if (ev == KeyValue.Editing.CLEAR_CLIPBOARD) {
            recv.getContext()?.let { context ->
                recv.showPrivateCopyFeedback(context.getString(ClipboardHistoryService.clearSystemClipboard(context)))
            }
            return
        }
        // Route editing operations to clipboard edit field when in inline edit mode
        if (recv.isClipboardEditMode()) {
            when (ev) {
                KeyValue.Editing.PASTE -> recv.pasteToClipboardEdit()
                KeyValue.Editing.CUT -> recv.cutFromClipboardEdit()
                KeyValue.Editing.SELECT_ALL -> recv.selectAllClipboardEdit()
                KeyValue.Editing.COPY -> recv.copyFromClipboardEdit()
                KeyValue.Editing.UNDO -> recv.undoClipboardEdit()
                KeyValue.Editing.REDO -> recv.redoClipboardEdit()
                KeyValue.Editing.CLEAR -> {
                    recv.selectAllClipboardEdit()
                    recv.backspaceClipboardEdit()
                }
                // Case cycling rewrites text in place; the inline editor's own undo history and
                // privacy gate are not built for it, so it is refused there, visibly.
                KeyValue.Editing.CASE_CYCLE -> showFeedback(R.string.dynamic_action_unavailable)
                // Word moves and word/line selection have an exact inline equivalent: the editor's
                // text is the IME's own, so the same boundary rules run over all of it.
                KeyValue.Editing.WORD_LEFT, KeyValue.Editing.WORD_RIGHT,
                KeyValue.Editing.SELECT_WORD_LEFT, KeyValue.Editing.SELECT_WORD_RIGHT,
                KeyValue.Editing.SELECT_WORD, KeyValue.Editing.SELECT_LINE -> {
                    val command = navigationCommand(ev)
                    if (!recv.navigateClipboardEdit(command) && command == EditorTextNavigation.Command.SELECT_WORD) {
                        showFeedback(R.string.keyboard_text_menu_no_word)
                    }
                }
                // Other editing keys (share, replace, assist, cursor jumps …) have no inline
                // editor equivalent — no-op, never sent to the app behind the panel.
                else -> {}
            }
            return
        }
        // The other inline fields (clipboard search, tag entry, emoji / GIF search) are modal
        // too: paste lands in the active field via the sendText routing chain, and no other
        // editing command may act on the app hidden behind the panel.
        if (isInlineFieldActive()) {
            if (ev == KeyValue.Editing.PASTE || ev == KeyValue.Editing.PASTE_PLAIN) {
                readSystemClipText()?.let { sendText(it) }
            } else if (ev == KeyValue.Editing.CASE_CYCLE) {
                showFeedback(R.string.dynamic_action_unavailable)
            }
            return
        }
        // Every command below acts on the target editor (paste, undo, cursor jumps, word
        // deletion …); in a terminal the typed word it may have changed is no longer known.
        recv.handle_non_text_input()
        when (ev) {
            // Dispatched (and returned) above, before inline-editor routing: suffix commands
            // must report "unavailable" there instead of becoming a silent no-op, and clearing
            // the system clip never needs an InputConnection. Listed only for exhaustiveness.
            KeyValue.Editing.APPEND_POSSESSIVE, KeyValue.Editing.APPEND_APOSTROPHE,
            KeyValue.Editing.CLEAR_CLIPBOARD, KeyValue.Editing.ASSIST_MENU -> Unit
            KeyValue.Editing.COPY_PRIVATE -> handlePrivateCopy()
            KeyValue.Editing.COPY -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.copy)
            KeyValue.Editing.PASTE -> handlePaste()
            KeyValue.Editing.CUT -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.cut)
            KeyValue.Editing.SELECT_ALL -> sendContextMenuAction(android.R.id.selectAll)
            KeyValue.Editing.SHARE -> shareSelection()
            KeyValue.Editing.PASTE_PLAIN -> handlePaste(android.R.id.pasteAsPlainText)
            KeyValue.Editing.UNDO -> sendUndoRedoShortcut(redo = false)
            KeyValue.Editing.REDO -> sendUndoRedoShortcut(redo = true)
            KeyValue.Editing.REPLACE -> launchTextAction(TextActionPolicy.TextAction.REPLACE)
            KeyValue.Editing.ASSIST -> launchTextAction(TextActionPolicy.TextAction.ASSIST)
            // The editor's own replace action, no chooser and no selection precondition: stock
            // EditText shows its suggestion popup (spelling fixes, our SuggestionSpan swipe
            // alternatives) for the word at the cursor (maintainer decision 2026-10-13).
            KeyValue.Editing.REPLACE_MENU -> sendContextMenuAction(android.R.id.replaceText)
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
            KeyValue.Editing.WORD_LEFT, KeyValue.Editing.WORD_RIGHT,
            KeyValue.Editing.SELECT_WORD_LEFT, KeyValue.Editing.SELECT_WORD_RIGHT,
            KeyValue.Editing.SELECT_WORD, KeyValue.Editing.SELECT_LINE -> navigateEditor(navigationCommand(ev))
            KeyValue.Editing.CASE_CYCLE -> cycleCase()
            KeyValue.Editing.CLEAR -> {
                // A terminal-style field has no readable document to erase: select-all reaches
                // nothing, and RustDesk's length diff would turn the commit into ONE remote
                // Backspace. Leave the unseen text alone (2026-10-13).
                if (isTerminalStyleEditor()) return
                val conn = recv.getCurrentInputConnection() ?: return
                conn.beginBatchEdit()
                conn.performContextMenuAction(android.R.id.selectAll)
                conn.commitText("", 1)
                conn.endBatchEdit()
            }
        }
    }

    /**
     * shareText: the selection through the IME's own ACTION_SEND chooser
     * ([TextActionLauncher.share]), which works in every editor — the editor's
     * `android.R.id.shareText` menu action is honoured only by a stock EditText. A selection is
     * required ("no text selected" otherwise): sharing a guessed word or line is not what the
     * user asked for. Refused in terminal-style editors, whose selection readback is not the
     * user's text (a blind field reads back placeholders).
     */
    private fun shareSelection() {
        val conn = recv.getCurrentInputConnection() ?: return
        val ctx = recv.getContext() ?: return
        if (isTerminalStyleEditor()) {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        val text = runCatching { conn.getSelectedText(0)?.toString() }.getOrNull()
        if (text.isNullOrEmpty()) {
            recv.showPrivateCopyFeedback(ctx.getString(R.string.text_action_no_selection, ctx.getString(R.string.cmd_share_text)))
            return
        }
        TextActionLauncher.share(ctx, text) { message -> recv.showPrivateCopyFeedback(message) }
    }

    /** The [EditorTextNavigation.Command] of a word/line editing key. */
    private fun navigationCommand(ev: KeyValue.Editing): EditorTextNavigation.Command = when (ev) {
        KeyValue.Editing.WORD_LEFT -> EditorTextNavigation.Command.WORD_LEFT
        KeyValue.Editing.WORD_RIGHT -> EditorTextNavigation.Command.WORD_RIGHT
        KeyValue.Editing.SELECT_WORD_LEFT -> EditorTextNavigation.Command.SELECT_WORD_LEFT
        KeyValue.Editing.SELECT_WORD_RIGHT -> EditorTextNavigation.Command.SELECT_WORD_RIGHT
        KeyValue.Editing.SELECT_WORD -> EditorTextNavigation.Command.SELECT_WORD
        KeyValue.Editing.SELECT_LINE -> EditorTextNavigation.Command.SELECT_LINE
        else -> throw IllegalArgumentException("not a navigation key: $ev")
    }

    /** Show [messageRes] in the suggestion bar (Toasts are suppressed for IMEs on 13+). */
    private fun showFeedback(messageRes: Int) {
        recv.getContext()?.let { recv.showPrivateCopyFeedback(it.getString(messageRes)) }
    }

    /**
     * Word moves (word_left/right), word selection (select_word_left/right) and select word /
     * line in the target editor (docs/research/2026-10-15-ime-editor-actions.md §3.2, §4.2).
     *
     * - Ordinary editors: the text around the selection is read back and the new selection is
     *   computed by [EditorTextNavigation] (the same rules everywhere, UTF-16 offsets), then set
     *   with `setSelection` — after finishing composition and confirming nothing changed since
     *   the read (CLAUDE.md "Explicit editor edits and composition"). A word or line reaching a
     *   cut edge of the read is refused, not guessed.
     * - Editors where `setSelection` is not used ([moveCursorForceFallback]: password-class
     *   variations, which include Chrome/WebView's WEB_EDIT_TEXT 0xa0, and Godot) or that give
     *   no readback window: moves and extensions are Ctrl(+Shift)+Left/Right key events, and
     *   select word / line are the editor's own keys chosen from the text read around the
     *   cursor ([selectWithEditorKeys]). Web fields are often a hidden textarea behind a JS
     *   editor, so the editor's own keys are what reach its real cursor.
     * - Terminal-style editors: the text cannot be read (a blind field's readback is not the
     *   user's text) and a pty has no selection, so only the moves are sent, as Ctrl+arrow key
     *   events; every selection command is refused.
     *
     * Every read and decision happens before the first write or key event, so a refusal leaves
     * the editor exactly as it was. "Left" is the logical previous word (as the cursor_left
     * slider), whatever the script's direction.
     */
    private fun navigateEditor(command: EditorTextNavigation.Command) {
        val conn = recv.getCurrentInputConnection() ?: return
        val keyable = command != EditorTextNavigation.Command.SELECT_WORD && command != EditorTextNavigation.Command.SELECT_LINE
        val extends = command == EditorTextNavigation.Command.SELECT_WORD_LEFT || command == EditorTextNavigation.Command.SELECT_WORD_RIGHT
        if (isTerminalStyleEditor()) {
            if (keyable && !extends) sendWordKey(command) else showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        if (moveCursorForceFallback && keyable) {
            sendWordKey(command)
            return
        }
        val reach = EditorTextNavigation.reachFor(command)
        val window = EditorTextWindowReader.capture(conn, reach)
        if (window == null || moveCursorForceFallback) {
            if (keyable) sendWordKey(command) else selectWithEditorKeys(conn, command, window)
            return
        }
        when (val plan = EditorTextNavigation.plan(command, window)) {
            EditorTextNavigation.Plan.NoTarget -> showFeedback(R.string.keyboard_text_menu_no_word)
            EditorTextNavigation.Plan.Unknown -> showFeedback(R.string.dynamic_action_unavailable)
            is EditorTextNavigation.Plan.Select -> {
                val unchanged = runCatching {
                    conn.finishComposingText() && EditorTextWindowReader.capture(conn, reach) == window
                }.getOrDefault(false)
                if (!unchanged) {
                    showFeedback(R.string.dynamic_action_unavailable)
                    return
                }
                // As moveCursor: an editor that refuses setSelection still gets the key events.
                if (!conn.setSelection(plan.start, plan.end)) {
                    if (keyable) sendWordKey(command) else selectWithEditorKeys(conn, command, window)
                }
            }
        }
    }

    /**
     * select_word / select_line as the editor's own key events ([EditorTextNavigation.keyPlan]),
     * for editors where `setSelection` is not used or not honoured. The text around the cursor
     * comes from [window] when one was read, else from getTextBeforeCursor / getTextAfterCursor /
     * getSelectedText (no offsets needed; a null selected text means none, as documented). When
     * that text cannot be read the command is refused before any key is sent.
     */
    private fun selectWithEditorKeys(conn: InputConnection, command: EditorTextNavigation.Command, window: EditorTextWindow?) {
        val reach = EditorTextNavigation.reachFor(command)
        val around = if (window != null) Triple(window.before, window.selected, window.after) else runCatching {
            val before = conn.getTextBeforeCursor(reach, 0)?.toString() ?: return@runCatching null
            val after = conn.getTextAfterCursor(reach, 0)?.toString() ?: return@runCatching null
            val selected = conn.getSelectedText(0)?.toString().orEmpty()
            if (selected.length > EditorTextWindowReader.MAX_SELECTION) null else Triple(before, selected, after)
        }.getOrNull()
        if (around == null) {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        when (val plan = EditorTextNavigation.keyPlan(command, around.first, around.second, around.third)) {
            EditorTextNavigation.KeyPlan.NoTarget -> showFeedback(R.string.keyboard_text_menu_no_word)
            EditorTextNavigation.KeyPlan.Unknown -> showFeedback(R.string.dynamic_action_unavailable)
            is EditorTextNavigation.KeyPlan.Send -> plan.keys.forEach(::sendEditorKey)
        }
    }

    /** One [EditorTextNavigation.EditorKey] as a key down/up pair with exactly its modifiers. */
    private fun sendEditorKey(key: EditorTextNavigation.EditorKey) {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        when (key) {
            EditorTextNavigation.EditorKey.COLLAPSE_TO_START -> send_key_down_up(KeyEvent.KEYCODE_DPAD_LEFT, 0)
            EditorTextNavigation.EditorKey.WORD_START -> send_key_down_up(KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
            EditorTextNavigation.EditorKey.EXTEND_TO_WORD_END -> send_key_down_up(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl or shift)
            EditorTextNavigation.EditorKey.LINE_START -> send_key_down_up(KeyEvent.KEYCODE_MOVE_HOME, 0)
            EditorTextNavigation.EditorKey.EXTEND_TO_LINE_END -> send_key_down_up(KeyEvent.KEYCODE_MOVE_END, shift)
        }
    }

    /**
     * case_cycle: lower → Title → UPPER → lower ([CaseCycle]) of the selection, or else of the
     * word at or just before the cursor (`hello |` after a swipe's auto-space).
     *
     * An explicit rewrite, so every step is verified (CLAUDE.md "Explicit editor edits and
     * composition"): the target is computed from a readback window; composition is finished and
     * the window must read back unchanged; the target is selected and must read back as exactly
     * the original text (UTF-16 offsets); the commit must read back as an exact replacement;
     * only then is the new text re-selected so the next press cycles it again. Refused, with
     * feedback, in terminal-style editors (their text cannot be read or rewritten in place),
     * without readback, and — before this method — behind an inline field.
     */
    private fun cycleCase() {
        val conn = recv.getCurrentInputConnection() ?: return
        val editor = recv.getCurrentEditorInfo()
        if (isTerminalStyleEditor()) {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        val reach = EditorTextNavigation.WORD_REACH
        val window = EditorTextWindowReader.capture(conn, reach) ?: run {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        val target = when (val plan = EditorTextNavigation.caseTarget(window)) {
            is EditorTextNavigation.Plan.Select -> plan
            EditorTextNavigation.Plan.NoTarget -> { showFeedback(R.string.keyboard_text_menu_no_word); return }
            EditorTextNavigation.Plan.Unknown -> { showFeedback(R.string.dynamic_action_unavailable); return }
        }
        val original = window.text.substring(target.start - window.base, target.end - window.base)
        val replacement = CaseCycle.next(original, caseLocale())
        if (replacement == original) {
            showFeedback(R.string.case_cycle_failed)
            return
        }
        val epoch = editorEpoch
        val stillCurrent = { editorEpoch == epoch && recv.getCurrentInputConnection() === conn && recv.getCurrentEditorInfo() === editor }
        val outcome = runCatching {
            if (!conn.finishComposingText() || EditorTextWindowReader.capture(conn, reach) != window || !stillCurrent()) {
                return@runCatching CaseOutcome.REFUSED
            }
            val alreadySelected = window.min == target.start && window.max == target.end
            if (!alreadySelected && !conn.setSelection(target.start, target.end)) return@runCatching CaseOutcome.REFUSED
            // The receipt the commit is verified against: exactly the target, selected.
            val selected = EditorReadback.capture(conn)
            if (selected == null || selected.rangeStart != target.start || selected.selectedText != original || !stillCurrent()) {
                return@runCatching CaseOutcome.REFUSED
            }
            learningHooks?.onExplicitEdit()
            recv.onExplicitEditStarted()
            val end = target.start + replacement.length
            if (!conn.commitText(replacement, 1) || !EditorReadback.matchesReplacement(conn, selected, replacement, end)) {
                return@runCatching CaseOutcome.UNVERIFIED
            }
            // Keep the result selected: the next press cycles the same text.
            conn.setSelection(target.start, end)
            CaseOutcome.DONE
        }.getOrDefault(CaseOutcome.UNVERIFIED)
        when (outcome) {
            CaseOutcome.DONE -> Unit
            CaseOutcome.REFUSED -> showFeedback(R.string.dynamic_action_unavailable)
            CaseOutcome.UNVERIFIED -> showFeedback(R.string.case_cycle_failed)
        }
    }

    private enum class CaseOutcome { DONE, REFUSED, UNVERIFIED }

    /** Case mapping follows the keyboard's language (Turkish dotted I), not the UI locale. */
    private fun caseLocale(): java.util.Locale =
        runCatching { java.util.Locale.forLanguageTag(Config.globalConfigOrNull()?.primary_language ?: "") }.getOrNull()
            ?.takeIf { it.language.isNotEmpty() } ?: java.util.Locale.getDefault()

    /** Ctrl+Left/Right (Ctrl+Shift for the selecting variants) as one key event. */
    private fun sendWordKey(command: EditorTextNavigation.Command) {
        val left = command == EditorTextNavigation.Command.WORD_LEFT || command == EditorTextNavigation.Command.SELECT_WORD_LEFT
        val extends = command == EditorTextNavigation.Command.SELECT_WORD_LEFT || command == EditorTextNavigation.Command.SELECT_WORD_RIGHT
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val meta = if (extends) ctrl or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else ctrl
        send_key_down_up(if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, meta)
    }

    /**
     * Text assist / replace text: the ACTION_PROCESS_TEXT app chooser, now WITH the result.
     *
     * Before 2026-10-17 the chooser was started from the service with startActivity while
     * promising an editable result (EXTRA_PROCESS_TEXT_READONLY = false), so any text an app
     * returned was silently dropped (research §3.1). Now [ProcessTextActivity] starts the
     * chooser for a result and [deliverProcessTextResult] writes it back — only into the same
     * field with the same selection, else onto the clipboard. Both commands share this one
     * implementation and differ only in the chooser title; both names stay (saved mappings,
     * release record).
     *
     * Requires a selection ("no text selected" otherwise). Refused in terminal-style editors
     * (no verifiable readback to write a result back against) and when the selection cannot be
     * read back exactly. Without a Context (UI-less receivers) or when the trampoline cannot
     * start, only the editor's own context-menu id is tried.
     */
    private fun launchTextAction(action: TextActionPolicy.TextAction) {
        val conn = recv.getCurrentInputConnection() ?: return
        val ctx = recv.getContext() ?: run {
            TextActionLauncher.fallbackMenuId(action)?.let { conn.performContextMenuAction(it) }
            return
        }
        val selected = runCatching { conn.getSelectedText(0)?.toString() }.getOrNull()
        if (selected.isNullOrEmpty()) {
            recv.showPrivateCopyFeedback(TextActionPolicy.noTextSelectedMessage(action) { id, args -> ctx.getString(id, *args) })
            return
        }
        if (isTerminalStyleEditor()) {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        val receipt = EditorReadback.capture(conn)
        if (receipt == null || receipt.collapsed) {
            showFeedback(R.string.dynamic_action_unavailable)
            return
        }
        val info = recv.getCurrentEditorInfo()
        val origin = ProcessTextReturn.begin(info?.packageName, info?.fieldId ?: 0, info?.inputType ?: 0, receipt)
        if (!TextActionLauncher.launchForResult(ctx, origin.requestId, receipt.selectedText, action)) {
            ProcessTextReturn.cancel(origin.requestId)
            val fallback = TextActionLauncher.fallbackMenuId(action)
            if (fallback != null) conn.performContextMenuAction(fallback) else showFeedback(R.string.dynamic_action_unavailable)
        }
    }

    /**
     * Write a process-text app's returned text back (textAssist / replaceText, see
     * [ProcessTextReturn]). Called by the service when an editor (re)starts input after a
     * result arrived, or after a short delay when none does. The result replaces the original
     * selection ONLY in the same field, with the same selection and text around it read back
     * ([ProcessTextReturn.decide]), after finishing composition and re-checking; the write
     * must read back as an exact replacement. Anything else — another field, an edited text, a
     * terminal-style editor, an open inline field, no readback — puts the result on the
     * clipboard with feedback, so it is never written into the wrong place.
     */
    fun deliverProcessTextResult() {
        val ready = ProcessTextReturn.take<EditorReadback>() ?: return
        val conn = recv.getCurrentInputConnection()
        val info = recv.getCurrentEditorInfo()
        val origin = ready.origin
        val inserted = conn != null && info != null && runCatching {
            val ordinary = !isTerminalStyleEditor() && canUseEditorActions()
            val now = EditorReadback.capture(conn)
            if (ProcessTextReturn.decide(origin, ready.result, info.packageName, info.fieldId, info.inputType, now, ordinary)
                != ProcessTextReturn.Decision.INSERT
            ) return@runCatching false
            if (!conn.finishComposingText() || EditorReadback.capture(conn) != origin.receipt) return@runCatching false
            learningHooks?.onExplicitEdit()
            recv.onExplicitEditStarted()
            val end = Math.addExact(origin.receipt.rangeStart, ready.result.length)
            conn.commitText(ready.result, 1) && EditorReadback.matchesReplacement(conn, origin.receipt, ready.result, end)
        }.getOrDefault(false)
        if (inserted) return
        val ctx = recv.getContext() ?: return
        // PII: the result is never logged. The clipboard write is the user's own text, made
        // because they asked for it; it records in history like any other copy.
        val copied = runCatching {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CleverKeys", ready.result))
            true
        }.getOrDefault(false)
        showFeedback(if (copied) R.string.process_text_result_copied else R.string.dynamic_action_unavailable)
    }

    /**
     * #156 Private copy (in-IME entry point A): store the current selection into CleverKeys'
     * clipboard DB marked private via the shared [PrivateCopyDispatch]. NEVER touches the OS
     * clipboard (delegates to [ClipboardHistoryService.privateCopy], the no-setPrimaryClip path).
     * Feedback via the suggestion bar (Toasts are IME-suppressed on 13+). Dispatch logic is shared
     * with the editing-pane surface so the two cannot drift.
     */
    private fun handlePrivateCopy() {
        // Without a Context there is no service to store into and no resources to word the
        // feedback in; only receivers with no UI (tests) return null, so stay silent.
        val ctx = recv.getContext() ?: return
        val ic = recv.getCurrentInputConnection() ?: run {
            recv.showPrivateCopyFeedback(
                ctx.getString(PrivateCopyDispatch.Outcome.NO_SELECTION.messageRes)
            )
            return
        }
        PrivateCopyDispatch.execute(ctx, ic, recv.getCurrentEditorInfo()) { message ->
            recv.showPrivateCopyFeedback(message)
        }
    }

    /**
     * Handle timestamp key: format current date/time and insert as text.
     * Uses Java DateTimeFormatter patterns (e.g., "yyyy-MM-dd", "HH:mm:ss").
     * Requires Android API 26+ for java.time APIs.
     */
    internal fun isCurrentEditor(ic: InputConnection, info: EditorInfo): Boolean = recv.getCurrentInputConnection() === ic && recv.getCurrentEditorInfo() === info

    override fun execute_suffix(suffix: String): Boolean {
        // A suffix rewrites the committed word and its auto-space in place: impossible in a
        // terminal-style (blind) field, where only appended text and key events arrive.
        val success = canUseEditorActions() && !isTerminalStyleEditor() &&
            learningHooks?.appendSuffix(suffix) == true
        if (!success) recv.getContext()?.let { recv.showPrivateCopyFeedback(it.getString(R.string.dynamic_action_unavailable)) }
        return success
    }

    override fun canUseEditorActions(): Boolean = !(recv.isClipboardTagMode() || recv.isClipboardEditMode() || recv.isClipboardSearchMode() || recv.isEmojiPaneOpen() || recv.isGifPaneOpen())

    override fun execute_template(template: String): Boolean {
        val context = recv.getContext() ?: return false
        val ic = recv.getCurrentInputConnection()
        val editor = recv.getCurrentEditorInfo()
        val capturedEpoch = editorEpoch
        val inline = recv.isClipboardTagMode() || recv.isClipboardEditMode() || recv.isClipboardSearchMode() || recv.isEmojiPaneOpen() || recv.isGifPaneOpen()
        val success = !inline && ic != null && editor != null &&
            tribixbite.cleverkeys.customization.DynamicTemplate.execute(template, context, ic, editor, onEditing = { learningHooks?.onExplicitEdit(); recv.onExplicitEditStarted() }) {
                editorEpoch == capturedEpoch && recv.getCurrentInputConnection() === ic && recv.getCurrentEditorInfo() === editor
            }
        if (!success) recv.showPrivateCopyFeedback(context.getString(R.string.dynamic_action_unavailable))
        return success
    }

    private fun handleTimestampKey(format: KeyValue.TimestampFormat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Fallback for API < 26: use SimpleDateFormat
            try {
                val sdf = java.text.SimpleDateFormat(format.pattern, java.util.Locale.getDefault())
                val formattedTime = sdf.format(java.util.Date())
                sendText(formattedTime)
            } catch (e: Exception) {
                android.util.Log.w("KeyEventHandler", "Invalid timestamp format: ${format.pattern}", e)
                sendText(format.pattern) // Fall back to showing the pattern itself
            }
            return
        }

        // API 26+: Use modern java.time API
        try {
            val formatter = DateTimeFormatter.ofPattern(format.pattern)
            val now = LocalDateTime.now()
            val formattedTime = now.format(formatter)
            sendText(formattedTime)
        } catch (e: Exception) {
            android.util.Log.w("KeyEventHandler", "Invalid timestamp format: ${format.pattern}", e)
            sendText(format.pattern) // Fall back to showing the pattern itself
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
        // In clipboard edit mode, route cursor movements to the inline EditText
        if (recv.isClipboardEditMode()) {
            val keyCode = when (s) {
                KeyValue.Slider.Cursor_left -> KeyEvent.KEYCODE_DPAD_LEFT
                KeyValue.Slider.Cursor_right -> KeyEvent.KEYCODE_DPAD_RIGHT
                KeyValue.Slider.Cursor_up -> KeyEvent.KEYCODE_DPAD_UP
                KeyValue.Slider.Cursor_down -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> null
            }
            if (keyCode != null) {
                // Dispatch once per repeat unit so slider distance maps to cursor steps
                repeat(kotlin.math.abs(r).coerceAtLeast(1)) {
                    recv.dispatchKeyToClipboardEdit(keyCode)
                }
            }
            // Audit A-4: edit mode is MODAL — nothing may fall through to the app's
            // InputConnection. Selection_cursor_* used to slip past the `when` above and
            // reach moveCursorSel(), silently moving the hidden target field's selection.
            // The dispatchKeyToClipboardEdit seam carries no meta state, so shift+DPAD
            // selection inside the edit field isn't expressible there; swallow instead.
            // TODO: extend the edit-dispatch seam with meta state to support selection
            // sliders inside the clipboard edit field.
            return
        }
        // Cursor sliders move the target's caret (key events in terminals): the typed word ends.
        recv.handle_non_text_input()
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
        // d==0 is degenerate: the do/while below only mutates by d, so an empty
        // selection would never satisfy (selStart != selEnd) and loop forever.
        if (d == 0) return
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

    /** Evaluate the macro asynchronously to make sure events are processed in the right order.
     * Always applies a delay between keys to avoid race conditions. */
    private fun evaluateMacroLoop(
        keys: Array<KeyValue>,
        i: Int,
        mods: Pointers.Modifiers,
        autocapPaused: Boolean
    ) {
        var currentI = i
        var currentMods = mods

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
        }

        currentI++
        if (currentI >= keys.size) {
            // Stop looping
            autocap.unpause(autocapPaused)
        } else {
            // Always add a delay before sending the next key to avoid race conditions
            // causing keys to be handled in the wrong order. KeyEvent handling is
            // scheduled differently than other edit functions, so consistent delay
            // ensures proper ordering for all key types.
            recv.getHandler().postDelayed({
                evaluateMacroLoop(keys, currentI, currentMods, autocapPaused)
            }, 1000 / 30)
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

    /**
     * Learning-bookkeeping events only this class can observe (learning-system audit
     * 2026-09-26, W2/W5): the #110 backspace undos, and Enter / the IME action, none of which
     * travel through the typed-text path the learn funnel listens to.
     *
     * Deliberately NOT part of [IReceiver]: IReceiver is the KeyEventHandler → KeyboardReceiver
     * input-routing seam (proxied member-by-member by KeyEventReceiverBridge), and these are
     * notifications to the prediction layer. The service wires the SuggestionHandler in directly.
     */
    interface LearningHooks {
        /** Explicit template edits invalidate typing ownership without learning payload data. */
        fun onExplicitEdit() {}
        fun appendSuffix(suffix: String): Boolean = false
        /** True means consumed, including a refused/uncertain suffix undo. */
        fun undoSuffix(): Boolean = false
        /**
         * Backspace while an inline field owns typing: true when it removed the word just
         * swiped into that field ([InlineSwipeSession.undo]); false falls through to the
         * field's ordinary backspace.
         */
        fun undoInlineSwipe(): Boolean = false
        /** Enter or the IME action is about to be sent; [ic] still ends with the typed word. */
        fun onEditorWordBoundary(ic: InputConnection?)

        /**
         * Backspace deleted the just-swiped [word] (#110 swipe undo). [ic] is the editor AFTER the
         * deletion — the swipe-correction tracker reads the text before the cursor from it to know
         * where the replacement word must appear (null when there is none).
         */
        fun onSwipeWordUndone(word: String, ic: InputConnection?)

        /**
         * Backspace reverted [correctedWord] to [originalWord] (#110 autocorrect undo).
         * [originalCompleted] is true when the restored word kept its trailing space.
         */
        fun onAutocorrectUndone(correctedWord: String, originalWord: String, originalCompleted: Boolean)
    }

    interface IReceiver {
        fun handle_event_key(ev: KeyValue.Event)
        fun set_shift_state(state: Boolean, lock: Boolean)
        fun set_compose_pending(pending: Boolean)
        fun selection_state_changed(selectionIsOngoing: Boolean)
        fun getCurrentInputConnection(): InputConnection?
        fun getCurrentEditorInfo(): EditorInfo? = null // #113: needed for terminal app detection
        fun getContext(): Context? = null // #113: needed for terminal clipboard paste
        fun getHandler(): Handler
        fun handle_text_typed(text: String)
        fun onExplicitEditStarted() {}
        fun handle_backspace() {} // Default implementation for backward compatibility
        /**
         * A key or command that is not typed text reached the editor (Enter, Tab, Esc, arrows,
         * Home/End, a Ctrl/Alt chord, the IME action, a cursor slider, an editing command).
         * Terminals expose no readable buffer, so the receiver ends the typed word there.
         */
        fun handle_non_text_input() {}
        fun handle_delete_last_word() {} // Delete last auto-inserted or typed word
        // Clipboard search mode methods
        fun isClipboardSearchMode(): Boolean = false // Check if clipboard search mode is active
        fun appendToClipboardSearch(text: String) {} // Append text to clipboard search box
        fun backspaceClipboardSearch() {} // Handle backspace in clipboard search
        fun exitClipboardSearchMode() {} // Exit clipboard search mode (clear search box and mode)
        // Clipboard tag dialog — modal overlay, checked before edit mode
        fun isClipboardTagMode(): Boolean = false
        fun insertToClipboardTag(text: String) {}
        fun backspaceClipboardTag() {}
        // Clipboard edit mode — route typing to inline edit field (same pattern as search)
        fun isClipboardEditMode(): Boolean = false
        fun insertToClipboardEdit(text: String) {}
        fun backspaceClipboardEdit() {}
        fun pasteToClipboardEdit() {}
        fun cutFromClipboardEdit() {}
        fun selectAllClipboardEdit() {}
        fun undoClipboardEdit() {}     // Undo the last edit in the inline editor
        fun redoClipboardEdit() {}     // Redo the last undone edit in the inline editor
        fun copyFromClipboardEdit() {} // Copy the inline editor's selection to the system clipboard
        fun dispatchKeyToClipboardEdit(keyCode: Int) {} // Arrow keys, Enter, etc.
        /** Word/line move or selection in the inline editor; false when it had no target. */
        fun navigateClipboardEdit(command: EditorTextNavigation.Command): Boolean = false
        // #41 v5: Emoji search routes typing to visible EditText (IME can't type into own views)
        fun isEmojiPaneOpen(): Boolean = false // Check if emoji pane is visible
        fun appendToEmojiSearch(text: String) {} // Append text to emoji search EditText
        fun backspaceEmojiSearch() {} // Handle backspace in emoji search
        // GIF search: same pattern — route typing to GIF search EditText
        fun isGifPaneOpen(): Boolean = false
        fun appendToGifSearch(text: String) {}
        fun backspaceGifSearch() {}
        /**
         * Swipe typing into inline fields ([InlineSwipeSession]): [field]'s text and selection,
         * or null when that field is not active or has no view.
         */
        fun inlineFieldState(field: InlineField): InlineFieldState? = null
        /** Replace [start, end) of [field] with [text], caret after it; false when refused. */
        fun replaceInInlineField(field: InlineField, start: Int, end: Int, text: String): Boolean = false
        // v1.2.7: Smart punctuation - track if last space was auto-inserted
        fun wasLastSpaceAutoInserted(): Boolean = false
        fun setLastSpaceAutoInserted(value: Boolean) {}
        // SAS-1: position-stamped auto-space pending state (see
        // PredictionContextTracker.markAutoSpacePending / SmartAutoSpace)
        fun getAutoSpaceStampedPosition(): Int = -1
        fun markAutoSpacePending(expectedCursorPosition: Int) {}
        // #151 TSR: consult-and-clear the owed-trailing-space state (see
        // PredictionContextTracker.takeOwedTrailingSpace) — non-null means a
        // composing-less editor dropped the trailing space of the last suggestion
        // commit and the next alphanumeric keystroke owes a leading space.
        fun takeOwedTrailingSpace(): String? = null
        // #110: Backspace undo swipe — check if last input was a swipe and get the word
        fun wasLastInputSwipe(): Boolean = false
        fun getLastAutoInsertedWord(): String? = null
        fun clearSwipeUndoState() {} // Reset swipe tracking after undo
        // #110: Backspace undo autocorrect — expose autocorrect state to KeyEventHandler
        fun getLastAutocorrectOriginalWord(): String? = null
        fun clearAutocorrectUndoState() {}
        // #156: private-copy feedback via the suggestion bar (Toasts are IME-suppressed on 13+).
        fun showPrivateCopyFeedback(message: String) {}
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
        private const val TAG = "KeyEventHandler"
        private var moveCursorReq: ExtractedTextRequest? = null

        // Key events that should be dispatched to the clipboard edit EditText
        // instead of the app's InputConnection when in edit mode
        /** Modifier meta bits that turn a letter into a shortcut (Shift alone does not). */
        private const val SYSTEM_META = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

        private val EDIT_MODE_DISPATCH_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
        )
    }
}
