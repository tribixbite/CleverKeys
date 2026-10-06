package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import tribixbite.cleverkeys.TerminalUtils
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.ui.settings.contentYOf
import tribixbite.cleverkeys.ui.settings.recordSettingPosition
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
                // TODO: audit this legacy toggle; its Config field no longer gates runtime handling.
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

                CustomTerminalPackagesSetting()

                // I-7 (maintainer decision 2026-09-08): the "don't ask again"
                // affordance for the default-IME reminder. The prompt itself is a
                // toast (no buttons), so this switch is the permanent opt-out it
                // points at; IMEStatusHelper reads the pref before every check.
                SettingsSwitch(
                    title = stringResource(R.string.advanced_ime_prompt_title),
                    description = stringResource(R.string.advanced_ime_prompt_desc),
                    checked = imeDefaultPromptEnabled,
                    onCheckedChange = {
                        imeDefaultPromptEnabled = it
                        saveSetting("ime_default_prompt_enabled", it)
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

/** Edits a bounded additive list without persisting drafts, including across rotation. */
@Composable
private fun SettingsActivity.CustomTerminalPackagesSetting() {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val title = stringResource(R.string.advanced_custom_terminal_title)
    val stored = remember(customTerminalPackages) { TerminalUtils.parseCustomPackages(customTerminalPackages) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.advanced_custom_terminal_desc))
        Button(
            onClick = { draft = customTerminalPackages; showDialog = true },
            modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                recordSettingPosition("custom_terminal_packages", contentYOf(it))
            }
        ) { Text(title) }
        Text(stringResource(R.string.advanced_custom_terminal_count,
            (stored as? TerminalUtils.PackageListResult.Valid)?.packages?.size ?: 0))
    }
    if (showDialog) {
        val result = TerminalUtils.parseCustomPackages(draft)
        val error = if (result is TerminalUtils.PackageListResult.Invalid) when (result.reason) {
            TerminalUtils.PackageListError.INVALID_PACKAGE -> stringResource(R.string.advanced_custom_terminal_invalid, result.entry, TerminalUtils.MAX_PACKAGE_LENGTH)
            TerminalUtils.PackageListError.TOO_MANY_PACKAGES -> stringResource(R.string.advanced_custom_terminal_limit, TerminalUtils.MAX_CUSTOM_PACKAGES)
            TerminalUtils.PackageListError.INPUT_TOO_LONG -> stringResource(R.string.advanced_custom_terminal_input_limit, TerminalUtils.MAX_PACKAGE_INPUT_LENGTH)
        } else null
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.advanced_custom_terminal_help))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(title) },
                        isError = error != null,
                        supportingText = { if (error != null) Text(error) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = result is TerminalUtils.PackageListResult.Valid, onClick = {
                    // Revalidate at the write boundary; never silently accept part of a bad list.
                    val valid = TerminalUtils.parseCustomPackages(draft) as? TerminalUtils.PackageListResult.Valid
                    if (valid != null) {
                        val canonical = valid.packages.joinToString("\n")
                        saveSetting("custom_terminal_packages", canonical)
                        customTerminalPackages = canonical
                        Config.globalConfigOrNull()?.custom_terminal_packages = valid.packages
                        showDialog = false
                    }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}
