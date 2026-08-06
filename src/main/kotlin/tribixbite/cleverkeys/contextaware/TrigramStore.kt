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
 * Efficient storage and retrieval of trigram (three-word sequence) data for
 * context-aware prediction and next-word generation (audit 2026-08-06 §1.3-D/§4.2).
 *
 * Mirrors [BigramStore]'s discipline exactly:
 * - PROCESS-WIDE SINGLETON ([getInstance]) — one writer, no clobber.
 * - LANGUAGE-KEYED entries, persisted per language (`trigrams_json_<lang>` in the
 *   `trigram_store` SharedPreferences file).
 * - Dirty-flag + debounced write-back via [DebouncedPersister] — never a write per
 *   keystroke; lifecycle call sites [flush]/[requestFlush] checkpoint explicitly.
 * - PRIVACY: this store has no learn decision of its own — the ONLY production
 *   write path is [ContextModel.recordSequence], which is reached exclusively
 *   through the gated learning funnel (`LearningGate.learnCommittedWord`), so the
 *   master `on_device_learning_enabled` gate and the per-feature
 *   `context_aware_predictions_enabled` gate both apply before any mutation here.
 *
 * Internal layout: prefix "word1 word2" → List<TrigramEntry> sorted by
 * conditional probability P(word3 | word1, word2), with per-prefix and per-language
 * caps to bound storage (trigrams fan out faster than bigrams, hence the smaller
 * per-context cap).
 */
class TrigramStore internal constructor(
    private val storage: LearnedDataStorage,
    debounceMs: Long = DebouncedPersister.DEFAULT_DEBOUNCE_MS,
    maxDelayMs: Long = DebouncedPersister.DEFAULT_MAX_DELAY_MS,
    scheduler: ScheduledExecutorService = DebouncedPersister.sharedScheduler()
) {
    companion object {
        private const val PREFS_NAME = "trigram_store"
        internal const val KEY_PREFIX = "trigrams_json_"
        const val DEFAULT_MIN_FREQUENCY = 2 // Ignore single occurrences (same floor as bigrams)
        private const val MAX_TRIGRAMS_PER_PREFIX = 10 // Trigram contexts are sharper than bigram ones
        private const val MAX_TOTAL_TRIGRAMS = 10000 // Overall storage limit (per language)

        @Volatile
        private var instance: TrigramStore? = null

        /** Process-wide singleton backed by the `trigram_store` SharedPreferences file. */
        @JvmStatic
        fun getInstance(context: Context): TrigramStore {
            return instance ?: synchronized(this) {
                instance ?: TrigramStore(
                    SharedPrefsLearnedStorage(
                        context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    )
                ).also { instance = it }
            }
        }

        internal fun storageKey(language: String): String =
            KEY_PREFIX + BigramStore.normalizeLanguage(language)

        /** Composite in-RAM key for the two-word prefix. */
        private fun prefixKey(word1: String, word2: String): String = "$word1 $word2"
    }

    /** Per-language in-RAM trigram tables. */
    private class LanguageTrigrams {
        // "word1 word2" → entries sorted by probability desc
        val trigramMap: ConcurrentHashMap<String, MutableList<TrigramEntry>> = ConcurrentHashMap()

        // "word1 word2" → total observed continuations (denominator for probability)
        val prefixFrequencies: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    }

    private val languages: ConcurrentHashMap<String, LanguageTrigrams> = ConcurrentHashMap()

    // Languages with unflushed in-RAM changes (drained by writeDirtyLanguages()).
    private val dirtyLanguages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val persister = DebouncedPersister(debounceMs, maxDelayMs, scheduler) {
        writeDirtyLanguages()
    }

    private var minFrequency: Int = DEFAULT_MIN_FREQUENCY

    /** Get (lazily loading from storage) the table for a language. */
    private fun forLanguage(language: String): LanguageTrigrams {
        val lang = BigramStore.normalizeLanguage(language)
        return languages.computeIfAbsent(lang) { code ->
            val data = LanguageTrigrams()
            storage.getString(storageKey(code))?.let { loadInto(data, it) }
            data
        }
    }

    /**
     * Record a trigram occurrence from user typing.
     * Increments frequency, recalculates conditional probability, and marks the
     * store dirty for the debounced write-back.
     */
    fun recordTrigram(language: String, word1: String, word2: String, word3: String) {
        val w1 = TrigramEntry.normalizeWord(word1)
        val w2 = TrigramEntry.normalizeWord(word2)
        val w3 = TrigramEntry.normalizeWord(word3)
        if (w1.isEmpty() || w2.isEmpty() || w3.isEmpty()) return
        if (w2 == w3) return // Skip immediate self-references ("very very")

        val lang = BigramStore.normalizeLanguage(language)
        val data = forLanguage(lang)
        val key = prefixKey(w1, w2)

        synchronized(this) {
            val prefixFreq = (data.prefixFrequencies[key] ?: 0) + 1
            data.prefixFrequencies[key] = prefixFreq

            val entries = data.trigramMap.getOrPut(key) { mutableListOf() }
            val existing = entries.find { it.word3 == w3 }
            if (existing != null) {
                val newFreq = existing.frequency + 1
                entries.remove(existing)
                entries.add(
                    existing.copy(
                        frequency = newFreq,
                        probability = TrigramEntry.calculateProbability(newFreq, prefixFreq)
                    )
                )
            } else {
                entries.add(
                    TrigramEntry(
                        word1 = w1, word2 = w2, word3 = w3,
                        frequency = 1,
                        probability = TrigramEntry.calculateProbability(1, prefixFreq)
                    )
                )
            }

            // Re-normalize siblings against the updated prefix total, sort, cap.
            val renormalized = entries.map {
                it.copy(probability = TrigramEntry.calculateProbability(it.frequency, prefixFreq))
            }
            entries.clear()
            entries.addAll(renormalized)
            entries.sortByDescending { it.probability }
            if (entries.size > MAX_TRIGRAMS_PER_PREFIX) {
                entries.subList(MAX_TRIGRAMS_PER_PREFIX, entries.size).clear()
            }

            pruneIfNeeded(data)
        }

        dirtyLanguages.add(lang)
        persister.markDirty()
    }

    /**
     * Get predicted third words given a two-word prefix, ranked by probability.
     *
     * @param minProbability floor below which entries are dropped (default 1%)
     */
    fun getPredictions(
        language: String,
        word1: String,
        word2: String,
        maxResults: Int = 10,
        minProbability: Float = 0.01f
    ): List<TrigramEntry> {
        val key = prefixKey(TrigramEntry.normalizeWord(word1), TrigramEntry.normalizeWord(word2))
        val entries = forLanguage(language).trigramMap[key] ?: return emptyList()
        synchronized(this) {
            return entries
                .filter { it.frequency >= minFrequency && it.probability >= minProbability }
                .take(maxResults)
        }
    }

    /** Get P(word3 | word1, word2) in a language, or 0 if the trigram is unknown. */
    fun getProbability(language: String, word1: String, word2: String, word3: String): Float {
        val key = prefixKey(TrigramEntry.normalizeWord(word1), TrigramEntry.normalizeWord(word2))
        val w3 = TrigramEntry.normalizeWord(word3)
        val entries = forLanguage(language).trigramMap[key] ?: return 0f
        synchronized(this) {
            return entries.find { it.word3 == w3 }?.probability ?: 0f
        }
    }

    /**
     * Probability with the min-frequency confidence floor applied: 0 when the
     * trigram has been observed fewer than [setMinimumFrequency] times (L2,
     * review 2026-08-06). [getProbability] stays RAW; the boost path
     * (`ContextModel.getContextBoost`) must use this so a once-seen trigram
     * can't claim the near-max 4× boost off a single observation.
     */
    fun getConfidentProbability(language: String, word1: String, word2: String, word3: String): Float {
        val key = prefixKey(TrigramEntry.normalizeWord(word1), TrigramEntry.normalizeWord(word2))
        val w3 = TrigramEntry.normalizeWord(word3)
        val entries = forLanguage(language).trigramMap[key] ?: return 0f
        synchronized(this) {
            val entry = entries.find { it.word3 == w3 } ?: return 0f
            return if (entry.frequency >= minFrequency) entry.probability else 0f
        }
    }

    /**
     * Inverse of [recordTrigram] (autocorrect-undo rollback, 2026-08-06): decrement
     * ONE observation of `(word1 word2 → word3)`. Removes the entry at frequency 0
     * and renormalizes surviving siblings against the reduced prefix total —
     * mirror of [BigramStore.unrecordBigram]. No-op when the trigram is unknown.
     *
     * @return true if an observation was removed
     */
    fun unrecordTrigram(language: String, word1: String, word2: String, word3: String): Boolean {
        val w1 = TrigramEntry.normalizeWord(word1)
        val w2 = TrigramEntry.normalizeWord(word2)
        val w3 = TrigramEntry.normalizeWord(word3)
        if (w1.isEmpty() || w2.isEmpty() || w3.isEmpty()) return false

        val lang = BigramStore.normalizeLanguage(language)
        val data = forLanguage(lang)
        val key = prefixKey(w1, w2)

        synchronized(this) {
            val entries = data.trigramMap[key] ?: return false
            val existing = entries.find { it.word3 == w3 } ?: return false

            entries.remove(existing)
            if (existing.frequency > 1) {
                entries.add(existing.copy(frequency = existing.frequency - 1))
            }

            val newTotal = maxOf(0, (data.prefixFrequencies[key] ?: 0) - 1)
            if (newTotal <= 0 || entries.isEmpty()) {
                if (entries.isEmpty()) data.trigramMap.remove(key)
                if (newTotal <= 0) {
                    data.prefixFrequencies.remove(key)
                } else {
                    data.prefixFrequencies[key] = newTotal
                }
            } else {
                data.prefixFrequencies[key] = newTotal
                val renormalized = entries.map {
                    it.copy(probability = TrigramEntry.calculateProbability(it.frequency, newTotal))
                }
                entries.clear()
                entries.addAll(renormalized)
                entries.sortByDescending { it.probability }
            }
        }

        dirtyLanguages.add(lang)
        persister.markDirty()
        return true
    }

    /** Total number of unique trigrams stored for a language. */
    fun getTotalTrigramCount(language: String): Int {
        synchronized(this) {
            return forLanguage(language).trigramMap.values.sumOf { it.size }
        }
    }

    /**
     * Clear all trigram data for one language (user-initiated reset).
     * Persists the removal immediately.
     */
    fun clear(language: String) {
        val lang = BigramStore.normalizeLanguage(language)
        // M1 (review 2026-08-06): storage removal INSIDE the serialize+write lock —
        // see BigramStore.clear for the interleaving this forbids.
        synchronized(this) {
            val data = forLanguage(lang)
            data.trigramMap.clear()
            data.prefixFrequencies.clear()
            dirtyLanguages.remove(lang)
            storage.remove(storageKey(lang))
        }
    }

    /** Clear ALL learned trigram data across every language, including persisted blobs. */
    fun clearAll() {
        // M1: same lock discipline as [clear].
        synchronized(this) {
            languages.values.forEach {
                it.trigramMap.clear()
                it.prefixFrequencies.clear()
            }
            dirtyLanguages.clear()
            storage.keys().filter { it.startsWith(KEY_PREFIX) }.forEach { storage.remove(it) }
        }
    }

    /** Languages with learned trigram data (loaded in RAM or persisted). */
    fun getKnownLanguages(): Set<String> {
        val fromStorage = storage.keys()
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
        return (languages.keys + fromStorage).toSet()
    }

    /** Set minimum frequency threshold for surfacing trigrams in predictions. */
    fun setMinimumFrequency(minFreq: Int) {
        minFrequency = maxOf(1, minFreq)
    }

    /** @return true if there are unflushed in-RAM changes. */
    fun isDirty(): Boolean = persister.isDirty()

    /** Synchronously flush unflushed changes to storage. Idempotent; no-op when clean. */
    fun flush() = persister.flush()

    /** Asynchronously flush on the persistence thread. No-op when clean. */
    fun requestFlush() = persister.requestFlush()

    /** Prune lowest-probability trigrams when a language exceeds the total cap. Caller holds the lock. */
    private fun pruneIfNeeded(data: LanguageTrigrams) {
        val totalCount = data.trigramMap.values.sumOf { it.size }
        if (totalCount <= MAX_TOTAL_TRIGRAMS) return

        val toKeep = data.trigramMap.values.flatten()
            .sortedByDescending { it.probability }
            .take(MAX_TOTAL_TRIGRAMS)
            .toSet()

        data.trigramMap.clear()
        toKeep.forEach { entry ->
            data.trigramMap.getOrPut(prefixKey(entry.word1, entry.word2)) { mutableListOf() }.add(entry)
        }
        data.trigramMap.values.forEach { it.sortByDescending { e -> e.probability } }
    }

    /** Serialize and write every dirty language's table to storage. */
    private fun writeDirtyLanguages() {
        val toWrite = dirtyLanguages.toList()
        dirtyLanguages.removeAll(toWrite.toSet())

        var failure: Exception? = null
        for (lang in toWrite) {
            val data = languages[lang] ?: continue
            try {
                // M1: serialize+write under ONE lock (shared with [clear]/[clearAll])
                // so a forget can never be resurrected by an in-flight flush; an
                // empty table maps to key removal. See BigramStore.writeDirtyLanguages.
                synchronized(this) {
                    if (data.trigramMap.isEmpty()) {
                        storage.remove(storageKey(lang))
                    } else {
                        storage.putString(storageKey(lang), serialize(data))
                    }
                }
            } catch (e: Exception) {
                // L9: re-add so the persister's dirty-restore retry finds it.
                dirtyLanguages.add(lang)
                failure = e
            }
        }
        failure?.let { throw it }
    }

    /** Serialize a language table to the persisted JSON-array format. Caller holds the lock. */
    private fun serialize(data: LanguageTrigrams): String {
        val json = JSONArray()
        data.trigramMap.values.flatten().forEach { entry ->
            json.put(
                JSONObject().apply {
                    put("word1", entry.word1)
                    put("word2", entry.word2)
                    put("word3", entry.word3)
                    put("frequency", entry.frequency)
                    put("probability", entry.probability.toDouble())
                }
            )
        }
        return json.toString()
    }

    /** Load a persisted JSON blob into a language table. Invalid JSON falls back to empty. */
    private fun loadInto(data: LanguageTrigrams, jsonString: String) {
        try {
            val json = JSONArray(jsonString)
            data.trigramMap.clear()
            data.prefixFrequencies.clear()

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val entry = TrigramEntry(
                    word1 = obj.getString("word1"),
                    word2 = obj.getString("word2"),
                    word3 = obj.getString("word3"),
                    frequency = obj.getInt("frequency"),
                    probability = obj.getDouble("probability").toFloat()
                )
                val key = prefixKey(entry.word1, entry.word2)
                data.trigramMap.getOrPut(key) { mutableListOf() }.add(entry)
                data.prefixFrequencies[key] = (data.prefixFrequencies[key] ?: 0) + entry.frequency
            }

            data.trigramMap.values.forEach { it.sortByDescending { e -> e.probability } }
        } catch (e: Exception) {
            data.trigramMap.clear()
            data.prefixFrequencies.clear()
        }
    }
}
