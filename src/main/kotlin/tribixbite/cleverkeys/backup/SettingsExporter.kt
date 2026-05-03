package tribixbite.cleverkeys.backup

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Stateless settings export. Serializes filtered prefs into a JsonObject
 * and returns the count of entries written. The caller (BackupRestoreManager)
 * wraps the JsonObject in metadata + writes to URI.
 *
 * Symmetric counterpart to [SettingsImportApplier]: both share the
 * `internalKeys` filter ([SettingsValidation.INTERNAL_KEYS]) so any value
 * that survives an export round-trips back through import.
 */
object SettingsExporter {
    /**
     * Returns `(preferencesJsonObject, count)`. `internalKeys` defaults to
     * the same `SettingsValidation.INTERNAL_KEYS` set used by import to skip
     * service-managed state — symmetry guarantees export/import round-trip.
     *
     * Type handling matches Android's [android.content.SharedPreferences.Editor]
     * supported scalar types plus `Set<String>` (rendered as a JSON array of
     * stringified elements). Unknown types are silently dropped — same
     * tolerance as [SharedPreferences.getAll].
     */
    fun buildPreferencesJson(
        prefsAll: Map<String, Any?>,
        internalKeys: Set<String> = SettingsValidation.INTERNAL_KEYS,
    ): Pair<JsonObject, Int> {
        val obj = JsonObject()
        var count = 0
        for ((key, value) in prefsAll) {
            if (key in internalKeys) continue
            when (value) {
                is Boolean -> obj.addProperty(key, value)
                is Int -> obj.addProperty(key, value)
                is Long -> obj.addProperty(key, value)
                is Float -> obj.addProperty(key, value)
                is String -> obj.addProperty(key, value)
                is Set<*> -> {
                    val arr = JsonArray()
                    value.forEach { arr.add(it.toString()) }
                    obj.add(key, arr)
                }
                else -> continue
            }
            count++
        }
        return obj to count
    }
}
