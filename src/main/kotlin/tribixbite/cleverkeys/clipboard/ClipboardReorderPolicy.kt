package tribixbite.cleverkeys.clipboard

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reordering Pinned and To-dos rows (maintainer request 2026-10-27). Pure (no Android types):
 * the list view ([tribixbite.cleverkeys.ClipboardHistoryView]) supplies the visible rows and
 * the drag geometry, and the database ([tribixbite.cleverkeys.ClipboardDatabase.moveEntry])
 * applies the [Plan] inside one transaction.
 *
 * Model: `pinned_entries` and `todo_entries` each carry a REAL `position`, shown in ascending
 * order (ties by row id). A move names the moved row and the two VISIBLE rows it is dropped
 * between; the moved row gets the midpoint of their positions — one UPDATE. An open end (top
 * or bottom of the list) goes past the extreme of the whole table, so "Move to top" puts the
 * row above every row, including ones the status or text-only filter hides.
 *
 * Renumbering: halving a gap runs out of useful precision eventually, so when the gap between
 * the two neighbours is at most [MIN_GAP] (or a position is not finite, or two rows share a
 * position after a merged import) the whole table is rewritten as 1.0, 2.0, … in its new order,
 * once. With gaps of 1.0, 2^-20 < [MIN_GAP] means about 20 consecutive drops into the same gap
 * before that happens; doubles near the positions in use resolve far below [MIN_GAP], so a
 * midpoint above the threshold is always strictly between its neighbours.
 *
 * Status groups: a to-do keeps its status when it moves (only `position` is written). The list
 * is one order in which active, planned and completed rows interleave; the status filter only
 * hides rows. Rows hidden between the two neighbours keep their own relative order, so moving
 * among the active rows (the default view) never reshuffles planned or completed ones.
 */
object ClipboardReorderPolicy {

    /** A neighbour gap at or below this renumbers the table instead of halving it again. */
    const val MIN_GAP = 1e-6

    /** Distance past the table's first or last position for a move to an open end. */
    const val END_STEP = 1.0

    /** One table row in display order: its row id and stored position. */
    data class Row(val id: Long, val position: Double)

    /** What a move writes. */
    sealed interface Plan {
        /** Write [position] to row [id]; nothing else changes. */
        data class Place(val id: Long, val position: Double) : Plan

        /** Rewrite every row's position: [rows] in the new order, numbered 1.0, 2.0, … */
        data class Renumber(val rows: List<Row>) : Plan

        /** The row already sits between its neighbours. */
        data object Unchanged : Plan

        /** A named row is gone or the neighbours are not in order: the view was stale. */
        data object Refused : Plan
    }

    /**
     * Plan moving row [movedId] of [rows] (the whole table in display order) so it sits after
     * [afterId] and before [beforeId]; null names an open end. Both null is refused.
     */
    fun plan(rows: List<Row>, movedId: Long, afterId: Long?, beforeId: Long?): Plan {
        if (afterId == null && beforeId == null) return Plan.Refused
        if (afterId == movedId || beforeId == movedId || (afterId != null && afterId == beforeId)) return Plan.Refused
        val movedIndex = rows.indexOfFirst { it.id == movedId }
        if (movedIndex < 0) return Plan.Refused
        val others = rows.filterIndexed { i, _ -> i != movedIndex }
        val afterIndex = afterId?.let { id -> others.indexOfFirst { it.id == id }.also { if (it < 0) return Plan.Refused } }
        val beforeIndex = beforeId?.let { id -> others.indexOfFirst { it.id == id }.also { if (it < 0) return Plan.Refused } }
        if (afterIndex != null && beforeIndex != null && afterIndex >= beforeIndex) return Plan.Refused

        // Already in place? In `others` the moved row would be re-inserted at movedIndex.
        val alreadyPlaced = when {
            afterIndex == null -> movedIndex == 0
            beforeIndex == null -> movedIndex == others.size
            else -> movedIndex in (afterIndex + 1)..beforeIndex
        }
        if (alreadyPlaced) return Plan.Unchanged

        // Where the moved row goes in `others` if the table must be renumbered.
        val insertAt = when {
            afterIndex == null -> 0
            beforeIndex == null -> others.size
            else -> afterIndex + 1
        }
        if (rows.any { !it.position.isFinite() }) return renumber(others, rows[movedIndex], insertAt)

        val position = when {
            afterIndex == null -> others.minOf { it.position } - END_STEP
            beforeIndex == null -> others.maxOf { it.position } + END_STEP
            else -> {
                val low = others[afterIndex].position
                val high = others[beforeIndex].position
                if (high - low <= MIN_GAP) return renumber(others, rows[movedIndex], insertAt)
                low + (high - low) / 2
            }
        }
        return Plan.Place(movedId, position)
    }

    private fun renumber(others: List<Row>, moved: Row, insertAt: Int): Plan.Renumber {
        val order = others.toMutableList().apply { add(insertAt, moved) }
        return Plan.Renumber(order.mapIndexed { i, row -> Row(row.id, (i + 1).toDouble()) })
    }

    /** [rows] after [plan], in display order (position, then id). Used by tests and the view. */
    fun applied(rows: List<Row>, plan: Plan): List<Row> = when (plan) {
        is Plan.Place -> rows.map { if (it.id == plan.id) Row(it.id, plan.position) else it }
            .sortedWith(compareBy<Row> { it.position }.thenBy { it.id })
        is Plan.Renumber -> plan.rows
        Plan.Unchanged, Plan.Refused -> rows
    }

    /**
     * The visible neighbours (after, before) for moving [movedId] to [targetIndex] of the
     * [visibleIds] list (its index once moved; clamped to the list). Null when nothing moves:
     * the row is missing, alone, or already at that index.
     */
    fun neighbours(visibleIds: List<Long>, movedId: Long, targetIndex: Int): Pair<Long?, Long?>? {
        val current = visibleIds.indexOf(movedId)
        if (current < 0 || visibleIds.size < 2) return null
        val others = visibleIds.filter { it != movedId }
        val target = targetIndex.coerceIn(0, others.size)
        if (target == current) return null
        return others.getOrNull(target - 1) to others.getOrNull(target)
    }

    /**
     * Index the dragged row lands at: the number of OTHER rows whose middle is above the
     * dragged row's centre. [tops] and [bottoms] are the rows' current bounds in list order.
     */
    fun dropIndex(tops: List<Int>, bottoms: List<Int>, draggedIndex: Int, draggedCenterY: Float): Int {
        var count = 0
        for (i in tops.indices) {
            if (i == draggedIndex) continue
            if ((tops[i] + bottoms[i]) / 2f < draggedCenterY) count++
        }
        return count
    }

    /**
     * The dragged row's vertical offset for a finger travel of [fingerDy], for a row laid out at
     * [rowTop]..[rowBottom] in a list [listHeight] tall. The row may go half its height past
     * either end of the list, so its centre can pass the first or last row's middle and the
     * first and last slots are reachable (a row clamped inside the list could not reach them).
     */
    fun dragOffset(fingerDy: Float, rowTop: Int, rowBottom: Int, listHeight: Int): Float {
        val half = (rowBottom - rowTop) / 2f
        val min = -rowTop - half
        val max = (listHeight - rowBottom) + half
        return fingerDy.coerceIn(min, max(min, max))
    }

    /**
     * Pixels to scroll this frame while dragging with the finger at [fingerY] inside a
     * viewport of [viewportHeight]: negative (up) inside the top [edge] band, positive inside
     * the bottom one, growing to [maxStep] at the edge and beyond. Zero elsewhere. A viewport
     * shorter than two bands (the landscape pane: ~195 px against 105 px bands on a Saga) uses
     * bands of a third of its height, so the middle third still holds the list still.
     */
    fun autoScrollStep(fingerY: Float, viewportHeight: Float, edge: Float, maxStep: Float): Int {
        if (edge <= 0f || viewportHeight <= 0f) return 0
        val band = if (viewportHeight < 2 * edge) viewportHeight / 3 else edge
        return bandStep(fingerY, viewportHeight, band, maxStep)
    }

    private fun bandStep(fingerY: Float, viewportHeight: Float, edge: Float, maxStep: Float): Int {
        val depth = when {
            fingerY < edge -> -(edge - fingerY) / edge
            fingerY > viewportHeight - edge -> (fingerY - (viewportHeight - edge)) / edge
            else -> return 0
        }
        val clamped = max(-1f, min(1f, depth))
        val step = (clamped * maxStep).roundToInt()
        // Inside a band always move at least one pixel, so a finger resting there still scrolls.
        return if (step == 0) (if (clamped < 0) -1 else 1) else step
    }

    /** Whether rows show the drag handle and accept moves. */
    enum class Availability {
        /** Moves work; the handle is shown. */
        AVAILABLE,

        /** A search or a non-status filter is active: the handle is dimmed and explains why. */
        FILTERED,

        /** History tab, selection mode, edit mode or a running bulk action: no handle. */
        HIDDEN,
    }

    /**
     * Availability of reordering. [filtered] means a search, date, size, private-only or tag
     * filter is active — the visible rows are then a scattered subset, and dropping between
     * two of them would place the row among unseen ones, so moves wait until it is cleared.
     * The status filter does NOT count: it is the To-dos default (active only), and the
     * hidden statuses keep their order (see the class comment).
     */
    fun availability(isHistory: Boolean, selecting: Boolean, editing: Boolean, bulkRunning: Boolean, filtered: Boolean): Availability = when {
        isHistory || selecting || editing || bulkRunning -> Availability.HIDDEN
        filtered -> Availability.FILTERED
        else -> Availability.AVAILABLE
    }

    /** TalkBack custom actions on a row. */
    enum class MoveAction { UP, DOWN, TOP }

    /**
     * Moves offered for the row at [index] of [count] visible rows. "Move to top" is offered
     * from the third row on: on the second it would duplicate "Move up".
     */
    fun moveActions(index: Int, count: Int): List<MoveAction> {
        if (count < 2 || index !in 0 until count) return emptyList()
        return buildList {
            if (index > 0) add(MoveAction.UP)
            if (index < count - 1) add(MoveAction.DOWN)
            if (index > 1) add(MoveAction.TOP)
        }
    }

    /** Target index of [action] for the row now at [index]. */
    fun targetIndex(action: MoveAction, index: Int): Int = when (action) {
        MoveAction.UP -> index - 1
        MoveAction.DOWN -> index + 1
        MoveAction.TOP -> 0
    }
}
