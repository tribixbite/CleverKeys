package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.SwipePerformanceStats
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.UserAdaptationManager
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.personalization.UserVocabulary
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.io.deleteCollectedData
import tribixbite.cleverkeys.ui.settings.io.exportPerfStats
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataJSON
import tribixbite.cleverkeys.ui.settings.io.exportSwipeDataNDJSON
import tribixbite.cleverkeys.ui.settings.io.viewCollectedData
import tribixbite.cleverkeys.ui.settings.io.viewPerfStats
import tribixbite.cleverkeys.ui.settings.saveSetting

@Composable
internal fun SettingsActivity.PrivacySection() {
            // Privacy Section (Collapsible)
            CollapsibleSettingsSection(
                title = stringResource(R.string.settings_section_privacy),
                expanded = privacySectionExpanded,
                onExpandChange = { privacySectionExpanded = it }
            ) {
                Text(
                    text = stringResource(R.string.privacy_section_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // ── MASTER on-device learning gate (Task A 2026-08-06) ──────
                // One clear switch that stops ALL typing-behavior learning at
                // the write layer: context LM (bigrams/trigrams), personalization
                // vocabulary, selection adaptation, and swipe-ML collection.
                var showForgetLearnedDialog by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(R.string.privacy_on_device_learning_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                SettingsSwitch(
                    title = stringResource(R.string.privacy_on_device_learning_title),
                    description = stringResource(R.string.privacy_on_device_learning_desc),
                    checked = onDeviceLearningEnabled,
                    onCheckedChange = { enabled ->
                        onDeviceLearningEnabled = enabled
                        saveSetting("on_device_learning_enabled", enabled)
                        Config.globalConfig()?.on_device_learning_enabled = enabled
                        if (!enabled) {
                            // Offer a one-tap "and forget what's already learned"
                            showForgetLearnedDialog = true
                        }
                    }
                )
                if (showForgetLearnedDialog) {
                    AlertDialog(
                        onDismissRequest = { showForgetLearnedDialog = false },
                        title = { Text(stringResource(R.string.privacy_forget_learned_title)) },
                        text = {
                            Text(stringResource(R.string.privacy_forget_learned_body))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showForgetLearnedDialog = false
                                val appContext = applicationContext
                                Thread {
                                    // Reuse the learned-data manager's forget APIs
                                    BigramStore.getInstance(appContext).clearAll()
                                    TrigramStore.getInstance(appContext).clearAll()
                                    UserVocabulary.getInstance(appContext).clearAll()
                                    UserAdaptationManager.getInstance(appContext).resetAdaptation()
                                }.start()
                            }) { Text(stringResource(R.string.privacy_forget_learned_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showForgetLearnedDialog = false }) {
                                Text(stringResource(R.string.privacy_forget_learned_keep))
                            }
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.privacy_local_collection_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                SettingsSwitch(
                    title = stringResource(R.string.privacy_swipe_pattern_title),
                    description = stringResource(R.string.privacy_swipe_pattern_desc),
                    checked = privacyCollectSwipe,
                    onCheckedChange = {
                        privacyCollectSwipe = it
                        saveSetting("privacy_collect_swipe", it)
                    }
                )

                SettingsSwitch(
                    title = stringResource(R.string.privacy_performance_metrics_title),
                    description = stringResource(R.string.privacy_performance_metrics_desc),
                    checked = privacyCollectPerformance,
                    onCheckedChange = {
                        privacyCollectPerformance = it
                        saveSetting("privacy_collect_performance", it)
                    }
                )

                // DEFERRED BY DESIGN (was a TODO; clarified 2026-08-06): the Error
                // Reports toggle stays hidden because no error-logging pipeline
                // exists — shipping the switch would be a placebo control, which
                // this settings screen must never do. If an error-report feature
                // is ever built, it should use async file logging (no keystroke
                // latency impact) and surface its toggle here, behind the same
                // privacy framing as the other collection switches.

                // Collected Data Stats and Export
                Text(
                    text = stringResource(R.string.privacy_collected_data_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                // Show stats
                val stats = remember {
                    try {
                        tribixbite.cleverkeys.ml.SwipeMLDataStore.getInstance(this@PrivacySection).getStatistics()
                    } catch (e: Exception) {
                        null
                    }
                }

                if (stats != null && stats.totalCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.privacy_swipe_stats,
                            stats.totalCount,
                            stats.uniqueWords
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Export buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportSwipeDataJSON() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.privacy_export_json))
                        }
                        OutlinedButton(
                            onClick = { exportSwipeDataNDJSON() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.privacy_export_ndjson))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // View and Delete buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewCollectedData() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.common_view))
                        }
                        OutlinedButton(
                            onClick = { deleteCollectedData() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.common_delete))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.privacy_no_swipe_data),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Performance Metrics Section
                Text(
                    text = stringResource(R.string.privacy_performance_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                val perfStats = remember {
                    try {
                        SwipePerformanceStats.getInstance(this@PrivacySection)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (perfStats != null && perfStats.hasStats()) {
                    Text(
                        text = stringResource(
                            R.string.privacy_perf_stats,
                            perfStats.getTotalPredictions(),
                            perfStats.getAverageInferenceTime(),
                            perfStats.getTop1Accuracy()
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewPerfStats() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.common_view))
                        }
                        OutlinedButton(
                            onClick = { exportPerfStats() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.common_export))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.privacy_no_perf_data),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
}
