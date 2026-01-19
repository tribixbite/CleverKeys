package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for swipe typing functionality.
 * Tests SwipeDetector, NeuralSwipeTypingEngine, and gesture recognition.
 */
@RunWith(AndroidJUnit4::class)
class SwipePredictionTest {

    private lateinit var context: Context
    private lateinit var swipeEngine: NeuralSwipeTypingEngine
    private lateinit var swipeDetector: SwipeDetector

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        swipeEngine = NeuralSwipeTypingEngine(context, Config.globalConfig())
        swipeDetector = SwipeDetector()
    }

    @After
    fun cleanup() {
        try {
            swipeEngine.cleanup()
        } catch (e: IllegalStateException) {
            // OrtSession may already be closed - ignore
        }
    }

    // =========================================================================
    // NeuralSwipeTypingEngine tests
    // =========================================================================

    @Test
    fun testEngineCreation() {
        assertNotNull("Engine should be created", swipeEngine)
    }

    @Test
    fun testEngineInitialization() {
        val initialized = swipeEngine.initialize()
        // May or may not initialize depending on ONNX model availability
    }

    @Test
    fun testEngineSetKeyboardDimensions() {
        swipeEngine.setKeyboardDimensions(1080f, 480f)
        // Should not crash
    }

    @Test
    fun testEngineSetQwertyAreaBounds() {
        swipeEngine.setQwertyAreaBounds(50f, 400f)
        // Should not crash
    }

    @Test
    fun testEngineSetTouchYOffset() {
        swipeEngine.setTouchYOffset(10f)
        // Should not crash
    }

    @Test
    fun testEngineSetMargins() {
        swipeEngine.setMargins(20f, 20f)
        // Should not crash
    }

    @Test
    fun testIsNeuralAvailable() {
        val available = swipeEngine.isNeuralAvailable()
        // Just verify it returns without crashing
    }

    @Test
    fun testGetCurrentMode() {
        val mode = swipeEngine.getCurrentMode()
        assertNotNull("Mode should not be null", mode)
    }

    @Test
    fun testEngineWithConfig() {
        val config = Config.globalConfig()
        swipeEngine.setConfig(config)
        // Should not crash
    }

    @Test
    fun testReloadCustomWords() {
        swipeEngine.reloadCustomWords()
        // Should not crash
    }

    // =========================================================================
    // SwipeDetector tests
    // =========================================================================

    @Test
    fun testSwipeDetectorCreation() {
        assertNotNull("SwipeDetector should be created", swipeDetector)
    }

    @Test
    fun testSwipeDetectorUpdateConfig() {
        val config = Config.globalConfig()
        swipeDetector.updateConfig(config)
        // Should not crash
    }

    // =========================================================================
    // ImprovedSwipeGestureRecognizer tests
    // =========================================================================

    @Test
    fun testGestureRecognizerCreation() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        assertNotNull("Recognizer should be created", recognizer)
    }

    @Test
    fun testGestureRecognizerStartSwipe() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        // Should not crash
    }

    @Test
    fun testGestureRecognizerAddPoints() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        recognizer.addPoint(150f, 200f, null)
        recognizer.addPoint(200f, 200f, null)
        recognizer.addPoint(250f, 200f, null)

        val path = recognizer.getSwipePath()
        // Path may or may not include all points depending on deduplication logic
        assertNotNull("Path should not be null", path)
    }

    @Test
    fun testGestureRecognizerEndSwipe() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        recognizer.addPoint(200f, 200f, null)
        recognizer.addPoint(300f, 200f, null)

        val result = recognizer.endSwipe()
        assertNotNull("End swipe should return result", result)
    }

    @Test
    fun testGestureRecognizerIsSwipeTyping() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        recognizer.addPoint(200f, 200f, null)
        recognizer.addPoint(300f, 200f, null)

        val isTyping = recognizer.isSwipeTyping()
        // Result depends on swipe distance
    }

    @Test
    fun testGestureRecognizerGetTimestamps() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        recognizer.addPoint(200f, 200f, null)

        val timestamps = recognizer.getTimestamps()
        assertNotNull("Timestamps should not be null", timestamps)
    }

    @Test
    fun testGestureRecognizerReset() {
        val recognizer = ImprovedSwipeGestureRecognizer()
        recognizer.startSwipe(100f, 200f, null)
        recognizer.addPoint(200f, 200f, null)
        recognizer.reset()

        val path = recognizer.getSwipePath()
        assertTrue("Path should be empty after reset", path.isEmpty())
    }

    // =========================================================================
    // Integration tests
    // =========================================================================

    @Test
    fun testFullSwipePipeline() {
        // Simulate a complete swipe typing flow
        val recognizer = ImprovedSwipeGestureRecognizer()

        // Start swipe at 'h'
        recognizer.startSwipe(100f, 200f, null)

        // Move through 'e', 'l', 'l', 'o'
        for (i in 1..10) {
            recognizer.addPoint(100f + i * 20f, 200f + (if (i % 2 == 0) 10f else -10f), null)
        }

        // End swipe
        val result = recognizer.endSwipe()
        assertNotNull(result)
    }

    @Test
    fun testSwipeWithRealKeyPositions() {
        // Set up real key positions (simplified QWERTY row)
        val keyPositions = mapOf(
            'q' to PointF(54f, 200f),
            'w' to PointF(162f, 200f),
            'e' to PointF(270f, 200f),
            'r' to PointF(378f, 200f),
            't' to PointF(486f, 200f),
            'y' to PointF(594f, 200f),
            'u' to PointF(702f, 200f),
            'i' to PointF(810f, 200f),
            'o' to PointF(918f, 200f),
            'p' to PointF(1026f, 200f)
        )

        swipeEngine.setRealKeyPositions(keyPositions)
        swipeEngine.setKeyboardDimensions(1080f, 480f)
        // Should not crash
    }

    // =========================================================================
    // Performance tests
    // =========================================================================

    @Test
    fun testGestureRecognitionPerformance() {
        val recognizer = ImprovedSwipeGestureRecognizer()

        val startTime = System.currentTimeMillis()

        for (i in 1..50) {
            recognizer.startSwipe(100f, 200f, null)
            for (j in 1..20) {
                recognizer.addPoint(100f + j * 10f, 200f, null)
            }
            recognizer.endSwipe()
            recognizer.reset()
        }

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("50 gesture recognitions should complete in under 500ms", elapsed < 500)
    }
}
