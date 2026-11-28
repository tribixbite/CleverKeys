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

    /**
     * Pre-tag review 2026-11-25 (S1): a newest clipping larger than the whole limit made the
     * running total exceed the budget at the FIRST row, and every History row went, the clipping
     * just copied included. The newest always stays, as under the count limit (whose minimum is
     * one row); the older rows go because nothing else fits beside it.
     */
    @Test
    fun aNewestClippingLargerThanTheLimitStaysAndTheOlderRowsGo() {
        val rows = listOf(9L to 3 * mb, 8L to 10L, 7L to 10L)
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(rows, mb)).containsExactly(8L, 7L).inOrder()
    }

    @Test
    fun aLoneClippingLargerThanTheLimitStays() {
        assertThat(ClipboardSizePolicy.idsOverSizeBudget(listOf(1L to 2 * mb), mb)).isEmpty()
    }

    // The database's walk order (newest first, through this policy) is pinned behaviourally by
    // clipboard.ClipboardSizeLimitWalkTest in the mock tier; it replaced a source-text check here.

    // ─── Stored-row limits (Fable audit H3, 2026-11-28) ─────────────────────────────────────
    // A row over Android's 2 MiB CursorWindow cannot be read: the History reader's catch
    // returned the rows read so far, so one such row emptied History (or cut it there) and a
    // full backup silently left the clipboard out. Only an import could store one: capture,
    // edit and merge all stop at the per-clipping cap.

    @Test
    fun theImportCapIsTheLargestCaptureCap() {
        assertThat(ClipboardSizePolicy.MAX_STORED_ITEM_BYTES)
            .isEqualTo(SettingsRanges.CLIPBOARD_MAX_ITEM_SIZE_KB.last * 1024L)
    }

    @Test
    fun everyRowAnImportCanStoreFitsTheReadBudgetAndTheCursorWindow() {
        // Content and tags at their caps plus the largest thumbnail we write must be readable.
        val largestRow = ClipboardSizePolicy.MAX_STORED_ITEM_BYTES +
            ClipboardSizePolicy.MAX_STORED_TAGS_BYTES + ClipboardMediaManager.MAX_THUMBNAIL_BYTES
        assertThat(largestRow).isAtMost(ClipboardSizePolicy.MAX_READ_ROW_BYTES)
        // And the read budget leaves room in the 2 MiB window for the row's other columns.
        assertThat(ClipboardSizePolicy.MAX_READ_ROW_BYTES).isLessThan(2L * mb - 256L * 1024)
    }

    @Test
    fun anImportedEntryOverTheCapIsRefusedInUtf8Bytes() {
        val cap = ClipboardSizePolicy.MAX_STORED_ITEM_BYTES.toInt()
        assertThat(ClipboardSizePolicy.importTooLarge("x".repeat(cap), "[]")).isFalse()
        assertThat(ClipboardSizePolicy.importTooLarge("x".repeat(cap + 1), "[]")).isTrue()
        // Counted in UTF-8 bytes, not chars: 2/3 of the cap in 3-byte chars is over it.
        assertThat(ClipboardSizePolicy.importTooLarge("€".repeat(cap * 2 / 3), null)).isTrue()
    }

    @Test
    fun anImportedEntryWithOversizedTagsIsRefused() {
        val tagCap = ClipboardSizePolicy.MAX_STORED_TAGS_BYTES.toInt()
        assertThat(ClipboardSizePolicy.importTooLarge("ok", "x".repeat(tagCap))).isFalse()
        assertThat(ClipboardSizePolicy.importTooLarge("ok", "x".repeat(tagCap + 1))).isTrue()
    }
}
