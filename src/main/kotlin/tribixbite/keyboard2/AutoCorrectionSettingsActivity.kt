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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.keyboard2.theme.KeyboardTheme

/**
 * Auto-Correction Settings Activity - Phase 2 Implementation
 *
 * Provides detailed configuration for auto-correction behavior including:
 * - Minimum word length for correction
 * - Character match threshold (Levenshtein ratio)
 * - Minimum frequency threshold for dictionary words
 *
 * All settings map to existing Config.kt properties:
 * - autocorrect_min_word_length (default: 3)
 * - autocorrect_char_match_threshold (default: 0.67 = 2/3 chars)
 * - autocorrect_confidence_min_frequency (default: 500)
 */
@OptIn(ExperimentalMaterial3Api::class)
class AutoCorrectionSettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "AutoCorrectionSettings"
    }

    // SharedPreferences
    private lateinit var prefs: SharedPreferences

    // Settings state
    private var autoCorrectEnabled by mutableStateOf(true)
    private var minWordLength by mutableStateOf(3)
    private var charMatchThreshold by mutableStateOf(0.67f)
    private var minFrequency by mutableStateOf(500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize preferences
        try {
            prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            loadCurrentSettings()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing preferences", e)
            Toast.makeText(this, "Error loading settings: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            KeyboardTheme(darkTheme = true) {
                AutoCorrectionSettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        // Save to protected storage
        DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "autocorrect_enabled" -> {
                autoCorrectEnabled = prefs.getBoolean(key, true)
            }
            "autocorrect_min_word_length" -> {
                minWordLength = prefs.getInt(key, 3)
            }
            "autocorrect_char_match_threshold" -> {
                charMatchThreshold = prefs.getFloat(key, 0.67f)
            }
            "autocorrect_confidence_min_frequency" -> {
                minFrequency = prefs.getInt(key, 500)
            }
        }
    }

    private fun loadCurrentSettings() {
        autoCorrectEnabled = prefs.getBoolean("autocorrect_enabled", true)
        minWordLength = prefs.getInt("autocorrect_min_word_length", 3)
        charMatchThreshold = prefs.getFloat("autocorrect_char_match_threshold", 0.67f)
        minFrequency = prefs.getInt("autocorrect_confidence_min_frequency", 500)
    }

    private fun saveSetting(key: String, value: Any) {
        lifecycleScope.launch {
            try {
                val editor = prefs.edit()
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
                editor.apply()
                android.util.Log.d(TAG, "Setting saved: $key = $value")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving setting: $key = $value", e)
                Toast.makeText(this@AutoCorrectionSettingsActivity,
                    "Error saving: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Composable
    private fun AutoCorrectionSettingsScreen() {
        val scrollState = rememberScrollState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Auto-Correction Settings") },
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
                // Enable/Disable Auto-Correction
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
                                    text = "Enable Auto-Correction",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Automatically correct misspelled words while typing",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = autoCorrectEnabled,
                                onCheckedChange = {
                                    autoCorrectEnabled = it
                                    saveSetting("autocorrect_enabled", it)
                                }
                            )
                        }
                    }
                }

                // About Auto-Correction
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
                            text = "About Auto-Correction",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Auto-correction compares typed words against the dictionary using " +
                                    "Levenshtein distance algorithm. Words are corrected if they match " +
                                    "closely enough (character match threshold) and appear frequently " +
                                    "in the dictionary (minimum frequency).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Settings (only visible when enabled)
                if (autoCorrectEnabled) {
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
                                text = "Correction Parameters",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Min Word Length Slider
                            SliderSetting(
                                title = "Minimum Word Length",
                                description = "Only correct words with at least this many characters",
                                value = minWordLength.toFloat(),
                                valueRange = 2f..10f,
                                steps = 8,
                                onValueChange = {
                                    minWordLength = it.toInt()
                                    saveSetting("autocorrect_min_word_length", minWordLength)
                                },
                                displayValue = "$minWordLength characters"
                            )

                            // Char Match Threshold Slider
                            SliderSetting(
                                title = "Character Match Threshold",
                                description = "Minimum similarity ratio for correction (higher = stricter)",
                                value = charMatchThreshold,
                                valueRange = 0.5f..1.0f,
                                steps = 50,
                                onValueChange = {
                                    charMatchThreshold = it
                                    saveSetting("autocorrect_char_match_threshold", charMatchThreshold)
                                },
                                displayValue = "%.2f (%.0f%%)".format(charMatchThreshold, charMatchThreshold * 100),
                                helpText = "Default: 0.67 (67%) means 2 out of 3 characters must match"
                            )

                            // Min Frequency Slider
                            SliderSetting(
                                title = "Minimum Frequency",
                                description = "Only suggest words that appear at least this often in the dictionary",
                                value = minFrequency.toFloat(),
                                valueRange = 100f..2000f,
                                steps = 19,
                                onValueChange = {
                                    minFrequency = it.toInt()
                                    saveSetting("autocorrect_confidence_min_frequency", minFrequency)
                                },
                                displayValue = "$minFrequency",
                                helpText = "Higher values = more common words only"
                            )
                        }
                    }

                    // Reset to Defaults Button
                    Button(
                        onClick = {
                            minWordLength = 3
                            charMatchThreshold = 0.67f
                            minFrequency = 500
                            saveSetting("autocorrect_min_word_length", 3)
                            saveSetting("autocorrect_char_match_threshold", 0.67f)
                            saveSetting("autocorrect_confidence_min_frequency", 500)
                            Toast.makeText(this@AutoCorrectionSettingsActivity,
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
}
