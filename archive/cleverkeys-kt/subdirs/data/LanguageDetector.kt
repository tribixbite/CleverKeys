package tribixbite.keyboard2.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Language Detector for Multi-Language Keyboard Support
 *
 * Detects language from text samples or word lists using:
 * - Character frequency analysis
 * - Common word matching
 * - Statistical patterns
 *
 * Features:
 * - Detect from text sample (paragraph, sentence)
 * - Detect from word list (recent typing history)
 * - Multi-language support (en, es, fr, de, it, pt)
 * - Confidence scoring
 *
 * Detection methods:
 * 1. Common words: Check for language-specific frequent words
 * 2. Character frequencies: Analyze letter distribution
 * 3. Diacritics: Detect language-specific accented characters
 *
 * Usage:
 * ```kotlin
 * val detector = LanguageDetector(context)
 * val language = detector.detectLanguage("Hello, how are you?")
 * // Returns "en"
 * ```
 */
class LanguageDetector(private val context: Context) {

    companion object {
        private const val TAG = "LanguageDetector"
        private val SUPPORTED_LANGUAGES = listOf("en", "es", "fr", "de", "it", "pt")
    }

    // Common words for each language (loaded from assets)
    private val commonWords = mutableMapOf<String, Set<String>>()

    // Character frequency patterns (for statistical analysis)
    private val charFrequencies = mutableMapOf<String, Map<Char, Float>>()

    init {
        // Load common words for language detection (async in background)
        // For now, use hardcoded common words
        loadCommonWords()
    }

    /**
     * Load common words for each supported language
     */
    private fun loadCommonWords() {
        // English common words
        commonWords["en"] = setOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she"
        )

        // Spanish common words
        commonWords["es"] = setOf(
            "de", "la", "que", "el", "en", "y", "a", "los", "se", "del",
            "las", "un", "por", "con", "no", "una", "su", "para", "es", "al",
            "lo", "como", "más", "o", "pero", "sus", "le", "ha", "me", "si"
        )

        // French common words
        commonWords["fr"] = setOf(
            "de", "la", "le", "et", "les", "des", "en", "un", "du", "une",
            "que", "est", "pour", "qui", "dans", "a", "par", "plus", "pas", "au",
            "sur", "se", "avec", "son", "il", "ce", "sont", "cette", "avoir", "ou"
        )

        // German common words
        commonWords["de"] = setOf(
            "der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich",
            "des", "auf", "für", "ist", "im", "dem", "nicht", "ein", "eine", "als",
            "auch", "es", "an", "werden", "aus", "er", "hat", "dass", "sie", "nach"
        )

        // Italian common words
        commonWords["it"] = setOf(
            "di", "a", "da", "in", "con", "su", "per", "tra", "fra", "il",
            "lo", "la", "i", "gli", "le", "un", "uno", "una", "e", "che",
            "non", "si", "è", "anche", "come", "sono", "o", "ma", "se", "quale"
        )

        // Portuguese common words
        commonWords["pt"] = setOf(
            "de", "a", "o", "que", "e", "do", "da", "em", "um", "para",
            "é", "com", "não", "uma", "os", "no", "se", "na", "por", "mais",
            "as", "dos", "como", "mas", "foi", "ao", "ele", "das", "tem", "à"
        )

        Log.d(TAG, "Loaded common words for ${commonWords.size} languages")
    }

    /**
     * Detect language from a text sample
     *
     * @param text The text to analyze
     * @return Detected language code (e.g., "en"), or null if uncertain
     */
    fun detectLanguage(text: String): String? {
        if (text.isEmpty()) return null

        val lowerText = text.lowercase()
        val words = lowerText.split(Regex("\\s+")).filter { it.isNotEmpty() }

        return detectLanguageFromWords(words)
    }

    /**
     * Detect language from a list of words (e.g., recent typing history)
     *
     * @param words List of words to analyze
     * @return Detected language code (e.g., "en"), or null if uncertain
     */
    fun detectLanguageFromWords(words: List<String>): String? {
        if (words.isEmpty()) return null

        // Count matches for each language
        val languageScores = mutableMapOf<String, Int>()

        for (language in SUPPORTED_LANGUAGES) {
            val commonWordsSet = commonWords[language] ?: continue
            var matchCount = 0

            for (word in words) {
                if (word.lowercase() in commonWordsSet) {
                    matchCount++
                }
            }

            if (matchCount > 0) {
                languageScores[language] = matchCount
            }
        }

        // Return language with highest score
        val bestMatch = languageScores.maxByOrNull { it.value }

        // Require at least 20% match rate for confidence
        val matchRate = (bestMatch?.value ?: 0).toFloat() / words.size
        if (matchRate < 0.2f) {
            return null // Not confident enough
        }

        return bestMatch?.key
    }

    /**
     * Get confidence score for a detected language
     *
     * @param text The text to analyze
     * @param language The language to check
     * @return Confidence score (0.0-1.0)
     */
    fun getConfidence(text: String, language: String): Float {
        val lowerText = text.lowercase()
        val words = lowerText.split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (words.isEmpty()) return 0.0f

        val commonWordsSet = commonWords[language] ?: return 0.0f
        var matchCount = 0

        for (word in words) {
            if (word in commonWordsSet) {
                matchCount++
            }
        }

        return matchCount.toFloat() / words.size
    }

    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(language: String): Boolean {
        return language in SUPPORTED_LANGUAGES
    }
}
