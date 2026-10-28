package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.DictionaryTabPlan.Kind.ACTIVE
import tribixbite.cleverkeys.DictionaryTabPlan.Kind.CUSTOM
import tribixbite.cleverkeys.DictionaryTabPlan.Kind.DISABLED
import tribixbite.cleverkeys.DictionaryTabPlan.Kind.USER
import tribixbite.cleverkeys.DictionaryTabPlan.Tab

/**
 * Dictionary Manager's tabs (code-quality audit 2026-10-20, todo 13c): the user's languages as
 * before, and — new — Active / Disabled / Custom tabs for every language a layout is BOUND to
 * (GH #186/#61, `layout-system.md` "Language binding"), the way a secondary language appears.
 * Before, a layout bound to `fa` got no tab, so its personal dictionary was reachable only by
 * choosing Persian in Multi-Language; an installed pack showed only its Custom tab.
 */
class DictionaryTabPlanTest {

    private fun lang(kind: DictionaryTabPlan.Kind, code: String) = Tab(kind, code, languageInTitle = true)

    @Test
    fun singleLanguageEnglishKeepsItsFourPlainTabs() {
        assertThat(DictionaryTabPlan.plan("en", null, emptyList(), emptyList())).containsExactly(
            Tab(ACTIVE, "en", languageInTitle = false), Tab(DISABLED, "en", languageInTitle = false),
            Tab(USER, null, languageInTitle = false), Tab(CUSTOM, "en", languageInTitle = false),
        ).inOrder()
    }

    @Test
    fun singleLanguageOtherThanEnglishNamesTheLanguage() {
        assertThat(DictionaryTabPlan.plan("de", null, emptyList(), emptyList())).containsExactly(
            lang(ACTIVE, "de"), lang(DISABLED, "de"), Tab(USER, null, languageInTitle = false), lang(CUSTOM, "de"),
        ).inOrder()
    }

    @Test
    fun multiLanguageShowsBothLanguagesAroundTheUserDictionary() {
        assertThat(DictionaryTabPlan.plan("en", "es", emptyList(), emptyList())).containsExactly(
            lang(ACTIVE, "en"), lang(DISABLED, "en"), lang(CUSTOM, "en"),
            Tab(USER, null, languageInTitle = false),
            lang(ACTIVE, "es"), lang(DISABLED, "es"), lang(CUSTOM, "es"),
        ).inOrder()
    }

    @Test
    fun anInstalledPackNotOtherwiseShownKeepsItsCustomTab() {
        assertThat(DictionaryTabPlan.plan("en", null, emptyList(), listOf("en", "ru")).last())
            .isEqualTo(lang(CUSTOM, "ru"))
        assertThat(DictionaryTabPlan.plan("en", null, emptyList(), listOf("en"))).hasSize(4)
    }

    @Test
    fun aLayoutBoundToALanguageGetsThatLanguagesTabs() {
        // Layouts: QWERTY (unbound), Persian PC (bound to fa), with the fa pack installed.
        val tabs = DictionaryTabPlan.plan("en", null, listOf(null, "fa"), listOf("fa"))
        assertThat(tabs).containsExactly(
            Tab(ACTIVE, "en", languageInTitle = false), Tab(DISABLED, "en", languageInTitle = false),
            Tab(USER, null, languageInTitle = false), Tab(CUSTOM, "en", languageInTitle = false),
            lang(ACTIVE, "fa"), lang(DISABLED, "fa"), lang(CUSTOM, "fa"),
        ).inOrder()
    }

    @Test
    fun aBoundLanguageAlreadyShownOrBoundTwiceIsListedOnce() {
        // Two layouts bound to de, one to the user's own secondary es.
        val tabs = DictionaryTabPlan.plan("en", "es", listOf("de", "es", "de", null), emptyList())
        assertThat(tabs.filter { it.language == "de" }).containsExactly(
            lang(ACTIVE, "de"), lang(DISABLED, "de"), lang(CUSTOM, "de"),
        ).inOrder()
        assertThat(tabs.count { it.language == "es" }).isEqualTo(3)
    }

    @Test
    fun aBoundLanguageWithoutAPackStillGetsItsTabs() {
        // The tabs are where the user's custom / disabled words for that language live; a
        // missing pack only means the Active list is empty (the "no dictionary" warning case).
        val tabs = DictionaryTabPlan.plan("en", null, listOf("fa"), emptyList())
        assertThat(tabs.filter { it.language == "fa" }.map { it.kind }).containsExactly(ACTIVE, DISABLED, CUSTOM).inOrder()
    }

    @Test
    fun boundLanguagesListsEachBindingOnceInLayoutOrder() {
        assertThat(DictionaryTabPlan.boundLanguages(listOf(null, "fa", "de", "fa"))).containsExactly("fa", "de").inOrder()
    }
}
