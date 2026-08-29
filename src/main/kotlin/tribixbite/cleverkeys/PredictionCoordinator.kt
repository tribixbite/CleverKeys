package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * Coordinates prediction engines and manages prediction lifecycle.
 *
 * This class centralizes the management of:
 * - DictionaryManager (active prediction language + the user's custom words)
 * - WordPredictor (typing predictions and context) — the process's ONLY predictor
 *   since ARC-079; it is the sole holder of loaded dictionaries and learned data.
 *
 * Responsibilities:
 * - Initialize and configure prediction engines
 * - Coordinate predictions from multiple sources
 * - Manage engine lifecycle (shutdown, cleanup)
 * - Provide unified interface for prediction requests
 *
 * NOT included (remains in CleverKeysService):
 * - SuggestionBar UI integration
 * - InputConnection text insertion
 * - Auto-insertion logic
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.346).
 */
class PredictionCoordinator(
    private val context: Context,
    private var config: Config
) {
    companion object {
        private const val TAG = "PredictionCoordinator"
    }

    // Prediction engines
    private var dictionaryManager: DictionaryManager? = null
    private var wordPredictor: Predictor? = null

    // Supporting services
    private var mlDataStore: SwipeMLDataStore? = null
    private var adaptationManager: UserAdaptationManager? = null

    // Track if PII components have been initialized (Direct Boot compatibility)
    @Volatile
    private var piiComponentsInitialized = false

    /**
     * Check if user has unlocked the device (Direct Boot compatibility).
     */
    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= 24) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            true // Pre-N doesn't have Direct Boot
        }
    }

    /**
     * Initializes prediction engines based on configuration.
     * Should be called during keyboard startup.
     *
     * DIRECT BOOT: PII components (DictionaryManager, UserAdaptationManager,
     * WordPredictor with personalization) are deferred until user unlock to
     * avoid crash when accessing Credential Encrypted storage at lock screen.
     */
    fun initialize() {
        // Check if user is unlocked
        if (isUserUnlocked()) {
            // User is unlocked, initialize everything
            initializePiiComponents()
        } else {
            // Device is locked, defer PII component initialization
            Log.i(TAG, "Device locked - deferring PII component initialization until unlock")
            DirectBootManager.getInstance(context).registerUnlockCallback {
                Log.i(TAG, "Device unlocked - initializing PII components")
                initializePiiComponents()
            }
        }
    }

    /**
     * Initialize PII components that require Credential Encrypted storage.
     * Called after user unlocks the device.
     */
    private fun initializePiiComponents() {
        if (piiComponentsInitialized) {
            Log.d(TAG, "PII components already initialized")
            return
        }

        try {
            // Initialize ML data store (uses SQLite, needs CE storage)
            mlDataStore = SwipeMLDataStore.getInstance(context)

            // Initialize user adaptation manager (uses SharedPreferences, needs CE storage)
            adaptationManager = UserAdaptationManager.getInstance(context)

            // Initialize dictionary manager and word predictor
            initializeWordPredictor()

            piiComponentsInitialized = true
            Log.i(TAG, "PII components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PII components", e)
        }
    }

    /**
     * Initializes word predictor for typing predictions.
     */
    private fun initializeWordPredictor() {
        // v1.1.89: Use primary language from config instead of hardcoding "en"
        val primaryLang = config.primary_language

        dictionaryManager = DictionaryManager(context).apply {
            setLanguage(primaryLang)
        }
        // ARC-079: this phase used to be named `wordPredictor.dictionaryManager` because the
        // manager built a full second predictor here. It no longer holds a dictionary at all —
        // just the active language and the user's custom words — so a near-zero delta is now
        // the EXPECTED reading, and is itself the on-device evidence that the duplicate
        // residency is gone. (Adjudicating the total startup footprint remains ARC-070's
        // device measurement, not this mark's job.)
        MemoryProbe.mark("dictionaryManager.userWordsOnly", settle = true) { "lang=$primaryLang" }

        wordPredictor = WordPredictor().apply {
            setContext(context) // Enable disabled words filtering
            setConfig(config)
            adaptationManager?.let { setUserAdaptationManager(it) }

            // FIX: Load dictionary asynchronously to prevent Main Thread blocking during startup
            // This prevents ANRs when the keyboard initializes
            // v1.1.89: Load primary language dictionary instead of hardcoding English
            Log.d(TAG, "Starting async dictionary loading for '$primaryLang'...")
            loadDictionaryAsync(context, primaryLang) {
                Log.d(TAG, "Dictionary loaded successfully: $primaryLang")
            }

            // v1.1.93: Load secondary dictionary for bilingual touch typing
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
            val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"
            if (multiLangEnabled && secondaryLang != "none" && secondaryLang.isNotEmpty()) {
                Log.d(TAG, "Loading secondary dictionary for touch typing: $secondaryLang")
                loadSecondaryDictionary(secondaryLang)
                MemoryProbe.mark("wordPredictor.secondary", settle = true) { "lang=$secondaryLang" }
            }

            // OPTIMIZATION: Start observing dictionary changes for automatic updates
            startObservingDictionaryChanges()
        }

        Log.d(TAG, "WordPredictor initialized with automatic update observation")
    }

    /**
     * Ensures word predictor is initialized (lazy initialization).
     * Called when predictions are first requested.
     *
     * Note: If device is still locked, PII components won't be available
     * and predictions will be limited.
     */
    fun ensureInitialized() {
        // Only initialize PII components if user is unlocked
        if (wordPredictor == null && isUserUnlocked()) {
            initializePiiComponents()
        }
    }

    /**
     * Updates configuration and propagates to engines.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        val oldPrimaryLang = config.primary_language
        config = newConfig
        val newPrimaryLang = config.primary_language

        // Update word predictor config if it exists
        wordPredictor?.setConfig(config)

        // v1.1.89: Reload dictionary if primary language changed
        if (oldPrimaryLang != newPrimaryLang && wordPredictor != null) {
            Log.i(TAG, "Primary language changed from '$oldPrimaryLang' to '$newPrimaryLang' - reloading dictionary")
            wordPredictor?.loadDictionaryAsync(context, newPrimaryLang) {
                Log.i(TAG, "Dictionary reloaded for '$newPrimaryLang'")
            }
            dictionaryManager?.setLanguage(newPrimaryLang)
        }
    }

    /**
     * Reload WordPredictor dictionary for a specific language.
     * Called when language preference changes.
     *
     * v1.1.90: Direct reload method that doesn't rely on config comparison
     * (since config object is shared and already updated when this is called)
     *
     * @param language Language code to load (e.g., "fr", "de", "en")
     */
    fun reloadWordPredictorDictionary(language: String) {
        if (wordPredictor == null) {
            Log.w(TAG, "Cannot reload dictionary - WordPredictor not initialized")
            return
        }

        Log.i(TAG, "Reloading WordPredictor dictionary for language: $language")
        wordPredictor?.loadDictionaryAsync(context, language) {
            Log.i(TAG, "WordPredictor dictionary reloaded for '$language'")
        }
        dictionaryManager?.setLanguage(language)
    }

    /**
     * v1.1.93: Reload secondary dictionary for bilingual touch typing.
     * Called when secondary language preference changes.
     *
     * @param language Secondary language code (e.g., "es", "fr") or "none" to unload
     */
    fun reloadWordPredictorSecondaryDictionary(language: String) {
        if (wordPredictor == null) {
            Log.w(TAG, "Cannot reload secondary dictionary - WordPredictor not initialized")
            return
        }

        if (language == "none" || language.isEmpty()) {
            Log.i(TAG, "Unloading secondary dictionary for touch typing")
            wordPredictor?.unloadSecondaryDictionary()
        } else {
            Log.i(TAG, "Loading secondary dictionary for touch typing: $language")
            wordPredictor?.loadSecondaryDictionary(language)
        }
    }

    /**
     * Refresh custom words in both touch typing and swipe typing predictors.
     * Call after adding a new word to the dictionary.
     *
     * @since v1.2.2
     */
    fun refreshCustomWords() {
        Log.d(TAG, "Refreshing custom words in all predictors")

        // Reload in touch typing predictor. The CTC engine re-derives its merged lexicon
        // from a content hash, so it self-invalidates and needs no explicit reload here.
        wordPredictor?.reloadCustomAndUserWords()
    }

    // ── Unigram language detection: DELETED 2026-08-28 (ARC-006) ──────────────────────
    //
    // `UnigramLanguageDetector` was re-homed here from SwipePredictorOrchestrator during the
    // neural removal (ADR-011 §E) on the strength of "every commit feeds it". It was fed —
    // and read by nobody: `getLanguageScores`/`getDetectedLanguage` lost their last caller
    // when `OptimizedVocabulary` was deleted, so the whole path cost a lazy per-language
    // 5k-unigram asset load plus a sliding-window update on every committed word, produced
    // no output, and swallowed its own failures.
    //
    // The detector class and its `assets/unigrams/*.txt` profiles are gone with it. Bringing
    // it back means writing the CONSUMER first (auto language switching is the only design
    // that ever wanted it) together with the pure test ADR-011 §E promised — the feed on its
    // own is not evidence of anything. Auto-detection today runs through the separate
    // `LanguageDetector`/`MultiLanguageManager` pair, which is unaffected by this deletion.

    /**
     * Gets the WordPredictor instance.
     *
     * @return WordPredictor for typing predictions, or null if not initialized
     */
    fun getWordPredictor(): Predictor? {
        return wordPredictor
    }

    /**
     * Gets the DictionaryManager instance — the active language and the user's custom words
     * for it. Not a source of predictions or predictors (ARC-079).
     *
     * @return Dictionary manager, or null if not initialized
     */
    fun getDictionaryManager(): DictionaryManager? {
        return dictionaryManager
    }

    /**
     * Gets the SwipeMLDataStore instance.
     *
     * @return ML data store for swipe training data, or null if not initialized
     */
    fun getMlDataStore(): SwipeMLDataStore? {
        return mlDataStore
    }

    /**
     * Gets the UserAdaptationManager instance.
     *
     * @return User adaptation manager for learning user preferences, or null if not initialized
     */
    fun getAdaptationManager(): UserAdaptationManager? {
        return adaptationManager
    }

    /**
     * Checks if word prediction is available.
     *
     * @return true if word predictor is initialized and ready
     */
    fun isWordPredictionAvailable(): Boolean {
        return wordPredictor != null
    }

    /**
     * Checkpoint all learned data (context LM bigrams + personalization
     * vocabulary) held by the predictor. Asynchronous debounced-store flush —
     * cheap no-op when nothing is dirty. Called from
     * CleverKeysService.onFinishInputView and from [shutdown] — i.e. exactly at
     * input-session boundaries.
     *
     * ARC-079: this used to fan out to [DictionaryManager]'s per-language
     * predictor cache as well. That cache is deleted, so [wordPredictor] is the
     * only holder left — which makes this method the ONLY checkpoint on the way
     * to disk. Losing the call loses everything learned since the last boundary.
     * (No user learning was lost with the cache: `ContextModel` and
     * `PersonalizationEngine` sit on process singletons — BigramStore /
     * TrigramStore / UserVocabulary `getInstance` — so the cached predictors were
     * flushing the very same stores this line flushes.)
     *
     * H1 (review 2026-08-06): after the flush, the predictor's rolling
     * recent-words window is CLEARED. Without this, the learn funnel's window
     * straddled the field/app boundary — the last words typed in app A were
     * joined to the first word committed in app B, learned as a bigram, and
     * surfaced cross-app by next-word prediction. (`_contextTracker.clearAll()`
     * only cleared the surface tracker, not this buffer.)
     */
    fun flushLearnedData() {
        try {
            wordPredictor?.persistLearnedData()
            wordPredictor?.clearContext()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing learned data", e)
        }
    }

    /**
     * Shuts down all prediction engines and cleans up resources.
     * Should be called during keyboard shutdown.
     */
    fun shutdown() {
        // Checkpoint learned data (context LM bigrams + user vocabulary) BEFORE the
        // predictor/dictionary teardown below discards the live instances
        // (2026-08-06 persistence fix)
        flushLearnedData()

        // Stop observing dictionary changes
        wordPredictor?.stopObservingDictionaryChanges()

        // ARC-079: the manager is not torn down here any more — it owns no predictor and no
        // dictionary observer, so it has nothing to release beyond ordinary GC.
        wordPredictor = null
        dictionaryManager = null

        Log.d(TAG, "PredictionCoordinator shutdown complete")
    }

    /**
     * Gets a debug string showing current state.
     * Useful for logging and troubleshooting.
     *
     * @return Human-readable state description
     */
    fun getDebugState(): String {
        return "PredictionCoordinator{wordPredictor=" +
            "${if (wordPredictor != null) "initialized" else "null"}, " +
            "dictionaryManager=${if (dictionaryManager != null) "initialized" else "null"}}"
    }
}
