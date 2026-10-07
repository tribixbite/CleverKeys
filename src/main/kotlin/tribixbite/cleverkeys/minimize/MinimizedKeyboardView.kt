package tribixbite.cleverkeys.minimize

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.hypot
import kotlin.math.min
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Theme

/** How the minimized keyboard looks (gh #175, docs/specs/keyboard-minimize.md). */
enum class MinimizedStyle {
    /** A thin full-width strip; the app is resized to sit above it. */
    BAR,

    /** One round button in the bottom corner; the app gets the whole screen around it. */
    FAB,
}

/**
 * What the input window shows while the keyboard is minimized: a [MinimizedStyle.BAR] strip or
 * a [MinimizedStyle.FAB] button, each with an up chevron. Tapping it expands the keyboard.
 *
 * Drawn directly (no child views, nothing allocated per frame) in the keyboard theme's colours.
 * It pads itself for the navigation bar, like the key view does under edge-to-edge.
 */
class MinimizedKeyboardView(context: Context) : View(context) {

    var style: MinimizedStyle = MinimizedStyle.FAB
        private set

    /** Invoked when the user taps the bar or button. */
    var onExpand: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val barHeight = BAR_HEIGHT_DP * density
    private val fabDiameter = FAB_DIAMETER_DP * density
    private val fabMargin = FAB_MARGIN_DP * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevron = Path()
    private val fabBounds = RectF()
    private val location = IntArray(2)

    private var backgroundColour = Color.DKGRAY
    private var keyColour = Color.GRAY
    private var pressedColour = Color.LTGRAY
    private var labelColour = Color.WHITE
    private var accentColour = Color.WHITE
    private var navInset = 0
    private var pressed = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.short_swipe_show_keyboard)
        // Under edge-to-edge the input window reaches behind the navigation bar: sit above it.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (bottom != navInset) {
                navInset = bottom
                requestLayout()
            }
            insets
        }
    }

    /** Show [style] in [theme]'s colours. */
    fun bind(style: MinimizedStyle, theme: Theme?) {
        this.style = style
        if (theme != null) {
            backgroundColour = if (theme.colorKeyboardBackground != 0) theme.colorKeyboardBackground else theme.colorKey
            keyColour = theme.colorKey
            pressedColour = theme.colorKeyActivated
            labelColour = theme.labelColor
            accentColour = theme.activatedColor
        }
        pressed = false
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val content = when (style) {
            MinimizedStyle.BAR -> barHeight
            MinimizedStyle.FAB -> fabDiameter + 2 * fabMargin
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (content + navInset).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The button sits at the end (right in LTR, left in RTL), centred in the content height.
        // Direction comes from the configuration, not the view: inside the input window the
        // view's resolved direction stayed LTR with the system set to Arabic (Saga, 2026-10-07),
        // so the button drew on the right. PanePagerArrows reads the same configuration.
        val r = fabDiameter / 2f
        val cy = (height - navInset) / 2f
        val rtl = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL
        val cx = if (rtl) fabMargin + r else width - fabMargin - r
        fabBounds.set(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onDraw(canvas: Canvas) {
        when (style) {
            MinimizedStyle.BAR -> {
                fillPaint.color = if (pressed) pressedColour else backgroundColour
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
                drawChevron(canvas, width / 2f, (height - navInset) / 2f, barHeight * 0.32f, labelColour)
            }
            MinimizedStyle.FAB -> {
                // The rest of the strip stays transparent: the app shows (and is touchable) there.
                val r = fabBounds.width() / 2f
                fillPaint.color = if (pressed) pressedColour else keyColour
                canvas.drawCircle(fabBounds.centerX(), fabBounds.centerY(), r, fillPaint)
                ringPaint.color = accentColour
                ringPaint.strokeWidth = RING_DP * density
                canvas.drawCircle(fabBounds.centerX(), fabBounds.centerY(), r - ringPaint.strokeWidth / 2f, ringPaint)
                drawChevron(canvas, fabBounds.centerX(), fabBounds.centerY(), r * 0.42f, labelColour)
            }
        }
    }

    /** An up chevron ("expand") of half-width [size] centred on ([cx], [cy]). */
    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, size: Float, colour: Int) {
        chevron.reset()
        chevron.moveTo(cx - size, cy + size * 0.45f)
        chevron.lineTo(cx, cy - size * 0.45f)
        chevron.lineTo(cx + size, cy + size * 0.45f)
        chevronPaint.color = colour
        chevronPaint.strokeWidth = min(size * 0.28f, CHEVRON_MAX_STROKE_DP * density)
        canvas.drawPath(chevron, chevronPaint)
    }

    private fun hits(x: Float, y: Float): Boolean = when (style) {
        MinimizedStyle.BAR -> true
        MinimizedStyle.FAB -> hypot(x - fabBounds.centerX(), y - fabBounds.centerY()) <= fabBounds.width() / 2f
    }

    @SuppressLint("ClickableViewAccessibility")  // performClick() runs on ACTION_UP below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hits(event.x, event.y)) return false
                pressed = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = hits(event.x, event.y)
                if (inside != pressed) {
                    pressed = inside
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val inside = hits(event.x, event.y)
                pressed = false
                invalidate()
                if (inside) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onExpand?.invoke()
        return true
    }

    /**
     * The area that takes touches, in input-window coordinates: the whole bar, or just the
     * button. Everything else of the input window passes touches through to the app.
     */
    fun touchableArea(out: Rect) {
        getLocationInWindow(location)
        when (style) {
            MinimizedStyle.BAR -> out.set(location[0], location[1], location[0] + width, location[1] + height)
            MinimizedStyle.FAB -> out.set(
                location[0] + fabBounds.left.toInt(), location[1] + fabBounds.top.toInt(),
                location[0] + fabBounds.right.toInt(), location[1] + fabBounds.bottom.toInt(),
            )
        }
    }

    private companion object {
        const val BAR_HEIGHT_DP = 30f
        const val FAB_DIAMETER_DP = 52f
        const val FAB_MARGIN_DP = 12f
        const val RING_DP = 1.5f
        const val CHEVRON_MAX_STROKE_DP = 3f
    }
}
