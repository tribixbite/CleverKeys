package tribixbite.cleverkeys

import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import tribixbite.cleverkeys.theme.CustomTheme
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme

/**
 * Review 2026-11-14 (cosmetic): Layout Manager's per-layout theme picker drew a custom theme's
 * swatch from its base [CustomTheme.colors], while Theme Manager flattens a version-2 theme's
 * light/dark variant through ThemeProvider — the same theme showed two different swatches. The
 * picker now takes the scheme lookup from its caller, which passes ThemeProvider's.
 */
class LayoutThemePickerSchemeTest {

    private val resources = mockk<Resources>(relaxed = true) {
        every { getString(any()) } returns "section"
    }

    private val base = darkKeyboardColorScheme()
    private val variant = base.copy(
        keyboardBackground = Color(0xFF102030),
        keyDefault = Color(0xFF405060),
        keyLabel = Color(0xFFA0B0C0),
    )
    private val theme = CustomTheme(id = "t1", name = "Mine", colors = base)

    @Test
    fun theCustomSwatchUsesTheSchemeTheCallerResolves() {
        val choice = layoutThemeChoices(resources, listOf(theme)) { variant }
            .single { it.id == "custom_t1" }

        assertThat(choice.background).isEqualTo(variant.keyboardBackground)
        assertThat(choice.key).isEqualTo(variant.keyDefault)
        assertThat(choice.label).isEqualTo(variant.keyLabel)
    }

    @Test
    fun withoutALookupTheBaseColoursAreUsed() {
        val choice = layoutThemeChoices(resources, listOf(theme)).single { it.id == "custom_t1" }
        assertThat(choice.background).isEqualTo(base.keyboardBackground)
    }
}
