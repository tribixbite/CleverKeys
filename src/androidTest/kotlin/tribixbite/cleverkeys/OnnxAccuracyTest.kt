package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * ONNX Accuracy Test - Validates real predictions with actual models
 * Tests that critical bug fixes produce accurate words (not gibberish)
 *
 * Run with: ./gradlew connectedAndroidTest --tests OnnxAccuracyTest
 */
@RunWith(AndroidJUnit4::class)
class OnnxAccuracyTest {

    private lateinit var context: Context
    private lateinit var neuralEngine: NeuralSwipeTypingEngine

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        neuralEngine = NeuralSwipeTypingEngine(context, Config.globalConfig())
    }

    /**
     * Test Case 1: "hello" swipe
     * Path simulates: h -> e -> l -> l -> o
     */
    @Test
    fun testHelloSwipe_producesAccurateWord() = runBlocking {
        // Initialize neural engine
        val initSuccess = neuralEngine.initialize()
        assertTrue("Neural engine should initialize", initSuccess)

        // Create realistic "hello" swipe
        val helloSwipe = createHelloSwipe()

        // Get predictions
        val result = neuralEngine.predictAsync(helloSwipe)

        // Assertions
        assertFalse("Should return predictions", result.isEmpty)
        assertTrue("Should return at least 3 predictions", result.size >= 3)

        // CRITICAL: Top prediction should be "hello" (not gibberish like "ggeeeeee")
        val topWord = result.topPrediction
        assertNotNull("Should have top prediction", topWord)

        println("🎯 Test: hello swipe")
        println("   Top prediction: '$topWord'")
        println("   All predictions: ${result.words.take(5)}")

        // Verify it's an actual word (not gibberish)
        assertTrue(
            "Top prediction should be actual word, got: '$topWord'",
            topWord!!.all { it.isLetter() } && topWord.length > 2
        )

        // Best case: top prediction is "hello"
        if (topWord == "hello") {
            println("   ✅ PERFECT: Got exact word 'hello'")
        } else {
            // Acceptable: "hello" is in top 3
            val top3 = result.words.take(3)
            assertTrue(
                "Word 'hello' should be in top 3 predictions. Got: $top3",
                top3.contains("hello")
            )
            println("   ⚠️  'hello' found in position ${top3.indexOf("hello") + 1}")
        }

        neuralEngine.cleanup()
    }

    /**
     * Test Case 2: "test" swipe
     * Path simulates: t -> e -> s -> t
     */
    @Test
    fun testTestSwipe_producesAccurateWord() = runBlocking {
        val initSuccess = neuralEngine.initialize()
        assertTrue("Neural engine should initialize", initSuccess)

        val testSwipe = createTestSwipe()
        val result = neuralEngine.predictAsync(testSwipe)

        assertFalse("Should return predictions", result.isEmpty)

        val topWord = result.topPrediction
        assertNotNull("Should have top prediction", topWord)

        println("🎯 Test: test swipe")
        println("   Top prediction: '$topWord'")
        println("   All predictions: ${result.words.take(5)}")

        // Verify not gibberish
        assertTrue(
            "Top prediction should be actual word, got: '$topWord'",
            topWord!!.all { it.isLetter() } && topWord.length > 2
        )

        // "test" should be in top 5
        val top5 = result.words.take(5)
        assertTrue(
            "Word 'test' should be in top 5 predictions. Got: $top5",
            top5.contains("test")
        )

        neuralEngine.cleanup()
    }

    /**
     * Test Case 3: "the" swipe (common word)
     * Path simulates: t -> h -> e
     */
    @Test
    fun testTheSwipe_producesAccurateWord() = runBlocking {
        val initSuccess = neuralEngine.initialize()
        assertTrue("Neural engine should initialize", initSuccess)

        val theSwipe = createTheSwipe()
        val result = neuralEngine.predictAsync(theSwipe)

        assertFalse("Should return predictions", result.isEmpty)

        val topWord = result.topPrediction
        assertNotNull("Should have top prediction", topWord)

        println("🎯 Test: the swipe")
        println("   Top prediction: '$topWord'")
        println("   All predictions: ${result.words.take(5)}")

        // "the" is very common - should be top 3
        val top3 = result.words.take(3)
        assertTrue(
            "Word 'the' should be in top 3 predictions. Got: $top3",
            top3.contains("the")
        )

        neuralEngine.cleanup()
    }

    /**
     * Test Case 4: Anti-regression test for gibberish bug
     * Verifies we DON'T get repeated character gibberish
     */
    @Test
    fun testNoGibberishPredictions() = runBlocking {
        val initSuccess = neuralEngine.initialize()
        assertTrue("Neural engine should initialize", initSuccess)

        // Test multiple swipes
        val testSwipes = listOf(
            createHelloSwipe() to "hello test",
            createTestSwipe() to "test test",
            createTheSwipe() to "the test"
        )

        for ((swipe, testName) in testSwipes) {
            val result = neuralEngine.predictAsync(swipe)

            assertFalse("$testName: Should return predictions", result.isEmpty)

            val topWord = result.topPrediction!!

            // Check for gibberish patterns
            val hasRepeatedChars = topWord.zipWithNext().count { (a, b) -> a == b } > topWord.length / 2
            val isTooLong = topWord.length > 15
            val hasOnlyOneChar = topWord.toSet().size == 1

            assertFalse(
                "$testName: Got gibberish with repeated chars: '$topWord'",
                hasRepeatedChars
            )

            assertFalse(
                "$testName: Got gibberish too long: '$topWord' (${topWord.length} chars)",
                isTooLong
            )

            assertFalse(
                "$testName: Got gibberish with single char: '$topWord'",
                hasOnlyOneChar
            )

            println("✅ $testName: No gibberish detected, got: '$topWord'")
        }

        neuralEngine.cleanup()
    }

    /**
     * Test Case 5: Confidence scores are reasonable
     */
    @Test
    fun testConfidenceScoresReasonable() = runBlocking {
        val initSuccess = neuralEngine.initialize()
        assertTrue("Neural engine should initialize", initSuccess)

        val testSwipe = createHelloSwipe()
        val result = neuralEngine.predictAsync(testSwipe)

        assertFalse("Should return predictions", result.isEmpty)
        assertTrue("Should have scores", result.scores.isNotEmpty())

        // Scores should be reasonable (not astronomical or negative)
        val topScore = result.topScore

        println("🎯 Test: confidence scores")
        println("   Top score: $topScore")
        println("   All scores: ${result.scores.take(5)}")

        assertTrue(
            "Top score should be positive, got: $topScore",
            topScore > 0
        )

        assertTrue(
            "Top score should be < 1000, got: $topScore (would indicate raw logits not log-softmax)",
            topScore < 1000
        )

        // Scores should be in descending order
        for (i in 0 until result.scores.size - 1) {
            assertTrue(
                "Scores should be descending: score[$i]=${result.scores[i]} > score[${i+1}]=${result.scores[i+1]}",
                result.scores[i] >= result.scores[i+1]
            )
        }

        println("   ✅ Confidence scores are reasonable and sorted")

        neuralEngine.cleanup()
    }

    // Helper functions to create realistic swipe coordinates

    private fun createHelloSwipe(): SwipeInput {
        // Simulates: h -> e -> l -> l -> o
        val coordinates = listOf(
            // Start at 'h' (540, 200)
            PointF(540f, 200f), PointF(545f, 200f), PointF(550f, 200f),
            // Move to 'e' (280, 100)
            PointF(500f, 150f), PointF(400f, 120f), PointF(350f, 110f), PointF(280f, 100f),
            // Move to 'l' (730, 200)
            PointF(400f, 150f), PointF(550f, 180f), PointF(680f, 195f), PointF(720f, 200f), PointF(730f, 200f),
            // Stay at 'l' (double letter)
            PointF(735f, 200f), PointF(740f, 200f),
            // Move to 'o' (820, 100)
            PointF(760f, 180f), PointF(780f, 150f), PointF(800f, 120f), PointF(810f, 110f), PointF(820f, 100f)
        )

        val timestamps = coordinates.indices.map { it * 50L } // 50ms per point

        return SwipeInput(coordinates, timestamps, emptyList())
    }

    private fun createTestSwipe(): SwipeInput {
        // Simulates: t -> e -> s -> t
        val coordinates = listOf(
            // Start at 't' (450, 100)
            PointF(450f, 100f), PointF(455f, 100f), PointF(460f, 100f),
            // Move to 'e' (280, 100)
            PointF(400f, 100f), PointF(340f, 100f), PointF(280f, 100f),
            // Move to 's' (180, 200)
            PointF(240f, 140f), PointF(200f, 170f), PointF(180f, 200f),
            // Move to 't' (450, 100)
            PointF(250f, 170f), PointF(330f, 140f), PointF(400f, 110f), PointF(450f, 100f)
        )

        val timestamps = coordinates.indices.map { it * 60L }

        return SwipeInput(coordinates, timestamps, emptyList())
    }

    private fun createTheSwipe(): SwipeInput {
        // Simulates: t -> h -> e
        val coordinates = listOf(
            // Start at 't' (450, 100)
            PointF(450f, 100f), PointF(455f, 100f),
            // Move to 'h' (540, 200)
            PointF(470f, 130f), PointF(500f, 160f), PointF(520f, 180f), PointF(540f, 200f),
            // Move to 'e' (280, 100)
            PointF(480f, 170f), PointF(420f, 140f), PointF(350f, 120f), PointF(280f, 100f)
        )

        val timestamps = coordinates.indices.map { it * 70L }

        return SwipeInput(coordinates, timestamps, emptyList())
    }
}
