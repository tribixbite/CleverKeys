package tribixbite.cleverkeys.gif

/**
 * Audit E-6: pure tap-vs-scroll discriminator for the GIF category bar.
 *
 * The bar is 19 fixed 44 dp buttons inside a HorizontalScrollView — it ALWAYS overflows,
 * so it must be scrollable. The old `GifGroupButton.onTouch` fired the category switch on
 * ACTION_DOWN, i.e. on the FIRST touch of every scroll gesture — scrolling the bar always
 * jumped the grid to an unintended category and wiped any in-progress search (the wiring
 * clears the search input on selection). ViewGroup interception only kicks in from MOVE
 * onward, so the DOWN is always delivered to the button first; the selection must
 * therefore commit on ACTION_UP, and only when the pointer stayed within touch slop.
 *
 * Pure Kotlin (raw action ints, no android imports) so the rule is unit-tested in
 * `runPureTests` ([GifCategoryTapDetectorTest]); the button feeds it MotionEvent values.
 * A parent intercepting the gesture delivers ACTION_CANCEL, which aborts tracking — the
 * scroll case fires nothing.
 */
class GifCategoryTapDetector(private val touchSlopPx: Float) {

    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    /**
     * Feed one touch event; returns true exactly when this event COMPLETES a tap
     * (an UP that stayed within [touchSlopPx] of its DOWN, uninterrupted by CANCEL).
     */
    fun feed(action: Int, x: Float, y: Float): Boolean {
        when (action) {
            ACTION_DOWN -> {
                downX = x
                downY = y
                tracking = true
            }
            ACTION_MOVE -> {
                if (tracking && !withinSlop(x, y)) tracking = false
            }
            ACTION_UP -> {
                val fire = tracking && withinSlop(x, y)
                tracking = false
                return fire
            }
            ACTION_CANCEL -> tracking = false
        }
        return false
    }

    private fun withinSlop(x: Float, y: Float): Boolean =
        kotlin.math.abs(x - downX) <= touchSlopPx && kotlin.math.abs(y - downY) <= touchSlopPx

    companion object {
        // android.view.MotionEvent constants, restated so this file stays pure-JVM.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
    }
}
