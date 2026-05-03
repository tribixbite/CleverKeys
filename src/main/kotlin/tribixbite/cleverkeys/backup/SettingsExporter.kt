package tribixbite.cleverkeys.backup

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Stateless helper that serializes a `prefs.all` snapshot to a Gson `JsonObject`,
 * filtering out internal-state keys via [SettingsValidation.INTERNAL_KEYS].
 *
 * Currently used as a count helper for export-result dialogs and as a unit-test
 * surface — not yet wired as the canonical body of `BackupRestoreManager.exportConfig`,
 * which retains the legacy default-prefs + JSON-string-preserve handling.
 * See `memory/todo.md` for the migration plan.
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
