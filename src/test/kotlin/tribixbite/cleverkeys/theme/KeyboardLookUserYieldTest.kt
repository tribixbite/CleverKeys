package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [LookUserYield]: the CleverKeys default look ([KeyboardLooks.cleverkeysLook]) gives way only to
 * a swipe trail colour the user changed. Its geometry is its own: the global key gaps (maintainer
 * 2026-11-07: SwiftKey's spacing, equal on every side) and a custom border (the hairline is the
 * frame) both lose to it. Pure JVM.
 */
class KeyboardLookUserYieldTest {

    private val look = KeyboardLooks.cleverkeysLook.geometry
    private val user = KeyboardLookGeometry.UserGeometry(
        verticalMargin = 3f, horizontalMargin = 4f, borderRadius = 5f, borderWidth = 1f, borderWidthActivated = 3f,
    )

    private fun resolve(g: LookGeometry) =
        KeyboardLookGeometry.resolve(g, user, keyWidth = 100f, rowHeight = 50f, density = 2f)

    @Test
    fun theLookGapsWinOverTheUsersGlobalGaps() {
        val e = resolve(look)
        assertThat(e.horizontalMargin).isEqualTo(look.keyGapHorizontal!! * 100f)
        assertWithMessage("rows are as far apart as keys").that(e.verticalMargin).isEqualTo(e.horizontalMargin)
    }

    @Test
    fun theFrameIsAlwaysTheLooksEvenOverACustomBorder() {
        // The user geometry here carries a custom border (radius 5, outline 1/3): the look wins.
        val e = resolve(look)
        assertThat(e.borderRadius).isEqualTo(look.cornerRadiusDp!! * 2f)
        assertThat(e.borderWidth).isEqualTo(look.borderWidthDp!! * 2f)
        assertThat(e.borderWidthActivated).isEqualTo(look.borderWidthDp!! * 2f)
        assertWithMessage("label scale is the look's").that(e.labelScale).isEqualTo(look.labelScale)
    }

    @Test
    fun equalGapsAreCappedLikeAFractionalGap() {
        // A short row: the horizontal gap (40 px) would exceed half the row (25 px).
        val g = LookGeometry(keyGapHorizontal = 0.4f, equalGaps = true)
        val e = resolve(g)
        assertThat(e.horizontalMargin).isEqualTo(40f)
        assertThat(e.verticalMargin).isEqualTo(KeyboardLookGeometry.MAX_GAP * 50f)
        // Without a horizontal gap there is nothing to copy: the vertical fraction (or the user's) applies.
        assertThat(resolve(LookGeometry(keyGapVertical = 0.2f, equalGaps = true)).verticalMargin).isEqualTo(10f)
        assertThat(resolve(LookGeometry(equalGaps = true)).verticalMargin).isEqualTo(3f)
    }

    @Test
    fun aChangedTrailColourWins() {
        val lookTrail = KeyboardLooks.cleverkeysLook.dark.swipeTrail
        val silver = 0xFFC0C0C0.toInt()
        assertWithMessage("default trail: the look's").that(LookUserYield.trailColor(lookTrail, silver, silver)).isEqualTo(lookTrail)
        val red = 0xFFFF0000.toInt()
        assertWithMessage("changed trail: the user's").that(LookUserYield.trailColor(lookTrail, red, silver)).isEqualTo(red)
    }
}
