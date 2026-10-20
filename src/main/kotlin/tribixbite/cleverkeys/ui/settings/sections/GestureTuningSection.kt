package tribixbite.cleverkeys.ui.settings.sections

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.SettingsRanges
import tribixbite.cleverkeys.ShortSwipeCalibrationActivity
import tribixbite.cleverkeys.toSliderRange
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.SwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.applySwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.getSwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.saveSetting
import androidx.compose.material3.MaterialTheme
import kotlin.math.roundToInt

@Composable
internal fun SettingsActivity.GestureTuningSection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_gesture_tuning),
                expanded = gestureTuningSectionExpanded,
                onExpandChange = { gestureTuningSectionExpanded = it }
            ) {
                Text(
                    text = stringResource(R.string.gesture_section_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Short Gestures subsection (moved from Input section)
                Text(
                    text = stringResource(R.string.gesture_short_gestures_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = stringResource(R.string.gesture_enable_short_title),
                    description = stringResource(R.string.gesture_enable_short_desc),
                    checked = shortGesturesEnabled,
                    onCheckedChange = {
                        shortGesturesEnabled = it
                        saveSetting("short_gestures_enabled", it)
                    },
                    highlightId = "short_gestures"
                )

                // GH #147/#87: every swipe past the minimum resolves to its direction's subkey
                // and word swipes are off. Visible-but-disabled while short gestures are off
                // (Config ignores the stored value then), never hidden.
                SettingsSwitch(
                    title = stringResource(R.string.gesture_short_swipes_only_title),
                    description = stringResource(R.string.gesture_short_swipes_only_desc),
                    checked = shortSwipesOnly,
                    enabled = shortGesturesEnabled,
                    onCheckedChange = {
                        shortSwipesOnly = it
                        saveSetting("short_swipes_only", it)
                    }
                )

                if (shortGesturesEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.gesture_min_distance_title),
                        description = stringResource(R.string.gesture_min_distance_desc),
                        value = shortGestureMinDistance.toFloat(),
                        valueRange = SettingsRanges.SHORT_GESTURE_MIN_DISTANCE.toSliderRange(),
                        increment = 1f,
                        onValueChange = {
                            shortGestureMinDistance = it.roundToInt()
                            saveSetting("short_gesture_min_distance", shortGestureMinDistance)
                        },
                        displayValue = "${shortGestureMinDistance}%"
                    )

                    SettingsSlider(
                        title = stringResource(R.string.gesture_max_distance_title),
                        // With short swipes only there is no long swipe, so the boundary is
                        // unused: keep the slider visible but disabled, and say why.
                        description = stringResource(
                            if (shortSwipesOnly) R.string.gesture_max_distance_desc_short_swipes_only
                            else R.string.gesture_max_distance_desc
                        ),
                        value = shortGestureMaxDistance.toFloat(),
                        valueRange = SettingsRanges.SHORT_GESTURE_MAX_DISTANCE.toSliderRange(),
                        increment = 1f,
                        onValueChange = {
                            shortGestureMaxDistance = it.roundToInt()
                            saveSetting("short_gesture_max_distance", shortGestureMaxDistance)
                        },
                        displayValue = "${shortGestureMaxDistance}%",
                        enabled = !shortSwipesOnly
                    )

                    // Calibration Activity Button
                    val calibrationContext = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(calibrationContext, ShortSwipeCalibrationActivity::class.java)
                            calibrationContext.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.gesture_open_calibration))
                    }
                    // Customize Per-Key Actions button moved to Activities section at top
                }

                // Subkey popover subsection (hold-then-select, docs/specs/subkey-popover.md)
                Text(
                    text = stringResource(R.string.gesture_subkey_popover_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = stringResource(R.string.gesture_subkey_popover_title),
                    description = stringResource(R.string.gesture_subkey_popover_desc),
                    checked = subkeyPopoverEnabled,
                    onCheckedChange = {
                        subkeyPopoverEnabled = it
                        saveSetting("subkey_popover_enabled", it)
                    }
                )

                if (subkeyPopoverEnabled) {
                    val neutralRange = Defaults.SUBKEY_POPOVER_NEUTRAL_MIN.toFloat()..Defaults.SUBKEY_POPOVER_NEUTRAL_MAX.toFloat()
                    // 5% steps across the range: (max - min) / 5 - 1 intermediate stops.
                    SettingsSlider(
                        title = stringResource(R.string.gesture_popover_neutral_width_title),
                        description = stringResource(R.string.gesture_popover_neutral_width_desc),
                        value = subkeyPopoverNeutralWidth.toFloat(),
                        valueRange = neutralRange,
                        increment = 5f,
                        onValueChange = {
                            subkeyPopoverNeutralWidth = it.roundToInt()
                            saveSetting("subkey_popover_neutral_width", subkeyPopoverNeutralWidth)
                        },
                        displayValue = "${subkeyPopoverNeutralWidth}%"
                    )
                    SettingsSlider(
                        title = stringResource(R.string.gesture_popover_neutral_height_title),
                        description = stringResource(R.string.gesture_popover_neutral_height_desc),
                        value = subkeyPopoverNeutralHeight.toFloat(),
                        valueRange = neutralRange,
                        increment = 5f,
                        onValueChange = {
                            subkeyPopoverNeutralHeight = it.roundToInt()
                            saveSetting("subkey_popover_neutral_height", subkeyPopoverNeutralHeight)
                        },
                        displayValue = "${subkeyPopoverNeutralHeight}%"
                    )
                }

                // Selection-Delete Mode subsection (backspace swipe+hold)
                Text(
                    text = stringResource(R.string.gesture_selection_delete_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.gesture_selection_delete_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_vertical_threshold_title),
                    description = stringResource(R.string.gesture_vertical_threshold_desc),
                    value = selectionDeleteVerticalThreshold.toFloat(),
                    valueRange = 20f..80f,
                    increment = 5f,
                    onValueChange = {
                        selectionDeleteVerticalThreshold = it.roundToInt()
                        saveSetting("selection_delete_vertical_threshold", selectionDeleteVerticalThreshold)
                    },
                    displayValue = "${selectionDeleteVerticalThreshold}%"
                )

                // GH #190: speed-by-distance joystick (default) vs follow-finger selection.
                val followFinger = selectionDeleteMode == Defaults.SELECTION_DELETE_MODE_FOLLOW
                SettingsDropdown(
                    title = stringResource(R.string.gesture_selection_delete_mode_title),
                    description = stringResource(R.string.gesture_selection_delete_mode_desc),
                    // Index 0 = "speed", 1 = "follow"; the stored value is the id, never the label.
                    options = listOf(
                        stringResource(R.string.gesture_selection_delete_mode_speed),
                        stringResource(R.string.gesture_selection_delete_mode_follow)
                    ),
                    selectedIndex = if (followFinger) 1 else 0,
                    onSelectionChange = { index ->
                        selectionDeleteMode = if (index == 1) {
                            Defaults.SELECTION_DELETE_MODE_FOLLOW
                        } else {
                            Defaults.SELECTION_DELETE_MODE_SPEED
                        }
                        saveSetting("selection_delete_mode", selectionDeleteMode)
                    }
                )

                if (followFinger) {
                    val stepRange = SettingsRanges.SELECTION_DELETE_STEP_DP
                    SettingsSlider(
                        title = stringResource(R.string.gesture_selection_delete_step_title),
                        description = stringResource(R.string.gesture_selection_delete_step_desc),
                        value = selectionDeleteStepDp.toFloat(),
                        valueRange = stepRange.first.toFloat()..stepRange.last.toFloat(),
                        increment = 1f,
                        onValueChange = {
                            selectionDeleteStepDp = it.roundToInt().coerceIn(stepRange)
                            saveSetting("selection_delete_step_dp", selectionDeleteStepDp)
                        },
                        displayValue = "$selectionDeleteStepDp dp"
                    )
                } else {
                    // Vertical speed only scales the joystick's repeat rate; follow-finger
                    // selects one line per key height, so the slider would do nothing there.
                    SettingsSlider(
                        title = stringResource(R.string.gesture_vertical_speed_title),
                        description = stringResource(R.string.gesture_vertical_speed_desc),
                        value = selectionDeleteVerticalSpeed,
                        valueRange = 0.1f..1.0f,
                        increment = 0.05f,
                        onValueChange = {
                            selectionDeleteVerticalSpeed = it
                            saveSetting("selection_delete_vertical_speed", selectionDeleteVerticalSpeed)
                        },
                        displayValue = String.format(java.util.Locale.getDefault(), "%.1fx", selectionDeleteVerticalSpeed)
                    )
                }

                // Tap and Typing subsection
                Text(
                    text = stringResource(R.string.gesture_tap_typing_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_tap_duration_title),
                    description = stringResource(R.string.gesture_tap_duration_desc),
                    value = tapDurationThreshold.toFloat(),
                    valueRange = 50f..500f,
                    increment = 10f,
                    onValueChange = {
                        tapDurationThreshold = it.roundToInt()
                        saveSetting("tap_duration_threshold", tapDurationThreshold)
                    },
                    displayValue = "${tapDurationThreshold}ms"
                )

                SettingsSwitch(
                    title = stringResource(R.string.gesture_double_space_period_title),
                    description = stringResource(R.string.gesture_double_space_period_desc),
                    checked = doubleSpaceToPeriod,
                    onCheckedChange = {
                        doubleSpaceToPeriod = it
                        saveSetting("double_space_to_period", doubleSpaceToPeriod)
                    }
                )

                if (doubleSpaceToPeriod) {
                    SettingsSlider(
                        title = stringResource(R.string.gesture_double_space_timing_title),
                        description = stringResource(R.string.gesture_double_space_timing_desc),
                        value = doubleSpaceThreshold.toFloat(),
                        valueRange = 200f..800f,
                        increment = 50f,
                        onValueChange = {
                            doubleSpaceThreshold = it.roundToInt()
                            saveSetting("double_space_threshold", doubleSpaceThreshold)
                        },
                        displayValue = "${doubleSpaceThreshold}ms"
                    )
                }

                // Swipe Recognition subsection
                Text(
                    text = stringResource(R.string.gesture_swipe_recognition_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Swipe Sensitivity Preset
                // Enum identifies the preset; only the displayed label is localized.
                val sensitivityPresets = SwipeSensitivityPreset.entries
                SettingsDropdown(
                    title = stringResource(R.string.gesture_sensitivity_preset_title),
                    description = stringResource(R.string.gesture_sensitivity_preset_desc),
                    options = sensitivityPresets.map { stringResource(it.labelRes) },
                    selectedIndex = sensitivityPresets.indexOf(getSwipeSensitivityPreset()),
                    onSelectionChange = { index ->
                        applySwipeSensitivityPreset(sensitivityPresets[index])
                    }
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_min_swipe_distance_title),
                    description = stringResource(R.string.gesture_min_swipe_distance_desc),
                    value = swipeMinDistance,
                    valueRange = 20f..100f,
                    increment = 1f,
                    onValueChange = {
                        swipeMinDistance = it
                        saveSetting("swipe_min_distance", swipeMinDistance)
                    },
                    displayValue = "%.0f px".format(swipeMinDistance)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_min_key_distance_title),
                    description = stringResource(R.string.gesture_min_key_distance_desc),
                    value = swipeMinKeyDistance,
                    valueRange = 15f..80f,
                    increment = 5f,
                    onValueChange = {
                        swipeMinKeyDistance = it
                        saveSetting("swipe_min_key_distance", swipeMinKeyDistance)
                    },
                    displayValue = "%.0f px".format(swipeMinKeyDistance)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_min_key_dwell_title),
                    description = stringResource(R.string.gesture_min_key_dwell_desc),
                    value = swipeMinDwellTime.toFloat(),
                    valueRange = 0f..50f,
                    increment = 1f,
                    onValueChange = {
                        swipeMinDwellTime = it.roundToInt()
                        saveSetting("swipe_min_dwell_time", swipeMinDwellTime)
                    },
                    displayValue = "${swipeMinDwellTime}ms"
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_noise_filter_title),
                    description = stringResource(R.string.gesture_noise_filter_desc),
                    value = swipeNoiseThreshold,
                    valueRange = 0.5f..10f,
                    increment = 0.5f,
                    onValueChange = {
                        swipeNoiseThreshold = it
                        saveSetting("swipe_noise_threshold", swipeNoiseThreshold)
                    },
                    displayValue = "%.1f px".format(swipeNoiseThreshold)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_high_velocity_title),
                    description = stringResource(R.string.gesture_high_velocity_desc),
                    value = swipeHighVelocityThreshold,
                    valueRange = 200f..2000f,
                    increment = 100f,
                    onValueChange = {
                        swipeHighVelocityThreshold = it
                        saveSetting("swipe_high_velocity_threshold", swipeHighVelocityThreshold)
                    },
                    displayValue = "%.0f px/s".format(swipeHighVelocityThreshold)
                )

                // Slider Key Behavior subsection
                Text(
                    text = stringResource(R.string.gesture_slider_key_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_speed_smoothing_title),
                    description = stringResource(R.string.gesture_speed_smoothing_desc),
                    value = sliderSpeedSmoothing,
                    valueRange = 0.1f..0.95f,
                    increment = 0.05f,
                    onValueChange = {
                        sliderSpeedSmoothing = it
                        saveSetting("slider_speed_smoothing", sliderSpeedSmoothing)
                    },
                    displayValue = "%.2f".format(sliderSpeedSmoothing)
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_max_speed_multiplier_title),
                    description = stringResource(R.string.gesture_max_speed_multiplier_desc),
                    value = sliderSpeedMax,
                    valueRange = 1.0f..10f,
                    increment = 0.5f,
                    onValueChange = {
                        sliderSpeedMax = it
                        saveSetting("slider_speed_max", sliderSpeedMax)
                    },
                    displayValue = "%.1fx".format(sliderSpeedMax)
                )

                // Touch smoothing. Belongs HERE, not in an engine screen: the moving average is
                // applied by ImprovedSwipeGestureRecognizer to the raw path, and the smoothed
                // path is what BOTH engines decode. Its only slider used to live in the deleted
                // NeuralSettingsActivity, so from 018d94f7 until 2026-08-19 the pref kept
                // working on every swipe while being reachable only through a backup import.
                SettingsSlider(
                    title = stringResource(R.string.gesture_touch_smoothing_title),
                    description = stringResource(R.string.gesture_touch_smoothing_desc),
                    value = swipeSmoothingWindow.toFloat(),
                    valueRange = SettingsRanges.SWIPE_SMOOTHING_WINDOW.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        swipeSmoothingWindow = it.roundToInt()
                        saveSetting("swipe_smoothing_window", swipeSmoothingWindow)
                    },
                    displayValue = "$swipeSmoothingWindow"
                )

                // Finger-occlusion compensation. Signed: positive shifts the read-back point
                // DOWN (for users whose touches land above the key they aimed at, the common
                // case), negative shifts it up. Default 0 — the pre-2026-08-18 engine defaulted
                // to +12.5% but that figure was never measured, and the shipped CTC model was
                // trained on uncorrected traces.
                SettingsSlider(
                    title = stringResource(R.string.gesture_finger_occlusion_title),
                    description = stringResource(R.string.gesture_finger_occlusion_desc),
                    value = fingerOcclusionOffset.toFloat(),
                    valueRange = SettingsRanges.FINGER_OCCLUSION_OFFSET.toSliderRange(),
                    increment = 1f,
                    onValueChange = {
                        fingerOcclusionOffset = it.roundToInt()
                        saveSetting("finger_occlusion_offset", fingerOcclusionOffset)
                    },
                    displayValue = if (fingerOcclusionOffset == 0) stringResource(R.string.common_off)
                    else "$fingerOcclusionOffset%"
                )

                Text(
                    text = stringResource(R.string.gesture_tuning_tip),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
}
