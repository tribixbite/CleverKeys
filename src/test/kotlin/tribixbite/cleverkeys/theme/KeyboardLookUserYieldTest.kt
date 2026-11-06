package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [LookUserYield]: the CleverKeys default look ([KeyboardLooks.cleverkeysLook]) gives way to the
 * appearance settings a user tuned before it became the default — key gaps changed from their
 * defaults and a changed trail colour — and takes its own values everywhere else, the frame
 * included (a custom border does not survive: the borderless key is the look).
 * Pure JVM.
 */
class KeyboardLookUserYieldTest {

    private val look = KeyboardLooks.cleverkeysLook.geometry
    private val user = KeyboardLookGeometry.UserGeometry(
        verticalMargin = 3f, horizontalMargin = 4f, borderRadius = 5f, borderWidth = 1f, borderWidthActivated = 3f,
    )

    private fun resolve(g: LookGeometry) =
        KeyboardLookGeometry.resolve(g, user, keyWidth = 100f, rowHeight = 50f, density = 2f)

    @Test
    fun untouchedSettingsTakeTheLookGeometry() {
        val g = LookUserYield.geometry(look, verticalGapCustomised = false, horizontalGapCustomised = false)
        assertThat(g).isEqualTo(look)
        val e = resolve(g)
        assertThat(e.verticalMargin).isEqualTo(look.keyGapVertical!! * 50f)
        assertThat(e.horizontalMargin).isEqualTo(look.keyGapHorizontal!! * 100f)
        assertThat(e.borderRadius).isEqualTo(look.cornerRadiusDp!! * 2f)
        assertWithMessage("the look's 1 dp hairline").that(e.borderWidth).isEqualTo(look.borderWidthDp!! * 2f)
    }

    @Test
    fun aTunedGapKeepsTheUsersValueOnlyForThatGap() {
        val e = resolve(LookUserYield.geometry(look, verticalGapCustomised = true, horizontalGapCustomised = false))
        assertWithMessage("the user's vertical gap").that(e.verticalMargin).isEqualTo(3f)
        assertWithMessage("the look's horizontal gap").that(e.horizontalMargin).isEqualTo(look.keyGapHorizontal!! * 100f)
        val h = resolve(LookUserYield.geometry(look, verticalGapCustomised = false, horizontalGapCustomised = true))
        assertThat(h.horizontalMargin).isEqualTo(4f)
        assertThat(h.verticalMargin).isEqualTo(look.keyGapVertical!! * 50f)
    }

    @Test
    fun theFrameIsAlwaysTheLooksEvenOverACustomBorder() {
        // The user geometry here carries a custom border (radius 5, outline 1/3): the look wins.
        val e = resolve(LookUserYield.geometry(look, verticalGapCustomised = true, horizontalGapCustomised = true))
        assertThat(e.borderRadius).isEqualTo(look.cornerRadiusDp!! * 2f)
        assertThat(e.borderWidth).isEqualTo(look.borderWidthDp!! * 2f)
        assertThat(e.borderWidthActivated).isEqualTo(look.borderWidthDp!! * 2f)
        assertWithMessage("label scale is the look's either way").that(e.labelScale).isEqualTo(look.labelScale)
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
