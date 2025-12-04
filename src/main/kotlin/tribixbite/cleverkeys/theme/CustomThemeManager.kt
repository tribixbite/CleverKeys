package tribixbite.cleverkeys.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // Reactive list of custom themes
    private val _customThemes = MutableStateFlow<List<CustomTheme>>(loadCustomThemes())
    val customThemes: StateFlow<List<CustomTheme>> = _customThemes.asStateFlow()

    companion object {
        private const val PREFS_NAME = "custom_keyboard_themes"
        private const val KEY_THEMES = "themes"
        private const val MAX_CUSTOM_THEMES = 50
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
        val removed = current.removeIf { it.id == themeId }

        return if (removed && saveCustomThemesToPrefs(current)) {
            _customThemes.value = current
            true
        } else {
            false
        }
    }

    /**
     * Get a custom theme by ID.
     *
     * @param themeId ID of theme to retrieve
     * @return CustomTheme if found, null otherwise
     */
    fun getCustomTheme(themeId: String): CustomTheme? {
        return _customThemes.value.find { it.id == themeId }
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
            e.printStackTrace()
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
            e.printStackTrace()
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
            e.printStackTrace()
            null
        }
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
                    e.printStackTrace()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Save custom themes to SharedPreferences.
     */
    private fun saveCustomThemesToPrefs(themes: List<CustomTheme>): Boolean {
        return try {
            val jsonArray = JSONArray()
            themes.forEach { theme ->
                jsonArray.put(theme.toJson())
            }

            prefs.edit()
                .putString(KEY_THEMES, jsonArray.toString())
                .apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

/**
 * Custom theme data class with JSON serialization.
 */
data class CustomTheme(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colors: KeyboardColorScheme,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to ThemeInfo for use in theme selector.
     */
    fun toThemeInfo(): ThemeInfo = ThemeInfo(
        id = id,
        name = name,
        category = ThemeCategory.CUSTOM,
        colorScheme = colors,
        description = "Custom theme created ${formatTimestamp(createdAt)}",
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
                colors = KeyboardColorSchemeHelper.fromJson(json.getJSONObject("colors"))
            )
        }

        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val days = diff / (24 * 60 * 60 * 1000)

            return when {
                days == 0L -> "today"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} weeks ago"
                else -> "${days / 30} months ago"
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
