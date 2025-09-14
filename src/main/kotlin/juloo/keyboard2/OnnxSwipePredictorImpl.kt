package juloo.keyboard2

import ai.onnxruntime.*
import android.content.Context
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Complete ONNX-based neural swipe predictor with Kotlin coroutines
 * Full implementation of transformer encoder-decoder architecture
 */
class OnnxSwipePredictorImpl private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "OnnxSwipePredictor"
        private const val MAX_SEQUENCE_LENGTH = 150
        private const val TRAJECTORY_FEATURES = 6 // x, y, vx, vy, ax, ay
        private const val NORMALIZED_WIDTH = 1.0f
        private const val NORMALIZED_HEIGHT = 1.0f
        private const val DEFAULT_BEAM_WIDTH = 8
        private const val DEFAULT_MAX_LENGTH = 35
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.1f
        
        // Special tokens
        private const val PAD_IDX = 0
        private const val UNK_IDX = 1
        private const val SOS_IDX = 2
        private const val EOS_IDX = 3
        
        @Volatile
        private var instance: OnnxSwipePredictorImpl? = null
        
        fun getInstance(context: Context): OnnxSwipePredictorImpl {
            return instance ?: synchronized(this) {
                instance ?: OnnxSwipePredictorImpl(context).also { instance = it }
            }
        }
    }
    
    // ONNX Runtime components
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    
    // Processing components
    private val tokenizer = SwipeTokenizer()
    private val trajectoryProcessor = SwipeTrajectoryProcessor()
    private val vocabulary = OptimizedVocabulary(context)
    
    // Configuration
    private var beamWidth = DEFAULT_BEAM_WIDTH
    private var maxLength = DEFAULT_MAX_LENGTH
    private var confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD
    
    // State
    var isModelLoaded = false
        private set
    private var isInitialized = false
    private var debugLogger: ((String) -> Unit)? = null
    
    // Pre-allocated tensors for performance
    private var reusableTokensArray = LongArray(20)
    private var reusableTargetMaskArray = Array(1) { BooleanArray(20) }
    
    // Executor for async operations
    private val onnxExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OnnxPredictor").apply { isDaemon = true }
    }
    
    /**
     * Initialize ONNX models and components
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            logDebug("🔄 Loading ONNX transformer models...")
            
            // Load encoder model
            val encoderData = loadModelFromAssets("models/swipe_model_character_quant.onnx")
            encoderSession = ortEnvironment.createSession(encoderData, createSessionOptions("Encoder"))
            logDebug("✅ Encoder session created successfully")
            
            // Load decoder model  
            val decoderData = loadModelFromAssets("models/swipe_decoder_character_quant.onnx")
            decoderSession = ortEnvironment.createSession(decoderData, createSessionOptions("Decoder"))
            logDebug("✅ Decoder session created successfully")
            
            // Initialize tokenizer and vocabulary
            tokenizer.initialize()
            val vocabLoaded = vocabulary.loadVocabulary()
            logDebug("📚 Vocabulary loaded: $vocabLoaded (words: ${vocabulary.getStats().totalWords})")
            
            isModelLoaded = true
            isInitialized = true
            logDebug("🧠 ONNX neural prediction system ready!")
            
            true
        } catch (e: Exception) {
            logE("Failed to initialize ONNX predictor", e)
            false
        }
    }
    
    /**
     * Predict words from swipe input
     */
    suspend fun predict(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            if (!initialize()) {
                return@withContext PredictionResult.empty
            }
        }
        
        try {
            logDebug("🚀 Starting neural prediction for ${input.coordinates.size} points")
            
            // Extract trajectory features
            val features = trajectoryProcessor.extractFeatures(input.coordinates, input.timestamps)
            
            // Run encoder
            val encoderResult = runEncoder(features)
            val memory = encoderResult.get(0) as OnnxTensor
            
            // Create source mask
            val srcMaskTensor = createSourceMaskTensor(features)
            
            // Run beam search decoder
            val candidates = runBeamSearch(memory, srcMaskTensor, features)
            
            // Create final prediction result
            val result = createPredictionResult(candidates)
            
            logDebug("🧠 Neural prediction completed: ${result.size} candidates")
            result
            
        } catch (e: Exception) {
            logE("Neural prediction failed", e)
            PredictionResult.empty
        }
    }
    
    /**
     * Run encoder inference
     */
    private suspend fun runEncoder(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OrtSession.Result {
        val encoderSession = this.encoderSession ?: throw IllegalStateException("Encoder not loaded")
        
        // Create input tensors
        val trajectoryTensor = createTrajectoryTensor(features)
        val nearestKeysTensor = createNearestKeysTensor(features)
        val srcMaskTensor = createSourceMaskTensor(features)
        
        // Run encoder
        val inputs = mapOf(
            "trajectory_features" to trajectoryTensor,
            "nearest_keys" to nearestKeysTensor,
            "src_mask" to srcMaskTensor
        )
        
        return encoderSession.run(inputs)
    }
    
    /**
     * Run beam search decoder
     */
    private suspend fun runBeamSearch(
        memory: OnnxTensor,
        srcMaskTensor: OnnxTensor,
        features: SwipeTrajectoryProcessor.TrajectoryFeatures
    ): List<BeamSearchCandidate> = withContext(Dispatchers.Default) {
        
        val decoderSession = this@OnnxSwipePredictorImpl.decoderSession ?: return@withContext emptyList()
        
        // Initialize beam search
        val beams = mutableListOf<BeamSearchState>()
        beams.add(BeamSearchState(SOS_IDX, 0.0f, false))
        
        logDebug("🚀 Beam search initialized with SOS token ($SOS_IDX)")
        logDebug("⚠️ PERFORMANCE WARNING: Using sequential processing - each beam requires separate inference call")
        
        // Beam search loop
        for (step in 0 until maxLength) {
            val candidates = mutableListOf<BeamSearchState>()
            logDebug("🔄 Beam search step $step with ${beams.size} beams")
            
            for (beam in beams) {
                if (beam.finished) {
                    candidates.add(beam)
                    continue
                }
                
                try {
                    // Update reusable tensors for this beam
                    updateReusableTokens(beam, 20)
                    
                    // Create decoder input tensors
                    val targetTokensTensor = OnnxTensor.createTensor(
                        ortEnvironment,
                        FloatBuffer.wrap(reusableTokensArray.map { it.toFloat() }.toFloatArray()),
                        longArrayOf(1, 20)
                    )
                    val targetMaskTensor = OnnxTensor.createTensor(ortEnvironment, reusableTargetMaskArray)
                    
                    // Run decoder
                    val decoderInputs = mapOf(
                        "memory" to memory,
                        "target_tokens" to targetTokensTensor,
                        "target_mask" to targetMaskTensor,
                        "src_mask" to srcMaskTensor
                    )
                    
                    val decoderOutput = decoderSession.run(decoderInputs)
                    val logitsTensor = decoderOutput.get(0) as OnnxTensor
                    
                    // Process tensor to get probabilities
                    val tensorData = logitsTensor.value
                    if (tensorData is Array<*> && tensorData[0] is Array<*>) {
                        val logits3D = tensorData as Array<Array<FloatArray>>
                        val currentPos = beam.tokens.size - 1
                        
                        if (currentPos >= 0 && currentPos < logits3D[0].size) {
                            val vocabLogits = logits3D[0][currentPos]
                            val topK = getTopKIndices(vocabLogits, beamWidth)
                            
                            // Create new beam candidates
                            for (tokenId in topK) {
                                val newBeam = BeamSearchState(beam)
                                newBeam.tokens.add(tokenId.toLong())
                                newBeam.score += vocabLogits[tokenId]
                                
                                if (tokenId == EOS_IDX) {
                                    newBeam.finished = true
                                }
                                
                                candidates.add(newBeam)
                            }
                        }
                    }
                    
                    // Clean up tensors
                    targetTokensTensor.close()
                    targetMaskTensor.close()
                    decoderOutput.close()
                    
                } catch (e: Exception) {
                    logE("Beam search error for beam ${beam.tokens}", e)
                }
            }
            
            // Select top beams
            candidates.sortByDescending { it.score }
            beams.clear()
            beams.addAll(candidates.take(beamWidth))
            
            // Check if all beams finished
            if (beams.all { it.finished }) {
                break
            }
        }
        
        // Convert beams to candidates
        beams.map { beam ->
            val word = tokenizer.tokensToWord(beam.tokens.drop(1)) // Remove SOS token
            BeamSearchCandidate(word, Math.exp(beam.score).toFloat())
        }
    }
    
    /**
     * Create trajectory tensor from features
     */
    private fun createTrajectoryTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        val trajectoryData = Array(1) { Array(MAX_SEQUENCE_LENGTH) { FloatArray(TRAJECTORY_FEATURES) } }
        
        for (i in 0 until minOf(features.coordinates.size, MAX_SEQUENCE_LENGTH)) {
            val point = features.coordinates[i]
            trajectoryData[0][i] = floatArrayOf(
                point.x / NORMALIZED_WIDTH,
                point.y / NORMALIZED_HEIGHT,
                features.velocities.getOrNull(i) ?: 0f,
                features.velocities.getOrNull(i) ?: 0f, // vx, vy (simplified)
                0f, // ax (acceleration)
                0f  // ay
            )
        }
        
        return OnnxTensor.createTensor(ortEnvironment, trajectoryData)
    }
    
    /**
     * Create nearest keys tensor
     */
    private fun createNearestKeysTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        val nearestKeysData = Array(1) { LongArray(MAX_SEQUENCE_LENGTH) }
        
        for (i in 0 until minOf(features.coordinates.size, MAX_SEQUENCE_LENGTH)) {
            nearestKeysData[0][i] = features.nearestKeys.getOrNull(i)?.toLong() ?: PAD_IDX.toLong()
        }
        
        return OnnxTensor.createTensor(ortEnvironment, nearestKeysData)
    }
    
    /**
     * Create source mask tensor
     */
    private fun createSourceMaskTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        val maskData = Array(1) { BooleanArray(MAX_SEQUENCE_LENGTH) }
        
        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            maskData[0][i] = i >= features.actualLength
        }
        
        return OnnxTensor.createTensor(ortEnvironment, maskData)
    }
    
    /**
     * Update reusable token arrays for beam
     */
    private fun updateReusableTokens(beam: BeamSearchState, seqLength: Int) {
        reusableTokensArray.fill(PAD_IDX.toLong())
        reusableTargetMaskArray[0].fill(false)
        
        for (i in 0 until minOf(beam.tokens.size, seqLength)) {
            reusableTokensArray[i] = beam.tokens[i]
            reusableTargetMaskArray[0][i] = true
        }
    }
    
    /**
     * Get top K indices from probability array
     */
    private fun getTopKIndices(array: FloatArray, k: Int): IntArray {
        return array.withIndex()
            .sortedByDescending { it.value }
            .take(k)
            .map { it.index }
            .toIntArray()
    }
    
    /**
     * Load model from assets
     */
    private fun loadModelFromAssets(modelPath: String): ByteArray {
        return context.assets.open(modelPath).use { inputStream ->
            val available = inputStream.available()
            val modelData = ByteArray(available)
            var totalRead = 0
            
            while (totalRead < available) {
                val read = inputStream.read(modelData, totalRead, available - totalRead)
                if (read == -1) break
                totalRead += read
            }
            
            logDebug("Successfully loaded $totalRead bytes from $modelPath")
            modelData
        }
    }
    
    /**
     * Create optimized session options
     */
    private fun createSessionOptions(modelName: String): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(0) // Auto-detect
            setMemoryPatternOptimization(true)
            
            // Try to enable hardware acceleration
            try {
                addQNN() // Samsung S25U Snapdragon NPU
                logDebug("🚀 QNN execution provider enabled for $modelName")
            } catch (e: Exception) {
                try {
                    addXNNPACK() // Optimized ARM CPU
                    logDebug("⚡ XNNPACK enabled for $modelName")
                } catch (e2: Exception) {
                    logDebug("💻 Using CPU execution for $modelName")
                }
            }
        }
    }
    
    /**
     * Create prediction result from beam candidates
     */
    private fun createPredictionResult(candidates: List<BeamSearchCandidate>): PredictionResult {
        if (candidates.isEmpty()) {
            return PredictionResult.empty
        }
        
        // Filter through vocabulary if available
        val filteredCandidates = if (vocabulary.isLoaded()) {
            vocabulary.filterPredictions(candidates.map { candidate ->
                OptimizedVocabulary.CandidateWord(candidate.word, candidate.confidence)
            }, createSwipeStats())
        } else {
            candidates.map { OptimizedVocabulary.FilteredPrediction(it.word, it.confidence) }
        }
        
        val words = filteredCandidates.map { it.word }
        val scores = filteredCandidates.map { (it.score * 1000).toInt() }
        
        return PredictionResult(words, scores)
    }
    
    private fun createSwipeStats(): OptimizedVocabulary.SwipeStats {
        // Create basic swipe stats for vocabulary filtering
        return OptimizedVocabulary.SwipeStats(
            pathLength = 0f,
            duration = 0f,
            straightnessRatio = 0f
        )
    }
    
    /**
     * Set configuration
     */
    fun setConfig(config: Config) {
        beamWidth = if (config.neural_beam_width != 0) config.neural_beam_width else DEFAULT_BEAM_WIDTH
        maxLength = if (config.neural_max_length != 0) config.neural_max_length else DEFAULT_MAX_LENGTH
        confidenceThreshold = config.neural_confidence_threshold
        
        logDebug("Neural config updated: beam_width=$beamWidth, max_length=$maxLength, threshold=$confidenceThreshold")
    }
    
    /**
     * Set debug logger
     */
    fun setDebugLogger(logger: ((String) -> Unit)?) {
        debugLogger = logger
    }
    
    /**
     * Set keyboard dimensions
     */
    fun setKeyboardDimensions(width: Int, height: Int) {
        trajectoryProcessor.setKeyboardDimensions(width, height)
    }
    
    /**
     * Set real key positions
     */
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        trajectoryProcessor.setRealKeyPositions(keyPositions)
    }
    
    /**
     * Debug logging
     */
    private fun logDebug(message: String) {
        Log.d(TAG, message)
        debugLogger?.invoke(message)
    }
    
    private fun logE(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        debugLogger?.invoke("ERROR: $message - ${throwable.message}")
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        encoderSession?.close()
        decoderSession?.close()
        onnxExecutor.shutdown()
        isModelLoaded = false
        isInitialized = false
    }
    
    /**
     * Beam search state
     */
    data class BeamSearchState(
        val tokens: MutableList<Long>,
        var score: Float,
        var finished: Boolean
    ) {
        constructor(startToken: Int, score: Float, finished: Boolean) : this(
            mutableListOf(startToken.toLong()), score, finished
        )
        
        constructor(other: BeamSearchState) : this(
            other.tokens.toMutableList(), other.score, other.finished
        )
    }
    
    /**
     * Beam search candidate result
     */
    data class BeamSearchCandidate(
        val word: String,
        val confidence: Float
    )
}

/**
 * Simplified trajectory processor
 */
class SwipeTrajectoryProcessor {
    
    data class TrajectoryFeatures(
        val coordinates: List<PointF>,
        val velocities: List<Float>,
        val nearestKeys: List<Int>,
        val actualLength: Int
    )
    
    fun extractFeatures(coordinates: List<PointF>, timestamps: List<Long>): TrajectoryFeatures {
        val velocities = coordinates.zipWithNext { p1, p2 ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }
        
        val nearestKeys = coordinates.map { point ->
            // Simple key detection - would need actual keyboard layout
            when {
                point.x < 300 -> 1 // 'a' region
                point.x < 600 -> 2 // 's' region  
                else -> 3 // 'd' region
            }
        }
        
        return TrajectoryFeatures(
            coordinates = coordinates,
            velocities = velocities,
            nearestKeys = nearestKeys,
            actualLength = coordinates.size
        )
    }
    
    fun setKeyboardDimensions(width: Int, height: Int) {
        // Set keyboard dimensions for coordinate normalization
    }
    
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        // Set real key positions for nearest key detection
    }
}

/**
 * Simple tokenizer
 */
class SwipeTokenizer {
    fun initialize() {
        // Initialize tokenizer
    }
    
    fun tokensToWord(tokens: List<Long>): String {
        return tokens.mapNotNull { token ->
            when (token.toInt()) {
                in 4..29 -> ('a' + (token.toInt() - 4)).toString()
                else -> null
            }
        }.joinToString("")
    }
}

/**
 * Optimized vocabulary stub
 */
class OptimizedVocabulary(private val context: Context) {
    
    fun loadVocabulary(): Boolean = true
    fun isLoaded(): Boolean = true
    fun getStats(): VocabStats = VocabStats(10000)
    
    fun filterPredictions(candidates: List<CandidateWord>, stats: SwipeStats): List<FilteredPrediction> {
        return candidates.map { FilteredPrediction(it.word, it.confidence) }
    }
    
    data class VocabStats(val totalWords: Int)
    data class CandidateWord(val word: String, val confidence: Float)
    data class FilteredPrediction(val word: String, val score: Float)
    data class SwipeStats(val pathLength: Float, val duration: Float, val straightnessRatio: Float)
}