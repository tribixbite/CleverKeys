package tribixbite.cleverkeys.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * Paints a [LookBackdrop] (aurora curtains, glows and a static star field) over [baseColor]: the
 * keyboard view's background while a look whose palette defines one is effective. The curtains
 * are rendered once per size at half resolution ([AuroraRaster], cached across instances so a
 * re-inflated keyboard does not render them again); shaders and star positions are built in
 * [onBoundsChange]; [draw] only replays them, so the per-frame cost is a bitmap blit and a
 * handful of shader fills.
 *
 * With [motionEnabled] (the user's "Moving aurora") it also animates ([AuroraMotion]): a second
 * curtain image cross-fades over the first while both drift, and the stars twinkle in groups.
 * Frames are scheduled through the drawable's own callback ([scheduleSelf]), only while it is
 * visible, [motionGate] allows it and the user touched the keyboard within [AuroraMotion.IDLE_MS].
 */
class AuroraBackdropDrawable(
    private val baseColor: Int,
    private val backdrop: LookBackdrop,
    private val density: Float,
    /** Asked before each start: false (battery saver, animations off) keeps the aurora still. */
    private val motionGate: () -> Boolean = { false },
) : Drawable() {

    private val basePaint = Paint().apply { color = baseColor }
    private val glowPaints = backdrop.glows.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val fadePaint = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ribbonPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var ribbons: Bitmap? = null
    private val ribbonDst = Rect()
    private var stars: List<Star> = emptyList()
    /** Whole-drawable alpha: the keyboard opacity setting (`Keyboard2View` sets `background.alpha`). */
    private var drawableAlpha = 0xFF

    // ---- Motion (off unless the user turns on "Moving aurora") ----
    /** The second curtain image ([AuroraMotion.alternate]); rendered when motion is first enabled. */
    private var ribbonsAlt: Bitmap? = null
    private val ribbonAltPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val driftDst = android.graphics.RectF()
    private val clock = AuroraMotionClock()
    private var ticking = false
    private val tick = Runnable { onTick() }
    /**
     * The stars of each twinkle group recorded once ([RenderNode]s, API 29+), so a motion frame
     * only sets each group's alpha instead of re-recording every star; null until first drawn
     * moving, and after a size or colour-filter change.
     */
    private var starNodes: Array<Any>? = null

    /**
     * Whether the aurora may move (set by `Keyboard2View` from the user's setting on every key
     * redraw). Turning it off stops at once and paints the still aurora again.
     */
    var motionEnabled = false
        private set

    fun setMotionEnabled(enabled: Boolean) {
        if (enabled == motionEnabled) return
        motionEnabled = enabled
        if (enabled) {
            if (ribbons != null && ribbonsAlt == null) ribbonsAlt = altRibbonBitmap(bounds.width(), bounds.height())
            wake()
        } else {
            stopMotion()
            ribbonsAlt = null
        }
        invalidateSelf()
    }

    /**
     * The user is at the keyboard (a touch, the keyboard showing): move for the next
     * [AuroraMotion.IDLE_MS]. Cheap when already moving; [motionGate] is asked only to start.
     */
    fun wake() {
        if (!motionEnabled || !isVisible || ribbons == null) return
        val now = SystemClock.uptimeMillis()
        clock.wake(now)
        if (!ticking && motionGate()) {
            ticking = true
            scheduleSelf(tick, now)
        }
    }

    /** Stops scheduling frames; the aurora rests where it is. */
    fun stopMotion() {
        if (ticking) unscheduleSelf(tick)
        ticking = false
        clock.rest()
    }

    private fun onTick() {
        if (!ticking) return
        val now = SystemClock.uptimeMillis()
        val moving = clock.advance(now)
        invalidateSelf()
        if (moving) scheduleSelf(tick, now + AuroraMotion.FRAME_MS) else ticking = false
    }

    /** Hidden (keyboard closed, view detached): stop; shown again: move for a while. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) stopMotion() else if (changed) wake()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        ribbons = if (backdrop.ribbons.isEmpty()) null else ribbonBitmap(bounds.width(), bounds.height())
        ribbonsAlt = if (motionEnabled && ribbons != null) altRibbonBitmap(bounds.width(), bounds.height()) else null
        ribbonDst.set(bounds)
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
        starNodes = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // A translucent keyboard composites the whole painting once, so glows over the base do
        // not add up to more opacity than the base alone.
        val layer = if (drawableAlpha < 0xFF) canvas.saveLayerAlpha(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), drawableAlpha,
        ) else -1
        val curtains = ribbons
        val alt = ribbonsAlt
        val frame = if (motionEnabled && curtains != null && alt != null) {
            AuroraMotion.frame(clock.motionMs / 1000.0)
        } else null
        if (frame != null && curtains != null && alt != null) {
            drawDrifting(canvas, curtains, alt, frame)
        } else if (curtains != null) {
            canvas.drawBitmap(curtains, null, ribbonDst, ribbonPaint)
        } else {
            canvas.drawRect(b, basePaint)
        }
        for (p in glowPaints) if (p.shader != null) canvas.drawRect(b, p)
        val field = backdrop.stars
        if (field != null && frame != null && Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            drawStarGroups(canvas, field, frame)
        } else if (field != null) {
            val rgb = field.color and 0x00FFFFFF
            glintPaint.strokeWidth = 0.5f * density
            for (i in stars.indices) {
                val s = stars[i]
                val x = b.left + s.x
                val y = b.top + s.y
                // A moving aurora dims each star group in turn (a twinkle); a still one draws all.
                val alpha = if (frame == null) s.alpha
                else (s.alpha * frame.starAlpha[i % AuroraMotion.STAR_GROUPS]).toInt()
                starPaint.color = (alpha shl 24) or rgb
                canvas.drawCircle(x, y, s.radius, starPaint)
                if (s.glint) {
                    // A faint four-point glint, about four radii long, at under half the star's alpha.
                    glintPaint.color = ((alpha * 2 / 5) shl 24) or rgb
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

    /**
     * A moving aurora's stars: each twinkle group is a display list recorded once, drawn at its
     * group's brightness for this [frame] (a property change, not a re-recording).
     */
    @RequiresApi(29)
    private fun drawStarGroups(canvas: Canvas, field: LookStarfield, frame: AuroraMotionFrame) {
        val b = bounds
        // Held as Any (an Object[]): the field must not name an API 29 class.
        val nodes = starNodes ?: Array<Any>(AuroraMotion.STAR_GROUPS) { g ->
            RenderNode("AuroraStars$g").apply {
                setPosition(b)
                // Stars never overlap enough to need an offscreen pass for the group alpha.
                setHasOverlappingRendering(false)
                val c = beginRecording()
                try {
                    drawStars(c, field, g, -b.left.toFloat(), -b.top.toFloat())
                } finally {
                    endRecording()
                }
            }
        }.also { starNodes = it }
        for (g in nodes.indices) {
            val node = nodes[g] as RenderNode
            node.setAlpha(frame.starAlpha[g])
            canvas.drawRenderNode(node)
        }
    }

    /** The stars of twinkle group [group] at full brightness, offset by ([dx], [dy]). */
    private fun drawStars(canvas: Canvas, field: LookStarfield, group: Int, dx: Float, dy: Float) {
        val b = bounds
        val rgb = field.color and 0x00FFFFFF
        glintPaint.strokeWidth = 0.5f * density
        for (i in group until stars.size step AuroraMotion.STAR_GROUPS) {
            val s = stars[i]
            val x = b.left + s.x + dx
            val y = b.top + s.y + dy
            starPaint.color = (s.alpha shl 24) or rgb
            canvas.drawCircle(x, y, s.radius, starPaint)
            if (s.glint) {
                glintPaint.color = ((s.alpha * 2 / 5) shl 24) or rgb
                val arm = s.radius * 4f
                canvas.drawLine(x - arm, y, x + arm, y, glintPaint)
                canvas.drawLine(x, y - arm, x, y + arm, glintPaint)
            }
        }
    }

    /**
     * The two curtain images, each stretched [AuroraMotion.DRIFT_MARGIN_DP] past both edges (a
     * stretch of about 1 %, invisible on soft curtains) and shifted by its drift; the second over
     * the first at the cross-fade. Both are opaque, so the result is exactly their blend.
     */
    private fun drawDrifting(canvas: Canvas, a: Bitmap, alt: Bitmap, frame: AuroraMotionFrame) {
        val b = bounds
        val margin = AuroraMotion.DRIFT_MARGIN_DP * density
        val save = canvas.save()
        canvas.clipRect(b)
        driftDst.set(b.left - margin + frame.driftADp * density, b.top.toFloat(),
            b.right + margin + frame.driftADp * density, b.bottom.toFloat())
        canvas.drawBitmap(a, null, driftDst, ribbonPaint)
        val altAlpha = (frame.crossfade * 255).toInt()
        if (altAlpha > 0) {
            ribbonAltPaint.alpha = altAlpha
            driftDst.set(b.left - margin + frame.driftBDp * density, b.top.toFloat(),
                b.right + margin + frame.driftBDp * density, b.bottom.toFloat())
            canvas.drawBitmap(alt, null, driftDst, ribbonAltPaint)
        }
        canvas.restoreToCount(save)
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
        ribbonPaint.colorFilter = colorFilter
        ribbonAltPaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        starPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        starNodes = null
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (alphaOf(baseColor) == 0xFF && drawableAlpha == 0xFF) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    /** The curtains over the base colour at half the view's size, from the cache or rendered. */
    private fun ribbonBitmap(widthPx: Int, heightPx: Int): Bitmap = ribbonBitmap(backdrop.ribbons, widthPx, heightPx)

    /** The second curtain image of a moving aurora ([AuroraMotion.alternate]). */
    private fun altRibbonBitmap(widthPx: Int, heightPx: Int): Bitmap? =
        if (widthPx <= 0 || heightPx <= 0) null
        else ribbonBitmap(AuroraMotion.alternate(backdrop.ribbons), widthPx, heightPx)

    private fun ribbonBitmap(curtains: List<AuroraRibbon>, widthPx: Int, heightPx: Int): Bitmap {
        val w = (widthPx / RIBBON_SCALE).coerceAtLeast(1)
        val h = (heightPx / RIBBON_SCALE).coerceAtLeast(1)
        val key = RibbonKey(baseColor, curtains, w, h, density)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val pixels = AuroraRaster.render(baseColor, curtains, w, h, density / RIBBON_SCALE)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    private data class RibbonKey(val base: Int, val ribbons: List<AuroraRibbon>, val w: Int, val h: Int, val density: Float)

    companion object {
        /** Curtains are soft; half resolution quarters the render cost and the memory. */
        private const val RIBBON_SCALE = 2

        /**
         * The last few rendered sizes (portrait, landscape, a split screen; two images each while
         * the aurora moves), shared by every instance: the keyboard view and its drawable are
         * re-created on theme and layout changes, and each render walks every pixel. An
         * access-ordered map evicting the eldest.
         */
        private val cache = object : LinkedHashMap<RibbonKey, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RibbonKey, Bitmap>?) = size > 6
        }

        /**
         * Whether the system lets the aurora move now: Android 10+ (`Keyboard2View` caches its
         * keys in a RenderNode on motion frames, an API 29 class), battery saver off, and
         * animations not switched off in the developer or accessibility settings.
         */
        fun systemAllowsMotion(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isPowerSaveMode == true) return false
            return ValueAnimator.areAnimatorsEnabled()
        }

        private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
        private fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }
}
