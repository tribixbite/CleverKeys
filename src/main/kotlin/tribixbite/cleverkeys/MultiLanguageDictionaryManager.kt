package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple language-specific dictionaries with lazy loading
 *
 * Provides efficient dictionary management for multi-language support:
 * - Lazy loading: Load dictionaries on-demand
 * - Caching: Keep loaded dictionaries in memory
 * - Thread-safe: Concurrent access from multiple threads
 * - Memory management: Track and control memory usage
 */
class MultiLanguageDictionaryManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "MultiLanguageDictionaryManager"
    }

    // Cached dictionaries (language code → OptimizedVocabulary)
    // Thread-safe for concurrent access
    private val dictionaries = ConcurrentHashMap<String, OptimizedVocabulary>()

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
}
