package tribixbite.keyboard2

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import tribixbite.keyboard2.theme.KeyboardTheme
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
    }

    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    private lateinit var backupRestoreManager: BackupRestoreManager

    // State
    private var isProcessing by mutableStateOf(false)
    private var showResultDialog by mutableStateOf(false)
    private var resultTitle by mutableStateOf("")
    private var resultMessage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences and backup manager
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            backupRestoreManager = BackupRestoreManager(this)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            KeyboardTheme(darkTheme = true) {
                BackupRestoreScreen()
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
                            enabled = !isProcessing,
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
                            enabled = !isProcessing,
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
                                enabled = !isProcessing,
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
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("Import")
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
                                    "• Dictionary imports merge with existing words (non-destructive)\n" +
                                    "• Some settings may not import if they are invalid or incompatible\n" +
                                    "• After importing, restart the keyboard for all changes to take effect\n" +
                                    "• Clipboard history export/import coming soon",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Result dialog
        if (showResultDialog) {
            AlertDialog(
                onDismissRequest = { showResultDialog = false },
                title = { Text(resultTitle) },
                text = { Text(resultMessage) },
                confirmButton = {
                    TextButton(onClick = { showResultDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }

        // Loading indicator
        if (isProcessing) {
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

    private fun performExport(uri: Uri) {
        lifecycleScope.launch {
            isProcessing = true
            try {
                withContext(Dispatchers.IO) {
                    backupRestoreManager.exportConfig(uri, prefs)
                }

                resultTitle = "Export Successful"
                resultMessage = "Configuration exported successfully.\n\n" +
                        "File: ${uri.lastPathSegment}\n\n" +
                        "You can now transfer this file to another device or keep it as a backup."
                showResultDialog = true

                android.util.Log.i(TAG, "Export successful: $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Export failed", e)
                resultTitle = "Export Failed"
                resultMessage = "Failed to export configuration:\n\n${e.message}"
                showResultDialog = true
            } finally {
                isProcessing = false
            }
        }
    }

    private fun performImport(uri: Uri) {
        lifecycleScope.launch {
            isProcessing = true
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

                resultTitle = "Import Successful"
                resultMessage = messageBuilder.toString()
                showResultDialog = true

                android.util.Log.i(TAG, "Import successful: imported=${result.importedCount}, skipped=${result.skippedCount}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Import failed", e)
                resultTitle = "Import Failed"
                resultMessage = "Failed to import configuration:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys backup file."
                showResultDialog = true
            } finally {
                isProcessing = false
            }
        }
    }

    private fun performExportDictionaries(uri: Uri) {
        lifecycleScope.launch {
            isProcessing = true
            try {
                withContext(Dispatchers.IO) {
                    backupRestoreManager.exportDictionaries(uri)
                }

                resultTitle = "Dictionary Export Successful"
                resultMessage = "Dictionaries exported successfully.\n\n" +
                        "File: ${uri.lastPathSegment}\n\n" +
                        "Includes:\n" +
                        "• User dictionary words\n" +
                        "• Disabled words\n\n" +
                        "You can now transfer this file to another device or keep it as a backup."
                showResultDialog = true

                android.util.Log.i(TAG, "Dictionary export successful: $uri")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary export failed", e)
                resultTitle = "Dictionary Export Failed"
                resultMessage = "Failed to export dictionaries:\n\n${e.message}"
                showResultDialog = true
            } finally {
                isProcessing = false
            }
        }
    }

    private fun performImportDictionaries(uri: Uri) {
        lifecycleScope.launch {
            isProcessing = true
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

                resultTitle = "Dictionary Import Successful"
                resultMessage = messageBuilder.toString()
                showResultDialog = true

                android.util.Log.i(TAG, "Dictionary import successful: userWords=${result.userWordsImported}, disabledWords=${result.disabledWordsImported}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Dictionary import failed", e)
                resultTitle = "Dictionary Import Failed"
                resultMessage = "Failed to import dictionaries:\n\n${e.message}\n\n" +
                        "Make sure the file is a valid CleverKeys dictionary backup file."
                showResultDialog = true
            } finally {
                isProcessing = false
            }
        }
    }
}
