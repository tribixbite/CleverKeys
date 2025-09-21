package tribixbite.keyboard2

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceActivity
import android.widget.*
import kotlinx.coroutines.*
import tribixbite.keyboard2.R

/**
 * Main settings activity for CleverKeys
 * Kotlin implementation with reactive preferences
 */
class SettingsActivity : Activity() {
    
    companion object {
        private const val TAG = "SettingsActivity"
    }
    
    private lateinit var neuralConfig: NeuralConfig
    private lateinit var config: Config
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        neuralConfig = NeuralConfig(prefs)
        config = Config.globalConfig()
        
        setupUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    private fun setupUI() = ScrollView(this).apply {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            
            // Header
            addView(TextView(this@SettingsActivity).apply {
                text = "⚙️ CleverKeys Settings"
                textSize = 24f
                setPadding(0, 0, 0, 32)
            })
            
            // Neural prediction section
            addView(createNeuralSection())
            
            // Keyboard appearance section
            addView(createAppearanceSection())
            
            // Advanced section
            addView(createAdvancedSection())
            
            // Action buttons
            addView(createActionSection())
        })
        
        setContentView(this)
    }
    
    private fun createNeuralSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        addView(createSectionHeader("🧠 Neural Prediction"))
        
        addView(CheckBox(this@SettingsActivity).apply {
            text = "Enable Neural Swipe Prediction"
            isChecked = neuralConfig.neuralPredictionEnabled
            setOnCheckedChangeListener { _, isChecked ->
                neuralConfig.neuralPredictionEnabled = isChecked
            }
        })
        
        addView(createSliderSetting(
            "Beam Width",
            neuralConfig.beamWidth,
            neuralConfig.beamWidthRange
        ) { neuralConfig.beamWidth = it })
        
        addView(createSliderSetting(
            "Max Word Length", 
            neuralConfig.maxLength,
            neuralConfig.maxLengthRange
        ) { neuralConfig.maxLength = it })
        
        addView(createFloatSliderSetting(
            "Confidence Threshold",
            neuralConfig.confidenceThreshold,
            neuralConfig.confidenceRange
        ) { neuralConfig.confidenceThreshold = it })
    }
    
    private fun createAppearanceSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        addView(createSectionHeader("🎨 Appearance"))
        
        addView(createSpinnerSetting(
            "Theme",
            listOf("System", "Light", "Dark", "Black"),
            getCurrentThemeIndex()
        ) { index -> handleThemeChange(index) })
        
        addView(createSliderSetting(
            "Keyboard Height (%)",
            config.keyboardHeightPercent,
            20..60
        ) { value ->
            config.keyboardHeightPercent = value
            saveConfigurationChange("keyboardHeightPercent", value)
        })
    }
    
    private fun createAdvancedSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        addView(createSectionHeader("🔧 Advanced"))
        
        addView(CheckBox(this@SettingsActivity).apply {
            text = "Enable Vibration"
            isChecked = config.vibrate_custom
            setOnCheckedChangeListener { _, isChecked ->
                config.vibrate_custom = isChecked
                saveConfigurationChange("vibrate_custom", isChecked)
            }
        })

        addView(CheckBox(this@SettingsActivity).apply {
            text = "Show Debug Information"
            isChecked = Logs.getDebugEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                Logs.setDebugEnabled(isChecked)
                saveConfigurationChange("debug_enabled", isChecked)
            }
        })
    }
    
    private fun createActionSection() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 32, 0, 0)
        
        addView(Button(this@SettingsActivity).apply {
            text = "🔄 Reset All"
            setOnClickListener { resetAllSettings() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        addView(Button(this@SettingsActivity).apply {
            text = "🧪 Open Calibration"
            setOnClickListener { openCalibration() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }
    
    private fun createSectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 18f
        setPadding(0, 24, 0, 16)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    
    private fun createSliderSetting(
        name: String,
        currentValue: Int,
        range: IntRange,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            
            val label = TextView(this@SettingsActivity).apply {
                text = "$name: $currentValue"
                textSize = 16f
            }
            addView(label)
            
            addView(SeekBar(this@SettingsActivity).apply {
                max = range.last - range.first
                progress = currentValue - range.first
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val value = range.first + progress
                            label.text = "$name: $value"
                            onChanged(value)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            })
        }
    }
    
    private fun createFloatSliderSetting(
        name: String,
        currentValue: Float,
        range: ClosedFloatingPointRange<Float>,
        onChanged: (Float) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            
            val label = TextView(this@SettingsActivity).apply {
                text = "$name: %.3f".format(currentValue)
                textSize = 16f
            }
            addView(label)
            
            addView(SeekBar(this@SettingsActivity).apply {
                max = 1000
                progress = ((currentValue - range.start) * 1000 / (range.endInclusive - range.start)).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val value = range.start + (progress / 1000f) * (range.endInclusive - range.start)
                            label.text = "$name: %.3f".format(value)
                            onChanged(value)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            })
        }
    }
    
    private fun createSpinnerSetting(
        name: String,
        options: List<String>,
        selectedIndex: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            
            addView(TextView(this@SettingsActivity).apply {
                text = name
                textSize = 16f
            })
            
            addView(Spinner(this@SettingsActivity).apply {
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, options).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(selectedIndex)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        onChanged(position)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            })
        }
    }

    /**
     * Get current theme index for spinner
     */
    private fun getCurrentThemeIndex(): Int {
        return when (config.theme) {
            R.style.Light -> 1
            R.style.Dark -> 2
            R.style.Black -> 3
            else -> 0 // System default
        }
    }

    /**
     * Handle theme change
     */
    private fun handleThemeChange(index: Int) {
        val newTheme = when (index) {
            1 -> R.style.Light
            2 -> R.style.Dark
            3 -> R.style.Black
            else -> R.style.Dark // Default to dark
        }

        config.theme = newTheme
        saveConfigurationChange("theme", newTheme)

        // Apply theme immediately
        setTheme(newTheme)
        recreate()
    }

    /**
     * Save configuration change to preferences
     */
    private fun saveConfigurationChange(key: String, value: Any) {
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val editor = prefs.edit()

        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
        }

        editor.apply()
        logD("Configuration saved: $key = $value")
    }

    /**
     * Show toast message
     */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun resetAllSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Settings")
            .setMessage("This will reset all settings to defaults. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                neuralConfig.resetToDefaults()
                toast("Settings reset to defaults")
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun openCalibration() {
        startActivity(Intent(this, SwipeCalibrationActivity::class.java))
    }
}