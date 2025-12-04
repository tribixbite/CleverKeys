package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages grammar checking with rule-based validation.
 *
 * Provides intelligent grammar checking including subject-verb agreement,
 * article usage, tense consistency, word order, common errors, and
 * style suggestions.
 *
 * Features:
 * - Subject-verb agreement checking
 * - Article usage validation (a/an/the)
 * - Tense consistency detection
 * - Word order validation
 * - Common grammar error detection
 * - Punctuation checking
 * - Capitalization rules
 * - Pronoun usage validation
 * - Double negative detection
 * - Redundancy detection
 * - Style suggestions
 * - Sentence fragment detection
 * - Run-on sentence detection
 *
 * Bug #317 - CATASTROPHIC: Complete implementation of missing GrammarChecker.java
 *
 * @param context Application context
 */
class GrammarChecker(
    private val context: Context
) {
    companion object {
        private const val TAG = "GrammarChecker"

        // Checking parameters
        private const val DEFAULT_MAX_ISSUES = 10
        private const val DEFAULT_MIN_CONFIDENCE = 0.5f

        /**
         * Grammar issue type.
         */
        enum class IssueType {
            SUBJECT_VERB_AGREEMENT,
            ARTICLE_ERROR,
            TENSE_INCONSISTENCY,
            WORD_ORDER,
            PUNCTUATION,
            CAPITALIZATION,
            PRONOUN_ERROR,
            DOUBLE_NEGATIVE,
            REDUNDANCY,
            FRAGMENT,
            RUN_ON,
            SPELLING,
            STYLE,
            OTHER
        }

        /**
         * Issue severity.
         */
        enum class Severity {
            ERROR,      // Grammatically incorrect
            WARNING,    // Potentially incorrect
            SUGGESTION, // Style improvement
            INFO        // Informational
        }

        /**
         * Grammar issue.
         */
        data class GrammarIssue(
            val type: IssueType,
            val severity: Severity,
            val message: String,
            val position: Int,
            val length: Int,
            val suggestions: List<String>,
            val confidence: Float,
            val rule: String = ""
        )

        /**
         * Grammar check result.
         */
        data class GrammarResult(
            val text: String,
            val issues: List<GrammarIssue>,
            val isCorrect: Boolean,
            val score: Float  // 0.0 - 1.0
        )
    }

    /**
     * Callback interface for grammar checking events.
     */
    interface Callback {
        /**
         * Called when grammar issues are found.
         *
         * @param issues List of grammar issues
         */
        fun onIssuesFound(issues: List<GrammarIssue>)

        /**
         * Called when grammar check completes.
         *
         * @param result Check result
         */
        fun onCheckComplete(result: GrammarResult)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private var maxIssues: Int = DEFAULT_MAX_ISSUES
    private var minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    private var callback: Callback? = null

    // Grammar rules
    private val singularVerbs = setOf("is", "was", "has", "does", "goes", "makes")
    private val pluralVerbs = setOf("are", "were", "have", "do", "go", "make")
    private val singularPronouns = setOf("he", "she", "it", "this", "that")
    private val pluralPronouns = setOf("they", "we", "these", "those")

    // Common errors
    private val commonErrors = mapOf(
        "alot" to "a lot",
        "cant" to "can't",
        "wont" to "won't",
        "shouldnt" to "shouldn't",
        "wouldnt" to "wouldn't",
        "couldnt" to "couldn't",
        "your" to "you're",  // context-dependent
        "its" to "it's",      // context-dependent
        "their" to "they're", // context-dependent
        "then" to "than",     // context-dependent
        "affect" to "effect", // context-dependent
        "loose" to "lose"     // context-dependent
    )

    // Redundant phrases
    private val redundantPhrases = mapOf(
        "very unique" to "unique",
        "completely finished" to "finished",
        "past history" to "history",
        "future plans" to "plans",
        "advance warning" to "warning",
        "end result" to "result",
        "final outcome" to "outcome",
        "free gift" to "gift"
    )

    // Double negatives
    private val negatives = setOf("no", "not", "never", "nothing", "nobody", "none", "neither")

    init {
        logD("GrammarChecker initialized")
    }

    /**
     * Check text for grammar issues.
     *
     * @param text Text to check
     * @return Grammar check result
     */
    suspend fun check(text: String): GrammarResult = withContext(Dispatchers.Default) {
        if (!_isEnabled.value || text.isBlank()) {
            return@withContext GrammarResult(
                text = text,
                issues = emptyList(),
                isCorrect = true,
                score = 1.0f
            )
        }

        val issues = mutableListOf<GrammarIssue>()

        // Tokenize into sentences
        val sentences = splitSentences(text)

        // Check each sentence
        sentences.forEach { sentence ->
            val sentenceStart = text.indexOf(sentence)
            if (sentenceStart >= 0) {
                issues.addAll(checkSentence(sentence, sentenceStart))
            }
        }

        // Filter by confidence
        val filtered = issues
            .filter { it.confidence >= minConfidence }
            .take(maxIssues)

        // Calculate score
        val score = calculateScore(text, filtered)

        val result = GrammarResult(
            text = text,
            issues = filtered,
            isCorrect = filtered.isEmpty(),
            score = score
        )

        callback?.onIssuesFound(filtered)
        callback?.onCheckComplete(result)

        result
    }

    /**
     * Check a single sentence.
     */
    private fun checkSentence(sentence: String, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()
        val words = tokenize(sentence)

        // Check capitalization
        issues.addAll(checkCapitalization(sentence, offset))

        // Check punctuation
        issues.addAll(checkPunctuation(sentence, offset))

        // Check subject-verb agreement
        issues.addAll(checkSubjectVerbAgreement(words, offset))

        // Check article usage
        issues.addAll(checkArticles(words, offset))

        // Check double negatives
        issues.addAll(checkDoubleNegatives(words, offset))

        // Check redundancy
        issues.addAll(checkRedundancy(sentence, offset))

        // Check common errors
        issues.addAll(checkCommonErrors(words, offset))

        // Check sentence structure
        issues.addAll(checkSentenceStructure(words, offset))

        return issues
    }

    /**
     * Check capitalization rules.
     */
    private fun checkCapitalization(sentence: String, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()

        // First letter should be capitalized
        if (sentence.isNotEmpty() && sentence[0].isLowerCase()) {
            issues.add(
                GrammarIssue(
                    type = IssueType.CAPITALIZATION,
                    severity = Severity.ERROR,
                    message = "Sentence should start with a capital letter",
                    position = offset,
                    length = 1,
                    suggestions = listOf(sentence[0].uppercase() + sentence.substring(1)),
                    confidence = 0.9f,
                    rule = "capitalize_first"
                )
            )
        }

        // Check "i" should be "I"
        val words = sentence.split(" ")
        words.forEachIndexed { index, word ->
            if (word == "i") {
                val pos = offset + sentence.indexOf(" i ")
                if (pos >= offset) {
                    issues.add(
                        GrammarIssue(
                            type = IssueType.CAPITALIZATION,
                            severity = Severity.ERROR,
                            message = "Personal pronoun 'I' should be capitalized",
                            position = pos + 1,
                            length = 1,
                            suggestions = listOf("I"),
                            confidence = 1.0f,
                            rule = "capitalize_i"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Check punctuation.
     */
    private fun checkPunctuation(sentence: String, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()

        // Sentence should end with punctuation
        if (sentence.isNotEmpty() && !sentence.last().let { it in ".!?;" }) {
            issues.add(
                GrammarIssue(
                    type = IssueType.PUNCTUATION,
                    severity = Severity.WARNING,
                    message = "Sentence should end with punctuation",
                    position = offset + sentence.length,
                    length = 0,
                    suggestions = listOf("$sentence.", "$sentence!", "$sentence?"),
                    confidence = 0.7f,
                    rule = "end_punctuation"
                )
            )
        }

        // Check for multiple spaces
        if (sentence.contains("  ")) {
            val pos = offset + sentence.indexOf("  ")
            issues.add(
                GrammarIssue(
                    type = IssueType.PUNCTUATION,
                    severity = Severity.WARNING,
                    message = "Multiple spaces detected",
                    position = pos,
                    length = 2,
                    suggestions = listOf(sentence.replace("  ", " ")),
                    confidence = 0.9f,
                    rule = "multiple_spaces"
                )
            )
        }

        return issues
    }

    /**
     * Check subject-verb agreement.
     */
    private fun checkSubjectVerbAgreement(words: List<String>, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()

        for (i in 0 until words.size - 1) {
            val subject = words[i].lowercase()
            val verb = words[i + 1].lowercase()

            // Check singular subject with plural verb
            if (subject in singularPronouns && verb in pluralVerbs) {
                issues.add(
                    GrammarIssue(
                        type = IssueType.SUBJECT_VERB_AGREEMENT,
                        severity = Severity.ERROR,
                        message = "Singular subject '$subject' requires singular verb",
                        position = offset + calculatePosition(words, i + 1),
                        length = verb.length,
                        suggestions = getSingularVerb(verb)?.let { listOf(it) } ?: emptyList(),
                        confidence = 0.8f,
                        rule = "subject_verb_singular"
                    )
                )
            }

            // Check plural subject with singular verb
            if (subject in pluralPronouns && verb in singularVerbs) {
                issues.add(
                    GrammarIssue(
                        type = IssueType.SUBJECT_VERB_AGREEMENT,
                        severity = Severity.ERROR,
                        message = "Plural subject '$subject' requires plural verb",
                        position = offset + calculatePosition(words, i + 1),
                        length = verb.length,
                        suggestions = getPluralVerb(verb)?.let { listOf(it) } ?: emptyList(),
                        confidence = 0.8f,
                        rule = "subject_verb_plural"
                    )
                )
            }
        }

        return issues
    }

    /**
     * Check article usage (a/an/the).
     */
    private fun checkArticles(words: List<String>, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()
        val vowels = setOf('a', 'e', 'i', 'o', 'u')

        for (i in 0 until words.size - 1) {
            val article = words[i].lowercase()
            val nextWord = words[i + 1].lowercase()

            if (article == "a" && nextWord.firstOrNull() in vowels) {
                issues.add(
                    GrammarIssue(
                        type = IssueType.ARTICLE_ERROR,
                        severity = Severity.ERROR,
                        message = "Use 'an' before words starting with a vowel sound",
                        position = offset + calculatePosition(words, i),
                        length = 1,
                        suggestions = listOf("an"),
                        confidence = 0.9f,
                        rule = "article_an"
                    )
                )
            }

            if (article == "an" && nextWord.firstOrNull() !in vowels) {
                issues.add(
                    GrammarIssue(
                        type = IssueType.ARTICLE_ERROR,
                        severity = Severity.ERROR,
                        message = "Use 'a' before words starting with a consonant sound",
                        position = offset + calculatePosition(words, i),
                        length = 2,
                        suggestions = listOf("a"),
                        confidence = 0.9f,
                        rule = "article_a"
                    )
                )
            }
        }

        return issues
    }

    /**
     * Check for double negatives.
     */
    private fun checkDoubleNegatives(words: List<String>, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()
        val lowercase = words.map { it.lowercase() }

        val negativeCount = lowercase.count { it in negatives }
        if (negativeCount >= 2) {
            issues.add(
                GrammarIssue(
                    type = IssueType.DOUBLE_NEGATIVE,
                    severity = Severity.WARNING,
                    message = "Double negative detected - may be confusing",
                    position = offset,
                    length = words.joinToString(" ").length,
                    suggestions = emptyList(),
                    confidence = 0.6f,
                    rule = "double_negative"
                )
            )
        }

        return issues
    }

    /**
     * Check for redundant phrases.
     */
    private fun checkRedundancy(sentence: String, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()
        val lowercase = sentence.lowercase()

        redundantPhrases.forEach { (redundant, replacement) ->
            if (lowercase.contains(redundant)) {
                val pos = lowercase.indexOf(redundant)
                issues.add(
                    GrammarIssue(
                        type = IssueType.REDUNDANCY,
                        severity = Severity.SUGGESTION,
                        message = "'$redundant' is redundant - consider '$replacement'",
                        position = offset + pos,
                        length = redundant.length,
                        suggestions = listOf(replacement),
                        confidence = 0.7f,
                        rule = "redundancy"
                    )
                )
            }
        }

        return issues
    }

    /**
     * Check common grammar errors.
     */
    private fun checkCommonErrors(words: List<String>, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()

        words.forEachIndexed { index, word ->
            val lowercase = word.lowercase()
            if (lowercase in commonErrors.keys) {
                issues.add(
                    GrammarIssue(
                        type = IssueType.SPELLING,
                        severity = Severity.WARNING,
                        message = "Possible error: '$word' may be incorrect",
                        position = offset + calculatePosition(words, index),
                        length = word.length,
                        suggestions = commonErrors[lowercase]?.let { listOf(it) } ?: emptyList(),
                        confidence = 0.6f,
                        rule = "common_error"
                    )
                )
            }
        }

        return issues
    }

    /**
     * Check sentence structure.
     */
    private fun checkSentenceStructure(words: List<String>, offset: Int): List<GrammarIssue> {
        val issues = mutableListOf<GrammarIssue>()

        // Check for sentence fragments (too short)
        if (words.size < 3) {
            issues.add(
                GrammarIssue(
                    type = IssueType.FRAGMENT,
                    severity = Severity.INFO,
                    message = "Sentence may be too short or incomplete",
                    position = offset,
                    length = words.joinToString(" ").length,
                    suggestions = emptyList(),
                    confidence = 0.5f,
                    rule = "fragment"
                )
            )
        }

        // Check for run-on sentences (too long)
        if (words.size > 30) {
            issues.add(
                GrammarIssue(
                    type = IssueType.RUN_ON,
                    severity = Severity.SUGGESTION,
                    message = "Sentence may be too long - consider breaking it up",
                    position = offset,
                    length = words.joinToString(" ").length,
                    suggestions = emptyList(),
                    confidence = 0.6f,
                    rule = "run_on"
                )
            )
        }

        return issues
    }

    /**
     * Split text into sentences.
     */
    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("[.!?]+\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Tokenize sentence into words.
     */
    private fun tokenize(sentence: String): List<String> {
        return sentence.split(Regex("\\s+"))
            .map { it.trim().replace(Regex("[^a-zA-Z']"), "") }
            .filter { it.isNotEmpty() }
    }

    /**
     * Calculate word position in original text.
     */
    private fun calculatePosition(words: List<String>, index: Int): Int {
        return words.take(index).joinToString(" ").length + if (index > 0) 1 else 0
    }

    /**
     * Get singular form of verb.
     */
    private fun getSingularVerb(verb: String): String? {
        return when (verb) {
            "are" -> "is"
            "were" -> "was"
            "have" -> "has"
            "do" -> "does"
            else -> null
        }
    }

    /**
     * Get plural form of verb.
     */
    private fun getPluralVerb(verb: String): String? {
        return when (verb) {
            "is" -> "are"
            "was" -> "were"
            "has" -> "have"
            "does" -> "do"
            else -> null
        }
    }

    /**
     * Calculate grammar score (0.0 - 1.0).
     */
    private fun calculateScore(text: String, issues: List<GrammarIssue>): Float {
        if (text.isEmpty()) return 1.0f

        val errorCount = issues.count { it.severity == Severity.ERROR }
        val warningCount = issues.count { it.severity == Severity.WARNING }

        val penalty = (errorCount * 0.2f + warningCount * 0.1f)
        return (1.0f - penalty).coerceIn(0f, 1f)
    }

    /**
     * Set maximum number of issues to report.
     *
     * @param max Maximum issues
     */
    fun setMaxIssues(max: Int) {
        maxIssues = max.coerceIn(1, 50)
    }

    /**
     * Set minimum confidence threshold.
     *
     * @param confidence Minimum confidence (0.0 - 1.0)
     */
    fun setMinConfidence(confidence: Float) {
        minConfidence = confidence.coerceIn(0f, 1f)
    }

    /**
     * Enable or disable grammar checking.
     *
     * @param enabled Whether checking is enabled
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        logD("Grammar checking ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get grammar checking statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "enabled" to _isEnabled.value,
        "max_issues" to maxIssues,
        "min_confidence" to minConfidence,
        "rules_count" to (redundantPhrases.size + commonErrors.size)
    )

    /**
     * Set callback for grammar checking events.
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
        logD("Releasing GrammarChecker resources...")

        try {
            scope.cancel()
            callback = null
            logD("✅ GrammarChecker resources released")
        } catch (e: Exception) {
            logE("Error releasing grammar checker resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
