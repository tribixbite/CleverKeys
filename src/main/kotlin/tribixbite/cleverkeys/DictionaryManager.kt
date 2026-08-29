package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * The active prediction language and the user's custom words for it.
 *
 * v1.2.2: Uses same storage as CustomDictionarySource (custom_words_{lang} in DirectBootAwarePreferences)
 * so words added here appear in Dictionary Manager UI and can be deleted.
 *
 * ## What this class is NOT (ARC-079, 2026-08-29)
 *
 * It used to also keep a per-language [WordPredictor] cache, retaining one predictor for each
 * of the up-to-4 configured language slots so a slot toggle would not pay an async reload.
 * Each of those predictors loaded a **full dictionary** — the same ~5-10 MB
 * `PredictionCoordinator` had already loaded for the language actually being typed in — and
 * **no consumer ever read a prediction out of them**: nothing here handed a predictor out, and
 * every consumer gets theirs from `PredictionCoordinator.getWordPredictor()`. The cache's only
 * readers were its own bookkeeping (`isLoading`/`flushLearnedData`/`cleanup`) plus a
 * `preloadLanguages()` with zero callers. It also started a redundant UserDictionary
 * ContentObserver per cached language.
 *
 * The learned data those predictors appeared to protect is not per-instance: `ContextModel`
 * goes through `BigramStore`/`TrigramStore.getInstance`, and `PersonalizationEngine` through
 * `UserVocabulary.getInstance` — process singletons. So the coordinator's single predictor
 * flushes exactly the same stores, and the deletion loses no user learning. See
 * `PredictionCoordinatorLifecycleTest` for the surviving flush/teardown contract and
 * `LearningWiringDriftTest` for the residency pin that keeps a predictor out of this class.
 */
class DictionaryManager(private val context: Context) {

    // Use DirectBootAwarePreferences for consistency with CustomDictionarySource
    private val prefs: SharedPreferences = DirectBootAwarePreferences.get_shared_preferences(context)
    private val gson = Gson()
    private val userWords = mutableSetOf<String>()
    private var currentLanguage: String = "en"

    init {
        // Pre-v1.1.86 GLOBAL custom_words/disabled_words → the per-language `_en` keys.
        // Re-homed here 2026-08-18: this ran inside OptimizedVocabulary's load, which is
        // being deleted with the neural engine. It must run BEFORE migrateLegacyCustomWords
        // below, because that one CREATES custom_words_<lang> and this one skips whenever
        // custom_words_en already exists — reversing the order would strand every
        // pre-v1.1.86 user's custom and disabled words. Idempotent (version-flagged).
        LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)

        // Migrate legacy custom words BEFORE loading (one-time migration)
        migrateLegacyCustomWords()
        setLanguage(Locale.getDefault().language)
        loadUserWords()
    }

    /**
     * Migrate custom words from legacy storage format to new format.
     *
     * Legacy format (pre-v1.2.2):
     *   - SharedPreferences file: "user_dictionary"
     *   - Key: "user_words"
     *   - Format: StringSet
     *
     * New format (v1.2.2+):
     *   - SharedPreferences: DirectBootAwarePreferences
     *   - Key: "custom_words_{lang}" (e.g., "custom_words_en")
     *   - Format: JSON map {"word": frequency}
     *
     * This migration runs once and clears the legacy data after successful migration.
     */
    private fun migrateLegacyCustomWords() {
        try {
            val legacyPrefs = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE)
            val legacyWords = legacyPrefs.getStringSet("user_words", null)

            if (legacyWords.isNullOrEmpty()) {
                // No legacy data to migrate
                return
            }

            Log.i(TAG, "Found ${legacyWords.size} legacy custom words to migrate")

            // Migrate to the default language (usually "en", but use system locale)
            val migrationLang = Locale.getDefault().language
            val targetKey = LanguagePreferenceKeys.customWordsKey(migrationLang)

            // Load any existing words in the new format
            val existingJson = prefs.getString(targetKey, null)
            val existingWords: MutableMap<String, Int> = if (existingJson != null) {
                try {
                    val type = object : TypeToken<MutableMap<String, Int>>() {}.type
                    gson.fromJson(existingJson, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }

            // Merge legacy words (don't overwrite existing words with same key)
            var migratedCount = 0
            for (word in legacyWords) {
                if (!existingWords.containsKey(word)) {
                    existingWords[word] = 100 // Default frequency
                    migratedCount++
                }
            }

            // Save merged words to new format
            prefs.edit()
                .putString(targetKey, gson.toJson(existingWords))
                .apply()

            // Clear legacy data after successful migration
            legacyPrefs.edit()
                .remove("user_words")
                .apply()

            Log.i(TAG, "Migrated $migratedCount legacy words to '$targetKey' (${existingWords.size} total)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy custom words", e)
        }
    }

    /**
     * Set the active prediction language.
     *
     * Pure bookkeeping since ARC-079: it records the language and swaps in that language's
     * user-word set. The dictionary behind it is loaded by the ONE predictor the process
     * owns — `PredictionCoordinator.reloadWordPredictorDictionary` / `setConfig` call
     * `wordPredictor.loadDictionaryAsync(...)` and this method together, so there is no path
     * that moves the active language without reloading the serving dictionary.
     *
     * Callers rely on this being SYNCHRONOUS: `PreferenceUIUpdateHandler` re-warms the swipe
     * engine immediately afterwards and the prewarm reads [getCurrentLanguage].
     */
    fun setLanguage(languageCode: String?) {
        val code = languageCode ?: "en"
        val languageChanged = currentLanguage != code
        currentLanguage = code

        // Reload user words if language changed
        if (languageChanged) {
            loadUserWords()
        }
    }

    /**
     * Add a word to the user dictionary for current language.
     * Uses same storage as CustomDictionarySource so words appear in Dictionary Manager.
     */
    fun addUserWord(word: String?) {
        if (word.isNullOrEmpty()) return

        userWords.add(word)
        saveUserWords()
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Added '$word' to custom words for '$currentLanguage'")
    }

    /**
     * Remove a word from the user dictionary
     */
    fun removeUserWord(word: String) {
        userWords.remove(word)
        saveUserWords()
    }

    /**
     * Check if a word is in the user dictionary
     */
    fun isUserWord(word: String): Boolean = word in userWords

    /**
     * Clear the user dictionary
     */
    fun clearUserDictionary() {
        userWords.clear()
        saveUserWords()
    }

    /**
     * Get the custom words key for current language.
     * Uses LanguagePreferenceKeys for consistency with CustomDictionarySource.
     */
    private fun getCustomWordsKey(): String {
        return LanguagePreferenceKeys.customWordsKey(currentLanguage)
    }

    /**
     * Load user words from preferences (JSON format matching CustomDictionarySource)
     */
    private fun loadUserWords() {
        val key = getCustomWordsKey()
        val jsonString = prefs.getString(key, null)
        userWords.clear()

        if (jsonString != null) {
            try {
                val type = object : TypeToken<MutableMap<String, Int>>() {}.type
                val wordsMap: MutableMap<String, Int>? = gson.fromJson(jsonString, type)
                wordsMap?.keys?.let { userWords.addAll(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse custom words JSON", e)
            }
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Loaded ${userWords.size} custom words for '$currentLanguage'")
    }

    /**
     * Save user words to preferences (JSON format matching CustomDictionarySource)
     */
    private fun saveUserWords() {
        val key = getCustomWordsKey()
        // Convert to map with default frequency of 100
        val wordsMap = userWords.associateWith { 100 }
        prefs.edit()
            .putString(key, gson.toJson(wordsMap))
            .apply()
    }

    /**
     * Get the current language code
     */
    fun getCurrentLanguage(): String? = currentLanguage

    // ARC-079 (2026-08-29) — deleted with the per-language predictor cache:
    //
    //   isLoading()             had no production caller (one instrumented smoke test only);
    //                           load state belongs to the serving predictor, which consumers
    //                           reach via PredictionCoordinator.getWordPredictor().
    //   preloadLanguages()      had NO callers at all — it existed solely to warm the cache.
    //   getConfiguredLanguages() served only the cache's eviction retention set.
    //   flushLearnedData()      \ both fanned out to cached predictors that were never fed a
    //   cleanup()               / word; the coordinator flushes the same singleton stores.
    //
    // PredictionCoordinator.flushLearnedData/shutdown are now the sole learned-data
    // checkpoints (PredictionCoordinatorLifecycleTest).

    companion object {
        private const val TAG = "DictionaryManager"
    }
}
