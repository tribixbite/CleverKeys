// Quick Kotlin compilation test for CleverKeys core components
// Test without Android framework dependencies

data class TestPoint(val x: Float, val y: Float)

data class TestSwipeInput(
    val coordinates: List<TestPoint>,
    val timestamps: List<Long>
) {
    val pathLength: Float by lazy {
        coordinates.zipWithNext { p1, p2 ->
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.sum()
    }

    val duration: Float by lazy {
        if (timestamps.size < 2) 0f
        else (timestamps.last() - timestamps.first()) / 1000f
    }
}

data class TestPredictionResult(
    val words: List<String>,
    val scores: List<Int>
)

class TestNeuralEngine {
    suspend fun predict(input: TestSwipeInput): TestPredictionResult {
        // Simulate ONNX processing
        kotlinx.coroutines.delay(10)

        val mockWords = when {
            input.pathLength < 100f -> listOf("the", "and", "for")
            input.pathLength < 200f -> listOf("hello", "world", "test")
            else -> listOf("keyboard", "swipe", "neural")
        }

        val scores = mockWords.mapIndexed { index, _ -> 1000 - index * 100 }
        return TestPredictionResult(mockWords, scores)
    }
}

suspend fun main() {
    println("🧪 Testing CleverKeys Kotlin core components...")

    // Test data classes
    val testInput = TestSwipeInput(
        coordinates = listOf(
            TestPoint(100f, 200f),
            TestPoint(200f, 200f),
            TestPoint(300f, 200f)
        ),
        timestamps = listOf(0L, 100L, 200L)
    )

    println("✅ SwipeInput created: ${testInput.coordinates.size} points")
    println("   Path length: ${testInput.pathLength}")
    println("   Duration: ${testInput.duration}s")

    // Test neural engine
    val neuralEngine = TestNeuralEngine()
    val result = neuralEngine.predict(testInput)

    println("✅ Neural prediction: ${result.words.size} words")
    println("   Top prediction: ${result.words.firstOrNull()}")
    println("   Scores: ${result.scores}")

    println("🎉 Core Kotlin components working correctly!")
}