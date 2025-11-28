package tribixbite.keyboard2

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import kotlin.math.min

class Theme(context: Context, attrs: AttributeSet?) {
    // Key colors
    val colorKey: Int
    val colorKeyActivated: Int

    // Label colors
    @JvmField
    val lockedColor: Int
    @JvmField
    val activatedColor: Int
    @JvmField
    val labelColor: Int
    @JvmField
    val subLabelColor: Int
    @JvmField
    val secondaryLabelColor: Int
    @JvmField
    val greyedLabelColor: Int

    // Key borders
    val keyBorderRadius: Float
    val keyBorderWidth: Float
    val keyBorderWidthActivated: Float
    val keyBorderColorLeft: Int
    val keyBorderColorTop: Int
    val keyBorderColorRight: Int
    val keyBorderColorBottom: Int

    @JvmField
    val colorNavBar: Int
    @JvmField
    val isLightNavBar: Boolean

    init {
        getKeyFont(context) // _key_font will be accessed
        val s = context.theme.obtainStyledAttributes(attrs, R.styleable.keyboard, 0, 0)
        colorKey = s.getColor(R.styleable.keyboard_colorKey, 0)
        colorKeyActivated = s.getColor(R.styleable.keyboard_colorKeyActivated, 0)
        // colorKeyboard = s.getColor(R.styleable.keyboard_colorKeyboard, 0)
        colorNavBar = s.getColor(R.styleable.keyboard_navigationBarColor, 0)
        isLightNavBar = s.getBoolean(R.styleable.keyboard_windowLightNavigationBar, false)
        labelColor = s.getColor(R.styleable.keyboard_colorLabel, 0)
        activatedColor = s.getColor(R.styleable.keyboard_colorLabelActivated, 0)
        lockedColor = s.getColor(R.styleable.keyboard_colorLabelLocked, 0)
        subLabelColor = s.getColor(R.styleable.keyboard_colorSubLabel, 0)
        secondaryLabelColor = adjustLight(
            labelColor,
            s.getFloat(R.styleable.keyboard_secondaryDimming, 0.25f)
        )
        greyedLabelColor = adjustLight(
            labelColor,
            s.getFloat(R.styleable.keyboard_greyedDimming, 0.5f)
        )
        keyBorderRadius = s.getDimension(R.styleable.keyboard_keyBorderRadius, 0f)
        keyBorderWidth = s.getDimension(R.styleable.keyboard_keyBorderWidth, 0f)
        keyBorderWidthActivated = s.getDimension(R.styleable.keyboard_keyBorderWidthActivated, 0f)
        keyBorderColorLeft = s.getColor(R.styleable.keyboard_keyBorderColorLeft, colorKey)
        keyBorderColorTop = s.getColor(R.styleable.keyboard_keyBorderColorTop, colorKey)
        keyBorderColorRight = s.getColor(R.styleable.keyboard_keyBorderColorRight, colorKey)
        keyBorderColorBottom = s.getColor(R.styleable.keyboard_keyBorderColorBottom, colorKey)
        s.recycle()
    }

    /** Interpolate the 'value' component toward its opposite by 'alpha'. */
    private fun adjustLight(color: Int, alpha: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val v = hsv[2]
        hsv[2] = alpha - (2 * alpha - 1) * v
        return Color.HSVToColor(hsv)
    }

    fun initIndicationPaint(align: Paint.Align, font: Typeface?): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = align
            if (font != null) {
                typeface = font
            }
        }
    }

    class Computed(
        theme: Theme,
        config: Config,
        keyWidth: Float,
        layout: KeyboardData
    ) {
        @JvmField
        val vertical_margin: Float
        @JvmField
        val horizontal_margin: Float
        @JvmField
        val margin_top: Float
        @JvmField
        val margin_left: Float
        @JvmField
        val row_height: Float
        @JvmField
        val indication_paint: Paint

        @JvmField
        val key: Key
        @JvmField
        val key_activated: Key

        init {
            // Rows height is proportional to the keyboard height, meaning it doesn't
            // change for layouts with more or less rows. 3.95 is the usual height of
            // a layout in KeyboardData unit. The keyboard will be higher if the
            // layout has more rows and smaller if it has less because rows stay the
            // same height.
            row_height = min(
                config.screenHeightPixels * config.keyboardHeightPercent / 100 / 3.95f,
                config.screenHeightPixels / layout.keysHeight
            )
            vertical_margin = config.key_vertical_margin * row_height
            horizontal_margin = config.key_horizontal_margin * keyWidth
            // Add half of the key margin on the left and on the top as it's also
            // added on the right and on the bottom of every keys.
            margin_top = config.marginTop + vertical_margin / 2
            margin_left = horizontal_margin / 2
            key = Key(theme, config, keyWidth, false)
            key_activated = Key(theme, config, keyWidth, true)
            indication_paint = init_label_paint(config, null).apply {
                color = theme.subLabelColor
            }
        }

        class Key(
            theme: Theme,
            config: Config,
            keyWidth: Float,
            activated: Boolean
        ) {
            @JvmField
            val bg_paint = Paint()
            @JvmField
            val border_paint: Paint
            @JvmField
            val border_width: Float
            @JvmField
            val border_radius: Float
            private val _label_paint: Paint
            private val _special_label_paint: Paint
            private val _sublabel_paint: Paint
            private val _special_sublabel_paint: Paint
            private val _label_alpha_bits: Int

            init {
                bg_paint.color = if (activated) theme.colorKeyActivated else theme.colorKey

                if (config.borderConfig) {
                    border_radius = config.customBorderRadius * keyWidth
                    border_width = config.customBorderLineWidth
                } else {
                    border_radius = theme.keyBorderRadius
                    border_width = if (activated) theme.keyBorderWidthActivated else theme.keyBorderWidth
                }

                bg_paint.alpha = if (activated) config.keyActivatedOpacity else config.keyOpacity
                border_paint = init_border_paint(config, border_width, theme.keyBorderColorTop)
                _label_paint = init_label_paint(config, null)
                _special_label_paint = init_label_paint(config, _key_font)
                _sublabel_paint = init_label_paint(config, null)
                _special_sublabel_paint = init_label_paint(config, _key_font)
                _label_alpha_bits = (config.labelBrightness and 0xFF) shl 24
            }

            fun label_paint(special_font: Boolean, color: Int, text_size: Float): Paint {
                val p = if (special_font) _special_label_paint else _label_paint
                p.color = (color and 0x00FFFFFF) or _label_alpha_bits
                p.textSize = text_size
                return p
            }

            fun sublabel_paint(
                special_font: Boolean,
                color: Int,
                text_size: Float,
                align: Paint.Align
            ): Paint {
                val p = if (special_font) _special_sublabel_paint else _sublabel_paint
                p.color = (color and 0x00FFFFFF) or _label_alpha_bits
                p.textSize = text_size
                p.textAlign = align
                return p
            }
        }

        companion object {
            private fun init_border_paint(config: Config, border_width: Float, color: Int): Paint {
                return Paint().apply {
                    alpha = config.keyOpacity
                    style = Paint.Style.STROKE
                    strokeWidth = border_width
                    setColor(color)
                }
            }

            private fun init_label_paint(config: Config, font: Typeface?): Paint {
                return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    if (font != null) {
                        typeface = font
                    }
                }
            }
        }
    }

    companion object {
        private var _key_font: Typeface? = null

        @JvmStatic
        fun getKeyFont(context: Context): Typeface {
            if (_key_font == null) {
                _key_font = Typeface.createFromAsset(context.assets, "special_font.ttf")
            }
            return _key_font!!
        }
    }
}
