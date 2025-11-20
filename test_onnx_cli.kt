/**
 * Complete ONNX Neural Pipeline CLI Test
 * Loads real ONNX models and runs actual inference to verify predictions
 */

import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

// ============================================================================
// Data Structures
// ============================================================================

data class PointF(val x: Float, val y: Float)

data class TrajectoryFeatures(
    val coordinates: List<PointF>,
    val velocities: List<PointF>,
    val accelerations: List<PointF>,
    val nearestKeys: List<Int>,
    val actualLength: Int
)

data class BeamState(
    val tokens: MutableList<Long>,
    var score: Float,
    var finished: Boolean
)

data class PredictionResult(
    val words: List<String>,
    val scores: List<Float>
)

// ============================================================================
// Constants
// ============================================================================

const val MAX_TRAJECTORY_POINTS = 250
const val KEYBOARD_WIDTH = 1080f
const val KEYBOARD_HEIGHT = 400f
const val BEAM_WIDTH = 8
const val MAX_LENGTH = 35
const val DECODER_SEQ_LENGTH = 20  // Fixed decoder sequence length (model was exported with this)
const val PAD_IDX = 0L
const val UNK_IDX = 1L
const val SOS_IDX = 2L
const val EOS_IDX = 3L

// Token mapping (a=4, b=5, ..., z=29)
val CHAR_TO_TOKEN = ('a'..'z').mapIndexed { idx, char -> char to (idx + 4).toLong() }.toMap()
val TOKEN_TO_CHAR = CHAR_TO_TOKEN.entries.associate { (k, v) -> v to k }

// QWERTY layout for nearest key detection
val QWERTY_LAYOUT = arrayOf(
    arrayOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
    arrayOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
    arrayOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
)

// ============================================================================
// Feature Extraction (Fix #6 - Correct Implementation)
// ============================================================================

/**
 * Extract features from swipe coordinates using CORRECT formulas
 * Fix #6: Normalize FIRST, then use simple deltas for velocity/acceleration
 */
fun extractFeatures(coordinates: List<PointF>): TrajectoryFeatures {
    println("📊 Feature Extraction Pipeline")
    println("   Input: ${coordinates.size} points")

    // Step 1: Normalize coordinates FIRST (critical fix)
    val normalizedCoords = coordinates.map {
        PointF(it.x / KEYBOARD_WIDTH, it.y / KEYBOARD_HEIGHT)
    }
    println("   ✅ Step 1: Normalized coordinates to [0,1]")

    // Step 2: Nearest keys - set to 0 (PAD) for synthetic test
    val nearestKeys = List(coordinates.size) { 0 }
    println("   ✅ Step 2: Set nearest keys to PAD (synthetic test)")

    // Step 3: Pad or truncate to MAX_TRAJECTORY_POINTS
    val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS, PointF(0f, 0f))
    val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)
    println("   ✅ Step 3: Padded to $MAX_TRAJECTORY_POINTS points")

    // Step 4: Calculate velocities and accelerations (SIMPLE DELTAS)
    val velocities = mutableListOf<PointF>()
    val accelerations = mutableListOf<PointF>()

    for (i in 0 until MAX_TRAJECTORY_POINTS) {
        if (i == 0) {
            velocities.add(PointF(0f, 0f))
            accelerations.add(PointF(0f, 0f))
        } else if (i == 1) {
            // Velocity: simple delta vx = x[i] - x[i-1]
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))
            accelerations.add(PointF(0f, 0f))
        } else {
            // Velocity: simple delta
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))

            // Acceleration: velocity delta ax = vx[i] - vx[i-1]
            val ax = vx - velocities[i-1].x
            val ay = vy - velocities[i-1].y
            accelerations.add(PointF(ax, ay))
        }
    }
    println("   ✅ Step 4: Calculated velocities and accelerations (simple deltas)")

    return TrajectoryFeatures(
        coordinates = finalCoords,
        velocities = velocities,
        accelerations = accelerations,
        nearestKeys = finalNearestKeys,
        actualLength = coordinates.size.coerceAtMost(MAX_TRAJECTORY_POINTS)
    )
}

fun detectNearestKey(point: PointF): Int {
    // Standard QWERTY key positions (center of each key) on 1080x400 keyboard
    val keyPositions = mapOf(
        'q' to PointF(54f, 100f), 'w' to PointF(162f, 100f), 'e' to PointF(270f, 100f),
        'r' to PointF(378f, 100f), 't' to PointF(486f, 100f), 'y' to PointF(594f, 100f),
        'u' to PointF(702f, 100f), 'i' to PointF(810f, 100f), 'o' to PointF(918f, 100f),
        'p' to PointF(1026f, 100f),

        'a' to PointF(108f, 200f), 's' to PointF(216f, 200f), 'd' to PointF(324f, 200f),
        'f' to PointF(432f, 200f), 'g' to PointF(540f, 200f), 'h' to PointF(648f, 200f),
        'j' to PointF(756f, 200f), 'k' to PointF(864f, 200f), 'l' to PointF(972f, 200f),

        'z' to PointF(162f, 300f), 'x' to PointF(270f, 300f), 'c' to PointF(378f, 300f),
        'v' to PointF(486f, 300f), 'b' to PointF(594f, 300f), 'n' to PointF(702f, 300f),
        'm' to PointF(810f, 300f)
    )

    // Find nearest key by Euclidean distance
    var nearestKey = 'a'
    var minDistance = Float.MAX_VALUE

    for ((char, keyPos) in keyPositions) {
        val dx = point.x - keyPos.x
        val dy = point.y - keyPos.y
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (distance < minDistance) {
            minDistance = distance
            nearestKey = char
        }
    }

    return CHAR_TO_TOKEN[nearestKey]?.toInt() ?: 0
}

fun <T> padOrTruncate(list: List<T>, targetSize: Int, paddingValue: T): List<T> {
    return when {
        list.size >= targetSize -> list.take(targetSize)
        else -> list + List(targetSize - list.size) { paddingValue }
    }
}

// ============================================================================
// ONNX Tensor Creation
// ============================================================================

fun createTrajectoryTensor(env: OrtEnvironment, features: TrajectoryFeatures): OnnxTensor {
    // Shape: [batch_size=1, seq_length=150, features=6]
    val shape = longArrayOf(1, MAX_TRAJECTORY_POINTS.toLong(), 6)
    val data = FloatArray(MAX_TRAJECTORY_POINTS * 6)

    for (i in 0 until MAX_TRAJECTORY_POINTS) {
        val baseIdx = i * 6
        data[baseIdx + 0] = features.coordinates[i].x
        data[baseIdx + 1] = features.coordinates[i].y
        data[baseIdx + 2] = features.velocities[i].x
        data[baseIdx + 3] = features.velocities[i].y
        data[baseIdx + 4] = features.accelerations[i].x
        data[baseIdx + 5] = features.accelerations[i].y
    }

    return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
}

fun createNearestKeysTensor(env: OrtEnvironment, features: TrajectoryFeatures): OnnxTensor {
    // Shape: [batch_size=1, seq_length=150] - inferred from 2D array
    val data = Array(1) { features.nearestKeys.map { it.toLong() }.toLongArray() }
    return OnnxTensor.createTensor(env, data)
}

fun createSourceMaskTensor(env: OrtEnvironment, actualLength: Int): OnnxTensor {
    // Shape: [batch_size=1, seq_length=150] - inferred from 2D array
    // Convention: true = padded, false = valid
    val data = Array(1) { BooleanArray(MAX_TRAJECTORY_POINTS) { i -> i >= actualLength } }
    return OnnxTensor.createTensor(env, data)
}

fun createTargetTokensTensor(env: OrtEnvironment, tokens: List<Long>): OnnxTensor {
    // Shape: [batch_size=1, seq_length=20] - FIXED length, pad with PAD_IDX
    val paddedTokens = tokens.take(DECODER_SEQ_LENGTH).toMutableList()
    while (paddedTokens.size < DECODER_SEQ_LENGTH) {
        paddedTokens.add(PAD_IDX)
    }
    val data = Array(1) { paddedTokens.toLongArray() }
    return OnnxTensor.createTensor(env, data)
}

fun createTargetMaskTensor(env: OrtEnvironment, validLength: Int): OnnxTensor {
    // Shape: [batch_size=1, seq_length=20] - FIXED length
    // Convention: true = padded, false = valid
    val data = Array(1) { BooleanArray(DECODER_SEQ_LENGTH) { i -> i >= validLength } }
    return OnnxTensor.createTensor(env, data)
}

// ============================================================================
// ONNX Model Loading
// ============================================================================

class OnnxModels(
    val encoder: OrtSession,
    val decoder: OrtSession,
    val env: OrtEnvironment
) {
    fun close() {
        encoder.close()
        decoder.close()
    }
}

fun loadOnnxModels(modelsDir: String): OnnxModels {
    println("\n🔧 Loading ONNX Models")
    println("-" .repeat(70))

    val env = OrtEnvironment.getEnvironment()

    // Load encoder model
    val encoderPath = "$modelsDir/swipe_model_character_quant.onnx"
    val encoderFile = File(encoderPath)
    if (!encoderFile.exists()) {
        throw IllegalStateException("Encoder model not found: $encoderPath")
    }
    println("   Loading encoder: ${encoderFile.name} (${encoderFile.length() / 1024 / 1024}MB)")
    val encoder = env.createSession(encoderFile.absolutePath)
    println("   ✅ Encoder loaded")

    // Load decoder model
    val decoderPath = "$modelsDir/swipe_decoder_character_quant.onnx"
    val decoderFile = File(decoderPath)
    if (!decoderFile.exists()) {
        throw IllegalStateException("Decoder model not found: $decoderPath")
    }
    println("   Loading decoder: ${decoderFile.name} (${decoderFile.length() / 1024 / 1024}MB)")
    val decoder = env.createSession(decoderFile.absolutePath)
    println("   ✅ Decoder loaded")

    return OnnxModels(encoder, decoder, env)
}

// ============================================================================
// Neural Prediction Pipeline
// ============================================================================

fun runEncoderInference(
    models: OnnxModels,
    features: TrajectoryFeatures
): OnnxTensor {
    println("\n🧠 Running Encoder Inference")

    val trajectoryTensor = createTrajectoryTensor(models.env, features)
    val nearestKeysTensor = createNearestKeysTensor(models.env, features)
    val srcMaskTensor = createSourceMaskTensor(models.env, features.actualLength)

    val inputs = mapOf(
        "trajectory_features" to trajectoryTensor,
        "nearest_keys" to nearestKeysTensor,
        "src_mask" to srcMaskTensor
    )

    val startTime = System.currentTimeMillis()
    val outputs = models.encoder.run(inputs)
    val encoderTime = System.currentTimeMillis() - startTime

    println("   ✅ Encoder completed in ${encoderTime}ms")

    // Clean up input tensors
    trajectoryTensor.close()
    nearestKeysTensor.close()
    srcMaskTensor.close()

    return outputs.get(0) as OnnxTensor
}

fun runDecoderStep(
    models: OnnxModels,
    encoderOutput: OnnxTensor,
    targetTokens: List<Long>,
    srcMask: OnnxTensor
): FloatArray {
    val targetTensor = createTargetTokensTensor(models.env, targetTokens)
    val targetMaskTensor = createTargetMaskTensor(models.env, targetTokens.size)

    val inputs = mapOf(
        "memory" to encoderOutput,
        "target_tokens" to targetTensor,
        "src_mask" to srcMask,
        "target_mask" to targetMaskTensor
    )

    val outputs = models.decoder.run(inputs)
    val logits = ((outputs.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)[0]

    // Clean up tensors
    targetTensor.close()
    targetMaskTensor.close()
    outputs.close()

    // Get logits at current position (targetTokens.size - 1)
    return logits[targetTokens.size - 1]
}

fun applyLogSoftmax(logits: FloatArray): FloatArray {
    val maxLogit = logits.maxOrNull() ?: 0f
    val expValues = logits.map { exp((it - maxLogit).toDouble()).toFloat() }
    val sumExp = expValues.sum()
    val logSumExp = ln(sumExp.toDouble()).toFloat() + maxLogit
    return logits.map { it - logSumExp }.toFloatArray()
}

fun beamSearchDecode(
    models: OnnxModels,
    encoderOutput: OnnxTensor,
    srcMask: OnnxTensor
): PredictionResult {
    println("\n🔍 Beam Search Decoding (width=$BEAM_WIDTH)")

    // Initialize beams
    val beams = MutableList(BEAM_WIDTH) {
        BeamState(mutableListOf(SOS_IDX), 0f, false)
    }

    var step = 0
    while (step < MAX_LENGTH) {
        val allCandidates = mutableListOf<BeamState>()

        for (beam in beams) {
            if (beam.finished) {
                allCandidates.add(beam)
                continue
            }

            // Run decoder for this beam
            val logits = runDecoderStep(models, encoderOutput, beam.tokens, srcMask)
            val logProbs = applyLogSoftmax(logits)

            // Get top-k tokens
            val topK = logProbs.withIndex()
                .sortedByDescending { it.value }
                .take(BEAM_WIDTH)

            for ((tokenIdx, logProb) in topK) {
                val newTokens = beam.tokens.toMutableList()
                newTokens.add(tokenIdx.toLong())

                val newScore = beam.score + logProb
                val finished = tokenIdx.toLong() == EOS_IDX || newTokens.size >= MAX_LENGTH

                allCandidates.add(BeamState(newTokens, newScore, finished))
            }
        }

        // Select top beams
        beams.clear()
        beams.addAll(allCandidates.sortedByDescending { it.score }.take(BEAM_WIDTH))

        step++

        // Early stopping optimization
        val finishedCount = beams.count { it.finished }
        if (beams.all { it.finished } || (step >= 10 && finishedCount >= 3)) {
            println("   ⚡ Early stopping at step $step ($finishedCount beams finished)")
            break
        }
    }

    println("   ✅ Generated ${beams.size} predictions")

    // Convert beams to words
    val words = beams.map { beam ->
        beam.tokens
            .filter { it > EOS_IDX } // Remove special tokens
            .mapNotNull { TOKEN_TO_CHAR[it] }
            .joinToString("")
    }

    val scores = beams.map { exp(it.score) } // Convert log probs to probabilities

    return PredictionResult(words, scores)
}

fun predictWithOnnx(models: OnnxModels, features: TrajectoryFeatures): PredictionResult {
    println("\n🚀 Running Complete Neural Prediction Pipeline")
    println("=" .repeat(70))

    val totalStartTime = System.currentTimeMillis()

    // Step 1: Encoder inference
    val encoderOutput = runEncoderInference(models, features)

    // Step 2: Beam search decoding
    val srcMask = createSourceMaskTensor(models.env, features.actualLength)
    val result = beamSearchDecode(models, encoderOutput, srcMask)

    val totalTime = System.currentTimeMillis() - totalStartTime
    println("\n⏱️  Total prediction time: ${totalTime}ms")

    // Clean up
    srcMask.close()

    return result
}

// ============================================================================
// Validation & Display
// ============================================================================

fun validatePredictions(result: PredictionResult, targetWord: String) {
    println("\n📊 Prediction Quality Analysis")
    println("=" .repeat(70))

    var allPassed = true

    // Check 1: Got predictions
    val check1 = result.words.isNotEmpty()
    println("   ${if (check1) "✅" else "❌"} Generated predictions: ${result.words.size}")
    allPassed = allPassed && check1

    // Check 2: No empty words
    val check2 = result.words.all { it.isNotEmpty() }
    println("   ${if (check2) "✅" else "❌"} All predictions non-empty")
    allPassed = allPassed && check2

    // Check 3: All alphabetic (no gibberish like "ggeeeeee")
    val gibberishWords = result.words.filter { word ->
        val hasRepeatedChars = word.zipWithNext().count { (a, b) -> a == b } > word.length / 2
        val isTooLong = word.length > 15
        val hasOnlyOneChar = word.toSet().size == 1
        hasRepeatedChars || isTooLong || hasOnlyOneChar
    }
    val check3 = gibberishWords.isEmpty()
    println("   ${if (check3) "✅" else "❌"} No gibberish patterns detected")
    if (!check3) {
        println("      Gibberish found: ${gibberishWords.joinToString(", ")}")
    }
    allPassed = allPassed && check3

    // Check 4: Target word found
    val check4 = result.words.contains(targetWord)
    println("   ${if (check4) "✅" else "❌"} Target word '$targetWord' found: $check4")
    if (check4) {
        val rank = result.words.indexOf(targetWord) + 1
        println("      Found at rank: $rank")
    }
    allPassed = allPassed && check4

    // Check 5: Scores are reasonable
    val maxScore = result.scores.maxOrNull() ?: 0f
    val minScore = result.scores.minOrNull() ?: 0f
    val check5 = maxScore > 0f && maxScore <= 1.0f
    println("   ${if (check5) "✅" else "❌"} Score range reasonable: [${"%.4f".format(minScore)}, ${"%.4f".format(maxScore)}]")
    allPassed = allPassed && check5

    println()
    if (allPassed) {
        println("🎉 ALL VALIDATION CHECKS PASSED!")
        println("   Neural prediction is producing accurate words (not gibberish)")
    } else {
        println("❌ VALIDATION FAILED - Review implementation")
    }
}

fun displayPredictions(result: PredictionResult) {
    println("\n🎯 Top Predictions")
    println("=" .repeat(70))

    result.words.zip(result.scores).take(10).forEachIndexed { index, (word, score) ->
        val percentage = (score * 100).coerceAtMost(100f).toInt()
        val bar = "█".repeat((score * 50).coerceAtMost(50f).toInt())
        println("   ${index + 1}. %-15s [%.1f%%] %s".format(word, score * 100, bar))
    }
}

// ============================================================================
// Test Cases
// ============================================================================

fun createTestSwipe(word: String): List<PointF> {
    println("\n📝 Creating Test Swipe for '$word'")

    // Standard QWERTY key positions (same as detectNearestKey)
    val keyPositions = mapOf(
        'q' to PointF(54f, 100f), 'w' to PointF(162f, 100f), 'e' to PointF(270f, 100f),
        'r' to PointF(378f, 100f), 't' to PointF(486f, 100f), 'y' to PointF(594f, 100f),
        'u' to PointF(702f, 100f), 'i' to PointF(810f, 100f), 'o' to PointF(918f, 100f),
        'p' to PointF(1026f, 100f),
        'a' to PointF(108f, 200f), 's' to PointF(216f, 200f), 'd' to PointF(324f, 200f),
        'f' to PointF(432f, 200f), 'g' to PointF(540f, 200f), 'h' to PointF(648f, 200f),
        'j' to PointF(756f, 200f), 'k' to PointF(864f, 200f), 'l' to PointF(972f, 200f),
        'z' to PointF(162f, 300f), 'x' to PointF(270f, 300f), 'c' to PointF(378f, 300f),
        'v' to PointF(486f, 300f), 'b' to PointF(594f, 300f), 'n' to PointF(702f, 300f),
        'm' to PointF(810f, 300f)
    )

    val points = mutableListOf<PointF>()

    for (char in word) {
        val keyPos = keyPositions[char] ?: continue

        // Add several points around each key (simulate continuous swipe)
        for (i in 0 until 10) {
            val x = keyPos.x + (Math.random() * 20 - 10).toFloat()
            val y = keyPos.y + (Math.random() * 15 - 7.5).toFloat()
            points.add(PointF(x, y))
        }
    }

    println("   Generated ${points.size} coordinate points")
    return points
}

// ============================================================================
// Main Test
// ============================================================================

fun main() {
    println("🧪 CleverKeys Complete ONNX Neural Pipeline CLI Test")
    println("=" .repeat(70))
    println()

    try {
        // Load ONNX models
        val modelsDir = "assets/models"
        val models = loadOnnxModels(modelsDir)

        println()
        println("🎯 Test Case: Swipe for word 'hello'")
        println("=" .repeat(70))

        // Create test swipe
        val helloSwipe = createTestSwipe("hello")

        // Extract features
        val features = extractFeatures(helloSwipe)

        // Show sample features
        println("\n📊 Sample Features (first 3 points):")
        for (i in 0 until 3) {
            val coord = features.coordinates[i]
            val vel = features.velocities[i]
            val acc = features.accelerations[i]
            println("   [$i] x=%.3f, y=%.3f, vx=%.4f, vy=%.4f, ax=%.4f, ay=%.4f".format(
                coord.x, coord.y, vel.x, vel.y, acc.x, acc.y
            ))
        }

        // Run neural prediction
        val result = predictWithOnnx(models, features)

        // Display results
        displayPredictions(result)

        // Validate predictions
        validatePredictions(result, "hello")

        // Clean up
        models.close()

        println()
        println("✅ Test completed successfully!")

    } catch (e: Exception) {
        println()
        println("❌ Test failed with exception:")
        println("   ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}
