package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.personalization.UserVocabulary

/**
 * Learning & Data manager (audit 2026-08-06 §3.3): shows what the keyboard has
 * learned on-device (context-LM bigram counts per language, personalization
 * vocabulary stats) and gives the user delete controls with count-bearing
 * confirmations. Export/import rides the standard Backup & Restore dictionary
 * payload (`learned_bigrams_by_language` / `user_vocabulary`).
 *
 * Rendered inside the Advanced Prediction block of [InputBehaviorSection].
 * All store reads/writes happen off the main thread.
 */
// TODO(audit §3.3): per-word browse/delete list UI (BigramStore.removeBigram /
// UserVocabulary.removeWord are implemented and tested; only the list UI remains).
@Composable
internal fun SettingsActivity.LearningDataManagerBlock() {
    var bigramSummary by remember { mutableStateOf("Loading…") }
    var bigramTotal by remember { mutableIntStateOf(0) }
    var vocabSummary by remember { mutableStateOf("Loading…") }
    var vocabTotal by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showClearBigrams by remember { mutableStateOf(false) }
    var showClearVocab by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        val (bigramText, bigramCount, vocabText, vocabCount) = withContext(Dispatchers.IO) {
            val store = BigramStore.getInstance(this@LearningDataManagerBlock)
            val perLang = store.getKnownLanguages().sorted().mapNotNull { lang ->
                val stats = store.getStatistics(lang)
                if (stats.totalBigrams > 0) "$lang: ${stats.totalBigrams} pairs" else null
            }
            val total = store.getKnownLanguages().sumOf { store.getTotalBigramCount(it) }
            val bigramText = if (perLang.isEmpty()) "No learned word pairs yet"
            else perLang.joinToString("  ·  ")

            val vocab = UserVocabulary.getInstance(this@LearningDataManagerBlock)
            val stats = vocab.getStats()
            val vocabText = if (stats.totalWords == 0) "No learned words yet"
            else buildString {
                append("${stats.totalWords} words")
                stats.mostUsedWord?.let { append("  ·  most used: “${it.word}” (${it.usageCount}×)") }
            }
            Quad(bigramText, total, vocabText, stats.totalWords)
        }
        bigramSummary = bigramText
        bigramTotal = bigramCount
        vocabSummary = vocabText
        vocabTotal = vocabCount
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

    if (showClearBigrams) {
        AlertDialog(
            onDismissRequest = { showClearBigrams = false },
            title = { Text("Forget phrase patterns?") },
            text = {
                Text(
                    "Delete $bigramTotal learned word pairs across all languages? " +
                        "Context-aware suggestions will relearn from your typing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearBigrams = false
                    Thread {
                        BigramStore.getInstance(this@LearningDataManagerBlock).clearAll()
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

/** Simple 4-tuple for the single stats payload crossing the IO→UI boundary. */
private data class Quad(
    val bigramText: String,
    val bigramCount: Int,
    val vocabText: String,
    val vocabCount: Int
)
