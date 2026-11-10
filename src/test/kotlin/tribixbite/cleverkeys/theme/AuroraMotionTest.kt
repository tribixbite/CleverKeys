package tribixbite.cleverkeys.theme

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs

/**
 * The aurora's optional motion (maintainer 2026-11-10: gentle, negligible overhead, and it must
 * stop when the keyboard is idle so the phone can idle): ranges, continuity, the second image,
 * and the idle clock.
 */
class AuroraMotionTest {

    @Test
    fun everyFrameStaysInRangeAndADriftNeverUncoversAnEdge() {
        assertThat(AuroraMotion.DRIFT_MARGIN_DP).isAtLeast(AuroraMotion.MAX_DRIFT_DP)
        var t = 0.0
        while (t < 600.0) {
            val f = AuroraMotion.frame(t)
            assertThat(f.crossfade).isIn(Range.closed(0f, 1f))
            assertThat(abs(f.driftADp)).isAtMost(AuroraMotion.MAX_DRIFT_DP)
            assertThat(abs(f.driftBDp)).isAtMost(AuroraMotion.MAX_DRIFT_DP)
            assertThat(f.starAlpha.size).isEqualTo(AuroraMotion.STAR_GROUPS)
            for (a in f.starAlpha) assertThat(a).isIn(Range.closed(AuroraMotion.STAR_MIN, 1f))
            t += 0.37
        }
    }

    /** "Gently": between two frames at the frame rate nothing visibly jumps. */
    @Test
    fun consecutiveFramesMoveByASliverOfADp() {
        val dt = AuroraMotion.FRAME_MS / 1000.0
        var t = 0.0
        while (t < 300.0) {
            val a = AuroraMotion.frame(t)
            val b = AuroraMotion.frame(t + dt)
            assertWithMessage("drift per frame at $t").that(abs(b.driftADp - a.driftADp)).isLessThan(0.2f)
            assertThat(abs(b.driftBDp - a.driftBDp)).isLessThan(0.2f)
            assertWithMessage("cross-fade per frame").that(abs(b.crossfade - a.crossfade)).isLessThan(0.05f)
            for (i in a.starAlpha.indices) assertThat(abs(b.starAlpha[i] - a.starAlpha[i])).isLessThan(0.08f)
            t += 0.5
        }
    }

    @Test
    fun itActuallyMovesAndTheStillFrameIsTheFirstImage() {
        val samples = (0 until 400).map { AuroraMotion.frame(it * 0.25) }
        assertWithMessage("the cross-fade sweeps most of its range")
            .that(samples.maxOf { it.crossfade } - samples.minOf { it.crossfade }).isGreaterThan(0.7f)
        assertThat(samples.maxOf { it.driftADp } - samples.minOf { it.driftADp }).isGreaterThan(8f)
        val rest = AuroraMotionFrame.REST
        assertThat(rest.crossfade).isEqualTo(0f)
        assertThat(rest.starAlpha.toList()).containsExactly(1f, 1f, 1f)
    }

    @Test
    fun theSecondImageHasOtherRaysAndTheSameLight() {
        val ribbons = KeyboardLooks.CLEVERKEYS_AURORA.ribbons
        val alt = AuroraMotion.alternate(ribbons)
        assertThat(alt).hasSize(ribbons.size)
        for ((a, b) in ribbons.zip(alt)) {
            assertThat(b.seed).isNotEqualTo(a.seed)
            assertThat(b.edgeColor).isEqualTo(a.edgeColor)
            assertThat(b.topColor).isEqualTo(a.topColor)
            assertThat(b.edgeY).isEqualTo(a.edgeY)
            assertThat(abs(b.phase - a.phase)).isAtMost(0.05f)
        }
        val base = 0xFF0A080E.toInt()
        assertWithMessage("a different picture")
            .that(AuroraRaster.render(base, alt, 120, 80, 0.5f))
            .isNotEqualTo(AuroraRaster.render(base, ribbons, 120, 80, 0.5f))
    }

    @Test
    fun theClockRunsWhileTouchedThenEasesToRestAfterTheIdleTimeout() {
        val clock = AuroraMotionClock(idleMs = 20_000, easeMs = 2_500)
        var now = 1_000L
        assertWithMessage("never woken: at rest").that(clock.advance(now)).isFalse()
        clock.wake(now)
        val step = AuroraMotion.FRAME_MS
        // Speeds up over the ease, without a jump on the first frame.
        assertThat(clock.advance(now)).isTrue()
        assertThat(clock.speed).isEqualTo(0f)
        now += step; clock.advance(now)
        assertThat(clock.speed).isIn(Range.openClosed(0f, 0.05f))
        while (now < 1_000L + 19_000L) { now += step; assertThat(clock.advance(now)).isTrue() }
        assertThat(clock.speed).isEqualTo(1f)
        // Past the timeout it slows down over the ease, then stops.
        var frames = 0
        while (clock.advance(now)) { now += step; frames++; check(frames < 1_000) }
        assertThat(clock.speed).isEqualTo(0f)
        assertWithMessage("stops within the timeout plus the ease")
            .that(now - 1_000L).isAtMost(20_000L + 2_500L + 2 * step)
        val restingAt = clock.motionMs
        now += 60_000; clock.advance(now)
        assertWithMessage("at rest, motion time stands still").that(clock.motionMs).isEqualTo(restingAt)
        // A touch starts it again from where it rested.
        clock.wake(now)
        assertThat(clock.advance(now)).isTrue()
        now += step; clock.advance(now)
        assertThat(clock.motionMs).isGreaterThan(restingAt)
    }

    @Test
    fun aLongGapOrAHiddenKeyboardNeverJumps() {
        val clock = AuroraMotionClock(idleMs = 20_000, easeMs = 0, maxStepMs = 250)
        clock.wake(0); clock.advance(0)
        clock.advance(100)
        val before = clock.motionMs
        clock.advance(10_100) // e.g. the main thread was blocked
        assertThat(clock.motionMs - before).isAtMost(250.0)
        clock.rest()
        assertThat(clock.speed).isEqualTo(0f)
        val rested = clock.motionMs
        clock.wake(50_000)
        clock.advance(50_000)
        assertWithMessage("the first frame after rest() starts from zero elapsed").that(clock.motionMs).isEqualTo(rested)
    }

    /** A drifting particle group's offset always lands inside the view, from any motion time. */
    @Test
    fun aDriftWrapsIntoTheView() {
        assertThat(AuroraMotion.wrap(0f, 100)).isEqualTo(0f)
        assertThat(AuroraMotion.wrap(250f, 100)).isEqualTo(50f)
        assertThat(AuroraMotion.wrap(-30f, 100)).isEqualTo(70f)
        assertThat(AuroraMotion.wrap(42f, 0)).isEqualTo(0f)
        var v = -1000f
        while (v < 1000f) {
            assertThat(AuroraMotion.wrap(v, 97)).isIn(Range.closedOpen(0f, 97f))
            v += 13.7f
        }
    }
}

