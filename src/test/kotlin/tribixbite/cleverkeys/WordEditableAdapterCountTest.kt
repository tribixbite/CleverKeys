package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * The Custom tab label counts WORDS, never the "+ Add word" row (fresh-install Saga,
 * 2026-10-11: an empty Custom tab read "(1)", and the Seeker's 11 words read "(12)").
 *
 * `getFilteredCount()` feeds the tab label (DictionaryManagerActivity.updateTabCounts);
 * `getItemCount()` is the RecyclerView row count, which keeps the Add row so the list
 * stays visible (and the Add button reachable) when there are no custom words yet.
 *
 * Mock runner: RecyclerView.Adapter's constructor builds an android.database.Observable,
 * a Stub! in android.jar, so the adapters are created with Objenesis (constructor skipped)
 * and their word list is injected into BaseWordAdapter's backing field — the same state
 * `setWords` leaves behind, minus the notifyDataSetChanged that needs the observable.
 */
class WordEditableAdapterCountTest {

    private fun <T : BaseWordAdapter> withWords(cls: Class<T>, words: List<DictionaryWord>): T {
        val adapter = ObjenesisStd().newInstance(cls)
        BaseWordAdapter::class.java.getDeclaredField("currentList").apply {
            isAccessible = true
            set(adapter, words)
        }
        return adapter
    }

    private fun word(w: String) = DictionaryWord(w, 255, WordSource.CUSTOM)

    @Test
    fun anEmptyCustomTabCountsZeroWordsButStillShowsTheAddRow() {
        val a = withWords(WordEditableAdapter::class.java, emptyList())
        assertThat(a.getFilteredCount()).isEqualTo(0)
        assertThat(a.itemCount).isEqualTo(1)
    }

    @Test
    fun theTabCountIsTheNumberOfWords() {
        val a = withWords(WordEditableAdapter::class.java, listOf(word("adb"), word("bambu"), word("ghostty")))
        assertThat(a.getFilteredCount()).isEqualTo(3)
        assertThat(a.itemCount).isEqualTo(4)
    }

    @Test
    fun toggleTabsCountTheirRowsAsBefore() {
        val a = withWords(WordToggleAdapter::class.java, listOf(word("adb"), word("bambu")))
        assertThat(a.getFilteredCount()).isEqualTo(2)
        assertThat(a.itemCount).isEqualTo(2)
    }
}
