package tribixbite.keyboard2

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

    // High-performance tensor pooling for 50-70% speedup
    private val tensorPool = OptimizedTensorPool.getInstance(ortEnvironment)
    private val tensorMemoryManager = TensorMemoryManager(ortEnvironment)

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
            
            // Load encoder model with validation
            val encoderData = loadModelFromAssets("models/swipe_model_character_quant.onnx")
            logDebug("📥 Encoder model data loaded: ${encoderData.size} bytes")

            encoderSession = ortEnvironment.createSession(encoderData, createSessionOptions("Encoder"))
            logDebug("✅ Encoder session created successfully")

            // Validate encoder input/output schema
            encoderSession?.let { session ->
                val inputInfo = session.inputInfo
                val outputInfo = session.outputInfo
                logDebug("   Encoder inputs: ${inputInfo.keys}")
                logDebug("   Encoder outputs: ${outputInfo.keys}")

                // Validate expected input names
                val expectedInputs = setOf("trajectory_features", "nearest_keys", "src_mask")
                if (!inputInfo.keys.containsAll(expectedInputs)) {
                    throw RuntimeException("Encoder missing expected inputs: $expectedInputs")
                }
            }

            // Load decoder model with validation
            val decoderData = loadModelFromAssets("models/swipe_decoder_character_quant.onnx")
            logDebug("📥 Decoder model data loaded: ${decoderData.size} bytes")

            decoderSession = ortEnvironment.createSession(decoderData, createSessionOptions("Decoder"))
            logDebug("✅ Decoder session created successfully")

            // Validate decoder input/output schema
            decoderSession?.let { session ->
                val inputInfo = session.inputInfo
                val outputInfo = session.outputInfo
                logDebug("   Decoder inputs: ${inputInfo.keys}")
                logDebug("   Decoder outputs: ${outputInfo.keys}")

                // Validate expected input names
                val expectedInputs = setOf("memory", "target_tokens", "src_mask", "target_mask")
                if (!inputInfo.keys.containsAll(expectedInputs)) {
                    throw RuntimeException("Decoder missing expected inputs: $expectedInputs")
                }
            }
            
            // Initialize tokenizer and vocabulary
            tokenizer.initialize()
            val vocabLoaded = vocabulary.loadVocabulary()
            logDebug("📚 Vocabulary loaded: $vocabLoaded (words: ${vocabulary.getStats().totalWords})")
            
            // Perform complete pipeline validation test
            validateCompletePipeline()

            isModelLoaded = true
            isInitialized = true
            logDebug("🧠 ONNX neural prediction system ready and validated!")

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

            // Log raw input data (first 10 points)
            logDebug("📍 Raw swipe input (first 10 points):")
            input.coordinates.take(10).forEachIndexed { i, point ->
                val timestamp = if (i < input.timestamps.size) input.timestamps[i] else 0L
                logDebug("   [$i] x=${point.x}, y=${point.y}, t=$timestamp")
            }

            // Extract trajectory features
            val features = trajectoryProcessor.extractFeatures(input.coordinates, input.timestamps)
            logDebug("📊 Feature extraction complete:")
            logDebug("   Actual length: ${features.actualLength}")
            logDebug("   First 10 nearest keys: ${features.nearestKeys.take(10)}")
            logDebug("   First 3 normalized points: ${features.normalizedCoordinates.take(3).map { "(%.3f, %.3f)".format(it.x, it.y) }}")

            // Run encoder
            val encoderResult = runEncoder(features)
            val memory = encoderResult.get(0) as OnnxTensor
            
            // Create source mask
            val srcMaskTensor = createSourceMaskTensor(features)

            // Run beam search decoder
            logDebug("🔍 Starting beam search decoder...")
            val candidates = runBeamSearch(memory, srcMaskTensor, features)
            logDebug("✅ Beam search returned ${candidates.size} candidates")

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
     * Run beam search decoder with batched inference optimization
     */
    private suspend fun runBeamSearch(
        memory: OnnxTensor,
        srcMaskTensor: OnnxTensor,
        features: SwipeTrajectoryProcessor.TrajectoryFeatures
    ): List<BeamSearchCandidate> = withContext(Dispatchers.Default) {

        val decoderSession = this@OnnxSwipePredictorImpl.decoderSession ?: run {
            logE("❌ CRITICAL: Decoder session not initialized - cannot run beam search", RuntimeException("Decoder not initialized"))
            return@withContext emptyList()
        }

        // Initialize beam search
        val beams = mutableListOf<BeamSearchState>()
        beams.add(BeamSearchState(SOS_IDX, 0.0f, false))
        
        // Maintain separate lists for finished and active beams
        val finishedBeams = mutableListOf<BeamSearchState>()

        // Beam search loop with batched processing
        for (step in 0 until maxLength) {
            try {
                // Separate active beams (need further expansion)
                val activeBeams = beams.filter { !it.finished }

                if (activeBeams.isEmpty()) {
                    break  // All beams finished
                }

                // CRITICAL OPTIMIZATION: Process all active beams in single batch
                // Returns beamWidth globally-selected beams (already sorted and pruned)
                val newBeams = processBatchedBeams(activeBeams, memory, srcMaskTensor, decoderSession)

                // Separate new finished beams from still-active ones
                val newFinished = newBeams.filter { it.finished }
                val stillActive = newBeams.filter { !it.finished }

                // Add newly finished beams to finished list
                finishedBeams.addAll(newFinished)

                // Update beams list for next iteration
                // No need to sort/take - processBatchedResults already did global top-k selection
                beams.clear()
                beams.addAll(stillActive)

                // Early stopping conditions (matching web demo):
                // 1. All beams finished
                // 2. After step 10, if we have at least 3 finished beams (good enough)
                if (beams.isEmpty() && finishedBeams.isNotEmpty()) {
                    break  // All beams finished
                }
                if (step >= 10 && finishedBeams.size >= 3) {
                    break  // Have enough good predictions
                }

            } catch (e: Exception) {
                logE("❌ Beam search failed at step $step", e)
                break
            }
        }

        // Return best hypotheses from both finished and remaining active beams
        val allFinalBeams = finishedBeams + beams

        // Convert beams to candidates
        val candidates = allFinalBeams.map { beam ->
            val word = tokenizer.tokensToWord(beam.tokens.drop(1)) // Remove SOS token
            BeamSearchCandidate(word, kotlin.math.exp(beam.score).toFloat())
        }

        // Log top 3 results
        val top3 = candidates.sortedByDescending { it.confidence }.take(3)
        logDebug("🏆 Top 3: ${top3.map { "'${it.word}'" }.joinToString(", ")}")

        candidates
    }
    
    /**
     * CRITICAL OPTIMIZATION: Process all beams in single batched inference call
     * Enhanced with tensor pooling for 50-70% additional speedup
     */
    private suspend fun processBatchedBeams(
        activeBeams: List<BeamSearchState>,
        memory: OnnxTensor,
        srcMaskTensor: OnnxTensor,
        decoderSession: OrtSession
    ): List<BeamSearchState> = withContext(Dispatchers.Default) {

        val batchSize = activeBeams.size
        val seqLength = 20 // Standard decoder sequence length

        // CRITICAL FIX: Expand memory tensor to match batch size
        // Memory shape is [1, 150, 256], need [batchSize, 150, 256]
        val memoryShape = memory.info.shape
        val expandedMemoryShape = longArrayOf(batchSize.toLong(), memoryShape[1], memoryShape[2])

        val expandedMemory = if (batchSize > 1) {
            expandMemoryTensor(memory, batchSize)
        } else {
            memory  // No expansion needed for single beam
        }

        // CRITICAL FIX: Also expand src_mask to match batch size
        // src_mask shape is [1, 150], need [batchSize, 150]
        val expandedSrcMask = if (batchSize > 1) {
            expandSrcMaskTensor(srcMaskTensor, batchSize)
        } else {
            srcMaskTensor  // No expansion needed for single beam
        }

        // OPTIMIZATION: Use tensor pool for batched tensors - eliminates allocation overhead
        val batchedTokensShape = longArrayOf(batchSize.toLong(), seqLength.toLong())
        val batchedMaskShape = longArrayOf(batchSize.toLong(), seqLength.toLong())

        tensorPool.useTensor(batchedTokensShape, "long") { batchedTokensTensor ->
            tensorPool.useTensor(batchedMaskShape, "boolean") { batchedMaskTensor ->

                // Fill tensor data directly from pool buffers
                populateBatchedTensors(activeBeams, batchedTokensTensor, batchedMaskTensor, seqLength)

                // Prepare decoder inputs with optimized tensors
                val decoderInputs = mapOf(
                    "memory" to expandedMemory,
                    "target_tokens" to batchedTokensTensor,
                    "target_mask" to batchedMaskTensor,
                    "src_mask" to expandedSrcMask
                )

                // SINGLE BATCHED INFERENCE with tensor pool optimization
                val batchedOutput = decoderSession.run(decoderInputs)

                // Process batched results with enhanced error handling
                val newBeamCandidates = processBatchedResults(batchedOutput, activeBeams)

                // Automatic cleanup via tensor pool
                batchedOutput.close()

                // Clean up expanded tensors if we created them
                if (batchSize > 1) {
                    if (expandedMemory != memory) {
                        expandedMemory.close()
                    }
                    if (expandedSrcMask != srcMaskTensor) {
                        expandedSrcMask.close()
                    }
                }

                newBeamCandidates
            }
        }
    }

    /**
     * Expand memory tensor from [1, seq_len, hidden_dim] to [batch_size, seq_len, hidden_dim]
     * by replicating the single batch across multiple batches
     */
    private fun expandMemoryTensor(memory: OnnxTensor, batchSize: Int): OnnxTensor {
        val memoryData = memory.value as Array<Array<FloatArray>>
        val seqLen = memoryData[0].size
        val hiddenDim = memoryData[0][0].size

        // Create expanded array [batch_size, seq_len, hidden_dim]
        val expandedData = Array(batchSize) { Array(seqLen) { FloatArray(hiddenDim) } }

        // Replicate the single batch to all batch positions
        for (b in 0 until batchSize) {
            for (s in 0 until seqLen) {
                System.arraycopy(memoryData[0][s], 0, expandedData[b][s], 0, hiddenDim)
            }
        }

        return OnnxTensor.createTensor(ortEnvironment, expandedData)
    }

    /**
     * Expand src_mask tensor from [1, seq_len] to [batch_size, seq_len]
     * by replicating the single batch mask across multiple batches
     */
    private fun expandSrcMaskTensor(srcMask: OnnxTensor, batchSize: Int): OnnxTensor {
        val maskData = srcMask.value as Array<BooleanArray>
        val seqLen = maskData[0].size

        // Create expanded array [batch_size, seq_len]
        val expandedMask = Array(batchSize) { BooleanArray(seqLen) }

        // Replicate the single batch mask to all batch positions
        for (b in 0 until batchSize) {
            System.arraycopy(maskData[0], 0, expandedMask[b], 0, seqLen)
        }

        return OnnxTensor.createTensor(ortEnvironment, expandedMask)
    }

    /**
     * Populate batched tensors efficiently from beam states
     */
    private fun populateBatchedTensors(
        activeBeams: List<BeamSearchState>,
        batchedTokensTensor: OnnxTensor,
        batchedMaskTensor: OnnxTensor,
        seqLength: Int
    ) {
        // Get direct access to tensor data for efficient population
        val tokensData = batchedTokensTensor.value as Array<LongArray>
        val maskData = batchedMaskTensor.value as Array<BooleanArray>

        // Populate batch data for all beams efficiently
        activeBeams.forEachIndexed { batchIndex, beam ->
            val tokensArray = tokensData[batchIndex]
            val maskArray = maskData[batchIndex]

            // Fill tokens and mask for this beam
            // CRITICAL: Mask convention is 1 = PADDED, 0 = VALID (matches web demo)
            for (seqIndex in 0 until seqLength) {
                if (seqIndex < beam.tokens.size) {
                    tokensArray[seqIndex] = beam.tokens[seqIndex]
                    maskArray[seqIndex] = false  // Valid token = 0
                } else {
                    tokensArray[seqIndex] = PAD_IDX.toLong()
                    maskArray[seqIndex] = true   // Padded position = 1
                }
            }
        }
    }

    /**
     * Process batched results with GLOBAL top-k selection
     * CRITICAL FIX: Select top-k candidates from ALL beams globally, not locally per beam
     * This prevents beam collapse where all beams originate from single parent
     */
    private fun processBatchedResults(
        batchedOutput: OrtSession.Result,
        activeBeams: List<BeamSearchState>
    ): List<BeamSearchState> {
        val batchedLogitsTensor = batchedOutput.get(0) as OnnxTensor
        val batchedTensorData = batchedLogitsTensor.value

        logDebug("📊 Decoder output shape: ${batchedLogitsTensor.info.shape.contentToString()}, type: ${batchedTensorData::class.simpleName}")

        if (batchedTensorData !is Array<*>) {
            return emptyList()
        }

        val batchedLogits = batchedTensorData as Array<Array<FloatArray>>

        // CRITICAL: Collect ALL possible next hypotheses with their GLOBAL scores
        // Each item is (parentBeamIndex, tokenId, newTotalScore)
        val allHypotheses = mutableListOf<Triple<Int, Int, Float>>()

        activeBeams.forEachIndexed { batchIndex, beam ->
            val currentPos = beam.tokens.size - 1

            if (currentPos >= 0 && currentPos < batchedLogits[batchIndex].size) {
                val vocabLogits = batchedLogits[batchIndex][currentPos]
                val logProbs = applyLogSoftmax(vocabLogits)

                // CRITICAL FIX: Consider EVERY possible next token for global selection
                // This prevents beam collapse by allowing lower-scoring beams with
                // high-probability tokens to compete with higher-scoring beams
                logProbs.forEachIndexed { tokenId, logProb ->
                    val newScore = beam.score + logProb
                    allHypotheses.add(Triple(batchIndex, tokenId, newScore))
                }
            }
        }

        // GLOBAL top-k selection: Sort ALL possibilities and take top beamWidth
        val topHypotheses = allHypotheses.sortedByDescending { it.third }.take(beamWidth)

        // Construct new beam generation from globally-selected winners
        val newBeams = mutableListOf<BeamSearchState>()
        topHypotheses.forEach { (parentIndex, tokenId, score) ->
            val parentBeam = activeBeams[parentIndex]
            val newBeam = BeamSearchState(parentBeam)
            newBeam.tokens.add(tokenId.toLong())
            newBeam.score = score  // Use the globally-computed score

            if (tokenId == EOS_IDX) {
                newBeam.finished = true
            }

            newBeams.add(newBeam)
        }

        return newBeams
    }

    /**
     * Create trajectory tensor from features - EXACT Java implementation match
     */
    private fun createTrajectoryTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create direct buffer exactly like Java implementation for performance
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4) // 4 bytes per float
        byteBuffer.order(java.nio.ByteOrder.nativeOrder())
        val buffer = byteBuffer.asFloatBuffer()

        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            val point = features.normalizedCoordinates.getOrNull(i) ?: PointF(0f, 0f)
            val velocity = features.velocities.getOrNull(i) ?: PointF(0f, 0f)
            val acceleration = features.accelerations.getOrNull(i) ?: PointF(0f, 0f)

            // Exact 6-feature layout matching web demo: [x, y, vx, vy, ax, ay]
            buffer.put(point.x)          // Normalized x [0,1]
            buffer.put(point.y)          // Normalized y [0,1]
            buffer.put(velocity.x)       // Velocity x component (delta)
            buffer.put(velocity.y)       // Velocity y component (delta)
            buffer.put(acceleration.x)   // Acceleration x component (delta of delta)
            buffer.put(acceleration.y)   // Acceleration y component (delta of delta)
        }

        buffer.rewind()
        val tensor = OnnxTensor.createTensor(ortEnvironment, buffer, longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong()))

        // Track tensor with memory manager
        tensorMemoryManager.trackTensor(tensor, "TrajectoryTensor", longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong()), MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4L)

        return tensor
    }
    
    /**
     * Create nearest keys tensor - EXACT Java implementation match
     */
    private fun createNearestKeysTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create direct buffer exactly like Java implementation
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8) // 8 bytes per long
        byteBuffer.order(java.nio.ByteOrder.nativeOrder())
        val buffer = byteBuffer.asLongBuffer()

        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            if (i < features.nearestKeys.size) {
                val keyIndex = features.nearestKeys[i]
                buffer.put(keyIndex.toLong())
            } else {
                buffer.put(PAD_IDX.toLong()) // Padding
            }
        }

        buffer.rewind()
        return OnnxTensor.createTensor(ortEnvironment, buffer, longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong()))
    }
    
    /**
     * Create source mask tensor - EXACT Java implementation match
     */
    private fun createSourceMaskTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create 2D boolean array for proper tensor shape [1, MAX_SEQUENCE_LENGTH] - exactly like Java
        val maskData = Array(1) { BooleanArray(MAX_SEQUENCE_LENGTH) }

        // Mask padded positions (true = masked/padded, false = valid) - matching Java logic
        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            maskData[0][i] = (i >= features.actualLength)
        }

        // Use 2D boolean array - ONNX API will infer shape as [1, MAX_SEQUENCE_LENGTH]
        return OnnxTensor.createTensor(ortEnvironment, maskData)
    }
    
    /**
     * Update reusable token arrays for beam
     * CRITICAL: Mask convention is 1 = PADDED, 0 = VALID (matches web demo)
     */
    private fun updateReusableTokens(beam: BeamSearchState, seqLength: Int) {
        reusableTokensArray.fill(PAD_IDX.toLong())
        reusableTargetMaskArray[0].fill(true)  // Default: all padded (1)

        for (i in 0 until minOf(beam.tokens.size, seqLength)) {
            reusableTokensArray[i] = beam.tokens[i]
            reusableTargetMaskArray[0][i] = false  // Mark as VALID (0)
        }
    }

    /**
     * Apply log-softmax to convert raw logits to log probabilities
     * log_softmax(x) = x - log(sum(exp(x)))
     * Uses numerical stability trick: subtract max before exp to prevent overflow
     */
    private fun applyLogSoftmax(logits: FloatArray): FloatArray {
        // Find max for numerical stability
        val maxLogit = logits.maxOrNull() ?: 0f

        // Compute exp(x - max) and sum
        val expValues = logits.map { kotlin.math.exp((it - maxLogit).toDouble()).toFloat() }
        val sumExp = expValues.sum()

        // Compute log probabilities: log(exp(x - max) / sum) = (x - max) - log(sum)
        val logSumExp = kotlin.math.ln(sumExp.toDouble()).toFloat() + maxLogit
        return logits.map { it - logSumExp }.toFloatArray()
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
            
            // Use default execution providers (QNN/XNNPACK not available in this ONNX version)
            logDebug("💻 Using default execution providers for $modelName")
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
            val result = vocabulary.filterPredictions(candidates.map { candidate ->
                OptimizedVocabulary.CandidateWord(candidate.word, candidate.confidence)
            }, createSwipeStats())
            logDebug("📋 Vocabulary filter: ${candidates.size} → ${result.size} candidates")
            result
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
     * Set configuration from NeuralConfig
     */
    fun setConfig(neuralConfig: NeuralConfig) {
        beamWidth = neuralConfig.beamWidth.coerceIn(neuralConfig.beamWidthRange)
        maxLength = neuralConfig.maxLength.coerceIn(neuralConfig.maxLengthRange)
        confidenceThreshold = neuralConfig.confidenceThreshold.coerceIn(neuralConfig.confidenceRange)

        logDebug("Neural config updated: beam_width=$beamWidth, max_length=$maxLength, threshold=$confidenceThreshold")
    }

    /**
     * Set configuration from Config (fallback for compatibility)
     * Creates NeuralConfig from SharedPreferences
     */
    fun setConfig(config: Config) {
        val prefs = Config.globalPrefs()
        val neuralConfig = NeuralConfig(prefs)
        setConfig(neuralConfig)
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
     * Validate complete pipeline with test input
     */
    private suspend fun validateCompletePipeline() {
        logDebug("🧪 Validating complete ONNX prediction pipeline...")

        try {
            // Create test input that matches Java calibration activity
            val testPoints = listOf(
                PointF(100f, 200f), PointF(200f, 200f), PointF(300f, 200f),
                PointF(400f, 250f), PointF(500f, 200f)
            )
            val testTimestamps = testPoints.indices.map { it * 100L }
            val testInput = SwipeInput(testPoints, testTimestamps, emptyList())

            logDebug("   Test input: ${testInput.coordinates.size} points, ${testInput.pathLength} path length")

            // Test feature extraction
            val features = trajectoryProcessor.extractFeatures(testInput.coordinates, testInput.timestamps)
            logDebug("   Feature extraction: ${features.actualLength} features, ${features.nearestKeys.size} nearest keys")

            // Test encoder
            val trajectoryTensor = createTrajectoryTensor(features)
            val nearestKeysTensor = createNearestKeysTensor(features)
            val srcMaskTensor = createSourceMaskTensor(features)

            logDebug("   Tensor creation successful:")
            logDebug("     Trajectory: ${trajectoryTensor.info.shape.contentToString()}")
            logDebug("     Nearest keys: ${nearestKeysTensor.info.shape.contentToString()}")
            logDebug("     Source mask: ${srcMaskTensor.info.shape.contentToString()}")

            // Test encoder inference
            val encoderResult = runEncoder(features)
            val memory = encoderResult.get(0) as OnnxTensor
            logDebug("   Encoder inference: output shape ${memory.info.shape.contentToString()}")

            // Test one step of decoder (simplified validation)
            val beams = listOf(BeamSearchState(SOS_IDX, 0.0f, false))
            val session = decoderSession ?: run {
                logE("Decoder session not initialized for validation")
                return
            }
            val candidates = processBatchedBeams(beams, memory, srcMaskTensor, session)
            logDebug("   Decoder inference: ${candidates.size} beam candidates generated")

            // Cleanup test tensors
            trajectoryTensor.close()
            nearestKeysTensor.close()
            srcMaskTensor.close()
            encoderResult.close()

            logDebug("✅ Complete pipeline validation successful")

        } catch (e: Exception) {
            logE("Pipeline validation failed", e)
            throw RuntimeException("ONNX pipeline validation failed", e)
        }
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
     * Cleanup resources with complete memory management and tensor pool
     */
    fun cleanup() {
        encoderSession?.close()
        decoderSession?.close()
        onnxExecutor.shutdown()

        // Cleanup optimized tensor pool
        kotlinx.coroutines.runBlocking {
            tensorPool.cleanup()
        }

        tensorMemoryManager.cleanup()
        isModelLoaded = false
        isInitialized = false
        logDebug("ONNX predictor cleaned up with optimized tensor pool and memory management")
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
        val velocities: List<PointF>,  // Now stores (vx, vy) as PointF
        val accelerations: List<PointF>,  // Now stores (ax, ay) as PointF
        val nearestKeys: List<Int>,
        val actualLength: Int,
        val normalizedCoordinates: List<PointF>
    )
    
    fun extractFeatures(coordinates: List<PointF>, timestamps: List<Long>): TrajectoryFeatures {
        // 1. Normalize coordinates FIRST (0-1 range) - matches web demo line 1171-1172
        val normalizedCoords = normalizeCoordinates(coordinates)

        // 2. Detect nearest keys - CRITICAL for accurate predictions
        // Model needs this information to understand which keys the swipe path passes near
        val nearestKeys = detectNearestKeys(coordinates)

        // 3. Pad or truncate to MAX_TRAJECTORY_POINTS
        val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS)
        val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)

        // 4. Calculate velocities and accelerations on normalized coords (simple deltas, no time!)
        //    Matches web demo lines 1220-1238
        val velocities = mutableListOf<PointF>()
        val accelerations = mutableListOf<PointF>()

        for (i in 0 until MAX_TRAJECTORY_POINTS) {
            if (i == 0) {
                // First point: zero velocity and acceleration
                velocities.add(PointF(0f, 0f))
                accelerations.add(PointF(0f, 0f))
            } else if (i == 1) {
                // Second point: calculate velocity, zero acceleration
                val vx = finalCoords[i].x - finalCoords[i-1].x
                val vy = finalCoords[i].y - finalCoords[i-1].y
                velocities.add(PointF(vx, vy))
                accelerations.add(PointF(0f, 0f))
            } else {
                // Rest: calculate both velocity and acceleration
                val vx = finalCoords[i].x - finalCoords[i-1].x
                val vy = finalCoords[i].y - finalCoords[i-1].y
                velocities.add(PointF(vx, vy))

                val ax = vx - velocities[i-1].x  // acceleration = delta of velocity
                val ay = vy - velocities[i-1].y
                accelerations.add(PointF(ax, ay))
            }
        }

        return TrajectoryFeatures(
            coordinates = finalCoords,
            velocities = velocities,
            accelerations = accelerations,
            nearestKeys = finalNearestKeys,
            actualLength = coordinates.size.coerceAtMost(MAX_TRAJECTORY_POINTS),
            normalizedCoordinates = finalCoords
        )
    }
    
    /**
     * Smooth trajectory using moving average. This implementation uses an explicit loop
     * to avoid potential issues with the .windowed() extension function on lists of objects.
     */
    private fun smoothTrajectory(coordinates: List<PointF>): List<PointF> {
        if (coordinates.size <= SMOOTHING_WINDOW) {
            return coordinates
        }

        val smoothedResult = mutableListOf<PointF>()
        for (i in coordinates.indices) {
            // Define the window starting at the current index, up to SMOOTHING_WINDOW elements.
            // This replicates the behavior of .windowed(size, step=1, partialWindows=true).
            val windowEnd = (i + SMOOTHING_WINDOW).coerceAtMost(coordinates.size)
            val window = coordinates.subList(i, windowEnd)

            if (window.isEmpty()) continue

            // Manually calculate the average for clarity and safety.
            var sumX = 0.0
            var sumY = 0.0
            for (p in window) {
                sumX += p.x
                sumY += p.y
            }
            smoothedResult.add(PointF((sumX / window.size).toFloat(), (sumY / window.size).toFloat()))
        }
        return smoothedResult
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
     * Detect nearest key for each coordinate using real keyboard layout
     */
    private fun detectNearestKeys(coordinates: List<PointF>): List<Int> {
        return coordinates.map { point ->
            if (realKeyPositions.isNotEmpty()) {
                // Use actual keyboard layout positions
                val nearestKey = realKeyPositions.minByOrNull { (_, keyPos) ->
                    val dx = point.x - keyPos.x
                    val dy = point.y - keyPos.y
                    dx * dx + dy * dy
                }

                // Convert character to token index (simplified for trajectory processor)
                nearestKey?.key?.let { char ->
                    if (char in 'a'..'z') (char - 'a') + 4 else 0 // Simple a-z mapping to tokens 4-29
                } ?: 0
            } else {
                // Enhanced grid detection with proper QWERTY mapping
                detectKeyFromQwertyGrid(point)
            }
        }
    }

    /**
     * Detect key using accurate QWERTY grid mapping
     */
    private fun detectKeyFromQwertyGrid(point: PointF): Int {
        val normalizedX = point.x / keyboardWidth
        val normalizedY = point.y / keyboardHeight

        // QWERTY layout mapping with proper key boundaries
        val qwertyLayout = arrayOf(
            arrayOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
            arrayOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
            arrayOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
        )

        val row = (normalizedY * qwertyLayout.size).toInt().coerceIn(0, qwertyLayout.size - 1)
        val rowKeys = qwertyLayout[row]

        // Handle row offset for QWERTY layout
        val effectiveX = when (row) {
            1 -> normalizedX - 0.05f // ASDF row offset
            2 -> normalizedX - 0.15f // ZXCV row offset
            else -> normalizedX
        }

        val col = (effectiveX * rowKeys.size).toInt().coerceIn(0, rowKeys.size - 1)
        val detectedChar = rowKeys[col]

        return if (detectedChar in 'a'..'z') (detectedChar - 'a') + 4 else 0 // Simple a-z mapping
    }
    
    /**
     * Pad or truncate list to target size
     */
    /**
     * Pad or truncate list to target size.
     * FIX: Handles padding of mutable objects like PointF by creating new instances.
     */
    private fun <T> padOrTruncate(list: List<T>, targetSize: Int, paddingValue: T? = null): List<T> {
        return when {
            list.size == targetSize -> list
            list.size > targetSize -> list.take(targetSize)
            else -> {
                val lastValue = list.lastOrNull()
                val paddingTemplate = paddingValue ?: lastValue

                if (paddingTemplate != null) {
                    val paddingList = List(targetSize - list.size) {
                        // If the padding value is a PointF, create a new instance to avoid reference sharing.
                        // Otherwise, use the value as-is (for immutable types like Float, Int).
                        if (paddingTemplate is PointF) {
                            PointF(paddingTemplate.x, paddingTemplate.y) as T
                        } else {
                            paddingTemplate
                        }
                    }
                    list + paddingList
                } else {
                    list
                }
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
