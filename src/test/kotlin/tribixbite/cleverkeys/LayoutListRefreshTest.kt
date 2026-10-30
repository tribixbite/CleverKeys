package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Layout Manager re-reads the stored layout list when it changes elsewhere (Saga 2026-10-30: left
 * open behind the keyboard, it kept showing the old list after the subkey popover created a
 * "(modified)" copy). [LayoutListRefresh.reconcile] keeps each unchanged row's id, so a reload
 * neither restarts drag state nor recomposes rows that did not change.
 */
class LayoutListRefreshTest {

    private val qwerty = LayoutsPreference.NamedLayout("latn_qwerty_us")
    private val system = LayoutsPreference.SystemLayout()
    private fun copy(xml: String) = LayoutsPreference.CustomLayout(xml, parsed = null, origin = "latn_qwerty_us")

    @Test
    fun aCopyAddedElsewhereAppears() {
        val shown = listOf(LayoutWithId("a", system), LayoutWithId("b", qwerty))
        val stored = listOf(system, copy("<keyboard/>"))
        val result = LayoutListRefresh.reconcile(shown, stored)
        assertThat(result.map { it.layout }).containsExactlyElementsIn(stored).inOrder()
        // The unchanged row keeps its id; the replaced row is a new row.
        assertThat(result[0].id).isEqualTo("a")
        assertThat(result[1].id).isNotEqualTo("b")
    }

    @Test
    fun anUnchangedListIsReturnedAsIs() {
        val shown = listOf(LayoutWithId("a", system), LayoutWithId("b", copy("<keyboard/>")))
        // A fresh read parses a new KeyboardData each time; equal content must still match.
        val stored = listOf(system, copy("<keyboard/>"))
        assertThat(LayoutListRefresh.reconcile(shown, stored)).isSameInstanceAs(shown)
    }

    @Test
    fun reorderedAndDuplicatedRowsKeepTheirIds() {
        val shown = listOf(LayoutWithId("a", qwerty), LayoutWithId("b", system), LayoutWithId("c", qwerty))
        val stored = listOf(system, qwerty, qwerty)
        val result = LayoutListRefresh.reconcile(shown, stored)
        assertThat(result.map { it.id }).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun aRemovedRowDisappears() {
        val shown = listOf(LayoutWithId("a", system), LayoutWithId("b", qwerty))
        val result = LayoutListRefresh.reconcile(shown, listOf(qwerty))
        assertThat(result.map { it.id }).containsExactly("b")
    }
}
