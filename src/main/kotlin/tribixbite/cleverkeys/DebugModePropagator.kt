package tribixbite.cleverkeys

/**
 * Propagates debug mode changes to keyboard managers.
 *
 * This class implements DebugLoggingManager.DebugModeListener and forwards
 * debug mode state changes to managers that support debug logging:
 * - SuggestionHandler: Receives debug mode and logger
 * - KeyboardDimensionsHelper: Receives debug mode and logger
 *
 * The propagator pattern centralizes debug mode distribution, making it
 * easier to add or remove debug-aware managers without modifying CleverKeysService.
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.392).
 *
 * @since v1.32.392
 */
class DebugModePropagator(
    private val suggestionHandler: SuggestionHandler?,
    private val keyboardDimensionsHelper: KeyboardDimensionsHelper?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val debugLogger: SuggestionHandler.DebugLogger,
    private val debugLoggingManager: DebugLoggingManager
) : DebugLoggingManager.DebugModeListener {

    /**
     * Called when debug mode state changes.
     *
     * Propagates the new state to all registered managers:
     * - SuggestionHandler gets debug mode + logger
     * - KeyboardDimensionsHelper gets debug mode + logger adapter
     *
     * @param enabled True if debug mode is enabled, false otherwise
     */
    override fun onDebugModeChanged(enabled: Boolean) {
        // Propagate debug mode to SuggestionHandler
        suggestionHandler?.setDebugMode(enabled, debugLogger)

        // Propagate debug mode to KeyboardDimensionsHelper with logger adapter
        keyboardDimensionsHelper?.setDebugMode(enabled, object : KeyboardDimensionsHelper.DebugLogger {
            override fun sendDebugLog(message: String) {
                debugLoggingManager.sendDebugLog(message)
            }
        })

        // PredictionCoordinator no longer gates any debug logging of its own: the only
        // consumer was the neural engine's per-swipe trace dump, deleted 2026-08-18.
    }

    companion object {
        /**
         * Create a DebugModePropagator.
         *
         * @param suggestionHandler The SuggestionHandler to receive debug mode updates (nullable)
         * @param keyboardDimensionsHelper The KeyboardDimensionsHelper to receive debug mode updates (nullable)
         * @param predictionCoordinator The PredictionCoordinator to receive debug mode updates (nullable)
         * @param debugLogger The debug logger for SuggestionHandler
         * @param debugLoggingManager The debug logging manager for sending logs
         * @return A new DebugModePropagator instance
         */
        @JvmStatic
        fun create(
            suggestionHandler: SuggestionHandler?,
            keyboardDimensionsHelper: KeyboardDimensionsHelper?,
            predictionCoordinator: PredictionCoordinator?,
            debugLogger: SuggestionHandler.DebugLogger,
            debugLoggingManager: DebugLoggingManager
        ): DebugModePropagator {
            return DebugModePropagator(
                suggestionHandler,
                keyboardDimensionsHelper,
                predictionCoordinator,
                debugLogger,
                debugLoggingManager
            )
        }
    }
}
