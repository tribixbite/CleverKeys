package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [LookStars]: the star field of a [LookBackdrop] is static (the same sky for the same keyboard
 * size, so a relayout never makes it flicker), inside the view, sized by area and capped. Pure JVM.
 */
class LookBackdropTest {

    private val field = KeyboardLooks.CLEVERKEYS_AURORA.stars!!

    @Test
    fun theSameSizeAlwaysGetsTheSameSky() {
        val a = LookStars.place(field, 1080, 760, 2.625f)
        val b = LookStars.place(field, 1080, 760, 2.625f)
        assertThat(a).isEqualTo(b)
        assertWithMessage("a rotated keyboard gets its own sky").that(LookStars.place(field, 2340, 600, 2.625f)).isNotEqualTo(a)
    }

    @Test
    fun starsStayInsideTheViewWithinTheirRanges() {
        val density = 2.625f
        val stars = LookStars.place(field, 1080, 760, density)
        // 411 × 290 dp ≈ 11.9 units of 100 × 100 dp at 8 per unit (2026-11-10: twice the stars).
        assertThat(stars.size).isEqualTo(95)
        for (s in stars) {
            assertThat(s.x).isAtLeast(0f); assertThat(s.x).isLessThan(1080f)
            assertThat(s.y).isAtLeast(0f); assertThat(s.y).isLessThan(760f)
            assertThat(s.radius).isAtLeast(field.minRadiusDp * density - 1e-3f)
            assertThat(s.radius).isAtMost(field.maxRadiusDp * density + 1e-3f)
            assertThat(s.alpha).isAtLeast(field.minAlpha); assertThat(s.alpha).isAtMost(field.maxAlpha)
        }
        assertWithMessage("every 5th star glints").that(stars.count { it.glint }).isEqualTo(19)
    }

    @Test
    fun anEmptyOrHugeViewIsBounded() {
        assertThat(LookStars.place(field, 0, 760, 2f)).isEmpty()
        assertThat(LookStars.place(field, 1080, 760, 0f)).isEmpty()
        assertThat(LookStars.place(field, 20_000, 20_000, 1f).size).isEqualTo(LookStars.MAX_STARS)
    }

    @Test
    fun glowsAreTranslucentAndTheTopFadesIntoTheBar() {
        val aurora = KeyboardLooks.CLEVERKEYS_AURORA
        for (g in aurora.glows) {
            val alpha = (g.color ushr 24) and 0xFF
            assertWithMessage("glow alpha").that(alpha).isIn(com.google.common.collect.Range.closed(0x10, 0x66))
        }
        assertThat(aurora.topFade).isIn(com.google.common.collect.Range.openClosed(0f, 0.5f))
    }

    /**
     * The curtains (maintainer 2026-11-09: the glows alone were "too cloud like"): rayed, lit
     * over the base and never dimmer than it, brightest at a curtain's rim, the same picture for
     * the same size.
     */
    @Test
    fun theAuroraIsRayedCurtainsOverTheBase() {
        val aurora = KeyboardLooks.CLEVERKEYS_AURORA
        assertWithMessage("the look paints curtains, not only glows").that(aurora.ribbons).isNotEmpty()
        val base = 0xFF0A080E.toInt()
        val w = 200
        val h = 120
        val a = AuroraRaster.render(base, aurora.ribbons, w, h, 0.5f)
        assertThat(a.size).isEqualTo(w * h)
        assertWithMessage("deterministic").that(a).isEqualTo(AuroraRaster.render(base, aurora.ribbons, w, h, 0.5f))
        fun channelSum(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        val baseSum = channelSum(base)
        for (c in a) {
            assertThat(c ushr 24).isEqualTo(0xFF)
            assertThat(channelSum(c)).isAtLeast(baseSum)
        }
        assertWithMessage("some of the view is lit").that(a.count { channelSum(it) > baseSum + 30 }).isGreaterThan(w * h / 20)
        assertWithMessage("and some is left as dark sky").that(a.count { channelSum(it) <= baseSum + 3 }).isGreaterThan(w * h / 10)

        // Rays: along a row through the near curtain, brightness varies column to column.
        val near = aurora.ribbons.first()
        val rays = AuroraRaster.rayProfile(near, w, 0.5f)
        assertThat(rays.all { it in 0.15f..1f }).isTrue()
        assertWithMessage("rays stand out of the band").that(rays.maxOrNull()!! - rays.minOrNull()!!).isGreaterThan(0.4f)

        assertThat(AuroraRaster.render(base, aurora.ribbons, 0, h, 0.5f)).isEmpty()
        assertWithMessage("no curtains = the base alone")
            .that(AuroraRaster.render(base, emptyList(), 4, 3, 1f).toSet()).containsExactly(base)
    }
}
