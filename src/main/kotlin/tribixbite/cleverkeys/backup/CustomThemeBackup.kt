package tribixbite.cleverkeys.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import tribixbite.cleverkeys.theme.CustomTheme
import tribixbite.cleverkeys.theme.CustomThemeManager
import tribixbite.cleverkeys.theme.ThemeSpecJson

/**
 * DIY (custom) keyboard themes in a settings backup.
 *
 * The themes live in their own prefs file (`custom_keyboard_themes`, see `CustomThemeManager`),
 * not in the settings preferences, so they travel as a separate top-level section of
 * `config.json` — the settings export and, through it, the full backup ZIP:
 *
 * ```
 * "custom_themes": { "version": 1, "themes": [ <CustomTheme.toJson()>, ... ] }
 * ```
 *
 * Before 2026-10-25 no backup carried them: a reinstall plus a restore lost every DIY theme,
 * and a restored `theme = custom_<id>` (or a layout bound to one) fell back to the default.
 *
 * Import mirrors the short-swipe section: the preview shows one row per theme and a
 * Skip / Merge / Replace choice ([ShortSwipeImportMode]); Merge lets the file win on an id
 * collision and keeps the other local themes, Replace installs exactly the file's set.
 *
 * Pure (Gson only, no Android), so validation, the diff and the merge are unit-tested.
 */
object CustomThemeBackup {

    /** Top-level `config.json` key of the section. */
    const val SECTION_KEY = "custom_themes"

    /** Section format this build writes and reads; a newer section is skipped whole. */
    const val FORMAT_VERSION = 1

    /** The most DIY themes the store keeps (`CustomThemeManager`'s own limit). */
    const val MAX_THEMES = CustomThemeManager.MAX_CUSTOM_THEMES

    /** Longest accepted theme id: store ids are UUIDs (36 characters). */
    private const val MAX_ID_LENGTH = 64

    /** Longest accepted theme name. */
    private const val MAX_NAME_LENGTH = 200

    /** Theme ids become `custom_<id>` preference values, so only plain id characters pass. */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]+")

    /**
     * The 17 `KeyboardColorScheme` fields as `CustomTheme.toJson` names them. Drift-pinned
     * against the real serializer by `CustomThemeBackupFormatTest` (mock tier).
     */
    val COLOR_KEYS: List<String> = listOf(
        "keyDefault", "keyActivated", "keyLocked", "keyModifier", "keySpecial",
        "keyLabel", "keySubLabel", "keySecondaryLabel",
        "keyBorder", "keyBorderActivated",
        "swipeTrail", "ripple",
        "suggestionText", "suggestionBackground", "suggestionHighConfidence",
        "keyboardBackground", "keyboardSurface",
    )

    /**
     * A parsed section: the valid themes in file order (normalized to the stored shape, first
     * occurrence of an id wins) and one [SkippedKey] per entry that cannot be imported.
     */
    data class Section(val themes: List<JsonObject>, val skipped: List<SkippedKey>)

    /** Wrap the stored themes (a JSON array of `CustomTheme.toJson` objects) as the section. */
    fun section(themesArrayJson: String): JsonObject = JsonObject().apply {
        addProperty("version", FORMAT_VERSION)
        add("themes", JsonParser.parseString(themesArrayJson).asJsonArray)
    }

    /**
     * Parse the section of an imported file. Null when the file has no section (older backups);
     * an unreadable or newer section yields no themes and a skipped row explaining why.
     */
    fun parse(element: JsonElement?): Section? {
        if (element == null || element.isJsonNull) return null
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            ?: return unreadable("not an object")
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: return unreadable("missing version")
        if (version > FORMAT_VERSION) {
            return unreadable("section version $version is newer than $FORMAT_VERSION")
        }
        val array = obj.get("themes")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return unreadable("missing themes array")
        return parseThemes(array)
    }

    /**
     * Parse a stored-themes JSON array: the device's own themes, or a plan's already-validated
     * file themes. Lenient on purpose — every object with a string `id` is kept as it is, so a
     * Merge never drops a local theme this validator would have refused in a file. Null when
     * the text is not a JSON array.
     */
    fun parseStored(themesArrayJson: String?): List<JsonObject>? {
        if (themesArrayJson == null) return null
        return try {
            val element = JsonParser.parseString(themesArrayJson)
            if (!element.isJsonArray) return null
            element.asJsonArray.mapNotNull { entry ->
                entry.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { stringField(it, "id") != null }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseThemes(array: JsonArray): Section {
        val themes = mutableListOf<JsonObject>()
        val skipped = mutableListOf<SkippedKey>()
        val seen = mutableSetOf<String>()
        array.forEachIndexed { index, entry ->
            val label = "$SECTION_KEY[$index]"
            val normalized = normalize(entry)
            when {
                normalized == null -> skipped += SkippedKey(label, "not a valid DIY theme", SkipKind.UNREADABLE)
                !seen.add(idOf(normalized)) -> skipped += SkippedKey(label, "duplicate theme id", SkipKind.INVALID_VALUE)
                else -> themes += normalized
            }
        }
        return Section(themes, skipped)
    }

    private fun unreadable(reason: String) =
        Section(emptyList(), listOf(SkippedKey(SECTION_KEY, reason, SkipKind.UNREADABLE)))

    /**
     * The theme in the exact stored shape (`id`, `name`, `createdAt`, `modifiedAt`, `colors`
     * with every [COLOR_KEYS] entry as an ARGB int, and — format version 2 — `formatVersion` and
     * the `look` extension, re-serialized by [ThemeSpecJson] so only known, in-range fields
     * survive), or null when any part is missing or malformed. Unknown fields are dropped, so a
     * later format's extras never reach the store.
     */
    private fun normalize(entry: JsonElement): JsonObject? {
        val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = stringField(obj, "id")?.takeIf { it.length <= MAX_ID_LENGTH && ID_PATTERN.matches(it) }
            ?: return null
        val name = stringField(obj, "name")?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_LENGTH }
            ?: return null
        val createdAt = longField(obj, "createdAt") ?: return null
        val modifiedAt = longField(obj, "modifiedAt") ?: return null
        val colors = obj.get("colors")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val normalizedColors = JsonObject()
        for (key in COLOR_KEYS) {
            val value = longField(colors, key) ?: return null
            // toArgb() serializes a signed Int; accept the unsigned spelling of the same bits too.
            val argb = when (value) {
                in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> value.toInt()
                in 0L..0xFFFFFFFFL -> value.toInt()
                else -> return null
            }
            normalizedColors.addProperty(key, argb)
        }
        val look = obj.get("look")?.let { raw ->
            if (!raw.isJsonObject) return null
            try {
                val spec = ThemeSpecJson.fromJson(org.json.JSONObject(raw.toString()))
                JsonParser.parseString(ThemeSpecJson.toJson(spec).toString()).asJsonObject.takeIf { !spec.isEmpty }
            } catch (_: Exception) {
                // A wrong-typed extension: refuse the theme rather than half-import it.
                return null
            }
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("createdAt", createdAt)
            addProperty("modifiedAt", modifiedAt)
            add("colors", normalizedColors)
            if (look != null) {
                addProperty("formatVersion", CustomTheme.FORMAT_VERSION_LOOK)
                add("look", look)
            }
        }
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun longField(obj: JsonObject, key: String): Long? {
        val primitive = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!primitive.isNumber) return null
        val number = primitive.asBigDecimal
        return try {
            number.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** The theme's store id. */
    fun idOf(theme: JsonObject): String = theme.get("id").asString

    /** The theme's display name (its id when a stored theme has none). */
    fun nameOf(theme: JsonObject): String = stringField(theme, "name") ?: idOf(theme)

    /** What the user sees of a theme: its name and colours (timestamps are bookkeeping). */
    private fun visibleBody(theme: JsonObject): List<Any?> = listOf(nameOf(theme), theme.get("colors"), theme.get("look"))

    /**
     * The per-theme diff between the device's stored themes and the file's, matched by id.
     * Null when the current side is unknown or unreadable (the preview then shows the count only).
     */
    fun diff(currentThemesArrayJson: String?, file: List<JsonObject>): CustomThemeDiff? {
        val current = parseStored(currentThemesArrayJson) ?: return null
        val currentById = current.associateBy(::idOf)
        val fileIds = file.map(::idOf).toSet()
        val added = mutableListOf<CustomThemeDiff.Entry>()
        val changed = mutableListOf<CustomThemeDiff.Entry>()
        var unchanged = 0
        for (theme in file) {
            val mine = currentById[idOf(theme)]
            when {
                mine == null -> added += CustomThemeDiff.Entry(idOf(theme), nameOf(theme))
                visibleBody(mine) != visibleBody(theme) -> changed += CustomThemeDiff.Entry(idOf(theme), nameOf(theme))
                else -> unchanged++
            }
        }
        val removed = current.filter { idOf(it) !in fileIds }.map { CustomThemeDiff.Entry(idOf(it), nameOf(it)) }
        return CustomThemeDiff(added, removed, changed, unchanged)
    }

    /**
     * The stored set after importing [file] over [current] with [mode]:
     *  - SKIP: [current] unchanged;
     *  - MERGE: a file theme replaces the local theme with the same id in place, new ones are
     *    appended, every other local theme is kept;
     *  - REPLACE: exactly the file's themes.
     * Capped at [MAX_THEMES]; the themes past the cap are the ones not stored.
     */
    fun merge(current: List<JsonObject>, file: List<JsonObject>, mode: ShortSwipeImportMode): List<JsonObject> =
        when (mode) {
            ShortSwipeImportMode.SKIP -> current
            ShortSwipeImportMode.REPLACE -> file.take(MAX_THEMES)
            ShortSwipeImportMode.MERGE -> {
                val fileById = file.associateBy(::idOf)
                val currentIds = current.map(::idOf).toSet()
                val kept = current.map { fileById[idOf(it)] ?: it }
                (kept + file.filter { idOf(it) !in currentIds }).take(MAX_THEMES)
            }
        }

    /** Serialize a theme list as the stored JSON array. */
    fun toArrayJson(themes: List<JsonObject>): String = JsonArray().apply { themes.forEach(::add) }.toString()
}

/**
 * Which DIY themes an import adds, changes or removes, matched by theme id.
 * [Entry.name] is what the preview row shows.
 */
data class CustomThemeDiff(
    val added: List<Entry>,
    val removed: List<Entry>,
    val changed: List<Entry>,
    val unchanged: Int,
) {
    data class Entry(val id: String, val name: String)

    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /** The part of the diff the chosen [mode] applies (same rules as `ShortSwipeDiff.forMode`). */
    fun forMode(mode: ShortSwipeImportMode): CustomThemeDiff = when (mode) {
        ShortSwipeImportMode.REPLACE -> this
        ShortSwipeImportMode.MERGE -> copy(removed = emptyList(), unchanged = unchanged + removed.size)
        ShortSwipeImportMode.SKIP -> CustomThemeDiff(
            added = emptyList(),
            removed = emptyList(),
            changed = emptyList(),
            unchanged = unchanged + changed.size + removed.size,
        )
    }
}
