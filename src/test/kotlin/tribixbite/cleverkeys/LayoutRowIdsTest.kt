package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Review 2026-11-14 M22 + the stale-index low: Layout Manager's rows get a random id each time the
 * screen is built, and its dialogs held a list index. A rotation therefore lost every open dialog,
 * and a list changed under an open dialog (the keyboard creating a "(modified)" copy) made Delete
 * remove a different row. The dialogs now hold a row id ([LayoutRowIds.indexOf] resolves it when
 * they act) and the ids survive recreation ([LayoutRowIds.restore]).
 */
class LayoutRowIdsTest {

    private val qwerty = LayoutsPreference.NamedLayout("latn_qwerty_us")
    private val azerty = LayoutsPreference.NamedLayout("latn_azerty_fr")
    private val system = LayoutsPreference.SystemLayout()

    @Test
    fun savedIdsArePairedWithTheStoredRowsInOrder() {
        val rows = LayoutRowIds.restore(listOf(system, qwerty, azerty), listOf("a", "b", "c"))
        assertThat(rows.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(rows.map { it.layout }).containsExactly(system, qwerty, azerty).inOrder()
    }

    /** The list changed while the activity was gone: the old ids describe other rows. */
    @Test
    fun aDifferentRowCount_getsFreshIds() {
        val rows = LayoutRowIds.restore(listOf(system, qwerty), listOf("a", "b", "c"))
        assertThat(rows.map { it.layout }).containsExactly(system, qwerty).inOrder()
        assertThat(rows.map { it.id }).containsNoneOf("a", "b", "c")
        assertThat(rows.map { it.id }.toSet()).hasSize(2)
    }

    @Test
    fun noSavedIds_getsFreshUniqueIds() {
        val rows = LayoutRowIds.restore(listOf(system, qwerty, azerty), null)
        assertThat(rows.map { it.id }.toSet()).hasSize(3)
    }

    /** Duplicated saved ids would give two rows one LazyColumn key (a crash): never reuse them. */
    @Test
    fun duplicateSavedIds_areNotReused() {
        val rows = LayoutRowIds.restore(listOf(system, qwerty), listOf("a", "a"))
        assertThat(rows.map { it.id }.toSet()).hasSize(2)
    }

    @Test
    fun anIdResolvesToItsCurrentIndex_evenAfterTheListChanged() {
        val before = listOf(LayoutWithId("a", system), LayoutWithId("b", qwerty), LayoutWithId("c", azerty))
        assertThat(LayoutRowIds.indexOf(before, "c")).isEqualTo(2)
        // A row was inserted above while a dialog for "c" was open: index 2 is now "b".
        val after = listOf(LayoutWithId("a", system), LayoutWithId("x", azerty), LayoutWithId("b", qwerty), LayoutWithId("c", azerty))
        assertThat(LayoutRowIds.indexOf(after, "c")).isEqualTo(3)
    }

    @Test
    fun aRowThatIsGone_resolvesToNull() {
        val rows = listOf(LayoutWithId("a", system))
        assertThat(LayoutRowIds.indexOf(rows, "b")).isNull()
        assertThat(LayoutRowIds.indexOf(rows, null)).isNull()
    }
}
