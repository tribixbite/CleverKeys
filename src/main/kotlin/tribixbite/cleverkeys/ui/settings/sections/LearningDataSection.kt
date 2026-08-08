package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.personalization.UserVocabulary
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * Learning & Data manager (audit 2026-08-06 §3.3): shows what the keyboard has
 * learned on-device (context-LM bigram/trigram counts per language,
 * personalization vocabulary stats), lets the user BROWSE and DELETE individual
 * learned phrases/words (per-word delete via the tested
 * [BigramStore.removeBigram] / `UserVocabulary.removeWord` APIs), and gives
 * bulk delete controls with count-bearing confirmations. Export/import rides
 * the standard Backup & Restore dictionary payload
 * (`learned_bigrams_by_language` / `user_vocabulary`).
 *
 * Rendered inside the Advanced Prediction block of [InputBehaviorSection].
 * All store reads/writes happen off the main thread.
 */
@Composable
internal fun SettingsActivity.LearningDataManagerBlock() {
    var bigramSummary by remember { mutableStateOf("Loading…") }
    var bigramTotal by remember { mutableIntStateOf(0) }
    var vocabSummary by remember { mutableStateOf("Loading…") }
    var vocabTotal by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showClearBigrams by remember { mutableStateOf(false) }
    var showClearVocab by remember { mutableStateOf(false) }
    var showBrowsePhrases by remember { mutableStateOf(false) }
    var showBrowseWords by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        val stats = withContext(Dispatchers.IO) {
            val store = BigramStore.getInstance(this@LearningDataManagerBlock)
            val trigrams = TrigramStore.getInstance(this@LearningDataManagerBlock)
            val perLang = store.getKnownLanguages().sorted().mapNotNull { lang ->
                val s = store.getStatistics(lang)
                val triples = trigrams.getTotalTrigramCount(lang)
                if (s.totalBigrams > 0 || triples > 0) {
                    buildString {
                        append("$lang: ${s.totalBigrams} pairs")
                        if (triples > 0) append(", $triples triples")
                    }
                } else {
                    null
                }
            }
            val total = store.getKnownLanguages().sumOf { store.getTotalBigramCount(it) } +
                trigrams.getKnownLanguages().sumOf { trigrams.getTotalTrigramCount(it) }
            val bigramText = if (perLang.isEmpty()) "No learned word pairs yet"
            else perLang.joinToString("  ·  ")

            val vocab = UserVocabulary.getInstance(this@LearningDataManagerBlock)
            val vStats = vocab.getStats()
            val vocabText = if (vStats.totalWords == 0) "No learned words yet"
            else buildString {
                append("${vStats.totalWords} words")
                vStats.mostUsedWord?.let { append("  ·  most used: “${it.word}” (${it.usageCount}×)") }
            }
            LearnedDataSummary(bigramText, total, vocabText, vStats.totalWords)
        }
        bigramSummary = stats.bigramText
        bigramTotal = stats.bigramCount
        vocabSummary = stats.vocabText
        vocabTotal = stats.vocabCount
    }

    // Enforce a LOWERED vocabulary cap off the hot path: debounce past slider
    // drag, then evict lowest-value words down to the new cap on IO. No-op
    // (and no stats refresh) when already within the cap.
    LaunchedEffect(personalizationMaxWords) {
        delay(600)
        val evicted = withContext(Dispatchers.IO) {
            UserVocabulary.getInstance(this@LearningDataManagerBlock).enforceCap()
        }
        if (evicted > 0) refreshKey++
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Learning & Data", fontWeight = FontWeight.SemiBold)
        Text(
            "All learning stays on this device. Learned phrase patterns and word " +
                "usage are included in dictionary exports (Backup & Restore).",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Phrase patterns — $bigramSummary", style = MaterialTheme.typography.bodySmall)
        Text("Word usage — $vocabSummary", style = MaterialTheme.typography.bodySmall)

        // User-configurable learned-vocabulary size cap (personalization_max_words).
        // Lowering below the current word count evicts least-valuable words first
        // (debounced enforcement above).
        SettingsSlider(
            title = stringResource(R.string.input_personalization_max_words_title),
            description = stringResource(R.string.input_personalization_max_words_desc),
            value = personalizationMaxWords.toFloat(),
            valueRange = 1000f..20000f,
            steps = 37, // 500-word increments
            onValueChange = {
                val snapped = ((it / 500f).roundToInt() * 500).coerceIn(1000, 20000)
                if (snapped != personalizationMaxWords) {
                    personalizationMaxWords = snapped
                    saveSetting("personalization_max_words", snapped)
                    Config.globalConfig()?.personalization_max_words = snapped
                }
            },
            displayValue = "$personalizationMaxWords words"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showBrowsePhrases = true },
                enabled = bigramTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Browse phrases") }
            OutlinedButton(
                onClick = { showBrowseWords = true },
                enabled = vocabTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Browse words") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showClearBigrams = true },
                enabled = bigramTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Forget phrases") }
            OutlinedButton(
                onClick = { showClearVocab = true },
                enabled = vocabTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Forget words") }
        }
    }

    if (showBrowsePhrases) {
        LearnedPhraseBrowserDialog(
            onDismiss = {
                showBrowsePhrases = false
                refreshKey++
            }
        )
    }

    if (showBrowseWords) {
        LearnedWordBrowserDialog(
            onDismiss = {
                showBrowseWords = false
                refreshKey++
            }
        )
    }

    if (showClearBigrams) {
        AlertDialog(
            onDismissRequest = { showClearBigrams = false },
            title = { Text("Forget phrase patterns?") },
            text = {
                Text(
                    "Delete $bigramTotal learned word pairs and triples across all " +
                        "languages? Context-aware suggestions will relearn from your typing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearBigrams = false
                    Thread {
                        BigramStore.getInstance(this@LearningDataManagerBlock).clearAll()
                        TrigramStore.getInstance(this@LearningDataManagerBlock).clearAll()
                        runOnUiThread { refreshKey++ }
                    }.start()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearBigrams = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearVocab) {
        AlertDialog(
            onDismissRequest = { showClearVocab = false },
            title = { Text("Forget word usage?") },
            text = {
                Text(
                    "Delete $vocabTotal learned words? Personalized boosting will " +
                        "relearn from your typing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearVocab = false
                    Thread {
                        UserVocabulary.getInstance(this@LearningDataManagerBlock).clearAll()
                        runOnUiThread { refreshKey++ }
                    }.start()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearVocab = false }) { Text("Cancel") }
            }
        )
    }
}

/** One learned phrase row in the browser (bigram + its language). */
private data class PhraseRow(
    val language: String,
    val word1: String,
    val word2: String,
    val frequency: Int
)

/** Cap on rows shown in a browser dialog (frequency-ranked, so the head matters). */
private const val BROWSE_LIMIT = 200

/**
 * Per-phrase browse/delete dialog (audit §3.3): every learned bigram across all
 * languages, most frequent first, with per-entry delete via
 * [BigramStore.removeBigram]. NOTE: trigrams are not individually browsable —
 * they are bulk-cleared via "Forget phrases" (documented scope).
 */
@Composable
private fun LearnedPhraseBrowserDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<PhraseRow>>(emptyList()) }
    var loadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadKey) {
        rows = withContext(Dispatchers.IO) {
            val store = BigramStore.getInstance(context)
            store.getKnownLanguages().sorted().flatMap { lang ->
                store.getAllEntries(lang).map { PhraseRow(lang, it.word1, it.word2, it.frequency) }
            }.sortedByDescending { it.frequency }.take(BROWSE_LIMIT)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Learned phrases") },
        text = {
            if (rows.isEmpty()) {
                Text("No learned phrases.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(rows, key = { "${it.language}|${it.word1}|${it.word2}" }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${row.word1} → ${row.word2}  (${row.frequency}×, ${row.language})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                Thread {
                                    BigramStore.getInstance(context)
                                        .removeBigram(row.language, row.word1, row.word2)
                                    loadKey++
                                }.start()
                            }) { Text("✕") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * Per-word browse/delete dialog (audit §3.3): the personalization vocabulary's
 * top words with usage counts, per-entry delete via `UserVocabulary.removeWord`.
 */
@Composable
private fun LearnedWordBrowserDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(loadKey) {
        rows = withContext(Dispatchers.IO) {
            UserVocabulary.getInstance(context)
                .getTopWords(BROWSE_LIMIT)
                .map { it.word to it.usageCount }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Learned words") },
        text = {
            if (rows.isEmpty()) {
                Text("No learned words.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(rows, key = { it.first }) { (word, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$word  ($count×)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                Thread {
                                    UserVocabulary.getInstance(context).removeWord(word)
                                    loadKey++
                                }.start()
                            }) { Text("✕") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** Single stats payload crossing the IO→UI boundary. */
private data class LearnedDataSummary(
    val bigramText: String,
    val bigramCount: Int,
    val vocabText: String,
    val vocabCount: Int
)
