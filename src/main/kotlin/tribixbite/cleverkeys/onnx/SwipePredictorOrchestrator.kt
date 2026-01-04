package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.PointF
import android.util.Log
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.DirectBootAwarePreferences
import tribixbite.cleverkeys.KeyboardGrid
import tribixbite.cleverkeys.NeuralSwipeTypingEngine
import tribixbite.cleverkeys.ModelVersionManager
import tribixbite.cleverkeys.NeuralModelMetadata
import tribixbite.cleverkeys.OptimizedVocabulary
import tribixbite.cleverkeys.SwipeInput
import tribixbite.cleverkeys.SwipeResampler
import tribixbite.cleverkeys.SwipeTokenizer
import tribixbite.cleverkeys.SwipeTrajectoryProcessor
import tribixbite.cleverkeys.UnigramLanguageDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Orchestrator for neural swipe prediction.
 * Replaces the monolithic OnnxSwipePredictor Java class.
 */
class SwipePredictorOrchestrator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SwipePredictorOrchestrator"
        private const val TRAJECTORY_FEATURES = 6
        private val instanceLock = Any()
        @Volatile private var instance: SwipePredictorOrchestrator? = null

        @JvmStatic
        fun getInstance(context: Context): SwipePredictorOrchestrator {
            return instance ?: synchronized(instanceLock) {
                instance ?: SwipePredictorOrchestrator(context).also { instance = it }
            }
        }
    }

    // Components
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer = SwipeTokenizer()
    private val vocabulary = OptimizedVocabulary(context)
    private val modelLoader = ModelLoader(context, ortEnvironment)
    private val trajectoryProcessor = SwipeTrajectoryProcessor() // Move here
    private val versionManager = ModelVersionManager.getInstance(context)
    private val languageDetector = UnigramLanguageDetector(context)
    private var tensorFactory: TensorFactory? = null
    private var encoderWrapper: EncoderWrapper? = null
    private var decoderWrapper: DecoderWrapper? = null
    
    // State
    @Volatile private var isInitialized = false
    @Volatile private var isModelLoaded = false
    private var forceCpuFallback = false
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    // Debug logging callback (sends to SwipeDebugActivity)
    // IMPORTANT: debugLogger is set, but debugModeActive gates expensive string building
    private var debugLogger: ((String) -> Unit)? = null
    @Volatile private var debugModeActive = false
    
    // Configuration - defaults MUST match Defaults in Config.kt
    private var config: Config? = null
    private var beamWidth = Defaults.NEURAL_BEAM_WIDTH
    private var maxLength = Defaults.NEURAL_MAX_LENGTH
    private var confidenceThreshold = Defaults.NEURAL_CONFIDENCE_THRESHOLD
    private var beamAlpha = Defaults.NEURAL_BEAM_ALPHA
    private var beamPruneConfidence = Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE
    private var beamScoreGap = Defaults.NEURAL_BEAM_SCORE_GAP
    private var adaptiveWidthStep = Defaults.NEURAL_ADAPTIVE_WIDTH_STEP
    private var scoreGapStep = Defaults.NEURAL_SCORE_GAP_STEP
    private var temperature = Defaults.NEURAL_TEMPERATURE
    private var maxSequenceLength = 250
    private var enableVerboseLogging = false
    private var showRawOutput = false
    private var batchBeams = false
    
    // Threading
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ONNX-Inference").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    fun setConfig(newConfig: Config?) {
        this.config = newConfig
        newConfig?.let {
            // Use Defaults.* for fallbacks to ensure consistency across all settings screens
            beamWidth = if (it.neural_beam_width != 0) it.neural_beam_width else Defaults.NEURAL_BEAM_WIDTH
            maxLength = if (it.neural_max_length != 0) it.neural_max_length else Defaults.NEURAL_MAX_LENGTH
            confidenceThreshold = if (it.neural_confidence_threshold != 0f) it.neural_confidence_threshold else Defaults.NEURAL_CONFIDENCE_THRESHOLD
            beamAlpha = it.neural_beam_alpha
            beamPruneConfidence = it.neural_beam_prune_confidence
            beamScoreGap = it.neural_beam_score_gap
            adaptiveWidthStep = if (it.neural_adaptive_width_step > 0) it.neural_adaptive_width_step else Defaults.NEURAL_ADAPTIVE_WIDTH_STEP
            scoreGapStep = if (it.neural_score_gap_step > 0) it.neural_score_gap_step else Defaults.NEURAL_SCORE_GAP_STEP
            temperature = if (it.neural_temperature > 0f) it.neural_temperature else Defaults.NEURAL_TEMPERATURE
            enableVerboseLogging = it.swipe_debug_detailed_logging
            showRawOutput = it.swipe_debug_show_raw_output
            batchBeams = it.neural_batch_beams
            
            if (it.neural_user_max_seq_length > 0) {
                maxSequenceLength = it.neural_user_max_seq_length
            }
            
            it.neural_resampling_mode?.let { mode ->
                trajectoryProcessor.setResamplingMode(SwipeResampler.parseMode(mode))
            }
            
            vocabulary.updateConfig(it)
        }
    }

    fun initializeAsync() {
        if (!isInitialized) {
            executor.submit { initialize() }
        }
    }

    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, returning isModelLoaded=$isModelLoaded")
            return isModelLoaded
        }

        Log.d(TAG, "Starting ONNX model initialization...")
        val startTime = System.currentTimeMillis()
        val versionId = "builtin_v2_android" // Unique ID for builtin models

        // Check if rollback is needed before attempting load
        val rollbackDecision = versionManager.shouldRollback()
        if (rollbackDecision.shouldRollback) {
            Log.w(TAG, "Rollback recommended: ${rollbackDecision.reason}")
            if (versionManager.rollback()) {
                Log.i(TAG, "Rolled back to previous version successfully")
                // The rollback changed the version, but we'll still try loading builtin
                // In a full implementation, this would load the previous version from storage
            }
        }

        try {
            Log.d(TAG, "Initializing SwipePredictorOrchestrator...")

            // Load Tokenizer & Vocabulary
            tokenizer.loadFromAssets(context)
            if (!vocabulary.isLoaded()) vocabulary.loadVocabulary()

            // Load primary language dictionary if not English
            loadPrimaryDictionaryFromPrefs()

            // Load secondary language dictionary if configured
            loadSecondaryDictionaryFromPrefs()

            // Initialize language detector with available languages
            initializeLanguageDetector()

            // Load Models
            val encoderPath = "models/swipe_encoder_android.onnx"
            val decoderPath = "models/swipe_decoder_android.onnx"

            // Register this version attempt
            versionManager.registerVersion(
                versionId = versionId,
                versionName = "Built-in v2 Android",
                encoderPath = "assets://$encoderPath",
                decoderPath = "assets://$decoderPath",
                isBuiltin = true
            )

            // Use SessionConfigurator logic inside ModelLoader
            val encResult = modelLoader.loadModel(encoderPath, "Encoder", !forceCpuFallback)
            val decResult = modelLoader.loadModel(decoderPath, "Decoder", !forceCpuFallback)

            encoderSession = encResult.session
            decoderSession = decResult.session

            // Initialize Wrappers
            tensorFactory = TensorFactory(ortEnvironment, maxSequenceLength, TRAJECTORY_FEATURES)
            encoderWrapper = EncoderWrapper(encoderSession!!, tensorFactory!!, ortEnvironment, enableVerboseLogging)
            // Check broadcast support (simplified)
            val broadcastEnabled = true // Assuming v2 models
            decoderWrapper = DecoderWrapper(decoderSession!!, tensorFactory!!, ortEnvironment, broadcastEnabled, enableVerboseLogging)

            isModelLoaded = true

            // Record success in version manager
            versionManager.recordSuccess(versionId)

            // Record model metadata for versioning and monitoring
            val loadDuration = System.currentTimeMillis() - startTime
            try {
                val metadata = NeuralModelMetadata.getInstance(context)
                metadata.recordModelLoad(
                    modelType = NeuralModelMetadata.MODEL_TYPE_BUILTIN,
                    encoderPath = "assets://$encoderPath",
                    decoderPath = "assets://$decoderPath",
                    encoderSize = encResult.modelSizeBytes,
                    decoderSize = decResult.modelSizeBytes,
                    loadDuration = loadDuration
                )
                Log.d(TAG, "Model metadata recorded (load time: ${loadDuration}ms)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record model metadata", e)
            }

            Log.i(TAG, "✅ Initialization complete (${loadDuration}ms)")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            isModelLoaded = false

            // Record failure in version manager
            versionManager.recordFailure(versionId, e.message ?: "Unknown error")

            // Check if we should trigger rollback
            val postFailureDecision = versionManager.shouldRollback()
            if (postFailureDecision.shouldRollback) {
                Log.w(TAG, "⚠️ Rollback triggered after failure: ${postFailureDecision.reason}")
                // In a production system, this would attempt to reload with the previous version
                // For now, we just log the recommendation
            }
        }

        // FIX: Only mark as initialized if loading succeeded
        // This allows retry on subsequent calls if initialization failed
        // (e.g., during Direct Boot when storage may be restricted)
        isInitialized = isModelLoaded

        return isModelLoaded
    }

    fun predict(input: SwipeInput): PredictionPostProcessor.Result {
        if (!isModelLoaded) return PredictionPostProcessor.Result(emptyList(), emptyList())

        val startTime = System.currentTimeMillis()

        try {
            // Log touch trace (gated behind debugModeActive to avoid expensive string building)
            if (debugModeActive && input.coordinates.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("\n📍 TOUCH TRACE (${input.coordinates.size} points):\n")
                sb.append("─────────────────────────────────────────────────────────\n")

                // Show first 5 and last 5 points
                val coords = input.coordinates
                val showCount = minOf(5, coords.size)
                for (i in 0 until showCount) {
                    val p = coords[i]
                    sb.append("  [$i] (${String.format("%.1f", p.x)}, ${String.format("%.1f", p.y)})\n")
                }
                if (coords.size > 10) {
                    sb.append("  ... ${coords.size - 10} more points ...\n")
                    for (i in coords.size - 5 until coords.size) {
                        val p = coords[i]
                        sb.append("  [$i] (${String.format("%.1f", p.x)}, ${String.format("%.1f", p.y)})\n")
                    }
                }
                logDebug(sb.toString())
            }

            // Feature Extraction
            val featureStartTime = System.currentTimeMillis()
            val features = trajectoryProcessor.extractFeatures(input, maxSequenceLength)
            val featureTime = System.currentTimeMillis() - featureStartTime

            // Log detected key sequence with start/end analysis
            if (debugModeActive && features.nearestKeys.isNotEmpty()) {
                val keySeq = StringBuilder()
                var lastKey = -1
                var firstKey: Char? = null
                var lastKeyChar: Char? = null

                for (tokenIdx in features.nearestKeys) {
                    if (tokenIdx != lastKey && tokenIdx in 4..29) {
                        val c = 'a' + (tokenIdx - 4)
                        keySeq.append(c)
                        if (firstKey == null) firstKey = c
                        lastKeyChar = c
                        lastKey = tokenIdx
                    }
                }

                // Count out-of-bounds points
                var outOfBoundsCount = 0
                for (coord in input.coordinates) {
                    if (coord.y < 0 || coord.y > trajectoryProcessor.keyboardHeight) {
                        outOfBoundsCount++
                    }
                }

                val sb = StringBuilder()
                sb.append("\n🎯 DETECTED KEY SEQUENCE: \"$keySeq\"\n")
                sb.append("   📍 Start key: '$firstKey' | End key: '$lastKeyChar'\n")
                sb.append("   📊 ${features.actualLength} points → ${keySeq.length} unique keys\n")
                if (outOfBoundsCount > 0) {
                    sb.append("   ⚠️ WARNING: $outOfBoundsCount points OUT OF BOUNDS (Y < 0 or Y > ${trajectoryProcessor.keyboardHeight.toInt()})\n")
                }
                sb.append("   ⏱️ Feature extraction: ${featureTime}ms\n")
                logDebug(sb.toString())

                // Log tensor shapes and padding status
                sb.clear()
                sb.append("\n📊 TENSOR SHAPES (NN INPUT):\n")
                sb.append("─────────────────────────────────────────────────────────\n")
                sb.append("   trajectory_features: [1, $maxSequenceLength, 6]\n")
                sb.append("   nearest_keys: [1, $maxSequenceLength]\n")
                sb.append("   actual_length: ${features.actualLength}\n")
                sb.append("   padding: ${maxSequenceLength - features.actualLength} zero-padded positions\n")
                sb.append("─────────────────────────────────────────────────────────\n")
                logDebug(sb.toString())

                // Log sample feature values (first, mid, last of actual data)
                if (features.normalizedPoints.isNotEmpty()) {
                    sb.clear()
                    sb.append("\n🔬 FEATURE VALUES (x, y, vx, vy, ax, ay):\n")
                    sb.append("─────────────────────────────────────────────────────────\n")
                    val sampleIndices = listOf(0, features.actualLength / 2, features.actualLength - 1)
                    for (idx in sampleIndices) {
                        if (idx < features.normalizedPoints.size) {
                            val p = features.normalizedPoints[idx]
                            val label = when (idx) {
                                0 -> "FIRST"
                                features.actualLength - 1 -> "LAST"
                                else -> "MID"
                            }
                            sb.append("   $label[$idx]: pos=(${String.format("%.3f", p.x)}, ${String.format("%.3f", p.y)})")
                            sb.append(" vel=(${String.format("%.4f", p.vx)}, ${String.format("%.4f", p.vy)})")
                            sb.append(" acc=(${String.format("%.4f", p.ax)}, ${String.format("%.4f", p.ay)})\n")
                        }
                    }
                    // Also show first padded position to verify padding
                    if (features.actualLength < features.normalizedPoints.size) {
                        val padIdx = features.actualLength
                        val p = features.normalizedPoints[padIdx]
                        sb.append("   PAD[$padIdx]: pos=(${String.format("%.3f", p.x)}, ${String.format("%.3f", p.y)}) (should be 0.0)\n")
                    }
                    sb.append("─────────────────────────────────────────────────────────\n")
                    logDebug(sb.toString())
                }
            }

            // Encoder
            val encoderStartTime = System.currentTimeMillis()
            val encoderResult = encoderWrapper!!.encode(features)
            val memory = encoderResult.memory
            val encoderTime = System.currentTimeMillis() - encoderStartTime

            if (debugModeActive) {
                logDebug("⚡ Encoder: ${encoderTime}ms (seq_len=${features.actualLength})\n")
            }

            // Decoder (Search)
            val decoderStartTime = System.currentTimeMillis()
            val searchMode = if (config?.neural_greedy_search == true) "greedy" else "beam(width=$beamWidth)"

            // Only pass debugLogger to child components when debug mode is active
            val activeLogger = if (debugModeActive) debugLogger else null

            val candidates = if (config?.neural_greedy_search == true) {
                val engine = GreedySearchEngine(decoderSession!!, ortEnvironment, tokenizer, maxLength, activeLogger)
                val results = engine.search(memory, features.actualLength)
                results.map { PredictionPostProcessor.Candidate(it.word, it.confidence) }
            } else {
                val engine = BeamSearchEngine(
                    decoderSession!!, ortEnvironment, tokenizer,
                    vocabulary.getVocabularyTrie(), beamWidth, maxLength,
                    confidenceThreshold, beamAlpha, beamPruneConfidence, beamScoreGap,
                    adaptiveWidthStep, scoreGapStep, temperature, activeLogger
                )
                val results = engine.search(memory, features.actualLength, batchBeams)
                results.map { PredictionPostProcessor.Candidate(it.word, it.confidence) }
            }

            val decoderTime = System.currentTimeMillis() - decoderStartTime

            if (debugModeActive) {
                logDebug("⚡ Decoder ($searchMode): ${decoderTime}ms → ${candidates.size} candidates\n")

                // Log RAW beam search output before vocabulary filtering
                val sb = StringBuilder()
                sb.append("\n🔬 RAW BEAM SEARCH OUTPUT (before vocab filtering):\n")
                sb.append("─────────────────────────────────────────────────────────\n")
                candidates.take(15).forEachIndexed { idx, c ->
                    sb.append("  #${idx + 1}: \"${c.word}\" (raw_conf=${String.format("%.4f", c.confidence)})\n")
                }
                if (candidates.size > 15) {
                    sb.append("  ... and ${candidates.size - 15} more\n")
                }
                sb.append("─────────────────────────────────────────────────────────\n")
                logDebug(sb.toString())
            }

            // Post-processing
            val postStartTime = System.currentTimeMillis()
            val postProcessor = PredictionPostProcessor(
                vocabulary, confidenceThreshold, showRawOutput, activeLogger
            )

            val result = postProcessor.process(candidates, input, config?.swipe_show_raw_beam_predictions ?: false)
            val postTime = System.currentTimeMillis() - postStartTime

            val totalTime = System.currentTimeMillis() - startTime

            if (debugModeActive) {
                logDebug("⚡ Post-processing: ${postTime}ms\n")
                logDebug("─────────────────────────────────────────────────────────\n")
                logDebug("⏱️ TOTAL INFERENCE: ${totalTime}ms (feature=${featureTime}ms, encoder=${encoderTime}ms, decoder=${decoderTime}ms, post=${postTime}ms)\n\n")
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            logDebug("❌ Prediction failed: ${e.message}\n")
            return PredictionPostProcessor.Result(emptyList(), emptyList())
        }
    }
    
    // Pass-through methods for compatibility
    fun isAvailable() = isModelLoaded
    fun setKeyboardDimensions(w: Float, h: Float) {
        trajectoryProcessor.keyboardWidth = w
        trajectoryProcessor.keyboardHeight = h
    }
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>?) {
        if (keyPositions != null) {
            val width = trajectoryProcessor.keyboardWidth
            val height = trajectoryProcessor.keyboardHeight
            trajectoryProcessor.setKeyboardLayout(keyPositions, width, height)
        }
    }
    fun setQwertyAreaBounds(top: Float, height: Float) = trajectoryProcessor.setQwertyAreaBounds(top, height)
    fun setTouchYOffset(offset: Float) = trajectoryProcessor.setTouchYOffset(offset)
    fun setMargins(left: Float, right: Float) = trajectoryProcessor.setMargins(left, right)
    fun reloadVocabulary() = vocabulary.reloadCustomAndDisabledWords()

    /**
     * Load primary dictionary based on user preference.
     * Called during initialization and when settings change.
     * For non-English primary languages, loads V2 binary dictionary for accent recovery.
     */
    private fun loadPrimaryDictionaryFromPrefs() {
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
            val primaryLang = prefs.getString("pref_primary_language", "en") ?: "en"
            val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"

            // Configure English fallback based on language settings
            // English fallback is DISABLED when Primary=French, Secondary=None (French only)
            // English fallback is ENABLED when Primary=French, Secondary=English (French + English)
            vocabulary.setPrimaryLanguageConfig(primaryLang, secondaryLang)

            if (multiLangEnabled && primaryLang != "en") {
                val loaded = vocabulary.loadPrimaryDictionary(primaryLang)
                if (loaded) {
                    Log.i(TAG, "Primary dictionary loaded: $primaryLang (English fallback: ${primaryLang == "en" || secondaryLang == "en"})")
                } else {
                    Log.w(TAG, "Failed to load primary dictionary: $primaryLang")
                }
            } else {
                // English or multilang disabled - no accent normalization needed
                vocabulary.unloadPrimaryDictionary()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading primary dictionary from prefs", e)
        }
    }

    /**
     * Reload primary dictionary (called when settings change).
     */
    fun reloadPrimaryDictionary() {
        loadPrimaryDictionaryFromPrefs()
    }

    /**
     * Check if primary dictionary is active (for non-English languages).
     */
    fun hasPrimaryDictionary(): Boolean = vocabulary.hasPrimaryDictionary()

    /**
     * Load secondary dictionary based on user preference.
     * Called during initialization and when settings change.
     */
    private fun loadSecondaryDictionaryFromPrefs() {
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
            val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"
            val autoSwitchEnabled = prefs.getBoolean("pref_auto_detect_language", false)
            val autoSwitchThreshold = prefs.getFloat("pref_language_detection_sensitivity", 0.6f)

            if (multiLangEnabled && secondaryLang != "none") {
                val loaded = vocabulary.loadSecondaryDictionary(secondaryLang)
                if (loaded) {
                    Log.i(TAG, "Secondary dictionary loaded: $secondaryLang")
                } else {
                    Log.w(TAG, "Failed to load secondary dictionary: $secondaryLang")
                }

                // Configure auto-switch
                vocabulary.setAutoSwitchConfig(autoSwitchEnabled, autoSwitchThreshold, secondaryLang)
            } else {
                // Unload any existing secondary dictionary
                vocabulary.unloadSecondaryDictionary()
                vocabulary.setAutoSwitchConfig(false, 0.6f, "none")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading secondary dictionary from prefs", e)
        }
    }

    /**
     * Reload secondary dictionary (called when settings change).
     */
    fun reloadSecondaryDictionary() {
        loadSecondaryDictionaryFromPrefs()
    }

    /**
     * Check if secondary dictionary is active.
     */
    fun hasSecondaryDictionary(): Boolean = vocabulary.hasSecondaryDictionary()

    /**
     * Initialize language detector with configured languages.
     * Loads unigram frequency lists for English and any secondary language.
     */
    private fun initializeLanguageDetector() {
        try {
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val multiLangEnabled = prefs.getBoolean("pref_enable_multilang", false)
            val secondaryLang = prefs.getString("pref_secondary_language", "none") ?: "none"

            // Always load English as primary
            val languagesToLoad = mutableListOf("en")

            // Add secondary language if configured
            if (multiLangEnabled && secondaryLang != "none") {
                languagesToLoad.add(secondaryLang)
            }

            val loaded = languageDetector.loadLanguages(languagesToLoad)
            Log.i(TAG, "Language detector initialized: $loaded/${languagesToLoad.size} languages")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing language detector", e)
        }
    }

    /**
     * Track a committed word for language detection.
     * Call this when the user selects a suggestion or commits a word via space/punctuation.
     * The language detector uses this to build a profile of the user's typing language.
     *
     * @param word The word that was committed
     */
    fun trackCommittedWord(word: String) {
        languageDetector.addWord(word)

        // Update vocabulary language multiplier for auto-switch
        val scores = languageDetector.getLanguageScores()
        vocabulary.updateLanguageMultiplier(scores)

        // Log language scores in debug mode
        if (debugModeActive) {
            val primary = languageDetector.getPrimaryLanguage()
            logDebug("🌍 Language detection: primary=$primary, scores=$scores\n")
        }
    }

    /**
     * Get current language detection scores.
     *
     * @return Map of language code → confidence score (0.0-1.0)
     */
    fun getLanguageScores(): Map<String, Float> = languageDetector.getLanguageScores()

    /**
     * Get the currently detected primary language.
     *
     * @return Language code (e.g., "en", "es") or null if no languages loaded
     */
    fun getDetectedLanguage(): String? = languageDetector.getPrimaryLanguage()

    /**
     * Clear language detection history.
     * Call this when starting a new text field or conversation context.
     */
    fun clearLanguageHistory() {
        languageDetector.clearHistory()
    }

    fun setDebugLogger(logger: Any?) {
        // Accept NeuralSwipeTypingEngine.DebugLogger and convert to lambda
        @Suppress("UNCHECKED_CAST")
        debugLogger = when (logger) {
            is NeuralSwipeTypingEngine.DebugLogger -> { msg: String -> logger.log(msg) }
            is Function1<*, *> -> logger as? ((String) -> Unit)
            else -> null
        }
        // Propagate to trajectory processor
        trajectoryProcessor.setDebugLogger(debugLogger)
    }

    /**
     * Set debug mode active state. When false, all debug logging is skipped
     * to avoid expensive string building during normal inference.
     */
    fun setDebugModeActive(active: Boolean) {
        debugModeActive = active
        // Propagate to trajectory processor
        trajectoryProcessor.setDebugModeActive(active)
    }

    private fun logDebug(message: String) {
        debugLogger?.invoke(message)
        if (enableVerboseLogging) {
            Log.d(TAG, message)
        }
    }

    fun cleanup() {
        encoderSession?.close()
        decoderSession?.close()
        isModelLoaded = false
        isInitialized = false // Allow re-initialization after cleanup
        Log.d(TAG, "Cleanup complete - ready for re-initialization")
    }
}
