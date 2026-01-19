package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat

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
    private var suggestionBar: View? = null

    // Track which pane is currently visible for toggle behavior
    private var currentPaneType: PaneType = PaneType.NONE

    private enum class PaneType { NONE, EMOJI, CLIPBOARD }

    // #41: Emoji search manager (uses suggestion bar for status display)
    private var emojiSearchManager: EmojiSearchManager? = null

    /**
     * Sets references to emoji pane and content pane container.
     * These are created later in CleverKeysService lifecycle.
     *
     * @param emojiPane Emoji pane view
     * @param contentPaneContainer Container for emoji/clipboard panes
     * @param suggestionBar Suggestion bar view (to hide when pane is open)
     */
    fun setViewReferences(emojiPane: ViewGroup?, contentPaneContainer: ViewGroup?, suggestionBar: View? = null) {
        this.emojiPane = emojiPane
        this.contentPaneContainer = contentPaneContainer
        this.suggestionBar = suggestionBar
    }

    /**
     * #41: Sets the emoji search manager.
     * Called from CleverKeysService after initialization.
     */
    fun setEmojiSearchManager(manager: EmojiSearchManager) {
        this.emojiSearchManager = manager
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
                val resId = keyboard2.resources.getIdentifier("numeric", "raw", keyboard2.packageName)
                val numpad = layoutManager.loadNumpad(resId)
                if (numpad != null) {
                    keyboardView.setKeyboard(numpad)
                }
            }

            KeyValue.Event.SWITCH_EMOJI -> {
                // Toggle behavior: if emoji pane already visible, close it
                if (currentPaneType == PaneType.EMOJI && contentPaneContainer?.visibility == View.VISIBLE) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_EMOJI)
                    return
                }

                if (emojiPane == null) {
                    emojiPane = keyboard2.inflate_view(R.layout.emoji_pane) as ViewGroup
                }

                // Capture for null safety
                val pane = emojiPane

                // Hide suggestion bar to avoid double bar (pane has its own search bar)
                suggestionBar?.visibility = View.GONE

                // Show emoji pane in content container (keyboard stays visible below)
                contentPaneContainer?.let {
                    it.removeAllViews()
                    it.addView(pane)
                    it.visibility = View.VISIBLE
                    // FIX #1131: Request window insets to apply nav bar padding on Android 15+
                    ViewCompat.requestApplyInsets(it)
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    if (pane != null) {
                        keyboard2.setInputView(pane)
                    }
                }

                currentPaneType = PaneType.EMOJI

                // #41 v4: Initialize emoji search manager with the pane and notify pane opened
                emojiPane?.let { pane ->
                    emojiSearchManager?.initialize(pane)
                    // Auto-detect context word for initial search query
                    val textBeforeCursor = keyboard2.currentInputConnection
                        ?.getTextBeforeCursor(100, 0)
                    val contextWord = emojiSearchManager?.extractWordBeforeCursor(textBeforeCursor)
                    emojiSearchManager?.onPaneOpened(contextWord)

                    // Wire up search manager to category buttons
                    pane.findViewById<EmojiGroupButtonsBar>(R.id.emoji_group_buttons)
                        ?.setSearchManager(emojiSearchManager!!)

                    // #41 v8: Wire up search manager to emoji grid for selection bypass
                    pane.findViewById<EmojiGridView>(R.id.emoji_grid)?.let { grid ->
                        grid.setSearchManager(emojiSearchManager!!)
                        // #41 v10: Wire up service for suggestion bar messages on long-press
                        grid.setService(keyboard2)
                    }

                    // #41 v10: Close button callback to return to keyboard
                    emojiSearchManager?.setOnCloseCallback {
                        handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
                    }
                }
            }

            KeyValue.Event.SWITCH_CLIPBOARD -> {
                // Toggle behavior: if clipboard pane already visible, close it
                if (currentPaneType == PaneType.CLIPBOARD && contentPaneContainer?.visibility == View.VISIBLE) {
                    handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
                    return
                }

                // SECURITY: Block clipboard access on lock screen (contains PII)
                if (DirectBootManager.getInstance(context).isDeviceLocked) {
                    android.util.Log.w("KeyboardReceiver", "Clipboard blocked: screen is locked")
                    return
                }

                // Get clipboard pane from manager (lazy initialization)
                val clipboardPane = clipboardManager.getClipboardPane(keyboard2.layoutInflater)

                // Reset search mode and clear any previous search when showing clipboard pane
                clipboardManager.resetSearchOnShow()

                // Hide suggestion bar to avoid double bar
                suggestionBar?.visibility = View.GONE

                // Show clipboard pane in content container (keyboard stays visible below)
                contentPaneContainer?.let {
                    it.removeAllViews()
                    it.addView(clipboardPane)
                    it.visibility = View.VISIBLE
                    // FIX #1131: Request window insets to apply nav bar padding on Android 15+
                    ViewCompat.requestApplyInsets(it)
                } ?: run {
                    // Fallback for when predictions disabled (no container)
                    keyboard2.setInputView(clipboardPane)
                }

                currentPaneType = PaneType.CLIPBOARD
            }

            KeyValue.Event.SWITCH_BACK_EMOJI,
            KeyValue.Event.SWITCH_BACK_CLIPBOARD -> {
                // Exit clipboard search mode when switching back
                clipboardManager.resetSearchOnHide()

                // #41 v4: Notify emoji search manager pane is closing
                emojiSearchManager?.onPaneClosed()

                // Reset pane tracking
                currentPaneType = PaneType.NONE

                // Show suggestion bar again
                suggestionBar?.visibility = View.VISIBLE

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
                val layoutCount = layoutManager.getLayoutCount()
                val currentIndex = layoutManager.getCurrentLayoutIndex()
                android.util.Log.d("KeyboardReceiver", "SWITCH_FORWARD: layoutCount=$layoutCount, currentIndex=$currentIndex")
                if (layoutCount > 1) {
                    val newLayout = layoutManager.incrTextLayout(1)
                    android.util.Log.d("KeyboardReceiver", "SWITCH_FORWARD: switching to newIndex=${layoutManager.getCurrentLayoutIndex()}")
                    keyboardView.setKeyboard(newLayout)
                } else {
                    android.util.Log.w("KeyboardReceiver", "SWITCH_FORWARD: Only $layoutCount layout(s) configured, cannot switch")
                }
            }

            KeyValue.Event.SWITCH_BACKWARD -> {
                val layoutCount = layoutManager.getLayoutCount()
                val currentIndex = layoutManager.getCurrentLayoutIndex()
                android.util.Log.d("KeyboardReceiver", "SWITCH_BACKWARD: layoutCount=$layoutCount, currentIndex=$currentIndex")
                if (layoutCount > 1) {
                    val newLayout = layoutManager.incrTextLayout(-1)
                    android.util.Log.d("KeyboardReceiver", "SWITCH_BACKWARD: switching to newIndex=${layoutManager.getCurrentLayoutIndex()}")
                    keyboardView.setKeyboard(newLayout)
                } else {
                    android.util.Log.w("KeyboardReceiver", "SWITCH_BACKWARD: Only $layoutCount layout(s) configured, cannot switch")
                }
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

    override fun selection_state_changed(selectionIsOngoing: Boolean) {
        keyboardView.set_selection_state(selectionIsOngoing)
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

    // #41 v5: Emoji search routes typing to visible EditText (IME can't type into own views)
    override fun isEmojiPaneOpen(): Boolean {
        val result = emojiSearchManager?.isEmojiPaneOpen() ?: false
        android.util.Log.d("KeyboardReceiver", "isEmojiPaneOpen: manager=$emojiSearchManager, result=$result")
        return result
    }

    override fun appendToEmojiSearch(text: String) {
        android.util.Log.d("KeyboardReceiver", "appendToEmojiSearch: '$text', manager=$emojiSearchManager")
        emojiSearchManager?.appendToSearch(text)
    }

    override fun backspaceEmojiSearch() {
        emojiSearchManager?.backspaceSearch()
    }
}
