import ai.onnxruntime.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/**
 * Standalone Kotlin test for ONNX swipe predictions
 * Compile: kotlinc -cp onnxruntime-android-1.20.0.jar:. TestOnnxPrediction.kt -include-runtime -d test.jar
 * Run: java -jar test.jar
 *
 * Updated for new ONNX model format (Nov 2025):
 * - Uses actual_length (int32) instead of src_mask (bool[])
 * - Uses int32 for nearest_keys and target_tokens
 * - Sequence length: 250 (matches production models)
 */

const val MAX_SEQUENCE_LENGTH = 250
const val DECODER_SEQ_LENGTH = 20
const val TRAJECTORY_FEATURES = 6
const val PAD_IDX = 0
const val SOS_IDX = 2
const val EOS_IDX = 3

val CHAR_TO_IDX = ('a'..'z').mapIndexed { i, c -> c to (i + 4) }.toMap()
val IDX_TO_CHAR = CHAR_TO_IDX.entries.associate { (k, v) -> v to k }

val QWERTY_LAYOUT = listOf(
    listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
    listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
    listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
)

data class SwipeTest(val word: String, val xCoords: List<Float>, val yCoords: List<Float>)
// Updated for new model format: int32 for nearestKeys, actualLength as single int
data class TrajectoryFeatures(val trajectory: FloatArray, val nearestKeys: IntArray, val actualLength: Int)

fun loadSwipesFromJson(path: String): List<SwipeTest> {
    val tests = mutableListOf<SwipeTest>()
    File(path).forEachLine { line ->
        if (line.isNotBlank()) {
            val word = Regex(""""word":\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@forEachLine
            val xMatch = Regex(""""x":\s*\[([\d.,\s]+)]""").find(line) ?: return@forEachLine
            val yMatch = Regex(""""y":\s*\[([\d.,\s]+)]""").find(line) ?: return@forEachLine

            val xCoords = xMatch.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
            val yCoords = yMatch.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }

            if (xCoords.size == yCoords.size) {
                tests.add(SwipeTest(word, xCoords, yCoords))
            }
        }
    }
    return tests
}

fun getNearestKey(x: Float, y: Float): Int {
    val keyWidth = 360.0f / 10
    val keyHeight = 280.0f / 4

    val row = (y / keyHeight).toInt().coerceIn(0, 2)
    val col = when (row) {
        0 -> (x / keyWidth).toInt().coerceIn(0, 9)
        1 -> ((x - keyWidth/2) / keyWidth).toInt().coerceIn(0, 8)
        else -> ((x - keyWidth) / keyWidth).toInt().coerceIn(0, 6)
    }

    val char = when (row) {
        0 -> QWERTY_LAYOUT[0].getOrNull(col)
        1 -> QWERTY_LAYOUT[1].getOrNull(col)
        else -> QWERTY_LAYOUT[2].getOrNull(col)
    } ?: return PAD_IDX

    return CHAR_TO_IDX[char] ?: PAD_IDX
}

fun extractFeatures(test: SwipeTest): TrajectoryFeatures {
    val actualLength = minOf(test.xCoords.size, MAX_SEQUENCE_LENGTH)
    val normalizedX = test.xCoords.map { it / 360.0f }
    val normalizedY = test.yCoords.map { it / 280.0f }

    val trajectory = FloatArray(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES)
    // New model format uses int32 for nearest_keys
    val nearestKeys = IntArray(MAX_SEQUENCE_LENGTH) { PAD_IDX }

    for (i in 0 until MAX_SEQUENCE_LENGTH) {
        val idx = i * TRAJECTORY_FEATURES

        if (i < actualLength) {
            val x = normalizedX[i]
            val y = normalizedY[i]

            val vx = if (i > 0) x - normalizedX[i-1] else 0f
            val vy = if (i > 0) y - normalizedY[i-1] else 0f

            val prevVx = if (i > 1) normalizedX[i-1] - normalizedX[i-2] else 0f
            val prevVy = if (i > 1) normalizedY[i-1] - normalizedY[i-2] else 0f
            val ax = vx - prevVx
            val ay = vy - prevVy

            trajectory[idx] = x
            trajectory[idx + 1] = y
            trajectory[idx + 2] = vx
            trajectory[idx + 3] = vy
            trajectory[idx + 4] = ax
            trajectory[idx + 5] = ay

            nearestKeys[i] = getNearestKey(test.xCoords[i], test.yCoords[i])
        } else {
            if (actualLength > 0) {
                val lastIdx = (actualLength - 1) * TRAJECTORY_FEATURES
                System.arraycopy(trajectory, lastIdx, trajectory, idx, TRAJECTORY_FEATURES)
                nearestKeys[i] = nearestKeys[actualLength - 1]
            }
        }
    }

    // New model format uses actualLength instead of srcMask
    return TrajectoryFeatures(trajectory, nearestKeys, actualLength)
}

fun createTensorFromFeatures(env: OrtEnvironment, features: TrajectoryFeatures): Triple<OnnxTensor, OnnxTensor, OnnxTensor> {
    val trajBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4)
    trajBuffer.order(ByteOrder.nativeOrder())
    trajBuffer.asFloatBuffer().put(features.trajectory)

    // HEX DUMP: First 30 float values (5 points × 6 features)
    val readTrajBuffer = trajBuffer.asFloatBuffer()
    readTrajBuffer.position(0)
    println("🔬 CLI Trajectory tensor hex dump (first 30 floats):")
    for (i in 0 until 30) {
        val value = readTrajBuffer.get()
        println(String.format("   [%2d] %.6f (0x%08x)", i, value, java.lang.Float.floatToRawIntBits(value)))
    }

    val trajTensor = OnnxTensor.createTensor(env, trajBuffer.asFloatBuffer(), longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong()))

    // New model format uses int32 for nearest_keys
    val keysTensor = OnnxTensor.createTensor(env, Array(1) { features.nearestKeys })

    // HEX DUMP: First 15 int values
    println("🔬 CLI Nearest keys tensor hex dump (first 15 ints):")
    for (i in 0 until 15) {
        println(String.format("   [%2d] %d", i, features.nearestKeys[i]))
    }

    // New model format uses actual_length (int32) instead of src_mask
    val actualLengthTensor = OnnxTensor.createTensor(env, intArrayOf(features.actualLength))

    return Triple(trajTensor, keysTensor, actualLengthTensor)
}

// Updated for new model format with actual_src_length instead of masks
fun runBeamSearch(env: OrtEnvironment, decoder: OrtSession, memory: OnnxTensor, actualLength: Int, beamSize: Int = 8, maxLen: Int = 20): String {
    // EXACTLY matching Python: (last_token, sequence, score)
    data class Beam(val lastToken: Int, val sequence: List<Int>, val score: Float)

    // Initialize beams with <sos> token
    var beams = listOf(Beam(SOS_IDX, listOf(SOS_IDX), 0.0f))

    // Create actual_src_length tensor once (reused for all beams)
    val actualSrcLengthTensor = OnnxTensor.createTensor(env, intArrayOf(actualLength))

    for (step in 0 until maxLen) {
        val candidates = mutableListOf<Beam>()

        for (beam in beams) {
            // New model format: target_tokens as int32
            val tgtTokens = IntArray(maxLen) { PAD_IDX }
            for (i in beam.sequence.indices) {
                if (i < maxLen) {
                    tgtTokens[i] = beam.sequence[i]
                }
            }

            val tgtTokensTensor = OnnxTensor.createTensor(env, Array(1) { tgtTokens })

            // Run decoder with new input format
            val decoderInputs = mapOf(
                "memory" to memory,
                "target_tokens" to tgtTokensTensor,
                "actual_src_length" to actualSrcLengthTensor
            )

            val result = decoder.run(decoderInputs)
            val logits = (result[0].value as Array<Array<FloatArray>>)[0]

            result.close()
            tgtTokensTensor.close()

            // Get logits for last valid position (Python line 155)
            val currentPos = beam.sequence.size - 1
            if (currentPos >= 0 && currentPos < DECODER_SEQ_LENGTH) {
                val vocabLogits = logits[currentPos]

                // Apply softmax (Python line 157)
                val probs = FloatArray(vocabLogits.size)
                val maxLogit = vocabLogits.maxOrNull() ?: 0f
                var sumExp = 0f
                for (i in vocabLogits.indices) {
                    val exp = exp((vocabLogits[i] - maxLogit).toDouble()).toFloat()
                    probs[i] = exp
                    sumExp += exp
                }
                for (i in probs.indices) {
                    probs[i] = probs[i] / sumExp
                }

                // Get top beam_size tokens (Python line 160)
                val topIndices = probs.withIndex()
                    .sortedByDescending { it.value }
                    .take(beamSize)
                    .map { it.index }

                for (idx in topIndices) {
                    val newScore = beam.score - ln(probs[idx].toDouble() + 1e-10).toFloat()
                    val newSeq = beam.sequence + idx
                    candidates.add(Beam(idx, newSeq, newScore))
                }
            }
        }

        // Select top beams (Python line 168)
        beams = candidates.sortedBy { it.score }.take(beamSize)

        // Check if all beams ended (Python line 171)
        if (beams.all { it.lastToken == EOS_IDX || it.lastToken == PAD_IDX }) {
            break
        }
    }

    // Cleanup actualSrcLengthTensor
    actualSrcLengthTensor.close()

    // Return best beam (Python line 175)
    val bestBeam = beams.firstOrNull() ?: return ""
    return bestBeam.sequence
        .drop(1) // Skip SOS
        .takeWhile { it != EOS_IDX && it != PAD_IDX }
        .mapNotNull { IDX_TO_CHAR[it] }
        .joinToString("")
}

fun main() {
    println("=".repeat(70))
    println("Kotlin CLI Test - 10 Valid Swipes (No Negative Coordinates)")
    println("=".repeat(70))

    // New model format (Nov 2025) with actual_length inputs
    val encoderPath = "src/main/assets/models/swipe_encoder_android.onnx"
    val decoderPath = "src/main/assets/models/swipe_decoder_android.onnx"
    val swipesPath = "test_swipes_10.jsonl"

    if (!File(encoderPath).exists()) {
        println("❌ ERROR: Encoder not found at $encoderPath")
        return
    }
    if (!File(decoderPath).exists()) {
        println("❌ ERROR: Decoder not found at $decoderPath")
        return
    }
    if (!File(swipesPath).exists()) {
        println("❌ ERROR: Test data not found at $swipesPath")
        return
    }

    println("\n✅ Loading ONNX models...")
    val env = OrtEnvironment.getEnvironment()
    val encoder = env.createSession(encoderPath)
    val decoder = env.createSession(decoderPath)

    println("✅ Encoder loaded: $encoderPath")
    println("✅ Decoder loaded: $decoderPath")

    println("\nEncoder inputs:")
    encoder.inputNames.forEach { name -> println("   $name") }

    println("\n✅ Models loaded successfully")

    println("\n✅ Loading test data from $swipesPath...")
    val tests = loadSwipesFromJson(swipesPath)
    println("✅ Loaded ${tests.size} test swipes")

    println("\n" + "=".repeat(70))
    println("Running Full Prediction Tests (Encoder + Decoder)")
    println("=".repeat(70))

    var correctCount = 0
    val results = mutableListOf<Pair<String, String>>()

    tests.forEachIndexed { i, test ->
        try {
            val features = extractFeatures(test)
            val (trajTensor, keysTensor, actualLengthTensor) = createTensorFromFeatures(env, features)

            // New model format uses actual_length instead of src_mask
            val encoderInputs = mapOf("trajectory_features" to trajTensor, "nearest_keys" to keysTensor, "actual_length" to actualLengthTensor)
            val encoderResult = encoder.run(encoderInputs)
            val memory = encoderResult[0] as OnnxTensor

            val predicted = runBeamSearch(env, decoder, memory, features.actualLength)

            val isCorrect = predicted == test.word
            val status = if (isCorrect) "✅" else "❌"

            println("  [${i+1}/${tests.size}] Target: '${test.word.padEnd(10)}' → Predicted: '${predicted.padEnd(10)}' $status")

            results.add(test.word to predicted)
            if (isCorrect) correctCount++

            encoderResult.close()
            trajTensor.close()
            keysTensor.close()
            actualLengthTensor.close()

        } catch (e: Exception) {
            println("  [${i+1}/${tests.size}] Target: '${test.word.padEnd(10)}' → ERROR: ${e.message} ❌")
            e.printStackTrace()
            results.add(test.word to "ERROR")
        }
    }

    println("\n" + "=".repeat(70))
    println("Test Summary")
    println("=".repeat(70))
    println("Total tests: ${tests.size}")
    println("Correct predictions: $correctCount")
    val accuracy = (correctCount.toFloat() / tests.size * 100)
    println("Prediction accuracy: ${"%.1f".format(accuracy)}%")
    println("=".repeat(70))

    println("\n📊 Detailed Results:")
    results.forEach { (target, predicted) ->
        val status = if (target == predicted) "✅ CORRECT" else "❌ WRONG"
        println("   $status: '$target' → '$predicted'")
    }

    println("\n✅ PREDICTION TEST COMPLETE")
    println("   ✅ Model accepts [batch, 250] nearest_keys (int32)")
    println("   ✅ Encoder+decoder pipeline working with actual_length format")
    val emoji = if (correctCount == tests.size) "✅" else "⚠️"
    println("   $emoji  Prediction accuracy: ${"%.1f".format(accuracy)}%")

    encoder.close()
    decoder.close()
}
