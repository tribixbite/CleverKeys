package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.io.exportClipboardHistory
import tribixbite.cleverkeys.ui.settings.io.exportClipboardZip
import tribixbite.cleverkeys.ui.settings.io.exportConfiguration
import tribixbite.cleverkeys.ui.settings.io.exportCustomDictionary
import tribixbite.cleverkeys.ui.settings.io.exportFullBackup
import tribixbite.cleverkeys.ui.settings.io.importClipboardHistory
import tribixbite.cleverkeys.ui.settings.io.importClipboardZip
import tribixbite.cleverkeys.ui.settings.io.importConfiguration
import tribixbite.cleverkeys.ui.settings.io.importCustomDictionary
import tribixbite.cleverkeys.ui.settings.io.importFullBackup

@Composable
internal fun SettingsActivity.BackupRestoreSection() {
            // Backup & Restore Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_backup_restore),
                expanded = backupRestoreSectionExpanded,
                onExpandChange = { backupRestoreSectionExpanded = it },
                sectionId = "backup_restore"
            ) {
                Text(
                    text = stringResource(R.string.backup_section_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Stage B: backup-password block + encryption indicator (design §9).
                BackupPasswordBlock()
                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                // Configuration backup/restore
                Text(
                    text = stringResource(R.string.backup_section_configuration),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportConfiguration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_export_config))
                    }
                    Button(
                        onClick = { importConfiguration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_import_config))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom dictionary backup/restore
                Text(
                    text = stringResource(R.string.backup_section_custom_dictionary),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportCustomDictionary() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_export_dict))
                    }
                    Button(
                        onClick = { importCustomDictionary() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_import_dict))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clipboard history backup/restore — JSON (text-only) + ZIP (full backup with media)
                Text(
                    text = stringResource(R.string.backup_section_clipboard_history),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.backup_section_clipboard_text_only),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportClipboardHistory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_export_clip))
                    }
                    Button(
                        onClick = { importClipboardHistory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_import_clip))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.backup_section_clipboard_zip),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportClipboardZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_export_zip))
                    }
                    Button(
                        onClick = { importClipboardZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_import_zip))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // GitHub #142: one-click full backup as dated ZIP (config + dicts + clipboard + media).
                Text(
                    text = stringResource(R.string.backup_section_full_backup),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.backup_section_full_backup_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportFullBackup() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_export_full))
                    }
                    Button(
                        onClick = { importFullBackup() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.backup_section_import_full))
                    }
                }

                // Stage B: plaintext opt-out (design §10 decision 1 — kept). Only
                // meaningful once a password is set (otherwise exports are already
                // plaintext). Arms a one-shot flag consumed by the NEXT export tap.
                if (backupPassphraseStore.hasPassphrase()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    var showPlaintextConfirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { showPlaintextConfirm = true }) {
                        Text(
                            stringResource(
                                if (pendingPlaintextExport) R.string.backup_section_plaintext_armed
                                else R.string.backup_section_plaintext_offer
                            ),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                    if (showPlaintextConfirm) {
                        AlertDialog(
                            onDismissRequest = { showPlaintextConfirm = false },
                            title = { Text(stringResource(R.string.backup_section_plaintext_dialog_title)) },
                            text = {
                                Text(
                                    stringResource(R.string.backup_section_plaintext_dialog_body)
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingPlaintextExport = true
                                    showPlaintextConfirm = false
                                }) {
                                    Text(
                                        stringResource(
                                            R.string.backup_section_plaintext_dialog_confirm
                                        )
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPlaintextConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
                            },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.backup_section_preview_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
}
