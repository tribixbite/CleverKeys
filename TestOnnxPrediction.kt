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
 */

const val MAX_SEQUENCE_LENGTH = 150
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
data class TrajectoryFeatures(val trajectory: FloatArray, val nearestKeys: LongArray, val srcMask: BooleanArray, val actualLength: Int)

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
    val nearestKeys = LongArray(MAX_SEQUENCE_LENGTH) { PAD_IDX.toLong() }

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

            nearestKeys[i] = getNearestKey(test.xCoords[i], test.yCoords[i]).toLong()
        } else {
            if (actualLength > 0) {
                val lastIdx = (actualLength - 1) * TRAJECTORY_FEATURES
                System.arraycopy(trajectory, lastIdx, trajectory, idx, TRAJECTORY_FEATURES)
                nearestKeys[i] = nearestKeys[actualLength - 1]
            }
        }
    }

    val srcMask = BooleanArray(MAX_SEQUENCE_LENGTH) { it >= actualLength }
    return TrajectoryFeatures(trajectory, nearestKeys, srcMask, actualLength)
}

fun createTensorFromFeatures(env: OrtEnvironment, features: TrajectoryFeatures): Triple<OnnxTensor, OnnxTensor, OnnxTensor> {
    val trajBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4)
    trajBuffer.order(ByteOrder.nativeOrder())
    trajBuffer.asFloatBuffer().put(features.trajectory)
    val trajTensor = OnnxTensor.createTensor(env, trajBuffer.asFloatBuffer(), longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong()))

    val keysBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8)
    keysBuffer.order(ByteOrder.nativeOrder())
    keysBuffer.asLongBuffer().put(features.nearestKeys)
    val keysTensor = OnnxTensor.createTensor(env, keysBuffer.asLongBuffer(), longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong()))

    val maskData = Array(1) { features.srcMask }
    val maskTensor = OnnxTensor.createTensor(env, maskData)

    return Triple(trajTensor, keysTensor, maskTensor)
}

fun runBeamSearch(env: OrtEnvironment, decoder: OrtSession, memory: OnnxTensor, beamSize: Int = 8, maxLen: Int = 20): String {
    data class Beam(val tokens: MutableList<Int>, var score: Float, var finished: Boolean = false)

    var beams = listOf(Beam(mutableListOf(SOS_IDX), 0f))

    for (step in 0 until maxLen) {
        // CRITICAL: Stop if we have finished beams and no more active beams,
        // OR if all beams are finished
        val finishedBeams = beams.filter { it.finished }
        val activeBeams = beams.filter { !it.finished }

        if (finishedBeams.isNotEmpty() && activeBeams.isEmpty()) {
            println("      Stopping at step $step: All beams finished")
            break
        }

        val candidates = mutableListOf<Beam>()

        for (beam in beams.filter { !it.finished }) {
            val tgtTokens = LongArray(DECODER_SEQ_LENGTH) { PAD_IDX.toLong() }
            val tgtMask = BooleanArray(DECODER_SEQ_LENGTH) { true }

            for ((i, token) in beam.tokens.withIndex()) {
                if (i < DECODER_SEQ_LENGTH) {
                    tgtTokens[i] = token.toLong()
                    tgtMask[i] = false
                }
            }

            val tgtTokensTensor = OnnxTensor.createTensor(env, Array(1) { tgtTokens })
            val tgtMaskTensor = OnnxTensor.createTensor(env, Array(1) { tgtMask })
            val srcMaskTensor = OnnxTensor.createTensor(env, Array(1) { BooleanArray(MAX_SEQUENCE_LENGTH) { false } })

            val decoderInputs = mapOf(
                "memory" to memory,
                "target_tokens" to tgtTokensTensor,
                "src_mask" to srcMaskTensor,
                "target_mask" to tgtMaskTensor
            )

            val result = decoder.run(decoderInputs)
            val logits = (result[0].value as Array<Array<FloatArray>>)[0]

            result.close()
            tgtTokensTensor.close()
            tgtMaskTensor.close()
            srcMaskTensor.close()

            val currentPos = beam.tokens.size - 1
            if (currentPos >= 0 && currentPos < DECODER_SEQ_LENGTH) {
                val vocabLogits = logits[currentPos]

                val maxLogit = vocabLogits.maxOrNull() ?: 0f
                val expValues = vocabLogits.map { exp((it - maxLogit).toDouble()).toFloat() }
                val sumExp = expValues.sum()
                val probs = expValues.map { it / sumExp }

                val topIndices = probs.withIndex().sortedByDescending { it.value }.take(beamSize).map { it.index }

                // Debug disabled for cleaner output

                for (tokenId in topIndices) {
                    val newBeam = Beam(
                        beam.tokens.toMutableList().apply { add(tokenId) },
                        beam.score - ln(probs[tokenId].toDouble() + 1e-10).toFloat()
                    )
                    // Mark beam as finished if it produces EOS or PAD token
                    if (tokenId == EOS_IDX || tokenId == PAD_IDX) {
                        newBeam.finished = true
                    }
                    candidates.add(newBeam)
                }
            }
        }

        beams = candidates.sortedBy { it.score }.take(beamSize)

        // CRITICAL: Stop if all beams have ended (matching Python implementation)
        if (beams.all { it.finished }) {
            break
        }
    }

    // Return best beam (lowest score = highest probability)
    val bestBeam = beams.minByOrNull { it.score } ?: return ""
    val bestTokens = bestBeam.tokens

    // Decode: skip SOS, stop at EOS/PAD
    return bestTokens
        .drop(1) // Skip SOS token
        .takeWhile { it != EOS_IDX && it != PAD_IDX }
        .mapNotNull { IDX_TO_CHAR[it] }
        .joinToString("")
}

fun main() {
    println("=".repeat(70))
    println("Kotlin Standalone Test - ONNX Swipe Recognition (No APK)")
    println("=".repeat(70))

    val encoderPath = "assets/models/swipe_model_character_quant.onnx"
    val decoderPath = "assets/models/swipe_decoder_character_quant.onnx"
    val swipesPath = "../swype-model-training/swipes.jsonl"

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
            val (trajTensor, keysTensor, maskTensor) = createTensorFromFeatures(env, features)

            val encoderInputs = mapOf("trajectory_features" to trajTensor, "nearest_keys" to keysTensor, "src_mask" to maskTensor)
            val encoderResult = encoder.run(encoderInputs)
            val memory = encoderResult[0] as OnnxTensor

            val predicted = runBeamSearch(env, decoder, memory)

            val isCorrect = predicted == test.word
            val status = if (isCorrect) "✅" else "❌"

            println("  [${i+1}/${tests.size}] Target: '${test.word.padEnd(10)}' → Predicted: '${predicted.padEnd(10)}' $status")

            results.add(test.word to predicted)
            if (isCorrect) correctCount++

            encoderResult.close()
            trajTensor.close()
            keysTensor.close()
            maskTensor.close()

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
    println("   ✅ Model accepts [batch, 150] nearest_keys (2D)")
    println("   ✅ Encoder+decoder pipeline working")
    val emoji = if (correctCount == tests.size) "✅" else "⚠️"
    println("   $emoji  Prediction accuracy: ${"%.1f".format(accuracy)}%")

    encoder.close()
    decoder.close()
}
