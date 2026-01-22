package tribixbite.cleverkeys

import android.os.Handler
import android.view.inputmethod.InputConnection

/**
 * Bridge between KeyEventHandler and KeyboardReceiver.
 *
 * This class provides the KeyEventHandler.IReceiver implementation that
 * delegates all calls to a KeyboardReceiver instance. It solves the
 * initialization ordering problem where KeyEventHandler needs to be created
 * before KeyboardReceiver, but KeyEventHandler requires an IReceiver.
 *
 * Pattern: Delegation Bridge with Lazy Initialization
 * - KeyEventHandler is created first with this bridge
 * - Bridge holds a reference to KeyboardReceiver (initially null)
 * - KeyboardReceiver is created later and set via setReceiver()
 * - All calls are forwarded to the receiver once set
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.390).
 *
 * @since v1.32.390
 */
class KeyEventReceiverBridge(
    private val keyboard2: CleverKeysService,
    private val handler: Handler
) : KeyEventHandler.IReceiver {

    private var receiver: KeyboardReceiver? = null
    private var contextTracker: PredictionContextTracker? = null

    /**
     * Set the KeyboardReceiver instance.
     * Must be called after KeyboardReceiver is created.
     *
     * @param receiver The KeyboardReceiver to delegate to
     */
    fun setReceiver(receiver: KeyboardReceiver) {
        this.receiver = receiver
    }

    /**
     * Set the PredictionContextTracker instance.
     * Must be called after contextTracker is created.
     * v1.2.7: Used for smart punctuation auto-space tracking.
     */
    fun setContextTracker(tracker: PredictionContextTracker) {
        this.contextTracker = tracker
    }

    override fun handle_event_key(ev: KeyValue.Event) {
        receiver?.handle_event_key(ev)
    }

    override fun set_shift_state(state: Boolean, lock: Boolean) {
        receiver?.set_shift_state(state, lock)
    }

    override fun set_compose_pending(pending: Boolean) {
        receiver?.set_compose_pending(pending)
    }

    override fun selection_state_changed(selectionIsOngoing: Boolean) {
        receiver?.selection_state_changed(selectionIsOngoing)
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return keyboard2.getCurrentInputConnection()
    }

    override fun getHandler(): Handler {
        return handler
    }

    override fun handle_text_typed(text: String) {
        receiver?.handle_text_typed(text)
    }

    override fun handle_backspace() {
        receiver?.handle_backspace()
    }

    override fun handle_delete_last_word() {
        receiver?.handle_delete_last_word()
    }

    override fun isClipboardSearchMode(): Boolean {
        return receiver?.isClipboardSearchMode() ?: false
    }

    override fun appendToClipboardSearch(text: String) {
        receiver?.appendToClipboardSearch(text)
    }

    override fun backspaceClipboardSearch() {
        receiver?.backspaceClipboardSearch()
    }

    override fun exitClipboardSearchMode() {
        receiver?.exitClipboardSearchMode()
    }

    // #41 v7: Emoji search delegation (was missing - caused routing to fail!)
    override fun isEmojiPaneOpen(): Boolean {
        return receiver?.isEmojiPaneOpen() ?: false
    }

    override fun appendToEmojiSearch(text: String) {
        receiver?.appendToEmojiSearch(text)
    }

    override fun backspaceEmojiSearch() {
        receiver?.backspaceEmojiSearch()
    }

    // v1.2.7: Smart punctuation - track if last space was auto-inserted
    override fun wasLastSpaceAutoInserted(): Boolean {
        return contextTracker?.lastSpaceWasAutoInserted ?: false
    }

    override fun setLastSpaceAutoInserted(value: Boolean) {
        contextTracker?.lastSpaceWasAutoInserted = value
    }

    companion object {
        /**
         * Create a KeyEventReceiverBridge.
         *
         * @param keyboard2 The CleverKeysService instance for InputConnection access
         * @param handler The Handler for event posting
         * @return A new KeyEventReceiverBridge instance
         */
        @JvmStatic
        fun create(keyboard2: CleverKeysService, handler: Handler): KeyEventReceiverBridge {
            return KeyEventReceiverBridge(keyboard2, handler)
        }
    }
}
