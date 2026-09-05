package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * #96 — "Dictionary search resets after adjusting activity."
 *
 * The toggle-a-word path was fixed long ago (`WordListFragment.refresh()` re-applies
 * `filter(currentSearchQuery, currentSortType)`), but the RECREATION path was not, and it
 * loses the search context through three independent holes:
 *
 *  1. **The fragment's search context dies with the fragment.** `currentSearchQuery` and
 *     `currentSortType` are plain instance fields; `WordListFragment` overrides no
 *     `onSaveInstanceState`. After a rotation / split-screen resize, FragmentStateAdapter
 *     restores the fragments with the defaults ("" / FREQ).
 *  2. **The initial load is unconditionally unfiltered AND untracked.** `onViewCreated`
 *     calls `loadWords()`, which launches its own coroutine (`dataSource.getAllWords()` — a
 *     cold 50k-word parse after recreation, because the activity's `onDestroy` invalidated
 *     the shared cache) and populates the adapter OUTSIDE the `searchJob` a later `filter()`
 *     dispatch cancels. The activity re-dispatches the restored query on a 400 ms timer, so
 *     whichever coroutine finishes last wins: when the slow unfiltered load lands after the
 *     filtered one, the list shows every word frequency-sorted while the search box still
 *     shows the query — exactly the reported symptom.
 *  3. **Scroll position is never restored.** The RecyclerView's layout manager state cannot
 *     auto-restore (the list is empty at view-state-restore time; content arrives async),
 *     and nothing re-applies it once data lands.
 *
 * A Fragment cannot be exercised on the JVM (lifecycle + views need instrumentation), so —
 * following the `TestKeyboardSectionTest` idiom — the wiring that closes each hole is pinned
 * by scanning the source: those are exactly the edits that would reintroduce the bug.
 * The sort/filter behaviour itself runs for real in `DictionarySortOrderTest`.
 *
 * Pure tier: `scripts/gradle-guard.sh runPureTests -PtestClass=DictionarySearchStatePersistenceTest`.
 */
class DictionarySearchStatePersistenceTest {

    private fun read(path: String): String {
        val file = File(path)
        check(file.isFile) { "${file.path} not found — run with the project root as CWD." }
        return file.readText()
    }

    private val fragmentSource by lazy {
        read("src/main/kotlin/tribixbite/cleverkeys/WordListFragment.kt")
    }

    private val activitySource by lazy {
        read("src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt")
    }

    /** Body of the first `fun <name>(` in [source], extracted by brace counting. */
    private fun methodBody(source: String, header: String): String {
        val start = source.indexOf(header)
        check(start >= 0) { "'$header' not found" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "no body after '$header'" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after '$header'")
    }

    // =========================================================================
    // Hole 1: the search context must survive fragment destruction
    // =========================================================================

    @Test
    fun fragmentSavesSearchContextOnDestruction() {
        val body = methodBody(fragmentSource, "override fun onSaveInstanceState")
        assertWithMessage(
            "WordListFragment.onSaveInstanceState must persist the active search query — " +
                "without it a rotation resets every tab to an unfiltered list (#96)"
        ).that(body).contains("currentSearchQuery")
        assertWithMessage(
            "WordListFragment.onSaveInstanceState must persist the active sort as its ordinal"
        ).that(body).contains("currentSortType.ordinal")
    }

    @Test
    fun fragmentRestoresSearchContextBeforeTheFirstLoad() {
        val body = methodBody(fragmentSource, "override fun onViewCreated")
        assertWithMessage(
            "onViewCreated must read the saved search query back BEFORE kicking off the " +
                "initial load, so the first list the restored fragment shows is already filtered"
        ).that(body).contains("savedInstanceState")

        val restoreIdx = body.indexOf("currentSearchQuery")
        val loadIdx = body.indexOf("loadWords()")
        assertWithMessage("onViewCreated must restore currentSearchQuery").that(restoreIdx).isAtLeast(0)
        assertWithMessage("onViewCreated must still trigger the initial load").that(loadIdx).isAtLeast(0)
        assertWithMessage(
            "the restore must happen before the initial load is dispatched, not after"
        ).that(restoreIdx).isLessThan(loadIdx)
    }

    // =========================================================================
    // Hole 2: the initial load must apply the (restored) filter and must be
    // cancellable by a later dispatch — no untracked unfiltered coroutine may
    // exist that can land last and clobber a filtered result
    // =========================================================================

    @Test
    fun initialLoadReappliesTheFilterAndCannotClobberIt() {
        val body = methodBody(fragmentSource, "private fun loadWords()")
        assertWithMessage(
            "loadWords() must route through filter(currentSearchQuery, currentSortType) so the " +
                "restored context is applied and the load is tracked by searchJob (cancellable)"
        ).that(body).contains("filter(currentSearchQuery, currentSortType)")
        assertWithMessage(
            "loadWords() must NOT population-race the filter with its own untracked " +
                "dataSource.getAllWords() coroutine — that unfiltered load landing last IS #96"
        ).that(body).doesNotContain("getAllWords")
        assertWithMessage(
            "loadWords() must not launch its own coroutine outside searchJob"
        ).that(body).doesNotContain("lifecycleScope.launch")
    }

    // =========================================================================
    // Hole 3: scroll position must be saved and re-applied once data lands
    // =========================================================================

    @Test
    fun scrollPositionIsSavedAndRestoredAfterDataLands() {
        val saveBody = methodBody(fragmentSource, "override fun onSaveInstanceState")
        assertWithMessage(
            "onSaveInstanceState must capture the layout manager state (scroll position)"
        ).that(saveBody).contains("onSaveInstanceState()")

        // The restore cannot happen at view-state-restore time (the list is still empty);
        // it must be re-applied after setWords() delivers the restored content.
        val filterBody = methodBody(fragmentSource, "fun filter(")
        assertWithMessage(
            "filter() must re-apply the saved layout manager state once the restored list is set"
        ).that(filterBody).contains("onRestoreInstanceState")
    }

    // =========================================================================
    // Companion guards (already true at introduction — pinned so the other half
    // of the mechanism cannot regress independently)
    // =========================================================================

    @Test
    fun activitySavesAndRestoresItsOwnSearchState() {
        val saveBody = methodBody(activitySource, "override fun onSaveInstanceState")
        assertWithMessage("the activity must keep persisting the toolbar search query")
            .that(saveBody).contains("currentSearchQuery")
        assertWithMessage("the activity must keep persisting the sort spinner selection")
            .that(saveBody).contains("currentSort.ordinal")
    }

    @Test
    fun toggleAndDeletePreserveTheActiveFilter() {
        for (header in listOf("private fun toggleWord(", "private fun deleteWord(")) {
            val body = methodBody(fragmentSource, header)
            assertWithMessage(
                "$header must re-apply the CURRENT search context after mutating, " +
                    "never reset to an unfiltered list (#96's original repro)"
            ).that(body).contains("filter(currentSearchQuery, currentSortType)")
        }
    }
}
