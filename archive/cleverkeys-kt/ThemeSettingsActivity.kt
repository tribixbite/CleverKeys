package tribixbite.cleverkeys

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import tribixbite.cleverkeys.theme.*
import tribixbite.cleverkeys.ui.*

/**
 * Theme settings activity for CleverKeys.
 *
 * Provides access to:
 * - 18 predefined themes across 6 categories
 * - Custom theme creation with color picker
 * - Theme preview and selection
 * - Custom theme delete/export
 */
@OptIn(ExperimentalMaterial3Api::class)
class ThemeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KeyboardTheme {
                ThemeSettingsScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}

/**
 * Main theme settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { MaterialThemeManager(context) }
    val customThemeManager = remember { themeManager.getCustomThemeManager() }

    var showCustomThemeDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard Themes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        ThemeSelector(
            modifier = Modifier.padding(paddingValues),
            onThemeSelected = { themeId ->
                if (themeManager.selectTheme(themeId)) {
                    Toast.makeText(context, "Theme applied", Toast.LENGTH_SHORT).show()

                    // Restart keyboard service to apply theme
                    // (User will see change next time keyboard opens)
                } else {
                    Toast.makeText(context, "Failed to apply theme", Toast.LENGTH_SHORT).show()
                }
            },
            onCreateCustom = {
                showCustomThemeDialog = true
            },
            onDeleteTheme = { themeId ->
                showDeleteConfirmation = themeId
            },
            onExportTheme = { themeId ->
                customThemeManager.getCustomTheme(themeId)?.let { customTheme ->
                    val downloadsDir = File(context.getExternalFilesDir(null), "themes")
                    downloadsDir.mkdirs()

                    val fileName = "${customTheme.name.replace(" ", "_")}_theme.json"
                    val file = File(downloadsDir, fileName)

                    if (customThemeManager.exportTheme(customTheme, file)) {
                        Toast.makeText(
                            context,
                            "Theme exported to ${file.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()

                        // Share the file
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
                            putExtra(Intent.EXTRA_SUBJECT, "CleverKeys Theme: ${customTheme.name}")
                        }

                        context.startActivity(Intent.createChooser(shareIntent, "Share Theme"))
                    } else {
                        Toast.makeText(context, "Failed to export theme", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Custom theme dialog
    if (showCustomThemeDialog) {
        CustomThemeDialog(
            onDismiss = { showCustomThemeDialog = false },
            onCreateTheme = { name, colors ->
                val customTheme = CustomTheme(
                    name = name,
                    colors = colors
                )

                if (customThemeManager.saveCustomTheme(customTheme)) {
                    // Auto-select the new theme
                    themeManager.selectTheme(customTheme.id)
                    Toast.makeText(context, "Custom theme created!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to create theme (limit reached?)", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirmation?.let { themeId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text("Delete Theme?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customThemeManager.deleteCustomTheme(themeId)) {
                            Toast.makeText(context, "Theme deleted", Toast.LENGTH_SHORT).show()

                            // If deleted theme was selected, switch to default
                            if (themeManager.selectedThemeId.value == themeId) {
                                themeManager.selectTheme("default")
                            }
                        } else {
                            Toast.makeText(context, "Failed to delete theme", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirmation = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
