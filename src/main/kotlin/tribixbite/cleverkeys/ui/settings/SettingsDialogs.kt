@file:OptIn(ExperimentalMaterial3Api::class)

package tribixbite.cleverkeys.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.io.loadCollectedDataPage

/**
 * Dialog to view collected swipe data with search and pagination
 */
@Composable
internal fun SettingsActivity.CollectedDataViewerDialog(
    dataList: List<tribixbite.cleverkeys.ml.SwipeMLData>,
    stats: tribixbite.cleverkeys.ml.SwipeMLDataStore.DataStatistics?,
    onDismiss: () -> Unit
) {
    val _self = this  // capture extension receiver for use inside non-inline lambdas
    val clipboardManager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val totalPages = if (collectedDataTotalCount > 0) {
        (collectedDataTotalCount + collectedDataPageSize - 1) / collectedDataPageSize
    } else 0

    AlertDialog(
        onDismissRequest = {
            // Reset search state on dismiss
            collectedDataSearchQuery = ""
            collectedDataCurrentPage = 0
            onDismiss()
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_swipe_data_title))
                IconButton(
                    onClick = {
                        collectedDataSearchQuery = ""
                        collectedDataCurrentPage = 0
                        loadCollectedDataPage()
                    }
                ) {
                    // Glyph kept as-is; TalkBack reads the localized action instead of the symbol name.
                    val refreshDescription = stringResource(R.string.settings_swipe_data_refresh)
                    Text(
                        "↺",
                        fontSize = 18.sp,
                        modifier = Modifier.semantics { contentDescription = refreshDescription }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = collectedDataSearchQuery,
                    onValueChange = { query ->
                        collectedDataSearchQuery = query
                        collectedDataCurrentPage = 0
                        loadCollectedDataPage()
                    },
                    placeholder = { Text(stringResource(R.string.settings_search_words_hint), fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                // Stats summary
                if (stats != null) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_swipe_data_summary,
                            stats.uniqueWords,
                            stats.uniqueWords,
                            dataList.size,
                            collectedDataTotalCount
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Pagination controls
                if (totalPages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (collectedDataCurrentPage > 0) {
                                    collectedDataCurrentPage--
                                    loadCollectedDataPage()
                                }
                            },
                            enabled = collectedDataCurrentPage > 0
                        ) {
                            // AutoMirrored: "previous" points toward the reading start in RTL too.
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.common_previous_page)
                            )
                        }
                        Text(
                            text = "${collectedDataCurrentPage + 1} / $totalPages",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        IconButton(
                            onClick = {
                                if (collectedDataCurrentPage < totalPages - 1) {
                                    collectedDataCurrentPage++
                                    loadCollectedDataPage()
                                }
                            },
                            enabled = collectedDataCurrentPage < totalPages - 1
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.common_next_page)
                            )
                        }
                    }
                }

                if (dataList.isEmpty()) {
                    Text(
                        text = if (collectedDataSearchQuery.isNotEmpty()) {
                            stringResource(R.string.settings_swipe_data_no_results, collectedDataSearchQuery)
                        } else {
                            stringResource(R.string.settings_swipe_data_empty)
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Scrollable list of entries
                    val listScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .verticalScroll(listScrollState)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dataList.forEach { data ->
                            val dateFormat = java.text.SimpleDateFormat(
                                "MM/dd HH:mm",
                                java.util.Locale.getDefault()
                            )
                            val dateStr = dateFormat.format(java.util.Date(data.timestampUtc))
                            val keys = data.getRegisteredKeys().joinToString("")
                            val points = data.getTracePoints().size

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Copy full trace data to clipboard
                                        val traceJson = data.toJSON().toString(2)
                                        val clip = android.content.ClipData.newPlainText("Swipe Trace", traceJson)
                                        clipboardManager.setPrimaryClip(clip)
                                        Toast.makeText(
                                            _self,
                                            _self.getString(R.string.settings_swipe_data_trace_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_swipe_data_target_word, data.targetWord),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = dateStr,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_swipe_data_keys, keys),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = pluralStringResource(R.plurals.settings_swipe_data_points, points, points),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                collectedDataSearchQuery = ""
                collectedDataCurrentPage = 0
                onDismiss()
            }) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/**
 * Dialog to view performance statistics
 */
@Composable
internal fun SettingsActivity.PerfStatsViewerDialog(
    summary: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_perf_stats_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                val scrollState = rememberScrollState()
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
