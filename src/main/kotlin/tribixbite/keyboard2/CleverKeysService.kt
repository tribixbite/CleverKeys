package tribixbite.keyboard2

import android.content.SharedPreferences
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tribixbite.keyboard2.ui.SuggestionBarM3Wrapper

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
class CleverKeysService : InputMethodService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    ClipboardPasteCallback {
    
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
    private var keyboardView: Keyboard2View? = null
    private var neuralEngine: NeuralSwipeEngine? = null
    private var predictionService: SwipePredictionService? = null
    private var suggestionBar: SuggestionBarM3Wrapper? = null
    private var inputViewContainer: android.widget.LinearLayout? = null // Fix #52
    private var neuralConfig: NeuralConfig? = null
    private var keyEventHandler: KeyEventHandler? = null
    private var predictionPipeline: NeuralPredictionPipeline? = null
    private var performanceProfiler: PerformanceProfiler? = null
    private var configManager: ConfigurationManager? = null
    private var typingPredictionEngine: TypingPredictionEngine? = null
    private var voiceGuidanceEngine: VoiceGuidanceEngine? = null
    private var screenReaderManager: ScreenReaderManager? = null
    private var spellCheckerManager: SpellCheckerManager? = null
    private var spellCheckHelper: SpellCheckHelper? = null
    private var smartPunctuationHandler: SmartPunctuationHandler? = null
    private var caseConverter: CaseConverter? = null
    private var textExpander: TextExpander? = null
    private var cursorMovementManager: CursorMovementManager? = null
    private var multiTouchHandler: MultiTouchHandler? = null
    private var soundEffectManager: SoundEffectManager? = null
    private var animationManager: AnimationManager? = null
    private var keyPreviewManager: KeyPreviewManager? = null

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
            initializeComposeKeyData()
            initializeClipboardService()  // Bug #118 & #120 fix
            initializeAccessibilityEngines()
            initializeSpellChecker()
            initializeSmartPunctuation()  // Bug #316 & #361 fix
            initializeCaseConverter()      // Bug #318 fix
            initializeTextExpander()       // Bug #319 fix
            initializeCursorMovementManager()  // Bug #322 fix
            initializeMultiTouchHandler()  // Bug #323 fix
            initializeSoundEffectManager()  // Bug #324 fix
            initializeAnimationManager()    // Bug #325 fix
            initializeKeyPreviewManager()   // Bug #326 fix
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

        // Unregister preference listener to prevent memory leak
        try {
            DirectBootAwarePreferences.get_shared_preferences(this)
                .unregisterOnSharedPreferenceChangeListener(this)
        } catch (e: Exception) {
            logE("Failed to unregister preference listener", e)
        }

        // Clear view references to prevent memory leaks
        keyboardView = null
        suggestionBar = null

        // Clean shutdown of all components (suspend cleanup before cancelling scope)
        runBlocking {
            neuralEngine?.cleanup()
            // Other components with synchronous cleanup
            predictionService?.shutdown()
            predictionPipeline?.cleanup()
            performanceProfiler?.cleanup()
            configManager?.cleanup()
            typingPredictionEngine?.cleanup()
            voiceGuidanceEngine?.cleanup()
            spellCheckerManager?.cleanup()
            soundEffectManager?.release()  // Bug #324 - release audio resources
            animationManager?.release()    // Bug #325 - release animation resources
            keyPreviewManager?.release()   // Bug #326 - release preview resources
        }
        serviceScope.cancel()
    }
    
    /**
     * Initialize configuration with property delegation
     */
    private fun initializeConfiguration() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        // Create temporary placeholder handler for Config initialization (Fix #51)
        // The real handler will be set later in initializeKeyEventHandler()
        val placeholderHandler = KeyEventHandler(
            receiver = Receiver(),
            typingPredictionEngine = null,
            voiceGuidanceEngine = null,
            screenReaderManager = null,
            spellCheckHelper = null
        )

        Config.initGlobalConfig(prefs, resources, placeholderHandler, false)
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
     * Initialize accessibility engines (Fix for Bugs #359, #368)
     */
    private fun initializeAccessibilityEngines() {
        serviceScope.launch {
            try {
                // Initialize tap typing prediction engine (Bug #359 CATASTROPHIC)
                typingPredictionEngine = TypingPredictionEngine(this@CleverKeysService).apply {
                    val success = initialize()
                    if (success) {
                        logD("✅ Tap typing prediction engine initialized")
                    } else {
                        logE("Failed to initialize tap typing prediction engine")
                    }
                }

                // Initialize voice guidance engine (Bug #368 CATASTROPHIC)
                val prefs = DirectBootAwarePreferences.get_shared_preferences(this@CleverKeysService)
                if (prefs.getBoolean("voice_guidance_enabled", false)) {
                    voiceGuidanceEngine = VoiceGuidanceEngine(this@CleverKeysService).apply {
                        initialize { success ->
                            if (success) {
                                logD("✅ Voice guidance engine initialized")
                            } else {
                                logE("Failed to initialize voice guidance engine")
                            }
                        }
                    }
                } else {
                    logD("Voice guidance disabled in settings")
                }

                // Initialize screen reader manager (Bug #377 CATASTROPHIC)
                screenReaderManager = ScreenReaderManager(this@CleverKeysService).apply {
                    // Update state to detect TalkBack
                    updateScreenReaderState()
                    if (isScreenReaderActive()) {
                        logD("✅ Screen reader (TalkBack) detected and initialized")
                    } else {
                        logD("Screen reader not active")
                    }
                }
            } catch (e: Exception) {
                logE("Failed to initialize accessibility engines", e)
            }
        }
    }

    /**
     * Initialize spell checker integration (Fix for Bug #311)
     */
    private fun initializeSpellChecker() {
        try {
            // Initialize spell checker manager
            spellCheckerManager = SpellCheckerManager(this).apply {
                initialize()
                logD("✅ Spell checker manager initialized")
            }

            // Initialize spell check helper
            spellCheckerManager?.let { manager ->
                spellCheckHelper = SpellCheckHelper(manager)
                logD("✅ Spell check helper initialized")
            }
        } catch (e: Exception) {
            logE("Failed to initialize spell checker", e)
            // Non-fatal - keyboard can work without spell checking
        }
    }

    /**
     * Initialize smart punctuation handler (Fix for Bug #316 & #361)
     */
    private fun initializeSmartPunctuation() {
        try {
            smartPunctuationHandler = SmartPunctuationHandler()
            // Configure from settings
            config?.let { cfg ->
                smartPunctuationHandler?.setDoubleSpacePeriodEnabled(cfg.autocapitalisation)
                smartPunctuationHandler?.setAutoPairQuotesEnabled(true)
                smartPunctuationHandler?.setAutoPairBracketsEnabled(true)
                smartPunctuationHandler?.setContextSpacingEnabled(true)
            }
            logD("✅ Smart punctuation handler initialized")
        } catch (e: Exception) {
            logE("Failed to initialize smart punctuation", e)
            // Non-fatal - keyboard can work without smart punctuation
        }
    }

    /**
     * Initialize case converter (Fix for Bug #318)
     */
    private fun initializeCaseConverter() {
        try {
            caseConverter = CaseConverter()
            logD("✅ Case converter initialized")
        } catch (e: Exception) {
            logE("Failed to initialize case converter", e)
            // Non-fatal - keyboard can work without case conversion
        }
    }

    /**
     * Initialize text expander (Fix for Bug #319)
     */
    private fun initializeTextExpander() {
        try {
            textExpander = TextExpander(this)
            // Configure from settings
            config?.let { cfg ->
                // Enable by default - can be toggled via settings later
                textExpander?.setEnabled(true)
                textExpander?.setExpandOnSpace(true)
                textExpander?.setExpandOnPunctuation(true)
            }
            logD("✅ Text expander initialized (${textExpander?.getAllShortcuts()?.size ?: 0} shortcuts)")
        } catch (e: Exception) {
            logE("Failed to initialize text expander", e)
            // Non-fatal - keyboard can work without text expansion
        }
    }

    /**
     * Initialize cursor movement manager (Fix for Bug #322)
     */
    private fun initializeCursorMovementManager() {
        try {
            cursorMovementManager = CursorMovementManager()
            logD("✅ Cursor movement manager initialized")
        } catch (e: Exception) {
            logE("Failed to initialize cursor movement manager", e)
            // Non-fatal - keyboard can work without advanced cursor movement
        }
    }

    /**
     * Initialize multi-touch handler (Fix for Bug #323)
     */
    private fun initializeMultiTouchHandler() {
        try {
            multiTouchHandler = MultiTouchHandler(object : MultiTouchHandler.Callback {
                override fun onTwoFingerSwipe(direction: MultiTouchHandler.SwipeDirection, velocity: Float) {
                    logD("Two-finger swipe: ${direction.name} at ${velocity}px/s")
                    // Trigger appropriate keyboard action via KeyEventHandler
                    keyEventHandler?.let { handler ->
                        val event = when (direction) {
                            MultiTouchHandler.SwipeDirection.LEFT -> KeyValue.Event.TWO_FINGER_SWIPE_LEFT
                            MultiTouchHandler.SwipeDirection.RIGHT -> KeyValue.Event.TWO_FINGER_SWIPE_RIGHT
                            MultiTouchHandler.SwipeDirection.UP -> KeyValue.Event.TWO_FINGER_SWIPE_UP
                            MultiTouchHandler.SwipeDirection.DOWN -> KeyValue.Event.TWO_FINGER_SWIPE_DOWN
                        }
                        // TODO: Trigger event through KeyEventHandler
                        logD("Would trigger event: $event")
                    }
                }

                override fun onThreeFingerSwipe(direction: MultiTouchHandler.SwipeDirection) {
                    logD("Three-finger swipe: ${direction.name}")
                    keyEventHandler?.let { handler ->
                        val event = when (direction) {
                            MultiTouchHandler.SwipeDirection.LEFT -> KeyValue.Event.THREE_FINGER_SWIPE_LEFT
                            MultiTouchHandler.SwipeDirection.RIGHT -> KeyValue.Event.THREE_FINGER_SWIPE_RIGHT
                            MultiTouchHandler.SwipeDirection.UP -> KeyValue.Event.THREE_FINGER_SWIPE_UP
                            MultiTouchHandler.SwipeDirection.DOWN -> KeyValue.Event.THREE_FINGER_SWIPE_DOWN
                        }
                        // TODO: Trigger event through KeyEventHandler
                        logD("Would trigger event: $event")
                    }
                }

                override fun onPinchGesture(scale: Float) {
                    logD("Pinch gesture: scale = $scale")
                    val event = if (scale < 1.0f) {
                        KeyValue.Event.PINCH_IN
                    } else {
                        KeyValue.Event.PINCH_OUT
                    }
                    // TODO: Trigger event through KeyEventHandler
                    logD("Would trigger event: $event")
                }

                override fun onSimultaneousKeyPress(touchCount: Int) {
                    logD("Simultaneous key press: $touchCount touches")
                    // Could trigger special actions for multi-key combos
                }

                override fun performVibration() {
                    keyboardView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            })
            logD("✅ Multi-touch handler initialized")
        } catch (e: Exception) {
            logE("Failed to initialize multi-touch handler", e)
            // Non-fatal - keyboard can work without multi-touch gestures
        }
    }

    /**
     * Initialize sound effect manager for keyboard audio feedback.
     * Bug #324 - HIGH: Implement missing SoundEffectManager
     */
    private fun initializeSoundEffectManager() {
        try {
            // TODO: Get enabled state and volume from user preferences
            val soundsEnabled = true  // Default to enabled
            val volume = 0.5f         // Default volume (50%)

            soundEffectManager = SoundEffectManager(
                context = this,
                enabled = soundsEnabled,
                volumeLevel = volume
            )

            // Preload sounds for low-latency playback
            soundEffectManager?.preloadSounds()

            logD("✅ Sound effect manager initialized (enabled=$soundsEnabled, volume=$volume)")
        } catch (e: Exception) {
            logE("Failed to initialize sound effect manager", e)
            // Non-fatal - keyboard can work without sound effects
        }
    }

    /**
     * Initialize animation manager for keyboard UI animations.
     * Bug #325 - HIGH: Implement missing AnimationManager
     */
    private fun initializeAnimationManager() {
        try {
            // TODO: Get enabled state and duration scale from user preferences
            val animationsEnabled = true  // Default to enabled
            val durationScale = 1.0f      // Default scale (normal speed)

            animationManager = AnimationManager(
                context = this,
                enabled = animationsEnabled,
                durationScale = durationScale
            )

            logD("✅ Animation manager initialized (enabled=$animationsEnabled, scale=$durationScale)")
        } catch (e: Exception) {
            logE("Failed to initialize animation manager", e)
            // Non-fatal - keyboard can work without animations
        }
    }

    /**
     * Initialize key preview manager for key press popups.
     * Bug #326 - HIGH: Implement missing KeyPreviewManager
     */
    private fun initializeKeyPreviewManager() {
        try {
            // TODO: Get enabled state and duration from user preferences
            val previewsEnabled = true   // Default to enabled
            val previewDuration = 100L   // Default duration (100ms)

            keyPreviewManager = KeyPreviewManager(
                context = this,
                enabled = previewsEnabled,
                duration = previewDuration
            )

            logD("✅ Key preview manager initialized (enabled=$previewsEnabled, duration=${previewDuration}ms)")
        } catch (e: Exception) {
            logE("Failed to initialize key preview manager", e)
            // Non-fatal - keyboard can work without key previews
        }
    }

    /**
     * Initialize key event handler
     */
    private fun initializeKeyEventHandler() {
        keyEventHandler = KeyEventHandler(
            receiver = object : KeyEventHandler.IReceiver {
            override fun getInputConnection(): InputConnection? = currentInputConnection
            override fun getCurrentInputEditorInfo(): EditorInfo? = currentInputEditorInfo
            override fun getKeyboardView(): android.view.View? = keyboardView
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
                logD("Switching to main layout")
                // Return to the user's configured primary layout
                val mainLayoutIndex = 0
                switchToLayout(mainLayoutIndex)
            }
            override fun switchToNumericLayout() {
                logD("Switching to numeric layout")
                // Load numeric keypad layout
                try {
                    val numericLayoutName = when (config?.selected_number_layout) {
                        NumberLayout.NUMBER -> "numeric"
                        NumberLayout.NUMPAD -> "numpad"
                        NumberLayout.PIN -> "pin"
                        else -> "numeric"
                    }
                    val resourceId = resources.getIdentifier(numericLayoutName, "xml", packageName)
                    if (resourceId != 0) {
                        val numericLayout = KeyboardData.load(resources, resourceId)
                        numericLayout?.let { layout ->
                            keyboardView?.setKeyboard(layout)
                            logD("✅ Switched to numeric layout: $numericLayoutName")
                        }
                    }
                } catch (e: Exception) {
                    logE("Failed to switch to numeric layout", e)
                }
            }
            override fun switchToEmojiLayout() {
                logD("Switching to emoji layout")
                // Emoji layout is typically shown as a separate view/pane
                // For now, just log - full emoji implementation would need EmojiGridView integration
                logW("Emoji layout switching not yet fully integrated with EmojiGridView")
            }
            override fun openSettings() {
                logD("Opening settings")
                try {
                    val intent = android.content.Intent(this@CleverKeysService, SettingsActivity::class.java)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                } catch (e: Exception) {
                    logE("Failed to open settings", e)
                }
            }
            override fun updateSuggestions(suggestions: List<String>) {
                // Update suggestion bar with tap-typing predictions (Fix for Bug #313)
                suggestionBar?.setSuggestions(suggestions)
                logD("Tap typing predictions: ${suggestions.joinToString(", ")}")
            }
        },
            typingPredictionEngine = typingPredictionEngine,
            voiceGuidanceEngine = voiceGuidanceEngine,
            screenReaderManager = screenReaderManager,
            spellCheckHelper = spellCheckHelper,
            smartPunctuationHandler = smartPunctuationHandler,
            caseConverter = caseConverter,
            textExpander = textExpander,
            cursorMovementManager = cursorMovementManager,
            multiTouchHandler = multiTouchHandler,
            soundEffectManager = soundEffectManager
        )

        // Re-initialize Config with the fully-initialized handler (Fix #51, #311)
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        Config.initGlobalConfig(prefs, resources, keyEventHandler, false)
        config = Config.globalConfig()
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
                    neuralEngine?.let { engine ->
                        predictionService = SwipePredictionService(engine)
                    } ?: logE("Cannot initialize prediction service: neural engine is null")
                    
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
     * Load current keyboard layout from Config
     */
    private fun loadDefaultKeyboardLayout() {
        try {
            val cfg = config ?: run {
                logE("Config not initialized - cannot load layout")
                return
            }

            // Get current layout index from config
            val layoutIndex = cfg.get_current_layout()

            // Get the keyboard layout from config's layouts list
            currentLayout = cfg.layouts.getOrNull(layoutIndex)

            if (currentLayout != null) {
                logD("✅ Layout loaded from Config: index=$layoutIndex, name=${currentLayout?.name}")
            } else {
                // Fallback to first available layout if index is invalid
                currentLayout = cfg.layouts.firstOrNull()
                if (currentLayout != null) {
                    logD("✅ Fallback to first layout: ${currentLayout?.name}")
                } else {
                    logE("No keyboard layouts available in Config")
                }
            }
        } catch (e: Exception) {
            logE("Failed to load keyboard layout", e)
        }
    }

    /**
     * Initialize ComposeKeyData from binary assets file
     * Must be called after configuration is initialized
     */
    private fun initializeComposeKeyData() {
        try {
            ComposeKeyData.initialize(this)
            logD("✅ ComposeKeyData initialized (${ComposeKeyData.states.size} states)")
        } catch (e: Exception) {
            logE("Failed to initialize ComposeKeyData", e)
            // Non-critical - compose key feature will be unavailable
        }
    }

    /**
     * Bug #118 & #120 fix: Initialize clipboard history service with paste callback
     *
     * This registers the CleverKeysService as the paste callback handler,
     * enabling clipboard paste functionality from ClipboardPinView.
     */
    private fun initializeClipboardService() {
        try {
            // Register paste callback asynchronously
            CoroutineScope(Dispatchers.Main).launch {
                ClipboardHistoryService.onStartup(this@CleverKeysService, this@CleverKeysService)
                logD("✅ Clipboard history service initialized with paste callback")
            }
        } catch (e: Exception) {
            logE("Failed to initialize clipboard service", e)
            // Non-critical - clipboard paste will be unavailable
        }
    }

    /**
     * Android requires this method to create the keyboard view
     * Fix #52: Create container with suggestion bar + keyboard view
     */
    override fun onCreateInputView(): View? {
        logD("onCreateInputView() called - creating container with keyboard + suggestions")

        val currentConfig = config ?: run {
            logE("Configuration not available for input view creation")
            return null
        }

        return try {
            // Create keyboard view
            logD("Creating Keyboard2View...")
            val kbView = Keyboard2View(this).apply {
                setViewConfig(currentConfig)
                setKeyboardService(this@CleverKeysService)

                currentLayout?.let { layout ->
                    setKeyboard(layout)
                    logD("Layout set on view: ${layout.name}")
                } ?: logW("No keyboard layout available")

                setKeyboardHeightPercent(currentConfig.keyboardHeightPercent)
            }
            keyboardView = kbView

            // Create Material 3 suggestion bar
            logD("Creating Material 3 SuggestionBar...")
            val sugBar = SuggestionBarM3Wrapper(this).apply {
                setOnSuggestionSelectedListener { word ->
                    logD("User selected suggestion: '$word'")
                    currentInputConnection?.commitText(word + " ", 1)
                }
            }
            suggestionBar = sugBar

            // Create container (Fix #52: LinearLayout with suggestion bar on top)
            logD("Creating LinearLayout container...")
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL

                // Add suggestion bar on top (40dp height)
                val suggestionParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (40 * resources.displayMetrics.density).toInt()
                )
                sugBar.layoutParams = suggestionParams
                addView(sugBar)

                // Add keyboard view below (fills remaining space)
                val keyboardParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                kbView.layoutParams = keyboardParams
                addView(kbView)
            }
            inputViewContainer = container

            logD("✅ Container created successfully with suggestion bar + keyboard view")
            container

        } catch (e: Exception) {
            logE("Failed to create keyboard input view", e)
            null
        }
    }

    /**
     * Create suggestion bar - NO LONGER USED (Fix #52: integrated into onCreateInputView)
     * Keeping for compatibility but returns null
     */
    override fun onCreateCandidatesView(): View? {
        logD("onCreateCandidatesView() called - returning null (integrated into input view)")
        return null
    }

    /**
     * Switch to a different keyboard layout by index
     */
    private fun switchToLayout(layoutIndex: Int) {
        try {
            val cfg = config ?: run {
                logE("Cannot switch layout: config not initialized")
                return
            }

            if (layoutIndex >= 0 && layoutIndex < cfg.layouts.size) {
                val layout = cfg.layouts[layoutIndex]
                currentLayout = layout
                keyboardView?.setKeyboard(layout)
                cfg.set_current_layout(layoutIndex)
                logD("✅ Switched to layout index $layoutIndex: ${layout.name}")
            } else {
                logE("Invalid layout index: $layoutIndex (available: ${cfg.layouts.size})")
            }
        } catch (e: Exception) {
            logE("Failed to switch layout", e)
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
     * Handle input view starting - called when keyboard becomes visible
     */
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        logD("Starting input view: package=${editorInfo?.packageName}, restarting=$restarting")

        try {
            // Refresh configuration
            config?.refresh(resources, null)

            // Update keyboard view with current layout
            keyboardView?.let { view ->
                currentLayout?.let { layout ->
                    view.setKeyboard(layout)
                    logD("Layout applied to view: ${layout.name}")
                }
            }

            // Notify key event handler
            keyEventHandler?.started(editorInfo)

            logD("✅ Input view started successfully")
        } catch (e: Exception) {
            logE("Error starting input view", e)
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
     * Update keyboard dimensions for neural prediction coordinate normalization
     * Called by Keyboard2View when keyboard is measured
     */
    internal fun updateKeyboardDimensions(width: Int, height: Int) {
        neuralEngine?.setKeyboardDimensions(width, height)

        // CRITICAL: Update real key positions for accurate nearest_keys detection
        // This fixes the issue where nearest_keys were detecting wrong keys due to
        // using fallback grid detection with incorrect dimensions
        keyboardView?.getRealKeyPositions()?.let { keyPositions ->
            neuralEngine?.setRealKeyPositions(keyPositions)
            logD("🎹 Real key positions updated: ${keyPositions.size} keys")
        }

        logD("📐 Keyboard dimensions updated: ${width}x${height}")
    }

    /**
     * Handle swipe gesture completion with complete pipeline integration
     * Called by Keyboard2View when user completes a swipe gesture
     */
    internal fun handleSwipeGesture(swipeData: SwipeGestureData) {
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
        // Update suggestion bar with prediction results
        suggestionBar?.setSuggestions(result.predictions.words.take(5))

        logD("🧠 ${result.source} neural prediction: ${result.predictions.size} words in ${result.processingTimeMs}ms")
        logD("   Top predictions: ${result.predictions.words.take(3)}")

        // Auto-commit high-confidence predictions if enabled
        autoCommitHighConfidencePrediction(result)
    }

    /**
     * Auto-commit prediction if confidence is high enough
     */
    private fun autoCommitHighConfidencePrediction(result: NeuralPredictionPipeline.PipelineResult) {
        if (!config?.auto_commit_predictions.let { it == true }) return

        val topPrediction = result.predictions.words.firstOrNull() ?: return
        val topScore = result.predictions.topScore ?: return

        // Convert score (0-1000) to confidence (0.0-1.0)
        val topConfidence = topScore / 1000.0f

        // Auto-commit if confidence exceeds threshold (default 0.8 = 80%)
        val threshold = config?.auto_commit_threshold ?: 0.8f
        if (topConfidence >= threshold) {
            logD("Auto-committing high-confidence prediction: '$topPrediction' (${topConfidence * 100}%)")
            currentInputConnection?.commitText(topPrediction + " ", 1)
        }
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
     * Show error toast to user with proper UI thread handling
     */
    private fun showErrorToast(message: String) {
        logE("User error: $message")

        // Ensure toast is shown on main UI thread
        runOnUiThread {
            android.widget.Toast.makeText(
                this@CleverKeysService,
                "⚠️ $message",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Run code on main UI thread (helper for service context)
     */
    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
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
                    // Theme update handled by updateUITheme and view recreation
                    updateUITheme()
                    keyboardView?.invalidate()  // Request redraw with new theme
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
     * Update UI theme and propagate to active components
     */
    private fun updateUITheme() {
        try {
            val cfg = config ?: run {
                logW("Cannot update theme: config not initialized")
                return
            }

            // Update keyboard view theme
            keyboardView?.let { view ->
                // Trigger view refresh with new theme
                view.invalidate()
                view.requestLayout()
            }

            // Update suggestion bar theme if active
            (currentInputConnection as? android.view.View)?.invalidate()

            logD("✅ UI theme updated and propagated to active components")
        } catch (e: Exception) {
            logE("Failed to update UI theme", e)
        }
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
     * Stop performance monitoring with proper cleanup
     */
    private fun stopPerformanceMonitoring() {
        performanceProfiler?.cleanup()
        logD("Performance monitoring stopped and cleaned up")
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
     * Handle swipe prediction results from neural engine
     */
    fun handleSwipePrediction(prediction: PredictionResult) {
        serviceScope.launch {
            try {
                val suggestions = prediction.words.take(5)
                logD("Received ${suggestions.size} neural predictions: ${suggestions.joinToString(", ")}")

                // Update suggestion UI if available
                // suggestionBar?.setSuggestions(suggestions)

                // Could auto-commit high confidence predictions here
                val topScore = prediction.scores.firstOrNull()
                if (topScore != null && topScore > 800) { // High confidence threshold
                    logD("High confidence prediction: ${suggestions.firstOrNull()}")
                }
            } catch (e: Exception) {
                logE("Error handling swipe prediction", e)
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

    /**
     * Receiver implementation for KeyEventHandler (Fix #51)
     * Handles callbacks from the key event processing system
     */
    inner class Receiver : KeyEventHandler.IReceiver {
        override fun getInputConnection(): InputConnection? = currentInputConnection

        override fun getCurrentInputEditorInfo(): EditorInfo? = currentInputEditorInfo

        override fun getKeyboardView(): android.view.View? = keyboardView

        override fun performVibration() {
            // Haptic feedback handled by Keyboard2View
            keyboardView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun commitText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun performAction(action: Int) {
            currentInputConnection?.performEditorAction(action)
        }

        override fun switchToMainLayout() {
            // TODO: Implement layout switching
            logD("switchToMainLayout() called - not yet implemented")
        }

        override fun switchToNumericLayout() {
            // TODO: Implement layout switching
            logD("switchToNumericLayout() called - not yet implemented")
        }

        override fun switchToEmojiLayout() {
            // TODO: Implement layout switching
            logD("switchToEmojiLayout() called - not yet implemented")
        }

        override fun openSettings() {
            // Launch settings activity
            try {
                val intent = Intent(this@CleverKeysService, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                logE("Failed to open settings", e)
            }
        }

        override fun updateSuggestions(suggestions: List<String>) {
            // Update suggestion bar with tap typing predictions
            suggestionBar?.setSuggestions(suggestions)
        }
    }

    // ============================================================================
    // Clipboard Integration (Bug #118 & #120 fixes)
    // ============================================================================

    /**
     * Bug #118 & #120 fix: Implement ClipboardPasteCallback to enable paste from pinned items
     *
     * This method is called when user taps the paste button in ClipboardPinView.
     * It commits the clipboard content to the active text editor.
     */
    override fun pasteFromClipboardPane(content: String) {
        try {
            currentInputConnection?.commitText(content, 1)
            logD("Pasted clipboard content: ${content.take(50)}...")
        } catch (e: Exception) {
            logE("Failed to paste from clipboard pane", e)
        }
    }
}