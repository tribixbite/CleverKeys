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
    
    // Configuration and state
    private var config: Config? = null
    private var currentLayout: KeyboardData? = null
    
    override fun onCreate() {
        super.onCreate()
        logD("CleverKeys service starting...")
        
        initializeConfiguration()
        initializeKeyEventHandler()
        initializeNeuralComponents()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        logD("CleverKeys service stopping...")
        
        // Clean shutdown of all components
        serviceScope.cancel()
        predictionService?.shutdown()
        neuralEngine?.cleanup()
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
        
        logD("Configuration initialized")
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
     * Create keyboard view with modern Kotlin patterns
     */
    override fun onCreateInputView(): View? {
        val currentConfig = config ?: return null
        
        keyboardView = CleverKeysView(this, currentConfig).apply {
            onSwipeCompleted = { swipeData -> handleSwipeGesture(swipeData) }
            onKeyPressed = { key -> handleKeyPress(key) }
        }
        
        return keyboardView
    }
    
    /**
     * Handle swipe gesture completion
     */
    private fun handleSwipeGesture(swipeData: SwipeGestureData) {
        val predictionService = this.predictionService ?: return
        val suggestionBar = this.suggestionBar ?: return
        
        logD("🎯 Gesture completion: ${swipeData.path.size} points")
        
        // Create SwipeInput exactly like calibration (unified approach)
        val swipeInput = SwipeInput(
            coordinates = swipeData.path,
            timestamps = swipeData.timestamps,
            touchedKeys = emptyList() // Empty like calibration for consistency
        )
        
        // Request prediction with coroutines
        serviceScope.launch {
            try {
                val result = predictionService.requestPrediction(swipeInput).await()
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    suggestionBar.setSuggestions(result.words.take(5))
                    logD("Displayed ${result.size} predictions")
                }
                
            } catch (e: CancellationException) {
                logD("Prediction cancelled by new gesture")
            } catch (e: Exception) {
                logE("Prediction failed", e)
                withContext(Dispatchers.Main) {
                    suggestionBar.clearSuggestions()
                }
            }
        }
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
     * Configuration change handling
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "neural_beam_width", "neural_max_length", "neural_confidence_threshold" -> {
                // Update neural configuration
                neuralEngine?.setConfig(config ?: return)
                logD("Neural configuration updated for key: $key")
            }
            "swipe_typing_enabled" -> {
                // Reinitialize neural components if needed
                if (config?.swipe_typing_enabled == true && neuralEngine == null) {
                    initializeNeuralComponents()
                }
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