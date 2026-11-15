package tribixbite.cleverkeys.theme

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review 2026-11-14 M22: Theme Manager kept the Theme Creator's draft (name, 17 colours, the
 * "Keyboard look" part) and the theme being edited in plain `remember`, so a rotation threw the
 * edit away. [ThemeEditorSavers] carry them through the saved-state Bundle over the existing JSON
 * codecs; each must give back exactly what it saved, and only Bundle-safe values.
 */
class ThemeEditorSaversTest {

    /** Accepts what a Bundle accepts for these savers: strings only. */
    private val scope = SaverScope { it is String }

    private fun <T : Any?> roundTrip(saver: Saver<T, String>, value: T): T {
        val saved = with(saver) { scope.save(value) }
        return if (saved == null) value else saver.restore(saved) as T
    }

    private val scheme = darkKeyboardColorScheme().copy(
        keyDefault = Color(0xFF123456),
        swipeTrail = Color(0x80FEDCBA),
    )

    @Test
    fun colourScheme_survives() {
        assertThat(roundTrip(ThemeEditorSavers.ColorScheme, scheme)).isEqualTo(scheme)
    }

    @Test
    fun emptyLookPart_survives() {
        assertThat(roundTrip(ThemeEditorSavers.Spec, ThemeSpec())).isEqualTo(ThemeSpec())
    }

    @Test
    fun aDefinedLookPart_survives() {
        val spec = ThemeSpec(
            light = mapOf(ThemeColorRole.entries.first() to 0xFF00FF00.toInt()),
            geometry = LookGeometry(keyGapHorizontal = 0.11f, equalGaps = true),
            legends = LookLegends.DIGITS,
            hintStyle = LookHintStyle(0.3f, 700),
        )
        assertThat(roundTrip(ThemeEditorSavers.Spec, spec)).isEqualTo(spec)
    }

    @Test
    fun theThemeBeingEdited_survivesWithItsIdAndTimes() {
        val theme = CustomTheme(
            id = "abc", name = "Ocean", colors = scheme, createdAt = 5, modifiedAt = 6,
            extension = ThemeSpec(geometry = LookGeometry(equalGaps = true)),
        )
        assertThat(roundTrip(ThemeEditorSavers.EditedTheme, theme)).isEqualTo(theme)
    }

    @Test
    fun noThemeBeingEdited_savesNothing() {
        val saved = with(ThemeEditorSavers.EditedTheme) { scope.save(null) }
        assertThat(saved).isNull()
    }
}
