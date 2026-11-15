package tribixbite.cleverkeys.theme

import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.AttachedSurfaceControl
import android.view.Surface
import android.graphics.PixelFormat
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi

/**
 * Where one hardware layer of the aurora goes on a frame: its top-left corner in the IME
 * window ([x], [y], px), the scale from its buffer to the screen, and its alpha.
 */
data class OverlayPlacement(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val alpha: Float)

/**
 * The pure half of [SceneOverlay]: when the display hardware may blend a backdrop, and where each
 * of its layers goes on a frame. Kept free of Android types so the JVM suite pins it
 * (`SceneOverlayPlanTest`).
 *
 * Why (Pixel 8 Pro, 2026-11-11): every animated scene cost ~9.5 mJ of compute per frame whatever
 * it drew (aurora +96.5 mW at 10 fps, keyboard untouched), because each frame redraws the whole
 * IME window through HWUI, the GPU and SurfaceFlinger. With the curtains and stars as fixed
 * buffers under the window, a frame is one [SurfaceControl.Transaction] of positions and alphas:
 * no view draw, no RenderThread, and no GPU where the composer takes the layers as overlay planes.
 */
object SceneOverlayPlan {

    /**
     * The stars split into this many layers, each holding every [STAR_LAYERS]th twinkle group
     * and dimmed by the first of them: two keep a shimmer while costing two composer planes
     * rather than one per group ([AuroraMotion.STAR_GROUPS]).
     */
    const val STAR_LAYERS = 2

    /**
     * The star layers for a twinkle of [twinkle] (0..1): [STAR_LAYERS], or one when the stars do
     * not twinkle (every group then shows at full alpha, so one layer is the same picture).
     * Round 16 (Pixel 8 Pro, 2026-11-12, Aurora 10 fps, keyboard untouched, 2 runs each): 4 layers
     * +188 mW over motion off, 3 layers (one star layer) +160, 2 (no stars) +165: the second star
     * layer costs ~28 mW, mostly memory traffic. One layer at the default twinkle would make
     * every star pulse together, so it is used only where it changes nothing on screen.
     */
    fun starLayers(twinkle: Float): Int = if (twinkle <= 0f) 1 else STAR_LAYERS

    /** First Android release with every call used ([AttachedSurfaceControl], setBuffer, setDataSpace). */
    const val MIN_SDK = 33

    /**
     * Whether the backdrop's motion may run on hardware layers: the user's setting
     * ([SceneMotionTuning.overlay]) on API [MIN_SDK]+, while it moves, for a cross-fading
     * painting without moving layers ([hasAlt], not [hasMeshLayers] or rain), at full keyboard
     * opacity (a translucent keyboard blends the curtains with each other before the app below;
     * separate layers cannot), without a colour filter (theme previews), and with particles that
     * stay put ([starsDrift] would need wrapped copies per layer), at [MIN_FRAME_MS] or faster
     * ([frameMs]). Anything else draws in the window as before.
     */
    fun eligible(
        sdk: Int,
        overlaySetting: Boolean,
        motionEnabled: Boolean,
        hasAlt: Boolean,
        hasMeshLayers: Boolean,
        rain: Boolean,
        drawableAlpha: Int,
        hasColorFilter: Boolean,
        starsDrift: Boolean,
        frameMs: Long,
    ): Boolean = sdk >= MIN_SDK && overlaySetting && motionEnabled && hasAlt && !hasMeshLayers && !rain &&
        drawableAlpha == 0xFF && !hasColorFilter && !starsDrift && frameMs <= MIN_FRAME_MS

    /**
     * The slowest frame interval the layers are used at: 10 fps. There they halve the motion's
     * cost on the Pixel 8 Pro (+152–197 mW over motion off vs +340–374 on the window path, rounds
     * 13–14). At 5–8 fps the window path already lets the panel rest between frames and costs
     * the same at every rate in that range (+178–187 mW, round 17), while the layers cost
     * +150–163 (rounds 13, 17; the window path measured +115 in round 13): at most ~20 mW saved.
     * On a Galaxy Tab S11 (MediaTek, 2026-11-12) the four layers exceeded its display hardware's
     * planes, so they and the keyboard were all composed on the GPU and the panel stayed at
     * 120 Hz, where the window path kept the keyboard on the display hardware at 60 Hz.
     */
    const val MIN_FRAME_MS = 100L

    /**
     * A curtain image of [bufferW] × [bufferH] px drawn into the view at ([viewX], [viewY]) of
     * size [width] × [height], stretched past both side edges by [marginPx] and shifted sideways
     * by [driftPx]: exactly the rectangle `BackdropDrawable.drawDrifting` fills.
     */
    fun curtain(
        viewX: Float, viewY: Float, width: Int, height: Int,
        bufferW: Int, bufferH: Int, marginPx: Float, driftPx: Float, alpha: Float,
    ) = OverlayPlacement(
        x = viewX - marginPx + driftPx,
        y = viewY,
        scaleX = (width + 2 * marginPx) / bufferW.coerceAtLeast(1),
        scaleY = height.toFloat() / bufferH.coerceAtLeast(1),
        alpha = alpha.coerceIn(0f, 1f),
    )

    /**
     * Star layer [layer] of [layers] (full resolution, the view's size) at its group's twinkle;
     * a single layer holds every group and takes their mean.
     */
    fun stars(viewX: Float, viewY: Float, layer: Int, starAlpha: FloatArray, layers: Int = STAR_LAYERS) = OverlayPlacement(
        x = viewX, y = viewY, scaleX = 1f, scaleY = 1f,
        alpha = (if (layers == 1) starAlpha.average().toFloat() else starAlpha.getOrElse(layer) { 1f }).coerceIn(0f, 1f),
    )

    /** Whether twinkle group [group] is drawn on star layer [layer] of [layers]. */
    fun groupOnLayer(group: Int, layer: Int, layers: Int = STAR_LAYERS): Boolean = group % layers == layer

    /**
     * The clockwise rotation, in degrees, to draw a layer's image into its buffer with, for the
     * window's buffer transform hint [hint] (`SurfaceControl.BUFFER_TRANSFORM_*`, the display's own
     * rotation): the hint's turn, so the buffer already lies the way the panel scans out, and the
     * layer declares the inverse ([bufferTransformFor]) so its content shows upright. Without it a
     * rotated screen left every layer to the GPU (Pixel 8 Pro, 2026-11-12: all four layers
     * CLIENT, ROT_90, in landscape). Declaring the hint itself instead also showed upright but left
     * a net ROT_180 for the composer. 0 for the identity or a mirror (not a rotation).
     */
    fun preRotationDegrees(hint: Int): Int = when (hint) {
        BUFFER_TRANSFORM_ROTATE_90 -> 90
        BUFFER_TRANSFORM_ROTATE_180 -> 180
        BUFFER_TRANSFORM_ROTATE_270 -> 270
        else -> 0
    }

    /** The buffer transform a layer declares for [hint]: the inverse rotation, none otherwise. */
    fun bufferTransformFor(hint: Int): Int = when (hint) {
        BUFFER_TRANSFORM_ROTATE_90 -> BUFFER_TRANSFORM_ROTATE_270
        BUFFER_TRANSFORM_ROTATE_180 -> BUFFER_TRANSFORM_ROTATE_180
        BUFFER_TRANSFORM_ROTATE_270 -> BUFFER_TRANSFORM_ROTATE_90
        else -> 0
    }

    /** `SurfaceControl.BUFFER_TRANSFORM_*` values (API 33), here so the plan stays Android-free. */
    const val BUFFER_TRANSFORM_ROTATE_90 = 4
    const val BUFFER_TRANSFORM_ROTATE_180 = 3
    const val BUFFER_TRANSFORM_ROTATE_270 = 7

    /** What [BackdropDrawable] does with its layers when asked to bring them in line ([sync]). */
    enum class Sync {
        /** Take the layers down (or drop an anchor still waiting for its surface). */
        RELEASE,
        /** They are up and may stay. */
        KEEP,
        /** They may run but cannot be built now (mid-resize, hidden, not on screen): ask again later. */
        WAIT,
        /** Build them. */
        CREATE,
    }

    /**
     * The layers' lifecycle in one rule (Fable audit 2026-11-12: the release-then-create paths had
     * produced two bugs). They exist only while the backdrop moves ([ticking]) and may run
     * ([eligible]): a resting or gated keyboard is one window plane, not four hardware planes
     * holding a picture that does not change. Up ([up]) they stay; otherwise they are built only
     * when the drawable is not mid-resize ([resizing]), is [visible] and its view is on screen
     * ([hostShown]).
     */
    fun sync(ticking: Boolean, eligible: Boolean, up: Boolean, resizing: Boolean, visible: Boolean, hostShown: Boolean): Sync = when {
        !ticking || !eligible -> Sync.RELEASE
        up -> Sync.KEEP
        resizing || !visible || !hostShown -> Sync.WAIT
        else -> Sync.CREATE
    }

    /**
     * The layers' z among themselves, bottom first: curtain A, curtain B, then the star layers.
     * They are children of an empty SurfaceView's surface, which the platform stacks under the
     * window; the app cannot put a layer under its own window any other way (a negative z under
     * [AttachedSurfaceControl] still lands above the window's buffer: Pixel 8 Pro, 2026-11-11, the
     * curtains covered the keys).
     */
    fun zOrder(index: Int): Int = index + 1

    /**
     * Runs one build of the layers ([build]) and says what came of it: the layers, a wait (the
     * window or its anchor has no surface yet: [isNotReady]), or a refusal for good. Review
     * 2026-11-14 M18: the build copies view-sized bitmaps to the GPU on the main thread, and an
     * [OutOfMemoryError] there is an Error, not an Exception: it went past the old
     * `catch (e: Exception)` and took the keyboard down. Any Throwable now refuses the layers
     * (the backdrop is then painted in the window, as on a device without them).
     */
    fun <O> attemptBuild(isNotReady: (Throwable) -> Boolean, build: () -> O): OverlayBuild<O> =
        try {
            OverlayBuild.Built(build())
        } catch (e: Throwable) {
            if (isNotReady(e)) OverlayBuild.Waiting else OverlayBuild.Refused(e)
        }

    /**
     * Builds [count] parts in order with [make] (a layer: its image drawn, copied to the GPU, its
     * SurfaceControl made), so only one part's software image exists at a time. When any part
     * fails, those already built are freed with [free] (each on its own: one failing free does not
     * keep the rest) and the failure is rethrown. Review 2026-11-14 M18: the old cleanup sat in a
     * `catch (e: Exception)`, so an [OutOfMemoryError] skipped it and leaked every buffer built.
     */
    fun <T> buildEach(count: Int, make: (Int) -> T, free: (T) -> Unit): List<T> {
        val built = ArrayList<T>(count)
        try {
            for (i in 0 until count) built += make(i)
        } catch (e: Throwable) {
            for (part in built) runCatching { free(part) }
            throw e
        }
        return built
    }
}

/** What came of one attempt to build the layers ([SceneOverlayPlan.attemptBuild]). */
sealed class OverlayBuild<out O> {
    /** The layers are up. */
    class Built<O>(val overlay: O) : OverlayBuild<O>()
    /** The window or its anchor has no surface yet: try again on a later tick. */
    object Waiting : OverlayBuild<Nothing>()
    /** The platform refused the layers, or memory ran out: paint in the window from now on. */
    class Refused(val cause: Throwable) : OverlayBuild<Nothing>()
}

/**
 * Frees a released overlay's layers once, after the transaction that takes them off the window
 * has been queued on the window's next frame (pure; `SceneOverlayPlanTest`): on that
 * transaction's commit ([committed]) or, when the window never draws again (it hid before its
 * next frame), after [FALLBACK_MS] ([queued] arms it; review 2026-11-14 M20: four SurfaceControls
 * and their hardware buffers stayed alive behind a commit that never came). [free] gets whether
 * the fallback fired: the queued transaction never applied, so the layers are still attached and
 * must be taken off directly. Main thread only; [postDelayed] and [cancel] are the main Handler's.
 */
internal class QueuedFree(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val free: (timedOut: Boolean) -> Unit,
) {
    private var done = false
    private val fallback = Runnable { finish(timedOut = true) }

    /** The release transaction went to the window's next frame: free on its commit or after [FALLBACK_MS]. */
    fun queued() {
        if (!done) postDelayed(fallback, FALLBACK_MS)
    }

    /** The release transaction committed: free now (once). */
    fun committed() = finish(timedOut = false)

    private fun finish(timedOut: Boolean) {
        if (done) return
        done = true
        cancel(fallback)
        free(timedOut)
    }

    companion object {
        /**
         * How long a queued release waits for the window's frame: ~15 frames at 60 Hz. A shown
         * keyboard draws its next frame within one or two; one that has not drawn by then is hiding.
         */
        const val FALLBACK_MS = 250L
    }
}

/**
 * Which overlay owns each anchor (pure; `AnchorOwnersTest`). An overlay rebuilt in one pass (a
 * new size, painting or tuning) claims the anchor before its predecessor's release has
 * committed: the newest claim wins, and only the current owner may remove the anchor when it
 * frees, or the successor's layers would lose their parent (Fable audit, finding 1). Weak keys,
 * so a dropped anchor is not kept. Main thread only.
 */
internal class AnchorOwners<A : Any, O : Any> {
    private val owners = java.util.WeakHashMap<A, O>()

    /** [owner] now owns [anchor], whoever did before. */
    fun claim(anchor: A, owner: O) {
        owners[anchor] = owner
    }

    /** [owner] is done with [anchor]: true when it was the owner, i.e. the anchor may go. */
    fun release(anchor: A, owner: O): Boolean {
        if (owners[anchor] !== owner) return false
        owners.remove(anchor)
        return true
    }

    /** Whether any overlay owns [anchor] (an unowned one is a leftover that may be removed). */
    fun isOwned(anchor: A): Boolean = owners[anchor] != null

    /**
     * A build on [anchor] failed: drops [owner]'s claim if the build got as far as making one
     * (null: it did not), and says whether the anchor may go. Review 2026-11-14 (low): the failure
     * path removed the anchor unconditionally, under a predecessor whose release was still
     * queued on it; like [release], only an anchor nobody owns may be removed.
     */
    fun abandon(anchor: A, owner: O?): Boolean {
        if (owner != null) release(anchor, owner)
        return !isOwned(anchor)
    }
}

/**
 * The aurora's curtains and stars as [SurfaceControl] layers under the keyboard's window, moved
 * by transactions instead of window frames ([SceneOverlayPlan] explains why and when). The layers
 * are children of [anchor], an empty SurfaceView added at the bottom of the window's view tree:
 * the platform stacks a SurfaceView's surface under its window, which is the only public way to
 * put layers beneath the keys. The anchor never draws and sits under every view, so it takes no
 * touches. Owns the anchor, the layers and their hardware buffers; [release] frees all of them.
 * Created on the main thread with the host view attached and its window visible; a hiding window
 * destroys its surfaces, so the owner releases this on every hide and builds a new one when the
 * keyboard shows.
 *
 * The first placement and every layout change go through [AttachedSurfaceControl.applyTransactionOnDraw],
 * so the layers move in the same frame as the window content (the keyboard view stops drawing the
 * curtains in that frame); motion frames [apply] directly, with no window frame at all.
 */
@RequiresApi(SceneOverlayPlan.MIN_SDK)
internal class SceneOverlay private constructor(
    private val host: View,
    private val root: AttachedSurfaceControl,
    private val anchor: SurfaceView,
    /** Curtain A, curtain B, then the star layers. */
    private val layers: List<SurfaceControl>,
    /** The hardware bitmaps behind [layers] (same order); recycled on [release]. */
    private val bitmaps: List<Bitmap>,
    private val buffers: List<HardwareBuffer>,
    /** The window's buffer transform hint the buffers were pre-rotated for. */
    private val hint: Int,
) {
    private val location = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var released = false

    /**
     * Set by the owner: recompute the current frame for the view's new position and
     * [applyOnDraw] it. Called when the keyboard moved in its window (a pane opened above it).
     */
    var onMoved: (() -> Unit)? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!released && hostMoved()) onMoved?.invoke()
    }

    /**
     * Set by the owner: build the layers again. Called when the screen turned to an orientation
     * the buffers are not pre-rotated for (landscape one way to the other keeps the view's size,
     * so no bounds change rebuilds them).
     */
    var onRotated: (() -> Unit)? = null

    private val hintListener = AttachedSurfaceControl.OnBufferTransformHintChangedListener { newHint ->
        if (!released && newHint != hint) onRotated?.invoke()
    }

    init {
        host.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.addOnBufferTransformHintChangedListener(hintListener)
        // The anchor is claimed by create() once the overlay is fully set up, not here: a build
        // that failed after claiming had overwritten a queued predecessor's claim, so the failure
        // path removed the anchor from under the predecessor's layers (follow-up review 2026-11-15).
    }

    /** Stops listening to the host's layout and the window's rotation (release, or a failed build). */
    private fun detachListeners() {
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        root.removeOnBufferTransformHintChangedListener(hintListener)
    }

    private val anchorLocation = IntArray(2)

    /**
     * The host view's top-left corner relative to [anchor]'s surface, where the layers are placed,
     * px: `[x, y]`, valid until the next call.
     */
    fun hostOrigin(): IntArray {
        host.getLocationInWindow(location)
        anchor.getLocationInWindow(anchorLocation)
        location[0] -= anchorLocation[0]
        location[1] -= anchorLocation[1]
        return location
    }

    /** Moves the layers to [frame] at once: a motion frame, with no window frame behind it. */
    fun apply(frame: List<OverlayPlacement>) {
        if (released) return
        SurfaceControl.Transaction().use { t ->
            write(t, frame)
            t.apply()
        }
    }

    /** Moves the layers to [frame] together with the window's next frame. */
    fun applyOnDraw(frame: List<OverlayPlacement>) {
        if (released) return
        val t = SurfaceControl.Transaction()
        write(t, frame)
        if (!root.applyTransactionOnDraw(t)) t.apply()
        host.invalidate()
    }

    private fun write(t: SurfaceControl.Transaction, frame: List<OverlayPlacement>) {
        for (i in layers.indices) {
            val p = frame.getOrNull(i) ?: continue
            t.setPosition(layers[i], p.x, p.y)
            t.setScale(layers[i], p.scaleX, p.scaleY)
            t.setAlpha(layers[i], p.alpha)
        }
    }

    private fun hostMoved(): Boolean {
        hostOrigin()
        val moved = location[0] != lastX || location[1] != lastY
        lastX = location[0]
        lastY = location[1]
        return moved
    }

    /**
     * Removes the layers from the window and frees their buffers. Idempotent. [onDraw]: with the
     * window's next frame, the one in which the keyboard view paints the backdrop again, so
     * neither both nor neither show; the buffers are freed once that transaction has committed,
     * or after [QueuedFree.FALLBACK_MS] when the window never draws that frame (it hid first:
     * review 2026-11-14 M20). Otherwise (the window is hiding) at once.
     */
    fun release(onDraw: Boolean) {
        if (released) return
        released = true
        detachListeners()
        val t = SurfaceControl.Transaction()
        for (sc in layers) t.reparent(sc, null)
        val pending = QueuedFree({ r, ms -> mainHandler.postDelayed(r, ms) }, { r -> mainHandler.removeCallbacks(r) }) { timedOut ->
            // The queued transaction never applied: the layers are still on the anchor.
            if (timedOut) detachNow()
            free()
        }
        val queued = onDraw && runCatching {
            t.addTransactionCommittedListener(host.context.mainExecutor) { pending.committed() }
            root.applyTransactionOnDraw(t)
        }.getOrDefault(false)
        if (queued) {
            pending.queued()
            host.invalidate()
        } else {
            runCatching { t.apply() }
            t.close()
            free()
        }
    }

    /** Takes the layers off the anchor at once (a queued release whose frame never came). */
    private fun detachNow() {
        runCatching {
            SurfaceControl.Transaction().use { t ->
                for (sc in layers) if (sc.isValid) t.reparent(sc, null)
                t.apply()
            }
        }
    }

    private var freed = false

    private fun free() {
        if (freed) return
        freed = true
        // Only the anchor's current owner removes it: a successor may already have its layers on it.
        if (owners.release(anchor, this)) removeAnchor(anchor)
        for (sc in layers) sc.release()
        for (b in buffers) b.close()
        for (b in bitmaps) b.recycle()
    }

    /** The window has no surface yet (it is still being laid out): try again on a later frame. */
    class NotReady : Exception()

    companion object {
        private const val TAG = "SceneOverlay"

        /** Each anchor's current overlay (main thread only; weak, so a dropped anchor is not kept). */
        private val owners = AnchorOwners<SurfaceView, SceneOverlay>()

        /** Runs a queued release's fallback free ([QueuedFree]); a detached view's own queue may never run. */
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * Whether [host]'s anchor has its surface, adding the anchor the first time (it gets its
         * surface on a later frame). The owner asks this before drawing the layer images, so a
         * window that is slow to give the anchor a surface costs no image per frame.
         */
        fun anchorReady(host: View): Boolean = host.rootSurfaceControl != null && anchorOf(host) != null

        /**
         * [host]'s anchor SurfaceView once its surface exists; null while it is being created (it
         * is added here, at the bottom of the window's view tree, the first time). Its surface
         * appears on a later frame: the caller retries.
         */
        private fun anchorOf(host: View): SurfaceView? {
            val decor = host.rootView as? ViewGroup ?: return null
            val existing = (0 until decor.childCount).map(decor::getChildAt)
                .firstOrNull { it.getTag(R_TAG) == ANCHOR } as? SurfaceView
            if (existing != null) {
                return existing.takeIf { it.holder.surface?.isValid == true }
            }
            val anchor = SurfaceView(host.context).apply {
                setTag(R_TAG, ANCHOR)
                // Under the window (the default), and transparent where nothing is placed.
                setZOrderOnTop(false)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            decor.addView(anchor, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return null
        }

        private fun removeAnchor(anchor: SurfaceView) {
            (anchor.parent as? ViewGroup)?.removeView(anchor)
        }

        /**
         * Removes an anchor that [create] added but never built layers on (the window was still
         * getting its surface when the backdrop stopped being eligible). No-op when there is none.
         */
        fun discardAnchor(host: View) {
            val decor = host.rootView as? ViewGroup ?: return
            (0 until decor.childCount).map(decor::getChildAt)
                .filter { it.getTag(R_TAG) == ANCHOR && (it as? SurfaceView)?.let(owners::isOwned) != true }
                .forEach { decor.removeView(it) }
        }

        /** Tag key and value marking the anchor among the window's top-level views. */
        private val R_TAG = tribixbite.cleverkeys.R.id.scene_overlay_anchor
        private const val ANCHOR = "CleverKeysSceneOverlayAnchor"

        /**
         * Builds [count] layers (curtain A, curtain B, then the star layers) under [host]'s window
         * from [image] (software bitmaps, asked for one at a time and copied to the GPU here; those
         * [owned] are recycled once copied) and places them at [initial] (given the view's origin
         * relative to the anchor, as every later frame is placed) with the window's next frame,
         * each voting [frameRate] (frames per second) to the display's refresh rate choice. Throws
         * [NotReady] while the window has no surface, and any other Throwable (an
         * OutOfMemoryError included) when the layers cannot be made, after freeing whatever was
         * built (the caller then paints the backdrop in the window for good).
         */
        fun create(
            host: View,
            count: Int,
            image: (index: Int) -> Bitmap,
            owned: (index: Int) -> Boolean,
            initial: (originX: Int, originY: Int) -> List<OverlayPlacement>,
            frameRate: Float,
        ): SceneOverlay {
            val root = host.rootSurfaceControl ?: throw NotReady()
            val anchor = anchorOf(host) ?: throw NotReady()
            val parent = anchor.surfaceControl
            val t = SurfaceControl.Transaction()
            // Each buffer drawn pre-rotated for the screen's orientation (preRotationDegrees).
            val hint = root.bufferTransformHint
            val degrees = SceneOverlayPlan.preRotationDegrees(hint)
            var parts: List<Part> = emptyList()
            var overlay: SceneOverlay? = null
            try {
                // One layer at a time: its image (a star layer is drawn on demand and recycled once
                // copied), its rotated copy and its hardware copy are all that exist at once.
                parts = SceneOverlayPlan.buildEach(count, { i ->
                    buildPart(i, image, owned, degrees, hint, parent, frameRate, t)
                }, Part::free)
                val built = SceneOverlay(host, root, anchor, parts.map { it.layer }, parts.map { it.bitmap }, parts.map { it.buffer }, hint)
                overlay = built
                built.hostMoved()
                val origin = built.hostOrigin()
                built.write(t, initial(origin[0], origin[1]))
                if (!root.applyTransactionOnDraw(t)) t.apply()
                host.invalidate()
                // The newest overlay on an anchor owns it: a predecessor whose release is still
                // queued (rebuilt in one pass: size, painting or tuning change) must not remove it
                // (free()). Claimed last, so a failure above leaves the predecessor's claim intact.
                owners.claim(anchor, built)
                return built
            } catch (e: Throwable) {
                // Any Throwable (review 2026-11-14 M18): an OutOfMemoryError copying a view-sized
                // bitmap to the GPU skipped the old catch (Exception), and this cleanup with it.
                runCatching { Log.w(TAG, "hardware backdrop unavailable; drawing it in the window", e) }
                overlay?.detachListeners()
                // A predecessor whose release is still queued may own the anchor: it removes it then.
                if (owners.abandon(anchor, overlay)) removeAnchor(anchor)
                runCatching { t.close() }
                for (part in parts) runCatching { part.free() }
                throw e
            }
        }

        /** One built layer: its hardware bitmap, that bitmap's buffer and its SurfaceControl. */
        private class Part(val bitmap: Bitmap, val buffer: HardwareBuffer, val layer: SurfaceControl) {
            fun free() {
                layer.release()
                buffer.close()
                bitmap.recycle()
            }
        }

        /**
         * Layer [i]: [image] turned by [degrees], copied to the GPU and set on a new SurfaceControl
         * under [parent] in [t]. The software image is recycled here when [owned] (a star layer);
         * on a failure whatever this step made is freed before it is rethrown.
         */
        private fun buildPart(
            i: Int, image: (Int) -> Bitmap, owned: (Int) -> Boolean, degrees: Int, hint: Int,
            parent: SurfaceControl, frameRate: Float, t: SurfaceControl.Transaction,
        ): Part {
            val src = image(i)
            val copied = try {
                val upright = if (degrees == 0) src else Bitmap.createBitmap(
                    src, 0, 0, src.width, src.height, Matrix().apply { setRotate(degrees.toFloat()) }, false,
                )
                try {
                    upright.copy(Bitmap.Config.HARDWARE, false)
                } finally {
                    if (upright !== src) upright.recycle()
                }
            } finally {
                if (owned(i)) src.recycle()
            }
            val hw = copied ?: error("no hardware copy of layer $i")
            var buffer: HardwareBuffer? = null
            try {
                val hb = hw.hardwareBuffer
                buffer = hb
                val sc = SurfaceControl.Builder()
                    .setName("CleverKeysBackdrop$i")
                    .setBufferSize(hw.width, hw.height)
                    // The curtains are fully painted; the star layers are transparent between stars.
                    .setOpaque(i < 2)
                    .setHidden(false)
                    .setParent(parent)
                    .build()
                try {
                    t.setBuffer(sc, hb)
                    t.setDataSpace(sc, DataSpace.DATASPACE_SRGB)
                    // The placements scale the upright content (the buffer once this is applied).
                    t.setBufferTransform(sc, SceneOverlayPlan.bufferTransformFor(hint))
                    t.setLayer(sc, SceneOverlayPlan.zOrder(i))
                    // The pace the layers move at: the display may then stay at 60 Hz rather than
                    // its 120 Hz for "smooth" content, as the window path's low vote asks for.
                    t.setFrameRate(sc, frameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                    t.setVisibility(sc, true)
                } catch (e: Throwable) {
                    runCatching { sc.release() }
                    throw e
                }
                return Part(hw, hb, sc)
            } catch (e: Throwable) {
                runCatching { buffer?.close() }
                runCatching { hw.recycle() }
                throw e
            }
        }
    }
}
