package tribixbite.cleverkeys

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a restore REPORTS and keeps, found restoring the maintainer's backups on the Pixel, a Saga
 * and the Note10+ (2026-11-24):
 *
 *  - "Media files restored: 25" for a ZIP whose rows were nearly all already on the phone: the
 *    count was every media file in the archive, including the ones of duplicate rows (which are
 *    already there) and of rows that were not imported (which the orphan sweep then deletes).
 *  - "Source version: 2026-09-08 12:18:21": a clipboard file has no app version, and its export
 *    DATE was shown under that label.
 *  - A 1.x settings file restored only its settings: the custom and disabled words it carries were
 *    skipped as "restored by the dictionary import", which is a different file.
 *
 * Drives a REAL [BackupRestoreManager] over real archives and a real (temp) media tree; the DB and
 * framework seams are mocked, as in [BackupRestoreDbFailureTest]. Runs under `runMockTests`.
 */
class BackupRestoreImportReportTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var clipboardDb: ClipboardDatabase
    private lateinit var shortSwipeImporter: ShortSwipeImporter
    private lateinit var shortSwipeManager: ShortSwipeCustomizationManager
    private lateinit var prefs: TypedFakePreferences
    private val testRoot = File("build/test-work/backup-import-report")

    @Before
    fun setUp() {
        testRoot.deleteRecursively()
        File(testRoot, "files").mkdirs()
        File(testRoot, "cache").mkdirs()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        clipboardDb = mockk(relaxed = true)
        shortSwipeImporter = mockk(relaxed = true) {
            coEvery { importFromJson(any(), any()) } returns 0
        }
        shortSwipeManager = mockk(relaxed = true)

        every { context.packageName } returns "tribixbite.cleverkeys"
        every { context.packageManager } returns packageManager
        every { context.resources } returns resources
        every { context.contentResolver } returns contentResolver
        every { context.filesDir } returns File(testRoot, "files")
        every { context.cacheDir } returns File(testRoot, "cache")
        val pkgInfo = mockk<PackageInfo>(relaxed = true).also { it.versionName = "2.0.0-test" }
        every { packageManager.getPackageInfo(any<String>(), 0) } returns pkgInfo
        val dm = mockk<DisplayMetrics>(relaxed = true).also {
            it.widthPixels = 1080
            it.heightPixels = 2400
            it.density = 3.0f
        }
        every { resources.displayMetrics } returns dm

        prefs = TypedFakePreferences(mapOf("custom_words_en" to """{"alpha":100}"""))
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        mockkObject(ClipboardDatabase.Companion)
        every { ClipboardDatabase.getInstance(any()) } returns clipboardDb
        every { clipboardDb.getAllReferencedMediaPaths() } returns emptySet()
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(0, 0, 0, 0, 0)

        mockkObject(ShortSwipeCustomizationManager.Companion)
        every { ShortSwipeCustomizationManager.getInstance(any()) } returns shortSwipeManager
        coEvery { shortSwipeManager.loadMappings() } returns Unit
        every { shortSwipeManager.exportToJson() } returns "{}"

        mockkConstructor(ClipboardMediaManager::class)
        every { anyConstructed<ClipboardMediaManager>().getMediaFile(any()) } answers {
            File(testRoot, "files/${firstArg<String>()}")
        }
        every { anyConstructed<ClipboardMediaManager>().generateThumbnail(any(), any()) } returns null
        every { anyConstructed<ClipboardMediaManager>().cleanupOrphans(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
        testRoot.deleteRecursively()
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun manager() = BackupRestoreManager(context, shortSwipeImporter)

    private fun uriFor(bytes: ByteArray, name: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns name
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, payload) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(payload)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun clipboardJson(appVersion: String? = null): ByteArray = JSONObject().apply {
        put("export_version", 5)
        put("export_date", "2026-09-08 12:18:21")
        if (appVersion != null) put("app_version", appVersion)
        put("active_entries", org.json.JSONArray())
    }.toString().toByteArray(Charsets.UTF_8)

    private fun manifest(): ByteArray = JsonObject().apply {
        addProperty("format", "cleverkeys_full_backup")
        addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
        addProperty("app_version", "2.0.0-test")
    }.toString().toByteArray(Charsets.UTF_8)

    /** A media file already on the phone (the file of a row the backup also has). */
    private fun onThePhone(relative: String) {
        File(testRoot, "files/$relative").apply { parentFile!!.mkdirs(); writeText("already here") }
    }

    private val dup = "clipboard_media/ab/dup.gif"
    private val fresh = "clipboard_media/ab/fresh.gif"
    private val notImported = "clipboard_media/ab/not-imported.gif"

    /** One row imported, one duplicate; the duplicate's media is on the phone already. */
    private fun oneNewRowOneDuplicate() {
        onThePhone(dup)
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(1, 0, 0, 1, 0)
        // After the import the rows reference the duplicate's file and the new row's file; the
        // third archive member belongs to no row, so the orphan sweep removes it.
        every { clipboardDb.getAllReferencedMediaPaths() } returns setOf(dup, fresh)
    }

    // ── media count ────────────────────────────────────────────────────────────

    @Test
    fun aClipboardZipRestoreCountsOnlyTheMediaItAdded() {
        oneNewRowOneDuplicate()
        val archive = zip(
            "clipboard_data.json" to clipboardJson(),
            dup to "gif".toByteArray(),
            fresh to "gif".toByteArray(),
            notImported to "gif".toByteArray(),
        )

        val result = manager().importClipboardHistoryZip(uriFor(archive, "clip.zip"))

        assertEquals("only the new row's file was restored", 1, result.mediaFilesRestored)
    }

    @Test
    fun aFullBackupRestoreCountsOnlyTheMediaItAdded() {
        oneNewRowOneDuplicate()
        val archive = zip(
            BackupRestoreManager.ENTRY_MANIFEST to manifest(),
            BackupRestoreManager.ENTRY_CLIPBOARD_JSON to clipboardJson(),
            dup to "gif".toByteArray(),
            fresh to "gif".toByteArray(),
            notImported to "gif".toByteArray(),
        )

        val result = manager().importFullBackup(uriFor(archive, "full.zip"), prefs)

        assertTrue("import failed: ${result.errorMessage}", result.success)
        assertEquals("only the new row's file was restored", 1, result.mediaFilesRestored)
    }

    // ── source version ─────────────────────────────────────────────────────────

    @Test
    fun aClipboardRestoreDoesNotCallTheExportDateAVersion() {
        val zipResult = manager().importClipboardHistoryZip(
            uriFor(zip("clipboard_data.json" to clipboardJson()), "clip.zip")
        )
        assertEquals("unknown", zipResult.sourceVersion)
        assertEquals("2026-09-08 12:18:21", zipResult.exportDate)

        val jsonResult = manager().importClipboardHistory(uriFor(clipboardJson(), "clip.json"))
        assertEquals("unknown", jsonResult.sourceVersion)
        assertEquals("2026-09-08 12:18:21", jsonResult.exportDate)
    }

    @Test
    fun aClipboardRestoreNamesTheAppVersionTheFileCarries() {
        val zipResult = manager().importClipboardHistoryZip(
            uriFor(zip("clipboard_data.json" to clipboardJson("2.0.0")), "clip.zip")
        )
        assertEquals("2.0.0", zipResult.sourceVersion)

        val jsonResult = manager().importClipboardHistory(uriFor(clipboardJson("2.0.0"), "clip.json"))
        assertEquals("2.0.0", jsonResult.sourceVersion)
    }

    // ── a 1.x settings file's words ────────────────────────────────────────────

    @Test
    fun aLegacySettingsFileRestoresTheWordsItCarries() {
        // The shape of the maintainer's 1.2.9 export (2026-02-27): custom words as a JSON
        // string per language, disabled words as a list.
        val file = """
            {"metadata":{"app_version":"1.2.9"},
             "preferences":{
               "custom_words_en":"{\"Bostonian\":100,\"alpha\":50}",
               "custom_words_fr":"{\"pamplemousse\":100}",
               "disabled_words_en":["teh","wich"]
             }}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        manager().importConfig(uriFor(file, "settings.json"), prefs)

        val en = JsonParser.parseString(prefs.values["custom_words_en"] as String).asJsonObject
        assertTrue("the file's word is added: $en", en.has("Bostonian"))
        assertEquals("a word already on the phone keeps its frequency", 100, en["alpha"].asInt)
        val fr = JsonParser.parseString(prefs.values["custom_words_fr"] as String).asJsonObject
        assertTrue("every language is restored: $fr", fr.has("pamplemousse"))
        @Suppress("UNCHECKED_CAST")
        val disabled = prefs.values["disabled_words_en"] as Set<String>
        assertEquals(setOf("teh", "wich"), disabled)
    }
}
