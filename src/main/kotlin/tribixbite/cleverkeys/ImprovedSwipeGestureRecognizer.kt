package tribixbite.cleverkeys

import android.graphics.PointF
import java.util.ArrayList
import java.util.LinkedList
import java.util.Queue
import kotlin.collections.List // Ensure kotlin.collections.List is used
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Improved swipe gesture recognizer with better noise filtering.
 * Uses TrajectoryObjectPool to reduce GC pressure during high-frequency touch events.
 */
open class ImprovedSwipeGestureRecognizer {

    private val _rawPath: MutableList<PointF> = ArrayList()
    private val _smoothedPath: MutableList<PointF> = ArrayList()
    private val _touchedKeys: MutableList<KeyboardData.Key> = ArrayList()
    private val _timestamps: MutableList<Long> = ArrayList()
    private val _recentKeys: Queue<KeyboardData.Key> = LinkedList()
    private var _probabilisticDetector: ProbabilisticKeyDetector? = null
    private var _currentKeyboard: KeyboardData? = null
    
    private var _isSwipeTyping: Boolean = false
    private var _startTime: Long = 0
    private var _lastPointTime: Long = 0
    private var _totalDistance: Float = 0f
    private var _lastKey: KeyboardData.Key? = null
    private var _lastRegisteredKey: KeyboardData.Key? = null
    
    // Thresholds for improved filtering - non-configurable constants
    private val SMOOTHING_WINDOW = 3 // Points for moving average (optimal balance)
    private val DUPLICATE_CHECK_WINDOW = 5 // Check last 5 keys for duplicates
    private val MAX_POINT_INTERVAL_MS = 500L

    // Configurable thresholds from settings
    private val MIN_SWIPE_DISTANCE: Float
        get() = Config.globalConfig().swipe_min_distance
    private val MIN_KEY_DISTANCE: Float
        get() = Config.globalConfig().swipe_min_key_distance
    private val MIN_DWELL_TIME_MS: Long
        get() = Config.globalConfig().swipe_min_dwell_time
    private val NOISE_THRESHOLD: Float
        get() = Config.globalConfig().swipe_noise_threshold
    private val HIGH_VELOCITY_THRESHOLD: Float
        get() = Config.globalConfig().swipe_high_velocity_threshold

    // For velocity-based filtering
    private var _recentVelocity: Float = 0f
    
    /**
     * Set the current keyboard for probabilistic detection
     * @param marginLeft Left margin offset for touch coordinate mapping (screen-relative to keyboard-relative)
     */
    fun setKeyboard(keyboard: KeyboardData?, width: Float, height: Float, marginLeft: Float = 0f) {
        _currentKeyboard = keyboard
        if (keyboard != null) {
            _probabilisticDetector = ProbabilisticKeyDetector(keyboard, width, height, marginLeft)
        }
    }
    
    /**
     * Start tracking a new swipe gesture
     */
    fun startSwipe(x: Float, y: Float, key: KeyboardData.Key?) {
        reset()

        // Use object pool to reduce GC pressure
        val startPoint = TrajectoryObjectPool.obtainPointF(x, y)
        _rawPath.add(startPoint)
        _smoothedPath.add(startPoint)
        
        _startTime = System.currentTimeMillis()
        _lastPointTime = _startTime
        _timestamps.add(_startTime)
        
        // Only register starting key if it's alphabetic
        if (key != null && isValidAlphabeticKey(key)) {
            _touchedKeys.add(key)
            _lastKey = key
            _lastRegisteredKey = key
            _recentKeys.offer(key)
        }
        
        _totalDistance = 0f
        _recentVelocity = 0f
    }
    
    /**
     * Add a point to the current swipe path with improved filtering
     */
    fun addPoint(x: Float, y: Float, key: KeyboardData.Key?) {
        if (_rawPath.isEmpty())
            return
        
        val now = System.currentTimeMillis()
        val timeSinceLastPoint = now - _lastPointTime
        
        // Fix timestamp issues - ignore invalid time deltas
        if (timeSinceLastPoint <= 0 || timeSinceLastPoint > MAX_POINT_INTERVAL_MS) {
            return // Skip this point if timing is invalid
        }
        
        val lastRawPoint = _rawPath.last()
        val dx = x - lastRawPoint.x
        val dy = y - lastRawPoint.y
        val distance = sqrt(dx * dx + dy * dy)
        
        // Ignore tiny movements (noise)
        if (distance < NOISE_THRESHOLD) {
            return
        }
        
        // Calculate velocity
        _recentVelocity = (distance / timeSinceLastPoint) * 1000f // pixels per second

        // Add raw point (using object pool)
        _rawPath.add(TrajectoryObjectPool.obtainPointF(x, y))
        _timestamps.add(now)
        _lastPointTime = now
        _totalDistance += distance

        // Apply smoothing (also uses object pool)
        val smoothedPoint = applySmoothing(x, y)
        _smoothedPath.add(smoothedPoint)
        
        // Check if this should be considered swipe typing
        if (!_isSwipeTyping && _totalDistance > MIN_SWIPE_DISTANCE) {
            _isSwipeTyping = shouldConsiderSwipeTyping()
        }
        
        // Process key registration with improved filtering
        if (key != null && isValidAlphabeticKey(key)) {
            registerKeyWithFiltering(key, distance, timeSinceLastPoint)
        }
    }
    
    /**
     * Apply moving average smoothing to coordinates.
     * Uses object pool to avoid allocation on every touch event.
     */
    private fun applySmoothing(x: Float, y: Float): PointF {
        if (_rawPath.size < SMOOTHING_WINDOW) {
            return TrajectoryObjectPool.obtainPointF(x, y)
        }

        // Calculate moving average over last N points
        var avgX = 0f
        var avgY = 0f
        val startIdx = max(0, _rawPath.size - SMOOTHING_WINDOW)
        var count = 0

        for (i in startIdx until _rawPath.size) {
            val p = _rawPath[i]
            avgX += p.x
            avgY += p.y
            count++
        }

        return TrajectoryObjectPool.obtainPointF(avgX / count, avgY / count)
    }
    
    /**
     * Register key with improved filtering logic
     */
    private fun registerKeyWithFiltering(key: KeyboardData.Key, distance: Float, timeDelta: Long) {
        // Skip if same as last key
        if (key == _lastKey) {
            return
        }
        
        // Check dwell time - must be on key for minimum time
        if (timeDelta < MIN_DWELL_TIME_MS && _recentVelocity > HIGH_VELOCITY_THRESHOLD) {
            // Moving too fast, likely just passing through
            return
        }
        
        // Check if key is in recent history (avoid duplicates)
        if (isRecentDuplicate(key)) {
            return
        }
        
        // Check minimum distance from last registered key
        if (_lastRegisteredKey != null && distance < MIN_KEY_DISTANCE) {
            return
        }
        
        // Register the key
        _touchedKeys.add(key)
        _lastKey = key
        _lastRegisteredKey = key
        
        // Update recent keys queue
        _recentKeys.offer(key)
        if (_recentKeys.size > DUPLICATE_CHECK_WINDOW) {
            _recentKeys.poll()
        }
    }
    
    /**
     * Check if key is a recent duplicate
     */
    private fun isRecentDuplicate(key: KeyboardData.Key): Boolean {
        for (recentKey in _recentKeys) {
            if (recentKey == key) {
                return true
            }
        }
        return false
    }
    
    /**
     * End the swipe gesture and return the touched keys if it was swipe typing
     */
    fun endSwipe(): SwipeResult {
        // Apply endpoint stabilization
        stabilizeEndpoints()
        
        // Re-evaluate swipe typing status after stabilization
        // This handles cases where the swipe was too fast/short to trigger detection during movement,
        // but endpoint stabilization found a second valid key (e.g. "for", "not", "it")
        if (!_isSwipeTyping && _touchedKeys.size >= 2 && _totalDistance > MIN_SWIPE_DISTANCE) {
            _isSwipeTyping = shouldConsiderSwipeTyping()
        }
        
        if (_isSwipeTyping && _touchedKeys.size >= 2) {
            val finalKeys: MutableList<KeyboardData.Key>
            
            // Try probabilistic detection if available
            if (_probabilisticDetector != null && _smoothedPath.size > 5) {
                // Apply Ramer-Douglas-Peucker simplification first
                val simplifiedPath = ProbabilisticKeyDetector.simplifyPath(_smoothedPath, 15.0f)
                
                // Get probabilistic key detection
                val probabilisticKeys = _probabilisticDetector!!.detectKeys(simplifiedPath)
                
                // If probabilistic detection gives good results, use it
                if (probabilisticKeys != null && probabilisticKeys.size >= 2) {
                    finalKeys = probabilisticKeys.toMutableList()
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("SwipeRecognizer", "Using probabilistic keys: ${probabilisticKeys.size}")
                    }
                } else {
                    // Fall back to traditional detection
                    finalKeys = applyFinalFiltering(_touchedKeys)
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("SwipeRecognizer", "Using traditional keys: ${finalKeys.size}")
                    }
                }
            } else {
                // Use traditional detection
                finalKeys = applyFinalFiltering(_touchedKeys)
            }
            
            return SwipeResult(
                finalKeys,
                _smoothedPath.toList(), // Changed to .toList()
                _timestamps.toList(),   // Changed to .toList()
                _totalDistance,
                _isSwipeTyping
            )
        }
        
        return SwipeResult(null, null, null, 0f, false)
    }
    
    /**
     * Stabilize first and last keys using multiple points
     */
    private fun stabilizeEndpoints() {
        if (_smoothedPath.size < 10 || _touchedKeys.size < 2)
            return
        
        // Check first key stability
        val avgStart = calculateAveragePoint(_smoothedPath, 0, 5)
        val stableStartKey = findKeyAtPoint(avgStart)
        if (stableStartKey != null && isValidAlphabeticKey(stableStartKey)) {
            _touchedKeys[0] = stableStartKey
        }
        
        // Check last key stability
        val endIdx = _smoothedPath.size - 1
        val avgEnd = calculateAveragePoint(_smoothedPath, max(0, endIdx - 5), endIdx)
        val stableEndKey = findKeyAtPoint(avgEnd)
        if (stableEndKey != null && isValidAlphabeticKey(stableEndKey)) {
            _touchedKeys[_touchedKeys.size - 1] = stableEndKey
        }
    }
    
    /**
     * Calculate average point over a range.
     * Uses object pool to avoid allocation.
     */
    private fun calculateAveragePoint(points: List<PointF>, start: Int, end: Int): PointF {
        var sumX = 0f
        var sumY = 0f
        var count = 0

        for (i in start..min(end, points.size - 1)) {
            val p = points[i]
            sumX += p.x
            sumY += p.y
            count++
        }

        return TrajectoryObjectPool.obtainPointF(sumX / count, sumY / count)
    }
    
    /**
     * Find key at a given point (placeholder - needs keyboard layout)
     */
    private fun findKeyAtPoint(point: PointF): KeyboardData.Key? {
        // Use the probabilistic detector to find the key
        if (_probabilisticDetector != null) {
            return _probabilisticDetector!!.getKeyAt(point.x, point.y)
        }
        
        // Fallback if detector not available (shouldn't happen if setKeyboard called)
        return null
    }
    
    /**
     * Apply final filtering to remove obvious noise
     */
    private fun applyFinalFiltering(keys: List<KeyboardData.Key>): MutableList<KeyboardData.Key> {
        if (keys.size <= 3)
            return keys.toMutableList() // Ensure mutable list is returned for small sizes
        
        val filtered = mutableListOf<KeyboardData.Key>()
        
        // Always keep first key
        filtered.add(keys[0])
        
        // Filter middle keys - remove obvious zigzag patterns
        for (i in 1 until keys.size - 1) {
            val prev = keys[i - 1]
            val curr = keys[i]
            val next = keys[i + 1]
            
            // Skip if this creates a back-and-forth pattern
            if (prev != next || !isLikelyNoise(prev, curr, next)) {
                filtered.add(curr)
            }
        }
        
        // Always keep last key
        filtered.add(keys[keys.size - 1])
        
        return filtered
    }
    
    /**
     * Check if middle key is likely noise in a sequence
     */
    private fun isLikelyNoise(prev: KeyboardData.Key, curr: KeyboardData.Key, next: KeyboardData.Key): Boolean {
        // This would check keyboard layout to see if curr is between prev and next
        // For now, return false - actual implementation would use key positions
        return false
    }
    
    /**
     * Check if the current gesture should be considered swipe typing
     */
    private fun shouldConsiderSwipeTyping(): Boolean {
        // Add debug logging for swipe detection (only in debug builds for performance)
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("ImprovedSwipeGestureRecognizer", "🔍 SWIPE DETECTION CHECK:")
            android.util.Log.d("ImprovedSwipeGestureRecognizer", "- Keys touched: ${_touchedKeys.size}")
            android.util.Log.d("ImprovedSwipeGestureRecognizer", "- Total distance: ${_totalDistance} (need ${MIN_SWIPE_DISTANCE})")
        }

        // Need at least 2 alphabetic keys
        if (_touchedKeys.size < 2) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ImprovedSwipeGestureRecognizer", "❌ Too few keys: ${_touchedKeys.size} < 2")
            }
            return false
        }

        // Check total distance
        if (_totalDistance < MIN_SWIPE_DISTANCE) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("ImprovedSwipeGestureRecognizer", "❌ Distance too short: ${_totalDistance} < ${MIN_SWIPE_DISTANCE}")
            }
            return false
        }

        // Check if all touched keys are alphabetic
        for (key in _touchedKeys) {
            if (!isValidAlphabeticKey(key)) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d("ImprovedSwipeGestureRecognizer", "❌ Non-alphabetic key touched")
                }
                return false
            }
        }

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("ImprovedSwipeGestureRecognizer", "✅ SWIPE DETECTED - proceeding with swipe typing")
        }
        return true
    }
    
    /**
     * Check if a key is a valid alphabetic key
     */
    private fun isValidAlphabeticKey(key: KeyboardData.Key): Boolean {
        val kv = key.keys.getOrNull(0) ?: return false
        
        if (kv.getKind() != KeyValue.Kind.Char)
            return false
        
        val c = kv.getChar()
        return (c in 'a'..'z') || (c in 'A'..'Z')
    }
    
    /**
     * Get the current swipe path
     */
    fun getSwipePath(): List<PointF> {
        return _smoothedPath.toList()
    }
    
    /**
     * Get the timestamps
     */
    fun getTimestamps(): List<Long> {
        return _timestamps.toList()
    }
    
    /**
     * Check if currently swipe typing
     */
    fun isSwipeTyping(): Boolean {
        return _isSwipeTyping
    }
    
    /**
     * Reset the recognizer for a new gesture.
     * Note: We don't recycle PointF objects here because they may still be referenced
     * elsewhere (e.g., in SwipeResult returned by endSwipe()). The pool will naturally
     * reuse objects on subsequent swipes.
     */
    fun reset() {
        _rawPath.clear()
        _smoothedPath.clear()
        _touchedKeys.clear()
        _timestamps.clear()
        _recentKeys.clear()
        _isSwipeTyping = false
        _lastKey = null
        _lastRegisteredKey = null
        _totalDistance = 0f
        _recentVelocity = 0f
    }
}
