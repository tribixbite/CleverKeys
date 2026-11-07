package tribixbite.cleverkeys.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Paints a [LookBackdrop] (aurora glows and a static star field) over [baseColor]: the keyboard
 * view's background while a look whose palette defines one is effective. Shaders and star
 * positions are built once per size in [onBoundsChange]; [draw] only replays them, so the
 * keyboard's per-frame cost is a handful of shader fills.
 */
class AuroraBackdropDrawable(
    private val baseColor: Int,
    private val backdrop: LookBackdrop,
    private val density: Float,
) : Drawable() {

    private val basePaint = Paint().apply { color = baseColor }
    private val glowPaints = backdrop.glows.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val fadePaint = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var stars: List<Star> = emptyList()
    /** Whole-drawable alpha: the keyboard opacity setting (`Keyboard2View` sets `background.alpha`). */
    private var drawableAlpha = 0xFF

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        backdrop.glows.forEachIndexed { i, g ->
            val cx = bounds.left + g.cx * w
            val cy = bounds.top + g.cy * h
            val rx = (g.rx * w).coerceAtLeast(1f)
            val ry = (g.ry * h).coerceAtLeast(1f)
            // A circle of radius rx, squashed to ry and tilted about its centre: an ellipse.
            // The middle stop keeps a soft core instead of a linear cone.
            val shader = RadialGradient(
                cx, cy, rx,
                intArrayOf(g.color, withAlpha(g.color, (alphaOf(g.color) * 0.45f).toInt()), withAlpha(g.color, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            shader.setLocalMatrix(Matrix().apply {
                setScale(1f, ry / rx, cx, cy)
                postRotate(g.rotationDeg, cx, cy)
            })
            glowPaints[i].shader = shader
        }
        val fadeH = backdrop.topFade.coerceIn(0f, 1f) * h
        fadePaint.shader = if (fadeH > 0f) LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + fadeH,
            baseColor, withAlpha(baseColor, 0), Shader.TileMode.CLAMP,
        ) else null
        stars = backdrop.stars?.let { LookStars.place(it, bounds.width(), bounds.height(), density) }.orEmpty()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // A translucent keyboard composites the whole painting once, so glows over the base do
        // not add up to more opacity than the base alone.
        val layer = if (drawableAlpha < 0xFF) canvas.saveLayerAlpha(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), drawableAlpha,
        ) else -1
        canvas.drawRect(b, basePaint)
        for (p in glowPaints) if (p.shader != null) canvas.drawRect(b, p)
        val field = backdrop.stars
        if (field != null) {
            val rgb = field.color and 0x00FFFFFF
            glintPaint.strokeWidth = 0.5f * density
            for (s in stars) {
                val x = b.left + s.x
                val y = b.top + s.y
                starPaint.color = (s.alpha shl 24) or rgb
                canvas.drawCircle(x, y, s.radius, starPaint)
                if (s.glint) {
                    // A faint four-point glint, about four radii long, at under half the star's alpha.
                    glintPaint.color = ((s.alpha * 2 / 5) shl 24) or rgb
                    val arm = s.radius * 4f
                    canvas.drawLine(x - arm, y, x + arm, y, glintPaint)
                    canvas.drawLine(x, y - arm, x, y + arm, glintPaint)
                }
            }
        }
        // Last, so stars and glows fade into the base colour where the suggestion bar meets the keys.
        if (fadePaint.shader != null) canvas.drawRect(b, fadePaint)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        val a = alpha.coerceIn(0, 0xFF)
        if (a != drawableAlpha) {
            drawableAlpha = a
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        starPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (alphaOf(baseColor) == 0xFF && drawableAlpha == 0xFF) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    private companion object {
        fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
        fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }
}
