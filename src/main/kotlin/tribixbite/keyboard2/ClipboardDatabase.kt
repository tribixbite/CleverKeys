package tribixbite.keyboard2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Modern Kotlin SQLite-based storage for clipboard history.
 *
 * Features:
 * - Coroutine-safe operations with suspend functions
 * - Robust error handling with Result<T> return types
 * - Efficient indexing for performance
 * - Automatic cleanup of expired entries
 * - Pin functionality to preserve important clips
 * - Thread-safe singleton pattern with mutex protection
 */
class ClipboardDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "clipboard_history.db"
        private const val DATABASE_VERSION = 1

        // Table and column names
        private const val TABLE_CLIPBOARD = "clipboard_entries"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val COLUMN_IS_PINNED = "is_pinned"
        private const val COLUMN_CONTENT_HASH = "content_hash"

        @Volatile
        private var INSTANCE: ClipboardDatabase? = null
        private val initMutex = Mutex()

        suspend fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: initMutex.withLock {
                INSTANCE ?: ClipboardDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val operationMutex = Mutex()

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

        // Create optimized indices for common operations
        db.execSQL("CREATE INDEX idx_content_hash ON $TABLE_CLIPBOARD ($COLUMN_CONTENT_HASH)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CLIPBOARD ($COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_expiry ON $TABLE_CLIPBOARD ($COLUMN_EXPIRY_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_pinned ON $TABLE_CLIPBOARD ($COLUMN_IS_PINNED)")

        Log.d("ClipboardDatabase", "Database created with optimized indices")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w("ClipboardDatabase", "Upgrading database from version $oldVersion to $newVersion")

        try {
            // Migrate database schema while preserving data
            when {
                oldVersion < 2 && newVersion >= 2 -> {
                    // Example migration for future version 2:
                    // ALTER TABLE clipboard_entries ADD COLUMN new_column TEXT DEFAULT ''
                    Log.d("ClipboardDatabase", "No migrations needed from v$oldVersion to v$newVersion")
                }
                // Add more migration paths here as schema evolves
                // oldVersion < 3 && newVersion >= 3 -> { ... }
            }

            // For now, since this is version 1, no migrations exist yet
            // When version 2 is released, implement proper ALTER TABLE migrations above
            Log.d("ClipboardDatabase", "Database upgrade completed successfully")

        } catch (e: Exception) {
            Log.e("ClipboardDatabase", "Migration failed, recreating database", e)

            // Last resort: backup existing data, recreate table, restore data
            backupAndRecreateDatabase(db)
        }
    }

    /**
     * Backup existing data, recreate table with new schema, restore data.
     * This is a last-resort migration strategy if ALTER TABLE migrations fail.
     */
    private fun backupAndRecreateDatabase(db: SQLiteDatabase) {
        Log.w("ClipboardDatabase", "Performing backup-and-recreate migration")

        try {
            // Backup existing data to temporary table
            db.execSQL("CREATE TEMPORARY TABLE clipboard_backup AS SELECT * FROM $TABLE_CLIPBOARD")

            // Drop old table
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CLIPBOARD")

            // Create new table with current schema
            onCreate(db)

            // Restore data from backup (only columns that still exist)
            val restoreQuery = """
                INSERT OR IGNORE INTO $TABLE_CLIPBOARD
                    ($COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP, $COLUMN_IS_PINNED, $COLUMN_CONTENT_HASH)
                SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP, $COLUMN_IS_PINNED, $COLUMN_CONTENT_HASH
                FROM clipboard_backup
            """.trimIndent()

            db.execSQL(restoreQuery)

            // Drop backup table
            db.execSQL("DROP TABLE clipboard_backup")

            Log.d("ClipboardDatabase", "Backup-and-recreate migration completed successfully")

        } catch (e: Exception) {
            Log.e("ClipboardDatabase", "Backup-and-recreate migration failed, data may be lost", e)

            // If all else fails, start fresh (this should be extremely rare)
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CLIPBOARD")
            db.execSQL("DROP TABLE IF EXISTS clipboard_backup")
            onCreate(db)
        }
    }

    /**
     * Add a new clipboard entry to the database.
     *
     * @param content The clipboard content to store
     * @param expiryTimestamp When this entry should expire
     * @return Result indicating success or failure with error details
     */
    suspend fun addClipboardEntry(content: String, expiryTimestamp: Long): Result<Boolean> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val trimmedContent = content.trim()
                    if (trimmedContent.isEmpty()) {
                        return@runCatching false
                    }

                    val contentHash = trimmedContent.hashCode().toString()
                    val currentTime = System.currentTimeMillis()

                    val db = writableDatabase

                    // Check for duplicate content (ignore expired entries)
                    val duplicateQuery = """
                        SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                        WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? AND $COLUMN_EXPIRY_TIMESTAMP > ?
                    """.trimIndent()

                    db.rawQuery(duplicateQuery, arrayOf(contentHash, trimmedContent, currentTime.toString())).use { cursor ->
                        if (cursor.count > 0) {
                            Log.d("ClipboardDatabase", "Duplicate entry ignored: ${trimmedContent.take(20)}...")
                            return@runCatching false
                        }
                    }

                    // Insert new entry
                    val values = ContentValues().apply {
                        put(COLUMN_CONTENT, trimmedContent)
                        put(COLUMN_TIMESTAMP, currentTime)
                        put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                        put(COLUMN_IS_PINNED, 0)
                        put(COLUMN_CONTENT_HASH, contentHash)
                    }

                    val result = db.insert(TABLE_CLIPBOARD, null, values)

                    if (result != -1L) {
                        Log.d("ClipboardDatabase", "Added clipboard entry: ${trimmedContent.take(20)}... (id=$result)")
                        true
                    } else {
                        Log.w("ClipboardDatabase", "Failed to insert clipboard entry")
                        false
                    }
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error adding clipboard entry", exception)
                }
            }
        }

    /**
     * Get all active clipboard entries (non-expired).
     *
     * @return Result containing list of active clipboard entries
     */
    suspend fun getActiveClipboardEntries(): Result<List<String>> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val entries = mutableListOf<String>()
                    val currentTime = System.currentTimeMillis()

                    val query = """
                        SELECT $COLUMN_CONTENT FROM $TABLE_CLIPBOARD
                        WHERE $COLUMN_EXPIRY_TIMESTAMP > ? OR $COLUMN_IS_PINNED = 1
                        ORDER BY $COLUMN_IS_PINNED DESC, $COLUMN_TIMESTAMP DESC
                    """.trimIndent()

                    readableDatabase.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            entries.add(cursor.getString(0))
                        }
                    }

                    Log.d("ClipboardDatabase", "Retrieved ${entries.size} active clipboard entries")
                    entries
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error retrieving clipboard entries", exception)
                }
            }
        }

    /**
     * Remove a specific clipboard entry.
     *
     * @param content The content to remove
     * @return Result indicating success or failure
     */
    suspend fun removeClipboardEntry(content: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val trimmedContent = content.trim()
                    if (trimmedContent.isEmpty()) {
                        return@runCatching false
                    }

                    val deletedRows = writableDatabase.delete(
                        TABLE_CLIPBOARD,
                        "$COLUMN_CONTENT = ?",
                        arrayOf(trimmedContent)
                    )

                    Log.d("ClipboardDatabase", "Removed $deletedRows clipboard entries matching: ${trimmedContent.take(20)}...")
                    deletedRows > 0
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error removing clipboard entry", exception)
                }
            }
        }

    /**
     * Clear all clipboard entries (except pinned ones).
     *
     * @return Result containing number of entries cleared
     */
    suspend fun clearAllEntries(): Result<Int> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val deletedRows = writableDatabase.delete(
                        TABLE_CLIPBOARD,
                        "$COLUMN_IS_PINNED = 0",
                        null
                    )

                    Log.d("ClipboardDatabase", "Cleared $deletedRows clipboard entries (kept pinned entries)")
                    deletedRows
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error clearing clipboard entries", exception)
                }
            }
        }

    /**
     * Clean up expired entries to maintain database size.
     *
     * @return Result containing number of entries cleaned up
     */
    suspend fun cleanupExpiredEntries(): Result<Int> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val currentTime = System.currentTimeMillis()

                    val deletedRows = writableDatabase.delete(
                        TABLE_CLIPBOARD,
                        "$COLUMN_EXPIRY_TIMESTAMP <= ? AND $COLUMN_IS_PINNED = 0",
                        arrayOf(currentTime.toString())
                    )

                    if (deletedRows > 0) {
                        Log.d("ClipboardDatabase", "Cleaned up $deletedRows expired clipboard entries")
                    }

                    deletedRows
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error cleaning up expired entries", exception)
                }
            }
        }

    /**
     * Pin/unpin a clipboard entry to prevent expiration.
     *
     * @param content The content to pin/unpin
     * @param isPinned Whether to pin or unpin the entry
     * @return Result indicating success or failure
     */
    suspend fun setPinnedStatus(content: String, isPinned: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    val trimmedContent = content.trim()
                    if (trimmedContent.isEmpty()) {
                        return@runCatching false
                    }

                    val values = ContentValues().apply {
                        put(COLUMN_IS_PINNED, if (isPinned) 1 else 0)
                    }

                    val updatedRows = writableDatabase.update(
                        TABLE_CLIPBOARD,
                        values,
                        "$COLUMN_CONTENT = ?",
                        arrayOf(trimmedContent)
                    )

                    Log.d("ClipboardDatabase", "Updated pin status for $updatedRows entries: ${trimmedContent.take(20)}... (pinned=$isPinned)")
                    updatedRows > 0
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error updating pin status", exception)
                }
            }
        }

    /**
     * Get total number of entries in database.
     *
     * @return Result containing total entry count
     */
    suspend fun getTotalEntryCount(): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CLIPBOARD", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }.onFailure { exception ->
                Log.e("ClipboardDatabase", "Error getting total entry count", exception)
            }
        }

    /**
     * Get count of active (non-expired) entries.
     *
     * @return Result containing active entry count
     */
    suspend fun getActiveEntryCount(): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val currentTime = System.currentTimeMillis()
                val query = """
                    SELECT COUNT(*) FROM $TABLE_CLIPBOARD
                    WHERE $COLUMN_EXPIRY_TIMESTAMP > ? OR $COLUMN_IS_PINNED = 1
                """.trimIndent()

                readableDatabase.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }.onFailure { exception ->
                Log.e("ClipboardDatabase", "Error getting active entry count", exception)
            }
        }

    /**
     * Apply size limits by removing oldest entries (except pinned).
     *
     * @param maxSize Maximum number of entries to keep
     * @return Result containing number of entries removed
     */
    suspend fun applySizeLimit(maxSize: Int): Result<Int> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                runCatching {
                    if (maxSize <= 0) return@runCatching 0 // No limit

                    val currentTime = System.currentTimeMillis()
                    val db = writableDatabase

                    // Count current non-pinned active entries
                    val countQuery = """
                        SELECT COUNT(*) FROM $TABLE_CLIPBOARD
                        WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
                    """.trimIndent()

                    val currentCount = db.rawQuery(countQuery, arrayOf(currentTime.toString())).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }

                    if (currentCount <= maxSize) return@runCatching 0 // No cleanup needed

                    // Delete oldest entries to stay within limit
                    val entriesToDelete = currentCount - maxSize

                    val deleteQuery = """
                        DELETE FROM $TABLE_CLIPBOARD
                        WHERE $COLUMN_ID IN (
                            SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                            WHERE $COLUMN_IS_PINNED = 0 AND $COLUMN_EXPIRY_TIMESTAMP > ?
                            ORDER BY $COLUMN_TIMESTAMP ASC
                            LIMIT ?
                        )
                    """.trimIndent()

                    db.execSQL(deleteQuery, arrayOf(currentTime, entriesToDelete))

                    Log.d("ClipboardDatabase", "Applied size limit: removed $entriesToDelete oldest entries (limit=$maxSize)")
                    entriesToDelete
                }.onFailure { exception ->
                    Log.e("ClipboardDatabase", "Error applying size limit", exception)
                }
            }
        }

    /**
     * Get database statistics for monitoring and debugging.
     *
     * @return Map containing various database statistics
     */
    suspend fun getDatabaseStats(): Result<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val currentTime = System.currentTimeMillis()
                val db = readableDatabase

                val totalCount = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CLIPBOARD", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val activeCount = db.rawQuery(
                    "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP > ? OR $COLUMN_IS_PINNED = 1",
                    arrayOf(currentTime.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val pinnedCount = db.rawQuery(
                    "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_IS_PINNED = 1",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val expiredCount = totalCount - activeCount

                mapOf(
                    "total_entries" to totalCount,
                    "active_entries" to activeCount,
                    "expired_entries" to expiredCount,
                    "pinned_entries" to pinnedCount,
                    "database_version" to DATABASE_VERSION,
                    "last_cleanup" to currentTime
                )
            }.onFailure { exception ->
                Log.e("ClipboardDatabase", "Error getting database stats", exception)
            }
        }
}