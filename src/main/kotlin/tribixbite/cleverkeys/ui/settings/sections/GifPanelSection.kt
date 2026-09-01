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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.GifImportStatus
import tribixbite.cleverkeys.ui.settings.io.performGifRemoveAll
import tribixbite.cleverkeys.ui.settings.io.performGifRemovePack
import tribixbite.cleverkeys.ui.settings.saveSetting

/** Per-pack remove affordance. A glyph, not English copy — deliberately not a resource. */
private const val REMOVE_PACK_GLYPH = "X"

@Composable
internal fun SettingsActivity.GifPanelSection() {
            // GIF Panel Section (Collapsible) — opt-in, off by default
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_gif_panel),
                expanded = gifSectionExpanded,
                onExpandChange = { gifSectionExpanded = it }
            ) {
                Text(
                    text = stringResource(R.string.gif_section_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Master toggle — the gate target for search results that are
                // gatedBy="gif_enabled"; highlightId registers a scroll position
                // so search redirect can reach it when GIF is disabled.
                SettingsSwitch(
                    title = stringResource(R.string.gif_enable_title),
                    description = stringResource(R.string.gif_enable_desc),
                    checked = gifEnabled,
                    highlightId = "gif_enabled",
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
                                android.widget.Toast.makeText(this@GifPanelSection, getString(R.string.gif_toast_no_browser), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.gif_get_packs))
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // "Import Pack" button — opens file picker
                    androidx.compose.material3.Button(
                        onClick = {
                            try {
                                gifPackImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@GifPanelSection, getString(R.string.gif_toast_no_file_picker), android.widget.Toast.LENGTH_SHORT).show()
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
                            Text(stringResource(R.string.gif_importing))
                        } else {
                            Text(stringResource(R.string.gif_import_pack))
                        }
                    }

                    // Import status message. ARC-075: the colour comes from the status VARIANT,
                    // never from the message text — a failure reported in the user's language
                    // (or reworded upstream) must still render as a failure.
                    gifImportStatus?.let { status ->
                        Text(
                            text = status.message,
                            fontSize = 12.sp,
                            color = when (status) {
                                is GifImportStatus.Failed -> MaterialTheme.colorScheme.error
                                is GifImportStatus.Ok -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Installed packs list
                    if (installedGifPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.gif_installed_packs),
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
                                        pluralStringResource(
                                            R.plurals.gif_pack_stats,
                                            pack.gifCount,
                                            pack.gifCount,
                                            tribixbite.cleverkeys.gif.GifPackManager.formatBytes(pack.sizeBytes)
                                        ),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    onClick = { showGifRemovePackDialog = pack.packId }
                                ) {
                                    Text(REMOVE_PACK_GLYPH, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Total storage
                        Text(
                            text = stringResource(
                                R.string.gif_total_storage,
                                tribixbite.cleverkeys.gif.GifPackManager.formatBytes(gifStorageUsed)
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Grid columns slider
                    SettingsSlider(
                        title = stringResource(R.string.gif_grid_columns_title),
                        description = stringResource(R.string.gif_grid_columns_desc),
                        value = gifThumbnailColumns.toFloat(),
                        valueRange = 2f..5f,
                        steps = 3,
                        onValueChange = {
                            gifThumbnailColumns = it.toInt()
                            saveSetting("gif_thumbnail_columns", gifThumbnailColumns)
                        },
                        displayValue = pluralStringResource(
                            R.plurals.gif_grid_columns_display, gifThumbnailColumns, gifThumbnailColumns
                        )
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
                            Text(stringResource(R.string.gif_remove_all))
                        }
                    }
                }
            }

            // GIF pack removal confirmation dialogs
            if (showGifRemoveAllDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemoveAllDialog = false },
                    title = { Text(stringResource(R.string.gif_remove_all_title)) },
                    text = { Text(stringResource(R.string.gif_remove_all_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemoveAllDialog = false
                            performGifRemoveAll()
                        }) { Text(stringResource(R.string.gif_remove_all_confirm), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemoveAllDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            showGifRemovePackDialog?.let { packId ->
                val packName = installedGifPacks.find { it.packId == packId }?.name ?: packId
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGifRemovePackDialog = null },
                    title = { Text(stringResource(R.string.gif_remove_pack_confirm, packName)) },
                    text = { Text(stringResource(R.string.gif_remove_pack_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showGifRemovePackDialog = null
                            performGifRemovePack(packId)
                        }) { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGifRemovePackDialog = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }
}
