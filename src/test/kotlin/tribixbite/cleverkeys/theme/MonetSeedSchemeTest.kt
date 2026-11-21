package tribixbite.cleverkeys.theme

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Theme Creator's starting colours for a Monet theme (todo 13c, code-quality audit
 * 2026-10-20): they were a fixed purple/grey "approximation" whatever the wallpaper, so
 * "Customize" on Monet opened colours the keyboard never drew. [MonetSeedScheme] reads the
 * system colours that `MonetLight` / `MonetDark` (`res/values/themes.xml`) draw with, and
 * picks the variant the keyboard picks (`Config` style map). Mock tier (compose graphics on the
 * classpath); [color] stands in for `Context.getColor`, giving every `android.R.color` id its
 * own value.
 */
class MonetSeedSchemeTest {

    /** A distinct colour per resource id, so each role can be traced to the id it read. */
    private val color: (Int) -> Int = { id -> 0xFF000000.toInt() or (id and 0xFFFFFF) }

    @Test
    fun darkMonetSeedsTheColoursMonetDarkDraws() {
        val s = MonetSeedScheme.dynamic(light = false, color = color)
        assertThat(s.keyboardBackground.toArgb()).isEqualTo(color(android.R.color.system_neutral1_900))
        assertThat(s.keyDefault.toArgb()).isEqualTo(color(android.R.color.system_neutral1_800))
        assertWithMessage("colorKeyActivated = ?colorKeyboard")
            .that(s.keyActivated.toArgb()).isEqualTo(color(android.R.color.system_neutral1_900))
        assertThat(s.keyLabel.toArgb()).isEqualTo(color(android.R.color.system_neutral1_0))
        assertThat(s.keySubLabel.toArgb()).isEqualTo(color(android.R.color.system_neutral1_400))
        assertWithMessage("colorLabelActivated: the accent the keyboard shows")
            .that(s.swipeTrail.toArgb()).isEqualTo(color(android.R.color.system_accent1_400))
    }

    @Test
    fun lightMonetSeedsTheColoursMonetLightDraws() {
        val s = MonetSeedScheme.dynamic(light = true, color = color)
        assertThat(s.keyboardBackground.toArgb()).isEqualTo(color(android.R.color.system_neutral1_100))
        assertThat(s.keyDefault.toArgb()).isEqualTo(color(android.R.color.system_neutral1_50))
        assertThat(s.keyActivated.toArgb()).isEqualTo(color(android.R.color.system_neutral1_100))
        assertThat(s.keyLabel.toArgb()).isEqualTo(color(android.R.color.system_neutral1_800))
        assertThat(s.keySubLabel.toArgb()).isEqualTo(color(android.R.color.system_neutral1_500))
        assertThat(s.swipeTrail.toArgb()).isEqualTo(color(android.R.color.system_accent1_400))
    }

    @Test
    fun theVariantFollowsTheThemeNameAndForAutoTheSystemMode() {
        assertThat(MonetSeedScheme.isLight("monetlight", systemLight = false)).isTrue()
        assertThat(MonetSeedScheme.isLight("monetdark", systemLight = true)).isFalse()
        assertWithMessage("Monet (Auto) follows the system, as Config's style map does")
            .that(MonetSeedScheme.isLight("monet", systemLight = true)).isTrue()
        assertThat(MonetSeedScheme.isLight("monet", systemLight = false)).isFalse()
    }

    @Test
    fun withoutTheSystemPaletteTheSeedIsThePlainThemeTheKeyboardDraws() {
        // Below Android 12 Config draws Monet with R.style.Light / R.style.Dark.
        assertThat(MonetSeedScheme.fallbackThemeName(light = true)).isEqualTo("light")
        assertThat(MonetSeedScheme.fallbackThemeName(light = false)).isEqualTo("dark")
    }

    @Test
    fun everyMonetThemeNameIsRecognisedAndNothingElse() {
        assertThat(MonetSeedScheme.THEME_NAMES).containsExactly("monet", "monetlight", "monetdark")
    }
}
