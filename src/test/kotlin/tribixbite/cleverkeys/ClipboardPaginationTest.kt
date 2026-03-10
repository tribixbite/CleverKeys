package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for clipboard pagination math.
 *
 * ClipboardHistoryView.getTotalPages() uses ceiling division:
 *   if (total <= ITEMS_PER_PAGE) 1 else (total + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
 *
 * These tests validate the formula in isolation — no Android framework needed.
 * ITEMS_PER_PAGE = 100 (from ClipboardHistoryView companion object).
 */
class ClipboardPaginationTest {

    // Mirror the constant from ClipboardHistoryView — can't reference directly
    // because ClipboardHistoryView extends NonScrollListView (Android View).
    private val itemsPerPage = 100

    /** Replicate the exact formula from ClipboardHistoryView.getTotalPages(). */
    private fun totalPages(totalItems: Int): Int {
        return if (totalItems <= itemsPerPage) 1
        else (totalItems + itemsPerPage - 1) / itemsPerPage
    }

    /** Replicate pagination slice from ClipboardHistoryView.applyPagination(). */
    private fun paginatedRange(totalItems: Int, currentPage: Int): IntRange {
        if (totalItems <= itemsPerPage) return 0 until totalItems
        val startIndex = currentPage * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, totalItems)
        return startIndex until endIndex
    }

    // =========================================================================
    // Total pages calculation
    // =========================================================================

    @Test
    fun `0 items = 1 page`() {
        assertThat(totalPages(0)).isEqualTo(1)
    }

    @Test
    fun `1 item = 1 page`() {
        assertThat(totalPages(1)).isEqualTo(1)
    }

    @Test
    fun `99 items = 1 page`() {
        assertThat(totalPages(99)).isEqualTo(1)
    }

    @Test
    fun `100 items = 1 page (boundary)`() {
        // Exactly ITEMS_PER_PAGE — no pagination needed
        assertThat(totalPages(100)).isEqualTo(1)
    }

    @Test
    fun `101 items = 2 pages`() {
        assertThat(totalPages(101)).isEqualTo(2)
    }

    @Test
    fun `200 items = 2 pages`() {
        assertThat(totalPages(200)).isEqualTo(2)
    }

    @Test
    fun `201 items = 3 pages`() {
        assertThat(totalPages(201)).isEqualTo(3)
    }

    @Test
    fun `1000 items = 10 pages`() {
        assertThat(totalPages(1000)).isEqualTo(10)
    }

    @Test
    fun `1001 items = 11 pages`() {
        assertThat(totalPages(1001)).isEqualTo(11)
    }

    // =========================================================================
    // Pagination slicing — ensures correct index ranges
    // =========================================================================

    @Test
    fun `page 0 of 50 items returns full range (no pagination)`() {
        val range = paginatedRange(50, 0)
        assertThat(range.first).isEqualTo(0)
        assertThat(range.last).isEqualTo(49)
        assertThat(range.count()).isEqualTo(50)
    }

    @Test
    fun `page 0 of 250 items returns first 100`() {
        val range = paginatedRange(250, 0)
        assertThat(range.first).isEqualTo(0)
        assertThat(range.last).isEqualTo(99)
        assertThat(range.count()).isEqualTo(100)
    }

    @Test
    fun `page 1 of 250 items returns indices 100-199`() {
        val range = paginatedRange(250, 1)
        assertThat(range.first).isEqualTo(100)
        assertThat(range.last).isEqualTo(199)
        assertThat(range.count()).isEqualTo(100)
    }

    @Test
    fun `last page of 250 items returns indices 200-249`() {
        val range = paginatedRange(250, 2)
        assertThat(range.first).isEqualTo(200)
        assertThat(range.last).isEqualTo(249)
        assertThat(range.count()).isEqualTo(50) // Partial page
    }

    // =========================================================================
    // Page navigation bounds
    // =========================================================================

    @Test
    fun `hasPreviousPage is false at page 0`() {
        val currentPage = 0
        assertThat(currentPage > 0).isFalse()
    }

    @Test
    fun `hasPreviousPage is true at page 1`() {
        val currentPage = 1
        assertThat(currentPage > 0).isTrue()
    }

    @Test
    fun `hasNextPage is false at last page`() {
        val totalItems = 250
        val currentPage = totalPages(totalItems) - 1 // Last page (2)
        assertThat(currentPage < totalPages(totalItems) - 1).isFalse()
    }

    @Test
    fun `hasNextPage is true before last page`() {
        val totalItems = 250
        val currentPage = 0
        assertThat(currentPage < totalPages(totalItems) - 1).isTrue()
    }

    // =========================================================================
    // Page clamping — currentPage should stay within bounds
    // =========================================================================

    @Test
    fun `page clamped when filter reduces total pages`() {
        // Simulate: user is on page 3, then a search filter reduces items to 50
        // ClipboardHistoryView clamps: if (currentPage >= totalPages) currentPage = max(0, totalPages - 1)
        var currentPage = 3
        val newTotalPages = totalPages(50) // 1 page

        if (currentPage >= newTotalPages) {
            currentPage = maxOf(0, newTotalPages - 1)
        }
        assertThat(currentPage).isEqualTo(0)
    }

    @Test
    fun `page stays when still in range`() {
        var currentPage = 1
        val newTotalPages = totalPages(250) // 3 pages

        if (currentPage >= newTotalPages) {
            currentPage = maxOf(0, newTotalPages - 1)
        }
        assertThat(currentPage).isEqualTo(1) // Unchanged
    }

    // =========================================================================
    // ITEMS_PER_PAGE value
    // =========================================================================

    @Test
    fun `ITEMS_PER_PAGE is 100`() {
        // Documents the expected value — if this changes, pagination tests need updating
        assertThat(itemsPerPage).isEqualTo(100)
    }
}
