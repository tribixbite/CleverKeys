package tribixbite.cleverkeys.popover

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Hit-testing and layout for the hold-then-select subkey popover (docs/specs/subkey-popover.md).
 *
 * The popover is a 3×3 grid: the neutral centre cell plus one cell per [SwipeDirection]. Each cell
 * is one key wide and one row tall. The grid is centred on the finger's resting point but CLAMPED
 * inside the keyboard view, because touch coordinates in an IME window are clamped to the view
 * (see `Pointers.onTouchMove`): a top-row key's N row, or an edge key's outer column, would
 * otherwise sit where the finger can never go. Selection is hit-tested in the grid's DRAWN
 * coordinates, so what the user sees under the finger is what gets selected.
 *
 * A clamped grid leaves the resting finger over a slot instead of the centre. [isArmed] keeps the
 * selection neutral until the finger actually moves, so an open-then-release never inserts
 * something the user did not reach for.
 */
object SubkeyPopoverGeometry {

    /** Fraction of the smaller cell side the finger must travel before selection starts. */
    const val ARM_FRACTION = 0.15f

    /**
     * Centre of the grid along one axis: [rest] (the finger) clamped so all three cells of size
     * [cell] fit inside `[0, viewSize]`. A view smaller than three cells centres the grid on it.
     */
    fun gridCentre(rest: Float, cell: Float, viewSize: Float): Float {
        val half = 1.5f * cell
        if (viewSize <= 2f * half) return viewSize / 2f
        return rest.coerceIn(half, viewSize - half)
    }

    /**
     * Whether the finger has moved far enough from where it rested when the popover opened
     * ([restX], [restY]) for its position to select anything.
     */
    fun isArmed(restX: Float, restY: Float, x: Float, y: Float, cellWidth: Float, cellHeight: Float): Boolean =
        hypot(x - restX, y - restY) > ARM_FRACTION * min(cellWidth, cellHeight)

    /**
     * The slot a finger displaced by ([dx], [dy]) px from the grid centre points at, or `null`
     * inside the neutral zone.
     *
     * The neutral zone is a rectangle centred on the grid centre, [neutralWidthFraction] of a
     * cell wide and [neutralHeightFraction] of a cell tall (the user's percentages / 100).
     * Outside it the slot is the 45° sector of the displacement AFTER normalising by the cell
     * size, so the sector borders run along the grid's diagonals: one cell right and one cell up
     * is always NE, whatever the key's aspect ratio. Without the normalisation a tall key would
     * turn most up-right movements into N.
     */
    fun slotAt(
        dx: Float,
        dy: Float,
        cellWidth: Float,
        cellHeight: Float,
        neutralWidthFraction: Float,
        neutralHeightFraction: Float,
    ): SwipeDirection? {
        if (cellWidth <= 0f || cellHeight <= 0f) return null
        if (abs(dx) <= cellWidth * neutralWidthFraction / 2f &&
            abs(dy) <= cellHeight * neutralHeightFraction / 2f
        ) return null
        val nx = dx / cellWidth
        val ny = dy / cellHeight
        // Screen y grows downward; SwipeDirection angles are counter-clockwise from East.
        val angle = Math.toDegrees(atan2(-ny.toDouble(), nx.toDouble())).toFloat()
        return SwipeDirection.fromAngle(angle)
    }

    /**
     * Column offset of [direction]'s cell from the centre cell (-1 left, 0, 1 right). Separate
     * from [cellRow] (not a Pair) so the per-frame draw path allocates nothing.
     */
    fun cellColumn(direction: SwipeDirection): Int = when (direction) {
        SwipeDirection.NW, SwipeDirection.W, SwipeDirection.SW -> -1
        SwipeDirection.N, SwipeDirection.S -> 0
        SwipeDirection.NE, SwipeDirection.E, SwipeDirection.SE -> 1
    }

    /** Row offset of [direction]'s cell from the centre cell (-1 up, 0, 1 down). */
    fun cellRow(direction: SwipeDirection): Int = when (direction) {
        SwipeDirection.NW, SwipeDirection.N, SwipeDirection.NE -> -1
        SwipeDirection.W, SwipeDirection.E -> 0
        SwipeDirection.SW, SwipeDirection.S, SwipeDirection.SE -> 1
    }
}
