package tribixbite.cleverkeys

import android.util.Log

/**
 * Handles UI updates when SharedPreferences change.
 *
 * This handler consolidates UI update logic triggered by preference changes:
 * - Updates keyboard layout view when layout preferences change
 * - Updates suggestion bar opacity when opacity preference changes
 * - Updates neural engine config when model-related settings change
 *
 * Note: ConfigurationManager is the primary SharedPreferences listener and
 * handles config refresh. This handler focuses on UI-specific updates.
 *
 * Extracted from CleverKeysService.onSharedPreferenceChanged() to reduce main class size.
 *
 * @since v1.32.412
 */
class PreferenceUIUpdateHandler(
    private val config: Config?,
    private val layoutBridge: LayoutBridge?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val keyboardView: Keyboard2View?,
    private val suggestionBar: SuggestionBar?
) {
    /**
     * Handle UI updates for preference changes.
     *
     * @param key The preference key that changed (nullable)
     */
    fun handlePreferenceChange(key: String?) {
        // Update keyboard layout view
        updateKeyboardLayout()

        // Update suggestion bar opacity
        updateSuggestionBarOpacity()

        // Update neural engine config for model-related settings
        updateNeuralEngineIfNeeded(key)
    }

    /**
     * Update keyboard layout view with current layout.
     */
    private fun updateKeyboardLayout() {
        val layout = layoutBridge?.getCurrentLayout()
        if (layout != null) {
            keyboardView?.setKeyboard(layout)
        }
    }

    /**
     * Update suggestion bar opacity from config.
     */
    private fun updateSuggestionBarOpacity() {
        config?.let { cfg ->
            suggestionBar?.setOpacity(cfg.suggestion_bar_opacity)
        }
    }

    /**
     * Update neural engine config if model-related setting changed.
     *
     * @param key The preference key that changed
     */
    private fun updateNeuralEngineIfNeeded(key: String?) {
        if (key == null) return

        val isModelSetting = key in MODEL_RELATED_KEYS

        if (isModelSetting && config != null) {
            val neuralEngine = predictionCoordinator?.getNeuralEngine()
            if (neuralEngine != null) {
                neuralEngine.setConfig(config)
                Log.d(TAG, "Neural model setting changed: $key - engine config updated")
            }
        }
    }

    companion object {
        private const val TAG = "PreferenceUIUpdateHandler"

        /**
         * Preference keys that require neural engine config updates.
         */
        private val MODEL_RELATED_KEYS = setOf(
            "neural_user_max_seq_length"
        )

        /**
         * Create a PreferenceUIUpdateHandler.
         *
         * @param config The configuration
         * @param layoutBridge The layout bridge (nullable)
         * @param predictionCoordinator The prediction coordinator (nullable)
         * @param keyboardView The keyboard view (nullable)
         * @param suggestionBar The suggestion bar (nullable)
         * @return A new PreferenceUIUpdateHandler instance
         */
        @JvmStatic
        fun create(
            config: Config?,
            layoutBridge: LayoutBridge?,
            predictionCoordinator: PredictionCoordinator?,
            keyboardView: Keyboard2View?,
            suggestionBar: SuggestionBar?
        ): PreferenceUIUpdateHandler {
            return PreferenceUIUpdateHandler(
                config,
                layoutBridge,
                predictionCoordinator,
                keyboardView,
                suggestionBar
            )
        }
    }
}
