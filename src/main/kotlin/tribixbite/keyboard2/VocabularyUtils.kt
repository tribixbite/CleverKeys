package tribixbite.keyboard2

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

object VocabularyUtils {

    /**
     * Calculate combined score from neural network confidence and dictionary frequency
     */
    @JvmStatic
    fun calculateCombinedScore(
        confidence: Float,
        frequency: Float,
        boost: Float,
        confidenceWeight: Float,
        frequencyWeight: Float
    ): Float {
        // Use frequency directly - already normalized to [0,1] by loading code
        val freqScore = frequency

        // Weighted combination with boost factor
        return (confidenceWeight * confidence + frequencyWeight * freqScore) * boost
    }

    /**
     * Fuzzy match two words using autocorrect criteria.
     */
    @JvmStatic
    fun fuzzyMatch(
        word1: String,
        word2: String,
        charMatchThreshold: Float,
        maxLengthDiff: Int,
        prefixLength: Int,
        minWordLength: Int
    ): Boolean {
        // Check minimum word length
        if (word1.length < minWordLength || word2.length < minWordLength) return false

        // Check length difference
        val lengthDiff = abs(word1.length - word2.length)
        if (lengthDiff > maxLengthDiff) return false

        // Check prefix match
        val actualPrefixLen = min(prefixLength, min(word1.length, word2.length))
        if (actualPrefixLen > 0 && word1.substring(0, actualPrefixLen) != word2.substring(0, actualPrefixLen)) {
            return false
        }

        // Count matching characters at the same position
        var matches = 0
        val minLength = min(word1.length, word2.length)

        for (i in 0 until minLength) {
            if (word1[i] == word2[i]) {
                matches++
            }
        }

        // Calculate match ratio using shorter word length as denominator
        val matchRatio = matches.toFloat() / minLength
        return matchRatio >= charMatchThreshold
    }

    /**
     * Calculate Levenshtein distance (edit distance) between two words.
     */
    @JvmStatic
    fun calculateLevenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        if (s1 == s2) return 0
        if (len1 == 0) return len2
        if (len2 == 0) return len1

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Calculate match quality between two words using configurable algorithm.
     */
    @JvmStatic
    fun calculateMatchQuality(
        dictWord: String,
        beamWord: String,
        useEditDistance: Boolean
    ): Float {
        if (useEditDistance) {
            val distance = calculateLevenshteinDistance(dictWord, beamWord)
            val maxDistance = max(dictWord.length, beamWord.length)
            return 1.0f - (distance.toFloat() / maxDistance)
        } else {
            var matches = 0
            val minLen = min(dictWord.length, beamWord.length)

            for (i in 0 until minLen) {
                if (dictWord[i] == beamWord[i]) {
                    matches++
                }
            }

            return matches.toFloat() / dictWord.length
        }
    }
    
    @JvmStatic
    fun calculateMatchQuality(dictWord: String, beamWord: String): Float {
        return calculateMatchQuality(dictWord, beamWord, true)
    }
}
