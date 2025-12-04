package tribixbite.cleverkeys

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.*

/**
 * Comprehensive Trace Analysis Module
 *
 * Maximum modularity, configurability, and scope for swipe gesture analysis
 * Every parameter configurable through UI elements
 */
class ComprehensiveTraceAnalyzer(
    private val templateGenerator: WordGestureTemplateGenerator // Reuse existing generator with keyboard dimensions
) {
    // ========== 1. USER TRACE COLLECTION BOUNDING BOX ==========

    // Bounding box parameters (all configurable)
    private var enableBoundingBoxAnalysis = true
    private var boundingBoxPadding = 10.0           // Extra space around gesture
    private var includeBoundingBoxRotation = true  // Analyze rotated bounding box
    private var boundingBoxAspectRatioWeight = 1.0  // Importance of width/height ratio

    // ========== 2. TOTAL DISTANCE BREAKDOWN ==========

    // Directional distance parameters (all configurable)
    private var enableDirectionalAnalysis = true
    private var northSouthWeight = 1.0              // Vertical movement importance
    private var eastWestWeight = 1.0                // Horizontal movement importance
    private var diagonalMovementWeight = 0.8       // Diagonal vs cardinal movement
    private var movementSmoothingFactor = 0.9      // Movement direction smoothing

    // ========== 3. PAUSE/STOP DETECTION ==========

    // Stop detection parameters (all configurable)
    private var enableStopDetection = true
    private var stopThresholdMs = 150L                 // Pause duration threshold
    private var stopPositionTolerance = 15.0       // Position drift during stop
    private var stopLetterWeight = 2.0              // Extra weight for stopped letters
    private var minStopDuration = 50                   // Minimum pause to count as stop
    private var maxStopsPerGesture = 5                 // Maximum stops to consider

    // ========== 4. ANGLE POINT DETECTION ==========

    // Angle detection parameters (all configurable)
    private var enableAngleDetection = true
    private var angleDetectionThreshold = 30.0     // Degrees for direction change
    private var sharpAngleThreshold = 90.0         // Sharp turn detection
    private var smoothAngleThreshold = 15.0        // Gentle curve detection
    private var angleAnalysisWindowSize = 5           // Points to analyze for angle
    private var angleLetterBoost = 1.5             // Boost for letters at angles

    // ========== 5. LETTER DETECTION ==========

    // Letter detection parameters (all configurable)
    private var letterDetectionRadius = 80.0       // Key hit zone
    private var letterConfidenceThreshold = 0.7    // Minimum confidence for letter
    private var enableLetterPrediction = true     // Predict missed letters
    private var letterOrderWeight = 1.2            // Importance of letter sequence
    private var maxLettersPerGesture = 15              // Maximum letters to detect

    // ========== 6. START/END LETTER ANALYSIS ==========

    // Start/end parameters (all configurable)
    private var startLetterWeight = 3.0            // Start letter importance
    private var endLetterWeight = 1.0              // End letter importance
    private var startPositionTolerance = 25.0      // Start position accuracy
    private var endPositionTolerance = 50.0        // End position tolerance (less important)
    private var requireStartLetterMatch = true    // Must match start letter
    private var requireEndLetterMatch = false     // End letter optional

    init {
        Log.d(TAG, "Initialized with shared template generator")
    }

    // ========== COMPREHENSIVE TRACE ANALYSIS RESULT ==========

    data class TraceAnalysisResult(
        // Bounding box analysis
        var boundingBox: RectF? = null,
        var boundingBoxArea: Double = 0.0,
        var aspectRatio: Double = 0.0,
        var boundingBoxRotation: Double = 0.0,

        // Directional distance breakdown
        var totalDistance: Double = 0.0,
        var northDistance: Double = 0.0,
        var southDistance: Double = 0.0,
        var eastDistance: Double = 0.0,
        var westDistance: Double = 0.0,
        var diagonalDistance: Double = 0.0,

        // Stop analysis
        var stops: MutableList<StopPoint> = mutableListOf(),
        var totalStops: Int = 0,
        var stoppedLetters: MutableList<Char> = mutableListOf(),
        var averageStopDuration: Double = 0.0,

        // Angle analysis
        var anglePoints: MutableList<AnglePoint> = mutableListOf(),
        var sharpAngles: Int = 0,
        var gentleAngles: Int = 0,
        var angleLetters: MutableList<Char> = mutableListOf(),

        // Letter detection
        var detectedLetters: MutableList<Char> = mutableListOf(),
        var letterDetails: MutableList<LetterDetection> = mutableListOf(),
        var averageLetterConfidence: Double = 0.0,

        // Start/end analysis
        var startLetter: Char? = null,
        var endLetter: Char? = null,
        var startAccuracy: Double = 0.0,
        var endAccuracy: Double = 0.0,
        var startLetterMatch: Boolean = false,
        var endLetterMatch: Boolean = false,

        // Composite scores
        var overallConfidence: Double = 0.0,
        var gestureComplexity: Double = 0.0,
        var recognitionDifficulty: Double = 0.0
    )

    data class StopPoint(
        val position: PointF,
        val duration: Long,
        val nearestLetter: Char?,
        val confidence: Double
    )

    data class AnglePoint(
        val position: PointF,
        val angle: Double,
        val isSharp: Boolean,
        val nearestLetter: Char?
    )

    data class LetterDetection(
        val letter: Char,
        val position: PointF,
        val confidence: Double,
        val hadStop: Boolean,
        val hadAngle: Boolean,
        val timeSpent: Long
    )

    /**
     * Comprehensive analysis of user swipe trace with full configurability
     */
    fun analyzeTrace(swipePath: List<PointF>, timestamps: List<Long>?, targetWord: String): TraceAnalysisResult {
        val result = TraceAnalysisResult()

        if (swipePath.size < 2) return result

        Log.d(TAG, "Analyzing trace: ${swipePath.size} points for word '$targetWord'")

        // 1. BOUNDING BOX ANALYSIS
        if (enableBoundingBoxAnalysis) {
            analyzeBoundingBox(swipePath, result)
        }

        // 2. DIRECTIONAL DISTANCE BREAKDOWN
        if (enableDirectionalAnalysis) {
            analyzeDirectionalMovement(swipePath, result)
        }

        // 3. STOP/PAUSE DETECTION
        if (enableStopDetection && timestamps != null) {
            analyzeStops(swipePath, timestamps, result)
        }

        // 4. ANGLE POINT DETECTION
        if (enableAngleDetection) {
            analyzeAngles(swipePath, result)
        }

        // 5. LETTER DETECTION
        analyzeLetters(swipePath, result)

        // 6. START/END ANALYSIS
        analyzeStartEnd(swipePath, targetWord, result)

        // 7. COMPOSITE SCORING
        calculateCompositeScores(result)

        Log.d(
            TAG,
            "Analysis complete: ${result.detectedLetters.size} letters, ${result.totalStops} stops, " +
                "${result.anglePoints.size} angles, ${result.totalDistance.toInt()} total distance"
        )

        return result
    }

    /**
     * 1. BOUNDING BOX ANALYSIS - All parameters configurable
     */
    private fun analyzeBoundingBox(swipePath: List<PointF>, result: TraceAnalysisResult) {
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (point in swipePath) {
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
        }

        // Apply configurable padding
        result.boundingBox = RectF(
            minX - boundingBoxPadding.toFloat(),
            minY - boundingBoxPadding.toFloat(),
            maxX + boundingBoxPadding.toFloat(),
            maxY + boundingBoxPadding.toFloat()
        )
        result.boundingBoxArea = (result.boundingBox!!.width() * result.boundingBox!!.height()).toDouble()
        result.aspectRatio = (result.boundingBox!!.width() / result.boundingBox!!.height()).toDouble()

        // IMPLEMENTED: Rotated bounding box analysis for better gesture characterization
        result.boundingBoxRotation = if (includeBoundingBoxRotation && swipePath.size >= 3) {
            calculateOptimalRotation(swipePath)
        } else {
            0.0
        }

        Log.d(
            TAG,
            "Bounding box: ${result.boundingBox!!.width().toInt()}x${result.boundingBox!!.height().toInt()}, " +
                "aspect=${String.format("%.2f", result.aspectRatio)}"
        )
    }

    /**
     * 2. DIRECTIONAL DISTANCE BREAKDOWN - All parameters configurable
     */
    private fun analyzeDirectionalMovement(swipePath: List<PointF>, result: TraceAnalysisResult) {
        for (i in 1 until swipePath.size) {
            val prev = swipePath[i - 1]
            val curr = swipePath[i]

            val dx = curr.x - prev.x
            val dy = curr.y - prev.y
            val segmentDistance = sqrt(dx * dx + dy * dy).toDouble()

            result.totalDistance += segmentDistance

            // Categorize movement direction with configurable weights
            when {
                abs(dx) > abs(dy) -> { // Primarily horizontal
                    if (dx > 0) result.eastDistance += segmentDistance * eastWestWeight
                    else result.westDistance += segmentDistance * eastWestWeight
                }
                abs(dy) > abs(dx) -> { // Primarily vertical
                    if (dy > 0) result.southDistance += segmentDistance * northSouthWeight
                    else result.northDistance += segmentDistance * northSouthWeight
                }
                else -> { // Diagonal movement
                    result.diagonalDistance += segmentDistance * diagonalMovementWeight
                }
            }
        }

        Log.d(
            TAG,
            "Directional: N=${result.northDistance.toInt()} S=${result.southDistance.toInt()} " +
                "E=${result.eastDistance.toInt()} W=${result.westDistance.toInt()} Diag=${result.diagonalDistance.toInt()}"
        )
    }

    /**
     * 3. STOP/PAUSE DETECTION - All parameters configurable
     */
    private fun analyzeStops(swipePath: List<PointF>, timestamps: List<Long>, result: TraceAnalysisResult) {
        if (timestamps.size != swipePath.size) return

        for (i in 1 until timestamps.size) {
            if (result.stops.size >= maxStopsPerGesture) break

            val timeDelta = timestamps[i] - timestamps[i - 1]

            if (timeDelta >= stopThresholdMs) {
                val stopPosition = swipePath[i]

                // Check if position stayed within tolerance during pause
                var validStop = true
                if (i + 1 < swipePath.size) {
                    val nextPoint = swipePath[i + 1]
                    val positionDrift = sqrt(
                        (nextPoint.x - stopPosition.x).pow(2) +
                            (nextPoint.y - stopPosition.y).pow(2)
                    ).toDouble()
                    validStop = positionDrift <= stopPositionTolerance
                }

                if (validStop && timeDelta >= minStopDuration) {
                    // Find nearest letter to stop position
                    val nearestLetter = findNearestLetter(stopPosition)
                    val confidence = calculateStopConfidence(timeDelta, stopPosition)

                    val stop = StopPoint(stopPosition, timeDelta, nearestLetter, confidence)
                    result.stops.add(stop)

                    if (nearestLetter != null && !result.stoppedLetters.contains(nearestLetter)) {
                        result.stoppedLetters.add(nearestLetter)
                    }
                }
            }
        }

        result.totalStops = result.stops.size
        result.averageStopDuration = result.stops.map { it.duration }.average().takeIf { !it.isNaN() } ?: 0.0

        Log.d(
            TAG,
            "Stops: ${result.totalStops} detected, avg duration ${result.averageStopDuration.toInt()}ms, " +
                "letters: ${result.stoppedLetters}"
        )
    }

    /**
     * 4. ANGLE POINT DETECTION - All parameters configurable
     */
    private fun analyzeAngles(swipePath: List<PointF>, result: TraceAnalysisResult) {
        for (i in angleAnalysisWindowSize until swipePath.size - angleAnalysisWindowSize) {
            val angle = calculateDirectionChange(swipePath, i)

            if (abs(angle) >= angleDetectionThreshold) {
                val anglePosition = swipePath[i]
                val isSharp = abs(angle) >= sharpAngleThreshold
                val nearestLetter = findNearestLetter(anglePosition)

                val anglePoint = AnglePoint(anglePosition, angle, isSharp, nearestLetter)
                result.anglePoints.add(anglePoint)

                if (isSharp) result.sharpAngles++
                else if (abs(angle) >= smoothAngleThreshold) result.gentleAngles++

                if (nearestLetter != null && !result.angleLetters.contains(nearestLetter)) {
                    result.angleLetters.add(nearestLetter)
                }
            }
        }

        Log.d(
            TAG,
            "Angles: ${result.anglePoints.size} total, ${result.sharpAngles} sharp, " +
                "${result.gentleAngles} gentle, letters: ${result.angleLetters}"
        )
    }

    /**
     * 5. COMPREHENSIVE LETTER DETECTION - All parameters configurable
     */
    private fun analyzeLetters(swipePath: List<PointF>, result: TraceAnalysisResult) {
        var lastLetter: Char? = null
        var lastLetterTime = 0L

        for (i in swipePath.indices) {
            val point = swipePath[i]
            val nearestLetter = findNearestLetter(point)

            if (nearestLetter != null && nearestLetter != lastLetter) {
                val confidence = calculateLetterConfidence(point, nearestLetter)

                if (confidence >= letterConfidenceThreshold) {
                    // Check if this letter had stops or angles
                    val hadStop = result.stoppedLetters.contains(nearestLetter)
                    val hadAngle = result.angleLetters.contains(nearestLetter)
                    val timeSpent = if (i > 0) System.currentTimeMillis() - lastLetterTime else 0L

                    val detection = LetterDetection(nearestLetter, point, confidence, hadStop, hadAngle, timeSpent)
                    result.letterDetails.add(detection)

                    if (!result.detectedLetters.contains(nearestLetter)) {
                        result.detectedLetters.add(nearestLetter)
                    }

                    lastLetter = nearestLetter
                    lastLetterTime = System.currentTimeMillis()
                }
            }
        }

        result.averageLetterConfidence = result.letterDetails
            .map { it.confidence }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0

        Log.d(
            TAG,
            "Letters: ${result.detectedLetters}, avg confidence ${String.format("%.3f", result.averageLetterConfidence)}"
        )
    }

    /**
     * 6. START/END LETTER ANALYSIS - All parameters configurable
     */
    private fun analyzeStartEnd(swipePath: List<PointF>, targetWord: String, result: TraceAnalysisResult) {
        if (swipePath.isEmpty()) return

        // Analyze start letter
        val startPoint = swipePath[0]
        result.startLetter = findNearestLetter(startPoint)
        result.startAccuracy = calculatePositionAccuracy(startPoint, result.startLetter, startPositionTolerance)

        // Analyze end letter
        val endPoint = swipePath[swipePath.size - 1]
        result.endLetter = findNearestLetter(endPoint)
        result.endAccuracy = calculatePositionAccuracy(endPoint, result.endLetter, endPositionTolerance)

        // Check matches against target word
        if (targetWord.isNotEmpty()) {
            result.startLetterMatch = targetWord[0] == (result.startLetter ?: '\u0000')
            result.endLetterMatch = targetWord[targetWord.length - 1] == (result.endLetter ?: '\u0000')
        }

        Log.d(
            TAG,
            "Start: ${result.startLetter ?: '?'} (${String.format("%.3f", result.startAccuracy)}) " +
                "End: ${result.endLetter ?: '?'} (${String.format("%.3f", result.endAccuracy)}) " +
                "Matches: ${result.startLetterMatch}/${result.endLetterMatch}"
        )
    }

    /**
     * 7. COMPOSITE SCORING - All weights configurable
     */
    private fun calculateCompositeScores(result: TraceAnalysisResult) {
        // Calculate overall confidence based on all factors
        var confidence = 0.0

        // Bounding box contribution
        if (enableBoundingBoxAnalysis) {
            confidence += if (result.aspectRatio in 0.5..2.0) 0.2 else 0.0
        }

        // Directional movement contribution
        if (enableDirectionalAnalysis) {
            val directionalBalance = 1.0 - abs(0.5 - (result.eastDistance + result.westDistance) / result.totalDistance)
            confidence += directionalBalance * 0.2
        }

        // Letter detection contribution
        confidence += min(1.0, result.averageLetterConfidence) * 0.4

        // Start/end contribution
        confidence += if (result.startLetterMatch) startLetterWeight * 0.1 else 0.0
        confidence += if (result.endLetterMatch) endLetterWeight * 0.1 else 0.0

        result.overallConfidence = min(1.0, confidence)

        // Calculate gesture complexity
        result.gestureComplexity = (result.totalStops * 0.2) + (result.anglePoints.size * 0.3) +
            (result.detectedLetters.size * 0.1) + (result.totalDistance / 1000.0 * 0.4)

        // Calculate recognition difficulty
        result.recognitionDifficulty = 1.0 - result.overallConfidence + (result.gestureComplexity * 0.3)

        Log.d(
            TAG,
            "Composite: confidence=${String.format("%.3f", result.overallConfidence)}, " +
                "complexity=${String.format("%.3f", result.gestureComplexity)}, " +
                "difficulty=${String.format("%.3f", result.recognitionDifficulty)}"
        )
    }

    // ========== HELPER METHODS (All using configurable parameters) ==========

    private fun findNearestLetter(point: PointF): Char? {
        var minDistance = Double.MAX_VALUE
        var nearestLetter: Char? = null

        // Check all keyboard letters using existing coordinate system
        val allLetters = "qwertyuiopasdfghjklzxcvbnm"
        for (c in allLetters) {
            val coord = templateGenerator.getCharacterCoordinate(c)
            if (coord != null) {
                val distance = sqrt((point.x - coord.x).pow(2) + (point.y - coord.y).pow(2)).toDouble()

                // Use configurable letter detection radius
                if (distance <= letterDetectionRadius && distance < minDistance) {
                    minDistance = distance
                    nearestLetter = c
                }
            }
        }

        return nearestLetter
    }

    private fun calculateDirectionChange(path: List<PointF>, centerIndex: Int): Double {
        if (centerIndex < angleAnalysisWindowSize || centerIndex >= path.size - angleAnalysisWindowSize)
            return 0.0

        val before = path[centerIndex - angleAnalysisWindowSize]
        val center = path[centerIndex]
        val after = path[centerIndex + angleAnalysisWindowSize]

        val angle1 = atan2((center.y - before.y).toDouble(), (center.x - before.x).toDouble())
        val angle2 = atan2((after.y - center.y).toDouble(), (after.x - center.x).toDouble())

        var deltaAngle = Math.toDegrees(angle2 - angle1)
        if (deltaAngle > 180) deltaAngle -= 360
        if (deltaAngle < -180) deltaAngle += 360

        return deltaAngle
    }

    private fun calculateStopConfidence(duration: Long, position: PointF): Double {
        // Confidence based on stop duration and position stability
        val durationFactor = min(1.0, duration / stopThresholdMs.toDouble())
        return durationFactor * stopLetterWeight
    }

    private fun calculateLetterConfidence(point: PointF, letter: Char): Double {
        // Get actual key center coordinate
        val keyCenter = templateGenerator.getCharacterCoordinate(letter) ?: return 0.0

        // Calculate distance from point to key center
        val distance = sqrt((point.x - keyCenter.x).pow(2) + (point.y - keyCenter.y).pow(2)).toDouble()

        // Convert distance to confidence using configurable parameters
        // Confidence decreases exponentially with distance
        val confidence = exp(-distance / letterDetectionRadius)

        return min(1.0, confidence)
    }

    private fun calculatePositionAccuracy(point: PointF, letter: Char?, tolerance: Double): Double {
        if (letter == null) return 0.0

        // Get actual key center coordinate
        val keyCenter = templateGenerator.getCharacterCoordinate(letter) ?: return 0.0

        // Calculate distance from point to key center
        val distance = sqrt((point.x - keyCenter.x).pow(2) + (point.y - keyCenter.y).pow(2)).toDouble()

        // Accuracy is 1.0 if within tolerance, decreasing linearly beyond tolerance
        return if (distance <= tolerance) {
            1.0 - (distance / tolerance) * 0.5 // 50-100% accuracy within tolerance
        } else {
            max(0.0, 0.5 - (distance - tolerance) / tolerance) // Decreasing beyond tolerance
        }
    }

    // ========== CONFIGURATION METHODS ==========

    fun setBoundingBoxParameters(enable: Boolean, padding: Double, rotation: Boolean, aspectWeight: Double) {
        enableBoundingBoxAnalysis = enable
        boundingBoxPadding = padding
        includeBoundingBoxRotation = rotation
        boundingBoxAspectRatioWeight = aspectWeight
    }

    fun setDirectionalParameters(enable: Boolean, nsWeight: Double, ewWeight: Double, diagWeight: Double, smoothing: Double) {
        enableDirectionalAnalysis = enable
        northSouthWeight = nsWeight
        eastWestWeight = ewWeight
        diagonalMovementWeight = diagWeight
        movementSmoothingFactor = smoothing
    }

    fun setStopParameters(enable: Boolean, threshold: Long, tolerance: Double, weight: Double, minDur: Int, maxStops: Int) {
        enableStopDetection = enable
        stopThresholdMs = threshold
        stopPositionTolerance = tolerance
        stopLetterWeight = weight
        minStopDuration = minDur
        maxStopsPerGesture = maxStops
    }

    fun setAngleParameters(enable: Boolean, threshold: Double, sharp: Double, smooth: Double, window: Int, boost: Double) {
        enableAngleDetection = enable
        angleDetectionThreshold = threshold
        sharpAngleThreshold = sharp
        smoothAngleThreshold = smooth
        angleAnalysisWindowSize = window
        angleLetterBoost = boost
    }

    fun setLetterParameters(radius: Double, confidence: Double, predict: Boolean, order: Double, maxLetters: Int) {
        letterDetectionRadius = radius
        letterConfidenceThreshold = confidence
        enableLetterPrediction = predict
        letterOrderWeight = order
        maxLettersPerGesture = maxLetters
    }

    fun setStartEndParameters(
        startWeight: Double,
        endWeight: Double,
        startTol: Double,
        endTol: Double,
        reqStart: Boolean,
        reqEnd: Boolean
    ) {
        startLetterWeight = startWeight
        endLetterWeight = endWeight
        startPositionTolerance = startTol
        endPositionTolerance = endTol
        requireStartLetterMatch = reqStart
        requireEndLetterMatch = reqEnd
    }

    /**
     * Calculate optimal rotation angle for minimal bounding box
     */
    private fun calculateOptimalRotation(points: List<PointF>): Double {
        var minArea = Double.MAX_VALUE
        var optimalRotation = 0.0

        // Test rotations from 0 to 180 degrees (in 5-degree increments)
        for (angle in 0 until 180 step 5) {
            val radians = Math.toRadians(angle.toDouble())

            // Calculate rotated bounding box
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE

            for (point in points) {
                // Rotate point around origin
                val rotatedX = (point.x * cos(radians) - point.y * sin(radians)).toFloat()
                val rotatedY = (point.x * sin(radians) + point.y * cos(radians)).toFloat()

                minX = min(minX, rotatedX)
                maxX = max(maxX, rotatedX)
                minY = min(minY, rotatedY)
                maxY = max(maxY, rotatedY)
            }

            val area = ((maxX - minX) * (maxY - minY)).toDouble()
            if (area < minArea) {
                minArea = area
                optimalRotation = angle.toDouble()
            }
        }

        return optimalRotation
    }

    /**
     * Set keyboard dimensions for coordinate calculations
     */
    fun setKeyboardDimensions(width: Float, height: Float) {
        templateGenerator.setKeyboardDimensions(width, height)
        Log.d(TAG, "Keyboard dimensions set: ${width}x$height")
    }

    companion object {
        private const val TAG = "ComprehensiveTraceAnalyzer"
    }
}
