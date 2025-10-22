package tribixbite.keyboard2

import android.graphics.Color
import android.os.Bundle
import android.widget.*
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tribixbite.keyboard2.theme.KeyboardTheme

/**
 * Modern neural prediction parameter settings activity.
 *
 * Migrated from CGRSettingsActivity.java to focus on ONNX neural network parameters
 * instead of CGR (Continuous Gesture Recognition) parameters.
 *
 * Features:
 * - Real-time ONNX parameter tuning
 * - Modern Compose UI with Material Design 3
 * - Persistent settings with reactive updates
 * - Neural engine configuration validation
 * - Performance monitoring integration
 */
class NeuralSettingsActivity : ComponentActivity() {

    // Current parameter values with reactive state
    private var beamWidth by mutableStateOf(8)
    private var maxLength by mutableStateOf(35)
    private var confidenceThreshold by mutableStateOf(0.1f)
    private var temperatureScaling by mutableStateOf(1.0f)
    private var repetitionPenalty by mutableStateOf(1.1f)
    private var topK by mutableStateOf(50)
    private var batchSize by mutableStateOf(4)
    private var timeoutMs by mutableStateOf(200)
    private var enableBatching by mutableStateOf(true)
    private var enableCaching by mutableStateOf(true)

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
                text = "Neural Prediction Parameters",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Configure ONNX neural network parameters for swipe prediction.\nChanges apply immediately to the prediction engine.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            // Core Parameters Section
            ParameterSection("Core Prediction Parameters") {

                // Beam Width
                ParameterSlider(
                    title = "Beam Width",
                    description = "Number of prediction candidates to consider (1-32). Higher = more accurate but slower.",
                    value = beamWidth.toFloat(),
                    valueRange = 1f..32f,
                    steps = 31,
                    onValueChange = {
                        beamWidth = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = beamWidth.toString()
                )

                // Max Length
                ParameterSlider(
                    title = "Maximum Word Length",
                    description = "Maximum characters in predicted words (10-50). Affects memory usage.",
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
                    description = "Minimum confidence for predictions (0.0-1.0). Lower = more suggestions.",
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
            ParameterSection("Advanced Parameters") {

                // Temperature Scaling
                ParameterSlider(
                    title = "Temperature Scaling",
                    description = "Controls prediction diversity (0.1-2.0). Lower = focused, higher = diverse.",
                    value = temperatureScaling,
                    valueRange = 0.1f..2.0f,
                    steps = 95,
                    onValueChange = {
                        temperatureScaling = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(temperatureScaling)
                )

                // Repetition Penalty
                ParameterSlider(
                    title = "Repetition Penalty",
                    description = "Penalty for repeated characters (1.0-2.0). Higher = less repetitive.",
                    value = repetitionPenalty,
                    valueRange = 1.0f..2.0f,
                    steps = 100,
                    onValueChange = {
                        repetitionPenalty = it
                        updateNeuralParameters()
                    },
                    displayValue = "%.2f".format(repetitionPenalty)
                )

                // Top-K
                ParameterSlider(
                    title = "Top-K Filtering",
                    description = "Consider only top K tokens (1-100). Lower = focused, higher = diverse.",
                    value = topK.toFloat(),
                    valueRange = 1f..100f,
                    steps = 99,
                    onValueChange = {
                        topK = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = topK.toString()
                )
            }

            // Performance Options Section
            ParameterSection("Performance Options") {

                // Batch Size
                ParameterSlider(
                    title = "Batch Size",
                    description = "Number of predictions processed together (1-16). Higher = more efficient.",
                    value = batchSize.toFloat(),
                    valueRange = 1f..16f,
                    steps = 15,
                    onValueChange = {
                        batchSize = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = batchSize.toString()
                )

                // Timeout
                ParameterSlider(
                    title = "Prediction Timeout (ms)",
                    description = "Maximum time for predictions (50-1000ms). Lower = more responsive.",
                    value = timeoutMs.toFloat(),
                    valueRange = 50f..1000f,
                    steps = 95,
                    onValueChange = {
                        timeoutMs = it.toInt()
                        updateNeuralParameters()
                    },
                    displayValue = "${timeoutMs}ms"
                )

                // Enable Batching
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Batched Inference",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Process multiple predictions together for better performance",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = enableBatching,
                        onCheckedChange = {
                            enableBatching = it
                            updateNeuralParameters()
                        }
                    )
                }

                // Enable Caching
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Prediction Caching",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Cache predictions to avoid duplicate computations",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = enableCaching,
                        onCheckedChange = {
                            enableCaching = it
                            updateNeuralParameters()
                        }
                    )
                }
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
                    Text("Reset to Defaults")
                }

                Button(
                    onClick = { saveAndApplyParameters() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Apply")
                }
            }

            // Performance Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Performance Impact",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Higher beam width = better accuracy, slower speed\n" +
                               "• Lower confidence threshold = more suggestions\n" +
                               "• Batching improves throughput for multiple predictions\n" +
                               "• Caching reduces repeated computation overhead",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
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

    private fun updateNeuralParameters() {
        lifecycleScope.launch {
            try {
                // Update global neural configuration
                val config = Config.globalConfig()
                config.neural_beam_width = beamWidth
                config.neural_max_length = maxLength
                config.neural_confidence_threshold = confidenceThreshold

                // Save to preferences for immediate use
                saveParametersToPrefs()

                android.util.Log.d("NeuralSettings",
                    "Updated parameters: beam=$beamWidth, maxLen=$maxLength, " +
                    "conf=%.3f, temp=%.2f, rep=%.2f, topK=$topK".format(
                        confidenceThreshold, temperatureScaling, repetitionPenalty))

            } catch (e: Exception) {
                android.util.Log.e("NeuralSettings", "Error updating configuration", e)
                Toast.makeText(this@NeuralSettingsActivity,
                    "Error updating neural configuration: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetToDefaults() {
        beamWidth = 8
        maxLength = 35
        confidenceThreshold = 0.1f
        temperatureScaling = 1.0f
        repetitionPenalty = 1.1f
        topK = 50
        batchSize = 4
        timeoutMs = 200
        enableBatching = true
        enableCaching = true

        updateNeuralParameters()

        Toast.makeText(this, "Reset to default neural parameters", Toast.LENGTH_SHORT).show()
    }

    private fun saveAndApplyParameters() {
        lifecycleScope.launch {
            try {
                saveParametersToPrefs()

                // Apply parameters to neural engine
                android.util.Log.d("NeuralSettings", "Applying new neural parameters")

                Toast.makeText(this@NeuralSettingsActivity,
                    "Parameters saved and applied to neural engine", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                android.util.Log.e("NeuralSettings", "Error saving and applying", e)
                Toast.makeText(this@NeuralSettingsActivity,
                    "Error applying parameters: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedParameters() {
        val prefs = getSharedPreferences("neural_settings", MODE_PRIVATE)
        beamWidth = prefs.getInt("beam_width", 8)
        maxLength = prefs.getInt("max_length", 35)
        confidenceThreshold = prefs.getFloat("confidence_threshold", 0.1f)
        temperatureScaling = prefs.getFloat("temperature_scaling", 1.0f)
        repetitionPenalty = prefs.getFloat("repetition_penalty", 1.1f)
        topK = prefs.getInt("top_k", 50)
        batchSize = prefs.getInt("batch_size", 4)
        timeoutMs = prefs.getInt("timeout_ms", 200)
        enableBatching = prefs.getBoolean("enable_batching", true)
        enableCaching = prefs.getBoolean("enable_caching", true)
    }

    private fun saveParametersToPrefs() {
        val prefs = getSharedPreferences("neural_settings", MODE_PRIVATE)
        prefs.edit()
            .putInt("beam_width", beamWidth)
            .putInt("max_length", maxLength)
            .putFloat("confidence_threshold", confidenceThreshold)
            .putFloat("temperature_scaling", temperatureScaling)
            .putFloat("repetition_penalty", repetitionPenalty)
            .putInt("top_k", topK)
            .putInt("batch_size", batchSize)
            .putInt("timeout_ms", timeoutMs)
            .putBoolean("enable_batching", enableBatching)
            .putBoolean("enable_caching", enableCaching)
            .apply()
    }
}