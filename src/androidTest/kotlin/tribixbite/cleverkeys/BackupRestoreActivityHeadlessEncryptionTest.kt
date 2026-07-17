package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.crypto.BackupCrypto
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented (ew-cli, Pixel7 API 34) coverage of the exported-activity
 * enforcement policy (design §4.3 / §8.3). NOT runnable on the ARM64 Termux
 * device — instrumented only.
 *
 * Uses [BackupRestoreActivity.testManagerOverride] (a recording fake) +
 * [BackupRestoreActivity.testPassphraseStoreOverride] so we can assert the
 * Activity fails closed / rejects plaintext BEFORE ever touching the real manager.
 *
 * Asserts:
 *  - No passphrase → every action refuses (recording manager never invoked).
 *  - Passphrase set → `EXPORT_SETTINGS` invokes the exporter under HEADLESS_MANDATORY.
 *  - Headless `IMPORT_SETTINGS` with a PLAINTEXT `json_base64` payload → rejected, no apply.
 *  - Headless `IMPORT_SETTINGS` with a valid CKENC ciphertext → applied.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreActivityHeadlessEncryptionTest {

    private lateinit var context: Context
    private lateinit var tmpDir: File
    private val passphrase = "activity-headless-pass-42"

    private val exportCalls = AtomicInteger(0)
    private val importCalls = AtomicInteger(0)

    /** Recording fake: counts invocations without doing real IO. */
    private inner class RecordingManager(ctx: Context, store: BackupPassphraseStore) :
        BackupRestoreManager(ctx, passphraseStore = store) {
        override fun exportConfig(uri: Uri, prefs: SharedPreferences): Int {
            exportCalls.incrementAndGet()
            return 0
        }
        override fun buildSettingsImportPlan(uri: Uri, prefs: SharedPreferences) =
            tribixbite.cleverkeys.backup.SettingsImportPlan(
                sourceVersion = "test",
                sourceScreen = tribixbite.cleverkeys.backup.ScreenMetrics(1080, 2400, 3.0f),
                currentScreen = tribixbite.cleverkeys.backup.ScreenMetrics(1080, 2400, 3.0f),
                changes = emptyList(),
                parseSkippedKeys = emptyList(),
                internalRemoves = emptyList(),
                shortSwipeImportSize = 0,
                shortSwipeImportRawJson = null,
            ).also { importCalls.incrementAndGet() }
    }

    /** Passphrase store fake with a togglable presence. */
    private inner class FakeStore(private val present: Boolean) : BackupPassphraseStore(context) {
        override fun hasPassphrase() = present
        override fun getPassphrase() = if (present) passphrase.toCharArray() else null
        override fun setPassphrase(passphrase: CharArray) {}
        override fun clear() {}
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tmpDir = context.cacheDir.resolve("activity-enc").apply { deleteRecursively(); mkdirs() }
        exportCalls.set(0)
        importCalls.set(0)
        // Sequential instrumented tests run inside the 2s window — clear the static
        // rate-limit so the second+ action isn't throttled.
        BackupRestoreActivity.resetHeadlessRateLimitForTest()
    }

    @After
    fun tearDown() {
        BackupRestoreActivity.testManagerOverride = null
        BackupRestoreActivity.testPassphraseStoreOverride = null
    }

    private fun install(present: Boolean) {
        val store = FakeStore(present)
        BackupRestoreActivity.testPassphraseStoreOverride = store
        BackupRestoreActivity.testManagerOverride = RecordingManager(context, store)
    }

    private fun exportIntent(): Intent {
        val out = File(tmpDir, "out-${System.nanoTime()}.json")
        return Intent(context, BackupRestoreActivity::class.java).apply {
            action = BackupRestoreActivity.ACTION_EXPORT_SETTINGS
            data = Uri.fromFile(out)
        }
    }

    @Test
    fun noPassphrase_exportRefused_managerNeverInvoked() {
        install(present = false)
        ActivityScenario.launch<BackupRestoreActivity>(exportIntent()).use { }
        Thread.sleep(500)
        assertEquals("export must be refused when no passphrase set", 0, exportCalls.get())
    }

    @Test
    fun withPassphrase_exportInvokesManager() {
        install(present = true)
        ActivityScenario.launch<BackupRestoreActivity>(exportIntent()).use { }
        Thread.sleep(800)
        assertEquals("export should run under mandatory encryption", 1, exportCalls.get())
    }

    @Test
    fun headlessImport_plaintextBase64_isRejected() {
        install(present = true)
        val plaintextJson = """{"metadata":{},"preferences":{"keyboard_height":50}}"""
        val b64 = android.util.Base64.encodeToString(
            plaintextJson.toByteArray(Charsets.UTF_8), android.util.Base64.DEFAULT
        )
        val intent = Intent(context, BackupRestoreActivity::class.java).apply {
            action = BackupRestoreActivity.ACTION_IMPORT_SETTINGS
            putExtra("json_base64", b64)
        }
        ActivityScenario.launch<BackupRestoreActivity>(intent).use { }
        Thread.sleep(800)
        assertEquals("plaintext headless import must be rejected", 0, importCalls.get())
    }

    @Test
    fun headlessImport_encryptedBase64_isApplied() {
        install(present = true)
        val json = """{"metadata":{},"preferences":{"keyboard_height":50}}"""
        val container = BackupCrypto.encrypt(
            json.toByteArray(Charsets.UTF_8),
            passphrase.toCharArray(),
            EncryptedBackupFormat.SETTINGS_JSON,
            System.currentTimeMillis(),
        )
        val b64 = android.util.Base64.encodeToString(container, android.util.Base64.DEFAULT)
        val intent = Intent(context, BackupRestoreActivity::class.java).apply {
            action = BackupRestoreActivity.ACTION_IMPORT_SETTINGS
            putExtra("json_base64", b64)
        }
        ActivityScenario.launch<BackupRestoreActivity>(intent).use { }
        Thread.sleep(800)
        assertEquals("valid ciphertext import should reach the manager", 1, importCalls.get())
    }
}
