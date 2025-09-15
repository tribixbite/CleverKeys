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
     * Get recent emojis (would be stored in preferences)
     */
    fun getRecentEmojis(): List<EmojiData> {
        // TODO: Load from preferences
        return emojis.take(10)
    }
}