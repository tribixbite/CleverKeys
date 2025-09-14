package juloo.keyboard2

import android.app.Activity
import android.graphics.PointF
import android.os.Bundle
import android.widget.*
import kotlinx.coroutines.*

/**
 * Modern Kotlin settings activity with reactive configuration
 * Replaces complex Java preference management with clean, type-safe approach
 */
class CleverKeysSettings : Activity() {
    
    companion object {
        private const val TAG = "CleverKeysSettings"
    }
    
    private lateinit var neuralConfig: NeuralConfig
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize configuration
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        neuralConfig = NeuralConfig(prefs)
        
        setupUI()
    }
    
    private fun setupUI() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
        
        // Title
        addView(TextView(this@CleverKeysSettings).apply {
            text = "⚙️ CleverKeys Settings"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        })
        
        // Neural prediction settings section
        addView(createNeuralSettingsSection())
        
        // General keyboard settings section
        addView(createKeyboardSettingsSection())
        
        // Action buttons
        addView(createActionButtons())
        
        setContentView(this)
    }
    
    private fun createNeuralSettingsSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        addView(TextView(this@CleverKeysSettings).apply {
            text = "🧠 Neural Prediction Settings"
            textSize = 18f
            setPadding(0, 16, 0, 16)
        })
        
        // Neural prediction toggle
        addView(CheckBox(this@CleverKeysSettings).apply {
            text = "Enable Neural Swipe Prediction"
            isChecked = neuralConfig.neuralPredictionEnabled
            setOnCheckedChangeListener { _, isChecked ->
                neuralConfig.neuralPredictionEnabled = isChecked
                toast(if (isChecked) "Neural prediction enabled" else "Neural prediction disabled")
            }
        })
        
        // Beam width setting
        addSliderSetting(
            "Beam Width", 
            neuralConfig.beamWidth, 
            neuralConfig.beamWidthRange
        ) { value ->
            neuralConfig.beamWidth = value
        }
        
        // Max length setting
        addSliderSetting(
            "Max Word Length",
            neuralConfig.maxLength,
            neuralConfig.maxLengthRange
        ) { value ->
            neuralConfig.maxLength = value
        }
        
        // Confidence threshold
        addFloatSliderSetting(
            "Confidence Threshold",
            neuralConfig.confidenceThreshold,
            neuralConfig.confidenceRange
        ) { value ->
            neuralConfig.confidenceThreshold = value
        }
    }
    
    private fun createKeyboardSettingsSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        addView(TextView(this@CleverKeysSettings).apply {
            text = "⌨️ Keyboard Settings"
            textSize = 18f
            setPadding(0, 32, 0, 16)
        })
        
        // Add keyboard-specific settings here
        addView(TextView(this@CleverKeysSettings).apply {
            text = "Additional keyboard settings will be added here"
            textSize = 14f
            setPadding(16, 8, 16, 8)
        })
    }
    
    private fun createActionButtons() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 32, 0, 0)
        
        addView(Button(this@CleverKeysSettings).apply {
            text = "🔄 Reset to Defaults"
            setOnClickListener {
                neuralConfig.resetToDefaults()
                toast("Settings reset to defaults")
                recreate() // Refresh UI
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        addView(Button(this@CleverKeysSettings).apply {
            text = "🧪 Test Predictions"
            setOnClickListener {
                GlobalScope.launch {
                    testPredictionSystem()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    
    private fun LinearLayout.addSliderSetting(
        name: String,
        currentValue: Int,
        range: IntRange,
        onValueChanged: (Int) -> Unit
    ) {
        addView(TextView(this@CleverKeysSettings).apply {
            text = "$name: $currentValue"
            textSize = 16f
            setPadding(0, 16, 0, 8)
            tag = "label_$name" // For updating value display
        })
        
        addView(SeekBar(this@CleverKeysSettings).apply {
            max = range.last - range.first
            progress = currentValue - range.first
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = range.first + progress
                        onValueChanged(value)
                        
                        // Update label
                        findViewWithTag<TextView>("label_$name")?.text = "$name: $value"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        })
    }
    
    private fun LinearLayout.addFloatSliderSetting(
        name: String,
        currentValue: Float,
        range: ClosedFloatingPointRange<Float>,
        onValueChanged: (Float) -> Unit
    ) {
        addView(TextView(this@CleverKeysSettings).apply {
            text = "$name: %.3f".format(currentValue)
            textSize = 16f
            setPadding(0, 16, 0, 8)
            tag = "label_$name"
        })
        
        addView(SeekBar(this@CleverKeysSettings).apply {
            max = 1000 // Fine granularity
            progress = ((currentValue - range.start) * 1000 / (range.endInclusive - range.start)).toInt()
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = range.start + (progress / 1000f) * (range.endInclusive - range.start)
                        onValueChanged(value)
                        
                        // Update label
                        findViewWithTag<TextView>("label_$name")?.text = "$name: %.3f".format(value)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        })
    }
    
    private suspend fun testPredictionSystem() = withContext(Dispatchers.IO) {
        try {
            // Create test swipe input
            val testPoints = listOf(
                PointF(100f, 200f),
                PointF(150f, 200f),
                PointF(200f, 200f),
                PointF(250f, 200f),
                PointF(300f, 200f)
            )
            
            val testTimestamps = testPoints.mapIndexed { index: Int, _: PointF ->
                System.currentTimeMillis() + index * 100L
            }
            
            val testInput = SwipeInput(testPoints, testTimestamps, emptyList())
            
            // Test prediction
            val neuralEngine = NeuralSwipeEngine(this@CleverKeysSettings, Config.globalConfig())
            if (neuralEngine.initialize()) {
                val result = neuralEngine.predictAsync(testInput)
                
                withContext(Dispatchers.Main) {
                    val message = if (result.isEmpty) {
                        "Test failed: No predictions generated"
                    } else {
                        "Test successful: ${result.size} predictions\nTop: ${result.topPrediction}"
                    }
                    
                    android.app.AlertDialog.Builder(this@CleverKeysSettings)
                        .setTitle("Prediction Test Result")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast("Failed to initialize neural engine")
                }
            }
            
        } catch (e: Exception) {
            logE("Test prediction failed", e)
            withContext(Dispatchers.Main) {
                toast("Test failed: ${e.message}")
            }
        }
    }
}