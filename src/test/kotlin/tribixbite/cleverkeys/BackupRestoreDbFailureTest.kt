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
import tribixbite.cleverkeys.backup.DictImportApplier
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Failure-path rollback for [BackupRestoreManager]'s import routines — the CK-150-019/020/022
 * remediation (audit `docs/audit/2026-08-25-remediation-verification.md` §3, §4.1, §4.2, §4.7).
 *
 * An import mutates three stores that fail independently:
 *
 *  - **media files** — committed reversibly (`MediaCommit`); pre-existing targets are backed up
 *    into the staging dir, new targets are deleted on rollback.
 *  - **the clipboard DB** — one SQLite transaction. Before CK-150-019 `importFromJSON` swallowed
 *    its own exceptions and returned partial counts, so a rolled-back transaction reported
 *    success, the media commit was never reversed, and the user was told the import worked.
 *  - **SharedPreferences** — written by the settings + dictionary section appliers, with no undo
 *    of its own until the CK-150-020 snapshot/restore.
 *
 * Every test here drives a REAL `BackupRestoreManager` over a real ZIP and a real (temp) media
 * tree; only the DB, the media-path resolver and the framework seams are mocked. `prefs` is an
 * in-memory implementation rather than a relaxed mock, because the assertions are about values
 * actually surviving a rollback. Runs under `runMockTests` (android.jar stubs + MockK).
 */
class BackupRestoreDbFailureTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var packageManager: PackageManager
    private lateinit var resources: Resources
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var clipboardDb: ClipboardDatabase
    private lateinit var shortSwipeImporter: ShortSwipeImporter
    private lateinit var shortSwipeManager: ShortSwipeCustomizationManager
    private val testRoot = File("build/test-work/backup-db-failure")

    /** Media paths that must resolve to [poisonedTarget] instead of a plain [File]. */
    private var poisonedPath: String? = null
    private var poisonedTarget: File? = null

    /** Seed prefs — one value per SharedPreferences value class, so the typed restore is covered. */
    private val seededPrefs: Map<String, Any?> = linkedMapOf(
        PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED to false,
        "custom_words_en" to """{"alpha":100}""",
        "disabled_words_en" to setOf("beta"),
        "backup_test_int" to 7,
        "backup_test_long" to 9_000_000_000L,
        "backup_test_float" to 1.5f,
    )

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

        prefs = FakeSharedPreferences(seededPrefs)
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

        // Media targets resolve under the (fake) filesDir. One path may be swapped for a
        // [FlakyDeleteFile] so a single rollback entry can be made to fail.
        mockkConstructor(ClipboardMediaManager::class)
        every { anyConstructed<ClipboardMediaManager>().getMediaFile(any()) } answers {
            val name = firstArg<String>()
            if (name == poisonedPath) poisonedTarget!! else File(testRoot, "files/$name")
        }
        every { anyConstructed<ClipboardMediaManager>().generateThumbnail(any(), any()) } returns null
        every { anyConstructed<ClipboardMediaManager>().cleanupOrphans(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
        poisonedPath = null
        poisonedTarget = null
        testRoot.deleteRecursively()
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun newManager(): BackupRestoreManager =
        BackupRestoreManager(context, shortSwipeImporter)

    private fun fakeUriForInput(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "input.zip"
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
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

    private fun manifestBytes(): ByteArray = JsonObject().apply {
        addProperty("format", "cleverkeys_full_backup")
        addProperty("format_version", BackupRestoreManager.FULL_BACKUP_FORMAT_VERSION)
        addProperty("app_version", "1.4.0-test")
    }.toString().toByteArray(Charsets.UTF_8)

    private fun clipboardJsonBytes(): ByteArray = JSONObject().apply {
        put("total_active", 0); put("total_pinned", 0); put("total_todo", 0)
    }.toString().toByteArray(Charsets.UTF_8)

    /** Config that flips one real, non-internal boolean preference. */
    private fun configBytes(): ByteArray =
        """{"preferences":{"${PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED}":true}}"""
            .toByteArray(Charsets.UTF_8)

    private fun dictionariesBytes(): ByteArray =
        """{"custom_words_by_language":{"en":{"gamma":120}}}""".toByteArray(Charsets.UTF_8)

    private fun liveMedia(relative: String, content: String): File =
        File(testRoot, "files/$relative").apply {
            parentFile!!.mkdirs()
            writeText(content)
        }

    private fun stagingResidue(): List<String> =
        File(testRoot, "cache").listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith("ck_import_") }

    private fun dbFailure(message: String = "disk full") {
        every { clipboardDb.importFromJSON(any()) } throws RuntimeException(message)
    }

    // ── CK-150-019: a DB failure is observable and reverses the media commit ───

    @Test
    fun clipboardZipImport_dbFailure_restoresExistingMedia_deletesNewMedia_andClearsStaging() {
        val existing = liveMedia("clipboard_media/existing.bin", "original")
        val fresh = File(testRoot, "files/clipboard_media/fresh.bin")
        val zipBytes = buildZip(listOf(
            "clipboard_data.json" to clipboardJsonBytes(),
            "clipboard_media/existing.bin" to "replacement".toByteArray(),
            "clipboard_media/fresh.bin" to "brand-new".toByteArray(),
        ))
        dbFailure()

        try {
            newManager().importClipboardHistoryZip(fakeUriForInput(zipBytes))
            fail("A failed clipboard DB import must not report success")
        } catch (e: Exception) {
            val chain = generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" / ")
            assertTrue("failure cause must survive: $chain", chain.contains("disk full"))
        }

        assertEquals("pre-existing media must be restored", "original", existing.readText())
        assertFalse("newly created media must be deleted", fresh.exists())
        assertTrue("staging dir must be removed: ${stagingResidue()}", stagingResidue().isEmpty())
    }

    @Test
    fun fullBackupImport_dbFailure_reportsFailure_restoresMedia_andRestoresPrefs() {
        val existing = liveMedia("clipboard_media/existing.bin", "original")
        val fresh = File(testRoot, "files/clipboard_media/fresh.bin")
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifestBytes(),
            BackupRestoreManager.ENTRY_CONFIG to configBytes(),
            BackupRestoreManager.ENTRY_CLIPBOARD_JSON to clipboardJsonBytes(),
            "clipboard_media/existing.bin" to "replacement".toByteArray(),
            "clipboard_media/fresh.bin" to "brand-new".toByteArray(),
        ))
        dbFailure()

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("a failed DB import must report failure", result.success)
        assertTrue(
            "error message must carry the cause: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("disk full")
        )
        assertEquals("original", existing.readText())
        assertFalse(fresh.exists())
        assertTrue("staging dir must be removed: ${stagingResidue()}", stagingResidue().isEmpty())
        // CK-150-020: the settings section already ran — its writes must be undone too.
        assertEquals("prefs must be restored to the pre-import snapshot", seededPrefs, prefs.all)
        assertFalse("no config may be reported as applied", result.configImported)
        assertEquals(0, result.configKeysApplied)
    }

    // ── CK-150-020: a later section failure undoes the earlier sections' prefs ─

    @Test
    fun fullBackupImport_dictionarySectionFailure_restoresSettingsSectionPrefs() {
        mockkObject(DictImportApplier)
        every { DictImportApplier.apply(any(), any(), any(), any()) } throws
            RuntimeException("dictionary write failed")

        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifestBytes(),
            BackupRestoreManager.ENTRY_CONFIG to configBytes(),
            BackupRestoreManager.ENTRY_DICTIONARIES to dictionariesBytes(),
        ))

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertFalse("a failing section applier must fail the import", result.success)
        assertTrue(
            "error message must carry the cause: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("dictionary write failed")
        )
        assertEquals("section 1's prefs must be rolled back", seededPrefs, prefs.all)
        assertEquals(
            "the settings key must be back to its pre-import value",
            false,
            prefs.getBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, true)
        )
        assertFalse(result.configImported)
        assertEquals(0, result.configKeysApplied)
    }

    @Test
    fun fullBackupImport_allSectionsSucceed_appliesSettingsSection() {
        // Control for the two rollback tests: without a failure the SAME config really does
        // write the pref, so "restored to the snapshot" is a rollback and not a no-op.
        val zipBytes = buildZip(listOf(
            BackupRestoreManager.ENTRY_MANIFEST to manifestBytes(),
            BackupRestoreManager.ENTRY_CONFIG to configBytes(),
            BackupRestoreManager.ENTRY_DICTIONARIES to dictionariesBytes(),
        ))

        val result = newManager().importFullBackup(fakeUriForInput(zipBytes), prefs)

        assertTrue("import should succeed: err=${result.errorMessage}", result.success)
        assertTrue(result.configImported)
        assertEquals(1, result.configKeysApplied)
        assertTrue(
            "the settings section must have applied",
            prefs.getBoolean(PrivateCopyProcessTextActivity.PREF_TOOLBAR_ENABLED, false)
        )
    }

    // ── CK-150-022: one unrestorable entry cannot abort the whole rollback ─────

    @Test
    fun mediaRollback_continuesAfterAnEntryFailsToRestore() {
        val first = liveMedia("clipboard_media/first.bin", "original-first")
        val second = liveMedia("clipboard_media/second.bin", "original-second")
        // `second` is committed last, so it is the FIRST entry the reverse-order rollback tries.
        // Its second delete() fails, which makes the restoring copyTo throw — before CK-150-022
        // that exception escaped the loop and `first` was never restored.
        poisonedPath = "clipboard_media/second.bin"
        poisonedTarget = FlakyDeleteFile(second)

        val zipBytes = buildZip(listOf(
            "clipboard_data.json" to clipboardJsonBytes(),
            "clipboard_media/first.bin" to "replacement-first".toByteArray(),
            "clipboard_media/second.bin" to "replacement-second".toByteArray(),
        ))
        dbFailure()

        try {
            newManager().importClipboardHistoryZip(fakeUriForInput(zipBytes))
            fail("A failed clipboard DB import must not report success")
        } catch (_: Exception) {
            // expected — the DB failure is what triggers the rollback under test
        }

        assertEquals(
            "the entry after the failing one must still be rolled back",
            "original-first",
            first.readText()
        )
        assertEquals(
            "the unrestorable entry keeps the imported content (logged, not silently retried)",
            "replacement-second",
            second.readText()
        )
        assertTrue("staging dir must still be removed: ${stagingResidue()}", stagingResidue().isEmpty())
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    /**
     * A media target whose FIRST `delete()` succeeds and whose later ones fail — the commit's
     * overwrite goes through, the rollback's restore cannot (Kotlin's `File.copyTo(overwrite =
     * true)` throws `FileAlreadyExistsException` when it cannot unlink the destination). Models
     * a file that becomes unlinkable between commit and rollback (permissions, SELinux, a
     * vanished parent).
     */
    private class FlakyDeleteFile(target: File) : File(target.absolutePath) {
        private var deletes = 0

        override fun delete(): Boolean {
            deletes++
            return if (deletes == 1) super.delete() else false
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * In-memory [SharedPreferences]. Only the surface the import path uses is implemented, with
     * `Editor` semantics that match Android's: a `clear()` applies before the pending puts of the
     * same editor, and nothing is visible until `commit()`/`apply()`.
     */
    private class FakeSharedPreferences(initial: Map<String, Any?>) : SharedPreferences {

        private val values = LinkedHashMap<String, Any?>(initial)

        override fun getAll(): MutableMap<String, Any?> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val stored = values[key] as? Set<*> ?: return defValues
            return stored.filterIsInstance<String>().toMutableSet()
        }

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            /** Key → new value; a key mapped to [REMOVE] is deleted on commit. */
            private val pending = LinkedHashMap<String, Any?>()
            private var clearRequested = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value ?: REMOVE)
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                put(key, values?.toSet() ?: REMOVE)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, REMOVE)

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean {
                if (clearRequested) values.clear()
                for ((key, value) in pending) {
                    if (value === REMOVE) values.remove(key) else values[key] = value
                }
                pending.clear()
                clearRequested = false
                return true
            }

            override fun apply() {
                commit()
            }
        }

        private companion object {
            /** Sentinel for "delete this key" — distinct from a stored `null`. */
            val REMOVE = Any()
        }
    }
}
