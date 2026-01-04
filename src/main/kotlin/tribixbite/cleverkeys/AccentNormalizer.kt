package tribixbite.cleverkeys

import java.text.Normalizer

/**
 * Utility for normalizing accented characters for dictionary lookup.
 *
 * The neural swipe model has a 26-letter vocabulary (a-z only). It cannot
 * distinguish between "café" and "cafe" - both produce identical swipe
 * trajectories. This normalizer strips diacritical marks to enable matching
 * NN output to canonical (accented) dictionary forms.
 *
 * ## Usage
 * ```kotlin
 * val normalizer = AccentNormalizer
 * normalizer.normalize("café")     // Returns "cafe"
 * normalizer.normalize("naïve")    // Returns "naive"
 * normalizer.normalize("señor")    // Returns "senor"
 * normalizer.normalize("München")  // Returns "munchen"
 * ```
 *
 * ## Algorithm
 * 1. Convert to lowercase
 * 2. Apply Unicode NFD normalization (decomposes characters)
 * 3. Remove combining diacritical marks (U+0300-U+036F)
 * 4. Handle special characters (ß → ss, ø → o, etc.)
 *
 * @since v1.2.0 - Multilanguage support
 */
object AccentNormalizer {

    // Regex to match combining diacritical marks (accents, umlauts, etc.)
    // Unicode range U+0300 to U+036F covers most Latin diacritics
    private val DIACRITICS_REGEX = Regex("[\\u0300-\\u036F]")

    // Special character replacements that aren't handled by NFD
    private val SPECIAL_REPLACEMENTS = mapOf(
        'ß' to "ss",    // German sharp s
        'ø' to "o",     // Nordic slashed o
        'Ø' to "o",
        'ð' to "d",     // Icelandic eth
        'Ð' to "d",
        'þ' to "th",    // Icelandic thorn
        'Þ' to "th",
        'æ' to "ae",    // Ligature ae
        'Æ' to "ae",
        'œ' to "oe",    // Ligature oe
        'Œ' to "oe",
        'ł' to "l",     // Polish slashed l
        'Ł' to "l",
        'đ' to "d",     // Croatian/Vietnamese d with stroke
        'Đ' to "d",
        'ı' to "i",     // Turkish dotless i
        'ħ' to "h",     // Maltese h with stroke
        'Ħ' to "h"
    )

    /**
     * Normalize a word by removing diacritical marks.
     *
     * @param word The word to normalize (may contain accents)
     * @return Normalized form with accents stripped, lowercase
     */
    fun normalize(word: String): String {
        if (word.isEmpty()) return word

        // Step 1: Handle special characters first
        var result = word.lowercase()
        for ((char, replacement) in SPECIAL_REPLACEMENTS) {
            result = result.replace(char.toString(), replacement)
        }

        // Step 2: Apply NFD normalization (decomposes accented characters)
        // e.g., "é" (U+00E9) becomes "e" (U+0065) + "́" (U+0301)
        val normalized = Normalizer.normalize(result, Normalizer.Form.NFD)

        // Step 3: Remove combining diacritical marks
        return DIACRITICS_REGEX.replace(normalized, "")
    }

    /**
     * Check if a word contains any accented characters.
     *
     * @param word The word to check
     * @return true if word differs from its normalized form
     */
    fun hasAccents(word: String): Boolean {
        return word != normalize(word)
    }

    /**
     * Normalize a word while preserving original case pattern.
     *
     * Useful for touch typing where user's capitalization should be preserved.
     *
     * @param word The word to normalize
     * @return Normalized form preserving original case pattern
     */
    fun normalizePreservingCase(word: String): String {
        if (word.isEmpty()) return word

        val normalized = normalize(word)

        // Detect case pattern
        val isAllUpper = word.all { !it.isLetter() || it.isUpperCase() }
        val isFirstUpper = word.firstOrNull()?.isUpperCase() == true

        return when {
            isAllUpper -> normalized.uppercase()
            isFirstUpper -> normalized.replaceFirstChar { it.uppercase() }
            else -> normalized
        }
    }

    /**
     * Build an accent mapping from a list of canonical words.
     *
     * Groups words by their normalized form. Words that normalize to the same
     * key can be looked up together (e.g., "schon" and "schön" both map to "schon").
     *
     * @param words List of canonical (accented) words with frequencies
     * @return Map of normalized form to list of (canonical, frequency) pairs
     */
    fun buildAccentMap(words: List<Pair<String, Int>>): Map<String, List<Pair<String, Int>>> {
        val accentMap = mutableMapOf<String, MutableList<Pair<String, Int>>>()

        for ((canonical, frequency) in words) {
            val normalized = normalize(canonical)
            accentMap.getOrPut(normalized) { mutableListOf() }
                .add(Pair(canonical, frequency))
        }

        // Sort each list by frequency (highest first) for quick best-match lookup
        for ((_, forms) in accentMap) {
            forms.sortByDescending { it.second }
        }

        return accentMap
    }

    /**
     * Get the best canonical form for a normalized word.
     *
     * When multiple accented forms map to the same normalized key,
     * returns the most frequent one.
     *
     * @param normalized The normalized (accent-stripped) word
     * @param accentMap The accent mapping from buildAccentMap()
     * @return Best canonical form, or null if not found
     */
    fun getBestCanonical(normalized: String, accentMap: Map<String, List<Pair<String, Int>>>): String? {
        val forms = accentMap[normalized] ?: return null
        return forms.firstOrNull()?.first
    }

    /**
     * Get all canonical forms for a normalized word.
     *
     * @param normalized The normalized (accent-stripped) word
     * @param accentMap The accent mapping from buildAccentMap()
     * @return List of canonical forms sorted by frequency, empty if not found
     */
    fun getAllCanonicals(normalized: String, accentMap: Map<String, List<Pair<String, Int>>>): List<String> {
        val forms = accentMap[normalized] ?: return emptyList()
        return forms.map { it.first }
    }
}
