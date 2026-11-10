package tribixbite.cleverkeys.theme

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A painted keyboard background for a look's palette ([LookPalette.backdrop]): soft coloured
 * glows over [LookPalette.keyboardBackground] (an aurora) and a star field. It shows in the gaps
 * between keys and around them, and faintly through the keys when the look's [LookGeometry.keyFillOpacity] is below 1. It is
 * still unless the user turns on "Moving aurora" ([AuroraMotion]).
 *
 * Pure JVM (ARGB ints and fractions of the view), so the definition, the star placement and the
 * "darker than the keys" rule run in `runPureTests`; [BackdropDrawable] paints it.
 * The CleverKeys look's dark palette uses it (maintainer 2026-11-07: "a gradient of an aurora
 * borealis, purples on black, with a subtle dash of stardust, not animated"; 2026-11-10: motion as
 * an opt-in, off by default until its battery cost is measured).
 */
data class LookBackdrop(
    /** Glows painted in order over the base colour (and over the [ribbons]). */
    val glows: List<AuroraGlow>,
    /**
     * Aurora curtains painted first over the base colour ([AuroraRaster]): a wavy bright lower
     * edge with vertical rays fading upward, the shape a real aurora has. Soft [glows] alone read
     * as clouds (maintainer 2026-11-09: "too cloud like and less aurora like").
     */
    val ribbons: List<AuroraRibbon> = emptyList(),
    /**
     * A painted nature scene ([SceneRaster]) in place of the curtains: the scene looks' meadow,
     * stream, river rocks, forest, snow and beach (maintainer 2026-11-10). Null = the curtains
     * (or the plain base colour when there are none).
     */
    val scene: SceneKind? = null,
    /** Stars over the glows; null = none. */
    val stars: LookStarfield? = null,
    /**
     * Fraction of the view height, from the top edge, over which the painting fades back to the
     * base colour, so the keyboard meets the suggestion bar (drawn in the base colour) without
     * a seam. 0 = no fade.
     */
    val topFade: Float = 0.2f,
)

/**
 * One soft elliptical glow: [color] (with its alpha) at the centre, fading to transparent at the
 * ellipse's edge. Centre and radii are fractions of the view's width ([cx], [rx]) and height
 * ([cy], [ry]); [rotationDeg] tilts the ellipse clockwise, which reads as an aurora's slanted
 * curtain rather than a spotlight.
 */
data class AuroraGlow(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rotationDeg: Float,
    val color: Int,
)

/**
 * One aurora curtain: a band of light whose lower edge waves across the view and whose rays
 * rise from it and fade. Positions are fractions of the view (y from the top); colours carry
 * their peak opacity in the alpha byte. Painted by [AuroraRaster].
 */
data class AuroraRibbon(
    /** Mean height of the lower edge, fraction of the view height from the top. */
    val edgeY: Float,
    /** How far the edge waves up and down, fraction of the height. */
    val amplitude: Float,
    /** Wave periods across the view width (the edge is a sum of this and a slower half-wave). */
    val waves: Float,
    /** Phase of the edge's wave, in periods (0..1), so two ribbons do not run parallel. */
    val phase: Float,
    /** The edge rises by this fraction of the height from the left edge to the right one. */
    val slope: Float,
    /** How high the rays reach above the edge, fraction of the height. */
    val height: Float,
    /** Colour just above the lower edge, where a curtain is brightest. */
    val edgeColor: Int,
    /** Colour the rays fade into at the top of the curtain. */
    val topColor: Int,
    /** Rays per 100 dp of width. */
    val raysPer100Dp: Float,
    /** 0 = an even band, 1 = only the rays shine. */
    val rayContrast: Float,
    /** Seeds the ray placement, so the same size always draws the same curtain. */
    val seed: Long,
    /** Horizontal extent, fractions of the width; the curtain thins out over its last tenth. */
    val xStart: Float = 0f,
    val xEnd: Float = 1f,
    /**
     * The colours at [xEnd], for a curtain whose colour changes along its length (green at one
     * end of the low curtain, indigo into teal; maintainer 2026-11-10); null = [edgeColor] /
     * [topColor] all along.
     */
    val edgeColorEnd: Int? = null,
    val topColorEnd: Int? = null,
    /**
     * How far the rays lean towards [radiantX] (a fraction of the width) at the top of their
     * reach, as a share of their distance from it: rays converge on a point overhead, the
     * perspective of every real aurora's rays. 0 = straight up.
     */
    val convergence: Float = 0f,
    val radiantX: Float = 0.5f,
)

/**
 * Paints [AuroraRibbon]s over an opaque base colour into an ARGB pixel array (pure JVM, so the
 * look's "darker than the keys" rule can measure the real painting). Rendered once per view
 * size; `BackdropDrawable` scales the result up, the curtains being soft at any size.
 */
object AuroraRaster {

    /**
     * The opaque pixels ([width] × [height], row-major) of [ribbons] lit over [base] on a view
     * with [density] px per dp (the density of the RASTER: half the screen's at half size).
     * Light adds: two curtains crossing glow brighter, as they do in the sky.
     */
    fun render(base: Int, ribbons: List<AuroraRibbon>, width: Int, height: Int, density: Float): IntArray {
        val n = width.coerceAtLeast(0) * height.coerceAtLeast(0)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        if (n == 0) return IntArray(0)
        for (ribbon in ribbons) paint(ribbon, rayProfile(ribbon, width, density), width, height, r, g, b)
        return lit(base, r, g, b)
    }

    /**
     * One [ribbon] over [base] with the given per-column [rays] instead of its seeded ones: the
     * test hook for how rays rise and lean (`LookBackdropTest`).
     */
    internal fun paintRays(base: Int, ribbon: AuroraRibbon, rays: FloatArray, width: Int, height: Int): IntArray {
        val n = width * height
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        paint(ribbon, rays, width, height, r, g, b)
        return lit(base, r, g, b)
    }

    /** [base] with the light in [r], [g], [b] added, clamped, as opaque ARGB. */
    private fun lit(base: Int, r: FloatArray, g: FloatArray, b: FloatArray): IntArray {
        val n = r.size
        val br = ((base shr 16) and 0xFF).toFloat()
        val bg = ((base shr 8) and 0xFF).toFloat()
        val bb = (base and 0xFF).toFloat()
        return IntArray(n) { i ->
            val cr = (br + r[i]).coerceAtMost(255f).toInt()
            val cg = (bg + g[i]).coerceAtMost(255f).toInt()
            val cb = (bb + b[i]).coerceAtMost(255f).toInt()
            (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
    }

    private fun paint(rb: AuroraRibbon, rays: FloatArray, w: Int, h: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val x0 = rb.xStart * w
        val x1 = rb.xEnd * w
        val ramp = ((x1 - x0) * 0.1f).coerceAtLeast(1f)
        val soft = (0.035f * h).coerceAtLeast(1f) // the glow just under the edge
        // The vertical profile and colour, tabulated over t = (height above the edge) / reach,
        // at the curtain's start and (when its colour changes along it) its end.
        val steps = 64
        val start = profile(rb.edgeColor, rb.topColor, steps)
        val end = if (rb.edgeColorEnd == null && rb.topColorEnd == null) start
        else profile(rb.edgeColorEnd ?: rb.edgeColor, rb.topColorEnd ?: rb.topColor, steps)
        val twoPi = (2 * Math.PI).toFloat()
        val radiant = rb.radiantX * w
        for (x in 0 until w) {
            val fx = x + 0.5f
            if (fx < x0 || fx > x1) continue
            val env = minOf(1f, (fx - x0) / ramp, (x1 - fx) / ramp)
            val u = fx / w
            // Where along the curtain: its colour, eased so the change reads as a blend.
            val along = ((fx - x0) / (x1 - x0)).coerceIn(0f, 1f)
            val mix = along * along * (3f - 2f * along)
            val edge = h * (rb.edgeY - rb.slope * (u - 0.5f) +
                rb.amplitude * (0.7f * kotlin.math.sin(twoPi * (rb.waves * u + rb.phase)) +
                    0.3f * kotlin.math.sin(twoPi * (0.5f * u + rb.phase * 0.37f))))
            val ray = rays[x]
            val full = (rb.height * h).coerceAtLeast(1f)
            val top = (edge - full).toInt().coerceAtLeast(0)
            val bottom = (edge + 3 * soft).toInt().coerceAtMost(h - 1)
            for (y in top..bottom) {
                val fy = y + 0.5f
                val i = y * w + x
                if (fy <= edge) {
                    // A ray rooted at x0 rises leaning towards the radiant: at this height it
                    // stands at x0 + (radiant - x0)·c, so this pixel shows the ray rooted at
                    // (fx - radiant·c) / (1 - c).
                    val c = (rb.convergence * (edge - fy) / h).coerceAtMost(0.9f)
                    val here = if (c == 0f) ray else sample(rays, (fx - radiant * c) / (1f - c))
                    // Brighter rays also reach higher, which is what makes a curtain look rayed.
                    val reach = full * (0.55f + 0.45f * here)
                    if (edge - fy > reach) continue
                    val column = env * ((1f - rb.rayContrast) + rb.rayContrast * here)
                    val s = (((edge - fy) / reach) * steps).toInt().coerceIn(0, steps)
                    r[i] += lerp(start.r[s], end.r[s], mix) * column
                    g[i] += lerp(start.g[s], end.g[s], mix) * column
                    b[i] += lerp(start.b[s], end.b[s], mix) * column
                } else {
                    val d = (fy - edge) / soft
                    val f = kotlin.math.exp(-d * d) * env * ((1f - rb.rayContrast) + rb.rayContrast * ray)
                    r[i] += lerp(start.r[0], end.r[0], mix) * f
                    g[i] += lerp(start.g[0], end.g[0], mix) * f
                    b[i] += lerp(start.b[0], end.b[0], mix) * f
                }
            }
        }
    }

    /** A curtain's light up its reach (premultiplied channels over t = 0..1 in [steps] steps). */
    private class Profile(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    private fun profile(edgeColor: Int, topColor: Int, steps: Int): Profile {
        val r = FloatArray(steps + 1); val g = FloatArray(steps + 1); val b = FloatArray(steps + 1)
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            // A bright rim just above the edge (the fast term) over a slower glow that carries
            // the rays up, reaching nothing at the top.
            val f = (0.55f * kotlin.math.exp(-7f * t) + 0.45f * kotlin.math.exp(-1.8f * t)) * (1f - t * t)
            r[s] = lerpChannel(edgeColor, topColor, 16, t) * f
            g[s] = lerpChannel(edgeColor, topColor, 8, t) * f
            b[s] = lerpChannel(edgeColor, topColor, 0, t) * f
        }
        return Profile(r, g, b)
    }

    /** [rays] at fractional column [x] (pixel centres at i + 0.5), linearly interpolated. */
    private fun sample(rays: FloatArray, x: Float): Float {
        val p = (x - 0.5f).coerceIn(0f, (rays.size - 1).toFloat())
        val i = p.toInt()
        val k = p - i
        return if (i + 1 < rays.size) rays[i] + (rays[i + 1] - rays[i]) * k else rays[i]
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Per-column ray strength in 0..1: seeded rays of 1–7 dp wide Gaussian profile over a faint
     * floor. Deterministic for the same ribbon, width and density.
     */
    internal fun rayProfile(rb: AuroraRibbon, w: Int, density: Float): FloatArray {
        val out = FloatArray(w)
        if (w <= 0 || density <= 0f) return out
        val widthDp = w / density
        val count = (widthDp / 100f * rb.raysPer100Dp).toInt().coerceIn(0, MAX_RAYS)
        val rng = kotlin.random.Random(rb.seed xor (w.toLong() shl 16))
        repeat(count) {
            val cx = rng.nextFloat() * w
            val sigma = (1f + 6f * rng.nextFloat() * rng.nextFloat()) * density
            val strength = 0.35f + 0.65f * rng.nextFloat()
            val from = (cx - 3 * sigma).toInt().coerceAtLeast(0)
            val to = (cx + 3 * sigma).toInt().coerceAtMost(w - 1)
            for (x in from..to) {
                val d = (x + 0.5f - cx) / sigma
                out[x] += strength * kotlin.math.exp(-d * d)
            }
        }
        for (x in 0 until w) out[x] = (0.15f + out[x]).coerceAtMost(1f)
        return out
    }

    /** Channel [shift] of the colour between [a] and [b] at [t], premultiplied by its alpha. */
    private fun lerpChannel(a: Int, b: Int, shift: Int, t: Float): Float {
        val ca = ((a shr shift) and 0xFF) * (((a ushr 24) and 0xFF) / 255f)
        val cb = ((b shr shift) and 0xFF) * (((b ushr 24) and 0xFF) / 255f)
        return ca + (cb - ca) * t
    }

    /** A cap so a huge view cannot place an unbounded number of rays. */
    const val MAX_RAYS = 600
}

/**
 * A static star field: [perSquare100Dp] stars per 100 × 100 dp, placed by a seeded generator so a
 * given keyboard size always shows the same sky (no flicker on relayout).
 */
data class LookStarfield(
    val perSquare100Dp: Float,
    val seed: Long,
    /** Star colour; its alpha is replaced by each star's own. */
    val color: Int,
    val minAlpha: Int,
    val maxAlpha: Int,
    val minRadiusDp: Float,
    val maxRadiusDp: Float,
    /** Every Nth star also draws a faint four-point glint (the "twinkle"); 0 = none. */
    val glintEvery: Int,
    /** What each particle looks like: a star (the default), a snowflake, a leaf, a mote, a glint. */
    val shape: ParticleShape = ParticleShape.STAR,
    /**
     * Colours the particles are drawn in, one picked per particle (leaves in autumn colours);
     * empty = all in [color]. Alphas come from each particle as for [color].
     */
    val tints: List<Int> = emptyList(),
    /**
     * How the particles travel while the backdrop moves ([AuroraMotion]), dp per second: snow
     * falls, leaves drift down, motes rise, sparkles go with the current. The twinkle groups go
     * at 0.7× to 1.3× of it, so near and far particles part. 0 = they stay put and twinkle.
     */
    val driftXDpPerS: Float = 0f,
    val driftYDpPerS: Float = 0f,
)

/** The look of a [LookStarfield]'s particles ([BackdropDrawable] draws them). */
enum class ParticleShape {
    /** A dot, every Nth with a faint four-point glint. */
    STAR,
    /** A soft white flake with a halo. */
    FLAKE,
    /** A small tilted oval leaf. */
    LEAF,
    /** A soft glowing mote (pollen, a firefly). */
    MOTE,
    /** A four-point sparkle: sun on water, a wet stone. */
    GLINT,
    /**
     * A falling raindrop: a thin streak about twenty radii long along the field's drift, as a
     * drop looks at a fraction of a second's exposure (the river rocks, maintainer 2026-11-10).
     */
    RAIN,
}

/**
 * One placed star (or other particle), in pixels: [tint] is its colour without alpha (0 = the
 * field's), [angle] its tilt in degrees (leaves).
 */
data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Int,
    val glint: Boolean,
    val tint: Int = 0,
    val angle: Float = 0f,
)

/** Places a [LookStarfield] on a view of a given size. Pure; `LookBackdropTest`. */
object LookStars {

    /**
     * The stars of [field] on a [widthPx] × [heightPx] view at [density] px per dp, inside the
     * view and deterministic for the same inputs.
     */
    fun place(field: LookStarfield, widthPx: Int, heightPx: Int, density: Float): List<Star> {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return emptyList()
        val areaUnits = (widthPx / density / 100f) * (heightPx / density / 100f)
        val count = (areaUnits * field.perSquare100Dp).roundToInt().coerceIn(0, MAX_STARS)
        // Seeded by the size too, so a rotated keyboard gets its own (stable) sky.
        val rng = Random(field.seed xor (widthPx.toLong() shl 20) xor heightPx.toLong())
        val lowAlpha = field.minAlpha.coerceIn(0, 255)
        val highAlpha = field.maxAlpha.coerceIn(lowAlpha, 255)
        // Tints and tilts draw extra random numbers only for fields that use them, so a plain
        // star field keeps exactly the sky it always had.
        val tinted = field.tints.isNotEmpty()
        val tilted = field.shape == ParticleShape.LEAF
        return List(count) { i ->
            val t = rng.nextFloat()
            Star(
                x = rng.nextFloat() * widthPx,
                y = rng.nextFloat() * heightPx,
                // Most stars small: squaring biases the radius towards the minimum.
                radius = (field.minRadiusDp + (field.maxRadiusDp - field.minRadiusDp) * t * t) * density,
                alpha = lowAlpha + ((highAlpha - lowAlpha) * rng.nextFloat()).roundToInt(),
                glint = field.glintEvery > 0 && i % field.glintEvery == 0,
                tint = if (tinted) field.tints[rng.nextInt(field.tints.size)] and 0x00FFFFFF else 0,
                angle = if (tilted) rng.nextFloat() * 180f else 0f,
            )
        }
    }

    /** A cap so a huge view (tablet, external display) cannot allocate an unbounded list. */
    const val MAX_STARS = 400
}
