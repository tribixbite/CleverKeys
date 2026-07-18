package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.AccessibilitySection() {
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_accessibility),
                expanded = accessibilitySectionExpanded,
                onExpandChange = { accessibilitySectionExpanded = it }
            ) {
                // TalkBack support is built into the keyboard view (see
                // a11y/KeyboardAccessibilityHelper) — no toggle required. The
                // former "Sticky Keys" and "Voice Guidance" switches were dead:
                // sticky-keys duplicated the shipped modifier-latching, and
                // voice-guidance was superseded by first-class TalkBack support.
                Text(
                    text = stringResource(R.string.settings_screen_reader_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // v1.2.8: Vibration settings moved to Accessibility section
                Text(
                    text = "Haptic Feedback",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                SettingsSwitch(
                    title = stringResource(R.string.settings_vibration_title),
                    description = stringResource(R.string.settings_vibration_desc),
                    checked = vibrationEnabled,
                    onCheckedChange = {
                        vibrationEnabled = it
                        saveSetting("vibration_enabled", it)
                        // Update haptic_enabled immediately so the IME picks up the change
                        // without waiting for a full Config reload.
                        // Do NOT set vibrate_custom here — that flag is only set when the user
                        // explicitly drags the duration slider. Setting it on toggle was the
                        // root cause of #154: it forced the slow Vibrator.createOneShot path
                        // for all users, bypassing the low-latency performHapticFeedback path.
                        Config.globalConfig().haptic_enabled = it
                    }
                )

                if (vibrationEnabled) {
                    SettingsSlider(
                        title = "Custom Duration (optional)",
                        description = "Set a custom vibration length. Leave at default to use the system haptic pattern (lowest latency, OEM-tuned).",
                        value = vibrationDuration.toFloat(),
                        valueRange = 5f..100f,
                        steps = 19,
                        onValueChange = {
                            vibrationDuration = it.toInt()
                            saveSetting("vibrate_duration", vibrationDuration)
                            // Only enable custom mode when user explicitly drags the slider.
                            // This preserves the low-latency performHapticFeedback path for
                            // users who don't need a custom duration (fixes #154).
                            saveSetting("vibrate_custom", true)
                            Config.globalConfig().vibrate_custom = true
                            Config.globalConfig().vibrate_duration = vibrationDuration.toLong()
                        },
                        displayValue = "${vibrationDuration}ms"
                    )

                    // Per-event haptic feedback controls
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Haptic Events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )

                    SettingsSwitch(
                        title = "Key Press",
                        description = "Vibrate on key tap",
                        checked = hapticKeyPress,
                        onCheckedChange = {
                            hapticKeyPress = it
                            saveSetting("haptic_key_press", it)
                            Config.globalConfig().haptic_key_press = it
                        }
                    )

                    SettingsSwitch(
                        title = "Suggestion Tap",
                        description = "Vibrate when selecting a suggestion",
                        checked = hapticPredictionTap,
                        onCheckedChange = {
                            hapticPredictionTap = it
                            saveSetting("haptic_prediction_tap", it)
                            Config.globalConfig().haptic_prediction_tap = it
                        }
                    )

                    SettingsSwitch(
                        title = "TrackPoint Mode",
                        description = "Vibrate when entering cursor mode on nav keys",
                        checked = hapticTrackpointActivate,
                        onCheckedChange = {
                            hapticTrackpointActivate = it
                            saveSetting("haptic_trackpoint_activate", it)
                            Config.globalConfig().haptic_trackpoint_activate = it
                        }
                    )

                    SettingsSwitch(
                        title = "Long Press",
                        description = "Vibrate on modifier lock",
                        checked = hapticLongPress,
                        onCheckedChange = {
                            hapticLongPress = it
                            saveSetting("haptic_long_press", it)
                            Config.globalConfig().haptic_long_press = it
                        }
                    )

                    SettingsSwitch(
                        title = "Swipe Complete",
                        description = "Vibrate when swipe gesture finishes",
                        checked = hapticSwipeComplete,
                        onCheckedChange = {
                            hapticSwipeComplete = it
                            saveSetting("haptic_swipe_complete", it)
                            Config.globalConfig().haptic_swipe_complete = it
                        }
                    )
                }
            }
}
