package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.annotation.PluralsRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * learned phrases/words — searchable and paged, see [LearnedBrowsePolicy] — (per-word delete via the tested
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
    // Saveable: the browsers (and their search text) stay open across rotation.
    var showBrowsePhrases by rememberSaveable { mutableStateOf(false) }
    var showBrowseWords by rememberSaveable { mutableStateOf(false) }

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
            increment = 500f,
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

/** One learned word row in the browser. */
private data class WordRow(val word: String, val usageCount: Int)

/** Lazy-list keys of the browser's header items; row keys carry a different prefix. */
private const val KEY_SEARCH = "header:search"
private const val KEY_COUNT = "header:count"
private const val KEY_NO_MATCHES = "header:none"

/** Header items before the first row (search field + count line), for the paging index. */
private const val HEADER_ITEMS = 2

/**
 * Per-phrase browse/delete dialog (audit §3.3): every learned bigram across all
 * languages, most frequent first, searchable and paged ([LearnedBrowsePolicy]), with
 * per-entry delete via [BigramStore.removeBigram] + the trigram cascade
 * [TrigramStore.removeContinuationsOf] (ARC-004: without the cascade, a stored
 * `(·, w1) → w2` trigram keeps suggesting the deleted continuation, because
 * ContextModel prefers trigram evidence). NOTE: trigrams are not individually
 * browsable — beyond the cascade they are bulk-cleared via "Forget phrases"
 * (documented scope).
 */
@Composable
private fun LearnedPhraseBrowserDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LearnedBrowserDialog(
        title = stringResource(R.string.learning_data_phrases_dialog_title),
        searchHint = stringResource(R.string.learning_data_search_phrases_hint),
        emptyText = stringResource(R.string.learning_data_phrases_dialog_empty),
        totalPlural = R.plurals.learning_data_phrase_count,
        load = {
            val store = BigramStore.getInstance(context)
            val rows = store.getKnownLanguages().sorted().flatMap { lang ->
                store.getAllEntries(lang).map { PhraseRow(lang, it.word1, it.word2, it.frequency) }
            }.sortedByDescending { it.frequency }
            LearnedBrowsePolicy.index(rows) { listOf(it.word1, it.word2) }
        },
        rowKey = { "p:${it.language}|${it.word1}|${it.word2}" },
        rowText = { row ->
            stringResource(
                R.string.learning_data_phrase_row, row.word1, row.word2, row.frequency, row.language
            )
        },
        delete = { row ->
            BigramStore.getInstance(context).removeBigram(row.language, row.word1, row.word2)
            // ARC-004 cascade: also remove every (·, w1) → w2 trigram, or the deleted
            // continuation keeps surfacing via the trigram-first next-word/boost path.
            TrigramStore.getInstance(context).removeContinuationsOf(row.language, row.word1, row.word2)
        },
        onDismiss = onDismiss
    )
}

/**
 * Per-word browse/delete dialog (audit §3.3): the whole personalization vocabulary,
 * highest personalization boost first, searchable and paged ([LearnedBrowsePolicy]),
 * with per-entry delete via `UserVocabulary.removeWord`.
 */
@Composable
private fun LearnedWordBrowserDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LearnedBrowserDialog(
        title = stringResource(R.string.learning_data_words_dialog_title),
        searchHint = stringResource(R.string.learning_data_search_words_hint),
        emptyText = stringResource(R.string.learning_data_words_dialog_empty),
        totalPlural = R.plurals.learning_data_word_count,
        load = {
            val rows = UserVocabulary.getInstance(context).getAllWords().map { WordRow(it.word, it.usageCount) }
            LearnedBrowsePolicy.index(rows) { listOf(it.word) }
        },
        rowKey = { "w:${it.word}" },
        rowText = { row -> stringResource(R.string.learning_data_word_row, row.word, row.usageCount) },
        delete = { row -> UserVocabulary.getInstance(context).removeWord(row.word) },
        onDismiss = onDismiss
    )
}

/**
 * The shared learned-data browser: a search field and a count line ("1,234 words", or
 * "12 of 1,234" while searching) above the rows, all in ONE keyed lazy list so the header
 * scrolls with the rows and cannot starve a short landscape viewport of entries.
 *
 * [load] snapshots and folds the store once on [Dispatchers.IO]; filtering runs on
 * [Dispatchers.Default] per query (a substring scan of pre-folded text — the stores are
 * in-memory and capped at tens of thousands of rows, so this is milliseconds), and the list
 * grows [LearnedBrowsePolicy.PAGE_SIZE] rows at a time as it is scrolled. A delete drops the
 * row from the snapshot at once (the list keeps its scroll position) and calls [delete] on IO.
 * The query survives rotation (rememberSaveable); the page count restarts with each query.
 */
@Composable
private fun <T> LearnedBrowserDialog(
    title: String,
    searchHint: String,
    emptyText: String,
    @PluralsRes totalPlural: Int,
    load: suspend () -> List<LearnedBrowsePolicy.Indexed<T>>,
    rowKey: (T) -> String,
    rowText: @Composable (T) -> String,
    delete: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // null while the first snapshot loads.
    var snapshot by remember { mutableStateOf<List<LearnedBrowsePolicy.Indexed<T>>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<T>>(emptyList()) }
    var pages by rememberSaveable(query) { mutableIntStateOf(1) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { load() }
    }
    LaunchedEffect(snapshot, query) {
        val rows = snapshot ?: return@LaunchedEffect
        matches = withContext(Dispatchers.Default) { LearnedBrowsePolicy.filter(rows, query) }
    }
    // Lazy paging: add a page when the last laid-out row nears the end of the shown rows.
    LaunchedEffect(listState, matches) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastItem ->
                pages = LearnedBrowsePolicy.pagesAfterScroll(lastItem - HEADER_ITEMS, matches.size, pages)
            }
    }

    val rows = snapshot
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when {
                rows == null -> Text(stringResource(R.string.learning_data_loading))
                rows.isEmpty() -> Text(emptyText)
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 400.dp)) {
                        item(key = KEY_SEARCH) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(searchHint) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search))
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.clipboard_clear_search)
                                            )
                                        }
                                    }
                                },
                                singleLine = true
                            )
                        }
                        item(key = KEY_COUNT) {
                            val countText = when (val line = LearnedBrowsePolicy.countLine(query, matches.size, rows.size)) {
                                is LearnedBrowsePolicy.CountLine.Total ->
                                    pluralStringResource(totalPlural, line.total, line.total)
                                is LearnedBrowsePolicy.CountLine.Filtered ->
                                    stringResource(R.string.learning_data_filtered_count, line.matches, line.total)
                            }
                            Text(
                                countText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                        if (matches.isEmpty() && query.isNotBlank()) {
                            item(key = KEY_NO_MATCHES) {
                                Text(
                                    stringResource(R.string.learning_data_no_matches),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        // Read matches and pages HERE, not in the enclosing lambda: the lazy
                        // list re-runs this block on its own when either changes, and a page
                        // size computed outside went stale (subList past a shrunken match list
                        // crashed the process on the first keystroke, Pixel 2026-10-26).
                        items(LearnedBrowsePolicy.page(matches, pages), key = rowKey) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    rowText(row),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    val key = rowKey(row)
                                    snapshot = snapshot?.filterNot { rowKey(it.item) == key }
                                    scope.launch(Dispatchers.IO) { delete(row) }
                                }) { Text(DELETE_ROW_GLYPH) }
                            }
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
