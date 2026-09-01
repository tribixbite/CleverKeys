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
import androidx.compose.ui.res.pluralStringResource
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
 * Separator between the clauses of a stats summary line. Layout punctuation, not
 * translatable copy — kept in Kotlin so the per-clause resources stay standalone
 * sentences a translator can reorder freely.
 */
private const val SUMMARY_SEPARATOR = "  ·  "

/** Per-row delete affordance. A glyph, not English text — deliberately not a resource. */
private const val DELETE_ROW_GLYPH = "✕"

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
    // Hoisted out of the `remember { }` calculation lambdas — those are not
    // @Composable, so stringResource cannot be called inside them.
    val loadingLabel = stringResource(R.string.learning_data_loading)
    var bigramSummary by remember { mutableStateOf(loadingLabel) }
    var bigramTotal by remember { mutableIntStateOf(0) }
    var vocabSummary by remember { mutableStateOf(loadingLabel) }
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
            val activity = this@LearningDataManagerBlock
            val perLang = store.getKnownLanguages().sorted().mapNotNull { lang ->
                val s = store.getStatistics(lang)
                val triples = trigrams.getTotalTrigramCount(lang)
                if (s.totalBigrams > 0 || triples > 0) {
                    buildString {
                        // ARC-103: quantity strings — the pair/triple counts inflect the noun.
                        append(
                            activity.resources.getQuantityString(
                                R.plurals.learning_data_pairs_for_language,
                                s.totalBigrams, lang, s.totalBigrams
                            )
                        )
                        if (triples > 0) {
                            append(
                                activity.resources.getQuantityString(
                                    R.plurals.learning_data_triples_suffix, triples, triples
                                )
                            )
                        }
                    }
                } else {
                    null
                }
            }
            val total = store.getKnownLanguages().sumOf { store.getTotalBigramCount(it) } +
                trigrams.getKnownLanguages().sumOf { trigrams.getTotalTrigramCount(it) }
            val bigramText = if (perLang.isEmpty()) {
                activity.getString(R.string.learning_data_no_pairs)
            } else {
                perLang.joinToString(SUMMARY_SEPARATOR)
            }

            val vocab = UserVocabulary.getInstance(this@LearningDataManagerBlock)
            val vStats = vocab.getStats()
            val vocabText = if (vStats.totalWords == 0) {
                activity.getString(R.string.learning_data_no_words)
            } else {
                buildString {
                    append(
                        activity.resources.getQuantityString(
                            R.plurals.learning_data_word_count, vStats.totalWords, vStats.totalWords
                        )
                    )
                    vStats.mostUsedWord?.let {
                        append(SUMMARY_SEPARATOR)
                        append(
                            activity.getString(
                                R.string.learning_data_most_used, it.word, it.usageCount
                            )
                        )
                    }
                }
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
        Text(stringResource(R.string.learning_data_title), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.learning_data_privacy_note),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.learning_data_phrase_patterns, bigramSummary),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.learning_data_word_usage, vocabSummary),
            style = MaterialTheme.typography.bodySmall
        )

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
            displayValue = pluralStringResource(
                R.plurals.learning_data_word_count, personalizationMaxWords, personalizationMaxWords
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showBrowsePhrases = true },
                enabled = bigramTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.learning_data_browse_phrases)) }
            OutlinedButton(
                onClick = { showBrowseWords = true },
                enabled = vocabTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.learning_data_browse_words)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showClearBigrams = true },
                enabled = bigramTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.learning_data_forget_phrases)) }
            OutlinedButton(
                onClick = { showClearVocab = true },
                enabled = vocabTotal > 0,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.learning_data_forget_words)) }
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
            title = { Text(stringResource(R.string.learning_data_forget_phrases_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.learning_data_forget_phrases_body, bigramTotal, bigramTotal
                    )
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
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearBigrams = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showClearVocab) {
        AlertDialog(
            onDismissRequest = { showClearVocab = false },
            title = { Text(stringResource(R.string.learning_data_forget_words_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.learning_data_forget_words_body, vocabTotal, vocabTotal
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearVocab = false
                    Thread {
                        UserVocabulary.getInstance(this@LearningDataManagerBlock).clearAll()
                        runOnUiThread { refreshKey++ }
                    }.start()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearVocab = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
 * [BigramStore.removeBigram] + the trigram cascade
 * [TrigramStore.removeContinuationsOf] (ARC-004: without the cascade, a stored
 * `(·, w1) → w2` trigram keeps suggesting the deleted continuation, because
 * ContextModel prefers trigram evidence). NOTE: trigrams are not individually
 * browsable — beyond the cascade they are bulk-cleared via "Forget phrases"
 * (documented scope).
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
        title = { Text(stringResource(R.string.learning_data_phrases_dialog_title)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.learning_data_phrases_dialog_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(rows, key = { "${it.language}|${it.word1}|${it.word2}" }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(
                                    R.string.learning_data_phrase_row,
                                    row.word1, row.word2, row.frequency, row.language
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                Thread {
                                    BigramStore.getInstance(context)
                                        .removeBigram(row.language, row.word1, row.word2)
                                    // ARC-004 cascade: also remove every (·, w1) → w2 trigram,
                                    // or the deleted continuation keeps surfacing via the
                                    // trigram-first next-word/boost path.
                                    TrigramStore.getInstance(context)
                                        .removeContinuationsOf(row.language, row.word1, row.word2)
                                    loadKey++
                                }.start()
                            }) { Text(DELETE_ROW_GLYPH) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
        title = { Text(stringResource(R.string.learning_data_words_dialog_title)) },
        text = {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.learning_data_words_dialog_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(rows, key = { it.first }) { (word, count) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.learning_data_word_row, word, count),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                Thread {
                                    UserVocabulary.getInstance(context).removeWord(word)
                                    loadKey++
                                }.start()
                            }) { Text(DELETE_ROW_GLYPH) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
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
