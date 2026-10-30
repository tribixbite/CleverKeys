package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.KeyGeometryField
import tribixbite.cleverkeys.KeyStyles
import tribixbite.cleverkeys.LayoutThemeBinding

/**
 * The Theme Creator's "Keyboard look" edits ([ThemeSpecEdits]), Layout Manager's theme-choice
 * entries for a layout with its own `<theme>`, and the documentation drift: the wiki reference
 * names every attribute the parsers accept and embeds the worked example verbatim, and the
 * instrumented tests' copy of the example is identical to the wiki's. Pure JVM.
 */
class ThemeSpecEditsTest {

    @Test
    fun colourRowsEditTheMapOfTheirModeAndClearingRemovesTheEntry() {
        var ext = ThemeSpec()
        ext = ThemeSpecEdits.withColor(ext, ThemeSpecEdits.Mode.BOTH, ThemeColorRole.ENTER_KEY, 1)
        ext = ThemeSpecEdits.withColor(ext, ThemeSpecEdits.Mode.DARK, ThemeColorRole.KEY, 2)
        assertThat(ext.colors).containsExactly(ThemeColorRole.ENTER_KEY, 1)
        assertThat(ext.dark).containsExactly(ThemeColorRole.KEY, 2)
        assertThat(ThemeSpecEdits.color(ext, ThemeSpecEdits.Mode.LIGHT, ThemeColorRole.KEY)).isNull()
        ext = ThemeSpecEdits.withColor(ext, ThemeSpecEdits.Mode.DARK, ThemeColorRole.KEY, null)
        assertThat(ext.dark).isEmpty()
        assertWithMessage("the 17 DIY colours belong to the theme's scheme, not the extension's common map")
            .that(runCatching { ThemeSpecEdits.withColor(ext, ThemeSpecEdits.Mode.BOTH, ThemeColorRole.KEY, 3) }.isFailure)
            .isTrue()
    }

    @Test
    fun geometrySlidersSnapToTheirGridStayInRangeAndUnsetCleanly() {
        val g = ThemeSpecEdits.GeometryField.GAP_HORIZONTAL.set(LookGeometry(), 0.1234f)
        assertThat(g.keyGapHorizontal).isEqualTo(0.12f)
        assertThat(ThemeSpecEdits.GeometryField.GAP_VERTICAL.snap(9f)).isEqualTo(ThemeSpecXml.GAP_RANGE.endInclusive)
        assertThat(ThemeSpecEdits.GeometryField.LABEL_WEIGHT.set(LookGeometry(), 540f).labelWeight).isEqualTo(500)
        assertThat(ThemeSpecEdits.GeometryField.LABEL_SCALE.snap(1.17f)).isEqualTo(1.15f)
        assertThat(ThemeSpecEdits.GeometryField.GAP_HORIZONTAL.set(g, null)).isEqualTo(LookGeometry())
        for (f in ThemeSpecEdits.GeometryField.entries) {
            assertWithMessage("$f slider range lies inside what <theme> accepts")
                .that(ThemeSpecJson.sanitize(ThemeSpec(geometry = f.set(LookGeometry(), f.range.endInclusive))).geometry)
                .isEqualTo(f.set(LookGeometry(), f.range.endInclusive))
        }
        assertWithMessage("an extension that defines nothing is not stored (the theme stays version 1)")
            .that(ThemeSpecEdits.stored(ThemeSpec(name = "x"))).isNull()
    }

    /**
     * The Theme Creator's suggestion-bar controls (2026-10-30; before, only layout XML / JSON could
     * arrange the bar): "Use keyboard setting" = scrolling chips, then slots, centre, quotes,
     * emphasis and separators — each stored in the version-2 extension and read back by JSON.
     */
    @Test
    fun barControlsDefineTheArrangementAndRoundTripThroughTheCustomThemeJson() {
        var ext = ThemeSpec()
        assertThat(ThemeSpecEdits.withBarDefined(ext, define = false).bar).isNull()
        ext = ThemeSpecEdits.withBarDefined(ext, define = true)
        assertWithMessage("defining the bar starts from the familiar three slots, best centred")
            .that(ext.bar).isEqualTo(LookBar())
        ext = ThemeSpecEdits.withBarSlots(ext, 4.6f)
        assertThat(ext.bar!!.slots).isEqualTo(5)
        assertWithMessage("the slider cannot leave 1..5").that(ThemeSpecEdits.withBarSlots(ext, 0f).bar!!.slots).isEqualTo(1)
        ext = ThemeSpecEdits.withBar(ext) { it.copy(centreBest = false, quoteTyped = true, emphasis = BarEmphasis.CENTRE) }
        assertThat(ext.bar).isEqualTo(LookBar(slots = 5, centreBest = false, quoteTyped = true, emphasis = BarEmphasis.CENTRE))
        assertWithMessage("re-defining keeps the edited arrangement")
            .that(ThemeSpecEdits.withBarDefined(ext, define = true).bar).isEqualTo(ext.bar)
        assertThat(ThemeSpecEdits.stored(ext)).isNotNull()
        assertThat(ThemeSpecJson.fromJson(ThemeSpecJson.toJson(ext)).bar).isEqualTo(ext.bar)
        assertWithMessage("whole slots on the slider: 3 stops between 1 and 5")
            .that(ThemeSpecEdits.BAR_SLOTS_RANGE).isEqualTo(1f..5f)
    }

    @Test
    fun separatorChoiceIsTheCommonBarSeparatorColour() {
        val subLabel = 0xFF9FB3C8.toInt()
        var ext = ThemeSpec()
        assertThat(ThemeSpecEdits.separators(ext)).isEqualTo(ThemeSpecEdits.Separators.FOLLOW)
        ext = ThemeSpecEdits.withSeparators(ext, ThemeSpecEdits.Separators.HIDE, subLabel)
        assertThat(ext.colors[ThemeColorRole.BAR_SEPARATOR]).isEqualTo(0)
        assertThat(ThemeSpecEdits.separators(ext)).isEqualTo(ThemeSpecEdits.Separators.HIDE)
        ext = ThemeSpecEdits.withSeparators(ext, ThemeSpecEdits.Separators.SHOW, subLabel)
        assertWithMessage("showing from none takes the default separator (sub-label at alpha 100)")
            .that(ext.colors[ThemeColorRole.BAR_SEPARATOR]).isEqualTo(ThemeSpecs.defaultSeparator(subLabel))
        val custom = ThemeSpecEdits.withColor(ext, ThemeSpecEdits.Mode.BOTH, ThemeColorRole.BAR_SEPARATOR, 0xFF123456.toInt())
        assertWithMessage("SHOW keeps a colour the user picked")
            .that(ThemeSpecEdits.withSeparators(custom, ThemeSpecEdits.Separators.SHOW, subLabel).colors[ThemeColorRole.BAR_SEPARATOR])
            .isEqualTo(0xFF123456.toInt())
        assertThat(ThemeSpecEdits.withSeparators(custom, ThemeSpecEdits.Separators.FOLLOW, subLabel).colors).isEmpty()
    }

    @Test
    fun theFunctionKeyTextSizeIsAGeometrySlider() {
        val f = ThemeSpecEdits.GeometryField.FUNCTION_LABEL_SCALE
        assertThat(f.set(LookGeometry(), 1.27f).functionLabelScale).isEqualTo(1.25f)
        assertThat(f.neutral).isEqualTo(1f)
        assertThat(f.get(LookGeometry(functionLabelScale = 1.3f))).isEqualTo(1.3f)
    }

    @Test
    fun followingTheGlobalThemeOverALayoutsOwnThemeIsStoredExplicitly() {
        assertThat(LayoutThemeBinding.entryValueFor(null, declared = null, hasEmbedded = true)).isEqualTo(LayoutThemeBinding.UNBOUND)
        assertThat(LayoutThemeBinding.entryValueFor(null, declared = null)).isNull()
        assertThat(LayoutThemeBinding.entryValueFor("cobalt", declared = null, hasEmbedded = true)).isEqualTo("cobalt")
    }

    private val reference = File("docs/wiki/layouts/layout-xml-reference.md")
    private val example = File("docs/wiki/layouts/examples/harbour.xml")

    @Test
    fun theWikiReferenceDocumentsEveryAttributeAndEmbedsTheWorkedExampleVerbatim() {
        val doc = reference.readText()
        val attributes = ThemeSpecXml.THEME_ATTRIBUTES + ThemeColorRole.entries.map { it.xmlName } +
            KeyStyles.ATTRIBUTES + KeyGeometryField.entries.map { it.xmlAttribute }
        for (a in attributes) assertWithMessage("`$a` documented").that(doc).contains("`$a`")
        assertWithMessage("the example is shown exactly as shipped").that(doc).contains(example.readText().trim())
        assertWithMessage("the instrumented tests load the same example")
            .that(File("src/androidTest/assets/layouts/harbour.xml").readText()).isEqualTo(example.readText())
    }
}
