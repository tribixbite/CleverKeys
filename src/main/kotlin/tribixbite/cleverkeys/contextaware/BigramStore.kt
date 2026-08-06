package tribixbite.cleverkeys.contextaware

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tribixbite.cleverkeys.persist.DebouncedPersister
import tribixbite.cleverkeys.persist.LearnedDataStorage
import tribixbite.cleverkeys.persist.SharedPrefsLearnedStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService

/**
 * Efficient storage and retrieval of bigram (word pair) data for context-aware predictions.
 *
 * PROCESS-WIDE SINGLETON, LANGUAGE-KEYED (2026-08-06 persistence fix):
 * - One instance per process ([getInstance]) — the previous per-`WordPredictor` instances
 *   all opened the same SharedPreferences file, so any two live stores holding different
 *   in-RAM supersets would clobber each other's persisted blob (last-writer-wins).
 * - Entries are keyed by language internally. Each language persists under its own
 *   prefs key (`bigrams_json_<lang>`); a legacy un-keyed `bigrams_json` blob is migrated
 *   into the first language that loads (in practice the primary language, whose
 *   predictor is constructed first) and then deleted.
 *
 * Data Structure:
 * - Per language: HashMap<word1, List<BigramEntry>> for O(1) lookup by previous word
 * - Thread-safe for concurrent access during typing
 *
 * Persistence (dirty-flag + debounced write-back, NOT per-keystroke):
 * - [recordBigram] mutates RAM and marks the store dirty; a [DebouncedPersister]
 *   coalesces writes (~5 s debounce, ~30 s hard cap) onto a background thread.
 * - Lifecycle call sites ([flush] / [requestFlush]) checkpoint on input-view finish,
 *   predictor eviction, and coordinator shutdown. Both no-op when clean.
 *
 * Usage:
 * ```kotlin
 * val store = BigramStore.getInstance(context)
 * store.recordBigram("en", "I", "am")            // Learn from usage
 * val predictions = store.getPredictions("en", "I") // Likely next words after "I"
 * ```
 */
class BigramStore internal constructor(
    private val storage: LearnedDataStorage,
    debounceMs: Long = DebouncedPersister.DEFAULT_DEBOUNCE_MS,
    maxDelayMs: Long = DebouncedPersister.DEFAULT_MAX_DELAY_MS,
    scheduler: ScheduledExecutorService = DebouncedPersister.sharedScheduler()
) {
    companion object {
        private const val PREFS_NAME = "bigram_store"
        internal const val LEGACY_KEY_BIGRAMS = "bigrams_json"
        internal const val KEY_PREFIX = "bigrams_json_"
        const val DEFAULT_MIN_FREQUENCY = 2  // Ignore hapax legomena (single occurrences)
        private const val MAX_BIGRAMS_PER_WORD = 20  // Top 20 predictions per previous word
        private const val MAX_TOTAL_BIGRAMS = 10000  // Overall storage limit (per language)

        @Volatile
        private var instance: BigramStore? = null

        /** Process-wide singleton backed by the `bigram_store` SharedPreferences file. */
        @JvmStatic
        fun getInstance(context: Context): BigramStore {
            return instance ?: synchronized(this) {
                instance ?: BigramStore(
                    SharedPrefsLearnedStorage(
                        context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    )
                ).also { instance = it }
            }
        }

        /** Normalize a language code for keying ("" → "en", case-insensitive). */
        internal fun normalizeLanguage(language: String): String {
            val trimmed = language.trim().lowercase()
            return trimmed.ifEmpty { "en" }
        }

        internal fun storageKey(language: String): String = KEY_PREFIX + normalizeLanguage(language)
    }

    /** Per-language in-RAM bigram tables. */
    private class LanguageBigrams {
        // Primary data structure: word1 → List of BigramEntry (sorted by probability desc)
        val bigramMap: ConcurrentHashMap<String, MutableList<BigramEntry>> = ConcurrentHashMap()

        // Word1 frequency tracking for probability calculation
        val word1Frequencies: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    }

    private val languages: ConcurrentHashMap<String, LanguageBigrams> = ConcurrentHashMap()

    // Languages with unflushed in-RAM changes (drained by writeDirtyLanguages()).
    private val dirtyLanguages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val persister = DebouncedPersister(debounceMs, maxDelayMs, scheduler) {
        writeDirtyLanguages()
    }

    // Minimum frequency threshold for keeping bigrams in predictions
    private var minFrequency: Int = DEFAULT_MIN_FREQUENCY

    /**
     * Get (lazily loading) the table for a language. First access migrates a legacy
     * un-keyed `bigrams_json` blob into this language, then deletes the legacy key.
     */
    private fun forLanguage(language: String): LanguageBigrams {
        val lang = normalizeLanguage(language)
        return languages.computeIfAbsent(lang) { code ->
            val data = LanguageBigrams()
            val keyed = storage.getString(storageKey(code))
            if (keyed != null) {
                loadInto(data, keyed)
            } else if (code == "en") {
                // Legacy migration: the pre-language-keying blob was recorded when
                // "en" was the only (implicit) language, so it belongs to "en"
                // EXPLICITLY (L10, review 2026-08-06 — the previous
                // first-language-to-load rule mis-keyed en pairs into whatever
                // language a non-en-primary user loaded first).
                val legacy = storage.getString(LEGACY_KEY_BIGRAMS)
                if (legacy != null) {
                    loadInto(data, legacy)
                    storage.remove(LEGACY_KEY_BIGRAMS)
                    // Persist under the new key so the legacy data survives even if
                    // no new bigram is ever recorded.
                    dirtyLanguages.add(code)
                    persister.markDirty()
                }
            }
            data
        }
    }

    /**
     * Record a bigram occurrence from user typing.
     * Increments frequency, recalculates probability, and marks the store dirty
     * for the debounced write-back.
     *
     * @param language Active language code (entries are language-isolated)
     * @param word1 Previous word (context)
     * @param word2 Current word (prediction target)
     */
    fun recordBigram(language: String, word1: String, word2: String) {
        val normalizedWord1 = BigramEntry.normalizeWord(word1)
        val normalizedWord2 = BigramEntry.normalizeWord(word2)

        // Skip empty or invalid words
        if (normalizedWord1.isEmpty() || normalizedWord2.isEmpty()) return
        if (normalizedWord1 == normalizedWord2) return  // Skip self-references

        val lang = normalizeLanguage(language)
        val data = forLanguage(lang)

        synchronized(this) {
            // Increment word1 total frequency (non-null Int values → `?: 0` == getOrDefault).
            val word1Freq = (data.word1Frequencies[normalizedWord1] ?: 0) + 1
            data.word1Frequencies[normalizedWord1] = word1Freq

            // Find or create bigram entry
            val entries = data.bigramMap.getOrPut(normalizedWord1) { mutableListOf() }
            val existingEntry = entries.find { it.word2 == normalizedWord2 }

            if (existingEntry != null) {
                // Update existing entry
                val newFreq = existingEntry.frequency + 1
                entries.remove(existingEntry)
                entries.add(existingEntry.copy(frequency = newFreq))
            } else {
                // Add new entry
                entries.add(
                    BigramEntry(
                        word1 = normalizedWord1,
                        word2 = normalizedWord2,
                        frequency = 1,
                        probability = 0f // Recomputed below with every sibling
                    )
                )
            }

            // M4 (review 2026-08-06): the denominator (word1's total) changed, so
            // EVERY sibling's conditional probability is stale — renormalize the
            // whole list (≤ MAX_BIGRAMS_PER_WORD entries), exactly as
            // TrigramStore.recordTrigram and importFromJson already do. Without
            // this, an early-recorded sibling kept its inflated probability
            // forever and later, more frequent continuations could never outrank it.
            val renormalized = entries.map {
                it.copy(probability = BigramEntry.calculateProbability(it.frequency, word1Freq))
            }
            entries.clear()
            entries.addAll(renormalized)

            // Sort by probability (descending) and limit size
            entries.sortByDescending { it.probability }
            if (entries.size > MAX_BIGRAMS_PER_WORD) {
                entries.subList(MAX_BIGRAMS_PER_WORD, entries.size).clear()
            }

            // Check total bigram count
            pruneIfNeeded(data)
        }

        dirtyLanguages.add(lang)
        persister.markDirty()
    }

    /**
     * Get predicted words given a previous word, ranked by probability.
     *
     * @param language Language whose learned data to query
     * @param previousWord The context word
     * @param maxResults Maximum number of predictions to return (default: 10)
     * @param minProbability Minimum probability threshold (default: 0.01 = 1%)
     * @return List of BigramEntry sorted by probability (highest first)
     */
    fun getPredictions(
        language: String,
        previousWord: String,
        maxResults: Int = 10,
        minProbability: Float = 0.01f
    ): List<BigramEntry> {
        val normalized = BigramEntry.normalizeWord(previousWord)
        val entries = forLanguage(language).bigramMap[normalized] ?: return emptyList()

        synchronized(this) {
            return entries
                .filter { it.frequency >= minFrequency && it.probability >= minProbability }
                .take(maxResults)
        }
    }

    /**
     * Get the probability of a specific word pair in a language.
     *
     * @return Probability (0.0 to 1.0), or 0.0 if bigram not found
     */
    fun getProbability(language: String, word1: String, word2: String): Float {
        val normalized1 = BigramEntry.normalizeWord(word1)
        val normalized2 = BigramEntry.normalizeWord(word2)

        val entries = forLanguage(language).bigramMap[normalized1] ?: return 0f
        synchronized(this) {
            return entries.find { it.word2 == normalized2 }?.probability ?: 0f
        }
    }

    /**
     * Probability with the min-frequency confidence floor applied: 0 when the
     * pair has been observed fewer than [setMinimumFrequency] times (L2, review
     * 2026-08-06). [getProbability] stays RAW (export/statistics accessor);
     * ranking/boost consumers must use this so a once-seen pair can't claim a
     * conditional probability of 1.0.
     */
    fun getConfidentProbability(language: String, word1: String, word2: String): Float {
        val normalized1 = BigramEntry.normalizeWord(word1)
        val normalized2 = BigramEntry.normalizeWord(word2)

        val entries = forLanguage(language).bigramMap[normalized1] ?: return 0f
        synchronized(this) {
            val entry = entries.find { it.word2 == normalized2 } ?: return 0f
            return if (entry.frequency >= minFrequency) entry.probability else 0f
        }
    }

    /**
     * All learned bigrams for a language, most frequent first — the learned-data
     * manager's browse list (audit §3.3 per-word browse/delete UI).
     */
    fun getAllEntries(language: String): List<BigramEntry> {
        synchronized(this) {
            return forLanguage(language).bigramMap.values.flatten()
                .sortedWith(compareByDescending<BigramEntry> { it.frequency }.thenBy { it.word1 })
        }
    }

    /** Get all bigrams for a specific previous word in a language. */
    fun getAllBigrams(language: String, word1: String): List<BigramEntry> {
        val normalized = BigramEntry.normalizeWord(word1)
        synchronized(this) {
            return forLanguage(language).bigramMap[normalized]?.toList() ?: emptyList()
        }
    }

    /** Get total number of unique bigrams stored for a language. */
    fun getTotalBigramCount(language: String): Int {
        synchronized(this) {
            return forLanguage(language).bigramMap.values.sumOf { it.size }
        }
    }

    /** Get number of unique word1 (context) entries for a language. */
    fun getContextWordCount(language: String): Int {
        return forLanguage(language).bigramMap.size
    }

    /**
     * Remove a single learned bigram (user-initiated edit from the learned-data manager).
     *
     * @return true if the entry existed and was removed
     */
    fun removeBigram(language: String, word1: String, word2: String): Boolean {
        val lang = normalizeLanguage(language)
        val normalized1 = BigramEntry.normalizeWord(word1)
        val normalized2 = BigramEntry.normalizeWord(word2)
        val data = forLanguage(lang)

        var removed = false
        synchronized(this) {
            val entries = data.bigramMap[normalized1] ?: return false
            val entry = entries.find { it.word2 == normalized2 } ?: return false
            entries.remove(entry)
            removed = true

            // Rescale: removed occurrences no longer count toward word1's total.
            val newTotal = maxOf(0, (data.word1Frequencies[normalized1] ?: 0) - entry.frequency)
            if (newTotal <= 0 || entries.isEmpty()) {
                if (entries.isEmpty()) data.bigramMap.remove(normalized1)
                if (newTotal <= 0) data.word1Frequencies.remove(normalized1) else data.word1Frequencies[normalized1] = newTotal
            } else {
                data.word1Frequencies[normalized1] = newTotal
                val rescaled = entries.map {
                    it.copy(probability = BigramEntry.calculateProbability(it.frequency, newTotal))
                }
                entries.clear()
                entries.addAll(rescaled)
                entries.sortByDescending { it.probability }
            }
        }

        if (removed) {
            dirtyLanguages.add(lang)
            persister.markDirty()
            // L4 (review 2026-08-06): a USER-initiated delete must not sit in the
            // ~30 s debounce window — process death would resurrect the word the
            // user explicitly removed. Flush the removal promptly (async).
            persister.requestFlush()
        }
        return removed
    }

    /**
     * Clear all bigram data for one language (user-initiated reset).
     * Persists the removal immediately.
     */
    fun clear(language: String) {
        val lang = normalizeLanguage(language)
        // M1 (review 2026-08-06): the storage removal happens INSIDE the same
        // lock that [writeDirtyLanguages] holds across serialize+write, so an
        // in-flight flush can never re-persist ("resurrect") data the user just
        // forgot: either the flush completes first and this remove erases its
        // write, or this clear completes first and the flush serializes the
        // now-empty table (which maps to a key removal, not a write).
        synchronized(this) {
            val data = forLanguage(lang)
            data.bigramMap.clear()
            data.word1Frequencies.clear()
            dirtyLanguages.remove(lang)
            storage.remove(storageKey(lang))
        }
    }

    /** Clear ALL learned bigram data across every language, including persisted blobs. */
    fun clearAll() {
        // M1: same lock discipline as [clear] — no flush can interleave between
        // the in-RAM wipe and the persisted-blob removal.
        synchronized(this) {
            languages.values.forEach {
                it.bigramMap.clear()
                it.word1Frequencies.clear()
            }
            dirtyLanguages.clear()
            storage.remove(LEGACY_KEY_BIGRAMS)
            storage.keys().filter { it.startsWith(KEY_PREFIX) }.forEach { storage.remove(it) }
        }
    }

    /**
     * Languages with learned data (loaded in RAM or persisted).
     */
    fun getKnownLanguages(): Set<String> {
        val fromStorage = storage.keys()
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
        return (languages.keys + fromStorage).toSet()
    }

    /**
     * Set minimum frequency threshold for keeping bigrams.
     * Bigrams with frequency below this are ignored in predictions.
     */
    fun setMinimumFrequency(minFreq: Int) {
        minFrequency = maxOf(1, minFreq)
    }

    /** @return true if there are unflushed in-RAM changes. */
    fun isDirty(): Boolean = persister.isDirty()

    /**
     * Synchronously flush unflushed changes to storage. Idempotent; no-op when clean.
     * Safe from any thread — serialization happens on the calling thread.
     */
    fun flush() = persister.flush()

    /**
     * Asynchronously flush on the persistence thread. Preferred from main-thread
     * lifecycle call sites (input-view finish, eviction). No-op when clean.
     */
    fun requestFlush() = persister.requestFlush()

    /**
     * Prune low-frequency bigrams if total count exceeds limit.
     * Keeps most probable bigrams and removes rare ones. Caller holds the lock.
     */
    private fun pruneIfNeeded(data: LanguageBigrams) {
        val totalCount = data.bigramMap.values.sumOf { it.size }
        if (totalCount <= MAX_TOTAL_BIGRAMS) return

        // Collect all bigrams with their probabilities
        val allBigrams = data.bigramMap.values.flatten()
        val sortedBigrams = allBigrams.sortedByDescending { it.probability }

        // Keep top MAX_TOTAL_BIGRAMS
        val toKeep = sortedBigrams.take(MAX_TOTAL_BIGRAMS).toSet()

        // Rebuild bigramMap with only kept bigrams
        data.bigramMap.clear()
        toKeep.forEach { entry ->
            data.bigramMap.getOrPut(entry.word1) { mutableListOf() }.add(entry)
        }

        // Re-sort each list
        data.bigramMap.values.forEach { list ->
            list.sortByDescending { it.probability }
        }
    }

    /**
     * Serialize and write every dirty language's table to storage.
     * Runs on the persistence thread (or the caller of [flush]).
     */
    private fun writeDirtyLanguages() {
        // Drain the dirty set; a record racing in re-adds its language and re-marks
        // the persister, so nothing is lost.
        val toWrite = dirtyLanguages.toList()
        dirtyLanguages.removeAll(toWrite.toSet())

        var failure: Exception? = null
        for (lang in toWrite) {
            val data = languages[lang] ?: continue
            try {
                // M1 (review 2026-08-06): serialize AND write under ONE lock so a
                // concurrent [clear]/[clearAll] (which also holds this lock while
                // removing the persisted key) can never interleave between them —
                // that interleaving re-persisted just-forgotten data. An empty
                // table maps to key REMOVAL (post-clear flush, or last entry
                // removed) so a forget is never overwritten by an empty blob.
                synchronized(this) {
                    if (data.bigramMap.isEmpty()) {
                        storage.remove(storageKey(lang))
                    } else {
                        storage.putString(storageKey(lang), serialize(data))
                    }
                }
            } catch (e: Exception) {
                // L9: put this language back so the persister's dirty-restore
                // actually retries it — draining first and failing later left the
                // data stranded-unpersisted until the next record happened to
                // re-mark the same language.
                dirtyLanguages.add(lang)
                failure = e
            }
        }
        // Propagate so DebouncedPersister.flush restores its dirty flag for retry.
        failure?.let { throw it }
    }

    /** Serialize a language table to the persisted JSON-array format. Caller holds the lock. */
    private fun serialize(data: LanguageBigrams): String {
        val json = JSONArray()
        data.bigramMap.values.flatten().forEach { entry ->
            val obj = JSONObject().apply {
                put("word1", entry.word1)
                put("word2", entry.word2)
                put("frequency", entry.frequency)
                put("probability", entry.probability.toDouble())
            }
            json.put(obj)
        }
        return json.toString()
    }

    /**
     * Load a persisted JSON blob into a language table.
     * Invalid JSON falls back to an empty table.
     */
    private fun loadInto(data: LanguageBigrams, jsonString: String) {
        try {
            val json = JSONArray(jsonString)
            data.bigramMap.clear()
            data.word1Frequencies.clear()

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val entry = BigramEntry(
                    word1 = obj.getString("word1"),
                    word2 = obj.getString("word2"),
                    frequency = obj.getInt("frequency"),
                    probability = obj.getDouble("probability").toFloat()
                )

                data.bigramMap.getOrPut(entry.word1) { mutableListOf() }.add(entry)

                // Reconstruct word1 frequencies (non-null Int values → `?: 0` == getOrDefault).
                val currentFreq = data.word1Frequencies[entry.word1] ?: 0
                data.word1Frequencies[entry.word1] = currentFreq + entry.frequency
            }

            // Sort all lists by probability
            data.bigramMap.values.forEach { list ->
                list.sortByDescending { it.probability }
            }
        } catch (e: Exception) {
            // Invalid JSON, start fresh
            data.bigramMap.clear()
            data.word1Frequencies.clear()
        }
    }

    /** Export one language's bigram data as JSON string for backup or analysis. */
    fun exportToJson(language: String): String {
        synchronized(this) {
            val json = JSONArray()
            forLanguage(language).bigramMap.values.flatten().forEach { entry ->
                val obj = JSONObject().apply {
                    put("word1", entry.word1)
                    put("word2", entry.word2)
                    put("frequency", entry.frequency)
                    put("probability", entry.probability.toDouble())
                }
                json.put(obj)
            }
            return json.toString(2)  // Pretty print with 2-space indent
        }
    }

    /**
     * Import bigram data for one language from a JSON string.
     * Merges with existing data via DIRECT frequency merge (O(entries), not
     * O(total frequency) replay) and recomputes conditional probabilities.
     */
    fun importFromJson(language: String, jsonString: String) {
        val lang = normalizeLanguage(language)
        try {
            val json = JSONArray(jsonString)
            val data = forLanguage(lang)

            synchronized(this) {
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val word1 = BigramEntry.normalizeWord(obj.getString("word1"))
                    val word2 = BigramEntry.normalizeWord(obj.getString("word2"))
                    val frequency = obj.getInt("frequency")
                    if (word1.isEmpty() || word2.isEmpty() || frequency <= 0) continue
                    if (word1 == word2) continue

                    data.word1Frequencies[word1] = (data.word1Frequencies[word1] ?: 0) + frequency

                    val entries = data.bigramMap.getOrPut(word1) { mutableListOf() }
                    val existing = entries.find { it.word2 == word2 }
                    if (existing != null) {
                        entries.remove(existing)
                        entries.add(existing.copy(frequency = existing.frequency + frequency))
                    } else {
                        entries.add(BigramEntry(word1, word2, frequency, 0f))
                    }
                }

                // Recompute probabilities against the merged word1 totals + enforce caps.
                for ((word1, entries) in data.bigramMap) {
                    val total = data.word1Frequencies[word1] ?: continue
                    val recomputed = entries.map {
                        it.copy(probability = BigramEntry.calculateProbability(it.frequency, total))
                    }
                    entries.clear()
                    entries.addAll(recomputed)
                    entries.sortByDescending { it.probability }
                    if (entries.size > MAX_BIGRAMS_PER_WORD) {
                        entries.subList(MAX_BIGRAMS_PER_WORD, entries.size).clear()
                    }
                }
                pruneIfNeeded(data)
            }

            dirtyLanguages.add(lang)
            persister.markDirty()
            persister.flush()
        } catch (e: Exception) {
            // Invalid JSON, ignore
        }
    }

    /**
     * Get statistics about the bigram store for one language.
     */
    data class BigramStats(
        val totalBigrams: Int,
        val uniqueContextWords: Int,
        val averageBigramsPerContext: Float,
        val topContextWords: List<Pair<String, Int>>  // word1 with count of bigrams
    )

    fun getStatistics(language: String): BigramStats {
        synchronized(this) {
            val data = forLanguage(language)
            val totalBigrams = data.bigramMap.values.sumOf { it.size }
            val uniqueContextWords = data.bigramMap.size
            val average = if (uniqueContextWords > 0) {
                totalBigrams.toFloat() / uniqueContextWords.toFloat()
            } else 0f

            val topWords = data.bigramMap.entries
                .map { (word, entries) -> word to entries.size }
                .sortedByDescending { it.second }
                .take(10)

            return BigramStats(
                totalBigrams = totalBigrams,
                uniqueContextWords = uniqueContextWords,
                averageBigramsPerContext = average,
                topContextWords = topWords
            )
        }
    }
}
