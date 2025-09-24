package tribixbite.keyboard2

import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.graphics.PointF
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Window
import android.view.WindowManager
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*
import kotlin.math.*

/**
 * Modern Kotlin utility functions for CleverKeys.
 *
 * Features:
 * - String manipulation utilities with proper Unicode support
 * - IME dialog display utilities for keyboard integration
 * - File I/O utilities with proper encoding handling
 * - Gesture calculation utilities for swipe recognition
 * - UI measurement utilities
 * - Extension functions for cleaner code
 * - Comprehensive error handling
 */
object Utils {

    /**
     * Turn the first letter of a string uppercase with proper Unicode support.
     *
     * @param input The string to capitalize
     * @return Capitalized string preserving code points
     */
    fun capitalizeString(input: String): String {
        if (input.isEmpty()) return input

        // Make sure not to cut a code point in half
        val firstCodePointLength = input.offsetByCodePoints(0, 1)
        val firstPart = input.substring(0, firstCodePointLength).uppercase(Locale.getDefault())
        val remainingPart = input.substring(firstCodePointLength)

        return firstPart + remainingPart
    }

    /**
     * Show dialog properly configured for IME context.
     * Required for dialogs to appear correctly when keyboard is active.
     *
     * @param dialog The AlertDialog to show
     * @param token The input view's window token
     */
    fun showDialogOnIme(dialog: AlertDialog, token: IBinder) {
        try {
            val window = dialog.window
            if (window != null) {
                val layoutParams = window.attributes
                layoutParams.token = token
                layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                window.attributes = layoutParams
                window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            dialog.show()
        } catch (e: Exception) {
            // Fallback: show dialog normally if IME-specific configuration fails
            android.util.Log.w("Utils", "Failed to configure dialog for IME, showing normally", e)
            try {
                dialog.show()
            } catch (fallbackException: Exception) {
                android.util.Log.e("Utils", "Failed to show dialog even with fallback", fallbackException)
            }
        }
    }

    /**
     * Read all content from an InputStream as UTF-8 text.
     *
     * @param inputStream The InputStream to read from
     * @return The complete content as a String
     * @throws Exception If reading fails
     */
    @Throws(Exception::class)
    fun readAllUtf8(inputStream: InputStream): String {
        val reader = InputStreamReader(inputStream, "UTF-8")
        val output = StringBuilder()
        val bufferLength = 8000
        val buffer = CharArray(bufferLength)

        var bytesRead: Int
        while (reader.read(buffer, 0, bufferLength).also { bytesRead = it } != -1) {
            output.append(buffer, 0, bytesRead)
        }

        return output.toString()
    }

    /**
     * Safely read UTF-8 content from InputStream with automatic resource management.
     *
     * @param inputStream The InputStream to read from
     * @return The content as String, or null if reading fails
     */
    fun safeReadAllUtf8(inputStream: InputStream): String? {
        return try {
            inputStream.use { readAllUtf8(it) }
        } catch (e: Exception) {
            android.util.Log.e("Utils", "Failed to read UTF-8 content", e)
            null
        }
    }

    // === UI Utilities ===

    /**
     * Convert DP to pixels.
     */
    fun dpToPx(dp: Float, metrics: DisplayMetrics): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics)
    }

    /**
     * Convert SP to pixels.
     */
    fun spToPx(sp: Float, metrics: DisplayMetrics): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
    }

    /**
     * Safe resource access with fallback.
     */
    fun Resources.safeGetFloat(id: Int, default: Float): Float {
        return try {
            getDimension(id)
        } catch (e: Exception) {
            android.util.Log.w("Utils", "Failed to get resource float $id, using default", e)
            default
        }
    }

    // === Gesture Utilities ===

    /**
     * Calculate distance between two points.
     */
    fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate angle between two points in radians.
     */
    fun angle(p1: PointF, p2: PointF): Float {
        return atan2(p2.y - p1.y, p2.x - p1.x)
    }

    /**
     * Normalize angle to [-PI, PI] range.
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > PI) normalized -= 2 * PI.toFloat()
        while (normalized < -PI) normalized += 2 * PI.toFloat()
        return normalized
    }

    /**
     * Smooth trajectory using simple moving average.
     */
    fun smoothTrajectory(points: List<PointF>, windowSize: Int = 3): List<PointF> {
        if (points.size <= windowSize) return points

        return points.windowed(windowSize, partialWindows = true) { window ->
            val avgX = window.map { it.x }.average().toFloat()
            val avgY = window.map { it.y }.average().toFloat()
            PointF(avgX, avgY)
        }
    }

    /**
     * Calculate average curvature of a trajectory.
     */
    fun calculateCurvature(points: List<PointF>): Float {
        if (points.size < 3) return 0f

        var totalCurvature = 0f
        var validSegments = 0

        for (i in 1 until points.size - 1) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val p3 = points[i + 1]

            // Skip segments that are too short (noise)
            if (distance(p1, p2) < 1f || distance(p2, p3) < 1f) continue

            val angle1 = angle(p1, p2)
            val angle2 = angle(p2, p3)
            val curvature = abs(normalizeAngle(angle2 - angle1))

            totalCurvature += curvature
            validSegments++
        }

        return if (validSegments > 0) totalCurvature / validSegments else 0f
    }

    /**
     * Detect primary gesture direction.
     */
    fun detectPrimaryDirection(points: List<PointF>, threshold: Float = 20f): Direction {
        if (points.size < 2) return Direction.NONE

        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y

        return when {
            abs(dx) < threshold && abs(dy) < threshold -> Direction.NONE
            abs(dx) > abs(dy) -> if (dx > 0) Direction.RIGHT else Direction.LEFT
            else -> if (dy > 0) Direction.DOWN else Direction.UP
        }
    }

    /**
     * Primary gesture directions.
     */
    enum class Direction {
        NONE, LEFT, RIGHT, UP, DOWN,
        UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
    }

    /**
     * Calculate velocity profile for a gesture.
     */
    fun calculateVelocityProfile(points: List<PointF>, timestamps: List<Long>): List<Float> {
        if (points.size != timestamps.size || points.size < 2) {
            return emptyList()
        }

        return points.zip(timestamps).zipWithNext { (p1, t1), (p2, t2) ->
            val dist = distance(p1, p2)
            val timeDelta = (t2 - t1) / 1000f // Convert to seconds
            if (timeDelta > 0) dist / timeDelta else 0f
        }
    }

    /**
     * Detect if gesture is approximately circular.
     */
    fun isCircularGesture(points: List<PointF>, threshold: Float = 0.8f): Boolean {
        if (points.size < 10) return false

        val center = PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )

        val distances = points.map { distance(it, center) }
        val avgRadius = distances.average().toFloat()
        val radiusVariation = distances.map { abs(it - avgRadius) }.average().toFloat()

        return avgRadius > 0 && (radiusVariation / avgRadius) < (1 - threshold)
    }

    /**
     * Calculate total path length of a trajectory.
     */
    fun calculatePathLength(points: List<PointF>): Float {
        if (points.size < 2) return 0f

        var totalLength = 0f
        for (i in 1 until points.size) {
            totalLength += distance(points[i - 1], points[i])
        }

        return totalLength
    }

    /**
     * Detect if gesture is a loop (starts and ends near the same point).
     */
    fun isLoopGesture(points: List<PointF>, threshold: Float = 30f): Boolean {
        if (points.size < 4) return false

        val start = points.first()
        val end = points.last()
        val closingDistance = distance(start, end)

        return closingDistance <= threshold
    }

    /**
     * Simplify trajectory using Douglas-Peucker algorithm.
     */
    fun simplifyTrajectory(points: List<PointF>, tolerance: Float = 2f): List<PointF> {
        if (points.size <= 2) return points

        return douglasPeucker(points, tolerance)
    }

    private fun douglasPeucker(points: List<PointF>, tolerance: Float): List<PointF> {
        if (points.size <= 2) return points

        val firstPoint = points.first()
        val lastPoint = points.last()

        var maxDistance = 0f
        var maxIndex = 0

        // Find the point with maximum distance from the line
        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], firstPoint, lastPoint)
            if (dist > maxDistance) {
                maxDistance = dist
                maxIndex = i
            }
        }

        return if (maxDistance > tolerance) {
            // Recursively simplify
            val left = douglasPeucker(points.subList(0, maxIndex + 1), tolerance)
            val right = douglasPeucker(points.subList(maxIndex, points.size), tolerance)
            left.dropLast(1) + right
        } else {
            listOf(firstPoint, lastPoint)
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y

        if (dx == 0f && dy == 0f) {
            return distance(point, lineStart)
        }

        val t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (dx * dx + dy * dy)
        val projection = when {
            t < 0 -> lineStart
            t > 1 -> lineEnd
            else -> PointF(lineStart.x + t * dx, lineStart.y + t * dy)
        }

        return distance(point, projection)
    }

    // === String Extensions ===

    /**
     * Extension function for String capitalization.
     */
    fun String.capitalizeFirst(): String = capitalizeString(this)

    /**
     * Check if string contains only printable characters.
     */
    fun String.isPrintable(): Boolean {
        return this.all { char ->
            !Character.isISOControl(char) || Character.isWhitespace(char)
        }
    }

    /**
     * Truncate string to maximum length with ellipsis.
     */
    fun String.truncate(maxLength: Int, ellipsis: String = "..."): String {
        return if (this.length <= maxLength) {
            this
        } else {
            this.substring(0, (maxLength - ellipsis.length).coerceAtLeast(0)) + ellipsis
        }
    }
}