package tribixbite.cleverkeys

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import tribixbite.cleverkeys.ml.SwipeMLDataStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Pure-JVM one-shot gate coordinating a single neural-engine initialization attempt.
 *
 * Replaces the former main-thread busy-wait: waiters park on a [CountDownLatch] instead of
 * spinning in a sleep loop, and a single [pending] action is flushed exactly once when the
 * attempt completes (or immediately if it already has). Contains NO Android types so it is
 * unit-testable on the JVM.
 */
class EngineInitGate {
    // Replaceable so a completed gate can be re-armed for a NEW attempt cycle (e.g. after a
    // failed init's retry backoff elapses). Read/replaced under `latchLock`; individual latch
    // operations are themselves thread-safe.
    @Volatile
    private var attemptDone = CountDownLatch(1)
    private val latchLock = Any()
    private val pending = AtomicReference<Runnable?>(null)

    /** True once the current attempt cycle has finished, regardless of outcome. */
    val hasCompletedAttempt: Boolean
        get() = attemptDone.count == 0L

    /** Marks the current initialization attempt complete and flushes any pending action. Idempotent. */
    fun markAttemptComplete() {
        attemptDone.countDown()
        flushPending()
    }

    /**
     * Re-arms the gate for a new attempt cycle if the previous one has completed: installs a
     * fresh latch so new waiters park and [hasCompletedAttempt] reports false again. No-op if
     * an attempt is already pending (latch still armed). Returns true if it re-armed.
     */
    fun rearmIfCompleted(): Boolean = synchronized(latchLock) {
        if (attemptDone.count == 0L) {
            attemptDone = CountDownLatch(1)
            true
        } else {
            false
        }
    }

    /** Parks the caller until the attempt completes or [timeout] elapses. Returns true if completed. */
    fun awaitAttempt(timeout: Long, unit: TimeUnit): Boolean = attemptDone.await(timeout, unit)

    /** Registers a one-shot action to run on completion; runs it now if the attempt already completed. */
    fun setPending(action: Runnable) {
        pending.set(action)
        if (hasCompletedAttempt) flushPending()
    }

    /** Drops any registered pending action without running it. */
    fun clearPending() {
        pending.set(null)
    }

    /** Atomically claims and runs the pending action, guaranteeing it fires at most once. */
    private fun flushPending() {
        pending.getAndSet(null)?.run()
    }
}

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
    @Volatile
    private var neuralEngine: NeuralSwipeTypingEngine? = null
    @Volatile
    private var asyncPredictionHandler: AsyncPredictionHandler? = null

    // Engine instance retained ACROSS init attempts (published to [neuralEngine] only on
    // success). Keeping one instance is what makes NeuralSwipeTypingEngine's per-instance
    // retry backoff (lastInitAttemptMs/initRetryIntervalMs) actually engage: constructing a
    // fresh engine every attempt would reset that state and defeat the retry-storm guard, so
    // a persistent failure would otherwise run a full tokenizer/vocab/model-byte load on every
    // swipe. Only ever touched under `synchronized(this)` alongside isInitializingNeuralEngine.
    private var pendingEngine: NeuralSwipeTypingEngine? = null

    @Volatile
    private var isInitializingNeuralEngine = false // v1.32.529: Track initialization state

    // Set once shutdown() runs so a background init that completes afterwards cleans up the
    // engine it built (freshly-opened OrtSessions) instead of publishing it into a torn-down
    // coordinator — which would leak the ONNX native sessions with nothing left to close them.
    @Volatile
    private var isShutdown = false

    // Test-only override for the retained engine's retry-backoff interval, applied when the
    // engine is first constructed. null in production (engine uses its own default). Lets tests
    // drive backoff behavior deterministically without real-time delays.
    @Volatile
    private var neuralRetryIntervalOverrideMs: Long? = null

    // Test-only: run background init on the caller's thread (deterministic, no Handler.post /
    // Looper needed off-device). Never set in production. See setRunInitInlineForTest.
    @Volatile
    private var runInitInlineForTest = false

    // One-shot gate for background neural-engine init: lets callers park (no busy-wait) or
    // register a non-blocking continuation that fires when the attempt finishes.
    private val engineInitGate = EngineInitGate()
    // Lazy so the coordinator can be constructed off-device (unit tests) without a real Looper;
    // only materialized when a continuation is actually posted.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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

            // v1.1.93: Load secondary dictionary for bilingual touch typing
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
            val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"
            if (multiLangEnabled && secondaryLang != "none" && secondaryLang.isNotEmpty()) {
                Log.d(TAG, "Loading secondary dictionary for touch typing: $secondaryLang")
                loadSecondaryDictionary(secondaryLang)
            }

            // OPTIMIZATION: Start observing dictionary changes for automatic updates
            startObservingDictionaryChanges()
        }

        Log.d(TAG, "WordPredictor initialized with automatic update observation")
    }

    /**
     * Initializes neural engine for swipe typing.
     *
     * Retains a single [NeuralSwipeTypingEngine] instance across attempts (in [pendingEngine])
     * and re-drives its [NeuralSwipeTypingEngine.initialize], which owns the retry backoff. On
     * a persistent failure the re-attempt short-circuits inside the engine (cheap, no model
     * load) until the backoff window elapses — so this method stays cheap even when called
     * synchronously per swipe via [ensureInitialized].
     *
     * OPTIMIZATION v1.32.529: Removed synchronized as it's now protected by double-checked
     * locking in [runWhenNeuralEngineReady]/[initialize].
     */
    private fun initializeNeuralEngine() {
        // Atomically claim the single in-flight init slot: skip if already published or another
        // attempt is running. Doing the check-and-set under the monitor closes the double-spawn
        // window (two callers both seeing isInitializingNeuralEngine == false).
        synchronized(this) {
            if (neuralEngine != null || isInitializingNeuralEngine) {
                return
            }
            isInitializingNeuralEngine = true
        }

        try {
            // Reuse the retained engine so its per-instance retry backoff persists across
            // attempts; construct it only the first time.
            val engine = pendingEngine ?: NeuralSwipeTypingEngine(context, config).also {
                neuralRetryIntervalOverrideMs?.let { ms -> it.initRetryIntervalMs = ms }
                pendingEngine = it
            }

            // Set debug logger before initialization so logs appear during model loading.
            // Idempotent, safe to re-apply on retries.
            debugLogger?.let {
                engine.setDebugLogger(it)
                Log.d(TAG, "Debug logger set on neural engine")
            }

            // CRITICAL: Call initialize() to actually load the ONNX models. On a prior failure
            // still inside the backoff window this returns false WITHOUT re-loading the model.
            val success = engine.initialize()
            if (!success) {
                Log.e(TAG, "Neural engine initialization returned false (will retry after backoff)")
                // Keep pendingEngine so the backoff state survives; do NOT publish.
                return
            }

            // Publish-or-cleanup decision must be atomic w.r.t. shutdown(): both read/write
            // isShutdown + neuralEngine under `synchronized(this)` so a shutdown that runs
            // concurrently can't slip between the check and the publish (which would leak the
            // engine's freshly-opened OrtSessions with nothing left to close them).
            val handler: AsyncPredictionHandler? = synchronized(this) {
                if (isShutdown) {
                    null // fall through to cleanup below
                } else {
                    neuralEngine = engine
                    pendingEngine = null
                    // Construct the handler under the lock so shutdown() observes it and can
                    // shut it down (avoids a leaked handler thread).
                    AsyncPredictionHandler(engine, context).also { asyncPredictionHandler = it }
                }
            }

            if (handler == null) {
                // Torn down while this (possibly background) attempt ran — clean up instead of
                // publishing so the ONNX sessions are released.
                Log.d(TAG, "Neural engine ready after shutdown — cleaning up instead of publishing")
                try {
                    engine.cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up post-shutdown neural engine", e)
                }
                return
            }

            Log.d(TAG, "NeuralSwipeTypingEngine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize neural engine", e)
            // Retain pendingEngine (backoff state) but never publish a partially-built engine.
            asyncPredictionHandler = null
        } finally {
            isInitializingNeuralEngine = false
            // Signal that the single init attempt is done (success or failure) so parked waiters
            // wake and any registered non-blocking continuation fires exactly once.
            engineInitGate.markAttemptComplete()
        }
    }

    /**
     * Runs [action] once the neural engine is (or is confirmed not) ready — never parks the
     * caller's thread, so it is safe to call from the UI thread on swipe end.
     *
     * Invokes [action] with true if the neural engine is ready, false otherwise. If the engine
     * is already available (or swipe typing is disabled) the action runs synchronously; otherwise
     * a background init is kicked off (if eligible) and [action] is posted to the main thread once
     * the attempt completes.
     *
     * On a PERSISTENT init failure the model load stays off the main thread: after the retry
     * backoff (owned by [NeuralSwipeTypingEngine]) elapses, the gate is re-armed and a fresh
     * background attempt is kicked — the caller never triggers a synchronous full model load.
     *
     * @param action callback receiving whether the neural engine is ready
     */
    fun runWhenNeuralEngineReady(action: (Boolean) -> Unit) {
        // Swipe typing off: nothing to initialize.
        if (!config.swipe_typing_enabled) {
            action(false)
            return
        }

        // Fast path: already initialized.
        if (neuralEngine != null) {
            action(true)
            return
        }

        // Kick off (or re-kick) a single background init if eligible. Guard so exactly one
        // attempt cycle is in flight, and only re-arm/retry once the engine's backoff allows.
        synchronized(this) {
            if (neuralEngine == null && !isInitializingNeuralEngine && !isShutdown) {
                val firstAttempt = !engineInitGate.hasCompletedAttempt
                // Re-attempt only if the retained engine's backoff window has elapsed (or no
                // attempt has run yet). isReadyToRetryInit() is side-effect free.
                val backoffElapsed = pendingEngine?.isReadyToRetryInit() ?: true
                if (firstAttempt || backoffElapsed) {
                    // Re-arm a completed gate so this new attempt's waiters/continuation see a
                    // fresh cycle; no-op on the very first attempt (gate not yet completed).
                    engineInitGate.rearmIfCompleted()
                    // Register the continuation to fire (on the main thread) when THIS attempt
                    // completes. Registered under the lock, before the attempt can complete.
                    // Tests run inline and dispatch the continuation directly (no Handler/Looper).
                    engineInitGate.setPending {
                        if (runInitInlineForTest) {
                            action(neuralEngine != null)
                        } else {
                            mainHandler.post { action(neuralEngine != null) }
                        }
                    }
                    if (runInitInlineForTest) {
                        // Deterministic path for unit tests: run on the caller's thread.
                        initializeNeuralEngine()
                    } else {
                        thread(name = "NeuralEngineInit") {
                            initializeNeuralEngine()
                        }
                    }
                    return
                }
            }
        }

        // Not eligible to (re)attempt right now — either an attempt is already running (park the
        // continuation on it) or we are inside the backoff window (report not-ready immediately
        // rather than spin up a doomed attempt).
        if (isInitializingNeuralEngine) {
            engineInitGate.setPending {
                mainHandler.post { action(neuralEngine != null) }
            }
        } else {
            action(false)
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

        // Neural engine: only (re)attempt when eligible. On a persistent failure the retained
        // engine's backoff makes initializeNeuralEngine() a cheap no-op inside the window; the
        // explicit isReadyToRetryInit() guard avoids even that roundtrip and, crucially, keeps a
        // full model-load from being re-driven synchronously on the main thread per swipe.
        if (config.swipe_typing_enabled && neuralEngine == null && !isShutdown) {
            val eligible = pendingEngine?.isReadyToRetryInit() ?: true
            if (eligible) {
                initializeNeuralEngine()
            }
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

        // Reload in touch typing predictor
        wordPredictor?.reloadCustomAndUserWords()

        // Reload in swipe typing neural engine
        neuralEngine?.reloadCustomWords()
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
     * Checkpoint all learned data (context LM bigrams + personalization
     * vocabulary) held by the primary predictor and every per-language predictor
     * in [DictionaryManager]. Asynchronous debounced-store flush — cheap no-op
     * when nothing is dirty. Called from CleverKeysService.onFinishInputView and
     * from [shutdown].
     */
    fun flushLearnedData() {
        try {
            wordPredictor?.persistLearnedData()
            dictionaryManager?.flushLearnedData()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing learned data", e)
        }
    }

    /**
     * Shuts down all prediction engines and cleans up resources.
     * Should be called during keyboard shutdown.
     */
    fun shutdown() {
        // Mark shutdown FIRST (before the lock) so an in-flight background init observes it on
        // its fast @Volatile check and stops early. The publish-vs-cleanup decision inside
        // initializeNeuralEngine and the field teardown here BOTH run under `synchronized(this)`,
        // so a racing init can't slip between checking isShutdown and publishing its engine —
        // whichever wins the monitor either publishes-then-gets-cleaned-here, or sees isShutdown
        // and cleans up its own engine. Either way no OrtSession leaks.
        isShutdown = true

        // Snapshot + clear the engine references under the lock so the init thread and this
        // teardown agree on exactly one owner of each engine.
        val (engineToClose, pendingToClose, handlerToShut) = synchronized(this) {
            val e = neuralEngine
            val p = pendingEngine
            val h = asyncPredictionHandler
            neuralEngine = null
            pendingEngine = null
            asyncPredictionHandler = null
            Triple(e, p, h)
        }

        // Shutdown async prediction handler
        handlerToShut?.shutdown()

        // Checkpoint learned data (context LM bigrams + user vocabulary) BEFORE the
        // predictor/dictionary teardown below discards the live instances
        // (2026-08-06 persistence fix)
        flushLearnedData()

        // Stop observing dictionary changes
        wordPredictor?.stopObservingDictionaryChanges()

        // Clean up ONNX native resources (OrtSessions) explicitly — GC alone is unreliable.
        // Cover both the published engine and any retained-but-unpublished one (a failed/racing
        // init may have left an engine in pendingEngine with open sessions).
        try {
            engineToClose?.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up neural engine", e)
        }
        try {
            pendingToClose?.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up pending neural engine", e)
        }

        // Clean up all predictor instances held by DictionaryManager
        dictionaryManager?.cleanup()

        wordPredictor = null
        dictionaryManager = null

        // Drop any pending non-blocking continuation so it never fires post-shutdown.
        engineInitGate.clearPending()

        Log.d(TAG, "PredictionCoordinator shutdown complete")
    }

    /**
     * TEST-ONLY: override the retained neural engine's retry-backoff interval, applied when the
     * engine is first constructed. Must be called before the first init attempt. Not used in
     * production (the engine keeps its own default).
     */
    internal fun setNeuralRetryIntervalForTest(intervalMs: Long) {
        neuralRetryIntervalOverrideMs = intervalMs
    }

    /**
     * TEST-ONLY: run background neural init inline on the caller's thread (avoids Handler.post /
     * a real Looper off-device and makes the [runWhenNeuralEngineReady] flow deterministic).
     * Not used in production.
     */
    internal fun setRunInitInlineForTest(enabled: Boolean) {
        runInitInlineForTest = enabled
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
