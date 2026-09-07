package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Audit E-6 (2026-09-06) — the GIF category bar must not switch category on ACTION_DOWN.
 *
 * 19 fixed 44 dp buttons always overflow the HorizontalScrollView, so the bar must be
 * scrolled — and the HSV can only intercept from MOVE onward, meaning every scroll
 * gesture's DOWN lands on a button first. The old `GifGroupButton.onTouch` committed the
 * selection (and wiped the in-progress search) right on that DOWN, so scrolling the bar
 * always jumped the grid to an unintended category. Selection now commits on UP within
 * touch slop via [GifCategoryTapDetector]; parent interception delivers ACTION_CANCEL,
 * which aborts the tap.
 *
 * RED (pre-fix): no detector existed — the DOWN-fire lived inline in GifGroupButton
 * (`if (event.action != ACTION_DOWN) return false; onCategorySelected?.invoke(); ...`),
 * so "scroll wipes the search" was the shipped behavior; these tests pin the replacement
 * contract (the pure class is new code, so the red here is the pre-fix source shape).
 */
class GifCategoryTapDetectorTest {

    private val slop = 10f

    @Test
    fun cleanTapFiresOnUpOnly() {
        val d = GifCategoryTapDetector(slop)
        assertWithMessage("DOWN must never fire the selection")
            .that(d.feed(GifCategoryTapDetector.ACTION_DOWN, 50f, 20f)).isFalse()
        assertWithMessage("UP within slop completes the tap")
            .that(d.feed(GifCategoryTapDetector.ACTION_UP, 53f, 22f)).isTrue()
    }

    @Test
    fun scrollBeyondSlopNeverFires() {
        val d = GifCategoryTapDetector(slop)
        d.feed(GifCategoryTapDetector.ACTION_DOWN, 50f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_MOVE, 120f, 21f) // horizontal drag = bar scroll
        assertWithMessage("an UP after leaving the slop radius is a scroll, not a tap")
            .that(d.feed(GifCategoryTapDetector.ACTION_UP, 121f, 21f)).isFalse()
    }

    @Test
    fun returnIntoSlopAfterLeavingStillDoesNotFire() {
        val d = GifCategoryTapDetector(slop)
        d.feed(GifCategoryTapDetector.ACTION_DOWN, 50f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_MOVE, 120f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_MOVE, 51f, 20f)
        assertThat(d.feed(GifCategoryTapDetector.ACTION_UP, 51f, 20f)).isFalse()
    }

    @Test
    fun parentInterceptionCancelAbortsTheTap() {
        val d = GifCategoryTapDetector(slop)
        d.feed(GifCategoryTapDetector.ACTION_DOWN, 50f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_CANCEL, 50f, 20f) // HSV intercepted the gesture
        assertThat(d.feed(GifCategoryTapDetector.ACTION_UP, 50f, 20f)).isFalse()
    }

    @Test
    fun nextGestureAfterAbortWorksNormally() {
        val d = GifCategoryTapDetector(slop)
        d.feed(GifCategoryTapDetector.ACTION_DOWN, 50f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_CANCEL, 50f, 20f)
        d.feed(GifCategoryTapDetector.ACTION_DOWN, 80f, 20f)
        assertThat(d.feed(GifCategoryTapDetector.ACTION_UP, 82f, 20f)).isTrue()
    }

    @Test
    fun upWithoutTrackedDownNeverFires() {
        val d = GifCategoryTapDetector(slop)
        assertThat(d.feed(GifCategoryTapDetector.ACTION_UP, 50f, 20f)).isFalse()
    }
}
