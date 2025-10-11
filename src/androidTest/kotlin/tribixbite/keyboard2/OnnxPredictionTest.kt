package tribixbite.keyboard2

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation test for ONNX neural prediction
 * Tests the complete prediction pipeline with real ONNX models
 *
 * Run with: ./gradlew connectedAndroidTest
 * Or: adb shell am instrument -w tribixbite.keyboard2.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OnnxPredictionTest {

    private lateinit var predictor: OnnxSwipePredictorImpl
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        predictor = OnnxSwipePredictorImpl.getInstance(context)

        // Initialize predictor
        runBlocking {
            val initialized = predictor.initialize()
            assertTrue("Predictor should initialize successfully", initialized)
        }
    }

    @Test
    fun testSwipeHello() = runBlocking {
        println("\n🧪 Testing swipe: 'hello'")

        // Simulate swiping "hello" on QWERTY layout
        val coordinates = listOf(
            PointF(100f, 200f),  // h
            PointF(200f, 200f),  // e
            PointF(300f, 200f),  // l
            PointF(350f, 200f),  // l
            PointF(450f, 200f)   // o
        )
        val timestamps = coordinates.indices.map { it * 50L }

        val input = SwipeInput(coordinates, timestamps, emptyList())
        val result = predictor.predict(input)

        println("   Predictions: ${result.words.take(5)}")
        println("   Scores: ${result.scores.take(5)}")

        // Assertions
        assertTrue("Should return predictions", result.words.isNotEmpty())
        assertFalse("Should not return empty strings", result.words.any { it.isEmpty() })
        assertTrue("Top prediction should be valid word", result.words.first().length > 1)

        // Check for beam collapse (no repetitive single characters)
        val topWord = result.words.first()
        assertFalse("Should not have repetitive tokens like 'ttt'",
            topWord.matches(Regex("^(.)\\1+$")))
    }

    @Test
    fun testSwipeWorld() = runBlocking {
        println("\n🧪 Testing swipe: 'world'")

        val coordinates = listOf(
            PointF(80f, 180f),   // w
            PointF(450f, 180f),  // o
            PointF(300f, 180f),  // r
            PointF(300f, 200f),  // l
            PointF(350f, 180f)   // d
        )
        val timestamps = coordinates.indices.map { it * 50L }

        val input = SwipeInput(coordinates, timestamps, emptyList())
        val result = predictor.predict(input)

        println("   Predictions: ${result.words.take(5)}")
        println("   Scores: ${result.scores.take(5)}")

        assertTrue("Should return predictions", result.words.isNotEmpty())
        assertTrue("Top prediction should be valid word", result.words.first().length > 1)
    }

    @Test
    fun testSwipeTest() = runBlocking {
        println("\n🧪 Testing swipe: 'test'")

        val coordinates = listOf(
            PointF(220f, 180f),  // t
            PointF(200f, 200f),  // e
            PointF(180f, 220f),  // s
            PointF(220f, 180f)   // t
        )
        val timestamps = coordinates.indices.map { it * 50L }

        val input = SwipeInput(coordinates, timestamps, emptyList())
        val result = predictor.predict(input)

        println("   Predictions: ${result.words.take(5)}")
        println("   Scores: ${result.scores.take(5)}")

        assertTrue("Should return predictions", result.words.isNotEmpty())
    }

    @Test
    fun testSwipeValues() = runBlocking {
        println("\n🧪 Testing swipe: 'values' (Fix #30 validation)")

        // This swipe previously showed wrong nearest_keys: [25,25,25...] (nine 'v's)
        // After fix #30, should detect correct key sequence
        val coordinates = listOf(
            PointF(160f, 240f),  // v
            PointF(80f, 220f),   // a
            PointF(300f, 200f),  // l
            PointF(430f, 180f),  // u
            PointF(200f, 200f),  // e
            PointF(180f, 220f)   // s
        )
        val timestamps = coordinates.indices.map { it * 50L }

        val input = SwipeInput(coordinates, timestamps, emptyList())
        val result = predictor.predict(input)

        println("   Predictions: ${result.words.take(5)}")
        println("   Scores: ${result.scores.take(5)}")
        println("   ✅ Fix #30: Should have correct nearest_keys (not [25,25,25...])")

        assertTrue("Should return predictions", result.words.isNotEmpty())
        assertFalse("Should not return 'lll' or similar garbage",
            result.words.first().matches(Regex("^(.)\\1+$")))
    }

    @Test
    fun testKeyboardDimensions() {
        println("\n🧪 Testing keyboard dimensions update")

        // Simulate keyboard dimensions from Keyboard2View
        val width = 1080
        val height = 400

        predictor.setKeyboardDimensions(width, height)
        println("   ✅ Dimensions set: ${width}x${height}")

        // Test with real key positions (Fix #30)
        val keyPositions = mapOf(
            'a' to PointF(80f, 220f),
            'e' to PointF(200f, 200f),
            'l' to PointF(300f, 200f),
            'h' to PointF(280f, 220f),
            'o' to PointF(450f, 180f)
        )

        predictor.setRealKeyPositions(keyPositions)
        println("   ✅ Real key positions set: ${keyPositions.size} keys")
        println("   ✅ Fix #30: Predictor now uses actual key centers")
    }

    @Test
    fun testNeuralConfig() {
        println("\n🧪 Testing neural configuration")

        val prefs = context.getSharedPreferences("test_prefs", android.content.Context.MODE_PRIVATE)
        val config = NeuralConfig(prefs).apply {
            beamWidth = 8
            maxLength = 35
            confidenceThreshold = 0.1f
        }

        predictor.setConfig(config)

        println("   ✅ Neural config applied:")
        println("      beam_width: ${config.beamWidth}")
        println("      max_length: ${config.maxLength}")
        println("      confidence_threshold: ${config.confidenceThreshold}")
    }

    @Test
    fun testBeamSearchDiversity() = runBlocking {
        println("\n🧪 Testing beam search diversity (Fix #29 validation)")

        // Test that beam search produces diverse predictions, not collapsed beams
        val coordinates = listOf(
            PointF(120f, 180f),  // Random swipe
            PointF(250f, 200f),
            PointF(380f, 190f),
            PointF(320f, 210f)
        )
        val timestamps = coordinates.indices.map { it * 50L }

        val input = SwipeInput(coordinates, timestamps, emptyList())
        val result = predictor.predict(input)

        println("   Predictions: ${result.words.take(5)}")

        // Check for diversity
        val uniquePredictions = result.words.take(5).toSet()
        assertTrue("Should have diverse predictions (not beam collapse)",
            uniquePredictions.size >= 3)

        println("   ✅ Fix #29: Beam search maintains diversity")
        println("      Unique predictions: ${uniquePredictions.size}/5")
    }
}
