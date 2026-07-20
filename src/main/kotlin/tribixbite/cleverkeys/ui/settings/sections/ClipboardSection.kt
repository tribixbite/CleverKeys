package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.ClipboardHistoryService
import tribixbite.cleverkeys.PrivateCopyProcessTextActivity
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.notifySanitizationRulesChanged
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.ClipboardSection() {
            // Clipboard Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_clipboard),
                expanded = clipboardSectionExpanded,
                onExpandChange = { clipboardSectionExpanded = it }
            ) {
                // Enable/disable clipboard history
                SettingsSwitch(
                    title = stringResource(R.string.clipboard_history_title),
                    description = stringResource(R.string.clipboard_history_desc),
                    checked = clipboardHistoryEnabled,
                    onCheckedChange = {
                        clipboardHistoryEnabled = it
                        saveSetting("clipboard_history_enabled", it)
                    }
                )

                // Clipboard limit type dropdown
                val limitTypeOptions = listOf("By Count", "By Size")
                val limitTypeIndex = if (clipboardLimitType == "count") 0 else 1
                SettingsDropdown(
                    title = stringResource(R.string.clipboard_limit_type_title),
                    description = stringResource(R.string.clipboard_limit_type_desc),
                    options = limitTypeOptions,
                    selectedIndex = limitTypeIndex,
                    onSelectionChange = { idx ->
                        clipboardLimitType = if (idx == 0) "count" else "size"
                        saveSetting("clipboard_limit_type", clipboardLimitType)
                    }
                )

                // History limit (only shown if limit type is "count")
                if (clipboardLimitType == "count") {
                    SettingsSlider(
                        title = stringResource(R.string.clipboard_history_limit_title),
                        description = stringResource(R.string.clipboard_history_limit_desc),
                        value = clipboardHistoryLimit.toFloat(),
                        valueRange = 0f..500f,
                        steps = 50,  // 50 steps = increments of 10
                        onValueChange = {
                            clipboardHistoryLimit = it.toInt()
                            saveSetting("clipboard_history_limit", clipboardHistoryLimit)
                        },
                        displayValue = if (clipboardHistoryLimit == 0) "Unlimited" else "$clipboardHistoryLimit items"
                    )
                }

                // Entry Duration — discrete presets from 1 hour to Never
                // Auto-links with History Limit: setting "Never expire" also sets count to Unlimited
                run {
                    val durationPresets = listOf(60, 360, 720, 1440, 4320, 10080, 20160, 43200, -1)
                    val currentIndex = durationPresets.indexOf(clipboardHistoryDuration).let {
                        if (it >= 0) it else durationPresets.indexOfLast { p -> p in 1..clipboardHistoryDuration }
                            .coerceAtLeast(0)
                    }
                    // Contextual description based on limit+duration combination
                    val durationDesc = when {
                        clipboardHistoryDuration != -1 && clipboardHistoryLimit == 0 ->
                            "Warning: count is unlimited but entries expire after this duration"
                        clipboardHistoryDuration == -1 && clipboardHistoryLimit > 0 ->
                            "Entries never expire but capped at $clipboardHistoryLimit (count limit still applies)"
                        else -> "How long entries persist before auto-deletion (-1 = never)"
                    }
                    SettingsSlider(
                        title = stringResource(R.string.clipboard_entry_duration_title),
                        description = durationDesc,
                        value = currentIndex.toFloat(),
                        valueRange = 0f..(durationPresets.size - 1).toFloat(),
                        steps = durationPresets.size - 2,
                        onValueChange = {
                            val idx = it.toInt().coerceIn(0, durationPresets.size - 1)
                            clipboardHistoryDuration = durationPresets[idx]
                            saveSetting("clipboard_history_duration", clipboardHistoryDuration.toString())
                            // Auto-link: "Never expire" + capped count → set count to unlimited too
                            if (clipboardHistoryDuration == -1 && clipboardHistoryLimit > 0) {
                                clipboardHistoryLimit = 0
                                saveSetting("clipboard_history_limit", 0)
                            }
                            // Trigger mid-session rescue for entries with stale expiry timestamps
                            ClipboardHistoryService.onDurationSettingChanged()
                        },
                        displayValue = when (clipboardHistoryDuration) {
                            -1 -> "Never expire"
                            60 -> "1 hour"
                            360 -> "6 hours"
                            720 -> "12 hours"
                            1440 -> "1 day"
                            4320 -> "3 days"
                            10080 -> "7 days"
                            20160 -> "14 days"
                            43200 -> "30 days"
                            else -> "${clipboardHistoryDuration / 60} hours"
                        }
                    )
                }

                // Size limit (only shown if limit type is "size")
                if (clipboardLimitType == "size") {
                    SettingsSlider(
                        title = stringResource(R.string.clipboard_size_limit_title),
                        description = stringResource(R.string.clipboard_size_limit_desc),
                        value = clipboardSizeLimitMb.toFloat(),
                        valueRange = 1f..100f,
                        steps = 99,
                        onValueChange = {
                            clipboardSizeLimitMb = it.toInt()
                            saveSetting("clipboard_size_limit_mb", clipboardSizeLimitMb)
                        },
                        displayValue = "$clipboardSizeLimitMb MB"
                    )
                }

                // Pane height percentage
                SettingsSlider(
                    title = stringResource(R.string.clipboard_pane_height_title),
                    description = stringResource(R.string.clipboard_pane_height_desc),
                    value = clipboardPaneHeightPercent.toFloat(),
                    valueRange = 10f..50f,
                    steps = 40,
                    onValueChange = {
                        clipboardPaneHeightPercent = it.toInt()
                        saveSetting("clipboard_pane_height_percent", clipboardPaneHeightPercent)
                    },
                    displayValue = "$clipboardPaneHeightPercent%"
                )

                // Max item size
                SettingsSlider(
                    title = stringResource(R.string.clipboard_max_item_size_title),
                    description = stringResource(R.string.clipboard_max_item_size_desc),
                    value = clipboardMaxItemSizeKb.toFloat(),
                    valueRange = 64f..1024f,
                    steps = 14,  // 64, 128, 192, 256, ... 1024
                    onValueChange = {
                        clipboardMaxItemSizeKb = it.toInt()
                        saveSetting("clipboard_max_item_size_kb", clipboardMaxItemSizeKb)
                    },
                    displayValue = "${clipboardMaxItemSizeKb}KB"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Privacy: Exclude password managers
                SettingsSwitch(
                    title = stringResource(R.string.clipboard_exclude_password_managers_title),
                    description = stringResource(R.string.clipboard_exclude_password_managers_desc),
                    checked = clipboardExcludePasswordManagers,
                    onCheckedChange = {
                        clipboardExcludePasswordManagers = it
                        saveSetting("clipboard_exclude_password_managers", clipboardExcludePasswordManagers)
                    }
                )

                // #86: Privacy: Respect IS_SENSITIVE flag (Android 13+)
                SettingsSwitch(
                    title = stringResource(R.string.clipboard_respect_sensitive_title),
                    description = stringResource(R.string.clipboard_respect_sensitive_desc),
                    checked = clipboardRespectSensitiveFlag,
                    onCheckedChange = {
                        clipboardRespectSensitiveFlag = it
                        saveSetting("clipboard_respect_sensitive_flag", clipboardRespectSensitiveFlag)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // v4: Feature toggles
                SettingsSwitch(
                    title = stringResource(R.string.clipboard_text_only_title),
                    description = stringResource(R.string.clipboard_text_only_desc),
                    checked = clipboardTextOnly,
                    onCheckedChange = {
                        clipboardTextOnly = it
                        saveSetting("clipboard_text_only", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_pinned_tab_title),
                    description = stringResource(R.string.clipboard_pinned_tab_desc),
                    checked = clipboardPinnedEnabled,
                    onCheckedChange = {
                        clipboardPinnedEnabled = it
                        saveSetting("clipboard_pinned_enabled", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_todo_tab_title),
                    description = stringResource(R.string.clipboard_todo_tab_desc),
                    checked = clipboardTodoEnabled,
                    onCheckedChange = {
                        clipboardTodoEnabled = it
                        saveSetting("clipboard_todo_enabled", it)
                    }
                )

                // ── #156 Private copy subsection ─────────────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "🔒 Private copy",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // PROCESS_TEXT selection-toolbar opt-in. Flips the exported (but manifest-disabled)
                // PrivateCopyProcessTextActivity component so it appears in other apps' text-selection
                // menus. Default OFF (design §6.6) — enabling re-expands an exported surface, so this
                // is the sole enabler and the pref is the single source of truth for the state.
                SettingsSwitch(
                    title = stringResource(R.string.clipboard_private_copy_other_apps_title),
                    description = stringResource(R.string.clipboard_private_copy_other_apps_desc),
                    checked = clipboardPrivateCopyToolbarEnabled,
                    onCheckedChange = {
                        clipboardPrivateCopyToolbarEnabled = it
                        saveSetting(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, it)
                        setPrivateCopyToolbarComponentEnabled(it)
                    }
                )

                Text(
                    text = "In-app: the \"Private copy\" editing action can be bound to a short swipe or extra key (Short Swipe Customization). It stores the current selection into CleverKeys' private clipboard without touching the system clipboard. Private entries show a 🔒 badge; exporting one to the system clipboard always asks first, and plaintext backups exclude them (encrypted backups include them).",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── URL handling subsection (Chunk 4) ───────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "URL handling",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_sanitize_links_title),
                    description = stringResource(R.string.clipboard_sanitize_links_desc),
                    checked = clipboardSanitizeLinksEnabled,
                    onCheckedChange = {
                        clipboardSanitizeLinksEnabled = it
                        saveSetting("clipboard_sanitize_links_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_sanitize_system_title),
                    description = stringResource(R.string.clipboard_sanitize_system_desc),
                    checked = clipboardSanitizeSystemClipboard,
                    onCheckedChange = {
                        clipboardSanitizeSystemClipboard = it
                        saveSetting("clipboard_sanitize_system_clipboard", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_embed_enrich_title),
                    description = stringResource(R.string.clipboard_embed_enrich_desc),
                    checked = clipboardEmbedEnrichEnabled,
                    onCheckedChange = {
                        clipboardEmbedEnrichEnabled = it
                        saveSetting("clipboard_embed_enrich_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.clipboard_custom_rules_title),
                    description = stringResource(R.string.clipboard_custom_rules_desc),
                    checked = clipboardCustomRulesEnabled,
                    onCheckedChange = {
                        clipboardCustomRulesEnabled = it
                        saveSetting("clipboard_custom_rules_enabled", it)
                        notifySanitizationRulesChanged()
                    }
                )

                if (clipboardCustomRulesEnabled) {
                    Button(
                        onClick = { customRulesPickerLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (clipboardCustomRulesUri == null)
                                "Browse for custom.substitutions.json"
                            else
                                "Replace custom rules"
                        )
                    }
                    if (clipboardCustomRulesStatus.isNotEmpty()) {
                        Text(
                            text = clipboardCustomRulesStatus,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "Note: cleaning runs when CleverKeys saves a clip to its history, so pastes from CleverKeys' panel are always sanitized. With \"Also clean system clipboard\" on, the Android system clipboard is overwritten with the cleaned URL too, so pastes in other apps are sanitized as well (best-effort — only while the keyboard has clipboard access). With it off, other apps still see the original URL.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
}

/**
 * #156: flip the exported (but manifest-disabled) [PrivateCopyProcessTextActivity] component so it
 * appears in — or vanishes from — other apps' text-selection menus. The pref
 * `clipboard_private_copy_toolbar_enabled` is the single source of truth; this derives the OS
 * component state from it. [PackageManager.DONT_KILL_APP] keeps the running IME alive across the
 * flip. Uses `COMPONENT_ENABLED_STATE_DISABLED` (not `_DEFAULT`) on the off-path so the manifest
 * default can change later without surprising users (design §6.6). Should be re-applied on settings
 * load so a fresh install / restore reconciles the OS state with the persisted pref.
 */
internal fun SettingsActivity.setPrivateCopyToolbarComponentEnabled(enabled: Boolean) {
    // Delegate to the shared Context-based reconciler (single source of truth) so the Settings-load /
    // toggle path and the backup/restore import path flip the component identically. #156 F5.
    tribixbite.cleverkeys.reconcilePrivateCopyToolbarComponent(this, enabled)
}
