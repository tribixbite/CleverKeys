package tribixbite.keyboard2

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages backup and restore of keyboard configuration.
 * Uses Storage Access Framework (SAF) for Android 15+ compatibility.
 *
 * Features:
 * - JSON export/import of SharedPreferences
 * - Metadata tracking (app version, screen dimensions, export date)
 * - Version-tolerant parsing with validation
 * - Special handling for JSON-string preferences
 * - Type detection for boolean, int, float, string, StringSet
 * - Extensive validation of preference values
 * - Screen size mismatch detection
 * - Internal preference filtering
 *
 * Ported from Java to Kotlin with improvements.
 */
class BackupRestoreManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupRestoreManager"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Result of import operation
     */
    data class ImportResult(
        var importedCount: Int = 0,
        var skippedCount: Int = 0,
        var sourceVersion: String = "unknown",
        var sourceScreenWidth: Int = 0,
        var sourceScreenHeight: Int = 0,
        var currentScreenWidth: Int = 0,
        var currentScreenHeight: Int = 0,
        val importedKeys: MutableSet<String> = mutableSetOf(),
        val skippedKeys: MutableSet<String> = mutableSetOf()
    ) {
        /**
         * Check if backup is from significantly different screen size
         */
        fun hasScreenSizeMismatch(): Boolean {
            if (sourceScreenWidth == 0 || sourceScreenHeight == 0) {
                return false // No source dimensions available
            }

            val widthDiff = kotlin.math.abs(currentScreenWidth - sourceScreenWidth)
            val heightDiff = kotlin.math.abs(currentScreenHeight - sourceScreenHeight)

            // Consider it a mismatch if either dimension differs by more than 20%
            return (widthDiff > currentScreenWidth * 0.2) ||
                   (heightDiff > currentScreenHeight * 0.2)
        }
    }

    /**
     * Export all preferences to JSON file
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @param prefs SharedPreferences to export
     * @return true if successful
     * @throws Exception if export fails
     */
    @Throws(Exception::class)
    fun exportConfig(uri: Uri, prefs: SharedPreferences): Boolean {
        try {
            // Collect metadata
            val root = JsonObject()
            val metadata = JsonObject()

            // App version
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            metadata.addProperty("app_version", packageInfo.versionName)
            metadata.addProperty("version_code", packageInfo.versionCode)
            metadata.addProperty("export_date",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))

            // Screen dimensions
            val dm: DisplayMetrics = context.resources.displayMetrics
            metadata.addProperty("screen_width", dm.widthPixels)
            metadata.addProperty("screen_height", dm.heightPixels)
            metadata.addProperty("screen_density", dm.density)
            metadata.addProperty("android_version", Build.VERSION.SDK_INT)

            root.add("metadata", metadata)

            // Export all preferences
            val allPrefs = prefs.all
            val preferences = JsonObject()

            for ((key, value) in allPrefs) {
                // Preserve JSON-string preferences (layouts, extra_keys, custom_extra_keys)
                // These are already stored as JSON strings and should be preserved as-is
                if (isJsonStringPreference(key) && value is String) {
                    try {
                        // Parse the JSON string and add as JsonElement to avoid double-encoding
                        preferences.add(key, JsonParser.parseString(value))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse JSON preference: $key", e)
                        // Fall back to regular serialization if parsing fails
                        preferences.add(key, gson.toJsonTree(value))
                    }
                } else if (isInternalPreference(key)) {
                    // Skip internal state preferences
                    Log.i(TAG, "Skipping internal preference on export: $key")
                } else {
                    preferences.add(key, gson.toJsonTree(value))
                }
            }

            root.add("preferences", preferences)

            // Write to file
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            } ?: throw Exception("Failed to open output stream")

            Log.i(TAG, "Exported ${preferences.size()} preferences (out of ${allPrefs.size} total)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw Exception("Export failed: ${e.message}", e)
        }
    }

    /**
     * Import preferences from JSON file with version-tolerant parsing
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @param prefs SharedPreferences to import into
     * @return ImportResult with statistics
     * @throws Exception if import fails
     */
    @Throws(Exception::class)
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
        try {
            // Read JSON file
            val jsonBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        jsonBuilder.append(line)
                    }
                }
            } ?: throw Exception("Failed to open input stream")

            val root = JsonParser.parseString(jsonBuilder.toString()).asJsonObject

            // Parse metadata (optional, for informational purposes)
            val result = ImportResult()
            if (root.has("metadata")) {
                val metadata = root.getAsJsonObject("metadata")
                result.sourceVersion = if (metadata.has("app_version")) {
                    metadata.get("app_version").asString
                } else "unknown"
                result.sourceScreenWidth = if (metadata.has("screen_width")) {
                    metadata.get("screen_width").asInt
                } else 0
                result.sourceScreenHeight = if (metadata.has("screen_height")) {
                    metadata.get("screen_height").asInt
                } else 0
            }

            // Get current screen dimensions for comparison
            val dm: DisplayMetrics = context.resources.displayMetrics
            result.currentScreenWidth = dm.widthPixels
            result.currentScreenHeight = dm.heightPixels

            // Import preferences with validation
            if (!root.has("preferences")) {
                throw Exception("Invalid backup file: missing preferences section")
            }

            val preferences = root.getAsJsonObject("preferences")
            val editor = prefs.edit()

            var imported = 0
            var skipped = 0

            for ((key, value) in preferences.entrySet()) {
                try {
                    if (importPreference(editor, key, value)) {
                        imported++
                        result.importedKeys.add(key)
                        Log.d(TAG, "Imported: $key = $value")
                    } else {
                        skipped++
                        result.skippedKeys.add(key)
                        Log.i(TAG, "Skipped: $key = $value")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import key: $key = $value", e)
                    skipped++
                    result.skippedKeys.add(key)
                }
            }

            editor.apply()

            result.importedCount = imported
            result.skippedCount = skipped

            Log.i(TAG, "Import complete: $imported imported, $skipped skipped")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            throw Exception("Import failed: ${e.message}", e)
        }
    }

    /**
     * Import a single preference with type detection and validation
     * @return true if imported, false if skipped
     */
    private fun importPreference(
        editor: SharedPreferences.Editor,
        key: String,
        value: JsonElement
    ): Boolean {
        // Skip internal state preferences
        if (isInternalPreference(key)) {
            Log.i(TAG, "Skipping internal preference: $key")
            return false
        }

        // Handle JSON-string preferences (layouts, extra_keys, custom_extra_keys)
        // These are stored as JSON strings in SharedPreferences
        if (isJsonStringPreference(key)) {
            val jsonString: String = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    // Old format: the JSON was exported as a string primitive (double-encoded)
                    // Just use the string value directly
                    Log.i(TAG, "Importing old-format JSON-string preference: $key")
                    value.asString
                }
                value.isJsonArray || value.isJsonObject -> {
                    // New format: the JSON is a native array/object
                    // Convert to compact JSON string (no pretty printing for preferences)
                    Log.i(TAG, "Importing new-format JSON-string preference: $key")
                    value.toString()
                }
                else -> {
                    Log.w(TAG, "Unexpected format for JSON-string preference: $key")
                    return false
                }
            }

            editor.putString(key, jsonString)
            return true
        }

        // Handle different preference types
        if (value.isJsonPrimitive) {
            val primitive = value.asJsonPrimitive

            when {
                primitive.isBoolean -> {
                    editor.putBoolean(key, primitive.asBoolean)
                    return true
                }
                primitive.isNumber -> {
                    // Check if this preference is known to be a float type
                    // We must use the correct type because SharedPreferences throws ClassCastException
                    // if we try to read an int as float or vice versa
                    return if (isFloatPreference(key)) {
                        // Known float preference
                        val floatValue = primitive.asFloat
                        if (validateFloatPreference(key, floatValue)) {
                            editor.putFloat(key, floatValue)
                            true
                        } else {
                            Log.w(TAG, "Skipping invalid float value for $key: $floatValue")
                            false
                        }
                    } else {
                        // Assume integer for all other numeric preferences
                        val intValue = primitive.asInt
                        if (validateIntPreference(key, intValue)) {
                            editor.putInt(key, intValue)
                            true
                        } else {
                            Log.w(TAG, "Skipping invalid int value for $key: $intValue")
                            false
                        }
                    }
                }
                primitive.isString -> {
                    val stringValue = primitive.asString

                    // Some preferences store integers as strings (from ListPreference)
                    // Parse and store them as actual integers to prevent ClassCastException
                    if (isIntegerStoredAsString(key)) {
                        return try {
                            val intValue = stringValue.toInt()
                            if (validateIntPreference(key, intValue)) {
                                editor.putInt(key, intValue)
                                true
                            } else {
                                Log.w(TAG, "Skipping invalid int-as-string value for $key: $intValue")
                                false
                            }
                        } catch (e: NumberFormatException) {
                            Log.w(TAG, "Failed to parse int-as-string for $key: $stringValue")
                            false
                        }
                    }

                    return if (validateStringPreference(key, stringValue)) {
                        editor.putString(key, stringValue)
                        true
                    } else {
                        Log.w(TAG, "Skipping invalid string value for $key")
                        false
                    }
                }
            }
        } else if (value.isJsonArray) {
            // Only parse as StringSet if this preference is known to be a StringSet
            if (isStringSetPreference(key)) {
                val stringSet = mutableSetOf<String>()
                for (element in value.asJsonArray) {
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        stringSet.add(element.asString)
                    }
                }
                editor.putStringSet(key, stringSet)
                return true
            } else {
                Log.w(TAG, "Skipping unexpected JsonArray for key: $key")
                return false
            }
        } else if (value.isJsonNull) {
            // Null values - skip (can't store null in SharedPreferences)
            Log.i(TAG, "Skipping null preference: $key")
            return false
        } else if (value.isJsonObject) {
            // Unexpected JsonObject that's not a JSON-string preference
            Log.w(TAG, "Skipping unexpected JsonObject preference: $key")
            return false
        }

        Log.w(TAG, "Skipping unknown preference type for $key type=${value.javaClass.simpleName}")
        return false
    }

    /**
     * Validate integer preference values
     */
    private fun validateIntPreference(key: String, value: Int): Boolean {
        return when (key) {
            // Opacity values (0-100)
            "label_brightness", "keyboard_opacity", "key_opacity",
            "key_activated_opacity", "suggestion_bar_opacity" ->
                value in 0..100

            // Keyboard height percentages
            "keyboard_height", "keyboard_height_unfolded" ->
                value in 10..100
            "keyboard_height_landscape", "keyboard_height_landscape_unfolded" ->
                value in 20..65

            // Margins and spacing (0-200 dp max)
            "margin_bottom_portrait", "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded",
            "horizontal_margin_portrait", "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded", "horizontal_margin_landscape_unfolded" ->
                value in 0..200

            // Border radius (0-100%)
            "custom_border_radius" ->
                value in 0..100

            // Timing values (milliseconds)
            "vibrate_duration" ->
                value in 0..100
            "longpress_timeout" ->
                value in 50..2000
            "longpress_interval" ->
                value in 5..100

            // Short gesture distance (10-95%)
            "short_gesture_min_distance" ->
                value in 10..95

            // Neural network parameters
            "neural_beam_width" ->
                value in 1..16
            "neural_max_length" ->
                value in 10..50

            // Auto-correction parameters
            "autocorrect_min_word_length" ->
                value in 2..5
            "autocorrect_confidence_min_frequency" ->
                value in 100..5000

            // Clipboard history limit
            "clipboard_history_limit" ->
                value in 1..50

            // Circle sensitivity
            "circle_sensitivity" ->
                value in 1..5

            else ->
                // Unknown integer preference - allow it (version-tolerant)
                true
        }
    }

    /**
     * Validate float preference values
     */
    private fun validateFloatPreference(key: String, value: Float): Boolean {
        return when (key) {
            // Character size (0.75-1.5)
            "character_size" ->
                value in 0.75f..1.5f

            // Margins (0-5%)
            "key_vertical_margin", "key_horizontal_margin" ->
                value in 0f..5f

            // Border line width (0-5 dp)
            "custom_border_line_width" ->
                value in 0f..5f

            // Prediction weights
            "prediction_context_boost" ->
                value in 0.5f..5.0f
            "prediction_frequency_scale" ->
                value in 100.0f..5000.0f

            // Auto-correction threshold
            "autocorrect_char_match_threshold" ->
                value in 0.5f..0.9f

            // Neural confidence threshold
            "neural_confidence_threshold" ->
                value in 0.0f..1.0f

            else ->
                // Unknown float preference - allow it (version-tolerant)
                true
        }
    }

    /**
     * Check if a preference stores data as a JSON string
     * These preferences use ListGroupPreference which stores data as JSON-encoded strings
     */
    private fun isJsonStringPreference(key: String): Boolean {
        return when (key) {
            // LayoutsPreference - stores List<Layout> as JSON string
            "layouts",
            // ExtraKeysPreference - stores Map<KeyValue, PreferredPos> as JSON string
            "extra_keys",
            // CustomExtraKeysPreference - stores Map<KeyValue, PreferredPos> as JSON string
            "custom_extra_keys" -> true
            else -> false
        }
    }

    /**
     * Check if a preference is internal state that shouldn't be exported/imported
     */
    private fun isInternalPreference(key: String): Boolean {
        return when (key) {
            // Internal version tracking
            "version",
            // Current layout indices (managed by Config, device-specific)
            "current_layout_portrait",
            "current_layout_landscape" -> true
            else -> false
        }
    }

    /**
     * Check if a preference is stored as a float in SharedPreferences
     * This is critical because SharedPreferences throws ClassCastException if you
     * try to read an int as float or vice versa
     */
    private fun isFloatPreference(key: String): Boolean {
        return when (key) {
            // Character and UI sizing
            "character_size",
            "key_vertical_margin",
            "key_horizontal_margin",
            "custom_border_line_width",
            // Prediction weights
            "prediction_context_boost",
            "prediction_frequency_scale",
            // Auto-correction threshold
            "autocorrect_char_match_threshold",
            // Neural confidence threshold
            "neural_confidence_threshold" -> true
            else -> false
        }
    }

    /**
     * Check if a preference stores integers as strings (from ListPreference)
     * These need to be parsed and stored as int to prevent ClassCastException
     *
     * IMPORTANT: ListPreference always stores values as strings, even if they look like numbers.
     * Do NOT add ListPreference keys here - they must be imported as strings.
     */
    private fun isIntegerStoredAsString(key: String): Boolean {
        // Currently no preferences need this treatment
        // ListPreferences (show_numpad, circle_sensitivity, clipboard_history_limit) store as strings
        return false
    }

    /**
     * Check if a preference is stored as a StringSet
     * Prevents accidentally parsing other array types as StringSet
     */
    private fun isStringSetPreference(key: String): Boolean {
        // Currently no known StringSet preferences in this app
        // Add keys here if StringSet preferences are added in the future
        return false
    }

    /**
     * Export dictionaries (user words and disabled words) to JSON file
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return true if successful
     * @throws Exception if export fails
     */
    @Throws(Exception::class)
    fun exportDictionaries(uri: Uri): Boolean {
        try {
            val root = JsonObject()
            val metadata = JsonObject()

            // App version
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            metadata.addProperty("app_version", packageInfo.versionName)
            metadata.addProperty("export_date",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            metadata.addProperty("export_type", "dictionaries")

            root.add("metadata", metadata)

            // Export user dictionary
            val userDictPrefs = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE)
            val userWords = userDictPrefs.getStringSet("user_words", emptySet()) ?: emptySet()
            root.add("user_words", gson.toJsonTree(userWords.toList().sorted()))

            // Export disabled words
            val disabledWordsPrefs = context.getSharedPreferences("disabled_words", Context.MODE_PRIVATE)
            val disabledWords = disabledWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
            root.add("disabled_words", gson.toJsonTree(disabledWords.toList().sorted()))

            // Write to file
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            } ?: throw Exception("Failed to open output stream")

            Log.i(TAG, "Exported ${userWords.size} user words, ${disabledWords.size} disabled words")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary export failed", e)
            throw Exception("Dictionary export failed: ${e.message}", e)
        }
    }

    /**
     * Result of dictionary import operation
     */
    data class DictionaryImportResult(
        var userWordsImported: Int = 0,
        var disabledWordsImported: Int = 0,
        var sourceVersion: String = "unknown"
    )

    /**
     * Import dictionaries from JSON file with merge logic
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return DictionaryImportResult with statistics
     * @throws Exception if import fails
     */
    @Throws(Exception::class)
    fun importDictionaries(uri: Uri): DictionaryImportResult {
        try {
            // Read JSON file
            val jsonBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        jsonBuilder.append(line)
                    }
                }
            } ?: throw Exception("Failed to open input stream")

            val root = JsonParser.parseString(jsonBuilder.toString()).asJsonObject

            // Parse metadata
            val result = DictionaryImportResult()
            if (root.has("metadata")) {
                val metadata = root.getAsJsonObject("metadata")
                result.sourceVersion = if (metadata.has("app_version")) {
                    metadata.get("app_version").asString
                } else "unknown"
            }

            // Import user words (merge with existing)
            if (root.has("user_words")) {
                val userDictPrefs = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE)
                val existingWords = userDictPrefs.getStringSet("user_words", emptySet()) ?: emptySet()
                val newWords = mutableSetOf<String>()
                newWords.addAll(existingWords)

                val importedWords = root.getAsJsonArray("user_words")
                for (word in importedWords) {
                    if (word.isJsonPrimitive && word.asJsonPrimitive.isString) {
                        newWords.add(word.asString)
                    }
                }

                userDictPrefs.edit()
                    .putStringSet("user_words", newWords)
                    .apply()

                result.userWordsImported = newWords.size - existingWords.size
                Log.i(TAG, "Imported ${result.userWordsImported} new user words")
            }

            // Import disabled words (merge with existing)
            if (root.has("disabled_words")) {
                val disabledWordsPrefs = context.getSharedPreferences("disabled_words", Context.MODE_PRIVATE)
                val existingWords = disabledWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
                val newWords = mutableSetOf<String>()
                newWords.addAll(existingWords)

                val importedWords = root.getAsJsonArray("disabled_words")
                for (word in importedWords) {
                    if (word.isJsonPrimitive && word.asJsonPrimitive.isString) {
                        newWords.add(word.asString)
                    }
                }

                disabledWordsPrefs.edit()
                    .putStringSet("words", newWords)
                    .apply()

                result.disabledWordsImported = newWords.size - existingWords.size
                Log.i(TAG, "Imported ${result.disabledWordsImported} new disabled words")
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary import failed", e)
            throw Exception("Dictionary import failed: ${e.message}", e)
        }
    }

    private fun validateStringPreference(key: String, value: String?): Boolean {
        if (value == null) return false

        return when (key) {
            // Theme values - relaxed validation for forward compatibility
            // New themes added in future versions should still import successfully
            "theme" ->
                // Just ensure it's not empty - app will fall back to default if invalid
                value.isNotEmpty()

            // Number row options
            "number_row" ->
                value.matches(Regex("no_number_row|no_symbols|symbols"))

            // Show numpad options
            "show_numpad" ->
                value.matches(Regex("never|always|landscape|[0-9]+"))

            // Numpad layout
            "numpad_layout" ->
                value.matches(Regex("high_first|low_first|default"))

            // Number entry layout
            "number_entry_layout" ->
                value.matches(Regex("pin|number"))

            // Circle sensitivity (string representation)
            "circle_sensitivity" ->
                value.matches(Regex("[1-5]"))

            // Slider sensitivity (string representation)
            "slider_sensitivity" ->
                value.matches(Regex("[0-9]+"))

            // Swipe distance (string representation)
            "swipe_dist" ->
                value.matches(Regex("[0-9]+(\\.[0-9]+)?"))

            else ->
                // Unknown string preference - allow it (version-tolerant)
                true
        }
    }
}
