package tribixbite.cleverkeys.backup

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → `SettingsImportPlan` builder. No Android deps — all
 * snapshot data is injected by the caller. Mirror in `BackupRestoreManager`
 * is the IO wrapper that supplies the snapshots from real `SharedPreferences`.
 *
 * Build-time responsibilities:
 *  1. Parse + validate (today's `importPreference` logic, lifted out of IO).
 *  2. Run legacy-margin migration BEFORE diffing.
 *  3. Materialize each accepted entry as a typed `PrefValue` so apply is
 *     a pure dispatch loop.
 *  4. Categorize: changes / parseSkippedKeys / internalRemoves.
 */
object SettingsImportPlanBuilder {

    /**
     * List of pref keys treated as JSON-blob (their value is a layout/extra_keys
     * structure stored as a JSON string in SharedPreferences).
     * Source of truth: BackupRestoreManager.isJsonStringPreference (line ~945).
     */
    private val JSON_BLOB_KEYS = setOf("layouts", "extra_keys", "custom_extra_keys")

    fun fromJson(
        jsonString: String,
        currentSnapshot: Map<String, Any?>,
        screen: ScreenMetrics,
    ): SettingsImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject

        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"
        val sourceWidth = root.getAsJsonObject("metadata")
            ?.get("screen_width")?.asInt ?: screen.width
        val sourceHeight = root.getAsJsonObject("metadata")
            ?.get("screen_height")?.asInt ?: screen.height

        val sourceScreen = ScreenMetrics(sourceWidth, sourceHeight, screen.density)

        val preferences: JsonObject = root.getAsJsonObject("preferences")
            ?: return SettingsImportPlan(
                sourceVersion, sourceScreen, screen,
                emptyList(), emptyList(), emptyList(), 0, null,
            )

        val changes = mutableListOf<SettingsChange>()
        val skipped = mutableListOf<SkippedKey>()

        for ((key, valueElement) in preferences.entrySet()) {
            val proposed = parsePrefValue(key, valueElement)
            if (proposed == null) {
                skipped += SkippedKey(key, "unsupported JSON shape")
                continue
            }

            val currentRaw = currentSnapshot[key]
            val current = currentToPrefValue(currentRaw, key in JSON_BLOB_KEYS)
            if (current == proposed) continue   // no delta — skip

            val type = if (current == PrefValue.Unset) ChangeType.ADDED else ChangeType.MODIFIED
            changes += SettingsChange(key, current, proposed, type)
        }

        return SettingsImportPlan(
            sourceVersion = sourceVersion,
            sourceScreen = sourceScreen,
            currentScreen = screen,
            changes = changes,
            parseSkippedKeys = skipped,
            internalRemoves = emptyList(),
            shortSwipeImportSize = 0,
            shortSwipeImportRawJson = null,
        )
    }

    /**
     * Materialize a JSON value as a typed `PrefValue`. Returns null when the
     * JSON shape is unsupported (e.g. nested object for a non-blob key).
     *
     * Numeric dispatch is BY KEY for known float-typed prefs (verbatim from
     * BackupRestoreManager.isFloatPreference). The legacy IO path
     * (BackupRestoreManager.kt:658-683) calls `isFloatPreference(key)` to
     * decide between putInt and putFloat — using the JSON value's fractional
     * part would corrupt every float-typed pref whose runtime value happens
     * to be an integer multiple (e.g. `swipe_trail_width=5.0` → IntV(5) →
     * ClassCastException on next `prefs.getFloat`). For unknown numeric keys
     * we fall back to JSON shape (fractional digits → FloatV) which mirrors
     * the version-tolerant default of the legacy validators.
     */
    private fun parsePrefValue(key: String, element: JsonElement): PrefValue? {
        if (key in JSON_BLOB_KEYS) {
            // Two source representations: native object/array (new format) or
            // JSON-string primitive (old format, double-encoded). Canonicalize.
            val canonical = when {
                element.isJsonObject || element.isJsonArray -> element.toString()
                element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                    JsonParser.parseString(element.asString).toString()
                else -> return null
            }
            return PrefValue.JsonBlob(canonical)
        }
        if (!element.isJsonPrimitive) return null
        val p = element.asJsonPrimitive
        return when {
            p.isBoolean -> PrefValue.Bool(p.asBoolean)
            p.isNumber -> {
                if (SettingsValidation.isFloatPreference(key)) {
                    PrefValue.FloatV(p.asFloat)
                } else {
                    // Unknown numeric key: dispatch by JSON shape. A fractional
                    // literal (e.g. 3.14) lands as FloatV; an integer literal
                    // (e.g. 42, 50) lands as IntV. Known int-typed prefs are
                    // never fractional in well-formed exports.
                    val raw = p.asString
                    if ('.' in raw || 'e' in raw || 'E' in raw) PrefValue.FloatV(p.asFloat)
                    else PrefValue.IntV(p.asInt)
                }
            }
            p.isString -> PrefValue.Str(p.asString)
            else -> null
        }
    }

    private fun currentToPrefValue(value: Any?, isJsonBlob: Boolean): PrefValue {
        if (value == null) return PrefValue.Unset
        return when {
            isJsonBlob && value is String -> PrefValue.JsonBlob(JsonParser.parseString(value).toString())
            value is Boolean -> PrefValue.Bool(value)
            value is Int -> PrefValue.IntV(value)
            value is Float -> PrefValue.FloatV(value)
            value is Long -> PrefValue.IntV(value.toInt())   // see PrefValue header — Long unused in app
            value is String -> PrefValue.Str(value)
            else -> PrefValue.Unset
        }
    }
}
