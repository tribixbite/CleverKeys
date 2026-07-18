package tribixbite.cleverkeys.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * TalkBack virtual-view tree for the custom-drawn [tribixbite.cleverkeys.Keyboard2View].
 *
 * One virtual view per non-placeholder key. Bounds and hit-testing come from
 * [KeyboardGeometry] (the SAME geometry taps/swipes use), so the box TalkBack
 * highlights is exactly the tappable area.
 *
 * ## Install (do NOT override getAccessibilityNodeProvider)
 * `ExploreByTouchHelper` supplies the node provider itself; the host installs it
 * with `ViewCompat.setAccessibilityDelegate(view, helper)`. The host's only
 * mandatory overrides are `dispatchHoverEvent` (guarded by
 * `isTouchExplorationEnabled`), `dispatchKeyEvent`, and `onFocusChanged`.
 *
 * ## Zero cost when TalkBack is off
 * The platform only synthesizes hover events when touch-exploration is active, so
 * with TalkBack off this helper is never consulted and the `onTouch` gesture path
 * is completely untouched.
 *
 * All keyboard-specific data is injected as lambdas so this class stays free of a
 * direct dependency on the view's private state (and so the geometry/labeller
 * remain independently testable).
 *
 * @param host the Keyboard2View.
 * @param rectsProvider current per-key hit-test rects; recomputed by the host on
 *   every layout/shift/modifier change and re-published via [invalidateRoot].
 * @param describe spoken label for a rect. The host applies the live modifier
 *   transform (`describe(modifyKey(kv, mods) ?: kv)`) so a latched Shift announces
 *   "A", not "a".
 * @param onActivate performs an accessibility click. MUST route through
 *   `Pointers.onTouchDown/onTouchUp` (NOT the handler directly) so modifier
 *   latching works — see Keyboard2View.activateKeyForAccessibility.
 * @param checkedState for Shift/CapsLock: `(isCheckable, isChecked)`; null for
 *   every other key (not checkable).
 */
class KeyboardAccessibilityHelper(
    host: View,
    private val rectsProvider: () -> List<KeyboardGeometry.KeyRect>,
    private val describe: (KeyboardGeometry.KeyRect) -> CharSequence,
    private val onActivate: (KeyboardGeometry.KeyRect) -> Unit,
    private val checkedState: (KeyboardGeometry.KeyRect) -> Pair<Boolean, Boolean>?,
) : ExploreByTouchHelper(host) {

    // Scratch Rect reused across onPopulateNodeForVirtualView calls (a11y events
    // are rare and single-threaded on the main thread, so reuse is safe and
    // avoids per-node allocation).
    private val tmpBounds = Rect()

    /** Rect whose flat id == [virtualViewId], or null (stale id during relayout). */
    private fun rectById(virtualViewId: Int): KeyboardGeometry.KeyRect? =
        rectsProvider().getOrNull(virtualViewId)?.takeIf { it.virtualId == virtualViewId }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        // Linear scan is fine: a keyboard has ~30-50 keys and hover events are
        // low-frequency. Uses half-open cell bounds, matching KeyboardGeometry.
        for (r in rectsProvider()) {
            val b = r.bounds
            if (x >= b.left && x < b.right && y >= b.top && y < b.bottom) {
                return r.virtualId
            }
        }
        return HOST_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (r in rectsProvider()) virtualViewIds.add(r.virtualId)
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat,
    ) {
        val kr = rectById(virtualViewId)
        if (kr == null) {
            // Stale id mid-relayout. Return a valid, tiny, off-key node instead of
            // throwing — ExploreByTouchHelper requires a non-empty bounds rect and
            // a content description on every populated node.
            node.contentDescription = ""
            tmpBounds.set(0, 0, 1, 1)
            node.setBoundsInParent(tmpBounds)
            return
        }

        node.contentDescription = describe(kr)
        node.className = "android.inputmethodservice.Keyboard\$Key"
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)

        checkedState(kr)?.let { (checkable, checked) ->
            node.isCheckable = checkable
            node.isChecked = checked
        }

        val b = kr.bounds
        // Round outward so the node never reports a smaller box than the tappable
        // cell (avoids a 1px announce/hit mismatch from float truncation).
        tmpBounds.set(
            kotlin.math.floor(b.left).toInt(),
            kotlin.math.floor(b.top).toInt(),
            kotlin.math.ceil(b.right).toInt(),
            kotlin.math.ceil(b.bottom).toInt(),
        )
        node.setBoundsInParent(tmpBounds)
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val kr = rectById(virtualViewId) ?: return false
        onActivate(kr)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }
}
