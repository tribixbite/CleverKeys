package tribixbite.cleverkeys

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import android.provider.UserDictionary

/**
 * Manages backup and restore of keyboard configuration
 * Uses Storage Access Framework (SAF) for Android 15+ compatibility
 */
class BackupRestoreManager(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Export all preferences to JSON file
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return true if successful
     */
    fun exportConfig(uri: Uri, prefs: SharedPreferences): Boolean {
        return try {
            // Collect metadata
            val root = JsonObject()
            val metadata = JsonObject()

            // App version
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = packageInfo.versionCode

            metadata.addProperty("app_version", versionName)
            metadata.addProperty("version_code", versionCode)
            metadata.addProperty(
                "export_date",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            )

            // Screen dimensions
            val dm = context.resources.displayMetrics
            metadata.addProperty("screen_width", dm.widthPixels)
            metadata.addProperty("screen_height", dm.heightPixels)
            metadata.addProperty("screen_density", dm.density)
            metadata.addProperty("android_version", android.os.Build.VERSION.SDK_INT)

            root.add("metadata", metadata)

            // Export all preferences
            val allPrefs = prefs.all
            val preferences = JsonObject()

            for ((key, value) in allPrefs) {
                // Preserve JSON-string preferences (layouts, extra_keys, custom_extra_keys)
                // These are already stored as JSON strings and should be preserved as-is
                when {
                    isJsonStringPreference(key) && value is String -> {
                        try {
                            // Parse the JSON string and add as JsonElement to avoid double-encoding
                            preferences.add(key, JsonParser.parseString(value))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse JSON preference: $key", e)
                            // Fall back to regular serialization if parsing fails
                            preferences.add(key, gson.toJsonTree(value))
                        }
                    }
                    isInternalPreference(key) -> {
                        // Skip internal state preferences
                        Log.i(TAG, "Skipping internal preference on export: $key")
                    }
                    else -> {
                        preferences.add(key, gson.toJsonTree(value))
                    }
                }
            }

            root.add("preferences", preferences)

            // Write to file
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported ${preferences.size()} preferences (out of ${allPrefs.size} total)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw Exception("Export failed: ${e.message}", e)
        }
    }

    /**
     * Import preferences from JSON file with version-tolerant parsing
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ImportResult with statistics
     */
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
        return try {
            // Read JSON file
            val jsonBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        jsonBuilder.append(line)
                    }
                }
            }

            val root = JsonParser.parseString(jsonBuilder.toString()).asJsonObject

            // Parse metadata (optional, for informational purposes)
            val result = ImportResult()
            if (root.has("metadata")) {
                val metadata = root.getAsJsonObject("metadata")
                result.sourceVersion = metadata.get("app_version")?.asString ?: "unknown"
                result.sourceScreenWidth = metadata.get("screen_width")?.asInt ?: 0
                result.sourceScreenHeight = metadata.get("screen_height")?.asInt ?: 0
            }

            // Get current screen dimensions for comparison
            val dm = context.resources.displayMetrics
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
            result
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            throw Exception("Import failed: ${e.message}", e)
        }
    }

    /**
     * Import a single preference with type detection and validation
     * @return true if imported, false if skipped
     */
    private fun importPreference(editor: SharedPreferences.Editor, key: String, value: JsonElement): Boolean {
        // Skip internal state preferences
        if (isInternalPreference(key)) {
            Log.i(TAG, "Skipping internal preference: $key")
            return false
        }

        // Handle JSON-string preferences (layouts, extra_keys, custom_extra_keys)
        // These are stored as JSON strings in SharedPreferences
        if (isJsonStringPreference(key)) {
            val jsonString = when {
                // Old format: the JSON was exported as a string primitive (double-encoded)
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    Log.i(TAG, "Importing old-format JSON-string preference: $key")
                    value.asString
                }
                // New format: the JSON is a native array/object
                value.isJsonArray || value.isJsonObject -> {
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
        when {
            value.isJsonPrimitive -> {
                val primitive = value.asJsonPrimitive

                when {
                    primitive.isBoolean -> {
                        editor.putBoolean(key, primitive.asBoolean)
                        return true
                    }
                    primitive.isNumber -> {
                        // Check if this preference is known to be a float type
                        if (isFloatPreference(key)) {
                            val floatValue = primitive.asFloat
                            if (validateFloatPreference(key, floatValue)) {
                                editor.putFloat(key, floatValue)
                                return true
                            } else {
                                Log.w(TAG, "Skipping invalid float value for $key: $floatValue")
                                return false
                            }
                        } else {
                            // Assume integer for all other numeric preferences
                            val intValue = primitive.asInt
                            if (validateIntPreference(key, intValue)) {
                                editor.putInt(key, intValue)
                                return true
                            } else {
                                Log.w(TAG, "Skipping invalid int value for $key: $intValue")
                                return false
                            }
                        }
                    }
                    primitive.isString -> {
                        val stringValue = primitive.asString

                        // Some preferences store integers as strings (from ListPreference)
                        if (isIntegerStoredAsString(key)) {
                            try {
                                val intValue = stringValue.toInt()
                                if (validateIntPreference(key, intValue)) {
                                    editor.putInt(key, intValue)
                                    return true
                                } else {
                                    Log.w(TAG, "Skipping invalid int-as-string value for $key: $intValue")
                                    return false
                                }
                            } catch (e: NumberFormatException) {
                                Log.w(TAG, "Failed to parse int-as-string for $key: $stringValue")
                                return false
                            }
                        }

                        if (validateStringPreference(key, stringValue)) {
                            editor.putString(key, stringValue)
                            return true
                        } else {
                            Log.w(TAG, "Skipping invalid string value for $key")
                            return false
                        }
                    }
                }
            }
            value.isJsonArray -> {
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
            }
            value.isJsonNull -> {
                // Null values - skip (can't store null in SharedPreferences)
                Log.i(TAG, "Skipping null preference: $key")
                return false
            }
            value.isJsonObject -> {
                // Unexpected JsonObject that's not a JSON-string preference
                Log.w(TAG, "Skipping unexpected JsonObject preference: $key")
                return false
            }
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
            "key_activated_opacity", "suggestion_bar_opacity" -> value in 0..100

            // Keyboard height percentages
            "keyboard_height", "keyboard_height_unfolded" -> value in 10..100
            "keyboard_height_landscape", "keyboard_height_landscape_unfolded" -> value in 20..65

            // Margins and spacing (0-200 dp max)
            "margin_bottom_portrait", "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded", "margin_bottom_landscape_unfolded",
            "horizontal_margin_portrait", "horizontal_margin_landscape",
            "horizontal_margin_portrait_unfolded", "horizontal_margin_landscape_unfolded" -> value in 0..200

            // Border radius (0-100%)
            "custom_border_radius" -> value in 0..100

            // Timing values (milliseconds)
            "vibrate_duration" -> value in 0..100
            "longpress_timeout" -> value in 50..2000
            "longpress_interval" -> value in 5..100

            // Short gesture distance (10-95%)
            "short_gesture_min_distance" -> value in 10..95

            // Neural network parameters
            "neural_beam_width" -> value in 1..32
            "neural_max_length" -> value in 10..100
            "neural_user_max_seq_length" -> value in 0..500

            // Auto-correction parameters
            "autocorrect_min_word_length" -> value in 2..5
            "autocorrect_confidence_min_frequency" -> value in 100..5000

            // Clipboard history limit
            "clipboard_history_limit" -> value in 1..50

            // Circle sensitivity
            "circle_sensitivity" -> value in 1..5

            // Unknown integer preference - allow it (version-tolerant)
            else -> true
        }
    }

    /**
     * Validate float preference values
     */
    private fun validateFloatPreference(key: String, value: Float): Boolean {
        return when (key) {
            // Character size (0.75-1.5)
            "character_size" -> value in 0.75f..1.5f

            // Margins (0-5%)
            "key_vertical_margin", "key_horizontal_margin" -> value in 0f..5f

            // Border line width (0-5 dp)
            "custom_border_line_width" -> value in 0f..5f

            // Prediction weights
            "prediction_context_boost" -> value in 0.5f..5.0f
            "prediction_frequency_scale" -> value in 100.0f..5000.0f

            // Auto-correction threshold
            "autocorrect_char_match_threshold" -> value in 0.5f..0.9f

            // Neural confidence threshold
            "neural_confidence_threshold" -> value in 0.0f..1.0f

            // Neural beam search parameters
            "neural_beam_score_gap" -> value in 0.0f..100.0f
            "neural_beam_prune_confidence" -> value in 0.0f..1.0f
            "neural_beam_alpha" -> value in 0.0f..10.0f

            // Swipe typing boost parameters (0.0-2.0 range)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost" -> value in 0.0f..2.0f

            // Unknown float preference - allow it (version-tolerant)
            else -> true
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
            "character_size", "key_vertical_margin", "key_horizontal_margin", "custom_border_line_width",
            // Prediction weights
            "prediction_context_boost", "prediction_frequency_scale",
            // Auto-correction threshold
            "autocorrect_char_match_threshold",
            // Neural confidence threshold
            "neural_confidence_threshold",
            // Swipe typing boost parameters (SlideBarPreference floats)
            "swipe_rare_words_penalty", "swipe_common_words_boost", "swipe_top5000_boost",
            // Advanced gesture tuning floats
            "slider_speed_smoothing", "slider_speed_max",
            "swipe_min_distance", "swipe_min_key_distance", "swipe_noise_threshold",
            "swipe_high_velocity_threshold",
            // Neural beam search floats
            "neural_beam_score_gap", "neural_beam_prune_confidence", "neural_beam_alpha",
            // Language detection
            "pref_language_detection_sensitivity",
            // Swipe trail appearance
            "swipe_trail_width", "swipe_trail_glow_radius" -> true
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
     * Validate string preference values
     */
    private fun validateStringPreference(key: String, value: String?): Boolean {
        if (value == null) return false

        return when (key) {
            // Theme values - relaxed validation for forward compatibility
            "theme" -> value.isNotEmpty()

            // Number row options
            "number_row" -> value.matches(Regex("no_number_row|no_symbols|symbols"))

            // Show numpad options
            "show_numpad" -> value.matches(Regex("never|always|landscape|[0-9]+"))

            // Numpad layout
            "numpad_layout" -> value.matches(Regex("high_first|low_first|default"))

            // Number entry layout
            "number_entry_layout" -> value.matches(Regex("pin|number"))

            // Circle sensitivity (string representation)
            "circle_sensitivity" -> value.matches(Regex("[1-5]"))

            // Slider sensitivity (string representation)
            "slider_sensitivity" -> value.matches(Regex("[0-9]+"))

            // Swipe distance (string representation)
            "swipe_dist" -> value.matches(Regex("[0-9]+(\\.[0-9]+)?"))

            // Unknown string preference - allow it (version-tolerant)
            else -> true
        }
    }

    /**
     * Result of import operation
     */
    data class ImportResult(
        @JvmField var importedCount: Int = 0,
        @JvmField var skippedCount: Int = 0,
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var sourceScreenWidth: Int = 0,
        @JvmField var sourceScreenHeight: Int = 0,
        @JvmField var currentScreenWidth: Int = 0,
        @JvmField var currentScreenHeight: Int = 0,
        @JvmField val importedKeys: MutableSet<String> = mutableSetOf(),
        @JvmField val skippedKeys: MutableSet<String> = mutableSetOf()
    ) {
        fun hasScreenSizeMismatch(): Boolean {
            if (sourceScreenWidth == 0 || sourceScreenHeight == 0)
                return false // No source dimensions available

            val widthDiff = abs(currentScreenWidth - sourceScreenWidth)
            val heightDiff = abs(currentScreenHeight - sourceScreenHeight)

            // Consider it a mismatch if either dimension differs by more than 20%
            return (widthDiff > currentScreenWidth * 0.2) ||
                (heightDiff > currentScreenHeight * 0.2)
        }
    }

    /**
     * Export user dictionaries to JSON file
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     *
     * CRITICAL: DictionaryManager uses SharedPreferences file "user_dictionary" with key "user_words"
     * stored as a StringSet. We export from that location to ensure consistency with import.
     */
    fun exportDictionaries(uri: Uri) {
        try {
            val root = JsonObject()
            val metadata = JsonObject()

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            metadata.addProperty("app_version", packageInfo.versionName)
            metadata.addProperty("export_date",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            metadata.addProperty("type", "dictionaries")
            root.add("metadata", metadata)

            // Export user words from Android system UserDictionary
            val words = mutableListOf<DictionaryWord>()
            val cursor = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY),
                null, null, null
            )
            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                while (it.moveToNext()) {
                    val word = it.getString(wordIndex)
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else DEFAULT_USER_WORD_FREQ
                    words.add(DictionaryWord(word, freq, WordSource.USER, true))
                }
            }
            val userWords = JsonArray()
            for (word in words) {
                val wordObj = JsonObject()
                wordObj.addProperty("word", word.word)
                wordObj.addProperty("frequency", word.frequency)
                userWords.add(wordObj)
            }
            root.add("user_words", userWords)

            // Export disabled words from WordPredictor's storage
            val disabledWordsPrefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val disabledWordsSet = disabledWordsPrefs.getStringSet("disabled_words", emptySet()) ?: emptySet()
            val disabledWords = JsonArray()
            for (word in disabledWordsSet) {
                disabledWords.add(word)
            }

            root.add("disabled_words", disabledWords)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported dictionaries: ${userWords.size()} user words, ${disabledWords.size()} disabled words")
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary export failed", e)
            throw Exception("Dictionary export failed: ${e.message}", e)
        }
    }

    /**
     * Import user dictionaries from JSON file
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return DictionaryImportResult with statistics
     *
     * CRITICAL: Use Android's system UserDictionary API to add words.
     */
    fun importDictionaries(uri: Uri): DictionaryImportResult {
        return try {
            val jsonBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        jsonBuilder.append(line)
                    }
                }
            }

            val root = JsonParser.parseString(jsonBuilder.toString()).asJsonObject
            val result = DictionaryImportResult()

            if (root.has("metadata")) {
                val metadata = root.getAsJsonObject("metadata")
                result.sourceVersion = metadata.get("app_version")?.asString ?: "unknown"
            }

            // Load existing user words from Android's UserDictionary to avoid re-adding
            val existingUserWords = mutableSetOf<String>()
            val cursor = context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null, null, null
            )
            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                while (it.moveToNext()) {
                    existingUserWords.add(it.getString(wordIndex))
                }
            }
            Log.i(TAG, "Existing user words in system dictionary: ${existingUserWords.size}")

            // Handle new format: custom_words as object with word -> frequency
            if (root.has("custom_words") && root.get("custom_words").isJsonObject) {
                val customWords = root.getAsJsonObject("custom_words")
                for ((word, freqElement) in customWords.entrySet()) {
                    val frequency = freqElement.asInt.coerceIn(1, 255) // Android UserDictionary uses 1-255
                    if (word !in existingUserWords) {
                        UserDictionary.Words.addWord(context, word, frequency, null, null)
                        existingUserWords.add(word) // Add to our set to prevent re-adding
                        result.userWordsImported++
                    }
                }
                Log.i(TAG, "Parsed ${customWords.size()} custom_words (new format), added ${result.userWordsImported} new")
            }

            // Handle old format: user_words as array of strings or objects
            if (root.has("user_words") && root.get("user_words").isJsonArray) {
                val userWordsArray = root.getAsJsonArray("user_words")
                for (element in userWordsArray) {
                    val word: String
                    val frequency: Int

                    if (element.isJsonObject) {
                        // Handle format {"word": "abc", "frequency": 100}
                        val wordObj = element.getAsJsonObject()
                        word = wordObj.get("word").asString
                        frequency = wordObj.get("frequency")?.asInt?.coerceIn(1, 255) ?: DEFAULT_USER_WORD_FREQ
                    } else {
                        // Handle format "abc" (just string)
                        word = element.asString
                        frequency = DEFAULT_USER_WORD_FREQ
                    }

                    if (word !in existingUserWords) {
                        UserDictionary.Words.addWord(context, word, frequency, null, null)
                        existingUserWords.add(word)
                        result.userWordsImported++
                    }
                }
                Log.i(TAG, "Parsed ${userWordsArray.size()} user_words (old format), total added: ${result.userWordsImported}")
            }

            // Handle disabled_words - these go to DirectBootAwarePreferences
            if (root.has("disabled_words") && root.get("disabled_words").isJsonArray) {
                val disabledWordsPrefs = DirectBootAwarePreferences.get_shared_preferences(context)
                val existingDisabled = disabledWordsPrefs.getStringSet("disabled_words", emptySet())?.toMutableSet() ?: mutableSetOf()
                val newDisabled = mutableSetOf<String>()

                val disabledWords = root.getAsJsonArray("disabled_words")
                for (wordElement in disabledWords) {
                    val wordStr = wordElement.asString
                    if (wordStr !in existingDisabled) {
                        newDisabled.add(wordStr)
                        result.disabledWordsImported++
                    }
                }

                if (newDisabled.isNotEmpty()) {
                    val allDisabled = existingDisabled + newDisabled
                    disabledWordsPrefs.edit()
                        .putStringSet("disabled_words", allDisabled)
                        .apply()
                    Log.i(TAG, "Saved ${allDisabled.size} disabled words (${newDisabled.size} new)")
                }
            }

            Log.i(TAG, "Imported dictionaries: ${result.userWordsImported} user words, ${result.disabledWordsImported} disabled words")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary import failed", e)
            throw Exception("Dictionary import failed: ${e.message}", e)
        }
    }

    /**
     * Export clipboard history to JSON file
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     */
    fun exportClipboardHistory(uri: Uri) {
        try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val exportData = clipboardDb.exportToJSON()
                ?: throw Exception("Failed to export clipboard data")

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    writer.write(exportData.toString(2))
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported clipboard history")
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard export failed", e)
            throw Exception("Clipboard export failed: ${e.message}", e)
        }
    }

    /**
     * Import clipboard history from JSON file
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ClipboardImportResult with statistics
     */
    fun importClipboardHistory(uri: Uri): ClipboardImportResult {
        return try {
            val jsonBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        jsonBuilder.append(line)
                    }
                }
            }

            val importData = org.json.JSONObject(jsonBuilder.toString())
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val importResult = clipboardDb.importFromJSON(importData)

            val result = ClipboardImportResult()
            result.importedCount = importResult[0] + importResult[1]
            result.skippedCount = importResult[2]

            if (importData.has("export_date")) {
                result.sourceVersion = importData.getString("export_date")
            }

            Log.i(TAG, "Imported clipboard history: ${result.importedCount} imported, ${result.skippedCount} skipped")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard import failed", e)
            throw Exception("Clipboard import failed: ${e.message}", e)
        }
    }

    /**
     * Result of dictionary import operation
     */
    data class DictionaryImportResult(
        @JvmField var userWordsImported: Int = 0,
        @JvmField var disabledWordsImported: Int = 0,
        @JvmField var sourceVersion: String = "unknown"
    )

    /**
     * Result of clipboard import operation
     */
    data class ClipboardImportResult(
        @JvmField var importedCount: Int = 0,
        @JvmField var skippedCount: Int = 0,
        @JvmField var sourceVersion: String = "unknown"
    )

    companion object {
        private const val TAG = "BackupRestoreManager"
        private const val DEFAULT_USER_WORD_FREQ = 100 // Default frequency for user words
    }
}
