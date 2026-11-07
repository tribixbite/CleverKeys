package tribixbite.cleverkeys.theme

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A painted keyboard background for a look's palette ([LookPalette.backdrop]): soft coloured
 * glows over [LookPalette.keyboardBackground] (an aurora) and a static star field. It shows in the
 * gaps between keys and around them; keys keep their own opaque fill. Nothing animates.
 *
 * Pure JVM (ARGB ints and fractions of the view), so the definition, the star placement and the
 * "darker than the keys" rule run in `runPureTests`; [AuroraBackdropDrawable] paints it.
 * The CleverKeys look's dark palette uses it (maintainer 2026-11-07: "a gradient of an aurora
 * borealis, purples on black, with a subtle dash of stardust, not animated").
 */
data class LookBackdrop(
    /** Glows painted in order over the base colour. */
    val glows: List<AuroraGlow>,
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
)

/** One placed star, in pixels. */
data class Star(val x: Float, val y: Float, val radius: Float, val alpha: Int, val glint: Boolean)

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
        return List(count) { i ->
            val t = rng.nextFloat()
            Star(
                x = rng.nextFloat() * widthPx,
                y = rng.nextFloat() * heightPx,
                // Most stars small: squaring biases the radius towards the minimum.
                radius = (field.minRadiusDp + (field.maxRadiusDp - field.minRadiusDp) * t * t) * density,
                alpha = lowAlpha + ((highAlpha - lowAlpha) * rng.nextFloat()).roundToInt(),
                glint = field.glintEvery > 0 && i % field.glintEvery == 0,
            )
        }
    }

    /** A cap so a huge view (tablet, external display) cannot allocate an unbounded list. */
    const val MAX_STARS = 400
}
