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
                    text = "Export and import keyboard settings, dictionary, and clipboard history.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Stage B: backup-password block + encryption indicator (design §9).
                BackupPasswordBlock()
                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                // Configuration backup/restore
                Text(
                    text = "Configuration",
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
                        Text("Export Config")
                    }
                    Button(
                        onClick = { importConfiguration() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Config")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom dictionary backup/restore
                Text(
                    text = "Custom Dictionary",
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
                        Text("Export Dict")
                    }
                    Button(
                        onClick = { importCustomDictionary() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Dict")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clipboard history backup/restore — JSON (text-only) + ZIP (full backup with media)
                Text(
                    text = "Clipboard History",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Text only (JSON)",
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
                        Text("Export Clip")
                    }
                    Button(
                        onClick = { importClipboardHistory() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Clip")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Full backup (ZIP with media)",
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
                        Text("Export ZIP")
                    }
                    Button(
                        onClick = { importClipboardZip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import ZIP")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // GitHub #142: one-click full backup as dated ZIP (config + dicts + clipboard + media).
                Text(
                    text = "Full Backup",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Export everything (settings, dictionary, clipboard, media) into one dated ZIP file.",
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
                        Text("Export Full Backup")
                    }
                    Button(
                        onClick = { importFullBackup() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Full Backup")
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
                            if (pendingPlaintextExport) "Next export will be UNENCRYPTED — tap an Export button"
                            else "Export unencrypted…",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                    if (showPlaintextConfirm) {
                        AlertDialog(
                            onDismissRequest = { showPlaintextConfirm = false },
                            title = { Text("Export without encryption?") },
                            text = {
                                Text(
                                    "The next export you start will be written as PLAINTEXT — " +
                                        "anyone who obtains the file can read your settings, " +
                                        "dictionary, or clipboard contents. Only do this if you " +
                                        "will post-process the file yourself. Continue?"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingPlaintextExport = true
                                    showPlaintextConfirm = false
                                }) { Text("I understand — arm plaintext export") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPlaintextConfirm = false }) { Text("Cancel") }
                            },
                        )
                    }
                }

                Text(
                    text = "Settings + dictionary imports show a preview so you can deselect entries before applying. Clipboard imports merge non-destructively (duplicates are skipped).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
}
