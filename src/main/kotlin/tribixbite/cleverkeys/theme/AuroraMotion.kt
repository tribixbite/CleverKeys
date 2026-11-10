package tribixbite.cleverkeys.theme

import kotlin.math.PI
import kotlin.math.sin

/**
 * The aurora's optional motion (Appearance → "Moving aurora", `look_aurora_motion`, off by
 * default; maintainer 2026-11-10: "move / shimmer / float gently, ethereally, with negligible
 * overhead … time out with inactivity so the phone can idle").
 *
 * Nothing is re-rendered while it moves. [AuroraBackdropDrawable] keeps two pre-rendered curtain
 * bitmaps (the second with other ray seeds and a slightly shifted wave), cross-fades between them
 * and drifts each sideways by a few dp, and dims three groups of stars in turn. A frame is two
 * bitmap blits, the glows and the stars; `Keyboard2View` replays its keys from a cached display
 * list on these frames, so the keys are not redrawn either. Frames come at [FRAME_MS] (8 per
 * second): every movement here is slower than 1.5 dp per second, so more would be waste.
 *
 * Pure JVM: `AuroraMotionTest` pins the ranges, the continuity and the idle timeout.
 */
object AuroraMotion {

    /**
     * Time between frames while moving: 8 per second. Each frame costs the IME window's fixed
     * per-frame overhead (about 1 ms on the main thread and 1.5 ms of draw commands on the
     * Pixel 8 Pro, 2026-11-10), so the rate is the only lever left; the slowest steps here are
     * a fifth of a dp and 5 % of the cross-fade per frame, below what reads as a step.
     */
    const val FRAME_MS = 125L

    /** Moving stops this long after the last touch or the keyboard showing. */
    const val IDLE_MS = 20_000L

    /** Speeding up from rest and slowing down to it each take this long (no jolt either way). */
    const val EASE_MS = 2_500L

    /** The widest sideways drift of a curtain layer, dp. */
    const val MAX_DRIFT_DP = 5f

    /**
     * Each curtain bitmap is drawn this much wider than the view on both sides, so a drift never
     * uncovers its edge. Must be at least [MAX_DRIFT_DP].
     */
    const val DRIFT_MARGIN_DP = 6f

    /** Stars twinkle in this many groups (star index modulo the count), each on its own cycle. */
    const val STAR_GROUPS = 3

    /** Lowest brightness a twinkling star group dims to, as a share of its own. */
    const val STAR_MIN = 0.45f

    /** Each star group's twinkle period, seconds. */
    private val STAR_PERIODS_S = doubleArrayOf(5.3, 7.1, 8.9)

    /** Differing ray seeds and a slight wave shift: the second curtain image cross-fades in. */
    const val ALT_SEED_OFFSET = 1000L
    const val ALT_PHASE_SHIFT = 0.03f

    /** The curtains of the second image: same light, other rays, the folds slightly moved. */
    fun alternate(ribbons: List<AuroraRibbon>): List<AuroraRibbon> =
        ribbons.map { it.copy(seed = it.seed + ALT_SEED_OFFSET, phase = it.phase + ALT_PHASE_SHIFT) }

    /**
     * The motion at [seconds] of motion time (which only advances while moving, so a resumed
     * aurora carries on from where it rested). Periods are unrelated (9, 13, 26, 37 s and the
     * stars' 5.3, 7.1, 8.9 s), so the pattern never visibly repeats.
     */
    fun frame(seconds: Double): AuroraMotionFrame {
        fun wave(period: Double, phase: Double) = sin(2 * PI * seconds / period + phase)
        // Two cycles summed keep the cross-fade from beating at one obvious rhythm.
        val crossfade = (0.5 + 0.5 * (0.6 * wave(9.0, 0.0) + 0.4 * wave(13.0, 1.3))).toFloat().coerceIn(0f, 1f)
        val stars = FloatArray(STAR_GROUPS) { i ->
            val lift = 0.5 + 0.5 * wave(STAR_PERIODS_S[i % STAR_PERIODS_S.size], i * 2.1)
            (STAR_MIN + (1f - STAR_MIN) * lift.toFloat()).coerceIn(STAR_MIN, 1f)
        }
        return AuroraMotionFrame(
            crossfade = crossfade,
            driftADp = (MAX_DRIFT_DP * wave(26.0, 0.0)).toFloat(),
            driftBDp = (-0.8f * MAX_DRIFT_DP * wave(37.0, 0.7)).toFloat(),
            starAlpha = stars,
        )
    }
}

/**
 * One frame of [AuroraMotion]: how much of the second curtain image shows over the first (0..1),
 * the two images' sideways drifts in dp, and each star group's brightness share.
 */
class AuroraMotionFrame(
    val crossfade: Float,
    val driftADp: Float,
    val driftBDp: Float,
    val starAlpha: FloatArray,
) {
    companion object {
        /** The still aurora: the first image only, undrifted, every star at full brightness. */
        val REST = AuroraMotionFrame(0f, 0f, 0f, FloatArray(AuroraMotion.STAR_GROUPS) { 1f })
    }
}

/**
 * Motion time with an idle timeout: it advances at full speed for [idleMs] after the last
 * [wake], then slows to a stop over [easeMs] and stays there until woken (and speeds up over
 * [easeMs] again). Times are milliseconds on any monotonic clock. A gap between [advance] calls
 * longer than [maxStepMs] (the keyboard was hidden, the device slept) counts as [maxStepMs], so
 * the aurora never jumps.
 */
class AuroraMotionClock(
    private val idleMs: Long = AuroraMotion.IDLE_MS,
    private val easeMs: Long = AuroraMotion.EASE_MS,
    private val maxStepMs: Long = 250L,
) {
    /** Accumulated motion time, ms. */
    var motionMs: Double = 0.0
        private set

    /** Current speed, 0 (at rest) to 1. */
    var speed: Float = 0f
        private set

    private var lastAdvance = NONE
    private var lastWake = NONE

    /** The user is here: move (again) for the next [idleMs]. */
    fun wake(now: Long) {
        lastWake = now
    }

    /**
     * Moves the clock to [now]. Returns whether it is still moving or about to; false = at rest,
     * and nothing changes until the next [wake].
     */
    fun advance(now: Long): Boolean {
        val dt = if (lastAdvance == NONE) 0L else (now - lastAdvance).coerceIn(0L, maxStepMs)
        lastAdvance = now
        val awake = lastWake != NONE && now - lastWake < idleMs
        val step = if (easeMs <= 0) 1f else dt.toFloat() / easeMs
        // Speed eases linearly, so position eases quadratically: no visible start or stop.
        speed = if (awake) (speed + step).coerceAtMost(1f) else (speed - step).coerceAtLeast(0f)
        motionMs += dt * speed.toDouble()
        return awake || speed > 0f
    }

    /** Stops at once (keyboard hidden): the next [advance] starts from rest without a jump. */
    fun rest() {
        speed = 0f
        lastAdvance = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
