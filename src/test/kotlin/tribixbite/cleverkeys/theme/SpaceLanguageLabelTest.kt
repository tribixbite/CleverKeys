package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import org.junit.Test

/**
 * The language name a look prints on the space bar ([SpaceLanguageLabel], Samsung-style,
 * 2026-10-28): Samsung Keyboard shows "English (US)" — the language in its own name, then the
 * region code in brackets when the keyboard knows one (`build/oct28-samsung-ref/1.jpg`).
 */
class SpaceLanguageLabelTest {

    @Test
    fun theSystemRegionIsAddedWhenTheLanguageMatchesIt() {
        assertThat(SpaceLanguageLabel.text("en", Locale.US)).isEqualTo("English (US)")
        assertThat(SpaceLanguageLabel.text("fr", Locale.FRANCE)).isEqualTo("Français (FR)")
    }

    @Test
    fun anotherLanguageIsNamedInItsOwnLanguageWithoutARegion() {
        assertThat(SpaceLanguageLabel.text("de", Locale.US)).isEqualTo("Deutsch")
        assertWithMessage("an explicit region in the tag wins").that(SpaceLanguageLabel.text("pt-BR", Locale.US))
            .isEqualTo("Português (BR)")
    }

    @Test
    fun anUnknownOrEmptyTagFallsBackToTheTagItself() {
        assertThat(SpaceLanguageLabel.text("", Locale.US)).isEmpty()
        assertThat(SpaceLanguageLabel.text("xx", Locale.US)).isEqualTo("xx")
    }
}
