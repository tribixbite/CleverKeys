package tribixbite.cleverkeys.gif

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for issue #152 — "Full GIF pack is unusably slow".
 *
 * The 130k-entry pack made every search keystroke take seconds. Diagnosis:
 * searchGifs()/getRecentlyUsedGifs() hydrated Gif.categories with one
 * `SELECT ... FROM gif_category_map WHERE gif_id = ?` per result row — and
 * gif_category_map's only index is its (category_id, gif_id) PK, so each of
 * those 100 lookups was a full scan of the map table: O(results × pack size)
 * per keystroke. Gif.categories is never read by any production code, so the
 * work was entirely dead. Additionally getCategoryCount had no ALL branch
 * (ALL.id = -1 matches no rows → browsing "All" was capped at page one).
 *
 * These tests import a small synthetic pack through the real importPack path
 * and pin the fixed contracts:
 * - search results arrive WITHOUT per-row category hydration
 * - gif_category_map has a gif_id index (for the remaining single-row lookup
 *   in getGifById)
 * - getCategoryCount(ALL) reflects the full pack so pagination can advance
 */
@RunWith(AndroidJUnit4::class)
class GifDatabaseScaleInstrumentedTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: GifDatabase
    private lateinit var packDbFile: File

    // High IDs to stay clear of any real imported pack on the device
    private val baseId = 900_000_000L
    private val gifCount = 500

    @Before
    fun setup() {
        database = GifDatabase.getInstance(targetContext)
        packDbFile = File(targetContext.cacheDir, "scale_test_pack.db")
        if (packDbFile.exists()) packDbFile.delete()
        buildSyntheticPack()
        runBlocking {
            val imported = database.importPack(
                packDbFile = packDbFile,
                packId = PACK_ID,
                packName = "Scale Test Pack",
                gifCount = gifCount,
                sizeBytes = packDbFile.length(),
                hasFullGifs = false
            )
            assertEquals("importPack must ingest the synthetic pack", gifCount, imported)
        }
    }

    @After
    fun teardown() {
        runBlocking { database.removePack(PACK_ID) }
        packDbFile.delete()
    }

    /** Build a pack.db with the schema importPack expects (gifs, categories, gif_category_map). */
    private fun buildSyntheticPack() {
        val db = SQLiteDatabase.openOrCreateDatabase(packDbFile, null)
        db.use { pack ->
            pack.execSQL(
                """CREATE TABLE gifs (
                    gif_id INTEGER PRIMARY KEY, width INTEGER, height INTEGER,
                    duration_ms INTEGER, file_size INTEGER,
                    search_text TEXT, created_at INTEGER
                )"""
            )
            pack.execSQL("CREATE TABLE categories (category_id INTEGER PRIMARY KEY, name TEXT, icon TEXT, sort_order INTEGER)")
            pack.execSQL("CREATE TABLE gif_category_map (category_id INTEGER, gif_id INTEGER)")
            pack.beginTransaction()
            try {
                for (i in 0 until gifCount) {
                    val id = baseId + i
                    // Unique searchable token per gif plus a shared token for bulk hits
                    pack.execSQL(
                        "INSERT INTO gifs VALUES (?, 200, 200, 1000, 4096, ?, 0)",
                        arrayOf(id, "zebrapack token$i quagga")
                    )
                    // Every gif belongs to a real category so pre-fix hydration
                    // would produce non-empty categories
                    pack.execSQL(
                        "INSERT INTO gif_category_map VALUES (?, ?)",
                        arrayOf(GifCategory.AMUSEMENT.id, id)
                    )
                }
                pack.setTransactionSuccessful()
            } finally {
                pack.endTransaction()
            }
        }
    }

    // ── The dead N+1 is gone ───────────────────────────────────────────────

    @Test
    fun searchResultsAreNotCategoryHydrated() = runBlocking {
        val results = database.searchGifs("zebrapack", limit = 100)
        assertTrue("search must find the synthetic pack", results.isNotEmpty())
        // Contract: searchGifs returns rows straight from FTS without the
        // per-row gif_category_map lookup that made #152 O(results × pack).
        // Gif.categories is a write-only field in production; list views must
        // not pay for it.
        for (gif in results) {
            assertEquals(
                "searchGifs must not hydrate categories (dead N+1 from #152)",
                emptyList<GifCategory>(), gif.categories
            )
        }
    }

    @Test
    fun recentlyUsedIsNotCategoryHydrated() = runBlocking {
        database.recordGifUsage(baseId)
        val recent = database.getRecentlyUsedGifs(50)
        val mine = recent.filter { it.id == baseId }
        assertEquals("recorded gif must appear in recently used", 1, mine.size)
        assertEquals(
            "getRecentlyUsedGifs must not hydrate categories (dead N+1 from #152)",
            emptyList<GifCategory>(), mine[0].categories
        )
    }

    // ── Index for the remaining single-row lookup ──────────────────────────

    @Test
    fun gifCategoryMapHasGifIdIndex() {
        // getGifById still resolves categories for one row; without an index on
        // gif_category_map(gif_id) that lookup is a full scan of the map table
        // (the PK starts with category_id, unusable for gif_id probes).
        val dbFile = targetContext.getDatabasePath(GifDatabase.DATABASE_NAME)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='gif_category_map' AND sql LIKE '%gif_id%'",
                null
            )
            val found = cursor.use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
            assertTrue(
                "gif_category_map needs an index covering gif_id, found: $found",
                found.isNotEmpty()
            )
        }
    }

    // ── ALL-category count (pagination cap bug found during #152 triage) ───

    @Test
    fun allCategoryCountCoversWholePack() = runBlocking {
        val count = database.getCategoryCount(GifCategory.ALL)
        assertTrue(
            "getCategoryCount(ALL) must reflect the full corpus (got $count, " +
                "imported $gifCount) — ALL.id=-1 matches no gif_category_map rows",
            count >= gifCount
        )
    }

    @Test
    fun singleGifLookupStillResolvesCategories() = runBlocking {
        // The one legitimate consumer of category resolution keeps working
        val gif = database.getGifById(baseId)
        assertEquals(listOf(GifCategory.AMUSEMENT), gif?.categories)
    }

    companion object {
        private const val PACK_ID = "scale-test-pack"
    }
}
