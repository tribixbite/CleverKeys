package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.swipe.SwipeEngineRouter
import tribixbite.cleverkeys.MarginPrefKeys
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlin.math.roundToInt
import tribixbite.cleverkeys.LanguagePreferenceKeys
import tribixbite.cleverkeys.SettingsRanges
import tribixbite.cleverkeys.SwipePriority

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

    /**
     * Every top-level `config.json` section this importer reads. `BackupRestoreManager.buildConfigJson`
     * may write only these (`SettingsDefaultsDriftTest` scans it); a store a restore must bring
     * back — like the DIY themes, missing from every backup until 2026-10-25 — needs one.
     */
    val CONFIG_SECTIONS: Set<String> = setOf(
        "metadata", "preferences", "short_swipe_customizations", CustomThemeBackup.SECTION_KEY,
    )

    /**
     * Build an import plan.
     *
     * @param defaultSnapshot maps SharedPreferences key → the value the app
     *   would read for that key when it's absent (i.e. `Defaults.X` as
     *   supplied to `prefs.get*(key, default)` in Config.kt). When provided,
     *   the diff treats `current=Unset && proposed=defaults[key]` as a
     *   no-op (skipped) instead of an ADDED change — fresh-install imports
     *   no longer over-report every key as "added" when most equal the
     *   compile-time default the user already experiences. Keys absent
     *   from this map fall back to the pre-fix behavior (ADDED with
     *   `current=Unset`).
     * @param currentCustomWordsByLang this device's custom words per language, the
     *   [currentDisabledWordsByLang] and [currentSwipePrioritiesByLang] likewise: what a 1.x
     *   file's dictionary is planned against ([SettingsImportPlan.legacyDictionary]). Left
     *   empty, every word and level in the file counts as new.
     */
    fun fromJson(
        jsonString: String,
        currentSnapshot: Map<String, Any?>,
        screen: ScreenMetrics,
        defaultSnapshot: Map<String, PrefValue> = emptyMap(),
        currentShortSwipeRawJson: String? = null,
        currentCustomThemesRawJson: String? = null,
        currentCustomWordsByLang: Map<String, Map<String, Int>> = emptyMap(),
        currentDisabledWordsByLang: Map<String, Set<String>> = emptyMap(),
        currentSwipePrioritiesByLang: Map<String, Map<String, SwipePriority>> = emptyMap(),
    ): SettingsImportPlan {
        val root = JsonParser.parseString(jsonString).asJsonObject

        // DIY themes travel beside the preferences (their store is a separate prefs file).
        // Parsed before the preferences so a file carrying only themes still imports them.
        val customThemes = CustomThemeBackup.parse(root.get(CustomThemeBackup.SECTION_KEY))
        val customThemesRaw = customThemes?.themes?.takeIf { it.isNotEmpty() }
            ?.let(CustomThemeBackup::toArrayJson)

        val sourceVersion = root.getAsJsonObject("metadata")
            ?.get("app_version")?.asString ?: "unknown"
        val sourceWidth = root.getAsJsonObject("metadata")
            ?.get("screen_width")?.asInt ?: screen.width
        val sourceHeight = root.getAsJsonObject("metadata")
            ?.get("screen_height")?.asInt ?: screen.height

        // The file's own density (written since the first exporter); a file without it shows
        // this device's, as before. It used to be this device's always: an S25U backup read
        // "@2.25" on a Pixel 8 Pro (device test 2026-11-23).
        val sourceDensity = root.getAsJsonObject("metadata")
            ?.get("screen_density")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asFloat ?: screen.density
        val sourceScreen = ScreenMetrics(sourceWidth, sourceHeight, sourceDensity)

        val rawPreferences: JsonObject = root.getAsJsonObject("preferences")
            ?: return SettingsImportPlan(
                sourceVersion, sourceScreen, screen,
                emptyList(), customThemes?.skipped.orEmpty(), emptyList(), 0, null,
                customThemesImportSize = customThemes?.themes?.size ?: 0,
                customThemesImportRawJson = customThemesRaw,
                currentCustomThemesRawJson = currentCustomThemesRawJson,
            )

        // Legacy-margin migration runs BEFORE the diff so the user sees only
        // the new-format keys in `changes` and the old keys are queued in
        // `internalRemoves` + `parseSkippedKeys`.
        val (preferences, migration) = applyLegacyMigration(rawPreferences, screen)

        val changes = mutableListOf<SettingsChange>()
        val skipped = mutableListOf<SkippedKey>()
        val legacyDictionary = LegacyDictionaryKeys()
        skipped += migration.skipped
        skipped += customThemes?.skipped.orEmpty()

        for ((key, valueElement) in preferences.entrySet()) {
            if (SettingsValidation.isInternalPreference(key)) {
                skipped += SkippedKey(key, "internal preference", SkipKind.INTERNAL)
                continue
            }
            if (SettingsValidation.isDeprecatedPreference(key)) {
                // Legacy export-seed pollution. No code reads the key, so
                // surfacing it as a preview row would be misleading. Drop
                // silently — also blocks it from being written by the apply
                // step (which only writes keys present in `plan.changes`).
                skipped += SkippedKey(key, "deprecated (no read site in current code)", SkipKind.DEPRECATED)
                continue
            }
            if (SettingsValidation.isDictionaryPreference(key)) {
                // Per-language dictionary words (`custom_words_<lang>`, `disabled_words_<lang>`,
                // `swipe_priority_<lang>`) are not settings rows. A 1.x settings file carries the
                // whole dictionary this way, so they travel to the dictionary applier as
                // [SettingsImportPlan.legacyDictionaryJson] (2026-11-24); a value of no known
                // shape stays a skipped key, for the dictionary file to restore, and a language
                // over the import cap is reported in the preview instead of failing at apply.
                when (legacyDictionary.add(key, valueElement)) {
                    LegacyDictionaryKeys.Taken.YES -> Unit
                    LegacyDictionaryKeys.Taken.UNREADABLE ->
                        skipped += SkippedKey(key, "dictionary words (separate import flow)", SkipKind.SEPARATE_IMPORT)
                    LegacyDictionaryKeys.Taken.TOO_MANY ->
                        skipped += SkippedKey(
                            key, "more than ${DictImportPlanBuilder.MAX_WORDS_PER_LANGUAGE} words",
                            SkipKind.INVALID_VALUE,
                        )
                }
                continue
            }

            val rawProposed = parsePrefValue(key, valueElement)
            if (rawProposed == null) {
                skipped += SkippedKey(key, "unsupported JSON shape", SkipKind.UNREADABLE)
                continue
            }

            val rangeError = SettingsValidation.validate(key, rawProposed)
            if (rangeError != null) {
                skipped += SkippedKey(key, rangeError, SkipKind.INVALID_VALUE)
                continue
            }

            val currentRaw = currentSnapshot[key]
            val rawCurrent = currentToPrefValue(currentRaw, key in JSON_BLOB_KEYS)

            // Stringly-typed numeric prefs (`clipboard_history_limit` etc.)
            // are stored as String "50" via legacy slider write paths AND
            // as Int 50 via newer setter methods. Normalize both sides to
            // the canonical storage type (String) before comparing, so a
            // pure type-flip doesn't surface as a value change.
            val current = canonicalize(key, rawCurrent)
            val proposed = canonicalize(key, rawProposed)
            val typeError = SettingsValidation.storageTypeError(key, proposed)
            if (typeError != null) {
                skipped += SkippedKey(key, typeError, SkipKind.INVALID_VALUE)
                continue
            }

            // Effective state: what the user reads from prefs RIGHT NOW. When
            // the key is present, that's the stored value; when absent, it's
            // the compile-time default `prefs.get*(key, default)` returns.
            // The diff is against this effective value — comparing against
            // `Unset` would emit a noisy "ADDED: unset → default" row for
            // every key in a fresh-install backup.
            val effective = if (current == PrefValue.Unset)
                defaultSnapshot[key] ?: lookupDefault(key) ?: PrefValue.Unset
            else
                current
            if (effective == proposed) continue   // no perceived change — skip

            val type = if (current == PrefValue.Unset) ChangeType.ADDED else ChangeType.MODIFIED
            changes += SettingsChange(key, effective, proposed, type)
        }

        // Short-swipe section is captured raw — applier hands it to ShortSwipeImporter.
        // G-2 (comprehensive audit 2026-09-06): the importer's Gson model parses ONLY the
        // wrapped v2 shape {version, mappings}. A legacy flat section (keyCodes at the top
        // level) passed through verbatim previewed N mappings but imported 0 — and REPLACE
        // mode had already cleared the user's set. Normalize flat → wrapped HERE so the
        // shape the preview counts is byte-for-byte the shape the importer parses.
        val shortSwipeJson = root.getAsJsonObject("short_swipe_customizations")
        val normalizedShortSwipe = shortSwipeJson?.let { section ->
            if (section.get("mappings")?.isJsonObject == true) {
                section
            } else {
                JsonObject().apply {
                    addProperty("version", 2)
                    add("mappings", section)
                }
            }
        }
        val shortSwipeRaw = normalizedShortSwipe?.toString()
        // The preview's unit is key×direction MAPPINGS. The v2 export wraps them —
        // {"mappings": {...}, "version": N} — and counting the wrapper's own keys reported
        // "2 short-swipe mappings" for an EMPTY backup (on-device finding, 2026-09-03).
        // Post-normalization both source shapes count the same way. Non-object direction
        // values contribute 0.
        val shortSwipeMappings = normalizedShortSwipe?.getAsJsonObject("mappings")
        val shortSwipeSize = shortSwipeMappings?.entrySet()?.sumOf { (_, byDirection) ->
            if (byDirection.isJsonObject) byDirection.asJsonObject.entrySet().size else 0
        } ?: 0

        val legacyDictionaryJson = legacyDictionary.toJsonOrNull()
        return SettingsImportPlan(
            sourceVersion = sourceVersion,
            sourceScreen = sourceScreen,
            currentScreen = screen,
            changes = changes,
            parseSkippedKeys = skipped,
            internalRemoves = migration.removes,
            shortSwipeImportSize = shortSwipeSize,
            shortSwipeImportRawJson = shortSwipeRaw,
            currentShortSwipeRawJson = currentShortSwipeRawJson,
            customThemesImportSize = customThemes?.themes?.size ?: 0,
            customThemesImportRawJson = customThemesRaw,
            currentCustomThemesRawJson = currentCustomThemesRawJson,
            legacyDictionaryJson = legacyDictionaryJson,
            legacyDictionary = legacyDictionaryJson
                ?.let {
                    DictImportPlanBuilder.fromJson(
                        it, currentCustomWordsByLang, currentDisabledWordsByLang, currentSwipePrioritiesByLang,
                    )
                }
                ?.takeIf { it.hasChanges },
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
        for ((oldKey, newKeys) in MarginPrefKeys.LEGACY_HORIZONTAL) {
            if (out.has(oldKey) && !out.has(newKeys[0])) {
                try {
                    val dpValue = out.get(oldKey).asInt
                    val pixelValue = dpValue * screen.density
                    val percentValue = ((pixelValue / screen.width) * 100).toInt().coerceIn(SettingsRanges.MARGIN_SIDE_PERCENT)
                    for (newKey in newKeys) out.addProperty(newKey, percentValue)
                    out.remove(oldKey)
                    removes += oldKey
                    skipped += SkippedKey(oldKey, "superseded by ${newKeys[0]} + ${newKeys[1]}", SkipKind.SUPERSEDED)
                } catch (_: Exception) {
                    // Malformed value — leave for the validator to reject downstream.
                }
            }
        }

        // 2. Bottom-margin dp→% conversion (only when value > 30, matching legacy guard).
        for (key in MarginPrefKeys.BOTTOM_KEYS) {
            if (out.has(key)) {
                try {
                    val value = out.get(key).asInt
                    if (value > SettingsRanges.MARGIN_BOTTOM_PERCENT.last) {  // above the % range: legacy dp
                        val pixelValue = value * screen.density
                        val percentValue = ((pixelValue / screen.height) * 100).toInt().coerceIn(SettingsRanges.MARGIN_BOTTOM_PERCENT)
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
     *
     * ARC-093: shape dispatch is WRONG in the other direction too. A pref the
     * app reads with `getInt` can still appear as a fractional literal in a
     * legacy export — `finger_occlusion_offset` shipped behind a float slider
     * in v1.5.x, so those backups carry `12.5` / `40.0`. Dispatching those by
     * shape produced a `FloatV`, which `validateFloat` waves through
     * (`else -> true`), so the key's own int guard never ran and the wrong
     * storage TYPE was written; `Config.safeGetInt` then rescued it by
     * catching the ClassCastException and truncating + clamping at READ time —
     * lossy and invisible in the preview. [coerceLegacyFractionalInt] treats
     * that case as what it is, a legacy-format migration: round, clamp into
     * the key's validated range, and let the row render normally. Same shape
     * as [applyLegacyMigration]'s dp→% conversion.
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
                    // Dispatch by JSON shape. A fractional literal (e.g. 3.14)
                    // lands as FloatV; an integer literal (e.g. 42, 50) lands as
                    // IntV — unless the key's DECLARED default says it is
                    // int-typed, in which case the fractional literal is a legacy
                    // representation to migrate rather than a value to preserve.
                    val raw = p.asString
                    val fractional = '.' in raw || 'e' in raw || 'E' in raw
                    val declared = lookupDefault(key)
                    when {
                        !fractional -> PrefValue.IntV(p.asInt)
                        declared is PrefValue.IntV && LEGACY_DEFAULTS[key] == p.asFloat -> declared
                        declared is PrefValue.IntV ->
                            PrefValue.IntV(coerceLegacyFractionalInt(key, p.asFloat))
                        else -> PrefValue.FloatV(p.asFloat)
                    }
                }
            }
            p.isString -> PrefValue.Str(p.asString)
            else -> null
        }
    }

    /**
     * ARC-093: turn a legacy FRACTIONAL literal for an int-typed pref into the
     * integer that pref will actually be read as.
     *
     * Rounding is [kotlin.math.roundToInt] (half-up), which keeps a calibrated
     * 12.5 as 13 rather than the 12 that `Config.safeGetInt`'s `toInt()`
     * truncation produced. Clamping into [SettingsValidation.intRangeFor] then
     * puts the migrated value inside the same bounds the validator enforces, so
     * the row survives validation and the preview shows the number that will be
     * written — instead of the value slipping through as a float and being
     * clamped later, unseen, by the read site's own `coerceIn`.
     *
     * The clamp is deliberately confined to this legacy path. A well-formed
     * INTEGER literal out of range keeps its visible rejection in
     * `SettingsValidation.validate`; clamping that too would be a new silent
     * value rewrite, which is the very behaviour ARC-093 was filed against.
     */
    /**
     * 1.x defaults, written as fractional literals because 1.x exported every effective value, of
     * settings whose default 2.0 changed. Such a value means "the default", not a calibration, so
     * it imports as today's default ([parsePrefValue]); any other fractional value still rounds.
     *
     * `finger_occlusion_offset` 12.5 was the neural engine's default, never measured (Config's
     * note); 2.0 defaults to 0 because CTC was trained on uncorrected touches, and an in-place
     * upgrade from 1.5 reads 0. Restoring a 1.4/1.5 backup set 13 (Pixel and Note10+, 2026-11-24).
     */
    private val LEGACY_DEFAULTS: Map<String, Float> = mapOf("finger_occlusion_offset" to 12.5f)

    private fun coerceLegacyFractionalInt(key: String, value: Float): Int {
        val rounded = value.roundToInt()
        val range = SettingsValidation.intRangeFor(key) ?: return rounded
        return rounded.coerceIn(range.first, range.last)
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

    /**
     * Keys stored as String "50" by legacy slider-writer paths AND as Int
     * 50 by newer setter methods (e.g. `Config.set_clipboard_history_limit`
     * does `putInt`, but the slider UI writes via `putString`). Canonicalize
     * to `Str` for the diff so a pure type-flip isn't surfaced as a value
     * change. The read path already does `getString(…).toIntOrNull()` so
     * either form works at runtime.
     */
    private val STRING_BUT_NUMERIC_KEYS = setOf(
        "clipboard_history_limit",
        "clipboard_max_item_size_kb",
        "clipboard_size_limit_mb",
    )

    /**
     * Enum-like string prefs whose consumers compare lowercase (`SwipeEngineRouter.
     * Mode.fromPref`, the settings dropdown). Canonicalized to lowercase at parse
     * time (audit L1) so an imported "CTC" both diffs against a stored "ctc" as
     * unchanged AND applies in the canonical form every reader expects.
     */
    private val CASE_CANONICAL_STRING_KEYS = setOf(
        SwipeEngineRouter.Mode.PREF_KEY,
    )

    private fun canonicalize(key: String, v: PrefValue): PrefValue {
        if (key in CASE_CANONICAL_STRING_KEYS && v is PrefValue.Str) {
            return PrefValue.Str(v.v.lowercase(java.util.Locale.ROOT))
        }
        if (key !in STRING_BUT_NUMERIC_KEYS) return v
        return when (v) {
            is PrefValue.IntV -> PrefValue.Str(v.v.toString())
            is PrefValue.FloatV -> PrefValue.Str(v.v.toInt().toString())  // shouldn't happen but defensive
            else -> v
        }
    }
}

/**
 * Collects a settings file's dictionary keys — a 1.x export carries the whole dictionary as
 * `custom_words_<lang>`, `disabled_words_<lang>` and (2.0 dev exports before 2026-11-15)
 * `swipe_priority_<lang>` — into the dictionaries file's shape (`custom_words_by_language`,
 * `disabled_words_by_language`, [SwipePriority.BACKUP_SECTION]) that `DictImportPlanBuilder`
 * reads. Accepts the stored JSON-string form and the native object/list form of each key.
 */
private class LegacyDictionaryKeys {
    private val custom = JsonObject()
    private val disabled = JsonObject()
    private val priorities = JsonObject()

    /** Whether [add] took a key. */
    enum class Taken {
        YES,

        /** The value has no known shape. */
        UNREADABLE,

        /** More words than one language may import ([DictImportPlanBuilder.MAX_WORDS_PER_LANGUAGE]). */
        TOO_MANY,
    }

    /** Take [key]'s [value]; the caller skips a key that is not [Taken.YES]. */
    fun add(key: String, value: JsonElement): Taken {
        LanguagePreferenceKeys.languageFromCustomWordsKey(key)?.let { lang ->
            val words = asObject(value)
                ?.takeIf { obj -> obj.entrySet().all { (_, freq) -> isNumber(freq) } }
                ?: return Taken.UNREADABLE
            if (words.size() > DictImportPlanBuilder.MAX_WORDS_PER_LANGUAGE) return Taken.TOO_MANY
            custom.add(lang, words)
            return Taken.YES
        }
        LanguagePreferenceKeys.languageFromDisabledWordsKey(key)?.let { lang ->
            val words = asArray(value)
                ?.takeIf { arr -> arr.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } }
                ?: return Taken.UNREADABLE
            if (words.size() > DictImportPlanBuilder.MAX_WORDS_PER_LANGUAGE) return Taken.TOO_MANY
            disabled.add(lang, words)
            return Taken.YES
        }
        LanguagePreferenceKeys.languageFromSwipePriorityKey(key)?.let { lang ->
            val levels = asObject(value) ?: return Taken.UNREADABLE
            priorities.add(lang, levels)
            return Taken.YES
        }
        return Taken.UNREADABLE
    }

    /** The collected sections as one dictionaries-file JSON, or null when none were collected. */
    fun toJsonOrNull(): String? {
        if (custom.size() == 0 && disabled.size() == 0 && priorities.size() == 0) return null
        return JsonObject().apply {
            if (custom.size() > 0) add("custom_words_by_language", custom)
            if (disabled.size() > 0) add("disabled_words_by_language", disabled)
            if (priorities.size() > 0) add(SwipePriority.BACKUP_SECTION, priorities)
        }.toString()
    }

    private fun isNumber(e: JsonElement): Boolean = e.isJsonPrimitive && e.asJsonPrimitive.isNumber

    /** An object, or a JSON string holding one (how the preference itself stores it). */
    private fun asObject(value: JsonElement): JsonObject? = when {
        value.isJsonObject -> value.asJsonObject
        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
            parse(value.asString)?.takeIf { it.isJsonObject }?.asJsonObject
        else -> null
    }

    /** A list, or a JSON string holding one. */
    private fun asArray(value: JsonElement): JsonArray? = when {
        value.isJsonArray -> value.asJsonArray
        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
            parse(value.asString)?.takeIf { it.isJsonArray }?.asJsonArray
        else -> null
    }

    private fun parse(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (e: JsonParseException) {
        null
    }
}
