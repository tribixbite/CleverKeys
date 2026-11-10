package tribixbite.cleverkeys.theme

import kotlin.math.PI
import kotlin.math.sin

/**
 * The optional motion of a look's backdrop (Appearance → "Animated scenes",
 * `look_backdrop_motion`, off by default; maintainer 2026-11-10: "move / shimmer / float gently,
 * ethereally, with negligible overhead … time out with inactivity so the phone can idle"): the
 * CleverKeys Aurora curtains and the nature scenes.
 *
 * Nothing is re-rendered while it moves. For the aurora, the stream and the snow
 * [BackdropDrawable] keeps two pre-rendered images of the painting (curtains with other rays and
 * a little more light, or the scene a moment later), cross-fades between them and drifts each
 * sideways ([MotionStyle]), and dims three groups of stars in turn; the other scenes move their
 * own layers ([SceneMotion]). A frame is a few bitmap blits, the glows and the particles;
 * `Keyboard2View` replays its keys from a cached display list on these frames, so the keys are
 * not redrawn either.
 *
 * Pure JVM: `AuroraMotionTest` pins the ranges, the continuity and the idle timeout.
 */
object AuroraMotion {

    /**
     * Time between frames of the soft cross-fading scenes (stream, snow): 8 per second. Each
     * frame costs the IME window's fixed per-frame overhead (about 1 ms on the main thread and
     * 1.5 ms of draw commands on the Pixel 8 Pro, 2026-11-10), so the rate is the main lever; the
     * steps here stay a fraction of a dp. The aurora runs at [AURORA_FRAME_MS], the sharp
     * swaying scenes at their [SceneKind.frameMs].
     */
    const val FRAME_MS = 125L

    /**
     * The aurora's frames: 10 per second, for its livelier motion (maintainer 2026-11-10: "a bit
     * more vibrant … aurora is barely perceptible").
     */
    const val AURORA_FRAME_MS = 100L

    /** Moving stops this long after the last touch or the keyboard showing. */
    const val IDLE_MS = 20_000L

    /** Speeding up from rest and slowing down to it each take this long (no jolt either way). */
    const val EASE_MS = 2_500L

    /** The widest sideways drift of a [MotionStyle.GENTLE] image, dp. */
    const val MAX_DRIFT_DP = 5f


    /**
     * Stars twinkle in this many groups (star index modulo the count), each on its own cycle:
     * five, so neighbouring stars rarely brighten together and the sky shimmers (three read as
     * a slow pulse; maintainer 2026-11-10: "add the shimmering stars back").
     */
    const val STAR_GROUPS = 5

    /** Each star group's twinkle period, seconds; unrelated, so the groups never fall into step. */
    private val STAR_PERIODS_S = doubleArrayOf(3.1, 3.7, 4.3, 5.3, 6.1)

    /**
     * The second curtain image: other ray seeds, the folds moved along, and a little more light,
     * so the cross-fade reads as the curtains rippling and brightening, as an aurora does.
     */
    const val ALT_SEED_OFFSET = 1000L
    const val ALT_PHASE_SHIFT = 0.08f
    const val ALT_BRIGHTNESS = 1.3f

    /**
     * [v] folded into 0 until [size]: a drifting particle group's offset. The group is drawn in
     * four copies one view-size apart, so any offset in that range covers the whole view.
     */
    fun wrap(v: Float, size: Int): Float {
        if (size <= 0) return 0f
        val m = v % size
        return if (m < 0) m + size else m
    }

    /** The curtains of the second image: other rays, the folds moved along, more light. */
    fun alternate(ribbons: List<AuroraRibbon>): List<AuroraRibbon> =
        ribbons.map {
            it.copy(
                seed = it.seed + ALT_SEED_OFFSET, phase = it.phase + ALT_PHASE_SHIFT,
                edgeColor = brighter(it.edgeColor), topColor = brighter(it.topColor),
                edgeColorEnd = it.edgeColorEnd?.let(::brighter), topColorEnd = it.topColorEnd?.let(::brighter),
            )
        }

    /** [argb] with its alpha (how much light it adds) raised by [ALT_BRIGHTNESS]. */
    private fun brighter(argb: Int): Int {
        val a = (((argb ushr 24) and 0xFF) * ALT_BRIGHTNESS).toInt().coerceAtMost(0xFF)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * The motion at [seconds] of motion time (which only advances while moving, so a resumed
     * backdrop carries on from where it rested) in [style], as the user tuned it ([tuning]:
     * [SceneMotionTuning.intensity] scales the drift and the cross-fade, [SceneMotionTuning.twinkle]
     * how far the stars dim; its speed is the clock's). Periods are unrelated, so the pattern
     * never visibly repeats.
     */
    fun frame(seconds: Double, style: MotionStyle = MotionStyle.GENTLE, tuning: SceneMotionTuning = SceneMotionTuning.DEFAULT): AuroraMotionFrame {
        fun wave(period: Double, phase: Double) = sin(2 * PI * seconds / period + phase)
        // Two cycles summed keep the cross-fade from beating at one obvious rhythm. Below full
        // intensity it sweeps less of the way to the second image (none at 0: the still picture).
        val crossfade = ((0.5 + 0.5 * (0.6 * wave(style.fadePeriodS, 0.0) + 0.4 * wave(style.fadePeriodS * 1.44, 1.3)))
            .toFloat() * tuning.intensity.coerceAtMost(1f)).coerceIn(0f, 1f)
        val low = 1f - tuning.twinkle
        val stars = FloatArray(STAR_GROUPS) { i ->
            val lift = 0.5 + 0.5 * wave(STAR_PERIODS_S[i % STAR_PERIODS_S.size], i * 2.1)
            (low + (1f - low) * lift.toFloat()).coerceIn(low, 1f)
        }
        val drift = style.driftDp * tuning.intensity
        return AuroraMotionFrame(
            crossfade = crossfade,
            driftADp = (drift * wave(style.driftPeriodS, 0.0)).toFloat(),
            driftBDp = (-0.8f * drift * wave(style.driftPeriodS * 1.42, 0.7)).toFloat(),
            starAlpha = stars,
        )
    }
}

/**
 * The user's tuning of every moving backdrop (Appearance → Animated scenes; maintainer
 * 2026-11-10: "control all dynamic scene settings … speed, variation intensity,
 * twinkling/shimmer, crispness"). One set for all scenes: each scene keeps its own character and
 * these scale it.
 *
 * @property speed how fast motion time runs, 1 = as designed (`look_motion_speed`, 25–200 %).
 * @property intensity how far things move: the curtains' and water's drift and cross-fade, the
 *   bend of grass and branches, the run of the waves (`look_motion_intensity`, 0–200 %).
 * @property twinkle how far stars, glints and particles dim as they twinkle, 0 = steady
 *   (`look_motion_twinkle`, 0–100 %).
 * @property crisp paint the soft backdrops (aurora, stream, snow) at full resolution instead of
 *   half (`look_scene_crisp`): finer rays and ripples for four times the pixels.
 */
data class SceneMotionTuning(
    val speed: Float = 1f,
    val intensity: Float = 1f,
    val twinkle: Float = DEFAULT_TWINKLE,
    val crisp: Boolean = false,
) {
    companion object {
        /** The twinkle depth as designed: a star dims to 45 % of its brightness. */
        const val DEFAULT_TWINKLE = 0.55f

        val DEFAULT = SceneMotionTuning()

        /**
         * From the settings' percentages, clamped to their ranges (a hand-edited or old backup
         * can hold anything).
         */
        fun fromPercent(speed: Int, intensity: Int, twinkle: Int, crisp: Boolean) = SceneMotionTuning(
            speed = speed.coerceIn(SPEED_PERCENT) / 100f,
            intensity = intensity.coerceIn(INTENSITY_PERCENT) / 100f,
            twinkle = twinkle.coerceIn(TWINKLE_PERCENT) / 100f,
            crisp = crisp,
        )

        /** The settings' ranges, percent (`SettingsRanges` hands them to the sliders and the backup check). */
        val SPEED_PERCENT: IntRange = 25..200
        val INTENSITY_PERCENT: IntRange = 0..200
        val TWINKLE_PERCENT: IntRange = 0..100

        /** The settings' defaults, percent: the motion as designed. */
        const val DEFAULT_SPEED_PERCENT = 100
        const val DEFAULT_INTENSITY_PERCENT = 100
        const val DEFAULT_TWINKLE_PERCENT = 55
    }
}

/**
 * How a cross-fading backdrop moves: the widest sideways drift of its images and the periods of
 * the cross-fade and the drift.
 */
class MotionStyle(val driftDp: Float, val fadePeriodS: Double, val driftPeriodS: Double) {
    /**
     * Each image is drawn this much wider than the view on both sides at [intensity], so a drift
     * never uncovers its edge.
     */
    fun marginDp(intensity: Float = 1f): Float = driftDp * intensity.coerceAtLeast(0f) + 1f

    companion object {
        /** The stream and the snow: a slow cross-fade, a drift of a few dp. */
        val GENTLE = MotionStyle(AuroraMotion.MAX_DRIFT_DP, fadePeriodS = 9.0, driftPeriodS = 26.0)

        /**
         * The aurora: curtains that visibly ripple, brighten and sway (maintainer 2026-11-10:
         * "barely perceptible" before). About a dp per frame at its frame rate, still soft.
         */
        val AURORA = MotionStyle(driftDp = 12f, fadePeriodS = 5.5, driftPeriodS = 14.0)
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
        /** The still backdrop: the first image only, undrifted, every star at full brightness. */
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

    /**
     * Motion ms per real ms at full speed ([SceneMotionTuning.speed]). Applied to each step, so
     * changing it changes the pace from here on without a jump.
     */
    var rate: Float = 1f

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
        motionMs += dt * speed.toDouble() * rate.coerceAtLeast(0f)
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
