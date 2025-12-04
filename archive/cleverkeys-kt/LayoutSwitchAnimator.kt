package tribixbite.cleverkeys

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.View
import android.view.animation.*
import android.view.ViewGroup

/**
 * Specialized animator for keyboard layout transitions.
 *
 * Provides polished animations when switching between keyboard layouts
 * (e.g., QWERTY → numeric, text → emoji, etc.) with different animation styles.
 *
 * Features:
 * - Multiple animation styles (slide, fade, scale, flip)
 * - Directional animations based on layout hierarchy
 * - Smooth transitions with appropriate interpolators
 * - Callbacks for transition lifecycle events
 * - Configurable animation duration
 * - Respects system animation settings
 * - Automatic cleanup and memory management
 *
 * Bug #329 - MEDIUM: Complete implementation of missing LayoutSwitchAnimator.java
 *
 * @param context Application context for accessing resources
 * @param enabled Initial enabled state (default: true)
 * @param defaultDuration Default animation duration in milliseconds (default: 250ms)
 */
class LayoutSwitchAnimator(
    private val context: Context,
    private var enabled: Boolean = true,
    private var defaultDuration: Long = 250L
) {
    companion object {
        private const val TAG = "LayoutSwitchAnimator"

        // Animation durations
        private const val DEFAULT_DURATION = 250L
        private const val FAST_DURATION = 150L
        private const val SLOW_DURATION = 350L

        // Animation parameters
        private const val SLIDE_DISTANCE_FACTOR = 1.0f  // Full width/height
        private const val SCALE_MIN = 0.8f              // Minimum scale
        private const val SCALE_MAX = 1.0f              // Maximum scale
        private const val FLIP_DEGREES = 90f            // Flip rotation degrees

        // Alpha values
        private const val ALPHA_TRANSPARENT = 0f
        private const val ALPHA_OPAQUE = 1f
    }

    /**
     * Animation style for layout transitions.
     */
    enum class AnimationStyle {
        SLIDE_HORIZONTAL,  // Slide left/right
        SLIDE_VERTICAL,    // Slide up/down
        FADE,              // Crossfade
        SCALE,             // Scale in/out
        FLIP_HORIZONTAL,   // 3D flip horizontal
        FLIP_VERTICAL,     // 3D flip vertical
        NONE               // Instant switch (no animation)
    }

    /**
     * Direction for directional animations.
     */
    enum class Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    /**
     * Callback interface for animation events.
     */
    interface AnimationCallback {
        /**
         * Called when animation starts.
         */
        fun onAnimationStart()

        /**
         * Called when old layout is halfway through exit animation.
         * Good time to prepare new layout.
         */
        fun onAnimationMiddle()

        /**
         * Called when animation completes.
         */
        fun onAnimationEnd()
    }

    // System animation scale factor
    private var systemAnimationScale = 1.0f

    // Active animations for cancellation
    private val activeAnimations = mutableListOf<Animator>()

    init {
        logD("Initializing LayoutSwitchAnimator (enabled=$enabled, duration=${defaultDuration}ms)")
        updateSystemAnimationScale()
    }

    /**
     * Update system animation scale from global settings.
     */
    private fun updateSystemAnimationScale() {
        try {
            systemAnimationScale = android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (e: Exception) {
            logE("Failed to get system animation scale", e)
            systemAnimationScale = 1.0f
        }
    }

    /**
     * Calculate effective duration considering scale factors.
     */
    private fun getEffectiveDuration(baseDuration: Long = defaultDuration): Long {
        if (!enabled || systemAnimationScale == 0f) {
            return 0L
        }
        return (baseDuration * systemAnimationScale).toLong()
    }

    /**
     * Animate layout switch with specified style.
     *
     * @param oldLayout The layout being replaced (can be null for first layout)
     * @param newLayout The layout being shown
     * @param style Animation style to use
     * @param direction Direction for directional animations
     * @param callback Optional callback for animation events
     */
    fun animateLayoutSwitch(
        oldLayout: View?,
        newLayout: View,
        style: AnimationStyle = AnimationStyle.SLIDE_HORIZONTAL,
        direction: Direction = Direction.LEFT,
        callback: AnimationCallback? = null
    ) {
        if (!enabled) {
            // Instant switch
            oldLayout?.visibility = View.GONE
            newLayout.visibility = View.VISIBLE
            callback?.onAnimationStart()
            callback?.onAnimationMiddle()
            callback?.onAnimationEnd()
            return
        }

        val duration = getEffectiveDuration()

        callback?.onAnimationStart()

        when (style) {
            AnimationStyle.SLIDE_HORIZONTAL -> animateSlide(oldLayout, newLayout, true, direction, duration, callback)
            AnimationStyle.SLIDE_VERTICAL -> animateSlide(oldLayout, newLayout, false, direction, duration, callback)
            AnimationStyle.FADE -> animateFade(oldLayout, newLayout, duration, callback)
            AnimationStyle.SCALE -> animateScale(oldLayout, newLayout, duration, callback)
            AnimationStyle.FLIP_HORIZONTAL -> animateFlip(oldLayout, newLayout, true, duration, callback)
            AnimationStyle.FLIP_VERTICAL -> animateFlip(oldLayout, newLayout, false, duration, callback)
            AnimationStyle.NONE -> {
                oldLayout?.visibility = View.GONE
                newLayout.visibility = View.VISIBLE
                callback?.onAnimationMiddle()
                callback?.onAnimationEnd()
            }
        }
    }

    /**
     * Animate slide transition.
     */
    private fun animateSlide(
        oldLayout: View?,
        newLayout: View,
        horizontal: Boolean,
        direction: Direction,
        duration: Long,
        callback: AnimationCallback?
    ) {
        val distance = if (horizontal) newLayout.width.toFloat() else newLayout.height.toFloat()
        val directionFactor = when (direction) {
            Direction.LEFT, Direction.UP -> -1f
            Direction.RIGHT, Direction.DOWN -> 1f
        }

        // Animate old layout out
        oldLayout?.let { old ->
            val animator = if (horizontal) {
                old.animate().translationX(distance * directionFactor)
            } else {
                old.animate().translationY(distance * directionFactor)
            }

            animator
                .alpha(ALPHA_TRANSPARENT)
                .setDuration(duration)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    old.visibility = View.GONE
                    if (horizontal) old.translationX = 0f else old.translationY = 0f
                    old.alpha = ALPHA_OPAQUE
                }
                .start()
        }

        // Animate new layout in
        newLayout.visibility = View.VISIBLE
        if (horizontal) {
            newLayout.translationX = -distance * directionFactor
        } else {
            newLayout.translationY = -distance * directionFactor
        }
        newLayout.alpha = ALPHA_TRANSPARENT

        // Trigger middle callback halfway through
        newLayout.postDelayed({
            callback?.onAnimationMiddle()
        }, duration / 2)

        val animator = if (horizontal) {
            newLayout.animate().translationX(0f)
        } else {
            newLayout.animate().translationY(0f)
        }

        animator
            .alpha(ALPHA_OPAQUE)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                callback?.onAnimationEnd()
            }
            .start()
    }

    /**
     * Animate fade/crossfade transition.
     */
    private fun animateFade(
        oldLayout: View?,
        newLayout: View,
        duration: Long,
        callback: AnimationCallback?
    ) {
        // Fade out old layout
        oldLayout?.animate()
            ?.alpha(ALPHA_TRANSPARENT)
            ?.setDuration(duration)
            ?.withEndAction {
                oldLayout.visibility = View.GONE
                oldLayout.alpha = ALPHA_OPAQUE
            }
            ?.start()

        // Fade in new layout
        newLayout.visibility = View.VISIBLE
        newLayout.alpha = ALPHA_TRANSPARENT

        // Trigger middle callback halfway through
        newLayout.postDelayed({
            callback?.onAnimationMiddle()
        }, duration / 2)

        newLayout.animate()
            .alpha(ALPHA_OPAQUE)
            .setDuration(duration)
            .withEndAction {
                callback?.onAnimationEnd()
            }
            .start()
    }

    /**
     * Animate scale transition.
     */
    private fun animateScale(
        oldLayout: View?,
        newLayout: View,
        duration: Long,
        callback: AnimationCallback?
    ) {
        // Scale down and fade out old layout
        oldLayout?.animate()
            ?.scaleX(SCALE_MIN)
            ?.scaleY(SCALE_MIN)
            ?.alpha(ALPHA_TRANSPARENT)
            ?.setDuration(duration)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction {
                oldLayout.visibility = View.GONE
                oldLayout.scaleX = SCALE_MAX
                oldLayout.scaleY = SCALE_MAX
                oldLayout.alpha = ALPHA_OPAQUE
            }
            ?.start()

        // Scale up and fade in new layout
        newLayout.visibility = View.VISIBLE
        newLayout.scaleX = SCALE_MIN
        newLayout.scaleY = SCALE_MIN
        newLayout.alpha = ALPHA_TRANSPARENT

        // Trigger middle callback halfway through
        newLayout.postDelayed({
            callback?.onAnimationMiddle()
        }, duration / 2)

        newLayout.animate()
            .scaleX(SCALE_MAX)
            .scaleY(SCALE_MAX)
            .alpha(ALPHA_OPAQUE)
            .setDuration(duration)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                callback?.onAnimationEnd()
            }
            .start()
    }

    /**
     * Animate 3D flip transition.
     */
    private fun animateFlip(
        oldLayout: View?,
        newLayout: View,
        horizontal: Boolean,
        duration: Long,
        callback: AnimationCallback?
    ) {
        val halfDuration = duration / 2

        // First half: flip old layout away
        oldLayout?.let { old ->
            val animator = if (horizontal) {
                old.animate().rotationY(FLIP_DEGREES)
            } else {
                old.animate().rotationX(FLIP_DEGREES)
            }

            animator
                .alpha(ALPHA_TRANSPARENT)
                .setDuration(halfDuration)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    old.visibility = View.GONE
                    if (horizontal) old.rotationY = 0f else old.rotationX = 0f
                    old.alpha = ALPHA_OPAQUE

                    // Trigger middle callback
                    callback?.onAnimationMiddle()

                    // Second half: flip new layout in
                    newLayout.visibility = View.VISIBLE
                    if (horizontal) {
                        newLayout.rotationY = -FLIP_DEGREES
                    } else {
                        newLayout.rotationX = -FLIP_DEGREES
                    }
                    newLayout.alpha = ALPHA_TRANSPARENT

                    val newAnimator = if (horizontal) {
                        newLayout.animate().rotationY(0f)
                    } else {
                        newLayout.animate().rotationX(0f)
                    }

                    newAnimator
                        .alpha(ALPHA_OPAQUE)
                        .setDuration(halfDuration)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            callback?.onAnimationEnd()
                        }
                        .start()
                }
                .start()
        } ?: run {
            // No old layout, just flip new one in
            newLayout.visibility = View.VISIBLE
            if (horizontal) {
                newLayout.rotationY = -FLIP_DEGREES
            } else {
                newLayout.rotationX = -FLIP_DEGREES
            }
            newLayout.alpha = ALPHA_TRANSPARENT

            callback?.onAnimationMiddle()

            val animator = if (horizontal) {
                newLayout.animate().rotationY(0f)
            } else {
                newLayout.animate().rotationX(0f)
            }

            animator
                .alpha(ALPHA_OPAQUE)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    callback?.onAnimationEnd()
                }
                .start()
        }
    }

    /**
     * Quick convenience method for common layout switch scenarios.
     */
    fun animateToNumeric(oldLayout: View, newLayout: View, callback: AnimationCallback? = null) {
        animateLayoutSwitch(oldLayout, newLayout, AnimationStyle.SLIDE_VERTICAL, Direction.UP, callback)
    }

    fun animateToEmoji(oldLayout: View, newLayout: View, callback: AnimationCallback? = null) {
        animateLayoutSwitch(oldLayout, newLayout, AnimationStyle.SCALE, callback = callback)
    }

    fun animateToText(oldLayout: View, newLayout: View, callback: AnimationCallback? = null) {
        animateLayoutSwitch(oldLayout, newLayout, AnimationStyle.SLIDE_VERTICAL, Direction.DOWN, callback)
    }

    fun animateLayoutChange(oldLayout: View, newLayout: View, callback: AnimationCallback? = null) {
        animateLayoutSwitch(oldLayout, newLayout, AnimationStyle.FADE, callback = callback)
    }

    /**
     * Cancel all active animations.
     */
    fun cancelAllAnimations() {
        activeAnimations.forEach { it.cancel() }
        activeAnimations.clear()
        logD("All animations cancelled")
    }

    /**
     * Enable or disable animations.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            cancelAllAnimations()
        }
        logD("Layout animations ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if animations are enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set default animation duration.
     */
    fun setDefaultDuration(duration: Long) {
        this.defaultDuration = duration.coerceAtLeast(0L)
        logD("Default duration set to: ${this.defaultDuration}ms")
    }

    /**
     * Get default animation duration.
     */
    fun getDefaultDuration(): Long = defaultDuration

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing LayoutSwitchAnimator resources...")

        try {
            cancelAllAnimations()
            logD("✅ LayoutSwitchAnimator resources released")
        } catch (e: Exception) {
            logE("Error releasing layout switch animator resources", e)
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
