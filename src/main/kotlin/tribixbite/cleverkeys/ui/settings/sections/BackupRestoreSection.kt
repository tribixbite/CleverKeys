package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                title = "💾 Backup & Restore",
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

                Text(
                    text = "Settings + dictionary imports show a preview so you can deselect entries before applying. Clipboard imports merge non-destructively (duplicates are skipped).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
}
