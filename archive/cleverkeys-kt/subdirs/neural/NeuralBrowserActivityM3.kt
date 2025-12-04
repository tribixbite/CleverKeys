package tribixbite.cleverkeys.neural

import android.graphics.PointF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.NeuralSwipeEngine
import tribixbite.cleverkeys.PredictionResult
import tribixbite.cleverkeys.SwipeInput
import tribixbite.cleverkeys.theme.KeyboardTheme
import kotlin.math.*

/**
 * Material 3 Neural Model Browser for debugging ONNX predictions
 *
 * Replaces old View-based NeuralBrowserActivity with modern Compose UI.
 *
 * Features:
 * - ONNX model introspection and diagnostics
 * - Prediction confidence analysis with visualizations
 * - Feature extraction and analysis
 * - Performance benchmarking
 * - Material 3 theming with KeyboardTheme integration
 * - Reactive state management
 */
class NeuralBrowserActivityM3 : ComponentActivity() {

    private lateinit var neuralEngine: NeuralSwipeEngine
    private val testWords = listOf(
        "hello", "world", "android", "keyboard", "swipe", "neural", "prediction",
        "machine", "learning", "artificial", "intelligence", "algorithm",
        "the", "and", "you", "that", "was", "for", "are", "with", "his",
        "they", "this", "have", "from", "one", "had", "word", "what",
        "wonderful", "extraordinary", "supercalifragilisticexpialidocious"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize neural engine
        try {
            neuralEngine = NeuralSwipeEngine(this, Config.globalConfig())
        } catch (e: Exception) {
            android.util.Log.e("NeuralBrowserM3", "Failed to initialize neural engine", e)
        }

        setContent {
            KeyboardTheme {
                NeuralBrowserScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NeuralBrowserScreen() {
        var selectedWord by remember { mutableStateOf<String?>(null) }
        var analysisResult by remember { mutableStateOf<WordAnalysis?>(null) }
        var testResults by remember { mutableStateOf<TestResults?>(null) }
        var benchmarkResults by remember { mutableStateOf<BenchmarkResults?>(null) }
        var isAnalyzing by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("🧠 Neural Model Browser") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Instructions
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Select word to analyze neural prediction confidence and feature extraction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // Word list
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(testWords) { word ->
                            Text(
                                text = word,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selectedWord == word) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedWord = word
                                        lifecycleScope.launch {
                                            isAnalyzing = true
                                            analysisResult = analyzeWord(word)
                                            isAnalyzing = false
                                        }
                                    }
                                    .padding(12.dp)
                            )
                            if (word != testWords.last()) {
                                Divider()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Analysis results
                if (isAnalyzing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (analysisResult != null) {
                    AnalysisDisplay(analysisResult!!)
                } else if (testResults != null) {
                    TestResultsDisplay(testResults!!)
                } else if (benchmarkResults != null) {
                    BenchmarkResultsDisplay(benchmarkResults!!)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            lifecycleScope.launch {
                                isAnalyzing = true
                                testResults = runTests()
                                analysisResult = null
                                benchmarkResults = null
                                isAnalyzing = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔬 Test")
                    }

                    OutlinedButton(
                        onClick = {
                            lifecycleScope.launch {
                                isAnalyzing = true
                                benchmarkResults = runBenchmark()
                                analysisResult = null
                                testResults = null
                                isAnalyzing = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⏱️ Benchmark")
                    }
                }
            }
        }
    }

    @Composable
    private fun AnalysisDisplay(analysis: WordAnalysis) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Gesture visualization
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    GestureVisualization(
                        gesture = analysis.gesture,
                        prediction = analysis.prediction
                    )
                }
            }

            // Prediction results
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "WORD: ${analysis.word.uppercase()}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Gesture: ${analysis.gesture.coordinates.size} points, ${analysis.gesture.duration}ms",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Top predictions
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PREDICTION RESULTS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        analysis.prediction.words.take(5).forEachIndexed { index, word ->
                            val score = analysis.prediction.scores.getOrNull(index) ?: 0
                            val confidence = score / 1000f
                            val isCorrect = word.equals(analysis.word, ignoreCase = true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${if (isCorrect) "✅" else "❌"} ${index + 1}. $word",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "%.1f%%".format(confidence * 100),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Confidence bar
                            LinearProgressIndicator(
                                progress = confidence,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = when (index) {
                                    0 -> Color(0xFF4CAF50) // Green
                                    1 -> Color(0xFFFFEB3B) // Yellow
                                    2 -> Color(0xFFFF9800) // Orange
                                    else -> Color(0xFFF44336) // Red
                                }
                            )

                            if (index < 4) Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // Accuracy metrics
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (analysis.isTop1Correct) {
                            Color(0xFF4CAF50).copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ACCURACY METRICS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Top-1: ${if (analysis.isTop1Correct) "✅ CORRECT" else "❌ INCORRECT"}")
                        Text("Top-3: ${if (analysis.isTop3Correct) "✅ FOUND" else "❌ NOT FOUND"}")
                        Text("Score range: ${analysis.minScore} - ${analysis.maxScore}")
                    }
                }
            }

            // Feature analysis
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "FEATURE ANALYSIS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Trajectory length: %.1f px".format(analysis.trajectoryLength),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Gesture complexity: %.3f".format(analysis.gestureComplexity),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Velocity variance: %.1f".format(analysis.velocityVariance),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TestResultsDisplay(results: TestResults) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🔬 PREDICTION TEST RESULTS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Test words: ${results.totalWords}")
                Text("Top-1 accuracy: ${results.correctTop1}/${results.totalWords} (${results.top1Percentage}%)")
                Text("Top-3 accuracy: ${results.correctTop3}/${results.totalWords} (${results.top3Percentage}%)")
                Text("Average time: ${results.averageTimeMs}ms")
            }
        }
    }

    @Composable
    private fun BenchmarkResultsDisplay(results: BenchmarkResults) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⏱️ PERFORMANCE BENCHMARK",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Iterations: ${results.iterations}")
                Text("Average time: %.1f ms".format(results.averageTimeMs))
                Text("Median time: ${results.medianTimeMs} ms")
                Text("Min time: ${results.minTimeMs} ms")
                Text("Max time: ${results.maxTimeMs} ms")
                Text("Throughput: %.1f predictions/sec".format(results.throughput))
            }
        }
    }

    @Composable
    private fun GestureVisualization(
        gesture: SwipeInput,
        prediction: PredictionResult
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (gesture.coordinates.size < 2) {
                return@Canvas
            }

            val scaleX = (size.width - 100f) / 1080f
            val scaleY = (size.height - 200f) / 400f

            // Draw gesture path
            val path = Path()
            val firstPoint = gesture.coordinates[0]
            path.moveTo(firstPoint.x * scaleX + 50f, firstPoint.y * scaleY + 100f)

            for (i in 1 until gesture.coordinates.size) {
                val point = gesture.coordinates[i]
                path.lineTo(point.x * scaleX + 50f, point.y * scaleY + 100f)
            }

            drawPath(
                path = path,
                color = Color.Cyan,
                style = Stroke(width = 6f)
            )

            // Draw start/end points
            val startPoint = gesture.coordinates.first()
            val endPoint = gesture.coordinates.last()
            drawCircle(
                color = Color.Yellow,
                radius = 12f,
                center = Offset(startPoint.x * scaleX + 50f, startPoint.y * scaleY + 100f)
            )
            drawCircle(
                color = Color.Yellow,
                radius = 12f,
                center = Offset(endPoint.x * scaleX + 50f, endPoint.y * scaleY + 100f)
            )
        }
    }

    // Analysis functions
    private suspend fun analyzeWord(word: String): WordAnalysis {
        val gesture = generateSyntheticGesture(word)
        val prediction = neuralEngine.predictAsync(gesture)

        val topPrediction = prediction.words.firstOrNull() ?: ""
        val isTop1Correct = topPrediction.equals(word, ignoreCase = true)
        val isTop3Correct = prediction.words.take(3).any { it.equals(word, ignoreCase = true) }

        return WordAnalysis(
            word = word,
            gesture = gesture,
            prediction = prediction,
            isTop1Correct = isTop1Correct,
            isTop3Correct = isTop3Correct,
            minScore = prediction.scores.minOrNull() ?: 0,
            maxScore = prediction.scores.maxOrNull() ?: 0,
            trajectoryLength = calculateTrajectoryLength(gesture),
            gestureComplexity = calculateGestureComplexity(gesture),
            velocityVariance = calculateVelocityVariance(gesture)
        )
    }

    private suspend fun runTests(): TestResults {
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
                if (prediction.words.first().equals(word, ignoreCase = true)) correctTop1++
                if (prediction.words.take(3).any { it.equals(word, ignoreCase = true) }) correctTop3++
            }
        }

        return TestResults(
            totalWords = testWords.size,
            correctTop1 = correctTop1,
            correctTop3 = correctTop3,
            top1Percentage = (correctTop1 * 100 / testWords.size),
            top3Percentage = (correctTop3 * 100 / testWords.size),
            averageTimeMs = totalTime / testWords.size
        )
    }

    private suspend fun runBenchmark(): BenchmarkResults {
        val iterations = 50
        val word = "benchmark"
        val gesture = generateSyntheticGesture(word)
        val times = mutableListOf<Long>()

        for (i in 1..iterations) {
            val startTime = System.nanoTime()
            neuralEngine.predictAsync(gesture)
            val endTime = System.nanoTime()
            times.add((endTime - startTime) / 1_000_000)
        }

        val avgTime = times.average()
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L
        val medianTime = times.sorted()[times.size / 2]

        return BenchmarkResults(
            iterations = iterations,
            averageTimeMs = avgTime,
            medianTimeMs = medianTime,
            minTimeMs = minTime,
            maxTimeMs = maxTime,
            throughput = 1000.0 / avgTime
        )
    }

    private fun generateSyntheticGesture(word: String): SwipeInput {
        val trajectory = mutableListOf<PointF>()
        val timestamps = mutableListOf<Long>()

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
            val keyPos = keyPositions[char] ?: PointF(540f, 320f)

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
            if (timeDelta > 0) velocities.add(distance / timeDelta)
        }
        if (velocities.isEmpty()) return 0.0
        val mean = velocities.average()
        val variance = velocities.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    // Data classes
    private data class WordAnalysis(
        val word: String,
        val gesture: SwipeInput,
        val prediction: PredictionResult,
        val isTop1Correct: Boolean,
        val isTop3Correct: Boolean,
        val minScore: Int,
        val maxScore: Int,
        val trajectoryLength: Double,
        val gestureComplexity: Double,
        val velocityVariance: Double
    )

    private data class TestResults(
        val totalWords: Int,
        val correctTop1: Int,
        val correctTop3: Int,
        val top1Percentage: Int,
        val top3Percentage: Int,
        val averageTimeMs: Long
    )

    private data class BenchmarkResults(
        val iterations: Int,
        val averageTimeMs: Double,
        val medianTimeMs: Long,
        val minTimeMs: Long,
        val maxTimeMs: Long,
        val throughput: Double
    )
}
