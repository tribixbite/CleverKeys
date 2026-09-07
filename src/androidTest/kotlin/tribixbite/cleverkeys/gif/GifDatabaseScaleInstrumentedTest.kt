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
                    // Unique searchable token per gif plus a shared token for bulk hits.
                    // "eyera rolla" (two tokens, no "eyeraralla"-prefix token) feeds the
                    // E-3 compound-fallback pagination test below.
                    pack.execSQL(
                        "INSERT INTO gifs VALUES (?, 200, 200, 1000, 4096, ?, 0)",
                        arrayOf(id, "zebrapack token$i quagga eyera rolla")
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

    // ── Audit E-1 (P1): recordGifUsage must be a working upsert on EVERY minSdk ──
    // The old `INSERT ... ON CONFLICT DO UPDATE` was a parse-time SQLiteException on
    // API 24-28 (SQLite < 3.24) escaping an unhandled scope.launch — IME crash. The
    // portable two-statement form must both seed and increment.

    @Test
    fun recordGifUsageTwiceIncrementsUseCount() = runBlocking {
        database.recordGifUsage(baseId + 400)
        database.recordGifUsage(baseId + 400)

        val dbFile = targetContext.getDatabasePath(GifDatabase.DATABASE_NAME)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val cursor = db.rawQuery(
                "SELECT use_count FROM gif_usage WHERE gif_id = ?",
                arrayOf((baseId + 400).toString())
            )
            val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else -1 }
            assertEquals("two records must yield use_count == 2 (audit E-1)", 2, count)
        }
    }

    // ── Audit E-3: compound-word fallback must paginate ────────────────────

    @Test
    fun compoundFallbackServesPagesPastTheFirst() = runBlocking {
        // "eyerarolla" has NO raw token; the fallback splits to "eyera* rolla*",
        // which every synthetic row matches. Pre-fix the fallback ran only at
        // offset == 0, so page 2 of an advertised 5-page result set came back empty.
        val count = database.countSearchResults("eyerarolla")
        assertEquals("count must see the compound corpus", gifCount, count)

        val page2 = database.searchGifs("eyerarolla", limit = 100, offset = 100)
        assertEquals(
            "page 2 of a compound-fallback search must serve rows (audit E-3)",
            100, page2.size
        )
    }

    // ── Audit E-4: recently-used honors the offset ─────────────────────────

    @Test
    fun recentlyUsedPageTwoIsNotPageOneAgain() = runBlocking {
        // Seed 120 usage rows with DISTINCT last_used so ordering is total.
        val dbFile = targetContext.getDatabasePath(GifDatabase.DATABASE_NAME)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            for (i in 0 until 120) {
                db.execSQL(
                    "INSERT OR REPLACE INTO gif_usage (gif_id, use_count, last_used) VALUES (?, 1, ?)",
                    arrayOf(baseId + i, 1_000_000L + i)
                )
            }
        }
        val page1 = database.getRecentlyUsedGifs(100, 0).map { it.id }
        val page2 = database.getRecentlyUsedGifs(100, 100).map { it.id }
        assertEquals(100, page1.size)
        assertEquals("page 2 must hold the 20 remaining rows (audit E-4)", 20, page2.size)
        assertTrue(
            "page 2 must not repeat page 1 (offset was dropped pre-fix)",
            page1.toSet().intersect(page2.toSet()).isEmpty()
        )
    }

    // ── Audit E-8/E-9: overlapping re-import — honest count + gid backfill ──

    @Test
    fun overlappingImportReportsHonestCountAndBackfillsGid() = runBlocking {
        // Pack B: same gif_ids, but rows now carry a #149 gid: marker.
        val packBFile = File(targetContext.cacheDir, "scale_test_pack_b.db")
        if (packBFile.exists()) packBFile.delete()
        SQLiteDatabase.openOrCreateDatabase(packBFile, null).use { pack ->
            pack.execSQL(
                """CREATE TABLE gifs (
                    gif_id INTEGER PRIMARY KEY, width INTEGER, height INTEGER,
                    duration_ms INTEGER, file_size INTEGER,
                    search_text TEXT, created_at INTEGER
                )"""
            )
            pack.execSQL("CREATE TABLE categories (category_id INTEGER PRIMARY KEY, name TEXT, icon TEXT, sort_order INTEGER)")
            pack.execSQL("CREATE TABLE gif_category_map (category_id INTEGER, gif_id INTEGER)")
            pack.execSQL(
                "INSERT INTO gifs VALUES (?, 200, 200, 1000, 4096, ?, 0)",
                arrayOf(baseId, "zebrapack token0 quagga gid:TeStId01")
            )
        }
        try {
            val imported = database.importPack(
                packDbFile = packBFile,
                packId = "$PACK_ID-b",
                packName = "Scale Test Pack B",
                gifCount = 1,
                sizeBytes = packBFile.length(),
                hasFullGifs = false
            )
            // E-9: the single row is owned by pack A — OR IGNORE skips it, and the
            // return value must say so (pre-fix it reported the pack's row count, 1).
            assertEquals("fully-overlapping import must report 0 imported (audit E-9)", 0, imported)

            // E-8: the marker still lands — the owned row's search_text carried no gid:
            // and the incoming one does, so the backfill adopts it.
            assertEquals(
                "gid backfill must deliver the #149 marker over the legacy row (audit E-8)",
                "TeStId01", database.getGifById(baseId)?.getGiphyId()
            )
        } finally {
            database.removePack("$PACK_ID-b")
            packBFile.delete()
        }
    }

    companion object {
        private const val PACK_ID = "scale-test-pack"
    }
}
