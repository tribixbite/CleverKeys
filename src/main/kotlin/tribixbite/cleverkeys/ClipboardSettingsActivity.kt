package tribixbite.cleverkeys

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
import tribixbite.cleverkeys.theme.KeyboardTheme

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
 * - clipboard_history_enabled (default: true — see Defaults.CLIPBOARD_HISTORY_ENABLED)
 * - clipboard_history_limit (default: 50 — see Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
 * - clipboard_history_duration (default: -1 / never expire — see Defaults.CLIPBOARD_HISTORY_DURATION)
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
    private var historyDuration by mutableStateOf(-1) // minutes; -1 = never expire (default)

    // Feature toggles
    private var clipboardTextOnly by mutableStateOf(false)
    private var pinnedEnabled by mutableStateOf(true)
    private var todoEnabled by mutableStateOf(true)

    // Media settings state
    private var mediaClipboardEnabled by mutableStateOf(true)
    private var maxMediaSizeMb by mutableStateOf(10)

    // Statistics state
    private var statsLoading by mutableStateOf(true)
    private var totalEntries by mutableStateOf(0)
    private var activeEntries by mutableStateOf(0)
    private var pinnedEntries by mutableStateOf(0)
    private var todoEntries by mutableStateOf(0)
    private var expiredEntries by mutableStateOf(0)
    private var mediaFileCount by mutableStateOf(0)
    private var mediaTotalSizeMb by mutableStateOf("0")

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
            // #35: Follow system dark/light mode
            KeyboardTheme {
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
                clipboardEnabled = prefs.getBoolean(key, Defaults.CLIPBOARD_HISTORY_ENABLED)
            }
            "clipboard_history_limit" -> {
                val savedLimit = prefs.getInt(key, Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
                historyLimit = if (savedLimit <= 0) 100 else savedLimit
            }
            "clipboard_history_duration" -> {
                historyDuration = prefs.getString(key, Defaults.CLIPBOARD_HISTORY_DURATION)?.toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK
            }
        }
    }

    private fun loadCurrentSettings() {
        clipboardEnabled = prefs.getBoolean("clipboard_history_enabled", Defaults.CLIPBOARD_HISTORY_ENABLED)
        // Map 0 (unlimited sentinel) back to 100 for slider range (1..100)
        val savedLimit = prefs.getInt("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
        historyLimit = if (savedLimit <= 0) 100 else savedLimit
        historyDuration = prefs.getString("clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION)?.toIntOrNull() ?: Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK
        // v4 feature toggles + media settings
        clipboardTextOnly = prefs.getBoolean("clipboard_text_only", false)
        pinnedEnabled = prefs.getBoolean("clipboard_pinned_enabled", true)
        todoEnabled = prefs.getBoolean("clipboard_todo_enabled", true)
        mediaClipboardEnabled = prefs.getBoolean("clipboard_media_enabled", true)
        maxMediaSizeMb = prefs.getInt("clipboard_max_media_size_mb", 10).coerceIn(1, 50)
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
                    todoEntries = stats["todo_entries"] as? Int ?: 0
                    expiredEntries = stats["expired_entries"] as? Int ?: 0
                }

                // v4: Calculate media storage stats
                try {
                    val mediaManager = ClipboardMediaManager(this@ClipboardSettingsActivity)
                    val mediaStats = mediaManager.getStorageStats()
                    mediaFileCount = mediaStats.first
                    mediaTotalSizeMb = String.format("%.1f", mediaStats.second / (1024.0 * 1024.0))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Error loading media stats: ${e.message}")
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
                if (result != null && result.first > 0) {
                    // Clean up media files from deleted entries
                    val mediaManager = ClipboardMediaManager(this@ClipboardSettingsActivity)
                    result.second.forEach { mediaPath ->
                        if (!clipboardDatabase.isMediaPathReferenced(mediaPath)) {
                            mediaManager.deleteMedia(mediaPath)
                        }
                    }
                    Toast.makeText(
                        this@ClipboardSettingsActivity,
                        "Cleared ${result.first} clipboard entries",
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

                        // Feature toggles (visible when clipboard is enabled)
                        if (clipboardEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            FeatureToggle(
                                title = "Text Only",
                                description = "Hide media entries from clipboard history",
                                checked = clipboardTextOnly,
                                onCheckedChange = {
                                    clipboardTextOnly = it
                                    saveSetting("clipboard_text_only", it)
                                }
                            )
                            FeatureToggle(
                                title = "Pinned Tab",
                                description = "Pin important clips to prevent expiration",
                                checked = pinnedEnabled,
                                onCheckedChange = {
                                    pinnedEnabled = it
                                    saveSetting("clipboard_pinned_enabled", it)
                                }
                            )
                            FeatureToggle(
                                title = "Todo Tab",
                                description = "Mark clips as to-do items",
                                checked = todoEnabled,
                                onCheckedChange = {
                                    todoEnabled = it
                                    saveSetting("clipboard_todo_enabled", it)
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
                                    // Save 0 (unlimited sentinel) when slider is at max position
                                    val valueToSave = if (historyLimit >= 100) 0 else historyLimit
                                    saveSetting("clipboard_history_limit", valueToSave)
                                    // Auto-link: when user sets count to Unlimited and duration
                                    // is still at default 7 days, auto-set to "Never expire"
                                    // to prevent unexpected expiry (Bug #6)
                                    if (historyLimit >= 100 && historyDuration == 10080) {
                                        historyDuration = -1
                                        saveSetting("clipboard_history_duration", "-1")
                                    }
                                },
                                displayValue = if (historyLimit >= 100) "Unlimited" else "$historyLimit entries",
                                helpText = "Older entries are automatically removed when limit is reached"
                            )

                            // Duration Slider — discrete presets from 1 hour to Never
                            // Presets: 1h, 6h, 12h, 1d, 3d, 7d, 14d, 30d, Never
                            val durationPresets = listOf(60, 360, 720, 1440, 4320, 10080, 20160, 43200, -1)
                            val currentIndex = durationPresets.indexOf(historyDuration).let {
                                if (it >= 0) it else durationPresets.indexOfLast { p -> p in 1..historyDuration }
                                    .coerceAtLeast(0)
                            }
                            SliderSetting(
                                title = "Entry Duration",
                                description = "How long clipboard entries persist before expiring",
                                value = currentIndex.toFloat(),
                                valueRange = 0f..(durationPresets.size - 1).toFloat(),
                                steps = durationPresets.size - 2, // intermediate steps between endpoints
                                onValueChange = {
                                    val idx = it.toInt().coerceIn(0, durationPresets.size - 1)
                                    historyDuration = durationPresets[idx]
                                    saveSetting("clipboard_history_duration", historyDuration.toString())
                                },
                                displayValue = when (historyDuration) {
                                    -1 -> "Never expire"
                                    60 -> "1 hour"
                                    360 -> "6 hours"
                                    720 -> "12 hours"
                                    1440 -> "1 day"
                                    4320 -> "3 days"
                                    10080 -> "7 days"
                                    20160 -> "14 days"
                                    43200 -> "30 days"
                                    else -> "${historyDuration / 60} hours"
                                },
                                helpText = if (historyLimit >= 100 && historyDuration != -1)
                                    "Warning: count is unlimited but entries will still expire after this duration"
                                else "Pinned entries never expire regardless of this setting"
                            )
                        }
                    }

                    // Media Clipboard Settings Card (v4) — hidden in text-only mode
                    if (!clipboardTextOnly) Card(
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
                                text = "Media Clipboard",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "When enabled, images, videos, PDFs, and other files " +
                                        "copied to the clipboard are saved and shown with thumbnails.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )

                            // Media clipboard toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Enable media clipboard",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Switch(
                                    checked = mediaClipboardEnabled,
                                    onCheckedChange = {
                                        mediaClipboardEnabled = it
                                        saveSetting("clipboard_media_enabled", it)
                                    }
                                )
                            }

                            // Max media file size slider
                            Text(
                                text = "Max media file size: ${maxMediaSizeMb}MB",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = maxMediaSizeMb.toFloat(),
                                onValueChange = { maxMediaSizeMb = it.toInt() },
                                onValueChangeFinished = {
                                    saveSetting("clipboard_max_media_size_mb", maxMediaSizeMb)
                                },
                                valueRange = 1f..50f,
                                steps = 48,
                                enabled = mediaClipboardEnabled
                            )

                            // Media storage stats
                            if (!statsLoading) {
                                Text(
                                    text = "Media files: $mediaFileCount ($mediaTotalSizeMb MB on disk)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                StatRow("History (Active)", activeEntries.toString())
                                if (pinnedEnabled) StatRow("Pinned", pinnedEntries.toString())
                                if (todoEnabled) StatRow("Todos", todoEntries.toString())
                                StatRow("Total", totalEntries.toString())
                                StatRow("Expired (pending cleanup)", expiredEntries.toString())
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
                            historyLimit = Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK
                            historyDuration = Defaults.CLIPBOARD_HISTORY_DURATION_FALLBACK
                            saveSetting("clipboard_history_limit", Defaults.CLIPBOARD_HISTORY_LIMIT_FALLBACK)
                            saveSetting("clipboard_history_duration", Defaults.CLIPBOARD_HISTORY_DURATION)
                            // Also reset per-item size, limit type, size limit, and privacy settings
                            saveSetting("clipboard_max_item_size_kb", Defaults.CLIPBOARD_MAX_ITEM_SIZE_KB)
                            saveSetting("clipboard_limit_type", Defaults.CLIPBOARD_LIMIT_TYPE)
                            saveSetting("clipboard_size_limit_mb", Defaults.CLIPBOARD_SIZE_LIMIT_MB)
                            saveSetting("clipboard_exclude_password_managers", Defaults.CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS)
                            saveSetting("clipboard_respect_sensitive_flag", Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)
                            saveSetting("clipboard_pane_height_percent", Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT)
                            // v4 feature toggles + media settings defaults
                            clipboardTextOnly = false
                            pinnedEnabled = true
                            todoEnabled = true
                            mediaClipboardEnabled = true
                            maxMediaSizeMb = 10
                            saveSetting("clipboard_text_only", false)
                            saveSetting("clipboard_pinned_enabled", true)
                            saveSetting("clipboard_todo_enabled", true)
                            saveSetting("clipboard_media_enabled", true)
                            saveSetting("clipboard_max_media_size_mb", 10)
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
    private fun FeatureToggle(
        title: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
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
