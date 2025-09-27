package tribixbite.keyboard2

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Neural Model Browser for debugging ONNX predictions
 * Visualizes neural model performance, tensor shapes, and prediction analysis
 *
 * Replaces CGR template browser with neural-specific diagnostics:
 * - ONNX model introspection
 * - Prediction confidence analysis
 * - Feature visualization
 * - Performance metrics
 */
class NeuralBrowserActivity : Activity() {

    private lateinit var wordList: ListView
    private lateinit var predictionView: PredictionVisualizationView
    private lateinit var modelInfo: TextView
    private lateinit var allWords: List<String>
    private lateinit var neuralEngine: NeuralSwipeEngine

    // Coroutine scope for the activity
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Title
        val title = TextView(this).apply {
            text = "🧠 Neural Model Browser"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 8)
        }
        mainLayout.addView(title)

        // Instructions
        val instructions = TextView(this).apply {
            text = "Select word to analyze neural prediction confidence and feature extraction"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(16, 0, 16, 16)
        }
        mainLayout.addView(instructions)

        // Word list
        wordList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 300
            )
            setBackgroundColor(0xFF2D2D2D.toInt())
        }
        mainLayout.addView(wordList)

        // Prediction visualization
        predictionView = PredictionVisualizationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            )
            setBackgroundColor(0xFF1A1A1A.toInt())
        }
        mainLayout.addView(predictionView)

        // Model info display
        modelInfo = TextView(this).apply {
            text = "Select a word to see neural prediction analysis..."
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF2D2D2D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 250
            )
        }
        mainLayout.addView(modelInfo)

        // Control buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val testButton = Button(this).apply {
            text = "🔬 Test Predictions"
            setOnClickListener { testPredictions() }
        }
        buttonLayout.addView(testButton)

        val benchmarkButton = Button(this).apply {
            text = "⏱️ Benchmark"
            setOnClickListener { runBenchmark() }
        }
        buttonLayout.addView(benchmarkButton)

        val closeButton = Button(this).apply {
            text = "❌ Close"
            setOnClickListener { finish() }
        }
        buttonLayout.addView(closeButton)

        mainLayout.addView(buttonLayout)
        setContentView(mainLayout)

        // Initialize
        initializeNeuralBrowser()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun initializeNeuralBrowser() {
        activityScope.launch {
            try {
                // Initialize neural engine
                neuralEngine = NeuralSwipeEngine(this@NeuralBrowserActivity, Config.globalConfig())

                // Load dictionary words for testing
                allWords = loadTestWords()

                // Set up word list adapter
                val adapter = ArrayAdapter(
                    this@NeuralBrowserActivity,
                    android.R.layout.simple_list_item_1,
                    allWords
                )
                wordList.adapter = adapter

                // Set up word selection listener
                wordList.setOnItemClickListener { _, _, position, _ ->
                    val selectedWord = allWords[position]
                    analyzeWord(selectedWord)
                }

                android.util.Log.d("NeuralBrowser", "Initialized with ${allWords.size} words")

            } catch (e: Exception) {
                android.util.Log.e("NeuralBrowser", "Failed to initialize", e)
                modelInfo.text = "❌ Failed to initialize neural engine: ${e.message}"
            }
        }
    }

    private fun loadTestWords(): List<String> {
        // Load common test words for analysis
        return listOf(
            "hello", "world", "android", "keyboard", "swipe", "neural", "prediction",
            "machine", "learning", "artificial", "intelligence", "algorithm",
            "the", "and", "you", "that", "was", "for", "are", "with", "his",
            "they", "this", "have", "from", "one", "had", "word", "what",
            "wonderful", "extraordinary", "supercalifragilisticexpialidocious"
        )
    }

    private fun analyzeWord(word: String) {
        activityScope.launch {
            try {
                val info = StringBuilder()
                info.append("WORD: ${word.uppercase()}\n")
                info.append("================\n")

                // Generate synthetic gesture for word
                val syntheticGesture = generateSyntheticGesture(word)
                info.append("✅ Synthetic gesture generated\n")
                info.append("Points: ${syntheticGesture.coordinates.size}\n")
                info.append("Duration: ${syntheticGesture.duration}ms\n")

                // Get prediction
                val prediction = neuralEngine.predictAsync(syntheticGesture)
                info.append("\nPREDICTION RESULTS:\n")
                info.append("================\n")

                if (prediction.words.isEmpty()) {
                    info.append("❌ No predictions generated\n")
                } else {
                    info.append("✅ ${prediction.words.size} predictions\n")
                    info.append("Prediction completed successfully\n\n")

                    // Show top predictions with confidence
                    prediction.words.take(5).forEachIndexed { index, word_pred ->
                        val score = prediction.scores.getOrNull(index) ?: 0
                        val confidence = score / 1000f // Convert score (0-1000) to confidence (0.0-1.0)
                        val correctness = if (word_pred.equals(word, ignoreCase = true)) "✅" else "❌"
                        info.append("${index + 1}. $correctness $word_pred (conf: %.3f)\n".format(confidence))
                    }

                    // Calculate accuracy metrics
                    val topPrediction = prediction.words.first()
                    val isCorrect = topPrediction.equals(word, ignoreCase = true)
                    val top3Correct = prediction.words.take(3).any {
                        it.equals(word, ignoreCase = true)
                    }

                    info.append("\nACCURACY METRICS:\n")
                    info.append("================\n")
                    info.append("Top-1 Accuracy: ${if (isCorrect) "✅ CORRECT" else "❌ INCORRECT"}\n")
                    info.append("Top-3 Accuracy: ${if (top3Correct) "✅ FOUND" else "❌ NOT FOUND"}\n")
                    val minScore = prediction.scores.minOrNull() ?: 0
                    val maxScore = prediction.scores.maxOrNull() ?: 0
                    info.append("Score range: $minScore - $maxScore\n")

                    // Feature analysis
                    info.append("\nFEATURE ANALYSIS:\n")
                    info.append("================\n")
                    info.append("Trajectory length: %.1f px\n".format(calculateTrajectoryLength(syntheticGesture)))
                    info.append("Gesture complexity: %.3f\n".format(calculateGestureComplexity(syntheticGesture)))
                    info.append("Velocity variance: %.1f\n".format(calculateVelocityVariance(syntheticGesture)))

                    // Update visualization
                    predictionView.setPredictionData(syntheticGesture, prediction)
                }

                modelInfo.text = info.toString()
                android.util.Log.d("NeuralBrowser", "Analyzed word: $word")

            } catch (e: Exception) {
                android.util.Log.e("NeuralBrowser", "Failed to analyze word: $word", e)
                modelInfo.text = "❌ Failed to analyze '$word': ${e.message}"
                return@launch
            }
        }
    }

    private fun generateSyntheticGesture(word: String): SwipeInput {
        // Generate synthetic gesture coordinates for word
        val trajectory = mutableListOf<PointF>()
        val timestamps = mutableListOf<Long>()

        // Simple QWERTY layout approximation
        val keyPositions = mapOf(
            'q' to PointF(54f, 240f), 'w' to PointF(162f, 240f), 'e' to PointF(270f, 240f),
            'r' to PointF(378f, 240f), 't' to PointF(486f, 240f), 'y' to PointF(594f, 240f),
            'u' to PointF(702f, 240f), 'i' to PointF(810f, 240f), 'o' to PointF(918f, 240f),
            'p' to PointF(1026f, 240f),
            'a' to PointF(108f, 320f), 's' to PointF(216f, 320f), 'd' to PointF(324f, 320f),
            'f' to PointF(432f, 320f), 'g' to PointF(540f, 320f), 'h' to PointF(648f, 320f),
            'j' to PointF(756f, 320f), 'k' to PointF(864f, 320f), 'l' to PointF(972f, 320f),
            'z' to PointF(216f, 400f), 'x' to PointF(324f, 400f), 'c' to PointF(432f, 400f),
            'v' to PointF(540f, 400f), 'b' to PointF(648f, 400f), 'n' to PointF(756f, 400f),
            'm' to PointF(864f, 400f)
        )

        var currentTime = System.currentTimeMillis()

        word.lowercase().forEachIndexed { index, char ->
            val keyPos = keyPositions[char] ?: PointF(540f, 320f) // Default to 'g'

            // Add some interpolation points between keys
            if (index > 0) {
                val prevPos = trajectory.lastOrNull() ?: keyPos
                val steps = 5
                for (i in 1 until steps) {
                    val progress = i.toFloat() / steps
                    val interpX = prevPos.x + (keyPos.x - prevPos.x) * progress
                    val interpY = prevPos.y + (keyPos.y - prevPos.y) * progress
                    trajectory.add(PointF(interpX, interpY))
                    timestamps.add(currentTime)
                    currentTime += 20
                }
            }

            trajectory.add(keyPos)
            timestamps.add(currentTime)
            currentTime += 100
        }

        return SwipeInput(
            coordinates = trajectory,
            timestamps = timestamps,
            touchedKeys = emptyList()
        )
    }

    private fun calculateTrajectoryLength(gesture: SwipeInput): Double {
        if (gesture.coordinates.size < 2) return 0.0

        var length = 0.0
        for (i in 1 until gesture.coordinates.size) {
            val p1 = gesture.coordinates[i - 1]
            val p2 = gesture.coordinates[i]
            length += sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
        }
        return length
    }

    private fun calculateGestureComplexity(gesture: SwipeInput): Double {
        // Calculate curvature-based complexity
        if (gesture.coordinates.size < 3) return 0.0

        var totalCurvature = 0.0
        for (i in 1 until gesture.coordinates.size - 1) {
            val p1 = gesture.coordinates[i - 1]
            val p2 = gesture.coordinates[i]
            val p3 = gesture.coordinates[i + 1]

            val angle1 = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
            val angle2 = atan2((p3.y - p2.y).toDouble(), (p3.x - p2.x).toDouble())

            var angleDiff = angle2 - angle1
            while (angleDiff > PI) angleDiff -= 2 * PI
            while (angleDiff < -PI) angleDiff += 2 * PI

            totalCurvature += abs(angleDiff)
        }

        return totalCurvature / (gesture.coordinates.size - 2)
    }

    private fun calculateVelocityVariance(gesture: SwipeInput): Double {
        if (gesture.coordinates.size < 2 || gesture.timestamps.size != gesture.coordinates.size) return 0.0

        val velocities = mutableListOf<Double>()
        for (i in 1 until gesture.coordinates.size) {
            val p1 = gesture.coordinates[i - 1]
            val p2 = gesture.coordinates[i]
            val distance = sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
            val timeDelta = (gesture.timestamps[i] - gesture.timestamps[i - 1]) / 1000.0

            if (timeDelta > 0) {
                velocities.add(distance / timeDelta)
            }
        }

        if (velocities.isEmpty()) return 0.0

        val mean = velocities.average()
        val variance = velocities.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    private fun testPredictions() {
        activityScope.launch {
            try {
                modelInfo.text = "🔬 Testing predictions on sample words...\n\n"

                val testWords = listOf("hello", "world", "test", "android", "swipe")
                var correctTop1 = 0
                var correctTop3 = 0
                var totalTime = 0L

                for (word in testWords) {
                    val gesture = generateSyntheticGesture(word)
                    val startTime = System.currentTimeMillis()
                    val prediction = neuralEngine.predictAsync(gesture)
                    val endTime = System.currentTimeMillis()

                    totalTime += (endTime - startTime)

                    if (prediction.words.isNotEmpty()) {
                        val topPrediction = prediction.words.first()
                        if (topPrediction.equals(word, ignoreCase = true)) {
                            correctTop1++
                        }

                        val top3Correct = prediction.words.take(3).any {
                            it.equals(word, ignoreCase = true)
                        }
                        if (top3Correct) {
                            correctTop3++
                        }
                    }
                }

                val info = StringBuilder()
                info.append("🔬 PREDICTION TEST RESULTS\n")
                info.append("========================\n")
                info.append("Test words: ${testWords.size}\n")
                info.append("Top-1 accuracy: $correctTop1/${testWords.size} (${(correctTop1 * 100 / testWords.size)}%)\n")
                info.append("Top-3 accuracy: $correctTop3/${testWords.size} (${(correctTop3 * 100 / testWords.size)}%)\n")
                info.append("Average time: ${totalTime / testWords.size}ms\n")
                info.append("\nSelect a word for detailed analysis...")

                modelInfo.text = info.toString()

            } catch (e: Exception) {
                modelInfo.text = "❌ Test failed: ${e.message}"
            }
        }
    }

    private fun runBenchmark() {
        activityScope.launch {
            try {
                modelInfo.text = "⏱️ Running performance benchmark...\n\n"

                val iterations = 50
                val word = "benchmark"
                val gesture = generateSyntheticGesture(word)

                val times = mutableListOf<Long>()

                for (i in 1..iterations) {
                    val startTime = System.nanoTime()
                    neuralEngine.predictAsync(gesture)
                    val endTime = System.nanoTime()
                    times.add((endTime - startTime) / 1_000_000) // Convert to ms
                }

                val avgTime = times.average()
                val minTime = times.minOrNull() ?: 0L
                val maxTime = times.maxOrNull() ?: 0L
                val medianTime = times.sorted()[times.size / 2]

                val info = StringBuilder()
                info.append("⏱️ PERFORMANCE BENCHMARK\n")
                info.append("=======================\n")
                info.append("Iterations: $iterations\n")
                info.append("Average time: %.1f ms\n".format(avgTime))
                info.append("Median time: $medianTime ms\n")
                info.append("Min time: $minTime ms\n")
                info.append("Max time: $maxTime ms\n")
                info.append("Throughput: %.1f predictions/sec\n".format(1000.0 / avgTime))
                info.append("\nSelect a word for detailed analysis...")

                modelInfo.text = info.toString()

            } catch (e: Exception) {
                modelInfo.text = "❌ Benchmark failed: ${e.message}"
            }
        }
    }

    /**
     * Custom view for prediction visualization
     */
    private inner class PredictionVisualizationView(context: android.content.Context) : View(context) {

        private var currentGesture: SwipeInput? = null
        private var currentPrediction: PredictionResult? = null

        private val gesturePaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 6f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val pointPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
        }

        private val confidencePaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 4f
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun setPredictionData(gesture: SwipeInput, prediction: PredictionResult) {
            currentGesture = gesture
            currentPrediction = prediction
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val gesture = currentGesture
            val prediction = currentPrediction

            if (gesture == null || prediction == null) {
                canvas.drawText("No prediction data to display", 50f, height / 2f, textPaint)
                return
            }

            // Draw gesture trajectory
            if (gesture.coordinates.size >= 2) {
                val path = Path()
                val scaleX = (width - 100f) / 1080f
                val scaleY = (height - 200f) / 400f

                val firstPoint = gesture.coordinates[0]
                path.moveTo(firstPoint.x * scaleX + 50f, firstPoint.y * scaleY + 100f)

                for (i in 1 until gesture.coordinates.size) {
                    val point = gesture.coordinates[i]
                    path.lineTo(point.x * scaleX + 50f, point.y * scaleY + 100f)
                }

                canvas.drawPath(path, gesturePaint)

                // Draw start and end points
                val startPoint = gesture.coordinates.first()
                val endPoint = gesture.coordinates.last()
                canvas.drawCircle(startPoint.x * scaleX + 50f, startPoint.y * scaleY + 100f, 12f, pointPaint)
                canvas.drawCircle(endPoint.x * scaleX + 50f, endPoint.y * scaleY + 100f, 12f, pointPaint)
            }

            // Draw confidence bars for top predictions
            if (prediction.words.isNotEmpty()) {
                val barHeight = 20f
                val barSpacing = 25f
                val startY = height - 150f

                prediction.words.take(5).forEachIndexed { index, word ->
                    val score = prediction.scores.getOrNull(index) ?: 0
                    val confidence = score / 1000f // Convert score (0-1000) to confidence (0.0-1.0)
                    val barWidth = (confidence * (width - 200f)).toFloat()
                    val y = startY + index * barSpacing

                    // Confidence bar
                    confidencePaint.color = when (index) {
                        0 -> Color.GREEN
                        1 -> Color.YELLOW
                        2 -> Color.rgb(255, 165, 0) // Orange
                        else -> Color.RED
                    }
                    canvas.drawRect(100f, y, 100f + barWidth, y + barHeight, confidencePaint)

                    // Prediction text
                    canvas.drawText("$word (${(confidence * 100).toInt()}%)",
                        110f + barWidth, y + 15f, textPaint)
                }
            }
        }
    }
}