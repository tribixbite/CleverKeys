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
 * - KeyboardDimensionsHelper: keyboard dimension and layout utilities
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
     * - KeyboardDimensionsHelper requires: predictionCoordinator, keyboardView
     *
     * Note: SuggestionBar reference will be set later via setSuggestionBar() on
     * InputCoordinator, SuggestionHandler, and KeyboardDimensionsHelper.
     */
    data class InitializationResult(
        val contractionManager: ContractionManager,
        val clipboardManager: ClipboardManager,
        val contextTracker: PredictionContextTracker,
        val predictionCoordinator: PredictionCoordinator,
        val inputCoordinator: InputCoordinator,
        val suggestionHandler: SuggestionHandler,
        val keyboardDimensionsHelper: KeyboardDimensionsHelper,
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
     * 7. KeyboardDimensionsHelper - requires predictionCoordinator, keyboardView
     * 8. MLDataCollector - requires context
     *
     * @return InitializationResult containing all initialized managers
     */
    fun initialize(): InitializationResult {
        MemoryProbe.reset()
        MemoryProbe.mark("init.enter", settle = true)

        // Contraction mappings for apostrophe insertion, scoped to the languages the user
        // actually selected (v1.32.341; language-scoped 2026-08-19).
        //
        // This used to load the English base, then the primary language, then English AGAIN
        // unconditionally — so a German user got "I'm" when typing `im` (rank 16) and a French
        // user got "don't" for `dont` (rank 104), because the base loaded first and both
        // loaders are earlier-wins. loadTypingMappings encodes the precedence: primary,
        // then secondary, then the English base ONLY if English is one of the two.
        val contractionManager = ContractionManager(context)
        val prefsForLang = DirectBootAwarePreferences.get_shared_preferences(context)
        val secondaryLang = prefsForLang
            .getString("pref_secondary_language", "none")
            ?.takeIf { prefsForLang.getBoolean("pref_enable_multilang", false) }
        contractionManager.loadTypingMappings(config.primary_language, secondaryLang)
        MemoryProbe.mark("init.contractionManager", settle = true) {
            "known=${contractionManager.getTotalKnownCount()}"
        }

        // Initialize clipboard manager (v1.32.349)
        val clipboardManager = ClipboardManager(context, config)

        // Initialize prediction context tracker (v1.32.342)
        val contextTracker = PredictionContextTracker()
        MemoryProbe.mark("init.clipboard+contextTracker", settle = true)

        // Initialize prediction coordinator (v1.32.346)
        val predictionCoordinator = PredictionCoordinator(context, config)
        // v1.1.90: CRITICAL - Must call initialize() to load the dictionary
        predictionCoordinator.initialize()
        MemoryProbe.mark("init.predictionCoordinator", settle = true)

        // Initialize input coordinator (v1.32.350)
        // Note: SuggestionBar will be set later in onStartInputView
        val inputCoordinator = InputCoordinator(
            context,
            config,
            contextTracker,
            predictionCoordinator,
            null, // suggestionBar created later
            keyboardView
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

        // WP9 R-1 steps 4-6: wire the unified delegates now that both exist. MANDATORY since
        // step 6 — InputCoordinator has no fallback pipelines anymore. SuggestionHandler owns
        // the whole swipe-results flow (possessives, password guard, THE single commit engine)
        // and the cursor-sync prediction+post phase (guarded pipeline, R-7). Bookkeeping
        // (debounce, cursor sync, swipe gesture/ML capture) stays in InputCoordinator.
        inputCoordinator.setSwipeResultDelegate(suggestionHandler)
        inputCoordinator.setCursorSyncDelegate(suggestionHandler)

        // Initialize keyboard-dimensions helper (v1.32.362)
        val keyboardDimensionsHelper = KeyboardDimensionsHelper(
            context,
            config
        )
        keyboardDimensionsHelper.setKeyboardView(keyboardView)

        // Initialize ML data collector (v1.32.370)
        val mlDataCollector = MLDataCollector(context)
        MemoryProbe.mark("init.done", settle = true)

        return InitializationResult(
            contractionManager,
            clipboardManager,
            contextTracker,
            predictionCoordinator,
            inputCoordinator,
            suggestionHandler,
            keyboardDimensionsHelper,
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
