package juloo.keyboard2

import android.content.Context
import android.content.res.Resources
import android.graphics.PointF
import android.util.DisplayMetrics
import android.util.TypedValue
import kotlin.math.*

/**
 * Utility functions for keyboard operations
 * Kotlin implementation with extension functions and null safety
 */
object Utils {
    
    /**
     * Convert DP to pixels
     */
    fun dpToPx(dp: Float, metrics: DisplayMetrics): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics)
    }
    
    /**
     * Convert SP to pixels
     */
    fun spToPx(sp: Float, metrics: DisplayMetrics): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
    }
    
    /**
     * Safe resource access
     */
    fun Resources.safeGetFloat(id: Int, default: Float): Float {
        return try {
            getDimension(id)
        } catch (e: Exception) {
            default
        }
    }
    
    /**
     * Calculate distance between points
     */
    fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }
    
    /**
     * Calculate angle between points
     */
    fun angle(p1: PointF, p2: PointF): Float {
        return atan2(p2.y - p1.y, p2.x - p1.x)
    }
    
    /**
     * Normalize angle to [-PI, PI]
     */
    fun normalizeAngle(angle: Float): Float {
        var normalized = angle
        while (normalized > PI) normalized -= 2 * PI.toFloat()
        while (normalized < -PI) normalized += 2 * PI.toFloat()
        return normalized
    }
    
    /**
     * Smooth trajectory using simple moving average
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
     * Calculate trajectory curvature
     */
    fun calculateCurvature(points: List<PointF>): Float {
        if (points.size < 3) return 0f
        
        var totalCurvature = 0f
        
        for (i in 1 until points.size - 1) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val p3 = points[i + 1]
            
            val angle1 = angle(p1, p2)
            val angle2 = angle(p2, p3)
            val curvature = abs(normalizeAngle(angle2 - angle1))
            
            totalCurvature += curvature
        }
        
        return totalCurvature / (points.size - 2)
    }
    
    /**
     * Detect gesture direction
     */
    fun detectPrimaryDirection(points: List<PointF>): Direction {
        if (points.size < 2) return Direction.NONE
        
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        
        return when {
            abs(dx) < 20 && abs(dy) < 20 -> Direction.NONE
            abs(dx) > abs(dy) -> if (dx > 0) Direction.RIGHT else Direction.LEFT
            else -> if (dy > 0) Direction.DOWN else Direction.UP
        }
    }
    
    /**
     * Primary gesture directions
     */
    enum class Direction {
        NONE, LEFT, RIGHT, UP, DOWN, 
        UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
    }
    
    /**
     * Calculate gesture velocity profile
     */
    fun calculateVelocityProfile(points: List<PointF>, timestamps: List<Long>): List<Float> {
        if (points.size != timestamps.size || points.size < 2) return emptyList()
        
        return points.zip(timestamps).zipWithNext { (p1, t1), (p2, t2) ->
            val distance = distance(p1, p2)
            val timeDelta = (t2 - t1) / 1000f // Convert to seconds
            if (timeDelta > 0) distance / timeDelta else 0f
        }
    }
    
    /**
     * Detect if gesture is circular
     */
    fun isCircularGesture(points: List<PointF>, threshold: Float = 0.8f): Boolean {
        if (points.size < 10) return false
        
        val center = PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )
        
        val avgRadius = points.map { distance(it, center) }.average()
        val radiusVariation = points.map { abs(distance(it, center) - avgRadius) }.average()
        
        return radiusVariation / avgRadius < (1 - threshold)
    }
}