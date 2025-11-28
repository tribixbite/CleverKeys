package tribixbite.keyboard2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
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
                $COLUMN_CONTENT_HASH TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
        db.execSQL("CREATE INDEX idx_content_hash ON $TABLE_CLIPBOARD ($COLUMN_CONTENT_HASH)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CLIPBOARD ($COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_expiry ON $TABLE_CLIPBOARD ($COLUMN_EXPIRY_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CLIPBOARD")
        onCreate(db)
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
                if (cursor.count > 0) {
                    Log.d(TAG, "Duplicate entry ignored: ${trimmedContent.take(20)}...")
                    return false
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
            WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
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
            WHERE $COLUMN_IS_PINNED = 1 ORDER BY $COLUMN_TIMESTAMP DESC
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

    fun clearAllEntries() {
        try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_CLIPBOARD, "$COLUMN_IS_PINNED = 0", null)
            Log.d(TAG, "Cleared $deletedRows clipboard entries (kept pinned entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing clipboard entries: ${e.message}")
        }
    }

    fun cleanupExpiredEntries(): Int {
        val currentTime = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(
                TABLE_CLIPBOARD,
                "$COLUMN_EXPIRY_TIMESTAMP <= ? AND $COLUMN_IS_PINNED = 0",
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

    fun getStorageStats(): StorageStats {
        val currentTime = System.currentTimeMillis()
        var totalEntries = 0; var activeEntries = 0; var pinnedEntries = 0
        var totalSizeBytes = 0L; var activeSizeBytes = 0L; var pinnedSizeBytes = 0L
        readableDatabase.rawQuery(
            "SELECT $COLUMN_CONTENT, $COLUMN_IS_PINNED, $COLUMN_EXPIRY_TIMESTAMP FROM $TABLE_CLIPBOARD", null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val content = cursor.getString(0)
                    val isPinned = cursor.getInt(1) == 1
                    val expiryTimestamp = cursor.getLong(2)
                    val contentSize = try { content.toByteArray(StandardCharsets.UTF_8).size.toLong() } catch (e: Exception) { 0L }
                    totalEntries++; totalSizeBytes += contentSize
                    if (isPinned) { pinnedEntries++; pinnedSizeBytes += contentSize }
                    if (isPinned || expiryTimestamp > currentTime) { activeEntries++; activeSizeBytes += contentSize }
                } while (cursor.moveToNext())
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
                "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?",
                arrayOf(currentTime.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (currentCount <= maxSize) return 0
            val entriesToDelete = currentCount - maxSize
            db.execSQL("""
                DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (
                    SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                    WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
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

    fun applySizeLimitBytes(maxSizeMB: Int): Int {
        if (maxSizeMB <= 0) return 0
        val maxSizeBytes = maxSizeMB * 1024L * 1024L
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            var totalSize = 0L
            val idsToDelete = mutableListOf<Long>()
            db.rawQuery("""
                SELECT $COLUMN_ID, $COLUMN_CONTENT FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
                ORDER BY $COLUMN_TIMESTAMP ASC
            """.trimIndent(), arrayOf(currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val contentSize = try {
                            cursor.getString(1).toByteArray(StandardCharsets.UTF_8).size.toLong()
                        } catch (e: Exception) { 0L }
                        totalSize += contentSize
                        if (totalSize > maxSizeBytes) idsToDelete.add(cursor.getLong(0))
                    } while (cursor.moveToNext())
                }
            }
            if (idsToDelete.isEmpty()) return 0
            db.execSQL("DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (${idsToDelete.joinToString(",")})")
            Log.d(TAG, "Applied size limit (bytes): removed ${idsToDelete.size} oldest entries")
            idsToDelete.size
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit (bytes): ${e.message}")
            0
        }
    }

    fun exportToJSON(): JSONObject? {
        return try {
            val activeEntries = JSONArray(); val pinnedEntries = JSONArray()
            var activeCount = 0; var pinnedCount = 0
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_IS_PINNED, $COLUMN_EXPIRY_TIMESTAMP
                FROM $TABLE_CLIPBOARD ORDER BY $COLUMN_TIMESTAMP DESC
            """.trimIndent(), null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val entry = JSONObject().apply {
                            put("content", cursor.getString(0))
                            put("timestamp", cursor.getLong(1))
                            put("expiry_timestamp", cursor.getLong(3))
                        }
                        if (cursor.getInt(2) == 1) { pinnedEntries.put(entry); pinnedCount++ }
                        else { activeEntries.put(entry); activeCount++ }
                    } while (cursor.moveToNext())
                }
            }
            JSONObject().apply {
                put("active_entries", activeEntries); put("pinned_entries", pinnedEntries)
                put("export_version", 1)
                put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("total_active", activeCount); put("total_pinned", pinnedCount)
            }.also { Log.d(TAG, "Exported $activeCount active and $pinnedCount pinned clipboard entries") }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting clipboard data: ${e.message}")
            null
        }
    }

    fun importFromJSON(importData: JSONObject): IntArray {
        var activeAdded = 0; var pinnedAdded = 0; var duplicatesSkipped = 0
        try {
            val db = writableDatabase
            if (importData.has("active_entries")) {
                val activeEntries = importData.getJSONArray("active_entries")
                for (i in 0 until activeEntries.length()) {
                    val entry = activeEntries.getJSONObject(i)
                    val content = entry.getString("content")
                    val contentHash = content.hashCode().toString()
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                        arrayOf(contentHash, content)
                    ).use { if (it.count > 0) { duplicatesSkipped++; return@use } }
                    val values = ContentValues().apply {
                        put(COLUMN_CONTENT, content)
                        put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
                        put(COLUMN_EXPIRY_TIMESTAMP, entry.getLong("expiry_timestamp"))
                        put(COLUMN_IS_PINNED, 0)
                        put(COLUMN_CONTENT_HASH, contentHash)
                    }
                    if (db.insert(TABLE_CLIPBOARD, null, values) != -1L) activeAdded++
                }
            }
            if (importData.has("pinned_entries")) {
                val pinnedEntries = importData.getJSONArray("pinned_entries")
                for (i in 0 until pinnedEntries.length()) {
                    val entry = pinnedEntries.getJSONObject(i)
                    val content = entry.getString("content")
                    val contentHash = content.hashCode().toString()
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                        arrayOf(contentHash, content)
                    ).use { if (it.count > 0) { duplicatesSkipped++; return@use } }
                    val values = ContentValues().apply {
                        put(COLUMN_CONTENT, content)
                        put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
                        put(COLUMN_EXPIRY_TIMESTAMP, if (entry.has("expiry_timestamp")) 
                            entry.getLong("expiry_timestamp") else System.currentTimeMillis() + HISTORY_TTL_MS)
                        put(COLUMN_IS_PINNED, 1)
                        put(COLUMN_CONTENT_HASH, contentHash)
                    }
                    if (db.insert(TABLE_CLIPBOARD, null, values) != -1L) pinnedAdded++
                }
            }
            Log.d(TAG, "Import complete: $activeAdded active, $pinnedAdded pinned added, $duplicatesSkipped duplicates skipped")
        } catch (e: Exception) {
            Log.e(TAG, "Error importing clipboard data: ${e.message}")
        }
        return intArrayOf(activeAdded, pinnedAdded, duplicatesSkipped)
    }

    companion object {
        private const val DATABASE_NAME = "clipboard_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_CLIPBOARD = "clipboard_entries"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val COLUMN_IS_PINNED = "is_pinned"
        private const val COLUMN_CONTENT_HASH = "content_hash"
        private const val TAG = "ClipboardDatabase"
        private const val HISTORY_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        @Volatile private var instance: ClipboardDatabase? = null
        @JvmStatic
        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: ClipboardDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
