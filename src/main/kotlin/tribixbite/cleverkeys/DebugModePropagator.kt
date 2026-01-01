package tribixbite.cleverkeys

/**
 * Propagates debug mode changes to keyboard managers.
 *
 * This class implements DebugLoggingManager.DebugModeListener and forwards
 * debug mode state changes to managers that support debug logging:
 * - SuggestionHandler: Receives debug mode and logger
 * - NeuralLayoutHelper: Receives debug mode and logger
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
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val debugLogger: SuggestionHandler.DebugLogger,
    private val debugLoggingManager: DebugLoggingManager
) : DebugLoggingManager.DebugModeListener {

    /**
     * Called when debug mode state changes.
     *
     * Propagates the new state to all registered managers:
     * - SuggestionHandler gets debug mode + logger
     * - NeuralLayoutHelper gets debug mode + logger adapter
     *
     * @param enabled True if debug mode is enabled, false otherwise
     */
    override fun onDebugModeChanged(enabled: Boolean) {
        // Propagate debug mode to SuggestionHandler
        suggestionHandler?.setDebugMode(enabled, debugLogger)

        // Propagate debug mode to NeuralLayoutHelper with logger adapter
        neuralLayoutHelper?.setDebugMode(enabled, object : NeuralLayoutHelper.DebugLogger {
            override fun sendDebugLog(message: String) {
                debugLoggingManager.sendDebugLog(message)
            }
        })

        // Propagate debug mode to PredictionCoordinator (gates expensive logging)
        predictionCoordinator?.setDebugModeActive(enabled)
    }

    companion object {
        /**
         * Create a DebugModePropagator.
         *
         * @param suggestionHandler The SuggestionHandler to receive debug mode updates (nullable)
         * @param neuralLayoutHelper The NeuralLayoutHelper to receive debug mode updates (nullable)
         * @param predictionCoordinator The PredictionCoordinator to receive debug mode updates (nullable)
         * @param debugLogger The debug logger for SuggestionHandler
         * @param debugLoggingManager The debug logging manager for sending logs
         * @return A new DebugModePropagator instance
         */
        @JvmStatic
        fun create(
            suggestionHandler: SuggestionHandler?,
            neuralLayoutHelper: NeuralLayoutHelper?,
            predictionCoordinator: PredictionCoordinator?,
            debugLogger: SuggestionHandler.DebugLogger,
            debugLoggingManager: DebugLoggingManager
        ): DebugModePropagator {
            return DebugModePropagator(
                suggestionHandler,
                neuralLayoutHelper,
                predictionCoordinator,
                debugLogger,
                debugLoggingManager
            )
        }
    }
}
