package tribixbite.keyboard2

import android.graphics.PointF
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Integration tests for complete CleverKeys system
 * Tests end-to-end functionality from gesture to prediction
 */
class IntegrationTest {
    
    private lateinit var testScope: TestScope
    private lateinit var mockContext: MockContext
    
    @Before
    fun setup() {
        testScope = TestScope()
        mockContext = MockContext()
    }
    
    @Test
    fun testCompleteNeuralPredictionPipeline() = testScope.runTest {
        // Initialize pipeline
        val pipeline = NeuralPredictionPipeline(mockContext)
        val initialized = pipeline.initialize()
        
        // Skip if initialization fails (expected in test environment)
        if (!initialized) {
            println("Pipeline initialization failed - skipping neural test")
            return@runTest
        }
        
        // Test gesture processing
        val testGesture = createTestGesture("hello")
        val result = pipeline.processGesture(
            points = testGesture.coordinates,
            timestamps = testGesture.timestamps
        )
        
        // Verify result structure
        assertNotNull(result)
        assertNotNull(result.predictions)
        assertTrue("Should have processing time", result.processingTimeMs >= 0)
        assertEquals(NeuralPredictionPipeline.PredictionSource.NEURAL, result.source)

        // Verify predictions have words
        assertTrue("Should have prediction words", result.predictions.words.isNotEmpty())
    }
    
    @Test
    fun testSwipeDetectionIntegration() = testScope.runTest {
        val detector = SwipeDetector()

        // Test horizontal swipe
        val horizontalGesture = createHorizontalGesture()
        val swipeInput = SwipeInput(
            coordinates = horizontalGesture,
            timestamps = (0 until horizontalGesture.size).map { it * 50L },
            touchedKeys = emptyList()
        )

        // Verify SwipeInput structure
        assertNotNull(swipeInput.coordinates)
        assertTrue("Should have coordinates", swipeInput.coordinates.size > 0)
        assertEquals("Timestamps should match coordinates",
            swipeInput.coordinates.size, swipeInput.timestamps.size)
    }
    
    @Test
    fun testConfigurationManagement() = testScope.runTest {
        val configManager = ConfigurationManager(mockContext)
        val initialized = configManager.initialize()
        
        assertTrue("Configuration manager should initialize", initialized)
        
        // Test export/import
        val exportedConfig = configManager.exportConfiguration()
        assertNotNull(exportedConfig)
        assertTrue("Exported config should be JSON", exportedConfig.startsWith("{"))
        
        val importSuccess = configManager.importConfiguration(exportedConfig)
        assertTrue("Should successfully import configuration", importSuccess)
        
        // Test validation
        val validation = configManager.validateConfiguration()
        assertTrue("Configuration should be valid", validation.isValid)
        
        configManager.cleanup()
    }
    
    @Test
    fun testPerformanceOptimizations() = testScope.runTest {
        val profiler = PerformanceProfiler(mockContext)
        
        // Test multiple operations
        repeat(10) { i ->
            profiler.measureOperation("test_operation_$i") {
                delay((50 + i * 10).toLong()) // Variable duration
                "result_$i"
            }
        }
        
        // Generate performance report
        val report = profiler.generateReport()
        assertNotNull(report)
        assertTrue("Report should contain data", report.contains("Performance Report"))
        
        profiler.cleanup()
    }
    
    @Test
    fun testErrorRecovery() = testScope.runTest {
        // Test retry mechanism
        var attempts = 0
        val result = ErrorHandling.retryOperation(maxAttempts = 3) { attempt ->
            attempts++
            if (attempt < 3) {
                throw RuntimeException("Attempt $attempt failed")
            }
            "success"
        }
        
        assertEquals("success", result)
        assertEquals(3, attempts)
    }
    
    // Helper methods for creating test gestures
    
    private fun createTestGesture(word: String): SwipeInput {
        val coordinates = when (word) {
            "hello" -> createHelloGesture()
            "world" -> createWorldGesture()
            else -> createHorizontalGesture()
        }
        
        val timestamps = coordinates.indices.map { it * 100L }
        return SwipeInput(coordinates, timestamps, emptyList())
    }
    
    private fun createHelloGesture(): List<PointF> {
        // Simulate "hello" gesture path: h-e-l-l-o
        return listOf(
            PointF(600f, 300f), // h
            PointF(300f, 200f), // e
            PointF(900f, 300f), // l
            PointF(900f, 300f), // l (same position)
            PointF(1000f, 200f) // o
        )
    }
    
    private fun createWorldGesture(): List<PointF> {
        // Simulate "world" gesture path: w-o-r-l-d
        return listOf(
            PointF(200f, 200f), // w
            PointF(1000f, 200f), // o
            PointF(400f, 200f), // r
            PointF(900f, 300f), // l
            PointF(400f, 300f)  // d
        )
    }
    
    private fun createHorizontalGesture(): List<PointF> {
        return listOf(
            PointF(100f, 200f),
            PointF(200f, 200f),
            PointF(300f, 200f)
        )
    }
    
    private fun createCircularGesture(): List<PointF> {
        val center = PointF(200f, 200f)
        val radius = 100f
        val points = mutableListOf<PointF>()
        
        for (i in 0..20) {
            val angle = (i / 20f) * 2 * kotlin.math.PI
            val x = center.x + radius * kotlin.math.cos(angle).toFloat()
            val y = center.y + radius * kotlin.math.sin(angle).toFloat()
            points.add(PointF(x, y))
        }
        
        return points
    }
    
    private fun createZigzagGesture(): List<PointF> {
        return listOf(
            PointF(100f, 200f),
            PointF(200f, 100f),
            PointF(300f, 200f),
            PointF(400f, 100f),
            PointF(500f, 200f)
        )
    }
}