package juloo.keyboard2

import android.app.Activity
import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import kotlinx.coroutines.*

/**
 * Launcher activity for CleverKeys setup and navigation
 * Kotlin implementation with modern UI patterns
 */
class LauncherActivity : Activity() {
    
    companion object {
        private const val TAG = "LauncherActivity"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    private fun setupUI() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
        
        // Title
        addView(TextView(this@LauncherActivity).apply {
            text = "⌨️ CleverKeys Setup"
            textSize = 28f
            setPadding(0, 0, 0, 32)
        })
        
        // Description
        addView(TextView(this@LauncherActivity).apply {
            text = "Modern Kotlin keyboard with neural swipe prediction"
            textSize = 16f
            setPadding(0, 0, 0, 24)
        })
        
        // Setup buttons
        addView(createButton("🔧 Enable Keyboard") { openKeyboardSettings() })
        addView(createButton("⚙️ CleverKeys Settings") { openCleverKeysSettings() })
        addView(createButton("🧠 Neural Calibration") { openCalibration() })
        addView(createButton("📊 Test Neural Prediction") { testNeuralPrediction() })
        
        setContentView(this)
    }
    
    private fun createButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        setPadding(16, 16, 16, 16)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }
    }
    
    /**
     * Open system keyboard settings
     */
    private fun openKeyboardSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            toast("Could not open keyboard settings")
            logE("Failed to open keyboard settings", e)
        }
    }
    
    /**
     * Open CleverKeys settings
     */
    private fun openCleverKeysSettings() {
        try {
            startActivity(Intent(this, CleverKeysSettings::class.java))
        } catch (e: Exception) {
            toast("Could not open CleverKeys settings")
            logE("Failed to open settings", e)
        }
    }
    
    /**
     * Open neural calibration
     */
    private fun openCalibration() {
        try {
            startActivity(Intent(this, SwipeCalibrationActivity::class.java))
        } catch (e: Exception) {
            toast("Could not open calibration")
            logE("Failed to open calibration", e)
        }
    }
    
    /**
     * Test neural prediction system
     */
    private fun testNeuralPrediction() {
        scope.launch {
            try {
                val testPoints = listOf(
                    PointF(100f, 200f),
                    PointF(200f, 200f),
                    PointF(300f, 200f)
                )
                val testTimestamps = listOf(0L, 100L, 200L)
                val testInput = SwipeInput(testPoints, testTimestamps, emptyList())
                
                val neuralEngine = NeuralSwipeEngine(this@LauncherActivity, Config.globalConfig())
                if (neuralEngine.initialize()) {
                    val result = neuralEngine.predictAsync(testInput)
                    
                    withContext(Dispatchers.Main) {
                        val message = if (result.isEmpty) {
                            "Neural test failed: No predictions"
                        } else {
                            "Neural test passed!\nPredictions: ${result.words.take(3)}"
                        }
                        
                        android.app.AlertDialog.Builder(this@LauncherActivity)
                            .setTitle("Neural Test Result")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toast("Neural engine initialization failed")
                    }
                }
            } catch (e: Exception) {
                logE("Neural test failed", e)
                withContext(Dispatchers.Main) {
                    toast("Test failed: ${e.message}")
                }
            }
        }
    }
}