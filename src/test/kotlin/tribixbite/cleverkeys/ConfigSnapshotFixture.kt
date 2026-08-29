package tribixbite.cleverkeys

import tribixbite.cleverkeys.prefs.ConfigSnapshot

/**
 * Builds a [ConfigSnapshot] for pure JVM tests without touching `Config` — which cannot be
 * constructed off-device at all (its constructor needs Android `Resources` and
 * `SharedPreferences`; see `ConfigNullSafetyTest`).
 *
 * Every parameter is defaulted so a test names only the fields its assertion depends on, and
 * the values it names are visible at the call site instead of buried in a shipped default.
 * The defaults below are deliberately *test* values, not `Defaults.*` — a test's expected
 * output must not silently change when a product default is retuned.
 *
 * [ConfigSnapshot] itself has no defaults, so adding a field to the read-model breaks this
 * fixture at compile time and forces a decision about what tests should see.
 */
internal fun testConfigSnapshot(
    circle_sensitivity: Int = 3,
    tap_duration_threshold: Long = 150L,
    double_tap_lock_shift: Boolean = false,
    keyrepeat_enabled: Boolean = false,
    keyrepeat_backspace_only: Boolean = false,
    longPressTimeout: Long = 600L,
    longPressInterval: Long = 65L,
    selection_delete_vertical_speed: Float = 0.4f,
    selection_delete_vertical_threshold: Int = 40,
    short_gestures_enabled: Boolean = false,
    short_gesture_min_distance: PercentOfKey = PercentOfKey(25),
    short_gesture_max_distance: PercentOfKey = PercentOfKey(100),
    swipe_dist_px: Float = 30f,
    slide_step_px: Float = 30f,
    swipe_typing_enabled: Boolean = true,
    marginTop: Float = 4f,
    margin_bottom: Float = 8f,
    margin_left: Float = 0f,
    margin_right: Float = 0f,
    keyPadding: Float = 2f,
    labelTextSize: Float = 0.33f,
    sublabelTextSize: Float = 0.22f,
    secondary_label_size_scale: Float = 1f,
    characterSize: Float = 1f,
    keyboardOpacity: Int = 100,
    themeName: String = "cleverkeysdark",
    version: Int = 1
): ConfigSnapshot = ConfigSnapshot(
    circle_sensitivity = circle_sensitivity,
    tap_duration_threshold = tap_duration_threshold,
    double_tap_lock_shift = double_tap_lock_shift,
    keyrepeat_enabled = keyrepeat_enabled,
    keyrepeat_backspace_only = keyrepeat_backspace_only,
    longPressTimeout = longPressTimeout,
    longPressInterval = longPressInterval,
    selection_delete_vertical_speed = selection_delete_vertical_speed,
    selection_delete_vertical_threshold = selection_delete_vertical_threshold,
    short_gestures_enabled = short_gestures_enabled,
    short_gesture_min_distance = short_gesture_min_distance,
    short_gesture_max_distance = short_gesture_max_distance,
    swipe_dist_px = swipe_dist_px,
    slide_step_px = slide_step_px,
    swipe_typing_enabled = swipe_typing_enabled,
    marginTop = marginTop,
    margin_bottom = margin_bottom,
    margin_left = margin_left,
    margin_right = margin_right,
    keyPadding = keyPadding,
    labelTextSize = labelTextSize,
    sublabelTextSize = sublabelTextSize,
    secondary_label_size_scale = secondary_label_size_scale,
    characterSize = characterSize,
    keyboardOpacity = keyboardOpacity,
    themeName = themeName,
    version = version
)
