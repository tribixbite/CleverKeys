package tribixbite.keyboard2

import android.util.Log

/**
 * Autocorrection engine with Levenshtein edit distance and keyboard-aware substitution costs
 *
 * Implements typo correction with:
 * - Edit distance calculation (insertions, deletions, substitutions)
 * - Keyboard adjacency awareness (adjacent key errors cost less)
 * - Confidence scoring (0.0-1.0 based on edit distance)
 * - Prefix matching for autocomplete
 *
 * Fix for Bug #310: AutoCorrection system missing (CATASTROPHIC)
 */
class AutoCorrectionEngine {

    companion object {
        private const val TAG = "AutoCorrectionEngine"

        // Scoring constants
        private const val SCORE_EXACT_MATCH = 1000
        private const val SCORE_PREFIX_MATCH = 800
        private const val SCORE_OVERSWIPE = 700
        private const val SCORE_BASE_CORRECTION = 500
        private const val SCORE_PENALTY_PER_EDIT = 100
        private const val SCORE_FUZZY_BASE = 200
        private const val SCORE_FUZZY_PER_CHAR = 10

        // Correction parameters
        private const val MAX_EDIT_DISTANCE = 2  // Maximum typos to correct
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 3  // Don't correct very short words

        // Substitution costs (adjacent keys cost less)
        private const val COST_ADJACENT_SUBSTITUTION = 1
        private const val COST_NON_ADJACENT_SUBSTITUTION = 2
    }

    // QWERTY keyboard adjacency map
    private val adjacentKeys: Map<Char, Set<Char>> = buildAdjacentKeysMap()

    /**
     * Calculate correction score between typed word and dictionary word
     * Higher score = better match
     *
     * @param dictionaryWord Word from dictionary
     * @param typedWord Word typed by user
     * @return Score (0-1000), 0 means no match
     */
    fun calculateCorrectionScore(dictionaryWord: String, typedWord: String): Int {
        if (dictionaryWord.isEmpty() || typedWord.isEmpty()) return 0

        val dictLower = dictionaryWord.lowercase()
        val typedLower = typedWord.lowercase()

        // Exact match - highest score
        if (dictLower == typedLower) {
            return SCORE_EXACT_MATCH
        }

        // Prefix match (autocomplete) - second highest
        if (dictLower.startsWith(typedLower)) {
            return SCORE_PREFIX_MATCH
        }

        // Overswipe (typed more than needed) - third highest
        if (typedLower.startsWith(dictLower)) {
            return SCORE_OVERSWIPE
        }

        // Calculate edit distance with keyboard adjacency
        val distance = calculateEditDistance(dictLower, typedLower)
        if (distance <= MAX_EDIT_DISTANCE) {
            return SCORE_BASE_CORRECTION - (distance * SCORE_PENALTY_PER_EDIT)
        }

        // Fuzzy match based on common characters (last resort)
        val commonChars = countCommonCharacters(dictLower, typedLower)
        val minLength = minOf(dictLower.length, typedLower.length)
        if (commonChars >= minLength - 1) {
            return SCORE_FUZZY_BASE + (commonChars * SCORE_FUZZY_PER_CHAR)
        }

        return 0  // No match
    }

    /**
     * Convert score (0-1000) to confidence (0.0-1.0)
     */
    fun scoreToConfidence(score: Int): Float {
        return (score.toFloat() / SCORE_EXACT_MATCH).coerceIn(0f, 1f)
    }

    /**
     * Calculate Levenshtein edit distance with keyboard-aware costs
     * Adjacent key substitutions cost less than non-adjacent
     */
    private fun calculateEditDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        // Initialize base cases
        for (i in 0..s1.length) {
            dp[i][0] = i
        }
        for (j in 0..s2.length) {
            dp[0][j] = j
        }

        // Fill DP table
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val c1 = s1[i - 1]
                val c2 = s2[j - 1]

                if (c1 == c2) {
                    // Characters match - no cost
                    dp[i][j] = dp[i - 1][j - 1]
                } else {
                    // Determine substitution cost based on key adjacency
                    val substitutionCost = if (isAdjacent(c1, c2)) {
                        COST_ADJACENT_SUBSTITUTION
                    } else {
                        COST_NON_ADJACENT_SUBSTITUTION
                    }

                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,          // Deletion
                        dp[i][j - 1] + 1,          // Insertion
                        dp[i - 1][j - 1] + substitutionCost  // Substitution
                    )
                }
            }
        }

        return dp[s1.length][s2.length]
    }

    /**
     * Check if two keys are adjacent on QWERTY keyboard
     */
    private fun isAdjacent(c1: Char, c2: Char): Boolean {
        return adjacentKeys[c1]?.contains(c2) == true
    }

    /**
     * Count common characters between two strings (order-preserving)
     */
    private fun countCommonCharacters(s1: String, s2: String): Int {
        var count = 0
        var j = 0

        for (i in s1.indices) {
            if (j >= s2.length) break
            if (s1[i] == s2[j]) {
                count++
                j++
            }
        }

        return count
    }

    /**
     * Build QWERTY keyboard adjacency map
     * Each key maps to its immediate neighbors (horizontal and vertical)
     */
    private fun buildAdjacentKeysMap(): Map<Char, Set<Char>> {
        return mapOf(
            // Row 1: Q W E R T Y U I O P
            'q' to setOf('w', 'a', 's'),
            'w' to setOf('q', 'e', 's', 'd', 'a'),
            'e' to setOf('w', 'r', 'd', 'f', 's'),
            'r' to setOf('e', 't', 'f', 'g', 'd'),
            't' to setOf('r', 'y', 'g', 'h', 'f'),
            'y' to setOf('t', 'u', 'h', 'j', 'g'),
            'u' to setOf('y', 'i', 'j', 'k', 'h'),
            'i' to setOf('u', 'o', 'k', 'l', 'j'),
            'o' to setOf('i', 'p', 'l', 'k'),
            'p' to setOf('o', 'l'),

            // Row 2: A S D F G H J K L
            'a' to setOf('q', 'w', 's', 'z'),
            's' to setOf('a', 'd', 'w', 'e', 'x', 'z', 'q'),
            'd' to setOf('s', 'f', 'e', 'r', 'c', 'x'),
            'f' to setOf('d', 'g', 'r', 't', 'v', 'c'),
            'g' to setOf('f', 'h', 't', 'y', 'b', 'v'),
            'h' to setOf('g', 'j', 'y', 'u', 'n', 'b'),
            'j' to setOf('h', 'k', 'u', 'i', 'm', 'n'),
            'k' to setOf('j', 'l', 'i', 'o', 'm'),
            'l' to setOf('k', 'o', 'p'),

            // Row 3: Z X C V B N M
            'z' to setOf('a', 's', 'x'),
            'x' to setOf('z', 'c', 's', 'd'),
            'c' to setOf('x', 'v', 'd', 'f'),
            'v' to setOf('c', 'b', 'f', 'g'),
            'b' to setOf('v', 'n', 'g', 'h'),
            'n' to setOf('b', 'm', 'h', 'j'),
            'm' to setOf('n', 'j', 'k')
        )
    }

    /**
     * Get suggested corrections for a typed word from dictionary
     *
     * @param typedWord Word typed by user
     * @param dictionary List of valid dictionary words
     * @param maxSuggestions Maximum number of suggestions to return
     * @return List of correction suggestions with confidence scores
     */
    fun getSuggestions(
        typedWord: String,
        dictionary: List<String>,
        maxSuggestions: Int = 5
    ): List<CorrectionSuggestion> {
        if (typedWord.length < MIN_WORD_LENGTH_FOR_CORRECTION) {
            return emptyList()
        }

        val suggestions = dictionary
            .mapNotNull { dictWord ->
                val score = calculateCorrectionScore(dictWord, typedWord)
                if (score > 0) {
                    CorrectionSuggestion(
                        word = dictWord,
                        score = score,
                        confidence = scoreToConfidence(score),
                        isExactMatch = (score == SCORE_EXACT_MATCH)
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { it.score }
            .take(maxSuggestions)

        Log.d(TAG, "Autocorrection for '$typedWord': ${suggestions.size} suggestions")
        return suggestions
    }

    /**
     * Data class representing a correction suggestion
     */
    data class CorrectionSuggestion(
        val word: String,
        val score: Int,
        val confidence: Float,
        val isExactMatch: Boolean
    )
}
