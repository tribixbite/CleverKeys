package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Rendering of resolved theme specs ([ThemeSpecs.render], 2026-10-28): a look written as a spec
 * draws exactly that look's palette, and a colour-only spec draws exactly like a plain runtime
 * (decorative / DIY) theme — no look-only roles appear unless the spec defines them.
 *
 * Mock tier only because [KeyboardColorScheme] holds Compose colours (ui-graphics is an AAR,
 * absent from the pure classpath); nothing Android is mocked.
 */
class ThemeSpecRenderTest {

    @Test
    fun aLookAsASpecRendersExactlyTheLooksPalette() {
        for (look in KeyboardLooks.ALL) for (light in listOf(true, false)) {
            val palette = look.palette(light)
            val r = ThemeSpecs.render(ThemeSpecs.fromLook(look, palette))
            // The latched-Shift colours have no spec role yet (TODO in ThemeSpecs.render).
            assertWithMessage("${look.id} light=$light palette")
                .that(r.palette).isEqualTo(palette.copy(latchedKey = 0, latchedLabel = 0))
            assertWithMessage("${look.id} light=$light scheme").that(r.scheme).isEqualTo(KeyboardLookSchemes.colorScheme(palette))
            assertThat(r.geometry).isEqualTo(look.geometry)
            assertThat(r.preview).isEqualTo(look.preview)
            assertThat(r.legends).isEqualTo(look.legends)
            assertThat(r.pillKeys).isEqualTo(look.pillKeys)
            assertThat(r.popups).isTrue()
            assertWithMessage("${look.id}: the two-mode export flattens back to the palette")
                .that(ThemeSpecs.flatten(ThemeSpecs.ofLook(look), light).colors).isEqualTo(ThemeSpecs.paletteColors(palette))
        }
    }

    @Test
    fun aColourOnlySpecRendersLikeAPlainRuntimeTheme() {
        val scheme = darkKeyboardColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF9B59B6))
        val r = ThemeSpecs.render(ThemeSpecs.fromScheme(scheme))
        assertThat(r.scheme).isEqualTo(scheme)
        assertWithMessage("no Enter/action/label overrides, shadow or pop-ups: Theme keeps its runtime defaults")
            .that(listOf(r.palette.enterKey, r.palette.actionKey, r.palette.enterLabel, r.palette.actionLabel,
                r.palette.modifierLabel, r.palette.keyShadow, r.palette.popup, r.labelPressed)).containsExactly(0, 0, 0, 0, 0, 0, 0, 0)
        assertThat(r.palette.barSeparator).isEqualTo(ThemeSpecs.defaultSeparator(r.palette.hint))
        assertThat(r.popups).isFalse()
        assertThat(r.legends).isNull()
        assertThat(r.preview).isNull()
        assertThat(r.geometry).isEqualTo(LookGeometry())
    }

}
