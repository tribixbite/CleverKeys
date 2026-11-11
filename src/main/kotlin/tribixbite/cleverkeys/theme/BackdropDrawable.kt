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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
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
 * the user touched or opened the keyboard within [SceneMotionTuning.idleMs]; a key press holds it
 * still until [SceneMotionTuning.resumeMs] after the last one ([touch]). A frame that would show
 * nothing new is skipped ([VisibleChangeGate]).
 */
class BackdropDrawable(
    private val baseColor: Int,
    private val backdrop: LookBackdrop,
    private val density: Float,
    /**
     * Asked before each start and every [MotionAllowance.RECHECK_MS] while moving: false (battery
     * saver, animations off, a warm phone, a low battery) keeps the backdrop still.
     */
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
    /** A rain field's bead, shadow, wet-spot and glint textures, drawn once on first use ([splashSprites]). */
    private var sprites: SplashSprites? = null
    private val spriteDst = RectF()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
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

    /** The scene's own time between motion frames ([SceneKind.frameMs], or the aurora's). */
    private val sceneFrameMs = backdrop.scene?.frameMs ?: AuroraMotion.AURORA_FRAME_MS

    /** Time between motion frames: the user's frame rate ([SceneMotionTuning.fps]) or the scene's. */
    private val frameMs: Long get() = tuning.frameMs(sceneFrameMs)

    /** How far the scene's layers move: the user's intensity times the scene's own share. */
    private val layerIntensity: Float get() = tuning.intensity * (backdrop.scene?.motionScale ?: 1f)

    /** How the cross-faded images move. */
    private val motionStyle = MotionStyle.forScene(backdrop.scene)

    // ---- Motion (off unless the user turns on "Animated scenes") ----
    private val paintingAltPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val driftDst = RectF()
    private val clock = AuroraMotionClock()
    private var ticking = false
    private val tick = Runnable { onTick() }
    /** When [motionGate] was last asked while moving (uptime ms). */
    private var gateCheckedAt = 0L
    /** When [motionGate] last said no ([NEVER]: it has not, or said yes since). */
    private var gateDeniedAt = NEVER
    /** What the last drawn motion frame showed, so an unchanged frame is skipped. */
    private val shownGate = VisibleChangeGate()
    /** The visible state of a motion frame ([visibleState]), reused every tick. */
    private var stateScratch = FloatArray(0)
    /** Per-layer mesh vertices, reused across frames. */
    private var meshes: Array<FloatArray> = emptyArray()
    /** One displacement, reused for every mesh point of every frame. */
    private val meshScratch = FloatArray(2)
    private val flowDst = RectF()
    /**
     * Each particle group recorded once ([RenderNode]s, API 29+), so a motion frame only sets a
     * group's alpha and offset instead of re-recording every particle; null until first drawn
     * moving, and after a size or colour-filter change. Held as Any: the field must not name an
     * API 29 class.
     */
    private var particleNodes: Array<Any>? = null

    /**
     * The display-hardware layers ([SceneOverlay], API 33+) while they show the curtains and
     * stars under the window; null while this drawable paints them itself. Held as Any: the field
     * must not name an API 33 class.
     */
    private var overlay: Any? = null

    /** The platform refused the layers once (no surface support, a driver error): paint from then on. */
    private var overlayRefused = false

    /** A build is waiting for its anchor's surface ([SceneOverlay.NotReady]): retried each tick. */
    private var overlayPending = false

    /** A colour filter is set (theme previews tint the backdrop): the layers cannot apply it. */
    private var hasColorFilter = false

    /**
     * Inside [onBoundsChange]: a cached painting can arrive (and wake the motion) before the stars
     * are placed for the new size, so the layers are built once at its end, not from that wake.
     */
    private var resizing = false

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
     * or [SceneMotionTuning.trueBlack] paints the backdrop again (new resolution, or new colours).
     */
    fun setTuning(value: SceneMotionTuning) {
        if (value == tuning) return
        val repaint = value.crisp != tuning.crisp || value.trueBlack != tuning.trueBlack
        // The layers vote their frame rate when built: a new rate rebuilds them (below).
        if (value.fps != tuning.fps) releaseOverlay()
        tuning = value
        clock.rate = value.speed
        clock.holdMs = value.resumeMs
        clock.idleMs = value.idleMs
        if (repaint && !bounds.isEmpty) requestPainting(bounds.width(), bounds.height())
        if (!bounds.isEmpty) placeFade(bounds, bounds.width().toFloat(), bounds.height().toFloat())
        syncOverlay()
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
     * [SceneMotionTuning.idleMs]. Cheap when already moving; [motionGate] is asked only to start.
     * Before the painting is ready only the clock is woken; its delivery starts the frames.
     */
    fun wake() {
        if (!motionEnabled || !isVisible) return
        val now = SystemClock.uptimeMillis()
        clock.wake(now)
        syncOverlay()
        startTicking(now, throttled = false)
    }

    /**
     * A key press: hold still while the user types, and move again [SceneMotionTuning.resumeMs]
     * after the last press (and on for [SceneMotionTuning.idleMs]). A typing burst therefore costs the
     * two or three frames of slowing down, not a frame rate.
     */
    fun touch() {
        if (!motionEnabled || !isVisible) return
        val now = SystemClock.uptimeMillis()
        clock.touch(now)
        startTicking(now, throttled = true)
    }

    /**
     * Starts the frames if [motionGate] allows. [throttled] (touches: a swipe calls this for every
     * move event): while the gate says no, it is asked again only every few seconds, as each ask
     * is binder calls; the keyboard showing always asks.
     */
    private fun startTicking(now: Long, throttled: Boolean) {
        if (ticking || painting == null) return
        if (throttled && gateDeniedAt != NEVER && now - gateDeniedAt < MotionAllowance.RECHECK_MS) return
        gateCheckedAt = now
        if (!motionGate()) {
            gateDeniedAt = now
            return
        }
        gateDeniedAt = NEVER
        ticking = true
        scheduleSelf(tick, now)
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
        releaseOverlay()
    }

    private fun releaseParticleNodes() {
        val nodes = particleNodes ?: return
        particleNodes = null
        if (Build.VERSION.SDK_INT >= 29) for (n in nodes) (n as RenderNode).discardDisplayList()
    }

    private fun onTick() {
        if (!ticking) return
        val now = SystemClock.uptimeMillis()
        // Battery saver switched on, the phone warmed up or the battery ran low while moving:
        // rest where it is (asked every few seconds, not per frame: each ask is binder calls).
        if (now - gateCheckedAt >= MotionAllowance.RECHECK_MS) {
            gateCheckedAt = now
            if (!motionGate()) {
                gateDeniedAt = now
                ticking = false
                clock.rest()
                return
            }
        }
        val moving = clock.advance(now)
        // The count first: visibleState() may replace stateScratch with a larger array, and an
        // argument list reads stateScratch before calling it (the first tick crashed the IME).
        val count = visibleState()
        if (shownGate.changed(stateScratch, count)) {
            // On hardware layers a frame is a transaction of positions and alphas; otherwise the
            // window draws it.
            if (overlay != null) applyOverlayFrame(onDraw = false) else invalidateSelf()
        }
        // Layers that could not be built yet (the window had no surface when it showed).
        if (overlayPending && overlay == null) syncOverlay()
        when {
            moving -> scheduleSelf(tick, now + frameMs)
            // Stopped by typing: nothing to draw until the hold ends, so sleep until then (a
            // further key press moves the end later; the early tick just sleeps again).
            clock.isHeld(now) -> scheduleSelf(tick, clock.holdEndsAt)
            else -> ticking = false
        }
    }

    /**
     * What a motion frame at the clock's time would show, written to [stateScratch] in
     * [VisibleChangeGate] steps; returns the count. The curtains' drifts and cross-fade, each
     * star group's brightness and travel, and each moving layer's alpha and the displacement of
     * a 3 × 3 grid of its points (its corners include the free ends, where a sway is widest).
     * Rain is new every frame (drops strike, beads fly, spots dry): it counts as changed.
     */
    private fun visibleState(): Int {
        val field = backdrop.stars
        val rendered = painting
        val layers = rendered?.layers.orEmpty()
        val need = 3 + 3 * AuroraMotion.STAR_GROUPS + layers.size * (1 + 2 * GRID * GRID) + 1
        if (stateScratch.size < need) stateScratch = FloatArray(need)
        val out = stateScratch
        val px = 1f / VisibleChangeGate.POSITION_STEP_PX
        val level = 255f / VisibleChangeGate.ALPHA_STEP
        val seconds = clock.motionMs / 1000.0
        var n = 0
        val frame = AuroraMotion.frame(seconds, motionStyle, tuning)
        if (rendered?.alt != null) {
            out[n++] = frame.driftADp * density * px
            out[n++] = frame.driftBDp * density * px
            out[n++] = frame.crossfade * level
        }
        if (field != null && field.shape != ParticleShape.RAIN && stars.isNotEmpty()) {
            val starLevel = field.maxAlpha / VisibleChangeGate.ALPHA_STEP
            for (g in 0 until AuroraMotion.STAR_GROUPS) {
                out[n++] = frame.starAlpha[g] * starLevel
                if (field.driftXDpPerS != 0f || field.driftYDpPerS != 0f) {
                    val speed = 0.7f + 0.6f * g / (AuroraMotion.STAR_GROUPS - 1)
                    out[n++] = (field.driftXDpPerS * speed * density * seconds).toFloat() * px
                    out[n++] = (field.driftYDpPerS * speed * density * seconds).toFloat() * px
                }
            }
        }
        if (rendered != null) {
            val scale = bounds.width().toFloat() / rendered.width.coerceAtLeast(1)
            val intensity = layerIntensity
            for ((layer, _) in layers) {
                out[n++] = SceneMotion.alpha(layer.motion, seconds) * level
                for (r in 0 until GRID) for (c in 0 until GRID) {
                    val x = layer.left + layer.width * c / (GRID - 1f)
                    val y = layer.top + layer.height * r / (GRID - 1f)
                    SceneMotion.displace(layer.motion, x, y, seconds, meshScratch)
                    out[n++] = meshScratch[0] * intensity * scale * px
                    out[n++] = meshScratch[1] * intensity * scale * px
                }
            }
        }
        // A counter that never repeats: a rain field draws every frame.
        if (field?.shape == ParticleShape.RAIN) out[n++] = seconds.toFloat() * 1e6f
        return n
    }

    /** Hidden (keyboard closed, view detached): stop; shown: move for a while. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        // Whatever was on screen is gone: the first frame after showing is always drawn.
        shownGate.reset()
        val changed = super.setVisible(visible, restart)
        if (!visible) stopMotion() else wake()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        // The layers were built for the old size and stars; rebuilt at the end for the new ones.
        releaseOverlay()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        resizing = true
        try {
            placeForBounds(bounds, w, h)
        } finally {
            resizing = false
        }
        syncOverlay()
    }

    /**
     * The colour under and above the painting (the fill before it is ready, the top fade, the
     * aurora's sky): the look's base, or black in true black, so those pixels are off too.
     */
    private val shownBaseColor: Int
        get() = if (tuning.trueBlack) baseColor and 0xFF000000.toInt() else baseColor

    /**
     * The live glows and the top fade for a [w] × [h] view at [bounds], in [shownBaseColor] (true
     * black has no haze). Only the faded band is filled: the rest of a full-view rect would be
     * shaded by the GPU every frame to no effect.
     */
    private fun placeFade(bounds: Rect, w: Float, h: Float) {
        backdrop.glows.forEachIndexed { i, g ->
            glowPaints[i].shader = if (tuning.trueBlack) null else glowShader(g, bounds.left.toFloat(), bounds.top.toFloat(), w, h)
        }
        val fadeH = backdrop.topFade.coerceIn(0f, 1f) * h
        val color = shownBaseColor
        basePaint.color = color
        fadeRect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + fadeH)
        fadePaint.shader = if (fadeH > 0f) LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + fadeH,
            color, withAlpha(color, 0), Shader.TileMode.CLAMP,
        ) else null
    }

    /** [onBoundsChange]'s work for a non-empty [bounds]: the painting, shaders, fade and particles. */
    private fun placeForBounds(bounds: Rect, w: Float, h: Float) {
        requestPainting(bounds.width(), bounds.height())
        paintingDst.set(bounds)
        shownGate.reset()
        placeFade(bounds, w, h)
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
        // Layers hold the old painting's images: built again for the new one (wake, below).
        releaseOverlay()
        painting = rendered
        meshes = rendered?.layers?.map { (layer, _) ->
            FloatArray(2 * (SceneMotion.meshColumns(layer.width) + 1) * (SceneMotion.meshRows(layer.height) + 1))
        }?.toTypedArray() ?: emptyArray()
        invalidateSelf()
        // A wake that came before the painting (keyboard just shown) starts the frames now.
        if (motionEnabled && rendered != null) wake()
    }

    // ---- Display-hardware layers (SceneOverlay) ----

    /** Whether the curtains may run on hardware layers now ([SceneOverlayPlan.eligible]). */
    private fun overlayEligible(): Boolean {
        val rendered = painting ?: return false
        val field = backdrop.stars
        return !overlayRefused && SceneOverlayPlan.eligible(
            sdk = Build.VERSION.SDK_INT,
            overlaySetting = tuning.overlay,
            motionEnabled = motionEnabled,
            hasAlt = rendered.alt != null,
            hasMeshLayers = rendered.layers.isNotEmpty(),
            rain = field?.shape == ParticleShape.RAIN,
            drawableAlpha = drawableAlpha,
            hasColorFilter = hasColorFilter,
            starsDrift = field != null && (field.driftXDpPerS != 0f || field.driftYDpPerS != 0f),
        )
    }

    /** The keyboard view this drawable is the background of, while it is on screen. */
    private fun shownHost(): View? = (callback as? View)?.takeIf {
        it.isAttachedToWindow && it.windowVisibility == View.VISIBLE
    }

    /** Builds the layers when they may run and are not up yet; releases them when they may not. */
    private fun syncOverlay() {
        if (!overlayEligible()) {
            releaseOverlay()
            return
        }
        if (overlay != null || resizing || !isVisible || Build.VERSION.SDK_INT < SceneOverlayPlan.MIN_SDK) return
        val host = shownHost() ?: return
        createOverlay(host)
    }

    @RequiresApi(SceneOverlayPlan.MIN_SDK)
    private fun createOverlay(host: View) {
        val rendered = painting ?: return
        val alt = rendered.alt ?: return
        // No surface yet: skip the star images rather than drawing them for nothing every tick.
        if (host.rootSurfaceControl == null) return
        val origin = IntArray(2).also { host.getLocationInWindow(it) }
        val images = listOf(rendered.base, alt) + starLayerImages()
        val built = try {
            SceneOverlay.create(host, images, overlayFrame(origin[0], origin[1], images.size - 2), 1000f / frameMs)
        } catch (e: SceneOverlay.NotReady) {
            // The anchor is still getting its surface: the next tick or wake tries again.
            overlayPending = true
            for (i in 2 until images.size) images[i].recycle()
            return
        } catch (e: Exception) {
            overlayPending = false
            overlayRefused = true
            for (i in 2 until images.size) images[i].recycle()
            return
        }
        overlayPending = false
        // The star images were copied to the GPU; their software copies are not needed.
        for (i in 2 until images.size) images[i].recycle()
        built.onMoved = { applyOverlayFrame(onDraw = true) }
        overlay = built
        invalidateSelf()
    }

    /** Moves the layers to the clock's frame: at once ([onDraw] false) or with the window's next frame. */
    private fun applyOverlayFrame(onDraw: Boolean) {
        if (Build.VERSION.SDK_INT < SceneOverlayPlan.MIN_SDK) return
        val ov = overlay as? SceneOverlay ?: return
        val origin = ov.hostOrigin()
        val frame = overlayFrame(origin[0], origin[1], starLayerCount())
        if (onDraw) ov.applyOnDraw(frame) else ov.apply(frame)
    }

    /**
     * The layers' placements at the clock's time for a view at ([originX], [originY]) in its
     * window: the curtains exactly where [drawDrifting] puts them, each star layer at its group's
     * twinkle.
     */
    private fun overlayFrame(originX: Int, originY: Int, starLayers: Int): List<OverlayPlacement> {
        val rendered = painting ?: return emptyList()
        val b = bounds
        val x = (originX + b.left).toFloat()
        val y = (originY + b.top).toFloat()
        val frame = AuroraMotion.frame(clock.motionMs / 1000.0, motionStyle, tuning)
        val margin = motionStyle.marginDp(tuning.intensity) * density
        val out = ArrayList<OverlayPlacement>(2 + starLayers)
        out += SceneOverlayPlan.curtain(x, y, b.width(), b.height(), rendered.base.width, rendered.base.height,
            margin, frame.driftADp * density, 1f)
        out += SceneOverlayPlan.curtain(x, y, b.width(), b.height(), rendered.base.width, rendered.base.height,
            margin, frame.driftBDp * density, frame.crossfade)
        for (k in 0 until starLayers) out += SceneOverlayPlan.stars(x, y, k, frame.starAlpha)
        return out
    }

    private fun starLayerCount(): Int = if (stars.isEmpty()) 0 else SceneOverlayPlan.STAR_LAYERS

    /**
     * The stars as [SceneOverlayPlan.STAR_LAYERS] transparent images of the view's size, at full
     * resolution (a star scaled up from the half-resolution curtains would blur), each holding
     * its twinkle groups at their full brightness: the layer's alpha does the twinkling.
     */
    private fun starLayerImages(): List<Bitmap> {
        val field = backdrop.stars ?: return emptyList()
        if (stars.isEmpty() || bounds.isEmpty) return emptyList()
        return List(SceneOverlayPlan.STAR_LAYERS) { k ->
            val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            val c = Canvas(image)
            for (i in stars.indices) {
                if (!SceneOverlayPlan.groupOnLayer(i % AuroraMotion.STAR_GROUPS, k)) continue
                val s = stars[i]
                drawParticle(c, field, s, s.x, s.y, s.alpha)
            }
            image
        }
    }

    /**
     * Takes the layers down; the window paints the backdrop again from its next frame. While the
     * keyboard shows, the layers leave in that same frame (no flash of the app below); a hiding
     * window loses its surface anyway, so they go at once.
     */
    private fun releaseOverlay() {
        val ov = overlay
        if (ov == null) {
            // An anchor waiting for its surface when the layers stopped being wanted.
            if (overlayPending) {
                overlayPending = false
                if (Build.VERSION.SDK_INT >= SceneOverlayPlan.MIN_SDK) (callback as? View)?.let { SceneOverlay.discardAnchor(it) }
            }
            return
        }
        overlay = null
        if (Build.VERSION.SDK_INT >= SceneOverlayPlan.MIN_SDK) (ov as SceneOverlay).release(onDraw = shownHost() != null)
        invalidateSelf()
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
        if (overlay != null) {
            // The display hardware shows the curtains and stars under this window; only what lies
            // over them is drawn here: live glows (none when baked) and the fade into the bar.
            val rendered = painting
            if (rendered == null || !rendered.glowsBaked) for (p in glowPaints) if (p.shader != null) canvas.drawRect(b, p)
            if (fadePaint.shader != null) canvas.drawRect(fadeRect, fadePaint)
            canvas.restoreToCount(clip)
            if (layer >= 0) canvas.restoreToCount(layer)
            return
        }
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
            drawSplashes(canvas, field, seconds, rendered)
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
            if (layer.motion.kind == LayerMotionKind.FLOW) {
                drawFlowing(canvas, layer, bitmap, seconds, scale, share)
                continue
            }
            if (layer.motion.kind == LayerMotionKind.STILL) {
                // A foreground that never moves: one bitmap where it was painted.
                val left = bounds.left + layer.left * scale
                val top = bounds.top + layer.top * scale
                layerPaint.alpha = 0xFF
                flowDst.set(left, top, left + layer.width * scale, top + layer.height * scale)
                canvas.drawBitmap(bitmap, null, flowDst, layerPaint)
                continue
            }
            val verts = meshes.getOrNull(i) ?: continue
            SceneMotion.mesh(layer, seconds, scale, verts, layerIntensity, meshScratch)
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
     * Rain landing on the scene at [seconds] ([RainSplashes]), seen from above. On stone (the
     * painting's [SurfaceMask]) a drop flashes where it strikes, leaves an uneven dark wet spot
     * that dries over its lifetime with a glint where its film catches the light, and flings a
     * few beads of water to one side ([RainSplashes.splatter]): each hops (the bead lifts off its
     * shadow), lands and leaves a small wet dot. On the water between the stones it spreads two
     * rings. A still scene shows the splashes of its first moment.
     */
    private fun drawSplashes(canvas: Canvas, field: LookStarfield, seconds: Double, rendered: Rendered?) {
        val b = bounds
        val n = RainSplashes.at(field, b.width(), b.height(), density, seconds, splashes, tuning.intensity.coerceAtLeast(0.25f))
        val sp = sprites ?: splashSprites(field).also { sprites = it }
        val surface = rendered?.surface
        val toRaster = if (rendered != null) rendered.width / b.width().toFloat() else 1f
        val rgb = field.color and 0x00FFFFFF
        // The wet spots and the dots the beads left first, under every flying bead and ring.
        for (j in 0 until n) {
            val o = j * RainSplashes.STRIDE
            val x = splashes[o]; val y = splashes[o + 1]
            if (surface != null && !surface.isSolid(x * toRaster, y * toRaster)) continue
            val age = splashes[o + 6]
            val size = splashes[o + 2]
            val a = splashes[o + 3]
            val seed = splashes[o + 5].toInt()
            // The spot spreads fast, then dries; one of the uneven shapes, picked by the drop.
            val r = size * (0.45f + 0.55f * (age / 0.2f).coerceAtMost(1f))
            spritePaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            spriteDst.set(b.left + x - r, b.top + y - r, b.left + x + r, b.top + y + r)
            // Darker, with the film's sheen on the light's side (in the sprite: on dark stone
            // the sheen, not the darkening, is what shows the stone is wet).
            canvas.drawBitmap(sp.wet[seed % sp.wet.size], null, spriteDst, spritePaint)
            if (age < RainSplashes.SPLATTER_S) continue
            // The beads have landed: a small wet dot where each came down.
            val beads = RainSplashes.splatter(seed, splashes[o + 7], beadScratch)
            spritePaint.alpha = (200 * a).toInt().coerceIn(0, 255)
            for (k in 0 until beads) {
                val q = k * RainSplashes.BEAD_STRIDE
                val bx = b.left + x + beadScratch[q] * beadScratch[q + 2] * size
                val by = b.top + y + beadScratch[q + 1] * beadScratch[q + 2] * size
                val br = beadRadius(beadScratch[q + 3]) * 1.4f
                spriteDst.set(bx - br, by - br, bx + br, by + br)
                canvas.drawBitmap(sp.wet[(seed + k) % sp.wet.size], null, spriteDst, spritePaint)
            }
        }
        glintPaint.strokeWidth = 0.7f * density
        for (j in 0 until n) {
            val o = j * RainSplashes.STRIDE
            val x = b.left + splashes[o]; val y = b.top + splashes[o + 1]
            val age = splashes[o + 6]
            val size = splashes[o + 2]
            val onStone = surface == null || surface.isSolid(splashes[o] * toRaster, splashes[o + 1] * toRaster)
            if (onStone) {
                if (age >= RainSplashes.SPLATTER_S) continue
                // The strike: a brief bright point, not a disc.
                if (age < RainSplashes.FLASH_S) {
                    val f = 1f - age / RainSplashes.FLASH_S.toFloat()
                    val fr = 0.35f * size * (0.6f + 0.4f * f)
                    spritePaint.alpha = (230 * f).toInt().coerceIn(0, 255)
                    spriteDst.set(x - fr, y - fr, x + fr, y + fr)
                    canvas.drawBitmap(sp.glint, null, spriteDst, spritePaint)
                }
                // The beads in flight: out along their headings (fast, then slowing), each lifted
                // off its shadow by its hop, so it reads as leaving the stone and landing.
                val q = age / RainSplashes.SPLATTER_S.toFloat()
                val out = 1f - (1f - q) * (1f - q)
                val lift = 4f * q * (1f - q)
                val beads = RainSplashes.splatter(splashes[o + 5].toInt(), splashes[o + 7], beadScratch)
                for (k in 0 until beads) {
                    val p = k * RainSplashes.BEAD_STRIDE
                    val d = beadScratch[p + 2] * size * out
                    val gx = x + beadScratch[p] * d
                    val gy = y + beadScratch[p + 1] * d
                    val br = beadRadius(beadScratch[p + 3])
                    val h = lift * beadScratch[p + 4] * beadScratch[p + 2] * size
                    spritePaint.alpha = (120 * (1f - 0.5f * q)).toInt()
                    spriteDst.set(gx - br, gy - br * 0.8f, gx + br, gy + br * 0.8f)
                    canvas.drawBitmap(sp.shadow, null, spriteDst, spritePaint)
                    // Up is towards the light (upper left) as seen from above.
                    val bx = gx - 0.55f * h; val by = gy - 0.8f * h
                    spritePaint.alpha = 255
                    spriteDst.set(bx - br, by - br, bx + br, by + br)
                    canvas.drawBitmap(sp.bead, null, spriteDst, spritePaint)
                }
            } else {
                if (age >= RainSplashes.RIPPLE_S) continue
                // Two rings racing out and fading on the water; a small drop's are faint.
                val q = age / RainSplashes.RIPPLE_S.toFloat()
                val r = size * (0.15f + 1.1f * (1f - (1f - q) * (1f - q)))
                val strength = 0.45f + 0.55f * splashes[o + 7]
                val alpha = (field.maxAlpha * strength * (1f - q) * (1f - q)).toInt().coerceIn(0, 255)
                glintPaint.color = (alpha shl 24) or rgb
                canvas.drawCircle(x, y, r, glintPaint)
                if (q > 0.15f) {
                    glintPaint.color = ((alpha / 2) shl 24) or rgb
                    canvas.drawCircle(x, y, r * 0.55f, glintPaint)
                }
            }
        }
    }

    /**
     * A bead's radius in view px for its seeded [share] (0..1): 0.8–1.7 dp. Smaller beads were
     * lost against the stone's grain on a phone (Note10+, 2026-11-11).
     */
    private fun beadRadius(share: Float): Float = (0.8f + 0.9f * share) * density

    /**
     * The splash textures for [field], drawn once: [SplashSprites.bead], a clear bead of water
     * lit from the upper left (a dark rim, the colour of the field, a sharp highlight); its
     * [SplashSprites.shadow]; [SplashSprites.wet], a few uneven dark spots (a wet patch soaks
     * into rough stone unevenly, never a disc); and the [SplashSprites.glint] of a water film.
     */
    private fun splashSprites(field: LookStarfield): SplashSprites {
        val water = field.color or 0xFF000000.toInt()
        val bead = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(bead).apply {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            // Clear water: the stone shows through, darker at the rim, light gathered low right.
            p.shader = RadialGradient(12f, 12f, 12f,
                intArrayOf(withAlpha(water, 70), withAlpha(scaleRgb(water, 0.6f), 110), withAlpha(0xFF000000.toInt(), 150)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            drawCircle(12f, 12f, 11.5f, p)
            p.shader = RadialGradient(15f, 15.5f, 6f, intArrayOf(withAlpha(water, 120), withAlpha(water, 0)), null, Shader.TileMode.CLAMP)
            drawCircle(15f, 15.5f, 6f, p)
            p.shader = null
            p.color = 0xFAFFFFFF.toInt()
            drawCircle(8.5f, 8f, 3.4f, p)
        }
        val shadow = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(shadow).drawCircle(8f, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(8f, 8f, 8f, intArrayOf(0xC0000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        })
        val wet = List(WET_SHAPES) { v -> unevenSpot(v) }
        val glint = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(glint).drawCircle(16f, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(16f, 16f, 16f, intArrayOf(0xE6FFFFFF.toInt(), 0x50FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        })
        return SplashSprites(bead, shadow, wet, glint)
    }

    /** Pre-drawn splash textures ([splashSprites]). */
    private class SplashSprites(val bead: Bitmap, val shadow: Bitmap, val wet: List<Bitmap>, val glint: Bitmap)

    /** Each bead's layout ([RainSplashes.splatter]), reused for every splash of every frame. */
    private val beadScratch = FloatArray(RainSplashes.BEAD_STRIDE * RainSplashes.MAX_BEADS)

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

    /**
     * A [LayerMotionKind.FLOW] layer: two copies side by side, slid along by the flow (the layer
     * repeats across its width), so it runs on without a seam. Two bitmap draws, no mesh.
     */
    private fun drawFlowing(canvas: Canvas, layer: LayerPlacement, bitmap: Bitmap, seconds: Double, scale: Float, share: Float) {
        SceneMotion.displace(layer.motion, 0f, 0f, seconds, meshScratch)
        val wView = layer.width * scale
        val shift = (meshScratch[0] * layerIntensity.coerceAtLeast(0f) * scale) % wView
        val left = bounds.left + layer.left * scale + shift
        val top = bounds.top + layer.top * scale
        layerPaint.alpha = (255 * share).toInt().coerceIn(0, 255)
        flowDst.set(left, top, left + wView, top + layer.height * scale)
        canvas.drawBitmap(bitmap, null, flowDst, layerPaint)
        flowDst.offset(-wView, 0f)
        canvas.drawBitmap(bitmap, null, flowDst, layerPaint)
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
            syncOverlay()
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        paintingPaint.colorFilter = colorFilter
        paintingAltPaint.colorFilter = colorFilter
        layerPaint.colorFilter = colorFilter
        spritePaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        starPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        particleNodes = null
        hasColorFilter = colorFilter != null
        syncOverlay()
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        // On hardware layers the window shows them through transparent pixels here.
        if (overlay == null && alphaOf(baseColor) == 0xFF && drawableAlpha == 0xFF) PixelFormat.OPAQUE
        else PixelFormat.TRANSLUCENT

    private fun paintKey(widthPx: Int, heightPx: Int): PaintKey {
        val w = (widthPx / paintScale).coerceAtLeast(1)
        val h = (heightPx / paintScale).coerceAtLeast(1)
        // True black repaints a scene (SceneTrueBlack); the aurora keeps its curtains as they are
        // over a black sky, without the violet haze between them (maintainer 2026-11-11: "aurora
        // theme should get oled black too"; it was near black already: ~40 % less light, ~1 mW).
        val trueBlack = tuning.trueBlack
        val scene = backdrop.scene
        val glows = if (trueBlack && scene == null) emptyList() else backdrop.glows
        return PaintKey(shownBaseColor, backdrop.ribbons, glows, scene, w, h, density / paintScale, trueBlack && scene != null)
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
        /** Where rain splashes on stone rather than water ([ScenePainting.surface]). */
        val surface: SurfaceMask? = null,
    )

    private data class PaintKey(
        val base: Int, val ribbons: List<AuroraRibbon>, val glows: List<AuroraGlow>, val scene: SceneKind?,
        val w: Int, val h: Int, val rasterDensity: Float,
        /** Painted as [SceneTrueBlack] (scenes only). */
        val trueBlack: Boolean = false,
    )

    companion object {
        private const val TAG = "BackdropDrawable"

        /** The aurora's curtains are soft; half resolution quarters the render cost and the memory. */
        private const val AURORA_PAINT_SCALE = 2

        private const val NEVER = Long.MIN_VALUE

        /** How many uneven wet-spot shapes a rain field draws from ([unevenSpot]). */
        private const val WET_SHAPES = 6

        /**
         * Wet-spot shape [variant]: a dark patch whose edge wanders (three unrelated waves round
         * it), darker in the middle and soaking off softly at the edge, with the water film's
         * sheen — a bright crescent inside its upper-left edge and a hot spot of light — where it
         * faces the light. Pixels are unpremultiplied ARGB: dark and light are mixed per pixel.
         */
        private fun unevenSpot(variant: Int): Bitmap {
            val n = 48
            val px = IntArray(n * n)
            val c = n / 2f
            val ph = DoubleArray(6) { k -> ((variant * 2654435761L + k * 40503L) % 6283L) / 1000.0 }
            // The light comes from the upper left (as the stones are lit).
            val lightAngle = -0.75 * kotlin.math.PI
            for (y in 0 until n) for (x in 0 until n) {
                val dx = x + 0.5f - c; val dy = y + 0.5f - c
                val theta = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                val edge = 0.78 + 0.13 * kotlin.math.sin(2 * theta + ph[0]) + 0.08 * kotlin.math.sin(3 * theta + ph[1]) +
                    0.05 * kotlin.math.sin(5 * theta + ph[2]) + 0.03 * kotlin.math.sin(9 * theta + ph[3])
                val r = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) / c / edge
                if (r >= 1.0) continue
                // Darkest inside, with a soft edge where it soaks off.
                val dark = ((if (r < 0.6) 1.0 else 1.0 - (r - 0.6) / 0.4) * (0.62 + 0.1 * kotlin.math.sin(7 * theta + ph[4]) * r))
                    .coerceIn(0.0, 1.0) * 0.78
                // The sheen: a crescent just inside the edge facing the light, and a hot spot.
                val facing = kotlin.math.cos(theta - lightAngle).coerceAtLeast(0.0)
                val rim = (1.0 - kotlin.math.abs(r - 0.72) / 0.16).coerceIn(0.0, 1.0)
                val hx = dx / c + 0.32; val hy = dy / c + 0.36
                val hot = (1.0 - kotlin.math.sqrt(hx * hx + hy * hy) / 0.16).coerceIn(0.0, 1.0)
                val light = (0.55 * rim * facing * facing + 0.9 * hot).coerceIn(0.0, 1.0)
                val alpha = light + dark * (1 - light)
                if (alpha <= 0.0) continue
                val v = (255 * light / alpha).toInt().coerceIn(0, 255)
                px[y * n + x] = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (v shl 16) or (v shl 8) or v
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }

        /** Points per side of the grid sampled on each moving layer ([visibleState]). */
        private const val GRID = 3

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
                val painted = SceneRaster.paint(scene, key.w, key.h, key.rasterDensity)
                val p = if (key.trueBlack) SceneTrueBlack.apply(painted, scene, key.rasterDensity) else painted
                val layers = p.layers.map { it.placement to Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
                Rendered(
                    key.w, bitmap(p.base, key.w, key.h), p.alt?.let { bitmap(it, key.w, key.h) }, layers,
                    bytes = 4L * p.pixelCount, surface = p.surface,
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
         * Whether the system lets the backdrop move now ([MotionAllowance]): Android 10+
         * (`Keyboard2View` caches its keys in a RenderNode on motion frames, an API 29 class),
         * battery saver off, animations not switched off in the developer or accessibility
         * settings, the phone not warm and the battery not low (unless charging).
         */
        fun systemAllowsMotion(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            return MotionAllowance.allows(
                powerSave = power?.isPowerSaveMode == true,
                animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
                thermalStatus = power?.currentThermalStatus ?: 0,
                // CAPACITY is a percentage; Integer.MIN_VALUE (or 0 on some emulators) = not reported.
                batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it > 0 } ?: -1,
                charging = battery?.isCharging == true,
            )
        }

        private fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
        private fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
        private fun scaleRgb(color: Int, f: Float): Int {
            fun ch(shift: Int) = (((color shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
            return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
