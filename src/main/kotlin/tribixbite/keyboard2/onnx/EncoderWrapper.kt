package tribixbite.keyboard2.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import tribixbite.keyboard2.SwipeTrajectoryProcessor

/**
 * Wrapper for encoder inference operations.
 *
 * The encoder processes trajectory features (positions, velocities, accelerations)
 * and nearest key sequences to produce a memory representation of the input gesture.
 *
 * This memory tensor is then fed to the decoder for sequence generation.
 *
 * Responsibilities:
 * - Prepare encoder input tensors (trajectory, nearest_keys, actual_length)
 * - Run encoder inference session
 * - Extract and validate memory output tensor
 * - Performance timing and logging
 *
 * Thread Safety: This class is NOT thread-safe. Should use a single OrtSession per thread.
 */
class EncoderWrapper(
    private val encoderSession: OrtSession,
    private val tensorFactory: TensorFactory,
    private val ortEnvironment: OrtEnvironment,
    private val enableDetailedLogging: Boolean = false
) {

    companion object {
        private const val TAG = "EncoderWrapper"

        // Encoder input names (ONNX model graph)
        private const val INPUT_TRAJECTORY_FEATURES = "trajectory_features"
        private const val INPUT_NEAREST_KEYS = "nearest_keys"
        private const val INPUT_ACTUAL_LENGTH = "actual_length"

        // Encoder output names
        private const val OUTPUT_MEMORY = "memory"
    }

    /**
     * Result of encoder inference.
     *
     * @param memory Encoded representation [1, seq_len, hidden_dim]
     * @param inferenceTimeMs Time taken for inference in milliseconds
     */
    data class EncoderResult(
        val memory: OnnxTensor,
        val inferenceTimeMs: Double
    )

    /**
     * Run encoder inference on trajectory features.
     *
     * @param features Processed trajectory features with normalized points and nearest keys
     * @return EncoderResult containing memory tensor and performance metrics
     * @throws RuntimeException if inference fails
     */
    fun encode(features: SwipeTrajectoryProcessor.TrajectoryFeatures): EncoderResult {
        var trajectoryTensor: OnnxTensor? = null
        var nearestKeysTensor: OnnxTensor? = null
        var actualLengthTensor: OnnxTensor? = null

        try {
            // Prepare encoder input tensors
            trajectoryTensor = tensorFactory.createTrajectoryTensor(features)
            nearestKeysTensor = tensorFactory.createNearestKeysTensor(features)
            actualLengthTensor = OnnxTensor.createTensor(ortEnvironment, intArrayOf(features.actualLength))

            // Log tensor shapes only if detailed logging enabled
            if (enableDetailedLogging) {
                Log.d(TAG, "🔧 Encoder input tensor shapes (actualLength=${features.actualLength}):")
                Log.d(TAG, "   trajectory_features: ${trajectoryTensor.info.shape.contentToString()}")
                Log.d(TAG, "   nearest_keys: ${nearestKeysTensor.info.shape.contentToString()}")
                Log.d(TAG, "   actual_length: ${actualLengthTensor.info.shape.contentToString()}")
            }

            // Prepare inputs map
            val encoderInputs = mapOf(
                INPUT_TRAJECTORY_FEATURES to trajectoryTensor,
                INPUT_NEAREST_KEYS to nearestKeysTensor,
                INPUT_ACTUAL_LENGTH to actualLengthTensor
            )

            // Run encoder inference with timing
            val startTime = System.nanoTime()
            val encoderResults = encoderSession.run(encoderInputs)
            val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000.0

            // Extract memory tensor from results
            val memory = encoderResults.get(0) as? OnnxTensor
                ?: throw RuntimeException("Failed to extract memory tensor from encoder output")

            // Validate memory tensor shape [1, seq_len, hidden_dim]
            val memoryShape = memory.info.shape
            require(memoryShape.size == 3) {
                "Invalid memory shape: expected [1, seq_len, hidden_dim], got ${memoryShape.contentToString()}"
            }
            require(memoryShape[0] == 1L) {
                "Invalid memory batch size: expected 1, got ${memoryShape[0]}"
            }

            if (enableDetailedLogging) {
                Log.d(TAG, "✅ Encoder inference complete: ${inferenceTimeMs}ms")
                Log.d(TAG, "   memory shape: ${memoryShape.contentToString()}")
            }

            return EncoderResult(memory, inferenceTimeMs)

        } catch (e: Exception) {
            Log.e(TAG, "Encoder inference failed", e)
            throw RuntimeException("Encoder inference failed: ${e.message}", e)
        } finally {
            // Clean up input tensors (output memory tensor is owned by caller)
            trajectoryTensor?.close()
            nearestKeysTensor?.close()
            actualLengthTensor?.close()
        }
    }

    /**
     * Validate encoder session is ready for inference.
     *
     * @throws IllegalStateException if session is closed or invalid
     */
    fun validateSession() {
        try {
            // Check session metadata is accessible
            val inputNames = encoderSession.inputNames
            require(inputNames.containsAll(listOf(
                INPUT_TRAJECTORY_FEATURES,
                INPUT_NEAREST_KEYS,
                INPUT_ACTUAL_LENGTH
            ))) {
                "Encoder session missing required inputs. Expected: " +
                        "[trajectory_features, nearest_keys, actual_length], got: $inputNames"
            }

            val outputNames = encoderSession.outputNames
            require(outputNames.contains(OUTPUT_MEMORY)) {
                "Encoder session missing 'memory' output. Got: $outputNames"
            }

            Log.d(TAG, "✅ Encoder session validated")
        } catch (e: Exception) {
            throw IllegalStateException("Invalid encoder session: ${e.message}", e)
        }
    }

    /**
     * Get encoder model metadata.
     *
     * @return Map of metadata key-value pairs
     */
    fun getMetadata(): Map<String, String> {
        return try {
            mapOf(
                "inputs" to encoderSession.inputNames.toString(),
                "outputs" to encoderSession.outputNames.toString(),
                "input_count" to encoderSession.inputNames.size.toString(),
                "output_count" to encoderSession.outputNames.size.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get encoder metadata: ${e.message}")
            emptyMap()
        }
    }
}
