package tribixbite.keyboard2

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import tribixbite.keyboard2.R

/**
 * Modern Kotlin launcher activity for CleverKeys.
 *
 * Features:
 * - Keyboard setup guidance with animations
 * - Interactive keyboard testing area
 * - Settings access via menu
 * - Input method management
 * - Key event monitoring and display
 * - Coroutine-based async operations
 * - Enhanced error handling
 */
class LauncherActivity : Activity(), Handler.Callback {

    companion object {
        private const val TAG = "LauncherActivity"
        private const val ANIMATION_RESTART_DELAY = 3000L
        private const val ANIMATION_START_DELAY = 500L
        private const val MESSAGE_RESTART_ANIMATIONS = 0
    }

    // UI Components
    private lateinit var tryhereText: TextView
    private lateinit var tryhereArea: EditText

    // Animation management
    private val animations = mutableListOf<Animatable>()
    private lateinit var animationHandler: Handler

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val layoutId = resources.getIdentifier("launcher_activity", "layout", packageName)
            setContentView(layoutId)
            setupViews()
            setupAnimationHandler()
            Log.i(TAG, "LauncherActivity created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating LauncherActivity", e)
            // Fallback to programmatic UI if layout fails
            setupFallbackUI()
        }
    }

    override fun onStart() {
        super.onStart()
        setupAnimations()
        startAnimationCycle()
    }

    override fun onStop() {
        super.onStop()
        stopAnimationCycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        animations.clear()
    }

    private fun setupViews() {
        val textId = resources.getIdentifier("launcher_tryhere_text", "id", packageName)
        val areaId = resources.getIdentifier("launcher_tryhere_area", "id", packageName)
        tryhereText = findViewById(textId)
        tryhereArea = findViewById(areaId)

        // Add key event listener for API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryhereArea.addOnUnhandledKeyEventListener(TryhereOnUnhandledKeyEventListener())
        }
    }

    private fun setupAnimationHandler() {
        animationHandler = Handler(Looper.getMainLooper(), this)
    }

    private fun setupAnimations() {
        animations.clear()

        try {
            // Add animations if they exist in the layout
            val swipeId = resources.getIdentifier("launcher_anim_swipe", "id", packageName)
            val roundTripId = resources.getIdentifier("launcher_anim_round_trip", "id", packageName)
            val circleId = resources.getIdentifier("launcher_anim_circle", "id", packageName)
            findAnimation(swipeId)?.let { animations.add(it) }
            findAnimation(roundTripId)?.let { animations.add(it) }
            findAnimation(circleId)?.let { animations.add(it) }

            Log.d(TAG, "Loaded ${animations.size} animations")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading animations", e)
        }
    }

    private fun findAnimation(id: Int): Animatable? {
        return try {
            val imageView = findViewById<ImageView>(id)
            imageView?.drawable as? Animatable
        } catch (e: Exception) {
            Log.w(TAG, "Animation not found for id: $id", e)
            null
        }
    }

    private fun startAnimationCycle() {
        animationHandler.removeMessages(MESSAGE_RESTART_ANIMATIONS)
        animationHandler.sendEmptyMessageDelayed(MESSAGE_RESTART_ANIMATIONS, ANIMATION_START_DELAY)
    }

    private fun stopAnimationCycle() {
        animationHandler.removeMessages(MESSAGE_RESTART_ANIMATIONS)
        // Stop all running animations
        animations.forEach { animation ->
            try {
                if (animation.isRunning) {
                    animation.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping animation", e)
            }
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        return when (msg.what) {
            MESSAGE_RESTART_ANIMATIONS -> {
                restartAnimations()
                // Schedule next restart
                animationHandler.sendEmptyMessageDelayed(MESSAGE_RESTART_ANIMATIONS, ANIMATION_RESTART_DELAY)
                true
            }
            else -> false
        }
    }

    private fun restartAnimations() {
        animations.forEach { animation ->
            try {
                animation.start()
            } catch (e: Exception) {
                Log.w(TAG, "Error starting animation", e)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return try {
            val menuId = resources.getIdentifier("launcher_menu", "menu", packageName)
            menuInflater.inflate(menuId, menu)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu", e)
            false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val btnId = resources.getIdentifier("btnLaunchSettingsActivity", "id", packageName)
        return when (item.itemId) {
            btnId -> {
                launchSettingsActivity()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Launch the main settings activity
     */
    private fun launchSettingsActivity() {
        scope.launch {
            try {
                val intent = Intent(this@LauncherActivity, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "Launched SettingsActivity")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching SettingsActivity", e)
                showError("Could not open settings: ${e.message}")
            }
        }
    }

    /**
     * Launch system input method settings (called from XML button)
     */
    fun launch_imesettings(view: View) {
        scope.launch {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                Log.d(TAG, "Launched IME settings")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching IME settings", e)
                showError("Could not open keyboard settings: ${e.message}")
            }
        }
    }

    /**
     * Launch input method picker (called from XML button)
     */
    fun launch_imepicker(view: View) {
        scope.launch {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
                Log.d(TAG, "Launched IME picker")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching IME picker", e)
                showError("Could not open keyboard picker: ${e.message}")
            }
        }
    }

    /**
     * Launch neural settings activity (called from XML button)
     */
    fun launch_neural_settings(view: View) {
        scope.launch {
            try {
                val intent = Intent(this@LauncherActivity, NeuralSettingsActivity::class.java)
                startActivity(intent)
                Log.d(TAG, "Launched NeuralSettingsActivity")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching NeuralSettingsActivity", e)
                showError("Could not open neural settings: ${e.message}")
            }
        }
    }

    /**
     * Launch calibration activity (called from XML button)
     */
    fun launch_calibration(view: View) {
        scope.launch {
            try {
                val intent = Intent(this@LauncherActivity, SwipeCalibrationActivity::class.java)
                startActivity(intent)
                Log.d(TAG, "Launched SwipeCalibrationActivity")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching SwipeCalibrationActivity", e)
                showError("Could not open calibration: ${e.message}")
            }
        }
    }

    /**
     * Test neural prediction system (called from XML button)
     */
    fun test_neural_prediction(view: View) {
        scope.launch {
            try {
                Log.d(TAG, "Starting neural prediction test")

                // Create test swipe input
                val testPoints = listOf(
                    android.graphics.PointF(100f, 200f),
                    android.graphics.PointF(200f, 200f),
                    android.graphics.PointF(300f, 200f)
                )
                val testTimestamps = listOf(0L, 100L, 200L)
                val testInput = SwipeInput(testPoints, testTimestamps, emptyList())

                val neuralEngine = NeuralSwipeEngine(this@LauncherActivity, Config.globalConfig())

                if (neuralEngine.initialize()) {
                    val result = neuralEngine.predictAsync(testInput)

                    withContext(Dispatchers.Main) {
                        val message = if (result.isEmpty) {
                            "❌ Neural test failed: No predictions generated"
                        } else {
                            "✅ Neural test passed!\n\nTop predictions:\n${result.words.take(3).joinToString("\n") { "• $it" }}"
                        }

                        android.app.AlertDialog.Builder(this@LauncherActivity)
                            .setTitle("🧠 Neural Prediction Test")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()

                        Log.d(TAG, "Neural test completed: ${result.words.size} predictions")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showError("❌ Neural engine initialization failed")
                    }
                    Log.e(TAG, "Neural engine failed to initialize")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Neural test failed", e)
                withContext(Dispatchers.Main) {
                    showError("❌ Neural test failed: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Fallback UI creation if layout inflation fails
     */
    private fun setupFallbackUI() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val title = android.widget.TextView(this).apply {
            text = "⌨️ CleverKeys Launcher"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        // Try here text (fallback)
        tryhereText = android.widget.TextView(this).apply {
            text = "Type here to test keyboard..."
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tryhereText)

        // Try here area (fallback)
        tryhereArea = android.widget.EditText(this).apply {
            hint = "Test typing here"
            setPadding(16, 16, 16, 16)
        }
        layout.addView(tryhereArea)

        // Add key event listener for API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryhereArea.addOnUnhandledKeyEventListener(TryhereOnUnhandledKeyEventListener())
        }

        setContentView(layout)
        Log.i(TAG, "Fallback UI created")
    }

    /**
     * Key event listener for the test area
     */
    @TargetApi(Build.VERSION_CODES.P)
    inner class TryhereOnUnhandledKeyEventListener : View.OnUnhandledKeyEventListener {

        override fun onUnhandledKeyEvent(view: View, event: KeyEvent): Boolean {
            // Don't handle the back key
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                return false
            }

            // Key release of modifiers would erase interesting data
            if (KeyEvent.isModifierKey(event.keyCode)) {
                return false
            }

            // Build modifier string
            val modifiers = buildString {
                if (event.isAltPressed) append("Alt+")
                if (event.isShiftPressed) append("Shift+")
                if (event.isCtrlPressed) append("Ctrl+")
                if (event.isMetaPressed) append("Meta+")
            }

            // Get key name
            val keyName = KeyEvent.keyCodeToString(event.keyCode)
                .removePrefix("KEYCODE_")

            // Update display
            val displayText = "$modifiers$keyName"
            tryhereText.text = displayText

            Log.d(TAG, "Key event: $displayText")
            return false
        }
    }
}