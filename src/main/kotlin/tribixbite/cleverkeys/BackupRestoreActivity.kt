package tribixbite.cleverkeys

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
        @androidx.annotation.VisibleForTesting
        var testManagerOverride: BackupRestoreManager? = null
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var backupRestoreManager: BackupRestoreManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            backupRestoreManager = testManagerOverride ?: BackupRestoreManager(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // #70: Decode `json_base64` extra to a temp file URI when present —
        // bypasses scoped storage so callers can pipe content inline.
        val fileUri = intent.data
        val importUri = fileUri ?: resolveBase64Extra(intent)
        val action: (() -> Unit)? = when (intent.action) {
            ACTION_EXPORT_SETTINGS -> fileUri?.let { { performExport(it) } }
            ACTION_IMPORT_SETTINGS -> importUri?.let { { performImportHeadless(it) } }
            ACTION_EXPORT_DICTIONARIES -> fileUri?.let { { performExportDictionaries(it) } }
            ACTION_IMPORT_DICTIONARIES -> importUri?.let { { performImportDictionariesHeadless(it) } }
            ACTION_EXPORT_CLIPBOARD -> fileUri?.let { { performExportClipboard(it) } }
            ACTION_IMPORT_CLIPBOARD -> importUri?.let { { performImportClipboard(it) } }
            else -> null
        }

        if (action != null) {
            action()
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
        val msg = if (path != null) "$label: $path" else label
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
