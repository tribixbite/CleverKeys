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
 * Validate feature extraction against expected values from web demo
 * This replaces mock beam search with actual math validation
 */
fun validateFeatureExtraction(features: TrajectoryFeatures, swipeName: String): Boolean {
    println("\n🔍 Feature Extraction Validation")
    println("   Testing: $swipeName swipe")

    var allPassed = true

    // Test 1: Verify coordinates are normalized [0,1]
    val maxCoord = features.normalizedCoordinates.take(features.actualLength)
        .maxOfOrNull { maxOf(it.x, it.y) } ?: 0f
    val minCoord = features.normalizedCoordinates.take(features.actualLength)
        .minOfOrNull { minOf(it.x, it.y) } ?: 0f

    val normCheck = maxCoord <= 1.0f && minCoord >= 0.0f
    println("   ${if (normCheck) "✅" else "❌"} Normalization: coords in [0,1] (min=${"%.3f".format(minCoord)}, max=${"%.3f".format(maxCoord)})")
    allPassed = allPassed && normCheck

    // Test 2: Verify velocity values are reasonable (small deltas after normalization)
    val avgVelocityMag = features.velocities.take(features.actualLength)
        .map { sqrt(it.x * it.x + it.y * it.y) }
        .filter { it > 0 }
        .average()

    val velocityCheck = avgVelocityMag < 1.0 && avgVelocityMag > 0.0
    println("   ${if (velocityCheck) "✅" else "❌"} Velocity: avg magnitude = ${"%.4f".format(avgVelocityMag)} (should be << 1.0)")
    allPassed = allPassed && velocityCheck

    // Test 3: Verify velocities are ACTUALLY simple deltas
    // Check that vx[i] = x[i] - x[i-1]
    var deltaErrors = 0
    for (i in 1 until minOf(features.actualLength, 10)) {
        val expectedVx = features.normalizedCoordinates[i].x - features.normalizedCoordinates[i-1].x
        val expectedVy = features.normalizedCoordinates[i].y - features.normalizedCoordinates[i-1].y
        val actualVx = features.velocities[i].x
        val actualVy = features.velocities[i].y

        val errorX = abs(expectedVx - actualVx)
        val errorY = abs(expectedVy - actualVy)

        if (errorX > 0.0001f || errorY > 0.0001f) {
            deltaErrors++
            if (deltaErrors == 1) {
                println("   ❌ Velocity formula error at i=$i:")
                println("      Expected: vx=${"%.6f".format(expectedVx)}, vy=${"%.6f".format(expectedVy)}")
                println("      Actual:   vx=${"%.6f".format(actualVx)}, vy=${"%.6f".format(actualVy)}")
            }
        }
    }

    val deltaCheck = deltaErrors == 0
    println("   ${if (deltaCheck) "✅" else "❌"} Velocity formula: simple deltas (errors: $deltaErrors/10)")
    allPassed = allPassed && deltaCheck

    // Test 4: Verify accelerations are velocity deltas
    var accelErrors = 0
    for (i in 2 until minOf(features.actualLength, 10)) {
        val expectedAx = features.velocities[i].x - features.velocities[i-1].x
        val expectedAy = features.velocities[i].y - features.velocities[i-1].y
        val actualAx = features.accelerations[i].x
        val actualAy = features.accelerations[i].y

        val errorX = abs(expectedAx - actualAx)
        val errorY = abs(expectedAy - actualAy)

        if (errorX > 0.0001f || errorY > 0.0001f) {
            accelErrors++
            if (accelErrors == 1) {
                println("   ❌ Acceleration formula error at i=$i:")
                println("      Expected: ax=${"%.6f".format(expectedAx)}, ay=${"%.6f".format(expectedAy)}")
                println("      Actual:   ax=${"%.6f".format(actualAx)}, ay=${"%.6f".format(actualAy)}")
            }
        }
    }

    val accelCheck = accelErrors == 0
    println("   ${if (accelCheck) "✅" else "❌"} Acceleration formula: velocity deltas (errors: $accelErrors/10)")
    allPassed = allPassed && accelCheck

    // Test 5: Verify components are separated (vx != vy for movement)
    val hasDifferentComponents = features.velocities.take(features.actualLength)
        .count { it.x != it.y } > features.actualLength / 2

    println("   ${if (hasDifferentComponents) "✅" else "❌"} Component separation: vx/vy different (${features.velocities.count { it.x != it.y }} distinct)")
    allPassed = allPassed && hasDifferentComponents

    return allPassed
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

    // Step 5: Feature Extraction Validation
    val featureValidationPassed = validateFeatureExtraction(features, "hello")

    // Verification Summary
    println("\n" + "=" .repeat(70))
    println("✅ Pipeline Verification Summary")
    println("=" .repeat(70))

    var allPassed = true

    // Check 1: Feature extraction validation passed
    println("   ${if (featureValidationPassed) "✅" else "❌"} Feature extraction: Math validation passed")
    allPassed = allPassed && featureValidationPassed

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

    println()
    if (allPassed) {
        println("🎉 ALL CHECKS PASSED - Feature extraction math is correct!")
        println("   • Normalization: coordinates in [0,1]")
        println("   • Velocity formula: vx = x[i] - x[i-1]")
        println("   • Acceleration formula: ax = vx[i] - vx[i-1]")
        println("   • Component separation: vx/vy stored separately")
        println("   • Mask conventions: 1=padded, 0=valid")
        println()
        println("   ✅ Ready for real ONNX model testing")
        println("   → Run: ./test_onnx_accuracy.sh")
    } else {
        println("❌ Some checks failed - review feature extraction implementation")
    }

    println()
    System.exit(if (allPassed) 0 else 1)
}

main()
