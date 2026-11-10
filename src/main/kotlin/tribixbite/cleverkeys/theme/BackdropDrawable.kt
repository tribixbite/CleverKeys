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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Paints a [LookBackdrop] over [baseColor]: the keyboard view's background while a look whose
 * palette defines one is effective. The painting (aurora curtains, [AuroraRaster], or a nature
 * scene, [SceneRaster]) is rendered once per size on a background thread and cached across
 * instances, so a re-inflated keyboard does not render it again; until it is ready the base
 * colour shows. Shaders and particle positions are built in [onBoundsChange]; [draw] only replays
 * them, so the per-frame cost is a few bitmap blits, a handful of shader fills and the particles.
 *
 * With [motionEnabled] (the user's "Animated scenes") it also moves: the aurora, the stream and
 * the snow cross-fade a second image over the first while both drift ([AuroraMotion]); the other
 * scenes warp their own layers through a mesh ([SceneMotion]: grass bends, branches sway, waves
 * roll in and wash up); particles twinkle in groups and travel. Frames are scheduled through the
 * drawable's own callback ([scheduleSelf]), only while it is visible, [motionGate] allows it and
 * the user touched or opened the keyboard within [AuroraMotion.IDLE_MS].
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
    private val fadeRect = RectF()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ovalRect = RectF()
    /** The live splashes of a rain field ([RainSplashes.at]), reused every frame. */
    private val splashes: FloatArray =
        FloatArray(if (backdrop.stars?.shape == ParticleShape.RAIN) RainSplashes.STRIDE * LookStars.MAX_STARS else 0)
    /** A rain field's strike points and droplets per brightness bucket, reused every frame. */
    private val splashDots: Array<FloatArray> = Array(if (splashes.isEmpty()) 0 else SPLASH_LEVELS) {
        FloatArray(2 * (RainSplashes.DROPLETS + 1) * LookStars.MAX_STARS)
    }
    private val splashDotCount = IntArray(SPLASH_LEVELS)
    private val paintingPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** The rendered painting for the current size; null until the background render delivers it. */
    private var painting: Rendered? = null
    private val paintingDst = Rect()
    private var stars: List<Star> = emptyList()
    /** Whole-drawable alpha: the keyboard opacity setting (`Keyboard2View` sets `background.alpha`). */
    private var drawableAlpha = 0xFF
    /** Bumped on every new size: a render that finishes for an older size is dropped. */
    private var generation = 0

    /**
     * The user's tuning of the motion and the soft backdrops' resolution ([SceneMotionTuning];
     * `Keyboard2View` sets it from the settings with [setTuning]).
     */
    var tuning: SceneMotionTuning = SceneMotionTuning.DEFAULT
        private set

    /**
     * View px per painted px: the scene's own scale, or half resolution for the soft curtains;
     * full resolution for every backdrop with [SceneMotionTuning.crisp].
     */
    private val paintScale: Int get() = if (tuning.crisp) 1 else backdrop.scene?.paintScale ?: AURORA_PAINT_SCALE

    /** Time between motion frames ([SceneKind.frameMs], or the aurora's). */
    private val frameMs = backdrop.scene?.frameMs ?: AuroraMotion.AURORA_FRAME_MS

    /** How the cross-faded images move. */
    private val motionStyle = if (backdrop.scene == null) MotionStyle.AURORA else MotionStyle.GENTLE

    // ---- Motion (off unless the user turns on "Animated scenes") ----
    private val paintingAltPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val driftDst = RectF()
    private val clock = AuroraMotionClock()
    private var ticking = false
    private val tick = Runnable { onTick() }
    /** Per-layer mesh vertices, reused across frames. */
    private var meshes: Array<FloatArray> = emptyArray()
    /** One displacement, reused for every mesh point of every frame. */
    private val meshScratch = FloatArray(2)
    /**
     * Each particle group recorded once ([RenderNode]s, API 29+), so a motion frame only sets a
     * group's alpha and offset instead of re-recording every particle; null until first drawn
     * moving, and after a size or colour-filter change. Held as Any: the field must not name an
     * API 29 class.
     */
    private var particleNodes: Array<Any>? = null

    private val hasPainting get() = backdrop.scene != null || backdrop.ribbons.isNotEmpty()

    /**
     * Whether the backdrop may move (set by `Keyboard2View` from the user's setting whenever it
     * changes and as the keyboard shows). Turning it off stops at once; the backdrop rests where
     * it is.
     */
    var motionEnabled = false
        private set

    /**
     * Applies the user's [SceneMotionTuning]: speed, intensity and twinkle from the next frame
     * (the clock's pace changes, its position does not jump); a change of [SceneMotionTuning.crisp]
     * paints the backdrop again at its new resolution.
     */
    fun setTuning(value: SceneMotionTuning) {
        if (value == tuning) return
        val repaint = value.crisp != tuning.crisp
        tuning = value
        clock.rate = value.speed
        if (repaint && !bounds.isEmpty) requestPainting(bounds.width(), bounds.height())
        invalidateSelf()
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (enabled == motionEnabled) return
        motionEnabled = enabled
        if (enabled) wake() else stopMotion()
        invalidateSelf()
    }

    /**
     * The user is at the keyboard (a touch, the keyboard showing): move for the next
     * [AuroraMotion.IDLE_MS]. Cheap when already moving; [motionGate] is asked only to start.
     * Before the painting is ready only the clock is woken; its delivery starts the frames.
     */
    fun wake() {
        if (!motionEnabled || !isVisible) return
        val now = SystemClock.uptimeMillis()
        clock.wake(now)
        if (!ticking && painting != null && motionGate()) {
            ticking = true
            scheduleSelf(tick, now)
        }
    }

    /**
     * Stops scheduling frames; the backdrop rests where it is. The particle groups' display lists
     * are freed too (they are recorded again when it moves): a hidden keyboard holds no GPU
     * memory for them.
     */
    fun stopMotion() {
        if (ticking) unscheduleSelf(tick)
        ticking = false
        clock.rest()
        releaseParticleNodes()
    }

    private fun releaseParticleNodes() {
        val nodes = particleNodes ?: return
        particleNodes = null
        if (Build.VERSION.SDK_INT >= 29) for (n in nodes) (n as RenderNode).discardDisplayList()
    }

    private fun onTick() {
        if (!ticking) return
        val now = SystemClock.uptimeMillis()
        val moving = clock.advance(now)
        invalidateSelf()
        if (moving) scheduleSelf(tick, now + frameMs) else ticking = false
    }

    /** Hidden (keyboard closed, view detached): stop; shown: move for a while. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) stopMotion() else wake()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        requestPainting(bounds.width(), bounds.height())
        paintingDst.set(bounds)
        backdrop.glows.forEachIndexed { i, g ->
            glowPaints[i].shader = glowShader(g, bounds.left.toFloat(), bounds.top.toFloat(), w, h)
        }
        val fadeH = backdrop.topFade.coerceIn(0f, 1f) * h
        // Only the faded band is filled: the rest of a full-view rect would be shaded by the GPU
        // every frame to no effect.
        fadeRect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + fadeH)
        fadePaint.shader = if (fadeH > 0f) LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + fadeH,
            baseColor, withAlpha(baseColor, 0), Shader.TileMode.CLAMP,
        ) else null
        // A rain field has no placed particles: its splashes come from RainSplashes each frame.
        stars = backdrop.stars?.takeIf { it.shape != ParticleShape.RAIN }
            ?.let { LookStars.place(it, bounds.width(), bounds.height(), density) }.orEmpty()
        particleNodes = null
    }

    /**
     * The painting for a [widthPx] × [heightPx] view: from the cache at once, or rendered on the
     * background thread and delivered on the main thread (the detailed scenes take about a
     * second; the IME must not wait for them).
     */
    private fun requestPainting(widthPx: Int, heightPx: Int) {
        val gen = ++generation
        if (!hasPainting) {
            setPainting(null)
            return
        }
        val key = paintKey(widthPx, heightPx)
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) {
            setPainting(cached)
            return
        }
        // Keep showing the old size's painting (stretched) rather than flashing the base colour.
        renderer.execute {
            val rendered = try {
                synchronized(cache) { cache[key] } ?: render(key).also { r -> synchronized(cache) { cache.put(key, r) } }
            } catch (t: Throwable) {
                // Out of memory or a bug: the plain base colour is a fine backdrop.
                Log.w(TAG, "backdrop render failed", t)
                null
            }
            mainHandler.post { if (gen == generation) setPainting(rendered) }
        }
    }

    private fun setPainting(rendered: Rendered?) {
        painting = rendered
        meshes = rendered?.layers?.map { (layer, _) ->
            FloatArray(2 * (SceneMotion.meshColumns(layer.width) + 1) * (SceneMotion.meshRows(layer.height) + 1))
        }?.toTypedArray() ?: emptyArray()
        invalidateSelf()
        // A wake that came before the painting (keyboard just shown) starts the frames now.
        if (motionEnabled && rendered != null) wake()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // A translucent keyboard composites the whole painting once, so glows over the base do
        // not add up to more opacity than the base alone.
        val layer = if (drawableAlpha < 0xFF) canvas.saveLayerAlpha(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), drawableAlpha,
        ) else -1
        val clip = canvas.save()
        canvas.clipRect(b)
        val rendered = painting
        val seconds = clock.motionMs / 1000.0
        val alt = rendered?.alt
        val frame = if (motionEnabled && rendered != null) AuroraMotion.frame(seconds, motionStyle, tuning) else null
        if (rendered == null) {
            canvas.drawRect(b, basePaint)
        } else if (frame != null && alt != null) {
            drawDrifting(canvas, rendered.base, alt, frame)
        } else {
            canvas.drawBitmap(rendered.base, null, paintingDst, paintingPaint)
        }
        if (rendered != null) drawLayers(canvas, rendered, seconds)
        // A painting carries its glows (baked in at render: they never move); only a backdrop
        // without one, or before it is ready, draws them live.
        if (rendered == null || !rendered.glowsBaked) for (p in glowPaints) if (p.shader != null) canvas.drawRect(b, p)
        val field = backdrop.stars
        if (field != null && field.shape == ParticleShape.RAIN) {
            drawSplashes(canvas, field, seconds)
        } else if (field != null && frame != null && Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated) {
            drawParticleGroups(canvas, field, frame)
        } else if (field != null) {
            // Still (or software-drawn): every particle where it was placed, a moving one dimmed
            // by its group's twinkle.
            if (frame == null && batchable(field)) drawStarBatch(canvas, field, 0, 1, b.left.toFloat(), b.top.toFloat())
            else for (i in stars.indices) {
                val s = stars[i]
                val alpha = if (frame == null) s.alpha
                else (s.alpha * frame.starAlpha[i % AuroraMotion.STAR_GROUPS]).toInt()
                drawParticle(canvas, field, s, b.left + s.x, b.top + s.y, alpha)
            }
        }
        // Last, so particles and glows fade into the base colour where the suggestion bar meets the keys.
        if (fadePaint.shader != null) canvas.drawRect(fadeRect, fadePaint)
        canvas.restoreToCount(clip)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /**
     * The scene's moving layers at [seconds] of motion time (0 while it has never moved: the still
     * picture), each warped through its mesh ([SceneMotion.mesh]) so blades bend and branches
     * swing rather than slide.
     */
    private fun drawLayers(canvas: Canvas, rendered: Rendered, seconds: Double) {
        val scale = bounds.width().toFloat() / rendered.width
        for ((i, pair) in rendered.layers.withIndex()) {
            val (layer, bitmap) = pair
            val share = SceneMotion.alpha(layer.motion, seconds)
            if (share <= 0.003f) continue
            val verts = meshes.getOrNull(i) ?: continue
            SceneMotion.mesh(layer, seconds, scale, verts, tuning.intensity, meshScratch)
            if (bounds.left != 0 || bounds.top != 0) {
                for (k in verts.indices step 2) { verts[k] += bounds.left; verts[k + 1] += bounds.top }
            }
            layerPaint.alpha = (255 * share).toInt().coerceIn(0, 255)
            canvas.drawBitmapMesh(
                bitmap, SceneMotion.meshColumns(layer.width), SceneMotion.meshRows(layer.height),
                verts, 0, null, 0, layerPaint,
            )
        }
    }

    /**
     * Rain landing on the scene at [seconds] ([RainSplashes]): per splash a ring spreading from
     * the strike, a bright point where the drop hit while it is fresh, and droplets flung out.
     * One draw call per ring plus [SPLASH_LEVELS] point batches a frame (was seven calls per
     * splash), from reused buffers; a still scene shows the splashes of its first moment.
     */
    private fun drawSplashes(canvas: Canvas, field: LookStarfield, seconds: Double) {
        val b = bounds
        val n = RainSplashes.at(field, b.width(), b.height(), density, seconds, splashes, tuning.intensity.coerceAtLeast(0.25f))
        val rgb = field.color and 0x00FFFFFF
        glintPaint.strokeWidth = 0.7f * density
        for (q in splashDots.indices) splashDotCount[q] = 0
        for (j in 0 until n) {
            val o = j * RainSplashes.STRIDE
            val x = b.left + splashes[o]; val y = b.top + splashes[o + 1]
            val r = splashes[o + 2]; val a = splashes[o + 3]; val k = splashes[o + 4]
            val alpha = (a * 255).toInt().coerceIn(0, 255)
            if (alpha == 0) continue
            // The ring: one stroke per splash, the only per-splash draw call.
            glintPaint.color = (alpha shl 24) or rgb
            canvas.drawCircle(x, y, r, glintPaint)
            // The strike point and the droplets go into a few point batches by brightness.
            val bucket = (a * SPLASH_LEVELS).toInt().coerceIn(0, SPLASH_LEVELS - 1)
            if (k < 0.3f) addSplashDot(bucket, x, y)
            val seed = splashes[o + 5].toInt()
            for (dI in 0 until RainSplashes.DROPLETS) {
                // Droplets on seeded headings, flung a little past the ring.
                val angle = ((seed * (dI + 3) * 2654435761L) and 0xFFFF) / 65535f * 6.2832f
                val reach = r * (1.15f + 0.35f * ((seed shr dI) and 3) / 3f)
                addSplashDot(bucket, x + reach * kotlin.math.cos(angle), y + reach * kotlin.math.sin(angle))
            }
        }
        starPaint.style = Paint.Style.STROKE
        starPaint.strokeCap = Paint.Cap.ROUND
        starPaint.strokeWidth = 1.5f * density
        for (q in 0 until SPLASH_LEVELS) {
            val count = splashDotCount[q]
            if (count == 0) continue
            starPaint.color = (((q + 0.5f) / SPLASH_LEVELS * 255 * 0.8f).toInt() shl 24) or rgb
            canvas.drawPoints(splashDots[q], 0, count, starPaint)
        }
        starPaint.style = Paint.Style.FILL
    }

    private fun addSplashDot(bucket: Int, x: Float, y: Float) {
        val c = splashDotCount[bucket]
        val arr = splashDots[bucket]
        if (c + 2 > arr.size) return
        arr[c] = x; arr[c + 1] = y
        splashDotCount[bucket] = c + 2
    }

    /** Stars and sparkles in one colour can be drawn in batches ([drawStarBatch]). */
    private fun batchable(field: LookStarfield) =
        (field.shape == ParticleShape.STAR || field.shape == ParticleShape.GLINT) && field.tints.isEmpty()

    /**
     * Every [step]th star from [start], offset by ([offX], [offY]), in a handful of draw calls
     * instead of one or three per star: dots grouped into [ALPHA_LEVELS] brightness ×
     * [RADIUS_LEVELS] size buckets as round points, glints into one line batch per brightness.
     * About 280 separate antialiased circles and lines a frame were the larger part of the
     * aurora's GPU command time (framestats on the Pixel 8 Pro, 2026-11-10). Runs when a group
     * is recorded, not per frame.
     */
    private fun drawStarBatch(canvas: Canvas, field: LookStarfield, start: Int, step: Int, offX: Float, offY: Float) {
        val sparkle = field.shape == ParticleShape.GLINT
        val rgb = field.color and 0x00FFFFFF
        val lowA = field.minAlpha.toFloat()
        val spanA = (field.maxAlpha - field.minAlpha).coerceAtLeast(1).toFloat()
        val lowR = field.minRadiusDp * density
        val spanR = ((field.maxRadiusDp - field.minRadiusDp) * density).coerceAtLeast(1e-3f)
        val dots = arrayOfNulls<FloatList>(ALPHA_LEVELS * RADIUS_LEVELS)
        val lines = arrayOfNulls<FloatList>(ALPHA_LEVELS)
        for (i in start until stars.size step step) {
            val s = stars[i]
            val a = ((s.alpha - lowA) / spanA * ALPHA_LEVELS).toInt().coerceIn(0, ALPHA_LEVELS - 1)
            val r = ((s.radius - lowR) / spanR * RADIUS_LEVELS).toInt().coerceIn(0, RADIUS_LEVELS - 1)
            val x = offX + s.x; val y = offY + s.y
            (dots[a * RADIUS_LEVELS + r] ?: FloatList().also { dots[a * RADIUS_LEVELS + r] = it }).add(x, y)
            if (s.glint || sparkle) {
                // About four radii long (five for a sparkle), at under half the alpha.
                val arm = s.radius * (if (sparkle) 5f else 4f)
                (lines[a] ?: FloatList().also { lines[a] = it }).apply {
                    add(x - arm, y); add(x + arm, y); add(x, y - arm); add(x, y + arm)
                }
            }
        }
        starPaint.style = Paint.Style.STROKE
        starPaint.strokeCap = Paint.Cap.ROUND
        for (a in 0 until ALPHA_LEVELS) {
            val alpha = (lowA + spanA * (a + 0.5f) / ALPHA_LEVELS).toInt().coerceIn(0, 255)
            for (r in 0 until RADIUS_LEVELS) {
                val pts = dots[a * RADIUS_LEVELS + r] ?: continue
                starPaint.strokeWidth = 2f * (lowR + spanR * (r + 0.5f) / RADIUS_LEVELS)
                starPaint.color = (alpha shl 24) or rgb
                canvas.drawPoints(pts.values, 0, pts.size, starPaint)
            }
            val arms = lines[a] ?: continue
            glintPaint.strokeWidth = (if (sparkle) 0.7f else 0.5f) * density
            glintPaint.color = ((alpha * (if (sparkle) 3 else 2) / 5) shl 24) or rgb
            canvas.drawLines(arms.values, 0, arms.size, glintPaint)
        }
        starPaint.style = Paint.Style.FILL
    }

    /** A growable float array (points for [drawStarBatch]). */
    private class FloatList {
        var values = FloatArray(32)
        var size = 0
        fun add(x: Float, y: Float) {
            if (size + 2 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = x; values[size++] = y
        }
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
            // Splashes are drawn by drawSplashes, never as placed particles.
            ParticleShape.RAIN -> Unit
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
        // The system frees the display lists of drawn nodes when the keyboard's window hides
        // (switching apps): a kept node then draws nothing, and the particles vanished after
        // the first app switch while the frames kept coming. Record again when that happened.
        if (particleNodes?.any { !(it as RenderNode).hasDisplayList() } == true) particleNodes = null
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
                        if (batchable(field)) {
                            drawStarBatch(c, field, g, AuroraMotion.STAR_GROUPS, b.left + dx.toFloat(), b.top + dy.toFloat())
                        } else for (i in g until stars.size step AuroraMotion.STAR_GROUPS) {
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
        for (g in nodes.indices) {
            val node = nodes[g] as RenderNode
            node.setAlpha(frame.starAlpha[g])
            if (drifts) {
                // Near groups travel faster than far ones: from 0.7× to 1.3× of the drift.
                val speed = 0.7f + 0.6f * g / (nodes.size - 1).coerceAtLeast(1)
                node.translationX = AuroraMotion.wrap((field.driftXDpPerS * speed * density * seconds).toFloat(), b.width())
                node.translationY = AuroraMotion.wrap((field.driftYDpPerS * speed * density * seconds).toFloat(), b.height())
            }
            canvas.drawRenderNode(node)
        }
    }

    /**
     * The two images of the painting, each stretched past both edges by the style's margin
     * ([MotionStyle.marginDp] at the user's intensity: a stretch of a few percent, invisible on a
     * soft painting) and
     * shifted by its drift; the second over the first at the cross-fade. Both are opaque, so the
     * result is their blend.
     */
    private fun drawDrifting(canvas: Canvas, a: Bitmap, alt: Bitmap, frame: AuroraMotionFrame) {
        val b = bounds
        val margin = motionStyle.marginDp(tuning.intensity) * density
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
        layerPaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        starPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        particleNodes = null
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (alphaOf(baseColor) == 0xFF && drawableAlpha == 0xFF) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    private fun paintKey(widthPx: Int, heightPx: Int): PaintKey {
        val w = (widthPx / paintScale).coerceAtLeast(1)
        val h = (heightPx / paintScale).coerceAtLeast(1)
        return PaintKey(baseColor, backdrop.ribbons, backdrop.glows, backdrop.scene, w, h, density / paintScale)
    }

    /** The painting and its second image (curtains: [AuroraMotion.alternate]) or moving layers. */
    private class Rendered(
        val width: Int,
        val base: Bitmap,
        val alt: Bitmap?,
        /** Each moving layer's placement and its bitmap; its pixel array is not kept. */
        val layers: List<Pair<LayerPlacement, Bitmap>>,
        /** Bytes held, for the cache's budget. */
        val bytes: Long,
        /** The backdrop's glows are painted into [base] and [alt]. */
        val glowsBaked: Boolean = false,
    )

    private data class PaintKey(
        val base: Int, val ribbons: List<AuroraRibbon>, val glows: List<AuroraGlow>, val scene: SceneKind?,
        val w: Int, val h: Int, val rasterDensity: Float,
    )

    companion object {
        private const val TAG = "BackdropDrawable"

        /** The aurora's curtains are soft; half resolution quarters the render cost and the memory. */
        private const val AURORA_PAINT_SCALE = 2

        /** Brightness buckets of a frame's splash droplets. */
        private const val SPLASH_LEVELS = 4

        /** Brightness and size buckets of a star batch: fine enough that no step shows. */
        private const val ALPHA_LEVELS = 6
        private const val RADIUS_LEVELS = 3

        /** Offsets, in view sizes, of the four copies of a drifting particle group. */
        private val WRAP_COPIES = intArrayOf(0, 0, -1, 0, 0, -1, -1, -1)
        private val NO_COPIES = intArrayOf(0, 0)

        /**
         * Cache budget: one full-resolution scene with its layers in both orientations, or a
         * scene and the curtains. Was 40 MB, which an IME process kept for good.
         */
        private const val CACHE_BYTES = 24L * 1024 * 1024

        /**
         * The last few paintings (portrait, landscape, a split screen), shared by every instance:
         * the keyboard view and its drawable are re-created on theme and layout changes, and each
         * render walks every pixel. Access-ordered; the eldest go once over [CACHE_BYTES] (the
         * newest always stays).
         */
        private val cache = object : LinkedHashMap<PaintKey, Rendered>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PaintKey, Rendered>?): Boolean {
                var total = values.sumOf { it.bytes }
                val it = entries.iterator()
                while (total > CACHE_BYTES && size > 1 && it.hasNext()) {
                    val e = it.next()
                    total -= e.value.bytes
                    it.remove()
                }
                return false
            }
        }

        /** One low-priority thread renders every painting, in order. */
        private val renderer: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "BackdropRender").apply { isDaemon = true }
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Memory is short or the keyboard's UI went away (`CleverKeysService.onTrimMemory`):
         * keep only the painting used last (the next show finds it at once); the rest is
         * painted again if it is ever needed.
         */
        fun trimCache() {
            synchronized(cache) {
                val it = cache.entries.iterator()
                // Access order: the eldest first, the newest last.
                while (cache.size > 1 && it.hasNext()) { it.next(); it.remove() }
            }
        }

        /** Renders [key] (on the render thread): the painting's bitmaps. */
        private fun render(key: PaintKey): Rendered {
            val scene = key.scene
            return if (scene != null) {
                val p = SceneRaster.paint(scene, key.w, key.h, key.rasterDensity)
                val layers = p.layers.map { it.placement to Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
                Rendered(
                    key.w, bitmap(p.base, key.w, key.h), p.alt?.let { bitmap(it, key.w, key.h) }, layers,
                    bytes = 4L * p.pixelCount,
                )
            } else {
                val base = AuroraRaster.render(key.base, key.ribbons, key.w, key.h, key.rasterDensity)
                val alt = AuroraRaster.render(key.base, AuroraMotion.alternate(key.ribbons), key.w, key.h, key.rasterDensity)
                Rendered(
                    key.w, withGlows(base, key), withGlows(alt, key), emptyList(), bytes = 8L * key.w * key.h,
                    glowsBaked = true,
                )
            }
        }

        private fun bitmap(pixels: IntArray, w: Int, h: Int): Bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        /**
         * [pixels] as a bitmap with the key's glows painted over it once, at the raster's size:
         * a static haze shaded per frame was a full-view gradient pass for nothing.
         */
        private fun withGlows(pixels: IntArray, key: PaintKey): Bitmap {
            if (key.glows.isEmpty()) return bitmap(pixels, key.w, key.h)
            val out = Bitmap.createBitmap(key.w, key.h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, key.w, 0, 0, key.w, key.h)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (g in key.glows) {
                paint.shader = glowShader(g, 0f, 0f, key.w.toFloat(), key.h.toFloat())
                canvas.drawRect(0f, 0f, key.w.toFloat(), key.h.toFloat(), paint)
            }
            return out
        }

        /**
         * One [AuroraGlow] as a shader over a [w] × [h] box at ([left], [top]): a circle of radius
         * rx, squashed to ry and tilted about its centre (an ellipse); the middle stop keeps a soft
         * core instead of a linear cone.
         */
        private fun glowShader(g: AuroraGlow, left: Float, top: Float, w: Float, h: Float): Shader {
            val cx = left + g.cx * w
            val cy = top + g.cy * h
            val rx = (g.rx * w).coerceAtLeast(1f)
            val ry = (g.ry * h).coerceAtLeast(1f)
            return RadialGradient(
                cx, cy, rx,
                intArrayOf(g.color, withAlpha(g.color, (alphaOf(g.color) * 0.45f).toInt()), withAlpha(g.color, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            ).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(1f, ry / rx, cx, cy)
                    postRotate(g.rotationDeg, cx, cy)
                })
            }
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
