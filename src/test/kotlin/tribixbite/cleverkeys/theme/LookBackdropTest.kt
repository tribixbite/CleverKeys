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
        // 411 × 290 dp ≈ 11.9 units of 100 × 100 dp at 4 per unit.
        assertThat(stars.size).isEqualTo(48)
        for (s in stars) {
            assertThat(s.x).isAtLeast(0f); assertThat(s.x).isLessThan(1080f)
            assertThat(s.y).isAtLeast(0f); assertThat(s.y).isLessThan(760f)
            assertThat(s.radius).isAtLeast(field.minRadiusDp * density - 1e-3f)
            assertThat(s.radius).isAtMost(field.maxRadiusDp * density + 1e-3f)
            assertThat(s.alpha).isAtLeast(field.minAlpha); assertThat(s.alpha).isAtMost(field.maxAlpha)
        }
        assertWithMessage("every 7th star glints").that(stars.count { it.glint }).isEqualTo(7)
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
        assertThat(aurora.glows).isNotEmpty()
        for (g in aurora.glows) {
            val alpha = (g.color ushr 24) and 0xFF
            assertWithMessage("glow alpha").that(alpha).isIn(com.google.common.collect.Range.closed(0x20, 0x66))
        }
        assertThat(aurora.topFade).isIn(com.google.common.collect.Range.openClosed(0f, 0.5f))
    }
}
