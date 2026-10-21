package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.clipboard.ClipboardSwipePolicy.Axis

/**
 * GH #175(2): the swipe-to-delete gesture on clipboard rows, as a pure policy. Pins the
 * classification (touch slop, then |dx| > 2·|dy|), the commit rule (beyond 35 % of the row
 * width, or a fast fling in the same direction), the follow-finger fade, the gesture tracker
 * that the list view drives, and when a swipe may start at all.
 */
class ClipboardSwipePolicyTest {

    private val slop = 16f
    private val fling = 1_000f
    private val width = 1_000f

    // ─────────────────────────────────────────────── classification

    @Test
    fun movementInsideTheTouchSlopStaysUndecided() {
        assertThat(ClipboardSwipePolicy.classify(10f, 10f, slop)).isEqualTo(Axis.UNDECIDED)
        assertThat(ClipboardSwipePolicy.classify(16f, 0f, slop)).isEqualTo(Axis.UNDECIDED)
        assertThat(ClipboardSwipePolicy.classify(0f, -16f, slop)).isEqualTo(Axis.UNDECIDED)
    }

    @Test
    fun beyondTheSlopAMostlyHorizontalMoveIsHorizontalInBothDirections() {
        assertThat(ClipboardSwipePolicy.classify(17f, 0f, slop)).isEqualTo(Axis.HORIZONTAL)
        assertThat(ClipboardSwipePolicy.classify(-40f, 19f, slop)).isEqualTo(Axis.HORIZONTAL)
    }

    @Test
    fun theRatioIsStrictlyTwoToOne() {
        // |dx| == 2·|dy| is NOT horizontal: the rule is |dx| > 2·|dy|.
        assertThat(ClipboardSwipePolicy.classify(40f, 20f, slop)).isEqualTo(Axis.VERTICAL)
        assertThat(ClipboardSwipePolicy.classify(40.5f, 20f, slop)).isEqualTo(Axis.HORIZONTAL)
        assertThat(ClipboardSwipePolicy.classify(-40f, -20f, slop)).isEqualTo(Axis.VERTICAL)
    }

    @Test
    fun aDiagonalOrVerticalMoveIsVerticalSoScrollingIsUntouched() {
        assertThat(ClipboardSwipePolicy.classify(20f, 20f, slop)).isEqualTo(Axis.VERTICAL)
        assertThat(ClipboardSwipePolicy.classify(0f, 30f, slop)).isEqualTo(Axis.VERTICAL)
    }

    // ─────────────────────────────────────────────── commit rule

    @Test
    fun releaseBeyond35PercentOfTheRowCommitsEitherWay() {
        assertThat(ClipboardSwipePolicy.commits(351f, 0f, width, fling)).isTrue()
        assertThat(ClipboardSwipePolicy.commits(-351f, 0f, width, fling)).isTrue()
    }

    @Test
    fun releaseAtOrBelow35PercentWithoutAFlingSpringsBack() {
        assertThat(ClipboardSwipePolicy.commits(350f, 0f, width, fling)).isFalse()
        assertThat(ClipboardSwipePolicy.commits(-200f, -999f, width, fling)).isFalse()
    }

    @Test
    fun aFastFlingInTheSameDirectionCommitsAShortSwipe() {
        assertThat(ClipboardSwipePolicy.commits(60f, 1_000f, width, fling)).isTrue()
        assertThat(ClipboardSwipePolicy.commits(-60f, -1_500f, width, fling)).isTrue()
    }

    @Test
    fun aFlingAgainstTheSwipeDirectionDoesNotCommit() {
        assertWithMessage("swiped right, then flicked back left: the user changed their mind")
            .that(ClipboardSwipePolicy.commits(200f, -3_000f, width, fling)).isFalse()
        assertThat(ClipboardSwipePolicy.commits(-200f, 3_000f, width, fling)).isFalse()
    }

    @Test
    fun aDegenerateRowOrNoTravelNeverCommits() {
        assertThat(ClipboardSwipePolicy.commits(500f, 0f, 0f, fling)).isFalse()
        assertThat(ClipboardSwipePolicy.commits(0f, 5_000f, width, fling)).isFalse()
    }

    @Test
    fun flingThresholdScalesWithDensity() {
        assertThat(ClipboardSwipePolicy.flingVelocityPx(1f))
            .isEqualTo(ClipboardSwipePolicy.FLING_MIN_DP_PER_SECOND)
        assertThat(ClipboardSwipePolicy.flingVelocityPx(2.5f))
            .isEqualTo(ClipboardSwipePolicy.FLING_MIN_DP_PER_SECOND * 2.5f)
    }

    // ─────────────────────────────────────────────── follow-finger fade

    @Test
    fun theRowFadesWithTravelAndNeverVanishes() {
        assertThat(ClipboardSwipePolicy.alpha(0f, width)).isEqualTo(1f)
        val half = ClipboardSwipePolicy.alpha(500f, width)
        assertThat(half).isLessThan(1f)
        assertThat(half).isGreaterThan(ClipboardSwipePolicy.MIN_ALPHA)
        assertThat(ClipboardSwipePolicy.alpha(-500f, width)).isEqualTo(half)
        assertThat(ClipboardSwipePolicy.alpha(5_000f, width)).isEqualTo(ClipboardSwipePolicy.MIN_ALPHA)
        assertThat(ClipboardSwipePolicy.alpha(100f, 0f)).isEqualTo(1f)
    }

    // ─────────────────────────────────────────────── when a swipe may start

    @Test
    fun swipesStartOnlyInNormalModeWithTheSettingOn() {
        assertThat(ClipboardSwipePolicy.swipeAllowed(settingEnabled = true, selecting = false,
            editing = false, tagMode = false, bulkRunning = false)).isTrue()
        assertWithMessage("setting off: rows ignore horizontal swipes")
            .that(ClipboardSwipePolicy.swipeAllowed(false, false, false, false, false)).isFalse()
        assertWithMessage("selection mode").that(ClipboardSwipePolicy.swipeAllowed(true, true, false, false, false)).isFalse()
        assertWithMessage("a row is in edit mode").that(ClipboardSwipePolicy.swipeAllowed(true, false, true, false, false)).isFalse()
        assertWithMessage("tag panel").that(ClipboardSwipePolicy.swipeAllowed(true, false, false, true, false)).isFalse()
        assertWithMessage("bulk action running").that(ClipboardSwipePolicy.swipeAllowed(true, false, false, false, true)).isFalse()
    }

    @Test
    fun theAccessibilityDeleteActionIgnoresTheSwipeSettingButNotTheModes() {
        assertWithMessage("deliberate TalkBack action: available with the swipe setting off")
            .that(ClipboardSwipePolicy.deleteActionAllowed(selecting = false, editing = false,
                tagMode = false, bulkRunning = false)).isTrue()
        assertThat(ClipboardSwipePolicy.deleteActionAllowed(true, false, false, false)).isFalse()
        assertThat(ClipboardSwipePolicy.deleteActionAllowed(false, true, false, false)).isFalse()
        assertThat(ClipboardSwipePolicy.deleteActionAllowed(false, false, true, false)).isFalse()
        assertThat(ClipboardSwipePolicy.deleteActionAllowed(false, false, false, true)).isFalse()
    }

    // ─────────────────────────────────────────────── tracker

    private fun tracker() = ClipboardSwipeTracker(touchSlop = slop, flingVelocity = fling)

    @Test
    fun trackerReportsHorizontalIntentExactlyOnceAndThenFollowsTheFinger() {
        val t = tracker()
        t.down(100f, 100f)
        assertThat(t.move(110f, 101f)).isFalse()  // inside the slop
        assertThat(t.axis).isEqualTo(Axis.UNDECIDED)
        assertThat(t.move(130f, 102f)).isTrue()   // becomes horizontal: claim the gesture now
        assertThat(t.axis).isEqualTo(Axis.HORIZONTAL)
        assertThat(t.dx).isEqualTo(30f)
        assertThat(t.move(160f, 140f)).isFalse()  // already claimed; vertical drift is ignored
        assertThat(t.axis).isEqualTo(Axis.HORIZONTAL)
        assertThat(t.dx).isEqualTo(60f)
    }

    @Test
    fun aGestureClassifiedVerticalNeverBecomesASwipe() {
        val t = tracker()
        t.down(0f, 0f)
        assertThat(t.move(2f, 30f)).isFalse()
        assertThat(t.axis).isEqualTo(Axis.VERTICAL)
        assertThat(t.move(400f, 31f)).isFalse()
        assertThat(t.dx).isEqualTo(0f)
        assertThat(t.release(velocityX = 5_000f, rowWidth = width)).isFalse()
    }

    @Test
    fun releaseCommitsPastTheThresholdAndResetsTheTracker() {
        val t = tracker()
        t.down(0f, 0f)
        t.move(-20f, 0f)
        t.move(-400f, 5f)
        assertThat(t.release(velocityX = 0f, rowWidth = width)).isTrue()
        assertThat(t.active).isFalse()
        assertThat(t.axis).isEqualTo(Axis.UNDECIDED)
        assertThat(t.dx).isEqualTo(0f)
    }

    @Test
    fun releaseShortOfTheThresholdSpringsBackUnlessFlung() {
        val t = tracker()
        t.down(0f, 0f)
        t.move(100f, 0f)
        assertThat(t.release(velocityX = 0f, rowWidth = width)).isFalse()

        t.down(0f, 0f)
        t.move(100f, 0f)
        assertThat(t.release(velocityX = 1_200f, rowWidth = width)).isTrue()
    }

    @Test
    fun aTapNeverCommitsAndCancelAbandonsTheGesture() {
        val t = tracker()
        t.down(50f, 50f)
        assertThat(t.release(velocityX = 0f, rowWidth = width)).isFalse()

        t.down(0f, 0f)
        t.move(500f, 0f)
        t.cancel()
        assertThat(t.active).isFalse()
        assertThat(t.release(velocityX = 0f, rowWidth = width)).isFalse()
    }

    @Test
    fun movesWithoutADownAreIgnored() {
        val t = tracker()
        assertThat(t.move(500f, 0f)).isFalse()
        assertThat(t.axis).isEqualTo(Axis.UNDECIDED)
    }
}
