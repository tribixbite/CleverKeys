package tribixbite.keyboard2

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sophisticated swipe detection using multiple factors
 */
class SwipeDetector {
    companion object {
        private const val TAG = "SwipeDetector"

        // Thresholds for swipe detection
        private const val MIN_PATH_LENGTH = 50.0f
        private const val MIN_DURATION = 0.15f // seconds
        private const val MAX_DURATION = 3.0f // seconds
        private const val MIN_DIRECTION_CHANGES = 1
        private const val MIN_KEYBOARD_COVERAGE = 100.0f
        private const val MIN_AVERAGE_VELOCITY = 50.0f // pixels per second
        private const val MAX_VELOCITY_VARIATION = 500.0f
    }

    /**
     * Classification result for input
     */
    data class SwipeClassification(
        @JvmField val isSwipe: Boolean,
        @JvmField val confidence: Float,
        @JvmField val reason: String,
        @JvmField val quality: SwipeQuality
    ) {
        enum class SwipeQuality {
            HIGH,     // Clear, deliberate swipe
            MEDIUM,   // Acceptable swipe
            LOW,      // Ambiguous, might be typing
            NOT_SWIPE // Definitely not a swipe
        }
    }

    /**
     * Classify input as swipe or regular typing
     */
    fun classifyInput(input: SwipeInput): SwipeClassification {
        // Quick rejection checks
        if (input.coordinates.size < 3) {
            return SwipeClassification(
                false, 0.0f, "Too few points",
                SwipeClassification.SwipeQuality.NOT_SWIPE
            )
        }

        if (input.duration < MIN_DURATION) {
            return SwipeClassification(
                false, 0.1f, "Too fast (likely tap)",
                SwipeClassification.SwipeQuality.NOT_SWIPE
            )
        }

        if (input.duration > MAX_DURATION) {
            return SwipeClassification(
                false, 0.1f, "Too slow (likely typing)",
                SwipeClassification.SwipeQuality.NOT_SWIPE
            )
        }

        // Calculate multi-factor confidence score
        var confidence = 0f
        val reasoning = StringBuilder()

        // Factor 1: Path length (30% weight)
        val pathLengthScore = calculatePathLengthScore(input.pathLength)
        confidence += pathLengthScore * 0.3f
        if (pathLengthScore > 0.5f) {
            reasoning.append("Good path length; ")
        }

        // Factor 2: Duration (20% weight)
        val durationScore = calculateDurationScore(input.duration)
        confidence += durationScore * 0.2f
        if (durationScore > 0.5f) {
            reasoning.append("Good duration; ")
        }

        // Factor 3: Direction changes (20% weight)
        val directionScore = calculateDirectionScore(input.directionChanges)
        confidence += directionScore * 0.2f
        if (directionScore > 0.5f) {
            reasoning.append("Multiple directions; ")
        }

        // Factor 4: Velocity consistency (15% weight)
        val velocityScore = calculateVelocityScore(input.velocityProfile, input.averageVelocity)
        confidence += velocityScore * 0.15f
        if (velocityScore > 0.5f) {
            reasoning.append("Consistent velocity; ")
        }

        // Factor 5: Keyboard coverage (15% weight)
        val coverageScore = calculateCoverageScore(input.keyboardCoverage)
        confidence += coverageScore * 0.15f
        if (coverageScore > 0.5f) {
            reasoning.append("Good coverage; ")
        }

        // Determine classification
        val isSwipe = confidence > 0.5f
        val quality = when {
            confidence > 0.8f -> SwipeClassification.SwipeQuality.HIGH
            confidence > 0.6f -> SwipeClassification.SwipeQuality.MEDIUM
            confidence > 0.4f -> SwipeClassification.SwipeQuality.LOW
            else -> SwipeClassification.SwipeQuality.NOT_SWIPE
        }

        val reason = if (reasoning.isNotEmpty()) reasoning.toString() else "Low confidence factors"

        Log.d(TAG, "Classification: isSwipe=$isSwipe, confidence=${"%.2f".format(confidence)}, " +
            "quality=$quality, reason=$reason")

        return SwipeClassification(isSwipe, confidence, reason, quality)
    }

    private fun calculatePathLengthScore(pathLength: Float): Float {
        if (pathLength < MIN_PATH_LENGTH) return 0f
        if (pathLength > 500) return 1.0f
        // Linear interpolation between min and optimal
        return (pathLength - MIN_PATH_LENGTH) / (500 - MIN_PATH_LENGTH)
    }

    private fun calculateDurationScore(duration: Float): Float {
        // Optimal swipe duration is 0.3 - 1.2 seconds
        if (duration < 0.3f || duration > 2.0f) return 0.2f
        if (duration in 0.3f..1.2f) return 1.0f
        // Gradual decrease outside optimal range
        return if (duration < 0.3f) {
            duration / 0.3f
        } else {
            max(0.2f, 2.0f - duration)
        }
    }

    private fun calculateDirectionScore(directionChanges: Int): Float {
        if (directionChanges < MIN_DIRECTION_CHANGES) return 0f
        if (directionChanges >= 5) return 1.0f
        // More direction changes = more likely a swipe
        return directionChanges / 5.0f
    }

    private fun calculateVelocityScore(velocityProfile: List<Float>, averageVelocity: Float): Float {
        if (velocityProfile.isEmpty()) return 0f

        // Check if velocity is reasonable
        if (averageVelocity < MIN_AVERAGE_VELOCITY) return 0.1f

        // Calculate velocity variation
        var sum = 0f
        var sumSquared = 0f
        for (v in velocityProfile) {
            sum += v
            sumSquared += v * v
        }

        val mean = sum / velocityProfile.size
        val variance = (sumSquared / velocityProfile.size) - (mean * mean)
        val stdDev = sqrt(variance)

        // Swipes have relatively consistent velocity
        if (stdDev > MAX_VELOCITY_VARIATION) return 0.3f

        // Lower variation = higher score
        val variationScore = max(0f, 1.0f - (stdDev / MAX_VELOCITY_VARIATION))

        // Combine with average velocity score
        val avgScore = kotlin.math.min(1.0f, averageVelocity / 300.0f)

        return (variationScore * 0.6f) + (avgScore * 0.4f)
    }

    private fun calculateCoverageScore(keyboardCoverage: Float): Float {
        if (keyboardCoverage < MIN_KEYBOARD_COVERAGE) return 0f
        if (keyboardCoverage > 400) return 1.0f
        // Linear interpolation
        return (keyboardCoverage - MIN_KEYBOARD_COVERAGE) / (400 - MIN_KEYBOARD_COVERAGE)
    }

    /**
     * Determine if we should use DTW prediction based on swipe quality
     */
    fun shouldUseDTW(classification: SwipeClassification): Boolean {
        return classification.quality == SwipeClassification.SwipeQuality.HIGH ||
            classification.quality == SwipeClassification.SwipeQuality.MEDIUM
    }
}
