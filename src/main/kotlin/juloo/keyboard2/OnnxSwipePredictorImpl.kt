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
 * Complete trajectory processor matching Java implementation
 */
class SwipeTrajectoryProcessor {
    
    companion object {
        private const val TAG = "SwipeTrajectoryProcessor"
        private const val MAX_TRAJECTORY_POINTS = 150
        private const val SMOOTHING_WINDOW = 3
    }
    
    private var keyboardWidth = 1080
    private var keyboardHeight = 400
    private var realKeyPositions = mapOf<Char, PointF>()
    
    data class TrajectoryFeatures(
        val coordinates: List<PointF>,
        val velocities: List<Float>,
        val accelerations: List<Float>,
        val nearestKeys: List<Int>,
        val actualLength: Int,
        val normalizedCoordinates: List<PointF>
    )
    
    fun extractFeatures(coordinates: List<PointF>, timestamps: List<Long>): TrajectoryFeatures {
        // Smooth trajectory to reduce noise
        val smoothedCoords = smoothTrajectory(coordinates)
        
        // Calculate velocities (first derivative)
        val velocities = calculateVelocities(smoothedCoords, timestamps)
        
        // Calculate accelerations (second derivative)
        val accelerations = calculateAccelerations(velocities, timestamps)
        
        // Normalize coordinates to [0, 1] range
        val normalizedCoords = normalizeCoordinates(smoothedCoords)
        
        // Detect nearest keys for each point
        val nearestKeys = detectNearestKeys(smoothedCoords)
        
        // Pad or truncate to MAX_TRAJECTORY_POINTS
        val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS)
        val finalVelocities = padOrTruncate(velocities, MAX_TRAJECTORY_POINTS)
        val finalAccelerations = padOrTruncate(accelerations, MAX_TRAJECTORY_POINTS)
        val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)
        
        return TrajectoryFeatures(
            coordinates = finalCoords,
            velocities = finalVelocities,
            accelerations = finalAccelerations,
            nearestKeys = finalNearestKeys,
            actualLength = coordinates.size.coerceAtMost(MAX_TRAJECTORY_POINTS),
            normalizedCoordinates = finalCoords
        )
    }
    
    /**
     * Smooth trajectory using moving average
     */
    private fun smoothTrajectory(coordinates: List<PointF>): List<PointF> {
        if (coordinates.size <= SMOOTHING_WINDOW) return coordinates
        
        return coordinates.windowed(SMOOTHING_WINDOW, partialWindows = true) { window ->
            PointF(
                window.map { it.x }.average().toFloat(),
                window.map { it.y }.average().toFloat()
            )
        }
    }
    
    /**
     * Calculate velocity profile
     */
    private fun calculateVelocities(coordinates: List<PointF>, timestamps: List<Long>): List<Float> {
        if (coordinates.size < 2 || timestamps.size < 2) return listOf(0f)
        
        return coordinates.zip(timestamps).zipWithNext { (p1, t1), (p2, t2) ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val timeDelta = (t2 - t1) / 1000f // Convert to seconds
            
            if (timeDelta > 0) distance / timeDelta else 0f
        }
    }
    
    /**
     * Calculate acceleration profile
     */
    private fun calculateAccelerations(velocities: List<Float>, timestamps: List<Long>): List<Float> {
        if (velocities.size < 2 || timestamps.size < 2) return listOf(0f)
        
        return velocities.zipWithNext().zip(timestamps.zipWithNext()) { (v1, v2), (t1, t2) ->
            val velocityDelta = v2 - v1
            val timeDelta = (t2 - t1) / 1000f
            
            if (timeDelta > 0) velocityDelta / timeDelta else 0f
        }
    }
    
    /**
     * Normalize coordinates to [0, 1] range
     */
    private fun normalizeCoordinates(coordinates: List<PointF>): List<PointF> {
        return coordinates.map { point ->
            PointF(
                (point.x / keyboardWidth).coerceIn(0f, 1f),
                (point.y / keyboardHeight).coerceIn(0f, 1f)
            )
        }
    }
    
    /**
     * Detect nearest key for each coordinate
     */
    private fun detectNearestKeys(coordinates: List<PointF>): List<Int> {
        return coordinates.map { point ->
            if (realKeyPositions.isEmpty()) {
                // Fallback: Simple grid-based detection
                val col = (point.x / (keyboardWidth / 10f)).toInt().coerceIn(0, 9)
                val row = (point.y / (keyboardHeight / 4f)).toInt().coerceIn(0, 3)
                row * 10 + col
            } else {
                // Find nearest actual key
                realKeyPositions.minByOrNull { (_, keyPos) ->
                    val dx = point.x - keyPos.x
                    val dy = point.y - keyPos.y
                    dx * dx + dy * dy
                }?.key?.code ?: 0
            }
        }
    }
    
    /**
     * Pad or truncate list to target size
     */
    private fun <T> padOrTruncate(list: List<T>, targetSize: Int, paddingValue: T? = null): List<T> {
        return when {
            list.size == targetSize -> list
            list.size > targetSize -> list.take(targetSize)
            else -> {
                val padding = paddingValue ?: list.lastOrNull()
                if (padding != null) {
                    list + List(targetSize - list.size) { padding }
                } else list
            }
        }
    }
    
    fun setKeyboardDimensions(width: Int, height: Int) {
        keyboardWidth = width
        keyboardHeight = height
        logD("Keyboard dimensions set: ${width}x${height}")
    }
    
    fun setRealKeyPositions(keyPositions: Map<Char, PointF>) {
        realKeyPositions = keyPositions
        logD("Real key positions updated: ${keyPositions.size} keys")
    }
}

/**
 * Complete tokenizer matching Java implementation
 */
class SwipeTokenizer {
    
    companion object {
        private const val TAG = "SwipeTokenizer"
        private const val VOCAB_SIZE = 30
    }
    
    // Character to token mapping
    private val charToToken = mutableMapOf<Char, Int>()
    private val tokenToChar = mutableMapOf<Int, Char>()
    
    fun initialize() {
        // Initialize character mappings
        charToToken.clear()
        tokenToChar.clear()
        
        // Special tokens
        tokenToChar[0] = '\u0000' // PAD
        tokenToChar[1] = '\u0001' // UNK  
        tokenToChar[2] = '\u0002' // SOS
        tokenToChar[3] = '\u0003' // EOS
        
        // Character tokens (4-29 for a-z)
        ('a'..'z').forEachIndexed { index, char ->
            val tokenId = index + 4
            charToToken[char] = tokenId
            tokenToChar[tokenId] = char
        }
        
        logD("Tokenizer initialized with ${charToToken.size} character mappings")
    }
    
    fun charToToken(char: Char): Int {
        return charToToken[char.lowercaseChar()] ?: 1 // UNK token
    }
    
    fun tokenToChar(token: Int): Char {
        return tokenToChar[token] ?: '?'
    }
    
    fun tokensToWord(tokens: List<Long>): String {
        return tokens.mapNotNull { token ->
            val char = tokenToChar(token.toInt())
            if (char.isLetter()) char.toString() else null
        }.joinToString("")
    }
    
    fun wordToTokens(word: String): List<Long> {
        val tokens = mutableListOf<Long>()
        tokens.add(2L) // SOS token
        
        word.lowercase().forEach { char ->
            tokens.add(charToToken(char).toLong())
        }
        
        tokens.add(3L) // EOS token
        return tokens
    }
    
    fun isValidToken(token: Int): Boolean {
        return token in 0 until VOCAB_SIZE
    }
    
    val vocabularySize: Int get() = VOCAB_SIZE
}

/**
 * Use complete optimized vocabulary implementation
 */
class OptimizedVocabulary(context: Context) {
    private val impl = OptimizedVocabularyImpl(context)
    
    suspend fun loadVocabulary(): Boolean = impl.loadVocabulary()
    fun isLoaded(): Boolean = impl.isLoaded()
    fun getStats(): VocabStats = impl.getStats().let { stats ->
        VocabStats(stats.totalWords)
    }
    
    fun filterPredictions(candidates: List<CandidateWord>, stats: SwipeStats): List<FilteredPrediction> {
        val implCandidates = candidates.map { 
            OptimizedVocabularyImpl.CandidateWord(it.word, it.confidence) 
        }
        val implStats = OptimizedVocabularyImpl.SwipeStats(stats.pathLength, stats.duration, stats.straightnessRatio)
        
        return impl.filterPredictions(implCandidates, implStats).map {
            FilteredPrediction(it.word, it.score)
        }
    }
    
    data class VocabStats(val totalWords: Int)
    data class CandidateWord(val word: String, val confidence: Float)
    data class FilteredPrediction(val word: String, val score: Float)
    data class SwipeStats(val pathLength: Float, val duration: Float, val straightnessRatio: Float)
}