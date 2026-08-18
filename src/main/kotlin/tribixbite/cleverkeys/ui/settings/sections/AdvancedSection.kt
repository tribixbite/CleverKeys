package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.openSwipeDebugActivity
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.AdvancedSection() {
            // Advanced Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_advanced),
                expanded = advancedSectionExpanded,
                onExpandChange = { advancedSectionExpanded = it }
            ) {
                // Terminal Mode - moved from the Swipe Typing section (layout setting, not prediction)
                SettingsSwitch(
                    title = stringResource(R.string.advanced_terminal_mode_title),
                    description = stringResource(R.string.advanced_terminal_mode_desc),
                    checked = termuxModeEnabled,
                    onCheckedChange = {
                        termuxModeEnabled = it
                        saveSetting("termux_mode_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.settings_debug_title),
                    description = stringResource(R.string.settings_debug_desc),
                    checked = debugEnabled,
                    onCheckedChange = {
                        debugEnabled = it
                        saveSetting("debug_enabled", it)
                    }
                )

                // Phase 1: Swipe Debug Log Toggle
                SettingsSwitch(
                    title = stringResource(R.string.advanced_swipe_debug_log_title),
                    description = stringResource(R.string.advanced_swipe_debug_log_desc),
                    checked = swipeDebugEnabled,
                    onCheckedChange = {
                        swipeDebugEnabled = it
                        saveSetting("swipe_show_debug_scores", it)
                    }
                )

                if (swipeDebugEnabled) {
                    SettingsSwitch(
                        title = stringResource(R.string.advanced_detailed_logging_title),
                        description = stringResource(R.string.advanced_detailed_logging_desc),
                        checked = swipeDebugDetailedLogging,
                        onCheckedChange = {
                            swipeDebugDetailedLogging = it
                            saveSetting("swipe_debug_detailed_logging", it)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { openSwipeDebugActivity() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.advanced_open_debug_log))
                    }
                }

                // Task B Tier 2 (pipeline transparency): opt-in per-suggestion
                // origin markers. A user feature (not debug-gated) — long-press
                // provenance inspection is always available; this adds the
                // at-a-glance colored dots.
                SettingsSwitch(
                    title = stringResource(R.string.advanced_provenance_markers_title),
                    description = stringResource(R.string.advanced_provenance_markers_desc),
                    checked = suggestionProvenanceMarkers,
                    onCheckedChange = {
                        suggestionProvenanceMarkers = it
                        saveSetting("suggestion_provenance_markers", it)
                        tribixbite.cleverkeys.Config.globalConfig()?.suggestion_provenance_markers = it
                    }
                )

                // The "Keyboard Calibration" button opened SwipeCalibrationActivity, which was
                // deleted with the neural engine on 2026-08-18: the screen existed to record and
                // replay traces through the transformer and showed a fatal dialog without its
                // models. Keyboard height/margins are configured in Appearance.
            }
}
