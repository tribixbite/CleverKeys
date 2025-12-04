package tribixbite.cleverkeys

import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Recognizes swipe gestures across keyboard keys and tracks the path
 * for word prediction.
 */
class SwipeGestureRecognizer {
    private val swipePath: MutableList<PointF> = mutableListOf()
    private val touchedKeys: MutableList<KeyboardData.Key> = mutableListOf()
    private val timestamps: MutableList<Long> = mutableListOf()
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
        // android.util.Log.d("SwipeGesture", "startSwipe at $x,$y")
        val firstKey = key?.keys?.get(0)
        if (key != null && firstKey != null && isAlphabeticKey(firstKey)) {
            // android.util.Log.d("SwipeGesture", "Started on alphabetic key: ${firstKey.getString()}")
            touchedKeys.add(key)
            lastKey = key
        } else {
            // android.util.Log.d("SwipeGesture", "Started on non-alphabetic key")
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
                // android.util.Log.d("SwipeGesture", "Swipe typing check: $isSwipeTyping")
            } else if (!isMediumSwipe && totalDistance > MIN_MEDIUM_SWIPE_DISTANCE && timeSinceStart > 200) {
                // Medium swipe needs slightly more time to avoid conflicts with directional swipes
                isMediumSwipe = shouldConsiderMediumSwipe()
                // android.util.Log.d("SwipeGesture", "Medium swipe check: $isMediumSwipe")
            }
        }

        val lastPoint = swipePath[swipePath.size - 1]
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
        val timeDelta = if (timestamps.isNotEmpty()) {
            now - timestamps[timestamps.size - 1]
        } else {
            0L
        }
        val velocity = if (timeDelta > 0) distance / timeDelta else 0f

        // Add key if it's different from the last one and is alphabetic
        val keyVal = key?.keys?.get(0)
        if (key != null && key != lastKey && keyVal != null && isAlphabeticKey(keyVal)) {
            // Apply velocity-based filtering (skip if moving too fast)
            if (velocity > VELOCITY_THRESHOLD && timeDelta < MIN_DWELL_TIME_MS) {
                // Moving too fast - likely transitioning between keys
                // android.util.Log.d("SwipeGesture", "Skipping key due to high velocity: $velocity")
                return
            }

            // Check if this key is already in recent keys (avoid duplicates)
            val size = touchedKeys.size
            val isDuplicate = if (size >= 3) {
                // Check last 3 keys for duplicates (increased from 2)
                (max(0, size - 3) until size).any { touchedKeys[it] == key }
            } else {
                false
            }

            // Only add if not a recent duplicate and we've moved enough
            if (!isDuplicate && (distance > 35.0f || touchedKeys.isEmpty())) {
                // android.util.Log.d("SwipeGesture", "Adding key: ${key.keys[0].getString()}")
                touchedKeys.add(key)
                lastKey = key
            }
        }
    }

    /**
     * End the swipe gesture and return the touched keys if it was swipe typing
     */
    fun endSwipe(): List<KeyboardData.Key>? {
        // android.util.Log.d("SwipeGesture", "endSwipe: isSwipeTyping=$isSwipeTyping, touchedKeys=${touchedKeys.size}")

        // Log detailed swipe data for analysis
        logSwipeData()

        return when {
            isSwipeTyping && touchedKeys.size >= 2 -> {
                // android.util.Log.d("SwipeGesture", "Returning ${touchedKeys.size} keys")
                touchedKeys.toList()
            }
            isMediumSwipe && touchedKeys.size == 2 -> {
                // android.util.Log.d("SwipeGesture", "Returning medium swipe with 2 keys")
                touchedKeys.toList()
            }
            else -> {
                // android.util.Log.d("SwipeGesture", "Not enough keys or not swipe typing")
                null
            }
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
            val kv = key.keys.getOrNull(0)
            kv != null && isAlphabeticKey(kv)
        }
    }

    /**
     * Check if the current gesture should be considered a medium swipe (exactly 2 letters)
     */
    private fun shouldConsiderMediumSwipe(): Boolean {
        // Need exactly 2 alphabetic keys for medium swipe
        if (touchedKeys.size != 2) return false

        // Check if all touched keys are alphabetic
        if (!touchedKeys.all { key ->
            val kv = key.keys.getOrNull(0)
            kv != null && isAlphabeticKey(kv)
        }) {
            return false
        }

        // Additional check: medium swipe should have moderate distance
        // This helps avoid false positives for quick directional swipes
        return totalDistance >= MIN_MEDIUM_SWIPE_DISTANCE && totalDistance < MIN_SWIPE_DISTANCE
    }

    /**
     * Check if a KeyValue represents an alphabetic character
     */
    private fun isAlphabeticKey(kv: KeyValue): Boolean {
        if (kv.getKind() != KeyValue.Kind.Char) return false
        val c = kv.getChar()
        return c.isLetter()
    }

    /**
     * Get the current swipe path for rendering
     */
    fun getSwipePath(): List<PointF> = swipePath.toList()

    /**
     * Check if currently in swipe typing mode
     */
    fun isSwipeTyping(): Boolean = isSwipeTyping

    /**
     * Check if currently in medium swipe mode (exactly 2 letters)
     */
    fun isMediumSwipe(): Boolean = isMediumSwipe

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
            for (key in touchedKeys) {
                val kv = key.keys.getOrNull(0)
                if (kv != null && kv.getKind() == KeyValue.Kind.Char) {
                    val c = kv.getChar()
                    if (c.isLetter()) {
                        append(c)
                    }
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

        // android.util.Log.d("SwipeGesture", "Enhanced sequence: $baseSequence -> $enhanced")
        return enhanced
    }

    /**
     * Get timestamps for ML data collection
     */
    fun getTimestamps(): List<Long> = timestamps.toList()

    /**
     * Log comprehensive swipe data for analysis and debugging
     */
    private fun logSwipeData() {
        if (swipePath.isEmpty()) return

        // android.util.Log.d("SwipeAnalysis", "===== SWIPE DATA ANALYSIS =====")
        // android.util.Log.d("SwipeAnalysis", "Total points: ${swipePath.size}")
        // android.util.Log.d("SwipeAnalysis", "Total distance: $totalDistance")
        // android.util.Log.d("SwipeAnalysis", "Duration: ${System.currentTimeMillis() - startTime}ms")
        // android.util.Log.d("SwipeAnalysis", "Key sequence: ${getKeySequence()}")
        // android.util.Log.d("SwipeAnalysis", "Was swipe typing: $isSwipeTyping")

        // Log path coordinates for calibration analysis
        val pathStr = buildString {
            append("Path: ")
            for (i in 0 until min(swipePath.size, 20)) {
                val p = swipePath[i]
                append("(%.0f,%.0f) ".format(p.x, p.y))
            }
            if (swipePath.size > 20) {
                append("... (${swipePath.size - 20} more points)")
            }
        }
        // android.util.Log.d("SwipeAnalysis", pathStr)

        // Log touched keys
        val keysStr = buildString {
            append("Touched keys: ")
            for (key in touchedKeys) {
                val kv = key.keys.getOrNull(0)
                if (kv != null && kv.getKind() == KeyValue.Kind.Char) {
                    append(kv.getChar()).append(" ")
                }
            }
        }
        // android.util.Log.d("SwipeAnalysis", keysStr)

        // Log velocity and gesture characteristics
        if (swipePath.size >= 2) {
            val avgVelocity = totalDistance / (System.currentTimeMillis() - startTime)
            // android.util.Log.d("SwipeAnalysis", "Average velocity: $avgVelocity px/ms")

            // Calculate straightness ratio
            val start = swipePath[0]
            val end = swipePath[swipePath.size - 1]
            val directDistance = sqrt(
                (end.x - start.x).pow(2) + (end.y - start.y).pow(2)
            )
            val straightnessRatio = directDistance / totalDistance
            // android.util.Log.d("SwipeAnalysis", "Straightness ratio: $straightnessRatio")
        }

        // android.util.Log.d("SwipeAnalysis", "================================")
    }

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
}
