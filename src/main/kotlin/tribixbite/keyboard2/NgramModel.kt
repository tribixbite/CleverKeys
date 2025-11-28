package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.pow

/**
 * N-gram language model for improving swipe typing predictions.
 * Uses bigram and trigram probabilities to weight word predictions.
 * This should provide 15-25% accuracy improvement.
 */
class NgramModel {
    // N-gram maps
    private val unigramProbs: MutableMap<String, Float> = mutableMapOf()
    private val bigramProbs: MutableMap<String, Float> = mutableMapOf()
    private val trigramProbs: MutableMap<String, Float> = mutableMapOf()

    // Character frequency for start/end probabilities
    private val startCharProbs: MutableMap<Char, Float> = mutableMapOf()
    private val endCharProbs: MutableMap<Char, Float> = mutableMapOf()

    init {
        initializeDefaultNgrams()
    }

    /**
     * Initialize with common English n-grams
     * These are the most frequent patterns in English text
     */
    private fun initializeDefaultNgrams() {
        // Most common bigrams in English
        bigramProbs.apply {
            put("th", 0.037f)
            put("he", 0.030f)
            put("in", 0.020f)
            put("er", 0.019f)
            put("an", 0.018f)
            put("re", 0.017f)
            put("ed", 0.016f)
            put("on", 0.015f)
            put("es", 0.014f)
            put("st", 0.013f)
            put("en", 0.013f)
            put("at", 0.012f)
            put("to", 0.012f)
            put("nt", 0.011f)
            put("ha", 0.011f)
            put("nd", 0.010f)
            put("ou", 0.010f)
            put("ea", 0.010f)
            put("ng", 0.010f)
            put("as", 0.009f)
            put("or", 0.009f)
            put("ti", 0.009f)
            put("is", 0.009f)
            put("et", 0.008f)
            put("it", 0.008f)
            put("ar", 0.008f)
            put("te", 0.008f)
            put("se", 0.008f)
            put("hi", 0.007f)
            put("of", 0.007f)
        }

        // Most common trigrams
        trigramProbs.apply {
            put("the", 0.030f)
            put("and", 0.016f)
            put("tha", 0.012f)
            put("ent", 0.010f)
            put("ion", 0.009f)
            put("tio", 0.008f)
            put("for", 0.008f)
            put("nde", 0.007f)
            put("has", 0.007f)
            put("nce", 0.006f)
            put("edt", 0.006f)
            put("tis", 0.006f)
            put("oft", 0.006f)
            put("sth", 0.005f)
            put("men", 0.005f)
            put("ing", 0.018f)
            put("her", 0.007f)
            put("hat", 0.006f)
            put("his", 0.005f)
            put("ere", 0.005f)
            put("ter", 0.004f)
            put("was", 0.004f)
            put("you", 0.004f)
            put("ith", 0.004f)
            put("ver", 0.004f)
            put("all", 0.004f)
            put("wit", 0.003f)
        }

        // Common starting characters
        startCharProbs.apply {
            put('t', 0.16f)
            put('a', 0.11f)
            put('s', 0.09f)
            put('h', 0.08f)
            put('w', 0.08f)
            put('i', 0.07f)
            put('o', 0.07f)
            put('b', 0.06f)
            put('m', 0.05f)
            put('f', 0.05f)
            put('c', 0.05f)
            put('l', 0.04f)
            put('d', 0.04f)
            put('p', 0.03f)
            put('n', 0.02f)
        }

        // Common ending characters
        endCharProbs.apply {
            put('e', 0.19f)
            put('s', 0.14f)
            put('t', 0.13f)
            put('d', 0.10f)
            put('n', 0.09f)
            put('r', 0.08f)
            put('y', 0.07f)
            put('f', 0.05f)
            put('l', 0.05f)
            put('o', 0.04f)
            put('w', 0.03f)
            put('a', 0.02f)
            put('k', 0.01f)
        }
    }

    /**
     * Load n-gram data from a file (future enhancement)
     */
    fun loadNgramData(context: Context, filename: String) {
        try {
            BufferedReader(InputStreamReader(context.assets.open(filename))).use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2) {
                        val ngram = parts[0].lowercase()
                        val prob = parts[1].toFloat()

                        when (ngram.length) {
                            1 -> unigramProbs[ngram] = prob
                            2 -> bigramProbs[ngram] = prob
                            3 -> trigramProbs[ngram] = prob
                        }
                    }
                }
            }

            Log.d(TAG, "Loaded n-grams: ${unigramProbs.size} unigrams, " +
                    "${bigramProbs.size} bigrams, ${trigramProbs.size} trigrams")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load n-gram data: ${e.message}")
        }
    }

    /**
     * Get probability of a bigram (two-character sequence)
     */
    fun getBigramProbability(first: Char, second: Char): Float {
        val bigram = "$first$second"
        return bigramProbs.getOrDefault(bigram.lowercase(), SMOOTHING_FACTOR)
    }

    /**
     * Get probability of a trigram (three-character sequence)
     */
    fun getTrigramProbability(first: Char, second: Char, third: Char): Float {
        val trigram = "$first$second$third"
        return trigramProbs.getOrDefault(trigram.lowercase(), SMOOTHING_FACTOR)
    }

    /**
     * Get probability of a character starting a word
     */
    fun getStartProbability(c: Char): Float {
        return startCharProbs.getOrDefault(c.lowercaseChar(), SMOOTHING_FACTOR)
    }

    /**
     * Get probability of a character ending a word
     */
    fun getEndProbability(c: Char): Float {
        return endCharProbs.getOrDefault(c.lowercaseChar(), SMOOTHING_FACTOR)
    }

    /**
     * Calculate language model probability for a word
     * Combines unigram, bigram, and trigram probabilities
     */
    fun getWordProbability(word: String?): Float {
        if (word.isNullOrEmpty()) {
            return 0.0f
        }

        val lowerWord = word.lowercase()
        var probability = 1.0f

        // Start character probability
        probability *= getStartProbability(lowerWord[0])

        // Calculate n-gram probabilities
        for (i in lowerWord.indices) {
            // Unigram (single character frequency)
            // Skip for now as we don't have unigram data

            // Bigram
            if (i > 0) {
                val bigramProb = getBigramProbability(lowerWord[i - 1], lowerWord[i])
                probability *= bigramProb.pow(BIGRAM_WEIGHT)
            }

            // Trigram
            if (i > 1) {
                val trigramProb = getTrigramProbability(
                    lowerWord[i - 2], lowerWord[i - 1], lowerWord[i]
                )
                probability *= trigramProb.pow(TRIGRAM_WEIGHT)
            }
        }

        // End character probability
        probability *= getEndProbability(lowerWord[lowerWord.length - 1])

        // Apply word length normalization (longer words naturally have lower probability)
        probability = probability.pow(1.0f / lowerWord.length)

        return probability
    }

    /**
     * Score a word based on how well its n-grams match the language model
     * Higher score = more likely to be a real word
     */
    fun scoreWord(word: String?): Float {
        if (word == null || word.length < 2) {
            return 0.0f
        }

        val lowerWord = word.lowercase()
        var score = 0.0f
        var ngramCount = 0

        // Score bigrams
        for (i in 0 until lowerWord.length - 1) {
            val bigram = lowerWord.substring(i, i + 2)
            if (bigramProbs.containsKey(bigram)) {
                score += bigramProbs[bigram]!! * 100 // Scale up for visibility
                ngramCount++
            }
        }

        // Score trigrams
        for (i in 0 until lowerWord.length - 2) {
            val trigram = lowerWord.substring(i, i + 3)
            if (trigramProbs.containsKey(trigram)) {
                score += trigramProbs[trigram]!! * 200 // Higher weight for trigrams
                ngramCount++
            }
        }

        // Normalize by number of n-grams
        if (ngramCount > 0) {
            score /= ngramCount
        }

        // Bonus for good start/end characters
        score += getStartProbability(lowerWord[0]) * 50
        score += getEndProbability(lowerWord[lowerWord.length - 1]) * 50

        return score
    }

    /**
     * Check if a sequence of characters forms valid n-grams
     * Used for quick filtering of impossible words
     */
    fun hasValidNgrams(word: String?): Boolean {
        if (word == null || word.length < 2) {
            return false
        }

        val lowerWord = word.lowercase()
        var validCount = 0
        var totalCount = 0

        // Check bigrams
        for (i in 0 until lowerWord.length - 1) {
            val bigram = lowerWord.substring(i, i + 2)
            totalCount++
            if (bigramProbs.getOrDefault(bigram, 0f) > SMOOTHING_FACTOR) {
                validCount++
            }
        }

        // At least 30% of bigrams should be valid
        return validCount >= totalCount * 0.3
    }

    companion object {
        private const val TAG = "NgramModel"

        // Smoothing factor for unseen n-grams
        private const val SMOOTHING_FACTOR = 0.001f

        // Weight factors for different n-grams
        private const val UNIGRAM_WEIGHT = 0.1f
        private const val BIGRAM_WEIGHT = 0.3f
        private const val TRIGRAM_WEIGHT = 0.6f
    }
}
