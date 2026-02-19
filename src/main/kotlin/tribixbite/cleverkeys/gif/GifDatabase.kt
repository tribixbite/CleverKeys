package tribixbite.cleverkeys.gif

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * SQLite database handler for offline GIF storage with FTS5 full-text search.
 *
 * The database is pre-built by build_database.py and downloaded as gzipped DB
 * on first GIF panel enable. On-device it's decompressed to app's database dir.
 *
 * V2 Schema (optimized — no asset_name/thumbnail_name columns):
 * - categories: Emotion category definitions
 * - packs: Pack lookup table (normalizes pack_id strings to integers)
 * - gifs: GIF metadata (dimensions, pack_id, is_available flag)
 * - gif_category_map: WITHOUT ROWID, clustered by (category_id, gif_id)
 * - gifs_fts: Contentless FTS5 (content='', columnsize=0)
 * - gif_usage: Usage tracking for "recently used" feature (created on-device)
 *
 * Asset filenames derived at runtime: String.format("%06d.webp", gif_id)
 */
class GifDatabase private constructor(context: Context) {

    private val dbHelper: GifDatabaseHelper
    private val db: SQLiteDatabase

    init {
        copyDatabaseIfNeeded(context)
        dbHelper = GifDatabaseHelper(context)
        db = dbHelper.readableDatabase
    }

    /**
     * Search GIFs using FTS5 full-text search.
     * Uses contentless FTS5 — joins back to gifs table via rowid.
     * Only returns available GIFs (is_available = 1).
     * @param query Search query (supports prefix matching)
     * @param limit Maximum results to return
     */
    suspend fun searchGifs(query: String, limit: Int = 100): List<Gif> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val sanitizedQuery = sanitizeFtsQuery(query)
        val results = mutableListOf<Gif>()

        // Contentless FTS5: rowid matches gif_id, join back for metadata
        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.is_available
            FROM gifs_fts fts
            JOIN gifs g ON g.gif_id = fts.rowid
            WHERE fts.search_text MATCH ?
              AND g.is_available = 1
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            arrayOf(sanitizedQuery, limit.toString())
        )

        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToGif(it))
            }
        }

        // Load categories for each result
        results.map { gif ->
            gif.copy(categories = getCategoriesForGif(gif.id))
        }
    }

    /**
     * Get all available GIFs in a specific category.
     * @param category The category to filter by
     * @param limit Maximum results
     */
    suspend fun getGifsByCategory(category: GifCategory, limit: Int = 200): List<Gif> =
        withContext(Dispatchers.IO) {
            if (category == GifCategory.RECENTLY_USED) {
                return@withContext getRecentlyUsedGifs(limit)
            }

            val results = mutableListOf<Gif>()

            val cursor = db.rawQuery(
                """
                SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                       g.pack_id, g.is_available
                FROM gifs g
                JOIN gif_category_map gcm ON g.gif_id = gcm.gif_id
                WHERE gcm.category_id = ?
                  AND g.is_available = 1
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

        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.is_available
            FROM gifs g
            JOIN gif_usage u ON g.gif_id = u.gif_id
            WHERE u.use_count > 0
              AND g.is_available = 1
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
     * Writes to the writable database for gif_usage.
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
        val cursor = db.rawQuery(
            """
            SELECT g.gif_id, g.width, g.height, g.duration_ms, g.file_size,
                   g.pack_id, g.is_available
            FROM gifs g
            WHERE g.gif_id = ?
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
     * Get total count of available GIFs.
     */
    fun getTotalGifCount(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM gifs WHERE is_available = 1", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Get count of available GIFs in a category.
     */
    fun getCategoryCount(category: GifCategory): Int {
        if (category == GifCategory.RECENTLY_USED) {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM gif_usage WHERE use_count > 0",
                null
            )
            return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }

        val cursor = db.rawQuery(
            """
            SELECT COUNT(*) FROM gif_category_map gcm
            JOIN gifs g ON g.gif_id = gcm.gif_id
            WHERE gcm.category_id = ? AND g.is_available = 1
            """.trimIndent(),
            arrayOf(category.id.toString())
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Mark GIFs as available/unavailable by pack_id.
     * Called after downloading or deleting a pack.
     */
    suspend fun setPackAvailability(packId: Int, available: Boolean) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.execSQL(
            "UPDATE gifs SET is_available = ? WHERE pack_id = ?",
            arrayOf(if (available) 1 else 0, packId)
        )
    }

    /**
     * Get all pack IDs and their GIF counts.
     */
    fun getPackInfo(): List<DbPackInfo> {
        val results = mutableListOf<DbPackInfo>()
        val cursor = db.rawQuery(
            "SELECT pack_id, name, gif_count FROM packs ORDER BY pack_id",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    DbPackInfo(
                        id = it.getInt(0),
                        name = it.getString(1),
                        gifCount = it.getInt(2)
                    )
                )
            }
        }
        return results
    }

    /**
     * Get categories for a specific GIF.
     */
    private fun getCategoriesForGif(gifId: Long): List<GifCategory> {
        val categories = mutableListOf<GifCategory>()

        val cursor = db.rawQuery(
            "SELECT category_id FROM gif_category_map WHERE gif_id = ?",
            arrayOf(gifId.toString())
        )

        cursor.use {
            while (it.moveToNext()) {
                val catId = it.getInt(0)
                GifCategory.fromId(catId)?.let { cat -> categories.add(cat) }
            }
        }

        return categories
    }

    /**
     * Convert cursor row to Gif object.
     * V2 schema: no asset_name/thumbnail_name — derived from gif_id.
     */
    private fun cursorToGif(cursor: Cursor): Gif {
        return Gif(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("gif_id")),
            width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
            height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
            durationMs = cursor.getInt(cursor.getColumnIndexOrThrow("duration_ms")),
            fileSize = cursor.getInt(cursor.getColumnIndexOrThrow("file_size")),
            packId = cursor.getInt(cursor.getColumnIndexOrThrow("pack_id")),
            isAvailable = cursor.getInt(cursor.getColumnIndexOrThrow("is_available")) == 1
        )
    }

    /**
     * Sanitize query for FTS5 to prevent injection and handle special chars.
     */
    private fun sanitizeFtsQuery(query: String): String {
        val sanitized = query
            .replace("\"", "")
            .replace("'", "")
            .replace("*", "")
            .replace("-", " ")
            .trim()

        // Prefix matching for partial words
        return sanitized.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
    }

    /**
     * Copy pre-built database from assets or downloaded gzipped file.
     * Supports both gifs_v2.db.gz (gzipped) and gifs_v2.db (raw).
     */
    private fun copyDatabaseIfNeeded(context: Context) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)

        if (dbFile.exists()) {
            // TODO: Check version and re-copy if DB asset is newer
            return
        }

        dbFile.parentFile?.mkdirs()

        // Try gzipped version first (smaller download)
        val gzFile = File(context.filesDir, "gif_packs/$DATABASE_NAME.gz")
        if (gzFile.exists()) {
            try {
                GZIPInputStream(gzFile.inputStream().buffered()).use { gzIn ->
                    FileOutputStream(dbFile).use { out ->
                        gzIn.copyTo(out)
                    }
                }
                Log.i(TAG, "Decompressed GIF database from ${gzFile.name}")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decompress GIF database", e)
                dbFile.delete()
            }
        }

        // Try raw DB in assets
        try {
            context.assets.open(DATABASE_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied GIF database from assets")
        } catch (e: Exception) {
            // Database not bundled yet — will be created empty by helper
            Log.w(TAG, "GIF database not found in assets, using empty database")
        }
    }

    fun close() {
        dbHelper.close()
    }

    companion object {
        private const val TAG = "GifDatabase"
        const val DATABASE_NAME = "gifs_v2.db"
        const val DATABASE_VERSION = 2

        @Volatile
        private var instance: GifDatabase? = null

        /**
         * Get singleton instance of GifDatabase.
         * Only call when gif_enabled is true in Config.
         */
        fun getInstance(context: Context): GifDatabase {
            return instance ?: synchronized(this) {
                instance ?: GifDatabase(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Release singleton instance (call when GIF panel is disabled).
         */
        fun release() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

/** Pack metadata from database (lightweight — for DB queries). */
data class DbPackInfo(
    val id: Int,
    val name: String,
    val gifCount: Int
)

/**
 * SQLiteOpenHelper for GIF database.
 * Creates v2 schema if database doesn't exist (fallback for empty DB).
 */
class GifDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    GifDatabase.DATABASE_NAME,
    null,
    GifDatabase.DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Create v2 schema (empty DB fallback — normally pre-built DB is copied)
        db.execSQL(CREATE_CATEGORIES_TABLE)
        db.execSQL(CREATE_PACKS_TABLE)
        db.execSQL(CREATE_GIFS_TABLE)
        db.execSQL(CREATE_GIF_CATEGORY_MAP_TABLE)
        db.execSQL(CREATE_GIFS_FTS_TABLE)
        db.execSQL(CREATE_GIF_USAGE_TABLE)
        db.execSQL(CREATE_USAGE_INDEX)

        // Insert default categories
        for (category in GifCategory.entries) {
            if (category != GifCategory.RECENTLY_USED) {
                db.execSQL(
                    "INSERT INTO categories (category_id, name, icon, sort_order) VALUES (?, ?, ?, ?)",
                    arrayOf(category.id, category.displayName, category.icon, category.ordinal)
                )
            }
        }

        // Insert default pack
        db.execSQL("INSERT INTO packs (pack_id, name) VALUES (0, 'default')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // V1 → V2: Drop old tables and recreate (pre-built DB is re-downloaded)
            db.execSQL("DROP TABLE IF EXISTS gifs_fts")
            db.execSQL("DROP TABLE IF EXISTS gif_category_map")
            db.execSQL("DROP TABLE IF EXISTS gif_usage")
            db.execSQL("DROP TABLE IF EXISTS gifs")
            db.execSQL("DROP TABLE IF EXISTS categories")
            db.execSQL("DROP TABLE IF EXISTS packs")
            onCreate(db)
        }
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

        private const val CREATE_PACKS_TABLE = """
            CREATE TABLE IF NOT EXISTS packs (
                pack_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                version INTEGER DEFAULT 1,
                gif_count INTEGER DEFAULT 0
            )
        """

        // V2: No asset_name/thumbnail_name — derive from gif_id as "%06d.webp"
        private const val CREATE_GIFS_TABLE = """
            CREATE TABLE IF NOT EXISTS gifs (
                gif_id INTEGER PRIMARY KEY,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                duration_ms INTEGER DEFAULT 0,
                file_size INTEGER DEFAULT 0,
                pack_id INTEGER DEFAULT 0 REFERENCES packs(pack_id),
                is_available INTEGER DEFAULT 1,
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

        // Contentless FTS5 — smallest possible search index
        private const val CREATE_GIFS_FTS_TABLE = """
            CREATE VIRTUAL TABLE IF NOT EXISTS gifs_fts USING fts5(
                search_text,
                content='',
                columnsize=0
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
    }
}
