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

/** Result of an inline edit operation on a clipboard entry */
sealed class EditEntryResult {
    /** Content updated successfully */
    object Success : EditEntryResult()
    /** Another entry in the same table already has this content */
    object DuplicateConflict : EditEntryResult()
    /** Content is blank or otherwise invalid */
    object InvalidContent : EditEntryResult()
    /** Unexpected database error */
    data class Error(val message: String) : EditEntryResult()
}

/**
 * Pure decision logic for inline clipboard edits (UT-4). Shared by
 * [ClipboardHistoryService.editEntryContent] (service-layer early exit) and
 * [ClipboardDatabase.updateEntryContentInTable] (DB-layer guard) so both
 * layers agree on what counts as a no-op vs. a persistable change.
 *
 * No Android dependencies — covered by pure JVM tests (ClipboardEditJvmTest).
 */
object ClipboardEditPolicy {

    /** Outcome of comparing the stored content against the edited content. */
    sealed class Decision {
        /** Content is unchanged — skip the DB write, report success. */
        object NoOp : Decision()
        /** New content is blank/whitespace-only — reject the edit. */
        object Invalid : Decision()
        /** Persist [content] as the entry's new content. */
        data class Apply(val content: String) : Decision()
    }

    /**
     * Decide how an inline edit of [oldContent] → [newContent] should be handled.
     *
     * UT-4 fix: comparison and persistence are EXACT — a whitespace/newline-only
     * change (trailing newline, internal spacing, leading indent) is a real edit
     * and the new content is stored verbatim, NOT trimmed. Previously both layers
     * compared `.trim()`-ed values and stored the trimmed string, silently
     * dropping any edit that differed only in leading/trailing whitespace.
     */
    fun decide(oldContent: String, newContent: String): Decision = when {
        newContent.isBlank() -> Decision.Invalid
        newContent == oldContent -> Decision.NoOp
        else -> Decision.Apply(newContent)
    }
}

/**
 * Pure decision logic for merging the #156 private-copy markers when a clipboard insert
 * deduplicates onto an existing row. Extracted so the sticky-privacy semantics are unit-tested
 * (PrivateClipMergeRuleTest) independently of any Android/SQLite wiring.
 *
 * No Android dependencies.
 */
object PrivateClipMergeRule {
    /**
     * `is_private` is STICKY: once a row has been marked private by any copy, it stays private
     * even if a later normal copy dedups onto it (design §5.4). This preserves the row's
     * export-exclusion and push-gating promises. Result = old OR new.
     */
    fun mergeIsPrivate(existing: Boolean, incoming: Boolean): Boolean = existing || incoming

    /**
     * `source_package`: most-recent-non-null wins. A new copy that carries a provenance package
     * overwrites the stored one; a null incoming source leaves the existing value untouched.
     */
    fun mergeSourcePackage(existing: String?, incoming: String?): String? = incoming ?: existing
}

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
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Database upgraded v1→v2: added is_todo column")
            } catch (e: Exception) {
                if (e.message?.contains("duplicate column", ignoreCase = true) == true) {
                    // Column already exists — migration is idempotent, treat as already applied
                    Log.w(TAG, "v1→v2 column already exists, treating as migrated")
                } else {
                    // Re-throw so SQLiteOpenHelper rolls back the transaction (matches v2→v3/v3→v4)
                    Log.e(TAG, "Error upgrading v1→v2: ${e.message}", e)
                    throw e
                }
            }
        }
        if (oldVersion < 3) {
            migrateV2toV3(db)
        }
        if (oldVersion < 4) {
            migrateV3toV4(db)
        }
        if (oldVersion < 5) {
            migrateV4toV5(db)
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

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Database upgraded v2→v3: independent pinned/todo tables created")
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

            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Database upgraded v3→v4: media columns added to all tables")
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading v3→v4: ${e.message}", e)
            throw e  // Re-throw so SQLiteOpenHelper rolls back the transaction
        }
    }

    /**
     * Migrate from v4 to v5 (#156 private copy): add is_private + source_package to all three tables.
     *
     * Uses ALTER TABLE ADD COLUMN which is non-destructive and O(1) — only schema metadata changes,
     * no data is moved. Existing rows automatically get is_private=0 (not private) and
     * source_package=NULL (no provenance recorded before this feature existed).
     */
    private fun migrateV4toV5(db: SQLiteDatabase) {
        try {
            for (table in arrayOf(TABLE_CLIPBOARD, TABLE_PINNED, TABLE_TODO)) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $COLUMN_IS_PRIVATE INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $table ADD COLUMN $COLUMN_SOURCE_PACKAGE TEXT")
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Database upgraded v4→v5: is_private + source_package added to all tables")
        } catch (e: Exception) {
            Log.e(TAG, "Error upgrading v4→v5: ${e.message}", e)
            throw e  // Re-throw so SQLiteOpenHelper rolls back the transaction
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // History CRUD (clipboard_entries — regular history items only)
    // ═══════════════════════════════════════════════════════════════════

    fun addClipboardEntry(
        content: String?,
        expiryTimestamp: Long,
        isPrivate: Boolean = false,
        sourcePackage: String? = null
    ): Boolean {
        if (content.isNullOrBlank()) return false
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            val duplicateQuery = """
                SELECT $COLUMN_ID, $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? AND $COLUMN_EXPIRY_TIMESTAMP > ?
            """.trimIndent()
            db.rawQuery(duplicateQuery, arrayOf(contentHash, trimmedContent, currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    // #108: Move duplicate to top by updating its timestamp instead of ignoring
                    val existingId = cursor.getLong(0)
                    // #156: sticky-privacy merge — is_private ORs; source_package most-recent-non-null.
                    val mergedPrivate = PrivateClipMergeRule.mergeIsPrivate(cursor.getInt(1) != 0, isPrivate)
                    val mergedSource = PrivateClipMergeRule.mergeSourcePackage(
                        if (cursor.isNull(2)) null else cursor.getString(2), sourcePackage
                    )
                    val updateValues = ContentValues().apply {
                        put(COLUMN_TIMESTAMP, currentTime)
                        put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                        put(COLUMN_IS_PRIVATE, if (mergedPrivate) 1 else 0)
                        if (mergedSource != null) put(COLUMN_SOURCE_PACKAGE, mergedSource)
                        else putNull(COLUMN_SOURCE_PACKAGE)
                    }
                    db.update(TABLE_CLIPBOARD, updateValues, "$COLUMN_ID = ?", arrayOf(existingId.toString()))
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Duplicate moved to top (len=${trimmedContent.length}, id=$existingId, private=$mergedPrivate)")
                    }
                    return true
                }
            }
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_TIMESTAMP, currentTime)
                put(COLUMN_EXPIRY_TIMESTAMP, expiryTimestamp)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_IS_PRIVATE, if (isPrivate) 1 else 0)
                if (sourcePackage != null) put(COLUMN_SOURCE_PACKAGE, sourcePackage)
            }
            val result = db.insert(TABLE_CLIPBOARD, null, values)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Added clipboard entry (len=${trimmedContent.length}, id=$result)")
            }
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
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        Log.d(TAG, "Duplicate media moved to top (len=${content.length}, id=$existingId)")
                    }
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
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Added media entry ($mimeType, len=${content.length}, id=$result)")
            }
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error adding media clipboard entry: ${e.message}")
            false
        }
    }

    /** Get non-expired history entries with v4 media fields */
    fun getActiveClipboardEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val currentTime = System.currentTimeMillis()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_MIME_TYPE,
                   $COLUMN_THUMBNAIL_BLOB, $COLUMN_MEDIA_PATH, $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
            FROM $TABLE_CLIPBOARD
            WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
            ORDER BY $COLUMN_TIMESTAMP DESC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, arrayOf(currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(
                            content = cursor.getString(0),
                            timestamp = cursor.getLong(1),
                            mimeType = cursor.getString(2) ?: ClipboardEntry.MIME_TEXT_PLAIN,
                            thumbnailBlob = cursor.getBlob(3),
                            mediaPath = cursor.getString(4),
                            isPrivate = cursor.getInt(5) != 0,
                            sourcePackage = if (cursor.isNull(6)) null else cursor.getString(6)
                        ))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving clipboard entries: ${e.message}")
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Retrieved ${entries.size} active clipboard entries")
        return entries
    }

    /**
     * Remove a clipboard history entry by content. Returns the media_path of the deleted entry
     * (or null if text-only) so the caller can clean up the associated media file.
     */
    fun removeClipboardEntry(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val contentKey = resolveContentKey(TABLE_CLIPBOARD, content)
        return try {
            val db = writableDatabase
            // Query media_path before deleting so caller can clean up the file
            val mediaPath = db.rawQuery(
                "SELECT $COLUMN_MEDIA_PATH FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val deletedRows = db.delete(TABLE_CLIPBOARD, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Removed $deletedRows clipboard entries matching (len=${contentKey.length})")
            }
            if (deletedRows > 0) mediaPath else null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing clipboard entry: ${e.message}")
            null
        }
    }

    /**
     * Clear all history entries. Pinned/todo entries live in separate tables and are unaffected.
     * Returns the list of media_path values from deleted entries so caller can clean up files.
     */
    fun clearAllEntries(): Result<Pair<Int, List<String>>> {
        return try {
            val db = writableDatabase
            // Collect media paths before deleting so caller can clean up files
            val mediaPaths = mutableListOf<String>()
            db.rawQuery(
                "SELECT $COLUMN_MEDIA_PATH FROM $TABLE_CLIPBOARD WHERE $COLUMN_MEDIA_PATH IS NOT NULL",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { mediaPaths.add(it) }
                }
            }
            val deletedRows = db.delete(TABLE_CLIPBOARD, null, null)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Cleared $deletedRows history entries (${mediaPaths.size} with media, pinned/todo unaffected)")
            Result.success(Pair(deletedRows, mediaPaths))
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing clipboard entries: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Remove expired history entries. Pinned/todo are in separate tables and never expire.
     * Returns a pair of (deletedCount, mediaPaths) so caller can clean up associated media files.
     */
    fun cleanupExpiredEntries(): Pair<Int, List<String>> {
        val currentTime = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            // Collect media paths of expired entries before deleting
            val mediaPaths = mutableListOf<String>()
            db.rawQuery(
                "SELECT $COLUMN_MEDIA_PATH FROM $TABLE_CLIPBOARD WHERE $COLUMN_EXPIRY_TIMESTAMP <= ? AND $COLUMN_MEDIA_PATH IS NOT NULL",
                arrayOf(currentTime.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { mediaPaths.add(it) }
                }
            }
            val deletedRows = db.delete(
                TABLE_CLIPBOARD,
                "$COLUMN_EXPIRY_TIMESTAMP <= ?",
                arrayOf(currentTime.toString())
            )
            if (deletedRows > 0 && BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Cleaned up $deletedRows expired entries (${mediaPaths.size} with media)")
            Pair(deletedRows, mediaPaths)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired entries: ${e.message}")
            Pair(0, emptyList())
        }
    }

    /**
     * Rescue entries with stale expiry timestamps by updating them to never expire.
     * Called at startup when user's current duration setting is -1 (never expire).
     * This handles the case where entries were created with a previous TTL (e.g., 7 days)
     * before the default was changed to never expire — without this, those entries would
     * be silently deleted by cleanupExpiredEntries().
     *
     * @return number of entries rescued
     */
    fun rescueExpiredEntries(): Int {
        val currentTime = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            val cv = android.content.ContentValues().apply {
                put(COLUMN_EXPIRY_TIMESTAMP, Long.MAX_VALUE)
            }
            val updated = db.update(
                TABLE_CLIPBOARD,
                cv,
                "$COLUMN_EXPIRY_TIMESTAMP <= ? AND $COLUMN_EXPIRY_TIMESTAMP != ?",
                arrayOf(currentTime.toString(), Long.MAX_VALUE.toString())
            )
            if (updated > 0) Log.i(TAG, "Rescued $updated entries with stale expiry timestamps → never expire")
            updated
        } catch (e: Exception) {
            Log.e(TAG, "Error rescuing expired entries: ${e.message}")
            0
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pinned entries CRUD (pinned_entries — independent table)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Pin content by copying it to pinned_entries. If already pinned, returns false.
     * The original history entry (if any) is unaffected — COPY semantics.
     * Carries v4 media fields (mime_type, thumbnail_blob, media_path) when pinning media.
     *
     * @param content The text content to pin (or display name for media)
     * @param createdTimestamp Original clipboard copy time (defaults to now)
     * @param mimeType MIME type of the entry (default text/plain)
     * @param thumbnailBlob WebP thumbnail bytes or null
     * @param mediaPath Relative path to media file or null
     */
    fun pinEntry(
        content: String?,
        createdTimestamp: Long = System.currentTimeMillis(),
        mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
        thumbnailBlob: ByteArray? = null,
        mediaPath: String? = null,
        isPrivate: Boolean = false,
        sourcePackage: String? = null
    ): Boolean {
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
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Already pinned (len=${trimmedContent.length})")
                }
                return false
            }
            val position = getMaxPinnedPosition(db) + 1.0
            // #156: COPY semantics — the private marker travels from the source (history/other)
            // row onto the pinned copy at the DATA layer. Look up the source row's marker and
            // sticky-merge it with any explicitly-passed args: is_private ORs (once private, stays
            // private); source_package is explicit-if-given else the source row's provenance.
            val (sourceIsPrivate, sourceProvenance) = getPrivateMarker(trimmedContent)
            val mergedPrivate = PrivateClipMergeRule.mergeIsPrivate(sourceIsPrivate, isPrivate)
            val mergedSource = PrivateClipMergeRule.mergeSourcePackage(sourceProvenance, sourcePackage)
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTimestamp)
                put(COLUMN_PINNED_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_POSITION, position)
                put(COLUMN_TAGS, "[]")
                put(COLUMN_MIME_TYPE, mimeType)
                if (thumbnailBlob != null) put(COLUMN_THUMBNAIL_BLOB, thumbnailBlob)
                if (mediaPath != null) put(COLUMN_MEDIA_PATH, mediaPath)
                put(COLUMN_IS_PRIVATE, if (mergedPrivate) 1 else 0)  // #156: COPY semantics — marker travels
                if (mergedSource != null) put(COLUMN_SOURCE_PACKAGE, mergedSource)
            }
            val result = db.insert(TABLE_PINNED, null, values)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Pinned entry (len=${trimmedContent.length}, id=$result, pos=$position)")
            }
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error pinning entry: ${e.message}")
            false
        }
    }

    /**
     * Remove content from pinned_entries. History copy (if any) is unaffected.
     * Returns the media_path of the deleted entry (or null) so caller can clean up the file.
     * Note: media is only deleted if no other table references the same media_path.
     */
    fun unpinEntry(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val contentKey = resolveContentKey(TABLE_PINNED, content)
        return try {
            val db = writableDatabase
            val mediaPath = db.rawQuery(
                "SELECT $COLUMN_MEDIA_PATH FROM $TABLE_PINNED WHERE $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val deletedRows = db.delete(TABLE_PINNED, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Unpinned $deletedRows entries (len=${contentKey.length})")
            }
            if (deletedRows > 0) mediaPath else null
        } catch (e: Exception) {
            Log.e(TAG, "Error unpinning entry: ${e.message}")
            null
        }
    }

    /**
     * #156: look up the private-copy marker (is_private, source_package) for [content] across
     * all three tables, so pin/todo COPY operations preserve privacy provenance. Prefers the
     * history row; falls back to pinned/todo. Returns (isPrivate=false, source=null) if not found.
     */
    fun getPrivateMarker(content: String?): Pair<Boolean, String?> {
        if (content.isNullOrBlank()) return Pair(false, null)
        val trimmedContent = content.trim()
        val contentHash = trimmedContent.hashCode().toString()
        return try {
            val db = readableDatabase
            for (table in arrayOf(TABLE_CLIPBOARD, TABLE_PINNED, TABLE_TODO)) {
                db.rawQuery(
                    "SELECT $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE FROM $table " +
                        "WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? LIMIT 1",
                    arrayOf(contentHash, trimmedContent)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val isPrivate = cursor.getInt(0) != 0
                        val source = if (cursor.isNull(1)) null else cursor.getString(1)
                        if (isPrivate) return Pair(true, source)
                    }
                }
            }
            Pair(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading private marker: ${e.message}")
            Pair(false, null)
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
     * Ordered by position ASC (user-defined sort order). Includes v4 media fields.
     */
    fun getPinnedEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_PINNED_TIMESTAMP, $COLUMN_MIME_TYPE,
                   $COLUMN_THUMBNAIL_BLOB, $COLUMN_MEDIA_PATH, $COLUMN_TAGS,
                   $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
            FROM $TABLE_PINNED ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(
                            content = cursor.getString(0),
                            timestamp = cursor.getLong(1),
                            mimeType = cursor.getString(2) ?: ClipboardEntry.MIME_TEXT_PLAIN,
                            thumbnailBlob = cursor.getBlob(3),
                            mediaPath = cursor.getString(4),
                            tags = PinnedEntry.tagsFromJson(cursor.getString(5)),
                            isPrivate = cursor.getInt(6) != 0,
                            sourcePackage = if (cursor.isNull(7)) null else cursor.getString(7)
                        ))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving pinned entries: ${e.message}")
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Retrieved ${entries.size} pinned entries")
        return entries
    }

    /** Get pinned entries with full v4 fields (position, tags, timestamps, media) */
    fun getPinnedEntriesFull(): List<PinnedEntry> {
        val entries = mutableListOf<PinnedEntry>()
        val query = """
            SELECT $COLUMN_ID, $COLUMN_CONTENT, $COLUMN_CONTENT_HASH,
                   $COLUMN_CREATED_TIMESTAMP, $COLUMN_PINNED_TIMESTAMP,
                   $COLUMN_POSITION, $COLUMN_TAGS, $COLUMN_MIME_TYPE,
                   $COLUMN_THUMBNAIL_BLOB, $COLUMN_MEDIA_PATH,
                   $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
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
                            tags = PinnedEntry.tagsFromJson(cursor.getString(6)),
                            mimeType = cursor.getString(7) ?: ClipboardEntry.MIME_TEXT_PLAIN,
                            thumbnailBlob = cursor.getBlob(8),
                            mediaPath = cursor.getString(9),
                            isPrivate = cursor.getInt(10) != 0,
                            sourcePackage = if (cursor.isNull(11)) null else cursor.getString(11)
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
     * Carries v4 media fields when adding media as todo.
     *
     * @param content The text content to add as todo (or display name for media)
     * @param createdTimestamp Original clipboard copy time (defaults to now)
     * @param mimeType MIME type of the entry (default text/plain)
     * @param thumbnailBlob WebP thumbnail bytes or null
     * @param mediaPath Relative path to media file or null
     */
    fun addTodoEntry(
        content: String?,
        createdTimestamp: Long = System.currentTimeMillis(),
        mimeType: String = ClipboardEntry.MIME_TEXT_PLAIN,
        thumbnailBlob: ByteArray? = null,
        mediaPath: String? = null,
        isPrivate: Boolean = false,
        sourcePackage: String? = null
    ): Boolean {
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
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    Log.d(TAG, "Already a todo (len=${trimmedContent.length})")
                }
                return false
            }
            val position = getMaxTodoPosition(db) + 1.0
            // #156: COPY semantics — the private marker travels from the source (history/other)
            // row onto the todo copy at the DATA layer. Sticky-merge the source row's marker with
            // any explicitly-passed args (see pinEntry for the identical rationale).
            val (sourceIsPrivate, sourceProvenance) = getPrivateMarker(trimmedContent)
            val mergedPrivate = PrivateClipMergeRule.mergeIsPrivate(sourceIsPrivate, isPrivate)
            val mergedSource = PrivateClipMergeRule.mergeSourcePackage(sourceProvenance, sourcePackage)
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, trimmedContent)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTimestamp)
                put(COLUMN_ADDED_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_POSITION, position)
                put(COLUMN_STATUS, TodoEntry.STATUS_ACTIVE)
                put(COLUMN_TAGS, "[]")
                put(COLUMN_MIME_TYPE, mimeType)
                if (thumbnailBlob != null) put(COLUMN_THUMBNAIL_BLOB, thumbnailBlob)
                if (mediaPath != null) put(COLUMN_MEDIA_PATH, mediaPath)
                put(COLUMN_IS_PRIVATE, if (mergedPrivate) 1 else 0)  // #156: COPY semantics — marker travels
                if (mergedSource != null) put(COLUMN_SOURCE_PACKAGE, mergedSource)
            }
            val result = db.insert(TABLE_TODO, null, values)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Added todo (len=${trimmedContent.length}, id=$result, pos=$position)")
            }
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error adding todo entry: ${e.message}")
            false
        }
    }

    /**
     * Remove content from todo_entries. History copy (if any) is unaffected.
     * Returns the media_path of the deleted entry (or null) so caller can clean up the file.
     */
    fun removeTodoEntry(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val contentKey = resolveContentKey(TABLE_TODO, content)
        return try {
            val db = writableDatabase
            val mediaPath = db.rawQuery(
                "SELECT $COLUMN_MEDIA_PATH FROM $TABLE_TODO WHERE $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(contentKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val deletedRows = db.delete(TABLE_TODO, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Removed $deletedRows todo entries (len=${contentKey.length})")
            }
            if (deletedRows > 0) mediaPath else null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing todo entry: ${e.message}")
            null
        }
    }

    /** Update the status of a todo entry (active → planned → completed or any transition) */
    fun setTodoEntryStatus(content: String?, status: String): Boolean {
        if (content.isNullOrBlank()) return false
        if (status !in TodoEntry.VALID_STATUSES) {
            Log.w(TAG, "Invalid todo status: $status")
            return false
        }
        val contentKey = resolveContentKey(TABLE_TODO, content)
        return try {
            val db = writableDatabase
            val values = ContentValues().apply { put(COLUMN_STATUS, status) }
            val updatedRows = db.update(TABLE_TODO, values, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d(TAG, "Updated todo status to '$status' for $updatedRows entries (len=${contentKey.length})")
            }
            updatedRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating todo status: ${e.message}")
            false
        }
    }

    /**
     * Resolve the content key that actually exists in [table] for content-keyed
     * operations (delete/unpin/todo-status/tags). Legacy rows are trimmed on
     * insert, but rows edited via [updateEntryContentInTable] may carry
     * leading/trailing whitespace verbatim (UT-4). Callers pass the stored
     * `entry.content`, so try the exact string first; fall back to the trimmed
     * form for defensive compat with padded input against legacy trimmed rows.
     */
    private fun resolveContentKey(table: String, content: String): String {
        return try {
            val exactExists = readableDatabase.rawQuery(
                "SELECT 1 FROM $table WHERE $COLUMN_CONTENT = ? LIMIT 1",
                arrayOf(content)
            ).use { it.moveToFirst() }
            if (exactExists) content else content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving content key in $table: ${e.message}")
            content.trim()
        }
    }

    // ─── Inline edit: update content in a single table (COPY semantics preserved) ───

    /**
     * Update the content of a history (clipboard_entries) entry.
     * Recomputes content_hash. Rejects blank content and same-table duplicates.
     * Preserves timestamp, mime_type, thumbnail_blob, media_path, expiry fields.
     */
    fun updateHistoryEntryContent(oldContent: String, newContent: String): EditEntryResult {
        return updateEntryContentInTable(TABLE_CLIPBOARD, oldContent, newContent)
    }

    /**
     * Update the content of a pinned_entries entry.
     * Recomputes content_hash. Rejects blank content and same-table duplicates.
     * Preserves position, tags, timestamp, media fields.
     */
    fun updatePinnedEntryContent(oldContent: String, newContent: String): EditEntryResult {
        return updateEntryContentInTable(TABLE_PINNED, oldContent, newContent)
    }

    /**
     * Update the content of a todo_entries entry.
     * Recomputes content_hash. Rejects blank content and same-table duplicates.
     * Preserves position, tags, status, timestamp, media fields.
     */
    fun updateTodoEntryContent(oldContent: String, newContent: String): EditEntryResult {
        return updateEntryContentInTable(TABLE_TODO, oldContent, newContent)
    }

    /**
     * Shared implementation for updating content in any of the 3 clipboard tables.
     * Uses a transaction with dedup guard: if the new content already exists in the
     * same table (different row), returns DuplicateConflict instead of creating a duplicate.
     */
    private fun updateEntryContentInTable(table: String, oldContent: String, newContent: String): EditEntryResult {
        // UT-4: exact comparison + verbatim storage — whitespace/newline-only edits
        // are real changes and must persist (no trim normalization).
        val storedContent = when (val decision = ClipboardEditPolicy.decide(oldContent, newContent)) {
            is ClipboardEditPolicy.Decision.Invalid -> return EditEntryResult.InvalidContent
            is ClipboardEditPolicy.Decision.NoOp -> return EditEntryResult.Success // no-op
            is ClipboardEditPolicy.Decision.Apply -> decision.content
        }

        val newHash = storedContent.hashCode().toString()

        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // Dedup: check if another entry in this table already has the new content
                val hasDuplicate = db.rawQuery(
                    "SELECT 1 FROM $table WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ? AND $COLUMN_CONTENT != ? LIMIT 1",
                    arrayOf(newHash, storedContent, oldContent)
                ).use { it.moveToFirst() }

                if (hasDuplicate) {
                    db.endTransaction()
                    return EditEntryResult.DuplicateConflict
                }

                // Update content + hash, preserving all other columns.
                // Lookup key: exact stored content (legacy rows are trimmed-on-insert
                // and callers pass entry.content verbatim, so exact match is correct;
                // fall back to trimmed key for defensive compat with padded input).
                val values = ContentValues().apply {
                    put(COLUMN_CONTENT, storedContent)
                    put(COLUMN_CONTENT_HASH, newHash)
                }
                var updatedRows = db.update(table, values, "$COLUMN_CONTENT = ?", arrayOf(oldContent))
                if (updatedRows == 0 && oldContent != oldContent.trim()) {
                    updatedRows = db.update(table, values, "$COLUMN_CONTENT = ?", arrayOf(oldContent.trim()))
                }

                db.setTransactionSuccessful()
                if (updatedRows > 0) EditEntryResult.Success
                else EditEntryResult.Error("Entry not found in $table")
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating entry content in $table: ${e.message}")
            EditEntryResult.Error(e.message ?: "Unknown error")
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
     * Ordered by position ASC (user-defined sort order). Includes v4 media fields.
     */
    fun getTodoEntries(): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val query = """
            SELECT $COLUMN_CONTENT, $COLUMN_ADDED_TIMESTAMP, $COLUMN_MIME_TYPE,
                   $COLUMN_THUMBNAIL_BLOB, $COLUMN_MEDIA_PATH, $COLUMN_TAGS, $COLUMN_STATUS,
                   $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
            FROM $TABLE_TODO ORDER BY $COLUMN_POSITION ASC
        """.trimIndent()
        try {
            readableDatabase.rawQuery(query, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        entries.add(ClipboardEntry(
                            content = cursor.getString(0),
                            timestamp = cursor.getLong(1),
                            mimeType = cursor.getString(2) ?: ClipboardEntry.MIME_TEXT_PLAIN,
                            thumbnailBlob = cursor.getBlob(3),
                            mediaPath = cursor.getString(4),
                            tags = TodoEntry.tagsFromJson(cursor.getString(5)),
                            todoStatus = cursor.getString(6) ?: TodoEntry.STATUS_ACTIVE,
                            isPrivate = cursor.getInt(7) != 0,
                            sourcePackage = if (cursor.isNull(8)) null else cursor.getString(8)
                        ))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving todo entries: ${e.message}")
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Retrieved ${entries.size} todo entries")
        return entries
    }

    /** Get todo entries with full v4 fields (position, tags, status, timestamps, media) */
    fun getTodoEntriesFull(): List<TodoEntry> {
        val entries = mutableListOf<TodoEntry>()
        val query = """
            SELECT $COLUMN_ID, $COLUMN_CONTENT, $COLUMN_CONTENT_HASH,
                   $COLUMN_CREATED_TIMESTAMP, $COLUMN_ADDED_TIMESTAMP,
                   $COLUMN_POSITION, $COLUMN_STATUS, $COLUMN_TAGS,
                   $COLUMN_MIME_TYPE, $COLUMN_THUMBNAIL_BLOB, $COLUMN_MEDIA_PATH,
                   $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
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
                            tags = TodoEntry.tagsFromJson(cursor.getString(7)),
                            mimeType = cursor.getString(8) ?: ClipboardEntry.MIME_TEXT_PLAIN,
                            thumbnailBlob = cursor.getBlob(9),
                            mediaPath = cursor.getString(10),
                            isPrivate = cursor.getInt(11) != 0,
                            sourcePackage = if (cursor.isNull(12)) null else cursor.getString(12)
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
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Applied size limit: removed $entriesToDelete oldest history entries (limit=$maxSize)")
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
    /**
     * Apply size-based limit to clipboard history.
     * Accounts for text content, thumbnail BLOBs, and media files on disk.
     *
     * @param maxSizeMB Maximum total size in megabytes (text + thumbnails + media files)
     * @param filesDir App's filesDir for measuring media file sizes (null = DB-only measurement)
     * @return Pair of (entries deleted, media paths of deleted entries for file cleanup)
     */
    fun applySizeLimitBytes(maxSizeMB: Int, filesDir: java.io.File? = null): Pair<Int, List<String>> {
        if (maxSizeMB <= 0) return Pair(0, emptyList())
        val maxSizeBytes = maxSizeMB * 1024L * 1024L
        return try {
            val db = writableDatabase
            val currentTime = System.currentTimeMillis()
            var totalSize = 0L
            val idsToDelete = mutableListOf<Long>()
            val mediaPaths = mutableListOf<String>()
            // Include text content + thumbnail BLOB sizes from DB; media_path for disk stat
            db.rawQuery("""
                SELECT $COLUMN_ID,
                       LENGTH(CAST($COLUMN_CONTENT AS BLOB)) + COALESCE(LENGTH($COLUMN_THUMBNAIL_BLOB), 0),
                       $COLUMN_MEDIA_PATH
                FROM $TABLE_CLIPBOARD
                WHERE $COLUMN_EXPIRY_TIMESTAMP > ?
                ORDER BY $COLUMN_TIMESTAMP ASC
            """.trimIndent(), arrayOf(currentTime.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        val rowDbSize = cursor.getLong(1)
                        val mediaPath = if (!cursor.isNull(2)) cursor.getString(2) else null
                        // Include media file size on disk when filesDir is provided
                        val mediaFileSize = if (mediaPath != null && filesDir != null) {
                            java.io.File(filesDir, mediaPath).let { if (it.exists()) it.length() else 0L }
                        } else 0L
                        totalSize += rowDbSize + mediaFileSize
                        if (totalSize > maxSizeBytes) {
                            idsToDelete.add(cursor.getLong(0))
                            if (mediaPath != null) mediaPaths.add(mediaPath)
                        }
                    } while (cursor.moveToNext())
                }
            }
            if (idsToDelete.isEmpty()) return Pair(0, emptyList())
            for (chunk in idsToDelete.chunked(500)) {
                db.execSQL("DELETE FROM $TABLE_CLIPBOARD WHERE $COLUMN_ID IN (${chunk.joinToString(",")})")
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Applied size limit (bytes): removed ${idsToDelete.size} oldest entries (${mediaPaths.size} with media)")
            Pair(idsToDelete.size, mediaPaths)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying size limit (bytes): ${e.message}")
            Pair(0, emptyList())
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Media reference checks (v4 — COPY semantics means same file can be in multiple tables)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if a media_path is still referenced by any table.
     * COPY semantics means pinning/todoing a media entry duplicates the media_path reference,
     * so we must not delete the file until ALL references are gone.
     */
    fun isMediaPathReferenced(mediaPath: String?): Boolean {
        if (mediaPath.isNullOrBlank()) return false
        return try {
            val db = readableDatabase
            // Check all three tables for references to this media_path
            val tables = listOf(TABLE_CLIPBOARD, TABLE_PINNED, TABLE_TODO)
            tables.any { table ->
                db.rawQuery(
                    "SELECT 1 FROM $table WHERE $COLUMN_MEDIA_PATH = ? LIMIT 1",
                    arrayOf(mediaPath)
                ).use { it.moveToFirst() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking media path references: ${e.message}")
            true // Err on the side of keeping the file if check fails
        }
    }

    /**
     * Get all media_path values referenced by any table (for orphan cleanup).
     * Returns the set of all paths that should NOT be deleted from disk.
     *
     * Includes private rows — orphan-cleanup and thumbnail-regeneration must see
     * EVERY referenced file (deleting a private media file would corrupt the row).
     * For plaintext EXPORT packing, use [getReferencedMediaPaths] with
     * `includePrivate = false` so private media bytes never land in an unencrypted ZIP.
     */
    fun getAllReferencedMediaPaths(): Set<String> = getReferencedMediaPaths(includePrivate = true)

    /**
     * #156 option B (media): media_path values referenced by any table, optionally
     * excluding rows marked private.
     *
     * When [includePrivate] is false (a plaintext export), a path is returned ONLY if
     * at least one NON-private row references it — mirroring [exportToJSON]'s row filter
     * so the packed media set matches the plaintext manifest. This closes the leak where
     * a private media row (e.g. one flipped private by the sticky dedup merge) had its raw
     * bytes written into an unencrypted ZIP even though the manifest excluded the entry.
     *
     * A path shared by both a private and a non-private row IS included (a non-private row
     * still legitimately references the file); the DISTINCT + `is_private = 0` filter yields
     * exactly the set of files any exported plaintext row can point at.
     *
     * @param includePrivate `true` (default via [getAllReferencedMediaPaths]) for orphan
     *   cleanup / thumbnail regen / encrypted export; `false` for plaintext export packing.
     */
    fun getReferencedMediaPaths(includePrivate: Boolean): Set<String> {
        val paths = mutableSetOf<String>()
        val privacyFilter = if (includePrivate) "" else " AND $COLUMN_IS_PRIVATE = 0"
        try {
            val db = readableDatabase
            for (table in listOf(TABLE_CLIPBOARD, TABLE_PINNED, TABLE_TODO)) {
                db.rawQuery(
                    "SELECT DISTINCT $COLUMN_MEDIA_PATH FROM $table " +
                        "WHERE $COLUMN_MEDIA_PATH IS NOT NULL$privacyFilter",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { paths.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting referenced media paths (includePrivate=$includePrivate): ${e.message}")
        }
        return paths
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tags CRUD (foundation — UI deferred to separate PR)
    // ═══════════════════════════════════════════════════════════════════

    /** Set tags for a pinned entry by content */
    fun setPinnedEntryTags(content: String, tags: List<String>): Boolean {
        val contentKey = resolveContentKey(TABLE_PINNED, content)
        return try {
            val arr = JSONArray()
            tags.forEach { arr.put(it) }
            val values = ContentValues().apply { put(COLUMN_TAGS, arr.toString()) }
            val updated = writableDatabase.update(TABLE_PINNED, values, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
            updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pinned tags: ${e.message}")
            false
        }
    }

    /** Set tags for a todo entry by content */
    fun setTodoEntryTags(content: String, tags: List<String>): Boolean {
        val contentKey = resolveContentKey(TABLE_TODO, content)
        return try {
            val arr = JSONArray()
            tags.forEach { arr.put(it) }
            val values = ContentValues().apply { put(COLUMN_TAGS, arr.toString()) }
            val updated = writableDatabase.update(TABLE_TODO, values, "$COLUMN_CONTENT = ?", arrayOf(contentKey))
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
    // Export / Import (v4 format with v2/v3 backward compatibility)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Export clipboard data to JSON. Text-only entries are always included.
     * Media entries include mime_type and media_path metadata (no thumbnail BLOBs or
     * file bytes — those go in the ZIP export only).
     *
     * @param textOnly If true, skip media entries entirely (lightweight JSON export).
     *                 If false, include media metadata (for ZIP export manifest).
     */
    /**
     * @param textOnly       skip media entries (media files don't survive a JSON-only export).
     * @param includePrivate #156 option B: when false (plaintext export), private entries are
     *                       excluded and counted in `private_skipped`; when true (encrypted CKENC
     *                       export) they are included with the is_private/source_package markers so
     *                       the marker round-trips on import.
     */
    fun exportToJSON(textOnly: Boolean = false, includePrivate: Boolean = true): JSONObject? {
        return try {
            val activeArray = JSONArray()
            val pinnedArray = JSONArray()
            val todoArray = JSONArray()
            var activeCount = 0; var pinnedCount = 0; var todoCount = 0
            var mediaSkipped = 0
            var privateSkipped = 0  // #156: private entries omitted from a plaintext export

            // Export history entries (v5: includes content_hash, mime_type, media_path, is_private, source_package)
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_TIMESTAMP, $COLUMN_EXPIRY_TIMESTAMP,
                       $COLUMN_MIME_TYPE, $COLUMN_MEDIA_PATH, $COLUMN_CONTENT_HASH,
                       $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
                FROM $TABLE_CLIPBOARD ORDER BY $COLUMN_TIMESTAMP DESC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(3) ?: ClipboardEntry.MIME_TEXT_PLAIN
                    val mediaPath = cursor.getString(4)
                    val contentHash = cursor.getString(5)
                    val isPrivate = cursor.getInt(6) != 0
                    val sourcePackage = if (cursor.isNull(7)) null else cursor.getString(7)
                    val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
                    if (isPrivate && !includePrivate) { privateSkipped++; continue }
                    if (textOnly && isMedia) { mediaSkipped++; continue }
                    activeArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("timestamp", cursor.getLong(1))
                        put("expiry_timestamp", cursor.getLong(2))
                        if (contentHash != null) put("content_hash", contentHash)
                        if (isMedia) {
                            put("mime_type", mimeType)
                            if (mediaPath != null) put("media_path", mediaPath)
                        }
                        if (isPrivate) {
                            put("is_private", 1)
                            if (sourcePackage != null) put("source_package", sourcePackage)
                        }
                    })
                    activeCount++
                }
            }

            // Export pinned entries with v5 media + private fields
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP,
                       $COLUMN_PINNED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_TAGS,
                       $COLUMN_MIME_TYPE, $COLUMN_MEDIA_PATH,
                       $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
                FROM $TABLE_PINNED ORDER BY $COLUMN_POSITION ASC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(6) ?: ClipboardEntry.MIME_TEXT_PLAIN
                    val mediaPath = cursor.getString(7)
                    val isPrivate = cursor.getInt(8) != 0
                    val sourcePackage = if (cursor.isNull(9)) null else cursor.getString(9)
                    val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
                    if (isPrivate && !includePrivate) { privateSkipped++; continue }
                    if (textOnly && isMedia) { mediaSkipped++; continue }
                    pinnedArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("content_hash", cursor.getString(1))
                        put("created_timestamp", cursor.getLong(2))
                        put("pinned_timestamp", cursor.getLong(3))
                        put("timestamp", cursor.getLong(3))  // v2 compat key
                        put("position", cursor.getDouble(4))
                        put("tags", cursor.getString(5) ?: "[]")
                        if (isMedia) {
                            put("mime_type", mimeType)
                            if (mediaPath != null) put("media_path", mediaPath)
                        }
                        if (isPrivate) {
                            put("is_private", 1)
                            if (sourcePackage != null) put("source_package", sourcePackage)
                        }
                    })
                    pinnedCount++
                }
            }

            // Export todo entries with v5 media + private fields
            readableDatabase.rawQuery("""
                SELECT $COLUMN_CONTENT, $COLUMN_CONTENT_HASH, $COLUMN_CREATED_TIMESTAMP,
                       $COLUMN_ADDED_TIMESTAMP, $COLUMN_POSITION, $COLUMN_STATUS, $COLUMN_TAGS,
                       $COLUMN_MIME_TYPE, $COLUMN_MEDIA_PATH,
                       $COLUMN_IS_PRIVATE, $COLUMN_SOURCE_PACKAGE
                FROM $TABLE_TODO ORDER BY $COLUMN_POSITION ASC
            """.trimIndent(), null).use { cursor ->
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(7) ?: ClipboardEntry.MIME_TEXT_PLAIN
                    val mediaPath = cursor.getString(8)
                    val isPrivate = cursor.getInt(9) != 0
                    val sourcePackage = if (cursor.isNull(10)) null else cursor.getString(10)
                    val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
                    if (isPrivate && !includePrivate) { privateSkipped++; continue }
                    if (textOnly && isMedia) { mediaSkipped++; continue }
                    todoArray.put(JSONObject().apply {
                        put("content", cursor.getString(0))
                        put("content_hash", cursor.getString(1))
                        put("created_timestamp", cursor.getLong(2))
                        put("added_timestamp", cursor.getLong(3))
                        put("timestamp", cursor.getLong(3))  // v2 compat key
                        put("position", cursor.getDouble(4))
                        put("status", cursor.getString(5) ?: TodoEntry.STATUS_ACTIVE)
                        put("tags", cursor.getString(6) ?: "[]")
                        if (isMedia) {
                            put("mime_type", mimeType)
                            if (mediaPath != null) put("media_path", mediaPath)
                        }
                        if (isPrivate) {
                            put("is_private", 1)
                            if (sourcePackage != null) put("source_package", sourcePackage)
                        }
                    })
                    todoCount++
                }
            }

            JSONObject().apply {
                put("active_entries", activeArray)
                put("pinned_entries", pinnedArray)
                put("todo_entries", todoArray)
                put("export_version", 5)
                put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("total_active", activeCount)
                put("total_pinned", pinnedCount)
                put("total_todo", todoCount)
                if (mediaSkipped > 0) put("media_skipped", mediaSkipped)
                if (privateSkipped > 0) put("private_skipped", privateSkipped)  // #156 option B
            }.also {
                val mediaSuffix = if (textOnly) " (text-only, $mediaSkipped media skipped)" else ""
                val privSuffix = if (privateSkipped > 0) " ($privateSkipped private excluded)" else ""
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Exported $activeCount active, $pinnedCount pinned, $todoCount todo entries (v5)$mediaSuffix$privSuffix")
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
     * **Throws on failure (CK-150-019).** The transaction rolls back (no
     * `setTransactionSuccessful` on the failing path) and the exception propagates to the
     * caller. It must NOT be swallowed here: [BackupRestoreManager] commits staged media
     * files *before* calling this, and only an escaping exception reaches the media
     * rollback + `success = false` result. Returning partial counts after a rolled-back
     * transaction told the user a failed import had succeeded and orphaned the media commit.
     *
     * @return IntArray of [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped,
     *         truncatedByCap] — the fifth slot was added by ARC-034 and counts entries
     *         dropped by [MAX_IMPORT_ENTRIES_PER_ARRAY]; it is 0 for every real payload.
     * @throws Exception any SQLite/JSON failure; the DB transaction is already rolled back.
     */
    fun importFromJSON(importData: JSONObject): IntArray {
        var activeAdded = 0; var pinnedAdded = 0; var todoAdded = 0; var duplicatesSkipped = 0
        var truncatedByCap = 0

        // ARC-034: how many of [entries] to iterate. Accumulates the drop count and logs it
        // once per over-cap section, so a truncated import is never silent in logcat.
        fun capped(entries: JSONArray, section: String): Int {
            val length = entries.length()
            if (length <= MAX_IMPORT_ENTRIES_PER_ARRAY) return length
            val dropped = length - MAX_IMPORT_ENTRIES_PER_ARRAY
            truncatedByCap += dropped
            Log.w(
                TAG,
                "Clipboard import: '$section' declares $length entries — importing the first " +
                    "$MAX_IMPORT_ENTRIES_PER_ARRAY, dropping $dropped (per-array cap)"
            )
            return MAX_IMPORT_ENTRIES_PER_ARRAY
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val exportVersion = importData.optInt("export_version", 1)
            val freshExpiry = ClipboardHistoryService.getHistoryTtlMs().let { ttl ->
                if (ttl == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttl
            }

            // Import active/history entries (same format in v2 and v3)
            if (importData.has("active_entries")) {
                val entries = importData.getJSONArray("active_entries")
                val result = importHistoryEntries(
                    db, entries, freshExpiry, capped(entries, "active_entries")
                )
                activeAdded = result.first
                duplicatesSkipped += result.second
            }

            // Import pinned entries
            if (importData.has("pinned_entries")) {
                val entries = importData.getJSONArray("pinned_entries")
                val result = importPinnedEntries(
                    db, entries, exportVersion, freshExpiry, capped(entries, "pinned_entries")
                )
                pinnedAdded = result.first
                duplicatesSkipped += result.second
            }

            // Import todo entries
            if (importData.has("todo_entries")) {
                val entries = importData.getJSONArray("todo_entries")
                val result = importTodoEntries(
                    db, entries, exportVersion, freshExpiry, capped(entries, "todo_entries")
                )
                todoAdded = result.first
                duplicatesSkipped += result.second
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Import complete: $activeAdded active, $pinnedAdded pinned, $todoAdded todo, $duplicatesSkipped dupes skipped, $truncatedByCap truncated")
        return intArrayOf(activeAdded, pinnedAdded, todoAdded, duplicatesSkipped, truncatedByCap)
    }

    /** Import history entries into clipboard_entries.
     *  Uses exported content_hash when available (preserves SHA-256 for media entries).
     *  Falls back to String.hashCode() for text entries or old exports without hash. */
    private fun importHistoryEntries(
        db: SQLiteDatabase, entries: JSONArray, freshExpiry: Long, limit: Int
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        // ARC-034: `limit` is entries.length() capped at MAX_IMPORT_ENTRIES_PER_ARRAY.
        for (i in 0 until limit) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val mimeType = entry.optString("mime_type", ClipboardEntry.MIME_TEXT_PLAIN)
            val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
            // Prefer exported hash (preserves SHA-256 for media); fall back to hashCode for text
            val contentHash = entry.optString("content_hash", "").ifEmpty { content.hashCode().toString() }

            // Media dedup: hash-only (SHA-256 is collision-free)
            // Text dedup: hash+content (hashCode can collide)
            val isDuplicate = if (isMedia) {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ?",
                    arrayOf(contentHash)
                ).use { it.moveToFirst() }
            } else {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                    arrayOf(contentHash, content)
                ).use { it.moveToFirst() }
            }
            if (isDuplicate) { skipped++; continue }
            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_TIMESTAMP, entry.getLong("timestamp"))
                put(COLUMN_EXPIRY_TIMESTAMP, freshExpiry)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_MIME_TYPE, mimeType)
                val mediaPath = if (entry.has("media_path")) entry.getString("media_path") else null
                if (mediaPath != null) put(COLUMN_MEDIA_PATH, mediaPath)
                // #156: round-trip the private marker (absent → non-private, per old backups)
                put(COLUMN_IS_PRIVATE, if (entry.optInt("is_private", 0) != 0) 1 else 0)
                if (entry.has("source_package")) put(COLUMN_SOURCE_PACKAGE, entry.getString("source_package"))
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
        db: SQLiteDatabase, entries: JSONArray, exportVersion: Int, freshExpiry: Long, limit: Int
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        var maxPosition = getMaxPinnedPosition(db)
        // ARC-034: `limit` is entries.length() capped at MAX_IMPORT_ENTRIES_PER_ARRAY.
        for (i in 0 until limit) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val mimeType = entry.optString("mime_type", ClipboardEntry.MIME_TEXT_PLAIN)
            val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
            // Prefer exported hash (preserves SHA-256 for media); fall back to hashCode for text
            val contentHash = entry.optString("content_hash", "").ifEmpty { content.hashCode().toString() }

            // Duplicate check: media uses hash-only, text uses hash+content
            val isDuplicate = if (isMedia) {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_PINNED WHERE $COLUMN_CONTENT_HASH = ?",
                    arrayOf(contentHash)
                ).use { it.moveToFirst() }
            } else {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_PINNED WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                    arrayOf(contentHash, content)
                ).use { it.moveToFirst() }
            }
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
                put(COLUMN_MIME_TYPE, mimeType)
                val mediaPath = if (entry.has("media_path")) entry.getString("media_path") else null
                if (mediaPath != null) put(COLUMN_MEDIA_PATH, mediaPath)
                // #156: round-trip the private marker (absent → non-private, per old backups)
                put(COLUMN_IS_PRIVATE, if (entry.optInt("is_private", 0) != 0) 1 else 0)
                if (entry.has("source_package")) put(COLUMN_SOURCE_PACKAGE, entry.getString("source_package"))
            }
            if (db.insert(TABLE_PINNED, null, values) != -1L) added++

            // COPY semantics: also insert into history if not already there
            if (exportVersion < 3) {
                val inHistory = if (isMedia) {
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ?",
                        arrayOf(contentHash)
                    ).use { it.moveToFirst() }
                } else {
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                        arrayOf(contentHash, content)
                    ).use { it.moveToFirst() }
                }
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
        db: SQLiteDatabase, entries: JSONArray, exportVersion: Int, freshExpiry: Long, limit: Int
    ): Pair<Int, Int> {
        var added = 0; var skipped = 0
        var maxPosition = getMaxTodoPosition(db)
        // ARC-034: `limit` is entries.length() capped at MAX_IMPORT_ENTRIES_PER_ARRAY.
        for (i in 0 until limit) {
            val entry = entries.getJSONObject(i)
            val content = entry.getString("content")
            val mimeType = entry.optString("mime_type", ClipboardEntry.MIME_TEXT_PLAIN)
            val isMedia = mimeType != ClipboardEntry.MIME_TEXT_PLAIN
            // Prefer exported hash (preserves SHA-256 for media); fall back to hashCode for text
            val contentHash = entry.optString("content_hash", "").ifEmpty { content.hashCode().toString() }

            // Duplicate check: media uses hash-only, text uses hash+content
            val isDuplicate = if (isMedia) {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_TODO WHERE $COLUMN_CONTENT_HASH = ?",
                    arrayOf(contentHash)
                ).use { it.moveToFirst() }
            } else {
                db.rawQuery(
                    "SELECT $COLUMN_ID FROM $TABLE_TODO WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                    arrayOf(contentHash, content)
                ).use { it.moveToFirst() }
            }
            if (isDuplicate) { skipped++; continue }

            val createdTs = entry.optLong("created_timestamp", entry.getLong("timestamp"))
            val addedTs = entry.optLong("added_timestamp", entry.getLong("timestamp"))
            val position = if (exportVersion >= 3) entry.getDouble("position") else { maxPosition += 1.0; maxPosition }
            val rawStatus = if (exportVersion >= 3) entry.optString("status", TodoEntry.STATUS_ACTIVE) else TodoEntry.STATUS_ACTIVE
            // Validate against known statuses to guard against corrupted imports
            val status = if (rawStatus in TodoEntry.VALID_STATUSES) rawStatus else TodoEntry.STATUS_ACTIVE
            val tags = if (exportVersion >= 3) entry.optString("tags", "[]") else "[]"

            val values = ContentValues().apply {
                put(COLUMN_CONTENT, content)
                put(COLUMN_CONTENT_HASH, contentHash)
                put(COLUMN_CREATED_TIMESTAMP, createdTs)
                put(COLUMN_ADDED_TIMESTAMP, addedTs)
                put(COLUMN_POSITION, position)
                put(COLUMN_STATUS, status)
                put(COLUMN_TAGS, tags)
                put(COLUMN_MIME_TYPE, mimeType)
                val mediaPath = if (entry.has("media_path")) entry.getString("media_path") else null
                if (mediaPath != null) put(COLUMN_MEDIA_PATH, mediaPath)
                // #156: round-trip the private marker (absent → non-private, per old backups)
                put(COLUMN_IS_PRIVATE, if (entry.optInt("is_private", 0) != 0) 1 else 0)
                if (entry.has("source_package")) put(COLUMN_SOURCE_PACKAGE, entry.getString("source_package"))
            }
            if (db.insert(TABLE_TODO, null, values) != -1L) added++

            // COPY semantics: also insert into history if not already there
            if (exportVersion < 3) {
                val inHistory = if (isMedia) {
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ?",
                        arrayOf(contentHash)
                    ).use { it.moveToFirst() }
                } else {
                    db.rawQuery(
                        "SELECT $COLUMN_ID FROM $TABLE_CLIPBOARD WHERE $COLUMN_CONTENT_HASH = ? AND $COLUMN_CONTENT = ?",
                        arrayOf(contentHash, content)
                    ).use { it.moveToFirst() }
                }
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
        private const val DATABASE_VERSION = 5  // v5: #156 Private copy (is_private, source_package)
        private const val TABLE_CLIPBOARD = "clipboard_entries"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRY_TIMESTAMP = "expiry_timestamp"
        private const val COLUMN_CONTENT_HASH = "content_hash"
        private const val TAG = "ClipboardDatabase"

        /**
         * Per-ARRAY ceiling on [importFromJSON] (ARC-034).
         *
         * `BackupRestoreManager.MAX_IMPORT_ENTRIES` bounds how many entries a backup ZIP may
         * contain; it says nothing about what is INSIDE `clipboard_history.json`, whose three
         * arrays were iterated unbounded. Each imported row costs a `rawQuery` dedup probe plus
         * an `INSERT` inside one transaction, so a hand-built export with millions of entries
         * in one array is a cheap way to wedge the app on an import the user asked for.
         *
         * Deliberately the same 10,000 as the ZIP cap — the two bound different things but there
         * is no reason for the numbers to differ, and a single remembered figure is likelier to
         * stay right. Far above any real history: `clipboard_history_limit` tops out in the
         * hundreds, so only a synthetic payload can reach this.
         *
         * TRUNCATES rather than rejects (unlike the ZIP cap, which refuses outright): a clipboard
         * import is additive and partially-applied history is still useful, whereas a ZIP over
         * the entry cap is structurally suspect. The drop is logged and counted into the result.
         */
        const val MAX_IMPORT_ENTRIES_PER_ARRAY = 10_000

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

        // ─── v5 schema: #156 private-copy columns (all three tables) ───
        // is_private: 1 when the row was captured via the private-copy path (never on OS clipboard).
        // source_package: provenance — target editor package (entry point A) or getCallingPackage()
        //                 / "direct-launch" (entry point B). NULL for normal-copy and pre-v5 rows.
        const val COLUMN_IS_PRIVATE = "is_private"
        const val COLUMN_SOURCE_PACKAGE = "source_package"

        // DDL for clipboard_entries (fresh install → final v5 schema: media + private columns)
        private const val CREATE_TABLE_CLIPBOARD_V4 = """
            CREATE TABLE $TABLE_CLIPBOARD (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_EXPIRY_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_CONTENT_HASH TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT DEFAULT 'text/plain',
                $COLUMN_THUMBNAIL_BLOB BLOB,
                $COLUMN_MEDIA_PATH TEXT,
                $COLUMN_IS_PRIVATE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SOURCE_PACKAGE TEXT
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

        // v5 DDL for fresh installs (includes media + private columns)
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
                $COLUMN_MEDIA_PATH TEXT,
                $COLUMN_IS_PRIVATE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SOURCE_PACKAGE TEXT
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
                $COLUMN_MEDIA_PATH TEXT,
                $COLUMN_IS_PRIVATE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_SOURCE_PACKAGE TEXT
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
