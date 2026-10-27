package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Theme Manager's card of a custom theme shows the colours of the CURRENT system mode (Saga
 * 2026-10-27, todo item 27: a custom theme with `<dark>` colours showed its light colours in dark
 * mode). A version-2 custom theme's [CustomTheme.colors] is only its base (light) scheme; the
 * mode's colours come from `ThemeProvider.getColorScheme(customId)` — the same call the keyboard,
 * the look cards and the stale-view tracker use — which flattens the light/dark variants.
 *
 * A source ratchet (pure tier): the card is a Compose call inside a LazyColumn, and the provider
 * call it must make is already covered by the provider's own rendering path.
 */
class ThemeManagerCustomCardColoursTest {

    private val source = File("src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt").readText()

    /** The `items(customThemes) { … }` block of the Theme Manager list. */
    private fun customItemsBlock(): String {
        val start = source.indexOf("items(customThemes)")
        check(start >= 0) { "Theme Manager no longer lists custom themes with items(customThemes)" }
        var depth = 0
        var i = source.indexOf('{', start)
        val open = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces after items(customThemes)")
    }

    @Test
    fun customThemeCardsTakeTheCurrentModesColoursFromTheProvider() {
        val block = customItemsBlock()
        assertWithMessage("the custom card must not draw the base (light) scheme directly")
            .that(block).doesNotContain("colorScheme = theme.colors,")
        assertWithMessage("the custom card asks the provider for the current mode's scheme")
            .that(block).contains("getColorScheme(customId)")
    }
}
