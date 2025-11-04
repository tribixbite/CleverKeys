package tribixbite.keyboard2

/**
 * LEGACY: Stub implementation of ContinuousGestureRecognizer
 *
 * This class is part of the OLD CGR (Continuous Gesture Recognizer) system
 * that was replaced with pure ONNX neural prediction. It exists only to allow
 * legacy code to compile and is NOT used in the actual application.
 *
 * The new system uses:
 * - neural/OnnxSwipePredictorImpl.kt for predictions
 * - SwipeDetector.kt for gesture detection
 *
 * @deprecated Replaced by ONNX-based prediction system
 */
@Deprecated(
    message = "CGR library replaced with ONNX neural prediction",
    level = DeprecationLevel.WARNING
)
class ContinuousGestureRecognizer {

    /**
     * Point in gesture path
     */
    data class Point(
        val x: Double,
        val y: Double
    )

    /**
     * Recognition result with probability
     */
    data class Result(
        val template: Template,
        val prob: Double
    ) : Comparable<Result> {
        override fun compareTo(other: Result): Int {
            return other.prob.compareTo(this.prob) // Sort descending by probability
        }
    }

    /**
     * Template for gesture recognition
     */
    data class Template(
        val id: String,
        val points: List<Point>
    )

    /**
     * Set template set for recognition (stub implementation)
     */
    fun setTemplateSet(templates: List<Template>) {
        // Stub: CGR library removed, no actual implementation
    }

    /**
     * Recognize gesture from points (stub implementation)
     *
     * @return null since CGR library is not available
     */
    fun recognize(points: List<Point>): List<Result>? {
        // Stub: CGR library removed, return empty results
        return emptyList()
    }
}
