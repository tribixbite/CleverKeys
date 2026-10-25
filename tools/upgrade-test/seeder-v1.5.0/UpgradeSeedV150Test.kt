package tribixbite.cleverkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.prefs.LayoutsPreference
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 1 of the real 1.x → 2.0 over-install test: populate a REAL v1.5.0 install
 * through v1.5.0's own code paths, then write a read-only dump of what v1.5.0
 * stored to `filesDir/upgrade-test/seed_dump.json` for the 2.0 verifier.
 *
 * NOT part of any suite. This file lives in the 2.0 repo under
 * `tools/upgrade-test/seeder-v1.5.0/` and is copied into a `git worktree` of the
 * v1.5.0 tag by `tools/upgrade-test/run-upgrade-test.sh`; it compiles only there.
 * The orchestrator APK runs it with `am instrument` (see the recipe in
 * `.claude/skills/ew-cli-testing.md`).
 *
 * Every write goes through a 1.x API (ClipboardHistoryService.addClip, the
 * ClipboardDatabase pin/todo/tag/import methods, ClipboardMediaManager,
 * CustomDictionarySource, LayoutsPreference, ShortSwipeCustomizationManager)
 * or, for plain settings, the SharedPreferences file and value types the 1.x
 * settings screen writes. No raw SQL writes.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeSeedV150Test {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun seedV150() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        // The IME service may already have initialised Config in this process;
        // otherwise initialise it from the REAL preferences (as the IME would).
        try {
            Config.globalConfig()
        } catch (e: NullPointerException) {
            Config.initGlobalConfig(prefs, context.resources, null, null)
        }

        val db = ClipboardDatabase.getInstance(context)
        val service = ClipboardHistoryService.get_service(context)
        assertNotNull("1.x ClipboardHistoryService unavailable", service)
        val ttlMs = ClipboardHistoryService.getHistoryTtlMs()
        val expiry = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ttlMs

        // ── History through the real addClip path (size cap, sanitizer, dedupe) ──
        // addClip may show a Toast, so it must run on the main thread.
        instrumentation.runOnMainSync {
            service!!.addClip(UpgradeFixture.HISTORY_PLAIN)
            service.addClip(UpgradeFixture.HISTORY_DUP)
            service.addClip(UpgradeFixture.HISTORY_UNICODE)
            service.addClip(UpgradeFixture.HISTORY_DUP) // duplicate → moved to top, not a 2nd row
            service.addClip(UpgradeFixture.LARGE_TEXT)
            service.addClip(UpgradeFixture.OVERSIZE_TEXT) // over the 512 KB cap → refused
        }

        // ── Old-dated history / pinned / to-do through the 1.x restore path ──
        val importJson = JSONObject()
            .put("export_version", 4)
            .put(
                "active_entries",
                JSONArray().put(
                    JSONObject().put("content", UpgradeFixture.HISTORY_OLD)
                        .put("timestamp", UpgradeFixture.HISTORY_OLD_TS)
                        .put("expiry_timestamp", expiry)
                )
            )
            .put(
                "pinned_entries",
                JSONArray().put(
                    JSONObject().put("content", UpgradeFixture.PINNED_OLD)
                        .put("created_timestamp", UpgradeFixture.PINNED_OLD_CREATED_TS)
                        .put("pinned_timestamp", UpgradeFixture.PINNED_OLD_PINNED_TS)
                        .put("timestamp", UpgradeFixture.PINNED_OLD_PINNED_TS)
                        .put("position", 0.5)
                        .put("tags", JSONArray(UpgradeFixture.PINNED_OLD_TAGS).toString())
                )
            )
            .put(
                "todo_entries",
                JSONArray().put(
                    JSONObject().put("content", UpgradeFixture.TODO_OLD)
                        .put("created_timestamp", UpgradeFixture.TODO_OLD_CREATED_TS)
                        .put("added_timestamp", UpgradeFixture.TODO_OLD_ADDED_TS)
                        .put("timestamp", UpgradeFixture.TODO_OLD_ADDED_TS)
                        .put("position", 0.5)
                        .put("status", UpgradeFixture.STATUS_PLANNED)
                        .put("tags", "[]")
                )
            )
        val imported = db.importFromJSON(importJson)
        assertEquals("1.x import added [history, pinned, todo]", listOf(1, 1, 1), imported.take(3))

        // ── Pinned + tags (COPY semantics: HISTORY_PLAIN lives in both tables) ──
        assertTrue(db.pinEntry(UpgradeFixture.PINNED_PLAIN))
        assertTrue(db.pinEntry(UpgradeFixture.HISTORY_PLAIN))
        assertTrue(db.setPinnedEntryTags(UpgradeFixture.PINNED_PLAIN, UpgradeFixture.PINNED_PLAIN_TAGS))
        assertTrue(db.setPinnedEntryTags(UpgradeFixture.HISTORY_PLAIN, UpgradeFixture.PINNED_COPY_TAGS))

        // ── To-dos in each status ──
        assertTrue(db.addTodoEntry(UpgradeFixture.TODO_ACTIVE))
        assertTrue(db.addTodoEntry(UpgradeFixture.TODO_PLANNED))
        assertTrue(db.addTodoEntry(UpgradeFixture.TODO_COMPLETED))
        assertTrue(db.setTodoEntryStatus(UpgradeFixture.TODO_PLANNED, UpgradeFixture.STATUS_PLANNED))
        assertTrue(db.setTodoEntryStatus(UpgradeFixture.TODO_COMPLETED, UpgradeFixture.STATUS_COMPLETED))
        assertTrue(db.setTodoEntryTags(UpgradeFixture.TODO_ACTIVE, UpgradeFixture.TODO_ACTIVE_TAGS))
        assertTrue(db.setTodoEntryTags(UpgradeFixture.TODO_PLANNED, UpgradeFixture.TODO_PLANNED_TAGS))

        // ── Media: real PNGs through ClipboardMediaManager, into history (large one also pinned) ──
        val mediaManager = ClipboardMediaManager(context)
        fun seedImage(name: String, w: Int, h: Int): ClipboardMediaManager.MediaSaveResult {
            val png = File(context.cacheDir, name)
            writeTestPng(png, w, h)
            val media = mediaManager.saveMedia(Uri.fromFile(png), UpgradeFixture.MEDIA_MIME)
            assertNotNull("1.x saveMedia failed for $name", media)
            assertTrue(
                db.addMediaClipboardEntry(
                    content = media!!.displayName,
                    expiryTimestamp = expiry,
                    mimeType = media.mimeType,
                    thumbnailBlob = media.thumbnailBlob,
                    mediaPath = media.mediaPath,
                    contentHash = media.contentHash,
                )
            )
            png.delete()
            return media
        }
        val large = seedImage(UpgradeFixture.MEDIA_LARGE_NAME, UpgradeFixture.MEDIA_LARGE_WIDTH, UpgradeFixture.MEDIA_LARGE_HEIGHT)
        val small = seedImage(UpgradeFixture.MEDIA_SMALL_NAME, UpgradeFixture.MEDIA_SMALL_WIDTH, UpgradeFixture.MEDIA_SMALL_HEIGHT)
        assertNotNull("1.x made no thumbnail for the 200x150 image", large.thumbnailBlob)
        assertTrue(
            db.pinEntry(
                large.displayName,
                mimeType = large.mimeType,
                thumbnailBlob = large.thumbnailBlob,
                mediaPath = large.mediaPath,
            )
        )

        // ── Custom dictionary words ──
        val dict = CustomDictionarySource(prefs, UpgradeFixture.DICT_LANG)
        runBlocking { UpgradeFixture.CUSTOM_WORDS.forEach { (w, f) -> dict.addWord(w, f) } }

        // ── Custom layout ──
        val custom = LayoutsPreference.CustomLayout.parse(UpgradeFixture.CUSTOM_LAYOUT_XML)
        assertNotNull("1.x could not parse the custom layout", custom.parsed)
        val layoutEditor = prefs.edit()
        LayoutsPreference.saveToPreferences(
            layoutEditor,
            listOf(LayoutsPreference.NamedLayout(UpgradeFixture.NAMED_LAYOUT), custom)
        )
        assertTrue(layoutEditor.commit())

        // ── Short-swipe mapping ──
        val shortSwipe = ShortSwipeCustomizationManager.getInstance(context)
        runBlocking {
            shortSwipe.loadMappings()
            shortSwipe.setMapping(
                ShortSwipeMapping.textInput(
                    UpgradeFixture.SHORT_SWIPE_KEY,
                    SwipeDirection.valueOf(UpgradeFixture.SHORT_SWIPE_DIRECTION),
                    UpgradeFixture.SHORT_SWIPE_DISPLAY,
                    UpgradeFixture.SHORT_SWIPE_TEXT,
                )
            )
        }

        // ── Non-default settings, same keys and types as the 1.x settings screen ──
        assertTrue(
            prefs.edit()
                .putString("theme", UpgradeFixture.THEME)
                .putFloat("character_size", UpgradeFixture.CHARACTER_SIZE)
                .putInt("vibrate_duration", UpgradeFixture.VIBRATE_DURATION)
                .putInt("key_opacity", UpgradeFixture.KEY_OPACITY)
                .putInt("longpress_timeout", UpgradeFixture.LONGPRESS_TIMEOUT)
                .putFloat("swipe_trail_width", UpgradeFixture.SWIPE_TRAIL_WIDTH)
                .commit()
        )
        // An empty commit() flushes every earlier apply() (CustomDictionarySource uses
        // apply) before the orchestrator kills this process.
        assertTrue(prefs.edit().commit())

        // ── 1.x read-back sanity: the seed really landed ──
        val history = db.getActiveClipboardEntries()
        val historyContents = history.map { it.content }
        for (c in listOf(
            UpgradeFixture.HISTORY_PLAIN, UpgradeFixture.HISTORY_DUP, UpgradeFixture.HISTORY_UNICODE,
            UpgradeFixture.LARGE_TEXT, UpgradeFixture.HISTORY_OLD, large.displayName, small.displayName,
        )) assertTrue("1.x history lacks ${c.take(30)}", c in historyContents)
        assertEquals("dup deduped", 1, historyContents.count { it == UpgradeFixture.HISTORY_DUP })
        assertFalse("oversize refused", UpgradeFixture.OVERSIZE_TEXT in historyContents)
        assertEquals(4, db.getPinnedEntriesFull().size)
        assertEquals(4, db.getTodoEntriesFull().size)

        // ── Read-only dump for the 2.0 verifier ──
        val dpContext = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
        val shortSwipeFile = File(dpContext.filesDir, "short_swipe_customizations.json")
        assertTrue("short-swipe file missing", shortSwipeFile.exists())
        val mediaFiles = JSONObject()
        for (m in listOf(large, small)) mediaFiles.put(m.mediaPath, UpgradeDump.sha256(File(context.filesDir, m.mediaPath)))
        val dump = JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("app_version_code", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("seeded_at", System.currentTimeMillis())
            .put("clipboard", UpgradeDump.dumpClipboard(db.readableDatabase))
            .put("schema", UpgradeDump.schema(db.readableDatabase))
            .put("prefs", UpgradeDump.dumpPrefs(prefs))
            .put("short_swipe_file", shortSwipeFile.readText())
            .put("media_files", mediaFiles)
            .put(
                "media",
                JSONObject()
                    .put("large", JSONObject().put("name", large.displayName).put("path", large.mediaPath)
                        .put("thumbnail", large.thumbnailBlob != null))
                    .put("small", JSONObject().put("name", small.displayName).put("path", small.mediaPath)
                        .put("thumbnail", small.thumbnailBlob != null))
            )
        val dir = File(context.filesDir, UpgradeFixture.WORK_DIR).apply { mkdirs() }
        File(dir, UpgradeFixture.SEED_DUMP).writeText(dump.toString())
        Log.i(
            UpgradeFixture.LOG_TAG,
            "SEED_DONE version=${BuildConfig.VERSION_NAME} history=${history.size} " +
                "pinned=${db.getPinnedEntriesFull().size} todo=${db.getTodoEntriesFull().size} " +
                "schema_version=${db.readableDatabase.version}"
        )
    }

    /** A non-trivial PNG (solid fill, gradient row, text) so thumbnails are real decodes. */
    private fun writeTestPng(file: File, width: Int, height: Int) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(30, 120, 200))
        for (x in 0 until bmp.width) {
            bmp.setPixel(x, 0, Color.rgb((x * 4) % 256, 255 - (x * 4) % 256, 128))
        }
        canvas.drawText("1.x", 8f, 30f, Paint().apply { color = Color.WHITE; textSize = 20f })
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}
