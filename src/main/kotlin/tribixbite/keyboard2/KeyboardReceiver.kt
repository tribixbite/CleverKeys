package tribixbite.keyboard2

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection

/**
 * Handles keyboard events and state changes for CleverKeysService.
 *
 * This class centralizes logic for:
 * - Keyboard event handling (special keys, layout switching)
 * - View state management (shift, compose, selection)
 * - Layout switching (text, numeric, emoji, clipboard)
 * - Input method switching
 * - Clipboard and emoji pane management
 *
 * Responsibilities:
 * - Handle special key events (CONFIG, SWITCH_TEXT, SWITCH_NUMERIC, etc.)
 * - Manage keyboard view state updates
 * - Coordinate with managers for layout, clipboard, and input operations
 * - Bridge between KeyEventHandler and CleverKeysService
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - Manager initialization
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.368).
 */
class KeyboardReceiver(
    private val context: Context,
    private val keyboard2: CleverKeysService,
    private val keyboardView: Keyboard2View,
    private val layoutManager: LayoutManager,
    private val clipboardManager: ClipboardManager,
    private val contextTracker: PredictionContextTracker,
    private val inputCoordinator: InputCoordinator,
    private val subtypeManager: SubtypeManager,
    private val handler: Handler
) : KeyEventHandler.IReceiver {

    // View references
    private var emojiPane: ViewGroup? = null
    private var contentPaneContainer: ViewGroup? = null

    /**
     * Sets references to emoji pane and content pane container.
     * These are created later in CleverKeysService lifecycle.
     *
     * @param emojiPane Emoji pane view
     * @param contentPaneContainer Container for emoji/clipboard panes
     */
    fun setViewReferences(emojiPane: ViewGroup?, contentPaneContainer: ViewGroup?) {
        this.emojiPane = emojiPane
        this.contentPaneContainer = contentPaneContainer
    }

    override fun handle_event_key(ev: KeyValue.Event) {
        android.util.Log.i("KeyboardReceiver", "handle_event_key: $ev")
        when (ev) {
            KeyValue.Event.CONFIG -> {
                val intent = Intent(context, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }

            KeyValue.Event.SWITCH_TEXT -> {
                keyboardView.setKeyboard(layoutManager.clearSpecialLayout())
            }

            KeyValue.Event.SWITCH_NUMERIC -> {
                val numpad = layoutManager.loadNumpad(R.xml.numeric)
                if (numpad != null) {
                    keyboardView.setKeyboard(numpad)
                }
            }

            KeyValue.Event.SWITCH_EMOJI -> {
                if (emojiPane == null) {
                    emojiPane = keyboard2.inflate_view(R.layout.emoji_pane) as ViewGroup
                }

                // Capture for null safety
                val pane = emojiPane

                // Show emoji pane in content container (keyboard stays visible below)
                contentPaneContainer?.let {
                    it.removeAllViews()
                    it.addView(pane)
                    it.visibility = View.VISIBLE
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    if (pane != null) {
                        keyboard2.setInputView(pane)
                    }
                }
            }

            KeyValue.Event.SWITCH_CLIPBOARD -> {
                // Get clipboard pane from manager (lazy initialization)
                val clipboardPane = clipboardManager.getClipboardPane(keyboard2.layoutInflater)

                // Reset search mode and clear any previous search when showing clipboard pane
                clipboardManager.resetSearchOnShow()

                // Show clipboard pane in content container (keyboard stays visible below)
                contentPaneContainer?.let {
                    it.removeAllViews()
                    it.addView(clipboardPane)
                    it.visibility = View.VISIBLE
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    keyboard2.setInputView(clipboardPane)
                }
            }

            KeyValue.Event.SWITCH_BACK_EMOJI,
            KeyValue.Event.SWITCH_BACK_CLIPBOARD -> {
                // Exit clipboard search mode when switching back
                clipboardManager.resetSearchOnHide()

                // Hide content pane (keyboard remains visible)
                contentPaneContainer?.let {
                    it.visibility = View.GONE
                } ?: run {
                    // Fallback for when predictions disabled
                    keyboard2.setInputView(keyboardView)
                }
            }

            KeyValue.Event.CHANGE_METHOD_PICKER -> {
                subtypeManager.inputMethodManager.showInputMethodPicker()
            }

            KeyValue.Event.CHANGE_METHOD_AUTO -> {
                if (Build.VERSION.SDK_INT < 28) {
                    keyboard2.getConnectionToken()?.let { token ->
                        subtypeManager.inputMethodManager.switchToLastInputMethod(token)
                    }
                } else {
                    keyboard2.switchToNextInputMethod(false)
                }
            }

            KeyValue.Event.ACTION -> {
                keyboard2.currentInputConnection?.performEditorAction(keyboard2.actionId)
            }

            KeyValue.Event.SWITCH_FORWARD -> {
                keyboardView.setKeyboard(layoutManager.incrTextLayout(1))
            }

            KeyValue.Event.SWITCH_BACKWARD -> {
                keyboardView.setKeyboard(layoutManager.incrTextLayout(-1))
            }

            KeyValue.Event.SWITCH_GREEKMATH -> {
                val greekmath = layoutManager.loadNumpad(R.xml.greekmath)
                if (greekmath != null) {
                    keyboardView.setKeyboard(greekmath)
                }
            }

            KeyValue.Event.CAPS_LOCK -> {
                set_shift_state(true, true)
            }

            KeyValue.Event.SWITCH_VOICE_TYPING -> {
                if (!VoiceImeSwitcher.switch_to_voice_ime(
                        keyboard2,
                        subtypeManager.inputMethodManager,
                        Config.globalPrefs()
                    )
                ) {
                    keyboard2.getConfig()?.shouldOfferVoiceTyping = false
                }
            }

            KeyValue.Event.SWITCH_VOICE_TYPING_CHOOSER -> {
                VoiceImeSwitcher.choose_voice_ime(
                    keyboard2,
                    subtypeManager.inputMethodManager,
                    Config.globalPrefs()
                )
            }

            else -> {} // Unhandled events
        }
    }

    override fun set_shift_state(state: Boolean, lock: Boolean) {
        keyboardView.set_shift_state(state, lock)
    }

    override fun set_compose_pending(pending: Boolean) {
        keyboardView.set_compose_pending(pending)
    }

    override fun selection_state_changed(selection_is_ongoing: Boolean) {
        keyboardView.set_selection_state(selection_is_ongoing)
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return keyboard2.currentInputConnection
    }

    override fun getHandler(): Handler {
        return handler
    }

    override fun handle_text_typed(text: String) {
        // Reset swipe tracking when regular typing occurs
        contextTracker.setWasLastInputSwipe(false)
        inputCoordinator.resetSwipeData()
        keyboard2.handleRegularTyping(text)
    }

    override fun handle_backspace() {
        keyboard2.handleBackspace()
    }

    override fun handle_delete_last_word() {
        keyboard2.handleDeleteLastWord()
    }

    override fun isClipboardSearchMode(): Boolean {
        return clipboardManager.isInSearchMode()
    }

    override fun appendToClipboardSearch(text: String) {
        clipboardManager.appendToSearch(text)
    }

    override fun backspaceClipboardSearch() {
        clipboardManager.deleteFromSearch()
    }

    override fun exitClipboardSearchMode() {
        clipboardManager.clearSearch()
    }
}
