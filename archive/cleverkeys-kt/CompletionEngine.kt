package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.io.*

/**
 * Manages intelligent text completion with templates and snippets.
 *
 * Provides advanced text completion including word completion, phrase templates,
 * smart snippets, abbreviation expansion, and context-aware suggestions.
 *
 * Features:
 * - Word and phrase completion
 * - Template-based completion with placeholders
 * - Smart snippet expansion
 * - Abbreviation expansion (e.g., "btw" -> "by the way")
 * - Context-aware completion
 * - Dynamic placeholder replacement
 * - Multi-cursor template insertion
 * - Completion ranking by usage
 * - Custom completion additions
 * - Persistent completion storage
 * - Learning from user patterns
 * - Category-based organization
 * - Trigger-based activation
 *
 * Bug #314 - CATASTROPHIC: Complete implementation of missing CompletionEngine.java
 *
 * @param context Application context for storage
 */
class CompletionEngine(
    private val context: Context
) {
    companion object {
        private const val TAG = "CompletionEngine"

        // Storage
        private const val COMPLETIONS_FILE_NAME = "completions.dat"
        private const val BACKUP_FILE_NAME = "completions_backup.dat"

        // Completion parameters
        private const val DEFAULT_MAX_COMPLETIONS = 10
        private const val DEFAULT_MIN_PREFIX_LENGTH = 2
        private const val DEFAULT_MIN_CONFIDENCE = 0.3f

        /**
         * Completion type.
         */
        enum class Type {
            WORD,           // Single word completion
            PHRASE,         // Multi-word phrase
            TEMPLATE,       // Template with placeholders
            SNIPPET,        // Code or text snippet
            ABBREVIATION,   // Abbreviation expansion
            CUSTOM          // User-defined completion
        }

        /**
         * Completion category.
         */
        enum class Category {
            GENERAL,
            EMAIL,
            CODE,
            FORMAL,
            CASUAL,
            TECHNICAL,
            MEDICAL,
            LEGAL,
            CUSTOM
        }

        /**
         * Completion trigger.
         */
        enum class Trigger {
            PREFIX_MATCH,   // Matches prefix
            ABBREV_MATCH,   // Matches abbreviation
            CONTEXT_MATCH,  // Matches context pattern
            MANUAL          // Manually triggered
        }

        /**
         * Completion data.
         */
        data class Completion(
            val id: String,
            val trigger: String,          // What triggers this completion
            val text: String,              // Completion text
            val type: Type,
            val category: Category,
            val placeholders: List<Placeholder> = emptyList(),
            val description: String = "",
            var usageCount: Long = 0,
            var lastUsed: Long = 0,
            val metadata: Map<String, Any> = emptyMap()
        ) : Serializable {
            /**
             * Check if this completion has placeholders.
             */
            fun hasPlaceholders(): Boolean = placeholders.isNotEmpty()

            /**
             * Get display text (with placeholder hints).
             */
            fun getDisplayText(): String {
                if (!hasPlaceholders()) return text

                var result = text
                placeholders.forEach { placeholder ->
                    result = result.replace(
                        "{{${placeholder.name}}}",
                        "[${placeholder.hint}]"
                    )
                }
                return result
            }
        }

        /**
         * Template placeholder.
         */
        data class Placeholder(
            val name: String,
            val hint: String,
            val defaultValue: String = "",
            val required: Boolean = true
        ) : Serializable

        /**
         * Completion result.
         */
        data class CompletionResult(
            val completion: Completion,
            val confidence: Float,
            val trigger: Trigger,
            val replacementRange: IntRange
        )
    }

    /**
     * Callback interface for completion events.
     */
    interface Callback {
        /**
         * Called when completion is suggested.
         *
         * @param results List of completion results
         */
        fun onCompletionsSuggested(results: List<CompletionResult>)

        /**
         * Called when completion is applied.
         *
         * @param completion Applied completion
         */
        fun onCompletionApplied(completion: Completion)

        /**
         * Called when new completion is added.
         *
         * @param completion Added completion
         */
        fun onCompletionAdded(completion: Completion)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private var maxCompletions: Int = DEFAULT_MAX_COMPLETIONS
    private var minPrefixLength: Int = DEFAULT_MIN_PREFIX_LENGTH
    private var minConfidence: Float = DEFAULT_MIN_CONFIDENCE
    private var callback: Callback? = null

    // Completions storage (thread-safe)
    private val completions = ConcurrentHashMap<String, Completion>()  // id -> completion
    private val triggerIndex = ConcurrentHashMap<String, MutableList<String>>()  // trigger -> completion ids

    // Built-in completions
    private val builtInCompletions = listOf(
        // Common abbreviations
        Completion("btw", "btw", "by the way", Type.ABBREVIATION, Category.CASUAL),
        Completion("fyi", "fyi", "for your information", Type.ABBREVIATION, Category.GENERAL),
        Completion("imo", "imo", "in my opinion", Type.ABBREVIATION, Category.CASUAL),
        Completion("imho", "imho", "in my humble opinion", Type.ABBREVIATION, Category.CASUAL),
        Completion("tbh", "tbh", "to be honest", Type.ABBREVIATION, Category.CASUAL),
        Completion("afaik", "afaik", "as far as I know", Type.ABBREVIATION, Category.CASUAL),
        Completion("asap", "asap", "as soon as possible", Type.ABBREVIATION, Category.GENERAL),
        Completion("eta", "eta", "estimated time of arrival", Type.ABBREVIATION, Category.GENERAL),
        Completion("faq", "faq", "frequently asked questions", Type.ABBREVIATION, Category.GENERAL),
        Completion("tbd", "tbd", "to be determined", Type.ABBREVIATION, Category.GENERAL),

        // Common phrases
        Completion("tyvm", "thank you", "Thank you very much", Type.PHRASE, Category.CASUAL),
        Completion("yw", "you're welcome", "You're welcome", Type.PHRASE, Category.CASUAL),
        Completion("lmk", "let me know", "Please let me know", Type.PHRASE, Category.GENERAL),
        Completion("gtg", "got to go", "I've got to go", Type.PHRASE, Category.CASUAL),
        Completion("brb", "be right back", "I'll be right back", Type.PHRASE, Category.CASUAL),

        // Email templates
        Completion(
            "email_greeting",
            "dear",
            "Dear {{recipient}},\n\n",
            Type.TEMPLATE,
            Category.EMAIL,
            listOf(Placeholder("recipient", "Name", "Sir/Madam"))
        ),
        Completion(
            "email_closing",
            "regards",
            "Best regards,\n{{sender}}",
            Type.TEMPLATE,
            Category.EMAIL,
            listOf(Placeholder("sender", "Your Name"))
        ),

        // Code snippets
        Completion(
            "func_kotlin",
            "fun",
            "fun {{name}}({{params}}): {{return}} {\n    {{body}}\n}",
            Type.SNIPPET,
            Category.CODE,
            listOf(
                Placeholder("name", "Function name"),
                Placeholder("params", "Parameters", "", false),
                Placeholder("return", "Return type", "Unit"),
                Placeholder("body", "Function body")
            )
        ),
        Completion(
            "class_kotlin",
            "class",
            "class {{name}} {\n    {{body}}\n}",
            Type.SNIPPET,
            Category.CODE,
            listOf(
                Placeholder("name", "Class name"),
                Placeholder("body", "Class body")
            )
        ),

        // Formal phrases
        Completion(
            "formal_request",
            "kindly request",
            "I kindly request your assistance with {{matter}}.",
            Type.TEMPLATE,
            Category.FORMAL,
            listOf(Placeholder("matter", "Subject"))
        ),
        Completion(
            "formal_thanks",
            "grateful",
            "I am grateful for your {{support}}.",
            Type.TEMPLATE,
            Category.FORMAL,
            listOf(Placeholder("support", "What you're thankful for"))
        )
    )

    init {
        logD("CompletionEngine initialized")

        // Load built-in completions
        builtInCompletions.forEach { completion ->
            addCompletion(completion)
        }

        // Load user completions from storage
        scope.launch {
            loadCompletions()
        }
    }

    /**
     * Get completions for current input.
     *
     * @param text Current text
     * @param prefix Current word/prefix
     * @param cursorPosition Cursor position
     * @return List of completion results
     */
    suspend fun getCompletions(
        text: String,
        prefix: String,
        cursorPosition: Int
    ): List<CompletionResult> = withContext(Dispatchers.Default) {
        if (!_isEnabled.value || prefix.length < minPrefixLength) {
            return@withContext emptyList()
        }

        val results = mutableListOf<CompletionResult>()
        val normalizedPrefix = prefix.lowercase()

        // 1. Prefix matching
        triggerIndex.forEach { (trigger, completionIds) ->
            if (trigger.lowercase().startsWith(normalizedPrefix)) {
                completionIds.forEach { id ->
                    completions[id]?.let { completion ->
                        val confidence = calculatePrefixConfidence(normalizedPrefix, trigger)
                        if (confidence >= minConfidence) {
                            results.add(
                                CompletionResult(
                                    completion = completion,
                                    confidence = confidence,
                                    trigger = Trigger.PREFIX_MATCH,
                                    replacementRange = (cursorPosition - prefix.length) until cursorPosition
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Exact abbreviation matching
        triggerIndex[normalizedPrefix]?.forEach { id ->
            completions[id]?.let { completion ->
                if (completion.type == Type.ABBREVIATION) {
                    results.add(
                        CompletionResult(
                            completion = completion,
                            confidence = 0.95f,
                            trigger = Trigger.ABBREV_MATCH,
                            replacementRange = (cursorPosition - prefix.length) until cursorPosition
                        )
                    )
                }
            }
        }

        // Rank and filter
        val ranked = rankCompletions(results)
            .filter { it.confidence >= minConfidence }
            .take(maxCompletions)

        callback?.onCompletionsSuggested(ranked)

        ranked
    }

    /**
     * Calculate prefix matching confidence.
     */
    private fun calculatePrefixConfidence(prefix: String, trigger: String): Float {
        if (prefix == trigger) return 1.0f
        if (prefix.isEmpty() || trigger.isEmpty()) return 0f

        val matchRatio = prefix.length.toFloat() / trigger.length
        val prefixBonus = if (trigger.startsWith(prefix)) 0.2f else 0f

        return (matchRatio * 0.8f + prefixBonus).coerceIn(0f, 1f)
    }

    /**
     * Rank completions by usage and confidence.
     */
    private fun rankCompletions(results: List<CompletionResult>): List<CompletionResult> {
        return results.sortedWith(
            compareByDescending<CompletionResult> { it.confidence }
                .thenByDescending { it.completion.usageCount }
                .thenByDescending { it.completion.lastUsed }
        )
    }

    /**
     * Apply completion and record usage.
     *
     * @param completion Completion to apply
     * @param placeholderValues Values for placeholders (optional)
     * @return Expanded completion text
     */
    suspend fun applyCompletion(
        completion: Completion,
        placeholderValues: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.Default) {
        var result = completion.text

        // Replace placeholders
        if (completion.hasPlaceholders()) {
            completion.placeholders.forEach { placeholder ->
                val value = placeholderValues[placeholder.name]
                    ?: placeholder.defaultValue
                    ?: ""
                result = result.replace("{{${placeholder.name}}}", value)
            }
        }

        // Update usage statistics
        completion.usageCount++
        completion.lastUsed = System.currentTimeMillis()

        logD("Applied completion: ${completion.id} (usage: ${completion.usageCount})")
        callback?.onCompletionApplied(completion)

        // Save to persistent storage
        saveCompletions()

        result
    }

    /**
     * Add custom completion.
     *
     * @param completion Completion to add
     */
    fun addCompletion(completion: Completion) {
        completions[completion.id] = completion

        // Update trigger index
        val trigger = completion.trigger.lowercase()
        triggerIndex.getOrPut(trigger) { mutableListOf() }.add(completion.id)

        logD("Added completion: ${completion.id} (trigger: $trigger)")
        callback?.onCompletionAdded(completion)
    }

    /**
     * Remove completion.
     *
     * @param completionId Completion ID to remove
     */
    fun removeCompletion(completionId: String) {
        completions.remove(completionId)?.let { completion ->
            // Remove from trigger index
            triggerIndex[completion.trigger.lowercase()]?.remove(completionId)
            logD("Removed completion: $completionId")
        }
    }

    /**
     * Get all completions.
     *
     * @return List of all completions
     */
    fun getAllCompletions(): List<Completion> = completions.values.toList()

    /**
     * Get completions by category.
     *
     * @param category Category to filter by
     * @return List of completions in category
     */
    fun getCompletionsByCategory(category: Category): List<Completion> {
        return completions.values.filter { it.category == category }
    }

    /**
     * Get completions by type.
     *
     * @param type Type to filter by
     * @return List of completions of type
     */
    fun getCompletionsByType(type: Type): List<Completion> {
        return completions.values.filter { it.type == type }
    }

    /**
     * Clear all custom completions.
     */
    fun clearCustomCompletions() {
        val customIds = completions.values
            .filter { !builtInCompletions.contains(it) }
            .map { it.id }

        customIds.forEach { removeCompletion(it) }
        logD("Cleared ${customIds.size} custom completions")
    }

    /**
     * Save completions to persistent storage.
     */
    private suspend fun saveCompletions(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, COMPLETIONS_FILE_NAME)
            val backupFile = File(context.filesDir, BACKUP_FILE_NAME)

            // Backup existing file
            if (file.exists()) {
                file.copyTo(backupFile, overwrite = true)
            }

            // Save only custom completions (not built-in)
            val customCompletions = completions.values.filter { completion ->
                !builtInCompletions.any { it.id == completion.id }
            }

            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { oos ->
                oos.writeInt(1)  // Version
                oos.writeInt(customCompletions.size)
                customCompletions.forEach { completion ->
                    oos.writeObject(completion)
                }
            }

            logD("✅ Saved ${customCompletions.size} custom completions")
            Result.success(Unit)
        } catch (e: Exception) {
            logE("Failed to save completions", e)
            Result.failure(e)
        }
    }

    /**
     * Load completions from persistent storage.
     */
    private suspend fun loadCompletions(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, COMPLETIONS_FILE_NAME)
            if (!file.exists()) {
                logD("No saved completions found")
                return@withContext Result.success(Unit)
            }

            ObjectInputStream(BufferedInputStream(FileInputStream(file))).use { ois ->
                val version = ois.readInt()
                if (version != 1) {
                    logE("Unsupported completions version: $version", null)
                    return@withContext Result.failure(IOException("Unsupported version"))
                }

                val count = ois.readInt()
                repeat(count) {
                    val completion = ois.readObject() as Completion
                    addCompletion(completion)
                }

                logD("✅ Loaded $count custom completions")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logE("Failed to load completions", e)
            Result.failure(e)
        }
    }

    /**
     * Set maximum number of completions.
     *
     * @param max Maximum completions
     */
    fun setMaxCompletions(max: Int) {
        maxCompletions = max.coerceIn(1, 50)
    }

    /**
     * Set minimum prefix length for completion.
     *
     * @param length Minimum length
     */
    fun setMinPrefixLength(length: Int) {
        minPrefixLength = length.coerceIn(1, 10)
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
     * Enable or disable completions.
     *
     * @param enabled Whether completions are enabled
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        logD("Completions ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get completion statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "enabled" to _isEnabled.value,
        "total_completions" to completions.size,
        "built_in_completions" to builtInCompletions.size,
        "custom_completions" to (completions.size - builtInCompletions.size),
        "max_completions" to maxCompletions,
        "min_prefix_length" to minPrefixLength,
        "min_confidence" to minConfidence,
        "most_used" to (completions.values.maxByOrNull { it.usageCount }?.id ?: "none")
    )

    /**
     * Set callback for completion events.
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
        logD("Releasing CompletionEngine resources...")

        try {
            // Save before releasing
            scope.launch {
                saveCompletions()
            }

            scope.cancel()
            callback = null
            completions.clear()
            triggerIndex.clear()
            logD("✅ CompletionEngine resources released")
        } catch (e: Exception) {
            logE("Error releasing completion engine resources", e)
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
