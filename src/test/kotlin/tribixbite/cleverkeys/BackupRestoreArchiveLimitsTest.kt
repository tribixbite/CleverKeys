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
import io.mockk.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zip-bomb / archive-abuse hardening for [BackupRestoreManager]'s import paths.
 *
 * Backups are untrusted ZIPs. Two DoS vectors are guarded:
 *
 *  - **Decompression bomb** — a tiny compressed JSON entry that inflates to
 *    gigabytes. The importer now reads JSON entries via `readBoundedBytes`,
 *    which throws [java.io.IOException] once the decompressed size crosses
 *    [BackupRestoreManager.MAX_JSON_ENTRY_BYTES] instead of OOMing.
 *  - **Entry-count flood** — an archive with millions of tiny entries. Each
 *    ZIP loop caps iteration at [BackupRestoreManager.MAX_IMPORT_ENTRIES].
 *
 * A legitimate small backup must still import cleanly (guards don't false-positive).
 *
 * Mocking mirrors [BackupRestoreFullBackupTest]: MockK at the ContentResolver /
 * PackageManager / ClipboardDatabase boundary, with the manager's own ZIP-parsing
 * logic left unmocked so the caps are exercised for real. Runs under `runMockTests`
 * (android.jar stubs + MockK) on ARM64 — no Robolectric required.
 */
class BackupRestoreArchiveLimitsTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var packageManager: PackageManager
    private lateinit var resources: Resources
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardDb: ClipboardDatabase
    private lateinit var shortSwipeImporter: ShortSwipeImporter
    private lateinit var shortSwipeManager: ShortSwipeCustomizationManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

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

        every { context.packageName } returns "tribixbite.cleverkeys"
        every { context.packageManager } returns packageManager
        every { context.resources } returns resources
        every { context.contentResolver } returns contentResolver
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "ck-test-files-limits")

        val pkgInfo = mockk<PackageInfo>(relaxed = true).also {
            it.versionName = "1.4.0-test"
            @Suppress("DEPRECATION")
            it.versionCode = 42
        }
        every { packageManager.getPackageInfo(any<String>(), 0) } returns pkgInfo

        val dm = mockk<DisplayMetrics>(relaxed = true).also {
            it.widthPixels = 1080
            it.heightPixels = 2400
            it.density = 3.0f
        }
        every { resources.displayMetrics } returns dm

        every { prefs.all } returns emptyMap()
        every { prefs.getString(any(), any()) } returns "{}"
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true

        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        mockkObject(ClipboardDatabase.Companion)
        every { ClipboardDatabase.getInstance(any()) } returns clipboardDb
        every { clipboardDb.getAllReferencedMediaPaths() } returns emptySet()
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(0, 0, 0, 0)

        mockkObject(ShortSwipeCustomizationManager.Companion)
        every { ShortSwipeCustomizationManager.getInstance(any()) } returns shortSwipeManager
        coEvery { shortSwipeManager.loadMappings() } returns Unit
        every { shortSwipeManager.exportToJson() } returns "{}"

        // No real media operations — getMediaFile writes under the (fake) tmp filesDir.
        mockkConstructor(ClipboardMediaManager::class)
        every { anyConstructed<ClipboardMediaManager>().getMediaFile(any()) } answers {
            File(System.getProperty("java.io.tmpdir"), "ck-test-files-limits/${firstArg<String>()}")
        }
        every { anyConstructed<ClipboardMediaManager>().generateThumbnail(any(), any()) } returns null
        every { anyConstructed<ClipboardMediaManager>().cleanupOrphans(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun newManager(): BackupRestoreManager =
        BackupRestoreManager(context, shortSwipeImporter)

    private fun fakeUriForInput(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "input.zip"
        // Fresh stream each call — importers may re-open.
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
    }

    /** Build a ZIP from in-memory entries (materialized payloads). */
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

    /**
     * Build a valid full-backup manifest ZIP whose named [bombEntry] decompresses to
     * [inflatedBytes] of a single repeated byte — WITHOUT ever holding the inflated
     * payload in memory. We stream fixed-size chunks straight into a max-compression
     * [ZipOutputStream]; a constant byte stream deflates to a few KB, so the produced
     * archive is tiny while the decompressed entry is huge (the zip-bomb shape).
     */
    private fun buildManifestZipWithBomb(bombEntry: String, inflatedBytes: Long): ByteArray {
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
            addProperty("app_version", "1.4.0-test")
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)

            // Manifest first so the importer's format guard passes before it reaches the bomb.
            zip.putNextEntry(ZipEntry(BackupRestoreManager.ENTRY_MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // The bomb entry: stream constant bytes in chunks, never materialize the whole thing.
            zip.putNextEntry(ZipEntry(bombEntry))
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            var remaining = inflatedBytes
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size.toLong()).toInt()
                zip.write(chunk, 0, n)
                remaining -= n
            }
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun importFullBackup_oversizedJsonEntry_throwsBounded_notOOM() {
        // config.json inflates to just over the cap → readBoundedBytes must bail.
        val inflated = BackupRestoreManager.MAX_JSON_ENTRY_BYTES.toLong() + (1L * 1024 * 1024) // ~33 MB
        val zipBytes = buildManifestZipWithBomb(BackupRestoreManager.ENTRY_CONFIG, inflated)

        val mgr = newManager()
        val result = mgr.importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("oversized JSON entry must fail import", result.success)
        assertNotNull("error message must be populated", result.errorMessage)
        assertTrue(
            "error should reference the MB limit: ${result.errorMessage}",
            result.errorMessage!!.contains("MB limit")
        )
        // The manager must NOT have applied any config despite a valid manifest.
        assertEquals(0, result.configKeysApplied)
        assertFalse(result.configImported)
    }

    @Test
    fun importFullBackup_tooManyEntries_throwsEntryCountLimit() {
        // MAX_IMPORT_ENTRIES + 1 tiny entries. First is a valid manifest so the format
        // guard passes; the rest are benign so only the count cap can trip.
        val entries = ArrayList<Pair<String, ByteArray>>(BackupRestoreManager.MAX_IMPORT_ENTRIES + 1)
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
            addProperty("app_version", "1.4.0-test")
        }
        entries.add(BackupRestoreManager.ENTRY_MANIFEST to manifest.toString().toByteArray(Charsets.UTF_8))
        // Unknown entries are cheap (logged + skipped) — enough to cross the cap.
        for (i in 0 until BackupRestoreManager.MAX_IMPORT_ENTRIES) {
            entries.add("misc/$i.bin" to ByteArray(1) { i.toByte() })
        }

        val zipBytes = buildZip(entries)

        val mgr = newManager()
        val result = mgr.importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("entry-count flood must fail import", result.success)
        assertNotNull(result.errorMessage)
        assertTrue(
            "error should reference the entry limit: ${result.errorMessage}",
            result.errorMessage!!.contains("${BackupRestoreManager.MAX_IMPORT_ENTRIES} entries")
        )
    }

    @Test
    fun importClipboardHistoryZip_oversizedJson_throwsBounded() {
        // The clipboard-history importer rethrows (no failure-result object), so assert
        // the propagated IOException message.
        val inflated = BackupRestoreManager.MAX_JSON_ENTRY_BYTES.toLong() + (1L * 1024 * 1024)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry("clipboard_data.json"))
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            var remaining = inflated
            while (remaining > 0) {
                val n = minOf(remaining, chunk.size.toLong()).toInt()
                zip.write(chunk, 0, n)
                remaining -= n
            }
            zip.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        val mgr = newManager()
        try {
            mgr.importClipboardHistoryZip(fakeUriForInput(zipBytes))
            fail("Expected import to throw on oversized clipboard_data.json")
        } catch (e: Exception) {
            // importClipboardHistoryZip wraps the cause; the MB-limit message must survive.
            val chain = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
            assertTrue("error chain should mention MB limit: $chain", chain.contains("MB limit"))
        }
    }

    @Test
    fun importFullBackup_normalSmallBackup_stillImports() {
        // A legitimate, small full backup — the caps must NOT false-positive.
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
            addProperty("app_version", "1.4.0-test")
        }
        val config = JsonObject().apply {
            add("metadata", JsonObject())
            add("preferences", JsonObject())
        }
        val clipboard = JSONObject().apply {
            put("total_active", 0); put("total_pinned", 0); put("total_todo", 0)
        }
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifest.toString().toByteArray(Charsets.UTF_8),
            BackupRestoreManager.ENTRY_CONFIG to config.toString().toByteArray(Charsets.UTF_8),
            BackupRestoreManager.ENTRY_CLIPBOARD_JSON to clipboard.toString().toByteArray(Charsets.UTF_8),
        ))

        val mgr = newManager()
        val result = mgr.importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertTrue("normal small backup should import: err=${result.errorMessage}", result.success)
        assertEquals("1.4.0-test", result.sourceAppVersion)
        assertTrue("config section was processed", result.configImported)
    }

    @Test
    fun archiveLimitConstants_areStable() {
        // Deliberate ack: bumping these changes the DoS ceiling for every importer.
        assertEquals(32 * 1024 * 1024, BackupRestoreManager.MAX_JSON_ENTRY_BYTES)
        assertEquals(10_000, BackupRestoreManager.MAX_IMPORT_ENTRIES)
    }
}
