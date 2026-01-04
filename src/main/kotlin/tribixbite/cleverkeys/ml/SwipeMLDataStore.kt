package tribixbite.cleverkeys.ml

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent storage manager for ML swipe data.
 * Uses SQLite for efficient storage and querying, with JSON export capability.
 */
class SwipeMLDataStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _context: Context = context
    private val _executor = Executors.newSingleThreadExecutor()

    override fun onCreate(db: SQLiteDatabase) {
        // Create main data table
        val createTable = """CREATE TABLE $TABLE_SWIPES (
            $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_TRACE_ID TEXT UNIQUE NOT NULL,
            $COL_TARGET_WORD TEXT NOT NULL,
            $COL_TIMESTAMP INTEGER NOT NULL,
            $COL_SOURCE TEXT NOT NULL,
            $COL_JSON_DATA TEXT NOT NULL,
            $COL_IS_EXPORTED INTEGER DEFAULT 0
        )"""
        db.execSQL(createTable)

        // Create indexes separately
        db.execSQL("CREATE INDEX idx_word ON $TABLE_SWIPES ($COL_TARGET_WORD)")
        db.execSQL("CREATE INDEX idx_source ON $TABLE_SWIPES ($COL_SOURCE)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_SWIPES ($COL_TIMESTAMP)")

        Log.d(TAG, "Database created with version $DATABASE_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle future schema upgrades
        Log.w(TAG, "Upgrading database from version $oldVersion to $newVersion")
    }

    /**
     * Store swipe data asynchronously
     */
    fun storeSwipeData(data: SwipeMLData) {
        if (!data.isValid()) {
            Log.w(TAG, "Invalid swipe data, not storing")
            return
        }

        _executor.execute {
            try {
                val db = writableDatabase

                // Check if trace_id already exists
                val cursor = db.query(
                    TABLE_SWIPES, arrayOf(COL_ID),
                    "$COL_TRACE_ID=?", arrayOf(data.traceId),
                    null, null, null
                )

                if (cursor.count > 0) {
                    cursor.close()
                    Log.w(TAG, "Trace ID already exists: ${data.traceId}")
                    return@execute
                }
                cursor.close()

                // Store the data
                val values = ContentValues().apply {
                    put(COL_TRACE_ID, data.traceId)
                    put(COL_TARGET_WORD, data.targetWord)
                    put(COL_TIMESTAMP, data.timestampUtc)
                    put(COL_SOURCE, data.collectionSource)
                    put(COL_JSON_DATA, data.toJSON().toString())
                    put(COL_IS_EXPORTED, 0)
                }

                val rowId = db.insert(TABLE_SWIPES, null, values)

                if (rowId != -1L) {
                    Log.d(TAG, "Stored swipe data: ${data.targetWord} (${data.collectionSource})")
                    updateStatistics(data.collectionSource)
                } else {
                    Log.e(TAG, "Failed to store swipe data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error storing swipe data", e)
            }
        }
    }

    /**
     * Store multiple swipe data entries (batch operation)
     */
    fun storeSwipeDataBatch(dataList: List<SwipeMLData>) {
        _executor.execute {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (data in dataList) {
                    if (!data.isValid()) continue

                    val values = ContentValues().apply {
                        put(COL_TRACE_ID, data.traceId)
                        put(COL_TARGET_WORD, data.targetWord)
                        put(COL_TIMESTAMP, data.timestampUtc)
                        put(COL_SOURCE, data.collectionSource)
                        put(COL_JSON_DATA, data.toJSON().toString())
                        put(COL_IS_EXPORTED, 0)
                    }

                    db.insertWithOnConflict(TABLE_SWIPES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
                Log.d(TAG, "Stored batch of ${dataList.size} swipe entries")
            } catch (e: Exception) {
                Log.e(TAG, "Error storing batch data", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Load all swipe data (for export or training)
     */
    fun loadAllData(): List<SwipeMLData> {
        val dataList = mutableListOf<SwipeMLData>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_SWIPES, arrayOf(COL_JSON_DATA),
            null, null, null, null, "$COL_TIMESTAMP ASC"
        )

        while (cursor.moveToNext()) {
            try {
                val jsonStr = cursor.getString(0)
                val json = JSONObject(jsonStr)
                dataList.add(SwipeMLData(json))
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing stored JSON", e)
            }
        }
        cursor.close()

        Log.d(TAG, "Loaded ${dataList.size} swipe entries")
        return dataList
    }

    /**
     * Load data by collection source
     */
    fun loadDataBySource(source: String): List<SwipeMLData> {
        val dataList = mutableListOf<SwipeMLData>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_SWIPES, arrayOf(COL_JSON_DATA),
            "$COL_SOURCE=?", arrayOf(source),
            null, null, "$COL_TIMESTAMP ASC"
        )

        while (cursor.moveToNext()) {
            try {
                val jsonStr = cursor.getString(0)
                val json = JSONObject(jsonStr)
                dataList.add(SwipeMLData(json))
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing stored JSON", e)
            }
        }
        cursor.close()

        return dataList
    }

    /**
     * Load recent data for incremental training
     */
    fun loadRecentData(limit: Int): List<SwipeMLData> {
        val dataList = mutableListOf<SwipeMLData>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_SWIPES, arrayOf(COL_JSON_DATA),
            null, null, null, null,
            "$COL_TIMESTAMP DESC", limit.toString()
        )

        while (cursor.moveToNext()) {
            try {
                val jsonStr = cursor.getString(0)
                val json = JSONObject(jsonStr)
                dataList.add(SwipeMLData(json))
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing stored JSON", e)
            }
        }
        cursor.close()

        return dataList
    }

    /**
     * Delete a specific swipe entry from the database
     */
    fun deleteEntry(data: SwipeMLData?) {
        if (data == null) return

        val db = writableDatabase

        // Delete by matching the word and approximate timestamp
        val word = data.targetWord
        val timestamp = data.timestampUtc

        // Allow 1 second tolerance for timestamp matching
        val minTime = timestamp - 1000
        val maxTime = timestamp + 1000

        val deleted = db.delete(
            TABLE_SWIPES,
            "$COL_TARGET_WORD=? AND $COL_TIMESTAMP BETWEEN ? AND ?",
            arrayOf(word, minTime.toString(), maxTime.toString())
        )

        Log.d(TAG, "Deleted $deleted entries for word: $word")
    }

    /**
     * Export all data to JSON file
     */
    fun exportToJSON(): File {
        val allData = loadAllData()

        // Create export directory
        val exportDir = File(_context.getExternalFilesDir(null), "swipe_ml_export")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        // Create filename with timestamp
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val filename = "swipe_data_${sdf.format(Date())}.json"
        val exportFile = File(exportDir, filename)

        // Build JSON array
        val jsonArray = JSONArray()
        for (data in allData) {
            jsonArray.put(data.toJSON())
        }

        // Add metadata
        val root = JSONObject().apply {
            put("export_version", "1.0")
            put("export_timestamp", System.currentTimeMillis())
            put("total_samples", allData.size)
            put("database_version", DATABASE_VERSION)
            put("data", jsonArray)
        }

        // Add statistics
        val prefs = _context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stats = JSONObject().apply {
            put("total_swipes", prefs.getInt(PREF_TOTAL_COUNT, 0))
            put("calibration_swipes", prefs.getInt(PREF_CALIBRATION_COUNT, 0))
            put("user_swipes", prefs.getInt(PREF_USER_COUNT, 0))
        }
        root.put("statistics", stats)

        // Write to file
        FileWriter(exportFile).use { writer ->
            writer.write(root.toString(2)) // Pretty print with 2-space indent
        }

        // Mark all as exported
        markAllAsExported()

        Log.i(TAG, "Exported ${allData.size} entries to ${exportFile.absolutePath}")
        return exportFile
    }

    /**
     * Export to newline-delimited JSON (NDJSON) for streaming processing
     */
    fun exportToNDJSON(): File {
        val allData = loadAllData()

        val exportDir = File(_context.getExternalFilesDir(null), "swipe_ml_export")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val filename = "swipe_data_${sdf.format(Date())}.ndjson"
        val exportFile = File(exportDir, filename)

        FileWriter(exportFile).use { writer ->
            for (data in allData) {
                writer.write(data.toJSON().toString())
                writer.write("\n")
            }
        }

        Log.i(TAG, "Exported ${allData.size} entries to NDJSON: ${exportFile.absolutePath}")
        return exportFile
    }

    /**
     * Export all data to JSON via OutputStream (for SAF file picker)
     */
    fun exportToJSON(outputStream: OutputStream): Int {
        val allData = loadAllData()

        // Build JSON array
        val jsonArray = JSONArray()
        for (data in allData) {
            jsonArray.put(data.toJSON())
        }

        // Add metadata
        val root = JSONObject().apply {
            put("export_version", "1.0")
            put("export_timestamp", System.currentTimeMillis())
            put("total_samples", allData.size)
            put("database_version", DATABASE_VERSION)
            put("data", jsonArray)
        }

        // Add statistics
        val prefs = _context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stats = JSONObject().apply {
            put("total_swipes", prefs.getInt(PREF_TOTAL_COUNT, 0))
            put("calibration_swipes", prefs.getInt(PREF_CALIBRATION_COUNT, 0))
            put("user_swipes", prefs.getInt(PREF_USER_COUNT, 0))
        }
        root.put("statistics", stats)

        // Write to stream
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write(root.toString(2)) // Pretty print with 2-space indent
        }

        // Mark all as exported
        markAllAsExported()

        Log.i(TAG, "Exported ${allData.size} entries to JSON stream")
        return allData.size
    }

    /**
     * Export to NDJSON via OutputStream (for SAF file picker)
     */
    fun exportToNDJSON(outputStream: OutputStream): Int {
        val allData = loadAllData()

        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            for (data in allData) {
                writer.write(data.toJSON().toString())
                writer.write("\n")
            }
        }

        Log.i(TAG, "Exported ${allData.size} entries to NDJSON stream")
        return allData.size
    }

    /**
     * Get statistics about stored data
     */
    fun getStatistics(): DataStatistics {
        val db = readableDatabase
        val stats = DataStatistics()

        // Total count
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_SWIPES", null).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.totalCount = cursor.getInt(0)
            }
        }

        // Count by source
        db.rawQuery(
            "SELECT $COL_SOURCE, COUNT(*) FROM $TABLE_SWIPES GROUP BY $COL_SOURCE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.getString(0)
                val count = cursor.getInt(1)
                when (source) {
                    "calibration", "neural_calibration" -> stats.calibrationCount += count
                    "user_selection" -> stats.userSelectionCount = count
                }
            }
        }

        // Unique words
        db.rawQuery("SELECT COUNT(DISTINCT $COL_TARGET_WORD) FROM $TABLE_SWIPES", null).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.uniqueWords = cursor.getInt(0)
            }
        }

        return stats
    }

    /**
     * Clear all data (with confirmation)
     */
    fun clearAllData() {
        val db = writableDatabase
        db.delete(TABLE_SWIPES, null, null)

        // Reset statistics
        val prefs = _context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        Log.w(TAG, "All swipe data cleared")
    }

    /**
     * Import swipe data from JSON file
     * @param jsonFile File containing exported JSON data
     * @return Number of records imported
     */
    fun importFromJSON(jsonFile: File): Int {
        if (!jsonFile.exists()) {
            throw java.io.IOException("Import file does not exist: ${jsonFile.path}")
        }

        // Read file content
        val jsonContent = StringBuilder()
        BufferedReader(FileReader(jsonFile)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                jsonContent.append(line)
            }
        }

        // Parse JSON
        val root = JSONObject(jsonContent.toString())
        val swipes = root.getJSONArray("swipes")

        var importedCount = 0
        val db = writableDatabase
        db.beginTransaction()

        try {
            for (i in 0 until swipes.length()) {
                val swipe = swipes.getJSONObject(i)

                // Check if trace already exists
                val traceId = swipe.getString("trace_id")
                val cursor = db.query(
                    TABLE_SWIPES, arrayOf(COL_ID),
                    "$COL_TRACE_ID=?", arrayOf(traceId),
                    null, null, null
                )

                if (!cursor.moveToFirst()) {
                    // Insert new record
                    val values = ContentValues().apply {
                        put(COL_TRACE_ID, traceId)
                        put(COL_TARGET_WORD, swipe.getString("target_word"))
                        put(COL_TIMESTAMP, swipe.getLong("timestamp_utc"))
                        put(COL_SOURCE, swipe.getString("source"))
                        put(COL_JSON_DATA, swipe.toString())
                        put(COL_IS_EXPORTED, 1) // Mark as already exported
                    }

                    db.insert(TABLE_SWIPES, null, values)
                    importedCount++
                }
                cursor.close()
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "Imported $importedCount swipe records from ${jsonFile.name}")
        } finally {
            db.endTransaction()
        }

        // Update statistics
        val prefs = _context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val total = prefs.getInt(PREF_TOTAL_COUNT, 0)
        prefs.edit().putInt(PREF_TOTAL_COUNT, total + importedCount).apply()

        return importedCount
    }

    /**
     * Mark all entries as exported
     */
    private fun markAllAsExported() {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_EXPORTED, 1)
        }
        db.update(TABLE_SWIPES, values, null, null)
    }

    /**
     * Update statistics
     */
    private fun updateStatistics(source: String) {
        val prefs = _context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Increment total count
        val total = prefs.getInt(PREF_TOTAL_COUNT, 0)
        editor.putInt(PREF_TOTAL_COUNT, total + 1)

        // Increment source-specific count
        // Note: "neural_calibration" is the source used by SwipeCalibrationActivity
        when (source) {
            "calibration", "neural_calibration" -> {
                val count = prefs.getInt(PREF_CALIBRATION_COUNT, 0)
                editor.putInt(PREF_CALIBRATION_COUNT, count + 1)
            }
            "user_selection" -> {
                val count = prefs.getInt(PREF_USER_COUNT, 0)
                editor.putInt(PREF_USER_COUNT, count + 1)
            }
        }

        editor.apply()
    }

    /**
     * Statistics class
     */
    data class DataStatistics(
        @JvmField var totalCount: Int = 0,
        @JvmField var calibrationCount: Int = 0,
        @JvmField var userSelectionCount: Int = 0,
        @JvmField var uniqueWords: Int = 0
    ) {
        override fun toString(): String {
            return String.format(
                Locale.US,
                "Total: %d, Calibration: %d, User: %d, Unique words: %d",
                totalCount, calibrationCount, userSelectionCount, uniqueWords
            )
        }
    }

    companion object {
        private const val TAG = "SwipeMLDataStore"

        // Database configuration
        private const val DATABASE_NAME = "swipe_ml_data.db"
        private const val DATABASE_VERSION = 1

        // Table and column names
        private const val TABLE_SWIPES = "swipe_data"
        private const val COL_ID = "id"
        private const val COL_TRACE_ID = "trace_id"
        private const val COL_TARGET_WORD = "target_word"
        private const val COL_TIMESTAMP = "timestamp_utc"
        private const val COL_SOURCE = "collection_source"
        private const val COL_JSON_DATA = "json_data"
        private const val COL_IS_EXPORTED = "is_exported"

        // Statistics tracking
        private const val PREF_NAME = "swipe_ml_stats"
        private const val PREF_TOTAL_COUNT = "total_swipes"
        private const val PREF_CALIBRATION_COUNT = "calibration_swipes"
        private const val PREF_USER_COUNT = "user_swipes"

        @Volatile
        private var _instance: SwipeMLDataStore? = null

        /**
         * Get singleton instance
         */
        @JvmStatic
        fun getInstance(context: Context): SwipeMLDataStore {
            return _instance ?: synchronized(this) {
                _instance ?: SwipeMLDataStore(context.applicationContext).also { _instance = it }
            }
        }
    }
}
