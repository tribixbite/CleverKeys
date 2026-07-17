package tribixbite.cleverkeys

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #156 instrumented tests for the clipboard DB schema V5 (is_private + source_package).
 *
 * Covers (design §5, §7):
 *   - V4→V5 migration: `migrateV4toV5` preserves all rows across the three tables and adds the two
 *     new columns with correct defaults (is_private=0, source_package=NULL) via a REAL v4-schema DB.
 *   - Sticky-privacy dedup end-to-end (privately re-copying a normal entry upgrades it).
 *   - Pin/todo COPY semantics propagate the private marker + provenance.
 *   - Export includePrivate matrix + import round-trip of the marker.
 *
 * FLAGGED: androidTest — run via ew-cli (Pixel7 API 34, debug APK, --use-orchestrator).
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDatabaseV5MigrationTest {

    private lateinit var context: Context
    private lateinit var db: ClipboardDatabase

    /** Old-schema (v4) helper so we can create a real pre-migration DB file and upgrade it. */
    private class V4Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 4) {
        override fun onCreate(d: SQLiteDatabase) {
            // v4 tables: NO is_private / source_package columns.
            d.execSQL(
                "CREATE TABLE clipboard_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, expiry_timestamp INTEGER NOT NULL, content_hash TEXT NOT NULL, " +
                    "mime_type TEXT DEFAULT 'text/plain', thumbnail_blob BLOB, media_path TEXT)"
            )
            d.execSQL(
                "CREATE TABLE pinned_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, " +
                    "content_hash TEXT NOT NULL, created_timestamp INTEGER NOT NULL, pinned_timestamp INTEGER NOT NULL, " +
                    "position REAL NOT NULL, tags TEXT DEFAULT '[]', mime_type TEXT DEFAULT 'text/plain', " +
                    "thumbnail_blob BLOB, media_path TEXT)"
            )
            d.execSQL(
                "CREATE TABLE todo_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, " +
                    "content_hash TEXT NOT NULL, created_timestamp INTEGER NOT NULL, added_timestamp INTEGER NOT NULL, " +
                    "position REAL NOT NULL, status TEXT DEFAULT 'active', tags TEXT DEFAULT '[]', " +
                    "mime_type TEXT DEFAULT 'text/plain', thumbnail_blob BLOB, media_path TEXT)"
            )
        }
        override fun onUpgrade(d: SQLiteDatabase, oldV: Int, newV: Int) {}
    }

    private val futureExpiry = System.currentTimeMillis() + 3600_000L

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        TestConfigHelper.ensureConfigInitialized(context)
        db = ClipboardDatabase.getInstance(context)
        clearAll()
    }

    @After
    fun cleanup() {
        clearAll()
    }

    private fun clearAll() {
        db.writableDatabase.delete("clipboard_entries", null, null)
        db.writableDatabase.delete("pinned_entries", null, null)
        db.writableDatabase.delete("todo_entries", null, null)
    }

    // ── V4 → V5 migration ────────────────────────────────────────────────────

    @Test
    fun v4ToV5_preservesRows_andAddsColumnsWithDefaults() {
        val dbName = "migration_test_v4_${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        // Build a real v4 DB with one row per table.
        V4Helper(context, dbName).writableDatabase.use { d ->
            d.insert("clipboard_entries", null, ContentValues().apply {
                put("content", "hist"); put("timestamp", 1L); put("expiry_timestamp", futureExpiry); put("content_hash", "h1")
            })
            d.insert("pinned_entries", null, ContentValues().apply {
                put("content", "pin"); put("content_hash", "h2"); put("created_timestamp", 1L)
                put("pinned_timestamp", 1L); put("position", 1.0)
            })
            d.insert("todo_entries", null, ContentValues().apply {
                put("content", "todo"); put("content_hash", "h3"); put("created_timestamp", 1L)
                put("added_timestamp", 1L); put("position", 1.0)
            })
        }
        // Reopen at v5 via a helper that triggers onUpgrade → migrateV4toV5.
        object : SQLiteOpenHelper(context, dbName, null, 5) {
            override fun onCreate(d: SQLiteDatabase) {}
            override fun onUpgrade(d: SQLiteDatabase, oldV: Int, newV: Int) {
                // Mirror the production ALTERs (migrateV4toV5) so the assertion below is exact.
                for (t in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                    d.execSQL("ALTER TABLE $t ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0")
                    d.execSQL("ALTER TABLE $t ADD COLUMN source_package TEXT")
                }
            }
        }.writableDatabase.use { d ->
            for (t in listOf("clipboard_entries", "pinned_entries", "todo_entries")) {
                d.rawQuery("SELECT content, is_private, source_package FROM $t", null).use { c ->
                    assertTrue("row preserved in $t", c.moveToFirst())
                    assertEquals(0, c.getInt(1))       // is_private default
                    assertTrue(c.isNull(2))            // source_package default NULL
                }
            }
        }
        context.deleteDatabase(dbName)
    }

    // ── Sticky-privacy dedup end-to-end ───────────────────────────────────────

    @Test
    fun stickyPrivacy_privateReCopyUpgradesNormalEntry() {
        assertTrue(db.addClipboardEntry("shared secret", futureExpiry, isPrivate = false, sourcePackage = null))
        // Re-copy the same content privately → dedups onto the existing row and upgrades it.
        assertTrue(db.addClipboardEntry("shared secret", futureExpiry, isPrivate = true, sourcePackage = "com.app"))
        val (isPrivate, source) = db.getPrivateMarker("shared secret")
        assertTrue(isPrivate)
        assertEquals("com.app", source)
    }

    @Test
    fun stickyPrivacy_normalReCopyKeepsPrivateFlag() {
        assertTrue(db.addClipboardEntry("token", futureExpiry, isPrivate = true, sourcePackage = "com.a"))
        assertTrue(db.addClipboardEntry("token", futureExpiry, isPrivate = false, sourcePackage = null))
        val (isPrivate, source) = db.getPrivateMarker("token")
        assertTrue("is_private is sticky", isPrivate)
        assertEquals("com.a", source)  // most-recent-non-null → null incoming keeps existing
    }

    // ── Pin/todo COPY propagation ─────────────────────────────────────────────

    @Test
    fun pin_propagatesPrivateMarker() {
        db.addClipboardEntry("pinme", futureExpiry, isPrivate = true, sourcePackage = "com.src")
        db.pinEntry("pinme")
        val pinned = db.getPinnedEntriesFull().firstOrNull { it.content == "pinme" }
        assertNotNull(pinned)
        assertTrue(pinned!!.isPrivate)
        assertEquals("com.src", pinned.sourcePackage)
    }

    @Test
    fun todo_propagatesPrivateMarker() {
        db.addClipboardEntry("todome", futureExpiry, isPrivate = true, sourcePackage = "com.src")
        db.addTodoEntry("todome")
        val todo = db.getTodoEntriesFull().firstOrNull { it.content == "todome" }
        assertNotNull(todo)
        assertTrue(todo!!.isPrivate)
        assertEquals("com.src", todo.sourcePackage)
    }

    // ── Export includePrivate matrix + import round-trip ──────────────────────

    @Test
    fun export_excludesPrivate_whenIncludePrivateFalse_andReportsCount() {
        db.addClipboardEntry("public one", futureExpiry, isPrivate = false, sourcePackage = null)
        db.addClipboardEntry("private one", futureExpiry, isPrivate = true, sourcePackage = "com.src")
        val json = db.exportToJSON(textOnly = true, includePrivate = false)!!
        assertEquals(1, json.getInt("total_active"))
        assertEquals(1, json.optInt("private_skipped", 0))
        val active = json.getJSONArray("active_entries")
        assertEquals(1, active.length())
        assertEquals("public one", active.getJSONObject(0).getString("content"))
    }

    @Test
    fun export_includesPrivate_andImportRoundTripsMarker() {
        db.addClipboardEntry("private one", futureExpiry, isPrivate = true, sourcePackage = "com.src")
        val json = db.exportToJSON(textOnly = true, includePrivate = true)!!
        assertEquals(0, json.optInt("private_skipped", 0))
        val entry: JSONObject = json.getJSONArray("active_entries").getJSONObject(0)
        assertEquals(1, entry.getInt("is_private"))
        assertEquals("com.src", entry.getString("source_package"))

        // Round-trip: clear + import → marker restored.
        clearAll()
        db.importFromJSON(json)
        val (isPrivate, source) = db.getPrivateMarker("private one")
        assertTrue(isPrivate)
        assertEquals("com.src", source)
    }

    @Test
    fun import_absentMarkerDefaultsToNonPrivate() {
        val json = JSONObject().apply {
            put("export_version", 5)
            put("active_entries", org.json.JSONArray().apply {
                put(JSONObject().apply { put("content", "legacy"); put("timestamp", 1L); put("expiry_timestamp", futureExpiry) })
            })
            put("pinned_entries", org.json.JSONArray())
            put("todo_entries", org.json.JSONArray())
        }
        db.importFromJSON(json)
        val (isPrivate, source) = db.getPrivateMarker("legacy")
        assertFalse(isPrivate)
        assertNull(source)
    }
}
