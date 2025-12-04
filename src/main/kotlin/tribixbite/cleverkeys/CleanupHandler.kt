package tribixbite.cleverkeys

import android.content.Context

/**
 * Handles cleanup of keyboard managers and services on destroy.
 *
 * This class centralizes all cleanup logic when the keyboard service is destroyed:
 * - Closes fold state tracker
 * - Shuts down clipboard service and manager
 * - Shuts down prediction coordinator
 * - Unregisters debug mode receiver and closes log writer
 *
 * The cleanup handler pattern simplifies onDestroy() by consolidating all
 * cleanup operations into a single, well-tested utility.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.404).
 *
 * @since v1.32.404
 */
class CleanupHandler(
    private val context: Context,
    private val configManager: ConfigurationManager?,
    private val clipboardManager: ClipboardManager?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val debugLoggingManager: DebugLoggingManager?
) {
    /**
     * Perform all cleanup operations.
     *
     * Executes cleanup in this order:
     * 1. Close fold state tracker
     * 2. Shutdown clipboard service
     * 3. Cleanup clipboard manager
     * 4. Shutdown prediction coordinator
     * 5. Unregister debug receiver and close log writer
     */
    fun cleanup() {
        // Close fold state tracker
        configManager?.getFoldStateTracker()?.close()

        // Cleanup clipboard listener (static service)
        ClipboardHistoryService.on_shutdown()

        // Cleanup clipboard manager
        clipboardManager?.cleanup()

        // Cleanup prediction coordinator
        predictionCoordinator?.shutdown()

        // Unregister debug mode receiver and close log writer
        debugLoggingManager?.let {
            it.unregisterDebugModeReceiver(context)
            it.close()
        }
    }

    companion object {
        /**
         * Create a CleanupHandler.
         *
         * @param context The context (typically CleverKeysService service)
         * @param configManager The configuration manager (nullable)
         * @param clipboardManager The clipboard manager (nullable)
         * @param predictionCoordinator The prediction coordinator (nullable)
         * @param debugLoggingManager The debug logging manager (nullable)
         * @return A new CleanupHandler instance
         */
        @JvmStatic
        fun create(
            context: Context,
            configManager: ConfigurationManager?,
            clipboardManager: ClipboardManager?,
            predictionCoordinator: PredictionCoordinator?,
            debugLoggingManager: DebugLoggingManager?
        ): CleanupHandler {
            return CleanupHandler(
                context,
                configManager,
                clipboardManager,
                predictionCoordinator,
                debugLoggingManager
            )
        }
    }
}
