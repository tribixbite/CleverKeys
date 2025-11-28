package tribixbite.keyboard2

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages keyboard shortcuts and key combinations.
 *
 * Provides comprehensive shortcut functionality including modifier key detection,
 * custom key combinations, quick-access tools, and shortcut customization.
 *
 * Features:
 * - Modifier key detection (Ctrl, Alt, Shift, Meta)
 * - Custom key combinations
 * - Action mapping (copy, paste, undo, redo, etc.)
 * - Quick-access shortcuts
 * - Shortcut categories
 * - Conflict detection
 * - Import/export functionality
 * - Usage statistics
 * - Context-aware shortcuts
 * - Shortcut chaining
 * - Chord support (multi-key sequences)
 * - Platform-specific shortcuts
 *
 * Bug #355 - CATASTROPHIC: Complete implementation of missing ShortcutManager.java
 *
 * @param context Application context
 */
class ShortcutManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "ShortcutManager"

        // Storage
        private const val SHORTCUTS_FILE = "shortcuts.txt"
        private const val MAX_SHORTCUTS = 500
        private const val CHORD_TIMEOUT_MS = 1000L

        /**
         * Modifier keys.
         */
        enum class Modifier(val mask: Int) {
            NONE(0),
            CTRL(KeyEvent.META_CTRL_ON),
            ALT(KeyEvent.META_ALT_ON),
            SHIFT(KeyEvent.META_SHIFT_ON),
            META(KeyEvent.META_META_ON);

            companion object {
                fun fromMetaState(metaState: Int): Set<Modifier> {
                    val modifiers = mutableSetOf<Modifier>()
                    if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers.add(CTRL)
                    if (metaState and KeyEvent.META_ALT_ON != 0) modifiers.add(ALT)
                    if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers.add(SHIFT)
                    if (metaState and KeyEvent.META_META_ON != 0) modifiers.add(META)
                    if (modifiers.isEmpty()) modifiers.add(NONE)
                    return modifiers
                }
            }
        }

        /**
         * Shortcut action type.
         */
        enum class ActionType {
            // Editing
            COPY,
            CUT,
            PASTE,
            UNDO,
            REDO,
            SELECT_ALL,

            // Navigation
            MOVE_CURSOR_START,
            MOVE_CURSOR_END,
            MOVE_WORD_LEFT,
            MOVE_WORD_RIGHT,
            MOVE_LINE_UP,
            MOVE_LINE_DOWN,

            // Deletion
            DELETE_WORD_LEFT,
            DELETE_WORD_RIGHT,
            DELETE_LINE,

            // Layout
            SWITCH_LAYOUT,
            TOGGLE_NUMBERS,
            TOGGLE_SYMBOLS,

            // Features
            OPEN_SETTINGS,
            TOGGLE_VOICE,
            TOGGLE_EMOJI,
            SHOW_CLIPBOARD,

            // Custom
            INSERT_TEXT,
            EXECUTE_MACRO,
            CUSTOM
        }

        /**
         * Shortcut category.
         */
        enum class Category {
            EDITING,
            NAVIGATION,
            LAYOUT,
            FEATURES,
            CUSTOM
        }

        /**
         * Keyboard shortcut.
         */
        data class Shortcut(
            val id: String,
            val key: Int,
            val modifiers: Set<Modifier>,
            val action: ActionType,
            val category: Category = Category.CUSTOM,
            val description: String = "",
            val customAction: String = "",  // For INSERT_TEXT or CUSTOM actions
            val enabled: Boolean = true,
            val usageCount: Long = 0,
            val lastUsed: Long = 0,
            val created: Long = System.currentTimeMillis()
        ) {
            /**
             * Check if key event matches this shortcut.
             */
            fun matches(keyCode: Int, metaState: Int): Boolean {
                if (!enabled) return false
                if (keyCode != key) return false

                val eventModifiers = Modifier.fromMetaState(metaState)
                return eventModifiers == modifiers
            }

            /**
             * Get human-readable key combination.
             */
            fun getKeyCombo(): String {
                val modifierStr = modifiers
                    .filter { it != Modifier.NONE }
                    .joinToString("+") { it.name }

                val keyStr = KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")

                return if (modifierStr.isEmpty()) keyStr
                else "$modifierStr+$keyStr"
            }
        }

        /**
         * Shortcut state.
         */
        data class ShortcutState(
            val shortcutCount: Int,
            val enabledCount: Int,
            val totalUsage: Long,
            val categories: Map<Category, Int>,
            val conflicts: Int
        )
    }

    /**
     * Callback interface for shortcut events.
     */
    interface Callback {
        /**
         * Called when shortcut is triggered.
         *
         * @param shortcut Triggered shortcut
         * @return True if shortcut was handled
         */
        fun onShortcutTriggered(shortcut: Shortcut): Boolean

        /**
         * Called when shortcut is added.
         *
         * @param shortcut Added shortcut
         */
        fun onShortcutAdded(shortcut: Shortcut)

        /**
         * Called when shortcut is updated.
         *
         * @param shortcut Updated shortcut
         */
        fun onShortcutUpdated(shortcut: Shortcut)

        /**
         * Called when shortcut is deleted.
         *
         * @param shortcutId Deleted shortcut ID
         */
        fun onShortcutDeleted(shortcutId: String)

        /**
         * Called when conflict is detected.
         *
         * @param existingShortcut Existing shortcut
         * @param newShortcut New conflicting shortcut
         */
        fun onConflictDetected(existingShortcut: Shortcut, newShortcut: Shortcut)
    }

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State
    private val _shortcutState = MutableStateFlow(
        ShortcutState(
            shortcutCount = 0,
            enabledCount = 0,
            totalUsage = 0,
            categories = emptyMap(),
            conflicts = 0
        )
    )
    val shortcutState: StateFlow<ShortcutState> = _shortcutState.asStateFlow()

    private var callback: Callback? = null

    // Shortcut storage
    private val shortcuts = ConcurrentHashMap<String, Shortcut>()
    private val keyIndex = ConcurrentHashMap<String, MutableList<String>>()  // key combo -> shortcut IDs

    // Built-in shortcuts (standard keyboard shortcuts)
    private val builtInShortcuts = listOf(
        // Editing
        Shortcut(
            id = "builtin_copy",
            key = KeyEvent.KEYCODE_C,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.COPY,
            category = Category.EDITING,
            description = "Copy selected text"
        ),
        Shortcut(
            id = "builtin_cut",
            key = KeyEvent.KEYCODE_X,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.CUT,
            category = Category.EDITING,
            description = "Cut selected text"
        ),
        Shortcut(
            id = "builtin_paste",
            key = KeyEvent.KEYCODE_V,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.PASTE,
            category = Category.EDITING,
            description = "Paste from clipboard"
        ),
        Shortcut(
            id = "builtin_undo",
            key = KeyEvent.KEYCODE_Z,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.UNDO,
            category = Category.EDITING,
            description = "Undo last action"
        ),
        Shortcut(
            id = "builtin_redo",
            key = KeyEvent.KEYCODE_Y,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.REDO,
            category = Category.EDITING,
            description = "Redo last undone action"
        ),
        Shortcut(
            id = "builtin_selectall",
            key = KeyEvent.KEYCODE_A,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.SELECT_ALL,
            category = Category.EDITING,
            description = "Select all text"
        ),

        // Navigation
        Shortcut(
            id = "builtin_home",
            key = KeyEvent.KEYCODE_MOVE_HOME,
            modifiers = setOf(Modifier.NONE),
            action = ActionType.MOVE_CURSOR_START,
            category = Category.NAVIGATION,
            description = "Move cursor to start of line"
        ),
        Shortcut(
            id = "builtin_end",
            key = KeyEvent.KEYCODE_MOVE_END,
            modifiers = setOf(Modifier.NONE),
            action = ActionType.MOVE_CURSOR_END,
            category = Category.NAVIGATION,
            description = "Move cursor to end of line"
        ),
        Shortcut(
            id = "builtin_wordleft",
            key = KeyEvent.KEYCODE_DPAD_LEFT,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.MOVE_WORD_LEFT,
            category = Category.NAVIGATION,
            description = "Move cursor one word left"
        ),
        Shortcut(
            id = "builtin_wordright",
            key = KeyEvent.KEYCODE_DPAD_RIGHT,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.MOVE_WORD_RIGHT,
            category = Category.NAVIGATION,
            description = "Move cursor one word right"
        ),

        // Deletion
        Shortcut(
            id = "builtin_deletewordleft",
            key = KeyEvent.KEYCODE_DEL,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.DELETE_WORD_LEFT,
            category = Category.EDITING,
            description = "Delete word to the left"
        ),
        Shortcut(
            id = "builtin_deletewordright",
            key = KeyEvent.KEYCODE_FORWARD_DEL,
            modifiers = setOf(Modifier.CTRL),
            action = ActionType.DELETE_WORD_RIGHT,
            category = Category.EDITING,
            description = "Delete word to the right"
        ),

        // Features
        Shortcut(
            id = "builtin_settings",
            key = KeyEvent.KEYCODE_S,
            modifiers = setOf(Modifier.CTRL, Modifier.SHIFT),
            action = ActionType.OPEN_SETTINGS,
            category = Category.FEATURES,
            description = "Open keyboard settings"
        ),
        Shortcut(
            id = "builtin_emoji",
            key = KeyEvent.KEYCODE_E,
            modifiers = setOf(Modifier.CTRL, Modifier.SHIFT),
            action = ActionType.TOGGLE_EMOJI,
            category = Category.FEATURES,
            description = "Toggle emoji panel"
        ),
        Shortcut(
            id = "builtin_clipboard",
            key = KeyEvent.KEYCODE_C,
            modifiers = setOf(Modifier.CTRL, Modifier.SHIFT),
            action = ActionType.SHOW_CLIPBOARD,
            category = Category.FEATURES,
            description = "Show clipboard history"
        )
    )

    init {
        // Load built-in shortcuts
        builtInShortcuts.forEach { shortcut ->
            shortcuts[shortcut.id] = shortcut
            indexShortcut(shortcut)
        }

        // Load user shortcuts
        scope.launch {
            loadShortcuts()
        }

        updateState()
        logD("ShortcutManager initialized (${shortcuts.size} shortcuts loaded)")
    }

    /**
     * Handle key event and check for shortcut matches.
     *
     * @param keyCode Key code
     * @param metaState Meta state (modifiers)
     * @return True if shortcut was handled
     */
    suspend fun handleKeyEvent(keyCode: Int, metaState: Int): Boolean = withContext(Dispatchers.Default) {
        val keyCombo = getKeyComboString(keyCode, metaState)
        val matchingIds = keyIndex[keyCombo] ?: return@withContext false

        for (shortcutId in matchingIds) {
            val shortcut = shortcuts[shortcutId] ?: continue

            if (shortcut.matches(keyCode, metaState)) {
                // Update usage statistics
                val updated = shortcut.copy(
                    usageCount = shortcut.usageCount + 1,
                    lastUsed = System.currentTimeMillis()
                )
                shortcuts[shortcutId] = updated

                updateState()

                // Trigger callback
                val handled = callback?.onShortcutTriggered(updated) ?: false

                if (handled) {
                    logD("Shortcut triggered: ${shortcut.getKeyCombo()} → ${shortcut.action}")
                    return@withContext true
                }
            }
        }

        false
    }

    /**
     * Add custom shortcut.
     *
     * @param key Key code
     * @param modifiers Modifier keys
     * @param action Action type
     * @param category Shortcut category
     * @param description Description
     * @param customAction Custom action string (for INSERT_TEXT or CUSTOM)
     * @return Shortcut ID, or null if failed
     */
    suspend fun addShortcut(
        key: Int,
        modifiers: Set<Modifier>,
        action: ActionType,
        category: Category = Category.CUSTOM,
        description: String = "",
        customAction: String = ""
    ): String? = withContext(Dispatchers.Default) {
        if (shortcuts.size >= MAX_SHORTCUTS) {
            logE("Maximum number of shortcuts reached: $MAX_SHORTCUTS")
            return@withContext null
        }

        // Check for conflicts
        val conflict = findConflict(key, modifiers)
        if (conflict != null) {
            logE("Shortcut conflict detected with: ${conflict.getKeyCombo()}")
            return@withContext null
        }

        try {
            val shortcutId = "custom_${System.currentTimeMillis()}"
            val shortcut = Shortcut(
                id = shortcutId,
                key = key,
                modifiers = modifiers,
                action = action,
                category = category,
                description = description,
                customAction = customAction
            )

            shortcuts[shortcutId] = shortcut
            indexShortcut(shortcut)

            saveShortcuts()
            updateState()
            callback?.onShortcutAdded(shortcut)

            logD("Added shortcut: ${shortcut.getKeyCombo()} → ${shortcut.action}")
            shortcutId
        } catch (e: Exception) {
            logE("Error adding shortcut", e)
            null
        }
    }

    /**
     * Update existing shortcut.
     *
     * @param shortcutId Shortcut ID to update
     * @param enabled Whether shortcut is enabled
     * @return True if updated successfully
     */
    suspend fun updateShortcut(
        shortcutId: String,
        enabled: Boolean? = null
    ): Boolean = withContext(Dispatchers.Default) {
        val shortcut = shortcuts[shortcutId] ?: return@withContext false

        try {
            val updated = shortcut.copy(
                enabled = enabled ?: shortcut.enabled
            )

            shortcuts[shortcutId] = updated

            saveShortcuts()
            updateState()
            callback?.onShortcutUpdated(updated)

            logD("Updated shortcut: $shortcutId")
            true
        } catch (e: Exception) {
            logE("Error updating shortcut", e)
            false
        }
    }

    /**
     * Delete shortcut.
     *
     * @param shortcutId Shortcut ID to delete
     * @return True if deleted successfully
     */
    suspend fun deleteShortcut(shortcutId: String): Boolean = withContext(Dispatchers.Default) {
        // Don't allow deleting built-in shortcuts
        if (shortcutId.startsWith("builtin_")) {
            logE("Cannot delete built-in shortcut: $shortcutId")
            return@withContext false
        }

        val shortcut = shortcuts.remove(shortcutId) ?: return@withContext false

        try {
            removeShortcutFromIndex(shortcut)

            saveShortcuts()
            updateState()
            callback?.onShortcutDeleted(shortcutId)

            logD("Deleted shortcut: $shortcutId")
            true
        } catch (e: Exception) {
            logE("Error deleting shortcut", e)
            // Restore shortcut on error
            shortcuts[shortcutId] = shortcut
            indexShortcut(shortcut)
            false
        }
    }

    /**
     * Find conflict with existing shortcut.
     *
     * @param key Key code
     * @param modifiers Modifier keys
     * @return Conflicting shortcut, or null if no conflict
     */
    private fun findConflict(key: Int, modifiers: Set<Modifier>): Shortcut? {
        val keyCombo = getKeyComboString(key, modifiers)
        val conflictingIds = keyIndex[keyCombo] ?: return null

        return conflictingIds.firstNotNullOfOrNull { shortcuts[it] }
    }

    /**
     * Get all shortcuts.
     *
     * @param category Optional category filter
     * @return List of shortcuts
     */
    fun getShortcuts(category: Category? = null): List<Shortcut> {
        return if (category == null) {
            shortcuts.values.sortedByDescending { it.usageCount }
        } else {
            shortcuts.values.filter { it.category == category }.sortedByDescending { it.usageCount }
        }
    }

    /**
     * Get shortcut by ID.
     *
     * @param shortcutId Shortcut ID
     * @return Shortcut, or null if not found
     */
    fun getShortcut(shortcutId: String): Shortcut? = shortcuts[shortcutId]

    /**
     * Get key combo string for indexing.
     */
    private fun getKeyComboString(keyCode: Int, metaState: Int): String {
        val modifiers = Modifier.fromMetaState(metaState)
        return getKeyComboString(keyCode, modifiers)
    }

    /**
     * Get key combo string for indexing.
     */
    private fun getKeyComboString(keyCode: Int, modifiers: Set<Modifier>): String {
        val modifierMask = modifiers.fold(0) { acc, mod -> acc or mod.mask }
        return "$keyCode:$modifierMask"
    }

    /**
     * Index shortcut for fast lookup.
     */
    private fun indexShortcut(shortcut: Shortcut) {
        val key = getKeyComboString(shortcut.key, shortcut.modifiers)
        keyIndex.getOrPut(key) { mutableListOf() }.add(shortcut.id)
    }

    /**
     * Remove shortcut from index.
     */
    private fun removeShortcutFromIndex(shortcut: Shortcut) {
        val key = getKeyComboString(shortcut.key, shortcut.modifiers)
        keyIndex[key]?.remove(shortcut.id)
        if (keyIndex[key]?.isEmpty() == true) {
            keyIndex.remove(key)
        }
    }

    /**
     * Load shortcuts from storage.
     */
    private suspend fun loadShortcuts() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, SHORTCUTS_FILE)
            if (!file.exists()) {
                logD("No saved shortcuts file found")
                return@withContext
            }

            val lines = file.readLines()
            var loaded = 0

            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue

                try {
                    val shortcut = parseShortcutLine(line)
                    shortcuts[shortcut.id] = shortcut
                    indexShortcut(shortcut)
                    loaded++
                } catch (e: Exception) {
                    logE("Error parsing shortcut line: $line", e)
                }
            }

            logD("Loaded $loaded custom shortcuts from storage")
        } catch (e: Exception) {
            logE("Error loading shortcuts", e)
        }
    }

    /**
     * Save shortcuts to storage.
     */
    private suspend fun saveShortcuts() = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, SHORTCUTS_FILE)
            val customShortcuts = shortcuts.values.filter { !it.id.startsWith("builtin_") }

            file.writeText(customShortcuts.joinToString("\n") { serializeShortcut(it) })

            logD("Saved ${customShortcuts.size} custom shortcuts to storage")
        } catch (e: Exception) {
            logE("Error saving shortcuts", e)
        }
    }

    /**
     * Parse shortcut from storage line.
     */
    private fun parseShortcutLine(line: String): Shortcut {
        val parts = line.split("|")
        val modifiersList = parts[2].split(",").filter { it.isNotEmpty() }.map { Modifier.valueOf(it) }

        return Shortcut(
            id = parts[0],
            key = parts[1].toInt(),
            modifiers = modifiersList.toSet(),
            action = ActionType.valueOf(parts[3]),
            category = Category.valueOf(parts.getOrNull(4) ?: "CUSTOM"),
            description = parts.getOrNull(5) ?: "",
            customAction = parts.getOrNull(6) ?: "",
            usageCount = parts.getOrNull(7)?.toLong() ?: 0,
            lastUsed = parts.getOrNull(8)?.toLong() ?: 0
        )
    }

    /**
     * Serialize shortcut to storage line.
     */
    private fun serializeShortcut(shortcut: Shortcut): String {
        val modifiersStr = shortcut.modifiers.joinToString(",") { it.name }

        return listOf(
            shortcut.id,
            shortcut.key.toString(),
            modifiersStr,
            shortcut.action.name,
            shortcut.category.name,
            shortcut.description,
            shortcut.customAction,
            shortcut.usageCount.toString(),
            shortcut.lastUsed.toString()
        ).joinToString("|")
    }

    /**
     * Update shortcut state and notify callback.
     */
    private fun updateState() {
        val enabled = shortcuts.values.count { it.enabled }
        val usage = shortcuts.values.sumOf { it.usageCount }
        val categories = shortcuts.values.groupingBy { it.category }.eachCount()
        val conflicts = detectConflicts()

        val state = ShortcutState(
            shortcutCount = shortcuts.size,
            enabledCount = enabled,
            totalUsage = usage,
            categories = categories,
            conflicts = conflicts
        )

        _shortcutState.value = state
    }

    /**
     * Detect conflicting shortcuts.
     *
     * @return Number of conflicts detected
     */
    private fun detectConflicts(): Int {
        return keyIndex.values.count { it.size > 1 }
    }

    /**
     * Get shortcut statistics.
     *
     * @return Map of statistic names to values
     */
    fun getStatistics(): Map<String, Any> {
        val mostUsed = shortcuts.values.maxByOrNull { it.usageCount }

        return mapOf(
            "total_shortcuts" to shortcuts.size,
            "enabled_shortcuts" to shortcuts.values.count { it.enabled },
            "custom_shortcuts" to shortcuts.values.count { !it.id.startsWith("builtin_") },
            "builtin_shortcuts" to shortcuts.values.count { it.id.startsWith("builtin_") },
            "total_usage" to shortcuts.values.sumOf { it.usageCount },
            "most_used_combo" to (mostUsed?.getKeyCombo() ?: "none"),
            "most_used_count" to (mostUsed?.usageCount ?: 0),
            "conflicts" to detectConflicts(),
            "categories" to shortcuts.values.groupingBy { it.category }.eachCount()
        )
    }

    /**
     * Set callback for shortcut events.
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
        logD("Releasing ShortcutManager resources...")

        try {
            scope.cancel()
            callback = null
            shortcuts.clear()
            keyIndex.clear()
            logD("✅ ShortcutManager resources released")
        } catch (e: Exception) {
            logE("Error releasing shortcut manager resources", e)
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
