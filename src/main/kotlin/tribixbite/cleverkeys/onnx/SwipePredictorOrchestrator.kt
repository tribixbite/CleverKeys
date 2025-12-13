package tribixbite.cleverkeys.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.PointF
import android.util.Log
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.KeyboardGrid
import tribixbite.cleverkeys.ModelVersionManager
import tribixbite.cleverkeys.NeuralModelMetadata
import tribixbite.cleverkeys.OptimizedVocabulary
import tribixbite.cleverkeys.SwipeInput
import tribixbite.cleverkeys.SwipeResampler
import tribixbite.cleverkeys.SwipeTokenizer
import tribixbite.cleverkeys.SwipeTrajectoryProcessor
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
    private var tensorFactory: TensorFactory? = null
    private var encoderWrapper: EncoderWrapper? = null
    private var decoderWrapper: DecoderWrapper? = null
    
    // State
    @Volatile private var isInitialized = false
    @Volatile private var isModelLoaded = false
    private var forceCpuFallback = false
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    
    // Configuration - defaults MUST match Defaults in Config.kt
    private var config: Config? = null
    private var beamWidth = Defaults.NEURAL_BEAM_WIDTH
    private var maxLength = Defaults.NEURAL_MAX_LENGTH
    private var confidenceThreshold = Defaults.NEURAL_CONFIDENCE_THRESHOLD
    private var beamAlpha = Defaults.NEURAL_BEAM_ALPHA
    private var beamPruneConfidence = Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE
    private var beamScoreGap = Defaults.NEURAL_BEAM_SCORE_GAP
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
        if (isInitialized) return isModelLoaded

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
        } finally {
            isInitialized = true
        }

        return isModelLoaded
    }

    fun predict(input: SwipeInput): PredictionPostProcessor.Result {
        if (!isModelLoaded) return PredictionPostProcessor.Result(emptyList(), emptyList())
        
        try {
            // Feature Extraction
            val features = trajectoryProcessor.extractFeatures(input, maxSequenceLength)
            
            // Encoder
            val encoderResult = encoderWrapper!!.encode(features)
            val memory = encoderResult.memory
            
            // Decoder (Search)
            val candidates = if (config?.neural_greedy_search == true) {
                val engine = GreedySearchEngine(decoderSession!!, ortEnvironment, tokenizer, maxLength)
                val results = engine.search(memory, features.actualLength)
                results.map { PredictionPostProcessor.Candidate(it.word, it.confidence) }
            } else {
                val engine = BeamSearchEngine(
                    decoderSession!!, ortEnvironment, tokenizer, 
                    vocabulary.getVocabularyTrie(), beamWidth, maxLength, // Fixed: used getVocabularyTrie()
                    confidenceThreshold, beamAlpha, beamPruneConfidence, beamScoreGap
                )
                val results = engine.search(memory, features.actualLength, batchBeams)
                results.map { PredictionPostProcessor.Candidate(it.word, it.confidence) }
            }
            
            // Post-processing
            val postProcessor = PredictionPostProcessor(
                vocabulary, confidenceThreshold, showRawOutput
            )
            
            return postProcessor.process(candidates, input, config?.swipe_show_raw_beam_predictions ?: false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
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
    fun reloadVocabulary() = vocabulary.reloadCustomAndDisabledWords()
    
    fun setDebugLogger(logger: Any?) {
        // TODO: Implement proper debug logger interface if needed
        // For now, we rely on Logcat and enableVerboseLogging flag
    }

    fun cleanup() {
        encoderSession?.close()
        decoderSession?.close()
        isModelLoaded = false
    }
}
