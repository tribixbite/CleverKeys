package tribixbite.keyboard2

import android.graphics.PointF
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Swipe Gesture Recognizer
 *
 * Recognizes swipe gestures across keyboard keys and tracks the path
 * for word prediction. Determines if a gesture qualifies as swipe typing
 * or medium swipe based on distance, velocity, and touched keys.
 *
 * Features:
 * - Distance and velocity-based swipe detection
 * - Medium swipe (2-letter) vs full swipe typing distinction
 * - Point filtering (distance threshold, velocity filtering)
 * - Key dwell time filtering to avoid false registrations
 * - Loop detection for repeated letters
 * - Detailed logging for analysis
 *
 * Thresholds:
 * - MIN_SWIPE_DISTANCE: 50px for full swipe typing
 * - MIN_MEDIUM_SWIPE_DISTANCE: 35px for 2-letter medium swipes
 * - VELOCITY_THRESHOLD: 0.15 px/ms (based on FlorisBoard)
 * - MIN_POINT_DISTANCE: 25px between registered points
 * - MIN_DWELL_TIME: 30ms on a key to register it
 *
 * Usage:
 * ```kotlin
 * val recognizer = SwipeGestureRecognizer()
 * recognizer.setKeyboardDimensions(keyWidth, keyHeight)
 * recognizer.startSwipe(x, y, key)
 * // ... during swipe ...
 * recognizer.addPoint(x, y, key)
 * // ... on touch end ...
 * val keys = recognizer.endSwipe()
 * if (keys != null) {
 *     // Process swipe typing with keys
 * }
 * ```
 *
 * Ported from Java to Kotlin with improvements.
 */
class SwipeGestureRecognizer {

    companion object {
        // Minimum distance to consider it a swipe typing gesture
        private const val MIN_SWIPE_DISTANCE = 50.0f
        // Minimum distance for medium swipe (two-letter spans)
        private const val MIN_MEDIUM_SWIPE_DISTANCE = 35.0f
        // Maximum time between touch points to continue swipe
        private const val MAX_POINT_INTERVAL_MS = 500L
        // Velocity threshold in pixels per millisecond (based on FlorisBoard's 0.10 dp/ms)
        private const val VELOCITY_THRESHOLD = 0.15f
        // Minimum distance between points to register (based on FlorisBoard's key_width/4)
        private const val MIN_POINT_DISTANCE = 25.0f
        // Minimum dwell time on a key to register it (milliseconds)
        private const val MIN_DWELL_TIME_MS = 30L
    }

    private val swipePath = mutableListOf<PointF>()
    private val touchedKeys = mutableListOf<KeyboardData.Key>()
    private val timestamps = mutableListOf<Long>()
    private var isSwipeTyping = false
    private var isMediumSwipe = false
    private var startTime = 0L
    private var totalDistance = 0f
    private var lastKey: KeyboardData.Key? = null
    private var loopDetector: LoopGestureDetector

    init {
        // Initialize loop detector with approximate key dimensions
        // These will be updated when actual keyboard dimensions are known
        loopDetector = LoopGestureDetector(100.0f, 80.0f)
    }

    /**
     * Set keyboard dimensions for loop detection
     */
    fun setKeyboardDimensions(keyWidth: Float, keyHeight: Float) {
        loopDetector = LoopGestureDetector(keyWidth, keyHeight)
    }

    /**
     * Start tracking a new swipe gesture
     */
    fun startSwipe(x: Float, y: Float, key: KeyboardData.Key?) {
        reset()
        swipePath.add(PointF(x, y))

        // Add key if it's alphabetic
        if (key != null) {
            key.keys.firstOrNull()?.let { keyValue ->
                if (isAlphabeticKey(keyValue)) {
                    touchedKeys.add(key)
                    lastKey = key
                }
            }
        }

        startTime = System.currentTimeMillis()
        timestamps.add(startTime)
        totalDistance = 0f
    }

    /**
     * Add a point to the current swipe path
     */
    fun addPoint(x: Float, y: Float, key: KeyboardData.Key?) {
        if (swipePath.isEmpty()) return

        val now = System.currentTimeMillis()
        val timeSinceStart = now - startTime

        // Check if this should be considered swipe typing or medium swipe
        // Require minimum time to avoid false triggers on quick taps/swipes
        // CRITICAL FIX: Allow medium swipe to upgrade to full swipe typing
        if (!isSwipeTyping && timeSinceStart > 150) {
            if (totalDistance > MIN_SWIPE_DISTANCE) {
                // Promote from medium swipe to full swipe typing if distance threshold crossed
                isSwipeTyping = shouldConsiderSwipeTyping()
                isMediumSwipe = false // Clear medium swipe flag
            } else if (!isMediumSwipe && totalDistance > MIN_MEDIUM_SWIPE_DISTANCE && timeSinceStart > 200) {
                // Medium swipe needs slightly more time to avoid conflicts with directional swipes
                isMediumSwipe = shouldConsiderMediumSwipe()
            }
        }

        val lastPoint = swipePath.last()
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        val distance = sqrt(dx * dx + dy * dy)

        // Apply distance-based filtering (like FlorisBoard)
        if (distance < MIN_POINT_DISTANCE && swipePath.size > 1) {
            // Skip this point - too close to previous
            return
        }

        totalDistance += distance

        swipePath.add(PointF(x, y))
        timestamps.add(now)

        // Calculate velocity for filtering (like FlorisBoard)
        val timeDelta = if (timestamps.size > 0) {
            now - timestamps[timestamps.size - 1]
        } else {
            0L
        }
        val velocity = if (timeDelta > 0) distance / timeDelta else 0f

        // Add key if it's different from the last one and is alphabetic
        if (key != null && key != lastKey) {
            key.keys.firstOrNull()?.let { keyValue ->
                if (isAlphabeticKey(keyValue)) {
                    // Apply velocity-based filtering (skip if moving too fast)
                    if (velocity > VELOCITY_THRESHOLD && timeDelta < MIN_DWELL_TIME_MS) {
                        // Moving too fast - likely transitioning between keys
                        return
                    }

                    // Check if this key is already in recent keys (avoid duplicates)
                    val isDuplicate = if (touchedKeys.size >= 3) {
                        // Check last 3 keys for duplicates (increased from 2)
                        touchedKeys.takeLast(3).contains(key)
                    } else {
                        false
                    }

                    // Only add if not a recent duplicate and we've moved enough
                    if (!isDuplicate && (distance > 35.0f || touchedKeys.isEmpty())) {
                        touchedKeys.add(key)
                        lastKey = key
                    }
                }
            }
        }
    }

    /**
     * End the swipe gesture and return the touched keys if it was swipe typing
     */
    fun endSwipe(): List<KeyboardData.Key>? {
        // Log detailed swipe data for analysis
        logSwipeData()

        return when {
            isSwipeTyping && touchedKeys.size >= 2 -> {
                ArrayList(touchedKeys)
            }
            isMediumSwipe && touchedKeys.size == 2 -> {
                ArrayList(touchedKeys)
            }
            else -> null
        }
    }

    /**
     * Check if the current gesture should be considered swipe typing
     */
    private fun shouldConsiderSwipeTyping(): Boolean {
        // Need at least 2 alphabetic keys
        if (touchedKeys.size < 2) return false

        // Check if all touched keys are alphabetic
        return touchedKeys.all { key ->
            key.keys.firstOrNull()?.let { isAlphabeticKey(it) } ?: false
        }
    }

    /**
     * Check if the current gesture should be considered a medium swipe (exactly 2 letters)
     */
    private fun shouldConsiderMediumSwipe(): Boolean {
        // Need exactly 2 alphabetic keys for medium swipe
        if (touchedKeys.size != 2) return false

        // Check if all touched keys are alphabetic
        val allAlphabetic = touchedKeys.all { key ->
            key.keys.firstOrNull()?.let { isAlphabeticKey(it) } ?: false
        }

        if (!allAlphabetic) return false

        // Additional check: medium swipe should have moderate distance
        // This helps avoid false positives for quick directional swipes
        return totalDistance >= MIN_MEDIUM_SWIPE_DISTANCE && totalDistance < MIN_SWIPE_DISTANCE
    }

    /**
     * Check if a KeyValue represents an alphabetic character
     */
    private fun isAlphabeticKey(kv: KeyValue): Boolean {
        return when (kv) {
            is KeyValue.CharKey -> kv.char.isLetter()
            else -> false
        }
    }

    /**
     * Get the current swipe path for rendering
     */
    fun getSwipePath(): List<PointF> {
        return ArrayList(swipePath)
    }

    /**
     * Check if currently in swipe typing mode
     */
    fun isSwipeTyping(): Boolean {
        return isSwipeTyping
    }

    /**
     * Check if currently in medium swipe mode (exactly 2 letters)
     */
    fun isMediumSwipe(): Boolean {
        return isMediumSwipe
    }

    /**
     * Reset the recognizer for a new gesture
     */
    fun reset() {
        swipePath.clear()
        touchedKeys.clear()
        timestamps.clear()
        isSwipeTyping = false
        isMediumSwipe = false
        lastKey = null
        totalDistance = 0f
    }

    /**
     * Get the sequence of characters from touched keys
     */
    fun getKeySequence(): String {
        if (touchedKeys.isEmpty()) return ""

        return buildString {
            touchedKeys.forEach { key ->
                val kv = key.keys.firstOrNull()
                if (kv is KeyValue.CharKey && kv.char.isLetter()) {
                    append(kv.char)
                }
            }
        }
    }

    /**
     * Get the enhanced key sequence with loop detection for repeated letters
     */
    fun getEnhancedKeySequence(): String {
        val baseSequence = getKeySequence()
        if (baseSequence.isEmpty() || swipePath.size < 10) {
            return baseSequence
        }

        // Detect loops in the swipe path
        val loops = loopDetector.detectLoops(swipePath, touchedKeys)

        if (loops.isEmpty()) {
            return baseSequence
        }

        // Apply loop detection to enhance the sequence
        val enhanced = loopDetector.applyLoops(baseSequence, loops, swipePath)

        return enhanced
    }

    /**
     * Get timestamps for ML data collection
     */
    fun getTimestamps(): List<Long> {
        return ArrayList(timestamps)
    }

    /**
     * Log comprehensive swipe data for analysis and debugging
     */
    private fun logSwipeData() {
        if (swipePath.isEmpty()) return

        // All logging is commented out for production
        // Uncomment for debugging/calibration

        // Log path coordinates for calibration analysis
        val pathStr = buildString {
            append("Path: ")
            for (i in 0 until minOf(swipePath.size, 20)) {
                val p = swipePath[i]
                append(String.format("(%.0f,%.0f) ", p.x, p.y))
            }
            if (swipePath.size > 20) {
                append("... (${swipePath.size - 20} more points)")
            }
        }

        // Log touched keys
        val keysStr = buildString {
            append("Touched keys: ")
            touchedKeys.forEach { key ->
                val kv = key.keys.firstOrNull()
                if (kv is KeyValue.CharKey) {
                    append(kv.char).append(" ")
                }
            }
        }

        // Log velocity and gesture characteristics
        if (swipePath.size >= 2) {
            val avgVelocity = totalDistance / (System.currentTimeMillis() - startTime)

            // Calculate straightness ratio
            val start = swipePath.first()
            val end = swipePath.last()
            val directDistance = sqrt(
                (end.x - start.x).pow(2) + (end.y - start.y).pow(2)
            )
            val straightnessRatio = directDistance / totalDistance
        }
    }
}
