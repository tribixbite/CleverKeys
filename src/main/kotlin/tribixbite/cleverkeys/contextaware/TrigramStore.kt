package tribixbite.cleverkeys.contextaware

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import tribixbite.cleverkeys.persist.DebouncedPersister
import tribixbite.cleverkeys.persist.LearnedDataStorage
import tribixbite.cleverkeys.persist.SharedPrefsLearnedStorage
import java.util.Collections
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

        /**
         * Persisted-blob format version (ARC-080) — the mirror of
         * [BigramStore]'s, with the totals keyed by the composite `"word1 word2"`
         * prefix instead of a single context word.
         *
         * v1 (implicit, no marker) was a BARE JSON ARRAY of entries, which could
         * not express [LanguageTrigrams.prefixFrequencies]; [loadInto] rebuilt each
         * denominator as the sum of the SURVIVING entries. Since
         * [MAX_TRIGRAMS_PER_PREFIX] is only 10, a busy prefix loses most of its
         * observations to the cap and the reconstructed denominator was far too
         * small, inflating every survivor at the next observation after a restart.
         *
         * Version detection is STRUCTURAL — a bare array is v1 — so blobs already on
         * users' devices keep loading with exactly their previous behaviour. The
         * backup payload ([exportToJson]/[importFromJson]) is a separate, still
         * array-shaped contract and is deliberately untouched.
         */
        private const val FORMAT_VERSION = 2
        private const val KEY_VERSION = "version"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TOTALS = "totals"

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

    /**
     * Serializes [forLanguage]'s table CONSTRUCTION (see the API 21 note there).
     * Deliberately NOT `this`: the build path touches only local state and
     * `storage`, so it is never held while waiting on the data lock — no
     * lock-order cycle with [writeDirtyLanguages]/[clear], which hold `this`.
     */
    private val loadLock = Any()

    /**
     * Languages with unflushed in-RAM changes (drained by writeDirtyLanguages()).
     *
     * API 21 HAZARD: `ConcurrentHashMap.newKeySet()` is API 24 (Java 8) and throws
     * `NoSuchMethodError` on Android 5.0–6.0 — `minSdk` here is 21.
     * [Collections.newSetFromMap] over a [ConcurrentHashMap] is API 9 and returns
     * the same concurrent, weakly consistent Set view `newKeySet()` would.
     */
    private val dirtyLanguages: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val persister = DebouncedPersister(debounceMs, maxDelayMs, scheduler) {
        writeDirtyLanguages()
    }

    private var minFrequency: Int = DEFAULT_MIN_FREQUENCY

    /**
     * Get (lazily loading from storage) the table for a language.
     *
     * API 21 HAZARD: this used to be `languages.computeIfAbsent(lang) { … }`, a
     * Java 8 default method — API 24 — that throws `NoSuchMethodError` on Android
     * 5.0–6.0 (`minSdk` is 21). The double-checked [loadLock] reproduces
     * `computeIfAbsent`'s once-only CONSTRUCTION guarantee, which matters even
     * though this build path is side-effect free: Kotlin's `getOrPut` (the
     * tempting one-liner) is a get-then-`put`, so a second builder would REPLACE
     * a table another thread already holds and drop its records — see the same
     * note on [BigramStore.forLanguage], whose build additionally deletes the
     * legacy blob and must therefore never run twice.
     */
    private fun forLanguage(language: String): LanguageTrigrams {
        val lang = BigramStore.normalizeLanguage(language)
        languages[lang]?.let { return it }
        synchronized(loadLock) {
            languages[lang]?.let { return it }
            val data = LanguageTrigrams()
            storage.getString(storageKey(lang))?.let { loadInto(data, it) }
            languages[lang] = data
            return data
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
     * Is this language's table already resident in RAM? A peek that must NOT load — see
     * [BigramStore.isLanguageLoaded] for why the swipe rescoring path needs it.
     */
    fun isLanguageLoaded(language: String): Boolean =
        // BigramStore.normalizeLanguage, NOT TrigramEntry.normalizeWord — `forLanguage` keys the
        // map with the former, and the two differ (normalizeLanguage maps "" to "en"). Using the
        // word normalizer here would report a loaded language as cold and silently disable
        // rescoring for it forever.
        languages.containsKey(BigramStore.normalizeLanguage(language))

    /**
     * The confident trigram entry for `w1 w2 -> w3`, or null.
     *
     * Same floor as [getConfidentProbability], but returns the ENTRY so a caller can also read
     * `frequency` — which the swipe rescorer needs for the stricter rank-1 promotion floors.
     * Non-loading: returns null rather than building the table.
     */
    fun getConfidentEntry(
        language: String,
        word1: String,
        word2: String,
        word3: String,
    ): TrigramEntry? {
        if (!isLanguageLoaded(language)) return null
        val key = prefixKey(TrigramEntry.normalizeWord(word1), TrigramEntry.normalizeWord(word2))
        val w3 = TrigramEntry.normalizeWord(word3)
        val entries = forLanguage(language).trigramMap[key] ?: return null
        synchronized(this) {
            val entry = entries.find { it.word3 == w3 } ?: return null
            return if (entry.frequency >= minFrequency) entry else null
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

    /**
     * Cascade for a user-initiated learned-phrase delete (ARC-004): remove EVERY trigram
     * whose bigram tail is `(word1 → word2)` — i.e. every entry `(·, word1) → word2` —
     * so the deleted continuation cannot keep surfacing via the trigram path.
     *
     * [BigramStore.removeBigram] alone is not enough: `ContextModel` prefers trigram
     * evidence, so after deleting `want → to` the stored `(i, want) → to` would still
     * suggest "to" for the context `[i, want]`. Unlike [unrecordTrigram] (one observation),
     * this removes the whole entry — the user said "never suggest this", not "one less".
     *
     * The prefix `(word1, word2) → ·` trigrams are deliberately kept: they predict what
     * follows the phrase, they do not suggest word2 itself.
     *
     * @return number of trigram entries removed (across all matching prefixes)
     */
    fun removeContinuationsOf(language: String, word1: String, word2: String): Int {
        val w1 = TrigramEntry.normalizeWord(word1)
        val w2 = TrigramEntry.normalizeWord(word2)
        if (w1.isEmpty() || w2.isEmpty()) return 0

        val lang = BigramStore.normalizeLanguage(language)
        val data = forLanguage(lang)
        val tailSuffix = " $w1"
        var removed = 0

        synchronized(this) {
            // Snapshot keys: we mutate the map inside the loop.
            for (key in data.trigramMap.keys.toList()) {
                if (!key.endsWith(tailSuffix)) continue
                val entries = data.trigramMap[key] ?: continue
                val entry = entries.find { it.word3 == w2 } ?: continue

                entries.remove(entry)
                removed++

                // Rescale: the removed entry's observations no longer count toward the
                // prefix total — same discipline as removeBigram/unrecordTrigram.
                val newTotal = maxOf(0, (data.prefixFrequencies[key] ?: 0) - entry.frequency)
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
        }

        if (removed > 0) {
            dirtyLanguages.add(lang)
            persister.markDirty()
            // Same L4 rationale as BigramStore.removeBigram: a user-initiated delete
            // must not sit in the debounce window — process death would resurrect it.
            persister.requestFlush()
        }
        return removed
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

    /**
     * Serialize a language table to the persisted [FORMAT_VERSION] blob.
     * Caller holds the lock.
     */
    private fun serialize(data: LanguageTrigrams): String {
        val entries = JSONArray()
        data.trigramMap.values.flatten().forEach { entry ->
            entries.put(
                JSONObject().apply {
                    put("word1", entry.word1)
                    put("word2", entry.word2)
                    put("word3", entry.word3)
                    put("frequency", entry.frequency)
                    put("probability", entry.probability.toDouble())
                }
            )
        }

        // ARC-080: the TRUE denominators, which the entry list cannot express once
        // MAX_TRIGRAMS_PER_PREFIX has dropped continuations. Only prefixes that still
        // have entries are written — see BigramStore.serialize for why the orphans
        // are dropped rather than persisted.
        val totals = JSONObject()
        for ((key, total) in data.prefixFrequencies) {
            if (data.trigramMap.containsKey(key)) totals.put(key, total)
        }

        return JSONObject().apply {
            put(KEY_VERSION, FORMAT_VERSION)
            put(KEY_ENTRIES, entries)
            put(KEY_TOTALS, totals)
        }.toString()
    }

    /**
     * Load a persisted JSON blob into a language table. Invalid JSON falls back to empty.
     *
     * Accepts BOTH persisted formats (ARC-080): the v2 object, whose recorded
     * [LanguageTrigrams.prefixFrequencies] are restored verbatim, and the v1 bare
     * array written before ARC-080, whose denominators fall back to the sum of the
     * surviving entries exactly as they always did.
     */
    private fun loadInto(data: LanguageTrigrams, jsonString: String) {
        try {
            val entriesArray: JSONArray
            val persistedTotals: JSONObject?
            when (val root = JSONTokener(jsonString).nextValue()) {
                is JSONArray -> {
                    entriesArray = root
                    persistedTotals = null
                }
                is JSONObject -> {
                    // Lenient rather than an exact version match, so a blob written by
                    // a future version still yields its entries.
                    entriesArray = root.optJSONArray(KEY_ENTRIES) ?: JSONArray()
                    persistedTotals = root.optJSONObject(KEY_TOTALS)
                }
                else -> throw JSONException("unrecognized trigram blob root: ${root?.javaClass}")
            }

            data.trigramMap.clear()
            data.prefixFrequencies.clear()

            for (i in 0 until entriesArray.length()) {
                val obj = entriesArray.getJSONObject(i)
                val entry = TrigramEntry(
                    word1 = obj.getString("word1"),
                    word2 = obj.getString("word2"),
                    word3 = obj.getString("word3"),
                    frequency = obj.getInt("frequency"),
                    probability = obj.getDouble("probability").toFloat()
                )
                val key = prefixKey(entry.word1, entry.word2)
                data.trigramMap.getOrPut(key) { mutableListOf() }.add(entry)
                // Sum of survivors — the v1 denominator, and the floor for the v2 one.
                data.prefixFrequencies[key] = (data.prefixFrequencies[key] ?: 0) + entry.frequency
            }

            if (persistedTotals != null) {
                restoreTotals(data, persistedTotals)
            }

            data.trigramMap.values.forEach { it.sortByDescending { e -> e.probability } }
        } catch (e: Exception) {
            data.trigramMap.clear()
            data.prefixFrequencies.clear()
        }
    }

    /**
     * Replace the sum-of-survivors denominators with the persisted ones and
     * renormalize against them (ARC-080) — mirror of [BigramStore.restoreTotals],
     * including the clamp that stops a truncated or edited blob producing a
     * conditional probability above 1. Reached only from [loadInto] while the table
     * is still under construction ([loadLock], not yet published).
     */
    private fun restoreTotals(data: LanguageTrigrams, persistedTotals: JSONObject) {
        for ((key, entries) in data.trigramMap) {
            val survivorSum = data.prefixFrequencies[key] ?: 0
            val total = maxOf(persistedTotals.optInt(key, 0), survivorSum)

            data.prefixFrequencies[key] = total
            val renormalized = entries.map {
                it.copy(probability = TrigramEntry.calculateProbability(it.frequency, total))
            }
            entries.clear()
            entries.addAll(renormalized)
        }
    }

    /**
     * Export one language's trigram data as a JSON string for backup (ARC-022).
     *
     * Same array-of-objects shape [serialize] persists, pretty-printed — the mirror of
     * [BigramStore.exportToJson]. Consumed by `BackupRestoreManager.buildDictionariesJson`
     * under the `learned_trigrams_by_language` key.
     */
    fun exportToJson(language: String): String {
        synchronized(this) {
            val json = JSONArray()
            forLanguage(language).trigramMap.values.flatten().forEach { entry ->
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
            return json.toString(2) // Pretty print with 2-space indent (matches BigramStore)
        }
    }

    /**
     * Import trigram data for one language from a JSON string (ARC-022).
     *
     * MERGE semantics, exactly like [BigramStore.importFromJson]: frequencies ADD to any
     * existing entry (a direct O(entries) merge, not an O(total frequency) replay), then
     * conditional probabilities are recomputed against the merged prefix totals and both
     * caps ([MAX_TRIGRAMS_PER_PREFIX], [MAX_TOTAL_TRIGRAMS]) are re-enforced.
     *
     * Applies the same record-time guards as [recordTrigram] so a hand-edited or hostile
     * backup cannot inject entries the learning path would never produce: empty words are
     * dropped, non-positive frequencies are dropped, and `word2 == word3` self-references
     * are dropped.
     *
     * Invalid JSON is ignored (no partial mutation beyond whatever parsed before the throw,
     * which the outer try covers) — an absent/garbage section must never fail an import.
     */
    fun importFromJson(language: String, jsonString: String) {
        val lang = BigramStore.normalizeLanguage(language)
        try {
            val json = JSONArray(jsonString)
            val data = forLanguage(lang)

            synchronized(this) {
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val w1 = TrigramEntry.normalizeWord(obj.getString("word1"))
                    val w2 = TrigramEntry.normalizeWord(obj.getString("word2"))
                    val w3 = TrigramEntry.normalizeWord(obj.getString("word3"))
                    val frequency = obj.getInt("frequency")
                    if (w1.isEmpty() || w2.isEmpty() || w3.isEmpty() || frequency <= 0) continue
                    if (w2 == w3) continue // Same guard recordTrigram applies

                    val key = prefixKey(w1, w2)
                    data.prefixFrequencies[key] = (data.prefixFrequencies[key] ?: 0) + frequency

                    val entries = data.trigramMap.getOrPut(key) { mutableListOf() }
                    val existing = entries.find { it.word3 == w3 }
                    if (existing != null) {
                        entries.remove(existing)
                        entries.add(existing.copy(frequency = existing.frequency + frequency))
                    } else {
                        entries.add(TrigramEntry(w1, w2, w3, frequency, 0f))
                    }
                }

                // Recompute probabilities against the merged prefix totals + enforce caps.
                for ((key, entries) in data.trigramMap) {
                    val total = data.prefixFrequencies[key] ?: continue
                    val recomputed = entries.map {
                        it.copy(probability = TrigramEntry.calculateProbability(it.frequency, total))
                    }
                    entries.clear()
                    entries.addAll(recomputed)
                    entries.sortByDescending { it.probability }
                    if (entries.size > MAX_TRIGRAMS_PER_PREFIX) {
                        entries.subList(MAX_TRIGRAMS_PER_PREFIX, entries.size).clear()
                    }
                }
                pruneIfNeeded(data)
            }

            dirtyLanguages.add(lang)
            persister.markDirty()
            // Synchronous checkpoint: an import is a user-initiated bulk write that must
            // survive process death immediately (same rationale as BigramStore.importFromJson).
            persister.flush()
        } catch (e: Exception) {
            // Invalid JSON, ignore
        }
    }
}
