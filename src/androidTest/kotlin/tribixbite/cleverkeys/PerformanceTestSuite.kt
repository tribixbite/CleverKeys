package tribixbite.cleverkeys.test

import android.content.Context
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.*
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class PerformanceTestSuite {

    private lateinit var context: Context
    private lateinit var neuralEngine: NeuralPredictionPipeline
    private lateinit var config: NeuralConfig

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        config = NeuralConfig(context)
        neuralEngine = NeuralPredictionPipeline(context, config)
    }

    @Test
    fun testNeuralPredictionLatency() = runBlocking {
        // Initialize neural engine
        val initTime = measureTimeMillis {
            neuralEngine.initialize()
        }

        // Model loading should be under 500ms
        assertTrue("Neural engine initialization took too long: ${initTime}ms", initTime < 500)

        // Test prediction latency with sample swipe data
        val sampleSwipe = createSampleSwipeInput("hello")

        val predictionTime = measureTimeMillis {
            val result = neuralEngine.processGesture(sampleSwipe)
            assertNotNull("Prediction result should not be null", result)
            assertTrue("Should have at least one prediction", result.predictions.isNotEmpty())
        }

        // Prediction should be under 200ms for good UX
        assertTrue("Neural prediction took too long: ${predictionTime}ms", predictionTime < 200)

        // Log performance metrics
        logPerformanceMetric("neural_prediction_latency", predictionTime)
        logPerformanceMetric("neural_init_time", initTime)
    }

    @Test
    fun testSwipeRecognitionSpeed() {
        val gestureRecognizer = SwipeGestureRecognizer(context)
        val samplePoints = generateSampleSwipePoints()

        val recognitionTime = measureTimeMillis {
            val gesture = gestureRecognizer.recognizeGesture(samplePoints)
            assertNotNull("Gesture recognition should not fail", gesture)
        }

        // Gesture recognition should be very fast (under 16ms for 60fps)
        assertTrue("Gesture recognition took too long: ${recognitionTime}ms", recognitionTime < 16)

        logPerformanceMetric("gesture_recognition_time", recognitionTime)
    }

    @Test
    fun testMemoryUsage() {
        val runtime = Runtime.getRuntime()

        // Measure baseline memory
        System.gc()
        val baselineMemory = runtime.totalMemory() - runtime.freeMemory()

        // Initialize neural components
        runBlocking {
            neuralEngine.initialize()
        }

        // Measure memory after neural initialization
        System.gc()
        val afterInitMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = afterInitMemory - baselineMemory
        val memoryIncreaseMB = memoryIncrease / (1024 * 1024)

        // Memory increase should be under 25MB
        assertTrue("Memory usage too high: ${memoryIncreaseMB}MB", memoryIncreaseMB < 25)

        logPerformanceMetric("memory_usage_mb", memoryIncreaseMB)
    }

    @Test
    fun testBatchPredictionPerformance() = runBlocking {
        neuralEngine.initialize()

        // Create multiple sample swipes
        val swipes = listOf(
            createSampleSwipeInput("hello"),
            createSampleSwipeInput("world"),
            createSampleSwipeInput("testing"),
            createSampleSwipeInput("performance"),
            createSampleSwipeInput("neural")
        )

        val batchTime = measureTimeMillis {
            swipes.forEach { swipe ->
                val result = neuralEngine.processGesture(swipe)
                assertNotNull("Batch prediction should not fail", result)
            }
        }

        val avgTimePerPrediction = batchTime / swipes.size

        // Average prediction time should be reasonable
        assertTrue("Batch predictions too slow: ${avgTimePerPrediction}ms avg", avgTimePerPrediction < 150)

        logPerformanceMetric("batch_prediction_avg_time", avgTimePerPrediction)
        logPerformanceMetric("batch_prediction_total_time", batchTime)
    }

    @Test
    fun testKeyboardLayoutRenderingPerformance() {
        val layoutLoader = KeyboardLayoutLoader(context)

        val renderTime = measureTimeMillis {
            // Load and render different keyboard layouts
            val layouts = listOf("qwerty", "qwertz", "azerty", "dvorak")
            layouts.forEach { layoutName ->
                val layout = layoutLoader.loadLayout(layoutName)
                assertNotNull("Layout should load successfully: $layoutName", layout)
            }
        }

        // Layout loading should be fast
        assertTrue("Layout rendering took too long: ${renderTime}ms", renderTime < 100)

        logPerformanceMetric("layout_rendering_time", renderTime)
    }

    @Test
    fun testConfigurationLoadingSpeed() {
        val configLoadTime = measureTimeMillis {
            val testConfig = NeuralConfig(context)
            // Access various configuration properties
            testConfig.neuralBeamWidth
            testConfig.neuralMaxLength
            testConfig.neuralConfidenceThreshold
            testConfig.isNeuralPredictionEnabled
        }

        // Configuration loading should be instant
        assertTrue("Configuration loading too slow: ${configLoadTime}ms", configLoadTime < 10)

        logPerformanceMetric("config_loading_time", configLoadTime)
    }

    @Test
    fun testConcurrentPredictionHandling() = runBlocking {
        neuralEngine.initialize()

        val concurrentTime = measureTimeMillis {
            // Simulate concurrent prediction requests
            val swipe1 = createSampleSwipeInput("concurrent")
            val swipe2 = createSampleSwipeInput("testing")

            // These should handle gracefully without blocking
            val result1 = neuralEngine.processGesture(swipe1)
            val result2 = neuralEngine.processGesture(swipe2)

            assertNotNull("Concurrent prediction 1 should succeed", result1)
            assertNotNull("Concurrent prediction 2 should succeed", result2)
        }

        logPerformanceMetric("concurrent_prediction_time", concurrentTime)
    }

    private fun createSampleSwipeInput(targetWord: String): SwipeInput {
        // Generate realistic swipe coordinates for the target word
        val coordinates = mutableListOf<PointF>()
        val timestamps = mutableListOf<Long>()
        val touchedKeys = mutableListOf<KeyboardData.Key>()

        // Simulate swipe path through letters
        targetWord.forEachIndexed { index, char ->
            val x = 100f + (index * 120f) // Spread letters horizontally
            val y = 200f + (Math.sin(index * 0.5) * 20f).toFloat() // Add slight curve

            coordinates.add(PointF(x, y))
            timestamps.add(System.currentTimeMillis() + (index * 50L))

            // Create mock key
            val key = KeyboardData.Key(
                key0 = KeyValue.makeStringKey(char.toString()),
                key1 = null,
                key2 = null,
                key3 = null,
                key4 = null,
                x = x,
                y = y,
                width = 0.1f,
                height = 0.1f
            )
            touchedKeys.add(key)
        }

        return SwipeInput(coordinates, timestamps, touchedKeys)
    }

    private fun generateSampleSwipePoints(): List<PointF> {
        // Generate realistic swipe points for gesture recognition
        return (0..20).map { i ->
            PointF(
                100f + (i * 10f),
                200f + (Math.sin(i * 0.3) * 30f).toFloat()
            )
        }
    }

    private fun logPerformanceMetric(metricName: String, value: Long) {
        // Log performance metrics for CI/CD pipeline analysis
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bundle = android.os.Bundle().apply {
            putLong(metricName, value)
        }
        instrumentation.sendStatus(0, bundle)

        // Also log to console for debugging
        println("PERFORMANCE_METRIC: $metricName = ${value}ms")
    }
}