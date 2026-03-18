package tribixbite.cleverkeys

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ClipboardDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_CLIPBOARD (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_EXPIRY_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_IS_PINNED INTEGER DEFAULT 0,
                $COLUMN_IS_TODO INTEGER DEFAULT 0,
                $COLUMN_CONTENT_HASH TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_content_hash ON $TABLE_CLIPBOARD ($COLUMN_CONTENT_HASH)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CLIPBOARD ($COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_expiry ON $TABLE_CLIPBOARD ($COLUMN_EXPIRY_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrate from v1 to v2: add is_todo column
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CLIPBOARD ADD COLUMN $COLUMN_IS_TODO INTEGER DEFAULT 0")
                Log.d(TAG, "Database upgraded: added is_todo column")
            } catch (e: Exception) {
                Log.e(TAG, "Error upgrading database: ${e.message}")
            }
        }
    }

    fun addClipboardEntry(content: String?, expiryTimestamp: Long): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            val duplicateQuery = """
                SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? AND $COLUMN_EXPIRY_TIMESTAMP > ?
            """.trimIndent()
            db.rawQuery(duplicateQuery, arrayOf(contentHash, trimmedContent, currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    // #108: Move duplicate to top by updating its timestamp instead of ignoring
                    val existingId = cursor.getLong(0)
                    val updateValues = ContentValues().apply {
                        put(COLUMN_TIMESTAMP, currentTime)
                        put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                    }
                    db.update(TABLE_CLIPBOARD, updateValues, "$COLUMN_ID = ?", arrayOf(existingId.toString()))
                    Log.d(TAG, "Duplicate moved to top: ${trimmedContent.take(20)}... (id=$existingId)")
                    return true
                }
            }
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                put(COLUMN_IS_PINNED, 0)
                put(COLUMN_CONTENT_HASH, contentHash)
            }
            val result = db.insert(TABLE_CLIPBOARD, null, values)
            Log.d(TAG, "Added clipboard entry: ${trimmedContent.take(20)}... (id=$result)")
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clipboard entry: ${e.message}")
            false
        }
    }

    fun getActiveClipboardEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val currentTime = System.currentTimeMillis()
        val db = readableDatabase
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP FROM $TABLE_CLIPBOARD
            WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
            ORDER BY $COLUMN_TIMESTAMP DESC
        """.trimIndent()
        try {
            db.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(cursor.getString(0), cursor.getLong(1)))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving clipboard entries: ${e.message}")
        }
        Log.d(TAG, "Retrieved ${entries.size} active clipboard entries")
        return entries
    }

    fun getPinnedEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val db = readableDatabase
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP FROM $TABLE_CLIPBOARD
            WHERE $COLUMN_IS_PINNED = 1 ORDER BY $COLUMN_TIMESTAMP ASC
        """.trimIndent()
        try {
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(cursor.getString(0), cursor.getLong(1)))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving pinned entries: ${e.message}")
        }
        Log.d(TAG, "Retrieved ${entries.size} pinned entries")
        return entries
    }

    fun getTodoEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val db = readableDatabase
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP FROM $TABLE_CLIPBOARD
            WHERE $COLUMN_IS_TODO = 1 ORDER BY $COLUMN_TIMESTAMP ASC
        """.trimIndent()
        try {
            db.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(cursor.getString(0), cursor.getLong(1)))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving todo entries: ${e.message}")
        }
        Log.d(TAG, "Retrieved ${entries.size} todo entries")
        return entries
    }

    fun setTodoStatus(content: String?, isTodo: Boolean): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply { put(COLUMN_IS_TODO, if (isTodo) 1 else 0) }
            val updatedRows = db.update(TABLE_CLIPBOARD, values, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Updated todo status for $updatedRows entries: ${trimmedContent.take(20)}... (todo=$isTodo)")
            updatedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating todo status: ${e.message}")
            false
        }
    }

    fun removeClipboardEntry(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_CLIPBOARD, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Removed $deletedRows clipboard entries matching: ${trimmedContent.take(20)}...")
            deletedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error removing clipboard entry: ${e.message}")
            false
        }
    }

    fun clearAllEntries(): Result<Int> {
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_CLIPBOARD, "$COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0", null)
            Log.d(TAG, "Cleared $deletedRows clipboard entries (kept pinned and todo entries)")
            Result.success(deletedRows)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing clipboard entries: ${e.message}")
            Result.failure(e)
        }
    }

    fun cleanupExpiredEntries(): Int {
        val currentTime = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(
                TABLE_CLIPBOARD,
                "$COLUMN_EXPIRY_TIMESTAMP <= ? AND $COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0",
                arrayOf(currentTime.toString())
            )
            if (deletedRows > 0) Log.d(TAG, "Cleaned up $deletedRows expired clipboard entries")
            deletedRows
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired entries: ${e.message}")
            0
        }
    }

    fun setPinnedStatus(content: String?, isPinned: Boolean): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply { put(COLUMN_IS_PINNED, if (isPinned) 1 else 0) }
            val updatedRows = db.update(TABLE_CLIPBOARD, values, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Updated pin status for $updatedRows entries: ${trimmedContent.take(20)}... (pinned=$isPinned)")
            updatedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating pin status: ${e.message}")
            false
        }
    }

    fun getTotalEntryCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CLIPBOARD", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun getActiveEntryCount(): Int {
        val currentTime = System.currentTimeMillis()
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP > ? OR $COLUMN_IS_PINNED = 1",
            arrayOf(currentTime.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    data class StorageStats(
        val totalEntries: Int, val activeEntries: Int, val pinnedEntries: Int,
        val totalSizeBytes: Long, val activeSizeBytes: Long, val pinnedSizeBytes: Long
    )

    /**
     * Get database statistics as a Map (for ClipboardSettingsActivity compatibility)
     * @return Result containing stats map
     */
    fun getDatabaseStats(): Result<Map<String, Any>> {
        return try {
            val stats = getStorageStats()
            Result.success(mapOf(
                "total_entries" to stats.totalEntries,
                "active_entries" to stats.activeEntries,
                "pinned_entries" to stats.pinnedEntries,
                "expired_entries" to (stats.totalEntries - stats.activeEntries)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database stats", e)
            Result.failure(e)
        }
    }

    /**
     * Get storage statistics using pure SQL aggregation.
     * Uses LENGTH(CAST(content AS BLOB)) to measure UTF-8 byte sizes server-side,
     * avoiding loading content into JVM heap. Returns a single aggregated row
     * instead of iterating all entries — O(1) JNI crossings instead of O(n).
     */
    fun getStorageStats(): StorageStats {
        val currentTime = System.currentTimeMillis()
        var totalEntries = 0; var activeEntries = 0; var pinnedEntries = 0
        var totalSizeBytes = 0L; var activeSizeBytes = 0L; var pinnedSizeBytes = 0L
        val query = """
            SELECT
                COUNT(*),
                COALESCE(SUM(LENGTH(CAST($COLUMN_CONTENT AS BLOB))), 0),
                COALESCE(SUM(CASE WHEN $COLUMN_IS_PINNED = 1 THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN $COLUMN_IS_PINNED = 1 THEN LENGTH(CAST($COLUMN_CONTENT AS BLOB)) ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN $COLUMN_IS_PINNED = 1 OR $COLUMN_EXPIRY_TIMESTAMP > ? THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN $COLUMN_IS_PINNED = 1 OR $COLUMN_EXPIRY_TIMESTAMP > ? THEN LENGTH(CAST($COLUMN_CONTENT AS BLOB)) ELSE 0 END), 0)
            FROM $TABLE_CLIPBOARD
        """.trimIndent()
        readableDatabase.rawQuery(query, arrayOf(currentTime.toString(), currentTime.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                totalEntries = cursor.getInt(0)
                totalSizeBytes = cursor.getLong(1)
                pinnedEntries = cursor.getInt(2)
                pinnedSizeBytes = cursor.getLong(3)
                activeEntries = cursor.getInt(4)
                activeSizeBytes = cursor.getLong(5)
            }
        }
        return StorageStats(totalEntries, activeEntries, pinnedEntries, totalSizeBytes, activeSizeBytes, pinnedSizeBytes)
    }

    fun applySizeLimit(maxSize: Int): Int {
        if (maxSize <= 0) return 0
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            val currentCount = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?",
                arrayOf(currentTime.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (currentCount <= maxSize) return 0
            val entriesToDelete = currentCount - maxSize
            db.execSQL("""
                DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (
                    SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                    WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
                    ORDER BY $COLUMN_TIMESTAMP ASC LIMIT ?
                )
            """.trimIndent(), arrayOf(currentTime, entriesToDelete))
            Log.d(TAG, "Applied size limit: removed $entriesToDelete oldest entries (limit=$maxSize)")
            entriesToDelete
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit: ${e.message}")
            0
        }
    }

    /**
     * Remove oldest non-pinned/non-todo entries until total size is under [maxSizeMB].
     * Uses LENGTH(CAST(content AS BLOB)) to measure sizes server-side (no JVM heap allocation).
     * Batches DELETE in chunks of 500 IDs to stay within SQLite SQL length limits.
     */
    fun applySizeLimitBytes(maxSizeMB: Int): Int {
        if (maxSizeMB <= 0) return 0
        val maxSizeBytes = maxSizeMB * 1024L * 1024L
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            var totalSize = 0L
            val idsToDelete = mutableListOf<Long>()
            // Fetch only id + byte size — content never crosses JNI boundary
            db.rawQuery("""
                SELECT $COLUMN_ID, LENGTH(CAST($COLUMN_CONTENT AS BLOB))
                FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_IS_TODO = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
                ORDER BY $COLUMN_TIMESTAMP ASC
            """.trimIndent(), arrayOf(currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        totalSize += cursor.getLong(1)
                        if (totalSize > maxSizeBytes) idsToDelete.add(cursor.getLong(0))
                    } while (cursor.moveToNext())
                }
            }
            if (idsToDelete.isEmpty()) return 0
            // Batch deletes in chunks of 500 to stay within SQLITE_MAX_SQL_LENGTH
            for (chunk in idsToDelete.chunked(500)) {
                db.execSQL("DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (${chunk.joinToString(",")})")
            }
            Log.d(TAG, "Applied size limit (bytes): removed ${idsToDelete.size} oldest entries")
            idsToDelete.size
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit (bytes): ${e.message}")
            0
        }
    }

    fun exportToJSON(): JSONObject? {
        return try {
            val activeEntries = JSONArray(); val pinnedEntries = JSONArray(); val todoEntries = JSONArray()
            var activeCount = 0; var pinnedCount = 0; var todoCount = 0
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_IS_PINNED, $COLUMN_EXPIRY_TIMESTAMP, $COLUMN_IS_TODO
                FROM $TABLE_CLIPBOARD ORDER BY $COLUMN_TIMESTAMP DESC
            """.trimIndent(), null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val entry = JSONObject().apply {
                            put("content", cursor.getString(0))
                            put("timestamp", cursor.getLong(1))
                            put("expiry_timestamp", cursor.getLong(3))
                        }
                        val isPinned = cursor.getInt(2) == 1
                        val isTodo = cursor.getInt(4) == 1
                        when {
                            isTodo -> { todoEntries.put(entry); todoCount++ }
                            isPinned -> { pinnedEntries.put(entry); pinnedCount++ }
                            else -> { activeEntries.put(entry); activeCount++ }
                        }
                    } while (cursor.moveToNext())
                }
            }
            JSONObject().apply {
                put("active_entries", activeEntries)
                put("pinned_entries", pinnedEntries)
                put("todo_entries", todoEntries)
                put("export_version", 2)  // v2: includes todo_entries
                put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("total_active", activeCount)
                put("total_pinned", pinnedCount)
                put("total_todo", todoCount)
            }.also { Log.d(TAG, "Exported $activeCount active, $pinnedCount pinned, $todoCount todo clipboard entries") }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting clipboard data: ${e.message}")
            null
        }
    }

    /**
     * Import clipboard entries from a JSON export.
     * Wrapped in a single transaction for atomicity and performance —
     * eliminates per-row fsync() (was ~30-60s for 2000 entries, now <1s).
     * Duplicate detection uses moveToFirst() instead of cursor.count for efficiency.
     *
     * @return IntArray of [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
     */
    fun importFromJSON(importData: JSONObject): IntArray {
        var activeAdded = 0; var pinnedAdded = 0; var todoAdded = 0; var duplicatesSkipped = 0
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // Compute fresh expiry once (same TTL for all entries in this import batch)
                val freshExpiry = ClipboardHistoryService.getHistoryTtlMs().let { ttl ->
                    if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttl
                }

                if (importData.has("active_entries")) {
                    val result = importEntryArray(db, importData.getJSONArray("active_entries"),
                        isPinned = 0, isTodo = 0, freshExpiry = freshExpiry)
                    activeAdded = result.first
                    duplicatesSkipped += result.second
                }
                if (importData.has("pinned_entries")) {
                    val result = importEntryArray(db, importData.getJSONArray("pinned_entries"),
                        isPinned = 1, isTodo = 0, freshExpiry = freshExpiry)
                    pinnedAdded = result.first
                    duplicatesSkipped += result.second
                }
                // v2: Import todo entries (backwards compatible — older exports won't have this)
                if (importData.has("todo_entries")) {
                    val result = importEntryArray(db, importData.getJSONArray("todo_entries"),
                        isPinned = 0, isTodo = 1, freshExpiry = freshExpiry)
                    todoAdded = result.first
                    duplicatesSkipped += result.second
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.d(TAG, "Import complete: $activeAdded active, $pinnedAdded pinned, $todoAdded todo added, $duplicatesSkipped duplicates skipped")
        } catch (e: Exception) {
            Log.e(TAG, "Error importing clipboard data: ${e.message}")
        }
        return intArrayOf(activeAdded, pinnedAdded, todoAdded, duplicatesSkipped)
    }

    /**
     * Import a single category of entries (active/pinned/todo) from a JSONArray.
     * @return Pair(added, skippedDuplicates)
     */
    private fun importEntryArray(
        db: SQLiteDatabase, entries: JSONArray,
        isPinned: Int, isTodo: Int, freshExpiry: Long
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val contentHash = content.hashCode().toString()
            // Duplicate check — moveToFirst() is more efficient than cursor.count
            val isDuplicate = db.rawQuery(
                "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                arrayOf(contentHash, content)
            ).use { it.moveToFirst() }
            if (isDuplicate) {
                skipped++
                continue
            }
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
                put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)
                put(COLUMN_IS_PINNED, isPinned)
                put(COLUMN_IS_TODO, isTodo)
                put(COLUMN_CONTENT_HASH, contentHash)
            }
            if (db.insert(TABLE_CLIPBOARD, null, values) != -1L) added++
        }
        return Pair(added, skipped)
    }

    companion object {
        private const val DATABASE_NAME = "clipboard_history.db"
        private const val DATABASE_VERSION = 2  // v2: Added is_todo column
        private const val TABLE_CLIPBOARD = "clipboard_entries"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val COLUMN_IS_PINNED = "is_pinned"
        private const val COLUMN_IS_TODO = "is_todo"
        private const val COLUMN_CONTENT_HASH = "content_hash"
        private const val TAG = "ClipboardDatabase"
        @Volatile private var instance: ClipboardDatabase? = null
        @JvmStatic
        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: ClipboardDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
