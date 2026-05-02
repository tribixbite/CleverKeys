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

        val rawPreferences: JsonObject = root.getAsJsonObject("preferences")
            ?: return SettingsImportPlan(
                sourceVersion, sourceScreen, screen,
                emptyList(), emptyList(), emptyList(), 0, null,
            )

        // Legacy-margin migration runs BEFORE the diff so the user sees only
        // the new-format keys in `changes` and the old keys are queued in
        // `internalRemoves` + `parseSkippedKeys`.
        val (preferences, migration) = applyLegacyMigration(rawPreferences, screen)

        val changes = mutableListOf<SettingsChange>()
        val skipped = mutableListOf<SkippedKey>()
        skipped += migration.skipped

        for ((key, valueElement) in preferences.entrySet()) {
            if (SettingsValidation.isInternalPreference(key)) {
                skipped += SkippedKey(key, "internal preference")
                continue
            }

            val proposed = parsePrefValue(key, valueElement)
            if (proposed == null) {
                skipped += SkippedKey(key, "unsupported JSON shape")
                continue
            }

            val rangeError = SettingsValidation.validate(key, proposed)
            if (rangeError != null) {
                skipped += SkippedKey(key, rangeError)
                continue
            }

            val currentRaw = currentSnapshot[key]
            val current = currentToPrefValue(currentRaw, key in JSON_BLOB_KEYS)
            if (current == proposed) continue   // no delta — skip

            val type = if (current == PrefValue.Unset) ChangeType.ADDED else ChangeType.MODIFIED
            changes += SettingsChange(key, current, proposed, type)
        }

        // Short-swipe section is captured raw — applier hands it to ShortSwipeImporter.
        val shortSwipeJson = root.getAsJsonObject("short_swipe_customizations")
        val shortSwipeRaw = shortSwipeJson?.toString()
        val shortSwipeSize = shortSwipeJson?.entrySet()?.size ?: 0

        return SettingsImportPlan(
            sourceVersion = sourceVersion,
            sourceScreen = sourceScreen,
            currentScreen = screen,
            changes = changes,
            parseSkippedKeys = skipped,
            internalRemoves = migration.removes,
            shortSwipeImportSize = shortSwipeSize,
            shortSwipeImportRawJson = shortSwipeRaw,
        )
    }

    /**
     * Pure equivalent of `BackupRestoreManager.migrateLegacyMargins`
     * (lines 876-939 of BackupRestoreManager.kt as of v1.4.0).
     *
     * Handles four horizontal-margin pairs (portrait, landscape, portrait_unfolded,
     * landscape_unfolded) AND `margin_bottom_*` dp→% conversion using
     * `screen.density`. Verbatim port — do not abbreviate.
     */
    private fun applyLegacyMigration(
        prefs: JsonObject,
        screen: ScreenMetrics,
    ): Pair<JsonObject, MigrationOutputs> {
        val out = prefs.deepCopy()
        val removes = mutableListOf<String>()
        val skipped = mutableListOf<SkippedKey>()

        // 1. Horizontal-margin pairs — symmetric dp→% conversion.
        val horizontalKeys = listOf(
            "horizontal_margin_portrait" to listOf("margin_left_portrait", "margin_right_portrait"),
            "horizontal_margin_landscape" to listOf("margin_left_landscape", "margin_right_landscape"),
            "horizontal_margin_portrait_unfolded" to listOf("margin_left_portrait_unfolded", "margin_right_portrait_unfolded"),
            "horizontal_margin_landscape_unfolded" to listOf("margin_left_landscape_unfolded", "margin_right_landscape_unfolded"),
        )
        for ((oldKey, newKeys) in horizontalKeys) {
            if (out.has(oldKey) && !out.has(newKeys[0])) {
                try {
                    val dpValue = out.get(oldKey).asInt
                    val pixelValue = dpValue * screen.density
                    val percentValue = ((pixelValue / screen.width) * 100).toInt().coerceIn(0, 45)
                    for (newKey in newKeys) out.addProperty(newKey, percentValue)
                    out.remove(oldKey)
                    removes += oldKey
                    skipped += SkippedKey(oldKey, "superseded by ${newKeys[0]} + ${newKeys[1]}")
                } catch (_: Exception) {
                    // Malformed value — leave for the validator to reject downstream.
                }
            }
        }

        // 2. Bottom-margin dp→% conversion (only when value > 30, matching legacy guard).
        val bottomKeys = listOf(
            "margin_bottom_portrait",
            "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded",
            "margin_bottom_landscape_unfolded",
        )
        for (key in bottomKeys) {
            if (out.has(key)) {
                try {
                    val value = out.get(key).asInt
                    if (value > 30) {
                        val pixelValue = value * screen.density
                        val percentValue = ((pixelValue / screen.height) * 100).toInt().coerceIn(0, 30)
                        out.addProperty(key, percentValue)
                        // Same key — rewritten in-place; not a SkippedKey entry.
                    }
                } catch (_: Exception) { /* same as above */ }
            }
        }

        return out to MigrationOutputs(removes, skipped)
    }

    private data class MigrationOutputs(
        val removes: List<String>,
        val skipped: List<SkippedKey>,
    )

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
