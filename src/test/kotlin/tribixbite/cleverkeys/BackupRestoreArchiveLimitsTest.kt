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
import tribixbite.cleverkeys.backup.crypto.BackupCrypto
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat
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
    private val testRoot = File("build/test-work/backup-archive-limits")

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
        every { context.filesDir } returns File(testRoot, "files")
        every { context.cacheDir } returns File(testRoot, "cache")

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
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(0, 0, 0, 0, 0)

        mockkObject(ShortSwipeCustomizationManager.Companion)
        every { ShortSwipeCustomizationManager.getInstance(any()) } returns shortSwipeManager
        coEvery { shortSwipeManager.loadMappings() } returns Unit
        every { shortSwipeManager.exportToJson() } returns "{}"

        // No real media operations — getMediaFile writes under the (fake) tmp filesDir.
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

    private fun newManager(
        limits: BackupRestoreManager.ImportLimits = BackupRestoreManager.ImportLimits(),
    ): BackupRestoreManager =
        BackupRestoreManager(context, shortSwipeImporter, importLimits = limits)

    private fun fakeUriForInput(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "input.zip"
        // Fresh stream each call — importers may re-open.
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
    }

    /**
     * Build a ZIP from in-memory entries (materialized payloads). A `null` payload writes a
     * DIRECTORY member — `ZipEntry` treats any name ending in `/` as a directory.
     */
    private fun buildZip(entries: List<Pair<String, ByteArray?>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, payload) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (payload != null) zip.write(payload)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /** A ZIP directory member, e.g. `dirEntry("clipboard_media/sub/")`. */
    private fun dirEntry(name: String): Pair<String, ByteArray?> = name to null

    /**
     * Bytes of [zipBytes] up to the start of the central directory — i.e. the local-header
     * region, which is everything [java.util.zip.ZipInputStream] ever reads.
     *
     * The offset is taken from the End Of Central Directory record (the last 22 bytes; the
     * archives built here never carry a comment) rather than by scanning for the `PK\x01\x02`
     * signature, which could appear inside compressed data.
     */
    private fun localEntryRegion(zipBytes: ByteArray): ByteArray {
        val eocd = zipBytes.size - 22
        require(eocd >= 0 && readLe32(zipBytes, eocd) == 0x06054b50L) {
            "expected a comment-less EOCD at the end of the test archive"
        }
        return zipBytes.copyOf(readLe32(zipBytes, eocd + 16).toInt())
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /**
     * Assemble an archive that repeats a member name. `ZipOutputStream` refuses to write two
     * members with the same name, so the local-entry regions of two archives are concatenated;
     * `ZipInputStream` walks local headers sequentially and stops at EOF, so the importer sees
     * exactly the duplicated members.
     */
    private fun buildZipWithDuplicates(
        first: List<Pair<String, ByteArray?>>,
        second: List<Pair<String, ByteArray?>>,
    ): ByteArray = localEntryRegion(buildZip(first)) + localEntryRegion(buildZip(second))

    private fun fullBackupManifestBytes(): ByteArray =
        JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
            addProperty("app_version", "1.4.0-test")
        }.toString().toByteArray(Charsets.UTF_8)

    private fun emptyClipboardJsonBytes(): ByteArray =
        JSONObject().apply {
            put("total_active", 0); put("total_pinned", 0); put("total_todo", 0)
        }.toString().toByteArray(Charsets.UTF_8)

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
        // Unknown entries are bounded/drained — enough tiny entries to cross the count cap.
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
    fun importFullBackup_oversizedMediaNeverTouchesLiveMedia() {
        val path = "clipboard_media/oversized.bin"
        val zipBytes = buildManifestZipWithBomb(path, 256 * 1024L)
        val limits = BackupRestoreManager.ImportLimits(
            mediaEntryBytes = 128 * 1024L,
            importTotalBytes = 1024 * 1024L,
        )

        val result = newManager(limits).importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("byte limit"))
        assertFalse(File(testRoot, "files/$path").exists())
        verify(exactly = 0) { clipboardDb.importFromJSON(any()) }
    }

    @Test
    fun importFullBackup_aggregateLimitRollsBackAllStagedMedia() {
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
        }
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifest.toString().toByteArray(),
            "clipboard_media/one.bin" to ByteArray(80 * 1024),
            "clipboard_media/two.bin" to ByteArray(80 * 1024),
        ))
        val limits = BackupRestoreManager.ImportLimits(
            mediaEntryBytes = 128 * 1024L,
            importTotalBytes = 140 * 1024L,
        )

        val result = newManager(limits).importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("aggregate limit"))
        assertFalse(File(testRoot, "files/clipboard_media/one.bin").exists())
        assertFalse(File(testRoot, "files/clipboard_media/two.bin").exists())
    }

    @Test
    fun importFullBackup_unknownEntriesCannotBypassAggregateLimit() {
        val manifest = JsonObject().apply {
            addProperty("format", "cleverkeys_full_backup")
            addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
        }
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifest.toString().toByteArray(),
            "metadata/vendor-one.bin" to ByteArray(80 * 1024),
            "metadata/vendor-two.bin" to ByteArray(80 * 1024),
        ))
        val limits = BackupRestoreManager.ImportLimits(
            mediaEntryBytes = 128 * 1024L,
            importTotalBytes = 140 * 1024L,
        )

        val result = newManager(limits).importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("aggregate limit"))
        verify(exactly = 0) { clipboardDb.importFromJSON(any()) }
    }

    @Test
    fun importFullBackup_missingManifestPreservesExistingMedia() {
        val path = "clipboard_media/existing.bin"
        val live = File(testRoot, "files/$path").apply {
            parentFile!!.mkdirs()
            writeText("original")
        }
        val zipBytes = buildZip(listOf(path to "replacement".toByteArray()))

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("missing manifest"))
        assertEquals("original", live.readText())
    }

    @Test
    fun importClipboardHistoryZip_partialMediaCommitRestoresExistingFile() {
        val firstPath = "clipboard_media/first.bin"
        val secondPath = "clipboard_media/blocker/second.bin"
        val firstLive = File(testRoot, "files/$firstPath").apply {
            parentFile!!.mkdirs()
            writeText("original")
        }
        // A regular file where the second target needs a directory forces commit failure after
        // the first replacement, exercising the rollback journal.
        File(testRoot, "files/clipboard_media/blocker").writeText("not-a-directory")
        val clipboardJson = JSONObject().apply {
            put("total_active", 0); put("total_pinned", 0); put("total_todo", 0)
        }
        val zipBytes = buildZip(listOf(
            "clipboard_data.json" to clipboardJson.toString().toByteArray(),
            firstPath to "replacement".toByteArray(),
            secondPath to "second".toByteArray(),
        ))

        try {
            newManager().importClipboardHistoryZip(fakeUriForInput(zipBytes))
            fail("Expected the invalid second target to fail media commit")
        } catch (_: Exception) {
            assertEquals("original", firstLive.readText())
            verify(exactly = 0) { clipboardDb.importFromJSON(any()) }
        }
    }

    // ── CK-150-021: ZIP directory members are not media files ─────────────────

    @Test
    fun importClipboardHistoryZip_mediaDirectoryEntryIsSkipped_andLiveDirectorySurvives() {
        // The exact CK-150-021 shape: the archive carries an explicit directory member for a
        // path that already exists as a live DIRECTORY. Staging it produced an empty file whose
        // commit tried to copy over that directory — FileAlreadyExistsException for a non-empty
        // one (import fails), or silent replacement of an empty one.
        val keep = File(testRoot, "files/clipboard_media/sub/keep.bin").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        val zipBytes = buildZip(listOf(
            "clipboard_data.json" to emptyClipboardJsonBytes(),
            dirEntry("clipboard_media/sub/"),
            "clipboard_media/sub/new.bin" to "new".toByteArray(),
        ))

        val result = newManager().importClipboardHistoryZip(fakeUriForInput(zipBytes))

        assertEquals("only the real media file is staged", 1, result.mediaFilesRestored)
        assertTrue(
            "live media directory must survive the import",
            File(testRoot, "files/clipboard_media/sub").isDirectory
        )
        assertEquals("pre-existing sibling untouched", "keep", keep.readText())
        assertEquals("new", File(testRoot, "files/clipboard_media/sub/new.bin").readText())
    }

    @Test
    fun importFullBackup_mediaDirectoryEntryIsSkipped() {
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to fullBackupManifestBytes(),
            dirEntry("clipboard_media/nested/"),
            "clipboard_media/nested/one.bin" to "payload".toByteArray(),
        ))

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertTrue("import should succeed: err=${result.errorMessage}", result.success)
        assertEquals("directory member must not count as media", 1, result.mediaFilesRestored)
        assertTrue(File(testRoot, "files/clipboard_media/nested").isDirectory)
        assertEquals("payload", File(testRoot, "files/clipboard_media/nested/one.bin").readText())
    }

    // ── CK-150-021/034: the duplicate-name guard covers every member type ─────

    @Test
    fun importFullBackup_duplicateFileEntryIsRejected() {
        val zipBytes = buildZipWithDuplicates(
            listOf(
                BackupRestoreManager.ENTRY_MANIFEST to fullBackupManifestBytes(),
                "clipboard_media/dupe.bin" to "first".toByteArray(),
            ),
            listOf("clipboard_media/dupe.bin" to "second".toByteArray()),
        )

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("duplicate member must fail the import", result.success)
        assertTrue(
            "error should name the duplicate: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("duplicate entry")
        )
        assertFalse(File(testRoot, "files/clipboard_media/dupe.bin").exists())
    }

    @Test
    fun importFullBackup_duplicateDirectoryEntryIsRejected() {
        // Directory members used to be exempt from the `seenEntries` guard, so an archive could
        // repeat one without bound.
        val zipBytes = buildZipWithDuplicates(
            listOf(
                BackupRestoreManager.ENTRY_MANIFEST to fullBackupManifestBytes(),
                dirEntry("clipboard_media/sub/"),
            ),
            listOf(dirEntry("clipboard_media/sub/")),
        )

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("duplicate directory member must fail the import", result.success)
        assertTrue(
            "error should name the duplicate: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("duplicate entry")
        )
        verify(exactly = 0) { clipboardDb.importFromJSON(any()) }
    }

    // ── CK-150-034: the encrypted-container ceiling, through the manager ──────

    @Test
    fun importFullBackup_encryptedContainerOverCeiling_failsWithoutParsing() {
        // `archiveContainerBytes` bounds BOTH the container and the authenticated plaintext, but
        // only the container ceiling is reachable through an importer: the container is always
        // header+tag LARGER than its plaintext, so it trips first for any shared limit.
        val plaintextZip = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to fullBackupManifestBytes(),
            BackupRestoreManager.ENTRY_CLIPBOARD_JSON to emptyClipboardJsonBytes(),
        ))
        val container = BackupCrypto.encrypt(
            plaintextZip,
            "container-limit-pw".toCharArray(),
            EncryptedBackupFormat.FULL_BACKUP_ZIP,
            nowMillis = 1_700_000_000_000L,
            iterations = 2000, // iteration count is irrelevant to the ceiling; keep the KDF cheap
        )
        val ceiling = EncryptedBackupFormat.HEADER_LEN.toLong() + 16L
        assertTrue("test container must exceed the ceiling", container.size > ceiling)

        val mgr = newManager(BackupRestoreManager.ImportLimits(archiveContainerBytes = ceiling))
        mgr.setImportPassphraseOverride("container-limit-pw".toCharArray())

        val result = mgr.importFullBackup(fakeUriForInput(container), prefs)

        assertFalse("oversized encrypted container must fail import", result.success)
        assertTrue(
            "error should reference the container ceiling: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("$ceiling byte limit")
        )
        verify(exactly = 0) { clipboardDb.importFromJSON(any()) }
        val leftovers = File(testRoot, "cache").listFiles().orEmpty()
            .filter { it.name.startsWith("ck_decrypt_") || it.name.startsWith("ck_import_") }
        assertTrue("no decrypt/staging residue: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun importFullBackup_encryptedContainerWithinCeiling_stillImports() {
        // Control for the ceiling test: the same encrypted archive imports cleanly when the
        // limit admits it, so the failure above is the ceiling and not the encrypted path.
        val plaintextZip = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to fullBackupManifestBytes(),
            BackupRestoreManager.ENTRY_CLIPBOARD_JSON to emptyClipboardJsonBytes(),
        ))
        val container = BackupCrypto.encrypt(
            plaintextZip,
            "container-limit-pw".toCharArray(),
            EncryptedBackupFormat.FULL_BACKUP_ZIP,
            nowMillis = 1_700_000_000_000L,
            iterations = 2000,
        )

        val mgr = newManager(
            BackupRestoreManager.ImportLimits(archiveContainerBytes = container.size.toLong())
        )
        mgr.setImportPassphraseOverride("container-limit-pw".toCharArray())

        val result = mgr.importFullBackup(fakeUriForInput(container), prefs)

        assertTrue("encrypted import should succeed: err=${result.errorMessage}", result.success)
        assertEquals("1.4.0-test", result.sourceAppVersion)
    }

    @Test
    fun archiveLimitConstants_areStable() {
        // Deliberate ack: bumping these changes the DoS ceiling for every importer.
        assertEquals(32 * 1024 * 1024, BackupRestoreManager.MAX_JSON_ENTRY_BYTES)
        assertEquals(64L * 1024 * 1024, BackupRestoreManager.MAX_MEDIA_ENTRY_BYTES)
        assertEquals(512L * 1024 * 1024, BackupRestoreManager.MAX_IMPORT_TOTAL_BYTES)
        assertEquals(512L * 1024 * 1024, BackupRestoreManager.MAX_ARCHIVE_CONTAINER_BYTES)
        assertEquals(10_000, BackupRestoreManager.MAX_IMPORT_ENTRIES)
        // ARC-034: the ZIP cap above bounds how many MEMBERS an archive may hold; this one
        // bounds how many entries each JSON ARRAY *inside* clipboard_history.json may hold —
        // previously unbounded, so a single-member archive could still flood the DB.
        assertEquals(10_000, ClipboardDatabase.MAX_IMPORT_ENTRIES_PER_ARRAY)
    }

    // ── ARC-034: per-array truncation is reported, not silently absorbed ────────

    /**
     * The truncation count must reach [BackupRestoreManager.ClipboardImportResult] as its OWN
     * field. Folding it into `skippedCount` would be worse than dropping it: that field means
     * "duplicate" at every read site, so a flooded import would look like a deduplicated one.
     */
    @Test
    fun clipboardImport_surfacesTheCapTruncationCountSeparatelyFromDuplicates() {
        // [activeAdded, pinnedAdded, todoAdded, duplicatesSkipped, truncatedByCap]
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(4, 1, 0, 2, 7)

        val json = """{"export_version":5,"export_date":"2026-08-28 00:00:00","active_entries":[]}"""
        val result = newManager().importClipboardHistory(fakeUriForInput(json.toByteArray(Charsets.UTF_8)))

        assertEquals("active + pinned + todo", 5, result.importedCount)
        assertEquals("duplicates only", 2, result.skippedCount)
        assertEquals("cap-dropped entries, kept distinct from duplicates", 7, result.truncatedCount)
    }

    @Test
    fun clipboardImport_uncappedPayloadReportsZeroTruncation() {
        // The guard must not false-positive: an ordinary import reports 0.
        every { clipboardDb.importFromJSON(any()) } returns intArrayOf(3, 0, 0, 0, 0)

        val json = """{"export_version":5,"export_date":"2026-08-28 00:00:00","active_entries":[]}"""
        val result = newManager().importClipboardHistory(fakeUriForInput(json.toByteArray(Charsets.UTF_8)))

        assertEquals(3, result.importedCount)
        assertEquals(0, result.truncatedCount)
    }
}
