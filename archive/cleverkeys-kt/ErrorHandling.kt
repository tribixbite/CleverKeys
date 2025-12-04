package tribixbite.cleverkeys

import android.content.Context
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Comprehensive error handling and validation system
 * Kotlin implementation with structured exception management
 */
object ErrorHandling {
    
    /**
     * Custom exceptions for CleverKeys
     */
    sealed class CleverKeysException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        
        class NeuralEngineException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
        class GestureRecognitionException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
        class LayoutException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
        class ConfigurationException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
        class ResourceException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
        class ModelLoadingException(message: String, cause: Throwable? = null) : CleverKeysException(message, cause)
    }
    
    /**
     * Global exception handler for coroutines
     */
    class CleverKeysExceptionHandler(
        private val context: Context,
        private val onError: (CleverKeysException) -> Unit = {}
    ) : CoroutineExceptionHandler {
        
        override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler
        
        override fun handleException(context: CoroutineContext, exception: Throwable) {
            val cleverKeysException = when (exception) {
                is CleverKeysException -> exception
                is ai.onnxruntime.OrtException -> CleverKeysException.NeuralEngineException(
                    "ONNX Runtime error: ${exception.message}", exception
                )
                is OutOfMemoryError -> CleverKeysException.NeuralEngineException(
                    "Out of memory during neural processing", exception
                )
                else -> CleverKeysException.NeuralEngineException(
                    "Unexpected error: ${exception.message}", exception
                )
            }
            
            logE("Global exception handler caught: ${cleverKeysException.message}", cleverKeysException)
            onError(cleverKeysException)
        }
    }
    
    /**
     * Validation functions
     */
    object Validation {
        
        /**
         * Validate swipe input
         */
        fun validateSwipeInput(input: SwipeInput): ValidationResult {
            val errors = mutableListOf<String>()
            
            if (input.coordinates.isEmpty()) {
                errors.add("No coordinates provided")
            }
            
            if (input.timestamps.isEmpty()) {
                errors.add("No timestamps provided")
            }
            
            if (input.coordinates.size != input.timestamps.size) {
                errors.add("Coordinate and timestamp count mismatch")
            }
            
            if (input.pathLength < 10f) {
                errors.add("Path length too short: ${input.pathLength}")
            }
            
            if (input.duration < 0.05f) {
                errors.add("Duration too short: ${input.duration}")
            }
            
            if (input.duration > 10f) {
                errors.add("Duration too long: ${input.duration}")
            }
            
            // Check coordinate bounds
            input.coordinates.forEachIndexed { index, point ->
                if (point.x < 0 || point.y < 0) {
                    errors.add("Negative coordinates at index $index: ($point.x, $point.y)")
                }
                if (point.x > 5000 || point.y > 5000) {
                    errors.add("Coordinates too large at index $index: ($point.x, $point.y)")
                }
            }
            
            return ValidationResult(errors.isEmpty(), errors)
        }
        
        /**
         * Validate neural configuration
         */
        fun validateNeuralConfig(config: NeuralConfig): ValidationResult {
            val errors = mutableListOf<String>()
            
            if (config.beamWidth !in config.beamWidthRange) {
                errors.add("Beam width out of range: ${config.beamWidth}")
            }
            
            if (config.maxLength !in config.maxLengthRange) {
                errors.add("Max length out of range: ${config.maxLength}")
            }
            
            if (config.confidenceThreshold !in config.confidenceRange) {
                errors.add("Confidence threshold out of range: ${config.confidenceThreshold}")
            }
            
            return ValidationResult(errors.isEmpty(), errors)
        }
        
        /**
         * Validate keyboard layout
         */
        fun validateKeyboardLayout(layout: KeyboardData): ValidationResult {
            val errors = mutableListOf<String>()
            
            if (layout.rows.isEmpty()) {
                errors.add("No keyboard rows defined")
            }

            layout.rows.forEachIndexed { rowIndex, row ->
                if (row.keys.isEmpty()) {
                    errors.add("Empty row at index $rowIndex")
                }

                row.keys.forEachIndexed { keyIndex, key ->
                    if (key.keys.isEmpty() || key.keys.all { it == null }) {
                        errors.add("No key values defined at row $rowIndex, key $keyIndex")
                    }
                }
            }
            
            return ValidationResult(errors.isEmpty(), errors)
        }
    }
    
    /**
     * Validation result
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>
    ) {
        fun throwIfInvalid() {
            if (!isValid) {
                throw CleverKeysException.ConfigurationException("Validation failed: ${errors.joinToString(", ")}")
            }
        }
        
        fun getErrorSummary(): String {
            return if (isValid) "Valid" else "Invalid: ${errors.joinToString("; ")}"
        }
    }
    
    /**
     * Safe execution wrapper with error handling
     */
    suspend inline fun <T> safeExecute(
        operation: String,
        context: CoroutineContext = Dispatchers.Default,
        crossinline block: suspend () -> T
    ): Result<T> {
        return try {
            withContext(context) {
                Result.success(block())
            }
        } catch (e: CancellationException) {
            logD("Operation cancelled: $operation")
            throw e // Re-throw cancellation
        } catch (e: CleverKeysException) {
            logE("CleverKeys error in $operation: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            logE("Unexpected error in $operation: ${e.message}", e)
            Result.failure(CleverKeysException.NeuralEngineException("Operation failed: $operation", e))
        }
    }
    
    /**
     * Retry mechanism for flaky operations
     */
    suspend inline fun <T> retryOperation(
        maxAttempts: Int = 3,
        delayMs: Long = 1000,
        crossinline operation: suspend (attempt: Int) -> T
    ): T {
        var lastException: Exception? = null
        
        repeat(maxAttempts) { attempt ->
            try {
                return operation(attempt + 1)
            } catch (e: CancellationException) {
                throw e // Don't retry cancellation
            } catch (e: Exception) {
                lastException = e
                logW("Operation failed (attempt ${attempt + 1}/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts - 1) {
                    delay(delayMs)
                }
            }
        }
        
        throw lastException ?: Exception("All retry attempts failed")
    }
    
    /**
     * Resource validation
     */
    fun validateResources(context: Context): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Check essential assets
        val requiredAssets = listOf(
            "dictionaries/en.txt",
            "dictionaries/en_enhanced.txt", 
            "models/swipe_model_character_quant.onnx",
            "models/swipe_decoder_character_quant.onnx"
        )
        
        requiredAssets.forEach { assetPath ->
            try {
                context.assets.open(assetPath).close()
            } catch (e: Exception) {
                errors.add("Missing asset: $assetPath")
            }
        }
        
        // Check essential string resources
        val requiredStrings = listOf("app_name")
        requiredStrings.forEach { stringName ->
            val resId = context.resources.getIdentifier(stringName, "string", context.packageName)
            if (resId == 0) {
                errors.add("Missing string resource: $stringName")
            }
        }
        
        return ValidationResult(errors.isEmpty(), errors)
    }
}