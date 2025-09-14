package juloo.keyboard2

import android.content.Context
import kotlinx.coroutines.*

/**
 * Runtime validation system for CleverKeys
 * Validates models, assets, and system integration at runtime
 */
class RuntimeValidator(private val context: Context) {
    
    companion object {
        private const val TAG = "RuntimeValidator"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Validation result with detailed information
     */
    data class ValidationReport(
        val isValid: Boolean,
        val errors: List<ValidationError>,
        val warnings: List<ValidationWarning>,
        val systemInfo: SystemInfo
    )
    
    /**
     * Validation error
     */
    data class ValidationError(
        val component: String,
        val message: String,
        val severity: Severity,
        val suggestion: String?
    )
    
    /**
     * Validation warning
     */
    data class ValidationWarning(
        val component: String,
        val message: String,
        val impact: String
    )
    
    /**
     * System information
     */
    data class SystemInfo(
        val deviceModel: String,
        val androidVersion: String,
        val availableMemory: Long,
        val hasFoldableDisplay: Boolean,
        val hasNeuralProcessingUnit: Boolean,
        val onnxRuntimeVersion: String
    )
    
    /**
     * Error severity levels
     */
    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW }
    
    /**
     * Perform comprehensive runtime validation
     */
    suspend fun performValidation(): ValidationReport = withContext(Dispatchers.Default) {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        
        logD("Starting comprehensive runtime validation...")
        
        // Validate ONNX models
        validateOnnxModels(errors, warnings)
        
        // Validate dictionary assets
        validateDictionaryAssets(errors, warnings)
        
        // Validate system capabilities
        validateSystemCapabilities(errors, warnings)
        
        // Validate configuration
        validateConfiguration(errors, warnings)
        
        // Validate memory requirements
        validateMemoryRequirements(errors, warnings)
        
        // Collect system information
        val systemInfo = collectSystemInfo()
        
        val isValid = errors.none { it.severity in listOf(Severity.CRITICAL, Severity.HIGH) }
        
        ValidationReport(isValid, errors, warnings, systemInfo)
    }
    
    /**
     * Validate ONNX models
     */
    private suspend fun validateOnnxModels(errors: MutableList<ValidationError>, warnings: MutableList<ValidationWarning>) {
        val requiredModels = listOf(
            "models/swipe_model_character_quant.onnx" to "Encoder model",
            "models/swipe_decoder_character_quant.onnx" to "Decoder model",
            "models/tokenizer.json" to "Tokenizer configuration"
        )
        
        requiredModels.forEach { (path, description) ->
            try {
                context.assets.open(path).use { stream ->
                    val size = stream.available()
                    logD("✅ $description found: $size bytes")
                    
                    if (size == 0) {
                        errors.add(ValidationError(
                            component = "ONNX Models",
                            message = "$description is empty",
                            severity = Severity.CRITICAL,
                            suggestion = "Ensure model files are properly included in assets"
                        ))
                    } else if (size < 1_000_000 && path.endsWith(".onnx")) {
                        warnings.add(ValidationWarning(
                            component = "ONNX Models",
                            message = "$description seems small ($size bytes)",
                            impact = "May indicate corrupted or incomplete model"
                        ))
                    }
                }
            } catch (e: Exception) {
                errors.add(ValidationError(
                    component = "ONNX Models",
                    message = "$description not found: ${e.message}",
                    severity = Severity.CRITICAL,
                    suggestion = "Copy model files to assets/models/ directory"
                ))
            }
        }
        
        // Test ONNX Runtime availability
        try {
            val ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            logD("✅ ONNX Runtime available: ${ortEnv.name}")
        } catch (e: Exception) {
            errors.add(ValidationError(
                component = "ONNX Runtime",
                message = "ONNX Runtime not available: ${e.message}",
                severity = Severity.CRITICAL,
                suggestion = "Ensure ONNX Runtime dependency is correctly included"
            ))
        }
    }
    
    /**
     * Validate dictionary assets
     */
    private suspend fun validateDictionaryAssets(errors: MutableList<ValidationError>, warnings: MutableList<ValidationWarning>) {
        val requiredDictionaries = listOf(
            "dictionaries/en.txt" to "English dictionary",
            "dictionaries/en_enhanced.txt" to "Enhanced English dictionary"
        )
        
        requiredDictionaries.forEach { (path, description) ->
            try {
                context.assets.open(path).bufferedReader().useLines { lines ->
                    val wordCount = lines.count()
                    logD("✅ $description: $wordCount words")
                    
                    if (wordCount < 1000) {
                        warnings.add(ValidationWarning(
                            component = "Dictionaries",
                            message = "$description has only $wordCount words",
                            impact = "May limit prediction quality for longer words"
                        ))
                    }
                }
            } catch (e: Exception) {
                errors.add(ValidationError(
                    component = "Dictionaries",
                    message = "$description not found: ${e.message}",
                    severity = Severity.HIGH,
                    suggestion = "Ensure dictionary files are included in assets"
                ))
            }
        }
    }
    
    /**
     * Validate system capabilities
     */
    private suspend fun validateSystemCapabilities(errors: MutableList<ValidationError>, warnings: MutableList<ValidationWarning>) {
        // Check Android version
        val androidVersion = android.os.Build.VERSION.SDK_INT
        if (androidVersion < 21) {
            errors.add(ValidationError(
                component = "System",
                message = "Android version too old: API $androidVersion",
                severity = Severity.CRITICAL,
                suggestion = "Requires Android 5.0 (API 21) or newer"
            ))
        }
        
        // Check available memory
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        activityManager?.getMemoryInfo(memoryInfo)
        
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        if (availableMemoryMB < 100) {
            warnings.add(ValidationWarning(
                component = "Memory",
                message = "Low available memory: ${availableMemoryMB}MB",
                impact = "Neural prediction may be slow or fail"
            ))
        }
        
        // Check for hardware acceleration
        try {
            val hasGPU = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER) != null
            if (!hasGPU) {
                warnings.add(ValidationWarning(
                    component = "Hardware",
                    message = "GPU acceleration not detected",
                    impact = "ONNX inference may run on CPU only"
                ))
            }
        } catch (e: Exception) {
            logW("Could not detect GPU capabilities: ${e.message}")
        }
    }
    
    /**
     * Validate configuration
     */
    private suspend fun validateConfiguration(errors: MutableList<ValidationError>, warnings: MutableList<ValidationWarning>) {
        try {
            val configManager = ConfigurationManager(context)
            val configValidation = configManager.validateConfiguration()
            
            if (!configValidation.isValid) {
                configValidation.errors.forEach { error ->
                    errors.add(ValidationError(
                        component = "Configuration",
                        message = error,
                        severity = Severity.MEDIUM,
                        suggestion = "Check neural prediction settings"
                    ))
                }
            }
        } catch (e: Exception) {
            errors.add(ValidationError(
                component = "Configuration",
                message = "Configuration validation failed: ${e.message}",
                severity = Severity.HIGH,
                suggestion = "Reset configuration to defaults"
            ))
        }
    }
    
    /**
     * Validate memory requirements
     */
    private suspend fun validateMemoryRequirements(errors: MutableList<ValidationError>, warnings: MutableList<ValidationWarning>) {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024) // MB
        
        logD("Memory status: ${usedMemory}MB used / ${maxMemory}MB max")
        
        if (maxMemory < 512) {
            warnings.add(ValidationWarning(
                component = "Memory",
                message = "Low maximum heap size: ${maxMemory}MB",
                impact = "Large neural models may cause OutOfMemoryError"
            ))
        }
        
        if (usedMemory > maxMemory * 0.8) {
            warnings.add(ValidationWarning(
                component = "Memory",
                message = "High memory usage: ${usedMemory}MB / ${maxMemory}MB",
                impact = "May interfere with neural model loading"
            ))
        }
    }
    
    /**
     * Collect system information
     */
    private suspend fun collectSystemInfo(): SystemInfo = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val availableMemory = runtime.maxMemory()
        
        // Check for foldable display
        val foldTracker = FoldStateTrackerImpl(context)
        val hasFoldable = try {
            foldTracker.isUnfolded() // This will trigger device detection
            true
        } catch (e: Exception) {
            false
        }
        
        // Check for NPU (simplified detection)
        val hasNPU = android.os.Build.MODEL.contains("S25", ignoreCase = true) ||
                    android.os.Build.HARDWARE.contains("qcom", ignoreCase = true)
        
        // Get ONNX Runtime version
        val onnxVersion = try {
            ai.onnxruntime.OrtEnvironment.getEnvironment().name ?: "unknown"
        } catch (e: Exception) {
            "unavailable"
        }
        
        SystemInfo(
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidVersion = "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})",
            availableMemory = availableMemory,
            hasFoldableDisplay = hasFoldable,
            hasNeuralProcessingUnit = hasNPU,
            onnxRuntimeVersion = onnxVersion
        )
    }
    
    /**
     * Generate validation report
     */
    fun generateValidationReport(report: ValidationReport): String {
        return buildString {
            appendLine("🔍 CleverKeys Runtime Validation Report")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
            appendLine()
            
            // Overall status
            appendLine("📊 Overall Status: ${if (report.isValid) "✅ VALID" else "❌ INVALID"}")
            appendLine()
            
            // System information
            appendLine("🖥️ System Information:")
            appendLine("   Device: ${report.systemInfo.deviceModel}")
            appendLine("   Android: ${report.systemInfo.androidVersion}")
            appendLine("   Memory: ${report.systemInfo.availableMemory / (1024 * 1024)}MB")
            appendLine("   Foldable: ${if (report.systemInfo.hasFoldableDisplay) "Yes" else "No"}")
            appendLine("   NPU: ${if (report.systemInfo.hasNeuralProcessingUnit) "Detected" else "Not detected"}")
            appendLine("   ONNX Runtime: ${report.systemInfo.onnxRuntimeVersion}")
            appendLine()
            
            // Errors
            if (report.errors.isNotEmpty()) {
                appendLine("❌ Errors (${report.errors.size}):")
                report.errors.forEach { error ->
                    appendLine("   [${error.severity}] ${error.component}: ${error.message}")
                    error.suggestion?.let { appendLine("      💡 ${it}") }
                }
                appendLine()
            }
            
            // Warnings
            if (report.warnings.isNotEmpty()) {
                appendLine("⚠️ Warnings (${report.warnings.size}):")
                report.warnings.forEach { warning ->
                    appendLine("   ${warning.component}: ${warning.message}")
                    appendLine("      Impact: ${warning.impact}")
                }
                appendLine()
            }
            
            if (report.isValid) {
                appendLine("🚀 CleverKeys is ready for use!")
            } else {
                appendLine("🔧 Please address the errors above before using CleverKeys.")
            }
        }
    }
    
    /**
     * Perform quick health check
     */
    suspend fun quickHealthCheck(): Boolean = withContext(Dispatchers.Default) {
        try {
            // Check essential assets
            context.assets.open("models/swipe_model_character_quant.onnx").close()
            context.assets.open("models/swipe_decoder_character_quant.onnx").close()
            context.assets.open("dictionaries/en.txt").close()
            
            // Check ONNX Runtime
            ai.onnxruntime.OrtEnvironment.getEnvironment()
            
            logD("✅ Quick health check passed")
            true
        } catch (e: Exception) {
            logE("❌ Quick health check failed", e)
            false
        }
    }
    
    /**
     * Test neural prediction functionality
     */
    suspend fun testNeuralPrediction(): Boolean = withContext(Dispatchers.Default) {
        return@withContext try {
            val neuralEngine = NeuralSwipeEngine(context, Config.globalConfig())
            
            if (!neuralEngine.initialize()) {
                logE("Neural engine initialization failed")
                return@withContext false
            }
            
            // Create test input
            val testInput = SwipeInput(
                coordinates = listOf(
                    PointF(100f, 200f),
                    PointF(200f, 200f),
                    PointF(300f, 200f)
                ),
                timestamps = listOf(0L, 100L, 200L),
                touchedKeys = emptyList()
            )
            
            // Test prediction
            val result = neuralEngine.predictAsync(testInput)
            
            val success = result.isNotEmpty()
            logD("Neural prediction test: ${if (success) "✅ PASSED" else "❌ FAILED"}")
            
            neuralEngine.cleanup()
            success
            
        } catch (e: Exception) {
            logE("Neural prediction test failed", e)
            false
        }
    }
    
    /**
     * Cleanup validator
     */
    fun cleanup() {
        scope.cancel()
    }
}