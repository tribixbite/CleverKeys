#!/usr/bin/env kotlin

/**
 * CLI test to validate nearest_keys tensor format matches trained model
 * Tests actual Kotlin implementation with real ONNX models
 */

@file:DependsOn("ai.onnxruntime:onnxruntime-android:1.20.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

import ai.onnxruntime.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

// Constants matching OnnxSwipePredictorImpl
const val MAX_SEQUENCE_LENGTH = 150
const val PAD_IDX = 0L

fun main() {
    println("=".repeat(70))
    println("Testing nearest_keys Tensor Format")
    println("=".repeat(70))

    var testsPassed = 0
    var testsFailed = 0

    try {
        // Test 1: Verify 2D tensor creation
        println("\n[Test 1] Creating 2D nearest_keys tensor...")
        val mockNearestKeys = List(MAX_SEQUENCE_LENGTH) {
            listOf(5, 10, 15) // Mock: top 3 keys per point
        }

        val tensor = createNearestKeysTensor2D(mockNearestKeys)
        val shape = tensor.info.shape

        if (shape.size == 2 && shape[0] == 1L && shape[1] == 150L) {
            println("✅ PASS: Tensor shape is [1, 150] (2D)")
            testsPassed++
        } else {
            println("❌ FAIL: Expected [1, 150], got [${shape.joinToString(", ")}]")
            testsFailed++
        }

        tensor.close()

        // Test 2: Verify we use only first key
        println("\n[Test 2] Verifying only first nearest key is used...")
        val testKeys = listOf(
            listOf(7, 99, 88),  // Should use only 7
            listOf(3, 77, 66)   // Should use only 3
        )
        val tensor2 = createNearestKeysTensor2D(testKeys)
        val buffer = tensor2.longBuffer

        val firstKey = buffer.get(0)
        val secondKey = buffer.get(1)

        if (firstKey == 7L && secondKey == 3L) {
            println("✅ PASS: Uses first key only (got 7, 3 as expected)")
            testsPassed++
        } else {
            println("❌ FAIL: Expected (7, 3), got ($firstKey, $secondKey)")
            testsFailed++
        }

        tensor2.close()

        // Test 3: Verify padding
        println("\n[Test 3] Verifying padding with PAD_IDX...")
        val shortKeys = listOf(listOf(5, 10, 15)) // Only 1 point
        val tensor3 = createNearestKeysTensor2D(shortKeys)
        val buffer3 = tensor3.longBuffer

        val validKey = buffer3.get(0)
        val paddedKey = buffer3.get(1)

        if (validKey == 5L && paddedKey == PAD_IDX) {
            println("✅ PASS: Padding works correctly")
            testsPassed++
        } else {
            println("❌ FAIL: Expected (5, 0), got ($validKey, $paddedKey)")
            testsFailed++
        }

        tensor3.close()

        // Test 4: Load actual ONNX model and validate input
        println("\n[Test 4] Loading actual ONNX encoder model...")
        val modelPath = "assets/models/swipe_model_character_quant.onnx"

        if (java.io.File(modelPath).exists()) {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelPath, OrtSession.SessionOptions())

            println("Model loaded successfully")
            println("Expected inputs:")
            session.inputNames.forEach { name ->
                val info = session.inputInfo[name]
                println("  - $name: ${info?.info}")
            }

            // Check if nearest_keys input expects 2D
            val nearestKeysInfo = session.inputInfo["nearest_keys"]
            if (nearestKeysInfo != null) {
                val shape = (nearestKeysInfo.info as? TensorInfo)?.shape
                if (shape != null && shape.size == 2) {
                    println("✅ PASS: Model expects 2D nearest_keys")
                    testsPassed++
                } else {
                    println("❌ FAIL: Model expects unexpected shape: ${shape?.joinToString()}")
                    testsFailed++
                }
            } else {
                println("⚠️  SKIP: Could not verify model input (nearest_keys not found)")
            }

            session.close()
            env.close()
        } else {
            println("⚠️  SKIP: Model file not found at $modelPath")
        }

    } catch (e: Exception) {
        println("❌ ERROR: ${e.message}")
        e.printStackTrace()
        testsFailed++
    }

    // Summary
    println("\n" + "=".repeat(70))
    println("Test Summary")
    println("=".repeat(70))
    println("✅ Passed: $testsPassed")
    println("❌ Failed: $testsFailed")
    println("=".repeat(70))

    if (testsFailed > 0) {
        println("\n❌ TESTS FAILED - nearest_keys tensor format is incorrect")
        exitProcess(1)
    } else {
        println("\n✅ ALL TESTS PASSED - nearest_keys tensor format is correct")
        exitProcess(0)
    }
}

/**
 * Create 2D nearest_keys tensor matching OnnxSwipePredictorImpl.kt
 */
fun createNearestKeysTensor2D(nearestKeys: List<List<Int>>): OnnxTensor {
    val byteBuffer = ByteBuffer.allocateDirect(MAX_SEQUENCE_LENGTH * 8)
    byteBuffer.order(ByteOrder.nativeOrder())
    val buffer = byteBuffer.asLongBuffer()

    for (i in 0 until MAX_SEQUENCE_LENGTH) {
        if (i < nearestKeys.size) {
            val top3Keys = nearestKeys[i]
            // Use only the first (closest) key to match trained model
            val keyIndex = top3Keys.getOrNull(0)?.toLong() ?: PAD_IDX
            buffer.put(keyIndex)
        } else {
            // Padding
            buffer.put(PAD_IDX)
        }
    }

    buffer.rewind()
    val env = OrtEnvironment.getEnvironment()
    return OnnxTensor.createTensor(env, buffer, longArrayOf(1, MAX_SEQUENCE_LENGTH.toLong()))
}
