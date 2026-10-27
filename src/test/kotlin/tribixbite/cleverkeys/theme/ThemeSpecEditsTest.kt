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
