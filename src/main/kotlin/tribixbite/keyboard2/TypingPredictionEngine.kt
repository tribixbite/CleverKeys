package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Tap typing prediction engine with n-gram models and frequency-based predictions
 * Provides next-word predictions and autocomplete for regular tap typing
 * Fix for Bug #359 (CATASTROPHIC): NO tap typing predictions
 */
class TypingPredictionEngine(private val context: Context) {

    companion object {
        private const val TAG = "TypingPredictionEngine"
        private const val MAX_PREDICTIONS = 5
        private const val MIN_WORD_FREQUENCY = 10
        private const val MIN_PREFIX_LENGTH = 1
    }

    // Word frequency map (word -> frequency count)
    private val wordFrequencies = mutableMapOf<String, Int>()

    // Bigram model (word1 -> map of word2 -> count)
    private val bigramModel = mutableMapOf<String, MutableMap<String, Int>>()

    // Trigram model ((word1, word2) -> map of word3 -> count)
    private val trigramModel = mutableMapOf<Pair<String, String>, MutableMap<String, Int>>()

    // Prefix trie for fast autocomplete
    private val prefixTrie = TrieNode()

    private var isInitialized = false

    /**
     * Initialize prediction engine by loading dictionary and building models
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (isInitialized) return@withContext true

            Log.d(TAG, "Initializing tap typing prediction engine...")

            // Load dictionary and build models
            loadDictionary()
            buildNGramModels()
            buildPrefixTrie()

            isInitialized = true
            Log.d(TAG, "Initialization complete: ${wordFrequencies.size} words, " +
                      "${bigramModel.size} bigrams, ${trigramModel.size} trigrams")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            false
        }
    }

    /**
     * Get next-word predictions based on context
     */
    fun predictNextWords(context: String, maxResults: Int = MAX_PREDICTIONS): List<PredictionResult> {
        if (!isInitialized) return emptyList()

        val words = context.trim().lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        return when {
            words.size >= 2 -> {
                // Use trigram model (most specific)
                val w1 = words[words.size - 2]
                val w2 = words[words.size - 1]
                predictFromTrigram(w1, w2, maxResults)
                    .ifEmpty { predictFromBigram(w2, maxResults) }
                    .ifEmpty { predictFromFrequency(maxResults) }
            }
            words.size == 1 -> {
                // Use bigram model
                val w1 = words[0]
                predictFromBigram(w1, maxResults)
                    .ifEmpty { predictFromFrequency(maxResults) }
            }
            else -> {
                // Use frequency-based predictions
                predictFromFrequency(maxResults)
            }
        }
    }

    /**
     * Get autocomplete predictions for partial word
     */
    fun autocompleteWord(prefix: String, maxResults: Int = MAX_PREDICTIONS): List<PredictionResult> {
        if (!isInitialized || prefix.length < MIN_PREFIX_LENGTH) return emptyList()

        val lowerPrefix = prefix.lowercase(Locale.getDefault())
        val completions = findCompletions(lowerPrefix)

        return completions
            .sortedByDescending { wordFrequencies[it] ?: 0 }
            .take(maxResults)
            .map { word ->
                val frequency = wordFrequencies[word] ?: 0
                val confidence = calculateConfidence(frequency, completions.size)
                PredictionResult(word, confidence, PredictionSource.AUTOCOMPLETE)
            }
    }

    /**
     * Get predictions combining context and partial input
     */
    fun predictWithPrefix(
        contextWords: String,
        currentPrefix: String,
        maxResults: Int = MAX_PREDICTIONS
    ): List<PredictionResult> {
        if (!isInitialized) return emptyList()

        // If prefix is empty, return next-word predictions
        if (currentPrefix.isEmpty()) {
            return predictNextWords(contextWords, maxResults)
        }

        // Get autocomplete for prefix
        val autocompletions = autocompleteWord(currentPrefix, maxResults * 2)

        // If we have context, re-rank based on n-gram models
        if (contextWords.isNotEmpty()) {
            val words = contextWords.trim().lowercase(Locale.getDefault())
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }

            return when {
                words.size >= 2 -> {
                    val w1 = words[words.size - 2]
                    val w2 = words[words.size - 1]
                    rerankWithTrigram(autocompletions, w1, w2)
                }
                words.size == 1 -> {
                    val w1 = words[0]
                    rerankWithBigram(autocompletions, w1)
                }
                else -> autocompletions
            }.take(maxResults)
        }

        return autocompletions.take(maxResults)
    }

    /**
     * Prediction result with confidence score
     */
    data class PredictionResult(
        val word: String,
        val confidence: Float,
        val source: PredictionSource
    )

    enum class PredictionSource {
        TRIGRAM,    // Based on previous 2 words
        BIGRAM,     // Based on previous 1 word
        FREQUENCY,  // Based on word frequency only
        AUTOCOMPLETE // Based on prefix matching
    }

    // Private helper methods

    private fun loadDictionary() {
        try {
            // Load from assets/dictionaries/en.txt
            val inputStream = context.assets.open("dictionaries/en.txt")
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.isNotEmpty()) {
                        val word = parts[0].lowercase(Locale.getDefault())
                        val frequency = parts.getOrNull(1)?.toIntOrNull() ?: 100

                        if (word.length >= 2 && word.all { it.isLetter() }) {
                            wordFrequencies[word] = frequency
                        }
                    }
                }
            }
            Log.d(TAG, "Loaded ${wordFrequencies.size} words from dictionary")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary", e)
            // Load minimal fallback dictionary
            loadFallbackDictionary()
        }
    }

    private fun loadFallbackDictionary() {
        // Common English words with estimated frequencies
        val commonWords = listOf(
            "the" to 1000, "be" to 900, "to" to 850, "of" to 800, "and" to 750,
            "in" to 700, "that" to 650, "have" to 600, "it" to 550, "for" to 500,
            "not" to 450, "on" to 400, "with" to 380, "he" to 360, "as" to 340,
            "you" to 320, "do" to 300, "at" to 280, "this" to 260, "but" to 240,
            "his" to 220, "by" to 200, "from" to 190, "they" to 180, "we" to 170,
            "say" to 160, "her" to 150, "she" to 140, "or" to 130, "an" to 120,
            "will" to 110, "my" to 100, "one" to 95, "all" to 90, "would" to 85,
            "there" to 80, "their" to 75, "what" to 70, "so" to 65, "up" to 60,
            "out" to 55, "if" to 50, "about" to 48, "who" to 46, "get" to 44,
            "which" to 42, "go" to 40, "me" to 38, "when" to 36, "make" to 34,
            "can" to 32, "like" to 30, "time" to 28, "no" to 26, "just" to 24,
            "him" to 22, "know" to 20, "take" to 19, "people" to 18, "into" to 17,
            "year" to 16, "your" to 15, "good" to 14, "some" to 13, "could" to 12,
            "them" to 11, "see" to 10, "other" to 9, "than" to 8, "then" to 7,
            "now" to 6, "look" to 5, "only" to 4, "come" to 3, "its" to 2, "over" to 1
        )

        wordFrequencies.putAll(commonWords)
        Log.d(TAG, "Loaded ${wordFrequencies.size} fallback words")
    }

    private fun buildNGramModels() {
        // In a production system, this would load pre-trained n-gram models
        // For now, we build simple models from common patterns

        // Common bigrams
        val commonBigrams = listOf(
            "of" to "the", "in" to "the", "to" to "be", "on" to "the",
            "for" to "the", "at" to "the", "by" to "the", "with" to "a",
            "is" to "a", "was" to "a", "are" to "the", "have" to "been",
            "has" to "been", "had" to "been", "will" to "be", "would" to "be",
            "can" to "be", "could" to "be", "should" to "be", "may" to "be",
            "do" to "not", "does" to "not", "did" to "not", "is" to "not",
            "was" to "not", "are" to "not", "were" to "not", "have" to "to",
            "want" to "to", "need" to "to", "going" to "to", "have" to "a"
        )

        commonBigrams.forEach { (w1, w2) ->
            bigramModel.getOrPut(w1) { mutableMapOf() }
                .merge(w2, 10) { old, new -> old + new }
        }

        // Common trigrams (word1, word2) -> word3
        val commonTrigrams = listOf(
            ("one" to "of") to "the",
            ("a" to "lot") to "of",
            ("in" to "order") to "to",
            ("as" to "well") to "as",
            ("at" to "the") to "same",
            ("on" to "the") to "other",
            ("for" to "the") to "first"
        )

        commonTrigrams.forEach { (pair, w3) ->
            trigramModel.getOrPut(pair) { mutableMapOf() }
                .merge(w3, 10) { old, new -> old + new }
        }

        Log.d(TAG, "Built n-gram models: ${bigramModel.size} bigrams, ${trigramModel.size} trigrams")
    }

    private fun buildPrefixTrie() {
        wordFrequencies.forEach { (word, _) ->
            var node = prefixTrie
            word.forEach { char ->
                node = node.children.getOrPut(char) { TrieNode() }
            }
            node.isEndOfWord = true
            node.word = word
        }
    }

    private fun predictFromTrigram(w1: String, w2: String, maxResults: Int): List<PredictionResult> {
        val predictions = trigramModel[Pair(w1, w2)]
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(maxResults)
            ?.map { (word, count) ->
                PredictionResult(
                    word = word,
                    confidence = calculateConfidence(count, 100),
                    source = PredictionSource.TRIGRAM
                )
            } ?: emptyList()

        return predictions
    }

    private fun predictFromBigram(w1: String, maxResults: Int): List<PredictionResult> {
        val predictions = bigramModel[w1]
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(maxResults)
            ?.map { (word, count) ->
                PredictionResult(
                    word = word,
                    confidence = calculateConfidence(count, 50),
                    source = PredictionSource.BIGRAM
                )
            } ?: emptyList()

        return predictions
    }

    private fun predictFromFrequency(maxResults: Int): List<PredictionResult> {
        return wordFrequencies.entries
            .filter { it.value >= MIN_WORD_FREQUENCY }
            .sortedByDescending { it.value }
            .take(maxResults)
            .map { (word, frequency) ->
                PredictionResult(
                    word = word,
                    confidence = calculateConfidence(frequency, 1000),
                    source = PredictionSource.FREQUENCY
                )
            }
    }

    private fun findCompletions(prefix: String): List<String> {
        val completions = mutableListOf<String>()
        var node = prefixTrie

        // Navigate to prefix
        for (char in prefix) {
            node = node.children[char] ?: return emptyList()
        }

        // Collect all words with this prefix
        collectWords(node, completions)
        return completions
    }

    private fun collectWords(node: TrieNode, results: MutableList<String>) {
        if (node.isEndOfWord && node.word != null) {
            results.add(node.word!!)
        }
        node.children.values.forEach { child ->
            collectWords(child, results)
        }
    }

    private fun rerankWithBigram(
        predictions: List<PredictionResult>,
        previousWord: String
    ): List<PredictionResult> {
        val bigramCounts = bigramModel[previousWord] ?: return predictions

        return predictions.map { pred ->
            val bigramBoost = bigramCounts[pred.word]?.let { it / 10.0f } ?: 0f
            pred.copy(confidence = pred.confidence + bigramBoost)
        }.sortedByDescending { it.confidence }
    }

    private fun rerankWithTrigram(
        predictions: List<PredictionResult>,
        word1: String,
        word2: String
    ): List<PredictionResult> {
        val trigramCounts = trigramModel[Pair(word1, word2)] ?: return predictions

        return predictions.map { pred ->
            val trigramBoost = trigramCounts[pred.word]?.let { it / 5.0f } ?: 0f
            pred.copy(confidence = pred.confidence + trigramBoost)
        }.sortedByDescending { it.confidence }
    }

    private fun calculateConfidence(count: Int, maxCount: Int): Float {
        return (count.toFloat() / maxCount).coerceIn(0f, 1f)
    }

    /**
     * Trie node for prefix-based autocomplete
     */
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        var isEndOfWord = false
        var word: String? = null
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        wordFrequencies.clear()
        bigramModel.clear()
        trigramModel.clear()
        isInitialized = false
    }
}
