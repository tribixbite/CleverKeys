package tribixbite.cleverkeys

import android.graphics.PointF
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for neural prediction system data structures
 *
 * NOTE: Full neural tests require x86_64 emulator (Robolectric limitation)
 * These tests are skipped on ARM64 architecture (Termux).
 *
 * To run tests: Use Android Studio on x86_64 host or CI/CD pipeline
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

        // Create SwipeInput with empty touched keys (KeyboardData.Key? list)
        val swipeInput = SwipeInput(points, timestamps, emptyList())

        // Assertions
        assertEquals(3, swipeInput.coordinates.size)
        assertEquals(3, swipeInput.timestamps.size)
        assertTrue(swipeInput.pathLength > 0)
        assertTrue(swipeInput.duration > 0)
        assertEquals(100f, swipeInput.pathLength, 1f)
    }

    @Test
    fun testSwipeInputPathLength() = testScope.runTest {
        // Test diagonal path (100 units each direction = ~141.4 total)
        val diagonalPoints = listOf(
            PointF(0f, 0f),
            PointF(100f, 100f)
        )
        val timestamps = listOf(0L, 100L)

        val swipeInput = SwipeInput(diagonalPoints, timestamps, emptyList())

        // sqrt(100^2 + 100^2) = sqrt(20000) ≈ 141.4
        assertEquals(141.4f, swipeInput.pathLength, 1f)
    }

    @Test
    fun testSwipeInputDuration() = testScope.runTest {
        val points = listOf(
            PointF(0f, 0f),
            PointF(100f, 0f),
            PointF(200f, 0f)
        )
        val timestamps = listOf(0L, 50L, 150L)

        val swipeInput = SwipeInput(points, timestamps, emptyList())

        // Duration is in seconds (150ms = 0.15s)
        assertEquals(0.15f, swipeInput.duration, 0.01f)
    }

    @Test
    fun testSwipeInputEmptyTouchedKeys() = testScope.runTest {
        val points = listOf(PointF(0f, 0f), PointF(100f, 0f))
        val timestamps = listOf(0L, 100L)

        val swipeInput = SwipeInput(points, timestamps, emptyList())

        assertTrue(swipeInput.touchedKeys.isEmpty())
        assertEquals("", swipeInput.keySequence)
    }

    @Test
    fun testPredictionResultBasics() = testScope.runTest {
        val result = PredictionResult(
            words = listOf("hello", "world", "test"),
            scores = listOf(900, 800, 700)
        )

        assertEquals(3, result.words.size)
        assertEquals(3, result.scores.size)
        assertEquals("hello", result.words[0])
        assertEquals(900, result.scores[0])
    }

    @Test
    fun testPredictionResultEmpty() = testScope.runTest {
        val emptyResult = PredictionResult(
            words = emptyList(),
            scores = emptyList()
        )

        assertTrue(emptyResult.words.isEmpty())
        assertTrue(emptyResult.scores.isEmpty())
    }

    @Test
    fun testSwipeDetectorInstantiation() = testScope.runTest {
        // Just verify SwipeDetector can be instantiated
        val detector = SwipeDetector()
        assertNotNull(detector)
    }

    @Test
    fun testSwipeTrajectoryProcessorInstantiation() = testScope.runTest {
        // Just verify SwipeTrajectoryProcessor can be instantiated
        val processor = SwipeTrajectoryProcessor()
        assertNotNull(processor)
    }

    @Test
    fun testSwipeInputConfidence() = testScope.runTest {
        // Create a longer swipe to get confidence > 0
        val points = mutableListOf<PointF>()
        for (i in 0..20) {
            points.add(PointF(i * 15f, (i % 3) * 10f)) // zigzag pattern
        }
        val timestamps = points.indices.map { it * 50L }

        val swipeInput = SwipeInput(points, timestamps, emptyList())

        val confidence = swipeInput.getSwipeConfidence()
        assertTrue("Confidence should be > 0 for valid swipe", confidence > 0f)
        assertTrue("Confidence should be <= 1", confidence <= 1f)
    }

    @Test
    fun testSwipeInputHighQuality() = testScope.runTest {
        // Create a high-quality swipe pattern
        val points = mutableListOf<PointF>()
        for (i in 0..30) {
            val x = i * 10f
            val y = 100f + kotlin.math.sin(i * 0.3) * 50f
            points.add(PointF(x.toFloat(), y.toFloat()))
        }
        val timestamps = points.indices.map { it * 20L }

        val swipeInput = SwipeInput(points, timestamps, emptyList())

        // Test the quality check method exists and runs
        val isHighQuality = swipeInput.isHighQualitySwipe()
        // High quality swipes need direction changes and sufficient length
        assertNotNull(isHighQuality)
    }
}
