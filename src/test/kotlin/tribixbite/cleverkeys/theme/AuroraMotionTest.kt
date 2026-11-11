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
        for (style in listOf(MotionStyle.GENTLE, MotionStyle.AURORA)) {
            for (intensity in listOf(0f, 1f, 2f)) {
                val tuning = SceneMotionTuning(intensity = intensity)
                assertThat(style.marginDp(intensity)).isAtLeast(style.driftDp * intensity)
                var t = 0.0
                while (t < 600.0) {
                    val f = AuroraMotion.frame(t, style, tuning)
                    assertThat(f.crossfade).isIn(Range.closed(0f, 1f))
                    assertThat(abs(f.driftADp)).isAtMost(style.driftDp * intensity)
                    assertThat(abs(f.driftBDp)).isAtMost(style.driftDp * intensity)
                    assertThat(f.starAlpha.size).isEqualTo(AuroraMotion.STAR_GROUPS)
                    for (a in f.starAlpha) assertThat(a).isIn(Range.closed(1f - tuning.twinkle, 1f))
                    t += 0.37
                }
            }
        }
    }

    /**
     * "Gently": between two frames at the frame rate nothing visibly jumps — a sliver of a dp for
     * the stream and the snow, about half a dp for the livelier aurora at its faster frame rate.
     */
    @Test
    fun consecutiveFramesMoveByASliverOfADp() {
        for ((style, frameMs, maxDrift, maxFade) in listOf(
            Quad(MotionStyle.GENTLE, AuroraMotion.FRAME_MS, 0.2f, 0.05f),
            Quad(MotionStyle.AURORA, AuroraMotion.AURORA_FRAME_MS, 0.6f, 0.06f),
        )) {
            val dt = frameMs / 1000.0
            var t = 0.0
            while (t < 300.0) {
                val a = AuroraMotion.frame(t, style)
                val b = AuroraMotion.frame(t + dt, style)
                assertWithMessage("drift per frame at $t").that(abs(b.driftADp - a.driftADp)).isLessThan(maxDrift)
                assertThat(abs(b.driftBDp - a.driftBDp)).isLessThan(maxDrift)
                assertWithMessage("cross-fade per frame").that(abs(b.crossfade - a.crossfade)).isLessThan(maxFade)
                for (i in a.starAlpha.indices) assertThat(abs(b.starAlpha[i] - a.starAlpha[i])).isLessThan(0.08f)
                t += 0.5
            }
        }
    }

    private data class Quad(val style: MotionStyle, val frameMs: Long, val maxDrift: Float, val maxFade: Float)

    /**
     * Maintainer 2026-11-10: "aurora is barely perceptible". Its motion sways well over twice as
     * far as the gentle scenes' and cycles faster.
     */
    @Test
    fun theAuroraMovesVisiblyMoreThanTheGentleScenes() {
        assertThat(MotionStyle.AURORA.driftDp).isAtLeast(2 * MotionStyle.GENTLE.driftDp)
        assertThat(MotionStyle.AURORA.fadePeriodS).isLessThan(MotionStyle.GENTLE.fadePeriodS)
        assertThat(MotionStyle.AURORA.driftPeriodS).isLessThan(MotionStyle.GENTLE.driftPeriodS)
    }

    @Test
    fun itActuallyMovesAndTheStillFrameIsTheFirstImage() {
        val samples = (0 until 400).map { AuroraMotion.frame(it * 0.25) }
        assertWithMessage("the cross-fade sweeps most of its range")
            .that(samples.maxOf { it.crossfade } - samples.minOf { it.crossfade }).isGreaterThan(0.7f)
        assertThat(samples.maxOf { it.driftADp } - samples.minOf { it.driftADp }).isGreaterThan(8f)
        val rest = AuroraMotionFrame.REST
        assertThat(rest.crossfade).isEqualTo(0f)
        assertThat(rest.starAlpha.toList()).containsExactlyElementsIn(List(AuroraMotion.STAR_GROUPS) { 1f })
    }

    /** The second image: other rays, folds moved along, more light (the cross-fade shimmers). */
    @Test
    fun theSecondImageHasOtherRaysAndMoreLight() {
        val ribbons = KeyboardLooks.CLEVERKEYS_AURORA.ribbons
        val alt = AuroraMotion.alternate(ribbons)
        assertThat(alt).hasSize(ribbons.size)
        for ((a, b) in ribbons.zip(alt)) {
            assertThat(b.seed).isNotEqualTo(a.seed)
            assertThat(b.edgeColor and 0xFFFFFF).isEqualTo(a.edgeColor and 0xFFFFFF)
            assertWithMessage("brighter").that(b.edgeColor ushr 24).isGreaterThan(a.edgeColor ushr 24)
            assertThat(b.topColor and 0xFFFFFF).isEqualTo(a.topColor and 0xFFFFFF)
            assertThat(b.edgeY).isEqualTo(a.edgeY)
            assertThat(abs(b.phase - a.phase)).isAtMost(0.1f)
        }
        val base = 0xFF0A080E.toInt()
        assertWithMessage("a different picture")
            .that(AuroraRaster.render(base, alt, 120, 80, 0.5f))
            .isNotEqualTo(AuroraRaster.render(base, ribbons, 120, 80, 0.5f))
    }

    /**
     * The user's tuning (maintainer 2026-11-10: "control all dynamic scene settings … speed,
     * variation intensity, twinkling/shimmer, crispness"): intensity 0 holds the still picture,
     * twinkle 0 holds every star steady, and the speed changes the clock's pace without moving
     * where it is.
     */
    @Test
    fun theTuningScalesTheMotionWithoutAJump() {
        for (t in listOf(0.0, 3.3, 17.9)) {
            val still = AuroraMotion.frame(t, MotionStyle.AURORA, SceneMotionTuning(intensity = 0f, twinkle = 0f))
            assertThat(still.crossfade).isEqualTo(0f)
            assertThat(still.driftADp).isEqualTo(0f)
            assertThat(still.starAlpha.toList()).containsExactlyElementsIn(List(AuroraMotion.STAR_GROUPS) { 1f })
            val wide = AuroraMotion.frame(t, MotionStyle.AURORA, SceneMotionTuning(intensity = 2f))
            val usual = AuroraMotion.frame(t, MotionStyle.AURORA)
            assertThat(wide.driftADp).isWithin(1e-4f).of(2 * usual.driftADp)
        }
        val shimmer = (0 until 200).flatMap { AuroraMotion.frame(it * 0.2, MotionStyle.AURORA, SceneMotionTuning(twinkle = 1f)).starAlpha.toList() }
        assertWithMessage("full twinkle dims a star nearly out").that(shimmer.min()).isLessThan(0.05f)

        val clock = AuroraMotionClock(idleMs = 60_000, easeMs = 0, maxStepMs = 2_000)
        clock.wake(0); clock.advance(0)
        clock.advance(1_000)
        assertThat(clock.motionMs).isWithin(1e-6).of(1_000.0)
        clock.rate = 2f
        assertWithMessage("a new speed does not move the clock").that(clock.motionMs).isWithin(1e-6).of(1_000.0)
        clock.advance(1_100)
        assertThat(clock.motionMs).isWithin(1e-6).of(1_200.0)

        // The settings' percentages, clamped to the sliders' ranges.
        assertThat(SceneMotionTuning.fromPercent(100, 100, 55, false)).isEqualTo(SceneMotionTuning.DEFAULT)
        val wild = SceneMotionTuning.fromPercent(speed = 9_999, intensity = -5, twinkle = 400, crisp = true)
        assertThat(wild).isEqualTo(SceneMotionTuning(speed = 2f, intensity = 0f, twinkle = 1f, crisp = true))
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

    /**
     * Typing holds the motion (maintainer 2026-11-11: "resume after 1s of no typing"): a key
     * press slows it to a stop within the hold ease, no frames are asked for while presses keep
     * coming, and it moves again one second after the last press.
     */
    @Test
    fun typingHoldsTheMotionUntilASecondAfterTheLastPress() {
        val clock = AuroraMotionClock(idleMs = 20_000, easeMs = 1_000, maxStepMs = 250, holdMs = 1_000, holdEaseMs = 250)
        var now = 0L
        clock.wake(now)
        while (now < 2_000) { now += 100; clock.advance(now) }
        assertThat(clock.speed).isEqualTo(1f)
        // A burst of presses every 200 ms for three seconds.
        val burstEnd = now + 3_000
        var stoppedAt = -1L
        var movingWhileHeld = 0
        while (now < burstEnd) {
            if (now % 200 == 0L) clock.touch(now)
            now += 100
            val moving = clock.advance(now)
            if (clock.speed == 0f && stoppedAt < 0) stoppedAt = now
            if (stoppedAt >= 0 && moving) movingWhileHeld++
        }
        assertWithMessage("stopped within the hold ease").that(stoppedAt - 2_000).isAtMost(300L)
        assertWithMessage("no frames once stopped while typing").that(movingWhileHeld).isEqualTo(0)
        val lastPress = burstEnd - 200
        assertThat(clock.isHeld(lastPress + 999)).isTrue()
        assertThat(clock.holdEndsAt).isEqualTo(lastPress + 1_000)
        val held = clock.motionMs
        clock.advance(lastPress + 999)
        assertWithMessage("still while held").that(clock.motionMs).isEqualTo(held)
        assertThat(clock.isHeld(lastPress + 1_000)).isFalse()
        assertWithMessage("moving again after the hold").that(clock.advance(lastPress + 1_000)).isTrue()
        clock.advance(lastPress + 1_100)
        assertThat(clock.motionMs).isGreaterThan(held)
        assertWithMessage("it speeds up gently, no jump").that(clock.motionMs - held).isAtMost(30.0)
    }

    /** A held clock still rests after the idle time once typing stops. */
    @Test
    fun aHoldDoesNotKeepTheBackdropAwake() {
        val clock = AuroraMotionClock(idleMs = 5_000, easeMs = 500, maxStepMs = 250, holdMs = 1_000, holdEaseMs = 250)
        clock.touch(0)
        var now = 0L
        var last = true
        while (now < 10_000) { now += 100; last = clock.advance(now) }
        assertThat(last).isFalse()
        assertThat(clock.speed).isEqualTo(0f)
    }

    /** Only a whole visible step on screen draws a frame; what is shown is never a step behind. */
    @Test
    fun anUnchangedFrameIsSkipped() {
        val gate = VisibleChangeGate()
        val state = floatArrayOf(0f, 10f)
        assertWithMessage("the first frame is always drawn").that(gate.changed(state, 2)).isTrue()
        state[0] = 0.6f
        assertThat(gate.changed(state, 2)).isFalse()
        state[0] = 0.99f; state[1] = 9.2f
        assertThat(gate.changed(state, 2)).isFalse()
        state[0] = 1.0f
        assertThat(gate.changed(state, 2)).isTrue()
        state[0] = 1.5f
        assertWithMessage("compared with what is shown, not the last tick").that(gate.changed(state, 2)).isFalse()
        assertWithMessage("a different shape of state is a change").that(gate.changed(state, 1)).isTrue()
        gate.reset()
        assertThat(gate.changed(state, 1)).isTrue()
    }

    /**
     * The gate never lets the screen fall a whole step behind the motion. At the designed tuning
     * the aurora changes visibly every frame (its frame rate was set by its pace: measured
     * 2026-11-11, every scene drew 100 % of its frames), so the gate saves frames only where the
     * motion is slower: a tuning with no movement and no twinkle draws its first frame and then
     * nothing, where before it redrew the same picture ten times a second.
     */
    @Test
    fun theGateKeepsUpAndSkipsAStillTuning() {
        val density = 2.75f
        fun run(tuning: SceneMotionTuning): Pair<Int, Int> {
            val gate = VisibleChangeGate()
            val n = 3 + AuroraMotion.STAR_GROUPS
            val state = FloatArray(n)
            var shown = FloatArray(n)
            var drawn = 0; var ticks = 0
            var t = 0.0
            while (t < 120.0) {
                val f = AuroraMotion.frame(t, MotionStyle.AURORA, tuning)
                state[0] = f.driftADp * density / VisibleChangeGate.POSITION_STEP_PX
                state[1] = f.driftBDp * density / VisibleChangeGate.POSITION_STEP_PX
                state[2] = f.crossfade * 255f / VisibleChangeGate.ALPHA_STEP
                for (g in 0 until AuroraMotion.STAR_GROUPS) state[3 + g] = f.starAlpha[g] * 230f / VisibleChangeGate.ALPHA_STEP
                ticks++
                if (gate.changed(state, n)) { drawn++; shown = state.copyOf() }
                for (i in 0 until n) assertThat(abs(state[i] - shown[i])).isLessThan(1f)
                t += 0.1
            }
            return drawn to ticks
        }
        val (moving, ticks) = run(SceneMotionTuning.DEFAULT)
        assertThat(moving).isGreaterThan(ticks / 2)
        val (still, _) = run(SceneMotionTuning(intensity = 0f, twinkle = 0f))
        assertWithMessage("no movement, no twinkle: only the first frame").that(still).isEqualTo(1)
    }

    /** Battery saver, animations off, a warm phone and a low battery (not charging) keep it still. */
    @Test
    fun thePhonesStateDecidesWhetherItMoves() {
        fun allows(saver: Boolean = false, anim: Boolean = true, thermal: Int = 0, pct: Int = 80, charging: Boolean = false) =
            MotionAllowance.allows(saver, anim, thermal, pct, charging)
        assertThat(allows()).isTrue()
        assertThat(allows(saver = true)).isFalse()
        assertThat(allows(anim = false)).isFalse()
        assertThat(allows(thermal = 1)).isTrue()
        assertThat(allows(thermal = MotionAllowance.THERMAL_LIMIT)).isFalse()
        assertThat(allows(pct = MotionAllowance.LOW_BATTERY_PERCENT)).isFalse()
        assertThat(allows(pct = MotionAllowance.LOW_BATTERY_PERCENT, charging = true)).isTrue()
        assertThat(allows(pct = MotionAllowance.LOW_BATTERY_PERCENT + 1)).isTrue()
        assertWithMessage("an unknown battery level does not limit").that(allows(pct = -1)).isTrue()
    }
}

