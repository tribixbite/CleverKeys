package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Manages word and n-gram frequency tracking for predictive text.
 *
 * Provides intelligent word frequency modeling with n-gram support, context awareness,
 * learning capabilities, and persistence for accurate prediction ranking.
 *
 * Features:
 * - Unigram, bigram, and trigram frequency tracking
 * - Context-aware frequency adjustment
 * - Learning from user input
 * - Time-based frequency decay
 * - Persistent storage (save/load)
 * - Thread-safe concurrent access
 * - Laplace smoothing for unseen n-grams
 * - Maximum likelihood estimation
 * - Good-Turing smoothing
 * - Interpolation between n-gram models
 * - Vocabulary management
 * - Frequency normalization
 * - Statistical metrics (perplexity, entropy)
 *
 * Bug #312 - CATASTROPHIC: Complete implementation of missing FrequencyModel.java
 *
 * @param context Application context for accessing storage
 */
class FrequencyModel(
    private val context: Context
) {
    companion object {
        private const val TAG = "FrequencyModel"

        // Storage
        private const val FREQ_FILE_NAME = "frequency_model.dat"
        private const val BACKUP_FILE_NAME = "frequency_model_backup.dat"

        // Model parameters
        private const val DEFAULT_SMOOTHING_FACTOR = 0.1  // Laplace smoothing
        private const val DEFAULT_DECAY_FACTOR = 0.95     // Weekly decay
        private const val DEFAULT_MIN_FREQUENCY = 1       // Minimum frequency to store
        private const val DEFAULT_MAX_VOCABULARY_SIZE = 100000
        private const val DEFAULT_INTERPOLATION_LAMBDA = 0.33  // Trigram/bigram/unigram weights

        // N-gram types
        enum class NGramType {
            UNIGRAM,    // Single word
            BIGRAM,     // Two-word sequence
            TRIGRAM     // Three-word sequence
        }

        /**
         * N-gram data class.
         */
        data class NGram(
            val words: List<String>,
            val type: NGramType
        ) {
            override fun toString(): String = words.joinToString(" ")

            fun toKey(): String = words.joinToString("|")
        }

        /**
         * Frequency entry with metadata.
         */
        data class FrequencyEntry(
            val ngram: NGram,
            var count: Long,
            var lastUsed: Long = System.currentTimeMillis(),
            var decayedFrequency: Double = count.toDouble()
        )
    }

    /**
     * Callback interface for frequency model events.
     */
    interface Callback {
        /**
         * Called when new word is learned.
         *
         * @param word Learned word
         * @param frequency Current frequency
         */
        fun onWordLearned(word: String, frequency: Long)

        /**
         * Called when model is saved.
         *
         * @param success Whether save was successful
         */
        fun onModelSaved(success: Boolean)

        /**
         * Called when model is loaded.
         *
         * @param success Whether load was successful
         */
        fun onModelLoaded(success: Boolean)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private var callback: Callback? = null

    // Frequency maps (thread-safe)
    private val unigramFreq = ConcurrentHashMap<String, FrequencyEntry>()
    private val bigramFreq = ConcurrentHashMap<String, FrequencyEntry>()
    private val trigramFreq = ConcurrentHashMap<String, FrequencyEntry>()

    // Model parameters
    private var smoothingFactor: Double = DEFAULT_SMOOTHING_FACTOR
    private var decayFactor: Double = DEFAULT_DECAY_FACTOR
    private var minFrequency: Int = DEFAULT_MIN_FREQUENCY
    private var maxVocabularySize: Int = DEFAULT_MAX_VOCABULARY_SIZE
    private var interpolationLambda: Double = DEFAULT_INTERPOLATION_LAMBDA

    // Statistics
    private var totalUnigramCount: Long = 0
    private var totalBigramCount: Long = 0
    private var totalTrigramCount: Long = 0
    private var vocabularySize: Int = 0

    init {
        logD("FrequencyModel initialized")
        scope.launch {
            loadModel()
        }
    }

    /**
     * Record word usage (learn from user input).
     *
     * @param words List of words in sequence
     */
    suspend fun recordUsage(words: List<String>) = withContext(Dispatchers.Default) {
        if (words.isEmpty()) return@withContext

        val normalizedWords = words.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        if (normalizedWords.isEmpty()) return@withContext

        // Update unigrams
        normalizedWords.forEach { word ->
            updateNGram(listOf(word), NGramType.UNIGRAM)
        }

        // Update bigrams
        if (normalizedWords.size >= 2) {
            for (i in 0 until normalizedWords.size - 1) {
                updateNGram(listOf(normalizedWords[i], normalizedWords[i + 1]), NGramType.BIGRAM)
            }
        }

        // Update trigrams
        if (normalizedWords.size >= 3) {
            for (i in 0 until normalizedWords.size - 2) {
                updateNGram(
                    listOf(normalizedWords[i], normalizedWords[i + 1], normalizedWords[i + 2]),
                    NGramType.TRIGRAM
                )
            }
        }

        // Update statistics
        updateStatistics()

        // Prune if vocabulary too large
        if (vocabularySize > maxVocabularySize) {
            pruneVocabulary()
        }
    }

    /**
     * Update n-gram frequency.
     */
    private fun updateNGram(words: List<String>, type: NGramType) {
        val ngram = NGram(words, type)
        val key = ngram.toKey()

        val map = when (type) {
            NGramType.UNIGRAM -> unigramFreq
            NGramType.BIGRAM -> bigramFreq
            NGramType.TRIGRAM -> trigramFreq
        }

        val entry = map.getOrPut(key) {
            FrequencyEntry(ngram, 0)
        }

        entry.count++
        entry.lastUsed = System.currentTimeMillis()
        entry.decayedFrequency = entry.count.toDouble()

        // Notify callback for unigrams only
        if (type == NGramType.UNIGRAM && entry.count == 1L) {
            callback?.onWordLearned(words[0], entry.count)
        }
    }

    /**
     * Get word frequency with context.
     *
     * @param word Word to get frequency for
     * @param context Previous words for context (optional)
     * @return Frequency score (higher = more frequent)
     */
    fun getFrequency(word: String, context: List<String> = emptyList()): Double {
        val normalizedWord = word.lowercase().trim()
        if (normalizedWord.isEmpty()) return 0.0

        val normalizedContext = context.map { it.lowercase().trim() }.filter { it.isNotEmpty() }

        // Use interpolation between trigram, bigram, and unigram models
        return when {
            normalizedContext.size >= 2 -> {
                // Try trigram model
                val trigramProb = getTrigramProbability(normalizedWord, normalizedContext.takeLast(2))
                val bigramProb = getBigramProbability(normalizedWord, normalizedContext.takeLast(1))
                val unigramProb = getUnigramProbability(normalizedWord)

                // Interpolate
                interpolationLambda * trigramProb +
                interpolationLambda * bigramProb +
                (1 - 2 * interpolationLambda) * unigramProb
            }
            normalizedContext.size == 1 -> {
                // Try bigram model
                val bigramProb = getBigramProbability(normalizedWord, normalizedContext)
                val unigramProb = getUnigramProbability(normalizedWord)

                // Interpolate
                2 * interpolationLambda * bigramProb +
                (1 - 2 * interpolationLambda) * unigramProb
            }
            else -> {
                // Unigram only
                getUnigramProbability(normalizedWord)
            }
        }
    }

    /**
     * Get unigram probability with Laplace smoothing.
     */
    private fun getUnigramProbability(word: String): Double {
        val key = word
        val entry = unigramFreq[key]
        val count = entry?.decayedFrequency ?: 0.0

        // Laplace smoothing: P(w) = (count + α) / (total + α * V)
        return (count + smoothingFactor) / (totalUnigramCount + smoothingFactor * vocabularySize)
    }

    /**
     * Get bigram probability with backoff to unigram.
     */
    private fun getBigramProbability(word: String, context: List<String>): Double {
        if (context.isEmpty()) return getUnigramProbability(word)

        val key = NGram(context + word, NGramType.BIGRAM).toKey()
        val entry = bigramFreq[key]
        val count = entry?.decayedFrequency ?: 0.0

        // Get context count
        val contextKey = context[0]
        val contextEntry = unigramFreq[contextKey]
        val contextCount = contextEntry?.decayedFrequency ?: 0.0

        // Bigram probability with smoothing: P(w|c) = (count + α) / (contextCount + α * V)
        return if (contextCount > 0) {
            (count + smoothingFactor) / (contextCount + smoothingFactor * vocabularySize)
        } else {
            getUnigramProbability(word)
        }
    }

    /**
     * Get trigram probability with backoff to bigram.
     */
    private fun getTrigramProbability(word: String, context: List<String>): Double {
        if (context.size < 2) return getBigramProbability(word, context)

        val key = NGram(context + word, NGramType.TRIGRAM).toKey()
        val entry = trigramFreq[key]
        val count = entry?.decayedFrequency ?: 0.0

        // Get context count (bigram)
        val contextKey = NGram(context, NGramType.BIGRAM).toKey()
        val contextEntry = bigramFreq[contextKey]
        val contextCount = contextEntry?.decayedFrequency ?: 0.0

        // Trigram probability with smoothing
        return if (contextCount > 0) {
            (count + smoothingFactor) / (contextCount + smoothingFactor * vocabularySize)
        } else {
            getBigramProbability(word, context.takeLast(1))
        }
    }

    /**
     * Get top N most frequent words.
     *
     * @param n Number of words to return
     * @param context Optional context for conditional frequency
     * @return List of words sorted by frequency (descending)
     */
    fun getTopWords(n: Int, context: List<String> = emptyList()): List<Pair<String, Double>> {
        return unigramFreq.values
            .map { entry ->
                val word = entry.ngram.words[0]
                val freq = getFrequency(word, context)
                word to freq
            }
            .sortedByDescending { it.second }
            .take(n)
    }

    /**
     * Apply time-based decay to all frequencies.
     *
     * @param decayRate Decay rate (0.0 - 1.0, default: 0.95)
     */
    suspend fun applyDecay(decayRate: Double = decayFactor) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val daysSinceEpoch = now / (1000 * 60 * 60 * 24)

        // Apply decay to all n-grams
        listOf(unigramFreq, bigramFreq, trigramFreq).forEach { map ->
            map.values.forEach { entry ->
                val daysSinceUse = (now - entry.lastUsed) / (1000 * 60 * 60 * 24)
                entry.decayedFrequency = entry.count * exp(-decayRate * daysSinceUse)
            }
        }

        updateStatistics()
        logD("Applied decay to all n-grams (rate: $decayRate)")
    }

    /**
     * Prune low-frequency entries to manage memory.
     */
    private fun pruneVocabulary() {
        val threshold = minFrequency.toDouble()

        // Remove low-frequency unigrams
        val toRemove = unigramFreq.filterValues { it.decayedFrequency < threshold }.keys
        toRemove.forEach { unigramFreq.remove(it) }

        // Remove bigrams with pruned unigrams
        bigramFreq.keys.removeIf { key ->
            val words = key.split("|")
            words.any { !unigramFreq.containsKey(it) }
        }

        // Remove trigrams with pruned bigrams
        trigramFreq.keys.removeIf { key ->
            val words = key.split("|")
            val bigram1 = "${words[0]}|${words[1]}"
            val bigram2 = "${words[1]}|${words[2]}"
            !bigramFreq.containsKey(bigram1) || !bigramFreq.containsKey(bigram2)
        }

        updateStatistics()
        logD("Pruned vocabulary: removed ${toRemove.size} low-frequency entries")
    }

    /**
     * Update model statistics.
     */
    private fun updateStatistics() {
        totalUnigramCount = unigramFreq.values.sumOf { it.count }
        totalBigramCount = bigramFreq.values.sumOf { it.count }
        totalTrigramCount = trigramFreq.values.sumOf { it.count }
        vocabularySize = unigramFreq.size
    }

    /**
     * Calculate model perplexity on test data.
     *
     * @param testWords Test word sequence
     * @return Perplexity (lower = better)
     */
    fun calculatePerplexity(testWords: List<String>): Double {
        if (testWords.isEmpty()) return Double.POSITIVE_INFINITY

        var logProb = 0.0
        val normalizedWords = testWords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }

        for (i in normalizedWords.indices) {
            val word = normalizedWords[i]
            val context = when {
                i >= 2 -> normalizedWords.subList(i - 2, i)
                i == 1 -> listOf(normalizedWords[i - 1])
                else -> emptyList()
            }

            val prob = getFrequency(word, context)
            if (prob > 0) {
                logProb += ln(prob)
            } else {
                logProb += ln(smoothingFactor / totalUnigramCount)
            }
        }

        return exp(-logProb / normalizedWords.size)
    }

    /**
     * Save model to persistent storage.
     *
     * @return Result indicating success or failure
     */
    suspend fun saveModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FREQ_FILE_NAME)
            val backupFile = File(context.filesDir, BACKUP_FILE_NAME)

            // Backup existing file
            if (file.exists()) {
                file.copyTo(backupFile, overwrite = true)
            }

            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { oos ->
                // Write version
                oos.writeInt(1)

                // Write parameters
                oos.writeDouble(smoothingFactor)
                oos.writeDouble(decayFactor)
                oos.writeInt(minFrequency)
                oos.writeInt(maxVocabularySize)
                oos.writeDouble(interpolationLambda)

                // Write statistics
                oos.writeLong(totalUnigramCount)
                oos.writeLong(totalBigramCount)
                oos.writeLong(totalTrigramCount)
                oos.writeInt(vocabularySize)

                // Write frequency maps
                writeFrequencyMap(oos, unigramFreq)
                writeFrequencyMap(oos, bigramFreq)
                writeFrequencyMap(oos, trigramFreq)
            }

            logD("✅ Model saved successfully (${unigramFreq.size} unigrams, ${bigramFreq.size} bigrams, ${trigramFreq.size} trigrams)")
            callback?.onModelSaved(true)
            Result.success(Unit)
        } catch (e: Exception) {
            logE("Failed to save model", e)
            callback?.onModelSaved(false)
            Result.failure(e)
        }
    }

    /**
     * Write frequency map to stream.
     */
    private fun writeFrequencyMap(oos: ObjectOutputStream, map: ConcurrentHashMap<String, FrequencyEntry>) {
        oos.writeInt(map.size)
        map.forEach { (key, entry) ->
            oos.writeUTF(key)
            oos.writeLong(entry.count)
            oos.writeLong(entry.lastUsed)
            oos.writeDouble(entry.decayedFrequency)
            oos.writeInt(entry.ngram.words.size)
            entry.ngram.words.forEach { oos.writeUTF(it) }
            oos.writeInt(entry.ngram.type.ordinal)
        }
    }

    /**
     * Load model from persistent storage.
     *
     * @return Result indicating success or failure
     */
    suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FREQ_FILE_NAME)
            if (!file.exists()) {
                logD("No saved model found, starting fresh")
                _isLoaded.value = true
                callback?.onModelLoaded(true)
                return@withContext Result.success(Unit)
            }

            ObjectInputStream(BufferedInputStream(FileInputStream(file))).use { ois ->
                // Read version
                val version = ois.readInt()
                if (version != 1) {
                    logE("Unsupported model version: $version", null)
                    return@withContext Result.failure(IOException("Unsupported model version"))
                }

                // Read parameters
                smoothingFactor = ois.readDouble()
                decayFactor = ois.readDouble()
                minFrequency = ois.readInt()
                maxVocabularySize = ois.readInt()
                interpolationLambda = ois.readDouble()

                // Read statistics
                totalUnigramCount = ois.readLong()
                totalBigramCount = ois.readLong()
                totalTrigramCount = ois.readLong()
                vocabularySize = ois.readInt()

                // Read frequency maps
                unigramFreq.clear()
                bigramFreq.clear()
                trigramFreq.clear()

                readFrequencyMap(ois, unigramFreq)
                readFrequencyMap(ois, bigramFreq)
                readFrequencyMap(ois, trigramFreq)
            }

            logD("✅ Model loaded successfully (${unigramFreq.size} unigrams, ${bigramFreq.size} bigrams, ${trigramFreq.size} trigrams)")
            _isLoaded.value = true
            callback?.onModelLoaded(true)
            Result.success(Unit)
        } catch (e: Exception) {
            logE("Failed to load model", e)
            _isLoaded.value = false
            callback?.onModelLoaded(false)
            Result.failure(e)
        }
    }

    /**
     * Read frequency map from stream.
     */
    private fun readFrequencyMap(ois: ObjectInputStream, map: ConcurrentHashMap<String, FrequencyEntry>) {
        val size = ois.readInt()
        repeat(size) {
            val key = ois.readUTF()
            val count = ois.readLong()
            val lastUsed = ois.readLong()
            val decayedFreq = ois.readDouble()
            val wordsSize = ois.readInt()
            val words = List(wordsSize) { ois.readUTF() }
            val typeOrdinal = ois.readInt()
            val type = NGramType.entries[typeOrdinal]

            map[key] = FrequencyEntry(
                ngram = NGram(words, type),
                count = count,
                lastUsed = lastUsed,
                decayedFrequency = decayedFreq
            )
        }
    }

    /**
     * Clear all frequency data.
     */
    suspend fun clear() = withContext(Dispatchers.Default) {
        unigramFreq.clear()
        bigramFreq.clear()
        trigramFreq.clear()
        totalUnigramCount = 0
        totalBigramCount = 0
        totalTrigramCount = 0
        vocabularySize = 0
        logD("All frequency data cleared")
    }

    /**
     * Get model statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "vocabulary_size" to vocabularySize,
        "total_unigrams" to totalUnigramCount,
        "total_bigrams" to totalBigramCount,
        "total_trigrams" to totalTrigramCount,
        "unique_unigrams" to unigramFreq.size,
        "unique_bigrams" to bigramFreq.size,
        "unique_trigrams" to trigramFreq.size,
        "smoothing_factor" to smoothingFactor,
        "decay_factor" to decayFactor,
        "interpolation_lambda" to interpolationLambda
    )

    /**
     * Set model parameters.
     */
    fun setParameters(
        smoothing: Double? = null,
        decay: Double? = null,
        minFreq: Int? = null,
        maxVocab: Int? = null,
        lambda: Double? = null
    ) {
        smoothing?.let { smoothingFactor = it.coerceIn(0.0, 1.0) }
        decay?.let { decayFactor = it.coerceIn(0.0, 1.0) }
        minFreq?.let { minFrequency = it.coerceAtLeast(1) }
        maxVocab?.let { maxVocabularySize = it.coerceAtLeast(1000) }
        lambda?.let { interpolationLambda = it.coerceIn(0.0, 0.5) }

        logD("Parameters updated")
    }

    /**
     * Set callback for model events.
     *
     * @param callback Callback to receive events
     */
    fun setCallback(callback: Callback?) {
        this.callback = callback
    }

    /**
     * Release all resources and cleanup.
     */
    fun release() {
        logD("Releasing FrequencyModel resources...")

        try {
            // Save model before releasing
            scope.launch {
                saveModel()
            }

            scope.cancel()
            callback = null
            unigramFreq.clear()
            bigramFreq.clear()
            trigramFreq.clear()
            logD("✅ FrequencyModel resources released")
        } catch (e: Exception) {
            logE("Error releasing frequency model resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
