package tribixbite.keyboard2

import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive tests for neural prediction system
 * Kotlin implementation with coroutine testing and Robolectric
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NeuralPredictionTest {

    private lateinit var testScope: TestScope

    @Before
    fun setup() {
        testScope = TestScope()
    }
    
    @Test
    fun testSwipeInputCreation() = testScope.runTest {
        // Test data
        val points = listOf(
            PointF(100f, 200f),
            PointF(150f, 200f),
            PointF(200f, 200f)
        )
        val timestamps = listOf(0L, 100L, 200L)
        
        // Create SwipeInput
        val swipeInput = SwipeInput(points, timestamps, emptyList())
        
        // Assertions
        assertEquals(3, swipeInput.coordinates.size)
        assertEquals(3, swipeInput.timestamps.size)
        assertTrue(swipeInput.pathLength > 0)
        assertTrue(swipeInput.duration > 0)
        assertEquals(100f, swipeInput.pathLength, 1f)
    }
    
    @Test
    fun testGestureClassification() = testScope.runTest {
        val detector = SwipeDetector()
        
        // Test horizontal swipe
        val horizontalSwipe = SwipeInput(
            coordinates = listOf(
                PointF(100f, 200f),
                PointF(200f, 200f),
                PointF(300f, 200f)
            ),
            timestamps = listOf(0L, 100L, 200L),
            touchedKeys = emptyList()
        )
        
        val classification = detector.detectSwipe(horizontalSwipe)
        
        assertTrue("Should detect as swipe", classification.isSwipe)
        assertTrue("Should have reasonable confidence", classification.confidence > 0.1f)
        assertEquals(SwipeDetector.SwipeQuality.FAIR, classification.quality)
    }
    
    @Test
    fun testPredictionResultOperations() = testScope.runTest {
        val result = PredictionResult(
            words = listOf("hello", "world", "test"),
            scores = listOf(900, 800, 700)
        )
        
        // Test convenience methods
        assertEquals("hello", result.topPrediction)
        assertEquals(900, result.topScore)
        assertEquals(3, result.size)
        assertFalse(result.isEmpty)
        
        // Test filtering
        val filtered = result.filterByScore(750)
        assertEquals(2, filtered.size)
        assertEquals(listOf("hello", "world"), filtered.words)
    }
    
    @Test
    fun testTrajectoryFeatureExtraction() = testScope.runTest {
        val processor = SwipeTrajectoryProcessor()
        
        val coordinates = listOf(
            PointF(0f, 0f),
            PointF(100f, 100f),
            PointF(200f, 0f)
        )
        val timestamps = listOf(0L, 100L, 200L)
        
        val features = processor.extractFeatures(coordinates, timestamps)
        
        assertNotNull(features)
        assertEquals(3, features.actualLength)
        assertTrue(features.velocities.isNotEmpty())
        assertTrue(features.accelerations.isNotEmpty())
        assertTrue(features.nearestKeys.isNotEmpty())
        assertTrue(features.normalizedCoordinates.isNotEmpty())
    }
    
    @Test
    fun testConfigurationValidation() = testScope.runTest {
        // Test valid configuration
        val mockPrefs = MockSharedPreferences().apply {
            putInt("neural_beam_width", 8)
            putInt("neural_max_length", 35)
            putFloat("neural_confidence_threshold", 0.1f)
        }
        
        val config = NeuralConfig(mockPrefs)
        val validation = ErrorHandling.Validation.validateNeuralConfig(config)
        
        assertTrue("Valid config should pass validation", validation.isValid)
        assertTrue("Should have no errors", validation.errors.isEmpty())
        
        // Test invalid configuration
        config.beamWidth = 100 // Out of range
        val invalidValidation = ErrorHandling.Validation.validateNeuralConfig(config)
        
        assertFalse("Invalid config should fail validation", invalidValidation.isValid)
        assertTrue("Should have errors", invalidValidation.errors.isNotEmpty())
    }
    
    @Test
    fun testPerformanceProfiling() = testScope.runTest {
        val profiler = PerformanceProfiler(MockContext())
        
        // Measure operation
        val result = profiler.measureOperation("test_operation") {
            delay(100)
            "test_result"
        }
        
        assertEquals("test_result", result)
        
        // Check statistics
        val stats = profiler.getStats("test_operation")
        assertNotNull(stats)
        assertTrue("Should record execution time", stats!!.averageDurationMs >= 100)
        assertEquals(1, stats.totalCalls)
    }
    
    @Test
    fun testErrorHandling() = testScope.runTest {
        // Test safe execution
        val successResult = ErrorHandling.safeExecute("test_success") {
            "success"
        }
        
        assertTrue(successResult.isSuccess)
        assertEquals("success", successResult.getOrNull())
        
        // Test error handling
        val errorResult = ErrorHandling.safeExecute("test_error") {
            throw RuntimeException("Test error")
        }
        
        assertTrue(errorResult.isFailure)
        assertNotNull(errorResult.exceptionOrNull())
    }
    
    // TODO: testAdvancedTemplateMatching removed - AdvancedTemplateMatching class not implemented
}