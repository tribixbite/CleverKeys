package tribixbite.keyboard2

import ai.onnxruntime.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/**
 * JVM unit test for ONNX swipe predictions
 * Run with: ./gradlew test --tests OnnxPredictionTest
 * No APK installation required - runs on JVM with onnxruntime-android
 */
class OnnxPredictionTest {

    companion object {
        const val MAX_SEQUENCE_LENGTH = 150
        const val DECODER_SEQ_LENGTH = 20
        const val TRAJECTORY_FEATURES = 6
        const val PAD_IDX = 0
        const val SOS_IDX = 2
        const val EOS_IDX = 3

        // Token mapping (a=4, b=5, ..., z=29)
        val CHAR_TO_IDX = ('a'..'z').mapIndexed { i, c -> c to (i + 4) }.toMap()
        val IDX_TO_CHAR = CHAR_TO_IDX.entries.associate { (k, v) -> v to k }

        // QWERTY layout for nearest key detection
        val QWERTY_LAYOUT = listOf(
            listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
            listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
            listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
        )
    }

    data class SwipeTest(
        val word: String,
        val xCoords: List<Float>,
        val yCoords: List<Float>
    )

    data class TrajectoryFeatures(
        val trajectory: FloatArray,
        val nearestKeys: LongArray,
        val srcMask: BooleanArray,
        val actualLength: Int
    )

    private lateinit var env: OrtEnvironment
    private lateinit var encoder: OrtSession
    private lateinit var decoder: OrtSession

    @Before
    fun setup() {
        println("=" * 70)
        println("Kotlin JVM Test - ONNX Swipe Recognition")
        println("=" * 70)

        val encoderPath = "assets/models/swipe_model_character_quant.onnx"
        val decoderPath = "assets/models/swipe_decoder_character_quant.onnx"

        assertTrue("Encoder model not found at $encoderPath", File(encoderPath).exists())
        assertTrue("Decoder model not found at $decoderPath", File(decoderPath).exists())

        println("\n✅ Loading ONNX models...")
        env = OrtEnvironment.getEnvironment()
        encoder = env.createSession(encoderPath)
        decoder = env.createSession(decoderPath)

        println("✅ Encoder loaded: $encoderPath")
        println("✅ Decoder loaded: $decoderPath")

        // Validate encoder inputs
        println("\nEncoder inputs:")
        encoder.inputInfo.forEach { (name, info) ->
            println("   $name: ${info.info.shape.contentToString()}")
        }

        val nearestKeysInput = encoder.inputInfo["nearest_keys"]
        val nearestKeysShape = nearestKeysInput?.info?.shape
        assertEquals("nearest_keys should be 2D", 2, nearestKeysShape?.size)
        println("\n✅ VALIDATION PASSED: nearest_keys is 2D ${nearestKeysShape?.contentToString()}")
    }

    @Test
    fun testPredictionWithRealSwipeData() {
        val swipesPath = "../swype-model-training/swipes.jsonl"

        if (!File(swipesPath).exists()) {
            println("⚠️  Test data not found at $swipesPath - skipping")
            return
        }

        println("\n✅ Loading test data from $swipesPath...")
        val tests = loadSwipesFromJson(swipesPath)
        println("✅ Loaded ${tests.size} test swipes")

        println("\n" + "=" * 70)
        println("Running Full Prediction Tests (Encoder + Decoder)")
        println("=" * 70)

        var correctCount = 0
        val results = mutableListOf<Pair<String, String>>()

        tests.forEachIndexed { i, test ->
            try {
                // Extract features
                val features = extractFeatures(test)

                // Create tensors
                val (trajTensor, keysTensor, maskTensor) = createTensorFromFeatures(features)

                // Validate tensor shapes
                assertEquals("Keys tensor should be 2D", 2, keysTensor.info.shape.size)

                // Run encoder
                val encoderInputs = mapOf(
                    "trajectory_features" to trajTensor,
                    "nearest_keys" to keysTensor,
                    "src_mask" to maskTensor
                )

                val encoderResult = encoder.run(encoderInputs)
                val memory = encoderResult[0] as OnnxTensor

                // Run beam search decoder
                val predicted = runBeamSearch(memory)

                val isCorrect = predicted == test.word
                val status = if (isCorrect) "✅" else "❌"

                println("  [${i+1}/${tests.size}] Target: '${test.word.padEnd(10)}' → Predicted: '${predicted.padEnd(10)}' $status")

                results.add(test.word to predicted)
                if (isCorrect) correctCount++

                // Cleanup
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

        // Summary
        println("\n" + "=" * 70)
        println("Test Summary")
        println("=" * 70)
        println("Total tests: ${tests.size}")
        println("Correct predictions: $correctCount")
        val accuracy = (correctCount.toFloat() / tests.size * 100)
        println("Prediction accuracy: ${"%.1f".format(accuracy)}%")
        println("=" * 70)

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

        // Assert reasonable accuracy
        assertTrue("Prediction accuracy should be at least 30%", accuracy >= 30.0f)
    }

    private fun loadSwipesFromJson(path: String): List<SwipeTest> {
        val tests = mutableListOf<SwipeTest>()
        File(path).forEachLine { line ->
            if (line.isNotBlank()) {
                // Parse JSON manually (avoid org.json dependency for JVM test)
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

    private fun getNearestKey(x: Float, y: Float): Int {
        // Simple QWERTY grid detection
        val keyWidth = 360.0f / 10  // ~36px per key
        val keyHeight = 280.0f / 4  // ~70px per row

        val row = (y / keyHeight).toInt().coerceIn(0, 2)
        val col = when (row) {
            0 -> (x / keyWidth).toInt().coerceIn(0, 9)  // Top row: 10 keys
            1 -> ((x - keyWidth/2) / keyWidth).toInt().coerceIn(0, 8)  // Middle: 9 keys
            else -> ((x - keyWidth) / keyWidth).toInt().coerceIn(0, 6)  // Bottom: 7 keys
        }

        val char = when (row) {
            0 -> QWERTY_LAYOUT[0].getOrNull(col)
            1 -> QWERTY_LAYOUT[1].getOrNull(col)
            else -> QWERTY_LAYOUT[2].getOrNull(col)
        } ?: return PAD_IDX

        return CHAR_TO_IDX[char] ?: PAD_IDX
    }

    private fun extractFeatures(test: SwipeTest): TrajectoryFeatures {
        val actualLength = minOf(test.xCoords.size, MAX_SEQUENCE_LENGTH)

        // Normalize coordinates
        val normalizedX = test.xCoords.map { it / 360.0f }
        val normalizedY = test.yCoords.map { it / 280.0f }

        // Calculate velocities and accelerations
        val trajectory = FloatArray(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES)
        val nearestKeys = LongArray(MAX_SEQUENCE_LENGTH) { PAD_IDX.toLong() }

        for (i in 0 until MAX_SEQUENCE_LENGTH) {
            val idx = i * TRAJECTORY_FEATURES

            if (i < actualLength) {
                val x = normalizedX[i]
                val y = normalizedY[i]

                // Velocity
                val vx = if (i > 0) x - normalizedX[i-1] else 0f
                val vy = if (i > 0) y - normalizedY[i-1] else 0f

                // Acceleration
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
                // Padding with last point
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

    private fun createTensorFromFeatures(features: TrajectoryFeatures): Triple<OnnxTensor, OnnxTensor, OnnxTensor> {
        // Trajectory tensor [1, 150, 6]
        val trajBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * TRAJECTORY_FEATURES * 4)
        trajBuffer.order(ByteOrder.nativeOrder())
        trajBuffer.asFloatBuffer().put(features.trajectory)
        val trajTensor = OnnxTensor.createTensor(
            env,
            trajBuffer.asFloatBuffer(),
            longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong(), TRAJECTORY_FEATURES.toLong())
        )

        // Nearest keys tensor [1, 150] - 2D format
        val keysBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8)
        keysBuffer.order(ByteOrder.nativeOrder())
        keysBuffer.asLongBuffer().put(features.nearestKeys)
        val keysTensor = OnnxTensor.createTensor(
            env,
            keysBuffer.asLongBuffer(),
            longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong())
        )

        // Source mask tensor [1, 150]
        val maskData = Array(1) { features.srcMask }
        val maskTensor = OnnxTensor.createTensor(env, maskData)

        return Triple(trajTensor, keysTensor, maskTensor)
    }

    private fun runBeamSearch(memory: OnnxTensor, beamSize: Int = 8, maxLen: Int = 20): String {
        // EXACTLY matching Python: (last_token, sequence, score)
        data class Beam(val lastToken: Int, val sequence: List<Int>, val score: Float)

        // Initialize beams with <sos> token
        var beams = listOf(Beam(SOS_IDX, listOf(SOS_IDX), 0.0f))

        for (step in 0 until maxLen) {
            val candidates = mutableListOf<Beam>()

            for (beam in beams) {
                // CRITICAL: Pad sequence to DECODER_SEQ_LENGTH (matches Python line 133)
                val tgtTokens = LongArray(DECODER_SEQ_LENGTH) { PAD_IDX.toLong() }
                for (i in beam.sequence.indices) {
                    if (i < DECODER_SEQ_LENGTH) {
                        tgtTokens[i] = beam.sequence[i].toLong()
                    }
                }

                // Create target mask (false = valid, true = padded) - matches Python line 138
                val tgtMask = BooleanArray(DECODER_SEQ_LENGTH) { true }
                for (i in beam.sequence.indices) {
                    tgtMask[i] = false  // Mark valid positions
                }

                // Create src_mask (all zeros = all valid) - matches Python line 142
                val srcMaskTensor = OnnxTensor.createTensor(env, Array(1) { BooleanArray(MAX_SEQUENCE_LENGTH) { false } })
                val tgtTokensTensor = OnnxTensor.createTensor(env, Array(1) { tgtTokens })
                val tgtMaskTensor = OnnxTensor.createTensor(env, Array(1) { tgtMask })

                // Run decoder
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

        // Return best beam (Python line 175)
        val bestBeam = beams.firstOrNull() ?: return ""
        return bestBeam.sequence
            .drop(1) // Skip SOS
            .takeWhile { it != EOS_IDX && it != PAD_IDX }
            .mapNotNull { IDX_TO_CHAR[it] }
            .joinToString("")
    }

    private operator fun String.times(n: Int) = this.repeat(n)
}
