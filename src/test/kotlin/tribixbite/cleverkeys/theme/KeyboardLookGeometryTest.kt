package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Keyboard looks (2026-10-27): which geometry a keyboard view draws with — the effective look's
 * where it defines an attribute, the user's appearance settings everywhere else.
 *
 * Precedence pinned here (docs/specs/layout-system.md "Keyboard looks"):
 *  - no look (every built-in XML, decorative and DIY theme): exactly the user's geometry;
 *  - a look: every attribute it defines wins, including over a custom border
 *    (`border_config`), because selecting or binding a look is an explicit choice;
 *  - attributes the look leaves null keep the user's value;
 *  - label size is RELATIVE to the user's size setting, and out-of-range values are clamped.
 */
class KeyboardLookGeometryTest {

    /** A user with a CUSTOM border (border_config on): radius 12 px, 3 px outline. */
    private val user = KeyboardLookGeometry.UserGeometry(
        verticalMargin = 5f,
        horizontalMargin = 4f,
        borderRadius = 12f,
        borderWidth = 3f,
        borderWidthActivated = 6f,
    )

    private fun resolve(look: LookGeometry?) = KeyboardLookGeometry.resolve(
        look, user, keyWidth = 100f, rowHeight = 200f, density = 2f,
    )

    @Test
    fun withoutALookTheUserGeometryIsUsedUnchanged() {
        val e = resolve(null)
        assertThat(e.verticalMargin).isEqualTo(5f)
        assertThat(e.horizontalMargin).isEqualTo(4f)
        assertThat(e.borderRadius).isEqualTo(12f)
        assertThat(e.borderWidth).isEqualTo(3f)
        assertThat(e.borderWidthActivated).isEqualTo(6f)
        assertThat(e.shadowDy).isEqualTo(0f)
        assertThat(e.labelScale).isEqualTo(1f)
        assertThat(e.labelWeight).isNull()
    }

    @Test
    fun everyAttributeALookDefinesWinsEvenOverACustomBorder() {
        val e = resolve(
            LookGeometry(
                keyGapHorizontal = 0.15f, keyGapVertical = 0.2f, cornerRadiusDp = 5f,
                borderWidthDp = 0f, shadowDyDp = 1f, labelScale = 1.2f, labelWeight = 500,
            )
        )
        // Gaps are fractions of one key unit's width and of the row height.
        assertThat(e.horizontalMargin).isWithin(1e-4f).of(15f)
        assertThat(e.verticalMargin).isWithin(1e-4f).of(40f)
        // Dimensions are dp, converted with the density.
        assertWithMessage("a look's radius replaces the user's custom border radius while it is effective")
            .that(e.borderRadius).isEqualTo(10f)
        assertThat(e.borderWidth).isEqualTo(0f)
        assertWithMessage("a look draws one outline width pressed or not")
            .that(e.borderWidthActivated).isEqualTo(0f)
        assertThat(e.shadowDy).isEqualTo(2f)
        assertThat(e.labelScale).isEqualTo(1.2f)
        assertThat(e.labelWeight).isEqualTo(500)
    }

    @Test
    fun attributesALookLeavesUndefinedKeepTheUsersValues() {
        val e = resolve(LookGeometry(cornerRadiusDp = 8f))
        assertThat(e.borderRadius).isEqualTo(16f)
        assertThat(e.verticalMargin).isEqualTo(5f)
        assertThat(e.horizontalMargin).isEqualTo(4f)
        assertThat(e.borderWidth).isEqualTo(3f)
        assertThat(e.borderWidthActivated).isEqualTo(6f)
        assertThat(e.shadowDy).isEqualTo(0f)
        assertThat(e.labelScale).isEqualTo(1f)
        assertThat(e.labelWeight).isNull()
    }

    @Test
    fun outOfRangeLookValuesAreClampedNeverRenderedAsIs() {
        val e = resolve(
            LookGeometry(
                keyGapHorizontal = 3f, keyGapVertical = -1f, cornerRadiusDp = -4f,
                borderWidthDp = -2f, shadowDyDp = -1f, labelScale = 9f, labelWeight = 2000,
            )
        )
        assertThat(e.horizontalMargin).isEqualTo(KeyboardLookGeometry.MAX_GAP * 100f)
        assertThat(e.verticalMargin).isEqualTo(0f)
        assertThat(e.borderRadius).isEqualTo(0f)
        assertThat(e.borderWidth).isEqualTo(0f)
        assertThat(e.shadowDy).isEqualTo(0f)
        assertThat(e.labelScale).isEqualTo(KeyboardLookGeometry.MAX_LABEL_SCALE)
        assertThat(e.labelWeight).isEqualTo(900)
    }
}
