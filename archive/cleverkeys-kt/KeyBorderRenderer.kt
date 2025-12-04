package tribixbite.cleverkeys

import android.graphics.*
import android.util.Log

/**
 * Renders decorative borders and outlines for keyboard keys.
 *
 * Provides customizable border styles, colors, gradients, shadows, and effects
 * for enhanced key visual appearance and theme customization.
 *
 * Features:
 * - Multiple border styles (solid, dashed, dotted, gradient, double)
 * - Customizable border width, color, and opacity
 * - Rounded corner support with configurable radius
 * - Gradient borders with multiple color stops
 * - Shadow and glow effects
 * - Per-key border customization
 * - Performance-optimized rendering
 * - Integration with theme system
 * - Border animation support
 *
 * Bug #337 - LOW: Complete implementation of missing KeyBorderRenderer.java
 */
class KeyBorderRenderer {
    companion object {
        private const val TAG = "KeyBorderRenderer"

        // Default values
        private const val DEFAULT_BORDER_WIDTH_DP = 1.5f
        private const val DEFAULT_CORNER_RADIUS_DP = 4f
        private const val DEFAULT_BORDER_COLOR = 0xFF333333.toInt()
        private const val DEFAULT_BORDER_ALPHA = 255
        private const val DEFAULT_SHADOW_RADIUS_DP = 2f
        private const val DEFAULT_SHADOW_OFFSET_DP = 1f

        // Border style constants
        private const val DASH_INTERVAL_DP = 4f
        private const val DOT_INTERVAL_DP = 2f
        private const val DOUBLE_BORDER_GAP_DP = 2f

        // Gradient constants
        private const val GRADIENT_ANGLE_DEFAULT = 45f
    }

    /**
     * Border style options.
     */
    enum class BorderStyle {
        NONE,           // No border
        SOLID,          // Solid line border
        DASHED,         // Dashed line border
        DOTTED,         // Dotted line border
        GRADIENT,       // Gradient border
        DOUBLE,         // Double line border
        GLOW,           // Glowing border effect
        SHADOW          // Border with shadow
    }

    /**
     * Border configuration.
     */
    data class BorderConfig(
        val style: BorderStyle = BorderStyle.SOLID,
        val widthDp: Float = DEFAULT_BORDER_WIDTH_DP,
        val color: Int = DEFAULT_BORDER_COLOR,
        val alpha: Int = DEFAULT_BORDER_ALPHA,
        val cornerRadiusDp: Float = DEFAULT_CORNER_RADIUS_DP,
        val gradientColors: IntArray? = null,
        val gradientAngle: Float = GRADIENT_ANGLE_DEFAULT,
        val shadowRadiusDp: Float = DEFAULT_SHADOW_RADIUS_DP,
        val shadowOffsetDp: Float = DEFAULT_SHADOW_OFFSET_DP,
        val shadowColor: Int = Color.BLACK,
        val glowIntensity: Float = 0.5f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BorderConfig

            if (style != other.style) return false
            if (widthDp != other.widthDp) return false
            if (color != other.color) return false
            if (alpha != other.alpha) return false
            if (cornerRadiusDp != other.cornerRadiusDp) return false
            if (gradientColors != null) {
                if (other.gradientColors == null) return false
                if (!gradientColors.contentEquals(other.gradientColors)) return false
            } else if (other.gradientColors != null) return false
            if (gradientAngle != other.gradientAngle) return false
            if (shadowRadiusDp != other.shadowRadiusDp) return false
            if (shadowOffsetDp != other.shadowOffsetDp) return false
            if (shadowColor != other.shadowColor) return false
            if (glowIntensity != other.glowIntensity) return false

            return true
        }

        override fun hashCode(): Int {
            var result = style.hashCode()
            result = 31 * result + widthDp.hashCode()
            result = 31 * result + color
            result = 31 * result + alpha
            result = 31 * result + cornerRadiusDp.hashCode()
            result = 31 * result + (gradientColors?.contentHashCode() ?: 0)
            result = 31 * result + gradientAngle.hashCode()
            result = 31 * result + shadowRadiusDp.hashCode()
            result = 31 * result + shadowOffsetDp.hashCode()
            result = 31 * result + shadowColor
            result = 31 * result + glowIntensity.hashCode()
            return result
        }
    }

    /**
     * Callback interface for border rendering events.
     */
    interface Callback {
        /**
         * Called when border configuration changes.
         *
         * @param config New border configuration
         */
        fun onBorderConfigChanged(config: BorderConfig)

        /**
         * Called when border is rendered.
         *
         * @param keyBounds Key bounds that were rendered
         */
        fun onBorderRendered(keyBounds: RectF)
    }

    // Current state
    private var defaultConfig: BorderConfig = BorderConfig()
    private var callback: Callback? = null
    private var density: Float = 1f

    // Paint objects (reused for performance)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Reusable objects
    private val tempRect = RectF()
    private val tempPath = Path()

    init {
        logD("KeyBorderRenderer initialized")
    }

    /**
     * Set screen density for dp to px conversion.
     *
     * @param density Screen density factor
     */
    fun setDensity(density: Float) {
        this.density = density
        logD("Density set to: $density")
    }

    /**
     * Set default border configuration.
     *
     * @param config Default configuration for all keys
     */
    fun setDefaultConfig(config: BorderConfig) {
        defaultConfig = config
        logD("Default config updated: $config")
        callback?.onBorderConfigChanged(config)
    }

    /**
     * Render border for a key.
     *
     * @param canvas Canvas to draw on
     * @param keyBounds Key bounds
     * @param config Border configuration (null uses default)
     */
    fun renderBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig? = null) {
        val activeConfig = config ?: defaultConfig

        if (activeConfig.style == BorderStyle.NONE) {
            return
        }

        try {
            when (activeConfig.style) {
                BorderStyle.SOLID -> renderSolidBorder(canvas, keyBounds, activeConfig)
                BorderStyle.DASHED -> renderDashedBorder(canvas, keyBounds, activeConfig)
                BorderStyle.DOTTED -> renderDottedBorder(canvas, keyBounds, activeConfig)
                BorderStyle.GRADIENT -> renderGradientBorder(canvas, keyBounds, activeConfig)
                BorderStyle.DOUBLE -> renderDoubleBorder(canvas, keyBounds, activeConfig)
                BorderStyle.GLOW -> renderGlowBorder(canvas, keyBounds, activeConfig)
                BorderStyle.SHADOW -> renderShadowBorder(canvas, keyBounds, activeConfig)
                BorderStyle.NONE -> { /* No border */ }
            }

            callback?.onBorderRendered(keyBounds)
        } catch (e: Exception) {
            logE("Error rendering border", e)
        }
    }

    /**
     * Render solid border.
     */
    private fun renderSolidBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)
    }

    /**
     * Render dashed border.
     */
    private fun renderDashedBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val dashInterval = dpToPx(DASH_INTERVAL_DP)
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashInterval, dashInterval), 0f)

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        borderPaint.pathEffect = null
    }

    /**
     * Render dotted border.
     */
    private fun renderDottedBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val dotInterval = dpToPx(DOT_INTERVAL_DP)
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(dotInterval / 2, dotInterval), 0f)

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        borderPaint.pathEffect = null
    }

    /**
     * Render gradient border.
     */
    private fun renderGradientBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        val gradientColors = config.gradientColors
        if (gradientColors == null || gradientColors.isEmpty()) {
            // Fallback to solid border
            renderSolidBorder(canvas, keyBounds, config)
            return
        }

        configurePaint(borderPaint, config)

        // Calculate gradient start and end points based on angle
        val angleRad = Math.toRadians(config.gradientAngle.toDouble())
        val centerX = keyBounds.centerX()
        val centerY = keyBounds.centerY()
        val radius = Math.max(keyBounds.width(), keyBounds.height()) / 2

        val x1 = centerX + (Math.cos(angleRad) * radius).toFloat()
        val y1 = centerY + (Math.sin(angleRad) * radius).toFloat()
        val x2 = centerX - (Math.cos(angleRad) * radius).toFloat()
        val y2 = centerY - (Math.sin(angleRad) * radius).toFloat()

        borderPaint.shader = LinearGradient(
            x1, y1, x2, y2,
            gradientColors,
            null,
            Shader.TileMode.CLAMP
        )

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        borderPaint.shader = null
    }

    /**
     * Render double border.
     */
    private fun renderDoubleBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        val gap = dpToPx(DOUBLE_BORDER_GAP_DP)
        val borderWidth = dpToPx(config.widthDp)

        // Outer border
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        // Inner border
        tempRect.set(
            keyBounds.left + borderWidth + gap,
            keyBounds.top + borderWidth + gap,
            keyBounds.right - borderWidth - gap,
            keyBounds.bottom - borderWidth - gap
        )

        val innerRadius = Math.max(0f, cornerRadius - borderWidth - gap)
        canvas.drawRoundRect(tempRect, innerRadius, innerRadius, borderPaint)
    }

    /**
     * Render glowing border.
     */
    private fun renderGlowBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val glowRadius = dpToPx(config.widthDp * config.glowIntensity * 3)
        borderPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.OUTER)

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        borderPaint.maskFilter = null
    }

    /**
     * Render border with shadow.
     */
    private fun renderShadowBorder(canvas: Canvas, keyBounds: RectF, config: BorderConfig) {
        configurePaint(borderPaint, config)

        val shadowRadius = dpToPx(config.shadowRadiusDp)
        val shadowOffset = dpToPx(config.shadowOffsetDp)

        borderPaint.setShadowLayer(
            shadowRadius,
            shadowOffset,
            shadowOffset,
            config.shadowColor
        )

        val cornerRadius = dpToPx(config.cornerRadiusDp)
        canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, borderPaint)

        borderPaint.clearShadowLayer()
    }

    /**
     * Render border with custom path.
     *
     * @param canvas Canvas to draw on
     * @param path Custom path to stroke
     * @param config Border configuration
     */
    fun renderCustomPath(canvas: Canvas, path: Path, config: BorderConfig? = null) {
        val activeConfig = config ?: defaultConfig

        if (activeConfig.style == BorderStyle.NONE) {
            return
        }

        try {
            configurePaint(borderPaint, activeConfig)

            when (activeConfig.style) {
                BorderStyle.DASHED -> {
                    val dashInterval = dpToPx(DASH_INTERVAL_DP)
                    borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashInterval, dashInterval), 0f)
                }
                BorderStyle.DOTTED -> {
                    val dotInterval = dpToPx(DOT_INTERVAL_DP)
                    borderPaint.pathEffect = DashPathEffect(floatArrayOf(dotInterval / 2, dotInterval), 0f)
                }
                else -> borderPaint.pathEffect = null
            }

            canvas.drawPath(path, borderPaint)

            borderPaint.pathEffect = null
        } catch (e: Exception) {
            logE("Error rendering custom path", e)
        }
    }

    /**
     * Render filled background with border.
     *
     * @param canvas Canvas to draw on
     * @param keyBounds Key bounds
     * @param backgroundColor Background color
     * @param borderConfig Border configuration
     */
    fun renderFilledWithBorder(
        canvas: Canvas,
        keyBounds: RectF,
        backgroundColor: Int,
        borderConfig: BorderConfig? = null
    ) {
        val activeConfig = borderConfig ?: defaultConfig

        try {
            // Draw filled background
            fillPaint.color = backgroundColor
            val cornerRadius = dpToPx(activeConfig.cornerRadiusDp)
            canvas.drawRoundRect(keyBounds, cornerRadius, cornerRadius, fillPaint)

            // Draw border on top
            renderBorder(canvas, keyBounds, activeConfig)
        } catch (e: Exception) {
            logE("Error rendering filled with border", e)
        }
    }

    /**
     * Configure paint with border config settings.
     */
    private fun configurePaint(paint: Paint, config: BorderConfig) {
        paint.color = config.color
        paint.alpha = config.alpha
        paint.strokeWidth = dpToPx(config.widthDp)
        paint.style = Paint.Style.STROKE
    }

    /**
     * Convert dp to pixels.
     */
    private fun dpToPx(dp: Float): Float = dp * density

    /**
     * Create border config from theme colors.
     *
     * @param borderColor Border color
     * @param backgroundColor Key background color
     * @param style Border style
     * @return Border configuration
     */
    fun createThemeConfig(
        borderColor: Int,
        backgroundColor: Int,
        style: BorderStyle = BorderStyle.SOLID
    ): BorderConfig {
        return BorderConfig(
            style = style,
            color = borderColor,
            widthDp = DEFAULT_BORDER_WIDTH_DP,
            cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP
        )
    }

    /**
     * Create gradient border config.
     *
     * @param colors Gradient colors
     * @param angle Gradient angle in degrees
     * @return Border configuration
     */
    fun createGradientConfig(colors: IntArray, angle: Float = GRADIENT_ANGLE_DEFAULT): BorderConfig {
        return BorderConfig(
            style = BorderStyle.GRADIENT,
            gradientColors = colors,
            gradientAngle = angle,
            widthDp = DEFAULT_BORDER_WIDTH_DP,
            cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP
        )
    }

    /**
     * Create glow border config.
     *
     * @param glowColor Glow color
     * @param intensity Glow intensity (0.0 to 1.0)
     * @return Border configuration
     */
    fun createGlowConfig(glowColor: Int, intensity: Float = 0.5f): BorderConfig {
        return BorderConfig(
            style = BorderStyle.GLOW,
            color = glowColor,
            glowIntensity = intensity.coerceIn(0f, 1f),
            widthDp = DEFAULT_BORDER_WIDTH_DP,
            cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP
        )
    }

    /**
     * Create shadow border config.
     *
     * @param borderColor Border color
     * @param shadowColor Shadow color
     * @param shadowRadius Shadow radius in dp
     * @return Border configuration
     */
    fun createShadowConfig(
        borderColor: Int,
        shadowColor: Int = Color.BLACK,
        shadowRadius: Float = DEFAULT_SHADOW_RADIUS_DP
    ): BorderConfig {
        return BorderConfig(
            style = BorderStyle.SHADOW,
            color = borderColor,
            shadowColor = shadowColor,
            shadowRadiusDp = shadowRadius,
            widthDp = DEFAULT_BORDER_WIDTH_DP,
            cornerRadiusDp = DEFAULT_CORNER_RADIUS_DP
        )
    }

    /**
     * Animate border width.
     *
     * @param fromDp Starting width in dp
     * @param toDp Ending width in dp
     * @param progress Animation progress (0.0 to 1.0)
     * @return Animated border configuration
     */
    fun animateBorderWidth(fromDp: Float, toDp: Float, progress: Float): BorderConfig {
        val animatedWidth = fromDp + (toDp - fromDp) * progress.coerceIn(0f, 1f)
        return defaultConfig.copy(widthDp = animatedWidth)
    }

    /**
     * Animate border color.
     *
     * @param fromColor Starting color
     * @param toColor Ending color
     * @param progress Animation progress (0.0 to 1.0)
     * @return Animated border configuration
     */
    fun animateBorderColor(fromColor: Int, toColor: Int, progress: Float): BorderConfig {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val inverseProgress = 1f - clampedProgress

        val r = (Color.red(fromColor) * inverseProgress + Color.red(toColor) * clampedProgress).toInt()
        val g = (Color.green(fromColor) * inverseProgress + Color.green(toColor) * clampedProgress).toInt()
        val b = (Color.blue(fromColor) * inverseProgress + Color.blue(toColor) * clampedProgress).toInt()
        val a = (Color.alpha(fromColor) * inverseProgress + Color.alpha(toColor) * clampedProgress).toInt()

        val animatedColor = Color.argb(a, r, g, b)
        return defaultConfig.copy(color = animatedColor, alpha = a)
    }

    /**
     * Animate border opacity.
     *
     * @param fromAlpha Starting alpha (0-255)
     * @param toAlpha Ending alpha (0-255)
     * @param progress Animation progress (0.0 to 1.0)
     * @return Animated border configuration
     */
    fun animateBorderOpacity(fromAlpha: Int, toAlpha: Int, progress: Float): BorderConfig {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val animatedAlpha = (fromAlpha + (toAlpha - fromAlpha) * clampedProgress).toInt()
        return defaultConfig.copy(alpha = animatedAlpha.coerceIn(0, 255))
    }

    /**
     * Get current default configuration.
     *
     * @return Default border configuration
     */
    fun getDefaultConfig(): BorderConfig = defaultConfig

    /**
     * Set callback for border rendering events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Reset to default settings.
     */
    fun reset() {
        defaultConfig = BorderConfig()
        logD("Reset to default configuration")
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing KeyBorderRenderer resources...")

        try {
            callback = null
            borderPaint.reset()
            fillPaint.reset()
            tempPath.reset()
            logD("✅ KeyBorderRenderer resources released")
        } catch (e: Exception) {
            logE("Error releasing key border renderer resources", e)
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
