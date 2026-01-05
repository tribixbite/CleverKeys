package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import tribixbite.cleverkeys.ml.SwipeMLDataStore
import kotlin.concurrent.thread

/**
 * Coordinates prediction engines and manages prediction lifecycle.
 *
 * This class centralizes the management of:
 * - DictionaryManager (dictionary loading and management)
 * - WordPredictor (typing predictions and context)
 * - NeuralSwipeTypingEngine (swipe typing ML model)
 * - AsyncPredictionHandler (asynchronous prediction processing)
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
    private var wordPredictor: WordPredictor? = null
    private var neuralEngine: NeuralSwipeTypingEngine? = null
    private var asyncPredictionHandler: AsyncPredictionHandler? = null

    @Volatile
    private var isInitializingNeuralEngine = false // v1.32.529: Track initialization state

    // Supporting services
    private var mlDataStore: SwipeMLDataStore? = null
    private var adaptationManager: UserAdaptationManager? = null

    // Debug logging
    private var debugLogger: NeuralSwipeTypingEngine.DebugLogger? = null

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

        // Initialize neural engine if swipe typing is enabled
        // This uses DE storage so it's safe before unlock
        // CRITICAL: Must be SYNCHRONOUS to ensure first swipe works
        // ~200ms load is acceptable for cold start; singleton persists after
        if (config.swipe_typing_enabled) {
            initializeNeuralEngine()
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

            // OPTIMIZATION: Start observing dictionary changes for automatic updates
            startObservingDictionaryChanges()
        }

        Log.d(TAG, "WordPredictor initialized with automatic update observation")
    }

    /**
     * Initializes neural engine for swipe typing.
     * OPTIMIZATION v1.32.529: Removed synchronized as it's now protected by double-checked locking
     * in ensureNeuralEngineReady and initialize
     */
    private fun initializeNeuralEngine() {
        // Skip if already initialized or initializing
        if (neuralEngine != null || isInitializingNeuralEngine) {
            return
        }

        try {
            isInitializingNeuralEngine = true
            val engine = NeuralSwipeTypingEngine(context, config)

            // Set debug logger before initialization so logs appear during model loading
            debugLogger?.let {
                engine.setDebugLogger(it)
                Log.d(TAG, "Debug logger set on neural engine")
            }

            // CRITICAL: Call initialize() to actually load the ONNX models
            val success = engine.initialize()
            if (!success) {
                Log.e(TAG, "Neural engine initialization returned false")
                neuralEngine = null
                asyncPredictionHandler = null
                return
            }

            neuralEngine = engine

            // Initialize async prediction handler with context for performance stats
            asyncPredictionHandler = AsyncPredictionHandler(engine, context)

            Log.d(TAG, "NeuralSwipeTypingEngine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize neural engine", e)
            neuralEngine = null
            asyncPredictionHandler = null
        } finally {
            isInitializingNeuralEngine = false
        }
    }

    /**
     * Ensures neural engine is initialized before use.
     * OPTIMIZATION v1.32.529: Double-checked locking to prevent Main Thread blocking
     * This allows check to be fast if already initialized, without acquiring lock.
     * FIX v1.32.530: Wait for background initialization if in progress, with timeout
     */
    fun ensureNeuralEngineReady() {
        if (!config.swipe_typing_enabled) return

        // Fast path: already initialized
        if (neuralEngine != null) return

        // If background thread is initializing, wait for it (up to 5 seconds)
        if (isInitializingNeuralEngine) {
            Log.d(TAG, "Waiting for background ONNX initialization to complete...")
            val startTime = System.currentTimeMillis()
            val maxWaitMs = 5000L
            while (isInitializingNeuralEngine && neuralEngine == null) {
                if (System.currentTimeMillis() - startTime > maxWaitMs) {
                    Log.w(TAG, "Timeout waiting for ONNX initialization (${maxWaitMs}ms)")
                    break
                }
                Thread.sleep(50)
            }
            if (neuralEngine != null) {
                Log.d(TAG, "ONNX initialization completed after waiting")
                return
            }
        }

        // Fallback: synchronous initialization if not started or timed out
        synchronized(this) {
            if (neuralEngine == null && !isInitializingNeuralEngine) {
                Log.d(TAG, "Lazy-loading neural engine on first swipe...")
                initializeNeuralEngine()
            }
        }
    }

    /**
     * Sets the debug logger for neural engine logging.
     * Should be called before initialize() for model loading logs.
     *
     * @param logger Debug logger implementation that sends to SwipeDebugActivity
     */
    fun setDebugLogger(logger: NeuralSwipeTypingEngine.DebugLogger) {
        debugLogger = logger

        // Also set on existing engine if already initialized
        neuralEngine?.let {
            it.setDebugLogger(logger)
            Log.d(TAG, "Debug logger updated on existing neural engine")
        }
    }

    /**
     * Set debug mode active state. When false, expensive debug logging is skipped.
     */
    fun setDebugModeActive(active: Boolean) {
        neuralEngine?.setDebugModeActive(active)
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

        if (config.swipe_typing_enabled && neuralEngine == null) {
            initializeNeuralEngine()
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

        // Update neural engine config if it exists
        neuralEngine?.setConfig(config)

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
     * Gets the WordPredictor instance.
     *
     * @return WordPredictor for typing predictions, or null if not initialized
     */
    fun getWordPredictor(): WordPredictor? {
        return wordPredictor
    }

    /**
     * Gets the NeuralSwipeTypingEngine instance.
     *
     * @return Neural engine for swipe predictions, or null if not initialized
     */
    fun getNeuralEngine(): NeuralSwipeTypingEngine? {
        return neuralEngine
    }

    /**
     * Gets the AsyncPredictionHandler instance.
     *
     * @return Async handler for background predictions, or null if not initialized
     */
    fun getAsyncPredictionHandler(): AsyncPredictionHandler? {
        return asyncPredictionHandler
    }

    /**
     * Gets the DictionaryManager instance.
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
     * Checks if swipe typing is available.
     *
     * @return true if neural engine is initialized and ready
     */
    fun isSwipeTypingAvailable(): Boolean {
        return neuralEngine != null
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
     * Shuts down all prediction engines and cleans up resources.
     * Should be called during keyboard shutdown.
     */
    fun shutdown() {
        // Shutdown async prediction handler
        asyncPredictionHandler?.let {
            it.shutdown()
            asyncPredictionHandler = null
        }

        // Stop observing dictionary changes
        wordPredictor?.stopObservingDictionaryChanges()

        // Clean up engines
        neuralEngine = null
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
        return "PredictionCoordinator{wordPredictor=${if (wordPredictor != null) "initialized" else "null"}, " +
            "neuralEngine=${if (neuralEngine != null) "initialized" else "null"}, " +
            "asyncHandler=${if (asyncPredictionHandler != null) "initialized" else "null"}}"
    }
}
