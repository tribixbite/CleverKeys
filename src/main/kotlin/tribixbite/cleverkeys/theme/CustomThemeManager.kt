package tribixbite.cleverkeys.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import tribixbite.cleverkeys.R
import java.io.File
import java.util.UUID

/**
 * Manager for custom user-created keyboard themes.
 *
 * Features:
 * - Create and save custom themes
 * - Delete custom themes
 * - Export themes to JSON
 * - Import themes from JSON
 * - Reactive theme list updates
 * - Persistent storage via SharedPreferences
 *
 * Usage:
 * ```kotlin
 * val themeManager = CustomThemeManager(context)
 *
 * // Create custom theme
 * val theme = CustomTheme(
 *     name = "My Theme",
 *     colors = customColorScheme
 * )
 * themeManager.saveCustomTheme(theme)
 *
 * // Export theme
 * themeManager.exportTheme(theme, File("/path/to/theme.json"))
 *
 * // Import theme
 * themeManager.importTheme(File("/path/to/theme.json"))
 * ```
 */
class CustomThemeManager(private val context: Context) {

    // Use Device Encrypted storage for Direct Boot compatibility
    // Theme preferences are non-sensitive and needed at lock screen
    private val prefs: SharedPreferences = if (Build.VERSION.SDK_INT >= 24) {
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } else {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Reactive list of custom themes
    private val _customThemes = MutableStateFlow<List<CustomTheme>>(loadCustomThemes())
    val customThemes: StateFlow<List<CustomTheme>> = _customThemes.asStateFlow()

    companion object {
        private const val TAG = "CustomThemeManager"
        private const val PREFS_NAME = "custom_keyboard_themes"
        private const val KEY_THEMES = "themes"
        /** The most DIY themes the store keeps (also the backup restore's cap). */
        const val MAX_CUSTOM_THEMES = 50
    }

    /**
     * Save a custom theme.
     *
     * @param theme CustomTheme to save
     * @return true if successful, false if limit reached or error occurred
     */
    fun saveCustomTheme(theme: CustomTheme): Boolean {
        val current = _customThemes.value.toMutableList()

        // Check if theme already exists (update)
        val existingIndex = current.indexOfFirst { it.id == theme.id }
        if (existingIndex >= 0) {
            current[existingIndex] = theme
        } else {
            // Check limit
            if (current.size >= MAX_CUSTOM_THEMES) {
                return false
            }
            current.add(theme)
        }

        // Save to preferences
        return if (saveCustomThemesToPrefs(current)) {
            _customThemes.value = current
            true
        } else {
            false
        }
    }

    /**
     * Delete a custom theme by ID.
     *
     * @param themeId ID of theme to delete
     * @return true if deleted, false if not found
     */
    fun deleteCustomTheme(themeId: String): Boolean {
        val current = _customThemes.value.toMutableList()
        // removeAll{} (Kotlin stdlib) replaces Collection#removeIf (API 24); both return
        // whether the list changed, so the `removed` flag semantics are preserved.
        val removed = current.removeAll { it.id == themeId }

        return if (removed && saveCustomThemesToPrefs(current)) {
            _customThemes.value = current
            true
        } else {
            false
        }
    }

    /**
     * Get a custom theme by ID.
     * Handles both raw UUID and "custom_" prefixed IDs.
     *
     * @param themeId ID of theme to retrieve (with or without "custom_" prefix)
     * @return CustomTheme if found, null otherwise
     */
    fun getCustomTheme(themeId: String): CustomTheme? {
        // Strip "custom_" prefix if present
        val rawId = themeId.removePrefix("custom_")
        return _customThemes.value.find { it.id == rawId }
    }

    /**
     * Get all custom theme IDs.
     * Used by ThemeProvider to list available custom themes.
     */
    fun getAllCustomThemeIds(): List<String> {
        return _customThemes.value.map { it.id }
    }

    /**
     * Export a theme to JSON file.
     *
     * @param theme Theme to export
     * @param file File to write to
     * @return true if successful
     */
    fun exportTheme(theme: CustomTheme, file: File): Boolean {
        return try {
            val json = theme.toJson()
            file.writeText(json.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export theme '${theme.name}' to ${file.name}", e)
            false
        }
    }

    /**
     * Export a theme to JSON string.
     *
     * @param theme Theme to export
     * @return JSON string
     */
    fun exportThemeToString(theme: CustomTheme): String {
        return theme.toJson().toString(2)
    }

    /**
     * Import a theme from JSON file.
     *
     * @param file File to read from
     * @return CustomTheme if successful, null otherwise
     */
    fun importTheme(file: File): CustomTheme? {
        return try {
            val json = JSONObject(file.readText())
            val theme = CustomTheme.fromJson(json)

            // Save imported theme
            if (saveCustomTheme(theme)) {
                theme
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import theme from file ${file.name}", e)
            null
        }
    }

    /**
     * Import a theme from JSON string.
     *
     * @param jsonString JSON string to parse
     * @return CustomTheme if successful, null otherwise
     */
    fun importThemeFromString(jsonString: String): CustomTheme? {
        return try {
            val json = JSONObject(jsonString)
            val theme = CustomTheme.fromJson(json)

            // Save imported theme
            if (saveCustomTheme(theme)) {
                theme
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import theme from JSON string", e)
            null
        }
    }

    /**
     * Every stored theme as a JSON array of [CustomTheme.toJson] objects: the themes of the
     * backup's `custom_themes` section (`backup/CustomThemeBackup`).
     */
    fun exportAllThemesJson(): String =
        JSONArray().apply { _customThemes.value.forEach { put(it.toJson()) } }.toString()

    /**
     * Backup restore: make the stored set exactly [themesArrayJson] (a JSON array of
     * [CustomTheme.toJson] objects, ids and timestamps kept) and persist it with `commit()`, so a
     * restored `theme = custom_<id>` never outlives its theme across a process kill.
     *
     * All or nothing: every entry is parsed before anything is written.
     *
     * @return the number of stored themes.
     * @throws org.json.JSONException when an entry does not parse; the store is then unchanged.
     * @throws IllegalArgumentException when the set exceeds [MAX_CUSTOM_THEMES].
     * @throws IllegalStateException when the write fails; the store is then unchanged.
     */
    fun replaceAllThemes(themesArrayJson: String): Int {
        val array = JSONArray(themesArrayJson)
        val themes = (0 until array.length()).map { CustomTheme.fromJson(array.getJSONObject(it)) }
        require(themes.size <= MAX_CUSTOM_THEMES) { "${themes.size} themes exceed the limit of $MAX_CUSTOM_THEMES" }
        check(saveCustomThemesToPrefs(themes, synchronous = true)) { "Could not write ${themes.size} custom themes" }
        _customThemes.value = themes
        return themes.size
    }

    /**
     * Get all themes (predefined + custom).
     *
     * @return Map of category to themes
     */
    fun getAllThemes(): Map<ThemeCategory, List<ThemeInfo>> {
        val predefined = getAllPredefinedThemes().toMutableMap()

        // Add custom themes
        val customThemeInfos = _customThemes.value.map { it.toThemeInfo() }
        if (customThemeInfos.isNotEmpty()) {
            predefined[ThemeCategory.CUSTOM] = customThemeInfos
        }

        return predefined
    }

    /**
     * Get a flat list of all themes (predefined + custom).
     */
    fun getAllThemesList(): List<ThemeInfo> {
        return getAllThemes().values.flatten()
    }

    /**
     * Get a theme by ID (searches both predefined and custom).
     *
     * @param themeId ID of theme to retrieve
     * @return ThemeInfo if found, null otherwise
     */
    fun getThemeByIdAny(themeId: String): ThemeInfo? {
        // Check predefined first
        getThemeById(themeId)?.let { return it }

        // Check custom
        return getCustomTheme(themeId)?.toThemeInfo()
    }

    /**
     * Load custom themes from SharedPreferences.
     */
    private fun loadCustomThemes(): List<CustomTheme> {
        return try {
            val themesJson = prefs.getString(KEY_THEMES, null) ?: return emptyList()
            val jsonArray = JSONArray(themesJson)

            (0 until jsonArray.length()).mapNotNull { index ->
                try {
                    CustomTheme.fromJson(jsonArray.getJSONObject(index))
                } catch (e: Exception) {
                    // One corrupt entry must not discard the rest of the stored themes.
                    Log.e(TAG, "Skipping unparseable stored theme at index $index", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom themes from preferences", e)
            emptyList()
        }
    }

    /**
     * Save custom themes to SharedPreferences. [synchronous] commits (and reports the disk
     * write's result) instead of the default asynchronous apply.
     */
    private fun saveCustomThemesToPrefs(themes: List<CustomTheme>, synchronous: Boolean = false): Boolean {
        return try {
            val jsonArray = JSONArray()
            themes.forEach { theme ->
                jsonArray.put(theme.toJson())
            }

            val editor = prefs.edit().putString(KEY_THEMES, jsonArray.toString())
            if (synchronous) {
                editor.commit()
            } else {
                editor.apply()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist ${themes.size} custom themes to preferences", e)
            false
        }
    }
}

/**
 * Custom theme data class with JSON serialization.
 *
 * Format versions (backward compatible both ways, docs/specs/layout-system.md "Layout themes"):
 *  - 1 (every theme before 2026-10-28): `id`, `name`, `createdAt`, `modifiedAt` and the 17
 *    [KeyboardColorScheme] colours in `colors`. Rendered exactly as before.
 *  - 2: the same fields plus `formatVersion: 2` and `look`, a [ThemeSpec] ([ThemeSpecJson]) with
 *    everything a layout `<theme>` can carry beyond the 17 colours — look-only colours, light /
 *    dark variants, key geometry, hints, pills, preview. An older build reads `colors` and
 *    ignores the rest, so the theme still loads there (without its geometry).
 *
 * @property extension the version-2 `look` part, or null (version 1). Its common `colors` never
 *   repeat the 17 DIY roles: those live in [colors] alone ([fullSpec] joins the two).
 */
data class CustomTheme(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colors: KeyboardColorScheme,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val extension: ThemeSpec? = null,
) {
    /**
     * The whole appearance as one spec: [colors] as the common DIY colours, the [extension]'s
     * look-only colours, variants, geometry and policies. The theme name is the spec's name.
     */
    fun fullSpec(): ThemeSpec {
        val ext = extension ?: ThemeSpec()
        return ext.copy(name = name, colors = ThemeSpecs.schemeColors(colors) + ext.colors)
    }

    /**
     * Convert to ThemeInfo for use in theme selector.
     */
    fun toThemeInfo(): ThemeInfo = ThemeInfo(
        id = id,
        name = name,
        category = ThemeCategory.CUSTOM,
        colorScheme = colors,
        createdAt = createdAt,
        isDeletable = true,
        isExportable = true
    )

    /**
     * Convert to JSON for export/storage.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdAt", createdAt)
            put("modifiedAt", modifiedAt)
            put("colors", colors.toJson())
            // Version 2 only when there is something beyond the 17 colours: a version-1 theme
            // keeps its exact stored JSON (and its backups) byte for byte.
            extension?.let {
                put("formatVersion", FORMAT_VERSION_LOOK)
                put("look", ThemeSpecJson.toJson(it))
            }
        }
    }

    companion object {
        /**
         * Create CustomTheme from JSON.
         */
        fun fromJson(json: JSONObject): CustomTheme {
            return CustomTheme(
                id = json.getString("id"),
                name = json.getString("name"),
                createdAt = json.getLong("createdAt"),
                modifiedAt = json.getLong("modifiedAt"),
                colors = KeyboardColorSchemeHelper.fromJson(json.getJSONObject("colors")),
                extension = json.optJSONObject("look")?.let { split(ThemeSpecJson.fromJson(it)) },
            )
        }

        /** `formatVersion` of a theme with a [CustomTheme.extension]. */
        const val FORMAT_VERSION_LOOK = 2

        /**
         * A custom theme holding everything of [spec] (an embedded layout `<theme>`, or a look):
         * the 17 DIY colours come from [spec]'s common colours where it defines them and from
         * [base] (the theme it inherits from — the user's global theme in the current mode)
         * otherwise; the rest is kept as the [CustomTheme.extension], variants and geometry
         * included. [CustomTheme.fullSpec] of the result defines every field [spec] defines with
         * the same value (`CustomThemeSpecTest`: XML → custom theme → XML loses nothing).
         */
        fun fromSpec(spec: ThemeSpec, name: String, base: KeyboardColorScheme, id: String = UUID.randomUUID().toString()): CustomTheme {
            val baseColors = ThemeSpecs.schemeColors(base)
            val diy = baseColors + spec.colors.filterKeys { it.diy }
            val colors = ThemeSpecs.render(ThemeSpec(colors = diy), base).scheme
            return CustomTheme(id = id, name = name, colors = colors, extension = split(spec.copy(name = null)))
        }

        /** [spec] without its common DIY colours (they belong in `colors`); null when nothing is left. */
        private fun split(spec: ThemeSpec): ThemeSpec? =
            spec.copy(name = null, colors = spec.colors.filterKeys { !it.diy }).takeIf { !it.isEmpty }

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Localized "Custom theme created <age>" description for a custom theme card.
         * Each age bucket is a whole sentence (plurals for counts) so translators never
         * assemble fragments. A creation time in the future (clock change) reads as "today".
         */
        fun createdDescription(res: Resources, createdAt: Long, now: Long): String {
            val days = ((now - createdAt) / DAY_MS).coerceAtLeast(0L)
            return when {
                days == 0L -> res.getString(R.string.theme_custom_created_today)
                days == 1L -> res.getString(R.string.theme_custom_created_yesterday)
                days < 7 -> days.toInt().let {
                    res.getQuantityString(R.plurals.theme_custom_created_days_ago, it, it)
                }
                days < 30 -> (days / 7).toInt().let {
                    res.getQuantityString(R.plurals.theme_custom_created_weeks_ago, it, it)
                }
                else -> (days / 30).toInt().let {
                    res.getQuantityString(R.plurals.theme_custom_created_months_ago, it, it)
                }
            }
        }
    }
}

/**
 * Extension to convert KeyboardColorScheme to JSON.
 */
fun KeyboardColorScheme.toJson(): JSONObject {
    return JSONObject().apply {
        put("keyDefault", keyDefault.toArgb())
        put("keyActivated", keyActivated.toArgb())
        put("keyLocked", keyLocked.toArgb())
        put("keyModifier", keyModifier.toArgb())
        put("keySpecial", keySpecial.toArgb())

        put("keyLabel", keyLabel.toArgb())
        put("keySubLabel", keySubLabel.toArgb())
        put("keySecondaryLabel", keySecondaryLabel.toArgb())

        put("keyBorder", keyBorder.toArgb())
        put("keyBorderActivated", keyBorderActivated.toArgb())

        put("swipeTrail", swipeTrail.toArgb())
        put("ripple", ripple.toArgb())

        put("suggestionText", suggestionText.toArgb())
        put("suggestionBackground", suggestionBackground.toArgb())
        put("suggestionHighConfidence", suggestionHighConfidence.toArgb())

        put("keyboardBackground", keyboardBackground.toArgb())
        put("keyboardSurface", keyboardSurface.toArgb())
    }
}

/**
 * Helper object for KeyboardColorScheme JSON operations.
 */
object KeyboardColorSchemeHelper {
    /**
     * Create KeyboardColorScheme from JSON.
     */
    fun fromJson(json: JSONObject): KeyboardColorScheme {
        return KeyboardColorScheme(
            keyDefault = Color(json.getInt("keyDefault")),
            keyActivated = Color(json.getInt("keyActivated")),
            keyLocked = Color(json.getInt("keyLocked")),
            keyModifier = Color(json.getInt("keyModifier")),
            keySpecial = Color(json.getInt("keySpecial")),

            keyLabel = Color(json.getInt("keyLabel")),
            keySubLabel = Color(json.getInt("keySubLabel")),
            keySecondaryLabel = Color(json.getInt("keySecondaryLabel")),

            keyBorder = Color(json.getInt("keyBorder")),
            keyBorderActivated = Color(json.getInt("keyBorderActivated")),

            swipeTrail = Color(json.getInt("swipeTrail")),
            ripple = Color(json.getInt("ripple")),

            suggestionText = Color(json.getInt("suggestionText")),
            suggestionBackground = Color(json.getInt("suggestionBackground")),
            suggestionHighConfidence = Color(json.getInt("suggestionHighConfidence")),

            keyboardBackground = Color(json.getInt("keyboardBackground")),
            keyboardSurface = Color(json.getInt("keyboardSurface"))
        )
    }
}
