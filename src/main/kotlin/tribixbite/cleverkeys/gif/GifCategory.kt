package tribixbite.cleverkeys.gif

/**
 * GIF emotion categories based on GIFGIF+ research (17 emotions).
 * Maps to pre-indexed database categories for offline browsing.
 *
 * Reference: MIT Media Lab GIFGIF+ dataset
 */
enum class GifCategory(
    val id: Int,
    val displayName: String,
    val icon: String,
    val keywords: List<String>
) {
    RECENTLY_USED(0, "Recent", "\uD83D\uDD59", listOf("recent", "history")),
    AMUSEMENT(1, "Funny", "\uD83D\uDE02", listOf("laughing", "happy", "funny", "lol", "haha", "laugh")),
    ANGER(2, "Angry", "\uD83D\uDE20", listOf("angry", "frustrated", "rage", "mad", "upset", "furious")),
    CONTEMPT(3, "Smug", "\uD83D\uDE0F", listOf("eye roll", "unimpressed", "smug", "whatever", "annoyed")),
    CONTENTMENT(4, "Relaxed", "\uD83D\uDE0C", listOf("satisfied", "peaceful", "relaxed", "calm", "chill")),
    DISGUST(5, "Disgusted", "\uD83E\uDD22", listOf("gross", "ew", "disgusted", "yuck", "nope")),
    EMBARRASSMENT(6, "Awkward", "\uD83D\uDE33", listOf("facepalm", "awkward", "cringe", "embarrassed", "oops")),
    EXCITEMENT(7, "Excited", "\uD83E\uDD29", listOf("excited", "yay", "celebration", "woohoo", "pumped")),
    FEAR(8, "Scared", "\uD83D\uDE28", listOf("scared", "terrified", "horror", "afraid", "panic")),
    GUILT(9, "Sorry", "\uD83D\uDE14", listOf("sorry", "regret", "apologize", "my bad", "oops")),
    HAPPINESS(10, "Happy", "\uD83D\uDE0A", listOf("happy", "smile", "joy", "pleased", "cheerful")),
    LOVE(11, "Love", "\uD83D\uDE0D", listOf("heart", "love", "romantic", "adore", "crush")),
    PRIDE(12, "Proud", "\uD83D\uDE24", listOf("proud", "victory", "flex", "winning", "boss")),
    RELIEF(13, "Relieved", "\uD83D\uDE05", listOf("phew", "whew", "relieved", "close call", "safe")),
    SADNESS(14, "Sad", "\uD83D\uDE22", listOf("sad", "crying", "tears", "depressed", "upset")),
    SHAME(15, "Ashamed", "\uD83D\uDE1E", listOf("ashamed", "hide", "disappointed", "shame", "fail")),
    SURPRISE(16, "Surprised", "\uD83D\uDE32", listOf("shocked", "omg", "surprised", "wow", "what")),
    APPROVAL(17, "Approve", "\uD83D\uDC4D", listOf("yes", "agree", "thumbs up", "ok", "nice", "good"));

    companion object {
        /**
         * Get category by ID, returns null if not found.
         */
        fun fromId(id: Int): GifCategory? = entries.find { it.id == id }

        /**
         * Get all browsable categories (excludes RECENTLY_USED which is dynamic).
         */
        fun browsableCategories(): List<GifCategory> = entries.filter { it != RECENTLY_USED }

        /**
         * Find categories matching a search query.
         */
        fun searchCategories(query: String): List<GifCategory> {
            val queryLower = query.lowercase().trim()
            return entries.filter { category ->
                category.displayName.lowercase().contains(queryLower) ||
                category.keywords.any { it.contains(queryLower) }
            }
        }
    }
}
