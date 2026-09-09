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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import tribixbite.cleverkeys.ui.settings.scrollToSetting

/**
 * Live totals for the three stores the master learning gate can erase. Summed
 * across every language the stores know about — the per-language breakdown lives
 * in the Learning Data manager (Input Behaviour → Advanced Prediction), which
 * also owns browse and per-entry delete.
 */
private data class LearnedCounts(val pairs: Int, val triples: Int, val words: Int) {
    val isEmpty: Boolean get() = pairs == 0 && triples == 0 && words == 0
}

/**
 * Erase every store fed by the on-device learning gate. Shared by the master
 * switch's "forget what's already learned" prompt and the explicit
 * "Forget learned data" button so the two can never diverge on what "forget"
 * covers. Blocking — call from a background thread.
 */
private fun clearLearnedLanguageData(context: Context) {
    BigramStore.getInstance(context).clearAll()
    TrigramStore.getInstance(context).clearAll()
    UserVocabulary.getInstance(context).clearAll()
    UserAdaptationManager.getInstance(context).resetAdaptation()
}

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
                // Bumped whenever anything below erases a learned store, so the
                // counts re-read instead of reporting pre-delete totals.
                var learnedRefreshKey by remember { mutableIntStateOf(0) }
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
                                    clearLearnedLanguageData(appContext)
                                    runOnUiThread { learnedRefreshKey++ }
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

                // ── Learned language data (context LM + personalization) ────
                // The master gate above can already ERASE these stores, but until
                // 2026-09-09 the section never SHOWED them: the largest body of
                // learned text on the device — bigrams, trigrams and the
                // personalization vocabulary — had no counts here, so "forget"
                // was unverifiable and the user could not tell what was held.
                // Counts only; browse and per-entry delete stay in the Learning
                // Data manager rather than being duplicated.
                var learnedCounts by remember { mutableStateOf<LearnedCounts?>(null) }
                var showForgetLearnedDataDialog by remember { mutableStateOf(false) }

                LaunchedEffect(learnedRefreshKey) {
                    val appContext = applicationContext
                    learnedCounts = withContext(Dispatchers.IO) {
                        val bigrams = BigramStore.getInstance(appContext)
                        val trigrams = TrigramStore.getInstance(appContext)
                        LearnedCounts(
                            pairs = bigrams.getKnownLanguages()
                                .sumOf { bigrams.getTotalBigramCount(it) },
                            triples = trigrams.getKnownLanguages()
                                .sumOf { trigrams.getTotalTrigramCount(it) },
                            words = UserVocabulary.getInstance(appContext).getStats().totalWords
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.privacy_learned_data_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.privacy_learned_data_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                val counts = learnedCounts
                Text(
                    text = when {
                        counts == null -> stringResource(R.string.learning_data_loading)
                        counts.isEmpty -> stringResource(R.string.privacy_learned_none)
                        else -> stringResource(
                            R.string.privacy_learned_counts,
                            counts.pairs, counts.triples, counts.words
                        )
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Which learn paths are live right now. The three source toggles are
                // configured in Input Behaviour → Advanced Prediction; naming their
                // state here (and offering a jump) keeps this section a truthful
                // account of what is being recorded without duplicating controls
                // that would then need two-way sync and a second search entry.
                val sourceLabels = listOfNotNull(
                    stringResource(R.string.input_context_aware_title)
                        .takeIf { contextAwarePredictionsEnabled },
                    stringResource(R.string.input_next_word_title)
                        .takeIf { nextWordPredictionEnabled },
                    stringResource(R.string.input_personalized_learning_title)
                        .takeIf { personalizedLearningEnabled }
                )
                // The master gate outranks every source toggle, so with it off
                // nothing is recording regardless of the three below it.
                val recordingNow = if (onDeviceLearningEnabled) sourceLabels else emptyList()
                Text(
                    text = if (recordingNow.isEmpty()) {
                        stringResource(R.string.privacy_learned_sources_none)
                    } else {
                        stringResource(
                            R.string.privacy_learned_sources,
                            recordingNow.joinToString(", ")
                        )
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            // Open the panel that owns the three source toggles. The
                            // scroll only lands once the target has been composed (it
                            // registers its position then); expanding is the part that
                            // always works, so the control is reachable either way.
                            inputSectionExpanded = true
                            wordPredictionAdvancedExpanded = true
                            scrollToSetting("context_aware_predictions")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.privacy_learned_manage)) }
                    OutlinedButton(
                        onClick = { showForgetLearnedDataDialog = true },
                        enabled = counts != null && !counts.isEmpty,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.privacy_learned_forget)) }
                }

                if (showForgetLearnedDataDialog) {
                    AlertDialog(
                        onDismissRequest = { showForgetLearnedDataDialog = false },
                        title = { Text(stringResource(R.string.privacy_forget_learned_title)) },
                        text = { Text(stringResource(R.string.privacy_learned_forget_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showForgetLearnedDataDialog = false
                                val appContext = applicationContext
                                Thread {
                                    clearLearnedLanguageData(appContext)
                                    runOnUiThread { learnedRefreshKey++ }
                                }.start()
                            }) { Text(stringResource(R.string.common_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showForgetLearnedDataDialog = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.privacy_local_collection_header),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
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

                // Show stats. Keyed on a refresh counter: a keyless remember{} froze
                // the totals for the whole composition, so after Delete emptied the
                // store the old count and the export buttons stayed on screen. The
                // handler used to paper over that by recreate()-ing the activity,
                // which collapsed every section and threw away the user's scroll
                // position — bumping the key re-reads just this block instead.
                var collectedRefreshKey by remember { mutableIntStateOf(0) }
                val stats = remember(collectedRefreshKey) {
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
                            onClick = { deleteCollectedData { collectedRefreshKey++ } },
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
