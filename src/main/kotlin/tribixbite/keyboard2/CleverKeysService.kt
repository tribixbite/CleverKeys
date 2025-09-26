package tribixbite.keyboard2

import android.content.SharedPreferences
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// CleverKeys specific classes - all in same package

// Logging convenience functions
private fun logD(tag: String, message: String) = Log.d(tag, message)
private fun logE(tag: String, message: String, throwable: Throwable? = null) = Log.e(tag, message, throwable)
private fun logW(tag: String, message: String) = Log.w(tag, message)

// Extension for this service
private fun logD(message: String) = logD("CleverKeysService", message)
private fun logE(message: String, throwable: Throwable? = null) = logE("CleverKeysService", message, throwable)
private fun logW(message: String) = logW("CleverKeysService", message)

/**
 * Modern Kotlin InputMethodService for CleverKeys
 * Replaces Keyboard2.java with coroutines, null safety, and clean architecture
 */
class CleverKeysService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {
    
    companion object {
        private const val TAG = "CleverKeysService"
    }
    
    // Service scope for coroutine management
    private val serviceScope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Main.immediate + 
        CoroutineName("CleverKeysService")
    )
    
    // Core components with null safety
    private var keyboardView: CleverKeysView? = null
    private var neuralEngine: NeuralSwipeEngine? = null
    private var predictionService: SwipePredictionService? = null
    private var suggestionBar: SuggestionBar? = null
    private var neuralConfig: NeuralConfig? = null
    private var keyEventHandler: KeyEventHandler? = null
    private var predictionPipeline: NeuralPredictionPipeline? = null
    private var performanceProfiler: PerformanceProfiler? = null
    private var configManager: ConfigurationManager? = null
    
    // Configuration and state
    private var config: Config? = null
    private var currentLayout: KeyboardData? = null
    
    override fun onCreate() {
        super.onCreate()
        logD("CleverKeys InputMethodService starting...")

        try {
            // Initialize components in dependency order
            initializeConfiguration()
            loadDefaultKeyboardLayout()
            initializeKeyEventHandler()
            initializePerformanceProfiler()
            initializeNeuralComponents()
            initializePredictionPipeline()

            logD("✅ CleverKeys service initialization completed successfully")
        } catch (e: Exception) {
            logE("Critical service initialization failure", e)
            throw RuntimeException("CleverKeys service failed to initialize", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        logD("CleverKeys service stopping...")
        
        // Clean shutdown of all components
        serviceScope.cancel()
        predictionService?.shutdown()
        neuralEngine?.cleanup()
        predictionPipeline?.cleanup()
        performanceProfiler?.cleanup()
        configManager?.cleanup()
    }
    
    /**
     * Initialize configuration with property delegation
     */
    private fun initializeConfiguration() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        
        Config.initGlobalConfig(prefs, resources, null, false)
        config = Config.globalConfig()
        neuralConfig = NeuralConfig(prefs)

        // Load saved settings from SharedPreferences
        loadSavedSettings(prefs)
        
        // Initialize configuration manager
        configManager = ConfigurationManager(this).also { manager ->
            serviceScope.launch {
                manager.initialize()
                
                // Monitor configuration changes
                manager.getConfigChangesFlow().collect { change ->
                    handleConfigurationChange(change)
                }
            }
        }
        
        logD("Configuration initialized")
    }

    /**
     * Load saved settings from SharedPreferences with validation and error handling
     */
    private fun loadSavedSettings(prefs: android.content.SharedPreferences) {
        config?.let { cfg ->
            try {
                // Load theme with validation
                val themeValue = prefs.getInt("theme", R.style.Dark)
                cfg.theme = when (themeValue) {
                    R.style.Light, R.style.Dark, R.style.Black -> themeValue
                    else -> {
                        logW("Invalid theme value $themeValue, using default Dark theme")
                        R.style.Dark
                    }
                }

                // Load keyboard height with validation
                val heightValue = prefs.getInt("keyboardHeightPercent", 35)
                cfg.keyboardHeightPercent = heightValue.coerceIn(20, 60).also { validHeight ->
                    if (validHeight != heightValue) {
                        logW("Keyboard height $heightValue out of range, clamped to $validHeight")
                    }
                }

                // Load vibration setting
                cfg.vibrate_custom = prefs.getBoolean("vibrate_custom", false)

                // Load debug setting
                val debugEnabled = prefs.getBoolean("debug_enabled", false)
                Logs.setDebugEnabled(debugEnabled)

                // Validate neural configuration
                neuralConfig?.validate()

                logD("Settings loaded: theme=${cfg.theme}, height=${cfg.keyboardHeightPercent}, vibration=${cfg.vibrate_custom}, debug=$debugEnabled")

                // Test settings persistence for debugging
                if (debugEnabled) {
                    testSettingsPersistence(prefs)
                }

            } catch (e: Exception) {
                logE("Error loading settings, using defaults", e)
                // Reset to safe defaults
                cfg.theme = R.style.Dark
                cfg.keyboardHeightPercent = 35
                cfg.vibrate_custom = false
                Logs.setDebugEnabled(false)
            }
        }
    }

    /**
     * Initialize performance profiler
     */
    private fun initializePerformanceProfiler() {
        performanceProfiler = PerformanceProfiler(this)
        logD("Performance profiler initialized")
    }
    
    /**
     * Initialize key event handler
     */
    private fun initializeKeyEventHandler() {
        keyEventHandler = KeyEventHandler(object : KeyEventHandler.IReceiver {
            override fun getInputConnection(): InputConnection? = currentInputConnection
            override fun getCurrentInputEditorInfo(): EditorInfo? = currentInputEditorInfo
            override fun performVibration() {
                if (config?.vibrate_custom == true) {
                    keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            override fun commitText(text: String) {
                currentInputConnection?.commitText(text, 1)
            }
            override fun performAction(action: Int) {
                currentInputConnection?.performEditorAction(action)
            }
            override fun switchToMainLayout() {
                // TODO: Implement layout switching
                logD("Switching to main layout")
            }
            override fun switchToNumericLayout() {
                // TODO: Implement numeric layout switching
                logD("Switching to numeric layout")
            }
            override fun switchToEmojiLayout() {
                // TODO: Implement emoji layout switching
                logD("Switching to emoji layout")
            }
            override fun openSettings() {
                // TODO: Implement settings opening
                logD("Opening settings")
            }
        })
    }
    
    /**
     * Initialize neural prediction components
     */
    private fun initializeNeuralComponents() {
        val currentConfig = config ?: return
        
        if (currentConfig.swipe_typing_enabled) {
            serviceScope.launch {
                try {
                    // Initialize neural engine with both general config and neural-specific config
                    neuralEngine = NeuralSwipeEngine(this@CleverKeysService, currentConfig).apply {
                        if (!initialize()) {
                            throw RuntimeException("Failed to initialize neural engine")
                        }
                        setDebugLogger { message -> logD("Neural: $message") }

                        // Apply neural-specific configuration
                        neuralConfig?.let { nc ->
                            setNeuralConfig(nc)
                        }

                        // Register with configuration manager for automatic updates
                        configManager?.registerNeuralEngine(this)
                    }
                    
                    // Initialize prediction service
                    predictionService = SwipePredictionService(neuralEngine!!)
                    
                    logD("Neural components initialized successfully")
                    
                } catch (e: Exception) {
                    logE("Failed to initialize neural components", e)
                    // Continue without neural prediction
                }
            }
        }
    }
    
    /**
     * Initialize complete prediction pipeline
     */
    private fun initializePredictionPipeline() {
        predictionPipeline = NeuralPredictionPipeline(this).also { pipeline ->
            serviceScope.launch {
                try {
                    val success = pipeline.initialize()
                    if (success) {
                        logD("Neural prediction pipeline initialized successfully")
                    } else {
                        logE("Neural prediction pipeline initialization failed")
                    }
                } catch (e: Exception) {
                    logE("Failed to initialize prediction pipeline", e)
                }
            }
        }
    }

    /**
     * Load default QWERTY keyboard layout
     */
    private fun loadDefaultKeyboardLayout() {
        try {
            // Create a simple QWERTY layout for basic functionality
            currentLayout = createBasicQwertyLayout()
            logD("✅ Default QWERTY layout loaded")
        } catch (e: Exception) {
            logE("Failed to load default keyboard layout", e)
        }
    }

    /**
     * Create basic QWERTY layout for testing
     */
    private fun createBasicQwertyLayout(): KeyboardData {
        val charRows = listOf(
            // Top row
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            // Middle row
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            // Bottom row
            listOf("z", "x", "c", "v", "b", "n", "m")
        )

        val rows = mutableListOf<KeyboardData.Row>()

        // Add character rows
        charRows.forEach { row ->
            val keys = row.map { char ->
                val keyValue = KeyValue.makeCharKey(char[0])
                val keysArray = Array<KeyValue?>(9) { null }
                keysArray[0] = keyValue  // Center position (0 = center key value)
                KeyboardData.Key(
                    keys = keysArray,
                    width = 1.0f,
                    shift = 0.0f
                )
            }
            rows.add(KeyboardData.Row(
                keys = keys,
                height = 1.0f,
                shift = 0.0f
            ))
        }

        // Add bottom row with space and backspace
        val specialKeys = listOf(
            KeyValue.BACKSPACE,
            KeyValue.SPACE,
            KeyValue.ENTER
        ).map { keyValue ->
            val keysArray = Array<KeyValue?>(9) { null }
            keysArray[0] = keyValue
            KeyboardData.Key(
                keys = keysArray,
                width = 1.0f,
                shift = 0.0f
            )
        }
        rows.add(KeyboardData.Row(
            keys = specialKeys,
            height = 1.0f,
            shift = 0.0f
        ))

        return KeyboardData(
            rows = rows,
            keysWidth = 10.0f,  // 10 keys max width
            keysHeight = 1.0f,  // Default key height
            name = "Basic QWERTY"
        )
    }

    /**
     * Android requires this method to create the keyboard view
     */
    override fun onCreateInputView(): View? {
        logD("onCreateInputView() called - creating keyboard view")

        val currentConfig = config ?: run {
            logE("Configuration not available for input view creation")
            return null
        }

        return try {
            logD("Creating CleverKeysView...")

            val view = CleverKeysView(this, currentConfig).apply {
                onSwipeCompleted = { swipeData -> handleSwipeGesture(swipeData) }
                onKeyPressed = { key -> handleKeyPress(key) }

                // Register with configuration manager for automatic updates
                configManager?.registerKeyboardView(this)

                // Set keyboard layout if available
                currentLayout?.let { layout -> setLayout(layout) }

                // Apply keyboard height setting
                applyKeyboardHeight(this, currentConfig.keyboardHeightPercent)
            }

            keyboardView = view
            logD("✅ Keyboard view created successfully")
            view

        } catch (e: Exception) {
            logE("Failed to create keyboard input view", e)
            null
        }
    }

    /**
     * Apply keyboard height setting to the view
     */
    private fun applyKeyboardHeight(view: android.view.View, heightPercent: Int) {
        try {
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val desiredHeight = (screenHeight * heightPercent / 100f).toInt()

            view.layoutParams = view.layoutParams?.apply {
                height = desiredHeight
            } ?: android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                desiredHeight
            )

            logD("Applied keyboard height: ${heightPercent}% = ${desiredHeight}px")
        } catch (e: Exception) {
            logE("Failed to apply keyboard height", e)
        }
    }

    /**
     * Handle input starting
     */
    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        logD("Input started: package=${editorInfo?.packageName}, restarting=$restarting")
    }


    /**
     * Handle input finishing with cleanup
     */
    override fun onFinishInput() {
        super.onFinishInput()
        logD("Input finished - cleaning up resources")

        // Cancel any pending predictions
        predictionService?.cancelAll()
    }
    
    /**
     * Handle swipe gesture completion with complete pipeline integration
     */
    private fun handleSwipeGesture(swipeData: SwipeGestureData) {
        val pipeline = this.predictionPipeline ?: return
        val profiler = this.performanceProfiler ?: return
        
        logD("🎯 Gesture completion: ${swipeData.path.size} points")
        
        // Process through complete neural prediction pipeline
        serviceScope.launch {
            try {
                val pipelineResult = profiler.measureOperation("complete_gesture_processing") {
                    pipeline.processGesture(
                        points = swipeData.path,
                        timestamps = swipeData.timestamps,
                        context = getCurrentTextContext()
                    )
                }
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    updateSuggestionsFromPipeline(pipelineResult)
                    logPipelineResult(pipelineResult)
                }
                
            } catch (e: CancellationException) {
                logD("Gesture processing cancelled by new gesture")
            } catch (e: Exception) {
                logE("Pipeline processing failed", e)
                handlePredictionError(e)
            }
        }
    }
    
    /**
     * Update suggestions from pipeline result
     */
    private fun updateSuggestionsFromPipeline(result: NeuralPredictionPipeline.PipelineResult) {
        keyboardView?.updateSuggestions(result.predictions.words.take(5))

        logD("🧠 ${result.source} neural prediction: ${result.predictions.size} words in ${result.processingTimeMs}ms")
        logD("   Top predictions: ${result.predictions.words.take(3)}")
    }
    
    /**
     * Log detailed pipeline result
     */
    private fun logPipelineResult(result: NeuralPredictionPipeline.PipelineResult) {
        val details = buildString {
            appendLine("📊 ONNX Neural Pipeline Result:")
            appendLine("   Source: ${result.source}")
            appendLine("   Processing Time: ${result.processingTimeMs}ms")
            appendLine("   Predictions: ${result.predictions.words.take(5)}")
            appendLine("   Scores: ${result.predictions.scores.take(5)}")
        }
        logD(details)
    }
    
    /**
     * Get current text context for context-aware predictions
     */
    private fun getCurrentTextContext(): List<String> {
        return try {
            val inputConnection = currentInputConnection ?: return emptyList()
            val textBefore = inputConnection.getTextBeforeCursor(100, 0)?.toString() ?: ""
            
            // Extract last few words for context
            textBefore.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .takeLast(3)
        } catch (e: Exception) {
            logE("Failed to get text context", e)
            emptyList()
        }
    }
    
    /**
     * Handle prediction errors with user feedback
     */
    private fun handlePredictionError(error: Throwable) {
        serviceScope.launch(Dispatchers.Main) {
            when (error) {
                is ErrorHandling.CleverKeysException.NeuralEngineException -> {
                    // Neural prediction failed - no fallbacks, throw error
                    throw error
                }
                is ErrorHandling.CleverKeysException.GestureRecognitionException -> {
                    // Gesture recognition failed - no fallbacks
                    throw error
                }
                else -> {
                    // Any other error - no fallbacks
                    throw ErrorHandling.CleverKeysException.NeuralEngineException("Prediction failed", error)
                }
            }
        }
    }
    
    
    /**
     * Show error toast to user
     */
    private fun showErrorToast(message: String) {
        // TODO: Implement user-visible error feedback
        logE("User error: $message")
    }
    
    /**
     * Handle regular key press
     */
    private fun handleKeyPress(key: KeyValue) {
        // Handle regular typing, special keys, etc.
        when (key) {
            is KeyValue.CharKey -> {
                // Send character to input connection
                currentInputConnection?.commitText(key.char.toString(), 1)
            }
            is KeyValue.EventKey -> {
                // Handle special keys (backspace, enter, etc.)
                handleSpecialKey(key.event)
            }
            is KeyValue.KeyEventKey -> {
                // Handle Android key events
                handleAndroidKeyEvent(key.keyCode)
            }
            is KeyValue.StringKey -> {
                // Handle multi-character strings
                currentInputConnection?.commitText(key.string, 1)
            }
            else -> {
                logD("Unhandled key type: ${key::class.simpleName}")
            }
        }
    }
    
    /**
     * Handle special key events
     */
    private fun handleSpecialKey(event: KeyValue.Event) {
        when (event) {
            KeyValue.Event.CONFIG -> {
                // Open configuration/settings
                logD("Opening configuration")
            }
            KeyValue.Event.SWITCH_TEXT -> {
                // Switch to text input mode
                logD("Switching to text mode")
            }
            KeyValue.Event.SWITCH_EMOJI -> {
                // Switch to emoji input mode
                logD("Switching to emoji mode")
            }
            // Add other special event handling
            else -> {
                logD("Unhandled special event: $event")
            }
        }
    }

    /**
     * Handle Android key events
     */
    private fun handleAndroidKeyEvent(keyCode: Int) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            android.view.KeyEvent.KEYCODE_ENTER -> {
                currentInputConnection?.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            // Add other Android key handling
            else -> {
                logD("Unhandled Android key event: $keyCode")
            }
        }
    }
    
    /**
     * Configuration change handling with reactive propagation
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        serviceScope.launch {
            handleConfigurationChange(ConfigurationManager.ConfigChange(
                key = key ?: "",
                oldValue = null,
                newValue = sharedPreferences?.all?.get(key),
                source = "system"
            ))
        }
    }
    
    /**
     * Handle configuration changes with component updates
     */
    private suspend fun handleConfigurationChange(change: ConfigurationManager.ConfigChange) {
        when (change.key) {
            "neural_beam_width", "neural_max_length", "neural_confidence_threshold" -> {
                // Update neural configuration with both general and neural-specific settings
                neuralEngine?.let { engine ->
                    config?.let { cfg -> engine.setConfig(cfg) }
                    neuralConfig?.let { nc -> engine.setNeuralConfig(nc) }
                }
                predictionPipeline?.let { pipeline ->
                    // Reinitialize pipeline with new settings
                    pipeline.cleanup()
                    pipeline.initialize()
                }
                logD("Neural configuration updated for key: ${change.key}")
            }
            
            "swipe_typing_enabled" -> {
                // Reinitialize neural components if needed
                if (config?.swipe_typing_enabled == true && neuralEngine == null) {
                    initializeNeuralComponents()
                    initializePredictionPipeline()
                }
            }
            
            "theme" -> {
                // Update theme across all components
                withContext(Dispatchers.Main) {
                    keyboardView?.updateTheme()
                    updateUITheme()
                }
            }
            
            "keyboard_height", "keyboard_height_landscape" -> {
                // Update keyboard dimensions
                withContext(Dispatchers.Main) {
                    keyboardView?.requestLayout()
                }
            }
            
            "performance_monitoring" -> {
                // Toggle performance monitoring
                val enabled = change.newValue as? Boolean ?: false
                if (enabled) {
                    startPerformanceMonitoring()
                } else {
                    stopPerformanceMonitoring()
                }
            }
        }
    }
    
    /**
     * Update UI theme
     */
    private fun updateUITheme() {
        // TODO: Propagate theme changes to active UI components
        logD("UI theme updated")
    }
    
    /**
     * Start performance monitoring
     */
    private fun startPerformanceMonitoring() {
        performanceProfiler?.startMonitoring { metric ->
            if (metric.durationMs > 1000) { // Log slow operations
                logW("Slow operation detected: ${metric.operation} took ${metric.durationMs}ms")
            }
        }
    }
    
    /**
     * Stop performance monitoring
     */
    private fun stopPerformanceMonitoring() {
        // TODO: Stop performance monitoring
        logD("Performance monitoring stopped")
    }

    /**
     * Test settings persistence for debugging
     */
    private fun testSettingsPersistence(prefs: android.content.SharedPreferences) {
        logD("🧪 Testing settings persistence...")

        // Verify all expected settings exist
        val expectedSettings = mapOf(
            "theme" to R.style.Dark,
            "keyboardHeightPercent" to 35,
            "vibrate_custom" to false,
            "debug_enabled" to false,
            "neural_prediction_enabled" to true,
            "neural_beam_width" to 8,
            "neural_max_length" to 35,
            "neural_confidence_threshold" to 0.1f
        )

        var allSettingsPresent = true
        var settingsMatchExpected = true

        expectedSettings.forEach { (key, defaultValue) ->
            if (!prefs.contains(key)) {
                logW("⚠️ Setting '$key' not found in preferences")
                allSettingsPresent = false
            } else {
                val actualValue = when (defaultValue) {
                    is Boolean -> prefs.getBoolean(key, false)
                    is Int -> prefs.getInt(key, 0)
                    is Float -> prefs.getFloat(key, 0.0f)
                    is String -> prefs.getString(key, "")
                    else -> null
                }

                if (actualValue != prefs.all[key]) {
                    logW("⚠️ Setting '$key' value mismatch: expected type but got ${prefs.all[key]}")
                    settingsMatchExpected = false
                }
            }
        }

        // Test neural configuration specifically
        neuralConfig?.let { nc ->
            val neuralSettingsValid = nc.beamWidth in nc.beamWidthRange &&
                    nc.maxLength in nc.maxLengthRange &&
                    nc.confidenceThreshold in nc.confidenceRange

            if (!neuralSettingsValid) {
                logW("⚠️ Neural configuration validation failed")
                settingsMatchExpected = false
            }
        }

        when {
            allSettingsPresent && settingsMatchExpected -> {
                logD("✅ Settings persistence test PASSED - all settings loaded correctly")
            }
            allSettingsPresent && !settingsMatchExpected -> {
                logW("⚠️ Settings persistence test PARTIAL - settings exist but have validation issues")
            }
            else -> {
                logE("❌ Settings persistence test FAILED - missing settings detected")
            }
        }
    }

    /**
     * Swipe gesture data container
     */
    data class SwipeGestureData(
        val path: List<PointF>,
        val timestamps: List<Long>,
        val detectedKeys: List<String> = emptyList()
    )
}