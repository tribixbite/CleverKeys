package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emoji Recents Manager - Persistent storage for recently used emojis
 *
 * Features:
 * - Stores up to 30 recent emojis
 * - Most recently used appears first
 * - Thread-safe operations with coroutines
 * - Automatic persistence to SharedPreferences
 *
 * Usage:
 * ```kotlin
 * val manager = EmojiRecentsManager(context)
 * manager.addRecent("😀")
 * val recents = manager.getRecents()
 * ```
 *
 * @since v2.1.0
 */
class EmojiRecentsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "emoji_recents"
        private const val KEY_RECENTS = "recent_emojis"
        private const val MAX_RECENTS = 30
        private const val DELIMITER = ","
    }

    /**
     * Add an emoji to recents (moves to front if already present)
     *
     * @param emoji The emoji character to add
     */
    suspend fun addRecent(emoji: String) = withContext(Dispatchers.IO) {
        val current = getRecents().toMutableList()

        // Remove if already present
        current.remove(emoji)

        // Add to front
        current.add(0, emoji)

        // Limit to MAX_RECENTS
        if (current.size > MAX_RECENTS) {
            current.removeLast()
        }

        // Save
        saveRecents(current)
    }

    /**
     * Get all recent emojis (most recent first)
     *
     * @return List of emoji characters
     */
    suspend fun getRecents(): List<String> = withContext(Dispatchers.IO) {
        val recentsString = prefs.getString(KEY_RECENTS, "") ?: ""

        if (recentsString.isEmpty()) {
            return@withContext emptyList()
        }

        recentsString.split(DELIMITER)
            .filter { it.isNotEmpty() }
            .take(MAX_RECENTS)
    }

    /**
     * Clear all recent emojis
     */
    suspend fun clearRecents() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    /**
     * Save recents list to preferences
     */
    private fun saveRecents(recents: List<String>) {
        val recentsString = recents.joinToString(DELIMITER)
        prefs.edit().putString(KEY_RECENTS, recentsString).apply()
    }

    /**
     * Get recents synchronously (for non-suspending contexts)
     * Use suspend version when possible for better performance
     */
    fun getRecentsSync(): List<String> {
        val recentsString = prefs.getString(KEY_RECENTS, "") ?: ""

        if (recentsString.isEmpty()) {
            return emptyList()
        }

        return recentsString.split(DELIMITER)
            .filter { it.isNotEmpty() }
            .take(MAX_RECENTS)
    }

    /**
     * Add recent synchronously (for non-suspending contexts)
     * Use suspend version when possible for better performance
     */
    fun addRecentSync(emoji: String) {
        val current = getRecentsSync().toMutableList()

        // Remove if already present
        current.remove(emoji)

        // Add to front
        current.add(0, emoji)

        // Limit to MAX_RECENTS
        if (current.size > MAX_RECENTS) {
            current.removeLast()
        }

        // Save
        saveRecents(current)
    }
}
