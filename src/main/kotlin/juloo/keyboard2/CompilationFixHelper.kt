package juloo.keyboard2

import android.graphics.PointF

/**
 * Helper to fix remaining compilation issues
 * Provides missing implementations and compatibility shims
 */
object CompilationFixHelper {
    
    /**
     * Create safe PointF instances for testing
     */
    fun createTestPoint(x: Float, y: Float): PointF = PointF(x, y)
    
    /**
     * Safe measure time with proper return types
     */
    suspend inline fun <T> safeMeasureTime(operation: suspend () -> T): Pair<T, Long> {
        val startTime = System.currentTimeMillis()
        val result = operation()
        val duration = System.currentTimeMillis() - startTime
        return result to duration
    }
    
    /**
     * Fix ONNX Environment name access
     */
    fun getOnnxEnvironmentInfo(): String {
        return try {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            "ONNX Runtime Available"
        } catch (e: Exception) {
            "ONNX Runtime Error: ${e.message}"
        }
    }
    
    /**
     * Safe collection operations
     */
    fun <T> Collection<T>.safeCount(): Int = this.size
    
    fun <T> Collection<T>.safeIsNotEmpty(): Boolean = this.isNotEmpty()
    
    /**
     * Fix if-else expression issues
     */
    fun <T> safeIfElse(condition: Boolean, trueValue: T, falseValue: T): T {
        return if (condition) trueValue else falseValue
    }
}

/**
 * Extension functions to fix compilation issues
 */

// Safe PointF creation
fun Float.toPointF(y: Float): PointF = PointF(this, y)

// Safe collection operations
fun <T> List<T>.safeGet(index: Int): T? = getOrNull(index)

// Safe arithmetic operations
fun Number.safeToLong(): Long = toLong()
fun Number.safeToInt(): Int = toInt()
fun Number.safeToFloat(): Float = toFloat()

// Safe string operations
fun String?.orEmpty(): String = this ?: ""

// Collection utility
fun <T> emptyListOf(): List<T> = emptyList()