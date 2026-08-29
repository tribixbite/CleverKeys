package tribixbite.cleverkeys.prefs

import tribixbite.cleverkeys.PercentOfKey

/**
 * Immutable read-model of the [tribixbite.cleverkeys.Config] fields the touch and draw
 * hot paths consume (ARC-072).
 *
 * ## Why this exists: the torn read
 *
 * `Config` holds 157 `@JvmField var`s and `Config.refresh()` rewrites them **in place**,
 * one assignment at a time, on whatever thread asked for the refresh — a settings change,
 * a fold-state change, a rotation, a theme broadcast. A gesture or a frame that is already
 * running does not stop for that: it keeps reading fields as they are rewritten, so it can
 * see `swipe_dist_px` from the old configuration and `short_gesture_max_distance` from the
 * new one and make a decision no configuration ever expressed. Nothing crashes; the gesture
 * is simply classified against a state that never existed.
 *
 * A snapshot removes the window rather than narrowing it. `Config` builds one at the end of
 * every refresh and publishes it through a single `@Volatile` reference; a consumer captures
 * that reference ONCE at the start of a unit of work (a gesture at pointer-down, a frame at
 * draw start) and reads every value it needs from the captured object. A refresh that lands
 * mid-gesture swaps the reference for the *next* gesture and cannot perturb the one in
 * flight. Cost: one small allocation per configuration change — never per touch event, never
 * per frame.
 *
 * ## What is in here
 *
 * Exactly the union of config fields read by the four hot-path files: `Gesture.kt`,
 * `GestureClassifier.kt`, `Pointers.kt` and `Keyboard2View.kt`. Not the other ~120 fields —
 * settings screens, the clipboard, the predictors and the langpack importer all read live
 * `Config` and are none of this model's business.
 *
 * Two deliberate exclusions from that mechanical union:
 *  - `Config.handler` (`IKeyEventHandler`): a callback into the IME, not configuration
 *    state. Freezing a dispatch target into a value object would be a bug waiting to
 *    happen, and it is read only on `Keyboard2View`'s side-effect paths, which keep their
 *    live `Config` reference by design.
 *  - `Config.isRuntimeTheme()`: a predicate over [themeName], not a stored field — it is
 *    reproduced here as a derived property so both sides compute it from one implementation.
 *
 * ## Field naming
 *
 * Names mirror `Config`'s verbatim, snake_case and camelCase inconsistencies included, so a
 * consumer migration is a mechanical `_config.x` → `snap.x` with zero rename risk. The
 * camelCase normalisation is a separate, later pass (R7) and is explicitly NOT done here.
 *
 * There are no default values on purpose: every field must be supplied, so adding a field to
 * the model breaks `Config.buildSnapshot()` at compile time instead of silently publishing a
 * placeholder into the hot path.
 */
data class ConfigSnapshot(
    // ---- Rotation gestures — Gesture.kt ----
    /** Angular travel (in 1/16 turns) required before a rotation gesture starts. */
    val circle_sensitivity: Int,

    // ---- Tap/swipe classification — GestureClassifier.kt, Pointers.kt ----
    /** Max press duration still counted as a tap, in ms. */
    val tap_duration_threshold: Long,

    // ---- Touch handling — Pointers.kt ----
    val double_tap_lock_shift: Boolean,
    val keyrepeat_enabled: Boolean,
    val keyrepeat_backspace_only: Boolean,
    val longPressTimeout: Long,
    val longPressInterval: Long,
    val selection_delete_vertical_speed: Float,
    val selection_delete_vertical_threshold: Int,
    val short_gestures_enabled: Boolean,
    val short_gesture_min_distance: PercentOfKey,
    val short_gesture_max_distance: PercentOfKey,
    /** Displacement (px) at which a press becomes a directional gesture. */
    val swipe_dist_px: Float,
    /** Slider step size in px. */
    val slide_step_px: Float,
    val swipe_typing_enabled: Boolean,
    /** Exponential-smoothing factor for the slider's speed estimate (0..1). */
    val slider_speed_smoothing: Float,
    /** Ceiling on the slider's speed multiplier. */
    val slider_speed_max: Float,

    // ---- Measure/draw — Keyboard2View.kt ----
    val marginTop: Float,
    val margin_bottom: Float,
    val margin_left: Float,
    val margin_right: Float,
    val keyPadding: Float,
    val labelTextSize: Float,
    val sublabelTextSize: Float,
    val secondary_label_size_scale: Float,
    val characterSize: Float,
    val keyboardOpacity: Int,
    val themeName: String,

    // ---- Swipe trail — Keyboard2View.kt (per-frame while a swipe is in flight) ----
    val swipe_trail_enabled: Boolean,
    /** One of `none|solid|glow|rainbow|fade|sparkle`. */
    val swipe_trail_effect: String,
    val swipe_trail_color: Int,
    /** Trail stroke width in dp. */
    val swipe_trail_width: Float,
    /** Glow radius in dp. */
    val swipe_trail_glow_radius: Float,

    /**
     * Config generation counter, bumped by every `Config.refresh()`. Keyboard2View mixes it
     * into its rendered-layout cache keys; it doubles as the identity of this snapshot.
     */
    val version: Int
) {
    /**
     * Runtime themes (decorative/custom) resolve their colours through `KeyboardColorScheme`
     * at draw time instead of XML attributes. Derived from [themeName] rather than stored so
     * `Config.isRuntimeTheme()` and every snapshot consumer share one definition.
     */
    val isRuntimeTheme: Boolean get() = isRuntimeThemeName(themeName)

    /**
     * Minimum displacement (px) for a short swipe on a key whose diagonal is
     * [keyDiagonalPx]: the user's [short_gesture_min_distance] (a PERCENT of the key
     * diagonal) converted through that diagonal, capped by the absolute [swipe_dist_px]
     * `* 0.8` so wide keys (backspace/shift/space) don't demand uncomfortably long swipes.
     *
     * Lives on the snapshot rather than in `Pointers` because it is a pure function of
     * configuration and geometry, it is the single source of truth for four call sites
     * (the deferred nav/backspace touch-up pre-checks, the short-gesture decision and the
     * selection-delete entry), and here it is reachable from a pure JVM test.
     */
    fun shortGestureMinDistancePx(keyDiagonalPx: Float): Float {
        val percentMin = short_gesture_min_distance.toPx(keyDiagonalPx)
        val cap = if (swipe_dist_px > 0) swipe_dist_px * 0.8f else Float.MAX_VALUE
        return minOf(percentMin, cap)
    }

    /**
     * Maximum displacement (px) still counted as a short swipe on a key whose diagonal is
     * [keyDiagonalPx]. The same threshold defines "the pointer has left its starting key",
     * so the touch-move boundary test and the touch-up short/long decision cannot drift.
     */
    fun shortGestureMaxDistancePx(keyDiagonalPx: Float): Float =
        short_gesture_max_distance.toPx(keyDiagonalPx)

    companion object {
        /** The single definition of "this theme is resolved at runtime". */
        @JvmStatic
        fun isRuntimeThemeName(name: String): Boolean =
            name.startsWith("decorative_") || name.startsWith("custom_")
    }
}
