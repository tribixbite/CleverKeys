package tribixbite.cleverkeys.gif

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SQLite database handler for offline GIF storage with FTS4 full-text search.
 *
 * Uses FTS4 (not FTS5) for universal Android compatibility — FTS5 is not
 * available in android.database.sqlite on all devices/OEM builds.
 *
 * V4 Schema (incremental pack import):
 * - categories: Emotion category definitions (shared across packs)
 * - gifs: GIF metadata with search_text column (for content-synced FTS4)
 * - gif_category_map: WITHOUT ROWID, clustered by (category_id, gif_id)
 * - gifs_fts: Content-synced FTS4 (content="gifs") — supports per-row delete
 * - gif_usage: Usage tracking for "recently used" (created on-device)
 * - installed_packs: Tracks which packs are imported (pack_id TEXT, not integer)
 *
 * Pack import: ATTACH mini pack.db, INSERT INTO main tables in single transaction.
 * Pack removal: DELETE from gifs/category_map, then rebuild FTS4 index.
 * Asset filenames derived at runtime: String.format("%06d.webp", gif_id)
 */
class GifDatabase private constructor(private val appContext: Context) {

    private val dbHelper: GifDatabaseHelper = GifDatabaseHelper(appContext)

    /**
     * Search GIFs using FTS4 full-text search with compound word fallback.
     * Content-synced FTS4 — joins via docid matching gif_id.
     *
     * If the initial FTS query returns no results and the query is a single word
     * longer than 4 chars, tries splitting it into two subwords and re-searching.
     * This handles compound words like "eyeroll" matching "eye roll" in the index.
     */
    suspend fun searchGifs(query: String, limit: Int = 100): List<Gif> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val db = dbHelper.readableDatabase
        val sanitizedQuery = sanitizeFtsQuery(query)
        var results = executeFtsSearch(db, sanitizedQuery, limit)

        // Compound word fallback: if single word > 4 chars with no FTS results,
        // try splitting into two subwords. Handles "eyeroll" → "eye* roll*".
        if (results.isEmpty()) {
            val word = query.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            if (word.length > 4 && !query.trim().contains(" ")) {
                for (i in 3..(word.length - 3)) {
                    val splitQuery = "${word.substring(0, i)}* ${word.substring(i)}*"
                    results = executeFtsSearch(db, splitQuery, limit)
                    if (results.isNotEmpty()) break
                }
            }
        }

        // Load categories for each result
        results.map { gif ->
            gif.copy(categories = getCategoriesForGif(gif.id))
        }
    }

    /**
     * Execute a single FTS4 MATCH query and return raw Gif results.
     */
    private fun executeFtsSearch(db: SQLiteDatabase, ftsQuery: String, limit: Int): List<Gif> {
        val results = mutableListOf<Gif>()
        try {
            val cursor = db.rawQuery(
                """
                SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                       g.pack_id, g.search_text
                FROM gifs_fts fts
                JOIN gifs g ON g.gif_id = fts.docid
                WHERE fts.search_text MATCH ?
                LIMIT ?
                """.trimIndent(),
                arrayOf(ftsQuery, limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    results.add(cursorToGif(it))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS search failed for '$ftsQuery': ${e.message}")
        }
        return results
    }

    /**
     * Get all available GIFs in a specific category.
     */
    suspend fun getGifsByCategory(category: GifCategory, limit: Int = 200): List<Gif> =
        withContext(Dispatchers.IO) {
            if (category == GifCategory.RECENTLY_USED) {
                return@withContext getRecentlyUsedGifs(limit)
            }
            if (category == GifCategory.ALL) {
                return@withContext getAllGifs(limit)
            }

            val results = mutableListOf<Gif>()
            val db = dbHelper.readableDatabase

            val cursor = db.rawQuery(
                """
                SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                       g.pack_id, g.search_text
                FROM gifs g
                JOIN gif_category_map gcm ON g.gif_id = gcm.gif_id
                WHERE gcm.category_id = ?
                LIMIT ?
                """.trimIndent(),
                arrayOf(category.id.toString(), limit.toString())
            )

            cursor.use {
                while (it.moveToNext()) {
                    results.add(cursorToGif(it).copy(categories = listOf(category)))
                }
            }

            results
        }

    /**
     * Get recently used GIFs sorted by last use time.
     */
    suspend fun getRecentlyUsedGifs(limit: Int = 50): List<Gif> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Gif>()
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.search_text
            FROM gifs g
            JOIN gif_usage u ON g.gif_id = u.gif_id
            WHERE u.use_count > 0
            ORDER BY u.last_used DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        )

        cursor.use {
            while (it.moveToNext()) {
                val gif = cursorToGif(it)
                results.add(gif.copy(categories = getCategoriesForGif(gif.id)))
            }
        }

        results
    }

    /**
     * Record that a GIF was used (for recently used tracking).
     */
    suspend fun recordGifUsage(gifId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dbHelper.writableDatabase.execSQL(
            """
            INSERT INTO gif_usage (gif_id, use_count, last_used)
            VALUES (?, 1, ?)
            ON CONFLICT(gif_id) DO UPDATE SET
                use_count = use_count + 1,
                last_used = ?
            """.trimIndent(),
            arrayOf(gifId, now, now)
        )
    }

    /**
     * Get a single GIF by ID.
     */
    suspend fun getGifById(gifId: Long): Gif? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.search_text
            FROM gifs g WHERE g.gif_id = ?
            """.trimIndent(),
            arrayOf(gifId.toString())
        )

        cursor.use {
            if (it.moveToFirst()) {
                cursorToGif(it).copy(categories = getCategoriesForGif(gifId))
            } else {
                null
            }
        }
    }

    /**
     * Get all GIFs regardless of category (for initial overview display).
     */
    suspend fun getAllGifs(limit: Int = 200): List<Gif> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Gif>()
        val db = dbHelper.readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.search_text
            FROM gifs g
            ORDER BY g.gif_id
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        )

        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToGif(it))
            }
        }

        results
    }

    /**
     * Get total count of GIFs in the database.
     */
    fun getTotalGifCount(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM gifs", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Get count of GIFs in a category.
     */
    fun getCategoryCount(category: GifCategory): Int {
        val db = dbHelper.readableDatabase
        if (category == GifCategory.RECENTLY_USED) {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM gif_usage WHERE use_count > 0", null)
            return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }

        val cursor = db.rawQuery(
            """
            SELECT COUNT(*) FROM gif_category_map gcm
            JOIN gifs g ON g.gif_id = gcm.gif_id
            WHERE gcm.category_id = ?
            """.trimIndent(),
            arrayOf(category.id.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ==================== PACK IMPORT ====================

    /**
     * Import a GIF pack from a mini SQLite database file.
     * Uses ATTACH DATABASE to merge pack entries into the main DB in a single transaction.
     *
     * The pack.db must have tables: gifs (with search_text), gif_category_map, categories.
     *
     * @param packDbFile Absolute path to the extracted pack.db file
     * @param packId Unique string identifier for this pack
     * @param packName Display name
     * @param gifCount Number of GIFs in pack
     * @param sizeBytes Total size of pack data on disk
     * @param hasFullGifs Whether this pack includes full animated GIF files (not just thumbs)
     * @return Number of GIFs actually imported
     */
    suspend fun importPack(
        packDbFile: File,
        packId: String,
        packName: String,
        gifCount: Int,
        sizeBytes: Long,
        hasFullGifs: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        var imported = 0

        try {
            db.execSQL("ATTACH DATABASE ? AS pack_db", arrayOf(packDbFile.absolutePath))

            db.beginTransaction()
            try {
                // Register pack in packs lookup table to get an integer ID for gifs FK
                db.execSQL("""
                    INSERT OR IGNORE INTO packs (name, gif_count) VALUES (?, ?)
                """, arrayOf(packId, gifCount))
                val intPackIdCursor = db.rawQuery(
                    "SELECT pack_id FROM packs WHERE name = ?", arrayOf(packId)
                )
                val intPackId = intPackIdCursor.use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }

                // Merge categories (ignore duplicates)
                db.execSQL("""
                    INSERT OR IGNORE INTO categories (category_id, name, icon, sort_order)
                    SELECT category_id, name, icon, sort_order FROM pack_db.categories
                """)

                // Import GIFs — INSERT OR IGNORE so the first pack to provide a gif_id
                // owns the row. Later packs with overlapping IDs won't overwrite.
                db.execSQL("""
                    INSERT OR IGNORE INTO gifs (gif_id, width, height, duration_ms, file_size, pack_id, search_text, created_at)
                    SELECT gif_id, width, height, duration_ms, file_size, ?, search_text, created_at
                    FROM pack_db.gifs
                """, arrayOf(intPackId))

                // Import category mappings (ignore duplicates)
                db.execSQL("""
                    INSERT OR IGNORE INTO gif_category_map (category_id, gif_id)
                    SELECT category_id, gif_id FROM pack_db.gif_category_map
                """)

                // Record pack membership for each gif — enables clean multi-pack removal
                val hasFullInt = if (hasFullGifs) 1 else 0
                db.execSQL("""
                    INSERT OR IGNORE INTO gif_pack_membership (gif_id, pack_id, has_full_gif)
                    SELECT gif_id, ?, ? FROM pack_db.gifs
                """, arrayOf(packId, hasFullInt))

                // Rebuild FTS4 index to include new rows
                db.execSQL("INSERT INTO gifs_fts(gifs_fts) VALUES('rebuild')")

                // Track installed pack
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT OR REPLACE INTO installed_packs (pack_id, name, gif_count, installed_at, size_bytes)
                    VALUES (?, ?, ?, ?, ?)
                """, arrayOf(packId, packName, gifCount, now, sizeBytes))

                // Count actual imports
                val cursor = db.rawQuery("SELECT COUNT(*) FROM pack_db.gifs", null)
                imported = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }

                db.setTransactionSuccessful()
                Log.i(TAG, "Imported pack '$packId': $imported GIFs (hasFullGifs=$hasFullGifs)")
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import pack '$packId'", e)
            throw e
        } finally {
            try {
                db.execSQL("DETACH DATABASE pack_db")
            } catch (e: Exception) {
                Log.w(TAG, "DETACH failed (may not be attached): ${e.message}")
            }
        }

        imported
    }

    // ==================== PACK REMOVAL ====================

    /**
     * Remove a GIF pack — deletes memberships and any orphaned gifs.
     * Returns [RemovePackResult] with IDs for thumbnail/full file cleanup.
     *
     * Multi-pack safe: only deletes gifs that have NO remaining memberships
     * from other packs. Full GIF files are only flagged for deletion if no
     * other pack claims them with has_full_gif=1.
     */
    suspend fun removePack(packId: String): RemovePackResult = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val orphanedIds = mutableListOf<Long>()
        val orphanedFullIds = mutableListOf<Long>()

        db.beginTransaction()
        try {
            // Find gif_ids that ONLY belong to this pack (orphaned after removal)
            val orphanCursor = db.rawQuery("""
                SELECT m.gif_id FROM gif_pack_membership m
                WHERE m.pack_id = ?
                  AND m.gif_id NOT IN (
                      SELECT gif_id FROM gif_pack_membership WHERE pack_id != ?
                  )
            """, arrayOf(packId, packId))
            orphanCursor.use {
                while (it.moveToNext()) orphanedIds.add(it.getLong(0))
            }

            // Find gif_ids where this pack provided full GIFs and no other pack does
            val fullOrphanCursor = db.rawQuery("""
                SELECT m.gif_id FROM gif_pack_membership m
                WHERE m.pack_id = ? AND m.has_full_gif = 1
                  AND m.gif_id NOT IN (
                      SELECT gif_id FROM gif_pack_membership
                      WHERE pack_id != ? AND has_full_gif = 1
                  )
            """, arrayOf(packId, packId))
            fullOrphanCursor.use {
                while (it.moveToNext()) orphanedFullIds.add(it.getLong(0))
            }

            // Delete memberships for this pack
            db.execSQL("DELETE FROM gif_pack_membership WHERE pack_id = ?", arrayOf(packId))

            // Delete orphaned gifs (no remaining memberships) and their category mappings
            if (orphanedIds.isNotEmpty()) {
                for (chunk in orphanedIds.chunked(500)) {
                    val ph = chunk.joinToString(",") { "?" }
                    val args = chunk.map { it.toString() }.toTypedArray()
                    db.execSQL("DELETE FROM gif_category_map WHERE gif_id IN ($ph)", args)
                    db.execSQL("DELETE FROM gif_usage WHERE gif_id IN ($ph)", args)
                    db.execSQL("DELETE FROM gifs WHERE gif_id IN ($ph)", args)
                }
            }

            // Rebuild FTS4 index
            db.execSQL("INSERT INTO gifs_fts(gifs_fts) VALUES('rebuild')")

            // Remove from installed_packs and packs lookup table
            db.execSQL("DELETE FROM installed_packs WHERE pack_id = ?", arrayOf(packId))
            db.execSQL("DELETE FROM packs WHERE name = ?", arrayOf(packId))

            db.setTransactionSuccessful()
            Log.i(TAG, "Removed pack '$packId': ${orphanedIds.size} orphaned gifs deleted, " +
                "${orphanedFullIds.size} full GIF files to clean")
        } finally {
            db.endTransaction()
        }

        try { db.execSQL("VACUUM") } catch (_: Exception) {}

        RemovePackResult(
            orphanedThumbIds = orphanedIds,
            orphanedFullIds = orphanedFullIds
        )
    }

    /**
     * Remove all GIF data — deletes the database file entirely.
     * Caller must also call GifAssetManager.clearAll() and release singletons.
     */
    fun removeAllData() {
        close()
        val dbFile = appContext.getDatabasePath(DATABASE_NAME)
        if (dbFile.exists()) {
            dbFile.delete()
            Log.i(TAG, "Deleted GIF database")
        }
        // Also delete journal/wal files
        File(dbFile.path + "-journal").delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    // ==================== PACK QUERIES ====================

    /**
     * Get list of installed packs.
     */
    fun getInstalledPacks(): List<InstalledPackInfo> {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<InstalledPackInfo>()
        val cursor = db.rawQuery(
            "SELECT pack_id, name, gif_count, installed_at, size_bytes FROM installed_packs ORDER BY installed_at DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    InstalledPackInfo(
                        packId = it.getString(0),
                        name = it.getString(1),
                        gifCount = it.getInt(2),
                        installedAt = it.getLong(3),
                        sizeBytes = it.getLong(4)
                    )
                )
            }
        }
        return results
    }

    /**
     * Check if a pack is already installed.
     */
    fun isPackInstalled(packId: String): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM installed_packs WHERE pack_id = ?",
            arrayOf(packId)
        )
        return cursor.use { it.moveToFirst() }
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Look up the integer pack_id for a string pack identifier.
     * The packs table maps string names → integer IDs used as FK in gifs table.
     */
    private fun getIntPackId(packId: String): Int? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT pack_id FROM packs WHERE name = ?",
            arrayOf(packId)
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    private fun getCategoriesForGif(gifId: Long): List<GifCategory> {
        val db = dbHelper.readableDatabase
        val categories = mutableListOf<GifCategory>()
        val cursor = db.rawQuery(
            "SELECT category_id FROM gif_category_map WHERE gif_id = ?",
            arrayOf(gifId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                GifCategory.fromId(it.getInt(0))?.let { cat -> categories.add(cat) }
            }
        }
        return categories
    }

    /**
     * Convert cursor row to Gif object.
     * V3 schema: includes search_text column.
     */
    private fun cursorToGif(cursor: Cursor): Gif {
        return Gif(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("gif_id")),
            width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
            height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
            durationMs = cursor.getInt(cursor.getColumnIndexOrThrow("duration_ms")),
            fileSize = cursor.getInt(cursor.getColumnIndexOrThrow("file_size")),
            packId = cursor.getInt(cursor.getColumnIndexOrThrow("pack_id")),
            searchText = cursor.getString(cursor.getColumnIndexOrThrow("search_text")) ?: ""
        )
    }

    private fun sanitizeFtsQuery(query: String): String {
        val sanitized = query
            .replace("\"", "")
            .replace("'", "")
            .replace("*", "")
            .replace("-", " ")
            .trim()
        return sanitized.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
    }

    fun close() {
        dbHelper.close()
    }

    companion object {
        private const val TAG = "GifDatabase"
        const val DATABASE_NAME = "gifs_v5.db"
        const val DATABASE_VERSION = 5

        @Volatile
        private var instance: GifDatabase? = null

        fun getInstance(context: Context): GifDatabase {
            return instance ?: synchronized(this) {
                instance ?: GifDatabase(context.applicationContext).also { instance = it }
            }
        }

        fun release() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

/** Installed pack metadata (from installed_packs table). */
data class InstalledPackInfo(
    val packId: String,
    val name: String,
    val gifCount: Int,
    val installedAt: Long,
    val sizeBytes: Long
)

/** Result of removing a pack — separated IDs for thumbnail vs full file cleanup. */
data class RemovePackResult(
    /** Gif IDs whose thumbnails should be deleted (no other pack references them). */
    val orphanedThumbIds: List<Long>,
    /** Gif IDs whose full animated files should be deleted (no other pack provides them). */
    val orphanedFullIds: List<Long>
)

/**
 * SQLiteOpenHelper for GIF database.
 * Creates V4 schema with content-synced FTS4 and installed_packs tracking.
 */
class GifDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    GifDatabase.DATABASE_NAME,
    null,
    GifDatabase.DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_CATEGORIES_TABLE)
        db.execSQL(CREATE_GIFS_TABLE)
        db.execSQL(CREATE_GIF_CATEGORY_MAP_TABLE)
        db.execSQL(CREATE_GIFS_FTS_TABLE)
        db.execSQL(CREATE_GIF_USAGE_TABLE)
        db.execSQL(CREATE_USAGE_INDEX)
        db.execSQL(CREATE_INSTALLED_PACKS_TABLE)
        db.execSQL(CREATE_PACKS_TABLE)
        db.execSQL(CREATE_GIF_PACK_MEMBERSHIP_TABLE)

        // Insert default categories
        for (category in GifCategory.entries) {
            if (category != GifCategory.RECENTLY_USED) {
                db.execSQL(
                    "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
                    arrayOf(category.id, category.displayName, category.icon, category.ordinal)
                )
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Since GIF data comes entirely from imported packs, a clean recreation is safe.
        db.execSQL("DROP TABLE IF EXISTS gif_pack_membership")
        db.execSQL("DROP TABLE IF EXISTS gifs_fts")
        db.execSQL("DROP TABLE IF EXISTS gif_category_map")
        db.execSQL("DROP TABLE IF EXISTS gif_usage")
        db.execSQL("DROP TABLE IF EXISTS gifs")
        db.execSQL("DROP TABLE IF EXISTS categories")
        db.execSQL("DROP TABLE IF EXISTS packs")
        db.execSQL("DROP TABLE IF EXISTS installed_packs")
        onCreate(db)
    }

    companion object {
        private const val CREATE_CATEGORIES_TABLE = """
            CREATE TABLE IF NOT EXISTS categories (
                category_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                icon TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )
        """

        // Packs lookup: maps string pack names → integer IDs used as FK in gifs
        private const val CREATE_PACKS_TABLE = """
            CREATE TABLE IF NOT EXISTS packs (
                pack_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                version INTEGER DEFAULT 1,
                gif_count INTEGER DEFAULT 0
            )
        """

        // V4: search_text column for content-synced FTS4
        private const val CREATE_GIFS_TABLE = """
            CREATE TABLE IF NOT EXISTS gifs (
                gif_id INTEGER PRIMARY KEY,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                duration_ms INTEGER DEFAULT 0,
                file_size INTEGER DEFAULT 0,
                pack_id INTEGER DEFAULT 0 REFERENCES packs(pack_id),
                search_text TEXT DEFAULT '',
                created_at INTEGER DEFAULT 0
            )
        """

        // WITHOUT ROWID: clustered by (category_id, gif_id) for fast category lookups
        private const val CREATE_GIF_CATEGORY_MAP_TABLE = """
            CREATE TABLE IF NOT EXISTS gif_category_map (
                category_id INTEGER NOT NULL,
                gif_id INTEGER NOT NULL,
                PRIMARY KEY (category_id, gif_id),
                FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE,
                FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE
            )
        """

        // V4: Content-synced FTS4 — universally available on all Android versions.
        // FTS5 is NOT reliably available in android.database.sqlite across OEM builds.
        private const val CREATE_GIFS_FTS_TABLE = """
            CREATE VIRTUAL TABLE IF NOT EXISTS gifs_fts USING fts4(
                search_text,
                content="gifs"
            )
        """

        private const val CREATE_GIF_USAGE_TABLE = """
            CREATE TABLE IF NOT EXISTS gif_usage (
                gif_id INTEGER PRIMARY KEY,
                use_count INTEGER DEFAULT 0,
                last_used INTEGER DEFAULT 0,
                FOREIGN KEY (gif_id) REFERENCES gifs(gif_id) ON DELETE CASCADE
            )
        """

        private const val CREATE_USAGE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_gif_usage ON gif_usage(last_used DESC)"

        // Tracks installed packs (string pack_id, not integer)
        private const val CREATE_INSTALLED_PACKS_TABLE = """
            CREATE TABLE IF NOT EXISTS installed_packs (
                pack_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                gif_count INTEGER DEFAULT 0,
                installed_at INTEGER DEFAULT 0,
                size_bytes INTEGER DEFAULT 0
            )
        """

        // V5: Multi-pack ownership — tracks which gif_ids belong to which packs.
        // A gif can be in multiple packs. On removal, only delete gifs with zero
        // remaining memberships so overlapping packs don't lose shared entries.
        // has_full_gif: 1 if this pack provided the full animated file (not just thumb)
        private const val CREATE_GIF_PACK_MEMBERSHIP_TABLE = """
            CREATE TABLE IF NOT EXISTS gif_pack_membership (
                gif_id INTEGER NOT NULL,
                pack_id TEXT NOT NULL,
                has_full_gif INTEGER DEFAULT 0,
                PRIMARY KEY (gif_id, pack_id)
            )
        """
    }
}
