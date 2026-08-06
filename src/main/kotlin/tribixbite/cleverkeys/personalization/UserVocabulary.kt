package tribixbite.cleverkeys.personalization

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import tribixbite.cleverkeys.persist.DebouncedPersister
import tribixbite.cleverkeys.persist.LearnedDataStorage
import tribixbite.cleverkeys.persist.SharedPrefsLearnedStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import kotlin.concurrent.thread

/**
 * User vocabulary tracker for personalized word prediction.
 *
 * Maintains a personal dictionary of words the user frequently types, with usage statistics
 * that enable adaptive prediction boosting. All data is stored locally in SharedPreferences
 * for privacy preservation.
 *
 * PROCESS-WIDE SINGLETON (2026-08-06 persistence fix): every per-language
 * `WordPredictor` used to construct its own instance over the SAME prefs file —
 * concurrent whole-map saves from different instances clobbered each other.
 * [getInstance] guarantees a single writer.
 *
 * Persistence is dirty-flag + debounced write-back (~5 s debounce, ~30 s cap) via
 * [DebouncedPersister] — the previous implementation spawned a raw Thread and
 * re-serialized all ~5000 entries on EVERY committed word. Lifecycle call sites
 * flush via [flush]/[requestFlush].
 *
 * Thread-safe for concurrent access during typing.
 *
 * Example:
 * ```kotlin
 * val vocabulary = UserVocabulary.getInstance(context)
 * vocabulary.recordWordUsage("kotlin") // Learn from typing
 * val boost = vocabulary.getPersonalizationBoost("kotlin") // Get prediction boost
 * vocabulary.cleanupStaleWords() // Periodic maintenance
 * ```
 */
class UserVocabulary internal constructor(
    private val storage: LearnedDataStorage,
    debounceMs: Long = DebouncedPersister.DEFAULT_DEBOUNCE_MS,
    maxDelayMs: Long = DebouncedPersister.DEFAULT_MAX_DELAY_MS,
    scheduler: ScheduledExecutorService = DebouncedPersister.sharedScheduler(),
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "UserVocabulary"
        private const val PREFS_NAME = "user_vocabulary"
        private const val PREFS_KEY_WORDS = "vocabulary_data"

        // Limits to prevent unbounded growth
        private const val MAX_VOCABULARY_SIZE = 5000
        private const val MIN_USAGE_THRESHOLD = 2 // Must use word 2+ times to keep it

        // Cleanup frequency
        private const val CLEANUP_INTERVAL_MS = 86400000L // 24 hours

        @Volatile
        private var instance: UserVocabulary? = null

        /** Process-wide singleton backed by the `user_vocabulary` SharedPreferences file. */
        @JvmStatic
        fun getInstance(context: Context): UserVocabulary {
            return instance ?: synchronized(this) {
                instance ?: UserVocabulary(
                    storage = SharedPrefsLearnedStorage(
                        context.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ),
                    log = { msg, e ->
                        if (e != null) android.util.Log.e(TAG, msg, e)
                        else android.util.Log.d(TAG, msg)
                    }
                ).also { instance = it }
            }
        }
    }

    // Primary storage: word → usage data
    private val vocabulary: ConcurrentHashMap<String, UserWordUsage> = ConcurrentHashMap()

    // Gson for JSON serialization
    private val gson = Gson()

    // Debounced write-back — coalesces the per-word save storm into one write.
    private val persister = DebouncedPersister(debounceMs, maxDelayMs, scheduler) {
        writeToStorage()
    }

    // Track last cleanup time
    private var lastCleanup: Long = System.currentTimeMillis()

    init {
        loadFromStorage()
        log("UserVocabulary initialized with ${vocabulary.size} words", null)
    }

    /**
     * Record that the user typed a word.
     *
     * If the word is already tracked, increments usage count and updates timestamp.
     * If new, adds to vocabulary with initial usage count of 1.
     *
     * Persistence: marks the store dirty; the debounced persister writes back
     * in the background (NOT a per-word serialization).
     */
    fun recordWordUsage(word: String, timestamp: Long = System.currentTimeMillis()) {
        val normalized = UserWordUsage.normalizeWord(word)

        if (normalized.isEmpty() || normalized.length < 2) {
            return // Skip very short words
        }

        synchronized(this) {
            val existing = vocabulary[normalized]

            if (existing != null) {
                // Update existing entry
                vocabulary[normalized] = existing.recordNewUsage(timestamp)
            } else {
                // Add new entry
                if (vocabulary.size >= MAX_VOCABULARY_SIZE) {
                    // At capacity, remove least valuable word before adding
                    removeLowestValueWord()
                }
                vocabulary[normalized] = UserWordUsage(
                    word = normalized,
                    usageCount = 1,
                    lastUsed = timestamp,
                    firstUsed = timestamp
                )
            }

            // Periodic cleanup (async, non-blocking)
            if (timestamp - lastCleanup > CLEANUP_INTERVAL_MS) {
                performCleanupAsync()
            }
        }

        persister.markDirty()
    }

    /**
     * Get personalization boost for a word.
     *
     * Returns:
     * - 0.0-4.0 for known words (based on frequency and recency)
     * - 0.0 for unknown words
     */
    fun getPersonalizationBoost(word: String, currentTime: Long = System.currentTimeMillis()): Float {
        val normalized = UserWordUsage.normalizeWord(word)
        val usage = vocabulary[normalized] ?: return 0.0f

        return usage.getPersonalizationBoost(currentTime)
    }

    /**
     * Check if a word is in the user's vocabulary.
     */
    fun hasWord(word: String): Boolean {
        val normalized = UserWordUsage.normalizeWord(word)
        return vocabulary.containsKey(normalized)
    }

    /**
     * Get usage statistics for a word.
     */
    fun getWordUsage(word: String): UserWordUsage? {
        val normalized = UserWordUsage.normalizeWord(word)
        return vocabulary[normalized]
    }

    /**
     * Get all words in vocabulary, sorted by personalization boost (descending).
     */
    fun getAllWords(): List<UserWordUsage> {
        val currentTime = System.currentTimeMillis()
        return vocabulary.values
            .sortedByDescending { it.getPersonalizationBoost(currentTime) }
    }

    /**
     * Get top N most personalized words.
     */
    fun getTopWords(limit: Int = 100): List<UserWordUsage> {
        return getAllWords().take(limit)
    }

    /**
     * Get vocabulary size.
     */
    fun size(): Int = vocabulary.size

    /**
     * Remove a single word from the vocabulary (user-initiated edit from the
     * learned-data manager).
     *
     * @return true if the word was present and removed
     */
    fun removeWord(word: String): Boolean {
        val normalized = UserWordUsage.normalizeWord(word)
        val removed = synchronized(this) { vocabulary.remove(normalized) != null }
        if (removed) {
            persister.markDirty()
            // L4 (review 2026-08-06): user-initiated delete — flush promptly so
            // process death inside the debounce window can't resurrect the word.
            persister.requestFlush()
        }
        return removed
    }

    /**
     * Remove stale words from vocabulary.
     *
     * A word is stale if:
     * - Last used more than 90 days ago, OR
     * - Only used once and more than 30 days old
     */
    fun cleanupStaleWords(currentTime: Long = System.currentTimeMillis()): Int {
        var removedCount = 0

        synchronized(this) {
            val staleWords = vocabulary.values.filter { it.isStale(currentTime) }

            staleWords.forEach { usage ->
                vocabulary.remove(usage.word)
                removedCount++
            }

            lastCleanup = currentTime
        }

        if (removedCount > 0) {
            log("Cleaned up $removedCount stale words", null)
            persister.markDirty()
        }

        return removedCount
    }

    /**
     * Remove words below minimum usage threshold.
     */
    private fun removeLowestValueWord() {
        val currentTime = System.currentTimeMillis()

        // Find word with lowest personalization boost
        val lowestValueWord = vocabulary.values.minByOrNull {
            it.getPersonalizationBoost(currentTime)
        }

        lowestValueWord?.let {
            vocabulary.remove(it.word)
            log("Removed low-value word: ${it.word}", null)
        }
    }

    /**
     * Clear all vocabulary data (user reset). Persists the removal immediately.
     */
    fun clearAll() {
        // M1 (review 2026-08-06): the storage removal happens INSIDE the same
        // lock [writeToStorage] holds across serialize+write, so an in-flight
        // flush can never re-persist the just-forgotten vocabulary.
        synchronized(this) {
            vocabulary.clear()
            lastCleanup = System.currentTimeMillis()
            storage.remove(PREFS_KEY_WORDS)
        }
        log("User vocabulary cleared", null)
    }

    /**
     * Export vocabulary to JSON string.
     */
    fun exportToJson(): String {
        val data = getAllWords()
        return gson.toJson(data)
    }

    /**
     * Import vocabulary from JSON string (replaces current vocabulary).
     *
     * @return number of words imported (0 on parse failure)
     */
    fun importFromJson(json: String): Int {
        return try {
            val type = object : TypeToken<List<UserWordUsage>>() {}.type
            val imported: List<UserWordUsage> = gson.fromJson(json, type)

            synchronized(this) {
                vocabulary.clear()

                imported.forEach { usage ->
                    if (vocabulary.size < MAX_VOCABULARY_SIZE) {
                        vocabulary[usage.word] = usage
                    }
                }
            }

            persister.markDirty()
            persister.flush()
            log("Imported ${vocabulary.size} words from JSON", null)
            vocabulary.size
        } catch (e: Exception) {
            log("Failed to import vocabulary from JSON", e)
            0
        }
    }

    /** @return true if there are unflushed in-RAM changes. */
    fun isDirty(): Boolean = persister.isDirty()

    /**
     * Synchronously flush unflushed changes (idempotent, no-op when clean).
     */
    fun flush() = persister.flush()

    /**
     * Asynchronously flush on the persistence thread — preferred from
     * main-thread lifecycle call sites. No-op when clean.
     */
    fun requestFlush() = persister.requestFlush()

    /**
     * Load vocabulary from storage.
     */
    private fun loadFromStorage() {
        val json = storage.getString(PREFS_KEY_WORDS) ?: return

        try {
            val type = object : TypeToken<List<UserWordUsage>>() {}.type
            val loaded: List<UserWordUsage> = gson.fromJson(json, type)

            loaded.forEach { usage ->
                vocabulary[usage.word] = usage
            }

            log("Loaded ${vocabulary.size} words from storage", null)
        } catch (e: Exception) {
            log("Failed to load vocabulary from storage", e)
        }
    }

    /**
     * Serialize the whole vocabulary and write it to storage.
     * Runs on the persistence thread (or the caller of [flush]).
     */
    private fun writeToStorage() {
        // M1 (review 2026-08-06): serialize AND write under ONE lock (shared with
        // [clearAll]) so a forget can never interleave between them and be
        // resurrected. An empty vocabulary maps to key REMOVAL (post-clear flush,
        // or last word removed) — never an empty-blob overwrite of a forget.
        val size = synchronized(this) {
            if (vocabulary.isEmpty()) {
                storage.remove(PREFS_KEY_WORDS)
            } else {
                storage.putString(PREFS_KEY_WORDS, gson.toJson(getAllWords()))
            }
            vocabulary.size
        }
        log("Saved $size words to storage", null)
    }

    /**
     * Perform cleanup asynchronously.
     */
    private fun performCleanupAsync() {
        thread {
            cleanupStaleWords()
        }
    }

    /**
     * Get statistics about the vocabulary.
     */
    fun getStats(): VocabularyStats {
        val currentTime = System.currentTimeMillis()
        val allWords = vocabulary.values.toList()

        return VocabularyStats(
            totalWords = allWords.size,
            averageUsageCount = allWords.map { it.usageCount }.average(),
            mostUsedWord = allWords.maxByOrNull { it.usageCount },
            recentlyUsedCount = allWords.count {
                (currentTime - it.lastUsed) < 7 * 86400000L // Last 7 days
            }
        )
    }
}

/**
 * Statistics about user vocabulary.
 */
data class VocabularyStats(
    val totalWords: Int,
    val averageUsageCount: Double,
    val mostUsedWord: UserWordUsage?,
    val recentlyUsedCount: Int
)
