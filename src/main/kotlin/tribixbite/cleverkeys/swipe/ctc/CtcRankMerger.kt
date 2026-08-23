package tribixbite.cleverkeys.swipe.ctc

import java.util.Locale

/**
 * Merges primary/secondary CTC slates by rank, never by incomparable raw decoder scores.
 * Primary rank N receives `1000/(N+1)` and secondary rank N receives `920/(N+1)`.
 */
object CtcRankMerger {
    data class Item(val word: String, val score: Int, val language: String)

    fun merge(
        primaryLanguage: String,
        primaryWords: List<String>,
        secondaryLanguage: String?,
        secondaryWords: List<String>,
        limit: Int,
    ): List<Item> {
        if (limit <= 0) return emptyList()
        val ranked = ArrayList<Item>(primaryWords.size + secondaryWords.size)
        primaryWords.forEachIndexed { rank, word ->
            ranked.add(Item(word, 1000 / (rank + 1), primaryLanguage))
        }
        if (!secondaryLanguage.isNullOrBlank() && secondaryLanguage != primaryLanguage) {
            secondaryWords.forEachIndexed { rank, word ->
                ranked.add(Item(word, 920 / (rank + 1), secondaryLanguage))
            }
        }
        ranked.sortByDescending { it.score }

        val seen = HashSet<String>()
        val result = ArrayList<Item>(limit)
        for (item in ranked) {
            if (!seen.add(item.word.lowercase(Locale.ROOT))) continue
            result.add(item)
            if (result.size == limit) break
        }
        return result
    }
}
