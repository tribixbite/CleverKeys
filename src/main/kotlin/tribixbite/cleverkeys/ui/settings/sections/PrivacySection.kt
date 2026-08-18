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
                    text = "CleverKeys is fully offline — no data ever leaves your device. " +
                           "These optional settings store local data for potential future on-device model fine-tuning.",
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
                    text = "On-Device Learning",
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
                        title = { Text("Also forget learned data?") },
                        text = {
                            Text(
                                "Learning is now off — nothing new will be recorded. " +
                                    "Do you also want to delete everything already learned " +
                                    "(phrase patterns, word usage, and selection history)? " +
                                    "This cannot be undone."
                            )
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
                            }) { Text("Delete learned data") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showForgetLearnedDialog = false }) {
                                Text("Keep it")
                            }
                        }
                    )
                }

                Text(
                    text = "Local Data Collection (Optional)",
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
                    text = "Collected Data",
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
                        text = "Total swipes: ${stats.totalCount} • Unique words: ${stats.uniqueWords}",
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
                            Text("Export JSON")
                        }
                        OutlinedButton(
                            onClick = { exportSwipeDataNDJSON() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export NDJSON")
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
                            Text("View")
                        }
                        OutlinedButton(
                            onClick = { deleteCollectedData() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                } else {
                    Text(
                        text = "No swipe data collected yet. Enable collection above to start storing patterns for future on-device learning.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Performance Metrics Section
                Text(
                    text = "Performance Metrics",
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
                        text = "Predictions: ${perfStats.getTotalPredictions()} • Avg: ${perfStats.getAverageInferenceTime()}ms • Top-1: ${perfStats.getTop1Accuracy()}%",
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
                            Text("View")
                        }
                        OutlinedButton(
                            onClick = { exportPerfStats() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export")
                        }
                    }
                } else {
                    Text(
                        text = "No performance data collected yet. Enable collection above and use swipe typing.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
}
