package tribixbite.cleverkeys.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * True black scenes (`look_scene_true_black`; maintainer 2026-11-11: "add a true black option
 * for the bright scenes"). On an OLED screen a black pixel is off, and a bright painted scene
 * cost the Pixel's display ~55 mW more than the near-black aurora at brightness 102. This turns
 * a [ScenePainting] into a night version of itself: every broad, even area (sky, sand, snow,
 * fog, still water) goes to pure black, and three things stay lit:
 *
 * - **light**: the brightest part of the scene (the meadow's low sun, the forest's shafts),
 *   rescaled so the cut-off fades in instead of leaving a hard edge;
 * - **detail**: whatever differs from its own surroundings by more than a hair (blades, leaves,
 *   foam lines, sand grains, glitter), measured against a ~1.5 dp blur;
 * - **colour**, for the scenes whose light is colourful but dimmer than their ground (the
 *   beach's sea, the snow's aurora): saturated pixels stay at their own hue.
 *
 * Each pixel keeps its hue and is only ever scaled down ([PEAK] at most), so nothing gets
 * brighter than the painting it came from. Off-device preview (Pixel keyboard size): the mean
 * light of the pixels falls 78 % (meadow) to 99 % (beach), and 30–80 % of them are black.
 * Pure JVM and deterministic: `SceneTrueBlackTest`.
 */
object SceneTrueBlack {

    /**
     * How one scene is darkened.
     *
     * @property lightQuantile the share of the scene's pixels, darkest first, that the light term
     *   leaves black: the rest is rescaled from black to [PEAK].
     * @property colourWeight how much a saturated pixel keeps, 0 = colour alone keeps nothing.
     * @property colourFrom saturation (max − min channel, 0..1) where colour starts to count.
     * @property colourTo saturation where it counts in full.
     */
    class Params(
        val lightQuantile: Float,
        val colourWeight: Float = 0f,
        val colourFrom: Float = 0.25f,
        val colourTo: Float = 0.5f,
    )

    /** The brightest a kept pixel may be, as a share of its painted colour. */
    const val PEAK = 0.85f

    /** The detail term compares each pixel with a box blur of this radius, dp. */
    const val DETAIL_RADIUS_DP = 1.5f

    /** Luma difference from the surroundings where detail starts to show, and where it is in full. */
    const val DETAIL_FROM = 0.03f
    const val DETAIL_TO = 0.15f

    /** The light term's curve above the cut-off (a touch steeper than linear: a softer glow). */
    private const val LIGHT_GAMMA = 1.1f

    private const val OPAQUE_BLACK = 0xFF000000.toInt()

    /**
     * Per scene, tuned on previews of each one (scratch sheets, 2026-11-11). The beach and the
     * snow light their ground brighter than their sea and aurora, so a high cut-off drops the
     * ground and colour keeps the rest; the others keep their top third of light. The beach's
     * colour starts above its sand's saturation (~0.31; the sea is ~0.47), or the sand stays lit.
     */
    fun paramsFor(kind: SceneKind): Params = when (kind) {
        SceneKind.BEACH -> Params(lightQuantile = 0.92f, colourWeight = 0.9f, colourFrom = 0.34f, colourTo = 0.5f)
        SceneKind.SNOW -> Params(lightQuantile = 0.92f, colourWeight = 0.9f, colourFrom = 0.2f, colourTo = 0.45f)
        SceneKind.MEADOW, SceneKind.STREAM, SceneKind.RIVER_ROCKS, SceneKind.FOREST -> Params(lightQuantile = 0.65f)
    }

    /**
     * [painting] (of [kind], painted at [density] raster px per dp) in true black: the base, the
     * cross-fade image and every layer, with one light cut-off (taken from the whole picture at
     * rest) so a layer matches the ground it moves over. The surface map is unchanged.
     */
    fun apply(painting: ScenePainting, kind: SceneKind, density: Float): ScenePainting {
        if (painting.base.isEmpty()) return painting
        val params = paramsFor(kind)
        // The cut-off from the picture as seen (layers over the base at rest): the snow's hills are
        // a layer over a dark sky, and a cut-off from the sky alone left them lit (Fable audit).
        val seen = if (painting.layers.isEmpty()) painting.base else SceneMotion.composite(painting, 0.0)
        val cutoff = lumaQuantile(seen, params.lightQuantile)
        val radius = max(1, (DETAIL_RADIUS_DP * density).toInt())
        val w = painting.width
        val h = painting.height
        return ScenePainting(
            w, h,
            darken(painting.base, w, h, radius, cutoff, params),
            painting.alt?.let { darken(it, w, h, radius, cutoff, params) },
            painting.layers.map { l ->
                SceneLayer(l.left, l.top, l.width, l.height, darken(l.pixels, l.width, l.height, radius, cutoff, params), l.motion)
            },
            painting.surface,
        )
    }

    /** Rec. 709 luma of an ARGB colour, 0..1 (alpha ignored). */
    internal fun luma(c: Int): Float =
        (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

    /**
     * The luma below which [quantile] of the opaque pixels of [pixels] lie (a 256-bin histogram:
     * a sort of a million floats per painting is not needed for a cut-off).
     */
    internal fun lumaQuantile(pixels: IntArray, quantile: Float): Float {
        val bins = IntArray(256)
        var n = 0
        for (c in pixels) {
            if (c ushr 24 == 0) continue
            bins[(luma(c) * 255f + 0.5f).toInt().coerceIn(0, 255)]++
            n++
        }
        if (n == 0) return 1f
        val target = (quantile.coerceIn(0f, 1f) * (n - 1)).toLong()
        var seen = 0L
        for (i in 0..255) {
            seen += bins[i]
            if (seen > target) return i / 255f
        }
        return 1f
    }

    /**
     * [pixels] ([w] × [h], ARGB, alpha kept) darkened by [params]. The detail term compares with
     * an alpha-weighted blur, so a layer's transparent surroundings do not read as an edge.
     */
    internal fun darken(pixels: IntArray, w: Int, h: Int, radius: Int, cutoff: Float, params: Params): IntArray {
        // TODO: six float arrays per image (24 B/px: ~20 MB for a 1008 × 840 scene, three images
        //  for the snow) are allocated on the render thread; an OOM falls back to the base colour
        //  (requestPainting), but reusing one scratch set per painting would halve the peak.
        val size = w * h
        val y = FloatArray(size)
        val a = FloatArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            a[i] = (c ushr 24) / 255f
            y[i] = luma(c) * a[i]
        }
        val blurY = boxBlur(y, w, h, radius)
        val blurA = boxBlur(a, w, h, radius)
        val out = IntArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            val alpha = c ushr 24
            if (alpha == 0) continue
            val lum = luma(c)
            val surround = if (blurA[i] > 0f) blurY[i] / blurA[i] else lum
            val scale = PEAK * max(
                light(lum, cutoff),
                max(smoothstep(DETAIL_FROM, DETAIL_TO, abs(lum - surround)), colour(c, params)),
            )
            // Black keeps the pixel's alpha: an opaque base stays opaque black, not a hole.
            out[i] = if (scale <= 0f) (alpha shl 24) else scaled(c, scale)
        }
        return out
    }

    /** The light term's share for a pixel of luma [lum]: 0 at or below [cutoff], up to 1. */
    private fun light(lum: Float, cutoff: Float): Float {
        if (lum <= cutoff || lum <= 0f || cutoff >= 1f) return 0f
        val target = ((lum - cutoff) / (1f - cutoff)).pow(LIGHT_GAMMA)
        return min(1f, target / lum)
    }

    /** The colour term's share for [c]: its saturation through [Params.colourFrom]..[Params.colourTo]. */
    private fun colour(c: Int, params: Params): Float {
        if (params.colourWeight <= 0f) return 0f
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        val chroma = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
        return params.colourWeight * smoothstep(params.colourFrom, params.colourTo, chroma)
    }

    /** [c]'s colour times [s] (0..1), alpha kept. */
    private fun scaled(c: Int, s: Float): Int {
        fun ch(shift: Int) = ((c shr shift and 0xFF) * s + 0.5f).toInt().coerceIn(0, 255)
        return (c and OPAQUE_BLACK) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A separable box blur of radius [r] (the window shrinks at the edges, no padding). */
    internal fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (row in 0 until h) {
            val o = row * w
            var acc = 0f
            var n = 0
            for (x in 0 until min(r, w)) { acc += src[o + x]; n++ }
            for (x in 0 until w) {
                if (x + r < w) { acc += src[o + x + r]; n++ }
                if (x - r - 1 >= 0) { acc -= src[o + x - r - 1]; n-- }
                tmp[o + x] = acc / n
            }
        }
        for (col in 0 until w) {
            var acc = 0f
            var n = 0
            for (yy in 0 until min(r, h)) { acc += tmp[yy * w + col]; n++ }
            for (yy in 0 until h) {
                if (yy + r < h) { acc += tmp[(yy + r) * w + col]; n++ }
                if (yy - r - 1 >= 0) { acc -= tmp[(yy - r - 1) * w + col]; n-- }
                out[yy * w + col] = acc / n
            }
        }
        return out
    }
}
