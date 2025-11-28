package tribixbite.keyboard2.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/**
 * Wrapper for decoder inference operations with broadcast support.
 *
 * The decoder takes encoder memory and target token sequences to generate
 * probability distributions over the vocabulary (logits) for next token prediction.
 *
 * Supports two inference modes:
 * 1. **Broadcast mode**: Models with broadcast_enabled=true expand memory internally
 *    - Input: memory [1, seq_len, hidden_dim], tokens [num_beams, dec_seq_len]
 *    - Model broadcasts memory to [num_beams, seq_len, hidden_dim] internally
 *    - More efficient, reduces memory allocation
 *
 * 2. **Legacy mode**: Traditional models require pre-replicated memory
 *    - Input: memory [num_beams, seq_len, hidden_dim], tokens [num_beams, dec_seq_len]
 *    - Memory must be manually replicated for all beams before inference
 *
 * Responsibilities:
 * - Prepare decoder input tensors (memory, target_tokens, actual_src_length)
 * - Handle broadcast vs legacy memory replication
 * - Run decoder inference session
 * - Extract and validate logits output tensor
 * - Performance timing and logging
 *
 * Thread Safety: This class is NOT thread-safe. Should use a single OrtSession per thread.
 */
class DecoderWrapper(
    private val decoderSession: OrtSession,
    private val tensorFactory: TensorFactory,
    private val ortEnvironment: OrtEnvironment,
    private val broadcastEnabled: Boolean,
    private val enableDetailedLogging: Boolean = false
) {

    companion object {
        private const val TAG = "DecoderWrapper"

        // Decoder input names (ONNX model graph)
        private const val INPUT_MEMORY = "memory"
        private const val INPUT_TARGET_TOKENS = "target_tokens"
        private const val INPUT_ACTUAL_SRC_LENGTH = "actual_src_length"

        // Decoder output names
        private const val OUTPUT_LOGITS = "logits"
    }

    /**
     * Result of decoder inference.
     *
     * @param logits Output probabilities [num_beams, dec_seq_len, vocab_size]
     * @param inferenceTimeMs Time taken for inference in milliseconds
     */
    data class DecoderResult(
        val logits: FloatArray, // Flattened 3D array for efficient access
        val shape: LongArray,   // [num_beams, dec_seq_len, vocab_size]
        val inferenceTimeMs: Double
    )

    /**
     * Run decoder inference for a single beam.
     *
     * @param memory Encoder memory tensor [1, seq_len, hidden_dim]
     * @param tokens Target token sequence for current beam
     * @param actualSrcLength Actual non-padded length of encoder input
     * @param decoderSeqLength Fixed decoder sequence length (e.g., 20)
     * @return DecoderResult containing logits and performance metrics
     */
    fun decodeSingle(
        memory: OnnxTensor,
        tokens: List<Long>,
        actualSrcLength: Int,
        decoderSeqLength: Int
    ): DecoderResult {
        val targetTokensTensor = tensorFactory.createTargetTokensTensor(tokens, decoderSeqLength)
        val actualLengthTensor = tensorFactory.createActualLengthTensor(actualSrcLength, numBeams = 1)

        return try {
            runDecoderInference(
                memory = memory,
                targetTokensTensor = targetTokensTensor,
                actualLengthTensor = actualLengthTensor,
                numBeams = 1,
                step = -1 // Single-beam doesn't need step logging
            )
        } finally {
            targetTokensTensor.close()
            actualLengthTensor.close()
        }
    }

    /**
     * Run decoder inference for multiple beams (batched).
     *
     * @param memory Encoder memory tensor [1, seq_len, hidden_dim]
     * @param beamTokens List of token sequences, one per beam
     * @param actualSrcLength Actual non-padded length of encoder input
     * @param decoderSeqLength Fixed decoder sequence length (e.g., 20)
     * @param step Current beam search step (for logging)
     * @return DecoderResult containing logits and performance metrics
     */
    fun decodeBatched(
        memory: OnnxTensor,
        beamTokens: List<List<Long>>,
        actualSrcLength: Int,
        decoderSeqLength: Int,
        step: Int = 0
    ): DecoderResult {
        val numBeams = beamTokens.size
        val targetTokensTensor = tensorFactory.createBatchedTargetTokensTensor(beamTokens, decoderSeqLength)

        // Prepare memory and src_length tensors based on broadcast mode
        val memoryTensor: OnnxTensor
        val actualLengthTensor: OnnxTensor

        if (broadcastEnabled) {
            // Broadcast mode: Pass memory as-is [1, seq_len, hidden_dim]
            // Model will expand internally to [num_beams, seq_len, hidden_dim]
            memoryTensor = memory
            actualLengthTensor = tensorFactory.createActualLengthTensor(actualSrcLength, numBeams = 1)

            if (step == 0 && enableDetailedLogging) {
                val memoryShape = memory.info.shape
                Log.d(TAG, "🚀 Broadcast mode: memory [1, ${memoryShape[1]}, ${memoryShape[2]}] → $numBeams beams")
            }
        } else {
            // Legacy mode: Manually replicate memory for all beams
            memoryTensor = tensorFactory.replicateMemoryForBeams(memory, numBeams)
            actualLengthTensor = tensorFactory.createActualLengthTensor(actualSrcLength, numBeams = numBeams)

            if (step == 0 && enableDetailedLogging) {
                Log.d(TAG, "🔄 Legacy mode: memory replicated to [$numBeams, seq_len, hidden_dim]")
            }
        }

        return try {
            // Log detailed input info on first step
            if (step == 0 && enableDetailedLogging) {
                Log.d(TAG, "=== DECODER INPUTS (step 0) ===")
                Log.d(TAG, "  memory: ${memoryTensor.info.shape.contentToString()}")
                Log.d(TAG, "  target_tokens: ${targetTokensTensor.info.shape.contentToString()}")
                Log.d(TAG, "  actual_src_length: ${actualLengthTensor.info.shape.contentToString()}")
                Log.d(TAG, "  actualSrcLength value: $actualSrcLength")
                Log.d(TAG, "  numBeams: $numBeams")
                Log.d(TAG, "  broadcastEnabled: $broadcastEnabled")
            }

            runDecoderInference(
                memory = memoryTensor,
                targetTokensTensor = targetTokensTensor,
                actualLengthTensor = actualLengthTensor,
                numBeams = numBeams,
                step = step
            )
        } finally {
            targetTokensTensor.close()
            actualLengthTensor.close()
            // Only close memory if we created a replicated version (legacy mode)
            if (!broadcastEnabled && memoryTensor != memory) {
                memoryTensor.close()
            }
        }
    }

    /**
     * Internal method to run decoder inference.
     *
     * @param memory Memory tensor (broadcast [1, ...] or replicated [num_beams, ...])
     * @param targetTokensTensor Target tokens tensor [num_beams, dec_seq_len]
     * @param actualLengthTensor Actual source length tensor [1] or [num_beams]
     * @param numBeams Number of beams being processed
     * @param step Current step (for logging)
     * @return DecoderResult with logits
     */
    private fun runDecoderInference(
        memory: OnnxTensor,
        targetTokensTensor: OnnxTensor,
        actualLengthTensor: OnnxTensor,
        numBeams: Int,
        step: Int
    ): DecoderResult {
        try {
            // Prepare inputs map
            val decoderInputs = mapOf(
                INPUT_MEMORY to memory,
                INPUT_TARGET_TOKENS to targetTokensTensor,
                INPUT_ACTUAL_SRC_LENGTH to actualLengthTensor
            )

            // Run decoder inference with timing
            val startTime = System.nanoTime()
            val decoderOutput = decoderSession.run(decoderInputs)
            val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000.0

            // Extract logits tensor [num_beams, dec_seq_len, vocab_size]
            val logitsTensor = decoderOutput.get(0) as? OnnxTensor
                ?: throw RuntimeException("Failed to extract logits tensor from decoder output")

            val logitsShape = logitsTensor.info.shape
            @Suppress("UNCHECKED_CAST")
            val logits3D = logitsTensor.value as Array<Array<FloatArray>>

            // Flatten for efficient access
            val vocabSize = logitsShape[2].toInt()
            val decSeqLen = logitsShape[1].toInt()
            val flatLogits = FloatArray(numBeams * decSeqLen * vocabSize)

            for (b in 0 until numBeams) {
                for (t in 0 until decSeqLen) {
                    System.arraycopy(
                        logits3D[b][t], 0,
                        flatLogits, (b * decSeqLen + t) * vocabSize,
                        vocabSize
                    )
                }
            }

            logitsTensor.close()
            decoderOutput.close()

            if (step >= 0 && enableDetailedLogging && step % 5 == 0) {
                Log.d(TAG, "✅ Decoder step $step: ${inferenceTimeMs}ms, logits shape: ${logitsShape.contentToString()}")
            }

            return DecoderResult(flatLogits, logitsShape, inferenceTimeMs)

        } catch (e: Exception) {
            Log.e(TAG, "Decoder inference failed at step $step", e)
            throw RuntimeException("Decoder inference failed: ${e.message}", e)
        }
    }

    /**
     * Validate decoder session is ready for inference.
     *
     * @throws IllegalStateException if session is closed or invalid
     */
    fun validateSession() {
        try {
            val inputNames = decoderSession.inputNames
            require(inputNames.containsAll(listOf(
                INPUT_MEMORY,
                INPUT_TARGET_TOKENS,
                INPUT_ACTUAL_SRC_LENGTH
            ))) {
                "Decoder session missing required inputs. Expected: " +
                        "[memory, target_tokens, actual_src_length], got: $inputNames"
            }

            val outputNames = decoderSession.outputNames
            require(outputNames.contains(OUTPUT_LOGITS)) {
                "Decoder session missing 'logits' output. Got: $outputNames"
            }

            Log.d(TAG, "✅ Decoder session validated (broadcast=${broadcastEnabled})")
        } catch (e: Exception) {
            throw IllegalStateException("Invalid decoder session: ${e.message}", e)
        }
    }

    /**
     * Get decoder model metadata.
     *
     * @return Map of metadata key-value pairs
     */
    fun getMetadata(): Map<String, String> {
        return try {
            mapOf(
                "inputs" to decoderSession.inputNames.toString(),
                "outputs" to decoderSession.outputNames.toString(),
                "input_count" to decoderSession.inputNames.size.toString(),
                "output_count" to decoderSession.outputNames.size.toString(),
                "broadcast_enabled" to broadcastEnabled.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get decoder metadata: ${e.message}")
            emptyMap()
        }
    }
}
