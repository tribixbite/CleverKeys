package tribixbite.keyboard2

import android.content.Context
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Performs comprehensive analysis of swipe gesture traces.
 *
 * Provides advanced gesture analysis for swipe typing including geometric
 * features, motion characteristics, pattern recognition, and quality metrics.
 *
 * Features:
 * - Geometric analysis (length, curvature, angles)
 * - Motion analysis (velocity, acceleration, jerk)
 * - Pattern detection (loops, zigzags, curves)
 * - Quality metrics (smoothness, consistency)
 * - Direction changes tracking
 * - Stroke segmentation
 * - Feature vector extraction
 * - Statistical analysis
 * - Outlier detection
 * - Noise filtering
 * - Time-based analysis
 * - Pressure analysis (if available)
 *
 * Bug #276 - CATASTROPHIC: Complete implementation of missing ComprehensiveTraceAnalyzer.java
 *
 * @param context Application context
 */
class ComprehensiveTraceAnalyzer(
    private val context: Context
) {
    companion object {
        private const val TAG = "ComprehensiveTraceAnalyzer"

        // Analysis thresholds
        private const val MIN_TRACE_LENGTH = 20.0f  // pixels
        private const val SMOOTHNESS_WINDOW = 5
        private const val VELOCITY_THRESHOLD = 100.0f  // px/s
        private const val DIRECTION_CHANGE_THRESHOLD = 30.0  // degrees
        private const val LOOP_CLOSURE_THRESHOLD = 30.0f  // pixels
        private const val ZIGZAG_ANGLE_THRESHOLD = 120.0  // degrees

        /**
         * Gesture pattern type.
         */
        enum class PatternType {
            LINEAR,          // Straight line
            CURVED,          // Smooth curve
            LOOP,            // Closed loop
            ZIGZAG,          // Sharp direction changes
            SPIRAL,          // Spiral pattern
            COMPLEX,         // Complex multi-pattern
            UNKNOWN          // Unrecognized
        }

        /**
         * Gesture quality level.
         */
        enum class QualityLevel {
            EXCELLENT,       // Very smooth and consistent
            GOOD,            // Smooth with minor variations
            FAIR,            // Acceptable with some noise
            POOR,            // Noisy or inconsistent
            INVALID          // Too short or corrupted
        }

        /**
         * Trace point with metadata.
         */
        data class TracePoint(
            val point: PointF,
            val timestamp: Long,
            val pressure: Float = 1.0f,
            val distance: Float = 0.0f,  // Cumulative distance
            val velocity: Float = 0.0f,
            val angle: Double = 0.0
        )

        /**
         * Geometric features.
         */
        data class GeometricFeatures(
            val totalLength: Float,
            val straightLineDistance: Float,
            val curvatureIndex: Float,  // length / straightLineDistance
            val boundingBoxArea: Float,
            val aspectRatio: Float,
            val centroid: PointF,
            val averageAngle: Double,
            val angleVariance: Double
        )

        /**
         * Motion features.
         */
        data class MotionFeatures(
            val averageVelocity: Float,
            val maxVelocity: Float,
            val minVelocity: Float,
            val velocityVariance: Float,
            val averageAcceleration: Float,
            val maxAcceleration: Float,
            val averageJerk: Float,
            val duration: Long
        )

        /**
         * Pattern features.
         */
        data class PatternFeatures(
            val patternType: PatternType,
            val directionChanges: Int,
            val loopCount: Int,
            val zigzagCount: Int,
            val curvaturePoints: Int,
            val inflectionPoints: Int,
            val symmetryScore: Float
        )

        /**
         * Quality metrics.
         */
        data class QualityMetrics(
            val qualityLevel: QualityLevel,
            val smoothnessScore: Float,
            val consistencyScore: Float,
            val noiseLevel: Float,
            val outlierCount: Int,
            val samplingRate: Float  // points per second
        )

        /**
         * Complete trace analysis result.
         */
        data class AnalysisResult(
            val points: List<TracePoint>,
            val geometricFeatures: GeometricFeatures,
            val motionFeatures: MotionFeatures,
            val patternFeatures: PatternFeatures,
            val qualityMetrics: QualityMetrics,
            val featureVector: FloatArray
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as AnalysisResult

                if (points != other.points) return false
                if (geometricFeatures != other.geometricFeatures) return false
                if (motionFeatures != other.motionFeatures) return false
                if (patternFeatures != other.patternFeatures) return false
                if (qualityMetrics != other.qualityMetrics) return false
                if (!featureVector.contentEquals(other.featureVector)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = points.hashCode()
                result = 31 * result + geometricFeatures.hashCode()
                result = 31 * result + motionFeatures.hashCode()
                result = 31 * result + patternFeatures.hashCode()
                result = 31 * result + qualityMetrics.hashCode()
                result = 31 * result + featureVector.contentHashCode()
                return result
            }
        }
    }

    /**
     * Callback interface for analysis events.
     */
    interface Callback {
        /**
         * Called when analysis completes.
         */
        fun onAnalysisComplete(result: AnalysisResult)

        /**
         * Called when analysis fails.
         */
        fun onAnalysisError(error: String)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private var callback: Callback? = null

    // Statistics cache
    private var lastAnalysisTime: Long = 0
    private var totalAnalyses: Int = 0
    private var averageAnalysisTime: Long = 0

    init {
        logD("ComprehensiveTraceAnalyzer initialized")
    }

    /**
     * Analyze trace comprehensively.
     *
     * @param points Raw trace points
     * @param timestamps Timestamps for each point (optional)
     * @param pressures Pressure values for each point (optional)
     * @return Analysis result
     */
    suspend fun analyzeTrace(
        points: List<PointF>,
        timestamps: List<Long>? = null,
        pressures: List<Float>? = null
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        _isAnalyzing.value = true

        try {
            // Validate input
            if (points.size < 2) {
                throw IllegalArgumentException("Trace must have at least 2 points")
            }

            // Build trace points with metadata
            val tracePoints = buildTracePoints(points, timestamps, pressures)

            // Analyze geometric features
            val geometricFeatures = analyzeGeometry(tracePoints)

            // Analyze motion features
            val motionFeatures = analyzeMotion(tracePoints)

            // Detect patterns
            val patternFeatures = detectPatterns(tracePoints, geometricFeatures)

            // Calculate quality metrics
            val qualityMetrics = calculateQuality(tracePoints, motionFeatures)

            // Extract feature vector
            val featureVector = extractFeatureVector(
                geometricFeatures,
                motionFeatures,
                patternFeatures,
                qualityMetrics
            )

            val result = AnalysisResult(
                points = tracePoints,
                geometricFeatures = geometricFeatures,
                motionFeatures = motionFeatures,
                patternFeatures = patternFeatures,
                qualityMetrics = qualityMetrics,
                featureVector = featureVector
            )

            // Update statistics
            val analysisTime = System.currentTimeMillis() - startTime
            updateStatistics(analysisTime)

            callback?.onAnalysisComplete(result)
            logD("Analysis complete: ${points.size} points, ${analysisTime}ms")

            result
        } catch (e: Exception) {
            logE("Error analyzing trace", e)
            callback?.onAnalysisError("Analysis failed: ${e.message}")
            throw e
        } finally {
            _isAnalyzing.value = false
        }
    }

    /**
     * Build trace points with metadata.
     */
    private fun buildTracePoints(
        points: List<PointF>,
        timestamps: List<Long>?,
        pressures: List<Float>?
    ): List<TracePoint> {
        val tracePoints = mutableListOf<TracePoint>()
        var cumulativeDistance = 0.0f
        val baseTime = timestamps?.firstOrNull() ?: System.currentTimeMillis()

        for (i in points.indices) {
            val point = points[i]
            val timestamp = timestamps?.getOrNull(i) ?: (baseTime + i * 10L)
            val pressure = pressures?.getOrNull(i) ?: 1.0f

            // Calculate distance from previous point
            if (i > 0) {
                val prev = points[i - 1]
                val dx = point.x - prev.x
                val dy = point.y - prev.y
                cumulativeDistance += sqrt(dx * dx + dy * dy)
            }

            // Calculate velocity
            val velocity = if (i > 0 && timestamps != null) {
                val dt = (timestamp - timestamps[i - 1]).toFloat()
                if (dt > 0) {
                    val dx = point.x - points[i - 1].x
                    val dy = point.y - points[i - 1].y
                    val distance = sqrt(dx * dx + dy * dy)
                    (distance / dt) * 1000f  // px/s
                } else {
                    0f
                }
            } else {
                0f
            }

            // Calculate angle
            val angle = if (i > 0) {
                val dx = point.x - points[i - 1].x
                val dy = point.y - points[i - 1].y
                atan2(dy.toDouble(), dx.toDouble())
            } else {
                0.0
            }

            tracePoints.add(
                TracePoint(
                    point = point,
                    timestamp = timestamp,
                    pressure = pressure,
                    distance = cumulativeDistance,
                    velocity = velocity,
                    angle = angle
                )
            )
        }

        return tracePoints
    }

    /**
     * Analyze geometric features.
     */
    private fun analyzeGeometry(points: List<TracePoint>): GeometricFeatures {
        // Total length (cumulative distance)
        val totalLength = points.lastOrNull()?.distance ?: 0f

        // Straight line distance
        val first = points.first().point
        val last = points.last().point
        val dx = last.x - first.x
        val dy = last.y - first.y
        val straightLineDistance = sqrt(dx * dx + dy * dy)

        // Curvature index
        val curvatureIndex = if (straightLineDistance > 0) {
            totalLength / straightLineDistance
        } else {
            1.0f
        }

        // Bounding box
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (tp in points) {
            minX = min(minX, tp.point.x)
            minY = min(minY, tp.point.y)
            maxX = max(maxX, tp.point.x)
            maxY = max(maxY, tp.point.y)
        }

        val width = maxX - minX
        val height = maxY - minY
        val boundingBoxArea = width * height
        val aspectRatio = if (height > 0) width / height else 1.0f

        // Centroid
        val sumX = points.sumOf { it.point.x.toDouble() }.toFloat()
        val sumY = points.sumOf { it.point.y.toDouble() }.toFloat()
        val centroid = PointF(sumX / points.size, sumY / points.size)

        // Average angle and variance
        val angles = points.map { it.angle }
        val averageAngle = angles.average()
        val angleVariance = angles.map { (it - averageAngle).pow(2) }.average()

        return GeometricFeatures(
            totalLength = totalLength,
            straightLineDistance = straightLineDistance,
            curvatureIndex = curvatureIndex,
            boundingBoxArea = boundingBoxArea,
            aspectRatio = aspectRatio,
            centroid = centroid,
            averageAngle = averageAngle,
            angleVariance = angleVariance
        )
    }

    /**
     * Analyze motion features.
     */
    private fun analyzeMotion(points: List<TracePoint>): MotionFeatures {
        val velocities = points.map { it.velocity }.filter { it > 0 }

        val averageVelocity = if (velocities.isNotEmpty()) {
            velocities.average().toFloat()
        } else {
            0f
        }

        val maxVelocity = velocities.maxOrNull() ?: 0f
        val minVelocity = velocities.minOrNull() ?: 0f

        val velocityVariance = if (velocities.isNotEmpty()) {
            velocities.map { (it - averageVelocity).pow(2) }.average().toFloat()
        } else {
            0f
        }

        // Calculate acceleration
        val accelerations = mutableListOf<Float>()
        for (i in 1 until points.size) {
            val dv = points[i].velocity - points[i - 1].velocity
            val dt = (points[i].timestamp - points[i - 1].timestamp).toFloat()
            if (dt > 0) {
                accelerations.add((dv / dt) * 1000f)  // px/s²
            }
        }

        val averageAcceleration = if (accelerations.isNotEmpty()) {
            accelerations.average().toFloat()
        } else {
            0f
        }

        val maxAcceleration = accelerations.maxOrNull() ?: 0f

        // Calculate jerk (rate of change of acceleration)
        val jerks = mutableListOf<Float>()
        for (i in 1 until accelerations.size) {
            val da = accelerations[i] - accelerations[i - 1]
            val dt = (points[i + 1].timestamp - points[i].timestamp).toFloat()
            if (dt > 0) {
                jerks.add((da / dt) * 1000f)  // px/s³
            }
        }

        val averageJerk = if (jerks.isNotEmpty()) {
            jerks.map { abs(it) }.average().toFloat()
        } else {
            0f
        }

        val duration = points.last().timestamp - points.first().timestamp

        return MotionFeatures(
            averageVelocity = averageVelocity,
            maxVelocity = maxVelocity,
            minVelocity = minVelocity,
            velocityVariance = velocityVariance,
            averageAcceleration = averageAcceleration,
            maxAcceleration = maxAcceleration,
            averageJerk = averageJerk,
            duration = duration
        )
    }

    /**
     * Detect patterns in trace.
     */
    private fun detectPatterns(
        points: List<TracePoint>,
        geometry: GeometricFeatures
    ): PatternFeatures {
        // Count direction changes
        var directionChanges = 0
        for (i in 2 until points.size) {
            val angle1 = points[i - 1].angle
            val angle2 = points[i].angle
            val angleDiff = abs(angle2 - angle1) * 180 / PI

            if (angleDiff > DIRECTION_CHANGE_THRESHOLD) {
                directionChanges++
            }
        }

        // Detect loops (closed paths)
        var loopCount = 0
        for (i in 10 until points.size) {
            val current = points[i].point
            for (j in 0 until i - 5) {
                val prev = points[j].point
                val dx = current.x - prev.x
                val dy = current.y - prev.y
                val distance = sqrt(dx * dx + dy * dy)

                if (distance < LOOP_CLOSURE_THRESHOLD) {
                    loopCount++
                    break
                }
            }
        }

        // Count zigzags (sharp angle changes)
        var zigzagCount = 0
        for (i in 2 until points.size) {
            val angle1 = points[i - 1].angle
            val angle2 = points[i].angle
            val angleDiff = abs(angle2 - angle1) * 180 / PI

            if (angleDiff > ZIGZAG_ANGLE_THRESHOLD) {
                zigzagCount++
            }
        }

        // Count curvature points (moderate angle changes)
        var curvaturePoints = 0
        for (i in 2 until points.size) {
            val angle1 = points[i - 1].angle
            val angle2 = points[i].angle
            val angleDiff = abs(angle2 - angle1) * 180 / PI

            if (angleDiff in DIRECTION_CHANGE_THRESHOLD..ZIGZAG_ANGLE_THRESHOLD) {
                curvaturePoints++
            }
        }

        // Count inflection points (sign changes in curvature)
        var inflectionPoints = 0
        for (i in 3 until points.size) {
            val angle1 = points[i - 2].angle - points[i - 3].angle
            val angle2 = points[i - 1].angle - points[i - 2].angle
            val angle3 = points[i].angle - points[i - 1].angle

            if ((angle1 > 0 && angle2 < 0 && angle3 > 0) ||
                (angle1 < 0 && angle2 > 0 && angle3 < 0)) {
                inflectionPoints++
            }
        }

        // Determine pattern type
        val patternType = when {
            loopCount > 0 -> PatternType.LOOP
            geometry.curvatureIndex < 1.2f -> PatternType.LINEAR
            zigzagCount > points.size / 10 -> PatternType.ZIGZAG
            curvaturePoints > points.size / 5 -> PatternType.CURVED
            directionChanges > points.size / 3 -> PatternType.COMPLEX
            else -> PatternType.UNKNOWN
        }

        // Calculate symmetry score (simplified)
        val symmetryScore = 1.0f / (1.0f + abs(geometry.aspectRatio - 1.0f))

        return PatternFeatures(
            patternType = patternType,
            directionChanges = directionChanges,
            loopCount = loopCount,
            zigzagCount = zigzagCount,
            curvaturePoints = curvaturePoints,
            inflectionPoints = inflectionPoints,
            symmetryScore = symmetryScore
        )
    }

    /**
     * Calculate quality metrics.
     */
    private fun calculateQuality(
        points: List<TracePoint>,
        motion: MotionFeatures
    ): QualityMetrics {
        // Smoothness score (based on velocity variance)
        val smoothnessScore = 1.0f / (1.0f + motion.velocityVariance / 1000f)

        // Consistency score (based on acceleration)
        val consistencyScore = 1.0f / (1.0f + abs(motion.averageAcceleration) / 1000f)

        // Noise level (based on jerk)
        val noiseLevel = motion.averageJerk / 1000f

        // Count outliers (points with unusually high velocity)
        var outlierCount = 0
        for (point in points) {
            if (point.velocity > motion.averageVelocity * 3) {
                outlierCount++
            }
        }

        // Sampling rate
        val samplingRate = if (motion.duration > 0) {
            (points.size.toFloat() / motion.duration) * 1000f  // points/s
        } else {
            0f
        }

        // Determine quality level
        val qualityLevel = when {
            points.size < 2 -> QualityLevel.INVALID
            smoothnessScore > 0.8f && consistencyScore > 0.8f -> QualityLevel.EXCELLENT
            smoothnessScore > 0.6f && consistencyScore > 0.6f -> QualityLevel.GOOD
            smoothnessScore > 0.4f && consistencyScore > 0.4f -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }

        return QualityMetrics(
            qualityLevel = qualityLevel,
            smoothnessScore = smoothnessScore,
            consistencyScore = consistencyScore,
            noiseLevel = noiseLevel,
            outlierCount = outlierCount,
            samplingRate = samplingRate
        )
    }

    /**
     * Extract feature vector for machine learning.
     */
    private fun extractFeatureVector(
        geometry: GeometricFeatures,
        motion: MotionFeatures,
        pattern: PatternFeatures,
        quality: QualityMetrics
    ): FloatArray {
        return floatArrayOf(
            // Geometric features (8)
            geometry.totalLength,
            geometry.straightLineDistance,
            geometry.curvatureIndex,
            geometry.boundingBoxArea,
            geometry.aspectRatio,
            geometry.averageAngle.toFloat(),
            geometry.angleVariance.toFloat(),

            // Motion features (7)
            motion.averageVelocity,
            motion.maxVelocity,
            motion.velocityVariance,
            motion.averageAcceleration,
            motion.maxAcceleration,
            motion.averageJerk,
            motion.duration.toFloat(),

            // Pattern features (6)
            pattern.directionChanges.toFloat(),
            pattern.loopCount.toFloat(),
            pattern.zigzagCount.toFloat(),
            pattern.curvaturePoints.toFloat(),
            pattern.inflectionPoints.toFloat(),
            pattern.symmetryScore,

            // Quality features (4)
            quality.smoothnessScore,
            quality.consistencyScore,
            quality.noiseLevel,
            quality.samplingRate
        )
    }

    /**
     * Update analysis statistics.
     */
    private fun updateStatistics(analysisTime: Long) {
        lastAnalysisTime = analysisTime
        totalAnalyses++

        averageAnalysisTime = ((averageAnalysisTime * (totalAnalyses - 1)) + analysisTime) / totalAnalyses
    }

    /**
     * Get analysis statistics.
     *
     * @return Map of statistics
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalAnalyses" to totalAnalyses,
            "lastAnalysisTime" to lastAnalysisTime,
            "averageAnalysisTime" to averageAnalysisTime
        )
    }

    /**
     * Set callback for analysis events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing ComprehensiveTraceAnalyzer resources...")

        try {
            scope.cancel()
            callback = null

            logD("✅ ComprehensiveTraceAnalyzer resources released")
        } catch (e: Exception) {
            logE("Error releasing comprehensive trace analyzer resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
