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
import tribixbite.cleverkeys.backup.DictImportApplier
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.DictImportPlanBuilder
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.backup.RealShortSwipeImporter
import tribixbite.cleverkeys.backup.ScreenMetrics
import tribixbite.cleverkeys.backup.SettingsImportApplier
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.SETTINGS_DEFAULTS
import tribixbite.cleverkeys.backup.SettingsImportPlanBuilder
import tribixbite.cleverkeys.backup.SettingsValidation
import tribixbite.cleverkeys.backup.toExportableValue
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import kotlinx.coroutines.runBlocking

/**
 * Manages backup and restore of keyboard configuration
 * Uses Storage Access Framework (SAF) for Android 15+ compatibility
 */
open class BackupRestoreManager(
    private val context: Context,
    private val shortSwipeImporter: ShortSwipeImporter = RealShortSwipeImporter(
        ShortSwipeCustomizationManager.getInstance(context)
    ),
) {
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
     * Export all preferences to a JSON file, including defaults for documentation.
     *
     * Returns the count of preferences written so the SAF picker flow
     * (`BackupRestoreActivity.performExport`) can surface a real number in
     * the success dialog. The legacy `Boolean` return is gone — every
     * non-throwing path now returns the count; failures throw.
     *
     * Marked `open` so [BackupRestoreActivityImportPreviewTest]'s hand-rolled
     * fake can stub the count without performing real IO.
     *
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     * @return number of preferences written (defaults + stored, internal keys excluded)
     */
    open fun exportConfig(uri: Uri, prefs: SharedPreferences): Int {
        try {
            val (root, count) = buildConfigJson(prefs)

            // Write to file
            openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported $count preferences (${prefs.all.size} stored + defaults)")
            return count
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            throw Exception("Export failed: ${e.message}", e)
        }
    }

    /**
     * Build the config-export JSON tree without writing it anywhere. Pure helper
     * used by [exportConfig] and [exportFullBackup] to share serialization
     * (metadata + defaulted preferences + short-swipe customizations).
     *
     * Returns the root [JsonObject] and the preference count for caller telemetry.
     */
    private fun buildConfigJson(prefs: SharedPreferences): Pair<JsonObject, Int> {
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

        return root to preferences.size()
    }

    /**
     * Returns the export-seed defaults map. The single source of truth is
     * `SETTINGS_DEFAULTS` in `backup/SettingsDefaults.kt` (typed
     * `Map<String, PrefValue>`). This function unwraps it to `Map<String, Any>`
     * for Gson's `toJsonTree`.
     *
     * History: this function used to be a 151-line literal map that parallel-
     * tracked SETTINGS_DEFAULTS. The two drifted — `getAllDefaultPreferences`
     * had 8 orphan entries (`enable_multilang`, `primary_language`,
     * `auto_detect_language`, `language_detection_sensitivity`,
     * `double_tap_lock_shift`, `autocorrect_min_frequency`,
     * `keyboard_height_percent`, `extra_key_switch_greekmath`) that no code
     * read; they polluted every backup file as "(unset) → default" noise
     * rows on import. Consolidated 2026-05-14 (see SettingsValidation.
     * DEPRECATED_KEYS for the filter that suppresses them in legacy
     * backups).
     */
    private fun getAllDefaultPreferences(): Map<String, Any> =
        SETTINGS_DEFAULTS.mapValues { (_, v) -> v.toExportableValue() }


    /**
     * Import preferences from JSON file with version-tolerant parsing
     * @param uri URI from Storage Access Framework (ACTION_OPEN_DOCUMENT)
     * @return ImportResult with statistics
     */
    /**
     * Build a `SettingsImportPlan` for the given URI without applying any changes.
     * Reads the JSON, snapshots current prefs, and diffs to produce the plan that
     * the SAF-flow preview UI displays before the user accepts.
     */
    open fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences): SettingsImportPlan {
        val jsonString = readJsonFromUri(uri)
        val snapshot: Map<String, Any?> = prefs.all.toMap()
        val dm = context.resources.displayMetrics
        val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
        // Snapshot the current short-swipe state so the preview dialog can
        // render a structured diff against the import's short-swipe section.
        // loadMappings() is suspend; we runBlocking on the IO dispatcher this
        // is already on (called via withContext(Dispatchers.IO) by the SAF
        // pathway). Failure tolerated — null disables the diff section.
        val currentShortSwipeJson: String? = try {
            runBlocking { shortSwipeManager.loadMappings() }
            shortSwipeManager.exportToJson()
        } catch (e: Exception) {
            Log.w(TAG, "Short-swipe snapshot failed; preview will skip diff section", e)
            null
        }
        // SETTINGS_DEFAULTS suppresses preview rows where the proposed value
        // equals the compile-time default the user already experiences on
        // unset keys (fresh-install over-report fix, 2026-05-14).
        return SettingsImportPlanBuilder.fromJson(
            jsonString,
            currentSnapshot = snapshot,
            screen = screen,
            defaultSnapshot = SETTINGS_DEFAULTS,
            currentShortSwipeRawJson = currentShortSwipeJson,
        )
    }

    /**
     * Apply a previously-built `SettingsImportPlan` against the current prefs.
     * Thin delegator to `SettingsImportApplier.apply` — owns the editor + commit
     * lifecycle and short-swipe importer routing centrally.
     */
    fun applySettingsImportPlan(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
        prefs: SharedPreferences,
    ): ImportResult = runBlocking {
        SettingsImportApplier.apply(
            plan, excludedKeys, shortSwipeMode, prefs, shortSwipeImporter
        )
    }

    /**
     * Legacy headless entry point. Termux automation callers depend on this
     * signature and the destructive `merge=false` short-swipe semantics — both
     * are preserved by routing through `applySettingsImportPlan` with
     * `ShortSwipeImportMode.REPLACE`. The SAF-flow UI uses MERGE by default
     * (see `BackupRestoreActivity.applyPlannedSettings`); flipping the headless
     * default is intentionally out of scope (tracked in `memory/todo.md`).
     */
    fun importConfig(uri: Uri, prefs: SharedPreferences): ImportResult {
        return try {
            val plan = buildSettingsImportPlan(uri, prefs)
            applySettingsImportPlan(plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            throw Exception("Import failed: ${e.message}", e)
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
     * Check if a preference is internal state that shouldn't be exported/imported.
     * Delegates to SettingsValidation — single source of truth.
     */
    private fun isInternalPreference(key: String): Boolean =
        SettingsValidation.isInternalPreference(key)

    /**
     * Result of import operation
     */
    data class ImportResult(
        @JvmField var importedCount: Int = 0,
        @JvmField var skippedCount: Int = 0,
        @JvmField var excludedByUserCount: Int = 0,    // NEW: user-deselected in preview
        @JvmField var driftCount: Int = 0,              // NEW: changed between build and apply
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
     * Counts surfaced from a successful [exportDictionaries] call so the
     * SAF picker flow can render real numbers in the success dialog.
     */
    data class DictionaryExportSummary(
        @JvmField val customWordsCount: Int,
        @JvmField val disabledWordsCount: Int,
        @JvmField val languageCount: Int,
    )

    /**
     * Export user dictionaries to JSON file.
     *
     * Returns a [DictionaryExportSummary] with the actual counts, replacing
     * the legacy `Unit` return. Existing callers that ignored the return
     * value continue to compile unchanged.
     *
     * v1.1.88: Exports in language-specific format (custom_words_${lang}, disabled_words_${lang})
     * Also includes legacy format for backwards compatibility with older app versions.
     *
     * @param uri URI from Storage Access Framework (ACTION_CREATE_DOCUMENT)
     */
    fun exportDictionaries(uri: Uri): DictionaryExportSummary {
        try {
            val (root, summary) = buildDictionariesJson()

            openOutputStream(uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    gson.toJson(root, writer)
                    writer.flush()
                }
            }

            Log.i(TAG, "Exported dictionaries: ${summary.customWordsCount} custom + " +
                "${summary.disabledWordsCount} disabled across ${summary.languageCount} languages")
            return summary
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary export failed", e)
            throw Exception("Dictionary export failed: ${e.message}", e)
        }
    }

    /**
     * Build the dictionary-export JSON tree without writing it anywhere. Pure
     * helper shared by [exportDictionaries] and [exportFullBackup]. Returns the
     * root [JsonObject] plus a [DictionaryExportSummary] for caller telemetry.
     */
    private fun buildDictionariesJson(): Pair<JsonObject, DictionaryExportSummary> {
        val languagesWithData = mutableSetOf<String>()
        var totalCustomWords = 0
        var totalDisabledWords = 0

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

        for (lang in languages) {
            val langKey = LanguagePreferenceKeys.customWordsKey(lang)
            val wordsJson = prefs.getString(langKey, "{}")
            if (wordsJson != null && wordsJson != "{}") {
                customWordsPerLang.add(lang, JsonParser.parseString(wordsJson))
                // Count words for logging + summary
                try {
                    val wordsMap = JsonParser.parseString(wordsJson).asJsonObject
                    if (wordsMap.size() > 0) {
                        totalCustomWords += wordsMap.size()
                        languagesWithData += lang
                    }
                } catch (e: Exception) { /* ignore count errors */ }
            }
        }
        root.add("custom_words_by_language", customWordsPerLang)

        // Export disabled words per language (new format)
        val disabledWordsPerLang = JsonObject()
        val disabledLanguages = LanguagePreferenceKeys.getLanguagesWithDisabledWords(prefs)

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
                languagesWithData += lang
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

        val summary = DictionaryExportSummary(totalCustomWords, totalDisabledWords, languagesWithData.size)
        return root to summary
    }

    /**
     * Build a `DictImportPlan` for the given URI without applying any changes.
     * Pure IO + delegation to the pure planner — UI calls this on a background
     * thread to populate the preview dialog before the user confirms.
     */
    fun buildDictImportPlan(uri: Uri, prefs: SharedPreferences): DictImportPlan {
        val jsonString = readJsonFromUri(uri)
        val currentCustom = readCurrentCustomWordsByLang(prefs)
        val currentDisabled = readCurrentDisabledWordsByLang(prefs)
        return DictImportPlanBuilder.fromJson(jsonString, currentCustom, currentDisabled)
    }

    /**
     * Apply a previously-built `DictImportPlan` against the current prefs.
     * Thin delegator to `DictImportApplier.apply` — returns a populated
     * `DictionaryImportResult` for caller telemetry/UI display.
     *
     * Atomicity: a single `editor.commit()` covers all languages — see
     * DictImportApplier.apply contract.
     */
    fun applyDictImportPlan(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
        prefs: SharedPreferences,
    ): DictionaryImportResult {
        val (customApplied, disabledApplied) = DictImportApplier.apply(
            plan, excludedCustom, excludedDisabled, prefs
        )
        return DictionaryImportResult().apply {
            sourceVersion = plan.sourceVersion
            userWordsImported = customApplied
            disabledWordsImported = disabledApplied
            excludedByUserCount = excludedCustom.size + excludedDisabled.size
        }
    }

    /**
     * Read the user's current per-language custom words from prefs.
     * Scans for keys matching `custom_words_<lang>` via reverse helper.
     */
    private fun readCurrentCustomWordsByLang(prefs: SharedPreferences): Map<String, Map<String, Int>> {
        val out = mutableMapOf<String, Map<String, Int>>()
        val gson = Gson()
        val mapType = object : com.google.gson.reflect.TypeToken<Map<String, Int>>() {}.type
        for ((key, value) in prefs.all) {
            val lang = LanguagePreferenceKeys.languageFromCustomWordsKey(key) ?: continue
            if (value !is String) continue
            try {
                out[lang] = gson.fromJson(value, mapType) ?: emptyMap()
            } catch (_: Exception) { /* skip malformed */ }
        }
        return out
    }

    /**
     * Read the user's current per-language disabled words from prefs.
     */
    private fun readCurrentDisabledWordsByLang(prefs: SharedPreferences): Map<String, Set<String>> {
        val out = mutableMapOf<String, Set<String>>()
        for ((key, value) in prefs.all) {
            val lang = LanguagePreferenceKeys.languageFromDisabledWordsKey(key) ?: continue
            @Suppress("UNCHECKED_CAST")
            if (value is Set<*>) out[lang] = value as Set<String>
        }
        return out
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
            val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
            val plan = buildDictImportPlan(uri, prefs)
            val result = applyDictImportPlan(plan, emptySet(), emptySet(), prefs)
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
        @JvmField var sourceVersion: String = "unknown",
        @JvmField var excludedByUserCount: Int = 0,    // NEW: user-deselected in preview
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

    /**
     * Result of a full backup ZIP export. Tracks which sections were included so
     * the UI can render an accurate success dialog (and so tests can assert
     * coverage). [totalBytes] is best-effort; it counts bytes streamed to the
     * ZIP, not the compressed file size on disk.
     *
     * GitHub #142: one-click full backup containing manifest + config + dicts +
     * clipboard JSON + clipboard media files in a single dated ZIP.
     */
    data class FullBackupResult(
        @JvmField val success: Boolean,
        @JvmField val configIncluded: Boolean,
        @JvmField val dictionaryCount: Int,
        @JvmField val clipboardEntryCount: Int,
        @JvmField val mediaFileCount: Int,
        @JvmField val errorMessage: String? = null,
        @JvmField val totalBytes: Long = 0,
    )

    /**
     * Result of a full backup ZIP import. Aggregates per-section counts from
     * the existing [SettingsImportApplier], [DictImportApplier], and
     * [ClipboardDatabase.importFromJSON] outputs.
     */
    data class FullBackupImportResult(
        @JvmField val success: Boolean,
        @JvmField val configImported: Boolean,
        @JvmField val configKeysApplied: Int,
        @JvmField val customWordsImported: Int,
        @JvmField val disabledWordsImported: Int,
        @JvmField val clipboardEntriesImported: Int,
        @JvmField val clipboardEntriesSkipped: Int,
        @JvmField val mediaFilesRestored: Int,
        @JvmField val sourceAppVersion: String? = null,
        @JvmField val errorMessage: String? = null,
    )

    /**
     * Export EVERYTHING in one dated ZIP file (GitHub #142). The ZIP layout is:
     *
     *   manifest.json          — top-level metadata (app version, export date,
     *                            section inventory)
     *   config.json            — same JSON that [exportConfig] produces
     *   dictionaries.json      — same JSON that [exportDictionaries] produces
     *   clipboard_history.json — same JSON that [exportClipboardHistory] produces
     *                            (textOnly = false so media references stay)
     *   clipboard_media/...    — media file blobs referenced by the clipboard JSON,
     *                            paths match the in-DB media_path values (matches
     *                            [exportClipboardHistoryZip] verbatim so existing
     *                            ZIP importer code can be reused).
     *
     * Reuses the per-section JSON-builder helpers ([buildConfigJson],
     * [buildDictionariesJson]) and the media-streaming pattern from
     * [exportClipboardHistoryZip] — no duplicate serialization logic.
     */
    fun exportFullBackup(uri: Uri, prefs: SharedPreferences): FullBackupResult {
        var configIncluded = false
        var dictionaryCount = 0
        var clipboardEntryCount = 0
        var mediaFileCount = 0
        var totalBytes = 0L
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            val versionCode = packageInfo.versionCode
            val exportDateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

            // Pre-build all JSON payloads (so manifest can carry final counts).
            val (configRoot, configCount) = buildConfigJson(prefs)
            val configBytes = gson.toJson(configRoot).toByteArray(Charsets.UTF_8)
            configIncluded = configCount > 0

            val (dictRoot, dictSummary) = buildDictionariesJson()
            val dictBytes = gson.toJson(dictRoot).toByteArray(Charsets.UTF_8)
            dictionaryCount = dictSummary.languageCount

            val clipboardDb = ClipboardDatabase.getInstance(context)
            val clipboardJson = clipboardDb.exportToJSON(textOnly = false)
            val clipboardBytes: ByteArray
            if (clipboardJson != null) {
                clipboardEntryCount = clipboardJson.optInt("total_active", 0) +
                    clipboardJson.optInt("total_pinned", 0) +
                    clipboardJson.optInt("total_todo", 0)
                clipboardBytes = clipboardJson.toString(2).toByteArray(Charsets.UTF_8)
            } else {
                Log.w(TAG, "Clipboard export returned null; ZIP will omit clipboard section")
                clipboardBytes = ByteArray(0)
            }

            // Manifest entry — written FIRST so importers can sanity-check format
            // and refuse forward-incompatible files without scanning the whole ZIP.
            val manifest = JsonObject().apply {
                addProperty("format", "cleverkeys_full_backup")
                addProperty("format_version", FULL_BACKUP_FORMAT_VERSION)
                addProperty("app_version", versionName)
                addProperty("app_version_code", versionCode)
                addProperty("export_date", exportDateIso)
                addProperty("config_preference_count", configCount)
                addProperty("dictionary_language_count", dictionaryCount)
                addProperty("dictionary_custom_word_count", dictSummary.customWordsCount)
                addProperty("dictionary_disabled_word_count", dictSummary.disabledWordsCount)
                addProperty("clipboard_entry_count", clipboardEntryCount)
                val entriesArray = JsonArray().apply {
                    add(ENTRY_MANIFEST)
                    if (configCount > 0) add(ENTRY_CONFIG)
                    if (clipboardBytes.isNotEmpty()) add(ENTRY_CLIPBOARD_JSON)
                    add(ENTRY_DICTIONARIES)
                }
                add("entries", entriesArray)
            }
            val manifestBytes = gson.toJson(manifest).toByteArray(Charsets.UTF_8)

            val mediaManager = ClipboardMediaManager(context)
            openOutputStream(uri)?.use { outputStream ->
                java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                    // 1. manifest.json (first — readable by tools that only inspect headers)
                    totalBytes += writeZipEntry(zipOut, ENTRY_MANIFEST, manifestBytes)

                    // 2. config.json
                    if (configCount > 0) {
                        totalBytes += writeZipEntry(zipOut, ENTRY_CONFIG, configBytes)
                    }

                    // 3. dictionaries.json (always written, even when empty — symmetric importer)
                    totalBytes += writeZipEntry(zipOut, ENTRY_DICTIONARIES, dictBytes)

                    // 4. clipboard_history.json
                    if (clipboardBytes.isNotEmpty()) {
                        totalBytes += writeZipEntry(zipOut, ENTRY_CLIPBOARD_JSON, clipboardBytes)
                    }

                    // 5. clipboard_media/* — stream each file directly (OOM-safe).
                    // mediaPath already contains the `clipboard_media/` prefix from
                    // the DB so the importer can re-extract straight back to the
                    // same on-disk location without translation.
                    val mediaPaths = clipboardDb.getAllReferencedMediaPaths()
                    for (mediaPath in mediaPaths) {
                        val file = mediaManager.getMediaFile(mediaPath)
                        if (!file.exists()) {
                            Log.w(TAG, "Media file not found during full backup, skipping: $mediaPath")
                            continue
                        }
                        zipOut.putNextEntry(java.util.zip.ZipEntry(mediaPath))
                        val streamed = file.inputStream().use { it.copyTo(zipOut) }
                        totalBytes += streamed
                        zipOut.closeEntry()
                        mediaFileCount++
                    }
                }
            }

            Log.i(TAG, "Full backup exported: cfg=$configCount, dict langs=$dictionaryCount, " +
                "clip entries=$clipboardEntryCount, media=$mediaFileCount, bytes=$totalBytes")
            return FullBackupResult(
                success = true,
                configIncluded = configIncluded,
                dictionaryCount = dictionaryCount,
                clipboardEntryCount = clipboardEntryCount,
                mediaFileCount = mediaFileCount,
                totalBytes = totalBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Full backup export failed", e)
            return FullBackupResult(
                success = false,
                configIncluded = configIncluded,
                dictionaryCount = dictionaryCount,
                clipboardEntryCount = clipboardEntryCount,
                mediaFileCount = mediaFileCount,
                errorMessage = e.message ?: "Unknown error",
                totalBytes = totalBytes,
            )
        }
    }

    /**
     * Helper: write a single in-memory ZIP entry and return the byte count
     * streamed (useful for [FullBackupResult.totalBytes]).
     */
    private fun writeZipEntry(
        zipOut: java.util.zip.ZipOutputStream,
        name: String,
        payload: ByteArray,
    ): Long {
        zipOut.putNextEntry(java.util.zip.ZipEntry(name))
        zipOut.write(payload)
        zipOut.closeEntry()
        return payload.size.toLong()
    }

    /**
     * Symmetric inverse of [exportFullBackup]. Streams the ZIP entries and
     * dispatches each to the corresponding per-section importer, then performs
     * thumbnail regeneration + orphan-media cleanup matching
     * [importClipboardHistoryZip].
     *
     * Forward-compat guard: refuses to import if the manifest's
     * `format_version` is strictly greater than the version this build knows
     * about. The user must update the app first.
     */
    fun importFullBackup(uri: Uri, prefs: SharedPreferences): FullBackupImportResult {
        var configKeysApplied = 0
        var configImported = false
        var customWordsImported = 0
        var disabledWordsImported = 0
        var clipboardEntriesImported = 0
        var clipboardEntriesSkipped = 0
        var mediaFilesRestored = 0
        var sourceAppVersion: String? = null

        return try {
            val clipboardDb = ClipboardDatabase.getInstance(context)
            val mediaManager = ClipboardMediaManager(context)

            // Buffer JSON payloads to memory (small) while streaming media to disk.
            // We must process manifest before applying anything else.
            var manifestJson: JsonObject? = null
            var configJsonBytes: ByteArray? = null
            var dictionariesJsonBytes: ByteArray? = null
            var clipboardJsonData: org.json.JSONObject? = null

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == ENTRY_MANIFEST -> {
                                val bytes = zipIn.readBytes()
                                manifestJson = JsonParser.parseString(
                                    String(bytes, Charsets.UTF_8)
                                ).asJsonObject
                                // Format guard — refuse unrelated ZIPs cleanly instead of
                                // silently treating them as v1 full-backup files.
                                val format = manifestJson?.get("format")?.asString
                                if (format != "cleverkeys_full_backup") {
                                    throw Exception("Not a CleverKeys full backup ZIP " +
                                        "(manifest.json `format` was \"${format ?: "<missing>"}\", " +
                                        "expected \"cleverkeys_full_backup\").")
                                }
                                // Forward-compat check — refuse newer formats early.
                                val formatVersion = manifestJson?.get("format_version")?.asInt ?: 1
                                if (formatVersion > FULL_BACKUP_FORMAT_VERSION) {
                                    throw Exception("Full backup format_version $formatVersion is newer " +
                                        "than supported ($FULL_BACKUP_FORMAT_VERSION). Update the app and retry.")
                                }
                                sourceAppVersion = manifestJson?.get("app_version")?.asString
                            }
                            entry.name == ENTRY_CONFIG -> {
                                configJsonBytes = zipIn.readBytes()
                            }
                            entry.name == ENTRY_DICTIONARIES -> {
                                dictionariesJsonBytes = zipIn.readBytes()
                            }
                            entry.name == ENTRY_CLIPBOARD_JSON -> {
                                val bytes = zipIn.readBytes()
                                clipboardJsonData = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                            }
                            entry.name.startsWith("clipboard_media/") -> {
                                val targetFile = mediaManager.getMediaFile(entry.name)
                                targetFile.parentFile?.mkdirs()
                                targetFile.outputStream().use { out -> zipIn.copyTo(out) }
                                mediaFilesRestored++
                            }
                            else -> {
                                Log.w(TAG, "Unknown entry in full backup, skipping: ${entry.name}")
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: throw Exception("Cannot open full backup ZIP")

            if (manifestJson == null) {
                throw Exception("Full backup is missing manifest.json")
            }

            // Apply config.json — funnel through SettingsImportPlanBuilder + Applier
            // so screen-mismatch + drift handling stay consistent with single-file import.
            configJsonBytes?.let { bytes ->
                val configString = String(bytes, Charsets.UTF_8)
                val snapshot: Map<String, Any?> = prefs.all.toMap()
                val dm = context.resources.displayMetrics
                val screen = ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density)
                val plan = SettingsImportPlanBuilder.fromJson(
                    configString,
                    currentSnapshot = snapshot,
                    screen = screen,
                    defaultSnapshot = SETTINGS_DEFAULTS,
                    currentShortSwipeRawJson = null,
                )
                val result = runBlocking {
                    SettingsImportApplier.apply(
                        plan, emptySet(), ShortSwipeImportMode.REPLACE, prefs, shortSwipeImporter
                    )
                }
                configKeysApplied = result.importedCount
                configImported = true
            }

            // Apply dictionaries.json — funnel through DictImportPlanBuilder + Applier.
            dictionariesJsonBytes?.let { bytes ->
                val dictString = String(bytes, Charsets.UTF_8)
                val currentCustom = readCurrentCustomWordsByLang(prefs)
                val currentDisabled = readCurrentDisabledWordsByLang(prefs)
                val plan = DictImportPlanBuilder.fromJson(dictString, currentCustom, currentDisabled)
                val (custom, disabled) = DictImportApplier.apply(
                    plan, emptySet(), emptySet(), prefs
                )
                customWordsImported = custom
                disabledWordsImported = disabled
            }

            // Apply clipboard_history.json + regenerate thumbnails for any media
            // we just extracted to disk. Mirrors importClipboardHistoryZip's
            // post-import housekeeping.
            clipboardJsonData?.let { json ->
                val importResult = clipboardDb.importFromJSON(json)
                clipboardEntriesImported = importResult[0] + importResult[1] + importResult[2]
                clipboardEntriesSkipped = importResult[3]
            }

            if (mediaFilesRestored > 0) {
                val referencedPaths = clipboardDb.getAllReferencedMediaPaths()
                for (path in referencedPaths) {
                    val file = mediaManager.getMediaFile(path)
                    if (!file.exists()) continue
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
                        updateThumbnailForMediaPath(clipboardDb, path, thumbnail)
                    }
                }
                mediaManager.cleanupOrphans(referencedPaths)
            }

            Log.i(TAG, "Full backup imported: cfg=$configKeysApplied, custom=$customWordsImported, " +
                "disabled=$disabledWordsImported, clip imported=$clipboardEntriesImported, " +
                "clip skipped=$clipboardEntriesSkipped, media restored=$mediaFilesRestored")

            FullBackupImportResult(
                success = true,
                configImported = configImported,
                configKeysApplied = configKeysApplied,
                customWordsImported = customWordsImported,
                disabledWordsImported = disabledWordsImported,
                clipboardEntriesImported = clipboardEntriesImported,
                clipboardEntriesSkipped = clipboardEntriesSkipped,
                mediaFilesRestored = mediaFilesRestored,
                sourceAppVersion = sourceAppVersion,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Full backup import failed", e)
            FullBackupImportResult(
                success = false,
                configImported = configImported,
                configKeysApplied = configKeysApplied,
                customWordsImported = customWordsImported,
                disabledWordsImported = disabledWordsImported,
                clipboardEntriesImported = clipboardEntriesImported,
                clipboardEntriesSkipped = clipboardEntriesSkipped,
                mediaFilesRestored = mediaFilesRestored,
                sourceAppVersion = sourceAppVersion,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }

    companion object {
        private const val TAG = "BackupRestoreManager"

        /**
         * Bumped when the full-backup ZIP layout changes in a non-back-compatible
         * way. The importer refuses files whose `format_version` is strictly
         * greater than this constant — older files are accepted (the importer
         * tolerates missing entries).
         */
        const val FULL_BACKUP_FORMAT_VERSION = 1

        // Canonical ZIP entry names — referenced by both export + import so the
        // names stay in lockstep.
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_CONFIG = "config.json"
        const val ENTRY_DICTIONARIES = "dictionaries.json"
        const val ENTRY_CLIPBOARD_JSON = "clipboard_history.json"
    }
}
