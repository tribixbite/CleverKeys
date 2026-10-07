package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * GH #186 / GH #61 — per-layout language binding: the pure rules.
 *
 * [LayoutLanguageBinding] decides (a) which stored values are valid language codes, (b) which
 * binding a layout entry has once its user choice and its XML `language` default are combined,
 * and (c) which languages are ACTIVE for the current layout. Config, the backup importer and
 * Layout Manager all route through these functions, so the rules are pinned once here.
 * Spec: docs/specs/dictionary-and-language-system.md "Per-layout language binding".
 */
class LayoutLanguageBindingTest {

    // ── code validation ─────────────────────────────────────────────────────────

    @Test
    fun validCodesAreNormalisedToLowerCase() {
        assertThat(LayoutLanguageBinding.normalizeCode("fa")).isEqualTo("fa")
        assertThat(LayoutLanguageBinding.normalizeCode(" FA ")).isEqualTo("fa")
        assertThat(LayoutLanguageBinding.normalizeCode("pt_br")).isEqualTo("pt_br")
        assertThat(LayoutLanguageBinding.normalizeCode("en-web-50k")).isEqualTo("en-web-50k")
    }

    @Test
    fun invalidCodesAreRejected() {
        for (bad in listOf(null, "", "  ", "persian", "f", "../x", "fa/", "e1", "none", "f a")) {
            assertWithMessage("'$bad' must not be accepted as a language code")
                .that(LayoutLanguageBinding.normalizeCode(bad)).isNull()
        }
    }

    @Test
    fun entryValuesKeepTheExplicitUnboundSentinelAndDropGarbage() {
        assertThat(LayoutLanguageBinding.normalizeEntry(null)).isNull()
        assertThat(LayoutLanguageBinding.normalizeEntry("NONE")).isEqualTo(LayoutLanguageBinding.UNBOUND)
        assertThat(LayoutLanguageBinding.normalizeEntry("De")).isEqualTo("de")
        assertWithMessage("an invalid code from a hand-edited backup falls back to 'no choice'")
            .that(LayoutLanguageBinding.normalizeEntry("klingon!")).isNull()
    }

    // ── entry precedence: user choice over XML default ─────────────────────────

    @Test
    fun userChoiceOverridesTheXmlDefault() {
        assertThat(LayoutLanguageBinding.effective(entry = "de", declared = "fa")).isEqualTo("de")
        assertWithMessage("'none' explicitly unbinds a layout whose XML declares a language")
            .that(LayoutLanguageBinding.effective(entry = "none", declared = "fa")).isNull()
        assertWithMessage("no user choice: the XML default applies")
            .that(LayoutLanguageBinding.effective(entry = null, declared = "FA")).isEqualTo("fa")
        assertWithMessage("an invalid XML value never binds")
            .that(LayoutLanguageBinding.effective(entry = null, declared = "persian")).isNull()
        assertThat(LayoutLanguageBinding.effective(entry = null, declared = null)).isNull()
    }

    @Test
    fun pickerChoicesMapToTheSmallestEntryValue() {
        // Choosing the XML default stores nothing, so the layout keeps following its XML.
        assertThat(LayoutLanguageBinding.entryValueFor(choice = "fa", declared = "fa")).isNull()
        assertThat(LayoutLanguageBinding.entryValueFor(choice = "de", declared = "fa")).isEqualTo("de")
        // "Follow Multi-Language settings" must override an XML default explicitly …
        assertThat(LayoutLanguageBinding.entryValueFor(choice = null, declared = "fa"))
            .isEqualTo(LayoutLanguageBinding.UNBOUND)
        // … and is simply "no value" when there is no XML default (byte-identical prefs).
        assertThat(LayoutLanguageBinding.entryValueFor(choice = null, declared = null)).isNull()
    }

    // ── resolution ────────────────────────────────────────────────────────────

    @Test
    fun anUnboundLayoutKeepsTodaysLanguagesExactly() {
        val active = LayoutLanguageBinding.resolve(
            bindings = listOf(null, null),
            currentIndex = 1,
            userPrimary = "en",
            multilangEnabled = true,
            userSecondary = "fa",
        )
        assertThat(active).isEqualTo(ActiveLanguages(primary = "en", secondary = "fa", boundLayoutLanguage = null))

        val monolingual = LayoutLanguageBinding.resolve(listOf(null), 0, "en", false, "fa")
        assertWithMessage("Multi-Language off: no secondary, exactly as before")
            .that(monolingual.secondary).isNull()
        assertThat(LayoutLanguageBinding.resolve(listOf(null), 0, "en", true, "none").secondary).isNull()
    }

    @Test
    fun aBoundLayoutMakesItsLanguageTheOnlyActiveLanguage() {
        // #186: EN primary + FA secondary; switch_forward to the Persian board bound to fa.
        val active = LayoutLanguageBinding.resolve(
            bindings = listOf(null, "fa"),
            currentIndex = 1,
            userPrimary = "en",
            multilangEnabled = true,
            userSecondary = "fa",
        )
        assertThat(active.primary).isEqualTo("fa")
        assertWithMessage("#61: a bound layout is single-language — no word mixing")
            .that(active.secondary).isNull()
        assertThat(active.boundLayoutLanguage).isEqualTo("fa")
    }

    @Test
    fun anOutOfRangeIndexResolvesLikeLayoutManagerDoes() {
        // LayoutManager.current_layout_unmodified falls back to layout 0 when the stored index
        // is past the end (e.g. a layout was deleted); the language must follow the SAME layout.
        val active = LayoutLanguageBinding.resolve(listOf("de", null), 7, "en", false, null)
        assertThat(active.primary).isEqualTo("de")
    }

    @Test
    fun threeBoundLayoutsCycleThroughThreeLanguages() {
        val bindings = listOf("en", "de", "fa")
        val seen = (0 until 4).map { step ->
            LayoutLanguageBinding.resolve(bindings, step % bindings.size, "en", true, "fr").primary
        }
        assertThat(seen).containsExactly("en", "de", "fa", "en").inOrder()
    }
}
