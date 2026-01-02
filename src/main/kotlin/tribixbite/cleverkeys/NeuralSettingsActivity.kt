package tribixbite.cleverkeys

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * Modern neural prediction parameter settings activity.
 *
 * Features:
 * - Real-time ONNX parameter tuning
 * - Modern Compose UI with Material Design 3
 * - Persistent settings with reactive updates (using default shared prefs)
 * - Exposes critical beam search parameters for advanced users
 */
class NeuralSettingsActivity : ComponentActivity() {

    // Current parameter values with reactive state - MUST match Defaults in Config.kt
    private var beamWidth by mutableStateOf(Defaults.NEURAL_BEAM_WIDTH)
    private var maxLength by mutableStateOf(Defaults.NEURAL_MAX_LENGTH)
    private var confidenceThreshold by mutableStateOf(Defaults.NEURAL_CONFIDENCE_THRESHOLD)

    // Advanced Beam Search Parameters - MUST match Defaults in Config.kt
    private var beamAlpha by mutableStateOf(Defaults.NEURAL_BEAM_ALPHA)
    private var beamPruneConfidence by mutableStateOf(Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
    private var beamScoreGap by mutableStateOf(Defaults.NEURAL_BEAM_SCORE_GAP)
    private var adaptiveWidthStep by mutableStateOf(Defaults.NEURAL_ADAPTIVE_WIDTH_STEP)
    private var scoreGapStep by mutableStateOf(Defaults.NEURAL_SCORE_GAP_STEP)
    private var temperature by mutableStateOf(Defaults.NEURAL_TEMPERATURE)
    private var frequencyWeight by mutableStateOf(Defaults.NEURAL_FREQUENCY_WEIGHT)

    // Model Configuration - MUST match Defaults in Config.kt
    private var resamplingMode by mutableStateOf(Defaults.NEURAL_RESAMPLING_MODE)

    // Currently selected preset (null = custom settings)
    private var selectedPreset by mutableStateOf<NeuralPreset?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load saved parameters
        loadSavedParameters()

        setContent {
            KeyboardTheme(darkTheme = true) {
                NeuralSettingsScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NeuralSettingsScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Gesture Typing Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Configure the neural network swipe predictor. These settings affect accuracy, speed, and battery usage.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            // Preset Selector
            PresetSelector()

            // Core Parameters Section
            ParameterSection("Core Parameters") {

                // Beam Width
                ParameterSlider(
                    title = "Beam Width",
                    description = "Number of candidate paths to explore. Higher = better accuracy, slower speed.",
                    value = beamWidth.toFloat(),
                    valueRange = 1f..16f,
                    steps = 15,
                    onValueChange = {
                        beamWidth = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = beamWidth.toString()
                )

                // Max Length
                ParameterSlider(
                    title = "Max Sequence Length",
                    description = "Maximum length of predicted words. Increase for longer words.",
                    value = maxLength.toFloat(),
                    valueRange = 10f..50f,
                    steps = 40,
                    onValueChange = {
                        maxLength = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = maxLength.toString()
                )

                // Confidence Threshold
                ParameterSlider(
                    title = "Confidence Threshold",
                    description = "Minimum probability for a word to be suggested. Lower = more suggestions.",
                    value = confidenceThreshold,
                    valueRange = 0.0f..1.0f,
                    steps = 100,
                    onValueChange = {
                        confidenceThreshold = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.3f".format(confidenceThreshold)
                )
            }

            // Advanced Parameters Section
            ParameterSection("Advanced Beam Search") {

                // Beam Alpha (Length Penalty)
                ParameterSlider(
                    title = "Length Penalty (Alpha)",
                    description = "Controls bias towards longer words. >1.0 favors long words, <1.0 favors short words.",
                    value = beamAlpha,
                    valueRange = 0.0f..3.0f,
                    steps = 30,
                    onValueChange = {
                        beamAlpha = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(beamAlpha)
                )

                // Pruning Confidence
                ParameterSlider(
                    title = "Pruning Confidence",
                    description = "Aggressiveness of beam pruning. Higher (0.8+) = safer, Lower (0.03) = faster but risks missing words.",
                    value = beamPruneConfidence,
                    valueRange = 0.0f..1.0f,
                    steps = 100,
                    onValueChange = {
                        beamPruneConfidence = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(beamPruneConfidence)
                )

                // Score Gap
                ParameterSlider(
                    title = "Score Gap Threshold",
                    description = "Stop searching if top candidate is much better than others.",
                    value = beamScoreGap,
                    valueRange = 0.0f..50.0f,
                    steps = 50,
                    onValueChange = {
                        beamScoreGap = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.1f".format(beamScoreGap)
                )

                // Adaptive Width Step (NEW)
                ParameterSlider(
                    title = "Width Pruning Step",
                    description = "Decoding step to start beam width pruning. Higher = longer words complete before pruning.",
                    value = adaptiveWidthStep.toFloat(),
                    valueRange = 3f..20f,
                    steps = 17,
                    onValueChange = {
                        adaptiveWidthStep = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = adaptiveWidthStep.toString()
                )

                // Score Gap Step (NEW)
                ParameterSlider(
                    title = "Early Stop Step",
                    description = "Decoding step to start score gap early stopping. Higher = longer words get a chance.",
                    value = scoreGapStep.toFloat(),
                    valueRange = 3f..20f,
                    steps = 17,
                    onValueChange = {
                        scoreGapStep = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = scoreGapStep.toString()
                )
            }

            // Inference Tuning Section
            ParameterSection("Inference Tuning") {
                // Temperature
                ParameterSlider(
                    title = "Temperature",
                    description = "Softmax temperature. Lower = more confident/focused, Higher = more diverse.",
                    value = temperature,
                    valueRange = 0.1f..3.0f,
                    steps = 29,
                    onValueChange = {
                        temperature = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(temperature)
                )

                // Frequency Weight
                ParameterSlider(
                    title = "Vocabulary Frequency Weight",
                    description = "How much word frequency affects ranking. 0 = NN only, 1 = balanced, 2 = heavy freq.",
                    value = frequencyWeight,
                    valueRange = 0.0f..2.0f,
                    steps = 20,
                    onValueChange = {
                        frequencyWeight = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(frequencyWeight)
                )
            }

            // Model Configuration Section
            ParameterSection("Model Configuration") {
                // Trajectory Resampling
                ParameterDropdown(
                    title = "Trajectory Resampling",
                    description = "How to handle long swipe paths",
                    options = listOf("Discard Excess", "Interpolate", "Average"),
                    selectedIndex = when (resamplingMode) {
                        "discard" -> 0
                        "interpolate" -> 1
                        "average" -> 2
                        else -> 0
                    },
                    onSelectionChange = { index ->
                        resamplingMode = when (index) {
                            0 -> "discard"
                            1 -> "interpolate"
                            2 -> "average"
                            else -> "discard"
                        }
                        updateNeuralParameters()
                    }
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { resetToDefaults() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Defaults")
                }

                Button(
                    onClick = { saveAndApplyParameters() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Exit")
                }
            }
        }
    }

    @Composable
    private fun ParameterSection(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                content()
            }
        }
    }

    @Composable
    private fun ParameterSlider(
        title: String,
        description: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        displayValue: String
    ) {
        Column {
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
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ParameterDropdown(
        title: String,
        description: String,
        options: List<String>,
        selectedIndex: Int,
        onSelectionChange: (Int) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }

        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = options.getOrElse(selectedIndex) { options.firstOrNull() ?: "" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onSelectionChange(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    private fun updateNeuralParameters() {
        lifecycleScope.launch {
            try {
                // Update global neural configuration in memory
                val config = Config.globalConfig()
                config.neural_beam_width = beamWidth
                config.neural_max_length = maxLength
                config.neural_confidence_threshold = confidenceThreshold
                config.neural_beam_alpha = beamAlpha
                config.neural_beam_prune_confidence = beamPruneConfidence
                config.neural_beam_score_gap = beamScoreGap
                config.neural_adaptive_width_step = adaptiveWidthStep
                config.neural_score_gap_step = scoreGapStep
                config.neural_resampling_mode = resamplingMode
                config.neural_temperature = temperature
                config.neural_frequency_weight = frequencyWeight

                // Re-detect preset: if values were manually changed, this clears the preset
                // If values match a preset (including after applyPreset), it stays selected
                selectedPreset = detectCurrentPreset()

                // Save to preferences for immediate use
                saveParametersToPrefs()

                android.util.Log.d("NeuralSettings",
                    "Updated parameters: beam=$beamWidth, maxLen=$maxLength, " +
                    "conf=%.3f, temp=%.2f, freqWt=%.2f, preset=${selectedPreset?.name ?: "custom"}"
                        .format(confidenceThreshold, temperature, frequencyWeight))

            } catch (e: Exception) {
                android.util.Log.e("NeuralSettings", "Error updating configuration", e)
            }
        }
    }

    private fun resetToDefaults() {
        // Use Defaults.* constants for consistency across all settings
        // This is equivalent to applying the BALANCED preset
        applyPreset(NeuralPreset.BALANCED)
        // Note: applyPreset already shows a toast
    }

    private fun saveAndApplyParameters() {
        lifecycleScope.launch {
            try {
                saveParametersToPrefs()
                Toast.makeText(this@NeuralSettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                android.util.Log.e("NeuralSettings", "Error saving", e)
                Toast.makeText(this@NeuralSettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedParameters() {
        // CRITICAL FIX: Use default shared preferences to match Config.kt
        // All defaults MUST use Defaults.* constants for consistency
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)

        beamWidth = Config.safeGetInt(prefs, "neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
        maxLength = Config.safeGetInt(prefs, "neural_max_length", Defaults.NEURAL_MAX_LENGTH)
        confidenceThreshold = Config.safeGetFloat(prefs, "neural_confidence_threshold", Defaults.NEURAL_CONFIDENCE_THRESHOLD)

        beamAlpha = Config.safeGetFloat(prefs, "neural_beam_alpha", Defaults.NEURAL_BEAM_ALPHA)
        beamPruneConfidence = Config.safeGetFloat(prefs, "neural_beam_prune_confidence", Defaults.NEURAL_BEAM_PRUNE_CONFIDENCE)
        beamScoreGap = Config.safeGetFloat(prefs, "neural_beam_score_gap", Defaults.NEURAL_BEAM_SCORE_GAP)
        adaptiveWidthStep = Config.safeGetInt(prefs, "neural_adaptive_width_step", Defaults.NEURAL_ADAPTIVE_WIDTH_STEP)
        scoreGapStep = Config.safeGetInt(prefs, "neural_score_gap_step", Defaults.NEURAL_SCORE_GAP_STEP)
        resamplingMode = Config.safeGetString(prefs, "neural_resampling_mode", Defaults.NEURAL_RESAMPLING_MODE) ?: Defaults.NEURAL_RESAMPLING_MODE
        temperature = Config.safeGetFloat(prefs, "neural_temperature", Defaults.NEURAL_TEMPERATURE)
        frequencyWeight = Config.safeGetFloat(prefs, "neural_frequency_weight", Defaults.NEURAL_FREQUENCY_WEIGHT)

        // Detect if current settings match any preset
        selectedPreset = detectCurrentPreset()
    }

    private fun saveParametersToPrefs() {
        // CRITICAL FIX: Use default shared preferences to match Config.kt
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val editor = prefs.edit()

        editor.putInt("neural_beam_width", beamWidth)
        editor.putInt("neural_max_length", maxLength)
        editor.putFloat("neural_confidence_threshold", confidenceThreshold)

        editor.putFloat("neural_beam_alpha", beamAlpha)
        editor.putFloat("neural_beam_prune_confidence", beamPruneConfidence)
        editor.putFloat("neural_beam_score_gap", beamScoreGap)
        editor.putInt("neural_adaptive_width_step", adaptiveWidthStep)
        editor.putInt("neural_score_gap_step", scoreGapStep)
        editor.putString("neural_resampling_mode", resamplingMode)
        editor.putFloat("neural_temperature", temperature)
        editor.putFloat("neural_frequency_weight", frequencyWeight)

        // Save selected preset name (or "custom" if manually tweaked)
        editor.putString("neural_preset", selectedPreset?.name ?: "custom")

        editor.apply()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PresetSelector() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Quick Presets",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = "Choose a preset or customize individual settings below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NeuralPreset.values().forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { applyPreset(preset) },
                            label = { Text(preset.displayName) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // Show "Custom" indicator if settings don't match any preset
                if (selectedPreset == null) {
                    Text(
                        text = "⚙️ Custom settings",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    /**
     * Apply a preset, updating all parameters to the preset's values.
     */
    private fun applyPreset(preset: NeuralPreset) {
        beamWidth = preset.beamWidth
        maxLength = preset.maxLength
        confidenceThreshold = preset.confidenceThreshold
        beamAlpha = preset.beamAlpha
        beamPruneConfidence = preset.beamPruneConfidence
        beamScoreGap = preset.beamScoreGap
        adaptiveWidthStep = preset.adaptiveWidthStep
        scoreGapStep = preset.scoreGapStep
        temperature = preset.temperature
        frequencyWeight = preset.frequencyWeight

        selectedPreset = preset

        updateNeuralParameters()

        Toast.makeText(this, "Applied ${preset.displayName} preset", Toast.LENGTH_SHORT).show()
    }

    /**
     * Check if current settings match any preset, update selectedPreset accordingly.
     */
    private fun detectCurrentPreset(): NeuralPreset? {
        return NeuralPreset.values().find { preset ->
            preset.beamWidth == beamWidth &&
            preset.maxLength == maxLength &&
            preset.confidenceThreshold == confidenceThreshold &&
            preset.beamAlpha == beamAlpha &&
            preset.beamPruneConfidence == beamPruneConfidence &&
            preset.beamScoreGap == beamScoreGap &&
            preset.adaptiveWidthStep == adaptiveWidthStep &&
            preset.scoreGapStep == scoreGapStep &&
            preset.temperature == temperature &&
            preset.frequencyWeight == frequencyWeight
        }
    }
}
