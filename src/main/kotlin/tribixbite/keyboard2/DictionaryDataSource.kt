package tribixbite.keyboard2

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for dictionary data sources
 */
interface DictionaryDataSource {
    suspend fun getAllWords(): List<DictionaryWord>
    suspend fun searchWords(query: String): List<DictionaryWord>
    suspend fun toggleWord(word: String, enabled: Boolean)
    suspend fun addWord(word: String, frequency: Int = 100)
    suspend fun deleteWord(word: String)
    suspend fun updateWord(oldWord: String, newWord: String, frequency: Int)
}

/**
 * Main dictionary source - loads from assets dictionary file
 * Uses prefix indexing for fast search with 50k vocabulary
 */
class MainDictionarySource(
    private val context: Context,
    private val disabledSource: DisabledDictionarySource
) : DictionaryDataSource {

    // Cache the dictionary after first load
    private var cachedWords: List<DictionaryWord>? = null
    // Prefix index for fast search: prefix -> list of matching words
    private var prefixIndex: Map<String, List<DictionaryWord>>? = null

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        // Return cached if available
        if (cachedWords != null) {
            return@withContext cachedWords!!
        }

        try {
            val disabled = disabledSource.getDisabledWords()
            val words = mutableListOf<DictionaryWord>()

            // Try JSON format first (50k words with frequencies)
            try {
                val jsonFilename = "dictionaries/en_enhanced.json"
                val jsonString = context.assets.open(jsonFilename).bufferedReader().use { it.readText() }
                val jsonDict = org.json.JSONObject(jsonString)
                val keys = jsonDict.keys()

                while (keys.hasNext()) {
                    val word = keys.next().lowercase()
                    if (word.matches(Regex("^[a-z]+$"))) {
                        val frequency = jsonDict.getInt(word)
                        // Use raw frequency from JSON (128-255 range)
                        words.add(
                            DictionaryWord(
                                word = word,
                                frequency = frequency,
                                source = WordSource.MAIN,
                                enabled = !disabled.contains(word)
                            )
                        )
                    }
                }
                Log.d(TAG, "Loaded ${words.size} words from JSON dictionary")
            } catch (e: Exception) {
                Log.w(TAG, "JSON dictionary not found, falling back to text format")

                // Fall back to text format
                val filename = "dictionaries/en_enhanced.txt"
                context.assets.open(filename).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .forEach { line ->
                            val word = line.trim().lowercase()
                            words.add(
                                DictionaryWord(
                                    word = word,
                                    frequency = 100,
                                    source = WordSource.MAIN,
                                    enabled = !disabled.contains(word)
                                )
                            )
                        }
                }
                Log.d(TAG, "Loaded ${words.size} words from text dictionary")
            }

            cachedWords = words.sorted()

            // Build prefix index for fast search
            buildPrefixIndex(cachedWords!!)

            cachedWords!!
        } catch (e: Exception) {
            Log.e(TAG, "Error loading main dictionary", e)
            emptyList()
        }
    }

    /**
     * Build prefix index for fast word search
     * Creates mapping from prefixes (1-3 chars) to lists of matching words
     * Performance: Reduces 50k linear search to ~100-500 comparisons
     */
    private fun buildPrefixIndex(words: List<DictionaryWord>) {
        val index = mutableMapOf<String, MutableList<DictionaryWord>>()

        for (word in words) {
            val maxLen = minOf(PREFIX_INDEX_MAX_LENGTH, word.word.length)
            for (len in 1..maxLen) {
                val prefix = word.word.substring(0, len).lowercase()
                index.getOrPut(prefix) { mutableListOf() }.add(word)
            }
        }

        prefixIndex = index
        Log.d(TAG, "Built prefix index: ${index.size} prefixes for ${words.size} words")
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()

        val lowerQuery = query.lowercase()

        // Use prefix index if query starts at beginning of word (most common case)
        if (lowerQuery.length <= PREFIX_INDEX_MAX_LENGTH) {
            // Exact prefix match - use index
            val candidates = prefixIndex?.get(lowerQuery) ?: emptyList()
            // Filter for substring match (in case user typed middle of word)
            return candidates.filter { it.word.contains(lowerQuery, ignoreCase = true) }
        } else if (lowerQuery.length > PREFIX_INDEX_MAX_LENGTH) {
            // Use first 3 chars from index, then filter
            val prefix = lowerQuery.substring(0, PREFIX_INDEX_MAX_LENGTH)
            val candidates = prefixIndex?.get(prefix) ?: emptyList()
            return candidates.filter { it.word.contains(lowerQuery, ignoreCase = true) }
        }

        // Fallback to full search (should rarely happen)
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        disabledSource.setWordEnabled(word, enabled)
    }

    override suspend fun addWord(word: String, frequency: Int) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot add words to main dictionary")
    }

    override suspend fun deleteWord(word: String) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot delete words from main dictionary")
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot update words in main dictionary")
    }

    companion object {
        private const val TAG = "MainDictionarySource"
        private const val PREFIX_INDEX_MAX_LENGTH = 3
    }
}

/**
 * Disabled words source - manages disabled word list
 */
class DisabledDictionarySource(private val prefs: SharedPreferences) : DictionaryDataSource {

    fun getDisabledWords(): Set<String> {
        return prefs.getStringSet(PREF_DISABLED_WORDS, emptySet()) ?: emptySet()
    }

    fun setWordEnabled(word: String, enabled: Boolean) {
        val disabled = getDisabledWords().toMutableSet()
        if (enabled) {
            disabled.remove(word)
        } else {
            disabled.add(word)
        }
        prefs.edit().putStringSet(PREF_DISABLED_WORDS, disabled).apply()
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        getDisabledWords()
            .map { DictionaryWord(it, 0, WordSource.MAIN, false) }
            .sorted()
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        setWordEnabled(word, enabled)
    }

    override suspend fun addWord(word: String, frequency: Int) {
        // Disabled list doesn't support adding
        throw UnsupportedOperationException("Use toggleWord instead")
    }

    override suspend fun deleteWord(word: String) {
        setWordEnabled(word, true) // Re-enable word
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int) {
        // Disabled list doesn't support updating
        throw UnsupportedOperationException("Use toggleWord instead")
    }

    companion object {
        private const val PREF_DISABLED_WORDS = "disabled_words"
    }
}

/**
 * User dictionary source - reads from Android's UserDictionary
 */
class UserDictionarySource(
    private val context: Context,
    private val contentResolver: ContentResolver
) : DictionaryDataSource {

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        try {
            val words = mutableListOf<DictionaryWord>()
            val cursor = contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY
                ),
                null,
                null,
                "${UserDictionary.Words.WORD} ASC"
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex)
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else 100
                    words.add(DictionaryWord(word, freq, WordSource.USER, true))
                }
            }

            words.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user dictionary", e)
            emptyList()
        }
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getAllWords()

        try {
            val words = mutableListOf<DictionaryWord>()
            val selection = "${UserDictionary.Words.WORD} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val cursor = contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY
                ),
                selection,
                selectionArgs,
                "${UserDictionary.Words.WORD} ASC"
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex)
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else 100
                    words.add(DictionaryWord(word, freq, WordSource.USER, true))
                }
            }

            words.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Error searching user dictionary", e)
            emptyList()
        }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        // User dictionary doesn't support disabling, only deleting
        if (!enabled) deleteWord(word)
    }

    override suspend fun addWord(word: String, frequency: Int) = withContext(Dispatchers.IO) {
        // Use UserDictionary API to add word
        UserDictionary.Words.addWord(
            context,
            word,
            frequency,
            null,
            null
        )
    }

    override suspend fun deleteWord(word: String): Unit = withContext(Dispatchers.IO) {
        contentResolver.delete(
            UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=?",
            arrayOf(word)
        )
        Unit
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int) {
        deleteWord(oldWord)
        addWord(newWord, frequency)
    }

    companion object {
        private const val TAG = "UserDictionarySource"
    }
}

/**
 * Custom dictionary source - app-specific custom words
 */
class CustomDictionarySource(private val prefs: SharedPreferences) : DictionaryDataSource {

    private val gson = Gson()

    private fun getCustomWords(): MutableMap<String, Int> {
        val json = prefs.getString(PREF_CUSTOM_WORDS, "{}")
        val type = object : TypeToken<Map<String, Int>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    private fun saveCustomWords(words: Map<String, Int>) {
        val json = gson.toJson(words)
        prefs.edit().putString(PREF_CUSTOM_WORDS, json).apply()
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        getCustomWords()
            .map { (word, freq) ->
                DictionaryWord(word, freq, WordSource.CUSTOM, true)
            }
            .sorted()
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        // Custom words are always enabled, use delete to remove
        if (!enabled) deleteWord(word)
    }

    override suspend fun addWord(word: String, frequency: Int) {
        val words = getCustomWords()
        words[word] = frequency
        saveCustomWords(words)
    }

    override suspend fun deleteWord(word: String) {
        val words = getCustomWords()
        words.remove(word)
        saveCustomWords(words)
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int) {
        val words = getCustomWords()
        words.remove(oldWord)
        words[newWord] = frequency
        saveCustomWords(words)
    }

    companion object {
        private const val PREF_CUSTOM_WORDS = "custom_words"
    }
}
