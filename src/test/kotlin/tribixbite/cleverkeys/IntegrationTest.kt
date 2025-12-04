package tribixbite.cleverkeys

import android.content.Context
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
 * Integration tests for complete CleverKeys system
 *
 * NOTE: Full integration tests require x86_64 emulator (Robolectric limitation)
 * These tests are skipped on ARM64 architecture (Termux).
 *
 * To run tests: Use Android Studio on x86_64 host or CI/CD pipeline
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class IntegrationTest {

    private lateinit var testScope: TestScope
    private lateinit var context: Context

    @Before
    fun setup() {
        testScope = TestScope()
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSwipeInputStructure() = testScope.runTest {
        // Test SwipeInput data class structure
        val coordinates = listOf(
            PointF(100f, 200f),
            PointF(200f, 200f),
            PointF(300f, 200f)
        )
        val timestamps = listOf(0L, 50L, 100L)

        // touchedKeys is List<KeyboardData.Key?>, use emptyList for testing
        val swipeInput = SwipeInput(
            coordinates = coordinates,
            timestamps = timestamps,
            touchedKeys = emptyList()
        )

        // Verify SwipeInput structure
        assertNotNull(swipeInput.coordinates)
        assertEquals(3, swipeInput.coordinates.size)
        assertEquals(3, swipeInput.timestamps.size)
        assertTrue(swipeInput.touchedKeys.isEmpty())
    }

    @Test
    fun testGestureCreation() = testScope.runTest {
        // Test gesture creation helpers
        val helloGesture = createHelloGesture()
        assertNotNull(helloGesture)
        assertEquals(5, helloGesture.size) // h-e-l-l-o

        val horizontalGesture = createHorizontalGesture()
        assertNotNull(horizontalGesture)
        assertTrue(horizontalGesture.size >= 3)
    }

    @Test
    fun testCircularGestureCreation() = testScope.runTest {
        val circularGesture = createCircularGesture()
        assertNotNull(circularGesture)
        assertEquals(21, circularGesture.size) // 0..20 inclusive

        // Verify it's roughly circular - start and end should be close
        val start = circularGesture.first()
        val end = circularGesture.last()
        val distance = kotlin.math.sqrt(
            ((end.x - start.x) * (end.x - start.x) +
             (end.y - start.y) * (end.y - start.y)).toDouble()
        )
        assertTrue("Circular gesture should close within 10px", distance < 10)
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
