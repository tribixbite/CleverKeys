package tribixbite.cleverkeys

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tribixbite.cleverkeys.theme.KeyboardTheme
import tribixbite.cleverkeys.backup.DictImportPlan
import tribixbite.cleverkeys.backup.LangWord
import tribixbite.cleverkeys.backup.SettingsImportPlan
import tribixbite.cleverkeys.backup.ShortSwipeImportMode
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * Backup & Restore Activity - Phase 7 Implementation
 *
 * Provides configuration backup and restore functionality:
 * - Export SharedPreferences settings to JSON file
 * - Import SharedPreferences settings from JSON file
 * - Uses Storage Access Framework (SAF) for Android 15+ compatibility
 * - Displays import statistics (imported/skipped counts, screen size mismatch)
 * - Future: Dictionary and clipboard history export/import
 *
 * Backend: BackupRestoreManager.kt handles all serialization and validation
 */
@OptIn(ExperimentalMaterial3Api::class)
class BackupRestoreActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BackupRestoreActivity"
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

    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    private lateinit var backupRestoreManager: BackupRestoreManager

    // State hoisted into a ViewModel so plan + dialog state survive rotation.
    private val viewModel: BackupRestoreViewModel by viewModels()

    // #70: Headless mode — launched via intent action, no UI, finish() after operation
    private var isHeadless = false

    /** Toast the actual output path when running headless (no dialog to show). */
    private fun headlessToast(label: String) {
        if (!isHeadless) return
        val path = backupRestoreManager.lastOutputPath
        val msg = if (path != null) "$label: $path" else label
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences and backup manager
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            backupRestoreManager = testManagerOverride ?: BackupRestoreManager(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // #70: Handle programmatic Intent actions for automation (Termux, scripts)
        // Headless mode: perform operation, toast result, finish() — no UI shown
        val fileUri = intent.data
        // #70: json_base64 extra bypasses scoped storage entirely — decode to temp file
        val importUri = fileUri ?: resolveBase64Extra(intent)
        val headlessAction = when (intent.action) {
            ACTION_EXPORT_SETTINGS -> fileUri?.let { { performExport(it) } }
            ACTION_IMPORT_SETTINGS -> importUri?.let { { performImportHeadless(it) } }
            ACTION_EXPORT_DICTIONARIES -> fileUri?.let { { performExportDictionaries(it) } }
            ACTION_IMPORT_DICTIONARIES -> importUri?.let { { performImportDictionariesHeadless(it) } }
            ACTION_EXPORT_CLIPBOARD -> fileUri?.let { { performExportClipboard(it) } }
            ACTION_IMPORT_CLIPBOARD -> importUri?.let { { performImportClipboard(it) } }
            else -> null
        }
        if (headlessAction != null) {
            isHeadless = true
            headlessAction()
            return // Don't show UI — activity finishes after async operation
        }

        setContent {
            // #35: Follow system dark/light mode
            KeyboardTheme {
                BackupRestoreScreen()
                viewModel.settingsPreviewPlan?.let { plan ->
                    SettingsImportPreviewDialog(
                        plan = plan,
                        onCancel = { viewModel.settingsPreviewPlan = null },
                        onApply = { excluded, ssMode ->
                            viewModel.settingsPreviewPlan = null
                            applyPlannedSettings(plan, excluded, ssMode)
                        }
                    )
                }
                viewModel.dictPreviewPlan?.let { plan ->
                    DictionaryImportPreviewDialog(
                        plan = plan,
                        onCancel = { viewModel.dictPreviewPlan = null },
                        onApply = { excludedCustom, excludedDisabled ->
                            viewModel.dictPreviewPlan = null
                            applyPlannedDictionaries(plan, excludedCustom, excludedDisabled)
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun BackupRestoreScreen() {
        val scrollState = rememberScrollState()

        // Storage Access Framework launchers
        val exportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let { performExport(it) }
        }

        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { performImport(it) }
        }

        // Dictionary export/import launchers
        val exportDictionaryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let { performExportDictionaries(it) }
        }

        val importDictionaryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { performImportDictionaries(it) }
        }

        // Clipboard export/import launchers (JSON text-only)
        val exportClipboardLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let { performExportClipboard(it) }
        }

        val importClipboardLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { performImportClipboard(it) }
        }

        // Clipboard ZIP export/import launchers (full backup with media)
        val exportClipboardZipLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            uri?.let { performExportClipboardZip(it) }
        }

        val importClipboardZipLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { performImportClipboardZip(it) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Backup & Restore") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // About section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "About Backup & Restore",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Backup and restore your keyboard configuration including " +
                                    "all settings, preferences, and customizations. " +
                                    "Data is exported as a JSON file that can be imported later " +
                                    "or transferred to another device.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Export section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Export Configuration",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Export all keyboard settings to a JSON file. " +
                                    "The file includes metadata (app version, screen dimensions, export date) " +
                                    "for version-tolerant importing.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val filename = "CleverKeys_backup_$timestamp.json"
                                exportLauncher.launch(filename)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !viewModel.isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Export Settings")
                        }
                    }
                }

                // Import section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Import Configuration",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Import keyboard settings from a previously exported JSON file. " +
                                    "The import process validates all settings and will skip invalid values. " +
                                    "Screen size mismatches will be detected and reported.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        Button(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !viewModel.isProcessing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Import Settings")
                        }
                    }
                }

                // Dictionary Backup section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Dictionary Backup",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Export and import your custom dictionaries including user words and disabled words. " +
                                    "Import merges with existing words without overwriting.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val filename = "CleverKeys_dictionaries_$timestamp.json"
                                    exportDictionaryLauncher.launch(filename)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Export")
                            }

                            Button(
                                onClick = {
                                    importDictionaryLauncher.launch(arrayOf("application/json"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Import")
                            }
                        }
                    }
                }

                // Clipboard History Backup section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Clipboard History Backup",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Export and import your clipboard history. " +
                                    "JSON export is text-only (lightweight). " +
                                    "ZIP export includes media files (full backup).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )

                        // JSON text-only export/import
                        Text(
                            text = "Text Only (JSON)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val filename = "CleverKeys_clipboard_$timestamp.json"
                                    exportClipboardLauncher.launch(filename)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Export")
                            }

                            Button(
                                onClick = {
                                    importClipboardLauncher.launch(arrayOf("application/json"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Import")
                            }
                        }

                        // ZIP full backup export/import (includes media files)
                        Text(
                            text = "Full Backup (ZIP with media)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val filename = "CleverKeys_clipboard_full_$timestamp.zip"
                                    exportClipboardZipLauncher.launch(filename)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Export ZIP")
                            }

                            Button(
                                onClick = {
                                    importClipboardZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Import ZIP")
                            }
                        }
                    }
                }

                // Warning card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Important Notes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "• Importing settings will overwrite your current configuration\n" +
                                    "• Dictionary and clipboard imports merge with existing data (non-destructive)\n" +
                                    "• Some settings may not import if they are invalid or incompatible\n" +
                                    "• After importing, restart the keyboard for all changes to take effect",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Result dialog
        if (viewModel.showResultDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showResultDialog = false },
                title = { Text(viewModel.resultTitle) },
                text = { Text(viewModel.resultMessage) },
                confirmButton = {
                    TextButton(onClick = { viewModel.showResultDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }

        // Loading indicator
        if (viewModel.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    /**
     * #70: Decode json_base64 intent extra to a temp file, returning its URI.
     * Bypasses scoped storage entirely — the caller passes file content inline:
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
            viewModel.isProcessing = true
            try {
                val count = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportConfig(uri, prefs)
                }

                headlessToast("Settings exported: $count")
                viewModel.resultTitle = "Export Successful"
                viewModel.resultMessage = "Settings exported: $count\n\n" +
                        "File: ${uri.lastPathSegment}\n\n" +
                        "You can transfer this file to another device or keep it as a backup."
                viewModel.showResultDialog = true

                android.util.Log.i(TAG, "Export successful: $count preferences -> $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Export failed", e)
                headlessToast("Export failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Export Failed"
                viewModel.resultMessage = "Failed to export configuration:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    /**
     * SAF picker entry point: build a preview plan, then either skip
     * straight to the result dialog (no deltas) or surface the preview
     * dialog so the user can deselect keys / pick a short-swipe mode.
     *
     * The headless Intent path (Termux automation) does NOT route here —
     * see `performImportHeadless` for the legacy destructive default.
     */
    private fun performImport(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val plan = withContext(Dispatchers.IO) {
                    backupRestoreManager.buildSettingsImportPlan(uri, prefs)
                }
                val nothingToImport = plan.changes.isEmpty() &&
                    plan.parseSkippedKeys.isEmpty() &&
                    plan.shortSwipeImportSize == 0
                if (nothingToImport) {
                    viewModel.resultTitle = "No changes"
                    viewModel.resultMessage = "Backup file matches current settings."
                    viewModel.showResultDialog = true
                } else {
                    viewModel.settingsPreviewPlan = plan
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Build settings plan failed", e)
                viewModel.resultTitle = "Import Failed"
                viewModel.resultMessage = "Failed to read backup file:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
            }
        }
    }

    /**
     * Apply a previously-shown settings preview. Runs the IO commit on a
     * background thread, then mirrors the legacy result-dialog flow.
     * Always copies to protected storage afterward (Direct Boot survival).
     */
    private fun applyPlannedSettings(
        plan: SettingsImportPlan,
        excludedKeys: Set<String>,
        shortSwipeMode: ShortSwipeImportMode,
    ) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.applySettingsImportPlan(plan, excludedKeys, shortSwipeMode, prefs)
                }
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(this@BackupRestoreActivity, prefs)
                viewModel.resultTitle = "Import Successful"
                viewModel.resultMessage = buildSettingsResultMessage(result)
                viewModel.showResultDialog = true
                android.util.Log.i(
                    TAG,
                    "Import successful: imported=${result.importedCount}, skipped=${result.skippedCount}, excluded=${result.excludedByUserCount}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Apply settings plan failed", e)
                viewModel.resultTitle = "Import Failed"
                viewModel.resultMessage = "Failed to apply imported settings:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
            }
        }
    }

    /**
     * Headless Intent path: preserves the legacy `importConfig` semantics
     * (destructive `merge=false` short-swipe REPLACE). Termux automation
     * callers depend on this — do NOT route through the preview flow.
     */
    private fun performImportHeadless(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importConfig(uri, prefs)
                }

                // Copy to protected storage immediately after import
                DirectBootAwarePreferences.copy_preferences_to_protected_storage(this@BackupRestoreActivity, prefs)

                // Build result message
                val messageBuilder = StringBuilder()
                messageBuilder.append("Import completed successfully!\n\n")
                messageBuilder.append("Statistics:\n")
                messageBuilder.append("• Imported: ${result.importedCount} settings\n")
                messageBuilder.append("• Skipped: ${result.skippedCount} settings\n")

                if (result.sourceVersion != "unknown") {
                    messageBuilder.append("• Source version: ${result.sourceVersion}\n")
                }

                if (result.hasScreenSizeMismatch()) {
                    messageBuilder.append("\n⚠️ Screen Size Mismatch Detected:\n")
                    messageBuilder.append("• Source: ${result.sourceScreenWidth}x${result.sourceScreenHeight}\n")
                    messageBuilder.append("• Current: ${result.currentScreenWidth}x${result.currentScreenHeight}\n")
                    messageBuilder.append("\nSome visual settings may need adjustment.")
                }

                messageBuilder.append("\n\nPlease restart the keyboard for all changes to take effect.")

                viewModel.resultTitle = "Import Successful"
                viewModel.resultMessage = messageBuilder.toString()
                viewModel.showResultDialog = true

                headlessToast("Imported ${result.importedCount} settings")
                android.util.Log.i(TAG, "Import successful: imported=${result.importedCount}, skipped=${result.skippedCount}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import failed", e)
                headlessToast("Import failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Import Failed"
                viewModel.resultMessage = "Failed to import configuration:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys backup file."
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    private fun performExportDictionaries(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val summary = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportDictionaries(uri)
                }

                headlessToast(
                    "Dictionaries exported: ${summary.customWordsCount} custom + ${summary.disabledWordsCount} disabled"
                )
                viewModel.resultTitle = "Dictionary Export Successful"
                viewModel.resultMessage = "Custom words: ${summary.customWordsCount} " +
                        "(across ${summary.languageCount} languages)\n" +
                        "Disabled words: ${summary.disabledWordsCount}\n\n" +
                        "File: ${uri.lastPathSegment}"
                viewModel.showResultDialog = true

                android.util.Log.i(
                    TAG,
                    "Dictionary export successful: ${summary.customWordsCount} custom + ${summary.disabledWordsCount} disabled across ${summary.languageCount} langs -> $uri"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary export failed", e)
                headlessToast("Dict export failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Dictionary Export Failed"
                viewModel.resultMessage = "Failed to export dictionaries:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    /**
     * SAF picker entry point for dictionary import. Mirrors `performImport`:
     * builds a `DictImportPlan`, then either skips straight to the result
     * dialog (no new words) or surfaces the dictionary preview dialog so
     * the user can deselect per-word.
     *
     * The headless Intent path (Termux automation) does NOT route here —
     * see `performImportDictionariesHeadless` for the legacy semantics.
     */
    private fun performImportDictionaries(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val plan = withContext(Dispatchers.IO) {
                    backupRestoreManager.buildDictImportPlan(uri, prefs)
                }
                val nothingToImport = plan.perLanguage.values.all {
                    it.newCustomWords.isEmpty() && it.newDisabledWords.isEmpty()
                }
                if (nothingToImport) {
                    viewModel.resultTitle = "No changes"
                    viewModel.resultMessage = "Dictionary file has no new words to import."
                    viewModel.showResultDialog = true
                } else {
                    viewModel.dictPreviewPlan = plan
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Build dictionary plan failed", e)
                viewModel.resultTitle = "Import Failed"
                viewModel.resultMessage = "Failed to read dictionary file:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
            }
        }
    }

    /**
     * Apply a previously-shown dictionary preview. Single editor.commit()
     * across all per-language word lists; broadcasts ACTION_DICTIONARY_IMPORTED
     * so DictionaryManagerActivity refreshes its view.
     */
    private fun applyPlannedDictionaries(
        plan: DictImportPlan,
        excludedCustom: Set<LangWord>,
        excludedDisabled: Set<LangWord>,
    ) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.applyDictImportPlan(plan, excludedCustom, excludedDisabled, prefs)
                }
                viewModel.resultTitle = "Dictionary Import Successful"
                viewModel.resultMessage = buildDictResultMessage(result)
                viewModel.showResultDialog = true
                LocalBroadcastManager.getInstance(this@BackupRestoreActivity)
                    .sendBroadcast(Intent(ACTION_DICTIONARY_IMPORTED))
                android.util.Log.i(
                    TAG,
                    "Dictionary import successful: userWords=${result.userWordsImported}, disabledWords=${result.disabledWordsImported}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Apply dictionary plan failed", e)
                viewModel.resultTitle = "Import Failed"
                viewModel.resultMessage = "Failed to apply dictionary import:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
            }
        }
    }

    /**
     * Headless Intent path for dictionary import. Preserves legacy
     * `importDictionaries` semantics (no preview, merge-only via
     * first-writer-wins). Termux automation depends on this signature.
     */
    private fun performImportDictionariesHeadless(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importDictionaries(uri)
                }

                // Build result message
                val messageBuilder = StringBuilder()
                messageBuilder.append("Dictionary import completed successfully!\n\n")
                messageBuilder.append("Statistics:\n")
                messageBuilder.append("• New user words: ${result.userWordsImported}\n")
                messageBuilder.append("• New disabled words: ${result.disabledWordsImported}\n")

                if (result.sourceVersion != "unknown") {
                    messageBuilder.append("• Source version: ${result.sourceVersion}\n")
                }

                messageBuilder.append("\nNote: Import merges with existing words without overwriting.")

                viewModel.resultTitle = "Dictionary Import Successful"
                viewModel.resultMessage = messageBuilder.toString()
                viewModel.showResultDialog = true

                // Send a broadcast to notify DictionaryManagerActivity to refresh
                LocalBroadcastManager.getInstance(this@BackupRestoreActivity).sendBroadcast(Intent(ACTION_DICTIONARY_IMPORTED))

                headlessToast("Imported ${result.userWordsImported} user + ${result.disabledWordsImported} disabled words")
                android.util.Log.i(TAG, "Dictionary import successful: userWords=${result.userWordsImported}, disabledWords=${result.disabledWordsImported}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary import failed", e)
                headlessToast("Dict import failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Dictionary Import Failed"
                viewModel.resultMessage = "Failed to import dictionaries:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys dictionary backup file."
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    private fun performExportClipboard(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                withContext(Dispatchers.IO) {
                    backupRestoreManager.exportClipboardHistory(uri)
                }

                headlessToast("Clipboard exported")
                viewModel.resultTitle = "Clipboard Export Successful"
                viewModel.resultMessage = "Clipboard history exported successfully.\n\n" +
                        "File: ${uri.lastPathSegment}\n\n" +
                        "Includes:\n" +
                        "• All clipboard entries\n" +
                        "• Timestamps and expiry times\n" +
                        "• Pinned status\n\n" +
                        "You can now transfer this file to another device or keep it as a backup."
                viewModel.showResultDialog = true

                android.util.Log.i(TAG, "Clipboard export successful: $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard export failed", e)
                headlessToast("Clipboard export failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Clipboard Export Failed"
                viewModel.resultMessage = "Failed to export clipboard history:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    private fun performImportClipboard(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importClipboardHistory(uri)
                }

                val messageBuilder = StringBuilder()
                messageBuilder.append("Clipboard import completed successfully!\n\n")
                messageBuilder.append("Statistics:\n")
                messageBuilder.append("• Imported: ${result.importedCount} entries\n")
                messageBuilder.append("• Skipped: ${result.skippedCount} entries\n")

                if (result.sourceVersion != "unknown") {
                    messageBuilder.append("• Source version: ${result.sourceVersion}\n")
                }

                messageBuilder.append("\nNote: Import merges with existing history without overwriting.")

                viewModel.resultTitle = "Clipboard Import Successful"
                viewModel.resultMessage = messageBuilder.toString()
                viewModel.showResultDialog = true

                headlessToast("Imported ${result.importedCount} clipboard entries")
                android.util.Log.i(TAG, "Clipboard import successful: imported=${result.importedCount}, skipped=${result.skippedCount}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard import failed", e)
                headlessToast("Clipboard import failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Clipboard Import Failed"
                viewModel.resultMessage = "Failed to import clipboard history:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys clipboard backup file."
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish() // #70: close activity after headless operation
            }
        }
    }

    private fun performExportClipboardZip(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.exportClipboardHistoryZip(uri)
                }

                headlessToast("Clipboard ZIP exported")
                viewModel.resultTitle = "Full Clipboard Export Successful"
                viewModel.resultMessage = "Clipboard history exported with media files.\n\n" +
                        "File: ${uri.lastPathSegment}\n\n" +
                        "Includes:\n" +
                        "- ${result.exportedCount} clipboard entries\n" +
                        "- ${result.mediaFilesIncluded} media files\n\n" +
                        "This ZIP can be imported to fully restore all entries including images, videos, and other media."
                viewModel.showResultDialog = true

                android.util.Log.i(TAG, "Clipboard ZIP export successful: ${result.exportedCount} entries, ${result.mediaFilesIncluded} media files")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard ZIP export failed", e)
                headlessToast("ZIP export failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Clipboard ZIP Export Failed"
                viewModel.resultMessage = "Failed to export clipboard history with media:\n\n${e.message}"
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish()
            }
        }
    }

    private fun performImportClipboardZip(uri: Uri) {
        lifecycleScope.launch {
            viewModel.isProcessing = true
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRestoreManager.importClipboardHistoryZip(uri)
                }

                val messageBuilder = StringBuilder()
                messageBuilder.append("Full clipboard import completed!\n\n")
                messageBuilder.append("Statistics:\n")
                messageBuilder.append("- Imported: ${result.importedCount} entries\n")
                messageBuilder.append("- Skipped: ${result.skippedCount} duplicates\n")
                messageBuilder.append("- Media files restored: ${result.mediaFilesRestored}\n")

                if (result.sourceVersion != "unknown") {
                    messageBuilder.append("- Source: ${result.sourceVersion}\n")
                }

                messageBuilder.append("\nAll media files and thumbnails have been restored.")

                viewModel.resultTitle = "Full Clipboard Import Successful"
                viewModel.resultMessage = messageBuilder.toString()
                viewModel.showResultDialog = true

                headlessToast("Imported ${result.importedCount} entries + ${result.mediaFilesRestored} media")
                android.util.Log.i(TAG, "Clipboard ZIP import: ${result.importedCount} entries, ${result.mediaFilesRestored} media files")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clipboard ZIP import failed", e)
                headlessToast("ZIP import failed: ${e.message?.take(60)}")
                viewModel.resultTitle = "Clipboard ZIP Import Failed"
                viewModel.resultMessage = "Failed to import clipboard ZIP:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys clipboard ZIP backup."
                viewModel.showResultDialog = true
            } finally {
                viewModel.isProcessing = false
                if (isHeadless) finish()
            }
        }
    }
}
