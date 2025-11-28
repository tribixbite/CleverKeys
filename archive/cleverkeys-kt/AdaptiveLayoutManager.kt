package tribixbite.keyboard2

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface

/**
 * Manages adaptive keyboard layout adjustments based on device characteristics.
 *
 * Provides intelligent layout optimization that adapts to screen orientation, size,
 * resolution, and user typing patterns for optimal usability.
 *
 * Features:
 * - Automatic layout scaling for different screen sizes
 * - Orientation-aware layout adjustments (portrait/landscape)
 * - Screen density compensation
 * - Tablet vs phone layout optimization
 * - Custom scaling factors per device type
 * - Dynamic key size adjustment
 * - Aspect ratio awareness
 * - Multi-window mode detection
 *
 * Bug #335 - MEDIUM: Complete implementation of missing AdaptiveLayoutManager.java
 *
 * @param context Application context for accessing resources
 */
class AdaptiveLayoutManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "AdaptiveLayoutManager"

        // Device type thresholds (in dp)
        private const val PHONE_SMALL_WIDTH_DP = 320        // Small phone
        private const val PHONE_MEDIUM_WIDTH_DP = 360       // Medium phone
        private const val PHONE_LARGE_WIDTH_DP = 400        // Large phone/phablet
        private const val TABLET_SMALL_WIDTH_DP = 600       // Small tablet (7")
        private const val TABLET_LARGE_WIDTH_DP = 900       // Large tablet (10"+)

        // Scaling factors
        private const val SCALE_PHONE_SMALL = 0.85f         // Small phones - reduce size
        private const val SCALE_PHONE_MEDIUM = 1.0f         // Medium phones - default
        private const val SCALE_PHONE_LARGE = 1.05f         // Large phones - increase slightly
        private const val SCALE_TABLET_SMALL = 1.15f        // Small tablets - increase
        private const val SCALE_TABLET_LARGE = 1.25f        // Large tablets - increase more
        private const val SCALE_LANDSCAPE_FACTOR = 0.95f    // Landscape - reduce height slightly

        // Key size adjustments
        private const val MIN_KEY_SIZE_DP = 32f             // Minimum touchable size
        private const val MAX_KEY_SIZE_DP = 80f             // Maximum key size
        private const val DEFAULT_KEY_SIZE_DP = 48f         // Default key size

        // Aspect ratio thresholds
        private const val ASPECT_RATIO_NARROW = 1.5f        // 3:2 or narrower
        private const val ASPECT_RATIO_STANDARD = 1.78f     // 16:9
        private const val ASPECT_RATIO_WIDE = 2.1f          // 21:9 or wider
    }

    /**
     * Device type classification.
     */
    enum class DeviceType {
        PHONE_SMALL,        // Small phone (<360dp)
        PHONE_MEDIUM,       // Medium phone (360-400dp)
        PHONE_LARGE,        // Large phone/phablet (400-600dp)
        TABLET_SMALL,       // Small tablet (600-900dp)
        TABLET_LARGE        // Large tablet (>900dp)
    }

    /**
     * Screen orientation.
     */
    enum class Orientation {
        PORTRAIT,
        LANDSCAPE
    }

    /**
     * Layout configuration.
     */
    data class LayoutConfig(
        val deviceType: DeviceType,
        val orientation: Orientation,
        val scaleFactor: Float,
        val keySizeDp: Float,
        val aspectRatio: Float,
        val isMultiWindow: Boolean
    )

    /**
     * Callback interface for layout adaptation events.
     */
    interface Callback {
        /**
         * Called when layout configuration changes.
         *
         * @param config New layout configuration
         */
        fun onLayoutConfigChanged(config: LayoutConfig)

        /**
         * Called when device type is detected.
         *
         * @param deviceType Detected device type
         */
        fun onDeviceTypeDetected(deviceType: DeviceType)

        /**
         * Called when orientation changes.
         *
         * @param orientation New orientation
         */
        fun onOrientationChanged(orientation: Orientation)

        /**
         * Called when scale factor changes.
         *
         * @param scaleFactor New scale factor
         */
        fun onScaleFactorChanged(scaleFactor: Float)
    }

    // Current state
    private var currentConfig: LayoutConfig
    private var callback: Callback? = null
    private var customScaleFactor: Float? = null

    // Screen metrics
    private var screenWidthDp: Float = 0f
    private var screenHeightDp: Float = 0f
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var densityDpi: Int = 0
    private var density: Float = 0f

    init {
        logD("Initializing AdaptiveLayoutManager")
        updateScreenMetrics()
        currentConfig = calculateLayoutConfig()
        logD("Initial config: $currentConfig")
    }

    /**
     * Update screen metrics from resources.
     */
    private fun updateScreenMetrics() {
        val displayMetrics = context.resources.displayMetrics
        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels
        densityDpi = displayMetrics.densityDpi
        density = displayMetrics.density

        // Calculate dp dimensions
        screenWidthDp = screenWidthPx / density
        screenHeightDp = screenHeightPx / density

        logD("Screen: ${screenWidthPx}x${screenHeightPx}px, ${screenWidthDp}x${screenHeightDp}dp, ${densityDpi}dpi")
    }

    /**
     * Calculate complete layout configuration.
     */
    private fun calculateLayoutConfig(): LayoutConfig {
        val deviceType = detectDeviceType()
        val orientation = detectOrientation()
        val scaleFactor = calculateScaleFactor(deviceType, orientation)
        val keySizeDp = calculateKeySize(deviceType, scaleFactor)
        val aspectRatio = calculateAspectRatio()
        val isMultiWindow = detectMultiWindow()

        return LayoutConfig(
            deviceType = deviceType,
            orientation = orientation,
            scaleFactor = scaleFactor,
            keySizeDp = keySizeDp,
            aspectRatio = aspectRatio,
            isMultiWindow = isMultiWindow
        )
    }

    /**
     * Detect device type based on screen size.
     */
    private fun detectDeviceType(): DeviceType {
        val smallestWidth = minOf(screenWidthDp, screenHeightDp)

        return when {
            smallestWidth < PHONE_SMALL_WIDTH_DP -> DeviceType.PHONE_SMALL
            smallestWidth < PHONE_MEDIUM_WIDTH_DP -> DeviceType.PHONE_SMALL
            smallestWidth < PHONE_LARGE_WIDTH_DP -> DeviceType.PHONE_MEDIUM
            smallestWidth < TABLET_SMALL_WIDTH_DP -> DeviceType.PHONE_LARGE
            smallestWidth < TABLET_LARGE_WIDTH_DP -> DeviceType.TABLET_SMALL
            else -> DeviceType.TABLET_LARGE
        }
    }

    /**
     * Detect current screen orientation.
     */
    private fun detectOrientation(): Orientation {
        return if (screenWidthDp > screenHeightDp) {
            Orientation.LANDSCAPE
        } else {
            Orientation.PORTRAIT
        }
    }

    /**
     * Calculate appropriate scale factor for device and orientation.
     */
    private fun calculateScaleFactor(deviceType: DeviceType, orientation: Orientation): Float {
        // Use custom scale if set
        customScaleFactor?.let { return it }

        // Base scale factor by device type
        val baseScale = when (deviceType) {
            DeviceType.PHONE_SMALL -> SCALE_PHONE_SMALL
            DeviceType.PHONE_MEDIUM -> SCALE_PHONE_MEDIUM
            DeviceType.PHONE_LARGE -> SCALE_PHONE_LARGE
            DeviceType.TABLET_SMALL -> SCALE_TABLET_SMALL
            DeviceType.TABLET_LARGE -> SCALE_TABLET_LARGE
        }

        // Adjust for landscape orientation
        return if (orientation == Orientation.LANDSCAPE) {
            baseScale * SCALE_LANDSCAPE_FACTOR
        } else {
            baseScale
        }
    }

    /**
     * Calculate optimal key size for device and scale.
     */
    private fun calculateKeySize(deviceType: DeviceType, scaleFactor: Float): Float {
        val baseKeySize = when (deviceType) {
            DeviceType.PHONE_SMALL -> DEFAULT_KEY_SIZE_DP * 0.9f
            DeviceType.PHONE_MEDIUM -> DEFAULT_KEY_SIZE_DP
            DeviceType.PHONE_LARGE -> DEFAULT_KEY_SIZE_DP * 1.1f
            DeviceType.TABLET_SMALL -> DEFAULT_KEY_SIZE_DP * 1.2f
            DeviceType.TABLET_LARGE -> DEFAULT_KEY_SIZE_DP * 1.3f
        }

        val adjustedSize = baseKeySize * scaleFactor
        return adjustedSize.coerceIn(MIN_KEY_SIZE_DP, MAX_KEY_SIZE_DP)
    }

    /**
     * Calculate screen aspect ratio.
     */
    private fun calculateAspectRatio(): Float {
        val width = maxOf(screenWidthDp, screenHeightDp)
        val height = minOf(screenWidthDp, screenHeightDp)
        return width / height
    }

    /**
     * Detect if running in multi-window mode.
     */
    private fun detectMultiWindow(): Boolean {
        // Multi-window detection is approximate
        // In multi-window, available screen space is typically much smaller
        val availableWidthDp = screenWidthDp
        val availableHeightDp = screenHeightDp

        // If either dimension is unusually small, likely multi-window
        return availableWidthDp < 300 || availableHeightDp < 300
    }

    /**
     * Handle configuration change (e.g., rotation, multi-window).
     *
     * @param newConfig New configuration
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        val oldConfig = currentConfig

        updateScreenMetrics()
        currentConfig = calculateLayoutConfig()

        if (currentConfig != oldConfig) {
            logD("Configuration changed: $oldConfig → $currentConfig")

            callback?.onLayoutConfigChanged(currentConfig)

            if (currentConfig.deviceType != oldConfig.deviceType) {
                callback?.onDeviceTypeDetected(currentConfig.deviceType)
            }

            if (currentConfig.orientation != oldConfig.orientation) {
                callback?.onOrientationChanged(currentConfig.orientation)
            }

            if (currentConfig.scaleFactor != oldConfig.scaleFactor) {
                callback?.onScaleFactorChanged(currentConfig.scaleFactor)
            }
        }
    }

    /**
     * Set custom scale factor (overrides automatic scaling).
     *
     * @param scaleFactor Custom scale factor (0.5 to 2.0), or null for automatic
     */
    fun setCustomScaleFactor(scaleFactor: Float?) {
        customScaleFactor = scaleFactor?.coerceIn(0.5f, 2.0f)

        // Recalculate configuration with new scale
        currentConfig = calculateLayoutConfig()

        logD("Custom scale factor: $customScaleFactor")
        callback?.onScaleFactorChanged(currentConfig.scaleFactor)
        callback?.onLayoutConfigChanged(currentConfig)
    }

    /**
     * Get current layout configuration.
     *
     * @return Current configuration
     */
    fun getConfig(): LayoutConfig = currentConfig

    /**
     * Get current device type.
     *
     * @return Current device type
     */
    fun getDeviceType(): DeviceType = currentConfig.deviceType

    /**
     * Get current orientation.
     *
     * @return Current orientation
     */
    fun getOrientation(): Orientation = currentConfig.orientation

    /**
     * Get current scale factor.
     *
     * @return Current scale factor
     */
    fun getScaleFactor(): Float = currentConfig.scaleFactor

    /**
     * Get optimal key size in dp.
     *
     * @return Key size in dp
     */
    fun getKeySizeDp(): Float = currentConfig.keySizeDp

    /**
     * Get optimal key size in pixels.
     *
     * @return Key size in pixels
     */
    fun getKeySizePx(): Int = (currentConfig.keySizeDp * density).toInt()

    /**
     * Get screen aspect ratio.
     *
     * @return Aspect ratio
     */
    fun getAspectRatio(): Float = currentConfig.aspectRatio

    /**
     * Check if device is a tablet.
     *
     * @return true if tablet, false if phone
     */
    fun isTablet(): Boolean {
        return currentConfig.deviceType == DeviceType.TABLET_SMALL ||
               currentConfig.deviceType == DeviceType.TABLET_LARGE
    }

    /**
     * Check if currently in landscape orientation.
     *
     * @return true if landscape, false if portrait
     */
    fun isLandscape(): Boolean {
        return currentConfig.orientation == Orientation.LANDSCAPE
    }

    /**
     * Check if running in multi-window mode.
     *
     * @return true if multi-window, false otherwise
     */
    fun isMultiWindow(): Boolean {
        return currentConfig.isMultiWindow
    }

    /**
     * Check if screen has narrow aspect ratio (3:2 or narrower).
     *
     * @return true if narrow aspect ratio
     */
    fun isNarrowAspectRatio(): Boolean {
        return currentConfig.aspectRatio <= ASPECT_RATIO_NARROW
    }

    /**
     * Check if screen has wide aspect ratio (21:9 or wider).
     *
     * @return true if wide aspect ratio
     */
    fun isWideAspectRatio(): Boolean {
        return currentConfig.aspectRatio >= ASPECT_RATIO_WIDE
    }

    /**
     * Get recommended keyboard height as percentage of screen height.
     *
     * @return Height percentage (0.0 to 1.0)
     */
    fun getRecommendedHeightPercent(): Float {
        return when {
            isMultiWindow() -> 0.5f                          // Multi-window: 50%
            isLandscape() -> 0.4f                            // Landscape: 40%
            isTablet() -> 0.35f                              // Tablet portrait: 35%
            currentConfig.deviceType == DeviceType.PHONE_SMALL -> 0.45f  // Small phone: 45%
            else -> 0.4f                                     // Default: 40%
        }
    }

    /**
     * Get recommended number of rows for keyboard layout.
     *
     * @return Recommended number of rows
     */
    fun getRecommendedRows(): Int {
        return when {
            isMultiWindow() -> 4                             // Multi-window: 4 rows
            isLandscape() -> 4                               // Landscape: 4 rows
            isTablet() -> 5                                  // Tablet: 5 rows
            else -> 4                                        // Phone: 4 rows
        }
    }

    /**
     * Get screen width in dp.
     *
     * @return Width in dp
     */
    fun getScreenWidthDp(): Float = screenWidthDp

    /**
     * Get screen height in dp.
     *
     * @return Height in dp
     */
    fun getScreenHeightDp(): Float = screenHeightDp

    /**
     * Get screen width in pixels.
     *
     * @return Width in pixels
     */
    fun getScreenWidthPx(): Int = screenWidthPx

    /**
     * Get screen height in pixels.
     *
     * @return Height in pixels
     */
    fun getScreenHeightPx(): Int = screenHeightPx

    /**
     * Get screen density.
     *
     * @return Density factor
     */
    fun getDensity(): Float = density

    /**
     * Get screen density in DPI.
     *
     * @return Density in DPI
     */
    fun getDensityDpi(): Int = densityDpi

    /**
     * Convert dp to pixels.
     *
     * @param dp Value in dp
     * @return Value in pixels
     */
    fun dpToPx(dp: Float): Int = (dp * density).toInt()

    /**
     * Convert pixels to dp.
     *
     * @param px Value in pixels
     * @return Value in dp
     */
    fun pxToDp(px: Int): Float = px / density

    /**
     * Set callback for layout adaptation events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset custom scale factor and recalculate layout.
     */
    fun reset() {
        customScaleFactor = null
        currentConfig = calculateLayoutConfig()
        logD("Reset to automatic scaling")
        callback?.onLayoutConfigChanged(currentConfig)
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing AdaptiveLayoutManager resources...")

        try {
            callback = null
            logD("✅ AdaptiveLayoutManager resources released")
        } catch (e: Exception) {
            logE("Error releasing adaptive layout manager resources", e)
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
