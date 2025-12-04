package tribixbite.cleverkeys.animation

import androidx.compose.animation.core.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material Motion system for CleverKeys.
 *
 * Provides Material 3 compliant animation curves and durations:
 * - Emphasized easing (recommended for most animations)
 * - Standard easing (legacy, subtle animations)
 * - Durations (short, medium, long)
 * - Spring physics for natural motion
 *
 * Reference: https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 */
object MaterialMotion {

    // ==================== DURATIONS ====================

    /**
     * Short duration (50-150ms) - Micro-interactions.
     *
     * Use for:
     * - Key press feedback
     * - Ripple effects
     * - Small component state changes
     */
    const val DURATION_SHORT_1 = 50
    const val DURATION_SHORT_2 = 100
    const val DURATION_SHORT_3 = 150
    const val DURATION_SHORT_4 = 200

    /**
     * Medium duration (200-300ms) - Component transitions.
     *
     * Use for:
     * - Suggestion bar updates
     * - Dialog enter/exit
     * - Sheet expansion
     */
    const val DURATION_MEDIUM_1 = 250
    const val DURATION_MEDIUM_2 = 300
    const val DURATION_MEDIUM_3 = 350
    const val DURATION_MEDIUM_4 = 400

    /**
     * Long duration (400-500ms) - Container transformations.
     *
     * Use for:
     * - Keyboard show/hide
     * - Layout changes
     * - Large element transitions
     */
    const val DURATION_LONG_1 = 450
    const val DURATION_LONG_2 = 500
    const val DURATION_LONG_3 = 550
    const val DURATION_LONG_4 = 600

    // ==================== EASING CURVES ====================

    /**
     * Emphasized easing - RECOMMENDED for most animations.
     *
     * Starts slowly, accelerates quickly, ends gently.
     * Creates energetic, attention-grabbing motion.
     */
    object Emphasized {
        /**
         * Emphasized accelerate - for exit animations.
         *
         * Elements leaving the screen accelerate quickly.
         */
        val Accelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

        /**
         * Emphasized decelerate - for enter animations.
         *
         * Elements entering the screen decelerate gently.
         */
        val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

        /**
         * Standard emphasized - for state changes.
         *
         * Balanced emphasis for transitions.
         */
        val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    }

    /**
     * Standard easing - for subtle, legacy animations.
     *
     * Smooth, gentle motion without emphasis.
     */
    object Standard {
        val Accelerate = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
        val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
        val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    }

    /**
     * Legacy easing - Android platform default.
     *
     * Use only for compatibility with existing Android animations.
     */
    object Legacy {
        val Accelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
        val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
        val Standard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    }

    // ==================== SPRING PHYSICS ====================

    /**
     * High stiffness spring - fast, snappy motion.
     *
     * Use for:
     * - Key press feedback
     * - Quick state changes
     */
    val SpringHighStiffness = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh
    )

    /**
     * Medium stiffness spring - balanced motion.
     *
     * Use for:
     * - Suggestion item placement
     * - Card animations
     * - Most spring animations
     */
    val SpringMediumStiffness = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Low stiffness spring - slow, gentle motion.
     *
     * Use for:
     * - Large element movement
     * - Layout changes
     */
    val SpringLowStiffness = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    // ==================== COMMON ANIMATION SPECS ====================

    /**
     * Key press animation - emphasized decelerate.
     *
     * Quick feedback when key is pressed.
     */
    fun <T> keyPress(duration: Int = DURATION_SHORT_2): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Decelerate
    )

    /**
     * Key release animation - emphasized accelerate.
     *
     * Snappy release when key is released.
     */
    fun <T> keyRelease(duration: Int = DURATION_SHORT_1): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Accelerate
    )

    /**
     * Suggestion update animation - standard emphasized.
     *
     * Smooth transition between suggestion sets.
     */
    fun <T> suggestionUpdate(duration: Int = DURATION_SHORT_3): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Standard
    )

    /**
     * Dialog enter animation - emphasized decelerate.
     *
     * Dialog appears with deceleration.
     */
    fun <T> dialogEnter(duration: Int = DURATION_MEDIUM_2): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Decelerate
    )

    /**
     * Dialog exit animation - emphasized accelerate.
     *
     * Dialog disappears with acceleration.
     */
    fun <T> dialogExit(duration: Int = DURATION_SHORT_4): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Accelerate
    )

    /**
     * Keyboard show animation - long decelerate.
     *
     * Keyboard slides up with gentle deceleration.
     */
    fun <T> keyboardShow(duration: Int = DURATION_LONG_2): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Decelerate
    )

    /**
     * Keyboard hide animation - medium accelerate.
     *
     * Keyboard slides down with acceleration.
     */
    fun <T> keyboardHide(duration: Int = DURATION_MEDIUM_3): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Accelerate
    )

    /**
     * Swipe trail animation - standard emphasized.
     *
     * Trail follows finger with balanced motion.
     */
    fun <T> swipeTrail(duration: Int = DURATION_SHORT_2): TweenSpec<T> = tween(
        durationMillis = duration,
        easing = Emphasized.Standard
    )

    // ==================== ENTER/EXIT TRANSITIONS ====================

    /**
     * Fade in transition - emphasized decelerate.
     */
    fun fadeIn(duration: Int = DURATION_SHORT_3) = androidx.compose.animation.fadeIn(
        animationSpec = tween(duration, easing = Emphasized.Decelerate)
    )

    /**
     * Fade out transition - emphasized accelerate.
     */
    fun fadeOut(duration: Int = DURATION_SHORT_3) = androidx.compose.animation.fadeOut(
        animationSpec = tween(duration, easing = Emphasized.Accelerate)
    )

    /**
     * Slide in vertically - emphasized decelerate.
     */
    fun slideInVertically(
        duration: Int = DURATION_SHORT_3,
        initialOffset: (fullHeight: Int) -> Int = { it / 4 }
    ) = androidx.compose.animation.slideInVertically(
        animationSpec = tween(duration, easing = Emphasized.Decelerate),
        initialOffsetY = initialOffset
    )

    /**
     * Slide out vertically - emphasized accelerate.
     */
    fun slideOutVertically(
        duration: Int = DURATION_SHORT_3,
        targetOffset: (fullHeight: Int) -> Int = { -it / 4 }
    ) = androidx.compose.animation.slideOutVertically(
        animationSpec = tween(duration, easing = Emphasized.Accelerate),
        targetOffsetY = targetOffset
    )

    /**
     * Scale in - emphasized decelerate.
     */
    fun scaleIn(
        duration: Int = DURATION_SHORT_3,
        initialScale: Float = 0.8f
    ) = androidx.compose.animation.scaleIn(
        animationSpec = tween(duration, easing = Emphasized.Decelerate),
        initialScale = initialScale
    )

    /**
     * Scale out - emphasized accelerate.
     */
    fun scaleOut(
        duration: Int = DURATION_SHORT_2,
        targetScale: Float = 0.8f
    ) = androidx.compose.animation.scaleOut(
        animationSpec = tween(duration, easing = Emphasized.Accelerate),
        targetScale = targetScale
    )

    // ==================== DIMENSION ANIMATIONS ====================

    /**
     * Animate Dp values - for size changes.
     */
    fun animateDp(
        initialValue: Dp,
        targetValue: Dp,
        duration: Int = DURATION_MEDIUM_2,
        onUpdate: (Dp) -> Unit
    ): Animatable<Dp, AnimationVector1D> {
        return Animatable(initialValue, Dp.VectorConverter)
    }

    /**
     * Animate Float values - for alpha, scale, rotation.
     */
    fun animateFloat(
        initialValue: Float = 0f,
        targetValue: Float = 1f,
        duration: Int = DURATION_MEDIUM_2
    ): Animatable<Float, AnimationVector1D> {
        return Animatable(initialValue)
    }
}

/**
 * Animation configuration holder.
 *
 * Allows runtime disable of animations for accessibility/performance.
 */
data class AnimationConfig(
    val enabled: Boolean = true,
    val reducedMotion: Boolean = false
) {
    /**
     * Get duration, accounting for reduced motion preference.
     *
     * Reduced motion uses 50% shorter durations.
     */
    fun duration(base: Int): Int {
        return if (!enabled) 0
        else if (reducedMotion) base / 2
        else base
    }

    companion object {
        val Default = AnimationConfig(enabled = true, reducedMotion = false)
        val Disabled = AnimationConfig(enabled = false, reducedMotion = false)
        val ReducedMotion = AnimationConfig(enabled = true, reducedMotion = true)
    }
}
