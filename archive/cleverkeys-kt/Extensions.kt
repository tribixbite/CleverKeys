package tribixbite.cleverkeys

import android.content.Context
import android.graphics.PointF
import android.util.Log
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.*

/**
 * Kotlin extension functions for common operations in the keyboard app
 */

// Logging extensions
inline fun Any.logD(message: String) = Log.d(this::class.simpleName, message)
inline fun Any.logE(message: String, throwable: Throwable? = null) = 
    Log.e(this::class.simpleName, message, throwable)
inline fun Any.logW(message: String) = Log.w(this::class.simpleName, message)

// Context extensions
fun Context.toast(message: String, length: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, length).show()
}

fun Context.longToast(message: String) = toast(message, Toast.LENGTH_LONG)

// View extensions
inline fun <T : View> T.applyIf(condition: Boolean, block: T.() -> Unit): T {
    return if (condition) apply(block) else this
}

// PointF extensions
operator fun PointF.minus(other: PointF): PointF = PointF(x - other.x, y - other.y)
operator fun PointF.plus(other: PointF): PointF = PointF(x + other.x, y + other.y)
fun PointF.distanceTo(other: PointF): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}

// List extensions for gesture processing
fun List<PointF>.pathLength(): Float {
    return zipWithNext { p1, p2 -> p1.distanceTo(p2) }.sum()
}

fun List<PointF>.boundingBox(): Pair<PointF, PointF> {
    if (isEmpty()) return PointF(0f, 0f) to PointF(0f, 0f)
    
    val minX = minOf { it.x }
    val maxX = maxOf { it.x }
    val minY = minOf { it.y }
    val maxY = maxOf { it.y }
    
    return PointF(minX, minY) to PointF(maxX, maxY)
}

// Coroutine scope for UI components
val Context.uiScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

// Safe casting extensions
inline fun <reified T> Any?.safeCast(): T? = this as? T

// Math extensions for neural processing
fun Float.normalize(min: Float, max: Float): Float = (this - min) / (max - min)
fun Double.normalize(min: Double, max: Double): Double = (this - min) / (max - min)

// Collection extensions
fun <T> List<T>.safeGet(index: Int): T? = getOrNull(index)
fun <T> MutableList<T>.addIfNotNull(item: T?) = item?.let { add(it) }

// Performance measurement
inline fun <T> measureTimeMillis(block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    val time = System.currentTimeMillis() - start
    return result to time
}

inline fun <T> measureTimeNanos(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    val time = System.nanoTime() - start
    return result to time
}

// Tensor operation helpers
fun FloatArray.softmax(): FloatArray {
    val max = maxOrNull() ?: 0f
    val exp = map { exp(it - max) }.toFloatArray()
    val sum = exp.sum()
    return if (sum > 0) exp.map { it / sum }.toFloatArray() else exp
}

fun FloatArray.topKIndices(k: Int): IntArray {
    return withIndex()
        .sortedByDescending { it.value }
        .take(k)
        .map { it.index }
        .toIntArray()
}