package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Text expansion system for keyboard shortcuts and macros.
 *
 * Features:
 * - Shortcut expansion (brb → be right back)
 * - Multi-line text snippets
 * - Variable substitution (date, time, clipboard)
 * - Case-aware expansion
 * - Import/export functionality
 * - Persistent storage
 * - Conflict detection
 *
 * Addresses Bug #319: TextExpander missing (HIGH)
 */
class TextExpander(private val context: Context) {

    companion object {
        private const val TAG = "TextExpander"
        private const val PREFS_NAME = "text_expander"
        private const val SHORTCUTS_KEY = "shortcuts"

        // Built-in variables
        private const val VAR_DATE = "{date}"
        private const val VAR_TIME = "{time}"
        private const val VAR_DATETIME = "{datetime}"
        private const val VAR_CLIPBOARD = "{clipboard}"
        private const val VAR_CURSOR = "{cursor}"

        // Default shortcuts (examples)
        private val DEFAULT_SHORTCUTS = mapOf(
            "brb" to "be right back",
            "omw" to "on my way",
            "ty" to "thank you",
            "np" to "no problem",
            "imo" to "in my opinion",
            "fyi" to "for your information",
            "asap" to "as soon as possible",
            "btw" to "by the way",
            "idk" to "I don't know",
            "iirc" to "if I recall correctly",
            "tbd" to "to be determined",
            "wip" to "work in progress",
            "eta" to "estimated time of arrival"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val shortcuts = mutableMapOf<String, Shortcut>()
    private var enabled = true
    private var caseSensitive = false
    private var expandOnSpace = true
    private var expandOnPunctuation = true

    /**
     * Data class representing a text expansion shortcut
     */
    data class Shortcut(
        val trigger: String,
        val expansion: String,
        val caseSensitive: Boolean = false,
        val enabled: Boolean = true,
        val description: String = ""
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("trigger", trigger)
                put("expansion", expansion)
                put("caseSensitive", caseSensitive)
                put("enabled", enabled)
                put("description", description)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): Shortcut {
                return Shortcut(
                    trigger = json.getString("trigger"),
                    expansion = json.getString("expansion"),
                    caseSensitive = json.optBoolean("caseSensitive", false),
                    enabled = json.optBoolean("enabled", true),
                    description = json.optString("description", "")
                )
            }
        }
    }

    init {
        loadShortcuts()
        if (shortcuts.isEmpty()) {
            loadDefaultShortcuts()
        }
    }

    /**
     * Check if text should trigger an expansion
     * Returns the expanded text if matched, null otherwise
     */
    fun checkExpansion(text: String, triggerChar: Char): String? {
        if (!enabled) return null

        // Only expand on configured triggers
        if (triggerChar == ' ' && !expandOnSpace) return null
        if (isPunctuation(triggerChar) && !expandOnPunctuation) return null

        // Extract the last word
        val words = text.trim().split(Regex("\\s+"))
        if (words.isEmpty()) return null

        val lastWord = words.last()
        val shortcut = findShortcut(lastWord) ?: return null

        if (!shortcut.enabled) return null

        // Expand with variable substitution
        return expandVariables(shortcut.expansion)
    }

    /**
     * Process text from InputConnection to detect and expand shortcuts
     */
    fun processText(ic: InputConnection?, triggerChar: Char): Boolean {
        if (ic == null || !enabled) return false

        // Get text before cursor
        val beforeCursor = ic.getTextBeforeCursor(100, 0)?.toString() ?: return false
        if (beforeCursor.isEmpty()) return false

        // Check for expansion
        val expansion = checkExpansion(beforeCursor, triggerChar) ?: return false

        // Find the trigger word to delete
        val words = beforeCursor.trim().split(Regex("\\s+"))
        if (words.isEmpty()) return false

        val triggerWord = words.last()

        // Delete the trigger word
        ic.deleteSurroundingText(triggerWord.length, 0)

        // Handle cursor position variable
        val cursorIndex = expansion.indexOf(VAR_CURSOR)
        if (cursorIndex >= 0) {
            // Insert text before cursor variable
            val beforeCursorText = expansion.substring(0, cursorIndex)
            val afterCursorText = expansion.substring(cursorIndex + VAR_CURSOR.length)

            ic.commitText(beforeCursorText + afterCursorText, beforeCursorText.length + 1)
        } else {
            // Normal expansion - insert text
            ic.commitText(expansion, 1)
        }

        return true
    }

    /**
     * Find shortcut by trigger (case-aware)
     */
    private fun findShortcut(trigger: String): Shortcut? {
        // Try exact match first
        var shortcut = shortcuts[trigger]
        if (shortcut != null) return shortcut

        // Try case-insensitive if not case sensitive mode
        if (!caseSensitive) {
            val lowerTrigger = trigger.lowercase()
            shortcut = shortcuts.values.find {
                !it.caseSensitive && it.trigger.lowercase() == lowerTrigger
            }
        }

        return shortcut
    }

    /**
     * Expand variables in text
     */
    private fun expandVariables(text: String): String {
        var result = text

        // Date/time variables
        val now = Date()
        if (result.contains(VAR_DATE)) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            result = result.replace(VAR_DATE, dateFormat.format(now))
        }
        if (result.contains(VAR_TIME)) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            result = result.replace(VAR_TIME, timeFormat.format(now))
        }
        if (result.contains(VAR_DATETIME)) {
            val datetimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            result = result.replace(VAR_DATETIME, datetimeFormat.format(now))
        }

        // Clipboard variable
        if (result.contains(VAR_CLIPBOARD)) {
            val clipboard = getClipboardText()
            result = result.replace(VAR_CLIPBOARD, clipboard)
        }

        // Cursor position is handled separately in processText()

        return result
    }

    /**
     * Get text from clipboard
     */
    private fun getClipboardText(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Check if character is punctuation that triggers expansion
     */
    private fun isPunctuation(char: Char): Boolean {
        return char in ".,!?;:"
    }

    /**
     * Add a new shortcut
     */
    fun addShortcut(trigger: String, expansion: String, description: String = ""): Boolean {
        if (trigger.isBlank() || expansion.isBlank()) return false

        // Check for conflicts
        if (shortcuts.containsKey(trigger)) {
            return false // Shortcut already exists
        }

        shortcuts[trigger] = Shortcut(trigger, expansion, caseSensitive, true, description)
        saveShortcuts()
        return true
    }

    /**
     * Update an existing shortcut
     */
    fun updateShortcut(trigger: String, expansion: String, description: String = ""): Boolean {
        if (!shortcuts.containsKey(trigger)) return false

        val existing = shortcuts[trigger]!!
        shortcuts[trigger] = existing.copy(expansion = expansion, description = description)
        saveShortcuts()
        return true
    }

    /**
     * Remove a shortcut
     */
    fun removeShortcut(trigger: String): Boolean {
        if (!shortcuts.containsKey(trigger)) return false

        shortcuts.remove(trigger)
        saveShortcuts()
        return true
    }

    /**
     * Enable/disable a shortcut
     */
    fun setShortcutEnabled(trigger: String, enabled: Boolean): Boolean {
        val shortcut = shortcuts[trigger] ?: return false

        shortcuts[trigger] = shortcut.copy(enabled = enabled)
        saveShortcuts()
        return true
    }

    /**
     * Get all shortcuts
     */
    fun getAllShortcuts(): List<Shortcut> {
        return shortcuts.values.toList()
    }

    /**
     * Get enabled shortcuts
     */
    fun getEnabledShortcuts(): List<Shortcut> {
        return shortcuts.values.filter { it.enabled }
    }

    /**
     * Clear all shortcuts
     */
    fun clearAllShortcuts() {
        shortcuts.clear()
        saveShortcuts()
    }

    /**
     * Load default shortcuts
     */
    fun loadDefaultShortcuts() {
        DEFAULT_SHORTCUTS.forEach { (trigger, expansion) ->
            shortcuts[trigger] = Shortcut(trigger, expansion)
        }
        saveShortcuts()
    }

    /**
     * Export shortcuts to JSON
     */
    fun exportToJson(): String {
        val jsonArray = JSONArray()
        shortcuts.values.forEach { shortcut ->
            jsonArray.put(shortcut.toJson())
        }
        return jsonArray.toString(2)
    }

    /**
     * Import shortcuts from JSON
     */
    fun importFromJson(json: String, merge: Boolean = false): Boolean {
        return try {
            if (!merge) {
                shortcuts.clear()
            }

            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val shortcut = Shortcut.fromJson(jsonArray.getJSONObject(i))
                shortcuts[shortcut.trigger] = shortcut
            }

            saveShortcuts()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to import shortcuts", e)
            false
        }
    }

    /**
     * Save shortcuts to preferences
     */
    private fun saveShortcuts() {
        val json = exportToJson()
        prefs.edit().putString(SHORTCUTS_KEY, json).apply()
    }

    /**
     * Load shortcuts from preferences
     */
    private fun loadShortcuts() {
        val json = prefs.getString(SHORTCUTS_KEY, null) ?: return
        importFromJson(json, merge = false)
    }

    /**
     * Enable/disable text expander
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Check if text expander is enabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set case sensitivity
     */
    fun setCaseSensitive(sensitive: Boolean) {
        this.caseSensitive = sensitive
    }

    /**
     * Check if case sensitive
     */
    fun isCaseSensitive(): Boolean = caseSensitive

    /**
     * Set expand on space
     */
    fun setExpandOnSpace(expand: Boolean) {
        this.expandOnSpace = expand
    }

    /**
     * Set expand on punctuation
     */
    fun setExpandOnPunctuation(expand: Boolean) {
        this.expandOnPunctuation = expand
    }

    /**
     * Get statistics
     */
    fun getStats(): String {
        val total = shortcuts.size
        val enabled = shortcuts.values.count { it.enabled }
        val disabled = total - enabled

        return """
            Text Expander Statistics:
            - Total shortcuts: $total
            - Enabled: $enabled
            - Disabled: $disabled
            - Case sensitive: $caseSensitive
            - Expand on space: $expandOnSpace
            - Expand on punctuation: $expandOnPunctuation
        """.trimIndent()
    }

    /**
     * Search shortcuts by trigger or expansion
     */
    fun searchShortcuts(query: String): List<Shortcut> {
        val lowerQuery = query.lowercase()
        return shortcuts.values.filter {
            it.trigger.lowercase().contains(lowerQuery) ||
            it.expansion.lowercase().contains(lowerQuery) ||
            it.description.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Check for conflicts with new trigger
     */
    fun hasConflict(trigger: String): Boolean {
        return shortcuts.containsKey(trigger)
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Nothing to cleanup currently
    }
}
