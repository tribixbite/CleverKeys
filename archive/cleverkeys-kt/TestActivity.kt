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

        val initSuccess = engine.initialize()
        if (!initSuccess) {
            Log.e("TEST", "Engine init failed")
            return
        }

        Log.i("TEST", "Engine initialized")

        // Set dimensions AFTER initialize() so predictor exists (Fix #40)
        engine.setKeyboardDimensions(360, 280) // Training dimensions
        Log.i("TEST", "Set keyboard dimensions: 360x280")

        // FIX #39: DON'T set real key positions - use grid detection like CLI test
        // Grid detection now matches CLI logic and should work correctly

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
            assets.open("test_swipes.jsonl").bufferedReader().readLines().mapNotNull { line ->
                try {
                    val json = JSONObject(line)
                    val word = json.getString("word")
                    val curve = json.getJSONObject("curve")
                    val xArr = curve.getJSONArray("x")
                    val yArr = curve.getJSONArray("y")
                    val tArr = curve.getJSONArray("t")

                    val points = (0 until xArr.length()).map { i ->
                        PointF(xArr.getDouble(i).toFloat(), yArr.getDouble(i).toFloat())
                    }

                    val timestamps = (0 until tArr.length()).map { i ->
                        tArr.getLong(i)
                    }

                    TestData(word, points, timestamps)
                } catch (e: Exception) {
                    Log.e("TEST", "Failed to parse line: ${e.message}")
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
     * EXACTLY matches CLI test logic (TestOnnxPrediction.kt line 52-70)
     */
    private fun generateQwertyKeyPositions(): Map<Char, PointF> {
        val positions = mutableMapOf<Char, PointF>()

        // QWERTY layout (same as CLI test)
        val row1 = "qwertyuiop"
        val row2 = "asdfghjkl"
        val row3 = "zxcvbnm"

        val keyWidth = 360.0f / 10
        val keyHeight = 280.0f / 4

        // Row 0: no offset (CLI logic: col = x / keyWidth)
        row1.forEachIndexed { col, c ->
            val x = col * keyWidth + keyWidth/2
            val y = keyHeight/2
            positions[c] = PointF(x, y)
        }

        // Row 1: offset by keyWidth/2 (CLI logic: col = (x - keyWidth/2) / keyWidth)
        row2.forEachIndexed { col, c ->
            val x = keyWidth/2 + col * keyWidth + keyWidth/2
            val y = keyHeight + keyHeight/2
            positions[c] = PointF(x, y)
        }

        // Row 2: offset by keyWidth (CLI logic: col = (x - keyWidth) / keyWidth)
        row3.forEachIndexed { col, c ->
            val x = keyWidth + col * keyWidth + keyWidth/2
            val y = 2 * keyHeight + keyHeight/2
            positions[c] = PointF(x, y)
        }

        return positions
    }
}
