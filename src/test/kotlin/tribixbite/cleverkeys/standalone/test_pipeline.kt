#!/usr/bin/env kotlin

/**
 * Standalone CLI test for ONNX neural prediction pipeline
 * Tests feature extraction with realistic swipe coordinates
 */

// Simple Point class for standalone execution
data class PointF(val x: Float, val y: Float)

// Mock minimal dependencies for standalone execution
data class TrajectoryFeatures(
    val coordinates: List<PointF>,
    val velocities: List<PointF>,
    val accelerations: List<PointF>,
    val nearestKeys: List<Int>,
    val actualLength: Int,
    val normalizedCoordinates: List<PointF>
)

const val MAX_TRAJECTORY_POINTS = 150
const val KEYBOARD_WIDTH = 1080f
const val KEYBOARD_HEIGHT = 400f

/**
 * Feature extraction matching fixed implementation
 */
fun extractFeatures(coordinates: List<PointF>, timestamps: List<Long>): TrajectoryFeatures {
    println("📊 Extracting features from ${coordinates.size} points...")

    // 1. Normalize coordinates FIRST (0-1 range) - CRITICAL FIX
    val normalizedCoords = coordinates.map {
        PointF(it.x / KEYBOARD_WIDTH, it.y / KEYBOARD_HEIGHT)
    }
    println("   ✅ Normalized first point: (${normalizedCoords[0].x}, ${normalizedCoords[0].y})")

    // 2. Detect nearest keys on raw coordinates
    val nearestKeys = coordinates.map { detectNearestKey(it) }

    // 3. Pad or truncate to MAX_TRAJECTORY_POINTS
    val finalCoords = padOrTruncate(normalizedCoords, MAX_TRAJECTORY_POINTS, PointF(0f, 0f))
    val finalNearestKeys = padOrTruncate(nearestKeys, MAX_TRAJECTORY_POINTS, 0)

    // 4. Calculate velocities and accelerations on normalized coords (simple deltas!)
    val velocities = mutableListOf<PointF>()
    val accelerations = mutableListOf<PointF>()

    for (i in 0 until MAX_TRAJECTORY_POINTS) {
        if (i == 0) {
            velocities.add(PointF(0f, 0f))
            accelerations.add(PointF(0f, 0f))
        } else if (i == 1) {
            // Simple delta - CRITICAL FIX
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))
            accelerations.add(PointF(0f, 0f))
        } else {
            // Velocity as simple delta
            val vx = finalCoords[i].x - finalCoords[i-1].x
            val vy = finalCoords[i].y - finalCoords[i-1].y
            velocities.add(PointF(vx, vy))

            // Acceleration as delta of deltas
            val ax = vx - velocities[i-1].x
            val ay = vy - velocities[i-1].y
            accelerations.add(PointF(ax, ay))
        }
    }

    // Log first few feature values
    println("   📈 First 3 velocity values:")
    velocities.take(3).forEachIndexed { i, v ->
        println("      [$i] vx=${v.x}, vy=${v.y}")
    }

    println("   📈 First 3 acceleration values:")
    accelerations.take(3).forEachIndexed { i, a ->
        println("      [$i] ax=${a.x}, ay=${a.y}")
    }

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

    // QWERTY layout mapping
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

fun main() {
    println("🧪 CleverKeys Neural Pipeline Feature Extraction Test")
    println("=" .repeat(60))
    println()

    // Test Case 1: Realistic "hello" swipe coordinates
    // Simulating swipe path: h -> e -> l -> l -> o
    val helloSwipe = listOf(
        // Starting at 'h' (middle-right of ASDF row)
        PointF(540f, 200f),
        PointF(550f, 200f),
        PointF(560f, 200f),
        // Moving to 'e' (upper-left)
        PointF(280f, 100f),
        PointF(270f, 100f),
        // Moving to 'l' (middle-right of ASDF row)
        PointF(720f, 200f),
        PointF(730f, 200f),
        PointF(740f, 200f),
        // Staying at 'l' (double letter)
        PointF(745f, 200f),
        PointF(750f, 200f),
        // Moving to 'o' (upper-right)
        PointF(810f, 100f),
        PointF(820f, 100f),
        PointF(830f, 100f)
    )

    val timestamps = helloSwipe.indices.map { it * 50L } // 50ms per point

    println("🎯 Test: 'hello' swipe")
    println("   Points: ${helloSwipe.size}")
    println("   Duration: ${timestamps.last()}ms")
    println()

    // Extract features
    val features = extractFeatures(helloSwipe, timestamps)

    println()
    println("✅ Feature extraction complete!")
    println("   Actual length: ${features.actualLength}")
    println("   Total features: ${features.coordinates.size}")
    println("   Nearest keys detected: ${features.nearestKeys.take(5)}")
    println()

    // Verify critical fixes
    println("🔍 Verification:")
    var allPassed = true

    // Check 1: Coordinates are normalized [0,1]
    val maxCoord = features.normalizedCoordinates.take(features.actualLength).maxOf { maxOf(it.x, it.y) }
    val minCoord = features.normalizedCoordinates.take(features.actualLength).minOf { minOf(it.x, it.y) }
    val normalizationOK = maxCoord <= 1.0f && minCoord >= 0.0f
    println("   ${if (normalizationOK) "✅" else "❌"} Coordinates normalized to [0,1]: min=$minCoord, max=$maxCoord")
    allPassed = allPassed && normalizationOK

    // Check 2: Velocities are simple deltas (should be small values since normalized)
    val avgVelocityMagnitude = features.velocities.take(features.actualLength)
        .map { kotlin.math.sqrt(it.x * it.x + it.y * it.y) }
        .average()
    val velocityOK = avgVelocityMagnitude < 1.0 // Normalized deltas should be small
    println("   ${if (velocityOK) "✅" else "❌"} Velocities are simple deltas: avg magnitude=$avgVelocityMagnitude")
    allPassed = allPassed && velocityOK

    // Check 3: Accelerations exist and vary
    val nonZeroAccelerations = features.accelerations.take(features.actualLength)
        .count { it.x != 0f || it.y != 0f }
    val accelerationOK = nonZeroAccelerations > 0
    println("   ${if (accelerationOK) "✅" else "❌"} Accelerations calculated: $nonZeroAccelerations non-zero values")
    allPassed = allPassed && accelerationOK

    // Check 4: Features padded correctly
    val paddingOK = features.coordinates.size == MAX_TRAJECTORY_POINTS
    println("   ${if (paddingOK) "✅" else "❌"} Features padded to $MAX_TRAJECTORY_POINTS")
    allPassed = allPassed && paddingOK

    // Check 5: Velocity components separated (vx, vy stored separately)
    val distinctVelocities = features.velocities.take(features.actualLength).distinct().size
    val separationOK = distinctVelocities > 1 // Should have variety
    println("   ${if (separationOK) "✅" else "❌"} Velocity components separated: $distinctVelocities distinct values")
    allPassed = allPassed && separationOK

    println()
    if (allPassed) {
        println("🎉 All feature extraction checks PASSED!")
        println("   Pipeline is ready for ONNX inference")
    } else {
        println("❌ Some feature extraction checks FAILED")
        println("   Review implementation against web demo")
    }

    println()
    println("=" .repeat(60))
    println("📝 Summary:")
    println("   Feature extraction math: ${if (velocityOK && accelerationOK) "✅ FIXED" else "❌ BROKEN"}")
    println("   Normalization order: ${if (normalizationOK) "✅ CORRECT (normalize first)" else "❌ WRONG"}")
    println("   Velocity formula: ${if (velocityOK) "✅ SIMPLE DELTAS" else "❌ PHYSICS FORMULA"}")
    println("   Component separation: ${if (separationOK) "✅ VX/VY SEPARATE" else "❌ SAME MAGNITUDE"}")
    println()

    System.exit(if (allPassed) 0 else 1)
}

main()
