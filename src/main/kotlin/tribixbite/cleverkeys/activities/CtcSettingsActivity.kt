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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.theme.KeyboardTheme

/**
 * Full CTC Settings — the user-tunable knob of the CTC (trie-beam) swipe engine
 * (G5; spec `docs/specs/ctc-swipe-engine.md`). Companion to
 * [GeometricSettingsActivity], reached from Settings → Swipe Typing when the
 * Prediction Engine is CTC.
 *
 * Deliberately exposes ONE knob — commit-phase beam width. The scoring constants
 * (gamma/lambda/beta/prune) are `CtcScoringParams.tunedV2`, fitted offline on the
 * app-trie footing; exposing them would let users silently fall off the validated
 * operating point (the published-preset control measured −2.3 pt top-1).
 * Default MUST equal `Defaults.CTC_BEAM_WIDTH` (= the width every campaign
 * validation number was decoded at). CtcEngineAdapter re-reads Config per decode,
 * so changes apply on the next swipe with no engine rebuild beyond the memoized
 * decoder swap (no re-warm hook needed, unlike the geometric knobs).
 */
class CtcSettingsActivity : ComponentActivity() {

    private var beamWidth by mutableIntStateOf(Defaults.CTC_BEAM_WIDTH)

    // Re-homed from the deleted NeuralSettingsActivity (2026-08-18). The pref is an
    // ONNX Runtime session option, not a neural-model knob: CtcEngineAdapter reads it
    // when it builds the CTC encoder session through `onnx.ModelLoader`, so this is now
    // the only screen that owns it.
    private var onnxThreads by mutableIntStateOf(Defaults.ONNX_XNNPACK_THREADS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedParameters()
        setContent {
            KeyboardTheme {
                CtcSettingsScreen()
            }
        }
    }

    @Composable
    private fun CtcSettingsScreen() {
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
                text = stringResource(R.string.ctc_settings_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = stringResource(R.string.ctc_settings_intro),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            ParameterSection(stringResource(R.string.ctc_settings_beam_section)) {
                ParameterSlider(
                    title = stringResource(R.string.ctc_settings_beam_width_title),
                    description = stringResource(R.string.ctc_settings_beam_width_desc),
                    value = beamWidth.toFloat(),
                    valueRange = 10f..300f,
                    steps = 28,
                    onValueChange = {
                        beamWidth = it.toInt()
                        updateParameters()
                    },
                    displayValue = beamWidth.toString()
                )
            }

            ParameterSection(stringResource(R.string.ctc_settings_inference_section)) {
                ParameterSlider(
                    title = stringResource(R.string.ctc_settings_threads_title),
                    description = stringResource(R.string.ctc_settings_threads_desc),
                    value = onnxThreads.toFloat(),
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChange = {
                        onnxThreads = it.toInt()
                        updateParameters()
                    },
                    displayValue = "$onnxThreads threads"
                )
            }

            Button(
                onClick = {
                    beamWidth = Defaults.CTC_BEAM_WIDTH
                    onnxThreads = Defaults.ONNX_XNNPACK_THREADS
                    updateParameters()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ctc_settings_reset))
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
            config.ctc_beam_width = beamWidth
            config.onnx_xnnpack_threads = onnxThreads
        } catch (e: Exception) {
            android.util.Log.e("CtcSettings", "Error updating configuration", e)
        }
        DirectBootAwarePreferences.get_shared_preferences(this)
            .edit()
            .putInt("ctc_beam_width", beamWidth)
            .putInt("onnx_xnnpack_threads", onnxThreads)
            .apply()
    }

    /**
     * Loads the persisted knob, clamped into the slider's range — a settings import can
     * carry values the slider cannot represent, and an out-of-range Slider value silently
     * pins to an end of the track while the label shows the raw number.
     */
    private fun loadSavedParameters() {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        beamWidth = Config.safeGetInt(prefs, "ctc_beam_width", Defaults.CTC_BEAM_WIDTH)
            .coerceIn(10, 300)
        onnxThreads = Config.safeGetInt(prefs, "onnx_xnnpack_threads", Defaults.ONNX_XNNPACK_THREADS)
            .coerceIn(1, 8)
    }
}
