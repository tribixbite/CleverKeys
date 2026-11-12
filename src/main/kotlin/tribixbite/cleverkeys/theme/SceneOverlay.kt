package tribixbite.cleverkeys.theme

import android.graphics.Bitmap
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
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

    /** First Android release with every call used ([AttachedSurfaceControl], setBuffer, setDataSpace). */
    const val MIN_SDK = 33

    /**
     * Whether the backdrop's motion may run on hardware layers: the user's setting
     * ([SceneMotionTuning.overlay]) on API [MIN_SDK]+, while it moves, for a cross-fading
     * painting without moving layers ([hasAlt], not [hasMeshLayers] or rain), at full keyboard
     * opacity (a translucent keyboard blends the curtains with each other before the app below;
     * separate layers cannot), without a colour filter (theme previews), and with particles that
     * stay put ([starsDrift] would need wrapped copies per layer). Anything else draws in the
     * window as before.
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
    ): Boolean = sdk >= MIN_SDK && overlaySetting && motionEnabled && hasAlt && !hasMeshLayers && !rain &&
        drawableAlpha == 0xFF && !hasColorFilter && !starsDrift

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

    /** Star layer [layer] (full resolution, the view's size) at its group's twinkle. */
    fun stars(viewX: Float, viewY: Float, layer: Int, starAlpha: FloatArray) = OverlayPlacement(
        x = viewX, y = viewY, scaleX = 1f, scaleY = 1f,
        alpha = starAlpha.getOrElse(layer) { 1f }.coerceIn(0f, 1f),
    )

    /** Whether twinkle group [group] is drawn on star layer [layer]. */
    fun groupOnLayer(group: Int, layer: Int): Boolean = group % STAR_LAYERS == layer

    /**
     * The layers' z among themselves, bottom first: curtain A, curtain B, then the star layers.
     * They are children of an empty SurfaceView's surface, which the platform stacks under the
     * window; the app cannot put a layer under its own window any other way (a negative z under
     * [AttachedSurfaceControl] still lands above the window's buffer: Pixel 8 Pro, 2026-11-11, the
     * curtains covered the keys).
     */
    fun zOrder(index: Int): Int = index + 1
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

    init {
        host.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        // The newest overlay on an anchor owns it: a predecessor whose release is still queued
        // (rebuilt in one pass: size, painting or tuning change) must not remove it (free()).
        owners[anchor] = this
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
     * neither both nor neither show; the buffers are freed once that transaction has committed.
     * Otherwise (the window is hiding) at once.
     */
    fun release(onDraw: Boolean) {
        if (released) return
        released = true
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        val t = SurfaceControl.Transaction()
        for (sc in layers) t.reparent(sc, null)
        val queued = onDraw && runCatching {
            t.addTransactionCommittedListener(host.context.mainExecutor) { free() }
            root.applyTransactionOnDraw(t)
        }.getOrDefault(false)
        if (queued) {
            host.invalidate()
        } else {
            runCatching { t.apply() }
            t.close()
            free()
        }
    }

    private var freed = false

    private fun free() {
        if (freed) return
        freed = true
        // Only the anchor's current owner removes it: a successor may already have its layers on it.
        if (owners[anchor] === this) {
            owners.remove(anchor)
            removeAnchor(anchor)
        }
        for (sc in layers) sc.release()
        for (b in buffers) b.close()
        for (b in bitmaps) b.recycle()
    }

    /** The window has no surface yet (it is still being laid out): try again on a later frame. */
    class NotReady : Exception()

    companion object {
        private const val TAG = "SceneOverlay"

        /** Each anchor's current overlay (main thread only; weak, so a dropped anchor is not kept). */
        private val owners = java.util.WeakHashMap<SurfaceView, SceneOverlay>()

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
                .filter { it.getTag(R_TAG) == ANCHOR && owners[it as? SurfaceView] == null }
                .forEach { decor.removeView(it) }
        }

        /** Tag key and value marking the anchor among the window's top-level views. */
        private val R_TAG = tribixbite.cleverkeys.R.id.scene_overlay_anchor
        private const val ANCHOR = "CleverKeysSceneOverlayAnchor"

        /**
         * Builds the layers for [images] (curtain A, curtain B, then the star layers; software
         * bitmaps, copied to the GPU here) under [host]'s window and places them at [initial] (given
         * the view's origin relative to the anchor, as every later frame is placed) with
         * the window's next frame, each voting [frameRate] (frames per second) to the display's refresh
         * rate choice. Throws [NotReady] while the window has no surface, and any
         * other exception when the platform refuses the layers (the caller then paints the
         * backdrop in the window for good).
         */
        fun create(host: View, images: List<Bitmap>, initial: (originX: Int, originY: Int) -> List<OverlayPlacement>, frameRate: Float): SceneOverlay {
            val root = host.rootSurfaceControl ?: throw NotReady()
            val anchor = anchorOf(host) ?: throw NotReady()
            val parent = anchor.surfaceControl
            val bitmaps = ArrayList<Bitmap>(images.size)
            val buffers = ArrayList<HardwareBuffer>(images.size)
            val layers = ArrayList<SurfaceControl>(images.size)
            val t = SurfaceControl.Transaction()
            try {
                for ((i, image) in images.withIndex()) {
                    val hw = image.copy(Bitmap.Config.HARDWARE, false) ?: error("no hardware copy of layer $i")
                    bitmaps += hw
                    val buffer = hw.hardwareBuffer
                    buffers += buffer
                    val sc = SurfaceControl.Builder()
                        .setName("CleverKeysBackdrop$i")
                        .setBufferSize(image.width, image.height)
                        // The curtains are fully painted; the star layers are transparent between stars.
                        .setOpaque(i < 2)
                        .setHidden(false)
                        .setParent(parent)
                        .build()
                    layers += sc
                    t.setBuffer(sc, buffer)
                    t.setDataSpace(sc, DataSpace.DATASPACE_SRGB)
                    t.setLayer(sc, SceneOverlayPlan.zOrder(i))
                    // The pace the layers move at: the display may then stay at 60 Hz rather than
                    // its 120 Hz for "smooth" content, as the window path's low vote asks for.
                    t.setFrameRate(sc, frameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                    t.setVisibility(sc, true)
                }
                val overlay = SceneOverlay(host, root, anchor, layers, bitmaps, buffers)
                overlay.hostMoved()
                val origin = overlay.hostOrigin()
                overlay.write(t, initial(origin[0], origin[1]))
                if (!root.applyTransactionOnDraw(t)) t.apply()
                host.invalidate()
                return overlay
            } catch (e: Exception) {
                if (e !is NotReady) {
                    Log.w(TAG, "hardware backdrop unavailable; drawing it in the window", e)
                    removeAnchor(anchor)
                }
                t.close()
                for (sc in layers) sc.release()
                for (b in buffers) b.close()
                for (b in bitmaps) b.recycle()
                throw e
            }
        }
    }
}
