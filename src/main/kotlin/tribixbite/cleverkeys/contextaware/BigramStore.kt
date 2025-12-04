package tribixbite.cleverkeys.contextaware

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Efficient storage and retrieval of bigram (word pair) data for context-aware predictions.
 *
 * Data Structure:
 * - HashMap<word1, List<BigramEntry>> for O(1) lookup by previous word
 * - Thread-safe for concurrent access during typing
 * - Persistent storage in SharedPreferences
 *
 * Usage:
 * ```kotlin
 * val store = BigramStore(context)
 * store.recordBigram("I", "am")  // Learn from usage
 * val predictions = store.getPredictions("I")  // Get likely next words after "I"
 * ```
 *
 * Performance:
 * - Lookup: O(1) for finding all word2 options given word1
 * - Memory: ~10KB for 1000 bigrams (typical usage)
 * - Persistence: Async save to SharedPreferences
 */
class BigramStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "bigram_store"
        private const val KEY_BIGRAMS = "bigrams_json"
        private const val DEFAULT_MIN_FREQUENCY = 2  // Ignore hapax legomena (single occurrences)
        private const val MAX_BIGRAMS_PER_WORD = 20  // Top 20 predictions per previous word
        private const val MAX_TOTAL_BIGRAMS = 10000  // Overall storage limit
    }

    // Primary data structure: word1 → List of BigramEntry
    private val bigramMap: ConcurrentHashMap<String, MutableList<BigramEntry>> = ConcurrentHashMap()

    // Word1 frequency tracking for probability calculation
    private val word1Frequencies: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    // SharedPreferences for persistence
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Minimum frequency threshold for keeping bigrams
    private var minFrequency: Int = DEFAULT_MIN_FREQUENCY

    init {
        loadFromPreferences()
    }

    /**
     * Record a bigram occurrence from user typing.
     * Increments frequency and recalculates probability.
     *
     * Thread-safe: Uses ConcurrentHashMap for concurrent updates.
     *
     * @param word1 Previous word (context)
     * @param word2 Current word (prediction target)
     */
    fun recordBigram(word1: String, word2: String) {
        val normalizedWord1 = BigramEntry.normalizeWord(word1)
        val normalizedWord2 = BigramEntry.normalizeWord(word2)

        // Skip empty or invalid words
        if (normalizedWord1.isEmpty() || normalizedWord2.isEmpty()) return
        if (normalizedWord1 == normalizedWord2) return  // Skip self-references

        synchronized(this) {
            // Increment word1 total frequency
            val word1Freq = word1Frequencies.getOrDefault(normalizedWord1, 0) + 1
            word1Frequencies[normalizedWord1] = word1Freq

            // Find or create bigram entry
            val entries = bigramMap.getOrPut(normalizedWord1) { mutableListOf() }
            val existingEntry = entries.find { it.word2 == normalizedWord2 }

            if (existingEntry != null) {
                // Update existing entry
                val newFreq = existingEntry.frequency + 1
                val newProb = BigramEntry.calculateProbability(newFreq, word1Freq)
                entries.remove(existingEntry)
                entries.add(existingEntry.copy(frequency = newFreq, probability = newProb))
            } else {
                // Add new entry
                val newEntry = BigramEntry(
                    word1 = normalizedWord1,
                    word2 = normalizedWord2,
                    frequency = 1,
                    probability = BigramEntry.calculateProbability(1, word1Freq)
                )
                entries.add(newEntry)
            }

            // Sort by probability (descending) and limit size
            entries.sortByDescending { it.probability }
            if (entries.size > MAX_BIGRAMS_PER_WORD) {
                entries.subList(MAX_BIGRAMS_PER_WORD, entries.size).clear()
            }

            // Check total bigram count
            pruneIfNeeded()
        }
    }

    /**
     * Get predicted words given a previous word, ranked by probability.
     *
     * @param previousWord The context word
     * @param maxResults Maximum number of predictions to return (default: 10)
     * @param minProbability Minimum probability threshold (default: 0.01 = 1%)
     * @return List of BigramEntry sorted by probability (highest first)
     */
    fun getPredictions(
        previousWord: String,
        maxResults: Int = 10,
        minProbability: Float = 0.01f
    ): List<BigramEntry> {
        val normalized = BigramEntry.normalizeWord(previousWord)
        val entries = bigramMap[normalized] ?: return emptyList()

        return entries
            .filter { it.frequency >= minFrequency && it.probability >= minProbability }
            .take(maxResults)
    }

    /**
     * Get the probability of a specific word pair.
     *
     * @param word1 Previous word
     * @param word2 Predicted word
     * @return Probability (0.0 to 1.0), or 0.0 if bigram not found
     */
    fun getProbability(word1: String, word2: String): Float {
        val normalized1 = BigramEntry.normalizeWord(word1)
        val normalized2 = BigramEntry.normalizeWord(word2)

        val entries = bigramMap[normalized1] ?: return 0f
        return entries.find { it.word2 == normalized2 }?.probability ?: 0f
    }

    /**
     * Get all bigrams for a specific previous word.
     *
     * @param word1 Previous word
     * @return List of all BigramEntry for this word1
     */
    fun getAllBigrams(word1: String): List<BigramEntry> {
        val normalized = BigramEntry.normalizeWord(word1)
        return bigramMap[normalized]?.toList() ?: emptyList()
    }

    /**
     * Get total number of unique bigrams stored.
     */
    fun getTotalBigramCount(): Int {
        return bigramMap.values.sumOf { it.size }
    }

    /**
     * Get number of unique word1 (context) entries.
     */
    fun getContextWordCount(): Int {
        return bigramMap.size
    }

    /**
     * Clear all bigram data (for testing or user-initiated reset).
     */
    fun clear() {
        synchronized(this) {
            bigramMap.clear()
            word1Frequencies.clear()
            saveToPreferences()
        }
    }

    /**
     * Set minimum frequency threshold for keeping bigrams.
     * Bigrams with frequency below this are ignored in predictions.
     *
     * @param minFreq Minimum frequency (default: 2)
     */
    fun setMinimumFrequency(minFreq: Int) {
        minFrequency = maxOf(1, minFreq)
    }

    /**
     * Prune low-frequency bigrams if total count exceeds limit.
     * Keeps most probable bigrams and removes rare ones.
     */
    private fun pruneIfNeeded() {
        val totalCount = getTotalBigramCount()
        if (totalCount <= MAX_TOTAL_BIGRAMS) return

        // Collect all bigrams with their probabilities
        val allBigrams = bigramMap.values.flatten()
        val sortedBigrams = allBigrams.sortedByDescending { it.probability }

        // Keep top MAX_TOTAL_BIGRAMS
        val toKeep = sortedBigrams.take(MAX_TOTAL_BIGRAMS).toSet()

        // Rebuild bigramMap with only kept bigrams
        bigramMap.clear()
        toKeep.forEach { entry ->
            bigramMap.getOrPut(entry.word1) { mutableListOf() }.add(entry)
        }

        // Re-sort each list
        bigramMap.values.forEach { list ->
            list.sortByDescending { it.probability }
        }
    }

    /**
     * Save bigram data to SharedPreferences (async).
     * Format: JSON array of bigram objects.
     */
    fun saveToPreferences() {
        Thread {
            synchronized(this) {
                val json = JSONArray()
                bigramMap.values.flatten().forEach { entry ->
                    val obj = JSONObject().apply {
                        put("word1", entry.word1)
                        put("word2", entry.word2)
                        put("frequency", entry.frequency)
                        put("probability", entry.probability.toDouble())
                    }
                    json.put(obj)
                }

                prefs.edit()
                    .putString(KEY_BIGRAMS, json.toString())
                    .apply()
            }
        }.start()
    }

    /**
     * Load bigram data from SharedPreferences.
     * Called automatically on initialization.
     */
    private fun loadFromPreferences() {
        val jsonString = prefs.getString(KEY_BIGRAMS, null) ?: return

        try {
            val json = JSONArray(jsonString)
            bigramMap.clear()
            word1Frequencies.clear()

            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val entry = BigramEntry(
                    word1 = obj.getString("word1"),
                    word2 = obj.getString("word2"),
                    frequency = obj.getInt("frequency"),
                    probability = obj.getDouble("probability").toFloat()
                )

                bigramMap.getOrPut(entry.word1) { mutableListOf() }.add(entry)

                // Reconstruct word1 frequencies
                val currentFreq = word1Frequencies.getOrDefault(entry.word1, 0)
                word1Frequencies[entry.word1] = currentFreq + entry.frequency
            }

            // Sort all lists by probability
            bigramMap.values.forEach { list ->
                list.sortByDescending { it.probability }
            }
        } catch (e: Exception) {
            // Invalid JSON, start fresh
            bigramMap.clear()
            word1Frequencies.clear()
        }
    }

    /**
     * Export bigram data as JSON string for backup or analysis.
     */
    fun exportToJson(): String {
        val json = JSONArray()
        bigramMap.values.flatten().forEach { entry ->
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

    /**
     * Import bigram data from JSON string.
     * Merges with existing data (adds frequencies together).
     *
     * @param jsonString JSON array of bigram objects
     */
    fun importFromJson(jsonString: String) {
        try {
            val json = JSONArray(jsonString)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val word1 = obj.getString("word1")
                val word2 = obj.getString("word2")
                val frequency = obj.getInt("frequency")

                // Record multiple times to add frequency
                repeat(frequency) {
                    recordBigram(word1, word2)
                }
            }
            saveToPreferences()
        } catch (e: Exception) {
            // Invalid JSON, ignore
        }
    }

    /**
     * Get statistics about the bigram store.
     */
    data class BigramStats(
        val totalBigrams: Int,
        val uniqueContextWords: Int,
        val averageBigramsPerContext: Float,
        val topContextWords: List<Pair<String, Int>>  // word1 with count of bigrams
    )

    fun getStatistics(): BigramStats {
        val totalBigrams = getTotalBigramCount()
        val uniqueContextWords = getContextWordCount()
        val average = if (uniqueContextWords > 0) {
            totalBigrams.toFloat() / uniqueContextWords.toFloat()
        } else 0f

        val topWords = bigramMap.entries
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
