package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Manages split keyboard mode where keyboard is divided into left and right sections.
 *
 * Provides a split keyboard layout optimized for thumb typing on tablets and large phones,
 * with configurable gap width and independent section positioning.
 *
 * Features:
 * - Split keyboard into left and right sections
 * - Configurable gap width between sections
 * - Independent section sizing and positioning
 * - Smooth split/merge transitions
 * - Keyboard height adjustment in split mode
 * - Position memory for each mode
 * - Tablet and large-screen optimization
 *
 * Bug #333 - MEDIUM: Complete implementation of missing SplitKeyboardManager.java
 *
 * @param context Application context for accessing resources
 * @param enabled Initial enabled state (default: false - unified keyboard)
 */
class SplitKeyboardManager(
    private val context: Context,
    private var enabled: Boolean = false
) {
    companion object {
        private const val TAG = "SplitKeyboardManager"

        // Default split configuration
        private const val DEFAULT_GAP_PERCENT = 0.15f        // 15% of screen width
        private const val MIN_GAP_PERCENT = 0.05f            // Minimum 5%
        private const val MAX_GAP_PERCENT = 0.40f            // Maximum 40%

        // Section width (each section)
        private const val DEFAULT_SECTION_WIDTH_PERCENT = 0.40f  // 40% of screen width each
        private const val MIN_SECTION_WIDTH_PERCENT = 0.30f      // Minimum 30%
        private const val MAX_SECTION_WIDTH_PERCENT = 0.48f      // Maximum 48%

        // Vertical positioning
        private const val DEFAULT_VERTICAL_OFFSET_DP = 100f  // Distance from bottom
        private const val MIN_VERTICAL_OFFSET_DP = 0f
        private const val MAX_VERTICAL_OFFSET_DP = 300f

        // Animation duration
        private const val SPLIT_ANIMATION_DURATION_MS = 300L
        private const val MERGE_ANIMATION_DURATION_MS = 300L

        // Minimum screen width to enable split mode (inches)
        private const val MIN_SCREEN_WIDTH_INCHES = 7.0f     // Typical small tablet
    }

    /**
     * Split keyboard configuration.
     */
    data class SplitConfig(
        val gapPercent: Float = DEFAULT_GAP_PERCENT,
        val sectionWidthPercent: Float = DEFAULT_SECTION_WIDTH_PERCENT,
        val verticalOffsetDp: Float = DEFAULT_VERTICAL_OFFSET_DP
    )

    /**
     * Section identifier.
     */
    enum class Section {
        LEFT,
        RIGHT,
        UNIFIED  // Non-split mode
    }

    /**
     * Callback interface for split mode events.
     */
    interface Callback {
        /**
         * Called when split mode is enabled.
         */
        fun onSplitEnabled()

        /**
         * Called when split mode is disabled (merged back to unified).
         */
        fun onSplitDisabled()

        /**
         * Called when gap width changes.
         *
         * @param gapPercent New gap as percentage of screen width
         */
        fun onGapChanged(gapPercent: Float)

        /**
         * Called when section width changes.
         *
         * @param sectionWidthPercent New section width as percentage of screen width
         */
        fun onSectionWidthChanged(sectionWidthPercent: Float)

        /**
         * Called when vertical offset changes.
         *
         * @param offsetDp New vertical offset in dp
         */
        fun onVerticalOffsetChanged(offsetDp: Float)
    }

    // Current state
    private var config: SplitConfig = SplitConfig()
    private var callback: Callback? = null

    // Screen dimensions
    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var screenWidthInches: Float = 0f

    init {
        logD("Initializing SplitKeyboardManager (enabled=$enabled)")
        updateScreenDimensions()
        checkScreenSizeCompatibility()
    }

    /**
     * Update screen dimensions from resources.
     */
    private fun updateScreenDimensions() {
        val displayMetrics = context.resources.displayMetrics
        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels

        // Calculate screen width in inches
        val widthDp = screenWidthPx / displayMetrics.density
        screenWidthInches = widthDp / 160f

        logD("Screen: ${screenWidthPx}x${screenHeightPx}px, ${screenWidthInches}in wide")
    }

    /**
     * Check if screen is large enough for split keyboard.
     */
    private fun checkScreenSizeCompatibility() {
        if (screenWidthInches < MIN_SCREEN_WIDTH_INCHES) {
            logW("Screen width (${screenWidthInches}in) is below recommended minimum (${MIN_SCREEN_WIDTH_INCHES}in) for split keyboard")
        }
    }

    /**
     * Check if device screen is large enough for comfortable split keyboard use.
     *
     * @return true if screen is tablet-sized, false otherwise
     */
    fun isScreenSizeCompatible(): Boolean {
        updateScreenDimensions()
        return screenWidthInches >= MIN_SCREEN_WIDTH_INCHES
    }

    /**
     * Enable split keyboard mode.
     *
     * @param callback Optional callback for mode change events
     */
    fun enable(callback: Callback? = null) {
        this.callback = callback
        this.enabled = true

        logD("Split keyboard mode enabled (gap=${config.gapPercent * 100}%, section=${config.sectionWidthPercent * 100}%)")
        callback?.onSplitEnabled()
    }

    /**
     * Disable split mode and return to unified keyboard.
     */
    fun disable() {
        if (!enabled) {
            return
        }

        this.enabled = false

        logD("Split keyboard mode disabled (merged to unified)")
        callback?.onSplitDisabled()
    }

    /**
     * Toggle split mode on/off.
     *
     * @param callback Optional callback for mode change events
     */
    fun toggle(callback: Callback? = null) {
        if (enabled) {
            disable()
        } else {
            enable(callback)
        }
    }

    /**
     * Set gap width between keyboard sections.
     *
     * @param gapPercent Gap as percentage of screen width (0.05 to 0.40)
     */
    fun setGap(gapPercent: Float) {
        val clampedGap = gapPercent.coerceIn(MIN_GAP_PERCENT, MAX_GAP_PERCENT)

        if (config.gapPercent == clampedGap) {
            return
        }

        config = config.copy(gapPercent = clampedGap)
        logD("Gap changed to: ${config.gapPercent * 100}%")
        callback?.onGapChanged(config.gapPercent)
    }

    /**
     * Set width of each keyboard section.
     *
     * @param sectionWidthPercent Section width as percentage of screen width (0.30 to 0.48)
     */
    fun setSectionWidth(sectionWidthPercent: Float) {
        val clampedWidth = sectionWidthPercent.coerceIn(MIN_SECTION_WIDTH_PERCENT, MAX_SECTION_WIDTH_PERCENT)

        if (config.sectionWidthPercent == clampedWidth) {
            return
        }

        config = config.copy(sectionWidthPercent = clampedWidth)
        logD("Section width changed to: ${config.sectionWidthPercent * 100}%")
        callback?.onSectionWidthChanged(config.sectionWidthPercent)
    }

    /**
     * Set vertical offset from bottom of screen.
     *
     * @param offsetDp Offset in density-independent pixels (0 to 300dp)
     */
    fun setVerticalOffset(offsetDp: Float) {
        val clampedOffset = offsetDp.coerceIn(MIN_VERTICAL_OFFSET_DP, MAX_VERTICAL_OFFSET_DP)

        if (config.verticalOffsetDp == clampedOffset) {
            return
        }

        config = config.copy(verticalOffsetDp = clampedOffset)
        logD("Vertical offset changed to: ${config.verticalOffsetDp}dp")
        callback?.onVerticalOffsetChanged(config.verticalOffsetDp)
    }

    /**
     * Increase gap width by 5%.
     */
    fun increaseGap() {
        setGap(config.gapPercent + 0.05f)
    }

    /**
     * Decrease gap width by 5%.
     */
    fun decreaseGap() {
        setGap(config.gapPercent - 0.05f)
    }

    /**
     * Increase section width by 5%.
     */
    fun increaseSectionWidth() {
        setSectionWidth(config.sectionWidthPercent + 0.05f)
    }

    /**
     * Decrease section width by 5%.
     */
    fun decreaseSectionWidth() {
        setSectionWidth(config.sectionWidthPercent - 0.05f)
    }

    /**
     * Calculate bounds for a keyboard section in split mode.
     *
     * @param section Which section (LEFT or RIGHT)
     * @param keyboardHeight Height of keyboard in pixels
     * @return Rect defining section bounds (left, top, right, bottom)
     */
    fun calculateSectionBounds(section: Section, keyboardHeight: Int): Rect {
        updateScreenDimensions()

        if (!enabled || section == Section.UNIFIED) {
            // Unified mode - full width
            return Rect(0, screenHeightPx - keyboardHeight, screenWidthPx, screenHeightPx)
        }

        val sectionWidthPx = (screenWidthPx * config.sectionWidthPercent).toInt()
        val gapWidthPx = (screenWidthPx * config.gapPercent).toInt()
        val density = context.resources.displayMetrics.density
        val verticalOffsetPx = (config.verticalOffsetDp * density).toInt()

        val bottom = screenHeightPx - verticalOffsetPx
        val top = bottom - keyboardHeight

        return when (section) {
            Section.LEFT -> {
                // Left section - aligned to left edge
                val left = 0
                val right = sectionWidthPx
                Rect(left, top, right, bottom)
            }
            Section.RIGHT -> {
                // Right section - aligned to right edge
                val right = screenWidthPx
                val left = screenWidthPx - sectionWidthPx
                Rect(left, top, right, bottom)
            }
            Section.UNIFIED -> {
                // Should not reach here
                Rect(0, top, screenWidthPx, bottom)
            }
        }
    }

    /**
     * Calculate the gap bounds (empty space between sections).
     *
     * @param keyboardHeight Height of keyboard in pixels
     * @return Rect defining gap bounds
     */
    fun calculateGapBounds(keyboardHeight: Int): Rect {
        updateScreenDimensions()

        if (!enabled) {
            // No gap in unified mode
            return Rect(0, 0, 0, 0)
        }

        val sectionWidthPx = (screenWidthPx * config.sectionWidthPercent).toInt()
        val density = context.resources.displayMetrics.density
        val verticalOffsetPx = (config.verticalOffsetDp * density).toInt()

        val bottom = screenHeightPx - verticalOffsetPx
        val top = bottom - keyboardHeight
        val left = sectionWidthPx
        val right = screenWidthPx - sectionWidthPx

        return Rect(left, top, right, bottom)
    }

    /**
     * Apply split mode layout to keyboard sections.
     *
     * @param leftView The left section view
     * @param rightView The right section view
     * @return Pair of layout parameters (left, right)
     */
    fun applyLayout(leftView: View, rightView: View): Pair<ViewGroup.LayoutParams, ViewGroup.LayoutParams> {
        updateScreenDimensions()

        val leftParams = leftView.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val rightParams = rightView.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        if (!enabled) {
            // Unified mode - hide right section, expand left to full width
            leftParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            rightParams.width = 0
            rightView.visibility = View.GONE
            leftView.visibility = View.VISIBLE
            return Pair(leftParams, rightParams)
        }

        // Split mode - both sections visible
        val sectionWidthPx = (screenWidthPx * config.sectionWidthPercent).toInt()
        leftParams.width = sectionWidthPx
        rightParams.width = sectionWidthPx

        leftView.visibility = View.VISIBLE
        rightView.visibility = View.VISIBLE

        // Apply margins for positioning
        if (leftParams is ViewGroup.MarginLayoutParams && rightParams is ViewGroup.MarginLayoutParams) {
            val density = context.resources.displayMetrics.density
            val verticalMarginPx = (config.verticalOffsetDp * density).toInt()
            val gapWidthPx = (screenWidthPx * config.gapPercent).toInt()

            // Left section - align to left
            leftParams.leftMargin = 0
            leftParams.rightMargin = gapWidthPx + sectionWidthPx
            leftParams.bottomMargin = verticalMarginPx

            // Right section - align to right
            rightParams.leftMargin = sectionWidthPx + gapWidthPx
            rightParams.rightMargin = 0
            rightParams.bottomMargin = verticalMarginPx
        }

        return Pair(leftParams, rightParams)
    }

    /**
     * Check if a touch point is in a specific section.
     *
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @param section Section to check
     * @param keyboardHeight Height of keyboard
     * @return true if touch is in the section
     */
    fun isTouchInSection(x: Float, y: Float, section: Section, keyboardHeight: Int): Boolean {
        val bounds = calculateSectionBounds(section, keyboardHeight)
        return x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
    }

    /**
     * Check if a touch point is in the gap between sections.
     *
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @param keyboardHeight Height of keyboard
     * @return true if touch is in the gap
     */
    fun isTouchInGap(x: Float, y: Float, keyboardHeight: Int): Boolean {
        if (!enabled) {
            return false
        }

        val gapBounds = calculateGapBounds(keyboardHeight)
        return x >= gapBounds.left && x <= gapBounds.right && y >= gapBounds.top && y <= gapBounds.bottom
    }

    /**
     * Get which section a touch point is in.
     *
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @param keyboardHeight Height of keyboard
     * @return Section the touch is in, or null if not in keyboard
     */
    fun getSectionForTouch(x: Float, y: Float, keyboardHeight: Int): Section? {
        if (!enabled) {
            return Section.UNIFIED
        }

        return when {
            isTouchInSection(x, y, Section.LEFT, keyboardHeight) -> Section.LEFT
            isTouchInSection(x, y, Section.RIGHT, keyboardHeight) -> Section.RIGHT
            isTouchInGap(x, y, keyboardHeight) -> null
            else -> null
        }
    }

    /**
     * Get current split configuration.
     *
     * @return Current SplitConfig
     */
    fun getConfig(): SplitConfig = config

    /**
     * Set complete split configuration.
     *
     * @param config New configuration
     */
    fun setConfig(config: SplitConfig) {
        this.config = config.copy(
            gapPercent = config.gapPercent.coerceIn(MIN_GAP_PERCENT, MAX_GAP_PERCENT),
            sectionWidthPercent = config.sectionWidthPercent.coerceIn(MIN_SECTION_WIDTH_PERCENT, MAX_SECTION_WIDTH_PERCENT),
            verticalOffsetDp = config.verticalOffsetDp.coerceIn(MIN_VERTICAL_OFFSET_DP, MAX_VERTICAL_OFFSET_DP)
        )
        logD("Configuration updated: gap=${this.config.gapPercent * 100}%, section=${this.config.sectionWidthPercent * 100}%, offset=${this.config.verticalOffsetDp}dp")
    }

    /**
     * Check if split mode is currently enabled.
     *
     * @return true if enabled, false otherwise
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Get gap width in pixels.
     *
     * @return Gap width in pixels
     */
    fun getGapWidthPx(): Int {
        updateScreenDimensions()
        return (screenWidthPx * config.gapPercent).toInt()
    }

    /**
     * Get section width in pixels.
     *
     * @return Section width in pixels
     */
    fun getSectionWidthPx(): Int {
        updateScreenDimensions()
        return (screenWidthPx * config.sectionWidthPercent).toInt()
    }

    /**
     * Get animation duration for split/merge transition.
     *
     * @param isSplitting true for split animation, false for merge animation
     * @return Duration in milliseconds
     */
    fun getTransitionDuration(isSplitting: Boolean): Long {
        return if (isSplitting) SPLIT_ANIMATION_DURATION_MS else MERGE_ANIMATION_DURATION_MS
    }

    /**
     * Set callback for split mode events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to default configuration.
     */
    fun reset() {
        config = SplitConfig()
        disable()
        logD("Reset to default configuration")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing SplitKeyboardManager resources...")

        try {
            callback = null
            logD("✅ SplitKeyboardManager resources released")
        } catch (e: Exception) {
            logE("Error releasing split keyboard manager resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logW(message: String) = Log.w(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
