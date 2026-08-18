package tribixbite.cleverkeys

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.backup.crypto.BackupCrypto
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat
import java.io.File

/**
 * Instrumented (ew-cli, Pixel7 API 34) end-to-end coverage of Stage B backup
 * encryption against a REAL Context, real `SharedPreferences`, real `file://`
 * URIs, and the real [BackupPassphraseStore] (real Android Keystore wrap).
 *
 * NOT runnable on the ARM64 Termux device — instrumented only. Register with the
 * emulator.wtf run (see `.claude/skills/ew-cli-testing.md`).
 *
 * Asserts (design §8.3):
 *  - with a stored passphrase, exportConfig/exportDictionaries/exportClipboardHistory
 *    produce `CKENC1`-magic files;
 *  - a full import round-trip restores the settings under the same passphrase;
 *  - importing with a CHANGED stored passphrase fails with the single wrong-password
 *    message and zero prefs writes.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreEncryptionEndToEndTest {

    private lateinit var context: Context
    private lateinit var passphraseStore: BackupPassphraseStore
    private lateinit var tmpDir: File

    private val passphrase = "e2e-backup-passphrase-123"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        passphraseStore = BackupPassphraseStore(context)
        passphraseStore.clear()
        passphraseStore.setPassphrase(passphrase.toCharArray())
        tmpDir = context.cacheDir.resolve("enc-e2e").apply { deleteRecursively(); mkdirs() }
    }

    @org.junit.After
    fun tearDown() {
        // Don't leak the stored passphrase into later suites' processes (shared app data under the
        // orchestrator) — otherwise plaintext-export tests see CKENC output instead of plain JSON.
        passphraseStore.clear()
    }

    private fun newManager(): BackupRestoreManager =
        BackupRestoreManager(context, passphraseStore = passphraseStore)

    private fun sniff(file: File): EncryptedBackupFormat.PayloadKind =
        EncryptedBackupFormat.sniff(file.readBytes())

    @Test
    fun exportConfig_underMandatoryPolicy_writesCkencMagic() {
        val prefs = context.getSharedPreferences("enc_e2e_cfg", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt("keyboard_height", 55)
            .putBoolean("swipe_typing_enabled", true)
            .commit()

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val out = File(tmpDir, "config.json.ckenc")
        mgr.exportConfig(Uri.fromFile(out), prefs)

        assertTrue(out.exists())
        assertEquals(EncryptedBackupFormat.PayloadKind.ENCRYPTED, sniff(out))
        // Decrypts back to a settings JSON under the stored passphrase.
        val payload = BackupCrypto.decrypt(out.readBytes(), passphrase.toCharArray())
        assertEquals(EncryptedBackupFormat.SETTINGS_JSON, payload.contentType)
        assertTrue(String(payload.bytes, Charsets.UTF_8).contains("keyboard_height"))
    }

    @Test
    fun exportDictionaries_underMandatoryPolicy_writesCkencMagic() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(context)
        prefs.edit()
            .putString(LanguagePreferenceKeys.customWordsKey("en"), """{"zzz":3}""")
            .commit()

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val out = File(tmpDir, "dict.json.ckenc")
        mgr.exportDictionaries(Uri.fromFile(out))

        assertEquals(EncryptedBackupFormat.PayloadKind.ENCRYPTED, sniff(out))
        val payload = BackupCrypto.decrypt(out.readBytes(), passphrase.toCharArray())
        assertEquals(EncryptedBackupFormat.DICTIONARIES_JSON, payload.contentType)

        prefs.edit().remove(LanguagePreferenceKeys.customWordsKey("en")).commit()
    }

    @Test
    fun exportClipboard_underMandatoryPolicy_writesCkencMagic() {
        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val out = File(tmpDir, "clipboard.json.ckenc")
        mgr.exportClipboardHistory(Uri.fromFile(out))

        assertEquals(EncryptedBackupFormat.PayloadKind.ENCRYPTED, sniff(out))
        val payload = BackupCrypto.decrypt(out.readBytes(), passphrase.toCharArray())
        assertEquals(EncryptedBackupFormat.CLIPBOARD_JSON, payload.contentType)
    }

    @Test
    fun importRoundTrip_restoresSettingsUnderSamePassphrase() {
        val prefs = context.getSharedPreferences("enc_e2e_rt", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("ctc_beam_width", 120).commit()

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val out = File(tmpDir, "rt-config.json.ckenc")
        mgr.exportConfig(Uri.fromFile(out), prefs)

        // Fresh prefs (simulate another device) + import.
        val target = context.getSharedPreferences("enc_e2e_rt_target", Context.MODE_PRIVATE)
        target.edit().clear().commit()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val plan = mgr.buildSettingsImportPlan(Uri.fromFile(out), target)
        assertNotNull(plan)
        // The plan proposes the exported beam width against the empty target.
        val proposed = plan.changes.any { it.key == "ctc_beam_width" }
        assertTrue("round-tripped plan should propose ctc_beam_width", proposed)
    }

    @Test
    fun importWithChangedPassphrase_failsWithSingleMessage_noWrites() {
        val prefs = context.getSharedPreferences("enc_e2e_bad", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("keyboard_height", 60).commit()

        val mgr = newManager()
        mgr.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        val out = File(tmpDir, "bad-config.json.ckenc")
        mgr.exportConfig(Uri.fromFile(out), prefs)

        // Change the stored passphrase → import must fail closed.
        passphraseStore.setPassphrase("a-completely-different-pass".toCharArray())
        val target = context.getSharedPreferences("enc_e2e_bad_target", Context.MODE_PRIVATE)
        target.edit().clear().commit()

        try {
            mgr.buildSettingsImportPlan(Uri.fromFile(out), target)
            fail("import with changed passphrase must throw")
        } catch (e: BackupRestoreManager.BackupDecryptException) {
            assertEquals(BackupRestoreManager.WRONG_PASSWORD_OR_CORRUPT, e.message)
        }
        // No prefs were written into the target.
        assertTrue("target prefs must be untouched on decrypt failure", target.all.isEmpty())
    }
}
