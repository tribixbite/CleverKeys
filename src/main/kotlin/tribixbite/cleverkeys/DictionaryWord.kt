package tribixbite.cleverkeys

/**
 * Represents a word in the dictionary with its metadata
 */
data class DictionaryWord(
    val word: String,
    val frequency: Int = 0,
    val source: WordSource,
    val enabled: Boolean = true
) : Comparable<DictionaryWord> {

    override fun compareTo(other: DictionaryWord): Int {
        // Sort by frequency descending, then alphabetically
        val freqCompare = other.frequency.compareTo(this.frequency)
        return if (freqCompare != 0) freqCompare else word.compareTo(other.word)
    }
}

/**
 * Source of dictionary word
 */
enum class WordSource {
    MAIN,       // Main dictionary (bigrams.bin)
    USER,       // Android UserDictionary
    CUSTOM      // App-specific custom dictionary
}
