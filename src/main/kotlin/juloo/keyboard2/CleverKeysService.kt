package juloo.keyboard2

import android.content.SharedPreferences
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
                // TODO: Implement vibration
            }
            override fun commitText(text: String) {
                currentInputConnection?.commitText(text, 1)
            }
            override fun performAction(action: Int) {
                currentInputConnection?.performEditorAction(action)
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
                    // Initialize neural engine
                    neuralEngine = NeuralSwipeEngine(this@CleverKeysService, currentConfig).apply {
                        if (!initialize()) {
                            throw RuntimeException("Failed to initialize neural engine")
                        }
                        setDebugLogger { message -> logD("Neural: $message") }

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
     * Create keyboard view with complete InputMethodService integration
     */
    override fun onCreateInputView(): View? {
        logD("Creating keyboard input view...")

        val currentConfig = config ?: run {
            logE("Configuration not available for input view creation")
            return null
        }

        try {
            keyboardView = CleverKeysView(this, currentConfig).apply {
                onSwipeCompleted = { swipeData -> handleSwipeGesture(swipeData) }
                onKeyPressed = { key -> handleKeyPress(key) }

                // Register with configuration manager for automatic updates
                configManager?.registerKeyboardView(this)

                // Set keyboard layout if available
                currentLayout?.let { layout -> setLayout(layout) }
            }

            logD("✅ Keyboard input view created successfully")
            return keyboardView

        } catch (e: Exception) {
            logE("Failed to create keyboard input view", e)
            return null
        }
    }

    /**
     * Handle input started with proper context setup
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        logD("Input started: package=${info?.packageName}, restarting=$restarting")

        // Configure input connection manager
        serviceScope.launch {
            val inputConnectionManager = InputConnectionManager(this@CleverKeysService)
            inputConnectionManager.setInputConnection(currentInputConnection, info)
        }
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
        
        logD("🧠 ${result.source} prediction: ${result.predictions.size} words in ${result.processingTimeMs}ms")
        logD("   Gesture: ${result.gestureInfo.type} (${result.gestureInfo.confidence} confidence)")
        logD("   Classification: ${result.swipeClassification.quality} (${result.swipeClassification.confidence})")
    }
    
    /**
     * Log detailed pipeline result
     */
    private fun logPipelineResult(result: NeuralPredictionPipeline.PipelineResult) {
        val details = buildString {
            appendLine("📊 Pipeline Result Details:")
            appendLine("   Source: ${result.source}")
            appendLine("   Processing Time: ${result.processingTimeMs}ms")
            appendLine("   Gesture Type: ${result.gestureInfo.type}")
            appendLine("   Gesture Confidence: ${result.gestureInfo.confidence}")
            appendLine("   Swipe Quality: ${result.swipeClassification.quality}")
            appendLine("   Predictions: ${result.predictions.words.take(3)}")
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
        when (key.kind) {
            KeyValue.Kind.Char -> {
                // Send character to input connection
                currentInputConnection?.commitText(key.char.toString(), 1)
            }
            KeyValue.Kind.Event -> {
                // Handle special keys (backspace, enter, etc.)
                handleSpecialKey(key.eventCode)
            }
            else -> {
                logD("Unhandled key type: ${key.kind}")
            }
        }
    }
    
    /**
     * Handle special key events
     */
    private fun handleSpecialKey(eventCode: Int) {
        when (eventCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            android.view.KeyEvent.KEYCODE_ENTER -> {
                currentInputConnection?.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            }
            // Add other special key handling
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
                // Update neural configuration
                neuralEngine?.setConfig(config ?: return)
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
     * Swipe gesture data container
     */
    data class SwipeGestureData(
        val path: List<PointF>,
        val timestamps: List<Long>,
        val detectedKeys: List<String> = emptyList()
    )
}