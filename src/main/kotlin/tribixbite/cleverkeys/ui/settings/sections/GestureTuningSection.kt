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
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ShortSwipeCalibrationActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.applySwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.getSwipeSensitivityPreset
import tribixbite.cleverkeys.ui.settings.saveSetting
import androidx.compose.material3.MaterialTheme

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

                if (shortGesturesEnabled) {
                    SettingsSlider(
                        title = stringResource(R.string.gesture_min_distance_title),
                        description = stringResource(R.string.gesture_min_distance_desc),
                        value = shortGestureMinDistance.toFloat(),
                        valueRange = 10f..60f,
                        steps = 10,
                        onValueChange = {
                            shortGestureMinDistance = it.toInt()
                            saveSetting("short_gesture_min_distance", shortGestureMinDistance)
                        },
                        displayValue = "${shortGestureMinDistance}%"
                    )

                    SettingsSlider(
                        title = stringResource(R.string.gesture_max_distance_title),
                        description = stringResource(R.string.gesture_max_distance_desc),
                        value = shortGestureMaxDistance.toFloat(),
                        valueRange = 50f..200f,
                        steps = 30,
                        onValueChange = {
                            shortGestureMaxDistance = it.toInt()
                            saveSetting("short_gesture_max_distance", shortGestureMaxDistance)
                        },
                        displayValue = "${shortGestureMaxDistance}%"
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
                    steps = 12,
                    onValueChange = {
                        selectionDeleteVerticalThreshold = it.toInt()
                        saveSetting("selection_delete_vertical_threshold", selectionDeleteVerticalThreshold)
                    },
                    displayValue = "${selectionDeleteVerticalThreshold}%"
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_vertical_speed_title),
                    description = stringResource(R.string.gesture_vertical_speed_desc),
                    value = selectionDeleteVerticalSpeed,
                    valueRange = 0.1f..1.0f,
                    steps = 18,
                    onValueChange = {
                        selectionDeleteVerticalSpeed = it
                        saveSetting("selection_delete_vertical_speed", selectionDeleteVerticalSpeed)
                    },
                    displayValue = String.format(java.util.Locale.getDefault(), "%.1fx", selectionDeleteVerticalSpeed)
                )

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
                    steps = 45,
                    onValueChange = {
                        tapDurationThreshold = it.toInt()
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
                        steps = 12,
                        onValueChange = {
                            doubleSpaceThreshold = it.toInt()
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
                val sensitivityPresets = listOf("Low", "Medium", "High", "Custom")
                val currentPresetIndex = sensitivityPresets.indexOf(getSwipeSensitivityPreset())
                SettingsDropdown(
                    title = stringResource(R.string.gesture_sensitivity_preset_title),
                    description = stringResource(R.string.gesture_sensitivity_preset_desc),
                    options = sensitivityPresets,
                    selectedIndex = if (currentPresetIndex >= 0) currentPresetIndex else 3,
                    onSelectionChange = { index ->
                        applySwipeSensitivityPreset(sensitivityPresets[index])
                    }
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_min_swipe_distance_title),
                    description = stringResource(R.string.gesture_min_swipe_distance_desc),
                    value = swipeMinDistance,
                    valueRange = 20f..100f,
                    steps = 16,
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
                    steps = 13,
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
                    steps = 10,
                    onValueChange = {
                        swipeMinDwellTime = it.toInt()
                        saveSetting("swipe_min_dwell_time", swipeMinDwellTime)
                    },
                    displayValue = "${swipeMinDwellTime}ms"
                )

                SettingsSlider(
                    title = stringResource(R.string.gesture_noise_filter_title),
                    description = stringResource(R.string.gesture_noise_filter_desc),
                    value = swipeNoiseThreshold,
                    valueRange = 0.5f..10f,
                    steps = 19,
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
                    steps = 18,
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
                    steps = 17,
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
                    steps = 18,
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
                    valueRange = 1f..7f,
                    steps = 5,
                    onValueChange = {
                        swipeSmoothingWindow = it.toInt()
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
                    valueRange = -25f..25f,
                    steps = 49,
                    onValueChange = {
                        fingerOcclusionOffset = it.toInt()
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
