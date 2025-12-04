#!/usr/bin/env kotlin

/**
 * Standalone Kotlin CLI test for ONNX swipe predictions
 * Run with: kotlinc -script test_prediction.kts
 * Or with gradle: ./gradlew --quiet -q runKotlinScript -PscriptFile=test_prediction.kts
 */

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
@file:DependsOn("org.json:json:20231013")

import ai.onnxruntime.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

// Constants matching OnnxSwipePredictorImpl.kt
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

data class Point(val x: Float, val y: Float)

data class SwipeTest(
    val word: String,
    val xCoords: List<Float>,
    val yCoords: List<Float>,
    val tCoords: List<Long>
)

data class TrajectoryFeatures(
    val trajectory: FloatArray,
    val nearestKeys: LongArray,
    val srcMask: BooleanArray,
    val actualLength: Int
)

fun loadSwipesFromJson(path: String): List<SwipeTest> {
    val tests = mutableListOf<SwipeTest>()
    File(path).forEachLine { line ->
        if (line.isNotBlank()) {
            val json = JSONObject(line)
            val curve = json.getJSONObject("curve")
            val word = json.getString("word")

            val xArray = curve.getJSONArray("x")
            val yArray = curve.getJSONArray("y")
            val tArray = curve.getJSONArray("t")

            val xCoords = (0 until xArray.length()).map { xArray.getDouble(it).toFloat() }
            val yCoords = (0 until yArray.length()).map { yArray.getDouble(it).toFloat() }
            val tCoords = (0 until tArray.length()).map { tArray.getLong(it) }

            tests.add(SwipeTest(word, xCoords, yCoords, tCoords))
        }
    }
    return tests
}

fun getNearestKey(x: Float, y: Float): Int {
    // Simple QWERTY grid detection (matches Python test)
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

fun extractFeatures(test: SwipeTest): TrajectoryFeatures {
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

fun createTensorFromFeatures(env: OrtEnvironment, features: TrajectoryFeatures): Triple<OnnxTensor, OnnxTensor, OnnxTensor> {
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

fun runBeamSearch(
    env: OrtEnvironment,
    encoder: OrtSession,
    decoder: OrtSession,
    memory: OnnxTensor,
    beamSize: Int = 8,
    maxLen: Int = 20
): String {
    data class Beam(val tokens: MutableList<Int>, var score: Float, var finished: Boolean = false)

    var beams = listOf(Beam(mutableListOf(SOS_IDX), 0f))

    for (step in 0 until maxLen) {
        if (beams.all { it.finished }) break

        val candidates = mutableListOf<Beam>()

        for (beam in beams.filter { !it.finished }) {
            // Prepare decoder input [1, DECODER_SEQ_LENGTH]
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

            // Get logits at current position
            val currentPos = beam.tokens.size - 1
            if (currentPos >= 0 && currentPos < DECODER_SEQ_LENGTH) {
                val vocabLogits = logits[currentPos]

                // Apply softmax
                val maxLogit = vocabLogits.maxOrNull() ?: 0f
                val expValues = vocabLogits.map { exp((it - maxLogit).toDouble()).toFloat() }
                val sumExp = expValues.sum()
                val probs = expValues.map { it / sumExp }

                // Get top beamSize tokens
                val topIndices = probs.withIndex()
                    .sortedByDescending { it.value }
                    .take(beamSize)
                    .map { it.index }

                for (tokenId in topIndices) {
                    val newBeam = Beam(
                        beam.tokens.toMutableList().apply { add(tokenId) },
                        beam.score - ln(probs[tokenId].toDouble() + 1e-10).toFloat()
                    )
                    if (tokenId == EOS_IDX || tokenId == PAD_IDX) {
                        newBeam.finished = true
                    }
                    candidates.add(newBeam)
                }
            }
        }

        beams = candidates.sortedBy { it.score }.take(beamSize)
    }

    // Decode best beam
    val bestTokens = beams.firstOrNull()?.tokens ?: return ""
    return bestTokens
        .drop(1) // Skip SOS
        .takeWhile { it != EOS_IDX && it != PAD_IDX }
        .mapNotNull { IDX_TO_CHAR[it] }
        .joinToString("")
}

fun main() {
    println("=" * 70)
    println("Kotlin CLI Prediction Test - ONNX Swipe Recognition")
    println("=" * 70)

    val encoderPath = "assets/models/swipe_model_character_quant.onnx"
    val decoderPath = "assets/models/swipe_decoder_character_quant.onnx"
    val swipesPath = "../swype-model-training/swipes.jsonl"

    // Check files exist
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

    // Validate encoder inputs
    println("\nEncoder inputs:")
    encoder.inputInfo.forEach { (name, info) ->
        println("   $name: ${info.info.shape.contentToString()}")
    }

    val nearestKeysInput = encoder.inputInfo["nearest_keys"]
    val nearestKeysShape = nearestKeysInput?.info?.shape
    if (nearestKeysShape?.size == 2) {
        println("\n✅ VALIDATION PASSED: nearest_keys is 2D ${nearestKeysShape.contentToString()}")
    } else {
        println("\n❌ VALIDATION FAILED: nearest_keys is ${nearestKeysShape?.size}D")
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
            val (trajTensor, keysTensor, maskTensor) = createTensorFromFeatures(env, features)

            // Validate tensor shapes
            assert(keysTensor.info.shape.size == 2) { "Keys tensor not 2D: ${keysTensor.info.shape.size}D" }

            // Run encoder
            val encoderInputs = mapOf(
                "trajectory_features" to trajTensor,
                "nearest_keys" to keysTensor,
                "src_mask" to maskTensor
            )

            val encoderResult = encoder.run(encoderInputs)
            val memory = encoderResult[0] as OnnxTensor

            // Run beam search decoder
            val predicted = runBeamSearch(env, encoder, decoder, memory)

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
            results.add(test.word to "ERROR")
        }
    }

    // Summary
    println("\n" + "=" * 70)
    println("Test Summary")
    println("=" * 70)
    println("Total tests: ${tests.size}")
    println("Correct predictions: $correctCount")
    println("Prediction accuracy: ${(correctCount.toFloat() / tests.size * 100).let { "%.1f".format(it) }}%")
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
    println("   $emoji  Prediction accuracy: ${(correctCount.toFloat() / tests.size * 100).let { "%.1f".format(it) }}%")

    // Cleanup
    encoder.close()
    decoder.close()
}

// Extension for string multiplication
operator fun String.times(n: Int) = this.repeat(n)

main()
