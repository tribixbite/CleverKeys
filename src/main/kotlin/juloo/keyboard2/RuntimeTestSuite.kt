package juloo.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * Runtime test suite for validating CleverKeys functionality
 * Tests actual neural prediction, gesture recognition, and system integration
 */
class RuntimeTestSuite(private val context: Context) {
    
    companion object {
        private const val TAG = "RuntimeTestSuite"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Runtime test result
     */
    data class RuntimeTestResult(
        val testName: String,
        val success: Boolean,
        val executionTimeMs: Long,
        val details: String,
        val errorMessage: String? = null
    )
    
    /**
     * Complete runtime test suite
     */
    suspend fun runCompleteRuntimeTests(): List<RuntimeTestResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<RuntimeTestResult>()
        
        logD("🧪 Starting CleverKeys runtime test suite...")
        
        // Test 1: System initialization
        results.add(testSystemInitialization())
        
        // Test 2: Neural engine functionality
        results.add(testNeuralEngine())
        
        // Test 3: Gesture recognition
        results.add(testGestureRecognition())
        
        // Test 4: Configuration management
        results.add(testConfigurationSystem())
        
        // Test 5: Memory management
        results.add(testMemoryManagement())
        
        // Test 6: Error handling
        results.add(testErrorHandling())
        
        val passedTests = results.count { it.success }
        logD("🏁 Runtime tests completed: $passedTests/${results.size} passed")
        
        results
    }
    
    /**
     * Test system initialization
     */
    private suspend fun testSystemInitialization(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Test production initializer
            val initializer = ProductionInitializer(context)
            val result = initializer.initialize()
            
            val executionTime = System.currentTimeMillis() - startTime
            
            RuntimeTestResult(
                testName = "System Initialization",
                success = result.success,
                executionTimeMs = executionTime,
                details = "Errors: ${result.errors.size}, Warnings: ${result.warnings.size}",
                errorMessage = if (result.errors.isNotEmpty()) result.errors.first() else null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "System Initialization",
                success = false,
                executionTimeMs = 0,
                details = "Exception during initialization",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test neural engine functionality
     */
    private suspend fun testNeuralEngine(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Initialize neural engine
            val neuralEngine = NeuralSwipeEngine(context, Config.globalConfig())
            val initSuccess = neuralEngine.initialize()
            
            if (!initSuccess) {
                return RuntimeTestResult(
                    testName = "Neural Engine",
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    details = "Neural engine initialization failed",
                    errorMessage = "Could not load ONNX models"
                )
            }
            
            // Test prediction
            val testInput = createTestSwipeInput()
            val predictions = neuralEngine.predictAsync(testInput)
            
            val executionTime = System.currentTimeMillis() - startTime
            neuralEngine.cleanup()
            
            RuntimeTestResult(
                testName = "Neural Engine",
                success = !predictions.isEmpty,
                executionTimeMs = executionTime,
                details = "Predictions: ${predictions.size}, Top: ${predictions.topPrediction ?: "none"}",
                errorMessage = if (predictions.isEmpty) "No predictions generated" else null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "Neural Engine",
                success = false,
                executionTimeMs = 0,
                details = "Exception during neural prediction",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test gesture recognition
     */
    private suspend fun testGestureRecognition(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            // ONNX-only: Direct neural processing test
            
            // Test multiple gesture types
            val testGestures = listOf(
                createHorizontalGesture() to "horizontal",
                createVerticalGesture() to "vertical",
                createCircularGesture() to "circular"
            )
            
            var successCount = 0
            val details = mutableListOf<String>()
            
            testGestures.forEach { (points, type) ->
                try {
                    val result = recognizer.recognizeGesture(points, points.indices.map { it * 100L })
                    if (result.gesture.confidence > 0.3f) {
                        successCount++
                        details.add("$type: ${result.gesture.type} (${result.gesture.confidence})")
                    }
                } catch (e: Exception) {
                    details.add("$type: Failed - ${e.message}")
                }
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            recognizer.cleanup()
            
            RuntimeTestResult(
                testName = "Gesture Recognition",
                success = successCount >= 2, // At least 2/3 should work
                executionTimeMs = executionTime,
                details = "Recognized: $successCount/${testGestures.size} - ${details.joinToString(", ")}",
                errorMessage = if (successCount == 0) "No gestures recognized" else null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "Gesture Recognition",
                success = false,
                executionTimeMs = 0,
                details = "Exception during gesture recognition",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test configuration system
     */
    private suspend fun testConfigurationSystem(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            val configManager = ConfigurationManager(context)
            val initSuccess = configManager.initialize()
            
            if (!initSuccess) {
                return RuntimeTestResult(
                    testName = "Configuration System",
                    success = false,
                    executionTimeMs = System.currentTimeMillis() - startTime,
                    details = "Configuration manager initialization failed",
                    errorMessage = "Could not initialize configuration"
                )
            }
            
            // Test configuration validation
            val validation = configManager.validateConfiguration()
            
            val executionTime = System.currentTimeMillis() - startTime
            configManager.cleanup()
            
            RuntimeTestResult(
                testName = "Configuration System",
                success = validation.isValid,
                executionTimeMs = executionTime,
                details = "Valid: ${validation.isValid}, Errors: ${validation.errors.size}",
                errorMessage = if (!validation.isValid) validation.errors.firstOrNull() else null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "Configuration System",
                success = false,
                executionTimeMs = 0,
                details = "Exception during configuration test",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test memory management
     */
    private suspend fun testMemoryManagement(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            val ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val memoryManager = TensorMemoryManager(ortEnv)
            
            // Test tensor operations
            repeat(10) {
                val data = FloatArray(100) { it.toFloat() }
                val tensor = memoryManager.createManagedTensor(data, longArrayOf(1, 100))
                memoryManager.releaseTensor(tensor)
            }
            
            val stats = memoryManager.getMemoryStats()
            val executionTime = System.currentTimeMillis() - startTime
            
            memoryManager.cleanup()
            
            RuntimeTestResult(
                testName = "Memory Management",
                success = stats.activeTensors == 0, // All tensors should be cleaned up
                executionTimeMs = executionTime,
                details = "Active tensors: ${stats.activeTensors}, Created: ${stats.totalTensorsCreated}",
                errorMessage = if (stats.activeTensors > 0) "Memory leak detected" else null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "Memory Management",
                success = false,
                executionTimeMs = 0,
                details = "Exception during memory test",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Test error handling
     */
    private suspend fun testErrorHandling(): RuntimeTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Test retry mechanism
            var attempts = 0
            val result = ErrorHandling.retryOperation(maxAttempts = 3) { attempt ->
                attempts++
                if (attempt < 3) throw RuntimeException("Test failure")
                "success"
            }
            
            // Test safe execution
            val safeResult = ErrorHandling.safeExecute("test_operation") {
                throw RuntimeException("Test exception")
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            RuntimeTestResult(
                testName = "Error Handling",
                success = result == "success" && safeResult.isFailure && attempts == 3,
                executionTimeMs = executionTime,
                details = "Retry attempts: $attempts, Safe execution: ${safeResult.isFailure}",
                errorMessage = null
            )
        } catch (e: Exception) {
            RuntimeTestResult(
                testName = "Error Handling",
                success = false,
                executionTimeMs = 0,
                details = "Exception during error handling test",
                errorMessage = e.message
            )
        }
    }
    
    /**
     * Generate test report
     */
    fun generateTestReport(results: List<RuntimeTestResult>): String {
        val passedTests = results.count { it.success }
        val totalTime = results.sumOf { it.executionTimeMs }
        
        return buildString {
            appendLine("🧪 CleverKeys Runtime Test Report")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            appendLine()
            appendLine("📊 Overall Results:")
            appendLine("   Passed: $passedTests/${results.size} (${(passedTests * 100 / results.size)}%)")
            appendLine("   Total Time: ${totalTime}ms")
            appendLine()
            
            results.forEach { result ->
                val status = if (result.success) "✅ PASS" else "❌ FAIL"
                appendLine("$status ${result.testName} (${result.executionTimeMs}ms)")
                appendLine("   ${result.details}")
                result.errorMessage?.let { appendLine("   Error: $it") }
                appendLine()
            }
            
            if (passedTests == results.size) {
                appendLine("🎉 All runtime tests passed! CleverKeys is fully functional.")
            } else {
                appendLine("🔧 ${results.size - passedTests} tests failed. Review errors above.")
            }
        }
    }
    
    // Helper methods for creating test data
    
    private fun createTestSwipeInput(): SwipeInput {
        val points = listOf(
            PointF(100f, 200f),
            PointF(200f, 200f),
            PointF(300f, 200f),
            PointF(400f, 200f)
        )
        val timestamps = points.indices.map { it * 100L }
        return SwipeInput(points, timestamps, emptyList())
    }
    
    private fun createHorizontalGesture(): List<PointF> {
        return (0..5).map { PointF(it * 100f, 200f) }
    }
    
    private fun createVerticalGesture(): List<PointF> {
        return (0..5).map { PointF(200f, it * 50f) }
    }
    
    private fun createCircularGesture(): List<PointF> {
        val center = PointF(200f, 200f)
        val radius = 100f
        return (0..12).map { i ->
            val angle = (i / 12.0) * 2 * kotlin.math.PI
            PointF(
                center.x + radius * kotlin.math.cos(angle).toFloat(),
                center.y + radius * kotlin.math.sin(angle).toFloat()
            )
        }
    }
    
    /**
     * Cleanup test suite
     */
    fun cleanup() {
        scope.cancel()
    }
}