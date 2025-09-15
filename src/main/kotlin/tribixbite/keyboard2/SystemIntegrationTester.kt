package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * System integration tester for validating complete CleverKeys functionality
 * Tests end-to-end integration without requiring actual InputMethodService
 */
class SystemIntegrationTester(private val context: Context) {
    
    companion object {
        private const val TAG = "SystemIntegrationTester"
    }
    
    /**
     * Integration test result
     */
    data class IntegrationTestResult(
        val testName: String,
        val success: Boolean,
        val durationMs: Long,
        val details: String,
        val metrics: Map<String, Any> = emptyMap()
    )
    
    /**
     * Complete system test suite
     */
    data class SystemTestSuite(
        val results: List<IntegrationTestResult>,
        val overallSuccess: Boolean,
        val totalDurationMs: Long,
        val successRate: Float
    )
    
    /**
     * Run complete system integration tests
     */
    suspend fun runCompleteSystemTest(): SystemTestSuite = withContext(Dispatchers.Default) {
        val results = mutableListOf<IntegrationTestResult>()
        val startTime = System.currentTimeMillis()
        
        logD("🧪 Starting complete system integration tests...")
        
        // Test 1: Production initialization
        results.add(testProductionInitialization())
        
        // Test 2: Neural prediction accuracy
        results.add(testNeuralPredictionAccuracy())
        
        // Test 3: Gesture recognition performance
        results.add(testGestureRecognitionPerformance())
        
        // Test 4: Memory management
        results.add(testMemoryManagement())
        
        // Test 5: Configuration management
        results.add(testConfigurationManagement())
        
        // Test 6: Error handling resilience
        results.add(testErrorHandlingResilience())
        
        // Test 7: Performance optimization
        results.add(testPerformanceOptimization())
        
        val totalDuration = System.currentTimeMillis() - startTime
        val successCount = results.count { it.success }
        val successRate = successCount.toFloat() / results.size
        val overallSuccess = successRate >= 0.8f // 80% pass rate required
        
        logD("🏁 System tests completed: $successCount/${results.size} passed (${(successRate * 100).toInt()}%)")
        
        SystemTestSuite(results, overallSuccess, totalDuration, successRate)
    }
    
    /**
     * Test production initialization
     */
    private suspend fun testProductionInitialization(): IntegrationTestResult {
        return try {
            val (result, duration) = measureTimeMillis {
                val initializer = ProductionInitializer(context)
                initializer.initialize()
            }
            
            IntegrationTestResult(
                testName = "Production Initialization",
                success = result.success,
                durationMs = duration,
                details = if (result.success) {
                    "Initialization completed successfully"
                } else {
                    "Errors: ${result.errors.joinToString(", ")}"
                },
                metrics = result.performanceMetrics.mapValues { it.value as Any }
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Production Initialization",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test neural prediction accuracy
     */
    private suspend fun testNeuralPredictionAccuracy(): IntegrationTestResult {
        return try {
            val (results, duration) = measureTimeMillis {
                testMultipleGestureWords()
            }
            
            val accuracy = calculatePredictionAccuracy(results)
            
            IntegrationTestResult(
                testName = "Neural Prediction Accuracy",
                success = accuracy >= 0.7f, // 70% accuracy required
                durationMs = duration,
                details = "Accuracy: ${(accuracy * 100).toInt()}% (${results.size} tests)",
                metrics = mapOf(
                    "accuracy" to accuracy,
                    "total_tests" to results.size,
                    "correct_predictions" to results.count { it.second }
                )
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Neural Prediction Accuracy",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test gesture recognition performance
     */
    private suspend fun testGestureRecognitionPerformance(): IntegrationTestResult {
        return try {
            // ONNX-only: Test gesture processing without intermediate recognition
            val testGestures = createTestGestures()
            
            val (recognitionResults, duration) = measureTimeMillis {
                testGestures.map { (points, timestamps) ->
                    // ONNX-only: Direct gesture processing validation
                    val swipeInput = SwipeInput(points, timestamps, emptyList())
                    swipeInput.swipeConfidence > 0.5f // Simple gesture validation
                }
            }

            val avgLatency = duration.toFloat() / testGestures.size
            val accurateRecognitions = recognitionResults.count { it }
            
            IntegrationTestResult(
                testName = "Gesture Recognition Performance",
                success = avgLatency < 50f && accurateRecognitions >= testGestures.size * 0.8,
                durationMs = duration,
                details = "Avg latency: ${avgLatency.toInt()}ms, Accuracy: ${accurateRecognitions}/${testGestures.size}",
                metrics = mapOf(
                    "average_latency_ms" to avgLatency,
                    "recognition_accuracy" to (accurateRecognitions.toFloat() / testGestures.size)
                )
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Gesture Recognition Performance",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test memory management
     */
    private suspend fun testMemoryManagement(): IntegrationTestResult {
        return try {
            val ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val memoryManager = TensorMemoryManager(ortEnv)
            
            val (_, duration) = measureTimeMillis {
                // Simulate tensor operations
                repeat(50) {
                    val data = FloatArray(1000) { it.toFloat() }
                    val tensor = memoryManager.createManagedTensor(data, longArrayOf(1, 1000))
                    memoryManager.releaseTensor(tensor)
                }
            }
            
            val stats = memoryManager.getMemoryStats()
            memoryManager.cleanup()
            
            IntegrationTestResult(
                testName = "Memory Management",
                success = stats.activeTensors == 0, // All tensors should be cleaned up
                durationMs = duration,
                details = "Created/Released 50 tensors, Active: ${stats.activeTensors}",
                metrics = mapOf(
                    "tensors_created" to stats.totalTensorsCreated,
                    "active_tensors" to stats.activeTensors,
                    "memory_bytes" to stats.totalActiveMemoryBytes
                )
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Memory Management",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test configuration management
     */
    private suspend fun testConfigurationManagement(): IntegrationTestResult {
        return try {
            val (success, duration) = measureTimeMillis {
                val configManager = ConfigurationManager(context)
                configManager.initialize() &&
                configManager.validateConfiguration().isValid
            }
            
            IntegrationTestResult(
                testName = "Configuration Management",
                success = success,
                durationMs = duration,
                details = if (success) "Configuration valid" else "Configuration validation failed"
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Configuration Management", 
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test error handling resilience
     */
    private suspend fun testErrorHandlingResilience(): IntegrationTestResult {
        return try {
            val (results, duration) = measureTimeMillis {
                listOf(
                    testErrorRecovery(),
                    testGracefulDegradation(),
                    testExceptionHandling()
                )
            }
            
            val passedTests = results.count { it }
            
            IntegrationTestResult(
                testName = "Error Handling Resilience",
                success = passedTests == results.size,
                durationMs = duration,
                details = "Passed: $passedTests/${results.size} resilience tests"
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Error Handling Resilience",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Test performance optimization
     */
    private suspend fun testPerformanceOptimization(): IntegrationTestResult {
        return try {
            val profiler = PerformanceProfiler(context)
            
            val (_, duration) = measureTimeMillis {
                // Test batched vs sequential operations
                repeat(10) { i ->
                    profiler.measureOperation("test_operation_$i") {
                        delay(10) // Simulate work
                    }
                }
            }
            
            val stats = profiler.getStats("test_operation_0")
            profiler.cleanup()
            
            IntegrationTestResult(
                testName = "Performance Optimization",
                success = stats != null && stats.averageDurationMs < 100,
                durationMs = duration,
                details = "Performance monitoring functional: ${stats != null}"
            )
        } catch (e: Exception) {
            IntegrationTestResult(
                testName = "Performance Optimization",
                success = false,
                durationMs = 0,
                details = "Exception: ${e.message}"
            )
        }
    }
    
    // Helper methods
    
    private suspend fun testMultipleGestureWords(): List<Pair<String, Boolean>> {
        val testCases = mapOf(
            "hello" to createHelloGesture(),
            "world" to createWorldGesture(),
            "the" to createTheGesture(),
            "and" to createAndGesture()
        )
        
        val pipeline = NeuralPredictionPipeline(context)
        if (!pipeline.initialize()) return emptyList()
        
        return testCases.map { (expectedWord, gesture) ->
            val result = pipeline.processGesture(gesture.coordinates, gesture.timestamps)
            val predicted = result.predictions.topPrediction
            expectedWord to (predicted == expectedWord)
        }
    }
    
    private fun calculatePredictionAccuracy(results: List<Pair<String, Boolean>>): Float {
        if (results.isEmpty()) return 0f
        return results.count { it.second }.toFloat() / results.size
    }
    
    private fun createTestGestures(): List<Pair<List<PointF>, List<Long>>> {
        return listOf(
            createHorizontalGesture() to listOf(0L, 100L, 200L),
            createVerticalGesture() to listOf(0L, 150L, 300L),
            createDiagonalGesture() to listOf(0L, 120L, 240L)
        )
    }
    
    private fun createHelloGesture(): SwipeInput {
        val points = listOf(
            PointF(600f, 300f), // h
            PointF(300f, 200f), // e
            PointF(900f, 300f), // l
            PointF(900f, 300f), // l
            PointF(1000f, 200f) // o
        )
        return SwipeInput(points, points.indices.map { it * 100L }, emptyList())
    }
    
    private fun createWorldGesture(): SwipeInput {
        val points = listOf(
            PointF(200f, 200f), // w
            PointF(1000f, 200f), // o
            PointF(400f, 200f), // r
            PointF(900f, 300f), // l
            PointF(400f, 300f)  // d
        )
        return SwipeInput(points, points.indices.map { it * 120L }, emptyList())
    }
    
    private fun createTheGesture(): SwipeInput {
        val points = listOf(
            PointF(500f, 200f), // t
            PointF(600f, 300f), // h
            PointF(300f, 200f)  // e
        )
        return SwipeInput(points, points.indices.map { it * 80L }, emptyList())
    }
    
    private fun createAndGesture(): SwipeInput {
        val points = listOf(
            PointF(100f, 300f), // a
            PointF(800f, 300f), // n
            PointF(400f, 300f)  // d
        )
        return SwipeInput(points, points.indices.map { it * 90L }, emptyList())
    }
    
    private fun createHorizontalGesture(): List<PointF> {
        return listOf(PointF(100f, 200f), PointF(200f, 200f), PointF(300f, 200f))
    }
    
    private fun createVerticalGesture(): List<PointF> {
        return listOf(PointF(200f, 100f), PointF(200f, 200f), PointF(200f, 300f))
    }
    
    private fun createDiagonalGesture(): List<PointF> {
        return listOf(PointF(100f, 100f), PointF(200f, 200f), PointF(300f, 300f))
    }
    
    private suspend fun testErrorRecovery(): Boolean {
        return try {
            ErrorHandling.retryOperation(maxAttempts = 3) { attempt ->
                if (attempt < 3) throw RuntimeException("Test failure")
                "success"
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun testGracefulDegradation(): Boolean {
        return try {
            // Test that system continues to work even when neural prediction fails
            val pipeline = NeuralPredictionPipeline(context)
            // Simulate neural failure by not initializing
            val result = pipeline.processGesture(
                points = createHorizontalGesture(),
                timestamps = listOf(0L, 100L, 200L)
            )
            // Should get fallback predictions even if neural fails
            !result.predictions.isEmpty
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun testExceptionHandling(): Boolean {
        return try {
            val result = ErrorHandling.safeExecute("test_exception") {
                throw RuntimeException("Test exception")
            }
            result.isFailure // Should handle exception gracefully
        } catch (e: Exception) {
            false
        }
    }
}