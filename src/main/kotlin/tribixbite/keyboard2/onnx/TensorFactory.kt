package tribixbite.keyboard2.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import tribixbite.keyboard2.SwipeTrajectoryProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.FloatBuffer

/**
 * Factory for creating ONNX tensors from trajectory features and decoder state.
 *
 * This class encapsulates all tensor creation logic, including:
 * - Trajectory feature tensors (position, velocity, acceleration)
 * - Nearest keys sequence tensors
 * - Source mask tensors (for padding)
 * - Target token tensors (for decoder input)
 *
 * All tensors use direct ByteBuffers for optimal performance with ONNX Runtime.
 *
 * Thread Safety: This class is stateless and thread-safe.
 */
class TensorFactory(
    private val ortEnvironment: OrtEnvironment,
    private val maxSequenceLength: Int,
    private val trajectoryFeatures: Int = 6 // x, y, vx, vy, ax, ay
) {

    companion object {
        private const val PAD_IDX = 0L
    }

    /**
     * Create trajectory tensor from processed trajectory features.
     *
     * Shape: [1, max_seq_length, trajectory_features]
     * Data: x, y, vx, vy, ax, ay for each point (padded with zeros)
     *
     * @param features Processed trajectory features with normalized points
     * @return ONNX tensor containing trajectory data
     */
    fun createTrajectoryTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create direct buffer as recommended by ONNX docs
        val byteBuffer = ByteBuffer.allocateDirect(maxSequenceLength * trajectoryFeatures * 4) // 4 bytes per float
        byteBuffer.order(ByteOrder.nativeOrder())
        val buffer: FloatBuffer = byteBuffer.asFloatBuffer()

        for (i in 0 until maxSequenceLength) {
            if (i < features.normalizedPoints.size) {
                val point = features.normalizedPoints[i]
                buffer.put(point.x)
                buffer.put(point.y)
                buffer.put(point.vx)
                buffer.put(point.vy)
                buffer.put(point.ax)
                buffer.put(point.ay)
            } else {
                // Padding with zeros
                buffer.put(0.0f) // x
                buffer.put(0.0f) // y
                buffer.put(0.0f) // vx
                buffer.put(0.0f) // vy
                buffer.put(0.0f) // ax
                buffer.put(0.0f) // ay
            }
        }

        buffer.rewind()
        val shape = longArrayOf(1, maxSequenceLength.toLong(), trajectoryFeatures.toLong())
        return OnnxTensor.createTensor(ortEnvironment, buffer, shape)
    }

    /**
     * Create nearest keys tensor from token indices.
     *
     * Shape: [1, max_seq_length]
     * Data: Token indices for nearest key at each trajectory point (padded with PAD_IDX)
     *
     * @param features Processed trajectory features with nearest key token indices
     * @return ONNX tensor containing key sequence data
     */
    fun createNearestKeysTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create direct buffer - models expect int32, not int64
        val byteBuffer = ByteBuffer.allocateDirect(maxSequenceLength * 4) // 4 bytes per int
        byteBuffer.order(ByteOrder.nativeOrder())
        val buffer: IntBuffer = byteBuffer.asIntBuffer()

        for (i in 0 until maxSequenceLength) {
            if (i < features.nearestKeys.size) {
                val tokenIndex = features.nearestKeys[i]
                buffer.put(tokenIndex)
            } else {
                buffer.put(PAD_IDX.toInt()) // Padding (should never hit this - features are pre-padded)
            }
        }

        buffer.rewind()
        val shape = longArrayOf(1, maxSequenceLength.toLong())
        return OnnxTensor.createTensor(ortEnvironment, buffer, shape)
    }

    /**
     * Create source mask tensor for padding positions.
     *
     * Shape: [1, max_seq_length]
     * Data: Boolean mask (true = masked/padded, false = valid)
     *
     * @param features Processed trajectory features with actual length
     * @return ONNX tensor containing source mask
     */
    fun createSourceMaskTensor(features: SwipeTrajectoryProcessor.TrajectoryFeatures): OnnxTensor {
        // Create 2D boolean array for proper tensor shape [1, max_seq_length]
        val maskData = Array(1) { BooleanArray(maxSequenceLength) }

        // Mask padded positions (true = masked/padded, false = valid)
        for (i in 0 until maxSequenceLength) {
            maskData[0][i] = (i >= features.actualLength)
        }

        // Use 2D boolean array - ONNX API will infer shape as [1, max_seq_length]
        return OnnxTensor.createTensor(ortEnvironment, maskData)
    }

    /**
     * Create target tokens tensor for decoder input.
     *
     * Shape: [batch_size, decoder_seq_length]
     * Data: Token sequence for current beam(s) (padded with PAD_IDX)
     *
     * @param tokens List of token indices for current beam
     * @param decoderSeqLength Fixed decoder sequence length (e.g., 20)
     * @return ONNX tensor containing target tokens
     */
    fun createTargetTokensTensor(
        tokens: List<Long>,
        decoderSeqLength: Int
    ): OnnxTensor {
        val tokenArray = IntArray(decoderSeqLength)
        for (i in 0 until decoderSeqLength) {
            tokenArray[i] = if (i < tokens.size) {
                tokens[i].toInt()
            } else {
                PAD_IDX.toInt()
            }
        }

        val shape = longArrayOf(1, decoderSeqLength.toLong())
        return OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(tokenArray), shape)
    }

    /**
     * Create batched target tokens tensor for parallel beam processing.
     *
     * Shape: [num_beams, decoder_seq_length]
     * Data: Token sequences for all active beams (padded with PAD_IDX)
     *
     * @param beamTokens List of token sequences, one per beam
     * @param decoderSeqLength Fixed decoder sequence length (e.g., 20)
     * @return ONNX tensor containing batched target tokens
     */
    fun createBatchedTargetTokensTensor(
        beamTokens: List<List<Long>>,
        decoderSeqLength: Int
    ): OnnxTensor {
        val numBeams = beamTokens.size
        val flatTokens = IntArray(numBeams * decoderSeqLength)

        for (b in 0 until numBeams) {
            val tokens = beamTokens[b]
            for (i in 0 until decoderSeqLength) {
                flatTokens[b * decoderSeqLength + i] = if (i < tokens.size) {
                    tokens[i].toInt()
                } else {
                    PAD_IDX.toInt()
                }
            }
        }

        val shape = longArrayOf(numBeams.toLong(), decoderSeqLength.toLong())
        return OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(flatTokens), shape)
    }

    /**
     * Create actual source length tensor (scalar or batch).
     *
     * Shape: [1] or [num_beams]
     * Data: Actual non-padded length of encoder input
     *
     * @param actualLength Actual length of trajectory sequence
     * @param numBeams Number of beams (1 for broadcast models, beam_width for legacy)
     * @return ONNX tensor containing source length
     */
    fun createActualLengthTensor(actualLength: Int, numBeams: Int = 1): OnnxTensor {
        return if (numBeams == 1) {
            // Broadcast model: single value
            OnnxTensor.createTensor(ortEnvironment, intArrayOf(actualLength))
        } else {
            // Legacy model: replicate for all beams
            val srcLengths = IntArray(numBeams) { actualLength }
            OnnxTensor.createTensor(ortEnvironment, srcLengths)
        }
    }

    /**
     * Replicate encoder memory for all beams (legacy models only).
     *
     * Broadcast-enabled models handle this internally and should NOT use this method.
     *
     * Shape: [num_beams, seq_len, hidden_dim]
     * Data: Memory tensor replicated across all beams
     *
     * @param memory Original encoder memory tensor [1, seq_len, hidden_dim]
     * @param numBeams Number of beams to replicate for
     * @return ONNX tensor containing replicated memory
     */
    @Suppress("UNCHECKED_CAST")
    fun replicateMemoryForBeams(memory: OnnxTensor, numBeams: Int): OnnxTensor {
        val memoryShape = memory.info.shape // [1, seq_len, hidden_dim]
        val memorySeqLen = memoryShape[1].toInt()

        val memoryData = memory.value as Array<Array<FloatArray>>
        val replicatedMemory = Array(numBeams) {
            Array(memorySeqLen) { s ->
                memoryData[0][s].copyOf()
            }
        }

        return OnnxTensor.createTensor(ortEnvironment, replicatedMemory)
    }

    /**
     * Validate tensor shape matches expected dimensions.
     *
     * @param tensor Tensor to validate
     * @param expectedShape Expected shape dimensions
     * @throws IllegalStateException if shape mismatch
     */
    fun validateTensorShape(tensor: OnnxTensor, expectedShape: LongArray) {
        val actualShape = tensor.info.shape
        require(actualShape.contentEquals(expectedShape)) {
            "Tensor shape mismatch: expected ${expectedShape.contentToString()}, got ${actualShape.contentToString()}"
        }
    }
}
