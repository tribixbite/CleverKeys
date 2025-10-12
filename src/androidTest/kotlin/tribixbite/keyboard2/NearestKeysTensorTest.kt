package tribixbite.keyboard2

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Instrumentation test to validate nearest_keys tensor format
 * Runs on actual Android device with real ONNX models
 */
@RunWith(AndroidJUnit4::class)
class NearestKeysTensorTest {

    private lateinit var ortEnvironment: OrtEnvironment
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        const val MAX_SEQUENCE_LENGTH = 150
        const val PAD_IDX = 0L
    }

    @Before
    fun setup() {
        ortEnvironment = OrtEnvironment.getEnvironment()
    }

    @Test
    fun testTensorShape2D() {
        // Create mock nearest keys data (top 3 per point)
        val mockNearestKeys = List(MAX_SEQUENCE_LENGTH) {
            listOf(5, 10, 15)
        }

        val tensor = createNearestKeysTensor2D(mockNearestKeys)
        val shape = tensor.info.shape

        // Verify shape is [1, 150] (2D)
        assertEquals("Tensor should have 2 dimensions", 2, shape.size)
        assertEquals("Batch size should be 1", 1L, shape[0])
        assertEquals("Sequence length should be 150", 150L, shape[1])

        tensor.close()
    }

    @Test
    fun testUsesOnlyFirstKey() {
        // Test that we use only the first (closest) key
        val testKeys = listOf(
            listOf(7, 99, 88),  // Should use only 7
            listOf(3, 77, 66),  // Should use only 3
            listOf(2, 55, 44)   // Should use only 2
        )

        val tensor = createNearestKeysTensor2D(testKeys)
        val buffer = tensor.longBuffer

        assertEquals("First key should be 7", 7L, buffer.get(0))
        assertEquals("Second key should be 3", 3L, buffer.get(1))
        assertEquals("Third key should be 2", 2L, buffer.get(2))

        tensor.close()
    }

    @Test
    fun testPaddingBehavior() {
        // Test padding with PAD_IDX for sequences shorter than MAX_SEQUENCE_LENGTH
        val shortKeys = listOf(
            listOf(5, 10, 15),
            listOf(8, 12, 20)
        ) // Only 2 points

        val tensor = createNearestKeysTensor2D(shortKeys)
        val buffer = tensor.longBuffer

        // Check valid positions
        assertEquals("First valid key", 5L, buffer.get(0))
        assertEquals("Second valid key", 8L, buffer.get(1))

        // Check padded positions
        assertEquals("First padded position should be PAD_IDX", PAD_IDX, buffer.get(2))
        assertEquals("Second padded position should be PAD_IDX", PAD_IDX, buffer.get(3))
        assertEquals("Last position should be PAD_IDX", PAD_IDX, buffer.get(149))

        tensor.close()
    }

    @Test
    fun testEmptyInput() {
        // Test with completely empty input (all padding)
        val emptyKeys = emptyList<List<Int>>()

        val tensor = createNearestKeysTensor2D(emptyKeys)
        val buffer = tensor.longBuffer

        // All positions should be PAD_IDX
        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            assertEquals("Position $i should be PAD_IDX", PAD_IDX, buffer.get(i))
        }

        tensor.close()
    }

    @Test
    fun testOnnxModelInputCompatibility() {
        // Load actual ONNX encoder model and verify it accepts 2D nearest_keys
        val modelPath = "models/swipe_model_character_quant.onnx"

        try {
            val modelInputStream = context.assets.open(modelPath)
            val modelBytes = modelInputStream.readBytes()
            modelInputStream.close()

            val session = ortEnvironment.createSession(
                modelBytes,
                OrtSession.SessionOptions()
            )

            // Check model inputs
            assertTrue("Model should have nearest_keys input",
                session.inputNames.contains("nearest_keys"))

            val nearestKeysInfo = session.inputInfo["nearest_keys"]
            assertNotNull("nearest_keys input info should exist", nearestKeysInfo)

            val tensorInfo = nearestKeysInfo?.info as? TensorInfo
            assertNotNull("Should be a TensorInfo", tensorInfo)

            val shape = tensorInfo?.shape
            assertNotNull("Shape should exist", shape)

            // Verify model expects 2D input
            assertEquals("Model should expect 2D nearest_keys", 2, shape?.size)

            println("✅ Model input validation:")
            println("   nearest_keys shape: [${shape?.joinToString(", ")}]")

            session.close()

        } catch (e: Exception) {
            fail("Failed to load or validate ONNX model: ${e.message}")
        }
    }

    @Test
    fun testFullPredictionPipeline() {
        // Integration test: Create real tensor and verify ONNX inference works
        val modelPath = "models/swipe_model_character_quant.onnx"

        try {
            val modelInputStream = context.assets.open(modelPath)
            val modelBytes = modelInputStream.readBytes()
            modelInputStream.close()

            val session = ortEnvironment.createSession(
                modelBytes,
                OrtSession.SessionOptions()
            )

            // Create mock input data
            val mockTrajectoryFeatures = createMockTrajectoryTensor()
            val mockNearestKeys = createMockNearestKeysTensor()
            val mockSourceMask = createMockSourceMaskTensor()

            // Run inference
            val inputs = mapOf(
                "trajectory_features" to mockTrajectoryFeatures,
                "nearest_keys" to mockNearestKeys,
                "src_mask" to mockSourceMask
            )

            val outputs = session.run(inputs)

            // Verify we got output
            assertTrue("Should receive encoder output", outputs.isNotEmpty())

            val encoderOutput = outputs[0].value as Array<*>
            assertNotNull("Encoder output should not be null", encoderOutput)

            println("✅ Full pipeline test:")
            println("   Encoder output shape: ${outputs[0].info.shape.contentToString()}")

            // Cleanup
            outputs.forEach { it.close() }
            mockTrajectoryFeatures.close()
            mockNearestKeys.close()
            mockSourceMask.close()
            session.close()

        } catch (e: Exception) {
            fail("Prediction pipeline failed: ${e.message}\n${e.stackTraceToString()}")
        }
    }

    // Helper functions

    private fun createNearestKeysTensor2D(nearestKeys: List<List<Int>>): OnnxTensor {
        val byteBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8)
        byteBuffer.order(ByteOrder.nativeOrder())
        val buffer = byteBuffer.asLongBuffer()

        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            if (i < nearestKeys.size) {
                val top3Keys = nearestKeys[i]
                val keyIndex = top3Keys.getOrNull(0)?.toLong() ?: PAD_IDX
                buffer.put(keyIndex)
            } else {
                buffer.put(PAD_IDX)
            }
        }

        buffer.rewind()
        return OnnxTensor.createTensor(ortEnvironment, buffer, longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong()))
    }

    private fun createMockTrajectoryTensor(): OnnxTensor {
        // Create [1, 150, 6] float tensor
        val data = FloatArray(1 * 150 * 6) { 0.5f } // Mock normalized features
        return OnnxTensor.createTensor(
            ortEnvironment,
            data,
            longArrayOf(1, 150, 6)
        )
    }

    private fun createMockNearestKeysTensor(): OnnxTensor {
        // Create mock nearest keys (using key index 13 = 'q')
        val mockKeys = List(150) { listOf(13, 22, 4) }
        return createNearestKeysTensor2D(mockKeys)
    }

    private fun createMockSourceMaskTensor(): OnnxTensor {
        // Create [1, 150] boolean mask (false = valid, true = masked)
        val maskData = Array(1) { BooleanArray(150) { i -> i >= 50 } }
        return OnnxTensor.createTensor(ortEnvironment, maskData)
    }
}
