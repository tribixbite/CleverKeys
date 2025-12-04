package tribixbite.cleverkeys

import android.os.Handler

/**
 * Initializes KeyboardReceiver with lazy initialization pattern.
 *
 * This class encapsulates the creation and bridge registration of KeyboardReceiver,
 * which requires 9 dependencies and must be lazily initialized during onStartInputView().
 *
 * The initializer pattern ensures:
 * - KeyboardReceiver is only created once
 * - KeyboardReceiver is properly registered with KeyEventReceiverBridge
 * - All dependencies are provided in a clean, testable way
 *
 * This utility is extracted from CleverKeysService.java onStartInputView() as part of Phase 4
 * refactoring to reduce the main class size (v1.32.397).
 *
 * @since v1.32.397
 */
class ReceiverInitializer(
    private val context: CleverKeysService,
    private val keyboard2: CleverKeysService,
    private val keyboardView: Keyboard2View,
    private val layoutManager: LayoutManager?,
    private val clipboardManager: ClipboardManager,
    private val contextTracker: PredictionContextTracker,
    private val inputCoordinator: InputCoordinator,
    private val subtypeManager: SubtypeManager?,
    private val handler: Handler,
    private val receiverBridge: KeyEventReceiverBridge?
) {
    /**
     * Initialize KeyboardReceiver if not already created.
     *
     * Uses lazy initialization pattern:
     * 1. Checks if receiver already exists
     * 2. Creates new KeyboardReceiver with all dependencies (requires layoutManager)
     * 3. Sets receiver on KeyEventReceiverBridge
     *
     * @param existingReceiver The current receiver (null if not yet created)
     * @return The existing receiver, newly created receiver, or null if layoutManager not ready
     */
    fun initializeIfNeeded(existingReceiver: KeyboardReceiver?): KeyboardReceiver? {
        // Return existing receiver if already created
        if (existingReceiver != null) {
            return existingReceiver
        }

        // Cannot create receiver without layoutManager or subtypeManager - defer until initialized
        if (layoutManager == null || subtypeManager == null) {
            return null
        }

        // Create new KeyboardReceiver with all dependencies
        val newReceiver = KeyboardReceiver(
            context,
            keyboard2,
            keyboardView,
            layoutManager,
            clipboardManager,
            contextTracker,
            inputCoordinator,
            subtypeManager,
            handler
        )

        // Set receiver on bridge for KeyEventHandler delegation
        receiverBridge?.setReceiver(newReceiver)

        return newReceiver
    }

    companion object {
        /**
         * Create a ReceiverInitializer.
         *
         * @param context The Context (CleverKeysService service)
         * @param keyboard2 The CleverKeysService service
         * @param keyboardView The keyboard view
         * @param layoutManager The layout manager (nullable - if null, receiver creation deferred)
         * @param clipboardManager The clipboard manager
         * @param contextTracker The prediction context tracker
         * @param inputCoordinator The input coordinator
         * @param subtypeManager The subtype manager
         * @param handler The main thread handler
         * @param receiverBridge The KeyEventReceiverBridge (nullable)
         * @return A new ReceiverInitializer instance
         */
        @JvmStatic
        fun create(
            context: CleverKeysService,
            keyboard2: CleverKeysService,
            keyboardView: Keyboard2View,
            layoutManager: LayoutManager?,
            clipboardManager: ClipboardManager,
            contextTracker: PredictionContextTracker,
            inputCoordinator: InputCoordinator,
            subtypeManager: SubtypeManager?,
            handler: Handler,
            receiverBridge: KeyEventReceiverBridge?
        ): ReceiverInitializer {
            return ReceiverInitializer(
                context,
                keyboard2,
                keyboardView,
                layoutManager,
                clipboardManager,
                contextTracker,
                inputCoordinator,
                subtypeManager,
                handler,
                receiverBridge
            )
        }
    }
}
