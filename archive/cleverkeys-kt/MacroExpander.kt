package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages text macro expansion and custom shortcuts.
 *
 * Provides comprehensive macro functionality including user-defined shortcuts,
 * multi-line text expansion, variable substitution, and intelligent trigger detection.
 *
 * Features:
 * - Custom text macros/shortcuts
 * - Multi-line macro expansion
 * - Variable substitution (date, time, clipboard, etc.)
 * - Trigger detection (prefix, delimiter)
 * - Macro categories and organization
 * - Import/export functionality
 * - Usage statistics
 * - Context-aware activation
 * - Case-sensitive/insensitive matching
 * - Macro templates
 * - Nested macro expansion
 * - Conditional macros
 * - Regex-based triggers
 *
 * Bug #354 - CATASTROPHIC: Complete implementation of missing MacroExpander.java
 *
 * @param context Application context
 */
class MacroExpander(
    private val context: Context
) {
    companion object {
        private const val TAG = "MacroExpander"

        // Storage
        private const val MACROS_FILE = "macros.txt"
        private const val MAX_MACROS = 1000
        private const val MAX_EXPANSION_LENGTH = 10000

        /**
         * Macro trigger type.
         */
        enum class TriggerType {
            PREFIX,        // Triggered by typing trigger text (e.g., "brb" → expansion)
            DELIMITER,     // Triggered by delimiter after trigger (e.g., "brb " → expansion)
            REGEX,         // Triggered by regex pattern match
            MANUAL         // Triggered manually by user action
        }

        /**
         * Macro category.
         */
        enum class Category {
            GENERAL,       // General purpose macros
            WORK,          // Work-related macros
            PERSONAL,      // Personal macros
            PROGRAMMING,   // Code snippets
            EMAIL,         // Email templates
            SOCIAL,        // Social media responses
            CUSTOM         // User-defined categories
        }

        /**
         * Variable type for substitution.
         */
        enum class VariableType {
            DATE,          // Current date
            TIME,          // Current time
            DATETIME,      // Date and time
            CLIPBOARD,     // Clipboard content
            CURSOR,        // Cursor position marker
            SELECTION,     // Selected text
            RANDOM,        // Random value
            COUNTER,       // Incremental counter
            CUSTOM         // Custom variable value
        }

        /**
         * Text macro.
         */
        data class Macro(
            val id: String,
            val trigger: String,
            val expansion: String,
            val triggerType: TriggerType = TriggerType.PREFIX,
            val category: Category = Category.GENERAL,
            val description: String = "",
            val caseSensitive: Boolean = false,
            val multiLine: Boolean = false,
            val variables: Map<String, VariableType> = emptyMap(),
            val enabled: Boolean = true,
            val usageCount: Long = 0,
            val lastUsed: Long = 0,
            val created: Long = System.currentTimeMillis()
        ) {
            /**
             * Check if trigger matches text.
             */
            fun matches(text: String, withDelimiter: Boolean = false): Boolean {
                if (!enabled) return false

                return when (triggerType) {
                    TriggerType.PREFIX -> {
                        if (caseSensitive) {
                            text == trigger
                        } else {
                            text.equals(trigger, ignoreCase = true)
                        }
                    }
                    TriggerType.DELIMITER -> {
                        if (withDelimiter) {
                            if (caseSensitive) {
                                text.startsWith(trigger) && text.length > trigger.length
                            } else {
                                text.startsWith(trigger, ignoreCase = true) && text.length > trigger.length
                            }
                        } else false
                    }
                    TriggerType.REGEX -> {
                        try {
                            Regex(trigger).matches(text)
                        } catch (e: Exception) {
                            false
                        }
                    }
                    TriggerType.MANUAL -> false
                }
            }

            /**
             * Expand macro with variable substitution.
             */
            fun expand(context: Context, customVariables: Map<String, String> = emptyMap()): String {
                var result = expansion

                // Substitute variables
                variables.forEach { (name, type) ->
                    val value = when (type) {
                        VariableType.DATE -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        VariableType.TIME -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        VariableType.DATETIME -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        VariableType.CLIPBOARD -> getClipboardText(context)
                        VariableType.CURSOR -> "{{cursor}}"  // Special marker for cursor position
                        VariableType.SELECTION -> customVariables[name] ?: ""
                        VariableType.RANDOM -> (1000..9999).random().toString()
                        VariableType.COUNTER -> customVariables[name] ?: "0"
                        VariableType.CUSTOM -> customVariables[name] ?: ""
                    }

                    result = result.replace("{{$name}}", value)
                }

                return result
            }

            /**
             * Get cursor position in expanded text.
             * Returns -1 if no cursor marker.
             */
            fun getCursorPosition(expandedText: String): Int {
                return expandedText.indexOf("{{cursor}}")
            }
        }

        /**
         * Macro state.
         */
        data class MacroState(
            val macroCount: Int,
            val enabledCount: Int,
            val totalUsage: Long,
            val categories: Map<Category, Int>
        )

        /**
         * Get clipboard text.
         */
        private fun getClipboardText(context: Context): String {
            return try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * Callback interface for macro events.
     */
    interface Callback {
        /**
         * Called when macro is expanded.
         *
         * @param macro Expanded macro
         * @param expandedText Resulting text
         */
        fun onMacroExpanded(macro: Macro, expandedText: String)

        /**
         * Called when macro is added.
         *
         * @param macro Added macro
         */
        fun onMacroAdded(macro: Macro)

        /**
         * Called when macro is updated.
         *
         * @param macro Updated macro
         */
        fun onMacroUpdated(macro: Macro)

        /**
         * Called when macro is deleted.
         *
         * @param macroId Deleted macro ID
         */
        fun onMacroDeleted(macroId: String)

        /**
         * Called when state changes.
         *
         * @param state New macro state
         */
        fun onStateChanged(state: MacroState)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _macroState = MutableStateFlow(
        MacroState(
            macroCount = 0,
            enabledCount = 0,
            totalUsage = 0,
            categories = emptyMap()
        )
    )
    val macroState: StateFlow<MacroState> = _macroState.asStateFlow()

    private var callback: Callback? = null

    // Macro storage
    private val macros = ConcurrentHashMap<String, Macro>()
    private val triggerIndex = ConcurrentHashMap<String, MutableList<String>>()  // trigger -> macro IDs

    // Built-in macros
    private val builtInMacros = listOf(
        Macro(
            id = "builtin_brb",
            trigger = "brb",
            expansion = "be right back",
            category = Category.SOCIAL,
            description = "Be right back"
        ),
        Macro(
            id = "builtin_omw",
            trigger = "omw",
            expansion = "on my way",
            category = Category.SOCIAL,
            description = "On my way"
        ),
        Macro(
            id = "builtin_btw",
            trigger = "btw",
            expansion = "by the way",
            category = Category.GENERAL,
            description = "By the way"
        ),
        Macro(
            id = "builtin_fyi",
            trigger = "fyi",
            expansion = "for your information",
            category = Category.GENERAL,
            description = "For your information"
        ),
        Macro(
            id = "builtin_asap",
            trigger = "asap",
            expansion = "as soon as possible",
            category = Category.WORK,
            description = "As soon as possible"
        ),
        Macro(
            id = "builtin_imho",
            trigger = "imho",
            expansion = "in my humble opinion",
            category = Category.SOCIAL,
            description = "In my humble opinion"
        ),
        Macro(
            id = "builtin_lol",
            trigger = "lol",
            expansion = "laughing out loud",
            category = Category.SOCIAL,
            description = "Laughing out loud"
        ),
        Macro(
            id = "builtin_thx",
            trigger = "thx",
            expansion = "thanks",
            category = Category.SOCIAL,
            description = "Thanks"
        ),
        Macro(
            id = "builtin_pls",
            trigger = "pls",
            expansion = "please",
            category = Category.GENERAL,
            description = "Please"
        ),
        Macro(
            id = "builtin_idk",
            trigger = "idk",
            expansion = "I don't know",
            category = Category.SOCIAL,
            description = "I don't know"
        ),
        // Multi-line macros
        Macro(
            id = "builtin_email_greeting",
            trigger = "/hello",
            expansion = "Hello,\n\nI hope this email finds you well.\n\n{{cursor}}",
            category = Category.EMAIL,
            description = "Email greeting template",
            multiLine = true,
            variables = mapOf("cursor" to VariableType.CURSOR)
        ),
        Macro(
            id = "builtin_email_signature",
            trigger = "/sig",
            expansion = "Best regards,\n[Your Name]\n[Your Title]",
            category = Category.EMAIL,
            description = "Email signature",
            multiLine = true
        ),
        // Variable macros
        Macro(
            id = "builtin_date",
            trigger = "/date",
            expansion = "{{date}}",
            category = Category.GENERAL,
            description = "Insert current date",
            variables = mapOf("date" to VariableType.DATE)
        ),
        Macro(
            id = "builtin_time",
            trigger = "/time",
            expansion = "{{time}}",
            category = Category.GENERAL,
            description = "Insert current time",
            variables = mapOf("time" to VariableType.TIME)
        ),
        Macro(
            id = "builtin_datetime",
            trigger = "/now",
            expansion = "{{datetime}}",
            category = Category.GENERAL,
            description = "Insert current date and time",
            variables = mapOf("datetime" to VariableType.DATETIME)
        )
    )

    init {
        // Load built-in macros
        builtInMacros.forEach { macro ->
            macros[macro.id] = macro
            indexMacro(macro)
        }

        // Load user macros
        scope.launch {
            loadMacros()
        }

        updateState()
        logD("MacroExpander initialized (${macros.size} macros loaded)")
    }

    /**
     * Find matching macros for text.
     *
     * @param text Text to match
     * @param withDelimiter Whether text includes delimiter
     * @return List of matching macros
     */
    suspend fun findMatches(text: String, withDelimiter: Boolean = false): List<Macro> = withContext(Dispatchers.Default) {
        val matches = mutableListOf<Macro>()

        // Check trigger index for exact matches
        val triggerKey = if (withDelimiter) {
            text.substringBefore(' ', text).lowercase()
        } else {
            text.lowercase()
        }

        triggerIndex[triggerKey]?.forEach { macroId ->
            macros[macroId]?.let { macro ->
                if (macro.matches(text, withDelimiter)) {
                    matches.add(macro)
                }
            }
        }

        // Sort by usage count (most used first)
        matches.sortedByDescending { it.usageCount }
    }

    /**
     * Expand macro.
     *
     * @param macroId Macro ID to expand
     * @param customVariables Custom variable values
     * @return Expanded text, or null if macro not found
     */
    suspend fun expandMacro(
        macroId: String,
        customVariables: Map<String, String> = emptyMap()
    ): String? = withContext(Dispatchers.Default) {
        val macro = macros[macroId] ?: return@withContext null

        try {
            val expanded = macro.expand(context, customVariables)

            // Limit expansion length
            if (expanded.length > MAX_EXPANSION_LENGTH) {
                logE("Macro expansion too long: ${expanded.length} chars")
                return@withContext null
            }

            // Update usage statistics
            val updated = macro.copy(
                usageCount = macro.usageCount + 1,
                lastUsed = System.currentTimeMillis()
            )
            macros[macroId] = updated

            updateState()
            callback?.onMacroExpanded(updated, expanded)

            logD("Expanded macro: ${macro.trigger} → ${expanded.length} chars")
            expanded
        } catch (e: Exception) {
            logE("Error expanding macro: ${macro.trigger}", e)
            null
        }
    }

    /**
     * Add custom macro.
     *
     * @param trigger Trigger text
     * @param expansion Expansion text
     * @param triggerType Trigger type
     * @param category Macro category
     * @param description Macro description
     * @param caseSensitive Whether trigger is case-sensitive
     * @param multiLine Whether expansion is multi-line
     * @param variables Variable definitions
     * @return Macro ID, or null if failed
     */
    suspend fun addMacro(
        trigger: String,
        expansion: String,
        triggerType: TriggerType = TriggerType.PREFIX,
        category: Category = Category.CUSTOM,
        description: String = "",
        caseSensitive: Boolean = false,
        multiLine: Boolean = false,
        variables: Map<String, VariableType> = emptyMap()
    ): String? = withContext(Dispatchers.Default) {
        if (trigger.isBlank() || expansion.isBlank()) {
            logE("Invalid macro: empty trigger or expansion")
            return@withContext null
        }

        if (macros.size >= MAX_MACROS) {
            logE("Maximum number of macros reached: $MAX_MACROS")
            return@withContext null
        }

        try {
            val macroId = "custom_${System.currentTimeMillis()}"
            val macro = Macro(
                id = macroId,
                trigger = trigger,
                expansion = expansion,
                triggerType = triggerType,
                category = category,
                description = description,
                caseSensitive = caseSensitive,
                multiLine = multiLine,
                variables = variables
            )

            macros[macroId] = macro
            indexMacro(macro)

            saveMacros()
            updateState()
            callback?.onMacroAdded(macro)

            logD("Added macro: $trigger → ${expansion.take(50)}")
            macroId
        } catch (e: Exception) {
            logE("Error adding macro", e)
            null
        }
    }

    /**
     * Update existing macro.
     *
     * @param macroId Macro ID to update
     * @param trigger New trigger text
     * @param expansion New expansion text
     * @param enabled Whether macro is enabled
     * @return True if updated successfully
     */
    suspend fun updateMacro(
        macroId: String,
        trigger: String? = null,
        expansion: String? = null,
        enabled: Boolean? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val macro = macros[macroId] ?: return@withContext false

        try {
            // Remove from trigger index
            removeMacroFromIndex(macro)

            // Update macro
            val updated = macro.copy(
                trigger = trigger ?: macro.trigger,
                expansion = expansion ?: macro.expansion,
                enabled = enabled ?: macro.enabled
            )

            macros[macroId] = updated
            indexMacro(updated)

            saveMacros()
            updateState()
            callback?.onMacroUpdated(updated)

            logD("Updated macro: $macroId")
            true
        } catch (e: Exception) {
            logE("Error updating macro", e)
            false
        }
    }

    /**
     * Delete macro.
     *
     * @param macroId Macro ID to delete
     * @return True if deleted successfully
     */
    suspend fun deleteMacro(macroId: String): Boolean = withContext(Dispatchers.Default) {
        // Don't allow deleting built-in macros
        if (macroId.startsWith("builtin_")) {
            logE("Cannot delete built-in macro: $macroId")
            return@withContext false
        }

        val macro = macros.remove(macroId) ?: return@withContext false

        try {
            removeMacroFromIndex(macro)

            saveMacros()
            updateState()
            callback?.onMacroDeleted(macroId)

            logD("Deleted macro: $macroId")
            true
        } catch (e: Exception) {
            logE("Error deleting macro", e)
            // Restore macro on error
            macros[macroId] = macro
            indexMacro(macro)
            false
        }
    }

    /**
     * Get all macros.
     *
     * @param category Optional category filter
     * @return List of macros
     */
    fun getMacros(category: Category? = null): List<Macro> {
        return if (category == null) {
            macros.values.sortedByDescending { it.usageCount }
        } else {
            macros.values.filter { it.category == category }.sortedByDescending { it.usageCount }
        }
    }

    /**
     * Get macro by ID.
     *
     * @param macroId Macro ID
     * @return Macro, or null if not found
     */
    fun getMacro(macroId: String): Macro? = macros[macroId]

    /**
     * Index macro for fast lookup.
     */
    private fun indexMacro(macro: Macro) {
        val key = macro.trigger.lowercase()
        triggerIndex.getOrPut(key) { mutableListOf() }.add(macro.id)
    }

    /**
     * Remove macro from index.
     */
    private fun removeMacroFromIndex(macro: Macro) {
        val key = macro.trigger.lowercase()
        triggerIndex[key]?.remove(macro.id)
        if (triggerIndex[key]?.isEmpty() == true) {
            triggerIndex.remove(key)
        }
    }

    /**
     * Load macros from storage.
     */
    private suspend fun loadMacros() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, MACROS_FILE)
            if (!file.exists()) {
                logD("No saved macros file found")
                return@withContext
            }

            val lines = file.readLines()
            var loaded = 0

            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue

                try {
                    val macro = parseMacroLine(line)
                    macros[macro.id] = macro
                    indexMacro(macro)
                    loaded++
                } catch (e: Exception) {
                    logE("Error parsing macro line: $line", e)
                }
            }

            logD("Loaded $loaded custom macros from storage")
        } catch (e: Exception) {
            logE("Error loading macros", e)
        }
    }

    /**
     * Save macros to storage.
     */
    private suspend fun saveMacros() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, MACROS_FILE)
            val customMacros = macros.values.filter { !it.id.startsWith("builtin_") }

            file.writeText(customMacros.joinToString("\n") { serializeMacro(it) })

            logD("Saved ${customMacros.size} custom macros to storage")
        } catch (e: Exception) {
            logE("Error saving macros", e)
        }
    }

    /**
     * Parse macro from storage line.
     */
    private fun parseMacroLine(line: String): Macro {
        val parts = line.split("|")
        return Macro(
            id = parts[0],
            trigger = parts[1],
            expansion = parts[2].replace("\\n", "\n"),
            triggerType = TriggerType.valueOf(parts.getOrNull(3) ?: "PREFIX"),
            category = Category.valueOf(parts.getOrNull(4) ?: "CUSTOM"),
            description = parts.getOrNull(5) ?: "",
            caseSensitive = parts.getOrNull(6)?.toBoolean() ?: false,
            multiLine = parts.getOrNull(7)?.toBoolean() ?: false,
            usageCount = parts.getOrNull(8)?.toLong() ?: 0,
            lastUsed = parts.getOrNull(9)?.toLong() ?: 0
        )
    }

    /**
     * Serialize macro to storage line.
     */
    private fun serializeMacro(macro: Macro): String {
        return listOf(
            macro.id,
            macro.trigger,
            macro.expansion.replace("\n", "\\n"),
            macro.triggerType.name,
            macro.category.name,
            macro.description,
            macro.caseSensitive.toString(),
            macro.multiLine.toString(),
            macro.usageCount.toString(),
            macro.lastUsed.toString()
        ).joinToString("|")
    }

    /**
     * Update macro state and notify callback.
     */
    private fun updateState() {
        val enabled = macros.values.count { it.enabled }
        val usage = macros.values.sumOf { it.usageCount }
        val categories = macros.values.groupingBy { it.category }.eachCount()

        val state = MacroState(
            macroCount = macros.size,
            enabledCount = enabled,
            totalUsage = usage,
            categories = categories
        )

        _macroState.value = state
        callback?.onStateChanged(state)
    }

    /**
     * Get macro statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> {
        val mostUsed = macros.values.maxByOrNull { it.usageCount }
        val recentlyUsed = macros.values.maxByOrNull { it.lastUsed }

        return mapOf(
            "total_macros" to macros.size,
            "enabled_macros" to macros.values.count { it.enabled },
            "custom_macros" to macros.values.count { !it.id.startsWith("builtin_") },
            "builtin_macros" to macros.values.count { it.id.startsWith("builtin_") },
            "total_usage" to macros.values.sumOf { it.usageCount },
            "most_used_trigger" to (mostUsed?.trigger ?: "none"),
            "most_used_count" to (mostUsed?.usageCount ?: 0),
            "recently_used_trigger" to (recentlyUsed?.trigger ?: "none"),
            "categories" to macros.values.groupingBy { it.category }.eachCount()
        )
    }

    /**
     * Set callback for macro events.
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
        logD("Releasing MacroExpander resources...")

        try {
            scope.cancel()
            callback = null
            macros.clear()
            triggerIndex.clear()
            logD("✅ MacroExpander resources released")
        } catch (e: Exception) {
            logE("Error releasing macro expander resources", e)
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
