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

    /**
     * Moving stops this long after the last touch or the keyboard showing, by default; the user
     * sets it (`look_motion_idle_s`, [SceneMotionTuning.idleMs]).
     */
    const val IDLE_MS = 20_000L

    /** Speeding up from rest and slowing down to it each take this long (no jolt either way). */
    const val EASE_MS = 2_500L

    /**
     * Typing holds the backdrop still: a key press stops the motion and it starts again this
     * long after the last one (maintainer 2026-11-11: "resume after 1s of no typing"). While
     * typing the eyes are on the keys and the text; frames there were the whole measured cost
     * (+217 mW on the Pixel 8 Pro with the aurora moving under continuous typing). The default;
     * the user sets it (`look_motion_resume_ms`, [SceneMotionTuning.resumeMs]; 0 = no hold).
     */
    const val TYPING_HOLD_MS = 1_000L

    /**
     * Slowing to a stop at a key press takes this long: short enough that a typing burst costs
     * two or three frames, long enough that the stop does not jolt.
     */
    const val HOLD_EASE_MS = 250L

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
 * @property resumeMs a key press or swipe holds the motion still until this long after the last
 *   one; 0 = typing never holds it (`look_motion_resume_ms`; maintainer 2026-11-11: "make number
 *   of seconds after last key press/swipe before animation … user settings").
 * @property idleMs the motion stops this long after the last touch or the keyboard showing
 *   (`look_motion_idle_s`, stored in seconds).
 * @property fps frames per second while moving, for every scene; 0 = each scene's own rate
 *   ([SceneKind.frameMs], the aurora's [AuroraMotion.AURORA_FRAME_MS]) (`look_motion_fps`;
 *   maintainer 2026-11-11, after the Pixel measured ~9.5 mJ of compute per frame for every scene:
 *   the frame rate is what the motion costs).
 * @property overlay let the display hardware blend the aurora's curtains (`look_scene_overlay`,
 *   [SceneOverlay]): no window redraw per frame where the device supports it.
 * @property trueBlack paint the scenes as night versions of themselves ([SceneTrueBlack]): broad
 *   areas pure black, light, detail and colour kept (`look_scene_true_black`; OLED pixels off).
 */
data class SceneMotionTuning(
    val speed: Float = 1f,
    val intensity: Float = 1f,
    val twinkle: Float = DEFAULT_TWINKLE,
    val crisp: Boolean = false,
    val resumeMs: Long = AuroraMotion.TYPING_HOLD_MS,
    val idleMs: Long = AuroraMotion.IDLE_MS,
    val fps: Int = DEFAULT_FPS,
    val overlay: Boolean = DEFAULT_OVERLAY,
    val trueBlack: Boolean = DEFAULT_TRUE_BLACK,
) {
    /** Time between frames for a scene whose own is [sceneFrameMs]: the user's rate, or the scene's. */
    fun frameMs(sceneFrameMs: Long): Long = if (fps > 0) 1000L / fps else sceneFrameMs

    companion object {
        /** The twinkle depth as designed: a star dims to 45 % of its brightness. */
        const val DEFAULT_TWINKLE = 0.55f

        val DEFAULT = SceneMotionTuning()

        /**
         * From the settings' percentages, clamped to their ranges (a hand-edited or old backup
         * can hold anything).
         */
        fun fromPercent(
            speed: Int,
            intensity: Int,
            twinkle: Int,
            crisp: Boolean,
            resumeMs: Int = DEFAULT_RESUME_MS,
            idleSeconds: Int = DEFAULT_IDLE_SECONDS,
            fps: Int = DEFAULT_FPS,
            overlay: Boolean = DEFAULT_OVERLAY,
            trueBlack: Boolean = DEFAULT_TRUE_BLACK,
        ) = SceneMotionTuning(
            speed = speed.coerceIn(SPEED_PERCENT) / 100f,
            intensity = intensity.coerceIn(INTENSITY_PERCENT) / 100f,
            twinkle = twinkle.coerceIn(TWINKLE_PERCENT) / 100f,
            crisp = crisp,
            resumeMs = resumeMs.coerceIn(RESUME_MS).toLong(),
            idleMs = idleSeconds.coerceIn(IDLE_SECONDS) * 1000L,
            fps = fps.coerceIn(FPS),
            overlay = overlay,
            trueBlack = trueBlack,
        )

        /** The settings' ranges, percent (`SettingsRanges` hands them to the sliders and the backup check). */
        val SPEED_PERCENT: IntRange = 25..200
        val INTENSITY_PERCENT: IntRange = 0..200
        val TWINKLE_PERCENT: IntRange = 0..100
        /** Pause after the last key press or swipe, ms (the slider steps a quarter second). */
        val RESUME_MS: IntRange = 0..10_000
        /** Moving after the last touch, seconds (the slider steps five). */
        val IDLE_SECONDS: IntRange = 5..600
        /** Frames per second while moving; 0 = each scene's own rate. */
        val FPS: IntRange = 0..15

        /** The settings' defaults, percent: the motion as designed. */
        const val DEFAULT_SPEED_PERCENT = 100
        const val DEFAULT_INTENSITY_PERCENT = 100
        const val DEFAULT_TWINKLE_PERCENT = 55
        const val DEFAULT_RESUME_MS = AuroraMotion.TYPING_HOLD_MS.toInt()
        const val DEFAULT_IDLE_SECONDS = (AuroraMotion.IDLE_MS / 1000).toInt()
        const val DEFAULT_FPS = 0
        const val DEFAULT_OVERLAY = true
        const val DEFAULT_TRUE_BLACK = false
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
 * [easeMs] again). A key press ([touch]) also holds it: it slows to a stop over [holdEaseMs] and
 * stays still until [holdMs] after the last press, then speeds up again. Times are milliseconds
 * on any monotonic clock. A gap between [advance] calls longer than [maxStepMs] (the keyboard was
 * hidden, the device slept) counts as [maxStepMs], so the aurora never jumps.
 */
class AuroraMotionClock(
    /** The user's idle timeout ([SceneMotionTuning.idleMs]); a change applies from now on. */
    var idleMs: Long = AuroraMotion.IDLE_MS,
    private val easeMs: Long = AuroraMotion.EASE_MS,
    private val maxStepMs: Long = 250L,
    /** The user's typing pause ([SceneMotionTuning.resumeMs]); 0 = key presses never hold. */
    var holdMs: Long = AuroraMotion.TYPING_HOLD_MS,
    private val holdEaseMs: Long = AuroraMotion.HOLD_EASE_MS,
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
    private var lastTouch = NONE

    /** The user is here: move (again) for the next [idleMs]. */
    fun wake(now: Long) {
        lastWake = now
    }

    /**
     * A key press: the user is here (as [wake]) and typing, so hold still until [holdMs] after
     * the last press.
     */
    fun touch(now: Long) {
        lastWake = now
        lastTouch = now
    }

    /** Whether a recent key press holds the motion at [now]. */
    fun isHeld(now: Long): Boolean = lastTouch != NONE && now - lastTouch < holdMs

    /**
     * When the hold of the last key press ends (an uptime ms); only meaningful while [isHeld].
     * The frame loop sleeps until then instead of ticking at its frame rate.
     */
    val holdEndsAt: Long get() = if (lastTouch == NONE) Long.MIN_VALUE else lastTouch + holdMs

    /**
     * Moves the clock to [now]. Returns whether it is still moving or about to; false = at rest,
     * and nothing changes until the next [wake]. A held clock that has slowed to a stop returns
     * false too: there is nothing to draw until [holdEndsAt].
     */
    fun advance(now: Long): Boolean {
        val dt = if (lastAdvance == NONE) 0L else (now - lastAdvance).coerceIn(0L, maxStepMs)
        lastAdvance = now
        val awake = lastWake != NONE && now - lastWake < idleMs
        val held = isHeld(now)
        // Speed eases linearly, so position eases quadratically: no visible start or stop.
        speed = when {
            held -> (speed - (if (holdEaseMs <= 0) 1f else dt.toFloat() / holdEaseMs)).coerceAtLeast(0f)
            awake -> (speed + (if (easeMs <= 0) 1f else dt.toFloat() / easeMs)).coerceAtMost(1f)
            else -> (speed - (if (easeMs <= 0) 1f else dt.toFloat() / easeMs)).coerceAtLeast(0f)
        }
        motionMs += dt * speed.toDouble() * rate.coerceAtLeast(0f)
        return if (held) speed > 0f else awake || speed > 0f
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

/**
 * Skips motion frames that would show nothing new. A frame's visible state is a list of numbers
 * already in visible steps (positions in half pixels, alphas in steps of [AuroraMotion]'s
 * smallest visible change); a frame is drawn only once some number has moved a whole step from
 * what is on screen. The frame rate is the worst case (the fastest moment of a motion); a sine's
 * slow stretches need far fewer frames, and every pushed frame costs the display pipeline the
 * same (Pixel 8 Pro rails, 2026-11-10). Invisible by construction: what is on screen is never
 * more than a step behind.
 */
class VisibleChangeGate {
    private var shown = FloatArray(0)
    private var shownCount = -1

    /** Forget what is on screen: the next [changed] says yes (a new size, a repaint). */
    fun reset() {
        shownCount = -1
    }

    /**
     * Whether the first [count] values of [state] differ from those on screen by a whole step
     * somewhere; when they do, they become what is on screen.
     */
    fun changed(state: FloatArray, count: Int): Boolean {
        var differs = count != shownCount
        if (!differs) for (i in 0 until count) {
            if (kotlin.math.abs(state[i] - shown[i]) >= 1f) { differs = true; break }
        }
        if (!differs) return false
        if (shown.size < count) shown = FloatArray(count)
        state.copyInto(shown, 0, 0, count)
        shownCount = count
        return true
    }

    companion object {
        /** View pixels per step of a position: half a pixel, which antialiased edges just show. */
        const val POSITION_STEP_PX = 0.5f

        /** 0..255 alpha levels per step: two, below what a fade over a busy painting shows. */
        const val ALPHA_STEP = 2f
    }
}

/**
 * Whether the phone's state lets a backdrop move: never with battery saver, animations switched
 * off, the phone warm (thermal status [THERMAL_LIMIT] or above) or the battery at
 * [LOW_BATTERY_PERCENT] or less and not charging. Pure, so the policy is tested off-device; the
 * drawable asks it to start and again every [RECHECK_MS] while moving.
 */
object MotionAllowance {
    /** `PowerManager.THERMAL_STATUS_MODERATE`: the phone is warm and starts limiting itself. */
    const val THERMAL_LIMIT = 2

    /** A battery this low keeps the backdrop still unless charging. */
    const val LOW_BATTERY_PERCENT = 15

    /** While moving, the phone's state is asked again this often (each ask is a few binder calls). */
    const val RECHECK_MS = 5_000L

    /** [batteryPercent] < 0 = unknown (no battery, or the property is not reported): not limiting. */
    fun allows(
        powerSave: Boolean,
        animatorsEnabled: Boolean,
        thermalStatus: Int,
        batteryPercent: Int,
        charging: Boolean,
    ): Boolean {
        if (powerSave || !animatorsEnabled) return false
        if (thermalStatus >= THERMAL_LIMIT) return false
        if (batteryPercent in 0..LOW_BATTERY_PERCENT && !charging) return false
        return true
    }
}
