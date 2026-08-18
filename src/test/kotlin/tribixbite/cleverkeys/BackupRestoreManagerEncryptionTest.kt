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
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.backup.ShortSwipeImporter
import tribixbite.cleverkeys.backup.crypto.BackupCrypto
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Stage B (backup encryption) — manager-level coverage.
 *
 * Proves the load-bearing invariant of design §6: an ENCRYPTED settings import
 * decrypts-then-produces the IDENTICAL [tribixbite.cleverkeys.backup.SettingsImportPlan]
 * as the equivalent plaintext JSON, so the pure preview/diff engine is untouched.
 * Also covers the export encryption policy (HEADLESS_MANDATORY encrypts; the plaintext
 * opt-out does not) and the wrong-passphrase failure surfacing as [BackupRestoreManager.BackupDecryptException].
 *
 * The plaintext↔encrypted symmetry uses the REAL [BackupCrypto] substrate (pure JVM);
 * only the Android boundary (Context/ContentResolver/prefs) is mocked, matching the
 * pattern in [BackupRestoreFullBackupTest].
 */
class BackupRestoreManagerEncryptionTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var packageManager: PackageManager
    private lateinit var resources: Resources
    private lateinit var prefs: SharedPreferences
    private lateinit var shortSwipeImporter: ShortSwipeImporter
    private lateinit var shortSwipeManager: ShortSwipeCustomizationManager
    private lateinit var passphraseStore: BackupPassphraseStore

    private val storedPassphrase = "correct horse battery staple"

    // A small, realistic settings-backup JSON (metadata + preferences).
    private val settingsJson = """
        {
          "metadata": { "app_version": "1.5.0", "screen_width": 1080, "screen_height": 2400, "screen_density": 3.0 },
          "preferences": {
            "swipe_typing_enabled": true,
            "ctc_beam_width": 120,
            "theme": "dark_material"
          }
        }
    """.trimIndent()

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
        shortSwipeImporter = mockk(relaxed = true) {
            coEvery { importFromJson(any(), any()) } returns 0
        }
        shortSwipeManager = mockk(relaxed = true)
        passphraseStore = mockk(relaxed = true)

        every { context.packageName } returns "tribixbite.cleverkeys"
        every { context.packageManager } returns packageManager
        every { context.resources } returns resources
        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir"), "ck-test-cache").also { it.mkdirs() }
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "ck-test-files")

        val pkgInfo = mockk<PackageInfo>(relaxed = true).also {
            it.versionName = "1.5.0-test"
            @Suppress("DEPRECATION")
            it.versionCode = 50
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

        // Passphrase store returns a FRESH array each call (the manager zeroes it).
        every { passphraseStore.hasPassphrase() } returns true
        every { passphraseStore.getPassphrase() } answers { storedPassphrase.toCharArray() }

        mockkStatic(DirectBootAwarePreferences::get_shared_preferences)
        every { DirectBootAwarePreferences.get_shared_preferences(any()) } returns prefs

        mockkObject(ShortSwipeCustomizationManager.Companion)
        every { ShortSwipeCustomizationManager.getInstance(any()) } returns shortSwipeManager
        coEvery { shortSwipeManager.loadMappings() } returns Unit
        every { shortSwipeManager.exportToJson() } returns "{}"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun newManager(): BackupRestoreManager =
        BackupRestoreManager(context, shortSwipeImporter, passphraseStore)

    /** A `content://` URI whose input stream yields [bytes] each time it is opened. */
    private fun uriForInput(bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "backup.dat"
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        return uri
    }

    /** A `content://` output URI capturing everything written to [sink]. */
    private fun uriForOutput(sink: ByteArrayOutputStream): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns "content"
        every { uri.lastPathSegment } returns "out.dat"
        every { contentResolver.openOutputStream(uri) } returns sink
        return uri
    }

    private fun encryptSettings(json: String): ByteArray =
        BackupCrypto.encrypt(
            json.toByteArray(Charsets.UTF_8),
            storedPassphrase.toCharArray(),
            EncryptedBackupFormat.SETTINGS_JSON,
            nowMillis = 1_700_000_000_000L,
        )

    // ── preview preservation ────────────────────────────────────────────────

    @Test
    fun encryptedImport_producesIdenticalPlanToPlaintext() {
        val mgr = newManager()

        val plaintextUri = uriForInput(settingsJson.toByteArray(Charsets.UTF_8))
        val encryptedUri = uriForInput(encryptSettings(settingsJson))

        val plainPlan = mgr.buildSettingsImportPlan(plaintextUri, prefs)
        val encPlan = mgr.buildSettingsImportPlan(encryptedUri, prefs)

        // SettingsImportPlan is a data class — structural equality proves the pure
        // preview engine sees exactly the same input for encrypted vs plaintext.
        assertEquals(
            "Encrypted import must build the identical SettingsImportPlan as plaintext",
            plainPlan, encPlan,
        )
        assertTrue("plan should carry changes", encPlan.changes.isNotEmpty() || encPlan.parseSkippedKeys.isNotEmpty())
    }

    // ── headless mandatory-encryption import gate (TOCTOU fix, 2026-07-18) ─────────

    @Test
    fun headlessMandatory_plaintextJsonImport_isRejectedAtManagerSeam() {
        // The load-bearing gate: under HEADLESS_MANDATORY the manager must refuse a
        // plaintext JSON payload on the SAME bytes it would parse — closing the
        // separate-stream TOCTOU in the Activity's up-front sniff. Nothing imported.
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val uri = uriForInput(settingsJson.toByteArray(Charsets.UTF_8))
        try {
            mgr.buildSettingsImportPlan(uri, prefs)
            fail("HEADLESS_MANDATORY must reject a plaintext JSON import")
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            assertTrue(
                "message should explain plaintext is not accepted via automation",
                e.message!!.contains("Plaintext"),
            )
        }
        // The short-swipe importer is only touched during APPLY; a rejected build
        // must never reach it.
        coVerify(exactly = 0) { shortSwipeImporter.importFromJson(any(), any()) }
    }

    @Test
    fun headlessMandatory_encryptedJsonImport_stillSucceeds() {
        // The legit encrypted path: an ENCRYPTED payload sniffs as ENCRYPTED, passes
        // the gate, decrypts, and produces a real plan under the SAME policy.
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val uri = uriForInput(encryptSettings(settingsJson))

        val plan = mgr.buildSettingsImportPlan(uri, prefs)

        assertTrue(
            "encrypted import under HEADLESS_MANDATORY must still build a plan",
            plan.changes.isNotEmpty() || plan.parseSkippedKeys.isNotEmpty(),
        )
    }

    @Test
    fun uiDefault_plaintextJsonImport_isStillAccepted() {
        // Regression guard: the interactive (UI) path is NOT gated — a user-driven
        // plaintext import must keep working. Only HEADLESS_MANDATORY rejects.
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_DEFAULT
        val uri = uriForInput(settingsJson.toByteArray(Charsets.UTF_8))

        val plan = mgr.buildSettingsImportPlan(uri, prefs)

        assertTrue(
            "UI_DEFAULT plaintext import must still build a plan (not gated)",
            plan.changes.isNotEmpty() || plan.parseSkippedKeys.isNotEmpty(),
        )
    }

    @Test
    fun headlessMandatory_plaintextClipboardJsonImport_isRejected() {
        // Same gate, different action: clipboard JSON import also routes through
        // readJsonFromUri, so the plaintext payload is rejected before importFromJSON.
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val clipboardJson = """{"version":4,"active":[],"pinned":[],"todo":[]}"""
        val uri = uriForInput(clipboardJson.toByteArray(Charsets.UTF_8))
        try {
            mgr.importClipboardHistory(uri)
            fail("HEADLESS_MANDATORY must reject a plaintext clipboard JSON import")
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            assertTrue(e.message!!.contains("Plaintext"))
        }
    }

    @Test
    fun encryptedImport_wrongContentType_isRejectedBeforeParse() {
        val mgr = newManager()
        // Encrypt as CLIPBOARD_JSON, then try to import as SETTINGS → content-type mismatch.
        val wrongType = BackupCrypto.encrypt(
            settingsJson.toByteArray(Charsets.UTF_8),
            storedPassphrase.toCharArray(),
            EncryptedBackupFormat.CLIPBOARD_JSON,
            nowMillis = 1_700_000_000_000L,
        )
        val uri = uriForInput(wrongType)
        try {
            mgr.buildSettingsImportPlan(uri, prefs)
            fail("expected BackupDecryptException for content-type mismatch")
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            assertTrue(e.message!!.contains("content-type"))
        }
    }

    @Test
    fun encryptedImport_wrongPassphrase_throwsDecryptException() {
        // Store a DIFFERENT passphrase than the one the file was encrypted with.
        every { passphraseStore.getPassphrase() } answers { "the-wrong-password".toCharArray() }
        val mgr = newManager()
        val uri = uriForInput(encryptSettings(settingsJson))
        try {
            mgr.buildSettingsImportPlan(uri, prefs)
            fail("expected BackupDecryptException for wrong passphrase")
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            assertEquals(BackupRestoreManager.WRONG_PASSWORD_OR_CORRUPT, e.message)
        }
    }

    // ── export encryption policy ──────────────────────────────────────────────

    @Test
    fun headlessMandatoryExport_writesEncryptedContainer() {
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        mgr.exportConfig(uri, prefs)

        val out = sink.toByteArray()
        assertEquals(
            "export must be a CKENC1 encrypted container",
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(out),
        )
        // The container round-trips back to a valid settings JSON under the passphrase.
        val payload = BackupCrypto.decrypt(out, storedPassphrase.toCharArray())
        assertEquals(EncryptedBackupFormat.SETTINGS_JSON, payload.contentType)
        assertTrue(String(payload.bytes, Charsets.UTF_8).contains("preferences"))
    }

    @Test
    fun uiPlaintextOptOut_writesPlaintextJson() {
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_PLAINTEXT_OPTOUT
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        mgr.exportConfig(uri, prefs)

        val kind = EncryptedBackupFormat.sniff(sink.toByteArray())
        assertEquals(
            "plaintext opt-out must NOT encrypt",
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_JSON, kind,
        )
    }

    @Test
    fun uiDefaultExport_encryptsWhenPassphraseSet() {
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_DEFAULT
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        mgr.exportConfig(uri, prefs)

        assertEquals(
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(sink.toByteArray()),
        )
    }

    @Test
    fun uiDefaultExport_plaintextWhenNoPassphrase() {
        every { passphraseStore.hasPassphrase() } returns false
        every { passphraseStore.getPassphrase() } returns null
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_DEFAULT
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        mgr.exportConfig(uri, prefs)

        assertEquals(
            "UI_DEFAULT with no passphrase falls back to plaintext",
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_JSON,
            EncryptedBackupFormat.sniff(sink.toByteArray()),
        )
    }

    @Test
    fun headlessMandatoryExport_noPassphrase_throwsRatherThanWritePlaintext() {
        // This is the "Activity failed to fail-closed" guard: the manager must NOT
        // silently write plaintext to an attacker sink under HEADLESS_MANDATORY.
        every { passphraseStore.hasPassphrase() } returns false
        every { passphraseStore.getPassphrase() } returns null
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        // exportConfig wraps the IllegalStateException from encryptIfRequired.
        try {
            mgr.exportConfig(uri, prefs)
            fail("HEADLESS_MANDATORY export with no passphrase must throw, not write plaintext")
        } catch (e: Exception) {
            // wrapped as "Export failed: ..." — must NOT have written a plaintext JSON.
            assertEquals(0, sink.size())
        }
    }

    // ── #156 F7: passphrase resolved exactly ONCE per export ──────────────────────

    @Test
    fun jsonExport_resolvesPassphraseExactlyOnce() {
        // Before F7, willEncryptExport() resolved (Keystore unwrap + binder) once and
        // encryptIfRequired() resolved AGAIN — two round-trips + an extra plaintext heap copy.
        // Now a single resolveExportEncryption() must serve both the decision and the cipher input.
        clearMocks(passphraseStore, answers = false)
        every { passphraseStore.hasPassphrase() } returns true
        every { passphraseStore.getPassphrase() } answers { storedPassphrase.toCharArray() }

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_DEFAULT
        val sink = ByteArrayOutputStream()
        mgr.exportConfig(uriForOutput(sink), prefs)

        // The output must still be a real encrypted container (behavior preserved)…
        assertEquals(
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(sink.toByteArray()),
        )
        // …and the passphrase was materialized only ONCE for the whole export op.
        verify(exactly = 1) { passphraseStore.getPassphrase() }
    }

    @Test
    fun plaintextOptOutExport_neverResolvesPassphrase() {
        // The plaintext opt-out short-circuits shouldEncrypt() → the passphrase is never unwrapped
        // (no Keystore binder round-trip, no plaintext heap copy) — strictly better than before.
        clearMocks(passphraseStore, answers = false)
        every { passphraseStore.hasPassphrase() } returns true
        every { passphraseStore.getPassphrase() } answers { storedPassphrase.toCharArray() }

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.UI_PLAINTEXT_OPTOUT
        val sink = ByteArrayOutputStream()
        mgr.exportConfig(uriForOutput(sink), prefs)

        assertEquals(
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_JSON,
            EncryptedBackupFormat.sniff(sink.toByteArray()),
        )
        verify(exactly = 0) { passphraseStore.getPassphrase() }
    }

    @Test
    fun importPassphraseOverride_isNeverUsedForExport() {
        // Even if an override is set (import escape hatch), export uses the STORED
        // passphrase only (design §4.2 — the single most important rule).
        every { passphraseStore.getPassphrase() } answers { "STORED-only".toCharArray() }
        val mgr = newManager()
        mgr.setImportPassphraseOverride("ATTACKER-supplied".toCharArray())
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val sink = ByteArrayOutputStream()
        val uri = uriForOutput(sink)

        mgr.exportConfig(uri, prefs)

        // Must decrypt with the STORED passphrase, and NOT with the override.
        val out = sink.toByteArray()
        val ok = BackupCrypto.decrypt(out, "STORED-only".toCharArray())
        assertEquals(EncryptedBackupFormat.SETTINGS_JSON, ok.contentType)
        try {
            BackupCrypto.decrypt(out, "ATTACKER-supplied".toCharArray())
            fail("export must NOT be decryptable with the import override passphrase")
        } catch (e: javax.crypto.AEADBadTagException) {
            // expected
        }
    }
}
