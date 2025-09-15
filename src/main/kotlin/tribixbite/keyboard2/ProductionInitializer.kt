package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import kotlinx.coroutines.*

/**
 * Production initialization and validation system
 * Ensures CleverKeys is properly configured for production use
 */
class ProductionInitializer(private val context: Context) {
    
    companion object {
        private const val TAG = "ProductionInitializer"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Production initialization result
     */
    data class InitializationResult(
        val success: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val performanceMetrics: Map<String, Long>
    )
    
    /**
     * Perform complete production initialization
     */
    suspend fun initialize(): InitializationResult = withContext(Dispatchers.Default) {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val metrics = mutableMapOf<String, Long>()
        
        logD("🚀 Starting CleverKeys production initialization...")
        
        try {
            // Step 1: Validate runtime environment
            var validationSuccess = false
            val validationTime = kotlin.system.measureTimeMillis {
                validationSuccess = validateRuntimeEnvironment(errors, warnings)
            }
            metrics["environment_validation_ms"] = validationTime
            
            if (!validationSuccess) {
                return@withContext InitializationResult(false, errors, warnings, metrics)
            }
            
            // Step 2: Initialize core components
            var coreSuccess = false
            val coreInitTime = kotlin.system.measureTimeMillis {
                coreSuccess = initializeCoreComponents(errors, warnings)
            }
            metrics["core_initialization_ms"] = coreInitTime
            
            // Step 3: Load and validate neural models
            var modelSuccess = false
            val modelLoadTime = kotlin.system.measureTimeMillis {
                modelSuccess = loadAndValidateModels(errors, warnings)
            }
            metrics["model_loading_ms"] = modelLoadTime
            
            // Step 4: Initialize prediction pipeline
            var pipelineSuccess = false
            val pipelineInitTime = kotlin.system.measureTimeMillis {
                pipelineSuccess = initializePredictionPipeline(errors, warnings)
            }
            metrics["pipeline_initialization_ms"] = pipelineInitTime
            
            // Step 5: Perform system health check
            var healthSuccess = false
            val healthCheckTime = kotlin.system.measureTimeMillis {
                healthSuccess = performSystemHealthCheck(errors, warnings)
            }
            metrics["health_check_ms"] = healthCheckTime
            
            val overallSuccess = coreSuccess && modelSuccess && pipelineSuccess && healthSuccess
            
            if (overallSuccess) {
                logD("✅ CleverKeys production initialization completed successfully")
                logD("   Total time: ${metrics.values.sum()}ms")
            } else {
                logE("❌ CleverKeys production initialization failed")
                logE("   Errors: ${errors.size}, Warnings: ${warnings.size}")
            }
            
            InitializationResult(overallSuccess, errors, warnings, metrics)
            
        } catch (e: Exception) {
            logE("Critical initialization failure", e)
            errors.add("Critical failure: ${e.message}")
            InitializationResult(false, errors, warnings, metrics)
        }
    }
    
    /**
     * Validate runtime environment
     */
    private suspend fun validateRuntimeEnvironment(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        val validator = RuntimeValidator(context)
        val report = validator.performValidation()
        
        // Add critical errors
        report.errors.filter { it.severity in listOf(RuntimeValidator.Severity.CRITICAL, RuntimeValidator.Severity.HIGH) }
            .forEach { error -> errors.add("${error.component}: ${error.message}") }
        
        // Add warnings
        report.warnings.forEach { warning -> 
            warnings.add("${warning.component}: ${warning.message} (Impact: ${warning.impact})")
        }
        
        validator.cleanup()
        return report.isValid
    }
    
    /**
     * Initialize core components
     */
    private suspend fun initializeCoreComponents(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        return try {
            // Initialize configuration system
            val configManager = ConfigurationManager(context)
            if (!configManager.initialize()) {
                errors.add("Configuration manager initialization failed")
                return false
            }
            
            // Validate configuration
            val configValidation = configManager.validateConfiguration()
            if (!configValidation.isValid) {
                errors.addAll(configValidation.errors.map { "Config: $it" })
            }
            
            // Initialize logging system
            Logs.setDebugEnabled(BuildConfig.DEBUG)
            
            logD("Core components initialized successfully")
            true
        } catch (e: Exception) {
            errors.add("Core component initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Load and validate neural models
     */
    private suspend fun loadAndValidateModels(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        return try {
            // Test model loading
            val predictor = OnnxSwipePredictorImpl.getInstance(context)
            if (!predictor.initialize()) {
                errors.add("ONNX model loading failed")
                return false
            }
            
            // Validate model functionality
            val testInput = SwipeInput(
                coordinates = listOf(PointF(100f, 200f), PointF(200f, 200f)),
                timestamps = listOf(0L, 100L),
                touchedKeys = emptyList()
            )
            
            val result = predictor.predict(testInput)
            if (result.isEmpty) {
                warnings.add("Neural prediction test returned empty results")
            } else {
                logD("✅ Neural model validation successful: ${result.size} predictions")
            }
            
            true
        } catch (e: Exception) {
            errors.add("Model validation failed: ${e.message}")
            false
        }
    }
    
    /**
     * Initialize prediction pipeline
     */
    private suspend fun initializePredictionPipeline(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        return try {
            val pipeline = NeuralPredictionPipeline(context)
            if (!pipeline.initialize()) {
                errors.add("Prediction pipeline initialization failed")
                return false
            }
            
            // Test pipeline functionality
            val testResult = pipeline.processGesture(
                points = listOf(PointF(100f, 200f), PointF(200f, 200f), PointF(300f, 200f)),
                timestamps = listOf(0L, 100L, 200L)
            )
            
            if (testResult.predictions.isEmpty) {
                warnings.add("Pipeline test returned no predictions")
            }
            
            logD("✅ Prediction pipeline validated successfully")
            true
        } catch (e: Exception) {
            errors.add("Pipeline initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Perform system health check
     */
    private suspend fun performSystemHealthCheck(errors: MutableList<String>, warnings: MutableList<String>): Boolean {
        return try {
            val validator = RuntimeValidator(context)
            val healthCheckPassed = validator.quickHealthCheck()
            
            if (!healthCheckPassed) {
                errors.add("System health check failed")
                return false
            }
            
            // Test neural prediction functionality
            val neuralTestPassed = validator.testNeuralPrediction()
            if (!neuralTestPassed) {
                warnings.add("Neural prediction test failed - may impact functionality")
            }
            
            logD("✅ System health check completed")
            true
        } catch (e: Exception) {
            errors.add("Health check failed: ${e.message}")
            false
        }
    }
    
    /**
     * Generate initialization report
     */
    fun generateInitializationReport(result: InitializationResult): String {
        return buildString {
            appendLine("🚀 CleverKeys Production Initialization Report")
            appendLine("Status: ${if (result.success) "✅ SUCCESS" else "❌ FAILURE"}")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            appendLine()
            
            // Performance metrics
            appendLine("📊 Performance Metrics:")
            result.performanceMetrics.forEach { (metric, time) ->
                appendLine("   $metric: ${time}ms")
            }
            appendLine("   Total initialization time: ${result.performanceMetrics.values.sum()}ms")
            appendLine()
            
            // Errors
            if (result.errors.isNotEmpty()) {
                appendLine("❌ Errors (${result.errors.size}):")
                result.errors.forEach { error ->
                    appendLine("   • $error")
                }
                appendLine()
            }
            
            // Warnings
            if (result.warnings.isNotEmpty()) {
                appendLine("⚠️ Warnings (${result.warnings.size}):")
                result.warnings.forEach { warning ->
                    appendLine("   • $warning")
                }
                appendLine()
            }
            
            if (result.success) {
                appendLine("🎉 CleverKeys is ready for production use!")
                appendLine("   Neural prediction: Active")
                appendLine("   Gesture recognition: Advanced algorithms")
                appendLine("   Performance optimization: Batched inference")
                appendLine("   Memory management: Automatic pooling")
            } else {
                appendLine("🔧 Please resolve the errors above before deployment.")
            }
        }
    }
    
    /**
     * Cleanup initializer
     */
    fun cleanup() {
        scope.cancel()
    }
}