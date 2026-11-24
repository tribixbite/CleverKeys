package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [ClipboardSizePolicy.idsOverSizeBudget]: which History rows the total-size limit
 * (`clipboard_limit_type` = size) removes. The newest clippings stay while they fit; the oldest go.
 *
 * Pixel 2026-11-24: with a 5 MB limit, a restore of older backups made the history 15 MB, and the
 * next prune removed 159 of the phone's NEWEST clippings (the one on top included) and kept the
 * oldest. `ClipboardDatabase.applySizeLimitBytes` walked the rows oldest-first and deleted every
 * row after the budget was spent, so once the history was full each new copy was deleted as soon
 * as it was stored. The count limit always removed the oldest.
 */
class ClipboardSizeBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun theNewestClippingsStayAndTheOldestGo() {
        // Newest first: ids 5 (newest) … 1 (oldest), 400 KB each, under a 1 MB limit.
        val rows = (5L downTo 1L).map { it to 400L * 1024 }
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(rows, mb)).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun theClippingJustCopiedSurvivesAFullHistory() {
        // The history already fills the limit; a small new copy is the newest row.
        val rows = listOf(99L to 20L) + (10L downTo 1L).map { it to 110L * 1024 }
        val removed = ClipboardSizePolicy.idsOverSizeBudget(rows, mb)
        assertThat(removed).doesNotContain(99L)
        assertThat(removed).contains(1L)
    }

    @Test
    fun anOlderSmallClippingDoesNotJumpTheQueue() {
        // The second-newest does not fit; the oldest would, but recency decides, not size.
        val rows = listOf(3L to 900L * 1024, 2L to 300L * 1024, 1L to 10L)
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(rows, mb)).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun aHistoryWithinTheLimitLosesNothing() {
        val rows = (4L downTo 1L).map { it to 200L * 1024 }
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(rows, mb)).isEmpty()
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(emptyList(), mb)).isEmpty()
    }

    @Test
    fun theDatabaseWalksTheRowsNewestFirst() {
        val src = java.io.File("src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardDatabase.kt").readText()
        val body = src.substring(src.indexOf("fun applySizeLimitBytes("), src.indexOf("fun isMediaPathReferenced("))
        assertThat(body).contains("ORDER BY \$COLUMN_TIMESTAMP DESC")
        assertThat(body).doesNotContain("ORDER BY \$COLUMN_TIMESTAMP ASC")
        assertThat(body).contains("ClipboardSizePolicy.idsOverSizeBudget(")
    }
}
