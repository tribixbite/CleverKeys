package juloo.keyboard2

import android.graphics.PointF
import kotlin.math.*

/**
 * Sophisticated swipe detection using multiple factors
 * Kotlin implementation with sealed classes and data validation
 */
class SwipeDetector {
    
    companion object {
        private const val MIN_PATH_LENGTH = 50.0f
        private const val MIN_DURATION = 0.15f // seconds
        private const val MAX_DURATION = 3.0f // seconds
        private const val MIN_DIRECTION_CHANGES = 1
        private const val MIN_KEYBOARD_COVERAGE = 100.0f
        private const val MIN_AVERAGE_VELOCITY = 50.0f // pixels per second
        private const val MAX_VELOCITY_VARIATION = 500.0f
    }
    
    /**
     * Swipe quality levels
     */
    enum class SwipeQuality { EXCELLENT, GOOD, FAIR, POOR }
    
    /**
     * Classification result for input
     */
    data class SwipeClassification(
        val isSwipe: Boolean,
        val confidence: Float,
        val reason: String,
        val quality: SwipeQuality
    )
    
    /**
     * Detect if input represents a swipe gesture
     */
    fun detectSwipe(input: SwipeInput): SwipeClassification {
        val metrics = analyzeGestureMetrics(input)
        
        val isSwipe = when {
            input.pathLength < MIN_PATH_LENGTH -> false
            input.duration < MIN_DURATION || input.duration > MAX_DURATION -> false
            input.directionChanges < MIN_DIRECTION_CHANGES -> false
            input.averageVelocity < MIN_AVERAGE_VELOCITY -> false
            input.keyboardCoverage < MIN_KEYBOARD_COVERAGE -> false
            else -> true
        }
        
        val confidence = calculateConfidence(input, metrics)
        val quality = determineQuality(confidence)
        val reason = generateReason(input, isSwipe)
        
        return SwipeClassification(isSwipe, confidence, reason, quality)
    }
    
    /**
     * Analyze gesture metrics for classification
     */
    private fun analyzeGestureMetrics(input: SwipeInput): GestureMetrics {
        val velocityVariation = if (input.velocityProfile.isNotEmpty()) {
            val avgVelocity = input.velocityProfile.average()
            input.velocityProfile.map { abs(it - avgVelocity) }.average()
        } else 0.0
        
        val straightnessRatio = if (input.pathLength > 0) {
            val directDistance = input.startPoint.distanceTo(input.endPoint)
            directDistance / input.pathLength
        } else 0f
        
        return GestureMetrics(
            velocityVariation = velocityVariation.toFloat(),
            straightnessRatio = straightnessRatio,
            complexity = calculateComplexity(input)
        )
    }
    
    /**
     * Calculate gesture complexity score
     */
    private fun calculateComplexity(input: SwipeInput): Float {
        var complexity = 0f
        
        // Path length factor
        complexity += (input.pathLength / 500f).coerceAtMost(1f) * 0.3f
        
        // Direction changes factor
        complexity += (input.directionChanges / 10f).coerceAtMost(1f) * 0.3f
        
        // Duration factor (optimal around 0.5-1.5 seconds)
        val durationScore = when {
            input.duration in 0.5f..1.5f -> 1f
            input.duration in 0.2f..2.5f -> 0.7f
            else -> 0.3f
        }
        complexity += durationScore * 0.2f
        
        // Velocity variation factor (moderate variation is good)
        val velocityScore = when {
            input.velocityProfile.isEmpty() -> 0f
            else -> {
                val avgVel = input.velocityProfile.average()
                val variation = input.velocityProfile.map { abs(it - avgVel) }.average()
                (1f - (variation / MAX_VELOCITY_VARIATION).coerceAtMost(1f)) * 0.2f
            }
        }
        complexity += velocityScore
        
        return complexity.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate confidence score
     */
    private fun calculateConfidence(input: SwipeInput, metrics: GestureMetrics): Float {
        var confidence = 0f
        
        // Path length confidence
        confidence += when {
            input.pathLength > 200f -> 0.25f
            input.pathLength > 100f -> 0.15f
            input.pathLength > 50f -> 0.1f
            else -> 0f
        }
        
        // Duration confidence
        confidence += when {
            input.duration in 0.3f..2.0f -> 0.25f
            input.duration in 0.15f..3.0f -> 0.15f
            else -> 0f
        }
        
        // Direction changes confidence
        confidence += when {
            input.directionChanges >= 3 -> 0.2f
            input.directionChanges >= 2 -> 0.15f
            input.directionChanges >= 1 -> 0.1f
            else -> 0f
        }
        
        // Velocity confidence
        confidence += when {
            input.averageVelocity > 100f -> 0.15f
            input.averageVelocity > 50f -> 0.1f
            else -> 0.05f
        }
        
        // Complexity bonus
        confidence += metrics.complexity * 0.15f
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Determine gesture quality from confidence
     */
    private fun determineQuality(confidence: Float): SwipeQuality {
        return when {
            confidence >= 0.8f -> SwipeQuality.EXCELLENT
            confidence >= 0.6f -> SwipeQuality.GOOD
            confidence >= 0.4f -> SwipeQuality.FAIR
            else -> SwipeQuality.POOR
        }
    }
    
    /**
     * Generate human-readable reason for classification
     */
    private fun generateReason(input: SwipeInput, isSwipe: Boolean): String {
        if (!isSwipe) {
            return when {
                input.pathLength < MIN_PATH_LENGTH -> "Path too short (${input.pathLength}px < ${MIN_PATH_LENGTH}px)"
                input.duration < MIN_DURATION -> "Duration too short (${input.duration}s < ${MIN_DURATION}s)"
                input.duration > MAX_DURATION -> "Duration too long (${input.duration}s > ${MAX_DURATION}s)"
                input.directionChanges < MIN_DIRECTION_CHANGES -> "Too few direction changes (${input.directionChanges})"
                input.averageVelocity < MIN_AVERAGE_VELOCITY -> "Velocity too low (${input.averageVelocity} px/s)"
                else -> "Failed multiple criteria"
            }
        } else {
            return "Valid swipe: ${input.pathLength.toInt()}px, ${input.duration}s, ${input.directionChanges} changes"
        }
    }
    
    /**
     * Check if should use neural prediction based on swipe quality
     */
    fun shouldUseNeuralPrediction(classification: SwipeClassification): Boolean {
        return classification.isSwipe && classification.quality != SwipeQuality.POOR
    }
    
    /**
     * Gesture analysis metrics
     */
    private data class GestureMetrics(
        val velocityVariation: Float,
        val straightnessRatio: Float,
        val complexity: Float
    )
}