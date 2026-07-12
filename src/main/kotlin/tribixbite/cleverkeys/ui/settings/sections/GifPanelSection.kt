package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.performGifRemoveAll
import tribixbite.cleverkeys.ui.settings.io.performGifRemovePack
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.GifPanelSection() {
            // GIF Panel Section (Collapsible) — opt-in, off by default
            CollapsibleSettingsSection(
                title = "🎬 GIF Panel",
                expanded = gifSectionExpanded,
                onExpandChange = { gifSectionExpanded = it }
            ) {
                Text(
                    text = "Offline GIF reactions. Import packs from ZIP files (download from GitHub Releases).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Master toggle
                SettingsSwitch(
                    title = "Enable GIF Panel",
                    description = "Show GIF key on keyboard and enable reaction picker",
                    checked = gifEnabled,
                    onCheckedChange = {
                        gifEnabled = it
                        saveSetting("gif_enabled", it)
                    }
                )

                if (gifEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // "Get Packs" button — opens browser to GitHub Releases
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            try {
                                startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(tribixbite.cleverkeys.gif.GifPackManager.GITHUB_RELEASES_URL)
                                ))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@GifPanelSection, "Could not open browser", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Get GIF Packs (opens browser)")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // "Import Pack" button — opens file picker
                    androidx.compose.material3.Button(
                        onClick = {
                            try {
                                gifPackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@GifPanelSection, "Could not open file picker", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !gifImportInProgress
                    ) {
                        if (gifImportInProgress) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Importing...")
                        } else {
                            Text("Import Pack from ZIP")
                        }
                    }

                    // Import status message
                    gifImportStatus?.let { status ->
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = if (status.startsWith("Error")) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Installed packs list
                    if (installedGifPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Installed Packs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        installedGifPacks.forEach { pack ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pack.name, fontSize = 14.sp)
                                    Text(
                                        "${pack.gifCount} GIFs | ${tribixbite.cleverkeys.gif.GifPackManager.formatBytes(pack.sizeBytes)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    onClick = { showGifRemovePackDialog = pack.packId }
                                ) {
                                    Text("X", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Total storage
                        Text(
                            text = "Total: ${tribixbite.cleverkeys.gif.GifPackManager.formatBytes(gifStorageUsed)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Grid columns slider
                    SettingsSlider(
                        title = "Grid Columns",
                        description = "Number of columns in GIF picker grid",
                        value = gifThumbnailColumns.toFloat(),
                        valueRange = 2f..5f,
                        steps = 3,
                        onValueChange = {
                            gifThumbnailColumns = it.toInt()
                            saveSetting("gif_thumbnail_columns", gifThumbnailColumns)
                        },
                        displayValue = "$gifThumbnailColumns columns"
                    )

                    // Remove all GIF data (destructive, with confirmation)
                    if (installedGifPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { showGifRemoveAllDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Remove All GIF Data")
                        }
                    }
                }
            }

            // GIF pack removal confirmation dialogs
            if (showGifRemoveAllDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemoveAllDialog = false },
                    title = { Text("Remove All GIF Data?") },
                    text = { Text("This will delete all imported GIF packs, thumbnails, and database. This cannot be undone.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemoveAllDialog = false
                            performGifRemoveAll()
                        }) { Text("Remove All", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemoveAllDialog = false }) { Text("Cancel") }
                    }
                )
            }

            showGifRemovePackDialog?.let { packId ->
                val packName = installedGifPacks.find { it.packId == packId }?.name ?: packId
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemovePackDialog = null },
                    title = { Text("Remove $packName?") },
                    text = { Text("This will delete all GIFs from this pack and reclaim storage space.") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemovePackDialog = null
                            performGifRemovePack(packId)
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemovePackDialog = null }) { Text("Cancel") }
                    }
                )
            }
}
