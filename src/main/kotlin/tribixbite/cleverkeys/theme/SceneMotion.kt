package tribixbite.cleverkeys.theme

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A painted nature scene ([SceneRaster.paint]): a still [base], optionally a second image [alt]
 * that a moving backdrop cross-fades in (the stream's water, the snow's glitter), and the
 * [layers] that move on their own (grass, branches, ferns, waves, light on water), drawn over the
 * base in order. Sizes are raster pixels (the view's size divided by [SceneKind.paintScale]).
 */
class ScenePainting(
    val width: Int,
    val height: Int,
    /** Opaque ARGB, [width] × [height], row-major. */
    val base: IntArray,
    /** A second opaque image of the same size for the cross-fade, or null. */
    val alt: IntArray?,
    val layers: List<SceneLayer>,
    /** Where the scene is solid (stones) rather than water, for rain to splash on; null = all solid. */
    val surface: SurfaceMask? = null,
) {
    /** Pixels held, for the drawable's cache budget. */
    val pixelCount: Int get() = base.size + (alt?.size ?: 0) + layers.sumOf { it.pixels.size }
}

/**
 * A coarse map of a scene's solid ground: one flag per [cell] × [cell] raster px. Rain lands on
 * stone as a splash and a wet spot, and on water as ripples (the river rocks).
 */
class SurfaceMask(val cell: Int, val cols: Int, val rows: Int, private val solid: BooleanArray) {
    /** Whether raster px ([x], [y]) is solid; outside the map counts as solid. */
    fun isSolid(x: Float, y: Float): Boolean {
        val c = (x / cell).toInt(); val r = (y / cell).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return true
        return solid[r * cols + c]
    }

    /** The solid share of the map, 0..1. */
    val solidShare: Float get() = solid.count { it }.toFloat() / solid.size.coerceAtLeast(1)

    companion object {
        /** The mask of [pixels] ([w] × [h]) where a cell's centre is brighter than [threshold] (0..255 luma). */
        fun ofBrightness(pixels: IntArray, w: Int, h: Int, cell: Int, threshold: Int): SurfaceMask {
            val cols = (w + cell - 1) / cell; val rows = (h + cell - 1) / cell
            val solid = BooleanArray(cols * rows) { i ->
                val x = ((i % cols) * cell + cell / 2).coerceAtMost(w - 1)
                val y = ((i / cols) * cell + cell / 2).coerceAtMost(h - 1)
                val c = pixels[y * w + x]
                (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10 > threshold
            }
            return SurfaceMask(cell, cols, rows, solid)
        }
    }
}

/**
 * One moving part of a scene: [pixels] (non-premultiplied ARGB, transparent around the part) of a
 * [width] × [height] box whose top-left corner is ([left], [top]) in the scene's raster, which may
 * lie outside the view (grass rooted just past an edge, so a lean never uncovers the edge).
 */
class SceneLayer(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val motion: LayerMotion,
) {
    /** Where the layer sits and how it moves, without its pixels (what a drawn layer keeps). */
    val placement: LayerPlacement get() = LayerPlacement(left, top, width, height, motion)
}

/**
 * A [SceneLayer]'s box and motion alone: once its pixels are a bitmap, the drawable keeps only
 * this (the pixel array would hold the same image a second time on the Java heap).
 */
data class LayerPlacement(val left: Int, val top: Int, val width: Int, val height: Int, val motion: LayerMotion)

/** How a [SceneLayer] moves; every distance is in raster pixels. */
enum class LayerMotionKind {
    /** Rooted below [LayerMotion.anchorY]: the higher a point, the further it bends sideways (grass, ferns). */
    SWAY_UP,
    /** Hanging from [LayerMotion.anchorY]: the lower a point, the further it swings (branches, leaves). */
    SWAY_DOWN,
    /** A wobble that varies across the layer, as light refracted by moving water (caustics). */
    RIPPLE,
    /** Surges [LayerMotion.amplitude] down the view and slides back, fading as it recedes (a wave washing up a beach). */
    WASH,
    /** Travels [LayerMotion.amplitude] down the view once per period, fading in and out (a swell rolling to the shore). */
    ROLL,
    /**
     * Slides along x by [LayerMotion.amplitude] per period and wraps: the layer's pixels repeat
     * every layer width (it is painted seamless across), so it runs on forever (light on flowing
     * water). Drawn as two copies, not through a mesh.
     */
    FLOW,
}

/**
 * The motion of a [SceneLayer].
 *
 * @property anchorY SWAY_*: the line that stays put (the roots, the branch tops).
 * @property reach SWAY_*: the distance from [anchorY] over which the bend grows to its full size.
 * @property amplitude SWAY_*: the sideways travel at full bend; RIPPLE: the wobble; WASH / ROLL: the
 *   distance travelled down the view (negative = up).
 * @property bias SWAY_*: the resting lean, as a share of [amplitude] (the wind's steady push).
 * @property periodS one cycle, seconds.
 * @property phase where in its cycle the layer starts, in cycles.
 * @property wavelength SWAY_* / RIPPLE: the width of one gust (or ripple) travelling across the
 *   layer; 0 = the whole layer moves together.
 * @property fade WASH: the alpha lost while receding.
 */
data class LayerMotion(
    val kind: LayerMotionKind,
    val anchorY: Float = 0f,
    val reach: Float = 1f,
    val amplitude: Float,
    val bias: Float = 0f,
    val periodS: Float,
    val phase: Float = 0f,
    val wavelength: Float = 0f,
    val fade: Float = 0f,
)

/**
 * Where the points of a moving [SceneLayer] are at a moment of motion time: displacements for a
 * mesh (`Canvas.drawBitmapMesh`, so a blade bends instead of the whole layer sliding) and the
 * layer's alpha. Pure, so the motion is tested off-device (`SceneMotionTest`) and the same
 * function drives the drawable and the test compositor ([composite]).
 */
object SceneMotion {

    /** Share of the surge a [LayerMotionKind.WASH] spends running in; the rest is the slower ebb. */
    private const val SURGE = 0.38f

    /**
     * The gust at [x] and [seconds], -1..1 (plus [LayerMotion.bias]): two unrelated sines, one
     * travelling across the layer with the wind, so the field never sways in lockstep.
     */
    private fun gust(m: LayerMotion, x: Float, seconds: Double): Float {
        val across = if (m.wavelength > 0f) x / m.wavelength else 0f
        val a = sin(2 * PI * (seconds / m.periodS + m.phase - across))
        val b = sin(2 * PI * (seconds / (m.periodS * 1.73) + m.phase * 1.7 - across * 0.71) + 1.1)
        return (m.bias + 0.62 * a + 0.38 * b).toFloat()
    }

    private fun cycle(m: LayerMotion, seconds: Double): Float {
        val p = seconds / m.periodS + m.phase
        return (p - floor(p)).toFloat()
    }

    /** How far up a WASH has run, 0 (out) .. 1 (its furthest), eased at both ends. */
    fun surge(m: LayerMotion, seconds: Double): Float {
        val p = cycle(m, seconds)
        return if (p < SURGE) {
            val t = p / SURGE
            1f - (1f - t) * (1f - t) // fast in, slowing to its furthest
        } else {
            val t = (p - SURGE) / (1f - SURGE)
            1f - t * t * (3f - 2f * t) // a slow ebb
        }
    }

    /** The layer's alpha share at [seconds]. */
    fun alpha(m: LayerMotion, seconds: Double): Float = when (m.kind) {
        LayerMotionKind.WASH -> {
            val p = cycle(m, seconds)
            // Full while running in; thins out as it recedes; returns while the next one gathers.
            if (p < SURGE) 1f - m.fade * (1f - p / SURGE) * (1f - p / SURGE)
            else 1f - m.fade * ((p - SURGE) / (1f - SURGE))
        }
        LayerMotionKind.ROLL -> sin(PI * cycle(m, seconds)).toFloat().coerceAtLeast(0f).pow(0.8f)
        else -> 1f
    }

    /**
     * The displacement of the layer point ([x], [y]) (scene raster pixels) at [seconds], written to
     * [out] as (dx, dy) from [offset].
     */
    fun displace(m: LayerMotion, x: Float, y: Float, seconds: Double, out: FloatArray, offset: Int = 0) {
        var dx = 0f
        var dy = 0f
        when (m.kind) {
            LayerMotionKind.SWAY_UP, LayerMotionKind.SWAY_DOWN -> {
                val along = if (m.kind == LayerMotionKind.SWAY_UP) m.anchorY - y else y - m.anchorY
                val h = (along / max(1f, m.reach)).coerceIn(0f, 1f)
                // Bends more towards the free end, as a blade or a branch does, not as a rigid lean.
                val bend = h * (0.35f + 0.65f * h)
                dx = m.amplitude * gust(m, x, seconds) * bend
                // A bent blade is a little shorter; a swinging branch rises a little at the ends.
                dy = (if (m.kind == LayerMotionKind.SWAY_UP) 1f else -1f) * 0.12f * kotlin.math.abs(dx) * bend
            }
            LayerMotionKind.RIPPLE -> {
                val wl = if (m.wavelength > 0f) m.wavelength else 1f
                dx = m.amplitude * sin(2 * PI * (seconds / m.periodS + m.phase + y / wl)).toFloat()
                dy = 0.7f * m.amplitude * sin(2 * PI * (seconds / (m.periodS * 1.31) + m.phase + x / (wl * 1.17)) + 0.7).toFloat()
            }
            LayerMotionKind.WASH -> dy = m.amplitude * surge(m, seconds)
            LayerMotionKind.ROLL -> dy = m.amplitude * cycle(m, seconds)
            LayerMotionKind.FLOW -> dx = m.amplitude * cycle(m, seconds)
        }
        out[offset] = dx
        out[offset + 1] = dy
    }

    /** Mesh columns for a layer [width] raster px wide: about one per [CELL] px, at least one. */
    fun meshColumns(width: Int): Int = max(1, (width + CELL - 1) / CELL)

    /** Mesh rows for a layer [height] raster px tall. */
    fun meshRows(height: Int): Int = max(1, (height + CELL - 1) / CELL)

    /** Mesh cell size, raster px: fine enough that a bend reads as a curve. */
    const val CELL = 24

    /**
     * The `drawBitmapMesh` vertices of [layer] at [seconds]: (columns + 1) × (rows + 1) points,
     * row by row, in VIEW pixels ([scale] view px per raster px), written to [out] (size
     * `2 × (cols + 1) × (rows + 1)`). [intensity] scales every displacement (the user's
     * [SceneMotionTuning.intensity]; 0 = the layer rests where it was painted).
     */
    fun mesh(layer: SceneLayer, seconds: Double, scale: Float, out: FloatArray, intensity: Float = 1f) =
        mesh(layer.placement, seconds, scale, out, intensity, FloatArray(2))

    /** [mesh] for a [LayerPlacement], with a caller-owned 2-float [scratch] (no allocation per frame). */
    fun mesh(layer: LayerPlacement, seconds: Double, scale: Float, out: FloatArray, intensity: Float, scratch: FloatArray) {
        val cols = meshColumns(layer.width)
        val rows = meshRows(layer.height)
        val d = scratch
        var k = 0
        for (r in 0..rows) {
            val y = layer.top + layer.height * r.toFloat() / rows
            for (c in 0..cols) {
                val x = layer.left + layer.width * c.toFloat() / cols
                displace(layer.motion, x, y, seconds, d)
                out[k++] = (x + intensity * d[0]) * scale
                out[k++] = (y + intensity * d[1]) * scale
            }
        }
    }

    /**
     * [painting] flattened at [seconds] of motion time (null = the alt image is not shown) into
     * opaque ARGB pixels: the base, then each layer moved by its motion (sampled back through
     * the displacement, which is smooth and small, so a point's own displacement stands in for
     * the inverse). What the drawable shows, for tests and previews; [altShare] blends the alt
     * image over the base.
     */
    fun composite(painting: ScenePainting, seconds: Double, altShare: Float = 0f): IntArray {
        val w = painting.width
        val h = painting.height
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val alt = painting.alt
        for (i in 0 until w * h) {
            val c = painting.base[i]
            var cr = ((c shr 16) and 0xFF).toFloat(); var cg = ((c shr 8) and 0xFF).toFloat(); var cb = (c and 0xFF).toFloat()
            if (alt != null && altShare > 0f) {
                val a = alt[i]
                cr += (((a shr 16) and 0xFF) - cr) * altShare
                cg += (((a shr 8) and 0xFF) - cg) * altShare
                cb += ((a and 0xFF) - cb) * altShare
            }
            r[i] = cr; g[i] = cg; b[i] = cb
        }
        val d = FloatArray(2)
        for (layer in painting.layers) {
            val share = alpha(layer.motion, seconds)
            if (share <= 0f) continue
            val reachX = kotlin.math.abs(layer.motion.amplitude) + 2
            val reachY = kotlin.math.abs(layer.motion.amplitude) + 2
            val x0 = max(0, floor(layer.left - reachX).toInt())
            val x1 = min(w - 1, (layer.left + layer.width + reachX).toInt())
            val y0 = max(0, floor(layer.top - reachY).toInt())
            val y1 = min(h - 1, (layer.top + layer.height + reachY).toInt())
            val flow = layer.motion.kind == LayerMotionKind.FLOW
            for (y in y0..y1) for (x in x0..x1) {
                displace(layer.motion, x + 0.5f, y + 0.5f, seconds, d)
                var sx = floor(x + 0.5f - d[0] - layer.left).toInt()
                val sy = floor(y + 0.5f - d[1] - layer.top).toInt()
                // A flowing layer repeats across its width.
                if (flow) sx = Math.floorMod(sx, layer.width)
                if (sx < 0 || sy < 0 || sx >= layer.width || sy >= layer.height) continue
                val c = layer.pixels[sy * layer.width + sx]
                val a = ((c ushr 24) and 0xFF) / 255f * share
                if (a <= 0f) continue
                val i = y * w + x
                r[i] += (((c shr 16) and 0xFF) - r[i]) * a
                g[i] += (((c shr 8) and 0xFF) - g[i]) * a
                b[i] += ((c and 0xFF) - b[i]) * a
            }
        }
        return IntArray(w * h) { i ->
            (0xFF shl 24) or (r[i].toInt().coerceIn(0, 255) shl 16) or (g[i].toInt().coerceIn(0, 255) shl 8) or b[i].toInt().coerceIn(0, 255)
        }
    }
}
