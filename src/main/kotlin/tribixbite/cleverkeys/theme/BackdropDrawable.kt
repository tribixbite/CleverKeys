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
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * Paints a [LookBackdrop] over [baseColor]: the keyboard view's background while a look whose
 * palette defines one is effective. The painting (aurora curtains, [AuroraRaster], or a nature
 * scene, [SceneRaster]) is rendered once per size at half resolution and cached across
 * instances, so a re-inflated keyboard does not render it again; shaders and particle positions
 * are built in [onBoundsChange]; [draw] only replays them, so the per-frame cost is a bitmap
 * blit, a handful of shader fills and the particles.
 *
 * With [motionEnabled] (the user's "Animated scenes") it also moves ([AuroraMotion]): a second
 * image of the painting cross-fades over the first while both drift, and the particles twinkle
 * in groups and travel (snow falls, leaves drift, sparkles follow the current). Frames are
 * scheduled through the drawable's own callback ([scheduleSelf]), only while it is visible,
 * [motionGate] allows it and the user touched the keyboard within [AuroraMotion.IDLE_MS].
 */
class BackdropDrawable(
    private val baseColor: Int,
    private val backdrop: LookBackdrop,
    private val density: Float,
    /** Asked before each start: false (battery saver, animations off) keeps the backdrop still. */
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
    private val ovalRect = RectF()
    private val paintingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** The still painting (curtains or scene frame 0); null when the backdrop paints neither. */
    private var painting: Bitmap? = null
    private val paintingDst = Rect()
    private var stars: List<Star> = emptyList()
    /** Whole-drawable alpha: the keyboard opacity setting (`Keyboard2View` sets `background.alpha`). */
    private var drawableAlpha = 0xFF

    // ---- Motion (off unless the user turns on "Animated scenes") ----
    /** The second image ([AuroraMotion.alternate] curtains, or scene frame 1); made when motion is enabled. */
    private var paintingAlt: Bitmap? = null
    private val paintingAltPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val driftDst = RectF()
    private val clock = AuroraMotionClock()
    private var ticking = false
    private val tick = Runnable { onTick() }
    /**
     * Each particle group recorded once ([RenderNode]s, API 29+), so a motion frame only sets a
     * group's alpha and offset instead of re-recording every particle; null until first drawn
     * moving, and after a size or colour-filter change. Held as Any: the field must not name an
     * API 29 class.
     */
    private var particleNodes: Array<Any>? = null

    private val hasPainting get() = backdrop.scene != null || backdrop.ribbons.isNotEmpty()

    /**
     * Whether the backdrop may move (set by `Keyboard2View` from the user's setting on every key
     * redraw). Turning it off stops at once and paints the still backdrop again.
     */
    var motionEnabled = false
        private set

    fun setMotionEnabled(enabled: Boolean) {
        if (enabled == motionEnabled) return
        motionEnabled = enabled
        if (enabled) {
            if (painting != null && paintingAlt == null) paintingAlt = altPaintingBitmap(bounds.width(), bounds.height())
            wake()
        } else {
            stopMotion()
            paintingAlt = null
        }
        invalidateSelf()
    }

    /**
     * The user is at the keyboard (a touch, the keyboard showing): move for the next
     * [AuroraMotion.IDLE_MS]. Cheap when already moving; [motionGate] is asked only to start.
     */
    fun wake() {
        if (!motionEnabled || !isVisible || painting == null) return
        val now = SystemClock.uptimeMillis()
        clock.wake(now)
        if (!ticking && motionGate()) {
            ticking = true
            scheduleSelf(tick, now)
        }
    }

    /** Stops scheduling frames; the backdrop rests where it is. */
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
        painting = if (hasPainting) paintingBitmap(bounds.width(), bounds.height()) else null
        paintingAlt = if (motionEnabled && painting != null) altPaintingBitmap(bounds.width(), bounds.height()) else null
        paintingDst.set(bounds)
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
        particleNodes = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // A translucent keyboard composites the whole painting once, so glows over the base do
        // not add up to more opacity than the base alone.
        val layer = if (drawableAlpha < 0xFF) canvas.saveLayerAlpha(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), drawableAlpha,
        ) else -1
        val still = painting
        val alt = paintingAlt
        val frame = if (motionEnabled && still != null && alt != null) {
            AuroraMotion.frame(clock.motionMs / 1000.0)
        } else null
        if (frame != null && still != null && alt != null) {
            drawDrifting(canvas, still, alt, frame)
        } else if (still != null) {
            canvas.drawBitmap(still, null, paintingDst, paintingPaint)
        } else {
            canvas.drawRect(b, basePaint)
        }
        for (p in glowPaints) if (p.shader != null) canvas.drawRect(b, p)
        val field = backdrop.stars
        if (field != null && frame != null && Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            drawParticleGroups(canvas, field, frame)
        } else if (field != null) {
            // Still (or software-drawn): every particle where it was placed, a moving one dimmed
            // by its group's twinkle.
            for (i in stars.indices) {
                val s = stars[i]
                val alpha = if (frame == null) s.alpha
                else (s.alpha * frame.starAlpha[i % AuroraMotion.STAR_GROUPS]).toInt()
                drawParticle(canvas, field, s, b.left + s.x, b.top + s.y, alpha)
            }
        }
        // Last, so particles and glows fade into the base colour where the suggestion bar meets the keys.
        if (fadePaint.shader != null) canvas.drawRect(b, fadePaint)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /** One particle of [field] at ([x], [y]) with [alpha], in its [ParticleShape]. */
    private fun drawParticle(canvas: Canvas, field: LookStarfield, s: Star, x: Float, y: Float, alpha: Int) {
        val rgb = if (s.tint != 0) s.tint else field.color and 0x00FFFFFF
        when (field.shape) {
            ParticleShape.STAR, ParticleShape.GLINT -> {
                starPaint.color = (alpha shl 24) or rgb
                canvas.drawCircle(x, y, s.radius, starPaint)
                if (s.glint || field.shape == ParticleShape.GLINT) {
                    // A faint four-point glint, about four radii long, at under half the alpha;
                    // a sparkle's arms are longer and brighter.
                    val sparkle = field.shape == ParticleShape.GLINT
                    glintPaint.strokeWidth = (if (sparkle) 0.7f else 0.5f) * density
                    glintPaint.color = ((alpha * (if (sparkle) 3 else 2) / 5) shl 24) or rgb
                    val arm = s.radius * (if (sparkle) 5f else 4f)
                    canvas.drawLine(x - arm, y, x + arm, y, glintPaint)
                    canvas.drawLine(x, y - arm, x, y + arm, glintPaint)
                }
            }
            ParticleShape.FLAKE, ParticleShape.MOTE -> {
                // A soft halo under a bright core.
                starPaint.color = ((alpha / 4) shl 24) or rgb
                canvas.drawCircle(x, y, s.radius * 2.2f, starPaint)
                starPaint.color = (alpha shl 24) or rgb
                canvas.drawCircle(x, y, s.radius, starPaint)
            }
            ParticleShape.LEAF -> {
                starPaint.color = (alpha shl 24) or rgb
                val save = canvas.save()
                canvas.rotate(s.angle, x, y)
                ovalRect.set(x - s.radius * 1.9f, y - s.radius * 0.8f, x + s.radius * 1.9f, y + s.radius * 0.8f)
                canvas.drawOval(ovalRect, starPaint)
                canvas.restoreToCount(save)
            }
        }
    }

    /**
     * A moving backdrop's particles: each twinkle group is a display list recorded once, drawn
     * at its group's brightness for this [frame] and moved by its drift (a property change, not a
     * re-recording). A drifting group is recorded four times, one view-size apart, so as it wraps
     * the view is always covered.
     */
    @RequiresApi(29)
    private fun drawParticleGroups(canvas: Canvas, field: LookStarfield, frame: AuroraMotionFrame) {
        val b = bounds
        val drifts = field.driftXDpPerS != 0f || field.driftYDpPerS != 0f
        val nodes = particleNodes ?: Array<Any>(AuroraMotion.STAR_GROUPS) { g ->
            RenderNode("BackdropParticles$g").apply {
                setPosition(b)
                // Particles never overlap enough to need an offscreen pass for the group alpha.
                setHasOverlappingRendering(false)
                val c = beginRecording()
                try {
                    val copies = if (drifts) WRAP_COPIES else NO_COPIES
                    for (k in copies.indices step 2) {
                        val dx = copies[k] * b.width() - b.left
                        val dy = copies[k + 1] * b.height() - b.top
                        for (i in g until stars.size step AuroraMotion.STAR_GROUPS) {
                            val s = stars[i]
                            drawParticle(c, field, s, b.left + s.x + dx, b.top + s.y + dy, s.alpha)
                        }
                    }
                } finally {
                    endRecording()
                }
            }
        }.also { particleNodes = it }
        val seconds = clock.motionMs / 1000.0
        val save = canvas.save()
        canvas.clipRect(b)
        for (g in nodes.indices) {
            val node = nodes[g] as RenderNode
            node.setAlpha(frame.starAlpha[g])
            if (drifts) {
                // Near groups travel faster than far ones: 0.7×, 1× and 1.3× of the drift.
                val speed = 0.7f + 0.3f * g
                node.translationX = AuroraMotion.wrap((field.driftXDpPerS * speed * density * seconds).toFloat(), b.width())
                node.translationY = AuroraMotion.wrap((field.driftYDpPerS * speed * density * seconds).toFloat(), b.height())
            }
            canvas.drawRenderNode(node)
        }
        canvas.restoreToCount(save)
    }

    /**
     * The two images of the painting, each stretched [AuroraMotion.DRIFT_MARGIN_DP] past both
     * edges (a stretch of about 1 %, invisible on a soft painting) and shifted by its drift; the
     * second over the first at the cross-fade. Both are opaque, so the result is their blend.
     */
    private fun drawDrifting(canvas: Canvas, a: Bitmap, alt: Bitmap, frame: AuroraMotionFrame) {
        val b = bounds
        val margin = AuroraMotion.DRIFT_MARGIN_DP * density
        val save = canvas.save()
        canvas.clipRect(b)
        driftDst.set(b.left - margin + frame.driftADp * density, b.top.toFloat(),
            b.right + margin + frame.driftADp * density, b.bottom.toFloat())
        canvas.drawBitmap(a, null, driftDst, paintingPaint)
        val altAlpha = (frame.crossfade * 255).toInt()
        if (altAlpha > 0) {
            paintingAltPaint.alpha = altAlpha
            driftDst.set(b.left - margin + frame.driftBDp * density, b.top.toFloat(),
                b.right + margin + frame.driftBDp * density, b.bottom.toFloat())
            canvas.drawBitmap(alt, null, driftDst, paintingAltPaint)
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
        paintingPaint.colorFilter = colorFilter
        paintingAltPaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        starPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        particleNodes = null
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (alphaOf(baseColor) == 0xFF && drawableAlpha == 0xFF) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    /** The still painting at half the view's size, from the cache or rendered. */
    private fun paintingBitmap(widthPx: Int, heightPx: Int): Bitmap = paintingBitmap(0, widthPx, heightPx)

    /** The second image of a moving backdrop ([AuroraMotion.alternate] curtains, scene frame 1). */
    private fun altPaintingBitmap(widthPx: Int, heightPx: Int): Bitmap? =
        if (widthPx <= 0 || heightPx <= 0) null else paintingBitmap(1, widthPx, heightPx)

    private fun paintingBitmap(frame: Int, widthPx: Int, heightPx: Int): Bitmap {
        val w = (widthPx / PAINT_SCALE).coerceAtLeast(1)
        val h = (heightPx / PAINT_SCALE).coerceAtLeast(1)
        val scene = backdrop.scene
        val curtains = if (scene != null) emptyList()
        else if (frame == 0) backdrop.ribbons else AuroraMotion.alternate(backdrop.ribbons)
        val key = PaintKey(baseColor, curtains, scene, frame, w, h, density)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val raster = density / PAINT_SCALE
        val pixels = if (scene != null) SceneRaster.render(scene, w, h, raster, frame)
        else AuroraRaster.render(baseColor, curtains, w, h, raster)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    private data class PaintKey(
        val base: Int, val ribbons: List<AuroraRibbon>, val scene: SceneKind?, val frame: Int,
        val w: Int, val h: Int, val density: Float,
    )

    companion object {
        /** Paintings are soft; half resolution quarters the render cost and the memory. */
        private const val PAINT_SCALE = 2

        /** Offsets, in view sizes, of the four copies of a drifting particle group. */
        private val WRAP_COPIES = intArrayOf(0, 0, -1, 0, 0, -1, -1, -1)
        private val NO_COPIES = intArrayOf(0, 0)

        /**
         * The last few rendered sizes (portrait, landscape, a split screen; two images each while
         * the backdrop moves), shared by every instance: the keyboard view and its drawable are
         * re-created on theme and layout changes, and each render walks every pixel. An
         * access-ordered map evicting the eldest.
         */
        private val cache = object : LinkedHashMap<PaintKey, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PaintKey, Bitmap>?) = size > 6
        }

        /**
         * Whether the system lets the backdrop move now: Android 10+ (`Keyboard2View` caches its
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
