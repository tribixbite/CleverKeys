package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.Availability
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.MoveAction
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.Plan
import tribixbite.cleverkeys.clipboard.ClipboardReorderPolicy.Row

/**
 * Reordering Pinned and To-dos rows (2026-10-27): the midpoint write, the renumbering when a
 * gap gets too small, the neighbour choice from the rows the user can see, the drop slot of a
 * drag, the edge auto-scroll and when reordering is offered at all. Pure.
 */
class ClipboardReorderPolicyTest {

    private fun rows(vararg pairs: Pair<Long, Double>) = pairs.map { (id, pos) -> Row(id, pos) }

    // ─────────────────────────────────────────────── plan: one write between neighbours

    @Test
    fun aMoveBetweenTwoNeighboursWritesTheirMidpointOnly() {
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0, 4L to 4.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 4, afterId = 1, beforeId = 2)

        assertThat(plan).isEqualTo(Plan.Place(4, 1.5))
    }

    @Test
    fun movingToTheTopGoesAboveEveryRowOfTheTableIncludingHiddenOnes() {
        // Row 1 is a hidden (e.g. completed) to-do; 2 is the first visible one.
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 3, afterId = null, beforeId = 2)

        assertThat(plan).isEqualTo(Plan.Place(3, 1.0 - ClipboardReorderPolicy.END_STEP))
    }

    @Test
    fun movingToTheBottomGoesBelowEveryRowOfTheTable() {
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 7.5)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 1, afterId = 2, beforeId = null)

        assertThat(plan).isEqualTo(Plan.Place(1, 7.5 + ClipboardReorderPolicy.END_STEP))
    }

    @Test
    fun theMovedRowItselfIsNotItsOwnBound() {
        // Moving the global-last row to the end of a list whose last visible row precedes it.
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 1, afterId = 2, beforeId = null)

        assertThat(plan).isEqualTo(Plan.Place(1, 3.0 + ClipboardReorderPolicy.END_STEP))
    }

    @Test
    fun hiddenRowsBetweenTheNeighboursKeepTheirOrder() {
        // Visible: 1, 5 (active). Hidden between them: 2, 3, 4 (planned/completed).
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0, 4L to 4.0, 5L to 5.0, 6L to 6.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 6, afterId = 1, beforeId = 5) as Plan.Place
        val after = ClipboardReorderPolicy.applied(table, plan).map { it.id }

        assertWithMessage("visible order 1, 6, 5").that(after.filter { it in setOf(1L, 5L, 6L) })
            .containsExactly(1L, 6L, 5L).inOrder()
        assertWithMessage("hidden rows untouched and in order").that(after.filter { it in setOf(2L, 3L, 4L) })
            .containsExactly(2L, 3L, 4L).inOrder()
    }

    // ─────────────────────────────────────────────── plan: renumbering

    @Test
    fun aGapAtOrBelowTheThresholdRenumbersTheWholeTableOnce() {
        val tiny = ClipboardReorderPolicy.MIN_GAP / 2
        val table = rows(1L to 1.0, 2L to 1.0 + tiny, 3L to 3.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 1, beforeId = 2)

        assertThat(plan).isEqualTo(Plan.Renumber(rows(1L to 1.0, 3L to 2.0, 2L to 3.0)))
    }

    @Test
    fun duplicatePositionsFromAMergedImportRenumber() {
        // An import into a non-empty table can bring positions equal to existing ones.
        val table = rows(1L to 1.0, 2L to 1.0, 3L to 2.0)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 1, beforeId = 2)

        assertThat(plan).isEqualTo(Plan.Renumber(rows(1L to 1.0, 3L to 2.0, 2L to 3.0)))
    }

    @Test
    fun repeatedMovesIntoTheSameGapStayOrderedAndEventuallyRenumber() {
        var table = rows(1L to 1.0, 2L to 2.0) + (3L..60L).map { Row(it, it.toDouble()) }
        var renumbered = 0
        // Move each next row between 1 and the row inserted last: the gap halves every time.
        var before = 2L
        for (moved in 3L..60L) {
            val plan = ClipboardReorderPolicy.plan(table, moved, afterId = 1, beforeId = before)
            if (plan is Plan.Renumber) renumbered++
            table = ClipboardReorderPolicy.applied(table, plan)
            before = moved
        }
        val order = table.map { it.id }
        assertThat(order.first()).isEqualTo(1L)
        assertWithMessage("each move lands directly after row 1").that(order.subList(1, 59))
            .containsExactlyElementsIn((60L downTo 3L).toList()).inOrder()
        assertThat(order.last()).isEqualTo(2L)
        assertWithMessage("positions strictly increase").that(table.zipWithNext().all { (a, b) -> a.position < b.position }).isTrue()
        // 2^-20 < MIN_GAP: about one renumber per 20 halvings, never one per move.
        assertThat(renumbered).isIn(2..3)
    }

    @Test
    fun nonFinitePositionsRenumber() {
        val table = rows(1L to 1.0, 2L to Double.POSITIVE_INFINITY, 3L to Double.NaN)

        val plan = ClipboardReorderPolicy.plan(table, movedId = 1, afterId = 2, beforeId = null)

        assertThat(plan).isInstanceOf(Plan.Renumber::class.java)
        assertThat(ClipboardReorderPolicy.applied(table, plan).map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    // ─────────────────────────────────────────────── plan: stale or no-op requests

    @Test
    fun aMoveNamingARowThatNoLongerExistsIsRefused() {
        val table = rows(1L to 1.0, 2L to 2.0)

        assertThat(ClipboardReorderPolicy.plan(table, movedId = 9, afterId = 1, beforeId = 2)).isEqualTo(Plan.Refused)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 1, afterId = 9, beforeId = null)).isEqualTo(Plan.Refused)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 1, afterId = null, beforeId = null)).isEqualTo(Plan.Refused)
    }

    @Test
    fun neighboursInTheWrongOrderAreRefused() {
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0)

        assertThat(ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 2, beforeId = 1)).isEqualTo(Plan.Refused)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 1, beforeId = 1)).isEqualTo(Plan.Refused)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 3, beforeId = null)).isEqualTo(Plan.Refused)
    }

    @Test
    fun aRowAlreadyBetweenItsNeighboursIsUnchanged() {
        val table = rows(1L to 1.0, 2L to 2.0, 3L to 3.0)

        assertThat(ClipboardReorderPolicy.plan(table, movedId = 2, afterId = 1, beforeId = 3)).isEqualTo(Plan.Unchanged)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 1, afterId = null, beforeId = 2)).isEqualTo(Plan.Unchanged)
        assertThat(ClipboardReorderPolicy.plan(table, movedId = 3, afterId = 2, beforeId = null)).isEqualTo(Plan.Unchanged)
    }

    // ─────────────────────────────────────────────── neighbours from the visible list

    @Test
    fun neighboursAreTheVisibleRowsAroundTheTargetIndex() {
        val visible = listOf(10L, 20L, 30L, 40L)

        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 40, targetIndex = 0))
            .isEqualTo(null to 10L)
        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 10, targetIndex = 3))
            .isEqualTo(40L to null)
        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 10, targetIndex = 1))
            .isEqualTo(20L to 30L)
        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 30, targetIndex = 1))
            .isEqualTo(10L to 20L)
    }

    @Test
    fun theCurrentIndexOrAMissingRowIsNoMove() {
        val visible = listOf(10L, 20L, 30L)

        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 20, targetIndex = 1)).isNull()
        assertThat(ClipboardReorderPolicy.neighbours(visible, movedId = 99, targetIndex = 0)).isNull()
        assertThat(ClipboardReorderPolicy.neighbours(listOf(10L), movedId = 10, targetIndex = 0)).isNull()
        assertWithMessage("an out-of-range target is clamped").that(
            ClipboardReorderPolicy.neighbours(visible, movedId = 10, targetIndex = 99)
        ).isEqualTo(30L to null)
    }

    // ─────────────────────────────────────────────── drag geometry

    @Test
    fun theDropIndexCountsTheOtherRowsWhoseMiddleIsAboveTheDraggedRowsCentre() {
        // Four 100 px rows; row 0 is dragged.
        val tops = listOf(0, 100, 200, 300)
        val bottoms = listOf(100, 200, 300, 400)

        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 0, draggedCenterY = 50f)).isEqualTo(0)
        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 0, draggedCenterY = 149f)).isEqualTo(0)
        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 0, draggedCenterY = 151f)).isEqualTo(1)
        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 0, draggedCenterY = 999f)).isEqualTo(3)
        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 3, draggedCenterY = -50f)).isEqualTo(0)
        assertThat(ClipboardReorderPolicy.dropIndex(tops, bottoms, draggedIndex = 3, draggedCenterY = 340f)).isEqualTo(3)
    }

    @Test
    fun aRowHeldPastEitherEndOfTheListReachesTheFirstAndLastSlot() {
        // Saga 2026-10-27: the dragged row was clamped inside the list, so its centre could only
        // reach the last row's middle and a drag to the bottom landed one above the end.
        val tops = listOf(0, 100, 200, 300)
        val bottoms = listOf(100, 200, 300, 400)
        fun slot(dragged: Int, fingerDy: Float): Int {
            val dy = ClipboardReorderPolicy.dragOffset(fingerDy, tops[dragged], bottoms[dragged], listHeight = 400)
            return ClipboardReorderPolicy.dropIndex(tops, bottoms, dragged, tops[dragged] + dy + 50f)
        }

        assertThat(slot(dragged = 0, fingerDy = 10_000f)).isEqualTo(3)
        assertThat(slot(dragged = 3, fingerDy = -10_000f)).isEqualTo(0)
        assertThat(slot(dragged = 1, fingerDy = 10_000f)).isEqualTo(3)
        assertWithMessage("never more than half a row past the list")
            .that(ClipboardReorderPolicy.dragOffset(10_000f, 0, 100, listHeight = 400)).isEqualTo(350f)
        assertThat(ClipboardReorderPolicy.dragOffset(-10_000f, 300, 400, listHeight = 400)).isEqualTo(-350f)
        assertThat(ClipboardReorderPolicy.dragOffset(42f, 100, 200, listHeight = 400)).isEqualTo(42f)
    }

    @Test
    fun autoScrollStartsInsideTheEdgeBandAndGrowsTowardTheEdge() {
        val step = { y: Float -> ClipboardReorderPolicy.autoScrollStep(y, viewportHeight = 500f, edge = 50f, maxStep = 20f) }

        assertThat(step(250f)).isEqualTo(0)
        assertThat(step(50f)).isEqualTo(0)
        assertThat(step(450f)).isEqualTo(0)
        assertThat(step(25f)).isLessThan(0)
        assertThat(step(0f)).isEqualTo(-20)
        assertThat(step(-30f)).isEqualTo(-20)
        assertThat(step(475f)).isGreaterThan(0)
        assertThat(step(500f)).isEqualTo(20)
        assertThat(step(10f)).isLessThan(step(40f))
        // Saga landscape (2026-10-27): the pane's list viewport was ~195 px against 105 px bands.
        // A viewport shorter than two bands uses bands of a third of its height instead.
        val short = { y: Float -> ClipboardReorderPolicy.autoScrollStep(y, viewportHeight = 60f, edge = 50f, maxStep = 20f) }
        assertWithMessage("short viewport: top third scrolls up").that(short(10f)).isLessThan(0)
        assertWithMessage("short viewport: middle third is still").that(short(30f)).isEqualTo(0)
        assertWithMessage("short viewport: bottom third scrolls down").that(short(55f)).isGreaterThan(0)
        assertThat(short(60f)).isEqualTo(20)
    }

    // ─────────────────────────────────────────────── availability and accessible moves

    @Test
    fun reorderingIsOfferedOnPinnedAndTodosInNormalModeOnly() {
        fun avail(tab: String = "TODOS", selecting: Boolean = false, editing: Boolean = false,
                  bulk: Boolean = false, filtered: Boolean = false) =
            ClipboardReorderPolicy.availability(tab == "HISTORY", selecting, editing, bulk, filtered)

        assertThat(avail()).isEqualTo(Availability.AVAILABLE)
        assertThat(avail(tab = "PINNED")).isEqualTo(Availability.AVAILABLE)
        assertWithMessage("history is ordered by time").that(avail(tab = "HISTORY")).isEqualTo(Availability.HIDDEN)
        assertThat(avail(selecting = true)).isEqualTo(Availability.HIDDEN)
        assertThat(avail(editing = true)).isEqualTo(Availability.HIDDEN)
        assertThat(avail(bulk = true)).isEqualTo(Availability.HIDDEN)
        assertThat(avail(filtered = true)).isEqualTo(Availability.FILTERED)
        assertWithMessage("selection outranks a filter").that(avail(selecting = true, filtered = true)).isEqualTo(Availability.HIDDEN)
    }

    @Test
    fun accessibleMovesMatchTheRowsPlaceInTheList() {
        assertThat(ClipboardReorderPolicy.moveActions(index = 0, count = 3)).containsExactly(MoveAction.DOWN)
        assertThat(ClipboardReorderPolicy.moveActions(index = 1, count = 3)).containsExactly(MoveAction.UP, MoveAction.DOWN).inOrder()
        assertThat(ClipboardReorderPolicy.moveActions(index = 2, count = 3)).containsExactly(MoveAction.UP, MoveAction.TOP).inOrder()
        assertThat(ClipboardReorderPolicy.moveActions(index = 0, count = 1)).isEmpty()
        assertThat(ClipboardReorderPolicy.moveActions(index = 5, count = 3)).isEmpty()
    }

    @Test
    fun eachAccessibleMoveTargetsTheRightIndex() {
        assertThat(ClipboardReorderPolicy.targetIndex(MoveAction.UP, index = 4)).isEqualTo(3)
        assertThat(ClipboardReorderPolicy.targetIndex(MoveAction.DOWN, index = 4)).isEqualTo(5)
        assertThat(ClipboardReorderPolicy.targetIndex(MoveAction.TOP, index = 4)).isEqualTo(0)
    }
}
