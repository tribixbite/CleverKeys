package tribixbite.cleverkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.prefs.ExtraKeysPreference

/**
 * Extra Keys Configuration Activity - Manage Extra Keyboard Keys
 *
 * Features:
 * - Configure 85+ extra keys (system, navigation, editing, accents, symbols)
 * - Categorized key selection with search
 * - Key descriptions and preview
 * - Persist to SharedPreferences
 * - Integration with existing ExtraKeysPreference system
 */
class ExtraKeysConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ExtraKeysConfigScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraKeysConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // CRITICAL: Use DirectBootAwarePreferences to get the SAME SharedPreferences as the keyboard service
    val prefs = remember { DirectBootAwarePreferences.get_shared_preferences(context) }

    // Load enabled keys from preferences
    val enabledKeys = remember {
        mutableStateMapOf<String, Boolean>().apply {
            ExtraKeysPreference.extraKeys.forEach { keyName ->
                val prefKey = ExtraKeysPreference.prefKeyOfKeyName(keyName)
                this[keyName] = prefs.getBoolean(prefKey, ExtraKeysPreference.defaultChecked(keyName))
            }
        }
    }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Filter keys by search
    val filteredKeys = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            ExtraKeysPreference.extraKeys.toList()
        } else {
            ExtraKeysPreference.extraKeys.filter { keyName ->
                keyName.contains(searchQuery, ignoreCase = true) ||
                ExtraKeysPreference.keyDescription(context.resources, keyName)
                    ?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    // Categorize keys
    val categorizedKeys = remember(filteredKeys) {
        mapOf(
            "System" to filteredKeys.filter { it in listOf("alt", "meta", "compose", "voice_typing", "switch_clipboard", "change_method", "capslock") },
            "Navigation" to filteredKeys.filter { it in listOf("tab", "esc", "page_up", "page_down", "home", "end") },
            "Editing" to filteredKeys.filter { it.startsWith("copy") || it.startsWith("paste") || it.startsWith("cut") || it.startsWith("selectAll") || it.startsWith("undo") || it.startsWith("redo") || it.contains("delete_word") || it == "shareText" },
            "Formatting" to filteredKeys.filter { it in listOf("superscript", "subscript") },
            "Accents" to filteredKeys.filter { it.startsWith("accent_") },
            "Symbols" to filteredKeys.filter { it in listOf("€", "ß", "£", "§", "†", "ª", "º") },
            "Special Characters" to filteredKeys.filter { it in listOf("zwj", "zwnj", "nbsp", "nnbsp") },
            "Combining Characters" to filteredKeys.filter { it.startsWith("combining_") },
            "Functions" to filteredKeys.filter { it in listOf("switch_greekmath", "f11_placeholder", "f12_placeholder", "menu", "scroll_lock") }
        ).filterValues { it.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extra Keys Configuration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search extra keys...") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val enabledCount = enabledKeys.values.count { it }
                    Text(
                        text = "$enabledCount of ${ExtraKeysPreference.extraKeys.size} extra keys enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Selected keys will appear on the keyboard based on their preferred positions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Reset to defaults button
            OutlinedButton(
                onClick = {
                    ExtraKeysPreference.extraKeys.forEach { keyName ->
                        enabledKeys[keyName] = ExtraKeysPreference.defaultChecked(keyName)
                        val prefKey = ExtraKeysPreference.prefKeyOfKeyName(keyName)
                        prefs.edit().putBoolean(prefKey, enabledKeys[keyName] ?: false).apply()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset to Defaults")
            }

            // Categorized keys list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categorizedKeys.forEach { (category, keys) ->
                    item {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(keys) { keyName ->
                        ExtraKeyItem(
                            keyName = keyName,
                            isEnabled = enabledKeys[keyName] ?: false,
                            onToggle = { enabled ->
                                enabledKeys[keyName] = enabled
                                val prefKey = ExtraKeysPreference.prefKeyOfKeyName(keyName)
                                prefs.edit().putBoolean(prefKey, enabled).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExtraKeyItem(
    keyName: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val keyValue = remember { KeyValue.getKeyByName(keyName) }
    val title = remember { ExtraKeysPreference.keyTitle(keyName, keyValue) }
    val description = remember { ExtraKeysPreference.keyDescription(context.resources, keyName) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            Checkbox(
                checked = isEnabled,
                onCheckedChange = onToggle
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Key info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Show key name for debugging/clarity
                Text(
                    text = keyName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}
