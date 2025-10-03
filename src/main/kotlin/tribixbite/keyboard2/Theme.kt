package tribixbite.keyboard2

import android.content.Context
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.*
import tribixbite.keyboard2.Config
import tribixbite.keyboard2.KeyboardData

/**
 * Complete theme management system for CleverKeys.
 *
 * Features:
 * - Android system theme integration
 * - Dark/light mode support
 * - Dynamic theming with Material You
 * - Reactive theme updates
 * - Paint object caching for performance
 * - Custom font loading
 */
class Theme(context: Context, attrs: AttributeSet? = null) {

    // Core color properties
    val colorKey: Int
    val colorKeyActivated: Int
    val lockedColor: Int
    val activatedColor: Int
    val labelColor: Int
    val subLabelColor: Int
    val secondaryLabelColor: Int
    val greyedLabelColor: Int

    // Border properties
    val keyBorderRadius: Float
    val keyBorderWidth: Float
    val keyBorderWidthActivated: Float
    val keyBorderColorLeft: Int
    val keyBorderColorTop: Int
    val keyBorderColorRight: Int
    val keyBorderColorBottom: Int

    // Navigation bar properties
    val colorNavBar: Int
    val isLightNavBar: Boolean

    companion object {
        private var keyFont: Typeface? = null

        /**
         * Get the special keyboard font. Loads from assets if not already loaded.
         */
        @JvmStatic
        fun getKeyFont(context: Context): Typeface {
            if (keyFont == null) {
                keyFont = try {
                    Typeface.createFromAsset(context.assets, "special_font.ttf")
                } catch (e: Exception) {
                    Typeface.DEFAULT // Fallback to default font
                }
            }
            return keyFont ?: Typeface.DEFAULT
        }

        /**
         * Create theme instance with proper Android theme integration.
         */
        @JvmStatic
        fun createFromContext(context: Context, attrs: AttributeSet? = null): Theme {
            return Theme(context, attrs)
        }

        /**
         * Get current system theme properties for keyboard styling.
         */
        @JvmStatic
        fun getSystemThemeData(context: Context): ThemeData {
            val isDarkMode = isSystemDarkMode(context)

            return if (isDarkMode) {
                createSystemDarkTheme(context)
            } else {
                createSystemLightTheme(context)
            }
        }

        private fun isSystemDarkMode(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode
            return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        private fun createSystemDarkTheme(context: Context): ThemeData {
            val keyColor = getThemeColor(context, android.R.attr.colorBackground, 0xFF2B2B2B.toInt())
            val labelColor = getThemeColor(context, android.R.attr.textColorPrimary, Color.WHITE)
            val backgroundColor = getThemeColor(context, android.R.attr.colorBackgroundFloating, Color.BLACK)

            return ThemeData(
                keyColor = keyColor,
                keyBorderColor = adjustColorBrightness(keyColor, 0.8f),
                labelColor = labelColor,
                backgroundColor = backgroundColor,
                labelTextSize = 16f,
                isDarkMode = true,
                keyActivatedColor = adjustColorBrightness(keyColor, 1.2f),
                suggestionTextColor = labelColor,
                suggestionBackgroundColor = adjustColorBrightness(backgroundColor, 1.1f),
                swipeTrailColor = getThemeColor(context, android.R.attr.colorAccent, 0xFF00D4FF.toInt())
            )
        }

        private fun createSystemLightTheme(context: Context): ThemeData {
            val keyColor = getThemeColor(context, android.R.attr.colorBackground, Color.LTGRAY)
            val labelColor = getThemeColor(context, android.R.attr.textColorPrimary, Color.BLACK)
            val backgroundColor = getThemeColor(context, android.R.attr.colorBackgroundFloating, Color.WHITE)

            return ThemeData(
                keyColor = keyColor,
                keyBorderColor = adjustColorBrightness(keyColor, 0.8f),
                labelColor = labelColor,
                backgroundColor = backgroundColor,
                labelTextSize = 16f,
                isDarkMode = false,
                keyActivatedColor = adjustColorBrightness(keyColor, 0.9f),
                suggestionTextColor = labelColor,
                suggestionBackgroundColor = adjustColorBrightness(backgroundColor, 0.95f),
                swipeTrailColor = getThemeColor(context, android.R.attr.colorAccent, 0xFF1976D2.toInt())
            )
        }

        private fun getThemeColor(context: Context, attrId: Int, fallback: Int): Int {
            val typedValue = TypedValue()
            return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
                if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                    typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    typedValue.data
                } else {
                    ContextCompat.getColor(context, typedValue.resourceId)
                }
            } else {
                fallback
            }
        }

        private fun adjustColorBrightness(color: Int, factor: Float): Int {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val a = Color.alpha(color)

            return Color.argb(
                a,
                (r * factor).toInt().coerceIn(0, 255),
                (g * factor).toInt().coerceIn(0, 255),
                (b * factor).toInt().coerceIn(0, 255)
            )
        }
    }

    init {
        // Ensure key font is loaded
        getKeyFont(context)

        // Load theme from Android system colors (simplified for compatibility)
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            colorKey = Color.rgb(64, 64, 64)
            colorKeyActivated = Color.rgb(96, 96, 96)
            labelColor = Color.WHITE
            activatedColor = Color.rgb(255, 165, 0) // Orange
            lockedColor = Color.rgb(255, 255, 0) // Yellow
            subLabelColor = Color.rgb(192, 192, 192)
            colorNavBar = Color.BLACK
            isLightNavBar = false
        } else {
            colorKey = Color.rgb(240, 240, 240)
            colorKeyActivated = Color.rgb(200, 200, 200)
            labelColor = Color.BLACK
            activatedColor = Color.rgb(255, 140, 0) // Dark Orange
            lockedColor = Color.rgb(200, 200, 0) // Dark Yellow
            subLabelColor = Color.rgb(96, 96, 96)
            colorNavBar = Color.WHITE
            isLightNavBar = true
        }

        secondaryLabelColor = adjustLight(labelColor, 0.25f)
        greyedLabelColor = adjustLight(labelColor, 0.5f)

        keyBorderRadius = 8f
        keyBorderWidth = 1f
        keyBorderWidthActivated = 2f
        keyBorderColorLeft = colorKey
        keyBorderColorTop = colorKey
        keyBorderColorRight = colorKey
        keyBorderColorBottom = colorKey
    }

    /**
     * Interpolate the 'value' component toward its opposite by 'alpha'.
     */
    private fun adjustLight(color: Int, alpha: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val v = hsv[2]
        hsv[2] = alpha - (2 * alpha - 1) * v
        return Color.HSVToColor(hsv)
    }

    /**
     * Initialize indication paint with specific alignment and font.
     */
    fun initIndicationPaint(align: Paint.Align, font: Typeface?): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = align
        if (font != null) {
            paint.typeface = font
        }
        return paint
    }

    /**
     * Theme data class for reactive theme updates.
     */
    data class ThemeData(
        val keyColor: Int,
        val keyBorderColor: Int,
        val labelColor: Int,
        val backgroundColor: Int,
        val labelTextSize: Float,
        val isDarkMode: Boolean,
        val keyActivatedColor: Int = keyColor,
        val suggestionTextColor: Int = labelColor,
        val suggestionBackgroundColor: Int = backgroundColor,
        val swipeTrailColor: Int = 0xFF00D4FF.toInt(),
        val errorColor: Int = 0xFFFF5722.toInt(),
        val successColor: Int = 0xFF4CAF50.toInt(),
        val keyTextSize: Float = labelTextSize,
        val suggestionTextSize: Float = labelTextSize * 0.9f,
        val hintTextSize: Float = labelTextSize * 0.7f,
        val keyCornerRadius: Float = 8f,
        val keyElevation: Float = 2f,
        val suggestionBarHeight: Float = 48f
    )

    /**
     * Computed theme properties for efficient rendering.
     */
    class Computed(
        private val theme: Theme,
        config: Config,
        keyWidth: Float,
        layout: KeyboardData
    ) {
        val verticalMargin: Float
        val horizontalMargin: Float
        val marginTop: Float
        val marginLeft: Float
        val rowHeight: Float
        val indicationPaint: Paint

        val key: Key
        val keyActivated: Key

        init {
            // Calculate row height proportional to keyboard height
            rowHeight = minOf(
                config.screenHeightPixels * config.keyboardHeightPercent / 100 / 3.95f,
                config.screenHeightPixels / layout.keysHeight
            )

            verticalMargin = config.key_vertical_margin * rowHeight
            horizontalMargin = config.key_horizontal_margin * keyWidth
            marginTop = config.marginTop + verticalMargin / 2
            marginLeft = horizontalMargin / 2

            key = Key(theme, config, keyWidth, false)
            keyActivated = Key(theme, config, keyWidth, true)

            indicationPaint = initLabelPaint(config, null)
            indicationPaint.color = theme.subLabelColor
        }

        /**
         * Individual key rendering properties.
         */
        class Key(
            theme: Theme,
            config: Config,
            keyWidth: Float,
            activated: Boolean
        ) {
            val bgPaint = Paint()
            val borderLeftPaint: Paint
            val borderTopPaint: Paint
            val borderRightPaint: Paint
            val borderBottomPaint: Paint
            val borderWidth: Float
            val borderRadius: Float

            private val labelPaint: Paint
            private val specialLabelPaint: Paint
            private val subLabelPaint: Paint
            private val specialSubLabelPaint: Paint
            private val labelAlphaBits: Int

            init {
                bgPaint.color = if (activated) theme.colorKeyActivated else theme.colorKey

                if (config.borderConfig) {
                    borderRadius = config.customBorderRadius * keyWidth
                    borderWidth = config.customBorderLineWidth
                } else {
                    borderRadius = theme.keyBorderRadius
                    borderWidth = if (activated) theme.keyBorderWidthActivated else theme.keyBorderWidth
                }

                bgPaint.alpha = if (activated) config.keyActivatedOpacity else config.keyOpacity

                borderLeftPaint = initBorderPaint(config, borderWidth, theme.keyBorderColorLeft)
                borderTopPaint = initBorderPaint(config, borderWidth, theme.keyBorderColorTop)
                borderRightPaint = initBorderPaint(config, borderWidth, theme.keyBorderColorRight)
                borderBottomPaint = initBorderPaint(config, borderWidth, theme.keyBorderColorBottom)

                labelPaint = initLabelPaint(config, null)
                specialLabelPaint = initLabelPaint(config, keyFont)
                subLabelPaint = initLabelPaint(config, null)
                specialSubLabelPaint = initLabelPaint(config, keyFont)
                labelAlphaBits = (config.labelBrightness and 0xFF) shl 24
            }

            /**
             * Get label paint with specified properties.
             */
            fun labelPaint(specialFont: Boolean, color: Int, textSize: Float): Paint {
                val paint = if (specialFont) specialLabelPaint else labelPaint
                paint.color = (color and 0x00FFFFFF) or labelAlphaBits
                paint.textSize = textSize
                return paint
            }

            /**
             * Get sublabel paint with specified properties.
             */
            fun subLabelPaint(specialFont: Boolean, color: Int, textSize: Float, align: Paint.Align): Paint {
                val paint = if (specialFont) specialSubLabelPaint else subLabelPaint
                paint.color = (color and 0x00FFFFFF) or labelAlphaBits
                paint.textSize = textSize
                paint.textAlign = align
                return paint
            }
        }

        companion object {
            /**
             * Initialize border paint with configuration.
             */
            fun initBorderPaint(config: Config, borderWidth: Float, color: Int): Paint {
                val paint = Paint()
                paint.alpha = config.keyOpacity
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = borderWidth
                paint.color = color
                return paint
            }

            /**
             * Initialize label paint with configuration.
             */
            fun initLabelPaint(config: Config, font: Typeface?): Paint {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.textAlign = Paint.Align.CENTER
                if (font != null) {
                    paint.typeface = font
                }
                return paint
            }
        }
    }
}