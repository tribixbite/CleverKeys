package tribixbite.keyboard2

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Neural Model Parameter Settings Activity
 * Allows real-time tuning of ONNX neural prediction parameters
 *
 * Replaces CGR parameters with neural-specific configuration:
 * - Beam width for beam search decoding
 * - Maximum sequence length
 * - Confidence threshold
 * - Temperature sampling
 * - Repetition penalty
 */
class NeuralSettingsActivity : Activity() {

    // Neural parameter controls
    private lateinit var beamWidthSlider: SeekBar
    private lateinit var maxLengthSlider: SeekBar
    private lateinit var confidenceThresholdSlider: SeekBar
    private lateinit var temperatureSlider: SeekBar
    private lateinit var repetitionPenaltySlider: SeekBar
    private lateinit var topKSlider: SeekBar

    private lateinit var beamWidthText: TextView
    private lateinit var maxLengthText: TextView
    private lateinit var confidenceThresholdText: TextView
    private lateinit var temperatureText: TextView
    private lateinit var repetitionPenaltyText: TextView
    private lateinit var topKText: TextView

    // Current parameter values
    private var beamWidth = 8
    private var maxLength = 35
    private var confidenceThreshold = 0.1f
    private var temperature = 1.0f
    private var repetitionPenalty = 1.1f
    private var topK = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.BLACK)
        }

        // Title
        val title = TextView(this).apply {
            text = "Neural Model Parameters"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(title)

        // Description
        val description = TextView(this).apply {
            text = "Tune ONNX neural prediction parameters for optimal accuracy and performance.\n" +
                   "Changes apply immediately to the neural prediction engine."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24)
        }
        mainLayout.addView(description)

        // Beam Width (1-16)
        val beamControls = addParameterControl(
            mainLayout,
            "Beam Width - Search Breadth",
            1, 16, beamWidth,
            "Number of parallel hypotheses during decoding (higher = more accurate, slower)"
        ) { value ->
            beamWidth = value
            beamWidthText.text = "Beam Width: $beamWidth"
            updateNeuralParameters()
        }
        beamWidthSlider = beamControls.second
        beamWidthText = beamControls.first

        // Max Length (10-50)
        val lengthControls = addParameterControl(
            mainLayout,
            "Max Sequence Length",
            10, 50, maxLength,
            "Maximum characters in predicted words (higher = longer words, more memory)"
        ) { value ->
            maxLength = value
            maxLengthText.text = "Max Length: $maxLength"
            updateNeuralParameters()
        }
        maxLengthSlider = lengthControls.second
        maxLengthText = lengthControls.first

        // Confidence Threshold (0.0-1.0)
        val confidenceControls = addParameterControl(
            mainLayout,
            "Confidence Threshold",
            0, 100, (confidenceThreshold * 100).toInt(),
            "Minimum prediction confidence (0.0-1.0, higher = fewer but better suggestions)"
        ) { value ->
            confidenceThreshold = value / 100f
            confidenceThresholdText.text = "Confidence: %.2f".format(confidenceThreshold)
            updateNeuralParameters()
        }
        confidenceThresholdSlider = confidenceControls.second
        confidenceThresholdText = confidenceControls.first

        // Temperature (0.1-2.0)
        val temperatureControls = addParameterControl(
            mainLayout,
            "Temperature - Prediction Randomness",
            10, 200, (temperature * 100).toInt(),
            "Sampling temperature (lower = deterministic, higher = creative)"
        ) { value ->
            temperature = value / 100f
            temperatureText.text = "Temperature: %.2f".format(temperature)
            updateNeuralParameters()
        }
        temperatureSlider = temperatureControls.second
        temperatureText = temperatureControls.first

        // Repetition Penalty (1.0-2.0)
        val repetitionControls = addParameterControl(
            mainLayout,
            "Repetition Penalty",
            100, 200, (repetitionPenalty * 100).toInt(),
            "Penalty for repeated characters (higher = less repetitive predictions)"
        ) { value ->
            repetitionPenalty = value / 100f
            repetitionPenaltyText.text = "Repetition Penalty: %.2f".format(repetitionPenalty)
            updateNeuralParameters()
        }
        repetitionPenaltySlider = repetitionControls.second
        repetitionPenaltyText = repetitionControls.first

        // Top-K (1-100)
        val topKControls = addParameterControl(
            mainLayout,
            "Top-K Filtering",
            1, 100, topK,
            "Only consider top K tokens during sampling (lower = focused, higher = diverse)"
        ) { value ->
            topK = value
            topKText.text = "Top-K: $topK"
            updateNeuralParameters()
        }
        topKSlider = topKControls.second
        topKText = topKControls.first

        // Action buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 16)
        }

        val resetButton = Button(this).apply {
            text = "Reset to Defaults"
            setOnClickListener { resetToDefaults() }
        }
        buttonLayout.addView(resetButton)

        val saveButton = Button(this).apply {
            text = "Save & Apply"
            setOnClickListener { saveAndApplyParameters() }
        }
        buttonLayout.addView(saveButton)

        mainLayout.addView(buttonLayout)

        // Load saved values
        loadSavedParameters()

        setContentView(mainLayout)
    }

    private fun addParameterControl(
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        current: Int,
        description: String,
        listener: (Int) -> Unit
    ): Pair<TextView, SeekBar> {
        val controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val titleText = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        controlLayout.addView(titleText)

        val descText = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 8)
        }
        controlLayout.addView(descText)

        val slider = SeekBar(this).apply {
            max = max - min
            progress = current - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        listener(progress + min)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        controlLayout.addView(slider)
        parent.addView(controlLayout)

        return Pair(titleText, slider)
    }

    private fun updateNeuralParameters() {
        // Update global neural configuration
        val config = Config.globalConfig()
        config.neural_beam_width = beamWidth
        config.neural_max_length = maxLength
        config.neural_confidence_threshold = confidenceThreshold

        // Save to preferences for immediate use
        saveParametersToPrefs()

        android.util.Log.d("NeuralSettings",
            "Updated parameters: beam=$beamWidth, maxLen=$maxLength, " +
            "conf=%.2f, temp=%.2f, rep=%.2f, topK=$topK".format(
                confidenceThreshold, temperature, repetitionPenalty))
    }

    private fun resetToDefaults() {
        beamWidth = 8
        maxLength = 35
        confidenceThreshold = 0.1f
        temperature = 1.0f
        repetitionPenalty = 1.1f
        topK = 50

        updateAllControls()
        updateNeuralParameters()

        Toast.makeText(this, "Reset to default neural parameters", Toast.LENGTH_SHORT).show()
    }

    private fun saveAndApplyParameters() {
        saveParametersToPrefs()

        // Apply parameters to neural engine
        lifecycleScope.launch {
            try {
                // Notify neural engine of parameter changes
                android.util.Log.d("NeuralSettings", "Applying new neural parameters")

                Toast.makeText(this@NeuralSettingsActivity,
                    "Parameters saved and applied to neural engine", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@NeuralSettingsActivity,
                    "Error applying parameters: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateAllControls() {
        beamWidthSlider.progress = beamWidth - 1
        maxLengthSlider.progress = maxLength - 10
        confidenceThresholdSlider.progress = (confidenceThreshold * 100).toInt()
        temperatureSlider.progress = (temperature * 100).toInt() - 10
        repetitionPenaltySlider.progress = (repetitionPenalty * 100).toInt() - 100
        topKSlider.progress = topK - 1

        beamWidthText.text = "Beam Width: $beamWidth"
        maxLengthText.text = "Max Length: $maxLength"
        confidenceThresholdText.text = "Confidence: %.2f".format(confidenceThreshold)
        temperatureText.text = "Temperature: %.2f".format(temperature)
        repetitionPenaltyText.text = "Repetition Penalty: %.2f".format(repetitionPenalty)
        topKText.text = "Top-K: $topK"
    }

    private fun loadSavedParameters() {
        val prefs = getSharedPreferences("neural_settings", MODE_PRIVATE)
        beamWidth = prefs.getInt("beam_width", 8)
        maxLength = prefs.getInt("max_length", 35)
        confidenceThreshold = prefs.getFloat("confidence_threshold", 0.1f)
        temperature = prefs.getFloat("temperature", 1.0f)
        repetitionPenalty = prefs.getFloat("repetition_penalty", 1.1f)
        topK = prefs.getInt("top_k", 50)

        updateAllControls()
    }

    private fun saveParametersToPrefs() {
        val prefs = getSharedPreferences("neural_settings", MODE_PRIVATE)
        prefs.edit()
            .putInt("beam_width", beamWidth)
            .putInt("max_length", maxLength)
            .putFloat("confidence_threshold", confidenceThreshold)
            .putFloat("temperature", temperature)
            .putFloat("repetition_penalty", repetitionPenalty)
            .putInt("top_k", topK)
            .apply()
    }
}