package tribixbite.keyboard2

import android.app.Activity
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * Simple test activity for neural prediction validation
 * Launch via: adb shell am start -n tribixbite.keyboard2/.TestActivity
 */
class TestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("TEST", "=".repeat(70))
        Log.i("TEST", "Neural Prediction Test Starting")
        Log.i("TEST", "=".repeat(70))

        CoroutineScope(Dispatchers.Main).launch {
            try {
                runTests()
            } catch (e: Exception) {
                Log.e("TEST", "Test failed: ${e.message}", e)
            } finally {
                Log.i("TEST", "=".repeat(70))
                Log.i("TEST", "Test Complete")
                Log.i("TEST", "=".repeat(70))
                finish()
            }
        }
    }

    private suspend fun runTests() {
        // Initialize
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        Config.initGlobalConfig(prefs, resources, null, false)

        val engine = NeuralSwipeTypingEngine(this, Config.globalConfig())
        engine.setKeyboardDimensions(360, 280) // Training dimensions

        val initSuccess = engine.initialize()
        if (!initSuccess) {
            Log.e("TEST", "Engine init failed")
            return
        }

        Log.i("TEST", "Engine initialized")

        // Set real QWERTY key positions for 360×280 layout (Fix #38)
        // MUST be called AFTER initialize() so predictor exists
        val keyPositions = generateQwertyKeyPositions()
        Log.i("TEST", "Generated ${keyPositions.size} key positions")
        Log.i("TEST", "Sample positions: w=${keyPositions['w']}, h=${keyPositions['h']}, a=${keyPositions['a']}")
        engine.setRealKeyPositions(keyPositions)

        // Load test data
        val tests = loadTests()
        if (tests.isEmpty()) {
            Log.e("TEST", "No test data found")
            return
        }

        Log.i("TEST", "Running ${tests.size} tests...")

        var correct = 0
        tests.forEachIndexed { i, test ->
            val input = SwipeInput(test.points, test.timestamps, emptyList())
            val result = engine.predictAsync(input)
            val predicted = result.words.firstOrNull() ?: ""

            val status = if (predicted == test.word) {
                correct++
                "✅"
            } else "❌"

            Log.i("TEST", "[${i+1}/${tests.size}] '${test.word}' → '$predicted' $status")
        }

        val accuracy = (correct * 100.0) / tests.size
        Log.i("TEST", "Result: $correct/${tests.size} (${"%.1f".format(accuracy)}%)")

        engine.cleanup()
    }

    private fun loadTests(): List<TestData> {
        return try {
            assets.open("swipes.jsonl").bufferedReader().readLines().mapNotNull { line ->
                try {
                    val json = JSONObject(line)
                    val word = json.getString("word")
                    val xArr = json.getJSONArray("x_coords")
                    val yArr = json.getJSONArray("y_coords")

                    val points = (0 until xArr.length()).map { i ->
                        PointF(xArr.getDouble(i).toFloat(), yArr.getDouble(i).toFloat())
                    }

                    val timestamps = List(points.size) { it * 16L }
                    TestData(word, points, timestamps)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("TEST", "Failed to load tests: ${e.message}")
            emptyList()
        }
    }

    data class TestData(
        val word: String,
        val points: List<PointF>,
        val timestamps: List<Long>
    )

    /**
     * Generate QWERTY key center positions for 360×280 layout
     * Matches training data keyboard layout
     */
    private fun generateQwertyKeyPositions(): Map<Char, PointF> {
        val positions = mutableMapOf<Char, PointF>()

        // Standard QWERTY layout
        val row1 = "qwertyuiop"
        val row2 = "asdfghjkl"
        val row3 = "zxcvbnm"

        val keyWidth = 36f  // 360 / 10
        val rowHeight = 70f  // 280 / 4

        // Row 1: centered, starts at y=35 (middle of first row)
        row1.forEachIndexed { i, c ->
            positions[c] = PointF(keyWidth * i + keyWidth/2, rowHeight/2)
        }

        // Row 2: offset by half key, starts at y=105 (middle of second row)
        row2.forEachIndexed { i, c ->
            positions[c] = PointF(keyWidth/2 + keyWidth * i + keyWidth/2, rowHeight + rowHeight/2)
        }

        // Row 3: offset by 1 key, starts at y=175 (middle of third row)
        row3.forEachIndexed { i, c ->
            positions[c] = PointF(keyWidth + keyWidth * i + keyWidth/2, rowHeight * 2 + rowHeight/2)
        }

        return positions
    }
}
