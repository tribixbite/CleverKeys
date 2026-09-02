package tribixbite.cleverkeys

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ARC-110: tab counts in [DictionaryManagerActivity] must survive activity recreation.
 *
 * Wave-K device evidence (docs/eval/2026-09-02-wave-k-device-verification.md): after a
 * uimode flip recreated the activity, every tab showed "(0)" while the word list itself
 * stayed populated (ACTIVE was 97960 before). Root cause: FragmentStateAdapter restores
 * the previously-attached WordListFragments from the FragmentManager (createFragment is
 * never called for them), while onCreate's setupViewPager() rebuilds the activity's
 * `fragments` list with fresh instances that are never attached — so updateTabCounts()
 * read `getFilteredCount()` from orphans whose `adapter` lateinit was uninitialized,
 * which returns 0 forever. The fix resolves the LIVE fragment by its FragmentStateAdapter
 * tag ("f" + position), so counts derive from the same loaded data the list renders.
 *
 * Before the fix this test fails: after recreate() the counts are stomped to 0 by the
 * orphan reads and never recover, so the post-recreation poll times out.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryManagerRecreationTest {

    /** Parse the count out of a tab label like `"Active\n(97960)"`; null when absent. */
    private fun parseCount(text: CharSequence?): Int? =
        text?.let { Regex("""\((\d+)\)""").find(it)?.groupValues?.get(1)?.toInt() }

    private fun tabCount(scenario: ActivityScenario<DictionaryManagerActivity>, position: Int): Int? {
        var count: Int? = null
        scenario.onActivity { activity ->
            val tabs = activity.findViewById<TabLayout>(R.id.tab_layout)
            count = parseCount(tabs.getTabAt(position)?.text)
        }
        return count
    }

    /**
     * Poll until the tab at [position] shows a nonzero count. The ACTIVE tab is backed by
     * the bundled main dictionary (tens of thousands of words), so a persistent 0 there is
     * always the ARC-110 failure mode, never real data. Loading 50k+ rows takes a while on
     * slow emulators — hence the generous timeout.
     */
    private fun awaitNonzeroCount(
        scenario: ActivityScenario<DictionaryManagerActivity>,
        position: Int,
        phase: String,
        timeoutMs: Long = 60_000L,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Int? = null
        while (System.currentTimeMillis() < deadline) {
            last = tabCount(scenario, position)
            if ((last ?: 0) > 0) return last!!
            Thread.sleep(250)
        }
        fail("tab $position never showed a nonzero count $phase (last seen: $last)")
        throw AssertionError("unreachable")
    }

    @Test
    fun tabCounts_surviveRecreation() {
        val scenario = ActivityScenario.launch(DictionaryManagerActivity::class.java)
        try {
            // Tab 0 is ACTIVE (main dictionary) in every tab configuration.
            val before = awaitNonzeroCount(scenario, 0, "on first create")

            scenario.recreate()

            val after = awaitNonzeroCount(scenario, 0, "after recreate()")
            assertEquals(
                "ACTIVE tab count must derive from the same reloaded data after recreation",
                before,
                after
            )
        } finally {
            scenario.close()
        }
    }
}
