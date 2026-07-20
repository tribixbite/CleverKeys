package tribixbite.cleverkeys.ui.settings.sections

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                title = "👆 Gesture Tuning",
                expanded = gestureTuningSectionExpanded,
                onExpandChange = { gestureTuningSectionExpanded = it }
            ) {
                Text(
                    text = "Fine-tune tap, swipe, and slider behavior for your typing style.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Short Gestures subsection (moved from Input section)
                Text(
                    text = "Short Gestures",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = "Enable Short Gestures",
                    description = "Recognize short swipes for quick words (it, is, at, etc.)",
                    checked = shortGesturesEnabled,
                    onCheckedChange = {
                        shortGesturesEnabled = it
                        saveSetting("short_gestures_enabled", it)
                    },
                    highlightId = "short_gestures"
                )

                if (shortGesturesEnabled) {
                    SettingsSlider(
                        title = "Min Distance",
                        description = "Minimum swipe distance to trigger (% of key diagonal)",
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
                        title = "Max Distance",
                        description = "The short/long boundary (% of key diagonal): at or below = short swipe, beyond = swipe-typed word. Low values turn slight overshoots into words; high values require longer swipes before word typing starts.",
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
                        Text("📐 Open Calibration Tool")
                    }
                    // Customize Per-Key Actions button moved to Activities section at top
                }

                // Selection-Delete Mode subsection (backspace swipe+hold)
                Text(
                    text = "Selection-Delete Mode",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Text(
                    text = "Short swipe + hold on backspace to select text, then release to delete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingsSlider(
                    title = "Vertical Threshold",
                    description = "% of key height finger must move to trigger line selection. Higher = harder to accidentally select lines.",
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
                    title = "Vertical Speed",
                    description = "Speed multiplier for line selection (lower = slower). Character selection stays at full speed.",
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
                    text = "Tap and Typing",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = "Tap Duration Threshold",
                    description = "Maximum duration for a tap gesture (ms). Higher = easier taps but may interfere with swipes.",
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
                    title = "Double-Space to Period",
                    description = "Tap space twice quickly to insert period. Only triggers after letters/numbers.",
                    checked = doubleSpaceToPeriod,
                    onCheckedChange = {
                        doubleSpaceToPeriod = it
                        saveSetting("double_space_to_period", doubleSpaceToPeriod)
                    }
                )

                if (doubleSpaceToPeriod) {
                    SettingsSlider(
                        title = "Double-Space Timing",
                        description = "Maximum time between spaces to trigger period (ms)",
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
                    text = "Swipe Recognition",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Swipe Sensitivity Preset
                val sensitivityPresets = listOf("Low", "Medium", "High", "Custom")
                val currentPresetIndex = sensitivityPresets.indexOf(getSwipeSensitivityPreset())
                SettingsDropdown(
                    title = "Sensitivity Preset",
                    description = "Quick presets for swipe recognition. Custom shows when values differ from presets.",
                    options = sensitivityPresets,
                    selectedIndex = if (currentPresetIndex >= 0) currentPresetIndex else 3,
                    onSelectionChange = { index ->
                        applySwipeSensitivityPreset(sensitivityPresets[index])
                    }
                )

                SettingsSlider(
                    title = "Minimum Swipe Distance",
                    description = "Minimum traced path (px) before a gesture can qualify as a swipe-typed word. Lower helps very short words register; raise if stray touches misfire as words. (Short-vs-long is decided by Max Distance above.)",
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
                    title = "Minimum Key Distance",
                    description = "Distance between keys during swipe (px). Lower captures more keys but may add noise.",
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
                    title = "Minimum Key Dwell Time",
                    description = "Time to register a key during swipe (ms). Lower allows faster swiping.",
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
                    title = "Movement Noise Filter",
                    description = "Minimum movement to register (px). Higher filters jitter but may lose data.",
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
                    title = "High Velocity Threshold",
                    description = "Velocity for fast swipe detection (px/sec). Higher allows faster swipes.",
                    value = swipeHighVelocityThreshold,
                    valueRange = 200f..2000f,
                    steps = 18,
                    onValueChange = {
                        swipeHighVelocityThreshold = it
                        saveSetting("swipe_high_velocity_threshold", swipeHighVelocityThreshold)
                    },
                    displayValue = "%.0f px/s".format(swipeHighVelocityThreshold)
                )

                SettingsSlider(
                    title = "Finger Occlusion Compensation",
                    description = "Y-offset as % of row height to compensate for finger obscuring keys. Higher shifts touch point down toward key centers.",
                    value = fingerOcclusionOffset,
                    valueRange = 0f..50f,
                    steps = 10,
                    onValueChange = {
                        fingerOcclusionOffset = it
                        saveSetting("finger_occlusion_offset", fingerOcclusionOffset)
                    },
                    displayValue = "%.1f%%".format(fingerOcclusionOffset)
                )

                // Slider Key Behavior subsection
                Text(
                    text = "Slider Key Behavior",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                SettingsSlider(
                    title = "Speed Smoothing",
                    description = "Smoothing factor for slider movement. Higher is smoother but less responsive.",
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
                    title = "Maximum Speed Multiplier",
                    description = "Maximum slider acceleration. Higher allows faster sliding.",
                    value = sliderSpeedMax,
                    valueRange = 1.0f..10f,
                    steps = 18,
                    onValueChange = {
                        sliderSpeedMax = it
                        saveSetting("slider_speed_max", sliderSpeedMax)
                    },
                    displayValue = "%.1fx".format(sliderSpeedMax)
                )

                Text(
                    text = "If gestures feel laggy, reduce dwell time and noise threshold. If taps register as swipes, increase tap duration.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
}
