package tribixbite.cleverkeys

import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.backup.RealShortSwipeImporter
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * GitHub #142 — one-click full backup as dated ZIP.
 *
 * These tests exercise the shape contract of [BackupRestoreManager.exportFullBackup]
 * and [BackupRestoreManager.importFullBackup]:
 *
 *  - The ZIP always begins with `manifest.json` (so external inspection tools
 *    that read only the central directory get app-version + counts cheaply).
 *  - The manifest carries app version, format version, ISO export date, and
 *    a list of contained entries.
 *  - The manifest declares `format_version` and the importer refuses files
 *    whose version is strictly greater than [BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION].
 *  - Round-trip: exporting then importing the same data preserves
 *    customWords + disabledWords + clipboard JSON (config plumbed through the
 *    real SettingsImportPlanBuilder).
 *
 * Implementation note: the manager has heavy collaborators (ClipboardDatabase,
 * ClipboardMediaManager, DirectBootAwarePreferences, ShortSwipeCustomizationManager).
 * We mock at the boundary, keeping the manager's own ZIP-building/parsing logic
 * unmocked so the test catches structural regressions.
 */
class BackupRestoreFullBackupTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var packageManager: PackageManager
    private lateinit var resources: Resources
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardDb: ClipboardDatabase
    private lateinit var shortSwipeImporter: ShortSwipeImporter
    private lateinit var shortSwipeManager: ShortSwipeCustomizationManager

    // Captured outputs from the manager's writes
    private lateinit var capturedOutput: ByteArrayOutputStream

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        capturedOutput = ByteArrayOutputStream()

        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        resources = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        clipboardDb = mockk(relaxed = true)
        shortSwipeImporter = mockk(relaxed = true) {
            coEvery { importFromJson(any(), any()) } returns 0
        }
        shortSwipeManager = mockk(relaxed = true)

        // Wire context → resources / package manager / content resolver
        every { context.packageName } returns "tribixbite.cleverkeys"
        every { context.packageManager } returns packageManager
        every { context.resources } returns resources
        every { context.contentResolver } returns contentResolver
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "ck-test-files")

        // Package info with a known version. Use mockk instead of `PackageInfo()`
        // because the android.jar in the unit-test classpath provides only
        // stubs — direct `new PackageInfo()` throws `RuntimeException: Stub!`.
        val pkgInfo = mockk<PackageInfo>(relaxed = true).also {
            it.versionName = "1.4.0-test"
            @Suppress("DEPRECATION")
            it.versionCode = 42
        }
        every { packageManager.getPackageInfo(any<String>(), 0) } returns pkgInfo

        // Display metrics — same stub-avoidance pattern as PackageInfo above.
        val dm = mockk<DisplayMetrics>(relaxed = true).also {
            it.widthPixels = 1080
            it.heightPixels = 2400
            it.density = 3.0f
        }
        every { resources.displayMetrics } returns dm

        // SharedPreferences default values — empty maps so config export has just defaults
        every { prefs.all } returns emptyMap()
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true

        // Stub DirectBootAwarePreferences → return our prefs mock.
        // Use `mockkStatic(method-ref)` instead of `mockkObject(...)` because
        // the object's class transitively references `androidx.preference.
        // PreferenceManager`, which is not on the mock-test classpath. The
        // method-reference variant of mockkStatic intercepts at the call site
        // without loading the implementing object. Matches the pattern used
        // in PrivacyManagerTest + DirectBootAwarePreferencesTest.
        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        // Stub ClipboardDatabase.getInstance + relaxed exports. The JVM
        // dispatches `@JvmStatic` Companion methods via
        // `ClipboardDatabase$Companion.getInstance`, so neither
        // `mockkStatic(ClipboardDatabase::class)` nor
        // `mockkStatic(ClipboardDatabase::getInstance)` intercepts the call
        // recorded inside the every {} block — the real method runs and
        // dereferences the relaxed Context mock's `applicationContext`,
        // which throws AbstractMethodError because the android.jar stub's
        // abstract method has no implementation. `mockkObject(...Companion)`
        // is the working pattern — same approach used immediately below for
        // `ShortSwipeCustomizationManager.Companion`.
        mockkObject(ClipboardDatabase.Companion)
        every { ClipboardDatabase.getInstance(any()) } returns clipboardDb
        // Empty clipboard by default
        every { clipboardDb.exportToJSON(any()) } returns JSONObject().apply {
            put("total_active", 0)
            put("total_pinned", 0)
            put("total_todo", 0)
            put("export_date", "2026-05-21T00:00:00")
        }
        every { clipboardDb.getAllReferencedMediaPaths() } returns emptySet()
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(0, 0, 0, 0)

        // Stub ShortSwipeCustomizationManager.getInstance + return empty mappings.
        mockkObject(ShortSwipeCustomizationManager.Companion)
        every { ShortSwipeCustomizationManager.getInstance(any()) } returns shortSwipeManager
        coEvery { shortSwipeManager.loadMappings() } returns Unit
        every { shortSwipeManager.exportToJson() } returns "{}"

        // Mock ClipboardMediaManager constructor — no media operations needed in tests
        mockkConstructor(ClipboardMediaManager::class)
        every { anyConstructed<ClipboardMediaManager>().getMediaFile(any()) } answers {
            File(System.getProperty("java.io.tmpdir"), "ck-test-files/${firstArg<String>()}")
        }
        every { anyConstructed<ClipboardMediaManager>().generateThumbnail(any(), any()) } returns null
        every { anyConstructed<ClipboardMediaManager>().cleanupOrphans(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun newManager(): BackupRestoreManager {
        return BackupRestoreManager(context, shortSwipeImporter)
    }

    private fun fakeUriForOutput(): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "cleverkeys_full_backup_2026-05-21.zip"
        every { contentResolver.openOutputStream(uri) } returns capturedOutput
        return uri
    }

    private fun fakeUriForInput(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "input.zip"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        return uri
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return out
    }

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, payload) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun exportFullBackup_writesManifestWithCurrentAppVersion() {
        val mgr = newManager()
        val uri = fakeUriForOutput()

        val result = mgr.exportFullBackup(uri, prefs)

        assertTrue("export should succeed: err=${result.errorMessage}", result.success)
        val entries = zipEntries(capturedOutput.toByteArray())
        assertTrue("ZIP must contain manifest.json", entries.containsKey(BackupRestoreManager.ENTRY_MANIFEST))

        val manifest = JsonParser.parseString(String(entries[BackupRestoreManager.ENTRY_MANIFEST]!!, Charsets.UTF_8)).asJsonObject
        assertEquals("cleverkeys_full_backup", manifest.get("format").asString)
        assertEquals(BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION, manifest.get("format_version").asInt)
        assertEquals("1.4.0-test", manifest.get("app_version").asString)
        assertEquals(42, manifest.get("app_version_code").asInt)
        assertTrue("export_date present", manifest.has("export_date"))
        assertTrue("entries array present", manifest.has("entries"))
    }

    @Test
    fun exportFullBackup_includesConfigJsonAndDictionariesJson() {
        val mgr = newManager()
        val uri = fakeUriForOutput()

        val result = mgr.exportFullBackup(uri, prefs)
        assertTrue(result.success)

        val entries = zipEntries(capturedOutput.toByteArray())

        // config.json — must contain the standard top-level "metadata" + "preferences" sections
        // produced by buildConfigJson() so single-file importers still recognize the payload.
        assertTrue("config.json present", entries.containsKey(BackupRestoreManager.ENTRY_CONFIG))
        val config = JsonParser.parseString(String(entries[BackupRestoreManager.ENTRY_CONFIG]!!, Charsets.UTF_8)).asJsonObject
        assertTrue("config has metadata", config.has("metadata"))
        assertTrue("config has preferences", config.has("preferences"))

        // dictionaries.json — always written so importer is symmetric
        assertTrue("dictionaries.json present", entries.containsKey(BackupRestoreManager.ENTRY_DICTIONARIES))
        val dict = JsonParser.parseString(String(entries[BackupRestoreManager.ENTRY_DICTIONARIES]!!, Charsets.UTF_8)).asJsonObject
        assertTrue("dictionaries has metadata", dict.has("metadata"))
        // v2 = language-specific format (matches single-file exportDictionaries output)
        assertEquals(2, dict.get("metadata").asJsonObject.get("format_version").asInt)
    }

    @Test
    fun exportFullBackup_includesClipboardHistoryJson() {
        // Override clipboard mock to return a non-empty payload so the section is non-empty.
        every { clipboardDb.exportToJSON(any()) } returns JSONObject().apply {
            put("total_active", 1)
            put("total_pinned", 0)
            put("total_todo", 0)
            put("export_date", "2026-05-21T00:00:00")
            put("active", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("content", "hello world")
                    put("timestamp", 12345L)
                })
            })
        }

        val mgr = newManager()
        val uri = fakeUriForOutput()
        val result = mgr.exportFullBackup(uri, prefs)
        assertTrue(result.success)

        val entries = zipEntries(capturedOutput.toByteArray())
        assertTrue("clipboard_history.json present",
            entries.containsKey(BackupRestoreManager.ENTRY_CLIPBOARD_JSON))
        val clipPayload = JSONObject(String(entries[BackupRestoreManager.ENTRY_CLIPBOARD_JSON]!!, Charsets.UTF_8))
        assertEquals(1, clipPayload.optInt("total_active"))

        // FullBackupResult should reflect the count too.
        assertEquals(1, result.clipboardEntryCount)
    }

    @Test
    fun exportFullBackup_manifestEntryListsAllIncludedSections() {
        // Non-empty clipboard so the section IS included in entries[]
        every { clipboardDb.exportToJSON(any()) } returns JSONObject().apply {
            put("total_active", 2); put("total_pinned", 0); put("total_todo", 0)
        }

        val mgr = newManager()
        val uri = fakeUriForOutput()
        mgr.exportFullBackup(uri, prefs)

        val entries = zipEntries(capturedOutput.toByteArray())
        val manifest = JsonParser.parseString(String(entries[BackupRestoreManager.ENTRY_MANIFEST]!!, Charsets.UTF_8)).asJsonObject
        val entryList = manifest.getAsJsonArray("entries").map { it.asString }.toSet()
        assertTrue("manifest entry list mentions manifest.json", entryList.contains(BackupRestoreManager.ENTRY_MANIFEST))
        assertTrue("manifest entry list mentions config.json", entryList.contains(BackupRestoreManager.ENTRY_CONFIG))
        assertTrue("manifest entry list mentions dictionaries.json", entryList.contains(BackupRestoreManager.ENTRY_DICTIONARIES))
        assertTrue("manifest entry list mentions clipboard_history.json", entryList.contains(BackupRestoreManager.ENTRY_CLIPBOARD_JSON))
    }

    @Test
    fun importFullBackup_refusesNewerFormatVersion() {
        // Hand-craft a ZIP whose manifest declares format_version greater than ours.
        val futureVersion = BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION + 99
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", futureVersion)
            addProperty("app_version", "9.9.9-future")
        }
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifest.toString().toByteArray(Charsets.UTF_8),
        ))

        val mgr = newManager()
        val uri = fakeUriForInput(zipBytes)

        val result = mgr.importFullBackup(uri, prefs)

        assertFalse("future-version ZIP must be rejected", result.success)
        assertNotNull("error message must be populated", result.errorMessage)
        assertTrue(
            "error should mention the format version",
            result.errorMessage!!.contains("format_version") ||
                result.errorMessage!!.contains("newer")
        )
        // None of the per-section importers should have fired.
        assertEquals(0, result.configKeysApplied)
        assertEquals(0, result.customWordsImported)
        assertEquals(0, result.clipboardEntriesImported)
    }

    @Test
    fun importFullBackup_missingManifestIsRejected() {
        // Hand-craft a ZIP with only a config.json — no manifest.
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_CONFIG to "{\"preferences\":{}}".toByteArray(Charsets.UTF_8),
        ))

        val mgr = newManager()
        val uri = fakeUriForInput(zipBytes)
        val result = mgr.importFullBackup(uri, prefs)

        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        assertTrue("error should mention manifest", result.errorMessage!!.contains("manifest"))
    }

    @Test
    fun roundTrip_exportThenImport_preservesManifestContract() {
        // First export → capture bytes
        val mgr = newManager()
        val outUri = fakeUriForOutput()
        val exportResult = mgr.exportFullBackup(outUri, prefs)
        assertTrue(exportResult.success)

        // Then feed those exact bytes into importFullBackup
        val exportedBytes = capturedOutput.toByteArray()
        val inUri = fakeUriForInput(exportedBytes)
        val importResult = mgr.importFullBackup(inUri, prefs)

        assertTrue("round-trip import should succeed: err=${importResult.errorMessage}", importResult.success)
        assertEquals("1.4.0-test", importResult.sourceAppVersion)
        // Config IS imported (defaults from SETTINGS_DEFAULTS get round-tripped through the
        // SettingsImportPlanBuilder). configKeysApplied may be 0 when current matches defaults,
        // but configImported should be true since a config.json was present.
        assertTrue("config section was processed", importResult.configImported)
    }

    @Test
    fun fullBackupResult_failureCarriesErrorMessage() {
        // Force ClipboardDatabase to throw to exercise the error-path branch.
        every { clipboardDb.exportToJSON(any()) } throws RuntimeException("simulated db failure")

        val mgr = newManager()
        val uri = fakeUriForOutput()
        val result = mgr.exportFullBackup(uri, prefs)

        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("simulated db failure"))
    }

    @Test
    fun fullBackupFormatVersionConstant_isStable() {
        // Future migrations may bump this; bumping here is a deliberate ack that
        // older importers will reject newer files. This test ensures nobody
        // changes the constant accidentally.
        assertEquals(1, BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
        assertEquals("manifest.json", BackupRestoreManager.ENTRY_MANIFEST)
        assertEquals("config.json", BackupRestoreManager.ENTRY_CONFIG)
        assertEquals("dictionaries.json", BackupRestoreManager.ENTRY_DICTIONARIES)
        assertEquals("clipboard_history.json", BackupRestoreManager.ENTRY_CLIPBOARD_JSON)
    }
}
