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

    // ─── Schema creation (fresh install gets v4 directly) ───

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_CLIPBOARD_V4.trimIndent())
        db.execSQL("CREATE INDEX idx_content_hash ON $TABLE_CLIPBOARD ($COLUMN_CONTENT_HASH)")
        db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CLIPBOARD ($COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_expiry ON $TABLE_CLIPBOARD ($COLUMN_EXPIRY_TIMESTAMP)")
        db.execSQL(CREATE_TABLE_PINNED_V4.trimIndent())
        db.execSQL(CREATE_INDEX_PINNED_HASH)
        db.execSQL(CREATE_INDEX_PINNED_POS)
        db.execSQL(CREATE_TABLE_TODO_V4.trimIndent())
        db.execSQL(CREATE_INDEX_TODO_HASH)
        db.execSQL(CREATE_INDEX_TODO_POS)
        db.execSQL(CREATE_INDEX_TODO_STATUS)
    }

    // ─── Migration chain ───

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_CLIPBOARD ADD COLUMN is_todo INTEGER DEFAULT 0")
                Log.d(TAG, "Database upgraded v1→v2: added is_todo column")
            } catch (e: Exception) {
                Log.e(TAG, "Error upgrading v1→v2: ${e.message}")
            }
        }
        if (oldVersion < 3) {
            migrateV2toV3(db)
        }
        if (oldVersion < 4) {
            migrateV3toV4(db)
        }
    }

    /**
     * Migrate from v2 (flag-based) to v3 (independent tables).
     *
     * Uses CREATE-COPY-DROP-RENAME pattern because Android < API 34 (SQLite < 3.35.0)
     * lacks ALTER TABLE DROP COLUMN.
     *
     * COPY semantics: pinned/todo entries are copied to their independent tables AND
     * remain in clipboard_entries (history). Entries in history will expire naturally.
     *
     * Position is auto-computed via correlated subquery with id tie-breaker to prevent
     * duplicate positions when timestamps match.
     */
    private fun migrateV2toV3(db: SQLiteDatabase) {
        try {
            // 1. Create pinned_entries, copy flagged rows with auto-computed positions
            db.execSQL(CREATE_TABLE_PINNED.trimIndent())
            db.execSQL("""
                INSERT INTO $TABLE_PINNED ($COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP, $COLUMN_PINNED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_TAGS)
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_TIMESTAMP, $COLUMN_TIMESTAMP,
                    (SELECT COUNT(*) FROM $TABLE_CLIPBOARD c2
                     WHERE c2.is_pinned = 1
                     AND (c2.$COLUMN_TIMESTAMP < c1.$COLUMN_TIMESTAMP
                          OR (c2.$COLUMN_TIMESTAMP = c1.$COLUMN_TIMESTAMP AND c2.$COLUMN_ID < c1.$COLUMN_ID))
                    ) + 1.0,
                    '[]'
                FROM $TABLE_CLIPBOARD c1
                WHERE c1.is_pinned = 1
            """.trimIndent())

            // 2. Create todo_entries, copy flagged rows with auto-computed positions
            db.execSQL(CREATE_TABLE_TODO.trimIndent())
            db.execSQL("""
                INSERT INTO $TABLE_TODO ($COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP, $COLUMN_ADDED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_STATUS, $COLUMN_TAGS)
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_TIMESTAMP, $COLUMN_TIMESTAMP,
                    (SELECT COUNT(*) FROM $TABLE_CLIPBOARD c2
                     WHERE c2.is_todo = 1
                     AND (c2.$COLUMN_TIMESTAMP < c1.$COLUMN_TIMESTAMP
                          OR (c2.$COLUMN_TIMESTAMP = c1.$COLUMN_TIMESTAMP AND c2.$COLUMN_ID < c1.$COLUMN_ID))
                    ) + 1.0,
                    '${TodoEntry.STATUS_ACTIVE}',
                    '[]'
                FROM $TABLE_CLIPBOARD c1
                WHERE c1.is_todo = 1
            """.trimIndent())

            // 3. Rebuild clipboard_entries without is_pinned/is_todo columns
            //    COPY semantics: keep ALL rows (pinned/todo entries stay in history)
            db.execSQL("""
                CREATE TABLE clipboard_entries_new (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_CONTENT TEXT NOT NULL,
                    $COLUMN_TIMESTAMP INTEGER NOT NULL,
                    $COLUMN_EXPIRY_TIMESTAMP INTEGER NOT NULL,
                    $COLUMN_CONTENT_HASH TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO clipboard_entries_new ($COLUMN_ID, $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP, $COLUMN_CONTENT_HASH)
                SELECT $COLUMN_ID, $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP, $COLUMN_CONTENT_HASH
                FROM $TABLE_CLIPBOARD
            """.trimIndent())
            db.execSQL("DROP TABLE $TABLE_CLIPBOARD")
            db.execSQL("ALTER TABLE clipboard_entries_new RENAME TO $TABLE_CLIPBOARD")

            // 4. Recreate all indexes
            db.execSQL("CREATE INDEX idx_content_hash ON $TABLE_CLIPBOARD ($COLUMN_CONTENT_HASH)")
            db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CLIPBOARD ($COLUMN_TIMESTAMP DESC)")
            db.execSQL("CREATE INDEX idx_expiry ON $TABLE_CLIPBOARD ($COLUMN_EXPIRY_TIMESTAMP)")
            db.execSQL(CREATE_INDEX_PINNED_HASH)
            db.execSQL(CREATE_INDEX_PINNED_POS)
            db.execSQL(CREATE_INDEX_TODO_HASH)
            db.execSQL(CREATE_INDEX_TODO_POS)
            db.execSQL(CREATE_INDEX_TODO_STATUS)

            Log.d(TAG, "Database upgraded v2→v3: independent pinned/todo tables created")
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading v2→v3: ${e.message}", e)
            throw e  // Re-throw so SQLiteOpenHelper rolls back the transaction
        }
    }

    /**
     * Migrate from v3 to v4: add media columns to all three tables.
     *
     * Uses ALTER TABLE ADD COLUMN which is non-destructive and O(1) — only schema
     * metadata changes, no data is moved. Existing rows automatically get DEFAULT values:
     * mime_type='text/plain', thumbnail_blob=NULL, media_path=NULL.
     */
    private fun migrateV3toV4(db: SQLiteDatabase) {
        try {
            // Add media columns to clipboard_entries
            db.execSQL("ALTER TABLE $TABLE_CLIPBOARD ADD COLUMN $COLUMN_MIME_TYPE TEXT DEFAULT '${ClipboardEntry.MIME_TEXT_PLAIN}'")
            db.execSQL("ALTER TABLE $TABLE_CLIPBOARD ADD COLUMN $COLUMN_THUMBNAIL_BLOB BLOB")
            db.execSQL("ALTER TABLE $TABLE_CLIPBOARD ADD COLUMN $COLUMN_MEDIA_PATH TEXT")

            // Add media columns to pinned_entries
            db.execSQL("ALTER TABLE $TABLE_PINNED ADD COLUMN $COLUMN_MIME_TYPE TEXT DEFAULT '${ClipboardEntry.MIME_TEXT_PLAIN}'")
            db.execSQL("ALTER TABLE $TABLE_PINNED ADD COLUMN $COLUMN_THUMBNAIL_BLOB BLOB")
            db.execSQL("ALTER TABLE $TABLE_PINNED ADD COLUMN $COLUMN_MEDIA_PATH TEXT")

            // Add media columns to todo_entries
            db.execSQL("ALTER TABLE $TABLE_TODO ADD COLUMN $COLUMN_MIME_TYPE TEXT DEFAULT '${ClipboardEntry.MIME_TEXT_PLAIN}'")
            db.execSQL("ALTER TABLE $TABLE_TODO ADD COLUMN $COLUMN_THUMBNAIL_BLOB BLOB")
            db.execSQL("ALTER TABLE $TABLE_TODO ADD COLUMN $COLUMN_MEDIA_PATH TEXT")

            Log.d(TAG, "Database upgraded v3→v4: media columns added to all tables")
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading v3→v4: ${e.message}", e)
            throw e  // Re-throw so SQLiteOpenHelper rolls back the transaction
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // History CRUD (clipboard_entries — regular history items only)
    // ═══════════════════════════════════════════════════════════════════

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
                put(COLUMN_TIMESTAMP, currentTime)
                put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
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

    /**
     * Add a media clipboard entry (v4). Uses SHA-256 content hash for dedup instead
     * of String.hashCode(). Stores MIME type, thumbnail BLOB, and media file path.
     *
     * @param content Display name (filename or URI segment) — NOT the media bytes
     * @param contentHash SHA-256 hex digest of the media file
     */
    fun addMediaClipboardEntry(
        content: String,
        expiryTimestamp: Long,
        mimeType: String,
        thumbnailBlob: ByteArray?,
        mediaPath: String,
        contentHash: String
    ): Boolean {
        if (content.isBlank()) return false
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()

            // Dedup: check if this exact media file already exists (by content hash)
            val duplicateQuery = """
                SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_EXPIRY_TIMESTAMP > ?
            """.trimIndent()
            db.rawQuery(duplicateQuery, arrayOf(contentHash, currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    // Move duplicate to top by updating timestamp
                    val existingId = cursor.getLong(0)
                    val updateValues = ContentValues().apply {
                        put(COLUMN_TIMESTAMP, currentTime)
                        put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                    }
                    db.update(TABLE_CLIPBOARD, updateValues, "$COLUMN_ID = ?", arrayOf(existingId.toString()))
                    Log.d(TAG, "Duplicate media moved to top: $content (id=$existingId)")
                    return true
                }
            }

            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, currentTime)
                put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_MIME_TYPE, mimeType)
                if (thumbnailBlob != null) put(COLUMN_THUMBNAIL_BLOB, thumbnailBlob)
                put(COLUMN_MEDIA_PATH, mediaPath)
            }
            val result = db.insert(TABLE_CLIPBOARD, null, values)
            Log.d(TAG, "Added media entry: $content ($mimeType, id=$result)")
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error adding media clipboard entry: ${e.message}")
            false
        }
    }

    /** Get non-expired history entries (v3: no pinned/todo filters — those are separate tables) */
    fun getActiveClipboardEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val currentTime = System.currentTimeMillis()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP FROM $TABLE_CLIPBOARD
            WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
            ORDER BY $COLUMN_TIMESTAMP DESC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
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

    /** Clear all history entries. Pinned/todo entries live in separate tables and are unaffected. */
    fun clearAllEntries(): Result<Int> {
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_CLIPBOARD, null, null)
            Log.d(TAG, "Cleared $deletedRows history entries (pinned/todo tables unaffected)")
            Result.success(deletedRows)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing clipboard entries: ${e.message}")
            Result.failure(e)
        }
    }

    /** Remove expired history entries. Pinned/todo are in separate tables and never expire. */
    fun cleanupExpiredEntries(): Int {
        val currentTime = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(
                TABLE_CLIPBOARD,
                "$COLUMN_EXPIRY_TIMESTAMP <= ?",
                arrayOf(currentTime.toString())
            )
            if (deletedRows > 0) Log.d(TAG, "Cleaned up $deletedRows expired history entries")
            deletedRows
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired entries: ${e.message}")
            0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pinned entries CRUD (pinned_entries — independent table)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Pin content by copying it to pinned_entries. If already pinned, returns false.
     * The original history entry (if any) is unaffected — COPY semantics.
     *
     * @param content The text content to pin
     * @param createdTimestamp Original clipboard copy time (defaults to now)
     */
    fun pinEntry(content: String?, createdTimestamp: Long = System.currentTimeMillis()): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            val db = writableDatabase
            // Check if already pinned
            val alreadyPinned = db.rawQuery(
                "SELECT 1 FROM $TABLE_PINNED WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentHash, trimmedContent)
            ).use { it.moveToFirst() }
            if (alreadyPinned) {
                Log.d(TAG, "Already pinned: ${trimmedContent.take(20)}...")
                return false
            }
            val position = getMaxPinnedPosition(db) + 1.0
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTimestamp)
                put(COLUMN_PINNED_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_POSITION, position)
                put(COLUMN_TAGS, "[]")
            }
            val result = db.insert(TABLE_PINNED, null, values)
            Log.d(TAG, "Pinned entry: ${trimmedContent.take(20)}... (id=$result, pos=$position)")
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error pinning entry: ${e.message}")
            false
        }
    }

    /** Remove content from pinned_entries. History copy (if any) is unaffected. */
    fun unpinEntry(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_PINNED, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Unpinned $deletedRows entries: ${trimmedContent.take(20)}...")
            deletedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error unpinning entry: ${e.message}")
            false
        }
    }

    /** Check if content exists in pinned_entries */
    fun isPinned(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            readableDatabase.rawQuery(
                "SELECT 1 FROM $TABLE_PINNED WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentHash, trimmedContent)
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pinned status: ${e.message}")
            false
        }
    }

    /**
     * Get pinned entries as ClipboardEntry list (backward-compatible for existing views).
     * Ordered by position ASC (user-defined sort order).
     */
    fun getPinnedEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_PINNED_TIMESTAMP FROM $TABLE_PINNED
            ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
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

    /** Get pinned entries with full v3 fields (position, tags, timestamps) */
    fun getPinnedEntriesFull(): List<PinnedEntry> {
        val entries = mutableListOf<PinnedEntry>()
        val query = """
            SELECT $COLUMN_ID, $COLUMN_CONTENT, $COLUMN_CONTENT_HASH,
                   $COLUMN_CREATED_TIMESTAMP, $COLUMN_PINNED_TIMESTAMP,
                   $COLUMN_POSITION, $COLUMN_TAGS
            FROM $TABLE_PINNED ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(PinnedEntry(
                            id = cursor.getLong(0),
                            content = cursor.getString(1),
                            contentHash = cursor.getString(2),
                            createdTimestamp = cursor.getLong(3),
                            pinnedTimestamp = cursor.getLong(4),
                            position = cursor.getDouble(5),
                            tags = PinnedEntry.tagsFromJson(cursor.getString(6))
                        ))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving full pinned entries: ${e.message}")
        }
        return entries
    }

    /** Get the maximum position value in pinned_entries (0.0 if empty) */
    private fun getMaxPinnedPosition(db: SQLiteDatabase): Double {
        return db.rawQuery("SELECT MAX($COLUMN_POSITION) FROM $TABLE_PINNED", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else 0.0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Todo entries CRUD (todo_entries — independent table)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Add content to todo_entries. If already a todo, returns false.
     * The original history entry (if any) is unaffected — COPY semantics.
     *
     * @param content The text content to add as todo
     * @param createdTimestamp Original clipboard copy time (defaults to now)
     */
    fun addTodoEntry(content: String?, createdTimestamp: Long = System.currentTimeMillis()): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            val db = writableDatabase
            // Check if already a todo
            val alreadyTodo = db.rawQuery(
                "SELECT 1 FROM $TABLE_TODO WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentHash, trimmedContent)
            ).use { it.moveToFirst() }
            if (alreadyTodo) {
                Log.d(TAG, "Already a todo: ${trimmedContent.take(20)}...")
                return false
            }
            val position = getMaxTodoPosition(db) + 1.0
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTimestamp)
                put(COLUMN_ADDED_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_POSITION, position)
                put(COLUMN_STATUS, TodoEntry.STATUS_ACTIVE)
                put(COLUMN_TAGS, "[]")
            }
            val result = db.insert(TABLE_TODO, null, values)
            Log.d(TAG, "Added todo: ${trimmedContent.take(20)}... (id=$result, pos=$position)")
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error adding todo entry: ${e.message}")
            false
        }
    }

    /** Remove content from todo_entries. History copy (if any) is unaffected. */
    fun removeTodoEntry(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val deletedRows = db.delete(TABLE_TODO, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Removed $deletedRows todo entries: ${trimmedContent.take(20)}...")
            deletedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error removing todo entry: ${e.message}")
            false
        }
    }

    /** Update the status of a todo entry (active → planned → completed or any transition) */
    fun setTodoEntryStatus(content: String?, status: String): Boolean {
        if (content.isNullOrBlank()) return false
        if (status !in TodoEntry.VALID_STATUSES) {
            Log.w(TAG, "Invalid todo status: $status")
            return false
        }
        val trimmedContent = content.trim()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply { put(COLUMN_STATUS, status) }
            val updatedRows = db.update(TABLE_TODO, values, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            Log.d(TAG, "Updated todo status to '$status' for $updatedRows entries: ${trimmedContent.take(20)}...")
            updatedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating todo status: ${e.message}")
            false
        }
    }

    /** Check if content exists in todo_entries */
    fun isTodo(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            readableDatabase.rawQuery(
                "SELECT 1 FROM $TABLE_TODO WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentHash, trimmedContent)
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking todo status: ${e.message}")
            false
        }
    }

    /**
     * Get todo entries as ClipboardEntry list (backward-compatible for existing views).
     * Ordered by position ASC (user-defined sort order).
     */
    fun getTodoEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_ADDED_TIMESTAMP FROM $TABLE_TODO
            ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
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

    /** Get todo entries with full v3 fields (position, tags, status, timestamps) */
    fun getTodoEntriesFull(): List<TodoEntry> {
        val entries = mutableListOf<TodoEntry>()
        val query = """
            SELECT $COLUMN_ID, $COLUMN_CONTENT, $COLUMN_CONTENT_HASH,
                   $COLUMN_CREATED_TIMESTAMP, $COLUMN_ADDED_TIMESTAMP,
                   $COLUMN_POSITION, $COLUMN_STATUS, $COLUMN_TAGS
            FROM $TABLE_TODO ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(TodoEntry(
                            id = cursor.getLong(0),
                            content = cursor.getString(1),
                            contentHash = cursor.getString(2),
                            createdTimestamp = cursor.getLong(3),
                            addedTimestamp = cursor.getLong(4),
                            position = cursor.getDouble(5),
                            status = cursor.getString(6) ?: TodoEntry.STATUS_ACTIVE,
                            tags = TodoEntry.tagsFromJson(cursor.getString(7))
                        ))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving full todo entries: ${e.message}")
        }
        return entries
    }

    /** Get the maximum position value in todo_entries (0.0 if empty) */
    private fun getMaxTodoPosition(db: SQLiteDatabase): Double {
        return db.rawQuery("SELECT MAX($COLUMN_POSITION) FROM $TABLE_TODO", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else 0.0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Statistics
    // ═══════════════════════════════════════════════════════════════════

    fun getTotalEntryCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_CLIPBOARD", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Count of non-expired history entries */
    fun getActiveEntryCount(): Int {
        val currentTime = System.currentTimeMillis()
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP > ?",
            arrayOf(currentTime.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    data class StorageStats(
        val historyEntries: Int,
        val pinnedEntries: Int,
        val todoEntries: Int,
        val historySizeBytes: Long,
        val pinnedSizeBytes: Long,
        val todoSizeBytes: Long
    ) {
        val totalEntries: Int get() = historyEntries + pinnedEntries + todoEntries
        val totalSizeBytes: Long get() = historySizeBytes + pinnedSizeBytes + todoSizeBytes
        // Backward-compat aliases for existing callers
        val activeEntries: Int get() = historyEntries
        val activeSizeBytes: Long get() = historySizeBytes
    }

    /**
     * Get database statistics as a Map (for ClipboardSettingsActivity compatibility)
     */
    fun getDatabaseStats(): Result<Map<String, Any>> {
        return try {
            val stats = getStorageStats()
            val totalHistory = getTotalEntryCount()
            Result.success(mapOf(
                "total_entries" to stats.totalEntries,
                "active_entries" to stats.historyEntries,
                "pinned_entries" to stats.pinnedEntries,
                "todo_entries" to stats.todoEntries,
                "expired_entries" to (totalHistory - stats.historyEntries)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database stats", e)
            Result.failure(e)
        }
    }

    /**
     * Get storage statistics across all 3 tables using UNION ALL for a single JNI crossing.
     * Uses LENGTH(CAST(content AS BLOB)) to measure UTF-8 byte sizes server-side.
     */
    fun getStorageStats(): StorageStats {
        val currentTime = System.currentTimeMillis()
        var historyEntries = 0; var pinnedEntries = 0; var todoEntries = 0
        var historySizeBytes = 0L; var pinnedSizeBytes = 0L; var todoSizeBytes = 0L
        val query = """
            SELECT 'history', COUNT(*), COALESCE(SUM(LENGTH(CAST($COLUMN_CONTENT AS BLOB))), 0)
            FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
            UNION ALL
            SELECT 'pinned', COUNT(*), COALESCE(SUM(LENGTH(CAST($COLUMN_CONTENT AS BLOB))), 0)
            FROM $TABLE_PINNED
            UNION ALL
            SELECT 'todo', COUNT(*), COALESCE(SUM(LENGTH(CAST($COLUMN_CONTENT AS BLOB))), 0)
            FROM $TABLE_TODO
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)) {
                        "history" -> { historyEntries = cursor.getInt(1); historySizeBytes = cursor.getLong(2) }
                        "pinned" -> { pinnedEntries = cursor.getInt(1); pinnedSizeBytes = cursor.getLong(2) }
                        "todo" -> { todoEntries = cursor.getInt(1); todoSizeBytes = cursor.getLong(2) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage stats: ${e.message}")
        }
        return StorageStats(historyEntries, pinnedEntries, todoEntries, historySizeBytes, pinnedSizeBytes, todoSizeBytes)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Size limits (applied to history only — pinned/todo are user-curated)
    // ═══════════════════════════════════════════════════════════════════

    /** Enforce count-based limit on history entries (pinned/todo exempt) */
    fun applySizeLimit(maxSize: Int): Int {
        if (maxSize <= 0) return 0
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            val currentCount = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP > ?",
                arrayOf(currentTime.toString())
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (currentCount <= maxSize) return 0
            val entriesToDelete = currentCount - maxSize
            db.execSQL("""
                DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (
                    SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD
                    WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
                    ORDER BY $COLUMN_TIMESTAMP ASC LIMIT ?
                )
            """.trimIndent(), arrayOf(currentTime, entriesToDelete))
            Log.d(TAG, "Applied size limit: removed $entriesToDelete oldest history entries (limit=$maxSize)")
            entriesToDelete
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit: ${e.message}")
            0
        }
    }

    /**
     * Remove oldest history entries until total history size is under [maxSizeMB].
     * Pinned/todo entries are exempt (user-curated, not subject to size limits).
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
            db.rawQuery("""
                SELECT $COLUMN_ID, LENGTH(CAST($COLUMN_CONTENT AS BLOB))
                FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
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
            for (chunk in idsToDelete.chunked(500)) {
                db.execSQL("DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (${chunk.joinToString(",")})")
            }
            Log.d(TAG, "Applied size limit (bytes): removed ${idsToDelete.size} oldest history entries")
            idsToDelete.size
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit (bytes): ${e.message}")
            0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tags CRUD (foundation — UI deferred to separate PR)
    // ═══════════════════════════════════════════════════════════════════

    /** Set tags for a pinned entry by content */
    fun setPinnedEntryTags(content: String, tags: List<String>): Boolean {
        val trimmedContent = content.trim()
        return try {
            val arr = JSONArray()
            tags.forEach { arr.put(it) }
            val values = ContentValues().apply { put(COLUMN_TAGS, arr.toString()) }
            val updated = writableDatabase.update(TABLE_PINNED, values, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pinned tags: ${e.message}")
            false
        }
    }

    /** Set tags for a todo entry by content */
    fun setTodoEntryTags(content: String, tags: List<String>): Boolean {
        val trimmedContent = content.trim()
        return try {
            val arr = JSONArray()
            tags.forEach { arr.put(it) }
            val values = ContentValues().apply { put(COLUMN_TAGS, arr.toString()) }
            val updated = writableDatabase.update(TABLE_TODO, values, "$COLUMN_CONTENT = ?", arrayOf(trimmedContent))
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error setting todo tags: ${e.message}")
            false
        }
    }

    /** Get all unique tags used across pinned entries */
    fun getAllPinnedTags(): Set<String> {
        val tags = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery("SELECT $COLUMN_TAGS FROM $TABLE_PINNED", null).use { cursor ->
                while (cursor.moveToNext()) {
                    PinnedEntry.tagsFromJson(cursor.getString(0)).forEach { tags.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pinned tags: ${e.message}")
        }
        return tags
    }

    /** Get all unique tags used across todo entries */
    fun getAllTodoTags(): Set<String> {
        val tags = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery("SELECT $COLUMN_TAGS FROM $TABLE_TODO", null).use { cursor ->
                while (cursor.moveToNext()) {
                    TodoEntry.tagsFromJson(cursor.getString(0)).forEach { tags.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting todo tags: ${e.message}")
        }
        return tags
    }

    // ═══════════════════════════════════════════════════════════════════
    // Export / Import (v3 format with v2 backward compatibility)
    // ═══════════════════════════════════════════════════════════════════

    fun exportToJSON(): JSONObject? {
        return try {
            val activeArray = JSONArray()
            val pinnedArray = JSONArray()
            val todoArray = JSONArray()
            var activeCount = 0; var pinnedCount = 0; var todoCount = 0

            // Export history entries
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP
                FROM $TABLE_CLIPBOARD ORDER BY $COLUMN_TIMESTAMP DESC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    activeArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("timestamp", cursor.getLong(1))
                        put("expiry_timestamp", cursor.getLong(2))
                    })
                    activeCount++
                }
            }

            // Export pinned entries with v3 fields
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP,
                       $COLUMN_PINNED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_TAGS
                FROM $TABLE_PINNED ORDER BY $COLUMN_POSITION ASC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    pinnedArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("content_hash", cursor.getString(1))
                        put("created_timestamp", cursor.getLong(2))
                        put("pinned_timestamp", cursor.getLong(3))
                        put("timestamp", cursor.getLong(3))  // v2 compat key
                        put("position", cursor.getDouble(4))
                        put("tags", cursor.getString(5) ?: "[]")
                    })
                    pinnedCount++
                }
            }

            // Export todo entries with v3 fields
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP,
                       $COLUMN_ADDED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_STATUS, $COLUMN_TAGS
                FROM $TABLE_TODO ORDER BY $COLUMN_POSITION ASC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    todoArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("content_hash", cursor.getString(1))
                        put("created_timestamp", cursor.getLong(2))
                        put("added_timestamp", cursor.getLong(3))
                        put("timestamp", cursor.getLong(3))  // v2 compat key
                        put("position", cursor.getDouble(4))
                        put("status", cursor.getString(5) ?: TodoEntry.STATUS_ACTIVE)
                        put("tags", cursor.getString(6) ?: "[]")
                    })
                    todoCount++
                }
            }

            JSONObject().apply {
                put("active_entries", activeArray)
                put("pinned_entries", pinnedArray)
                put("todo_entries", todoArray)
                put("export_version", 3)
                put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("total_active", activeCount)
                put("total_pinned", pinnedCount)
                put("total_todo", todoCount)
            }.also {
                Log.d(TAG, "Exported $activeCount active, $pinnedCount pinned, $todoCount todo entries (v3)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting clipboard data: ${e.message}")
            null
        }
    }

    /**
     * Import clipboard entries from a JSON export. Handles both v2 and v3 formats.
     * Wrapped in a single transaction for atomicity and performance.
     *
     * @return IntArray of [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped]
     */
    fun importFromJSON(importData: JSONObject): IntArray {
        var activeAdded = 0; var pinnedAdded = 0; var todoAdded = 0; var duplicatesSkipped = 0
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val exportVersion = importData.optInt("export_version", 1)
                val freshExpiry = ClipboardHistoryService.getHistoryTtlMs().let { ttl ->
                    if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttl
                }

                // Import active/history entries (same format in v2 and v3)
                if (importData.has("active_entries")) {
                    val result = importHistoryEntries(db, importData.getJSONArray("active_entries"), freshExpiry)
                    activeAdded = result.first
                    duplicatesSkipped += result.second
                }

                // Import pinned entries
                if (importData.has("pinned_entries")) {
                    val result = importPinnedEntries(db, importData.getJSONArray("pinned_entries"), exportVersion, freshExpiry)
                    pinnedAdded = result.first
                    duplicatesSkipped += result.second
                }

                // Import todo entries
                if (importData.has("todo_entries")) {
                    val result = importTodoEntries(db, importData.getJSONArray("todo_entries"), exportVersion, freshExpiry)
                    todoAdded = result.first
                    duplicatesSkipped += result.second
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.d(TAG, "Import complete: $activeAdded active, $pinnedAdded pinned, $todoAdded todo, $duplicatesSkipped dupes skipped")
        } catch (e: Exception) {
            Log.e(TAG, "Error importing clipboard data: ${e.message}")
        }
        return intArrayOf(activeAdded, pinnedAdded, todoAdded, duplicatesSkipped)
    }

    /** Import history entries into clipboard_entries */
    private fun importHistoryEntries(db: SQLiteDatabase, entries: JSONArray, freshExpiry: Long): Pair<Int, Int> {
        var added = 0; var skipped = 0
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val contentHash = content.hashCode().toString()
            val isDuplicate = db.rawQuery(
                "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                arrayOf(contentHash, content)
            ).use { it.moveToFirst() }
            if (isDuplicate) { skipped++; continue }
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
                put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)
                put(COLUMN_CONTENT_HASH, contentHash)
            }
            if (db.insert(TABLE_CLIPBOARD, null, values) != -1L) added++
        }
        return Pair(added, skipped)
    }

    /**
     * Import pinned entries into pinned_entries table.
     * v2 format: uses "timestamp" for both created and pinned timestamps.
     * v3 format: has separate created_timestamp, pinned_timestamp, position, tags.
     * Also inserts into history (COPY semantics) for v2 imports.
     */
    private fun importPinnedEntries(
        db: SQLiteDatabase, entries: JSONArray, exportVersion: Int, freshExpiry: Long
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        var maxPosition = getMaxPinnedPosition(db)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val contentHash = content.hashCode().toString()
            // Duplicate check in pinned_entries
            val isDuplicate = db.rawQuery(
                "SELECT $COLUMN_ID FROM $TABLE_PINNED WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                arrayOf(contentHash, content)
            ).use { it.moveToFirst() }
            if (isDuplicate) { skipped++; continue }

            val createdTs = entry.optLong("created_timestamp", entry.getLong("timestamp"))
            val pinnedTs = entry.optLong("pinned_timestamp", entry.getLong("timestamp"))
            val position = if (exportVersion >= 3) entry.getDouble("position") else { maxPosition += 1.0; maxPosition }
            val tags = if (exportVersion >= 3) entry.optString("tags", "[]") else "[]"

            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTs)
                put(COLUMN_PINNED_TIMESTAMP, pinnedTs)
                put(COLUMN_POSITION, position)
                put(COLUMN_TAGS, tags)
            }
            if (db.insert(TABLE_PINNED, null, values) != -1L) added++

            // COPY semantics: also insert into history if not already there
            if (exportVersion < 3) {
                val inHistory = db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                    arrayOf(contentHash, content)
                ).use { it.moveToFirst() }
                if (!inHistory) {
                    val historyValues = ContentValues().apply {
                        put(COLUMN_CONTENT, content)
                        put(COLUMN_TIMESTAMP, createdTs)
                        put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)
                        put(COLUMN_CONTENT_HASH, contentHash)
                    }
                    db.insert(TABLE_CLIPBOARD, null, historyValues)
                }
            }
        }
        return Pair(added, skipped)
    }

    /**
     * Import todo entries into todo_entries table.
     * v2 format: uses "timestamp" for both created and added timestamps.
     * v3 format: has separate created_timestamp, added_timestamp, position, status, tags.
     * Also inserts into history (COPY semantics) for v2 imports.
     */
    private fun importTodoEntries(
        db: SQLiteDatabase, entries: JSONArray, exportVersion: Int, freshExpiry: Long
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        var maxPosition = getMaxTodoPosition(db)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val contentHash = content.hashCode().toString()
            // Duplicate check in todo_entries
            val isDuplicate = db.rawQuery(
                "SELECT $COLUMN_ID FROM $TABLE_TODO WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                arrayOf(contentHash, content)
            ).use { it.moveToFirst() }
            if (isDuplicate) { skipped++; continue }

            val createdTs = entry.optLong("created_timestamp", entry.getLong("timestamp"))
            val addedTs = entry.optLong("added_timestamp", entry.getLong("timestamp"))
            val position = if (exportVersion >= 3) entry.getDouble("position") else { maxPosition += 1.0; maxPosition }
            val status = if (exportVersion >= 3) entry.optString("status", TodoEntry.STATUS_ACTIVE) else TodoEntry.STATUS_ACTIVE
            val tags = if (exportVersion >= 3) entry.optString("tags", "[]") else "[]"

            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTs)
                put(COLUMN_ADDED_TIMESTAMP, addedTs)
                put(COLUMN_POSITION, position)
                put(COLUMN_STATUS, status)
                put(COLUMN_TAGS, tags)
            }
            if (db.insert(TABLE_TODO, null, values) != -1L) added++

            // COPY semantics: also insert into history if not already there
            if (exportVersion < 3) {
                val inHistory = db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                    arrayOf(contentHash, content)
                ).use { it.moveToFirst() }
                if (!inHistory) {
                    val historyValues = ContentValues().apply {
                        put(COLUMN_CONTENT, content)
                        put(COLUMN_TIMESTAMP, createdTs)
                        put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)
                        put(COLUMN_CONTENT_HASH, contentHash)
                    }
                    db.insert(TABLE_CLIPBOARD, null, historyValues)
                }
            }
        }
        return Pair(added, skipped)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Companion object — constants and singleton
    // ═══════════════════════════════════════════════════════════════════

    companion object {
        private const val DATABASE_NAME = "clipboard_history.db"
        private const val DATABASE_VERSION = 4  // v4: Media clipboard (mime_type, thumbnail, media_path)
        private const val TABLE_CLIPBOARD = "clipboard_entries"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val COLUMN_CONTENT_HASH = "content_hash"
        private const val TAG = "ClipboardDatabase"

        // ─── v3 schema: independent pinned/todo tables ───
        private const val TABLE_PINNED = "pinned_entries"
        private const val TABLE_TODO = "todo_entries"
        private const val COLUMN_CREATED_TIMESTAMP = "created_timestamp"
        private const val COLUMN_PINNED_TIMESTAMP = "pinned_timestamp"
        private const val COLUMN_ADDED_TIMESTAMP = "added_timestamp"
        private const val COLUMN_POSITION = "position"
        private const val COLUMN_STATUS = "status"
        private const val COLUMN_TAGS = "tags"

        // ─── v4 schema: media clipboard columns ───
        private const val COLUMN_MIME_TYPE = "mime_type"
        private const val COLUMN_THUMBNAIL_BLOB = "thumbnail_blob"
        private const val COLUMN_MEDIA_PATH = "media_path"

        // DDL for v4 clipboard_entries (with media columns)
        private const val CREATE_TABLE_CLIPBOARD_V4 = """
            CREATE TABLE $TABLE_CLIPBOARD (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_EXPIRY_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT DEFAULT 'text/plain',
                $COLUMN_THUMBNAIL_BLOB BLOB,
                $COLUMN_MEDIA_PATH TEXT
            )
        """

        // v3 DDL kept for migration chain (v2→v3 creates these, then v3→v4 alters them)
        private const val CREATE_TABLE_PINNED = """
            CREATE TABLE $TABLE_PINNED (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_CREATED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_POSITION REAL NOT NULL,
                $COLUMN_TAGS TEXT DEFAULT '[]'
            )
        """

        private const val CREATE_TABLE_TODO = """
            CREATE TABLE $TABLE_TODO (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_CREATED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_ADDED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_POSITION REAL NOT NULL,
                $COLUMN_STATUS TEXT DEFAULT 'active',
                $COLUMN_TAGS TEXT DEFAULT '[]'
            )
        """

        // v4 DDL for fresh installs (includes media columns)
        private const val CREATE_TABLE_PINNED_V4 = """
            CREATE TABLE $TABLE_PINNED (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_CREATED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_POSITION REAL NOT NULL,
                $COLUMN_TAGS TEXT DEFAULT '[]',
                $COLUMN_MIME_TYPE TEXT DEFAULT 'text/plain',
                $COLUMN_THUMBNAIL_BLOB BLOB,
                $COLUMN_MEDIA_PATH TEXT
            )
        """

        private const val CREATE_TABLE_TODO_V4 = """
            CREATE TABLE $TABLE_TODO (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_CREATED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_ADDED_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_POSITION REAL NOT NULL,
                $COLUMN_STATUS TEXT DEFAULT 'active',
                $COLUMN_TAGS TEXT DEFAULT '[]',
                $COLUMN_MIME_TYPE TEXT DEFAULT 'text/plain',
                $COLUMN_THUMBNAIL_BLOB BLOB,
                $COLUMN_MEDIA_PATH TEXT
            )
        """

        // Indexes for v3/v4 tables
        private const val CREATE_INDEX_PINNED_HASH = "CREATE INDEX idx_pinned_hash ON $TABLE_PINNED ($COLUMN_CONTENT_HASH)"
        private const val CREATE_INDEX_PINNED_POS = "CREATE INDEX idx_pinned_position ON $TABLE_PINNED ($COLUMN_POSITION)"
        private const val CREATE_INDEX_TODO_HASH = "CREATE INDEX idx_todo_hash ON $TABLE_TODO ($COLUMN_CONTENT_HASH)"
        private const val CREATE_INDEX_TODO_POS = "CREATE INDEX idx_todo_position ON $TABLE_TODO ($COLUMN_POSITION)"
        private const val CREATE_INDEX_TODO_STATUS = "CREATE INDEX idx_todo_status ON $TABLE_TODO ($COLUMN_STATUS)"

        @Volatile private var instance: ClipboardDatabase? = null
        @JvmStatic
        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: ClipboardDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}
