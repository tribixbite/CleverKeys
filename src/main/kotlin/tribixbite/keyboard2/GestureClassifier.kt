package tribixbite.keyboard2

import android.content.Context
import android.util.TypedValue

/**
 * Unified gesture classifier that determines if a touch gesture is a TAP or SWIPE.
 *
 * Eliminates race conditions by providing single source of truth for gesture classification.
 *
 * ## Classification Criteria:
 *
 * **SWIPE** if:
 * - Left starting key AND
 * - (Distance >= keyWidth/2 OR Time > 150ms)
 *
 * **TAP** otherwise
 *
 * ## Design Philosophy:
 *
 * Uses **dynamic threshold** (keyWidth/2) instead of fixed distance:
 * - Adapts to different keyboard sizes
 * - Accounts for different device screen densities
 * - More reliable than fixed pixel thresholds
 *
 * Uses **time threshold** (150ms) for deliberate gestures:
 * - Any gesture > 150ms considered intentional
 * - Prevents accidental swipes from quick taps
 *
 * ## Usage:
 *
 * ```kotlin
 * val classifier = GestureClassifier(context)
 * val gestureData = GestureClassifier.GestureData(
 *     hasLeftStartingKey = true,
 *     totalDistance = 45.0f,
 *     timeElapsed = 120L,
 *     keyWidth = 80.0f
 * )
 * val type = classifier.classify(gestureData)  // Returns TAP or SWIPE
 * ```
 */
class GestureClassifier(private val context: Context) {

    /**
     * Gesture type classification
     */
    enum class GestureType {
        /** Quick touch on a key */
        TAP,

        /** Continuous motion across keyboard (swipe typing) */
        SWIPE
    }

    /**
     * Data structure containing all gesture information needed for classification.
     *
     * @property hasLeftStartingKey Did finger leave the starting key?
     * @property totalDistance Total distance traveled (pixels)
     * @property timeElapsed Time elapsed since gesture start (milliseconds)
     * @property keyWidth Width of starting key (pixels) - already scaled
     */
    data class GestureData(
        val hasLeftStartingKey: Boolean,
        val totalDistance: Float,
        val timeElapsed: Long,
        val keyWidth: Float
    )

    companion object {
        /** Maximum duration for a tap (milliseconds) */
        const val MAX_TAP_DURATION_MS = 150L
    }

    /**
     * Classify a gesture as TAP or SWIPE based on multiple criteria.
     *
     * ## Algorithm:
     *
     * 1. Calculate dynamic threshold: `keyWidth / 2`
     * 2. If left starting key:
     *    - SWIPE if distance >= threshold OR time > 150ms
     * 3. Otherwise: TAP
     *
     * ## Examples:
     *
     * ```
     * Scenario 1: Quick tap
     * - hasLeftStartingKey = false
     * - totalDistance = 5px
     * - timeElapsed = 80ms
     * → TAP (stayed on key)
     *
     * Scenario 2: Short swipe
     * - hasLeftStartingKey = true
     * - totalDistance = 50px (keyWidth = 80px → threshold = 40px)
     * - timeElapsed = 100ms
     * → SWIPE (exceeded distance threshold)
     *
     * Scenario 3: Slow deliberate gesture
     * - hasLeftStartingKey = true
     * - totalDistance = 30px (keyWidth = 80px → threshold = 40px)
     * - timeElapsed = 200ms
     * → SWIPE (exceeded time threshold)
     *
     * Scenario 4: Finger slip
     * - hasLeftStartingKey = true
     * - totalDistance = 25px (keyWidth = 80px → threshold = 40px)
     * - timeElapsed = 50ms
     * → TAP (below both thresholds)
     * ```
     *
     * @param gesture Gesture data containing all classification parameters
     * @return TAP or SWIPE classification
     */
    fun classify(gesture: GestureData): GestureType {
        // Calculate dynamic threshold based on key size
        // Use half the key width as minimum swipe distance
        // Note: gesture.keyWidth is already in pixels (from key.width * _keyWidth)
        val minSwipeDistance = gesture.keyWidth / 2.0f

        // Clear criteria: SWIPE if left starting key AND (distance OR time threshold met)
        if (gesture.hasLeftStartingKey &&
            (gesture.totalDistance >= minSwipeDistance ||
             gesture.timeElapsed > MAX_TAP_DURATION_MS))
        {
            return GestureType.SWIPE
        }

        return GestureType.TAP
    }

    /**
     * Convert dp to pixels using display density.
     *
     * Utility function for converting density-independent pixels to actual screen pixels.
     *
     * @param dp Value in density-independent pixels
     * @return Value in screen pixels
     */
    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
