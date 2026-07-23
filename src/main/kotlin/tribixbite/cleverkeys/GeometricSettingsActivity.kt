package tribixbite.cleverkeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * Full Geometric Settings — the user-tunable knobs of the geometric (SHARK2) swipe engine
 * (WP9 R-1; spec `docs/specs/geometric-swipe-engine.md`). Companion to
 * [NeuralSettingsActivity], reached from Settings → Swipe Typing when the Prediction
 * Engine is Hybrid or Geometric.
 *
 * Deliberately exposes only THREE of the engine's 28 knobs — the ones with a
 * comprehensible user story and a bounded blast radius (suggestion count, frequency bias,
 * sloppy-endpoint tolerance). The rest are calibrated against the spec's measured accuracy
 * floors and stay code-only. Defaults MUST match `Defaults.GEO_*` == the engine's
 * `GeometricEngineConfig` defaults; GeometricEngineAdapter rebuilds its engine (cheap,
 * background re-warm) when any of these change.
 */
class GeometricSettingsActivity : ComponentActivity() {

    // Reactive state — MUST match Defaults in Config.kt
    private var maxResults by mutableIntStateOf(Defaults.GEO_MAX_RESULTS)
    private var frequencyWeight by mutableFloatStateOf(Defaults.GEO_FREQUENCY_WEIGHT)
    private var endpointInsetKw by mutableFloatStateOf(Defaults.GEO_ENDPOINT_INSET_KW)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedParameters()
        setContent {
            KeyboardTheme {
                GeometricSettingsScreen()
            }
        }
    }

    @Composable
    private fun GeometricSettingsScreen() {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Geometric Engine Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Tune the geometric (shape-matching) swipe engine used on non-QWERTY " +
                    "layouts in Hybrid mode, or on all layouts in Geometric mode. Other engine " +
                    "parameters are calibrated against measured accuracy and are not adjustable.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            ParameterSection("Decoding") {
                ParameterSlider(
                    title = "Max Suggestions",
                    description = "Ranked candidates emitted per swipe (suggestion bar shows the top few).",
                    value = maxResults.toFloat(),
                    valueRange = 3f..15f,
                    steps = 11,
                    onValueChange = {
                        maxResults = it.toInt()
                        updateParameters()
                    },
                    displayValue = maxResults.toString()
                )

                ParameterSlider(
                    title = "Frequency Weight",
                    description = "Bias toward common words vs. exact shape fidelity. Higher = " +
                        "common words win more often; 0 = pure shape matching.",
                    value = frequencyWeight,
                    valueRange = 0.0f..0.4f,
                    steps = 39,
                    onValueChange = {
                        frequencyWeight = it
                        updateParameters()
                    },
                    displayValue = "%.2f".format(frequencyWeight)
                )

                ParameterSlider(
                    title = "Endpoint Tolerance",
                    description = "Forgiveness for sloppy swipe start/end positions, in key widths. " +
                        "Higher = more forgiving; too high can surface wrong first/last letters.",
                    value = endpointInsetKw,
                    valueRange = 0.0f..0.8f,
                    steps = 15,
                    onValueChange = {
                        endpointInsetKw = it
                        updateParameters()
                    },
                    displayValue = "%.2f".format(endpointInsetKw)
                )
            }

            Button(
                onClick = {
                    maxResults = Defaults.GEO_MAX_RESULTS
                    frequencyWeight = Defaults.GEO_FREQUENCY_WEIGHT
                    endpointInsetKw = Defaults.GEO_ENDPOINT_INSET_KW
                    updateParameters()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset to Calibrated Defaults")
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

    /** Push to the live Config and persist — the adapter reads Config per decode. */
    private fun updateParameters() {
        try {
            val config = Config.globalConfig()
            config.geo_max_results = maxResults
            config.geo_frequency_weight = frequencyWeight
            config.geo_endpoint_inset_kw = endpointInsetKw
        } catch (e: Exception) {
            android.util.Log.e("GeometricSettings", "Error updating configuration", e)
        }
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        prefs.edit()
            .putInt("geo_max_results", maxResults)
            .putFloat("geo_frequency_weight", frequencyWeight)
            .putFloat("geo_endpoint_inset_kw", endpointInsetKw)
            .apply()
    }

    private fun loadSavedParameters() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        maxResults = Config.safeGetInt(prefs, "geo_max_results", Defaults.GEO_MAX_RESULTS)
        frequencyWeight = Config.safeGetFloat(prefs, "geo_frequency_weight", Defaults.GEO_FREQUENCY_WEIGHT)
        endpointInsetKw = Config.safeGetFloat(prefs, "geo_endpoint_inset_kw", Defaults.GEO_ENDPOINT_INSET_KW)
    }
}
