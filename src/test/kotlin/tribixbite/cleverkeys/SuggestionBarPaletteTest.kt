package tribixbite.cleverkeys

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import tribixbite.cleverkeys.theme.getAllPredefinedThemesList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Suggestion-bar legibility over every built-in theme (Pixel report 2026-10-17: suggestion text
 * the same colour as the bar).
 *
 * The bar draws three text roles on one background: ordinary chips ([Theme.suggestionTextColor]),
 * the highlighted commit chip ([Theme.suggestionHighConfidenceColor]) and the prompt chips
 * ("+word", "Don't ask": [Theme.suggestionSecondaryTextColor]). For XML themes [Theme] maps
 * them from `colorLabel`, `colorLabelActivated` and `colorSubLabel` on `colorKey`; but
 * `colorLabelActivated` was designed for `colorKeyActivated`, so on the bar it can equal the
 * background (Cobalt, Pine, ePaper Black: 1.00:1). Decorative themes carry the three roles
 * directly. [SuggestionBarPalette.resolve] is the single place both constructors pass through.
 *
 * Floor: [SuggestionBarPalette.MIN_CONTRAST] = 3:1 (WCAG 1.4.11 non-text / large-text
 * minimum). Measured 2026-10-17: ordinary chip text is 4.29:1 at worst (Everforest Light), so
 * every built-in already passes for that role and keeps its exact colour.
 */
class SuggestionBarPaletteTest {

    private data class Roles(val name: String, val text: Int, val highlight: Int, val secondary: Int, val background: Int)

    /** Each XML style's bar colours, resolved through `parent` and `?attr` references. */
    private fun xmlThemes(): List<Roles> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("res/values/themes.xml"))
        val styles = mutableMapOf<String, Pair<String?, Map<String, String>>>()
        val nodes = doc.getElementsByTagName("style")
        for (i in 0 until nodes.length) {
            val style = nodes.item(i) as Element
            val items = style.getElementsByTagName("item")
            val values = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("name") to item.textContent.trim()
            }
            styles[style.getAttribute("name")] = style.getAttribute("parent").ifEmpty { null } to values
        }
        fun resolve(style: String, attr: String): String? {
            val (parent, values) = styles[style] ?: return null
            val value = values[attr] ?: return parent?.let { resolve(it.substringAfterLast('/'), attr) }
            return if (value.startsWith("?")) resolve(style, value.removePrefix("?").removePrefix("attr/")) else value
        }
        fun argb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").takeLast(6).toLong(16)).toInt()
        return styles.keys.mapNotNull { style ->
            val label = resolve(style, "colorLabel") ?: return@mapNotNull null
            val key = resolve(style, "colorKey") ?: return@mapNotNull null
            // Monet styles reference @android:color/system_* (wallpaper-derived at runtime):
            // not resolvable here; their palette is Material's own tonal contrast.
            if (!label.startsWith("#") || !key.startsWith("#")) return@mapNotNull null
            Roles(
                style, argb(label),
                argb(resolve(style, "colorLabelActivated") ?: label),
                argb(resolve(style, "colorSubLabel") ?: label),
                argb(key),
            )
        }
    }

    private fun decorativeThemes(): List<Roles> = getAllPredefinedThemesList().map {
        val s = it.colorScheme
        Roles(it.id, s.suggestionText.toArgb(), s.suggestionHighConfidence.toArgb(),
            s.keySubLabel.toArgb(), s.suggestionBackground.toArgb())
    }

    private fun failures(themes: List<Roles>): List<String> = themes.flatMap { t ->
        val c = SuggestionBarPalette.resolve(t.text, t.highlight, t.secondary, t.background)
        listOf("text" to c.text, "highlight" to c.highlight, "secondary" to c.secondary).mapNotNull { (role, color) ->
            val ratio = SuggestionBarPalette.contrastRatio(color, t.background)
            if (ratio < SuggestionBarPalette.MIN_CONTRAST) "${t.name} $role %.2f:1".format(ratio) else null
        }
    }

    @Test
    fun everyBuiltInXmlThemeKeepsEverySuggestionRoleReadable() {
        val themes = xmlThemes()
        assertWithMessage("XML styles parsed").that(themes.map { it.name })
            .containsAtLeast("Dark", "Light", "Cobalt", "Pine", "ePaperBlack", "Desert", "Jungle", "Jewel")
        assertWithMessage("roles below ${SuggestionBarPalette.MIN_CONTRAST}:1").that(failures(themes)).isEmpty()
    }

    @Test
    fun everyDecorativeThemeKeepsEverySuggestionRoleReadable() {
        val themes = decorativeThemes()
        assertThat(themes.map { it.name }).contains("neon_cyberpunk")
        assertWithMessage("roles below ${SuggestionBarPalette.MIN_CONTRAST}:1").that(failures(themes)).isEmpty()
    }

    @Test
    fun readableColoursAreKeptExactly() {
        // Light: black text and #0066CC highlight on #CCCCCC already pass; nothing may change.
        val light = SuggestionBarPalette.resolve(0xFF000000.toInt(), 0xFF0066CC.toInt(), 0xFF333333.toInt(), 0xFFCCCCCC.toInt())
        assertThat(light).isEqualTo(SuggestionBarPalette.Colors(0xFF000000.toInt(), 0xFF0066CC.toInt(), 0xFF333333.toInt()))
        // Ordinary chip text of every built-in passes, so its colour is never substituted.
        for (t in xmlThemes() + decorativeThemes()) {
            assertWithMessage(t.name)
                .that(SuggestionBarPalette.resolve(t.text, t.highlight, t.secondary, t.background).text)
                .isEqualTo(t.text)
        }
    }

    @Test
    fun anUnreadableHighlightOrPromptFallsBackToTheTextColour() {
        // Cobalt: activated label is ?colorKey, i.e. the bar background itself.
        val cobalt = SuggestionBarPalette.resolve(0xFF95C9FF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())
        assertThat(cobalt.highlight).isEqualTo(0xFF95C9FF.toInt())
        assertThat(cobalt.secondary).isEqualTo(0xFF95C9FF.toInt())
    }

    @Test
    fun textTheSameColourAsTheBarBecomesBlackOrWhite() {
        // A DIY theme can set text == background; invisible text is never the intent.
        val onLight = SuggestionBarPalette.resolve(0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt(), 0xFFCCCCCC.toInt())
        assertThat(onLight).isEqualTo(SuggestionBarPalette.Colors(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()))
        val onDark = SuggestionBarPalette.resolve(0xFF121225.toInt(), 0xFF121225.toInt(), 0xFF121225.toInt(), 0xFF121225.toInt())
        assertThat(onDark.text).isEqualTo(0xFFFFFFFF.toInt())
        // The bar's alpha (user opacity) does not enter the comparison.
        assertThat(SuggestionBarPalette.contrastRatio(0x00000000, 0xFF000000.toInt())).isWithin(1e-9).of(1.0)
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertThat(SuggestionBarPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(1e-9).of(21.0)
        assertThat(SuggestionBarPalette.contrastRatio(0xFF777777.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.01).of(4.48)
    }
}
