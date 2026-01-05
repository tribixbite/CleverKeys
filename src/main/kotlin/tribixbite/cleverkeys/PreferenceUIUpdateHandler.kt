package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.onnx.SwipePredictorOrchestrator

/**
 * Handles UI updates when SharedPreferences change.
 *
 * This handler consolidates UI update logic triggered by preference changes:
 * - Updates keyboard layout view when layout preferences change
 * - Updates suggestion bar opacity when opacity preference changes
 * - Updates neural engine config when model-related settings change
 * - Reloads primary/secondary language dictionaries when language settings change
 *
 * Note: ConfigurationManager is the primary SharedPreferences listener and
 * handles config refresh. This handler focuses on UI-specific updates.
 *
 * Extracted from CleverKeysService.onSharedPreferenceChanged() to reduce main class size.
 *
 * @since v1.32.412
 * @since v1.1.86 - Added language dictionary reload on pref_primary_language/pref_secondary_language change
 */
class PreferenceUIUpdateHandler(
    private val context: Context,
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

        // Reload language dictionaries if language settings changed
        reloadLanguageDictionaryIfNeeded(key)
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

    /**
     * Reload language dictionaries if language settings changed.
     *
     * When user changes pref_primary_language or pref_secondary_language in settings,
     * this triggers a full reload of the vocabulary trie used by beam search.
     * This ensures the neural prediction uses the correct language dictionary.
     *
     * Note: Primary language is currently read-only (NN only supports English),
     * but we still handle both for future extensibility.
     *
     * @param key The preference key that changed
     * @since v1.1.86
     */
    private fun reloadLanguageDictionaryIfNeeded(key: String?) {
        if (key == null) return

        try {
            val orchestrator = SwipePredictorOrchestrator.getInstance(context)

            when (key) {
                "pref_primary_language" -> {
                    // Reload swipe dictionary (OptimizedVocabulary/beam search trie)
                    orchestrator.reloadPrimaryDictionary()
                    Log.i(TAG, "Primary language changed - swipe dictionary reloaded")

                    // v1.1.90: Also reload WordPredictor dictionary for touch typing
                    // Read fresh language value from prefs (config may be stale or shared)
                    val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
                    val newPrimaryLang = prefs.getString("pref_primary_language", "en") ?: "en"
                    predictionCoordinator?.reloadWordPredictorDictionary(newPrimaryLang)
                    Log.i(TAG, "Primary language changed to '$newPrimaryLang' - touch typing dictionary reload triggered")
                }
                "pref_secondary_language" -> {
                    orchestrator.reloadSecondaryDictionary()
                    Log.i(TAG, "Secondary language changed - dictionary reloaded")
                }
                "pref_enable_multilang" -> {
                    // Reload secondary dict when multilang toggle changes
                    orchestrator.reloadSecondaryDictionary()
                    Log.i(TAG, "Multilang toggle changed - secondary dictionary reloaded")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload dictionary on language change: ${e.message}", e)
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
         * @param context The Android context for accessing orchestrator
         * @param config The configuration
         * @param layoutBridge The layout bridge (nullable)
         * @param predictionCoordinator The prediction coordinator (nullable)
         * @param keyboardView The keyboard view (nullable)
         * @param suggestionBar The suggestion bar (nullable)
         * @return A new PreferenceUIUpdateHandler instance
         */
        @JvmStatic
        fun create(
            context: Context,
            config: Config?,
            layoutBridge: LayoutBridge?,
            predictionCoordinator: PredictionCoordinator?,
            keyboardView: Keyboard2View?,
            suggestionBar: SuggestionBar?
        ): PreferenceUIUpdateHandler {
            return PreferenceUIUpdateHandler(
                context,
                config,
                layoutBridge,
                predictionCoordinator,
                keyboardView,
                suggestionBar
            )
        }
    }
}
