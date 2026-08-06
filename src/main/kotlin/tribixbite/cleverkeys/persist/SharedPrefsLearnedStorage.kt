package tribixbite.cleverkeys.persist

import android.content.SharedPreferences

/**
 * [LearnedDataStorage] adapter over Android [SharedPreferences].
 *
 * Writes use `apply()` (async disk write handled by the framework); reads hit
 * the in-memory prefs cache. This is the production storage for the learned
 * bigram LM and user vocabulary stores.
 */
class SharedPrefsLearnedStorage(private val prefs: SharedPreferences) : LearnedDataStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
