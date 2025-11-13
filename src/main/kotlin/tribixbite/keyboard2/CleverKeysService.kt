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
    private var gestureTrailRenderer: GestureTrailRenderer? = null
    private var keyRepeatHandler: KeyRepeatHandler? = null
    private var layoutSwitchAnimator: LayoutSwitchAnimator? = null
    private var oneHandedModeManager: OneHandedModeManager? = null
    private var floatingKeyboardManager: FloatingKeyboardManager? = null
    private var splitKeyboardManager: SplitKeyboardManager? = null
    private var darkModeManager: DarkModeManager? = null
    private var adaptiveLayoutManager: AdaptiveLayoutManager? = null
    private var typingStatisticsCollector: TypingStatisticsCollector? = null
    private var keyBorderRenderer: KeyBorderRenderer? = null
    private var localeManager: LocaleManager? = null
    private var characterSetManager: CharacterSetManager? = null
    private var unicodeNormalizer: UnicodeNormalizer? = null
    private var translationEngine: TranslationEngine? = null
    private var autoCorrection: AutoCorrection? = null
    private var spellChecker: SpellChecker? = null
    private var frequencyModel: FrequencyModel? = null
    private var textPredictionEngine: TextPredictionEngine? = null
    private var completionEngine: CompletionEngine? = null
    private var contextAnalyzer: ContextAnalyzer? = null
    private var grammarChecker: GrammarChecker? = null
    private var undoRedoManager: UndoRedoManager? = null
    private var selectionManager: SelectionManager? = null  // Bug #321 fix
    private var macroExpander: MacroExpander? = null  // Bug #354 fix
    private var shortcutManager: ShortcutManager? = null  // Bug #355 fix
    private var gestureTypingCustomizer: GestureTypingCustomizer? = null  // Bug #356 fix
    private var continuousInputManager: ContinuousInputManager? = null  // Bug #357 fix
    private var handwritingRecognizer: HandwritingRecognizer? = null  // Bug #352 fix
    private var voiceTypingEngine: VoiceTypingEngine? = null  // Bug #353 fix
    private var languageManager: LanguageManager? = null  // Bug #344 fix
    private var imeLanguageSelector: IMELanguageSelector? = null  // Bug #347 fix
    private var rtlLanguageHandler: RTLLanguageHandler? = null  // Bug #349 fix
    private var comprehensiveTraceAnalyzer: ComprehensiveTraceAnalyzer? = null  // Bug #276 fix
    private var thumbModeOptimizer: ThumbModeOptimizer? = null  // Bug #359 fix
    private var multiLanguageDictionary: MultiLanguageDictionaryManager? = null  // Bug #277 fix
    private var keyboardSwipeRecognizer: KeyboardSwipeRecognizer? = null  // Bug #256 fix
    private var bigramModel: BigramModel? = null  // Bug #255 fix - contextual word prediction
    private var ngramModel: NgramModel? = null  // Bug #259 fix
    private var wordPredictor: WordPredictor? = null  // Bug #262 fix
    private var languageDetector: LanguageDetector? = null  // Bug #257 fix
    private var userAdaptationManager: UserAdaptationManager? = null  // Bug #263 fix
    private var swipeMLTrainer: tribixbite.keyboard2.ml.SwipeMLTrainer? = null  // Bug #274 fix
    private var neuralSwipeTypingEngine: NeuralSwipeTypingEngine? = null  // Bug #275 dependency - neural prediction engine
    private var asyncPredictionHandler: AsyncPredictionHandler? = null  // Bug #275 fix - async prediction processing
    private var inputConnectionManager: InputConnectionManager? = null  // Advanced input connection management
    private var personalizationManager: PersonalizationManager? = null  // Word frequency tracking and bigram learning
    private var longPressManager: LongPressManager? = null  // Bug #327 fix - long-press behavior
    private var backupRestoreManager: BackupRestoreManager? = null  // Configuration backup/restore with SAF
    private var settingsSyncManager: SettingsSyncManager? = null  // Bug #383 fix - settings backup/sync

    // Configuration and state
    private var config: Config? = null
    private var currentLayout: KeyboardData? = null
    
    override fun onCreate() {
        super.onCreate()
        logD("CleverKeys InputMethodService starting...")

        try {
            // Initialize components in dependency order
            initializeConfiguration()
            initializeLanguageManager()  // Bug #344 fix - foundational for multi-language
            initializeIMELanguageSelector()  // Bug #347 fix - language selection UI
            initializeRTLLanguageHandler()  // Bug #349 fix - RTL text support
            initializeComprehensiveTraceAnalyzer()  // Bug #276 fix - advanced gesture analysis
            initializeThumbModeOptimizer()  // Bug #359 fix - ergonomic thumb typing
            initializeMultiLanguageDictionary()  // Bug #277 fix - multi-language dictionaries
            initializeKeyboardSwipeRecognizer()  // Bug #256 fix - Bayesian swipe recognition
            initializeBigramModel()  // Bug #255 fix - contextual word prediction
            initializeNgramModel()  // Bug #259 fix
            initializeWordPredictor()  // Bug #262 fix
            initializeLanguageDetector()  // Bug #257 fix
            initializeUserAdaptationManager()  // Bug #263 fix
            initializeSwipeMLTrainer()  // Bug #274 fix
            initializeNeuralSwipeTypingEngine()  // Bug #275 dependency - neural prediction engine
            initializeAsyncPredictionHandler()  // Bug #275 fix - async prediction processing
            initializeInputConnectionManager()  // Advanced input connection management
            initializePersonalizationManager()  // Word frequency tracking and bigram learning
            initializeLongPressManager()  // Bug #327 fix - long-press behavior
            initializeBackupRestoreManager()  // Configuration backup/restore with SAF
            initializeSettingsSyncManager()  // Bug #383 fix - settings backup/sync
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
            initializeGestureTrailRenderer()  // Bug #328 fix
            initializeKeyRepeatHandler()    // Bug #330 fix
            initializeLayoutSwitchAnimator()  // Bug #329 fix
            initializeOneHandedModeManager()  // Bug #331 fix
            initializeFloatingKeyboardManager()  // Bug #332 fix
            initializeSplitKeyboardManager()  // Bug #333 fix
            initializeDarkModeManager()  // Bug #334 fix
            initializeAdaptiveLayoutManager()  // Bug #335 fix
            initializeTypingStatisticsCollector()  // Bug #336 fix
            initializeKeyBorderRenderer()  // Bug #337 fix
            initializeLocaleManager()  // Bug #346 fix
            initializeCharacterSetManager()  // Bug #350 fix
            initializeUnicodeNormalizer()  // Bug #351 fix
            initializeTranslationEngine()  // Bug #348 fix
            initializeAutoCorrection()  // Bug #310 fix
            initializeCustomSpellChecker()  // Bug #311 fix
            initializeFrequencyModel()  // Bug #312 fix
            initializeTextPredictionEngine()  // Bug #313 fix
            initializeCompletionEngine()  // Bug #314 fix
            initializeContextAnalyzer()  // Bug #315 fix
            initializeGrammarChecker()  // Bug #317 fix
            initializeUndoRedoManager()  // Bug #320 fix
            initializeSelectionManager()  // Bug #321 fix
            initializeMacroExpander()  // Bug #354 fix
            initializeShortcutManager()  // Bug #355 fix
            initializeGestureTypingCustomizer()  // Bug #356 fix
            initializeContinuousInputManager()  // Bug #357 fix
            initializeHandwritingRecognizer()  // Bug #352 fix
            initializeVoiceTypingEngine()  // Bug #353 fix
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
            gestureTrailRenderer?.release()  // Bug #328 - release trail renderer resources
            keyRepeatHandler?.release()    // Bug #330 - release key repeat handler resources
            layoutSwitchAnimator?.release()  // Bug #329 - release layout animator resources
            oneHandedModeManager?.release()  // Bug #331 - release one-handed mode manager resources
            floatingKeyboardManager?.release()  // Bug #332 - release floating keyboard manager resources
            splitKeyboardManager?.release()  // Bug #333 - release split keyboard manager resources
            darkModeManager?.release()  // Bug #334 - release dark mode manager resources
            adaptiveLayoutManager?.release()  // Bug #335 - release adaptive layout manager resources
            typingStatisticsCollector?.release()  // Bug #336 - release typing statistics collector resources
            keyBorderRenderer?.release()  // Bug #337 - release key border renderer resources
            localeManager?.release()  // Bug #346 - release locale manager resources
            characterSetManager?.release()  // Bug #350 - release character set manager resources
            unicodeNormalizer?.release()  // Bug #351 - release unicode normalizer resources
            translationEngine?.release()  // Bug #348 - release translation engine resources
            autoCorrection?.release()  // Bug #310 - release autocorrection resources
            spellChecker?.release()  // Bug #311 - release spell checker resources
            frequencyModel?.release()  // Bug #312 - release frequency model resources
            textPredictionEngine?.release()  // Bug #313 - release text prediction engine resources
            completionEngine?.release()  // Bug #314 - release completion engine resources
            contextAnalyzer?.release()  // Bug #315 - release context analyzer resources
            grammarChecker?.release()  // Bug #317 - release grammar checker resources
            undoRedoManager?.release()  // Bug #320 - release undo/redo manager resources
            selectionManager?.release()  // Bug #321 - release selection manager resources
            macroExpander?.release()  // Bug #354 - release macro expander resources
            shortcutManager?.release()  // Bug #355 - release shortcut manager resources
            gestureTypingCustomizer?.release()  // Bug #356 - release gesture typing customizer resources
            continuousInputManager?.release()  // Bug #357 - release continuous input manager resources
            handwritingRecognizer?.release()  // Bug #352 - release handwriting recognizer resources
            voiceTypingEngine?.release()  // Bug #353 - release voice typing engine resources
            rtlLanguageHandler?.release()  // Bug #349 - release RTL language handler resources
            comprehensiveTraceAnalyzer?.release()  // Bug #276 - release comprehensive trace analyzer resources
            thumbModeOptimizer?.release()  // Bug #359 - release thumb mode optimizer resources
            multiLanguageDictionary?.release()  // Bug #277 - release multi-language dictionary resources
            keyboardSwipeRecognizer?.release()  // Bug #256 - release keyboard swipe recognizer resources
            imeLanguageSelector?.release()  // Bug #347 - release IME language selector resources
            languageManager?.release()  // Bug #344 - release language manager resources
            userAdaptationManager?.cleanup()  // Bug #263 - release user adaptation manager resources
            swipeMLTrainer?.shutdown()  // Bug #274 - release swipe ML trainer resources
            asyncPredictionHandler?.shutdown()  // Bug #275 - release async prediction handler resources
            inputConnectionManager?.cleanup()  // Release input connection manager resources
            serviceScope.launch {
                neuralSwipeTypingEngine?.cleanup()  // Bug #275 dependency - cleanup is suspend function
            }
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
     * Initialize language manager for multi-language support.
     * Bug #344 fix - Foundational component for language switching.
     */
    private fun initializeLanguageManager() {
        try {
            languageManager = LanguageManager(context = this)

            logD("✅ LanguageManager initialized (Bug #344)")
        } catch (e: Exception) {
            logE("Failed to initialize language manager", e)
            // Non-fatal - keyboard defaults to English
        }
    }

    /**
     * Initialize IME language selector for language selection UI.
     * Bug #347 fix - Provides globe key menu and language switching UI.
     */
    private fun initializeIMELanguageSelector() {
        try {
            languageManager?.let { manager ->
                imeLanguageSelector = IMELanguageSelector(
                    context = this,
                    languageManager = manager
                )

                logD("✅ IMELanguageSelector initialized (Bug #347)")
            } ?: run {
                logE("Cannot initialize IMELanguageSelector: LanguageManager not initialized")
            }
        } catch (e: Exception) {
            logE("Failed to initialize IME language selector", e)
            // Non-fatal - language switching can still work without UI
        }
    }

    /**
     * Initialize RTL language handler for Arabic/Hebrew support.
     * Bug #349 fix - Essential for ~429M RTL language users.
     */
    private fun initializeRTLLanguageHandler() {
        try {
            rtlLanguageHandler = RTLLanguageHandler(context = this)

            logD("✅ RTLLanguageHandler initialized (Bug #349)")
        } catch (e: Exception) {
            logE("Failed to initialize RTL language handler", e)
            // Non-fatal - keyboard works without RTL support
        }
    }

    /**
     * Initialize comprehensive trace analyzer.
     *
     * Implements Bug #276 fix - Advanced gesture analysis for swipe typing.
     *
     * Provides comprehensive trace analysis including:
     * - Geometric features (length, curvature, angles, bounding box, aspect ratio)
     * - Motion features (velocity, acceleration, jerk)
     * - Pattern detection (LINEAR/CURVED/LOOP/ZIGZAG/SPIRAL/COMPLEX)
     * - Quality metrics (EXCELLENT/GOOD/FAIR/POOR/INVALID)
     * - 25-dimensional feature vector extraction for ML
     * - Stroke segmentation and direction change detection
     */
    private fun initializeComprehensiveTraceAnalyzer() {
        try {
            comprehensiveTraceAnalyzer = ComprehensiveTraceAnalyzer(context = this)

            logD("✅ ComprehensiveTraceAnalyzer initialized (Bug #276)")
        } catch (e: Exception) {
            logE("Failed to initialize comprehensive trace analyzer", e)
            // Non-fatal - gesture analysis provides enhancement but not critical
        }
    }

    /**
     * Initialize thumb mode optimizer.
     *
     * Implements Bug #359 fix - Ergonomic thumb-zone keyboard optimization.
     *
     * Provides ergonomic keyboard adaptations for one-handed and two-handed
     * thumb typing with curved key layouts and reach-zone optimization:
     * - Thumb reach zone calculation based on screen size
     * - One-handed mode (left/right thumb optimization)
     * - Two-handed mode (dual thumb zones)
     * - Curved/arc keyboard layout adaptation
     * - Dynamic key positioning for ergonomic reach
     * - Key size adjustments for better accessibility
     * - Screen size and orientation detection
     * - Thumb fatigue reduction optimization
     */
    private fun initializeThumbModeOptimizer() {
        try {
            thumbModeOptimizer = ThumbModeOptimizer(context = this)

            logD("✅ ThumbModeOptimizer initialized (Bug #359) - Device: ${thumbModeOptimizer?.getDeviceSize()}, Screen: ${thumbModeOptimizer?.getScreenSizeInches()}\"")
        } catch (e: Exception) {
            logE("Failed to initialize thumb mode optimizer", e)
            // Non-fatal - keyboard works without thumb mode optimization
        }
    }

    /**
     * Initialize multi-language dictionary manager.
     *
     * Implements Bug #277 fix - Multi-language dictionary support with user dictionary.
     *
     * Provides comprehensive dictionary management for multiple languages:
     * - 20 supported languages (en, es, fr, de, it, pt, ru, zh, ja, ko, ar, he, hi, th, el, tr, pl, nl, sv, da)
     * - System dictionaries (pre-loaded word lists)
     * - User dictionaries (personal words, learned from typing)
     * - Language switching with automatic dictionary reload
     * - Word frequency tracking and boosting
     * - User word persistence via SharedPreferences
     * - Common words and top-5000 fast paths
     * - OOV (out-of-vocabulary) handling with confidence thresholding
     *
     * Note: Coordinates with LanguageManager for consistent language state.
     */
    private fun initializeMultiLanguageDictionary() {
        try {
            multiLanguageDictionary = MultiLanguageDictionaryManager(context = this)

            // Load default language (English) in background
            serviceScope.launch {
                val success = multiLanguageDictionary?.setLanguage(
                    MultiLanguageDictionaryManager.Language.ENGLISH
                )
                if (success == true) {
                    logD("✅ MultiLanguageDictionaryManager initialized (Bug #277) - Default: English")
                } else {
                    logE("Failed to load default English dictionary", null)
                }
            }

            // Set up language change listener
            multiLanguageDictionary?.setCallback(object : MultiLanguageDictionaryManager.Callback {
                override fun onLanguageChanged(language: MultiLanguageDictionaryManager.Language) {
                    logD("Dictionary language changed: ${language.displayName}")
                }

                override fun onDictionaryLoaded(language: MultiLanguageDictionaryManager.Language, wordCount: Int) {
                    logD("Dictionary loaded: ${language.displayName} ($wordCount words)")
                }

                override fun onUserWordAdded(word: String, language: MultiLanguageDictionaryManager.Language) {
                    logD("User word added: $word (${language.displayName})")
                }
            })

            logD("MultiLanguageDictionaryManager created (Bug #277)")
        } catch (e: Exception) {
            logE("Failed to initialize multi-language dictionary", e)
            // Non-fatal - keyboard works with fallback vocabulary
        }
    }

    /**
     * Initialize keyboard swipe recognizer.
     *
     * Implements Bug #256 fix - Bayesian keyboard-specific swipe recognition.
     *
     * Provides probabilistic swipe-to-word recognition using Bayesian inference:
     * - P(word|path) = P(path|word) * P(word) / P(path)
     * - Keyboard layout integration (key positions, sizes, centers)
     * - Geometric path analysis (distance, angle, curvature, smoothness)
     * - Key proximity scoring with Gaussian distribution
     * - Path segmentation into key regions with dwell time tracking
     * - Multi-candidate scoring and ranking with confidence thresholds
     * - Language model prior probabilities
     * - Path likelihood calculation with proximity and dwell time
     * - Real-time incremental recognition for immediate feedback
     * - Edit distance penalty for non-exact matches
     * - Path smoothing with moving average filter
     * - Touch point clustering for noise reduction
     *
     * Note: Keyboard layout must be set before recognition can begin.
     */
    private fun initializeKeyboardSwipeRecognizer() {
        try {
            keyboardSwipeRecognizer = KeyboardSwipeRecognizer(context = this)

            // Set up recognition callback
            keyboardSwipeRecognizer?.setCallback(object : KeyboardSwipeRecognizer.Callback {
                override fun onRecognitionComplete(results: List<KeyboardSwipeRecognizer.RecognitionResult>) {
                    logD("Swipe recognition complete: ${results.size} results")
                    if (results.isNotEmpty()) {
                        val top = results.first()
                        logD("Top result: ${top.word} (confidence: ${top.confidence})")
                    }
                }

                override fun onPartialRecognition(topCandidate: KeyboardSwipeRecognizer.RecognitionResult?) {
                    topCandidate?.let {
                        logD("Partial: ${it.word} (${it.confidence})")
                    }
                }
            })

            logD("✅ KeyboardSwipeRecognizer initialized (Bug #256)")
        } catch (e: Exception) {
            logE("Failed to initialize keyboard swipe recognizer", e)
            // Non-fatal - keyboard works with fallback recognition
        }
    }

    /**
     * Initialize bigram model for contextual word prediction.
     *
     * Implements Bug #255 fix - Word-level bigram model system.
     *
     * Provides contextual word prediction using bigram probabilities:
     * - P(word | previous_word) for context-aware predictions
     * - 4-language support (en, es, fr, de) with language-specific models
     * - Linear interpolation smoothing (λ=0.95) between bigram/unigram
     * - Context multiplier (0.1-10.0x) for boosting/penalizing predictions
     * - User adaptation via addBigram() for learning new patterns
     * - Singleton pattern for global access
     *
     * Features:
     * - Language-specific bigram probabilities (e.g., "the|end", "a|lot")
     * - Language-specific unigram fallback probabilities
     * - Automatic language switching with setLanguage()
     * - File loading for comprehensive bigram data from assets
     * - User learning via addBigram() for personalization
     * - Statistics tracking (word count, language coverage)
     *
     * Integration:
     * - Will coordinate with LanguageManager for language switching
     * - Can load additional bigram data from assets/bigrams/
     * - Provides scoring for KeyboardSwipeRecognizer word candidates
     * - Enhances MultiLanguageDictionaryManager predictions
     *
     * Note: BigramModel is a singleton - getInstance() returns shared instance.
     * No cleanup needed in onDestroy() as singleton persists across lifecycle.
     */
    private fun initializeBigramModel() {
        try {
            // Get singleton instance (creates if first time)
            bigramModel = BigramModel.getInstance(context = this)

            // Set initial language from LanguageManager if available
            val currentLang = languageManager?.getCurrentLanguage()?.code ?: "en"
            bigramModel?.setLanguage(currentLang)

            // Log statistics
            val stats = bigramModel?.getStatistics() ?: "N/A"
            logD("BigramModel initialized: $stats")

            // TODO: Load additional bigram data from assets if available
            // bigramModel?.loadFromFile(this, "bigrams_en.txt")

            logD("✅ BigramModel initialized (Bug #255)")
        } catch (e: Exception) {
            logE("Failed to initialize bigram model", e)
            // Non-fatal - predictions work without contextual scoring
        }
    }

    /**
     * Initialize n-gram model (Bug #259 fix).
     */
    private fun initializeNgramModel() {
        try {
            ngramModel = NgramModel()
            logD("✅ NgramModel initialized (Bug #259)")
        } catch (e: Exception) {
            logE("Failed to initialize n-gram model", e)
        }
    }

    /**
     * Initialize word predictor (Bug #262 fix).
     */
    private fun initializeWordPredictor() {
        try {
            config?.let { cfg ->
                wordPredictor = WordPredictor(context = this, config = cfg)

                // TODO: WordPredictor expects tribixbite.keyboard2.data.BigramModel
                // but we integrated tribixbite.keyboard2.BigramModel (different class)
                // For now, pass null - predictions work without bigram context

                logD("✅ WordPredictor initialized (Bug #262)")
            }
        } catch (e: Exception) {
            logE("Failed to initialize word predictor", e)
        }
    }

    /**
     * Initialize language detector (Bug #257 fix).
     */
    private fun initializeLanguageDetector() {
        try {
            languageDetector = LanguageDetector()

            // TODO: WordPredictor expects tribixbite.keyboard2.data.LanguageDetector
            // but we have tribixbite.keyboard2.LanguageDetector (different class)
            // For now, detector works standalone

            logD("✅ LanguageDetector initialized (Bug #257)")
        } catch (e: Exception) {
            logE("Failed to initialize language detector", e)
        }
    }

    /**
     * Initialize user adaptation manager (Bug #263 fix).
     */
    private fun initializeUserAdaptationManager() {
        try {
            userAdaptationManager = UserAdaptationManager.getInstance(context = this)

            // TODO: WordPredictor expects tribixbite.keyboard2.data.UserAdaptationManager
            // but we have tribixbite.keyboard2.UserAdaptationManager (different class)
            // For now, adaptation manager works standalone

            logD("✅ UserAdaptationManager initialized (Bug #263)")
        } catch (e: Exception) {
            logE("Failed to initialize user adaptation manager", e)
        }
    }

    /**
     * Initialize swipe ML trainer (Bug #274 fix).
     */
    private fun initializeSwipeMLTrainer() {
        try {
            swipeMLTrainer = tribixbite.keyboard2.ml.SwipeMLTrainer(context = this)

            // Optional: Set up training listener for UI feedback
            // swipeMLTrainer?.setTrainingListener(object : SwipeMLTrainer.TrainingListener { ... })

            logD("✅ SwipeMLTrainer initialized (Bug #274)")
        } catch (e: Exception) {
            logE("Failed to initialize swipe ML trainer", e)
        }
    }

    /**
     * Initialize neural swipe typing engine (Bug #275 dependency).
     * This is the core prediction engine required by AsyncPredictionHandler.
     *
     * Features:
     * - ONNX-based neural swipe prediction
     * - Synchronous and asynchronous prediction APIs
     * - Keyboard dimension and key position configuration
     * - Debug logging support
     */
    private fun initializeNeuralSwipeTypingEngine() {
        try {
            val cfg = config ?: throw IllegalStateException("Config not initialized")

            neuralSwipeTypingEngine = NeuralSwipeTypingEngine(
                context = this,
                config = cfg
            )

            // Initialize asynchronously in background
            serviceScope.launch {
                val success = neuralSwipeTypingEngine?.initialize() ?: false
                if (success) {
                    logD("✅ NeuralSwipeTypingEngine initialized (Bug #275 dependency)")
                    logD("   - ONNX models loaded")
                    logD("   - Ready for predictions")
                } else {
                    logE("Failed to initialize NeuralSwipeTypingEngine")
                }
            }
        } catch (e: Exception) {
            logE("Failed to initialize neural swipe typing engine", e)
        }
    }

    /**
     * Initialize async prediction handler (Bug #275 fix).
     * Handles swipe predictions asynchronously to prevent UI blocking.
     *
     * Features:
     * - Background prediction processing with Dispatchers.Default
     * - Automatic cancellation of stale predictions
     * - Request ID tracking for result validation
     * - Main thread callback delivery
     * - Graceful error handling with performance timing
     */
    private fun initializeAsyncPredictionHandler() {
        try {
            val engine = neuralSwipeTypingEngine ?: run {
                logD("⏳ AsyncPredictionHandler initialization deferred (waiting for NeuralSwipeTypingEngine)")
                return
            }

            asyncPredictionHandler = AsyncPredictionHandler(neuralEngine = engine)

            logD("✅ AsyncPredictionHandler initialized (Bug #275)")
            logD("   - Coroutine-based async processing enabled")
            logD("   - Auto-cancellation of pending predictions")
            logD("   - Stats: ${asyncPredictionHandler?.getStats()}")
        } catch (e: Exception) {
            logE("Failed to initialize async prediction handler", e)
        }
    }

    /**
     * Initialize input connection manager.
     * Provides comprehensive input connection management with app-specific behavior.
     *
     * Features:
     * - EditorInfo-based behavior customization
     * - Context extraction and management
     * - Smart composition and contextual suggestions
     * - Emoji/hashtag/mention completion support
     * - Character limit warnings
     * - Advanced formatting and grammar suggestions
     * - Code/symbol completion
     * - Null-safe input connection wrapper
     */
    private fun initializeInputConnectionManager() {
        try {
            inputConnectionManager = InputConnectionManager(service = this)

            logD("✅ InputConnectionManager initialized")
            logD("   - App-specific behavior customization enabled")
            logD("   - Context-aware suggestions supported")
            logD("   - 378 lines of advanced input management")
        } catch (e: Exception) {
            logE("Failed to initialize input connection manager", e)
        }
    }

    /**
     * Initialize personalization manager.
     * Tracks user typing patterns and learns word/bigram frequencies.
     *
     * Features:
     * - Word frequency tracking (up to 1000 words)
     * - Bigram learning for context-aware predictions (up to 500 bigrams)
     * - Automatic word usage recording
     * - Personalized frequency scoring (30% weight)
     * - Next-word predictions based on previous word
     * - Frequency decay to reduce old patterns
     * - Persistent storage in SharedPreferences
     * - ConcurrentHashMap for thread-safe access
     */
    private fun initializePersonalizationManager() {
        try {
            personalizationManager = PersonalizationManager(context = this)

            val stats = personalizationManager?.getStats()
            logD("✅ PersonalizationManager initialized")
            logD("   - Words tracked: ${stats?.totalWords ?: 0}")
            logD("   - Bigrams learned: ${stats?.totalBigrams ?: 0}")
            logD("   - Most frequent: '${stats?.mostFrequentWord ?: "none"}'")
            logD("   - 326 lines of personalized learning")
        } catch (e: Exception) {
            logE("Failed to initialize personalization manager", e)
        }
    }

    /**
     * Initialize long-press manager (Bug #327 fix - CATASTROPHIC).
     * Manages long-press behavior for keyboard keys.
     *
     * Features:
     * - Long-press detection with configurable delay (500ms default)
     * - Popup showing alternate characters (accents, symbols, etc.)
     * - Touch tracking for alternate character selection
     * - Auto-repeat for certain keys (backspace, arrows, 50ms intervals)
     * - Cancellation on touch movement (30px threshold)
     * - Vibration feedback integration
     */
    private fun initializeLongPressManager() {
        try {
            // Create stub callback for integration (full implementation comes with feature wiring)
            val callback = object : LongPressManager.Callback {
                override fun onLongPress(key: KeyValue, x: Float, y: Float): Boolean {
                    // TODO: Show popup with alternate characters
                    logD("Long press on key: ${key.displayString}")
                    return false  // Stub - will show popup in full implementation
                }

                override fun onAutoRepeat(key: KeyValue) {
                    // TODO: Handle auto-repeat (backspace, arrows)
                    logD("Auto-repeat: ${key.displayString}")
                }

                override fun onAlternateSelected(key: KeyValue, alternate: KeyValue) {
                    // TODO: Input alternate character
                    logD("Alternate selected: ${key.displayString} → ${alternate.displayString}")
                }

                override fun performVibration() {
                    // TODO: Trigger vibration through VibratorCompat
                    logD("Vibration requested")
                }
            }

            longPressManager = LongPressManager(
                callback = callback,
                longPressDelay = 500L,
                autoRepeatDelay = 400L,
                autoRepeatInterval = 50L
            )

            logD("✅ LongPressManager initialized (Bug #327)")
            logD("   - Long-press delay: 500ms")
            logD("   - Auto-repeat delay: 400ms, interval: 50ms")
            logD("   - Movement threshold: 30px")
            logD("   - 353 lines of long-press behavior")
            logD("   - ⚠️ Stub callback (full implementation pending)")
        } catch (e: Exception) {
            logE("Failed to initialize long-press manager", e)
        }
    }

    /**
     * Initialize backup/restore manager.
     * Provides JSON export/import of keyboard configuration.
     *
     * Features:
     * - JSON export of SharedPreferences
     * - Metadata tracking (version, screen size, date)
     * - Version-tolerant import with validation
     * - Special handling for JSON-string preferences
     * - Type detection (boolean, int, float, string, StringSet)
     * - Screen size mismatch detection (20% threshold)
     * - Internal preference filtering
     * - Storage Access Framework (SAF) for Android 15+
     * - Import result statistics (imported/skipped counts)
     */
    private fun initializeBackupRestoreManager() {
        try {
            backupRestoreManager = BackupRestoreManager(context = this)

            logD("✅ BackupRestoreManager initialized")
            logD("   - JSON export/import of configuration")
            logD("   - Metadata: version, screen size, date")
            logD("   - Screen size mismatch detection (20% threshold)")
            logD("   - Storage Access Framework (SAF) support")
            logD("   - 593 lines of backup/restore logic")
        } catch (e: Exception) {
            logE("Failed to initialize backup/restore manager", e)
        }
    }

    /**
     * Initialize settings sync manager (Bug #383 fix - HIGH).
     * Provides automated backup/sync for keyboard settings.
     *
     * Features:
     * - Comprehensive settings backup (all preferences, layouts, themes, clipboard)
     * - Local backup management (up to 10 backups, GZIP compressed)
     * - Device info tracking (manufacturer, model, Android version, app version)
     * - Automatic backup on settings change
     * - Cloud storage integration hooks
     * - Version tracking (SETTINGS_VERSION = 1)
     * - JSON serialization with compression
     * - Restore with device compatibility check
     */
    private fun initializeSettingsSyncManager() {
        try {
            settingsSyncManager = SettingsSyncManager(context = this)

            logD("✅ SettingsSyncManager initialized (Bug #383)")
            logD("   - Automated backup/sync for settings")
            logD("   - Local backup management (max 10, GZIP)")
            logD("   - Device info tracking")
            logD("   - Cloud storage integration hooks")
            logD("   - 338 lines of sync logic")
        } catch (e: Exception) {
            logE("Failed to initialize settings sync manager", e)
        }
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
     * Initialize gesture trail renderer for swipe visual feedback.
     * Bug #328 - HIGH: Implement missing GestureTrailRenderer
     */
    private fun initializeGestureTrailRenderer() {
        try {
            // TODO: Get enabled state from user preferences
            val trailsEnabled = true   // Default to enabled

            gestureTrailRenderer = GestureTrailRenderer(
                context = this,
                enabled = trailsEnabled
            )

            logD("✅ Gesture trail renderer initialized (enabled=$trailsEnabled)")
        } catch (e: Exception) {
            logE("Failed to initialize gesture trail renderer", e)
            // Non-fatal - keyboard can work without gesture trails
        }
    }

    /**
     * Initialize key repeat handler for auto-repeat on held keys.
     * Bug #330 - HIGH: Implement missing KeyRepeatHandler
     */
    private fun initializeKeyRepeatHandler() {
        try {
            // TODO: Get enabled state and timing from user preferences
            val repeatEnabled = true     // Default to enabled
            val initialDelay = 400L      // Default initial delay (400ms)
            val repeatInterval = 50L     // Default repeat interval (50ms)

            keyRepeatHandler = KeyRepeatHandler(
                enabled = repeatEnabled,
                initialDelay = initialDelay,
                repeatInterval = repeatInterval
            )

            logD("✅ Key repeat handler initialized (enabled=$repeatEnabled, initialDelay=${initialDelay}ms, interval=${repeatInterval}ms)")
        } catch (e: Exception) {
            logE("Failed to initialize key repeat handler", e)
            // Non-fatal - keyboard can work without key repeat
        }
    }

    /**
     * Bug #329 fix: Initialize layout switch animator
     *
     * Creates specialized animator for smooth keyboard layout transitions
     * (e.g., QWERTY → numeric, text → emoji, etc.)
     */
    private fun initializeLayoutSwitchAnimator() {
        try {
            // TODO: Get enabled state and duration from user preferences
            val animationsEnabled = true  // Default to enabled
            val duration = 250L           // Default animation duration (250ms)

            layoutSwitchAnimator = LayoutSwitchAnimator(
                context = this,
                enabled = animationsEnabled,
                defaultDuration = duration
            )

            logD("✅ Layout switch animator initialized (enabled=$animationsEnabled, duration=${duration}ms)")
        } catch (e: Exception) {
            logE("Failed to initialize layout switch animator", e)
            // Non-fatal - keyboard can work without layout animations
        }
    }

    /**
     * Bug #331 fix: Initialize one-handed mode manager
     *
     * Creates manager for one-handed keyboard mode with position/size adjustments
     * for easier single-hand typing.
     */
    private fun initializeOneHandedModeManager() {
        try {
            // TODO: Get enabled state from user preferences
            val oneHandedEnabled = false  // Default to disabled (full-width mode)

            oneHandedModeManager = OneHandedModeManager(
                context = this,
                enabled = oneHandedEnabled
            )

            logD("✅ One-handed mode manager initialized (enabled=$oneHandedEnabled)")
        } catch (e: Exception) {
            logE("Failed to initialize one-handed mode manager", e)
            // Non-fatal - keyboard can work in full-width mode
        }
    }

    /**
     * Bug #332 fix: Initialize floating keyboard manager
     *
     * Creates manager for floating/detachable keyboard that can be freely
     * positioned anywhere on screen with drag-to-move support.
     */
    private fun initializeFloatingKeyboardManager() {
        try {
            // TODO: Get enabled state from user preferences
            val floatingEnabled = false  // Default to disabled (docked mode)

            floatingKeyboardManager = FloatingKeyboardManager(
                context = this,
                enabled = floatingEnabled
            )

            logD("✅ Floating keyboard manager initialized (enabled=$floatingEnabled)")
        } catch (e: Exception) {
            logE("Failed to initialize floating keyboard manager", e)
            // Non-fatal - keyboard can work in docked mode
        }
    }

    /**
     * Bug #333 fix: Initialize split keyboard manager
     *
     * Creates manager for split keyboard mode optimized for thumb typing
     * on tablets and large phones.
     */
    private fun initializeSplitKeyboardManager() {
        try {
            // TODO: Get enabled state from user preferences
            val splitEnabled = false  // Default to disabled (unified keyboard)

            splitKeyboardManager = SplitKeyboardManager(
                context = this,
                enabled = splitEnabled
            )

            logD("✅ Split keyboard manager initialized (enabled=$splitEnabled)")
        } catch (e: Exception) {
            logE("Failed to initialize split keyboard manager", e)
            // Non-fatal - keyboard can work in unified mode
        }
    }

    /**
     * Bug #334 fix: Initialize dark mode manager
     *
     * Creates manager for dark mode theme with automatic detection
     * and multiple dark theme variants.
     */
    private fun initializeDarkModeManager() {
        try {
            darkModeManager = DarkModeManager(context = this)

            logD("✅ Dark mode manager initialized (mode=${darkModeManager?.getThemeMode()}, dark=${darkModeManager?.isDarkMode()})")
        } catch (e: Exception) {
            logE("Failed to initialize dark mode manager", e)
            // Non-fatal - keyboard can work without theme management
        }
    }

    /**
     * Bug #335 fix: Initialize adaptive layout manager
     *
     * Creates manager for intelligent layout optimization based on
     * device characteristics and screen configuration.
     */
    private fun initializeAdaptiveLayoutManager() {
        try {
            adaptiveLayoutManager = AdaptiveLayoutManager(context = this)

            val config = adaptiveLayoutManager?.getConfig()
            logD("✅ Adaptive layout manager initialized (device=${config?.deviceType}, orientation=${config?.orientation}, scale=${config?.scaleFactor})")
        } catch (e: Exception) {
            logE("Failed to initialize adaptive layout manager", e)
            // Non-fatal - keyboard can work with default layout
        }
    }

    /**
     * Bug #336 fix: Initialize typing statistics collector
     *
     * Creates collector for tracking typing metrics, speed, accuracy,
     * and usage patterns for performance insights.
     */
    private fun initializeTypingStatisticsCollector() {
        try {
            typingStatisticsCollector = TypingStatisticsCollector(context = this)

            logD("✅ Typing statistics collector initialized")
        } catch (e: Exception) {
            logE("Failed to initialize typing statistics collector", e)
            // Non-fatal - keyboard can work without statistics
        }
    }

    /**
     * Bug #337 fix: Initialize key border renderer
     *
     * Creates renderer for decorative borders, outlines, gradients, shadows,
     * and visual effects on keyboard keys.
     */
    private fun initializeKeyBorderRenderer() {
        try {
            keyBorderRenderer = KeyBorderRenderer()

            // Set screen density for dp to px conversion
            val density = resources.displayMetrics.density
            keyBorderRenderer?.setDensity(density)

            logD("✅ Key border renderer initialized")
        } catch (e: Exception) {
            logE("Failed to initialize key border renderer", e)
            // Non-fatal - keyboard can work without custom borders
        }
    }

    /**
     * Bug #346 fix: Initialize locale manager
     *
     * Creates manager for locale-specific formatting, separators, RTL support,
     * and internationalization features.
     */
    private fun initializeLocaleManager() {
        try {
            localeManager = LocaleManager(context = this)

            logD("✅ Locale manager initialized (locale: ${localeManager?.getLanguageTag()}, RTL: ${localeManager?.isRtl()})")
        } catch (e: Exception) {
            logE("Failed to initialize locale manager", e)
            // Non-fatal - keyboard can work with default locale
        }
    }

    /**
     * Bug #350 fix: Initialize character set manager
     *
     * Creates manager for character encoding detection, transliteration,
     * and character set conversion.
     */
    private fun initializeCharacterSetManager() {
        try {
            characterSetManager = CharacterSetManager()

            logD("✅ Character set manager initialized")
        } catch (e: Exception) {
            logE("Failed to initialize character set manager", e)
            // Non-fatal - keyboard can work without charset conversion
        }
    }

    /**
     * Bug #351 fix: Initialize unicode normalizer
     *
     * Creates normalizer for consistent Unicode text representation,
     * critical for autocorrect with accented characters.
     */
    private fun initializeUnicodeNormalizer() {
        try {
            unicodeNormalizer = UnicodeNormalizer()

            logD("✅ Unicode normalizer initialized")
        } catch (e: Exception) {
            logE("Failed to initialize unicode normalizer", e)
            // Non-fatal - keyboard can work without normalization
        }
    }

    /**
     * Bug #348 fix: Initialize translation engine
     *
     * Creates translation engine for inline text translation with
     * multi-provider support and language detection.
     */
    private fun initializeTranslationEngine() {
        try {
            translationEngine = TranslationEngine(context = this)

            logD("✅ Translation engine initialized")
        } catch (e: Exception) {
            logE("Failed to initialize translation engine", e)
            // Non-fatal - keyboard can work without translation
        }
    }

    /**
     * Bug #310 fix: Initialize autocorrection
     *
     * Creates autocorrection engine for intelligent text correction
     * with user dictionary and learning capabilities.
     */
    private fun initializeAutoCorrection() {
        try {
            autoCorrection = AutoCorrection(context = this)

            logD("✅ AutoCorrection initialized")
        } catch (e: Exception) {
            logE("Failed to initialize autocorrection", e)
            // Non-fatal - keyboard can work without autocorrection
        }
    }

    /**
     * Bug #311 fix: Initialize custom spell checker
     *
     * Creates custom spell checker for real-time spelling validation
     * with phonetic matching and custom dictionary support.
     */
    private fun initializeCustomSpellChecker() {
        try {
            spellChecker = SpellChecker(context = this)

            logD("✅ Custom SpellChecker initialized (Bug #311)")
        } catch (e: Exception) {
            logE("Failed to initialize custom spell checker", e)
            // Non-fatal - keyboard can work without spell checking
        }
    }

    /**
     * Bug #312 fix: Initialize frequency model
     *
     * Creates frequency model for n-gram tracking, word frequency ranking,
     * and context-aware prediction with learning capabilities.
     */
    private fun initializeFrequencyModel() {
        try {
            frequencyModel = FrequencyModel(context = this)

            logD("✅ FrequencyModel initialized (Bug #312)")
        } catch (e: Exception) {
            logE("Failed to initialize frequency model", e)
            // Non-fatal - keyboard can work without frequency tracking
        }
    }

    /**
     * Bug #313 fix: Initialize text prediction engine
     *
     * Creates prediction engine that coordinates all prediction sources
     * (frequency model, autocorrection, spell check) for ranked suggestions.
     */
    private fun initializeTextPredictionEngine() {
        try {
            textPredictionEngine = TextPredictionEngine(
                context = this,
                frequencyModel = frequencyModel,
                autoCorrection = autoCorrection,
                spellChecker = spellChecker
            )

            logD("✅ TextPredictionEngine initialized (Bug #313)")
        } catch (e: Exception) {
            logE("Failed to initialize text prediction engine", e)
            // Non-fatal - keyboard can work without advanced predictions
        }
    }

    /**
     * Bug #314 fix: Initialize completion engine
     *
     * Creates completion engine for intelligent text completion with
     * templates, snippets, and abbreviation expansion.
     */
    private fun initializeCompletionEngine() {
        try {
            completionEngine = CompletionEngine(context = this)

            logD("✅ CompletionEngine initialized (Bug #314)")
        } catch (e: Exception) {
            logE("Failed to initialize completion engine", e)
            // Non-fatal - keyboard can work without completions
        }
    }

    /**
     * Bug #315 fix: Initialize context analyzer
     *
     * Creates context analyzer for intelligent text analysis including
     * sentence structure, writing style, and topic detection.
     */
    private fun initializeContextAnalyzer() {
        try {
            contextAnalyzer = ContextAnalyzer(context = this)

            logD("✅ ContextAnalyzer initialized (Bug #315)")
        } catch (e: Exception) {
            logE("Failed to initialize context analyzer", e)
            // Non-fatal - keyboard can work without context analysis
        }
    }

    /**
     * Bug #317 fix: Initialize grammar checker
     *
     * Creates grammar checker for rule-based grammar validation including
     * subject-verb agreement, article usage, and common error detection.
     */
    private fun initializeGrammarChecker() {
        try {
            grammarChecker = GrammarChecker(context = this)

            logD("✅ GrammarChecker initialized (Bug #317)")
        } catch (e: Exception) {
            logE("Failed to initialize grammar checker", e)
            // Non-fatal - keyboard can work without grammar checking
        }
    }

    /**
     * Bug #320 fix: Initialize undo/redo manager
     *
     * Creates undo/redo manager for multi-level text operation tracking
     * with operation batching and cursor position restoration.
     */
    private fun initializeUndoRedoManager() {
        try {
            undoRedoManager = UndoRedoManager(context = this)

            logD("✅ UndoRedoManager initialized (Bug #320)")
        } catch (e: Exception) {
            logE("Failed to initialize undo/redo manager", e)
            // Non-fatal - keyboard can work without undo/redo
        }
    }

    /**
     * Initialize selection manager (Bug #321 fix)
     */
    private fun initializeSelectionManager() {
        try {
            selectionManager = SelectionManager(context = this)

            logD("✅ SelectionManager initialized (Bug #321)")
        } catch (e: Exception) {
            logE("Failed to initialize selection manager", e)
            // Non-fatal - keyboard can work without selection manager
        }
    }

    /**
     * Initialize macro expander (Bug #354 fix)
     */
    private fun initializeMacroExpander() {
        try {
            macroExpander = MacroExpander(context = this)

            logD("✅ MacroExpander initialized (Bug #354)")
        } catch (e: Exception) {
            logE("Failed to initialize macro expander", e)
            // Non-fatal - keyboard can work without macro expander
        }
    }

    /**
     * Initialize shortcut manager (Bug #355 fix)
     */
    private fun initializeShortcutManager() {
        try {
            shortcutManager = ShortcutManager(context = this)

            logD("✅ ShortcutManager initialized (Bug #355)")
        } catch (e: Exception) {
            logE("Failed to initialize shortcut manager", e)
            // Non-fatal - keyboard can work without shortcut manager
        }
    }

    /**
     * Initialize gesture typing customizer (Bug #356 fix)
     */
    private fun initializeGestureTypingCustomizer() {
        try {
            gestureTypingCustomizer = GestureTypingCustomizer(context = this)

            logD("✅ GestureTypingCustomizer initialized (Bug #356)")
        } catch (e: Exception) {
            logE("Failed to initialize gesture typing customizer", e)
            // Non-fatal - keyboard can work without gesture typing customizer
        }
    }

    /**
     * Initialize continuous input manager (Bug #357 fix)
     */
    private fun initializeContinuousInputManager() {
        try {
            continuousInputManager = ContinuousInputManager(context = this)

            logD("✅ ContinuousInputManager initialized (Bug #357)")
        } catch (e: Exception) {
            logE("Failed to initialize continuous input manager", e)
            // Non-fatal - keyboard can work without continuous input manager
        }
    }

    /**
     * Initialize handwriting recognizer for character drawing input.
     * Bug #352 fix - Essential for CJK languages (1.3B users).
     */
    private fun initializeHandwritingRecognizer() {
        try {
            handwritingRecognizer = HandwritingRecognizer(context = this)

            logD("✅ HandwritingRecognizer initialized (Bug #352)")
        } catch (e: Exception) {
            logE("Failed to initialize handwriting recognizer", e)
            // Non-fatal - keyboard can work without handwriting recognition
        }
    }

    /**
     * Initialize voice typing engine for speech-to-text.
     * Bug #353 fix - Provides actual voice recognition within keyboard.
     */
    private fun initializeVoiceTypingEngine() {
        try {
            voiceTypingEngine = VoiceTypingEngine(context = this)

            logD("✅ VoiceTypingEngine initialized (Bug #353)")
        } catch (e: Exception) {
            logE("Failed to initialize voice typing engine", e)
            // Non-fatal - keyboard can work without voice typing
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