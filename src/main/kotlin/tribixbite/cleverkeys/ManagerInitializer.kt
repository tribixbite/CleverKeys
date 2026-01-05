package tribixbite.cleverkeys

import android.content.Context

/**
 * Initializes all keyboard managers during onCreate().
 *
 * This class centralizes the complex initialization sequence of managers
 * that work together to provide keyboard functionality. Managers are created
 * in the correct dependency order.
 *
 * Responsibilities:
 * - Create manager instances with proper dependencies
 * - Handle cross-dependencies between managers
 * - Return all managers in a structured result
 *
 * Managers initialized:
 * - ContractionManager: Apostrophe contraction mappings
 * - ClipboardManager: Clipboard history and operations
 * - PredictionContextTracker: Context tracking for predictions
 * - PredictionCoordinator: Prediction engine coordination
 * - InputCoordinator: Input handling coordination
 * - SuggestionHandler: Suggestion display and selection
 * - NeuralLayoutHelper: Neural network and layout utilities
 * - MLDataCollector: ML training data collection
 *
 * NOT included (remain in CleverKeysService):
 * - LayoutManager: Requires subtype information from onCreate flow
 * - SubtypeManager: Requires IME context
 * - KeyboardReceiver: Requires view and manager references
 * - DebugLoggingManager: Already has its own lifecycle
 * - ConfigPropagator: Requires all managers to be initialized first
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.388).
 *
 * @since v1.32.388
 */
class ManagerInitializer(
    private val context: Context,
    private val config: Config,
    private val keyboardView: Keyboard2View,
    private val keyEventHandler: KeyEventHandler
) {
    /**
     * Result containing all initialized managers.
     *
     * Managers with cross-dependencies:
     * - InputCoordinator requires: contextTracker, predictionCoordinator, contractionManager, keyboardView, keyEventHandler
     * - SuggestionHandler requires: contextTracker, predictionCoordinator, contractionManager, keyEventHandler
     * - NeuralLayoutHelper requires: predictionCoordinator, keyboardView
     *
     * Note: SuggestionBar reference will be set later via setSuggestionBar() on
     * InputCoordinator, SuggestionHandler, and NeuralLayoutHelper.
     */
    data class InitializationResult(
        val contractionManager: ContractionManager,
        val clipboardManager: ClipboardManager,
        val contextTracker: PredictionContextTracker,
        val predictionCoordinator: PredictionCoordinator,
        val inputCoordinator: InputCoordinator,
        val suggestionHandler: SuggestionHandler,
        val neuralLayoutHelper: NeuralLayoutHelper,
        val mlDataCollector: MLDataCollector
    )

    /**
     * Initialize all managers in the correct dependency order.
     *
     * Initialization order:
     * 1. ContractionManager - no dependencies, loads mappings from resources
     * 2. ClipboardManager - requires config
     * 3. PredictionContextTracker - no dependencies
     * 4. PredictionCoordinator - requires context, config
     * 5. InputCoordinator - requires contextTracker, predictionCoordinator, contractionManager
     * 6. SuggestionHandler - requires contextTracker, predictionCoordinator, contractionManager
     * 7. NeuralLayoutHelper - requires predictionCoordinator, keyboardView
     * 8. MLDataCollector - requires context
     *
     * @return InitializationResult containing all initialized managers
     */
    fun initialize(): InitializationResult {
        // Load contraction mappings for apostrophe insertion (v1.32.341)
        val contractionManager = ContractionManager(context)
        contractionManager.loadMappings()

        // v1.1.87: Load language-specific contractions for the primary language
        // This enables French "cest" -> "c'est", Italian "luomo" -> "l'uomo", etc.
        val primaryLang = config.primary_language
        if (primaryLang != "en") {
            contractionManager.loadLanguageContractions(primaryLang)
        }
        // Always load English as fallback (already loaded in loadMappings, but language-specific file may have more)
        contractionManager.loadLanguageContractions("en")

        // Initialize clipboard manager (v1.32.349)
        val clipboardManager = ClipboardManager(context, config)

        // Initialize prediction context tracker (v1.32.342)
        val contextTracker = PredictionContextTracker()

        // Initialize prediction coordinator (v1.32.346)
        val predictionCoordinator = PredictionCoordinator(context, config)

        // Initialize input coordinator (v1.32.350)
        // Note: SuggestionBar will be set later in onStartInputView
        val inputCoordinator = InputCoordinator(
            context,
            config,
            contextTracker,
            predictionCoordinator,
            contractionManager,
            null, // suggestionBar created later
            keyboardView,
            keyEventHandler
        )

        // Initialize suggestion handler (v1.32.361)
        val suggestionHandler = SuggestionHandler(
            context,
            config,
            contextTracker,
            predictionCoordinator,
            contractionManager,
            keyEventHandler
        )

        // Initialize neural layout helper (v1.32.362)
        val neuralLayoutHelper = NeuralLayoutHelper(
            context,
            config,
            predictionCoordinator
        )
        neuralLayoutHelper.setKeyboardView(keyboardView)

        // Initialize ML data collector (v1.32.370)
        val mlDataCollector = MLDataCollector(context)

        return InitializationResult(
            contractionManager,
            clipboardManager,
            contextTracker,
            predictionCoordinator,
            inputCoordinator,
            suggestionHandler,
            neuralLayoutHelper,
            mlDataCollector
        )
    }

    companion object {
        /**
         * Create a ManagerInitializer instance.
         *
         * @param context Android context
         * @param config Current keyboard configuration
         * @param keyboardView Keyboard view instance
         * @param keyEventHandler Key event handler instance
         * @return A new ManagerInitializer instance
         */
        @JvmStatic
        fun create(
            context: Context,
            config: Config,
            keyboardView: Keyboard2View,
            keyEventHandler: KeyEventHandler
        ): ManagerInitializer {
            return ManagerInitializer(context, config, keyboardView, keyEventHandler)
        }
    }
}
