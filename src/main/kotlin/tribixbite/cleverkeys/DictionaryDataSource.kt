package tribixbite.cleverkeys

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
 *
 * v1.1.89: Added language support - loads language-specific dictionary when available
 *
 * @param languageCode ISO 639-1 language code (e.g., "en", "fr", "es"). Defaults to "en".
 */
class MainDictionarySource(
    private val context: Context,
    private val disabledSource: DisabledDictionarySource,
    private val languageCode: String = "en"
) : DictionaryDataSource {

    // Cache the dictionary after first load
    private var cachedWords: List<DictionaryWord>? = null
    // Prefix index for fast search: prefix -> list of matching words
    private var prefixIndex: Map<String, List<DictionaryWord>>? = null

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getAllWords() called for language: $languageCode")

        // Return cached if available
        if (cachedWords != null) {
            Log.d(TAG, "Returning ${cachedWords!!.size} cached words for $languageCode")
            return@withContext cachedWords!!
        }

        try {
            val disabled = disabledSource.getDisabledWords()
            val words = mutableListOf<DictionaryWord>()

            // v1.1.89: Try language-specific binary dictionary first
            // v1.1.96: Also check installed language packs
            // v1.1.97: Fixed - also load English from V2 binary (was skipping to JSON with 128-255 scale)
            run {
                Log.d(TAG, "Trying binary dictionary for language: $languageCode")

                // First try installed language pack
                try {
                    val packManager = tribixbite.cleverkeys.langpack.LanguagePackManager.getInstance(context)
                    val packPath = packManager.getDictionaryPath(languageCode)
                    if (packPath != null) {
                        Log.d(TAG, "Found language pack dictionary: ${packPath.absolutePath}")
                        val loaded = loadBinaryDictionaryFromFile(packPath, words, disabled)
                        if (loaded) {
                            Log.d(TAG, "Loaded ${words.size} words from language pack: $languageCode")
                            cachedWords = words.sorted()
                            buildPrefixIndex(cachedWords!!)
                            return@withContext cachedWords!!
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Language pack not found for $languageCode, trying bundled assets", e)
                }

                // Fall back to bundled assets
                try {
                    val binFilename = "dictionaries/${languageCode}_enhanced.bin"
                    val loaded = loadBinaryDictionary(binFilename, words, disabled)
                    if (loaded) {
                        Log.d(TAG, "Loaded ${words.size} words from bundled binary dictionary: $binFilename")
                        cachedWords = words.sorted()
                        buildPrefixIndex(cachedWords!!)
                        return@withContext cachedWords!!
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Binary dictionary not found for $languageCode, trying JSON/TXT fallback")
                }
            }

            // Try JSON format first (50k words with frequencies)
            try {
                val jsonFilename = "dictionaries/${languageCode}_enhanced.json"
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
                Log.w(TAG, "JSON dictionary not found for $languageCode, falling back to text format")

                // Fall back to text format
                val filename = "dictionaries/${languageCode}_enhanced.txt"
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

    /**
     * Load dictionary from binary file (File object, for language packs).
     * v1.1.96: Added for language pack support.
     */
    private fun loadBinaryDictionaryFromFile(
        file: java.io.File,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex()
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(file, index)
            if (loaded) {
                extractWordsFromIndex(index, words, disabled)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading binary dictionary from file: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Load dictionary from binary format (.bin files for non-English languages).
     * Uses NormalizedPrefixIndex to read the binary format.
     */
    private fun loadBinaryDictionary(
        filename: String,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex()
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)
            if (loaded) {
                extractWordsFromIndex(index, words, disabled)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading binary dictionary: $filename", e)
            false
        }
    }

    /**
     * Extract words from NormalizedPrefixIndex into word list.
     * Shared by both loadBinaryDictionary() and loadBinaryDictionaryFromFile().
     */
    private fun extractWordsFromIndex(
        index: NormalizedPrefixIndex,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ) {
        val normalizedWords = index.getAllNormalizedWords()
        for (word in normalizedWords) {
            // Get canonical form (with accents) and frequency rank
            val results = index.getWordsWithPrefix(word)
            val match = results.find { it.normalized == word }
            val canonical = match?.bestCanonical ?: word
            // Convert rank (0-255, 0=most common) to display frequency (1-10000)
            // rank 0 → 10000, rank 255 → 1
            val rank = match?.bestFrequencyRank ?: 255
            val frequency = 10000 - (rank * 39)  // ~10000 to ~50
            words.add(
                DictionaryWord(
                    word = canonical,  // Show accented form
                    frequency = frequency.coerceIn(1, 10000),
                    source = WordSource.MAIN,
                    enabled = !disabled.contains(word) && !disabled.contains(canonical)
                )
            )
        }
    }

    companion object {
        private const val TAG = "MainDictionarySource"
        private const val PREFIX_INDEX_MAX_LENGTH = 3
    }
}

/**
 * Disabled words source - manages disabled word list.
 *
 * @param prefs SharedPreferences to use
 * @param languageCode ISO 639-1 language code (e.g., "en", "es") for language-specific storage.
 *                     If null, uses global key (legacy behavior).
 * @since v1.1.86 - Added language-specific storage support
 */
class DisabledDictionarySource(
    private val prefs: SharedPreferences,
    private val languageCode: String? = null
) : DictionaryDataSource {

    /**
     * Get the preference key for disabled words.
     * Uses language-specific key if languageCode is provided, otherwise legacy global key.
     */
    private val disabledWordsKey: String
        get() = if (languageCode != null) {
            LanguagePreferenceKeys.disabledWordsKey(languageCode)
        } else {
            PREF_DISABLED_WORDS_LEGACY
        }

    fun getDisabledWords(): Set<String> {
        return prefs.getStringSet(disabledWordsKey, emptySet()) ?: emptySet()
    }

    fun setWordEnabled(word: String, enabled: Boolean) {
        val disabled = getDisabledWords().toMutableSet()
        if (enabled) {
            disabled.remove(word)
        } else {
            disabled.add(word)
        }
        prefs.edit().putStringSet(disabledWordsKey, disabled).apply()
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
        // Legacy global key (pre-v1.1.86)
        private const val PREF_DISABLED_WORDS_LEGACY = "disabled_words"
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
 * Custom dictionary source - app-specific custom words (language-aware)
 *
 * v1.1.87: Now uses language-specific storage via LanguagePreferenceKeys.
 * This matches how OptimizedVocabulary stores custom words for swipe prediction.
 *
 * @param prefs SharedPreferences for storage (typically DirectBootAwarePreferences)
 * @param languageCode Language code for language-specific storage (e.g., "en", "fr")
 *                     If null, uses legacy global key "custom_words" for backwards compatibility
 */
class CustomDictionarySource(
    private val prefs: SharedPreferences,
    private val languageCode: String? = null
) : DictionaryDataSource {

    private val gson = Gson()

    // Use language-specific key when languageCode is provided
    private val customWordsKey: String = if (languageCode != null) {
        LanguagePreferenceKeys.customWordsKey(languageCode)
    } else {
        PREF_CUSTOM_WORDS_LEGACY
    }

    private fun getCustomWords(): MutableMap<String, Int> {
        // Try JSON format first (used by OptimizedVocabulary)
        val jsonString = prefs.getString(customWordsKey, null)
        if (jsonString != null) {
            return try {
                val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Int>>() {}.type
                gson.fromJson(jsonString, type) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        return mutableMapOf()
    }

    private fun saveCustomWords(words: Map<String, Int>) {
        prefs.edit().putString(customWordsKey, gson.toJson(words)).apply()
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        getCustomWords()
            .map { (word, freq) ->
                // Use stored frequency or default to 100
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
        // Legacy key for backwards compatibility (used when languageCode is null)
        private const val PREF_CUSTOM_WORDS_LEGACY = "custom_words"
    }
}