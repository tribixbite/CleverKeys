package tribixbite.cleverkeys

/**
 * Propagates configuration changes to all keyboard managers and components.
 *
 * This class centralizes the logic for updating configuration across multiple
 * managers when configuration changes occur (e.g., user preferences changed,
 * device rotated, fold state changed).
 *
 * Responsibilities:
 * - Propagate Config to all managers that need configuration updates
 * - Handle null checks for optional managers
 * - Reset keyboard view after config changes
 * - Refresh IME subtype settings
 *
 * Managers that receive config updates:
 * - ClipboardManager: Clipboard behavior settings
 * - PredictionCoordinator: Prediction engine settings
 * - InputCoordinator: Input handling settings
 * - SuggestionHandler: Suggestion display settings
 * - NeuralLayoutHelper: Neural network settings
 * - LayoutManager: Keyboard layout settings
 *
 * NOT included (remains in CleverKeysService):
 * - Config change listener registration
 * - Manager initialization
 * - Lifecycle management
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.386).
 *
 * @since v1.32.386
 */
class ConfigPropagator(
    private val clipboardManager: ClipboardManager?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val inputCoordinator: InputCoordinator?,
    private val suggestionHandler: SuggestionHandler?,
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val layoutManager: LayoutManager?,
    private val keyboardView: Keyboard2View?,
    private val subtypeManager: SubtypeManager?
) {
    /**
     * Propagate configuration to all managers.
     *
     * Updates configuration for all registered managers. Managers are updated
     * in a specific order to ensure dependencies are handled correctly:
     * 1. Refresh IME subtype settings (may affect layout)
     * 2. Update manager configurations
     * 3. Reset keyboard view to apply changes
     *
     * @param config The new configuration to propagate
     * @param resources Resources for subtype refresh (required by SubtypeManager)
     */
    fun propagateConfig(config: Config, resources: android.content.res.Resources? = null) {
        // Refresh subtitle IME (requires resources)
        if (resources != null) {
            subtypeManager?.refreshSubtype(config, resources)
        }

        // Update clipboard manager config
        clipboardManager?.setConfig(config)

        // Update prediction coordinator config
        predictionCoordinator?.setConfig(config)

        // Update input coordinator config
        inputCoordinator?.setConfig(config)

        // Update suggestion handler config
        suggestionHandler?.setConfig(config)

        // Update neural layout helper config
        neuralLayoutHelper?.setConfig(config)

        // Update layout manager config
        layoutManager?.setConfig(config)

        // Reset keyboard view to apply changes
        resetKeyboardView()
    }

    /**
     * Reset keyboard view to apply configuration changes.
     *
     * This should be called after configuration changes to ensure
     * the keyboard view reflects the new configuration.
     */
    fun resetKeyboardView() {
        keyboardView?.reset()
    }

    /**
     * Builder for ConfigPropagator.
     *
     * Provides a fluent API for constructing ConfigPropagator instances
     * with optional manager references.
     */
    class Builder {
        private var clipboardManager: ClipboardManager? = null
        private var predictionCoordinator: PredictionCoordinator? = null
        private var inputCoordinator: InputCoordinator? = null
        private var suggestionHandler: SuggestionHandler? = null
        private var neuralLayoutHelper: NeuralLayoutHelper? = null
        private var layoutManager: LayoutManager? = null
        private var keyboardView: Keyboard2View? = null
        private var subtypeManager: SubtypeManager? = null

        fun setClipboardManager(manager: ClipboardManager?): Builder {
            this.clipboardManager = manager
            return this
        }

        fun setPredictionCoordinator(coordinator: PredictionCoordinator?): Builder {
            this.predictionCoordinator = coordinator
            return this
        }

        fun setInputCoordinator(coordinator: InputCoordinator?): Builder {
            this.inputCoordinator = coordinator
            return this
        }

        fun setSuggestionHandler(handler: SuggestionHandler?): Builder {
            this.suggestionHandler = handler
            return this
        }

        fun setNeuralLayoutHelper(helper: NeuralLayoutHelper?): Builder {
            this.neuralLayoutHelper = helper
            return this
        }

        fun setLayoutManager(manager: LayoutManager?): Builder {
            this.layoutManager = manager
            return this
        }

        fun setKeyboardView(view: Keyboard2View?): Builder {
            this.keyboardView = view
            return this
        }

        fun setSubtypeManager(manager: SubtypeManager?): Builder {
            this.subtypeManager = manager
            return this
        }

        fun build(): ConfigPropagator {
            return ConfigPropagator(
                clipboardManager,
                predictionCoordinator,
                inputCoordinator,
                suggestionHandler,
                neuralLayoutHelper,
                layoutManager,
                keyboardView,
                subtypeManager
            )
        }
    }

    companion object {
        /**
         * Create a builder for ConfigPropagator.
         *
         * @return A new Builder instance
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
