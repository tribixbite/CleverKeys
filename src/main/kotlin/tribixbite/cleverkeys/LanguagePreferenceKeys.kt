package tribixbite.cleverkeys

import android.content.SharedPreferences
import android.util.Log

/**
 * Helper for language-specific preference keys.
 *
 * Prior to v1.1.86, custom_words and disabled_words were global (not language-specific).
 * This helper provides language-specific keys and migration utilities.
 *
 * @since v1.1.86
 */
object LanguagePreferenceKeys {
    private const val TAG = "LanguagePreferenceKeys"

    // Legacy global keys (pre-v1.1.86)
    private const val LEGACY_CUSTOM_WORDS = "custom_words"
    private const val LEGACY_DISABLED_WORDS = "disabled_words"

    // Version flag for migration
    private const val MIGRATION_VERSION_KEY = "lang_pref_migration_version"
    private const val CURRENT_MIGRATION_VERSION = 1

    /**
     * Get the custom words preference key for a language.
     *
     * @param languageCode ISO 639-1 language code (e.g., "en", "es", "fr")
     * @return Preference key like "custom_words_en"
     */
    fun customWordsKey(languageCode: String): String = "custom_words_${languageCode.lowercase()}"

    /**
     * Get the disabled words preference key for a language.
     *
     * @param languageCode ISO 639-1 language code (e.g., "en", "es", "fr")
     * @return Preference key like "disabled_words_en"
     */
    fun disabledWordsKey(languageCode: String): String = "disabled_words_${languageCode.lowercase()}"

    /**
     * Check if migration from global keys to language-specific keys is needed.
     *
     * @param prefs SharedPreferences to check
     * @return true if migration is needed
     */
    fun needsMigration(prefs: SharedPreferences): Boolean {
        val migrationVersion = prefs.getInt(MIGRATION_VERSION_KEY, 0)
        return migrationVersion < CURRENT_MIGRATION_VERSION
    }

    /**
     * Migrate legacy global custom/disabled words to English-specific keys.
     *
     * This should be called once on app startup (or vocabulary load) to
     * migrate existing user data to the new per-language format.
     *
     * Migration sources (in priority order):
     * 1. `custom_words` in DirectBootAwarePreferences (JSON Map format)
     * 2. `user_words` in separate "user_dictionary" SharedPreferences (StringSet format)
     * 3. `disabled_words` in DirectBootAwarePreferences (StringSet format)
     *
     * @param prefs SharedPreferences to migrate (DirectBootAwarePreferences)
     * @return true if migration was performed, false if already done
     */
    fun migrateToLanguageSpecific(prefs: SharedPreferences): Boolean {
        if (!needsMigration(prefs)) {
            return false
        }

        val editor = prefs.edit()
        val enCustomKey = customWordsKey("en")
        val enDisabledKey = disabledWordsKey("en")

        // Migrate custom_words JSON format to custom_words_en
        val legacyCustomWords = prefs.getString(LEGACY_CUSTOM_WORDS, null)
        if (legacyCustomWords != null && legacyCustomWords != "{}" && !prefs.contains(enCustomKey)) {
            editor.putString(enCustomKey, legacyCustomWords)
            Log.i(TAG, "Migrated custom_words (JSON) to $enCustomKey")
        }

        // Migrate disabled_words to disabled_words_en
        val legacyDisabledWords = prefs.getStringSet(LEGACY_DISABLED_WORDS, null)
        if (legacyDisabledWords != null && legacyDisabledWords.isNotEmpty() && !prefs.contains(enDisabledKey)) {
            editor.putStringSet(enDisabledKey, legacyDisabledWords)
            Log.i(TAG, "Migrated disabled_words to $enDisabledKey (${legacyDisabledWords.size} words)")
        }

        // Mark migration complete
        editor.putInt(MIGRATION_VERSION_KEY, CURRENT_MIGRATION_VERSION)
        editor.apply()

        Log.i(TAG, "Language preference migration complete (version $CURRENT_MIGRATION_VERSION)")
        return true
    }

    // NOTE: migrateUserDictionary() was removed in v1.2.2 - it was vestigial code that duplicated
    // DictionaryManager.migrateLegacyCustomWords() but with bugs (hardcoded "en", didn't clear legacy data).
    // DictionaryManager now handles all legacy migration correctly using Locale.getDefault().language.

    /**
     * Get all language codes that have custom words stored.
     *
     * @param prefs SharedPreferences to scan
     * @return List of language codes with custom words
     */
    fun getLanguagesWithCustomWords(prefs: SharedPreferences): List<String> {
        return prefs.all.keys
            .filter { it.startsWith("custom_words_") }
            .map { it.removePrefix("custom_words_") }
            .distinct()
    }

    /**
     * Get all language codes that have disabled words stored.
     *
     * @param prefs SharedPreferences to scan
     * @return List of language codes with disabled words
     */
    fun getLanguagesWithDisabledWords(prefs: SharedPreferences): List<String> {
        return prefs.all.keys
            .filter { it.startsWith("disabled_words_") }
            .map { it.removePrefix("disabled_words_") }
            .distinct()
    }
}
