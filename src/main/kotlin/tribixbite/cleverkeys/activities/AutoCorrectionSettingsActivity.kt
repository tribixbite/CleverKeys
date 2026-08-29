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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.theme.KeyboardTheme

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
 * - autocorrect_confidence_min_frequency (default: 100 = Config.Defaults.AUTOCORRECT_MIN_FREQUENCY)
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
    private var minWordLength by mutableIntStateOf(Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
    private var charMatchThreshold by mutableFloatStateOf(Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
    private var minFrequency by mutableIntStateOf(Defaults.AUTOCORRECT_MIN_FREQUENCY)

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
            // #35: Follow system dark/light mode
            KeyboardTheme {
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
                minFrequency = prefs.getInt(key, Defaults.AUTOCORRECT_MIN_FREQUENCY)
            }
        }
    }

    private fun loadCurrentSettings() {
        autoCorrectEnabled = prefs.getBoolean("autocorrect_enabled", true)
        minWordLength = prefs.getInt("autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
        charMatchThreshold = prefs.getFloat("autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
        minFrequency = prefs.getInt("autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)
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
                    title = { Text(stringResource(R.string.autocorrect_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
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
                                    text = stringResource(R.string.autocorrect_enable_title),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.autocorrect_activity_enable_desc),
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
                            text = stringResource(R.string.autocorrect_about_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.autocorrect_about_body),
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
                                text = stringResource(R.string.autocorrect_params_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Min Word Length Slider
                            SliderSetting(
                                title = stringResource(R.string.autocorrect_min_word_length_title),
                                description = stringResource(R.string.autocorrect_activity_min_length_desc),
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
                                title = stringResource(R.string.autocorrect_char_match_threshold_title),
                                description = stringResource(R.string.autocorrect_activity_match_threshold_desc),
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
                                title = stringResource(R.string.autocorrect_activity_min_freq_title),
                                description = stringResource(R.string.autocorrect_activity_min_freq_desc),
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
                            minWordLength = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
                            charMatchThreshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
                            minFrequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
                            saveSetting("autocorrect_min_word_length", Defaults.AUTOCORRECT_MIN_WORD_LENGTH)
                            saveSetting("autocorrect_char_match_threshold", Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD)
                            saveSetting("autocorrect_confidence_min_frequency", Defaults.AUTOCORRECT_MIN_FREQUENCY)
                            Toast.makeText(this@AutoCorrectionSettingsActivity,
                                "Reset to default values",
                                Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(stringResource(R.string.autocorrect_reset))
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
