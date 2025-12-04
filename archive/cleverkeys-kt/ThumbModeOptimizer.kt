package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Optimizes keyboard layout for thumb typing on large devices.
 *
 * Provides ergonomic keyboard adaptations for one-handed and two-handed
 * thumb typing with curved key layouts and reach-zone optimization.
 *
 * Features:
 * - Thumb reach zone calculation based on screen size
 * - One-handed mode (left/right thumb optimization)
 * - Two-handed mode (dual thumb zones)
 * - Curved/arc keyboard layout adaptation
 * - Dynamic key positioning for ergonomic reach
 * - Key size adjustments for better accessibility
 * - Screen size and orientation detection
 * - Thumb fatigue reduction optimization
 *
 * Bug #359 - CATASTROPHIC: Implement missing ThumbModeOptimizer for ergonomic typing
 *
 * @param context Application context
 */
class ThumbModeOptimizer(
    private val context: Context
) {
    companion object {
        private const val TAG = "ThumbModeOptimizer"

        /**
         * Thumb mode types.
         */
        enum class ThumbMode {
            DISABLED,           // Normal keyboard layout
            ONE_HANDED_LEFT,    // Optimized for left thumb
            ONE_HANDED_RIGHT,   // Optimized for right thumb
            TWO_HANDED          // Optimized for both thumbs
        }

        /**
         * Device size categories.
         */
        enum class DeviceSize {
            SMALL,      // < 5 inches (phones)
            MEDIUM,     // 5-7 inches (large phones)
            LARGE,      // > 7 inches (tablets)
            XLARGE      // > 10 inches (large tablets)
        }

        /**
         * Thumb reach optimization result.
         */
        data class ThumbReach(
            val comfortableRadius: Float,     // Pixels within comfortable reach
            val maximumRadius: Float,         // Pixels at maximum stretch
            val optimalCenter: PointF,        // Optimal thumb pivot point
            val reachableZone: RectF          // Bounding box of reachable area
        )

        /**
         * Key position adjustment.
         */
        data class KeyAdjustment(
            val originalPosition: PointF,
            val adjustedPosition: PointF,
            val scale: Float,                  // Size multiplier (0.5-1.5)
            val reachabilityScore: Float       // 0-1 (1 = most reachable)
        )

        /**
         * Thumb mode configuration.
         */
        data class ThumbModeConfig(
            val mode: ThumbMode,
            val curvatureStrength: Float,      // 0-1 (arc intensity)
            val keyboardWidth: Float,          // Percentage of screen (0-1)
            val horizontalOffset: Float,       // Percentage shift (-0.5 to 0.5)
            val enableKeyScaling: Boolean,     // Scale keys by reachability
            val minKeyScale: Float = 0.7f,     // Minimum key scale
            val maxKeyScale: Float = 1.3f      // Maximum key scale
        )

        // Thumb reach constants (in inches)
        private const val COMFORTABLE_THUMB_REACH_INCHES = 2.5f
        private const val MAXIMUM_THUMB_REACH_INCHES = 3.5f

        // Device size thresholds (in inches, diagonal)
        private const val MEDIUM_DEVICE_THRESHOLD = 5.0f
        private const val LARGE_DEVICE_THRESHOLD = 7.0f
        private const val XLARGE_DEVICE_THRESHOLD = 10.0f

        // Default configuration values
        private const val DEFAULT_CURVATURE = 0.3f
        private const val DEFAULT_ONE_HANDED_WIDTH = 0.75f
        private const val DEFAULT_TWO_HANDED_WIDTH = 1.0f
    }

    /**
     * Callback interface for thumb mode events.
     */
    interface Callback {
        /**
         * Called when thumb mode changes.
         */
        fun onThumbModeChanged(mode: ThumbMode)

        /**
         * Called when key positions should be updated.
         */
        fun onKeyPositionsUpdated(adjustments: List<KeyAdjustment>)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _currentMode = MutableStateFlow(ThumbMode.DISABLED)
    val currentMode: StateFlow<ThumbMode> = _currentMode.asStateFlow()

    private val _currentConfig = MutableStateFlow(getDefaultConfig(ThumbMode.DISABLED))
    val currentConfig: StateFlow<ThumbModeConfig> = _currentConfig.asStateFlow()

    private var callback: Callback? = null

    // Screen metrics
    private val displayMetrics: DisplayMetrics
    private val windowManager: WindowManager

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = context.resources.displayMetrics

        logD("ThumbModeOptimizer initialized - Device: ${getDeviceSize()}, Screen: ${getScreenSizeInches()}\"")
    }

    /**
     * Set thumb mode.
     *
     * @param mode Thumb mode to activate
     * @param config Optional custom configuration
     */
    fun setThumbMode(mode: ThumbMode, config: ThumbModeConfig? = null) {
        val newConfig = config ?: getDefaultConfig(mode)

        _currentMode.value = mode
        _currentConfig.value = newConfig

        callback?.onThumbModeChanged(mode)

        logD("Thumb mode changed to: $mode")
    }

    /**
     * Get default configuration for mode.
     *
     * @param mode Thumb mode
     * @return Default configuration
     */
    private fun getDefaultConfig(mode: ThumbMode): ThumbModeConfig {
        return when (mode) {
            ThumbMode.DISABLED -> ThumbModeConfig(
                mode = mode,
                curvatureStrength = 0f,
                keyboardWidth = 1.0f,
                horizontalOffset = 0f,
                enableKeyScaling = false
            )

            ThumbMode.ONE_HANDED_LEFT -> ThumbModeConfig(
                mode = mode,
                curvatureStrength = DEFAULT_CURVATURE,
                keyboardWidth = DEFAULT_ONE_HANDED_WIDTH,
                horizontalOffset = -0.125f,  // Shift left
                enableKeyScaling = true
            )

            ThumbMode.ONE_HANDED_RIGHT -> ThumbModeConfig(
                mode = mode,
                curvatureStrength = DEFAULT_CURVATURE,
                keyboardWidth = DEFAULT_ONE_HANDED_WIDTH,
                horizontalOffset = 0.125f,   // Shift right
                enableKeyScaling = true
            )

            ThumbMode.TWO_HANDED -> ThumbModeConfig(
                mode = mode,
                curvatureStrength = DEFAULT_CURVATURE * 0.5f,
                keyboardWidth = DEFAULT_TWO_HANDED_WIDTH,
                horizontalOffset = 0f,
                enableKeyScaling = true
            )
        }
    }

    /**
     * Calculate thumb reach zones.
     *
     * @param mode Thumb mode
     * @return Thumb reach information
     */
    fun calculateThumbReach(mode: ThumbMode): ThumbReach {
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Convert reach distances from inches to pixels
        val comfortableRadius = COMFORTABLE_THUMB_REACH_INCHES * displayMetrics.xdpi
        val maximumRadius = MAXIMUM_THUMB_REACH_INCHES * displayMetrics.xdpi

        // Calculate optimal thumb pivot point based on mode
        val optimalCenter = when (mode) {
            ThumbMode.ONE_HANDED_LEFT -> PointF(
                screenWidth * 0.15f,    // Near bottom-left
                screenHeight * 0.85f
            )

            ThumbMode.ONE_HANDED_RIGHT -> PointF(
                screenWidth * 0.85f,    // Near bottom-right
                screenHeight * 0.85f
            )

            ThumbMode.TWO_HANDED -> PointF(
                screenWidth * 0.5f,     // Center-bottom
                screenHeight * 0.9f
            )

            ThumbMode.DISABLED -> PointF(
                screenWidth * 0.5f,
                screenHeight * 0.5f
            )
        }

        // Calculate reachable zone
        val reachableZone = RectF(
            (optimalCenter.x - maximumRadius).coerceAtLeast(0f),
            (optimalCenter.y - maximumRadius).coerceAtLeast(0f),
            (optimalCenter.x + maximumRadius).coerceAtMost(screenWidth),
            (optimalCenter.y + maximumRadius).coerceAtMost(screenHeight)
        )

        return ThumbReach(
            comfortableRadius = comfortableRadius,
            maximumRadius = maximumRadius,
            optimalCenter = optimalCenter,
            reachableZone = reachableZone
        )
    }

    /**
     * Calculate key position adjustments for thumb mode.
     *
     * @param keyPositions Original key positions
     * @param config Thumb mode configuration
     * @return Adjusted key positions
     */
    suspend fun calculateKeyAdjustments(
        keyPositions: List<PointF>,
        config: ThumbModeConfig = _currentConfig.value
    ): List<KeyAdjustment> = withContext(Dispatchers.Default) {
        if (config.mode == ThumbMode.DISABLED) {
            // No adjustments needed
            return@withContext keyPositions.map { pos ->
                KeyAdjustment(
                    originalPosition = pos,
                    adjustedPosition = pos,
                    scale = 1.0f,
                    reachabilityScore = 1.0f
                )
            }
        }

        val thumbReach = calculateThumbReach(config.mode)
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        keyPositions.map { originalPos ->
            // Apply horizontal offset
            val offsetX = screenWidth * config.horizontalOffset
            var adjustedPos = PointF(originalPos.x + offsetX, originalPos.y)

            // Apply curvature (arc effect)
            if (config.curvatureStrength > 0f) {
                adjustedPos = applyCurvature(
                    position = adjustedPos,
                    center = thumbReach.optimalCenter,
                    strength = config.curvatureStrength,
                    screenHeight = screenHeight
                )
            }

            // Calculate reachability score
            val distance = distance(adjustedPos, thumbReach.optimalCenter)
            val reachabilityScore = calculateReachabilityScore(
                distance = distance,
                comfortableRadius = thumbReach.comfortableRadius,
                maximumRadius = thumbReach.maximumRadius
            )

            // Calculate key scale based on reachability
            val scale = if (config.enableKeyScaling) {
                // More reachable keys are larger
                config.minKeyScale + (reachabilityScore * (config.maxKeyScale - config.minKeyScale))
            } else {
                1.0f
            }

            KeyAdjustment(
                originalPosition = originalPos,
                adjustedPosition = adjustedPos,
                scale = scale,
                reachabilityScore = reachabilityScore
            )
        }
    }

    /**
     * Apply curvature to key position.
     *
     * @param position Original position
     * @param center Curvature center point
     * @param strength Curvature strength (0-1)
     * @param screenHeight Screen height in pixels
     * @return Curved position
     */
    private fun applyCurvature(
        position: PointF,
        center: PointF,
        strength: Float,
        screenHeight: Float
    ): PointF {
        // Calculate distance from center
        val dx = position.x - center.x
        val dy = position.y - center.y
        val distance = sqrt(dx * dx + dy * dy)

        if (distance == 0f) return position

        // Calculate angle from center
        val angle = atan2(dy, dx)

        // Apply arc curvature (pull towards center proportionally)
        val curvatureFactor = 1.0f - (strength * 0.3f * (dy / screenHeight))
        val curvedDistance = distance * curvatureFactor

        // Calculate new position
        val newX = center.x + curvedDistance * cos(angle)
        val newY = center.y + curvedDistance * sin(angle)

        return PointF(newX, newY)
    }

    /**
     * Calculate reachability score.
     *
     * @param distance Distance from thumb center
     * @param comfortableRadius Comfortable reach radius
     * @param maximumRadius Maximum reach radius
     * @return Score from 0-1 (1 = most reachable)
     */
    private fun calculateReachabilityScore(
        distance: Float,
        comfortableRadius: Float,
        maximumRadius: Float
    ): Float {
        return when {
            distance <= comfortableRadius -> 1.0f
            distance >= maximumRadius -> 0.0f
            else -> {
                // Linear interpolation between comfortable and maximum
                val range = maximumRadius - comfortableRadius
                val distanceFromComfortable = distance - comfortableRadius
                1.0f - (distanceFromComfortable / range)
            }
        }
    }

    /**
     * Calculate distance between two points.
     */
    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Get device size category.
     *
     * @return Device size
     */
    fun getDeviceSize(): DeviceSize {
        val screenInches = getScreenSizeInches()

        return when {
            screenInches >= XLARGE_DEVICE_THRESHOLD -> DeviceSize.XLARGE
            screenInches >= LARGE_DEVICE_THRESHOLD -> DeviceSize.LARGE
            screenInches >= MEDIUM_DEVICE_THRESHOLD -> DeviceSize.MEDIUM
            else -> DeviceSize.SMALL
        }
    }

    /**
     * Get screen size in inches (diagonal).
     *
     * @return Screen size in inches
     */
    fun getScreenSizeInches(): Float {
        val widthInches = displayMetrics.widthPixels / displayMetrics.xdpi
        val heightInches = displayMetrics.heightPixels / displayMetrics.ydpi
        return sqrt(widthInches * widthInches + heightInches * heightInches)
    }

    /**
     * Check if device is in landscape orientation.
     *
     * @return True if landscape
     */
    fun isLandscape(): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Check if thumb mode is recommended for current device.
     *
     * @return True if recommended
     */
    fun isThumbModeRecommended(): Boolean {
        val deviceSize = getDeviceSize()
        val isLandscape = isLandscape()

        // Recommend thumb mode for medium+ devices in portrait
        // or large+ devices in landscape
        return when {
            !isLandscape && deviceSize >= DeviceSize.MEDIUM -> true
            isLandscape && deviceSize >= DeviceSize.LARGE -> true
            else -> false
        }
    }

    /**
     * Get recommended thumb mode for current device/orientation.
     *
     * @return Recommended thumb mode
     */
    fun getRecommendedThumbMode(): ThumbMode {
        if (!isThumbModeRecommended()) {
            return ThumbMode.DISABLED
        }

        val deviceSize = getDeviceSize()
        val isLandscape = isLandscape()

        // Large tablets: two-handed in landscape, one-handed options in portrait
        // Medium phones: one-handed options preferred
        return when {
            deviceSize >= DeviceSize.LARGE && isLandscape -> ThumbMode.TWO_HANDED
            deviceSize >= DeviceSize.LARGE -> ThumbMode.ONE_HANDED_RIGHT  // User preference
            deviceSize == DeviceSize.MEDIUM -> ThumbMode.ONE_HANDED_RIGHT  // User preference
            else -> ThumbMode.DISABLED
        }
    }

    /**
     * Set callback for thumb mode events.
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Update key positions with current thumb mode.
     *
     * @param keyPositions Original key positions
     */
    suspend fun updateKeyPositions(keyPositions: List<PointF>) {
        val adjustments = calculateKeyAdjustments(keyPositions, _currentConfig.value)
        callback?.onKeyPositionsUpdated(adjustments)
    }

    /**
     * Get thumb mode statistics.
     *
     * @return Map of statistics
     */
    fun getStatistics(): Map<String, Any> {
        val thumbReach = calculateThumbReach(_currentMode.value)

        return mapOf(
            "currentMode" to _currentMode.value.name,
            "deviceSize" to getDeviceSize().name,
            "screenSizeInches" to getScreenSizeInches(),
            "isLandscape" to isLandscape(),
            "isRecommended" to isThumbModeRecommended(),
            "comfortableReachPixels" to thumbReach.comfortableRadius,
            "maximumReachPixels" to thumbReach.maximumRadius,
            "optimalThumbCenter" to "${thumbReach.optimalCenter.x},${thumbReach.optimalCenter.y}",
            "curvatureStrength" to _currentConfig.value.curvatureStrength,
            "keyboardWidth" to _currentConfig.value.keyboardWidth,
            "keyScalingEnabled" to _currentConfig.value.enableKeyScaling
        )
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing ThumbModeOptimizer resources...")

        try {
            scope.cancel()
            callback = null

            logD("✅ ThumbModeOptimizer resources released")
        } catch (e: Exception) {
            logE("Error releasing thumb mode optimizer resources", e)
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
