package tribixbite.keyboard2

import android.content.Context
import kotlinx.coroutines.*

/**
 * Emoji management system
 * Kotlin implementation with reactive emoji loading
 */
class Emoji(private val context: Context) {
    
    companion object {
        private const val TAG = "Emoji"
        private var instance: Emoji? = null
        
        fun getInstance(context: Context): Emoji {
            return instance ?: synchronized(this) {
                instance ?: Emoji(context).also { instance = it }
            }
        }
    }
    
    private val emojis = mutableListOf<EmojiData>()
    private val emojiGroups = mutableMapOf<String, List<EmojiData>>()
    private var isLoaded = false
    
    /**
     * Emoji data class
     */
    data class EmojiData(
        val emoji: String,
        val description: String,
        val group: String,
        val keywords: List<String>
    )
    
    /**
     * Load emoji data
     */
    suspend fun loadEmojis(): Boolean = withContext(Dispatchers.IO) {
        try {
            context.assets.open("raw/emojis.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(";")
                    if (parts.size >= 3) {
                        val emoji = parts[0].trim()
                        val description = parts[1].trim()
                        val group = parts.getOrNull(2)?.trim() ?: "other"
                        val keywords = parts.drop(3).map { it.trim() }
                        
                        emojis.add(EmojiData(emoji, description, group, keywords))
                    }
                }
            }
            
            // Group emojis
            emojiGroups.clear()
            emojis.groupBy { it.group }.forEach { (group, emojis) ->
                emojiGroups[group] = emojis
            }
            
            isLoaded = true
            logD("Loaded ${emojis.size} emojis in ${emojiGroups.size} groups")
            true
        } catch (e: Exception) {
            logE("Failed to load emojis", e)
            false
        }
    }
    
    /**
     * Search emojis by keyword
     */
    fun searchEmojis(query: String): List<EmojiData> {
        if (!isLoaded || query.isBlank()) return emptyList()
        
        val lowerQuery = query.lowercase()
        return emojis.filter { emoji ->
            emoji.description.lowercase().contains(lowerQuery) ||
            emoji.keywords.any { it.lowercase().contains(lowerQuery) }
        }.take(20)
    }
    
    /**
     * Get emojis by group
     */
    fun getEmojisByGroup(group: String): List<EmojiData> {
        return emojiGroups[group] ?: emptyList()
    }
    
    /**
     * Get all groups
     */
    fun getGroups(): List<String> {
        return emojiGroups.keys.toList()
    }
    
    /**
     * Get recent emojis from preferences
     */
    fun getRecentEmojis(context: android.content.Context): List<EmojiData> {
        try {
            val prefs = context.getSharedPreferences("cleverkeys_prefs", android.content.Context.MODE_PRIVATE)
            val recentString = prefs.getString("recent_emojis", "") ?: ""

            if (recentString.isEmpty()) {
                return emojis.take(10) // Default to first 10 if no recent history
            }

            // Recent emojis stored as pipe-separated emoji strings
            val recentEmojiStrings = recentString.split("|").filter { it.isNotEmpty() }
            val recentEmojis = mutableListOf<EmojiData>()

            for (emojiStr in recentEmojiStrings) {
                emojis.find { it.emoji == emojiStr }?.let { recentEmojis.add(it) }
            }

            return recentEmojis.ifEmpty { emojis.take(10) }
        } catch (e: Exception) {
            android.util.Log.e("Emoji", "Failed to load recent emojis", e)
            return emojis.take(10)
        }
    }

    /**
     * Record emoji usage and update recent list
     */
    fun recordEmojiUsage(context: android.content.Context, emoji: EmojiData) {
        try {
            val prefs = context.getSharedPreferences("cleverkeys_prefs", android.content.Context.MODE_PRIVATE)
            val recentString = prefs.getString("recent_emojis", "") ?: ""

            // Parse existing recent emojis
            val recentEmojiStrings = recentString.split("|")
                .filter { it.isNotEmpty() }
                .toMutableList()

            // Remove this emoji if it's already in the list (will be added to front)
            recentEmojiStrings.remove(emoji.emoji)

            // Add emoji to front
            recentEmojiStrings.add(0, emoji.emoji)

            // Keep only the 20 most recent
            val trimmedRecent = recentEmojiStrings.take(20)

            // Save back to preferences
            val newRecentString = trimmedRecent.joinToString("|")
            prefs.edit().putString("recent_emojis", newRecentString).apply()
        } catch (e: Exception) {
            android.util.Log.e("Emoji", "Failed to record emoji usage", e)
        }
    }

    /**
     * Get emojis by group index (compatibility with original API)
     */
    fun getEmojisByGroupIndex(groupIndex: Int): List<EmojiData> {
        val groupNames = getGroups()
        return if (groupIndex >= 0 && groupIndex < groupNames.size) {
            getEmojisByGroup(groupNames[groupIndex])
        } else {
            emptyList()
        }
    }

    /**
     * Get number of emoji groups (compatibility with original API)
     */
    fun getNumGroups(): Int {
        return emojiGroups.size
    }

    /**
     * Get first emoji of a group (compatibility with original API)
     */
    fun getFirstEmojiOfGroup(groupIndex: Int): EmojiData? {
        return getEmojisByGroupIndex(groupIndex).firstOrNull()
    }
}