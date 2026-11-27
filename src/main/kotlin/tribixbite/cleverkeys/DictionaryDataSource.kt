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
 * A dictionary could not be loaded; [message] is user-facing (the word list shows it in its
 * "Error loading words" line).
 */
class DictionaryLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * How Dictionary Manager's main-dictionary rows match the stored disabled words, and its German
 * list under Swiss spelling ([SwissSpelling], D2 follow-up 2026-11-26). Pure
 * (`DictionaryListSpellingTest`; through the real source in `MainDictionarySourceSpellingTest`).
 *
 * The keyboard respells every German lexicon at load; the manager listed the shipped `straße`,
 * words the keyboard never offers, so under Swiss spelling the list is respelled the same way and
 * twins (`Maße`/`Masse`) merged. One rule ([EnabledCheck]) decides whether a row is enabled, for
 * the load, for the re-sync after any toggle ([MainDictionarySource.onRefresh]) and for which
 * stored entries an enable removes: before, the re-sync compared only the row's own word, so a
 * row disabled through its shipped `straße` (or a twin, or the loader's lowercase/accent-free key)
 * showed enabled after the next toggle in any tab (Fable audit C1).
 */
internal object DictionaryListSpelling {

    /**
     * The spellings a stored disabled entry can name a row by, kept only when they are not the
     * default (the row's own word and its lower case): [shipped] are the dictionary's canonical
     * spellings merged into the row (what a disable stores, audit C3), [keys] the loader's
     * normalized keys of those (lower case, accents stripped, `ß` as `ss`).
     */
    class RowSpellings(val shipped: List<String>, val keys: List<String>)

    /** One entry of a language's shipped list: [row] in its canonical spelling, and its normalized [key]. */
    class Shipped(val row: DictionaryWord, val key: String)

    /** The rows to show, in shipped order, and (by row identity) their non-default [spellings]. */
    class Listing(val words: List<DictionaryWord>, val spellings: Map<DictionaryWord, RowSpellings>)

    /**
     * Whether rows are enabled against ONE stored disabled set. A row is disabled when the set
     * holds any of its spellings, or, under [swiss], when the row's (`ss`) word or its lower case
     * is in the keyboard's [SwissSpelling.foldDisabled] set (a disabled `Maße` disables `masse`).
     */
    class EnabledCheck(private val disabled: Set<String>, swiss: Boolean) {
        private val folded: Set<String>? = if (swiss) SwissSpelling.foldDisabled(true, disabled) else null

        fun isEnabled(row: DictionaryWord, spellings: RowSpellings?): Boolean {
            if (spellings == null) {
                if (row.word in disabled) return false
                val lower = row.word.lowercase()
                if (lower != row.word && lower in disabled) return false
            } else if (spellings.shipped.any { it in disabled } || spellings.keys.any { it in disabled }) {
                return false
            }
            val foldedDisabled = folded ?: return true
            return row.word !in foldedDisabled && row.word.lowercase() !in foldedDisabled
        }
    }

    /**
     * [shipped] as the list shows it, each row's [DictionaryWord.enabled] set by [EnabledCheck]:
     * as shipped, or under [swiss] with every `ß` written `ss` and twins merged into one row at
     * the first one's position with the higher frequency.
     */
    fun list(shipped: List<Shipped>, disabled: Set<String>, swiss: Boolean): Listing {
        val check = EnabledCheck(disabled, swiss)
        val spellings = java.util.IdentityHashMap<DictionaryWord, RowSpellings>()
        if (!swiss) {
            for (entry in shipped) {
                val extra = if (entry.key == entry.row.word.lowercase()) null
                else RowSpellings(listOf(entry.row.word), listOf(entry.key))
                if (extra != null) spellings[entry.row] = extra
                entry.row.enabled = check.isEnabled(entry.row, extra)
            }
            return Listing(shipped.map { it.row }, spellings)
        }
        class Merge(val first: DictionaryWord, val word: String) {
            var frequency = first.frequency
            val shippedSpellings = mutableListOf<String>()
            val keys = mutableListOf<String>()
        }
        val merged = LinkedHashMap<String, Merge>(shipped.size * 2)
        for (entry in shipped) {
            val folded = SwissSpelling.fold(entry.row.word)
            val merge = merged.getOrPut(folded) { Merge(entry.row, folded) }
            merge.frequency = maxOf(merge.frequency, entry.row.frequency)
            merge.shippedSpellings += entry.row.word
            merge.keys += entry.key
        }
        val words = merged.values.map { merge ->
            val row = if (merge.word == merge.first.word && merge.frequency == merge.first.frequency) merge.first
            else merge.first.copy(word = merge.word, frequency = merge.frequency)
            val default = merge.shippedSpellings.size == 1 && merge.shippedSpellings[0] == row.word &&
                merge.keys[0] == row.word.lowercase()
            val extra = if (default) null else RowSpellings(merge.shippedSpellings.distinct(), merge.keys.distinct())
            if (extra != null) spellings[row] = extra
            row.enabled = check.isEnabled(row, extra)
            row
        }
        return Listing(words, spellings)
    }

    /** [words] under Swiss spelling, each one's normalized key taken as its lower case. */
    fun respell(words: List<DictionaryWord>, disabled: Set<String>): List<DictionaryWord> =
        list(words.map { Shipped(it, it.word.lowercase()) }, disabled, swiss = true).words

    /**
     * The stored entries an enable of [row] removes: every entry of [disabled] that alone
     * disables it by [EnabledCheck], so the row is enabled afterwards whichever spelling disabled
     * it (`Maße` for the Swiss row `Masse`, `haus` for the row `Haus`).
     */
    fun entriesDisabling(row: DictionaryWord, spellings: RowSpellings?, disabled: Set<String>, swiss: Boolean): Set<String> =
        disabled.filterTo(linkedSetOf()) { !EnabledCheck(setOf(it), swiss).isEnabled(row, spellings) }

    /** [entriesDisabling] for the Swiss row [word] with its default spellings, plus [word] itself. */
    fun spellingsToEnable(word: String, disabled: Set<String>): Set<String> =
        linkedSetOf(word) + entriesDisabling(DictionaryWord(word, 0, WordSource.MAIN), null, disabled, swiss = true)

    /** The entries a disable of [row] stores: the shipped spellings it was merged from (audit C3). */
    fun spellingsToDisable(row: DictionaryWord, spellings: RowSpellings?): List<String> =
        spellings?.shipped ?: listOf(row.word)
}

/**
 * Interface for dictionary data sources
 */
interface DictionaryDataSource {
    suspend fun getAllWords(): List<DictionaryWord>
    suspend fun searchWords(query: String): List<DictionaryWord>
    suspend fun toggleWord(word: String, enabled: Boolean)
    // Wave U2: default is the top of the 1..255 stored user scale — an added word
    // should win its own gesture (see UserWordFrequency).
    suspend fun addWord(word: String, frequency: Int = UserWordFrequency.DEFAULT)
    suspend fun deleteWord(word: String)
    suspend fun updateWord(oldWord: String, newWord: String, frequency: Int)

    /**
     * Re-sync cached state from persistent storage.
     * Called by [WordListFragment.refresh] before re-filtering, so that changes made
     * by a different data source (e.g. DisabledDictionarySource toggling a word that
     * MainDictionarySource has cached) are reflected.
     * Default no-op — only sources with in-memory caches need to override.
     */
    fun onRefresh() {}
}

/**
 * Main dictionary source - loads from assets dictionary file
 * Uses prefix indexing for fast search with 98k vocabulary
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

    /**
     * This list is shown in Swiss spelling: the stored switch is on and the language is German.
     * Read from the stored preference ([LanguagePrefs.germanSwissSpelling]): Dictionary Manager
     * may run without the keyboard, whose Config alone is kept current.
     */
    private val swissSpelling: Boolean = SwissSpelling.appliesTo(
        LanguagePrefs.germanSwissSpelling(DirectBootAwarePreferences.get_shared_preferences(context)),
        languageCode,
    )

    // Instance-level cache references (point to shared static cache when language matches)
    private var cachedWords: List<DictionaryWord>? = null
    // Prefix index for fast search: prefix -> list of matching words
    private var prefixIndex: Map<String, List<DictionaryWord>>? = null
    // Rows whose stored-disabled spellings are not just their word (DictionaryListSpelling).
    private var rowSpellings: Map<DictionaryWord, DictionaryListSpelling.RowSpellings>? = null

    init {
        // Reuse shared cache if it was built for this language.
        // This avoids re-parsing the 98k binary dictionary every time
        // the user opens Dictionary Manager or switches tabs.
        synchronized(Companion) {
            cachedFor()?.let { cached ->
                cachedWords = cached.words
                prefixIndex = cached.prefixIndex
                rowSpellings = cached.spellings
            }
        }
    }

    /** The shared cache entry for this language in this spelling, or null. Caller holds the lock. */
    private fun cachedFor(): LanguageCache? =
        sharedCache[languageCode]?.takeIf { it.swissSpelling == swissSpelling }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getAllWords() called for language: $languageCode")

        // Return cached if available (instance-level fast path). Read once: `cachedWords` is a
        // plain var that a concurrent load on another IO thread may replace.
        cachedWords?.let { cached ->
            Log.d(TAG, "Returning ${cached.size} cached words for $languageCode")
            loadLock.markLoaded(languageCode)
            return@withContext cached
        }

        // Double-check shared cache: another fragment for this language may have
        // finished loading while we were waiting for the IO dispatcher
        synchronized(Companion) {
            cachedFor()?.let { cached ->
                cachedWords = cached.words
                prefixIndex = cached.prefixIndex
                rowSpellings = cached.spellings
                Log.d(TAG, "Returning ${cached.words.size} words from shared cache for $languageCode")
                loadLock.markLoaded(languageCode)
                return@withContext cached.words
            }
        }

        // One language parses at a time (2026-11-18 OOM): every language tab is created at once
        // and used to parse its dictionary in parallel, so the peak was the sum of all of them,
        // on top of the keyboard's own heap in the same process. A tab that waited here for the
        // same language finds it cached. The turn goes by tab order (2026-11-26): the primary's
        // list no longer waits for a secondary that happened to ask first ([DictionaryLoadGate]).
        loadLock.withTurn(languageCode) {
            synchronized(Companion) {
                cachedFor()?.let { cached ->
                    cachedWords = cached.words
                    prefixIndex = cached.prefixIndex
                    rowSpellings = cached.spellings
                    return@withTurn cached.words
                }
            }
            loadWithMemoryRetry()
        }
    }

    /**
     * Parse this language's dictionary (pack, bundled binary or JSON) and cache it. Runs under
     * [loadLock]: one language at a time, in tab order.
     */
    private fun loadUncached(): List<DictionaryWord> {
        try {
            val disabled = disabledSource.getDisabledWords()
            val words = mutableListOf<DictionaryListSpelling.Shipped>()

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
                        val loaded = loadBinaryDictionaryFromFile(packPath, words)
                        if (loaded) {
                            Log.d(TAG, "Loaded ${words.size} words from language pack: $languageCode")
                            return publish(displayList(words, disabled))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Language pack not found for $languageCode, trying bundled assets", e)
                }

                // Fall back to bundled assets
                try {
                    val binFilename = "dictionaries/${languageCode}_enhanced.bin"
                    val loaded = loadBinaryDictionary(binFilename, words)
                    if (loaded) {
                        Log.d(TAG, "Loaded ${words.size} words from bundled binary dictionary: $binFilename")
                        return publish(displayList(words, disabled))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Binary dictionary not found for $languageCode, trying JSON/TXT fallback")
                }
            }

            // Try JSON format first (98k words with frequencies)
            try {
                val jsonFilename = "dictionaries/${languageCode}_enhanced.json"
                val jsonString = context.assets.open(jsonFilename).bufferedReader().use { it.readText() }
                val jsonDict = org.json.JSONObject(jsonString)
                val keys = jsonDict.keys()

                while (keys.hasNext()) {
                    val word = keys.next().lowercase()
                    if (word.matches(Regex("^[\\p{L}'-]+$"))) {
                        val frequency = jsonDict.getInt(word)
                        // Use raw frequency from JSON (128-255 range)
                        // The JSON keys are lower-cased above, so the word is its own key.
                        words.add(
                            DictionaryListSpelling.Shipped(
                                DictionaryWord(word = word, frequency = frequency, source = WordSource.MAIN),
                                key = word,
                            )
                        )
                    }
                }
                Log.d(TAG, "Loaded ${words.size} words from JSON dictionary")
            } catch (e: Exception) {
                // No JSON lexicon either — and there is no further fallback. The historical
                // "${languageCode}_enhanced.txt" branch was dead code: no *_enhanced.txt asset
                // ever shipped, so it always threw into the outer handler. Preserve that
                // outcome (no cache, empty result) explicitly so a later retry can still load.
                Log.w(TAG, "No bundled dictionary (bin/json) for $languageCode", e)
                return emptyList()
            }

            return publish(displayList(words, disabled))
        } catch (e: Exception) {
            Log.e(TAG, "Error loading main dictionary", e)
            return emptyList()
        }
    }

    /**
     * [loadUncached], surviving one OutOfMemoryError. The Dictionary Manager shares the
     * keyboard's process and heap (OOM seen 2026-11-18 on an 18-hour-old process): the failed
     * attempt's partial lists are garbage once it unwinds, so one retry after a collection
     * usually fits. A second failure throws [DictionaryLoadException], which the word list
     * shows as its loading error instead of an empty "No words found" list. Nothing is cached
     * by a failed attempt, so reopening the tab tries again.
     */
    private fun loadWithMemoryRetry(): List<DictionaryWord> = try {
        loadUncached()
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory loading the $languageCode dictionary; retrying once", e)
        System.gc()
        try {
            loadUncached()
        } catch (again: OutOfMemoryError) {
            Log.e(TAG, "Out of memory again loading the $languageCode dictionary", again)
            throw DictionaryLoadException(context.getString(R.string.dict_error_out_of_memory), again)
        }
    }

    /**
     * Cache [sorted] for this instance and every later one. The prefix index is built FIRST:
     * if building it runs out of memory, [cachedWords] stays unset, so the instance fast path
     * cannot hand out words whose search index is missing (search would find nothing).
     */
    private fun publish(listing: DictionaryListSpelling.Listing): List<DictionaryWord> {
        buildPrefixIndex(listing.words, listing.spellings)
        rowSpellings = listing.spellings
        cachedWords = listing.words
        return listing.words
    }

    /**
     * [shipped] in the spelling the keyboard serves, sorted for display: respelled when
     * [swissSpelling] ([DictionaryListSpelling]), as-is otherwise.
     */
    private fun displayList(
        shipped: List<DictionaryListSpelling.Shipped>,
        disabled: Set<String>,
    ): DictionaryListSpelling.Listing {
        val listing = DictionaryListSpelling.list(shipped, disabled, swissSpelling)
        return DictionaryListSpelling.Listing(listing.words.sorted(), listing.spellings)
    }

    /**
     * Build prefix index for fast word search.
     * Creates mapping from prefixes (1-3 chars) to lists of matching words.
     * Performance: Reduces 98k linear search to ~100-500 comparisons.
     * Also updates shared static cache so subsequent MainDictionarySource
     * instances for the same language skip the full load.
     */
    private fun buildPrefixIndex(
        words: List<DictionaryWord>,
        spellings: Map<DictionaryWord, DictionaryListSpelling.RowSpellings>,
    ) {
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

        // Persist to shared static cache for cross-instance reuse
        synchronized(Companion) {
            sharedCache[languageCode] = LanguageCache(words, index, swissSpelling, spellings)
        }
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        // A search can come before the list (a keystroke cancels the tab's first load while it
        // waits its turn): load, or await the load, rather than search a missing index and show
        // nothing until the query is cleared (Fable audit C5).
        if (prefixIndex == null) getAllWords()

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

    /**
     * Re-sync all cached DictionaryWord.enabled flags from the current disabled set.
     * Handles cross-source coherence: when DisabledDictionarySource toggles a word,
     * this source's cache becomes stale. Called by WordListFragment.refresh() before
     * re-filtering. O(n) scan of the list against a HashSet.
     *
     * The same rule as the load ([DictionaryListSpelling.EnabledCheck], with each row's shipped
     * spellings): comparing the row's word alone re-enabled a Swiss `strasse` whose `straße` was
     * disabled, a merged twin, and a row disabled through the loader's lowercase key (audit C1).
     *
     * The prefix index stores the SAME DictionaryWord object references as cachedWords,
     * so mutating .enabled here also fixes prefix index search results.
     */
    override fun onRefresh() {
        val words = cachedWords ?: return
        val check = DictionaryListSpelling.EnabledCheck(disabledSource.getDisabledWords(), swissSpelling)
        val spellings = rowSpellings
        for (dw in words) {
            dw.enabled = check.isEnabled(dw, spellings?.get(dw))
        }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        val row = cachedWords?.firstOrNull { it.word == word }
        val spellings = row?.let { rowSpellings?.get(it) }
        if (enabled) {
            // Remove every stored entry that disables the row, whichever spelling it names (a
            // Swiss row disabled through its `ß` spelling before the switch was turned on).
            val stored = disabledSource.getDisabledWords()
            val entries = if (row != null) {
                DictionaryListSpelling.entriesDisabling(row, spellings, stored, swissSpelling) + word
            } else {
                setOf(word)
            }
            for (entry in entries) disabledSource.setWordEnabled(entry, true)
        } else {
            // Store the shipped spellings (audit C3): a Swiss row stored as `strasse` matched
            // nothing in the list, and the keyboard offered the word again, with the switch off.
            val entries = if (row != null) DictionaryListSpelling.spellingsToDisable(row, spellings) else listOf(word)
            for (entry in entries) disabledSource.setWordEnabled(entry, false)
        }
        // Re-derive every cached row's flag from the stored set, so getAllWords()/searchWords()
        // reflect the change without a reload and every spelling of the row agrees.
        onRefresh()
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
        words: MutableList<DictionaryListSpelling.Shipped>,
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex(indexPrefixes = false)  // only canonicals are read
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(file, index)
            if (loaded) {
                extractWordsFromIndex(index, words)
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
        words: MutableList<DictionaryListSpelling.Shipped>,
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex(indexPrefixes = false)  // only canonicals are read
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)
            if (loaded) {
                extractWordsFromIndex(index, words)
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
        words: MutableList<DictionaryListSpelling.Shipped>,
    ) {
        for (word in index.normalizedWords()) {
            // Canonical form (with accents) and frequency rank. A direct lookup: this used to ask
            // for every word sharing the prefix and pick this one out, a list per word.
            val match = index.lookup(word)
            val canonical = match?.bestCanonical ?: word
            // Convert rank (0-255, 0=most common) to display frequency (1-10000)
            // rank 0 → 10000, rank 255 → 1
            val rank = match?.bestFrequencyRank ?: 255
            val frequency = 10000 - (rank * 39)  // ~10000 to ~50
            // Enabled is set by DictionaryListSpelling.list from both spellings (canonical, key).
            words.add(
                DictionaryListSpelling.Shipped(
                    DictionaryWord(
                        word = canonical,  // Show accented form
                        frequency = frequency.coerceIn(1, 10000),
                        source = WordSource.MAIN,
                    ),
                    key = word,
                )
            )
        }
    }

    companion object {
        private const val TAG = "MainDictionarySource"
        private const val PREFIX_INDEX_MAX_LENGTH = 3

        // Per-language shared cache across MainDictionarySource instances.
        // Eliminates redundant 98k-word binary dict parsing when DictionaryManager
        // is reopened or tabs are switched (each fragment creates a new instance).
        // Keyed by language code so multilang tabs don't evict each other.
        // Thread-safe: synchronized on Companion in init{} and buildPrefixIndex().
        private data class LanguageCache(
            val words: List<DictionaryWord>,
            val prefixIndex: Map<String, List<DictionaryWord>>,
            /** Built in Swiss spelling; an entry in the other spelling is a miss (and is replaced). */
            val swissSpelling: Boolean,
            /** The rows' non-default spellings ([DictionaryListSpelling.RowSpellings]), by identity. */
            val spellings: Map<DictionaryWord, DictionaryListSpelling.RowSpellings>,
        )
        private val sharedCache = mutableMapOf<String, LanguageCache>()

        /** Serializes dictionary parsing across instances and languages, in tab order ([getAllWords]). */
        private val loadLock = DictionaryLoadGate()

        /**
         * The order Dictionary Manager's tabs show their languages in ([DictionaryTabPlan.loadOrder]);
         * a language already cached is not waited for. Call before the tabs are created.
         */
        fun prioritize(languages: List<String>, germanSwissSpelling: Boolean) {
            loadLock.prioritize(languages, loadedLanguages { SwissSpelling.appliesTo(germanSwissSpelling, it) })
        }

        /**
         * The languages whose list is cached in the spelling [swissFor] says it now loads in: an
         * entry built in the other spelling is a miss for the tab ([cachedFor]), so it must still
         * be waited for (Fable audit C4).
         */
        internal fun loadedLanguages(swissFor: (String) -> Boolean): Set<String> =
            synchronized(this) {
                sharedCache.filter { (language, cache) -> cache.swissSpelling == swissFor(language) }.keys.toSet()
            }

        /** Invalidate shared cache for all languages. */
        fun invalidateCache() {
            synchronized(this) {
                sharedCache.clear()
            }
            loadLock.clear()
        }

        /** Invalidate shared cache for a specific language. */
        fun invalidateCache(languageCode: String) {
            synchronized(this) {
                sharedCache.remove(languageCode)
            }
        }
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

    /**
     * This list is shown in Swiss spelling: the stored switch is on and the language is German.
     * Read per call from the stored preference, like [MainDictionarySource.swissSpelling].
     */
    private fun swissSpelling(): Boolean = languageCode != null &&
        SwissSpelling.appliesTo(LanguagePrefs.germanSwissSpelling(prefs), languageCode)

    /**
     * The rows shown, each with the stored entries it stands for. Under Swiss spelling an entry is
     * shown as the keyboard treats it (`straße` as `strasse`, Pixel r22 R9), and entries that
     * spell one word merge into one row; otherwise each stored entry is its own row.
     */
    private fun rows(): Map<String, List<String>> {
        val stored = getDisabledWords()
        if (!swissSpelling()) return stored.associateWith { listOf(it) }
        return stored.groupBy { SwissSpelling.fold(it) }
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        rows().keys
            .map { DictionaryWord(it, 0, WordSource.MAIN, false) }
            .sorted()
    }

    /** A row matches when the query is in its shown spelling or in any stored entry it stands for. */
    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        return withContext(Dispatchers.IO) {
            rows().filter { (shown, stored) ->
                shown.contains(query, ignoreCase = true) || stored.any { it.contains(query, ignoreCase = true) }
            }.keys.map { DictionaryWord(it, 0, WordSource.MAIN, false) }.sorted()
        }
    }

    /**
     * Enabling a row removes every stored entry that disables it by the Active tab's rule
     * ([DictionaryListSpelling.entriesDisabling], as `MainDictionarySource.toggleWord`), so a
     * Swiss row shown as `strasse` re-enables its stored `straße` too.
     */
    override suspend fun toggleWord(word: String, enabled: Boolean) {
        if (!enabled) {
            setWordEnabled(word, false)
            return
        }
        val stored = getDisabledWords()
        val row = DictionaryWord(word, 0, WordSource.MAIN, false)
        for (entry in DictionaryListSpelling.entriesDisabling(row, null, stored, swissSpelling()) + word) {
            setWordEnabled(entry, true)
        }
    }

    override suspend fun addWord(word: String, frequency: Int) {
        // Disabled list doesn't support adding
        throw UnsupportedOperationException("Use toggleWord instead")
    }

    override suspend fun deleteWord(word: String) {
        toggleWord(word, true) // Re-enable word, every stored spelling of it
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

    /**
     * Swipe-priority key for this language (2026-10-08). The legacy global source (null
     * [languageCode]) has none: priorities only exist for the per-language stores the swipe
     * engines read.
     */
    private val swipePriorityKey: String? = languageCode?.let { LanguagePreferenceKeys.swipePriorityKey(it) }

    /** The stored raised levels of this language; empty for the legacy source. */
    fun getSwipePriorities(): Map<String, SwipePriority> =
        swipePriorityKey?.let { SwipePriority.parseMap(prefs.getString(it, null)) } ?: emptyMap()

    /** Persist [map]; an empty map removes the key, so "nothing raised" is the absent default. */
    private fun saveSwipePriorities(map: Map<String, SwipePriority>) {
        val key = swipePriorityKey ?: return
        val edit = prefs.edit()
        if (map.isEmpty()) edit.remove(key) else edit.putString(key, SwipePriority.toJson(map))
        edit.apply()
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        val priorities = getSwipePriorities()
        getCustomWords()
            .map { (word, freq) ->
                // The stored 1..255 frequency, as written (legacy 100s are offered a raise,
                // never rewritten silently — see LegacyCustomWordFrequency).
                DictionaryWord(word, freq, WordSource.CUSTOM, true, priorities[word] ?: SwipePriority.NORMAL)
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

    /** Add [word] with an explicit swipe [priority] (Dictionary Manager "Add word" dialog). */
    suspend fun addWord(word: String, frequency: Int, priority: SwipePriority) {
        addWord(word, frequency)
        saveSwipePriorities(SwipePriority.withLevel(getSwipePriorities(), word, priority))
    }

    override suspend fun deleteWord(word: String) {
        val words = getCustomWords()
        words.remove(word)
        saveCustomWords(words)
        // A removed word keeps no level: re-adding it later starts at NORMAL.
        val priorities = getSwipePriorities()
        if (word in priorities) saveSwipePriorities(SwipePriority.without(priorities, listOf(word)))
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int) {
        val words = getCustomWords()
        words.remove(oldWord)
        words[newWord] = frequency
        saveCustomWords(words)
        // A rename carries the word's swipe priority with it.
        val priorities = getSwipePriorities()
        if (oldWord != newWord && oldWord in priorities) {
            saveSwipePriorities(SwipePriority.renamed(priorities, oldWord, newWord))
        }
    }

    /** Edit [oldWord] into [newWord] at [frequency] and set its swipe [priority] (Edit dialog). */
    suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, priority: SwipePriority) {
        val words = getCustomWords()
        words.remove(oldWord)
        words[newWord] = frequency
        saveCustomWords(words)
        val priorities = SwipePriority.without(getSwipePriorities(), listOf(oldWord))
        saveSwipePriorities(SwipePriority.withLevel(priorities, newWord, priority))
    }

    // ── Legacy default-frequency offer (maintainer decision A, 2026-10-10) ──────────────────
    // Policy and evidence for the legacy value live in the pure LegacyCustomWordFrequency;
    // these methods only read and write this language's stores.

    /** This language's custom words still at the old default frequency (display order). */
    fun legacyFrequencyWords(): List<String> =
        LegacyCustomWordFrequency.legacyWords(getCustomWords())

    /**
     * Should the Custom tab show the legacy-frequency notice? True when a legacy word exists that
     * the user has not already dismissed. Never for the legacy global store (no language).
     */
    fun shouldOfferLegacyFrequencyRaise(): Boolean {
        val lang = languageCode ?: return false
        val dismissed = LegacyCustomWordFrequency.dismissedFor(
            prefs.getString(LegacyCustomWordFrequency.DISMISSED_PREF_KEY, null), lang
        )
        return LegacyCustomWordFrequency.shouldOffer(legacyFrequencyWords(), dismissed)
    }

    /**
     * Raise [words] that are STILL at the old default to [UserWordFrequency.DEFAULT], in one
     * write; swipe priorities are untouched. Reads the store fresh, so a word edited or removed
     * since the list was shown is left as it now is.
     *
     * @return how many words were raised
     */
    suspend fun raiseLegacyFrequencies(words: Collection<String>): Int {
        val stored = getCustomWords()
        val count = LegacyCustomWordFrequency.raisableCount(stored, words)
        if (count > 0) saveCustomWords(LegacyCustomWordFrequency.raise(stored, words))
        return count
    }

    /**
     * Remember that the user dismissed the notice for the legacy words shown now, so it stays
     * hidden until a legacy word outside this set appears (e.g. from a backup import).
     */
    fun dismissLegacyFrequencyOffer() {
        val lang = languageCode ?: return
        val key = LegacyCustomWordFrequency.DISMISSED_PREF_KEY
        prefs.edit().putString(
            key,
            LegacyCustomWordFrequency.withDismissed(prefs.getString(key, null), lang, legacyFrequencyWords()),
        ).apply()
    }

    companion object {
        // Legacy key for backwards compatibility (used when languageCode is null)
        private const val PREF_CUSTOM_WORDS_LEGACY = "custom_words"
    }
}