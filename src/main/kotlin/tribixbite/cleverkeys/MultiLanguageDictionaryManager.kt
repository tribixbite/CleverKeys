package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple language-specific dictionaries with lazy loading.
 *
 * Provides efficient dictionary management for multi-language support:
 * - Lazy loading: Load dictionaries on-demand
 * - Caching: Keep loaded dictionaries in memory
 * - Thread-safe: Concurrent access from multiple threads
 * - Memory management: Track and control memory usage
 * - V2 binary format: Accent-aware lookups via NormalizedPrefixIndex
 *
 * ## Dictionary Formats
 * - V1 (legacy): OptimizedVocabulary for English-only (26-letter)
 * - V2 (new): NormalizedPrefixIndex for multilanguage with accents
 *
 * @since v1.2.0 - Added V2 format and NormalizedPrefixIndex support
 */
class MultiLanguageDictionaryManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "MultiLanguageDictionaryManager"
    }

    // Cached dictionaries (language code → OptimizedVocabulary)
    // Used for legacy V1 format and English
    // Thread-safe for concurrent access
    private val dictionaries = ConcurrentHashMap<String, OptimizedVocabulary>()

    // V2 dictionaries with accent support (language code → NormalizedPrefixIndex)
    // Used for multilanguage dictionaries with accented characters
    private val normalizedIndexes = ConcurrentHashMap<String, NormalizedPrefixIndex>()

    /**
     * Load dictionary for a specific language
     * Returns cached dictionary if already loaded
     *
     * @param language Language code (en, es, fr, pt, de)
     * @return OptimizedVocabulary or null if not found
     */
    @Synchronized
    fun loadDictionary(language: String): OptimizedVocabulary? {
        // Check cache first
        dictionaries[language]?.let {
            Log.d(TAG, "Using cached dictionary: $language")
            return it
        }

        try {
            Log.i(TAG, "Loading dictionary: $language")
            val startTime = System.currentTimeMillis()

            // OptimizedVocabulary loads from assets/en_enhanced.bin by default
            // For multi-language support, we would need language-specific dictionaries
            // For now, just load the standard vocabulary
            val vocab = OptimizedVocabulary(context)
            val success = vocab.loadVocabulary()

            if (!success) {
                Log.w(TAG, "Failed to load vocabulary for $language")
                return null
            }

            dictionaries[language] = vocab

            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Loaded dictionary: $language (${loadTime}ms)")

            return vocab

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary: $language", e)
            return null
        }
    }

    /**
     * Get dictionary for active language (fallback to English)
     *
     * @param language Preferred language code
     * @return OptimizedVocabulary (never null, falls back to English)
     * @throws IllegalStateException if no dictionaries available
     */
    fun getDictionary(language: String): OptimizedVocabulary {
        // Try requested language
        dictionaries[language]?.let { return it }

        // Try to load requested language
        loadDictionary(language)?.let { return it }

        // Fallback to English
        Log.w(TAG, "Dictionary not available for $language, falling back to English")
        dictionaries["en"]?.let { return it }

        // Try to load English
        loadDictionary("en")?.let { return it }

        // No dictionaries available
        throw IllegalStateException("No dictionaries available (tried $language and en)")
    }

    /**
     * Get dictionary for active language (returns null if not available)
     *
     * @param language Language code
     * @return OptimizedVocabulary or null
     */
    fun getDictionaryOrNull(language: String): OptimizedVocabulary? {
        return dictionaries[language] ?: loadDictionary(language)
    }

    /**
     * Check if a dictionary is loaded
     */
    fun isDictionaryLoaded(language: String): Boolean {
        return dictionaries.containsKey(language)
    }

    /**
     * Preload dictionary asynchronously
     * Useful for preloading dictionaries before they're needed
     */
    fun preloadDictionary(language: String) {
        Thread {
            Log.d(TAG, "Preloading dictionary: $language")
            loadDictionary(language)
        }.start()
    }

    /**
     * Unload a specific dictionary to free memory
     */
    @Synchronized
    fun unloadDictionary(language: String) {
        dictionaries.remove(language)?.let {
            Log.i(TAG, "Unloaded dictionary: $language")
        }
    }

    /**
     * Unload all dictionaries except the active one
     *
     * @param activeLanguage Language to keep loaded
     */
    @Synchronized
    fun unloadUnusedDictionaries(activeLanguage: String) {
        val toRemove = mutableListOf<String>()
        for (lang in dictionaries.keys) {
            if (lang != activeLanguage) {
                toRemove.add(lang)
            }
        }

        for (lang in toRemove) {
            unloadDictionary(lang)
        }

        Log.i(TAG, "Unloaded ${toRemove.size} unused dictionaries, kept: $activeLanguage")
    }

    /**
     * Get list of loaded dictionaries
     */
    fun getLoadedLanguages(): Set<String> {
        return dictionaries.keys.toSet()
    }

    /**
     * Get count of loaded dictionaries
     */
    fun getLoadedCount(): Int {
        return dictionaries.size
    }

    /**
     * Get memory usage estimate in MB
     * Assumes ~2MB per dictionary (binary format)
     */
    fun getMemoryUsageMB(): Float {
        return dictionaries.size * 2.0f
    }

    /**
     * Clear all cached dictionaries
     */
    @Synchronized
    fun clearAll() {
        val count = dictionaries.size
        dictionaries.clear()
        Log.i(TAG, "Cleared all dictionaries (count: $count)")
    }

    /**
     * Get statistics about loaded dictionaries
     */
    fun getStats(): DictionaryStats {
        return DictionaryStats(
            loadedCount = dictionaries.size,
            loadedLanguages = dictionaries.keys.toList(),
            memoryUsageMB = getMemoryUsageMB()
        )
    }

    /**
     * Statistics about loaded dictionaries
     */
    data class DictionaryStats(
        val loadedCount: Int,
        val loadedLanguages: List<String>,
        val memoryUsageMB: Float
    ) {
        override fun toString(): String {
            return "DictionaryStats(count=$loadedCount, languages=$loadedLanguages, memory=${memoryUsageMB}MB)"
        }
    }

    // ==================== V2 FORMAT SUPPORT ====================

    /**
     * Load a V2 binary dictionary with accent support.
     *
     * V2 format provides:
     * - NormalizedPrefixIndex for accent-aware lookups
     * - Frequency ranks (0-255) for scoring
     * - Canonical → normalized mapping for display
     *
     * @param language Language code (e.g., "es", "fr", "de")
     * @return NormalizedPrefixIndex or null if loading failed
     */
    @Synchronized
    fun loadNormalizedIndex(language: String): NormalizedPrefixIndex? {
        // Check cache first
        normalizedIndexes[language]?.let {
            Log.d(TAG, "Using cached normalized index: $language")
            return it
        }

        try {
            Log.i(TAG, "Loading V2 dictionary: $language")
            val startTime = System.currentTimeMillis()

            val filename = "dictionaries/${language}_enhanced.bin"
            val index = NormalizedPrefixIndex()

            val success = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)

            if (!success) {
                Log.w(TAG, "Failed to load V2 dictionary for $language")
                return null
            }

            normalizedIndexes[language] = index

            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "Loaded V2 dictionary: $language (${index.size()} words, ${loadTime}ms)")

            return index

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load V2 dictionary: $language", e)
            return null
        }
    }

    /**
     * Get NormalizedPrefixIndex for a language.
     *
     * @param language Language code
     * @return NormalizedPrefixIndex or null if not loaded
     */
    fun getNormalizedIndex(language: String): NormalizedPrefixIndex? {
        return normalizedIndexes[language] ?: loadNormalizedIndex(language)
    }

    /**
     * Check if a V2 dictionary is available for a language.
     *
     * @param language Language code
     * @return true if the dictionary file exists
     */
    fun hasV2Dictionary(language: String): Boolean {
        return try {
            val filename = "dictionaries/${language}_enhanced.bin"
            context.assets.open(filename).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a V2 dictionary is loaded for a language.
     */
    fun isNormalizedIndexLoaded(language: String): Boolean {
        return normalizedIndexes.containsKey(language)
    }

    /**
     * Unload a specific V2 dictionary to free memory.
     */
    @Synchronized
    fun unloadNormalizedIndex(language: String) {
        normalizedIndexes.remove(language)?.let {
            Log.i(TAG, "Unloaded V2 dictionary: $language")
        }
    }

    /**
     * Get list of loaded V2 dictionaries.
     */
    fun getLoadedNormalizedIndexLanguages(): Set<String> {
        return normalizedIndexes.keys.toSet()
    }

    /**
     * Clear all V2 dictionaries.
     */
    @Synchronized
    fun clearAllNormalizedIndexes() {
        val count = normalizedIndexes.size
        normalizedIndexes.clear()
        Log.i(TAG, "Cleared all V2 dictionaries (count: $count)")
    }

    /**
     * Create SuggestionRanker candidates from V2 dictionary lookup.
     *
     * @param language Language code
     * @param nnPredictions NN predictions to look up
     * @return List of candidates ready for ranking
     */
    fun createCandidatesFromNnPredictions(
        language: String,
        nnPredictions: List<CandidateWord>
    ): List<SuggestionRanker.Candidate> {
        val index = getNormalizedIndex(language) ?: return emptyList()
        val candidates = mutableListOf<SuggestionRanker.Candidate>()

        for (prediction in nnPredictions) {
            val normalized = AccentNormalizer.normalize(prediction.word)
            val results = index.getWordsWithPrefix(normalized).filter {
                it.normalized == normalized // Exact match only
            }

            for (result in results) {
                candidates.add(
                    SuggestionRanker.Candidate(
                        word = result.bestCanonical,
                        normalized = result.normalized,
                        frequencyRank = result.bestFrequencyRank,
                        source = SuggestionRanker.WordSource.SECONDARY,
                        nnConfidence = prediction.confidence,
                        languageCode = language
                    )
                )
            }
        }

        return candidates
    }

    /**
     * Get extended statistics including V2 dictionaries.
     */
    fun getExtendedStats(): ExtendedDictionaryStats {
        return ExtendedDictionaryStats(
            v1LoadedCount = dictionaries.size,
            v1LoadedLanguages = dictionaries.keys.toList(),
            v2LoadedCount = normalizedIndexes.size,
            v2LoadedLanguages = normalizedIndexes.keys.toList(),
            totalMemoryUsageMB = getMemoryUsageMB() + (normalizedIndexes.size * 3.0f) // ~3MB per V2 dict
        )
    }

    /**
     * Extended statistics including V2 dictionaries.
     */
    data class ExtendedDictionaryStats(
        val v1LoadedCount: Int,
        val v1LoadedLanguages: List<String>,
        val v2LoadedCount: Int,
        val v2LoadedLanguages: List<String>,
        val totalMemoryUsageMB: Float
    ) {
        override fun toString(): String {
            return "ExtendedDictionaryStats(v1=$v1LoadedCount$v1LoadedLanguages, " +
                   "v2=$v2LoadedCount$v2LoadedLanguages, memory=${totalMemoryUsageMB}MB)"
        }
    }
}
