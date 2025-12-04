package tribixbite.cleverkeys.benchmark

import android.content.Context
import android.graphics.PointF
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.*

@RunWith(AndroidJUnit4::class)
class NeuralPredictionBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var context: Context
    private lateinit var neuralEngine: NeuralPredictionPipeline
    private lateinit var config: NeuralConfig
    private lateinit var testSwipes: List<SwipeInput>

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        config = NeuralConfig(context)
        neuralEngine = NeuralPredictionPipeline(context, config)

        // Initialize neural engine once
        runBlocking {
            neuralEngine.initialize()
        }

        // Pre-generate test swipe data
        testSwipes = generateTestSwipes()
    }

    @Test
    fun benchmarkSinglePrediction() = runBlocking {
        val testSwipe = testSwipes.first()

        benchmarkRule.measureRepeated {
            val result = neuralEngine.processGesture(testSwipe)
            require(result.predictions.isNotEmpty()) { "Prediction should not be empty" }
        }
    }

    @Test
    fun benchmarkBatchPredictions() = runBlocking {
        benchmarkRule.measureRepeated {
            testSwipes.take(5).forEach { swipe ->
                val result = neuralEngine.processGesture(swipe)
                require(result.predictions.isNotEmpty()) { "Batch prediction should not be empty" }
            }
        }
    }

    @Test
    fun benchmarkEncoderOnly() = runBlocking {
        val predictor = OnnxSwipePredictorImpl(context, config)
        predictor.initialize()

        val testSwipe = testSwipes.first()

        benchmarkRule.measureRepeated {
            // Test just the encoder step
            val features = predictor.extractFeatures(testSwipe)
            require(features.isNotEmpty()) { "Features should not be empty" }
        }
    }

    @Test
    fun benchmarkDecoderOnly() = runBlocking {
        val predictor = OnnxSwipePredictorImpl(context, config)
        predictor.initialize()

        // Pre-compute encoder output
        val testSwipe = testSwipes.first()
        val encodedMemory = predictor.runEncoder(testSwipe)

        benchmarkRule.measureRepeated {
            val predictions = predictor.runDecoder(encodedMemory, 8, 35) // beam_width=8, max_length=35
            require(predictions.isNotEmpty()) { "Decoder predictions should not be empty" }
        }
    }

    @Test
    fun benchmarkFeatureExtraction() {
        val processor = SwipeTrajectoryProcessor()
        val testSwipe = testSwipes.first()

        benchmarkRule.measureRepeated {
            val features = processor.extractFeatures(testSwipe)
            require(features.isNotEmpty()) { "Features should be extracted" }
        }
    }

    @Test
    fun benchmarkTokenization() {
        val tokenizer = SwipeTokenizer()
        val testWords = listOf("hello", "world", "testing", "performance", "neural")

        benchmarkRule.measureRepeated {
            testWords.forEach { word ->
                val tokens = tokenizer.encode(word)
                require(tokens.isNotEmpty()) { "Tokens should not be empty" }
            }
        }
    }

    @Test
    fun benchmarkBeamSearch() = runBlocking {
        val predictor = OnnxSwipePredictorImpl(context, config)
        predictor.initialize()

        val testSwipe = testSwipes.first()
        val encodedMemory = predictor.runEncoder(testSwipe)

        // Test different beam widths
        listOf(1, 4, 8, 16).forEach { beamWidth ->
            benchmarkRule.measureRepeated {
                val predictions = predictor.runDecoder(encodedMemory, beamWidth, 20)
                require(predictions.isNotEmpty()) { "Beam search should produce predictions" }
            }
        }
    }

    @Test
    fun benchmarkConcurrentPredictions() = runBlocking {
        benchmarkRule.measureRepeated {
            // Simulate multiple concurrent prediction requests
            val concurrentSwipes = testSwipes.take(3)

            concurrentSwipes.forEach { swipe ->
                val result = neuralEngine.processGesture(swipe)
                require(result.predictions.isNotEmpty()) { "Concurrent prediction failed" }
            }
        }
    }

    @Test
    fun benchmarkMemoryEfficiency() = runBlocking {
        val runtime = Runtime.getRuntime()

        benchmarkRule.measureRepeated {
            val beforeMemory = runtime.totalMemory() - runtime.freeMemory()

            // Perform multiple predictions
            testSwipes.take(10).forEach { swipe ->
                neuralEngine.processGesture(swipe)
            }

            // Force garbage collection
            System.gc()
            Thread.sleep(10)

            val afterMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryIncrease = afterMemory - beforeMemory

            // Memory increase should be minimal (indicating good cleanup)
            require(memoryIncrease < 10_000_000) { "Memory usage increased too much: ${memoryIncrease / 1024 / 1024}MB" }
        }
    }

    @Test
    fun benchmarkDifferentSwipeLengths() = runBlocking {
        val shortSwipe = generateSwipe("hi", 5)
        val mediumSwipe = generateSwipe("hello", 15)
        val longSwipe = generateSwipe("performance", 30)

        listOf(
            "short" to shortSwipe,
            "medium" to mediumSwipe,
            "long" to longSwipe
        ).forEach { (length, swipe) ->
            benchmarkRule.measureRepeated {
                val result = neuralEngine.processGesture(swipe)
                require(result.predictions.isNotEmpty()) { "$length swipe prediction failed" }
            }
        }
    }

    @Test
    fun benchmarkColdStartPrediction() {
        // Test prediction performance on cold start
        benchmarkRule.measureRepeated {
            runBlocking {
                // Create fresh neural engine for each iteration
                val freshEngine = NeuralPredictionPipeline(context, config)
                freshEngine.initialize()

                val result = freshEngine.processGesture(testSwipes.first())
                require(result.predictions.isNotEmpty()) { "Cold start prediction failed" }

                // Clean up
                freshEngine.cleanup()
            }
        }
    }

    private fun generateTestSwipes(): List<SwipeInput> {
        val commonWords = listOf(
            "hello", "world", "testing", "performance", "neural",
            "keyboard", "android", "prediction", "machine", "learning",
            "swipe", "gesture", "touch", "input", "text"
        )

        return commonWords.map { word ->
            generateSwipe(word, word.length * 3) // 3 points per character
        }
    }

    private fun generateSwipe(targetWord: String, numPoints: Int): SwipeInput {
        val coordinates = mutableListOf<PointF>()
        val timestamps = mutableListOf<Long>()
        val touchedKeys = mutableListOf<KeyboardData.Key>()

        val startTime = System.currentTimeMillis()

        // Generate realistic swipe path
        targetWord.forEachIndexed { charIndex, char ->
            val pointsPerChar = numPoints / targetWord.length
            val baseX = 100f + (charIndex * 120f)
            val baseY = 200f

            repeat(pointsPerChar) { pointIndex ->
                val progress = pointIndex.toFloat() / pointsPerChar
                val x = baseX + (progress * 100f) + (Math.random() * 20f - 10f).toFloat()
                val y = baseY + (Math.sin(progress * Math.PI) * 30f).toFloat() + (Math.random() * 10f - 5f).toFloat()

                coordinates.add(PointF(x, y))
                timestamps.add(startTime + ((charIndex * pointsPerChar + pointIndex) * 50L))

                // Create mock key for each unique character position
                if (pointIndex == 0) {
                    val key = KeyboardData.Key(
                        key0 = KeyValue.makeStringKey(char.toString()),
                        key1 = null,
                        key2 = null,
                        key3 = null,
                        key4 = null,
                        x = baseX,
                        y = baseY,
                        width = 0.1f,
                        height = 0.1f
                    )
                    touchedKeys.add(key)
                }
            }
        }

        return SwipeInput(coordinates, timestamps, touchedKeys)
    }
}