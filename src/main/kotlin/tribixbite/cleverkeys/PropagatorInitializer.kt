package tribixbite.cleverkeys

/**
 * Initializes and configures keyboard propagators.
 *
 * This class centralizes the creation and registration of propagators that
 * distribute references and state changes across keyboard managers:
 * - DebugModePropagator: Propagates debug mode changes to managers
 * - ConfigPropagator: Propagates configuration changes to all managers
 *
 * The initializer pattern simplifies onCreate() by consolidating propagator
 * setup into a single operation with clear dependencies.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.396).
 *
 * @since v1.32.396
 */
class PropagatorInitializer(
    private val suggestionHandler: SuggestionHandler?,
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val debugLoggerImpl: SuggestionHandler.DebugLogger,
    private val debugLoggingManager: DebugLoggingManager,
    private val clipboardManager: ClipboardManager?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val inputCoordinator: InputCoordinator?,
    private val layoutManager: LayoutManager?,
    private val keyboardView: Keyboard2View?,
    private val subtypeManager: SubtypeManager?
) {
    /**
     * Result of propagator initialization.
     *
     * @property configPropagator The configured ConfigPropagator instance
     */
    data class InitializationResult(
        val configPropagator: ConfigPropagator
    )

    /**
     * Initialize all propagators.
     *
     * Creates and registers:
     * 1. DebugModePropagator with the debug logging manager
     * 2. ConfigPropagator using builder pattern with all managers
     *
     * @return InitializationResult containing the ConfigPropagator
     */
    fun initialize(): InitializationResult {
        // Create debug mode propagator
        val debugModePropagator = DebugModePropagator.create(
            suggestionHandler,
            neuralLayoutHelper,
            debugLoggerImpl,
            debugLoggingManager
        )

        // Register debug mode propagator with debug logging manager
        debugLoggingManager.registerDebugModeListener(debugModePropagator)

        // Initialize config propagator using builder pattern
        val configPropagator = ConfigPropagator.builder()
            .setClipboardManager(clipboardManager)
            .setPredictionCoordinator(predictionCoordinator)
            .setInputCoordinator(inputCoordinator)
            .setSuggestionHandler(suggestionHandler)
            .setNeuralLayoutHelper(neuralLayoutHelper)
            .setLayoutManager(layoutManager)
            .setKeyboardView(keyboardView)
            .setSubtypeManager(subtypeManager)
            .build()

        return InitializationResult(configPropagator)
    }

    companion object {
        /**
         * Create a PropagatorInitializer.
         *
         * @param suggestionHandler The SuggestionHandler (nullable)
         * @param neuralLayoutHelper The NeuralLayoutHelper (nullable)
         * @param debugLoggerImpl The debug logger implementation
         * @param debugLoggingManager The debug logging manager
         * @param clipboardManager The ClipboardManager (nullable)
         * @param predictionCoordinator The PredictionCoordinator (nullable)
         * @param inputCoordinator The InputCoordinator (nullable)
         * @param layoutManager The LayoutManager (nullable)
         * @param keyboardView The Keyboard2View (nullable)
         * @param subtypeManager The SubtypeManager (nullable)
         * @return A new PropagatorInitializer instance
         */
        @JvmStatic
        fun create(
            suggestionHandler: SuggestionHandler?,
            neuralLayoutHelper: NeuralLayoutHelper?,
            debugLoggerImpl: SuggestionHandler.DebugLogger,
            debugLoggingManager: DebugLoggingManager,
            clipboardManager: ClipboardManager?,
            predictionCoordinator: PredictionCoordinator?,
            inputCoordinator: InputCoordinator?,
            layoutManager: LayoutManager?,
            keyboardView: Keyboard2View?,
            subtypeManager: SubtypeManager?
        ): PropagatorInitializer {
            return PropagatorInitializer(
                suggestionHandler,
                neuralLayoutHelper,
                debugLoggerImpl,
                debugLoggingManager,
                clipboardManager,
                predictionCoordinator,
                inputCoordinator,
                layoutManager,
                keyboardView,
                subtypeManager
            )
        }
    }
}
