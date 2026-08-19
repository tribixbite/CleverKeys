package tribixbite.cleverkeys

import android.content.Context
import android.util.Log

/**
 * Handles UI updates when SharedPreferences change.
 *
 * This handler consolidates UI update logic triggered by preference changes:
 * - Updates keyboard layout view when layout preferences change
 * - Updates suggestion bar opacity when opacity preference changes
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
    private val suggestionBar: SuggestionBar?,
    private val contractionManager: ContractionManager? = null  // v1.2.0: For contraction reload on language toggle
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
     * Reload language dictionaries if language settings changed.
     *
     * When the user changes pref_primary_language or pref_secondary_language, this reloads
     * the tap-typing dictionaries and the contraction mappings. The swipe engines need no
     * hook: the CTC adapter re-derives its merged lexicon from a content hash (so a language
     * change invalidates it automatically) and the geometric engine rebuilds its template
     * index per (layout, language).
     *
     * @param key The preference key that changed
     * @since v1.1.86
     */
    private fun reloadLanguageDictionaryIfNeeded(key: String?) {
        if (key == null) return

        try {
            when (key) {
                "pref_primary_language" -> {
                    // v1.1.90: Reload the WordPredictor dictionary for touch typing
                    // Read fresh language value from prefs (config may be stale or shared)
                    val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
                    val newPrimaryLang = prefs.getString("pref_primary_language", "en") ?: "en"
                    predictionCoordinator?.reloadWordPredictorDictionary(newPrimaryLang)
                    Log.i(TAG, "Primary language changed to '$newPrimaryLang' - touch typing dictionary reload triggered")

                    // v1.2.0: Reload contractions for the new language.
                    // Must match ManagerInitializer — both go through loadTypingMappings, which
                    // owns the precedence rule (primary, then secondary, then the English base
                    // ONLY if English is one of the two). Before 2026-08-19 both call sites
                    // hand-rolled "base + language + always English", which is how a German user
                    // ended up with "I'm" for `im`; duplicating that order in two places is
                    // exactly why it had to be fixed twice.
                    contractionManager?.let { cm ->
                        val secondary = prefs.getString("pref_secondary_language", "none")
                            ?.takeIf { prefs.getBoolean("pref_enable_multilang", false) }
                        cm.loadTypingMappings(newPrimaryLang, secondary)
                        Log.i(TAG, "Contractions reloaded for primary '$newPrimaryLang'")
                    }
                }
                "pref_secondary_language" -> {
                    // v1.1.93: Reload the secondary dictionary for touch typing
                    val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
                    val newSecondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"
                    predictionCoordinator?.reloadWordPredictorSecondaryDictionary(newSecondaryLang)
                    // The secondary language now participates in contraction scoping too
                    // (2026-08-19): selecting English as secondary is what RE-ADMITS English
                    // morphology, and selecting French as secondary is what makes `m'appelle`
                    // work. Neither takes effect until the manager reloads, so this must fire
                    // here as well as on a primary change.
                    contractionManager?.let { cm ->
                        val primary = prefs.getString("pref_primary_language", "en") ?: "en"
                        cm.loadTypingMappings(
                            primary,
                            newSecondaryLang.takeIf { prefs.getBoolean("pref_enable_multilang", false) }
                        )
                    }
                    Log.i(TAG, "Secondary language changed to '$newSecondaryLang' - dictionaries reloaded")
                }
                "pref_enable_multilang" -> {
                    // Reload secondary dict when multilang toggle changes
                    val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
                    val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"
                    predictionCoordinator?.reloadWordPredictorSecondaryDictionary(secondaryLang)
                    // Toggling multilang OFF must also retract the secondary language's
                    // contractions — otherwise a user who disables it keeps seeing the second
                    // language's apostrophe forms, which is the same class of leak the language
                    // scoping fixed.
                    contractionManager?.let { cm ->
                        val primary = prefs.getString("pref_primary_language", "en") ?: "en"
                        cm.loadTypingMappings(
                            primary,
                            secondaryLang.takeIf { prefs.getBoolean("pref_enable_multilang", false) }
                        )
                    }
                    Log.i(TAG, "Multilang toggle changed - secondary dictionaries reloaded")
                }
            }
        } catch (t: Throwable) {
            // Catch Throwable (not just Exception) to prevent OOM/Error from killing IME
            // during dictionary reload triggered by language toggle
            Log.e(TAG, "Failed to reload dictionary on language change: ${t.message}", t)
        }
    }

    companion object {
        // Log tag kept <=23 chars so Log.isLoggable does not crash on API <26 (LongLogTag lint).
        private const val TAG = "PrefUIUpdateHandler"

        /**
         * Create a PreferenceUIUpdateHandler.
         *
         * @param context The Android context for accessing orchestrator
         * @param config The configuration
         * @param layoutBridge The layout bridge (nullable)
         * @param predictionCoordinator The prediction coordinator (nullable)
         * @param keyboardView The keyboard view (nullable)
         * @param suggestionBar The suggestion bar (nullable)
         * @param contractionManager The contraction manager (nullable, for v1.2.0 language toggle fix)
         * @return A new PreferenceUIUpdateHandler instance
         */
        @JvmStatic
        fun create(
            context: Context,
            config: Config?,
            layoutBridge: LayoutBridge?,
            predictionCoordinator: PredictionCoordinator?,
            keyboardView: Keyboard2View?,
            suggestionBar: SuggestionBar?,
            contractionManager: ContractionManager? = null
        ): PreferenceUIUpdateHandler {
            return PreferenceUIUpdateHandler(
                context,
                config,
                layoutBridge,
                predictionCoordinator,
                keyboardView,
                suggestionBar,
                contractionManager
            )
        }
    }
}
