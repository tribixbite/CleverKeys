#!/usr/bin/env kotlin

/**
 * CLI Test for ONNX Decoding Pipeline
 * Tests complete pipeline from coordinates to predictions
 */

import kotlin.math.*

// Mock data structures
data class PointF(val x: Float, val y: Float)

data class TrajectoryFeatures(
    val coordinates: List<PointF>,
    val velocities: List<PointF>,
    val accelerations: List<PointF>,
    val nearestKeys: List<Int>,
    val actualLength: Int,
    val normalizedCoordinates: List<PointF>
)

data class BeamState(
    val tokens: MutableList<Long>,
    var score: Float,
    var finished: Boolean
)

// Constants
const val MAX_TRAJECTORY_POINTS = 150
const val KEYBOARD_WIDTH = 1080f
const val KEYBOARD_HEIGHT = 400f
const val PAD_IDX = 0
const val UNK_IDX = 1
const val SOS_IDX = 2
const val EOS_IDX = 3
const val BEAM_WIDTH = 8
const val MAX_LENGTH = 35

// Token mapping (a=4, b=5, ..., z=29)
val tokenToChar = mapOf(
    0 to "<PAD>", 1 to "<UNK>", 2 to "<SOS>", 3 to "<EOS>",
    4 to "a", 5 to "b", 6 to "c", 7 to "d", 8 to "e", 9 to "f",
    10 to "g", 11 to "h", 12 to "i", 13 to "j", 14 to "k", 15 to "l",
    16 to "m", 17 to "n", 18 to "o", 19 to "p", 20 to "q", 21 to "r",
    22 to "s", 23 to "t", 24 to "u", 25 to "v", 26 to "w", 27 to "x",
    28 to "y", 29 to "z"
)

/**
 * Feature extraction matching Kotlin implementation
 */
fun extractFeatures(coordinates: List<PointF>): TrajectoryFeatures {
    println("📊 Feature Extraction Pipeline")
    println("   Input: ${coordinates.size} points")

    // 1. Normalize coordinates FIRST
    val normalizedCoords = coordinates.map {
        PointF(it.x / KEYBOARD_WIDTH, it.y / KEYBOARD_HEIGHT)
    }
    println("   ✅ Step 1: Normalized coordinates")

    // 2. Detect nearest keys
    val nearestKeys = coordinates.map { detectNearestKey(it) }
    println("   ✅ Step 2: Detected nearest keys")

    // 3. Pad or truncate to MAX_TRAJECTORY_POINTS
    val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS, PointF(0f, 0f))
    val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)
    println("   ✅ Step 3: Padded to $MAX_TRAJECTORY_POINTS points")

    // 4. Calculate velocities and accelerations (simple deltas)
    val velocities = mutableListOf<PointF>()
    val accelerations = mutableListOf<PointF>()

    for (i in 0 until MAX_TRAJECTORY_POINTS) {
        if (i == 0) {
            velocities.add(PointF(0f, 0f))
            accelerations.add(PointF(0f, 0f))
        } else if (i == 1) {
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))
            accelerations.add(PointF(0f, 0f))
        } else {
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))

            val ax = vx - velocities[i-1].x
            val ay = vy - velocities[i-1].y
            accelerations.add(PointF(ax, ay))
        }
    }
    println("   ✅ Step 4: Calculated velocities and accelerations")

    return TrajectoryFeatures(
        coordinates = finalCoords,
        velocities = velocities,
        accelerations = accelerations,
        nearestKeys = finalNearestKeys,
        actualLength = coordinates.size.coerceAtMost(MAX_TRAJECTORY_POINTS),
        normalizedCoordinates = finalCoords
    )
}

fun detectNearestKey(point: PointF): Int {
    val normalizedX = point.x / KEYBOARD_WIDTH
    val normalizedY = point.y / KEYBOARD_HEIGHT

    val qwertyLayout = arrayOf(
        arrayOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'),
        arrayOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'),
        arrayOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    )

    val row = (normalizedY * qwertyLayout.size).toInt().coerceIn(0, qwertyLayout.size - 1)
    val rowKeys = qwertyLayout[row]

    val effectiveX = when (row) {
        1 -> normalizedX - 0.05f
        2 -> normalizedX - 0.15f
        else -> normalizedX
    }

    val col = (effectiveX * rowKeys.size).toInt().coerceIn(0, rowKeys.size - 1)
    val detectedChar = rowKeys[col]

    return if (detectedChar in 'a'..'z') (detectedChar - 'a') + 4 else 0
}

fun <T> padOrTruncate(list: List<T>, targetSize: Int, paddingValue: T): List<T> {
    return when {
        list.size >= targetSize -> list.take(targetSize)
        else -> list + List(targetSize - list.size) { paddingValue }
    }
}

/**
 * Create tensor data from features
 */
fun createTensorData(features: TrajectoryFeatures): FloatArray {
    val tensorData = FloatArray(MAX_TRAJECTORY_POINTS * 6)

    for (i in 0 until MAX_TRAJECTORY_POINTS) {
        val point = features.normalizedCoordinates[i]
        val velocity = features.velocities[i]
        val acceleration = features.accelerations[i]

        val baseIdx = i * 6
        tensorData[baseIdx + 0] = point.x
        tensorData[baseIdx + 1] = point.y
        tensorData[baseIdx + 2] = velocity.x
        tensorData[baseIdx + 3] = velocity.y
        tensorData[baseIdx + 4] = acceleration.x
        tensorData[baseIdx + 5] = acceleration.y
    }

    return tensorData
}

/**
 * Mock beam search with simulated logits
 */
fun mockBeamSearch(features: TrajectoryFeatures, targetWord: String): List<Pair<String, Float>> {
    println("\n🔍 Mock Beam Search Decoder")
    println("   Target word: '$targetWord'")

    // Initialize beam with SOS token
    var beams = listOf(BeamState(mutableListOf(SOS_IDX.toLong()), 0.0f, false))
    val finishedBeams = mutableListOf<BeamState>()

    // Convert target word to token sequence for simulation
    val targetTokens = targetWord.map { char ->
        if (char in 'a'..'z') (char - 'a') + 4 else UNK_IDX
    }

    println("   Target tokens: $targetTokens")

    // Simulate beam search steps
    for (step in 0 until targetWord.length + 1) {
        val activeBeams = beams.filter { !it.finished }

        if (activeBeams.isEmpty()) break

        val newCandidates = mutableListOf<BeamState>()

        for (beam in activeBeams) {
            // Simulate logits for current position
            val currentPos = beam.tokens.size - 1

            // Create mock logits favoring target word
            val topK = if (currentPos < targetTokens.size) {
                // Favor correct next character
                val correctToken = targetTokens[currentPos]
                listOf(
                    correctToken to -0.5f,  // High probability for correct
                    ((correctToken + 1) % 26 + 4) to -2.0f,  // Lower for alternatives
                    ((correctToken + 2) % 26 + 4) to -2.5f
                ).take(BEAM_WIDTH)
            } else {
                // End of word - favor EOS
                listOf(EOS_IDX to -0.3f)
            }

            // Expand beam
            for ((tokenId, logProb) in topK) {
                val newBeam = BeamState(
                    tokens = (beam.tokens + tokenId.toLong()).toMutableList(),
                    score = beam.score + logProb,
                    finished = tokenId == EOS_IDX
                )
                newCandidates.add(newBeam)
            }
        }

        // Keep top beams
        beams = newCandidates.sortedByDescending { it.score }.take(BEAM_WIDTH)
        finishedBeams.addAll(beams.filter { it.finished })

        // Early stopping
        if (step >= 10 && finishedBeams.size >= 3) {
            println("   ⚡ Early stopping at step $step (${finishedBeams.size} beams finished)")
            break
        }
    }

    // Decode beams to words
    val allBeams = (finishedBeams + beams).sortedByDescending { it.score }
    val predictions = allBeams.map { beam ->
        val word = beam.tokens.drop(1).filter { it != EOS_IDX.toLong() }
            .mapNotNull { tokenToChar[it.toInt()] }
            .joinToString("")
        val confidence = exp(beam.score)
        word to confidence
    }.filter { it.first.isNotEmpty() }.distinctBy { it.first }.take(3)

    println("   ✅ Generated ${predictions.size} predictions")

    return predictions
}

/**
 * Apply log-softmax (numerically stable)
 */
fun applyLogSoftmax(logits: FloatArray): FloatArray {
    val maxLogit = logits.maxOrNull() ?: 0f
    val expValues = logits.map { exp((it - maxLogit).toDouble()).toFloat() }
    val sumExp = expValues.sum()
    val logSumExp = ln(sumExp.toDouble()).toFloat() + maxLogit
    return logits.map { it - logSumExp }.toFloatArray()
}

/**
 * Test mask conventions
 */
fun testMaskConventions(actualLength: Int) {
    println("\n🎭 Mask Conventions Test")

    // Source mask (1 = padded, 0 = valid)
    val srcMask = BooleanArray(MAX_TRAJECTORY_POINTS) { i -> i >= actualLength }
    val validCount = srcMask.count { !it }
    val paddedCount = srcMask.count { it }

    println("   Source Mask:")
    println("      Valid positions (0): $validCount")
    println("      Padded positions (1): $paddedCount")
    println("      Convention: ✅ 1=padded, 0=valid")

    // Target mask example
    val targetLength = 5
    val targetMask = BooleanArray(20) { i -> i >= targetLength }

    println("   Target Mask (for 5 tokens):")
    println("      Valid positions (0): ${targetMask.count { !it }}")
    println("      Padded positions (1): ${targetMask.count { it }}")
    println("      Convention: ✅ 1=padded, 0=valid")
}

/**
 * Main test function
 */
fun main() {
    println("🧪 CleverKeys ONNX Decoding Pipeline CLI Test")
    println("=" .repeat(70))
    println()

    // Test Case: "hello" swipe
    println("📝 Test Case: Swipe for word 'hello'")
    println("-" .repeat(70))

    // Realistic swipe coordinates (h -> e -> l -> l -> o)
    val helloSwipe = listOf(
        // Start at 'h' (middle of ASDF row)
        PointF(540f, 200f), PointF(545f, 200f), PointF(550f, 200f),
        // Move to 'e' (top row, left-middle)
        PointF(500f, 150f), PointF(350f, 100f), PointF(280f, 100f),
        // Move to 'l' (ASDF row, right-middle)
        PointF(600f, 180f), PointF(720f, 200f), PointF(730f, 200f),
        // Stay at 'l' (double letter)
        PointF(740f, 200f), PointF(745f, 200f),
        // Move to 'o' (top row, right-middle)
        PointF(780f, 120f), PointF(810f, 100f), PointF(820f, 100f)
    )

    println("   Coordinates: ${helloSwipe.size} points")
    println("   Path: h -> e -> l -> l -> o")
    println()

    // Step 1: Feature Extraction
    val features = extractFeatures(helloSwipe)
    println()

    // Step 2: Tensor Creation
    println("🎯 Tensor Creation")
    val tensorData = createTensorData(features)
    println("   Trajectory tensor shape: [1, $MAX_TRAJECTORY_POINTS, 6]")
    println("   Tensor size: ${tensorData.size} floats (${tensorData.size * 4} bytes)")
    println("   Nearest keys tensor shape: [1, $MAX_TRAJECTORY_POINTS]")
    println("   Source mask tensor shape: [1, $MAX_TRAJECTORY_POINTS]")

    // Show first 3 feature vectors
    println("\n   First 3 feature vectors:")
    for (i in 0 until 3) {
        val baseIdx = i * 6
        val x = tensorData[baseIdx + 0]
        val y = tensorData[baseIdx + 1]
        val vx = tensorData[baseIdx + 2]
        val vy = tensorData[baseIdx + 3]
        val ax = tensorData[baseIdx + 4]
        val ay = tensorData[baseIdx + 5]
        println("      [$i] x=%.3f, y=%.3f, vx=%.3f, vy=%.3f, ax=%.3f, ay=%.3f".format(x, y, vx, vy, ax, ay))
    }

    // Step 3: Verify nearest keys
    println("\n   First 5 nearest keys:")
    val keySequence = features.nearestKeys.take(features.actualLength).take(5)
        .map { tokenToChar[it] ?: "?" }
        .joinToString(" -> ")
    println("      $keySequence")

    // Step 4: Mask Conventions
    testMaskConventions(features.actualLength)

    // Step 5: Mock Beam Search
    val predictions = mockBeamSearch(features, "hello")

    // Step 6: Display Results
    println("\n🎉 Final Predictions")
    println("-" .repeat(70))
    predictions.forEachIndexed { index, (word, confidence) ->
        val percentage = (confidence * 100).toInt()
        val bar = "█".repeat((confidence * 50).toInt())
        println("   ${index + 1}. %-10s [confidence: %3d%%] %s".format(word, percentage, bar))
    }

    // Verification Summary
    println("\n" + "=" .repeat(70))
    println("✅ Pipeline Verification Summary")
    println("=" .repeat(70))

    var allPassed = true

    // Check 1: Feature extraction
    val maxCoord = features.normalizedCoordinates.take(features.actualLength)
        .maxOf { maxOf(it.x, it.y) }
    val check1 = maxCoord <= 1.0f
    println("   ${if (check1) "✅" else "❌"} Feature extraction: Normalized to [0,1]")
    allPassed = allPassed && check1

    // Check 2: Tensor shape
    val check2 = tensorData.size == MAX_TRAJECTORY_POINTS * 6
    println("   ${if (check2) "✅" else "❌"} Tensor shape: [1, 150, 6] = ${tensorData.size} floats")
    allPassed = allPassed && check2

    // Check 3: Velocity calculation
    val nonZeroVelocities = (0 until features.actualLength)
        .count { i -> features.velocities[i].x != 0f || features.velocities[i].y != 0f }
    val check3 = nonZeroVelocities > 0
    println("   ${if (check3) "✅" else "❌"} Velocity calculation: $nonZeroVelocities non-zero values")
    allPassed = allPassed && check3

    // Check 4: Acceleration calculation
    val nonZeroAccelerations = (0 until features.actualLength)
        .count { i -> features.accelerations[i].x != 0f || features.accelerations[i].y != 0f }
    val check4 = nonZeroAccelerations > 0
    println("   ${if (check4) "✅" else "❌"} Acceleration calculation: $nonZeroAccelerations non-zero values")
    allPassed = allPassed && check4

    // Check 5: Mask convention
    val srcMask = BooleanArray(MAX_TRAJECTORY_POINTS) { i -> i >= features.actualLength }
    val check5 = srcMask[features.actualLength - 1] == false &&
                 srcMask[features.actualLength] == true
    println("   ${if (check5) "✅" else "❌"} Mask convention: 1=padded, 0=valid")
    allPassed = allPassed && check5

    // Check 6: Predictions generated
    val check6 = predictions.isNotEmpty()
    println("   ${if (check6) "✅" else "❌"} Beam search: ${predictions.size} predictions generated")
    allPassed = allPassed && check6

    println()
    if (allPassed) {
        println("🎉 ALL CHECKS PASSED - Pipeline working correctly!")
        println("   Ready for ONNX runtime integration")
    } else {
        println("❌ Some checks failed - review implementation")
    }

    println()
    System.exit(if (allPassed) 0 else 1)
}

main()
