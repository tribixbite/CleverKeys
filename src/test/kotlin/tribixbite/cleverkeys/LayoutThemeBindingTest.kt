package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Per-layout THEME binding: the pure rules (modelled on the per-layout language binding).
 *
 * [LayoutThemeBinding] decides (a) which stored values are valid theme ids, (b) which binding a
 * layout entry has once its user choice and its XML `theme` default are combined, (c) which
 * theme the keyboard renders for the current layout — including the fallback to the global
 * theme when the bound theme is not on this device — and (d) how the binding is written into
 * an exported layout XML. Spec: docs/specs/layout-system.md "Per-layout theme binding".
 */
class LayoutThemeBindingTest {

    private val everyThemeExists: (String) -> Boolean = { true }

    // ── id validation ───────────────────────────────────────────────────────────

    @Test
    fun validThemeIdsAreKeptVerbatimApartFromTrimming() {
        assertThat(LayoutThemeBinding.normalizeId("rosepine")).isEqualTo("rosepine")
        assertThat(LayoutThemeBinding.normalizeId(" decorative_neon_hot_pink ")).isEqualTo("decorative_neon_hot_pink")
        assertWithMessage("custom ids embed a UUID and are case-sensitive store keys — never lower-cased")
            .that(LayoutThemeBinding.normalizeId("custom_3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
            .isEqualTo("custom_3F2504E0-4F89-11D3-9A0C-0305E82C3301")
    }

    @Test
    fun invalidThemeIdsAreRejected() {
        for (bad in listOf(null, "", "   ", "none", "NONE", "../x", "a b", "dark\"", "<x>", "_dark", "x".repeat(101))) {
            assertWithMessage("'$bad' must not be accepted as a theme id")
                .that(LayoutThemeBinding.normalizeId(bad)).isNull()
        }
    }

    @Test
    fun entryValuesKeepTheExplicitUnboundSentinelAndDropGarbage() {
        assertThat(LayoutThemeBinding.normalizeEntry(null)).isNull()
        assertThat(LayoutThemeBinding.normalizeEntry("None")).isEqualTo(LayoutThemeBinding.UNBOUND)
        assertThat(LayoutThemeBinding.normalizeEntry("cobalt")).isEqualTo("cobalt")
        assertWithMessage("an invalid id from a hand-edited backup falls back to 'no choice'")
            .that(LayoutThemeBinding.normalizeEntry("dark\" onload=\"x")).isNull()
    }

    // ── entry precedence: user choice over XML default ─────────────────────────

    @Test
    fun userChoiceOverridesTheXmlDefault() {
        assertThat(LayoutThemeBinding.effective(entry = "cobalt", declared = "rosepine")).isEqualTo("cobalt")
        assertWithMessage("'none' explicitly follows the global theme over an XML default")
            .that(LayoutThemeBinding.effective(entry = "none", declared = "rosepine")).isNull()
        assertWithMessage("no user choice: the XML default applies")
            .that(LayoutThemeBinding.effective(entry = null, declared = "rosepine")).isEqualTo("rosepine")
        assertWithMessage("an invalid XML value never binds")
            .that(LayoutThemeBinding.effective(entry = null, declared = "rose pine")).isNull()
        assertThat(LayoutThemeBinding.effective(entry = null, declared = null)).isNull()
    }

    @Test
    fun pickerChoicesMapToTheSmallestEntryValue() {
        assertWithMessage("following the global theme with no XML default stores nothing")
            .that(LayoutThemeBinding.entryValueFor(choice = null, declared = null)).isNull()
        assertWithMessage("following the global theme over an XML default must be explicit")
            .that(LayoutThemeBinding.entryValueFor(choice = null, declared = "rosepine"))
            .isEqualTo(LayoutThemeBinding.UNBOUND)
        assertWithMessage("picking the XML default stores nothing, so the layout keeps following its XML")
            .that(LayoutThemeBinding.entryValueFor(choice = "rosepine", declared = "rosepine")).isNull()
        assertThat(LayoutThemeBinding.entryValueFor(choice = "cobalt", declared = "rosepine")).isEqualTo("cobalt")
        assertThat(LayoutThemeBinding.entryValueFor(choice = "cobalt", declared = null)).isEqualTo("cobalt")
    }

    @Test
    fun xmlThemeProblemFlagsOnlyADeclaredButInvalidAttribute() {
        assertThat(LayoutThemeBinding.xmlThemeProblem(null)).isFalse()
        assertThat(LayoutThemeBinding.xmlThemeProblem("rosepine")).isFalse()
        assertWithMessage("an unknown but well-formed id is NOT a problem: it may be a DIY theme from another device")
            .that(LayoutThemeBinding.xmlThemeProblem("custom_not-on-this-device")).isFalse()
        assertThat(LayoutThemeBinding.xmlThemeProblem("rose pine")).isTrue()
        assertThat(LayoutThemeBinding.xmlThemeProblem("")).isTrue()
    }

    // ── effective theme resolution ──────────────────────────────────────────────

    @Test
    fun aBoundLayoutRendersItsThemeAndAnUnboundLayoutTheGlobalTheme() {
        val bindings = listOf(null, "rosepine")

        val bound = LayoutThemeBinding.resolve(bindings, currentIndex = 1, globalTheme = "dark", isAvailable = everyThemeExists)
        assertThat(bound).isEqualTo(ActiveTheme(themeName = "rosepine", boundTheme = "rosepine", missingTheme = null))

        val unbound = LayoutThemeBinding.resolve(bindings, currentIndex = 0, globalTheme = "dark", isAvailable = everyThemeExists)
        assertThat(unbound).isEqualTo(ActiveTheme(themeName = "dark", boundTheme = null, missingTheme = null))
    }

    @Test
    fun aBindingToAThemeThatIsNotOnThisDeviceFallsBackToTheGlobalTheme() {
        val gone = "custom_deleted-uuid"
        val active = LayoutThemeBinding.resolve(
            listOf(gone), currentIndex = 0, globalTheme = "cobalt", isAvailable = { it != gone },
        )
        assertWithMessage("a deleted DIY theme must never render (ThemeProvider would pick its own fallback, not the user's)")
            .that(active.themeName).isEqualTo("cobalt")
        assertThat(active.boundTheme).isNull()
        assertWithMessage("…and the missing id is reported so Layout Manager can show it")
            .that(active.missingTheme).isEqualTo(gone)
    }

    @Test
    fun aSelectionPastTheEndResolvesLikeLayoutManagerDoesToLayoutZero() {
        val active = LayoutThemeBinding.resolve(
            listOf("jewel", null), currentIndex = 7, globalTheme = "dark", isAvailable = everyThemeExists,
        )
        assertThat(active.themeName).isEqualTo("jewel")
    }

    @Test
    fun noLayoutsAtAllFollowsTheGlobalTheme() {
        val active = LayoutThemeBinding.resolve(emptyList(), currentIndex = 0, globalTheme = "dark", isAvailable = everyThemeExists)
        assertThat(active).isEqualTo(ActiveTheme("dark", null, null))
    }

    @Test
    fun theGlobalThemeIsNeverRewrittenByResolution() {
        // The binding is an override: resolution reads the user's global theme, never writes it.
        val global = "monet"
        val active = LayoutThemeBinding.resolve(listOf("rosepine"), 0, global, everyThemeExists)
        assertThat(active.themeName).isEqualTo("rosepine")
        assertThat(global).isEqualTo("monet")
    }

    // ── layout XML: reading and writing the `theme` attribute ─────────────────

    @Test
    fun declaredThemeIsReadFromTheKeyboardRootOnly() {
        val xml = """<?xml version="1.0"?>
            |<keyboard name="Mine" theme="rosepine" script="latin">
            |  <row><key key0="a" theme="ignored"/></row>
            |</keyboard>""".trimMargin()
        assertThat(LayoutThemeBinding.declaredThemeOfXml(xml)).isEqualTo("rosepine")
        assertThat(LayoutThemeBinding.declaredThemeOfXml("<keyboard name=\"x\"><row/></keyboard>")).isNull()
        assertThat(LayoutThemeBinding.declaredThemeOfXml("<keyboard\n  theme='cobalt'\n><row/></keyboard>")).isEqualTo("cobalt")
        assertWithMessage("a sibling attribute that merely ends in 'theme' is not the theme attribute")
            .that(LayoutThemeBinding.declaredThemeOfXml("<keyboard xtheme=\"cobalt\"><row/></keyboard>")).isNull()
    }

    @Test
    fun exportWritesASpecificBindingOntoTheRoot() {
        val xml = "<keyboard name=\"Mine\" script=\"latin\">\n<row><key key0=\"a\"/></row>\n</keyboard>"
        val out = LayoutThemeBinding.applyToXml(xml, entry = "decorative_neon_cyberpunk")
        assertThat(LayoutThemeBinding.declaredThemeOfXml(out)).isEqualTo("decorative_neon_cyberpunk")
        assertWithMessage("the rest of the document is untouched")
            .that(out.replace(" theme=\"decorative_neon_cyberpunk\"", "")).isEqualTo(xml)
    }

    @Test
    fun exportReplacesAnExistingAttributeAndUnboundRemovesIt() {
        val xml = "<keyboard name=\"Mine\" theme=\"rosepine\" script=\"latin\"><row/></keyboard>"
        val replaced = LayoutThemeBinding.applyToXml(xml, entry = "cobalt")
        assertThat(LayoutThemeBinding.declaredThemeOfXml(replaced)).isEqualTo("cobalt")
        assertThat(Regex("theme=").findAll(replaced).count()).isEqualTo(1)

        val unbound = LayoutThemeBinding.applyToXml(xml, entry = LayoutThemeBinding.UNBOUND)
        assertThat(LayoutThemeBinding.declaredThemeOfXml(unbound)).isNull()
        assertThat(unbound).isEqualTo("<keyboard name=\"Mine\" script=\"latin\"><row/></keyboard>")
    }

    @Test
    fun exportWithNoUserChoiceKeepsTheXmlAsWritten() {
        val xml = "<keyboard theme=\"rosepine\"><row/></keyboard>"
        assertThat(LayoutThemeBinding.applyToXml(xml, entry = null)).isEqualTo(xml)
        val noRoot = "not a layout"
        assertThat(LayoutThemeBinding.applyToXml(noRoot, entry = "cobalt")).isEqualTo(noRoot)
    }
}
