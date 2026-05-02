package tribixbite.cleverkeys

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import android.provider.UserDictionary
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.backup.PrefValue
import tribixbite.cleverkeys.backup.SettingsValidation
import kotlinx.coroutines.runBlocking

/**
 * Manages backup and restore of keyboard configuration
 * Uses Storage Access Framework (SAF) for Android 15+ compatibility
 */
class BackupRestoreManager(private val context: Context) {
    // Lazy init to avoid circular dependency issues
    private val shortSwipeManager: ShortSwipeCustomizationManager by lazy {
        ShortSwipeCustomizationManager.getInstance(context)
    }
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Open an OutputStream for writing to a URI. Handles both content:// (SAF)
     * and file:// (Termux/automation) schemes.
     *
     * Fallback chain for file:// URIs on Android 11+ scoped storage:
     * 1. Direct FileOutputStream (works if user granted MANAGE_EXTERNAL_STORAGE)
     * 2. Downloads/ via MediaStore (no permissions needed, visible in file managers)
     * 3. App-private external dir (always writable, but hidden from most file managers)
     *
     * The actual output path is stored in [lastOutputPath] for caller feedback.
     */
    var lastOutputPath: String? = null
        private set

    private fun openOutputStream(uri: Uri): OutputStream? {
        lastOutputPath = null
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val fileName = File(path).name
            // Try 1: Direct write to requested path (works with MANAGE_EXTERNAL_STORAGE)
            return try {
                val file = File(path)
                file.parentFile?.mkdirs()
                FileOutputStream(file).also {
                    lastOutputPath = file.absolutePath
                }
            } catch (e: Exception) {
                // EPERM or SecurityException — scoped storage blocks /sdcard/ writes
                if (e.message?.contains("EPERM") != true &&
                    e !is SecurityException) throw e

                // Try 2: Downloads/ via MediaStore (visible to user, no permissions)
                writeToDownloads(fileName)
                    // Try 3: App-private external dir (always writable)
                    ?: writeToAppExternalDir(fileName)
            }
        }
        return context.contentResolver.openOutputStream(uri)
    }

    /**
     * Write to Downloads/ via MediaStore. Works on Android 10+ without permissions.
     * Files appear in Downloads folder in all file managers.
     * @return OutputStream or null if MediaStore insert fails
     */
    private fun writeToDownloads(fileName: String): OutputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                // Place in CleverKeys subfolder within Downloads
                put(MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/CleverKeys")
            }
            val contentUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val stream = context.contentResolver.openOutputStream(contentUri)
            if (stream != null) {
                lastOutputPath = "${Environment.DIRECTORY_DOWNLOADS}/CleverKeys/$fileName"
                Log.i(TAG, "Writing to Downloads/CleverKeys/$fileName via MediaStore")
            }
            stream
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore Downloads write failed: ${e.message}")
            null
        }
    }

    /**
     * Last-resort fallback: write to app-private external dir.
     * Always writable but requires a file manager with Android/data/ access.
     */
    private fun writeToAppExternalDir(fileName: String): OutputStream {
        val extDir = context.getExternalFilesDir(null)
            ?: throw java.io.IOException("No external files directory available")
        val outputFile = File(extDir, fileName)
        Log.i(TAG, "Fallback write to app dir: ${outputFile.absolutePath}")
        lastOutputPath = outputFile.absolutePath
        return FileOutputStream(outputFile)
    }

    /**
     * Open an InputStream for reading from a URI. Same file:// handling as above.
     *
     * Search order for file:// URIs:
     * 1. Exact path via direct FileInputStream (try without canRead — FUSE may lie)
     * 2. Downloads/CleverKeys/ via direct FileInputStream
     * 3. Downloads/CleverKeys/ via MediaStore query (app's own exports)
     * 4. Force MediaStore scan + retry (handles cp/vim/external edits)
     * 5. Broader MediaStore query by filename only (any Downloads subfolder)
     * 6. App-private external dir (where exports land as last resort)
     *
     * #70: On Android 10+, scoped storage restricts file access to files the app
     * created. Files modified by external tools (cp, vim, etc.) get a new owner UID,
     * making them inaccessible via both File API and MediaStore without storage perms.
     */
    private fun openInputStream(uri: Uri): InputStream? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            val fileName = file.name

            // Try 1: Exact path — attempt direct read (FUSE canRead() may lie)
            try {
                return FileInputStream(file)
            } catch (e: Exception) {
                Log.d(TAG, "Direct read failed for $path: ${e.message}")
            }

            // Try 2: Downloads/CleverKeys/ direct
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val downloadsFile = File(downloadsDir, "CleverKeys/$fileName")
            if (downloadsFile != file) {
                try {
                    return FileInputStream(downloadsFile)
                } catch (e: Exception) {
                    Log.d(TAG, "Downloads dir read failed for $fileName: ${e.message}")
                }
            }

            // Try 3: MediaStore query by filename in Downloads/CleverKeys/ (app's own files)
            readFromDownloadsMediaStore(fileName, "%CleverKeys%")?.let { return it }

            // Try 4: Force MediaStore scan of the exact path, then retry query
            // This indexes files created/modified by external tools (cp, vim, etc.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                scanFileIntoMediaStore(path)
                readFromDownloadsMediaStore(fileName, "%CleverKeys%")?.let { return it }
            }

            // Try 5: Broader MediaStore query — any file with this name in Downloads/
            readFromDownloadsMediaStore(fileName, "%")?.let { return it }

            // Try 6: App-private external dir
            val extFile = File(context.getExternalFilesDir(null), fileName)
            try {
                return FileInputStream(extFile)
            } catch (e: Exception) {
                Log.d(TAG, "App-private dir read failed for $fileName: ${e.message}")
            }

            return null
        }
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Force MediaStore to scan a file path so it becomes queryable.
     * Synchronous: blocks up to 5 seconds for the scan to complete.
     * After scanning, the file entry is owned by the system (not this app),
     * so it may still be unreadable without storage permissions on Android 13+.
     */
    private fun scanFileIntoMediaStore(filePath: String) {
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(filePath), arrayOf("application/json")
            ) { scannedPath, scannedUri ->
                Log.i(TAG, "MediaScanner indexed: $scannedPath → $scannedUri")
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner scan failed for $filePath: ${e.message}")
        }
    }

    /**
     * Read from Downloads/ via MediaStore query. Finds files by display name
     * and optional relative path pattern.
     *
     * Note: On Android 10+ without storage permissions, MediaStore only returns
     * files owned by this app. Files created/copied by other processes (Termux, etc.)
     * may not be visible even after scanning.
     *
     * @param pathPattern LIKE pattern for RELATIVE_PATH (e.g., "%CleverKeys%" or "%")
     */
    private fun readFromDownloadsMediaStore(fileName: String, pathPattern: String): InputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(fileName, pathPattern),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    Log.i(TAG, "Reading $fileName via MediaStore (id=$id, path=$pathPattern)")
                    context.contentResolver.openInputStream(contentUri)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore Downloads read failed for $fileName: ${e.message}")
            null
        }
    }

    /**
     * Read entire JSON string from a URI. Throws IOException with a clear message
     * if the file cannot be read (e.g., scoped storage blocks access to files
     * created/modified by other apps like Termux).
     *
     * #70: On Android 10+, files copied/edited by external apps (cp, vim) become
     * owned by that app's UID. Without storage permissions, CleverKeys can only
     * read files it created. Use --es json_base64 intent extra as workaround.
     */
    fun readJsonFromUri(uri: Uri): String {
        val inputStream = openInputStream(uri)
            ?: throw java.io.IOException(
                "Cannot read file: ${uri.lastPathSegment ?: uri}\n\n" +
                "On Android 10+, files modified by external apps (cp, vim, etc.) " +
                "become inaccessible due to scoped storage restrictions.\n\n" +
                "Workarounds:\n" +
                "• Use the Import button in the UI (file picker grants access)\n" +
                "• Pass file content directly: --es json_base64 \"\$(base64 < file.json)\"\n" +
                "• Import the original exported file without modification"
            )
        return inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        }
    }

    /**
     * Export all preferences to JSON file, including defaults for documentation
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

            // Get all defaults first, then override with stored preferences
            val allDefaults = getAllDefaultPreferences()
            val storedPrefs = prefs.all
            val preferences = JsonObject()

            // First add all defaults
            for ((key, value) in allDefaults) {
                if (!isInternalPreference(key)) {
                    preferences.add(key, gson.toJsonTree(value))
                }
            }

            // Then override with stored preferences (these take precedence)
            for ((key, value) in storedPrefs) {
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

            // Export short swipe customizations (stored in separate file, not SharedPreferences)
            try {
                runBlocking { shortSwipeManager.loadMappings() }
                val shortSwipeJson = shortSwipeManager.exportToJson()
                if (shortSwipeJson.isNotBlank() && shortSwipeJson != "{}") {
                    root.add("short_swipe_customizations", JsonParser.parseString(shortSwipeJson))
                    Log.i(TAG, "Exported short swipe customizations")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to export short swipe customizations (non-fatal)", e)
            }

            // Write to file
            openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported ${preferences.size()} preferences (${storedPrefs.size} stored + defaults)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw Exception("Export failed: ${e.message}", e)
        }
    }

    /**
     * Returns a map of all default preference keys to their default values.
     * This ensures exports include defaults for documentation purposes
     * even if the user hasn't changed them from defaults.
     */
    private fun getAllDefaultPreferences(): Map<String, Any> = mapOf(
        // Appearance
        "theme" to Defaults.THEME,
        "keyboard_height_percent" to Defaults.KEYBOARD_HEIGHT_PORTRAIT,
        "keyboard_height_landscape" to Defaults.KEYBOARD_HEIGHT_LANDSCAPE,
        "label_brightness" to Defaults.LABEL_BRIGHTNESS,
        "keyboard_opacity" to Defaults.KEYBOARD_OPACITY,
        "key_opacity" to Defaults.KEY_OPACITY,
        "key_activated_opacity" to Defaults.KEY_ACTIVATED_OPACITY,
        "character_size" to Defaults.CHARACTER_SIZE,
        "key_vertical_margin" to Defaults.KEY_VERTICAL_MARGIN,
        "key_horizontal_margin" to Defaults.KEY_HORIZONTAL_MARGIN,
        "border_config" to Defaults.BORDER_CONFIG,
        "custom_border_radius" to Defaults.CUSTOM_BORDER_RADIUS,
        "custom_border_line_width" to Defaults.CUSTOM_BORDER_LINE_WIDTH,

        // Layout
        "show_numpad" to Defaults.SHOW_NUMPAD,
        "numpad_layout" to Defaults.NUMPAD_LAYOUT,
        "scale_numpad_height" to Defaults.SCALE_NUMPAD_HEIGHT,
        "number_row" to Defaults.NUMBER_ROW,
        "number_entry_layout" to Defaults.NUMBER_ENTRY_LAYOUT,
        "margin_bottom_portrait" to Defaults.MARGIN_BOTTOM_PORTRAIT,
        "margin_bottom_landscape" to Defaults.MARGIN_BOTTOM_LANDSCAPE,
        "margin_left_portrait" to Defaults.MARGIN_LEFT_PORTRAIT,
        "margin_left_landscape" to Defaults.MARGIN_LEFT_LANDSCAPE,
        "margin_right_portrait" to Defaults.MARGIN_RIGHT_PORTRAIT,
        "margin_right_landscape" to Defaults.MARGIN_RIGHT_LANDSCAPE,

        // Input behavior
        "vibrate_custom" to Defaults.VIBRATE_CUSTOM,
        "vibrate_duration" to Defaults.VIBRATE_DURATION,
        // Master haptic toggle
        "vibration_enabled" to Defaults.HAPTIC_ENABLED,
        // Per-event haptic feedback
        "haptic_key_press" to Defaults.HAPTIC_KEY_PRESS,
        "haptic_prediction_tap" to Defaults.HAPTIC_PREDICTION_TAP,
        "haptic_trackpoint_activate" to Defaults.HAPTIC_TRACKPOINT_ACTIVATE,
        "haptic_long_press" to Defaults.HAPTIC_LONG_PRESS,
        "haptic_swipe_complete" to Defaults.HAPTIC_SWIPE_COMPLETE,
        "longpress_timeout" to Defaults.LONGPRESS_TIMEOUT,
        "longpress_interval" to Defaults.LONGPRESS_INTERVAL,
        "keyrepeat_enabled" to Defaults.KEYREPEAT_ENABLED,
        "double_tap_lock_shift" to Defaults.DOUBLE_TAP_LOCK_SHIFT,
        "autocapitalisation" to Defaults.AUTOCAPITALISATION,
        "switch_input_immediate" to Defaults.SWITCH_INPUT_IMMEDIATE,
        "smart_punctuation" to Defaults.SMART_PUNCTUATION,

        // Gesture settings
        "swipe_dist" to Defaults.SWIPE_DIST,
        "slider_sensitivity" to Defaults.SLIDER_SENSITIVITY,
        "circle_sensitivity" to Defaults.CIRCLE_SENSITIVITY,
        "tap_duration_threshold" to Defaults.TAP_DURATION_THRESHOLD,
        "double_space_threshold" to Defaults.DOUBLE_SPACE_THRESHOLD,
        "swipe_min_distance" to Defaults.SWIPE_MIN_DISTANCE,
        "swipe_min_key_distance" to Defaults.SWIPE_MIN_KEY_DISTANCE,
        "swipe_min_dwell_time" to Defaults.SWIPE_MIN_DWELL_TIME,
        "swipe_noise_threshold" to Defaults.SWIPE_NOISE_THRESHOLD,
        "swipe_high_velocity_threshold" to Defaults.SWIPE_HIGH_VELOCITY_THRESHOLD,
        "slider_speed_smoothing" to Defaults.SLIDER_SPEED_SMOOTHING,
        "slider_speed_max" to Defaults.SLIDER_SPEED_MAX,

        // Short gestures
        "short_gestures_enabled" to Defaults.SHORT_GESTURES_ENABLED,
        "short_gesture_min_distance" to Defaults.SHORT_GESTURE_MIN_DISTANCE,
        "short_gesture_max_distance" to Defaults.SHORT_GESTURE_MAX_DISTANCE,

        // Swipe trail
        "swipe_trail_enabled" to Defaults.SWIPE_TRAIL_ENABLED,
        "swipe_trail_effect" to Defaults.SWIPE_TRAIL_EFFECT,
        "swipe_trail_color" to Defaults.SWIPE_TRAIL_COLOR,
        "swipe_trail_width" to Defaults.SWIPE_TRAIL_WIDTH,
        "swipe_trail_glow_radius" to Defaults.SWIPE_TRAIL_GLOW_RADIUS,

        // Neural prediction
        "neural_beam_width" to Defaults.NEURAL_BEAM_WIDTH,
        "neural_max_length" to Defaults.NEURAL_MAX_LENGTH,
        "neural_confidence_threshold" to Defaults.NEURAL_CONFIDENCE_THRESHOLD,
        "neural_batch_beams" to Defaults.NEURAL_BATCH_BEAMS,
        "neural_greedy_search" to Defaults.NEURAL_GREEDY_SEARCH,
        "neural_beam_alpha" to Defaults.NEURAL_BEAM_ALPHA,
        "neural_beam_prune_confidence" to Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE,
        "neural_beam_score_gap" to Defaults.NEURAL_BEAM_SCORE_GAP,
        "neural_adaptive_width_step" to Defaults.NEURAL_ADAPTIVE_WIDTH_STEP,
        "neural_score_gap_step" to Defaults.NEURAL_SCORE_GAP_STEP,
        "neural_temperature" to Defaults.NEURAL_TEMPERATURE,
        "neural_frequency_weight" to Defaults.NEURAL_FREQUENCY_WEIGHT,
        "swipe_smoothing_window" to Defaults.SWIPE_SMOOTHING_WINDOW,
        "neural_resampling_mode" to Defaults.NEURAL_RESAMPLING_MODE,
        "neural_user_max_seq_length" to Defaults.NEURAL_USER_MAX_SEQ_LENGTH,
        "onnx_xnnpack_threads" to Defaults.ONNX_XNNPACK_THREADS,

        // Word prediction
        "swipe_typing_enabled" to Defaults.SWIPE_TYPING_ENABLED,
        "swipe_on_password_fields" to Defaults.SWIPE_ON_PASSWORD_FIELDS,  // #39
        "word_prediction_enabled" to Defaults.WORD_PREDICTION_ENABLED,
        "suggestion_bar_opacity" to Defaults.SUGGESTION_BAR_OPACITY,
        "show_exact_typed_word" to Defaults.SHOW_EXACT_TYPED_WORD,  // #42: Tap-to-add
        "context_aware_predictions_enabled" to Defaults.CONTEXT_AWARE_PREDICTIONS_ENABLED,
        "personalized_learning_enabled" to Defaults.PERSONALIZED_LEARNING_ENABLED,
        "learning_aggression" to Defaults.LEARNING_AGGRESSION,
        "prediction_context_boost" to Defaults.PREDICTION_CONTEXT_BOOST,
        "prediction_frequency_scale" to Defaults.PREDICTION_FREQUENCY_SCALE,

        // Autocorrect
        "autocorrect_enabled" to Defaults.AUTOCORRECT_ENABLED,
        "autocorrect_min_word_length" to Defaults.AUTOCORRECT_MIN_WORD_LENGTH,
        "autocorrect_char_match_threshold" to Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD,
        "autocorrect_min_frequency" to Defaults.AUTOCORRECT_MIN_FREQUENCY,
        "autocorrect_max_length_diff" to Defaults.AUTOCORRECT_MAX_LENGTH_DIFF,
        "autocorrect_prefix_length" to Defaults.AUTOCORRECT_PREFIX_LENGTH,
        "autocorrect_max_beam_candidates" to Defaults.AUTOCORRECT_MAX_BEAM_CANDIDATES,
        "swipe_beam_autocorrect_enabled" to Defaults.SWIPE_BEAM_AUTOCORRECT_ENABLED,
        "swipe_final_autocorrect_enabled" to Defaults.SWIPE_FINAL_AUTOCORRECT_ENABLED,
        "swipe_fuzzy_match_mode" to Defaults.SWIPE_FUZZY_MATCH_MODE,
        "swipe_prediction_source" to Defaults.SWIPE_PREDICTION_SOURCE,
        "swipe_common_words_boost" to Defaults.SWIPE_COMMON_WORDS_BOOST,
        "swipe_top5000_boost" to Defaults.SWIPE_TOP5000_BOOST,
        "swipe_rare_words_penalty" to Defaults.SWIPE_RARE_WORDS_PENALTY,

        // Clipboard
        "clipboard_history_enabled" to Defaults.CLIPBOARD_HISTORY_ENABLED,
        "clipboard_history_limit" to Defaults.CLIPBOARD_HISTORY_LIMIT,
        "clipboard_pane_height_percent" to Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT,
        "clipboard_max_item_size_kb" to Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB,
        "clipboard_limit_type" to Defaults.CLIPBOARD_LIMIT_TYPE,
        "clipboard_size_limit_mb" to Defaults.CLIPBOARD_SIZE_LIMIT_MB,

        // Multi-language
        "enable_multilang" to Defaults.ENABLE_MULTILANG,
        "primary_language" to Defaults.PRIMARY_LANGUAGE,
        "auto_detect_language" to Defaults.AUTO_DETECT_LANGUAGE,
        "language_detection_sensitivity" to Defaults.LANGUAGE_DETECTION_SENSITIVITY,

        // Debug
        "debug_enabled" to Defaults.DEBUG_ENABLED,
        "swipe_show_debug_scores" to Defaults.SWIPE_SHOW_DEBUG_SCORES,
        "swipe_debug_detailed_logging" to Defaults.SWIPE_DEBUG_DETAILED_LOGGING,
        "swipe_debug_show_raw_output" to Defaults.SWIPE_DEBUG_SHOW_RAW_OUTPUT,
        "swipe_show_raw_beam_predictions" to Defaults.SWIPE_SHOW_RAW_BEAM_PREDICTIONS,
        "termux_mode_enabled" to Defaults.TERMUX_MODE_ENABLED,

        // Privacy
        "privacy_collect_swipe" to Defaults.PRIVACY_COLLECT_SWIPE,
        "privacy_collect_performance" to Defaults.PRIVACY_COLLECT_PERFORMANCE,
        "privacy_collect_errors" to Defaults.PRIVACY_COLLECT_ERRORS,

        // Accessibility
        "sticky_keys_enabled" to Defaults.STICKY_KEYS_ENABLED,
        "sticky_keys_timeout" to Defaults.STICKY_KEYS_TIMEOUT,
        "voice_guidance_enabled" to Defaults.VOICE_GUIDANCE_ENABLED
    )

    /**
     * Import preferences from JSON file with version-tolerant parsing
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ImportResult with statistics
     */
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
        return try {
            // #70: Read JSON with scoped-storage fallbacks
            val jsonContent = readJsonFromUri(uri)
            val root = JsonParser.parseString(jsonContent).asJsonObject

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

            // Migrate legacy margin settings before import (uses dm from above)
            val migratedPrefs = migrateLegacyMargins(preferences, dm.widthPixels, dm.heightPixels)

            var imported = 0
            var skipped = 0

            for ((key, value) in migratedPrefs.entrySet()) {
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

            // Import short swipe customizations if present
            if (root.has("short_swipe_customizations")) {
                try {
                    val shortSwipeJson = root.getAsJsonObject("short_swipe_customizations").toString()
                    val shortSwipeImported = runBlocking {
                        shortSwipeManager.importFromJson(shortSwipeJson, merge = false)
                    }
                    Log.i(TAG, "Imported $shortSwipeImported short swipe customizations")
                    result.shortSwipeCustomizationsImported = shortSwipeImported
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import short swipe customizations (non-fatal)", e)
                }
            }

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
     * Validate integer preference values. Delegates to SettingsValidation —
     * single source of truth shared with the new build-then-apply pipeline.
     */
    private fun validateIntPreference(key: String, value: Int): Boolean =
        SettingsValidation.validate(key, PrefValue.IntV(value)) == null

    /**
     * Validate float preference values. Delegates to SettingsValidation.
     */
    private fun validateFloatPreference(key: String, value: Float): Boolean =
        SettingsValidation.validate(key, PrefValue.FloatV(value)) == null

    /**
     * Migrate legacy margin settings to new percentage-based format.
     *
     * Old format:
     * - margin_bottom_portrait/landscape: dp values (e.g., 7dp, 3dp)
     * - horizontal_margin_portrait/landscape: dp values applied to both sides
     *
     * New format:
     * - margin_bottom_portrait/landscape: % of screen height (e.g., 2%)
     * - margin_left_portrait/landscape: % of screen width
     * - margin_right_portrait/landscape: % of screen width
     *
     * This function detects old dp values and converts them to percentages.
     * Detection: old dp values were typically 0-80, new % values are 0-30 for bottom, 0-45 for left/right
     * Old horizontal_margin values >10 are almost certainly dp values.
     */
    private fun migrateLegacyMargins(prefs: JsonObject, screenWidth: Int, screenHeight: Int): JsonObject {
        val result = prefs.deepCopy()
        val displayDensity = context.resources.displayMetrics.density

        // Migrate horizontal_margin_* to margin_left_* and margin_right_* (symmetric)
        val horizontalKeys = listOf(
            "horizontal_margin_portrait" to listOf("margin_left_portrait", "margin_right_portrait"),
            "horizontal_margin_landscape" to listOf("margin_left_landscape", "margin_right_landscape"),
            "horizontal_margin_portrait_unfolded" to listOf("margin_left_portrait_unfolded", "margin_right_portrait_unfolded"),
            "horizontal_margin_landscape_unfolded" to listOf("margin_left_landscape_unfolded", "margin_right_landscape_unfolded")
        )

        for ((oldKey, newKeys) in horizontalKeys) {
            if (result.has(oldKey) && !result.has(newKeys[0])) {
                try {
                    val dpValue = result.get(oldKey).asInt
                    // Convert dp to pixels, then to percentage of screen width
                    val pixelValue = dpValue * displayDensity
                    val percentValue = ((pixelValue / screenWidth) * 100).toInt().coerceIn(0, 45)

                    // Set both left and right to the same value (symmetric)
                    for (newKey in newKeys) {
                        result.addProperty(newKey, percentValue)
                    }
                    result.remove(oldKey)
                    Log.i(TAG, "Migrated $oldKey (${dpValue}dp) -> ${newKeys[0]}, ${newKeys[1]} ($percentValue%)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate $oldKey", e)
                }
            }
        }

        // Migrate old dp-based margin_bottom_* to percentage-based
        // Detection: if value > 30 it's likely an old dp value (new max is 30%)
        val bottomKeys = listOf(
            "margin_bottom_portrait",
            "margin_bottom_landscape",
            "margin_bottom_portrait_unfolded",
            "margin_bottom_landscape_unfolded"
        )

        for (key in bottomKeys) {
            if (result.has(key)) {
                try {
                    val value = result.get(key).asInt
                    // If value > 30, it's definitely an old dp value
                    // Old defaults were 7dp portrait, 3dp landscape
                    if (value > 30) {
                        val dpValue = value
                        val pixelValue = dpValue * displayDensity
                        val percentValue = ((pixelValue / screenHeight) * 100).toInt().coerceIn(0, 30)
                        result.addProperty(key, percentValue)
                        Log.i(TAG, "Migrated $key from ${dpValue}dp to $percentValue%")
                    }
                    // Values 0-30 could be either old small dp values or new percentages
                    // For safety, leave them as-is since they're in valid range
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate $key", e)
                }
            }
        }

        return result
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
     * Check if a preference is internal state that shouldn't be exported/imported.
     * Delegates to SettingsValidation — single source of truth.
     */
    private fun isInternalPreference(key: String): Boolean =
        SettingsValidation.isInternalPreference(key)

    /**
     * Check if a preference is stored as a float in SharedPreferences.
     * Delegates to SettingsValidation. SharedPreferences throws
     * ClassCastException if a key historically stored as Float is overwritten
     * with putInt — keeping a single allowlist prevents drift between the
     * legacy IO path and the new pure plan builder.
     */
    private fun isFloatPreference(key: String): Boolean =
        SettingsValidation.isFloatPreference(key)

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
     * Validate string preference values. Delegates to SettingsValidation.
     *
     * The legacy IO path (importPreference) calls this AFTER routing past
     * the int/float branches, so type-mismatch keys (e.g. "keyboard_height"
     * arriving as a string) never reach this method. The pure validator's
     * type-mismatch guard for int-/float-typed keys is intentionally STRICTER
     * than the legacy method's per-key allowlist; the legacy IO path's
     * dispatch order made the guard unreachable in practice.
     */
    private fun validateStringPreference(key: String, value: String?): Boolean =
        value != null && SettingsValidation.validate(key, PrefValue.Str(value)) == null

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
        @JvmField val skippedKeys: MutableSet<String> = mutableSetOf(),
        @JvmField var shortSwipeCustomizationsImported: Int = 0
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
     * v1.1.88: Exports in language-specific format (custom_words_${lang}, disabled_words_${lang})
     * Also includes legacy format for backwards compatibility with older app versions.
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
            metadata.addProperty("format_version", 2) // v2 = language-specific format
            root.add("metadata", metadata)

            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            // Run migration first to ensure all words are in new format
            LanguagePreferenceKeys.migrateToLanguageSpecific(prefs)
            // NOTE: Legacy user_dictionary migration is handled by DictionaryManager.migrateLegacyCustomWords()

            // Export custom words per language (new format)
            val customWordsPerLang = JsonObject()
            val languages = LanguagePreferenceKeys.getLanguagesWithCustomWords(prefs)
            var totalCustomWords = 0

            for (lang in languages) {
                val langKey = LanguagePreferenceKeys.customWordsKey(lang)
                val wordsJson = prefs.getString(langKey, "{}")
                if (wordsJson != null && wordsJson != "{}") {
                    customWordsPerLang.add(lang, JsonParser.parseString(wordsJson))
                    // Count words for logging
                    try {
                        val wordsMap = JsonParser.parseString(wordsJson).asJsonObject
                        totalCustomWords += wordsMap.size()
                    } catch (e: Exception) { /* ignore count errors */ }
                }
            }
            root.add("custom_words_by_language", customWordsPerLang)

            // Export disabled words per language (new format)
            val disabledWordsPerLang = JsonObject()
            val disabledLanguages = LanguagePreferenceKeys.getLanguagesWithDisabledWords(prefs)
            var totalDisabledWords = 0

            for (lang in disabledLanguages) {
                val langKey = LanguagePreferenceKeys.disabledWordsKey(lang)
                val wordsSet = prefs.getStringSet(langKey, emptySet()) ?: emptySet()
                if (wordsSet.isNotEmpty()) {
                    val wordsArray = JsonArray()
                    for (word in wordsSet) {
                        wordsArray.add(word)
                    }
                    disabledWordsPerLang.add(lang, wordsArray)
                    totalDisabledWords += wordsSet.size
                }
            }
            root.add("disabled_words_by_language", disabledWordsPerLang)

            // Also export in legacy format for backwards compatibility
            // Use English words if available, otherwise empty
            val enCustomWordsJson = prefs.getString(LanguagePreferenceKeys.customWordsKey("en"), "{}")
            if (enCustomWordsJson != null && enCustomWordsJson != "{}") {
                try {
                    val enWordsMap = JsonParser.parseString(enCustomWordsJson).asJsonObject
                    val userWords = JsonArray()
                    for ((word, freq) in enWordsMap.entrySet()) {
                        val wordObj = JsonObject()
                        wordObj.addProperty("word", word)
                        wordObj.addProperty("frequency", freq.asInt)
                        userWords.add(wordObj)
                    }
                    root.add("user_words", userWords) // Legacy format
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to export legacy format", e)
                }
            }

            val enDisabledWords = prefs.getStringSet(LanguagePreferenceKeys.disabledWordsKey("en"), emptySet()) ?: emptySet()
            val disabledWords = JsonArray()
            for (word in enDisabledWords) {
                disabledWords.add(word)
            }
            root.add("disabled_words", disabledWords) // Legacy format

            openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported dictionaries: $totalCustomWords custom words, $totalDisabledWords disabled words (${languages.size + disabledLanguages.size} languages)")
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
     * v1.1.88: Supports both old format (user_words, disabled_words) and new language-specific format
     * (custom_words_by_language, disabled_words_by_language). Old format is automatically migrated
     * to English language-specific keys.
     */
    fun importDictionaries(uri: Uri): DictionaryImportResult {
        return try {
            // #70: Read JSON with scoped-storage fallbacks
            val jsonContent = readJsonFromUri(uri)
            val root = JsonParser.parseString(jsonContent).asJsonObject
            val result = DictionaryImportResult()
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)

            if (root.has("metadata")) {
                val metadata = root.getAsJsonObject("metadata")
                result.sourceVersion = metadata.get("app_version")?.asString ?: "unknown"
            }

            // Check if using new language-specific format (v2+)
            val isNewFormat = root.has("custom_words_by_language") || root.has("disabled_words_by_language")

            if (isNewFormat) {
                // Import new format: custom_words_by_language
                if (root.has("custom_words_by_language") && root.get("custom_words_by_language").isJsonObject) {
                    val byLang = root.getAsJsonObject("custom_words_by_language")
                    for ((lang, wordsElement) in byLang.entrySet()) {
                        if (wordsElement.isJsonObject) {
                            val langKey = LanguagePreferenceKeys.customWordsKey(lang)
                            val existingJson = prefs.getString(langKey, "{}")
                            val existingWords: MutableMap<String, Int> = try {
                                val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Int>>() {}.type
                                Gson().fromJson(existingJson, type) ?: mutableMapOf()
                            } catch (e: Exception) { mutableMapOf() }

                            val importWords = wordsElement.asJsonObject
                            for ((word, freq) in importWords.entrySet()) {
                                if (!existingWords.containsKey(word)) {
                                    existingWords[word] = freq.asInt
                                    result.userWordsImported++
                                }
                            }

                            prefs.edit().putString(langKey, Gson().toJson(existingWords)).apply()
                            Log.i(TAG, "Imported ${importWords.size()} custom words for $lang")
                        }
                    }
                }

                // Import new format: disabled_words_by_language
                if (root.has("disabled_words_by_language") && root.get("disabled_words_by_language").isJsonObject) {
                    val byLang = root.getAsJsonObject("disabled_words_by_language")
                    for ((lang, wordsElement) in byLang.entrySet()) {
                        if (wordsElement.isJsonArray) {
                            val langKey = LanguagePreferenceKeys.disabledWordsKey(lang)
                            val existingWords = prefs.getStringSet(langKey, emptySet())?.toMutableSet() ?: mutableSetOf()

                            val importWords = wordsElement.asJsonArray
                            for (wordElement in importWords) {
                                val word = wordElement.asString
                                if (word !in existingWords) {
                                    existingWords.add(word)
                                    result.disabledWordsImported++
                                }
                            }

                            prefs.edit().putStringSet(langKey, existingWords).apply()
                            Log.i(TAG, "Imported ${importWords.size()} disabled words for $lang")
                        }
                    }
                }
            }

            // Also handle legacy format (for backwards compatibility)
            // This imports old format directly into English language-specific keys

            // Legacy custom_words (JSON object with word -> frequency)
            if (root.has("custom_words") && root.get("custom_words").isJsonObject) {
                val customWords = root.getAsJsonObject("custom_words")
                val enKey = LanguagePreferenceKeys.customWordsKey("en")
                val existingJson = prefs.getString(enKey, "{}")
                val existingWords: MutableMap<String, Int> = try {
                    val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Int>>() {}.type
                    Gson().fromJson(existingJson, type) ?: mutableMapOf()
                } catch (e: Exception) { mutableMapOf() }

                for ((word, freq) in customWords.entrySet()) {
                    if (!existingWords.containsKey(word)) {
                        existingWords[word] = freq.asInt
                        result.userWordsImported++
                    }
                }

                prefs.edit().putString(enKey, Gson().toJson(existingWords)).apply()
                Log.i(TAG, "Parsed ${customWords.size()} custom_words (legacy format) -> English")
            }

            // Legacy user_words (array of strings or objects)
            if (root.has("user_words") && root.get("user_words").isJsonArray) {
                val userWordsArray = root.getAsJsonArray("user_words")
                val enKey = LanguagePreferenceKeys.customWordsKey("en")
                val existingJson = prefs.getString(enKey, "{}")
                val existingWords: MutableMap<String, Int> = try {
                    val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, Int>>() {}.type
                    Gson().fromJson(existingJson, type) ?: mutableMapOf()
                } catch (e: Exception) { mutableMapOf() }

                for (element in userWordsArray) {
                    val word: String
                    val freq: Int
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        word = obj.get("word").asString
                        freq = obj.get("frequency")?.asInt ?: DEFAULT_USER_WORD_FREQ
                    } else {
                        word = element.asString
                        freq = DEFAULT_USER_WORD_FREQ
                    }
                    if (!existingWords.containsKey(word)) {
                        existingWords[word] = freq
                        result.userWordsImported++
                    }
                }

                prefs.edit().putString(enKey, Gson().toJson(existingWords)).apply()
                Log.i(TAG, "Parsed ${userWordsArray.size()} user_words (legacy format) -> English")
            }

            // Legacy disabled_words (array of strings)
            if (root.has("disabled_words") && root.get("disabled_words").isJsonArray) {
                val enKey = LanguagePreferenceKeys.disabledWordsKey("en")
                val existingDisabled = prefs.getStringSet(enKey, emptySet())?.toMutableSet() ?: mutableSetOf()

                val disabledWords = root.getAsJsonArray("disabled_words")
                for (wordElement in disabledWords) {
                    val wordStr = wordElement.asString
                    if (wordStr !in existingDisabled) {
                        existingDisabled.add(wordStr)
                        result.disabledWordsImported++
                    }
                }

                prefs.edit().putStringSet(enKey, existingDisabled).apply()
                Log.i(TAG, "Parsed ${disabledWords.size()} disabled_words (legacy format) -> English")
            }

            Log.i(TAG, "Imported dictionaries: ${result.userWordsImported} custom words, ${result.disabledWordsImported} disabled words")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary import failed", e)
            throw Exception("Dictionary import failed: ${e.message}", e)
        }
    }

    /**
     * Export clipboard history to JSON file (text-only, lightweight).
     * Media entries are skipped — use exportClipboardHistoryZip for full backup.
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return ClipboardExportResult with statistics
     */
    fun exportClipboardHistory(uri: Uri): ClipboardExportResult {
        try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val exportData = clipboardDb.exportToJSON(textOnly = true)
                ?: throw Exception("Failed to export clipboard data")

            openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    writer.write(exportData.toString(2))
                    writer.flush()
                }
            }

            val activeCount = exportData.optInt("total_active", 0)
            val pinnedCount = exportData.optInt("total_pinned", 0)
            val todoCount = exportData.optInt("total_todo", 0)
            val mediaSkipped = exportData.optInt("media_skipped", 0)

            Log.i(TAG, "Exported clipboard (text-only): $activeCount active, $pinnedCount pinned, $todoCount todo, $mediaSkipped media skipped")
            return ClipboardExportResult(activeCount + pinnedCount + todoCount, mediaSkipped)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard export failed", e)
            throw Exception("Clipboard export failed: ${e.message}", e)
        }
    }

    /**
     * Export clipboard history to ZIP file (full backup with media files).
     * ZIP contains: clipboard_data.json + clipboard_media/{files}
     * Streams media files directly — never loads all into memory (OOM safe).
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return ClipboardExportResult with statistics
     */
    fun exportClipboardHistoryZip(uri: Uri): ClipboardExportResult {
        try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            // Export JSON manifest with all entries including media metadata
            val exportData = clipboardDb.exportToJSON(textOnly = false)
                ?: throw Exception("Failed to export clipboard data")

            val mediaManager = ClipboardMediaManager(context)
            var mediaFileCount = 0

            openOutputStream(uri)?.use { outputStream ->
                java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                    // Write JSON manifest as first entry
                    val jsonEntry = java.util.zip.ZipEntry("clipboard_data.json")
                    zipOut.putNextEntry(jsonEntry)
                    zipOut.write(exportData.toString(2).toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // Collect unique media paths from all tables and stream files into ZIP
                    val mediaPaths = clipboardDb.getAllReferencedMediaPaths()
                    for (mediaPath in mediaPaths) {
                        val file = mediaManager.getMediaFile(mediaPath)
                        if (!file.exists()) {
                            Log.w(TAG, "Media file not found during export, skipping: $mediaPath")
                            continue
                        }
                        // mediaPath already contains "clipboard_media/" prefix from DB
                    val zipMediaEntry = java.util.zip.ZipEntry(mediaPath)
                        zipOut.putNextEntry(zipMediaEntry)
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        mediaFileCount++
                    }
                }
            }

            val activeCount = exportData.optInt("total_active", 0)
            val pinnedCount = exportData.optInt("total_pinned", 0)
            val todoCount = exportData.optInt("total_todo", 0)

            Log.i(TAG, "Exported clipboard ZIP: $activeCount active, $pinnedCount pinned, $todoCount todo, $mediaFileCount media files")
            return ClipboardExportResult(activeCount + pinnedCount + todoCount, 0, mediaFileCount)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard ZIP export failed", e)
            throw Exception("Clipboard ZIP export failed: ${e.message}", e)
        }
    }

    /**
     * Import clipboard history from ZIP file (full backup with media files).
     * Extracts media files to internal storage, regenerates thumbnails, imports JSON.
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ClipboardImportResult with statistics
     */
    fun importClipboardHistoryZip(uri: Uri): ClipboardImportResult {
        return try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val mediaManager = ClipboardMediaManager(context)
            var mediaFilesRestored = 0

            // Stream ZIP entries directly — never buffer entire payload in memory
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                    var jsonData: org.json.JSONObject? = null
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "clipboard_data.json" -> {
                                // Read JSON manifest
                                val jsonBytes = zipIn.readBytes()
                                jsonData = org.json.JSONObject(String(jsonBytes, Charsets.UTF_8))
                            }
                            entry.name.startsWith("clipboard_media/") -> {
                                // Extract media file to internal storage
                                // entry.name IS the media_path as stored in DB (e.g. "clipboard_media/042/hash.ext")
                                val targetFile = mediaManager.getMediaFile(entry.name)
                                targetFile.parentFile?.mkdirs()
                                targetFile.outputStream().use { out ->
                                    zipIn.copyTo(out)
                                }
                                mediaFilesRestored++
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }

                    if (jsonData == null) {
                        throw Exception("ZIP does not contain clipboard_data.json manifest")
                    }

                    // Import JSON entries (they reference media_path values we just extracted)
                    val importResult = clipboardDb.importFromJSON(jsonData)

                    // Regenerate thumbnails for imported media entries
                    val referencedPaths = clipboardDb.getAllReferencedMediaPaths()
                    var thumbnailsRegenerated = 0
                    for (path in referencedPaths) {
                        val file = mediaManager.getMediaFile(path)
                        if (!file.exists()) continue
                        // Determine MIME type from extension
                        val ext = file.extension.lowercase()
                        val mimeType = when (ext) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            "mp4" -> "video/mp4"
                            "pdf" -> "application/pdf"
                            else -> "application/octet-stream"
                        }
                        val thumbnail = mediaManager.generateThumbnail(file.absolutePath, mimeType)
                        if (thumbnail != null) {
                            // Update thumbnail_blob in all tables that reference this path
                            updateThumbnailForMediaPath(clipboardDb, path, thumbnail)
                            thumbnailsRegenerated++
                        }
                    }
                    Log.d(TAG, "Regenerated $thumbnailsRegenerated thumbnails after ZIP import")

                    // Clean up orphan media files not referenced by any DB table
                    // (handles stale files from previous state before import)
                    mediaManager.cleanupOrphans(referencedPaths)

                    val result = ClipboardImportResult()
                    result.importedCount = importResult[0] + importResult[1] + importResult[2]
                    result.skippedCount = importResult[3]
                    result.mediaFilesRestored = mediaFilesRestored
                    if (jsonData.has("export_date")) {
                        result.sourceVersion = jsonData.getString("export_date")
                    }
                    result
                }
            } ?: throw Exception("Cannot open ZIP file")
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard ZIP import failed", e)
            throw Exception("Clipboard ZIP import failed: ${e.message}", e)
        }
    }

    /** Update thumbnail_blob for all rows referencing a given media_path across all tables */
    private fun updateThumbnailForMediaPath(db: ClipboardDatabase, mediaPath: String, thumbnail: ByteArray) {
        try {
            val sqliteDb = db.writableDatabase
            val values = android.content.ContentValues().apply {
                put("thumbnail_blob", thumbnail)
            }
            for (table in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                sqliteDb.update(table, values, "media_path = ?", arrayOf(mediaPath))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update thumbnail for $mediaPath: ${e.message}")
        }
    }

    /**
     * Import clipboard history from JSON file
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ClipboardImportResult with statistics
     */
    fun importClipboardHistory(uri: Uri): ClipboardImportResult {
        return try {
            // #70: Read JSON with scoped-storage fallbacks
            val jsonContent = readJsonFromUri(uri)
            val importData = org.json.JSONObject(jsonContent)
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val importResult = clipboardDb.importFromJSON(importData)

            // importResult = [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
            val result = ClipboardImportResult()
            result.importedCount = importResult[0] + importResult[1] + importResult[2]  // active + pinned + todo
            result.skippedCount = importResult[3]  // duplicates skipped

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
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var mediaFilesRestored: Int = 0
    )

    /**
     * Result of clipboard export operation
     */
    data class ClipboardExportResult(
        @JvmField var exportedCount: Int = 0,
        @JvmField var mediaSkipped: Int = 0,
        @JvmField var mediaFilesIncluded: Int = 0
    )

    companion object {
        private const val TAG = "BackupRestoreManager"
        private const val DEFAULT_USER_WORD_FREQ = 100 // Default frequency for user words
    }
}
