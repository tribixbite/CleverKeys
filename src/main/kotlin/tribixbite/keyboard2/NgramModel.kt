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
 *
 * Features:
 * - Bigram (2-char) and trigram (3-char) probabilities
 * - Start and end character probabilities
 * - Word probability calculation with length normalization
 * - Word scoring based on n-gram matches
 * - Validation of n-gram sequences
 * - Optional file loading for custom n-gram data
 *
 * Ported from Java to Kotlin with improvements.
 */
class NgramModel {

    companion object {
        private const val TAG = "NgramModel"

        // Smoothing factor for unseen n-grams
        private const val SMOOTHING_FACTOR = 0.001f

        // Weight factors for different n-grams
        private const val UNIGRAM_WEIGHT = 0.1f
        private const val BIGRAM_WEIGHT = 0.3f
        private const val TRIGRAM_WEIGHT = 0.6f
    }

    // N-gram maps
    private val unigramProbs = mutableMapOf<String, Float>()
    private val bigramProbs = mutableMapOf<String, Float>()
    private val trigramProbs = mutableMapOf<String, Float>()

    // Character frequency for start/end probabilities
    private val startCharProbs = mutableMapOf<Char, Float>()
    private val endCharProbs = mutableMapOf<Char, Float>()

    init {
        initializeDefaultNgrams()
    }

    /**
     * Initialize with common English n-grams
     * These are the most frequent patterns in English text
     */
    private fun initializeDefaultNgrams() {
        // Most common bigrams in English
        bigramProbs["th"] = 0.037f
        bigramProbs["he"] = 0.030f
        bigramProbs["in"] = 0.020f
        bigramProbs["er"] = 0.019f
        bigramProbs["an"] = 0.018f
        bigramProbs["re"] = 0.017f
        bigramProbs["ed"] = 0.016f
        bigramProbs["on"] = 0.015f
        bigramProbs["es"] = 0.014f
        bigramProbs["st"] = 0.013f
        bigramProbs["en"] = 0.013f
        bigramProbs["at"] = 0.012f
        bigramProbs["to"] = 0.012f
        bigramProbs["nt"] = 0.011f
        bigramProbs["ha"] = 0.011f
        bigramProbs["nd"] = 0.010f
        bigramProbs["ou"] = 0.010f
        bigramProbs["ea"] = 0.010f
        bigramProbs["ng"] = 0.010f
        bigramProbs["as"] = 0.009f
        bigramProbs["or"] = 0.009f
        bigramProbs["ti"] = 0.009f
        bigramProbs["is"] = 0.009f
        bigramProbs["et"] = 0.008f
        bigramProbs["it"] = 0.008f
        bigramProbs["ar"] = 0.008f
        bigramProbs["te"] = 0.008f
        bigramProbs["se"] = 0.008f
        bigramProbs["hi"] = 0.007f
        bigramProbs["of"] = 0.007f

        // Most common trigrams
        trigramProbs["the"] = 0.030f
        trigramProbs["and"] = 0.016f
        trigramProbs["tha"] = 0.012f
        trigramProbs["ent"] = 0.010f
        trigramProbs["ion"] = 0.009f
        trigramProbs["tio"] = 0.008f
        trigramProbs["for"] = 0.008f
        trigramProbs["nde"] = 0.007f
        trigramProbs["has"] = 0.007f
        trigramProbs["nce"] = 0.006f
        trigramProbs["edt"] = 0.006f
        trigramProbs["tis"] = 0.006f
        trigramProbs["oft"] = 0.006f
        trigramProbs["sth"] = 0.005f
        trigramProbs["men"] = 0.005f
        trigramProbs["ing"] = 0.018f
        trigramProbs["her"] = 0.007f
        trigramProbs["hat"] = 0.006f
        trigramProbs["his"] = 0.005f
        trigramProbs["ere"] = 0.005f
        trigramProbs["ter"] = 0.004f
        trigramProbs["was"] = 0.004f
        trigramProbs["you"] = 0.004f
        trigramProbs["ith"] = 0.004f
        trigramProbs["ver"] = 0.004f
        trigramProbs["all"] = 0.004f
        trigramProbs["wit"] = 0.003f

        // Common starting characters
        startCharProbs['t'] = 0.16f
        startCharProbs['a'] = 0.11f
        startCharProbs['s'] = 0.09f
        startCharProbs['h'] = 0.08f
        startCharProbs['w'] = 0.08f
        startCharProbs['i'] = 0.07f
        startCharProbs['o'] = 0.07f
        startCharProbs['b'] = 0.06f
        startCharProbs['m'] = 0.05f
        startCharProbs['f'] = 0.05f
        startCharProbs['c'] = 0.05f
        startCharProbs['l'] = 0.04f
        startCharProbs['d'] = 0.04f
        startCharProbs['p'] = 0.03f
        startCharProbs['n'] = 0.02f

        // Common ending characters
        endCharProbs['e'] = 0.19f
        endCharProbs['s'] = 0.14f
        endCharProbs['t'] = 0.13f
        endCharProbs['d'] = 0.10f
        endCharProbs['n'] = 0.09f
        endCharProbs['r'] = 0.08f
        endCharProbs['y'] = 0.07f
        endCharProbs['f'] = 0.05f
        endCharProbs['l'] = 0.05f
        endCharProbs['o'] = 0.04f
        endCharProbs['w'] = 0.03f
        endCharProbs['a'] = 0.02f
        endCharProbs['k'] = 0.01f
    }

    /**
     * Load n-gram data from a file (future enhancement)
     * File format: ngram\tprobability (tab-separated)
     */
    fun loadNgramData(context: Context, filename: String) {
        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val parts = line!!.split("\t")
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
     *
     * Formula:
     * P(word) = P(start) * Π(P(bigram)^w_bi) * Π(P(trigram)^w_tri) * P(end)
     * Then normalized by word length: P(word)^(1/length)
     *
     * @param word Word to calculate probability for
     * @return Normalized probability [0, 1]
     */
    fun getWordProbability(word: String?): Float {
        if (word.isNullOrEmpty()) return 0.0f

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
     *
     * This is a simpler scoring than getWordProbability() - it just counts
     * how many known n-grams appear in the word and weights them.
     *
     * @param word Word to score
     * @return Score value (higher = better match to language model)
     */
    fun scoreWord(word: String?): Float {
        if (word == null || word.length < 2) return 0.0f

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
     *
     * A word is considered valid if at least 30% of its bigrams
     * are known in the language model.
     *
     * @param word Word to validate
     * @return true if word has enough valid n-grams
     */
    fun hasValidNgrams(word: String?): Boolean {
        if (word == null || word.length < 2) return false

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

    /**
     * Get statistics about the n-gram model
     */
    fun getStats(): String {
        return buildString {
            append("NgramModel Statistics:\n")
            append("- Unigrams: ${unigramProbs.size}\n")
            append("- Bigrams: ${bigramProbs.size}\n")
            append("- Trigrams: ${trigramProbs.size}\n")
            append("- Start characters: ${startCharProbs.size}\n")
            append("- End characters: ${endCharProbs.size}\n")
            append("- Smoothing factor: $SMOOTHING_FACTOR\n")
            append("- Weights: uni=${UNIGRAM_WEIGHT}, bi=${BIGRAM_WEIGHT}, tri=${TRIGRAM_WEIGHT}\n")
        }
    }
}
