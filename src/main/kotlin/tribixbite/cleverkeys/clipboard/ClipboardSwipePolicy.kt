package tribixbite.cleverkeys.clipboard

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * GH #175(2): when a horizontal drag on a clipboard row is a swipe-to-delete, and when it
 * commits. Pure (no Android types) so every threshold is unit-tested; the list view
 * ([tribixbite.cleverkeys.ClipboardHistoryView]) feeds it raw touch positions and the
 * platform velocity, and applies the translation/alpha it returns.
 *
 * The rules, in order:
 *  1. Nothing is decided inside the platform touch slop, so taps and long-presses on the row
 *     behave exactly as before.
 *  2. Past the slop the gesture is classified ONCE: horizontal only if |dx| > 2·|dy|,
 *     otherwise vertical. A vertical gesture is never claimed, so the pane's ScrollView keeps
 *     scrolling as it always did; a horizontal one is claimed (the parent is told not to
 *     intercept) and the row follows the finger.
 *  3. On release the swipe commits if the row travelled beyond [COMMIT_FRACTION] of its width,
 *     or was flung at [FLING_MIN_DP_PER_SECOND] or faster in the direction it travelled.
 *     Anything else springs back.
 */
object ClipboardSwipePolicy {

    /** A swipe is horizontal only when |dx| exceeds this multiple of |dy|. */
    const val HORIZONTAL_RATIO = 2f

    /** Fraction of the row width the finger must travel for a release to commit. */
    const val COMMIT_FRACTION = 0.35f

    /**
     * Minimum fling speed (dp per second) that commits a shorter swipe. ItemTouchHelper's
     * default escape velocity is 120 dp/s, which lets a lazy flick delete a row; 800 dp/s
     * needs a deliberate flick, which suits a destructive gesture with only a 5 s undo.
     */
    const val FLING_MIN_DP_PER_SECOND = 800f

    /** Opacity of a row dragged a full row width; it never fades out completely. */
    const val MIN_ALPHA = 0.2f

    /** Classification of a gesture after the touch slop. */
    enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    /** Classify a drag of ([dx], [dy]) pixels from the touch-down point. */
    fun classify(dx: Float, dy: Float, touchSlop: Float): Axis {
        if (dx * dx + dy * dy <= touchSlop * touchSlop) return Axis.UNDECIDED
        return if (abs(dx) > HORIZONTAL_RATIO * abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /**
     * Whether releasing a horizontal swipe of [dx] pixels at [velocityX] px/s deletes the row.
     * A fling only counts in the direction the row already travelled, so swiping out and
     * flicking back cancels.
     */
    fun commits(dx: Float, velocityX: Float, rowWidth: Float, flingVelocity: Float): Boolean {
        if (rowWidth <= 0f || dx == 0f) return false
        if (abs(dx) > COMMIT_FRACTION * rowWidth) return true
        return abs(velocityX) >= flingVelocity && sign(velocityX) == sign(dx)
    }

    /** The fling threshold in px/s for a display [density]. */
    fun flingVelocityPx(density: Float): Float = FLING_MIN_DP_PER_SECOND * density

    /** Row opacity while it follows the finger: linear from 1 to [MIN_ALPHA] over the row width. */
    fun alpha(dx: Float, rowWidth: Float): Float {
        if (rowWidth <= 0f) return 1f
        val travelled = min(1f, abs(dx) / rowWidth)
        if (travelled == 0f) return 1f
        // Interpolate from the MIN_ALPHA end so a full-width drag lands on it exactly.
        return MIN_ALPHA + (1f - MIN_ALPHA) * (1f - travelled)
    }

    /**
     * Whether a swipe may start: the setting is on and the pane is in NORMAL mode — not in
     * selection mode (rows toggle there), not while a row is edited, not in the tag panel and
     * not while a confirmed bulk action runs.
     */
    fun swipeAllowed(
        settingEnabled: Boolean,
        selecting: Boolean,
        editing: Boolean,
        tagMode: Boolean,
        bulkRunning: Boolean,
    ): Boolean = settingEnabled && deleteActionAllowed(selecting, editing, tagMode, bulkRunning)

    /**
     * Whether a row's deferred delete may run at all. The accessibility "Delete" action uses
     * this without the swipe setting: the setting exists to stop ACCIDENTAL horizontal
     * swipes, and a TalkBack custom action is never accidental.
     */
    fun deleteActionAllowed(
        selecting: Boolean,
        editing: Boolean,
        tagMode: Boolean,
        bulkRunning: Boolean,
    ): Boolean = !selecting && !editing && !tagMode && !bulkRunning
}

/**
 * One touch gesture on a clipboard row, from DOWN to UP/CANCEL. Positions are raw screen
 * coordinates so a content scroll during the slop phase cannot fake horizontal travel.
 * Main-thread confined; one instance is reused for every gesture.
 */
class ClipboardSwipeTracker(
    private val touchSlop: Float,
    private val flingVelocity: Float,
) {
    /** Whether a gesture is being tracked (between [down] and [release]/[cancel]). */
    var active: Boolean = false
        private set

    /** The gesture's classification; HORIZONTAL means the row is following the finger. */
    var axis: ClipboardSwipePolicy.Axis = ClipboardSwipePolicy.Axis.UNDECIDED
        private set

    /** Horizontal travel of a claimed swipe (0 until it is classified horizontal). */
    var dx: Float = 0f
        private set

    private var downX = 0f
    private var downY = 0f

    /** Start tracking a gesture at ([x], [y]). */
    fun down(x: Float, y: Float) {
        active = true
        axis = ClipboardSwipePolicy.Axis.UNDECIDED
        dx = 0f
        downX = x
        downY = y
    }

    /**
     * Feed a move. Returns true exactly once, on the move that classifies the gesture as
     * horizontal — the moment the caller claims it from the children and the parent scroll.
     */
    fun move(x: Float, y: Float): Boolean {
        if (!active) return false
        val travelX = x - downX
        when (axis) {
            ClipboardSwipePolicy.Axis.UNDECIDED -> {
                axis = ClipboardSwipePolicy.classify(travelX, y - downY, touchSlop)
                if (axis == ClipboardSwipePolicy.Axis.HORIZONTAL) {
                    dx = travelX
                    return true
                }
            }
            ClipboardSwipePolicy.Axis.HORIZONTAL -> dx = travelX
            ClipboardSwipePolicy.Axis.VERTICAL -> Unit
        }
        return false
    }

    /**
     * End the gesture with the release velocity; returns whether the row should be deleted.
     * The tracker is reset either way.
     */
    fun release(velocityX: Float, rowWidth: Float): Boolean {
        val commit = active && axis == ClipboardSwipePolicy.Axis.HORIZONTAL &&
            ClipboardSwipePolicy.commits(dx, velocityX, rowWidth, flingVelocity)
        cancel()
        return commit
    }

    /** Abandon the gesture (CANCEL, a second pointer, a vertical scroll, a state change). */
    fun cancel() {
        active = false
        axis = ClipboardSwipePolicy.Axis.UNDECIDED
        dx = 0f
    }
}
