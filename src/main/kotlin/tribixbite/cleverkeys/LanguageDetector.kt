package tribixbite.cleverkeys

import android.util.Log
import tribixbite.cleverkeys.BuildConfig
import kotlin.math.abs
import kotlin.math.max

/**
 * Simple language detection based on word patterns and character frequency analysis
 * Used for automatic language switching in contextual predictions
 */
class LanguageDetector {
    companion object {
        private const val TAG = "LanguageDetector"

        // Detection thresholds
        private const val MIN_CONFIDENCE_THRESHOLD = 0.6f
        private const val MIN_TEXT_LENGTH = 10 // Minimum characters needed for detection
    }

    /**
     * Result of language detection with confidence score
     */
    data class DetectionResult(
        val language: String,
        val confidence: Float
    )

    // Language-specific character patterns
    private val languageCharFreqs: MutableMap<String, Map<Char, Float>> = mutableMapOf()

    // Language-specific common words
    private val languageCommonWords: MutableMap<String, Array<String>> = mutableMapOf()

    init {
        initializeLanguagePatterns()
    }

    /**
     * Initialize language detection patterns
     */
    private fun initializeLanguagePatterns() {
        initializeEnglishPatterns()
        initializeSpanishPatterns()
        initializeFrenchPatterns()
        initializePortuguesePatterns()
        initializeGermanPatterns()
    }

    /**
     * Initialize English language patterns
     */
    private fun initializeEnglishPatterns() {
        val enChars = mapOf(
            'e' to 12.7f,
            't' to 9.1f,
            'a' to 8.2f,
            'o' to 7.5f,
            'i' to 7.0f,
            'n' to 6.7f,
            's' to 6.3f,
            'h' to 6.1f,
            'r' to 6.0f
        )
        languageCharFreqs["en"] = enChars

        val enWords = arrayOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "I",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at"
        )
        languageCommonWords["en"] = enWords
    }

    /**
     * Initialize Spanish language patterns
     */
    private fun initializeSpanishPatterns() {
        val esChars = mapOf(
            'a' to 12.5f,
            'e' to 12.2f,
            'o' to 8.7f,
            's' to 8.0f,
            'n' to 6.8f,
            'r' to 6.9f,
            'i' to 6.2f,
            'l' to 5.0f,
            'd' to 5.9f,
            't' to 4.6f
        )
        languageCharFreqs["es"] = esChars

        val esWords = arrayOf(
            "de", "la", "que", "el", "en", "y", "a", "es", "se", "no",
            "te", "lo", "le", "da", "su", "por", "son", "con", "para", "una"
        )
        languageCommonWords["es"] = esWords
    }

    /**
     * Initialize French language patterns
     */
    private fun initializeFrenchPatterns() {
        val frChars = mapOf(
            'e' to 14.7f,
            's' to 7.9f,
            'a' to 7.6f,
            'i' to 7.5f,
            't' to 7.2f,
            'n' to 7.1f,
            'r' to 6.6f,
            'u' to 6.3f,
            'l' to 5.5f,
            'o' to 5.4f
        )
        languageCharFreqs["fr"] = frChars

        val frWords = arrayOf(
            "de", "le", "et", "à", "un", "il", "être", "et", "en", "avoir",
            "que", "pour", "dans", "ce", "son", "une", "sur", "avec", "ne", "se"
        )
        languageCommonWords["fr"] = frWords
    }

    /**
     * Initialize Portuguese language patterns
     */
    private fun initializePortuguesePatterns() {
        val ptChars = mapOf(
            'a' to 14.6f,
            'e' to 12.6f,
            'o' to 10.7f,
            's' to 7.8f,
            'r' to 6.5f,
            'i' to 6.2f,
            'm' to 4.7f,
            't' to 4.7f,
            'd' to 5.0f,
            'n' to 5.0f
        )
        languageCharFreqs["pt"] = ptChars

        val ptWords = arrayOf(
            "de", "a", "o", "que", "e", "do", "da", "em", "um", "para",
            "é", "com", "não", "uma", "os", "no", "se", "na", "por", "mais"
        )
        languageCommonWords["pt"] = ptWords
    }

    /**
     * Initialize German language patterns
     */
    private fun initializeGermanPatterns() {
        val deChars = mapOf(
            'e' to 17.4f,
            'n' to 9.8f,
            's' to 7.3f,
            'r' to 7.0f,
            'i' to 7.5f,
            't' to 6.2f,
            'd' to 5.1f,
            'h' to 4.8f,
            'u' to 4.4f,
            'l' to 3.4f
        )
        languageCharFreqs["de"] = deChars

        val deWords = arrayOf(
            "der", "die", "und", "in", "den", "von", "zu", "das", "mit", "sich",
            "auf", "für", "ist", "im", "dem", "nicht", "ein", "eine", "als", "auch"
        )
        languageCommonWords["de"] = deWords
    }

    /**
     * Detect language from a text sample
     * @param text Input text to analyze
     * @return Detected language code ("en", "es", "fr", "de") or null if detection fails
     */
    fun detectLanguage(text: String?): String? {
        if (text == null || text.length < MIN_TEXT_LENGTH) {
            return null // Not enough text for reliable detection
        }

        val normalizedText = text.lowercase().trim()

        // Calculate scores for each language
        val languageScores = mutableMapOf<String, Float>()
        for (language in languageCharFreqs.keys) {
            val score = calculateLanguageScore(normalizedText, language)
            languageScores[language] = score
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Language $language score: $score")
            }
        }

        // Find the best match
        val (bestLanguage, bestScore) = languageScores.maxByOrNull { it.value }
            ?: return null

        // Check if confidence is high enough
        if (bestScore >= MIN_CONFIDENCE_THRESHOLD) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Detected language: $bestLanguage (confidence: $bestScore)")
            }
            return bestLanguage
        }

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            Log.d(TAG, "Language detection failed, confidence too low: $bestScore")
        }
        return null // Low confidence
    }

    /**
     * Detect language from a list of recent words
     * @param words List of recent words typed by user
     * @return Detected language code or null if detection fails
     */
    fun detectLanguageFromWords(words: List<String>?): String? {
        if (words.isNullOrEmpty()) {
            return null
        }

        val text = words.filterNotNull().joinToString(" ")
        return detectLanguage(text)
    }

    /**
     * Detect language with confidence score
     * @param text Input text to analyze
     * @return DetectionResult with language and confidence, or null if detection fails
     */
    fun detectLanguageWithConfidence(text: String?): DetectionResult? {
        if (text == null || text.length < MIN_TEXT_LENGTH) {
            return null
        }

        val normalizedText = text.lowercase().trim()

        // Calculate scores for each language
        val languageScores = mutableMapOf<String, Float>()
        for (language in languageCharFreqs.keys) {
            val score = calculateLanguageScore(normalizedText, language)
            languageScores[language] = score
        }

        // Find the best match
        val (bestLanguage, bestScore) = languageScores.maxByOrNull { it.value }
            ?: return null

        // Return result even if below threshold (caller can decide)
        return DetectionResult(bestLanguage, bestScore)
    }

    /**
     * Detect language with confidence from word list
     * @param words List of recent words typed by user
     * @return DetectionResult with language and confidence, or null if detection fails
     */
    fun detectLanguageFromWordsWithConfidence(words: List<String>?): DetectionResult? {
        if (words.isNullOrEmpty()) {
            return null
        }
        val text = words.filterNotNull().joinToString(" ")
        return detectLanguageWithConfidence(text)
    }

    /**
     * Calculate language score based on character frequency and common words
     */
    private fun calculateLanguageScore(text: String, language: String): Float {
        val charScore = calculateCharacterFrequencyScore(text, language)
        val wordScore = calculateCommonWordScore(text, language)

        // Weighted combination: 60% character frequency, 40% common words
        return (charScore * 0.6f) + (wordScore * 0.4f)
    }

    /**
     * Calculate score based on character frequency analysis
     */
    private fun calculateCharacterFrequencyScore(text: String, language: String): Float {
        val expectedFreqs = languageCharFreqs[language] ?: return 0.0f

        // Count character frequencies in the text
        val actualCounts = mutableMapOf<Char, Int>()
        var totalChars = 0

        for (c in text) {
            if (c.isLetter()) {
                actualCounts[c] = actualCounts.getOrDefault(c, 0) + 1
                totalChars++
            }
        }

        if (totalChars == 0) {
            return 0.0f
        }

        // Calculate correlation between expected and actual frequencies
        var score = 0.0f
        var matchedChars = 0

        for ((c, expectedFreq) in expectedFreqs) {
            val actualCount = actualCounts.getOrDefault(c, 0)
            val actualFreq = (actualCount * 100.0f) / totalChars

            // Use inverse of frequency difference as score contribution
            val freqDiff = abs(expectedFreq - actualFreq)
            val contribution = max(0f, 1.0f - (freqDiff / expectedFreq))
            score += contribution
            matchedChars++
        }

        return if (matchedChars > 0) score / matchedChars else 0.0f
    }

    /**
     * Calculate score based on presence of common words
     */
    private fun calculateCommonWordScore(text: String, language: String): Float {
        val commonWords = languageCommonWords[language] ?: return 0.0f

        val textWords = text.split("\\s+".toRegex())
        if (textWords.isEmpty()) {
            return 0.0f
        }

        var matches = 0
        for (commonWord in commonWords) {
            for (textWord in textWords) {
                if (commonWord == textWord.lowercase()) {
                    matches++
                    break // Only count each common word once
                }
            }
        }

        // Score is the ratio of matched common words
        return matches.toFloat() / commonWords.size
    }

    /**
     * Get list of supported languages
     */
    fun getSupportedLanguages(): Array<String> {
        return languageCharFreqs.keys.toTypedArray()
    }

    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(language: String): Boolean {
        return languageCharFreqs.containsKey(language)
    }

    /**
     * Set the minimum confidence threshold for detection
     */
    fun setConfidenceThreshold(threshold: Float) {
        // Note: This would require making MIN_CONFIDENCE_THRESHOLD non-final
        // For now, keeping it as a constant for reliability
    }
}
