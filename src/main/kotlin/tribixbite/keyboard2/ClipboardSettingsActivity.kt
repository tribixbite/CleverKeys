package tribixbite.keyboard2

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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

/**
 * Clipboard Settings Activity - Phase 4 Implementation
 *
 * Provides configuration for clipboard history management including:
 * - Enable/disable clipboard history
 * - History limit (max number of entries)
 * - Duration (how long entries persist)
 * - Real-time statistics (active/pinned/expired counts)
 * - Clear all functionality
 *
 * All settings map to existing Config.kt properties:
 * - clipboard_history_enabled (default: false)
 * - clipboard_history_limit (default: 6)
 * - clipboard_history_duration (default: 5 minutes, -1 for never)
 */
@OptIn(ExperimentalMaterial3Api::class)
class ClipboardSettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "ClipboardSettings"
    }

    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    private lateinit var clipboardDatabase: ClipboardDatabase

    // Settings state
    private var clipboardEnabled by mutableStateOf(false)
    private var historyLimit by mutableStateOf(6)
    private var historyDuration by mutableStateOf(5) // minutes

    // Statistics state
    private var statsLoading by mutableStateOf(true)
    private var totalEntries by mutableStateOf(0)
    private var activeEntries by mutableStateOf(0)
    private var pinnedEntries by mutableStateOf(0)
    private var expiredEntries by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            loadCurrentSettings()

            // Initialize database
            lifecycleScope.launch {
                clipboardDatabase = ClipboardDatabase.getInstance(this@ClipboardSettingsActivity)
                loadStatistics()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing preferences", e)
            Toast.makeText(this, "Error loading settings: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            KeyboardTheme(darkTheme = true) {
                ClipboardSettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        lifecycleScope.launch {
            loadStatistics()
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Save to protected storage
        DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "clipboard_history_enabled" -> {
                clipboardEnabled = prefs.getBoolean(key, false)
            }
            "clipboard_history_limit" -> {
                historyLimit = prefs.getInt(key, 6)
            }
            "clipboard_history_duration" -> {
                historyDuration = prefs.getString(key, "5")?.toIntOrNull() ?: 5
            }
        }
    }

    private fun loadCurrentSettings() {
        clipboardEnabled = prefs.getBoolean("clipboard_history_enabled", false)
        historyLimit = prefs.getInt("clipboard_history_limit", 6)
        historyDuration = prefs.getString("clipboard_history_duration", "5")?.toIntOrNull() ?: 5
    }

    private suspend fun loadStatistics() {
        withContext(Dispatchers.IO) {
            try {
                statsLoading = true
                val stats = clipboardDatabase.getDatabaseStats().getOrNull()

                if (stats != null) {
                    totalEntries = stats["total_entries"] as? Int ?: 0
                    activeEntries = stats["active_entries"] as? Int ?: 0
                    pinnedEntries = stats["pinned_entries"] as? Int ?: 0
                    expiredEntries = stats["expired_entries"] as? Int ?: 0
                }

                statsLoading = false
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading statistics", e)
                statsLoading = false
            }
        }
    }

    private fun saveSetting(key: String, value: Any) {
        lifecycleScope.launch {
            try {
                val editor = prefs.edit()
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                }
                editor.apply()
                android.util.Log.d(TAG, "Setting saved: $key = $value")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(this@ClipboardSettingsActivity,
                    "Error saving: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearAllHistory() {
        lifecycleScope.launch {
            try {
                val result = clipboardDatabase.clearAllEntries().getOrNull()
                if (result != null && result > 0) {
                    Toast.makeText(
                        this@ClipboardSettingsActivity,
                        "Cleared $result clipboard entries",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadStatistics()
                } else {
                    Toast.makeText(
                        this@ClipboardSettingsActivity,
                        "No entries to clear",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error clearing history", e)
                Toast.makeText(
                    this@ClipboardSettingsActivity,
                    "Error clearing history: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @Composable
    private fun ClipboardSettingsScreen() {
        val scrollState = rememberScrollState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Clipboard Settings") },
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
                // Enable/Disable Clipboard History
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Clipboard History",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Store clipboard entries for quick access",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = clipboardEnabled,
                                onCheckedChange = {
                                    clipboardEnabled = it
                                    saveSetting("clipboard_history_enabled", it)
                                }
                            )
                        }
                    }
                }

                // About Clipboard History
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
                            text = "About Clipboard History",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Clipboard history stores your copied text for easy re-use. " +
                                    "You can pin important clips to prevent expiration. " +
                                    "All data is stored locally in an encrypted SQLite database.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Settings (only visible when enabled)
                if (clipboardEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "History Configuration",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // History Limit Slider
                            SliderSetting(
                                title = "History Limit",
                                description = "Maximum number of clipboard entries to store",
                                value = historyLimit.toFloat(),
                                valueRange = 1f..100f,
                                steps = 99,
                                onValueChange = {
                                    historyLimit = it.toInt()
                                    saveSetting("clipboard_history_limit", historyLimit)
                                },
                                displayValue = if (historyLimit == 100) "Unlimited" else "$historyLimit entries",
                                helpText = "Older entries are automatically removed when limit is reached"
                            )

                            // Duration Slider
                            SliderSetting(
                                title = "Entry Duration",
                                description = "How long clipboard entries persist before expiring",
                                value = if (historyDuration == -1) 1441f else historyDuration.toFloat(),
                                valueRange = 1f..1441f, // 1441 represents "Never expire"
                                steps = 1440,
                                onValueChange = {
                                    historyDuration = if (it.toInt() == 1441) -1 else it.toInt()
                                    saveSetting("clipboard_history_duration", historyDuration.toString())
                                },
                                displayValue = when (historyDuration) {
                                    -1 -> "Never expire"
                                    1 -> "1 minute"
                                    in 2..59 -> "$historyDuration minutes"
                                    60 -> "1 hour"
                                    in 61..1439 -> "${historyDuration / 60} hours"
                                    1440 -> "1 day"
                                    else -> "$historyDuration minutes"
                                },
                                helpText = "Pinned entries never expire regardless of this setting"
                            )
                        }
                    }

                    // Statistics Card
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
                                text = "Statistics",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (statsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                            } else {
                                StatRow("Total Entries", totalEntries.toString())
                                StatRow("Active Entries", activeEntries.toString())
                                StatRow("Pinned Entries", pinnedEntries.toString())
                                StatRow("Expired Entries", expiredEntries.toString())
                            }
                        }
                    }

                    // Clear All Button
                    Button(
                        onClick = { clearAllHistory() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear All History")
                    }

                    // Reset to Defaults Button
                    Button(
                        onClick = {
                            historyLimit = 6
                            historyDuration = 5
                            saveSetting("clipboard_history_limit", 6)
                            saveSetting("clipboard_history_duration", "5")
                            Toast.makeText(this@ClipboardSettingsActivity,
                                "Reset to default values",
                                Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text("Reset to Defaults")
                    }
                }
            }
        }
    }

    @Composable
    private fun SliderSetting(
        title: String,
        description: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        displayValue: String,
        helpText: String? = null
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Text(
                    text = displayValue,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            if (helpText != null) {
                Text(
                    text = helpText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }

    @Composable
    private fun StatRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
