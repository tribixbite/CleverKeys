package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tribixbite.cleverkeys.backup.crypto.BackupPassphraseStore
import tribixbite.cleverkeys.backup.crypto.EncryptedBackupFormat

/**
 * Headless backup/restore Intent target.
 *
 * As of the 2026-05-07 unification (Option 2), the user-visible Compose UI
 * lives inline in [SettingsActivity]'s "💾 Backup & Restore" section. This
 * activity now exists ONLY to service programmatic Intent actions (Termux
 * automation, `am start`, scripts) — it has no UI of its own. When opened
 * without a known action, it redirects to [SettingsActivity] and finishes.
 *
 * Supported intent actions (data URI in `intent.data`, OR `json_base64`
 * extra for content piped inline):
 *   - [ACTION_EXPORT_SETTINGS]
 *   - [ACTION_IMPORT_SETTINGS]
 *   - [ACTION_EXPORT_DICTIONARIES]
 *   - [ACTION_IMPORT_DICTIONARIES]
 *   - [ACTION_EXPORT_CLIPBOARD]
 *   - [ACTION_IMPORT_CLIPBOARD]
 *
 * Each headless invocation toasts its result and `finish()`es.
 *
 * Backend: [BackupRestoreManager] handles all serialization and validation.
 */
class BackupRestoreActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BackupRestoreActivity"

        /** Broadcast emitted after a successful dictionary import — listened
         *  for by [DictionaryManagerActivity] and the inline preview path. */
        const val ACTION_DICTIONARY_IMPORTED = "tribixbite.cleverkeys.ACTION_DICTIONARY_IMPORTED"

        // #70: Intent actions for programmatic backup/restore (Termux, automation)
        const val ACTION_EXPORT_SETTINGS = "tribixbite.cleverkeys.action.EXPORT_SETTINGS"
        const val ACTION_IMPORT_SETTINGS = "tribixbite.cleverkeys.action.IMPORT_SETTINGS"
        const val ACTION_EXPORT_DICTIONARIES = "tribixbite.cleverkeys.action.EXPORT_DICTIONARIES"
        const val ACTION_IMPORT_DICTIONARIES = "tribixbite.cleverkeys.action.IMPORT_DICTIONARIES"
        const val ACTION_EXPORT_CLIPBOARD = "tribixbite.cleverkeys.action.EXPORT_CLIPBOARD"
        const val ACTION_IMPORT_CLIPBOARD = "tribixbite.cleverkeys.action.IMPORT_CLIPBOARD"

        /**
         * Test-only override hook. When non-null, the activity uses this
         * Manager instead of constructing its own. Instrumented tests set
         * it in @Before, clear it in @After. NOT thread-safe by design —
         * instrumented tests run sequentially.
         */
        // Test-only hook: non-null ONLY while an instrumented test is running (set in
        // @Before, cleared in @After), so it never retains a context in production.
        @SuppressLint("StaticFieldLeak")
        @androidx.annotation.VisibleForTesting
        var testManagerOverride: BackupRestoreManager? = null

        /**
         * Test-only override for the passphrase store (mirrors [testManagerOverride]).
         */
        // Test-only hook: non-null ONLY while an instrumented test is running (set in
        // @Before, cleared in @After), so it never retains a context in production.
        @SuppressLint("StaticFieldLeak")
        @androidx.annotation.VisibleForTesting
        var testPassphraseStoreOverride: BackupPassphraseStore? = null

        /**
         * In-memory rate-limit: minimum spacing between two headless actions
         * (design §7 residual-risk #5 — cheap KDF/disk-write DoS hardening).
         * Backed by a static timestamp so it survives per-invocation Activity
         * instances (each `am start` creates a fresh Activity).
         */
        const val MIN_HEADLESS_ACTION_SPACING_MS = 2_000L

        @Volatile
        private var lastHeadlessActionMs: Long = 0L

        /** Test-only: reset the headless rate-limit so sequential instrumented tests
         *  aren't throttled by the static timestamp. */
        @androidx.annotation.VisibleForTesting
        fun resetHeadlessRateLimitForTest() { lastHeadlessActionMs = 0L }

        /** Pref gating the headless `--es passphrase` IMPORT escape hatch (default off). */
        const val PREF_ALLOW_INTENT_PASSPHRASE = "backup_allow_intent_passphrase"

        private val IMPORT_ACTIONS = setOf(
            ACTION_IMPORT_SETTINGS, ACTION_IMPORT_DICTIONARIES, ACTION_IMPORT_CLIPBOARD,
        )
        private val EXPORT_ACTIONS = setOf(
            ACTION_EXPORT_SETTINGS, ACTION_EXPORT_DICTIONARIES, ACTION_EXPORT_CLIPBOARD,
        )
        private val KNOWN_BACKUP_ACTIONS = IMPORT_ACTIONS + EXPORT_ACTIONS
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var backupRestoreManager: BackupRestoreManager
    private lateinit var passphraseStore: BackupPassphraseStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            passphraseStore = testPassphraseStoreOverride ?: BackupPassphraseStore(this)
            backupRestoreManager = testManagerOverride ?: BackupRestoreManager(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val action = intent.action
        val isKnownBackupAction = action in KNOWN_BACKUP_ACTIONS

        if (isKnownBackupAction) {
            // Design §4.3: on the exported-activity path, encryption is MANDATORY.
            // No stored passphrase → fail closed (nothing written, nothing applied).
            if (!passphraseStore.hasPassphrase()) {
                android.util.Log.w(TAG, "Headless $action rejected: no backup password set")
                Toast.makeText(
                    this,
                    "Set a backup password in Settings → Backup & Restore first",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return
            }
            // Design §7 residual-risk #5: rate-limit consecutive headless actions.
            val now = android.os.SystemClock.elapsedRealtime()
            val sinceLast = now - lastHeadlessActionMs
            if (lastHeadlessActionMs != 0L && sinceLast < MIN_HEADLESS_ACTION_SPACING_MS) {
                android.util.Log.w(TAG, "Headless $action throttled (${sinceLast}ms since last)")
                Toast.makeText(this, "Backup action throttled — try again in a moment", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            lastHeadlessActionMs = now
            // All headless ops run under the mandatory-encryption policy.
            backupRestoreManager.encryptionPolicy = BackupRestoreManager.EncryptionPolicy.HEADLESS_MANDATORY
        }

        // #70: Decode `json_base64` extra to a temp file URI when present —
        // bypasses scoped storage so callers can pipe content inline.
        val fileUri = intent.data
        val importUri = fileUri ?: resolveBase64Extra(intent)

        // Gated escape hatch: accept `--es passphrase` ONLY for IMPORT, ONLY when the
        // toggle is on. NEVER for export (an attacker-supplied export passphrase would
        // reopen exfiltration — design §4.2). Set as a one-shot manager override.
        val isImport = action in IMPORT_ACTIONS
        if (isImport && prefs.getBoolean(PREF_ALLOW_INTENT_PASSPHRASE, false)) {
            intent.getStringExtra("passphrase")?.let { p ->
                if (p.isNotEmpty()) {
                    android.util.Log.i(TAG, "Using --es passphrase override for $action (toggle on)")
                    backupRestoreManager.setImportPassphraseOverride(p.toCharArray())
                }
            }
        }

        // Headless IMPORT of a plaintext (legacy) payload → reject (closes injection).
        if (isImport && importUri != null && payloadIsPlaintext(importUri)) {
            android.util.Log.w(TAG, "Headless $action rejected: plaintext payload not accepted")
            Toast.makeText(
                this,
                "Import failed: plaintext backups are not accepted via automation — use the app's Import button",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }

        val actionFn: (() -> Unit)? = when (action) {
            ACTION_EXPORT_SETTINGS -> fileUri?.let { { performExport(it) } }
            ACTION_IMPORT_SETTINGS -> importUri?.let { { performImportHeadless(it) } }
            ACTION_EXPORT_DICTIONARIES -> fileUri?.let { { performExportDictionaries(it) } }
            ACTION_IMPORT_DICTIONARIES -> importUri?.let { { performImportDictionariesHeadless(it) } }
            ACTION_EXPORT_CLIPBOARD -> fileUri?.let { { performExportClipboard(it) } }
            ACTION_IMPORT_CLIPBOARD -> importUri?.let { { performImportClipboard(it) } }
            else -> null
        }

        if (actionFn != null) {
            actionFn()
            // perform* coroutines call finish() in their finally block.
        } else {
            // No known action — redirect to the inline section in SettingsActivity.
            // Reachable when a stale shortcut, the launcher icon, or an unknown
            // intent action lands here.
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                putExtra("scroll_to", "backup_restore")
            })
            finish()
        }
    }

    /** Toast the actual output path so headless callers see a real file location. */
    private fun headlessToast(label: String) {
        val path = backupRestoreManager.lastOutputPath
        val result = if (path != null) "$label: $path" else label
        val protection = when (passphraseStore.protectionState()) {
            BackupPassphraseStore.ProtectionState.ANDROID_KEYSTORE -> "Android Keystore"
            BackupPassphraseStore.ProtectionState.LEGACY_APP_PRIVATE -> "legacy app-private"
            BackupPassphraseStore.ProtectionState.NOT_SET -> "not set"
        }
        val msg = "$result\nPassword protection: $protection"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * #70: Decode json_base64 intent extra to a temp file, returning its URI.
     * Bypasses scoped storage entirely — caller passes file content inline:
     *   am start -a ...IMPORT_SETTINGS --es json_base64 "$(base64 < backup.json)"
     */
    private fun resolveBase64Extra(intent: Intent): Uri? {
        val b64 = intent.getStringExtra("json_base64") ?: return null
        return try {
            val decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val tempFile = java.io.File(cacheDir, "import_base64_${System.currentTimeMillis()}.json")
            tempFile.writeBytes(decoded)
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to decode json_base64 extra", e)
            Toast.makeText(this, "Invalid base64 data: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    /**
     * Sniff the leading bytes of [uri] and return `true` when the payload must be
     * rejected on the headless path (design §4.3 — headless plaintext import is refused).
     *
     * This is DEFENSE-IN-DEPTH only. The load-bearing gate is
     * [BackupRestoreManager.enforceHeadlessEncryptionPolicy], which runs at the same
     * seam that hands bytes to the parser (so it is not TOCTOU-bypassable via a
     * hostile ContentProvider that serves different bytes on separate stream opens).
     * This up-front sniff opens a *separate* stream and only short-circuits obvious
     * plaintext before the manager is even invoked.
     *
     * Returns `true` for:
     *  - PLAINTEXT_JSON / PLAINTEXT_ZIP — legacy plaintext backups (rejected).
     *  - UNKNOWN — an unrecognized/garbage payload (rejected; the manager would reject
     *    it too, but there is no reason to proceed).
     *  - ANY sniff FAILURE (stream throws / null) — **fail CLOSED**: an unsniffable
     *    payload is treated as plaintext on the headless path. (Previously fail-open,
     *    which — combined with the separate-stream TOCTOU — let an attacker throw on
     *    this open and serve plaintext on the import read.)
     *
     * Returns `false` ONLY for ENCRYPTED — the sole payload allowed to proceed to
     * the manager's decrypt-and-authenticate step, which is the real gate.
     */
    private fun payloadIsPlaintext(uri: Uri): Boolean {
        return try {
            val head = contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(EncryptedBackupFormat.HEADER_LEN)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled == buffer.size) buffer else buffer.copyOf(filled)
            }
            EncryptedBackupFormat.rejectAsPlaintextForHeadless(head)
        } catch (e: Exception) {
            // Unsniffable payload → fail CLOSED (reject) — the manager seam is the real
            // gate, but a hostile source that throws here must NOT slip past.
            android.util.Log.w(TAG, "Could not sniff import payload; treating as plaintext (fail closed)", e)
            true
        }
    }

    private fun performExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportConfig(uri, prefs)
                }
                headlessToast("Settings exported: $count")
                android.util.Log.i(TAG, "Export successful: $count preferences -> $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Export failed", e)
                headlessToast("Export failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }

    /**
     * Headless settings import — preserves legacy `importConfig` semantics
     * (destructive `merge=false` short-swipe REPLACE). Termux automation
     * callers depend on this; the user-visible preview/approval flow lives
     * in [SettingsActivity.performConfigImport].
     */
    private fun performImportHeadless(uri: Uri) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importConfig(uri, prefs)
                }
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(this@BackupRestoreActivity, prefs)
                headlessToast("Imported ${result.importedCount} settings")
                android.util.Log.i(
                    TAG,
                    "Import successful: imported=${result.importedCount}, skipped=${result.skippedCount}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import failed", e)
                headlessToast("Import failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }

    private fun performExportDictionaries(uri: Uri) {
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportDictionaries(uri)
                }
                headlessToast(
                    "Dictionaries exported: ${summary.customWordsCount} custom + ${summary.disabledWordsCount} disabled"
                )
                android.util.Log.i(
                    TAG,
                    "Dictionary export: ${summary.customWordsCount} custom + ${summary.disabledWordsCount} disabled across ${summary.languageCount} langs -> $uri"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary export failed", e)
                headlessToast("Dict export failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }

    /**
     * Headless dictionary import — preserves legacy `importDictionaries`
     * semantics (no preview, merge-only via first-writer-wins).
     * The user-visible preview/approval flow lives in
     * [SettingsActivity.performDictionaryImport].
     */
    private fun performImportDictionariesHeadless(uri: Uri) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDictionaries(uri)
                }
                LocalBroadcastManager.getInstance(this@BackupRestoreActivity)
                    .sendBroadcast(Intent(ACTION_DICTIONARY_IMPORTED))
                headlessToast("Imported ${result.userWordsImported} user + ${result.disabledWordsImported} disabled words")
                android.util.Log.i(
                    TAG,
                    "Dict import: userWords=${result.userWordsImported}, disabledWords=${result.disabledWordsImported}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary import failed", e)
                headlessToast("Dict import failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }

    private fun performExportClipboard(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    backupRestoreManager.exportClipboardHistory(uri)
                }
                headlessToast("Clipboard exported")
                android.util.Log.i(TAG, "Clipboard export successful: $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard export failed", e)
                headlessToast("Clipboard export failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }

    private fun performImportClipboard(uri: Uri) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importClipboardHistory(uri)
                }
                headlessToast("Imported ${result.importedCount} clipboard entries")
                android.util.Log.i(
                    TAG,
                    "Clipboard import: imported=${result.importedCount}, skipped=${result.skippedCount}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard import failed", e)
                headlessToast("Clipboard import failed: ${e.message?.take(60)}")
            } finally {
                finish()
            }
        }
    }
}
