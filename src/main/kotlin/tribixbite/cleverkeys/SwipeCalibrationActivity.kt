package tribixbite.cleverkeys

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

/**
 * Pure neural swipe calibration with ONNX transformer prediction
 * Matches web demo trace collection and animation styling
 */
class SwipeCalibrationActivity : Activity() {
    companion object {
        private const val TAG = "NeuralCalibration"

        // Calibration settings
        private const val WORDS_PER_SESSION = 20

        // QWERTY layout
        private val KEYBOARD_LAYOUT = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        )
    }

    // Full vocabulary for random word selection
    private var fullVocabulary: List<String>? = null
    private val random = Random()

    // Contraction mappings for apostrophe display
    private val nonPairedContractions = HashMap<String, String>()

    // UI Components
    private lateinit var instructionText: TextView
    private lateinit var currentWordText: TextView
    private lateinit var progressText: TextView
    private lateinit var benchmarkText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var keyboardView: NeuralKeyboardView
    private lateinit var nextButton: Button
    private lateinit var skipButton: Button
    private lateinit var exportButton: Button

    // Results logging
    private lateinit var resultsTextBox: TextView
    private lateinit var copyResultsButton: Button
    private val resultsLog = StringBuilder()

    // Swipe data collection (needed by keyboard view)
    private val currentSwipePoints = ArrayList<PointF>()
    private val currentSwipeTimestamps = ArrayList<Long>()
    private var swipeStartTime: Long = 0

    // Neural prediction
    private lateinit var neuralEngine: NeuralSwipeTypingEngine
    private lateinit var config: Config

    // Calibration state
    private var currentIndex = 0
    private lateinit var currentWord: String
    private lateinit var sessionWords: List<String>
    private lateinit var mlDataStore: SwipeMLDataStore
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var keyboardHeight: Int = 0

    // Performance tracking
    private val predictionTimes = ArrayList<Long>()
    private var correctPredictions = 0
    private var totalPredictions = 0

    // User configuration (needed for keyboard layout)
    private var characterSize = 1.15f
    private var labelTextSize = 0.33f
    private var keyVerticalMargin = 0.015f
    private var keyHorizontalMargin = 0.02f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== NEURAL CALIBRATION ACTIVITY STARTED ===")

        // Initialize ML data store
        mlDataStore = SwipeMLDataStore.getInstance(this)

        // Initialize neural prediction engine
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        Config.initGlobalConfig(prefs, resources, null, false)
        config = Config.globalConfig()
        neuralEngine = NeuralSwipeTypingEngine(this, config)
        // Set up logging callback for neural engine
        neuralEngine.setDebugLogger { message -> logToResults(message) }

        try {
            neuralEngine.initialize()
            Log.d(TAG, "Neural engine initialized successfully")
            logToResults("✅ Neural engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize neural engine", e)
            logToResults("❌ Neural engine initialization failed: ${e.message}")
            showErrorDialog("Neural models failed to load. Error: ${e.message}")
            return
        }

        // Get screen dimensions
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // Load user's keyboard height setting properly
        val foldTracker = FoldStateTracker(this)
        val foldableUnfolded = foldTracker.isUnfolded()

        // Get keyboard height percentage from user settings
        val isLandscape = resources.configuration.orientation ==
                          android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val keyboardHeightPref = if (isLandscape) {
            val key = if (foldableUnfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape"
            prefs.getInt(key, 50).also {
                Log.d(TAG, "Reading landscape height from key '$key': $it")
            }
        } else {
            val key = if (foldableUnfolded) "keyboard_height_unfolded" else "keyboard_height"
            prefs.getInt(key, 35).also {
                Log.d(TAG, "Reading portrait height from key '$key': $it")
            }
        }

        // Calculate keyboard height using user setting
        val keyboardHeightPercent = keyboardHeightPref / 100.0f
        keyboardHeight = (screenHeight * keyboardHeightPercent).toInt()
        Log.d(TAG, "Calculated keyboard height: $keyboardHeight pixels ($keyboardHeightPref% of $screenHeight)")

        // Load user's text and margin settings
        characterSize = Config.safeGetFloat(prefs, "character_size", 1.15f)
        labelTextSize = 0.33f
        keyVerticalMargin = Config.safeGetFloat(prefs, "key_vertical_margin", 1.5f) / 100
        keyHorizontalMargin = Config.safeGetFloat(prefs, "key_horizontal_margin", 2.0f) / 100

        // Load contraction mappings for apostrophe display
        loadContractionMappings()

        // OPTIMIZATION: Prepare random session words from full vocabulary
        sessionWords = prepareRandomSessionWords()

        setupUI()
        setupKeyboard()
        showNextWord()
    }

    /**
     * OPTIMIZATION: Load full vocabulary and select random test words
     * Replaces fixed word list with truly random sampling from available dictionaries
     */
    private fun prepareRandomSessionWords(): List<String> {
        // Load full vocabulary if not already loaded
        if (fullVocabulary == null) {
            loadFullVocabulary()
        }

        val sessionWords = ArrayList<String>()
        val vocab = fullVocabulary

        if (vocab != null && vocab.size > WORDS_PER_SESSION) {
            // Select completely random words from full vocabulary
            val selectedWords = HashSet<String>() // Prevent duplicates

            while (selectedWords.size < WORDS_PER_SESSION) {
                val randomIndex = random.nextInt(vocab.size)
                val word = vocab[randomIndex]
                selectedWords.add(word) // No filtering - use any word from dictionary
            }

            sessionWords.addAll(selectedWords)
            Log.d(TAG, "Selected ${sessionWords.size} completely random words from ${vocab.size} total vocabulary")
        } else {
            Log.e(TAG, "No vocabulary loaded - cannot generate session words")
            return ArrayList() // Return empty list if no vocabulary
        }

        return sessionWords
    }

    /**
     * Load full vocabulary from dictionary assets
     * Loads from both en.txt and en_enhanced.txt for maximum word variety
     */
    private fun loadFullVocabulary() {
        try {
            Log.d(TAG, "Loading full vocabulary from multiple dictionaries for random test words...")

            val vocabulary = ArrayList<String>()
            val uniqueWords = HashSet<String>() // Prevent duplicates across files

            // Load from both dictionary files for maximum variety
            val dictFiles = arrayOf("dictionaries/en.txt", "dictionaries/en_enhanced.txt")

            for (dictFile in dictFiles) {
                try {
                    val inputStream = assets.open(dictFile)
                    val reader = BufferedReader(InputStreamReader(inputStream))

                    var fileWordCount = 0
                    reader.forEachLine { line ->
                        val word = line.trim().lowercase()
                        if (word.isNotEmpty() && uniqueWords.add(word)) { // Only add if not already present
                            vocabulary.add(word)
                            fileWordCount++
                        }
                    }

                    reader.close()
                    Log.d(TAG, "Loaded $fileWordCount words from $dictFile")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load $dictFile: ${e.message}")
                }
            }

            fullVocabulary = vocabulary
            Log.d(TAG, "Total loaded: ${vocabulary.size} unique words for random selection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load full vocabulary", e)
            throw RuntimeException("Vocabulary loading failed - no fallback allowed", e)
        }
    }

    /**
     * Load non-paired contraction mappings for apostrophe display in calibration
     * Loads mapping of "dont" -> "don't" for display purposes
     */
    private fun loadContractionMappings() {
        try {
            val inputStream = assets.open("dictionaries/contractions_non_paired.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonBuilder = StringBuilder()
            reader.forEachLine { line ->
                jsonBuilder.append(line)
            }
            reader.close()

            // Parse JSON object: { "dont": "don't", "cant": "can't", ... }
            val jsonObj = JSONObject(jsonBuilder.toString())
            val keys = jsonObj.keys()

            while (keys.hasNext()) {
                val withoutApostrophe = keys.next().lowercase()
                val withApostrophe = jsonObj.getString(withoutApostrophe).lowercase()
                nonPairedContractions[withoutApostrophe] = withApostrophe
            }

            Log.d(TAG, "Loaded ${nonPairedContractions.size} non-paired contractions for calibration display")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load contraction mappings: ${e.message}")
            nonPairedContractions.clear()
        }
    }

    private fun setupUI() {
        // Main RelativeLayout like original
        val mainLayout = RelativeLayout(this)
        mainLayout.setBackgroundColor(Color.BLACK)

        // Create top content layout
        val topLayout = LinearLayout(this)
        topLayout.orientation = LinearLayout.VERTICAL
        topLayout.setPadding(40, 40, 40, 20)
        val topParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        topLayout.layoutParams = topParams

        // Title
        val title = TextView(this)
        title.text = "🧠 Neural Swipe Calibration"
        title.textSize = 24f
        title.setTextColor(0xFF00d4ff.toInt()) // Neon blue
        title.setPadding(0, 0, 0, 20)
        topLayout.addView(title)

        // Instructions
        instructionText = TextView(this)
        instructionText.text = "Swipe the word shown below - auto-advances on completion"
        instructionText.setTextColor(Color.GRAY)
        instructionText.setPadding(0, 0, 0, 10)
        topLayout.addView(instructionText)

        // Current word display
        currentWordText = TextView(this)
        currentWordText.textSize = 32f
        currentWordText.setTextColor(Color.CYAN)
        currentWordText.setPadding(0, 20, 0, 20)
        topLayout.addView(currentWordText)

        // Progress
        progressText = TextView(this)
        progressText.setTextColor(Color.WHITE)
        topLayout.addView(progressText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = WORDS_PER_SESSION
        topLayout.addView(progressBar)

        // Benchmark display
        benchmarkText = TextView(this)
        benchmarkText.setTextColor(0xFF00d4ff.toInt())
        benchmarkText.textSize = 14f
        benchmarkText.setPadding(0, 10, 0, 10)
        topLayout.addView(benchmarkText)

        // Playground button
        val playgroundButton = Button(this)
        playgroundButton.text = "🎮 Neural Playground"
        playgroundButton.textSize = 14f
        playgroundButton.setOnClickListener { showNeuralPlayground() }
        playgroundButton.setBackgroundColor(0xFF4CAF50.toInt())
        playgroundButton.setTextColor(Color.WHITE)
        playgroundButton.setPadding(8, 8, 8, 8)
        topLayout.addView(playgroundButton)

        // Results textbox with copy button
        val resultsHeaderLayout = LinearLayout(this)
        resultsHeaderLayout.orientation = LinearLayout.HORIZONTAL
        resultsHeaderLayout.setPadding(16, 16, 16, 8)

        val resultsLabel = TextView(this)
        resultsLabel.text = "🔍 Neural Results Log:"
        resultsLabel.textSize = 14f
        resultsLabel.setTextColor(0xFF00d4ff.toInt())
        resultsLabel.typeface = Typeface.DEFAULT_BOLD
        resultsLabel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        resultsHeaderLayout.addView(resultsLabel)

        copyResultsButton = Button(this)
        copyResultsButton.text = "📋"
        copyResultsButton.textSize = 16f
        copyResultsButton.setOnClickListener { copyResultsToClipboard() }
        copyResultsButton.setBackgroundColor(0xFF4CAF50.toInt())
        copyResultsButton.setTextColor(Color.WHITE)
        copyResultsButton.setPadding(8, 8, 8, 8)
        resultsHeaderLayout.addView(copyResultsButton)

        topLayout.addView(resultsHeaderLayout)

        // Results textbox
        resultsTextBox = TextView(this)
        resultsTextBox.text = "Neural system starting...\n"
        resultsTextBox.textSize = 10f
        resultsTextBox.setPadding(12, 12, 12, 12)
        resultsTextBox.setTextColor(Color.WHITE)
        resultsTextBox.setBackgroundColor(0xFF1A1A1A.toInt())
        resultsTextBox.typeface = Typeface.MONOSPACE
        resultsTextBox.maxLines = 8
        resultsTextBox.isVerticalScrollBarEnabled = true
        resultsTextBox.movementMethod = ScrollingMovementMethod()
        topLayout.addView(resultsTextBox)

        // Control buttons
        val buttonLayout = LinearLayout(this)
        buttonLayout.orientation = LinearLayout.HORIZONTAL
        buttonLayout.setPadding(16, 16, 16, 8)
        buttonLayout.gravity = Gravity.CENTER

        skipButton = Button(this)
        skipButton.text = "Skip Word"
        skipButton.setOnClickListener { skipWord() }
        skipButton.setBackgroundColor(0xFFFF5722.toInt())
        skipButton.setTextColor(Color.WHITE)
        buttonLayout.addView(skipButton)

        nextButton = Button(this)
        nextButton.text = "Next Word"
        nextButton.setOnClickListener { nextWord() }
        nextButton.setBackgroundColor(0xFF4CAF50.toInt())
        nextButton.setTextColor(Color.WHITE)
        buttonLayout.addView(nextButton)

        exportButton = Button(this)
        exportButton.text = "Export Data"
        exportButton.setOnClickListener { exportTrainingData() }
        exportButton.setBackgroundColor(0xFF2196F3.toInt())
        exportButton.setTextColor(Color.WHITE)
        buttonLayout.addView(exportButton)

        val testTensorsButton = Button(this)
        testTensorsButton.text = "Test Tensors"
        testTensorsButton.setOnClickListener { testTensorCreation() }
        testTensorsButton.setBackgroundColor(0xFFE91E63.toInt())
        testTensorsButton.setTextColor(Color.WHITE)
        buttonLayout.addView(testTensorsButton)

        topLayout.addView(buttonLayout)
        mainLayout.addView(topLayout)

        setContentView(mainLayout)
    }

    private fun setupKeyboard() {
        keyboardView = NeuralKeyboardView(this)

        // Position keyboard at bottom using RelativeLayout params like original
        val keyboardParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight
        )
        keyboardParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        keyboardView.layoutParams = keyboardParams

        // Add keyboard to main RelativeLayout
        val mainLayout = findViewById<View>(android.R.id.content) as ViewGroup
        (mainLayout.getChildAt(0) as RelativeLayout).addView(keyboardView)

        // Set keyboard dimensions for neural engine
        neuralEngine.setKeyboardDimensions(screenWidth.toFloat(), keyboardHeight.toFloat())
    }

    private fun showNextWord() {
        if (currentIndex >= sessionWords.size) {
            showCompletionMessage()
            return
        }

        currentWord = sessionWords[currentIndex]

        // Apply contraction mapping for non-paired contractions (e.g., "dont" -> "don't")
        // This ensures both display and scoring use the apostrophe version
        // since OptimizedVocabulary also modifies predictions to include apostrophes
        if (nonPairedContractions.containsKey(currentWord)) {
            val originalWord = currentWord
            currentWord = nonPairedContractions[currentWord]!!
            Log.d(TAG, "Modified calibration word: \"$originalWord\" -> \"$currentWord\" (with apostrophe)")
        }

        currentWordText.text = currentWord.uppercase()
        progressText.text = String.format("Word %d of %d", currentIndex + 1, WORDS_PER_SESSION)
        progressBar.progress = currentIndex

        updateBenchmarkDisplay()

        Log.d(TAG, "Showing word: $currentWord")
    }

    private fun updateBenchmarkDisplay() {
        if (totalPredictions > 0) {
            val accuracy = (correctPredictions * 100.0f) / totalPredictions
            val avgTime = predictionTimes.map { it.toLong() }.sum() / predictionTimes.size

            benchmarkText.text = String.format(
                "📊 Neural Performance: %.1f%% accuracy, %.1fms avg prediction time",
                accuracy, avgTime / 1000000.0f // Convert nanoseconds to milliseconds
            )
        } else {
            benchmarkText.text = "📊 Neural Performance: No data yet"
        }
    }

    private fun nextWord() {
        currentIndex++
        showNextWord()
    }

    private fun skipWord() {
        Log.d(TAG, "Skipped word: $currentWord")
        currentIndex++
        showNextWord()
    }

    private fun exportTrainingData() {
        val allData = mlDataStore.loadDataBySource("neural_calibration")

        // Export in format matching web demo
        val export = StringBuilder()
        export.append("[\n")

        for (i in allData.indices) {
            val data = allData[i]
            export.append("  {\n")
            export.append("    \"word\": \"${data.targetWord}\",\n")
            export.append("    \"trajectory\": [\n")

            for (point in data.getTracePoints()) {
                export.append(String.format("      {\"x\": %.4f, \"y\": %.4f, \"t\": %d},\n",
                    point.x, point.y, point.tDeltaMs))
            }

            export.append("    ],\n")
            export.append("    \"keys\": ${data.getRegisteredKeys()}\n")
            export.append("  }${if (i < allData.size - 1) "," else ""}\n")
        }

        export.append("]\n")

        // Copy to clipboard
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Neural Training Data", export.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Training data exported to clipboard", Toast.LENGTH_LONG).show()
    }

    private fun testTensorCreation() {
        logToResults("🔧 Testing tensor creation directly...")

        try {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()

            // Test 1D boolean array
            val mask1D = BooleanArray(100)
            for (i in 50 until 100) mask1D[i] = true

            val tensor1D = ai.onnxruntime.OnnxTensor.createTensor(env, mask1D)
            logToResults("1D boolean[100] creates shape: ${tensor1D.info.shape.contentToString()}")
            tensor1D.close()

            // Test 2D boolean array
            val mask2D = Array(1) { BooleanArray(100) }
            for (i in 50 until 100) mask2D[0][i] = true

            val tensor2D = ai.onnxruntime.OnnxTensor.createTensor(env, mask2D)
            logToResults("2D boolean[1][100] creates shape: ${tensor2D.info.shape.contentToString()}")
            tensor2D.close()

            // Test with explicit shape parameter
            val mask1DExplicit = BooleanArray(100)
            for (i in 50 until 100) mask1DExplicit[i] = true

            // Test current approach
            logToResults("Current approach uses boolean[1][100] - should create [1, 100] tensor")

            // Test if the issue is with our tensor creation specifically
            try {
                val testMask = Array(1) { BooleanArray(10) }
                testMask[0][5] = true
                val testTensor = ai.onnxruntime.OnnxTensor.createTensor(env, testMask)
                logToResults("Small test boolean[1][10] shape: ${testTensor.info.shape.contentToString()}")
                testTensor.close()
            } catch (e: Exception) {
                logToResults("❌ Small test failed: ${e.message}")
            }

            logToResults("✅ Tensor creation tests complete")
        } catch (e: Exception) {
            logToResults("💥 Tensor creation test failed: ${e.message}")
            Log.e(TAG, "Tensor test failed", e)
        }
    }

    private fun showCompletionMessage() {
        instructionText.text = "🎉 Neural Calibration Complete!"
        currentWordText.text = "✓"
        currentWordText.setTextColor(0xFF4CAF50.toInt())
        progressBar.progress = progressBar.max

        updateBenchmarkDisplay()

        Log.d(TAG, "=== NEURAL CALIBRATION COMPLETE ===")
    }

    private fun showNeuralPlayground() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("🧠 Neural Parameters Playground")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)

        // Beam width control
        addSliderControl(layout, "Beam Width", config.neural_beam_width, 1, 16) { value ->
            config.neural_beam_width = value
        }

        // Max length control
        addSliderControl(layout, "Max Length", config.neural_max_length, 10, 50) { value ->
            config.neural_max_length = value
        }

        // Confidence threshold control
        addFloatSliderControl(layout, "Confidence Threshold", config.neural_confidence_threshold, 0.0f, 1.0f) { value ->
            config.neural_confidence_threshold = value
        }

        builder.setView(layout)
        builder.setPositiveButton("Apply") { dialog, which ->
            // Save settings to SharedPreferences for persistence
            val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
            val editor = prefs.edit()
            editor.putInt("neural_beam_width", config.neural_beam_width)
            editor.putInt("neural_max_length", config.neural_max_length)
            editor.putFloat("neural_confidence_threshold", config.neural_confidence_threshold)
            editor.apply()

            neuralEngine.setConfig(config)
            Toast.makeText(this, "Neural parameters saved and applied", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addSliderControl(
        parent: LinearLayout,
        name: String,
        currentValue: Int,
        min: Int,
        max: Int,
        setter: (Int) -> Unit
    ) {
        val label = TextView(this)
        label.text = String.format("%s: %d", name, currentValue)
        label.setTextColor(0xFFFFFFFF.toInt())
        parent.addView(label)

        val slider = SeekBar(this)
        slider.max = max - min
        slider.progress = currentValue - min
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + progress
                label.text = String.format("%s: %d", name, value)
                setter(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        parent.addView(slider)
    }

    private fun addFloatSliderControl(
        parent: LinearLayout,
        name: String,
        currentValue: Float,
        min: Float,
        max: Float,
        setter: (Float) -> Unit
    ) {
        val label = TextView(this)
        label.text = String.format("%s: %.3f", name, currentValue)
        label.setTextColor(0xFFFFFFFF.toInt())
        parent.addView(label)

        val slider = SeekBar(this)
        slider.max = 1000 // Fine granularity
        slider.progress = ((currentValue - min) * 1000 / (max - min)).toInt()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + (progress / 1000.0f) * (max - min)
                label.text = String.format("%s: %.3f", name, value)
                setter(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        parent.addView(slider)
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Neural Engine Error")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, which -> finish() }
            .setCancelable(false)
            .show()
    }

    // Results logging methods
    private fun logToResults(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        resultsLog.append(logEntry)

        // Safe check: UI may not be initialized yet during onCreate
        if (!::resultsTextBox.isInitialized) {
            return
        }

        resultsTextBox.text = resultsLog.toString()
        // Auto-scroll to bottom
        resultsTextBox.post {
            val layout = resultsTextBox.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(resultsTextBox.lineCount) - resultsTextBox.height
                if (scrollAmount > 0) {
                    resultsTextBox.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    private fun copyResultsToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Neural Results Log", resultsLog.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Results copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    /**
     * Restored keyboard view with proper 4-row QWERTY layout and touch handling
     */
    private inner class NeuralKeyboardView(context: Context) : View(context) {
        private val keyPaint: Paint
        private val keyBorderPaint: Paint
        private val textPaint: Paint
        private val swipePaint: Paint
        private val overlayPaint: Paint
        private val keys = HashMap<String, KeyButton>()
        private val swipePath = Path()
        private var overlayPath: Path? = null
        private val swipePoints = ArrayList<PointF>()
        private var swiping = false

        init {
            keyPaint = Paint().apply {
                color = 0xFF2B2B2B.toInt() // Darker gray similar to real keyboard
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            keyBorderPaint = Paint().apply {
                color = 0xFF1A1A1A.toInt() // Even darker for border
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }

            textPaint = Paint().apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                isSubpixelText = true
            }

            swipePaint = Paint().apply {
                color = Color.CYAN
                strokeWidth = 8f
                style = Paint.Style.STROKE
                alpha = 180
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            overlayPaint = Paint().apply {
                color = Color.GREEN
                strokeWidth = 10f
                style = Paint.Style.STROKE
                alpha = 200
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            setBackgroundColor(Color.BLACK)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutKeys(w, h)

            // Update neural engine with keyboard dimensions and key positions
            neuralEngine.setKeyboardDimensions(w.toFloat(), h.toFloat())

            val keyPositions = HashMap<Char, PointF>()
            for ((keyStr, button) in keys) {
                if (keyStr.length == 1) {
                    val keyChar = keyStr[0]
                    keyPositions[keyChar] = PointF(
                        button.x + button.width / 2,
                        button.y + button.height / 2
                    )
                }
            }
            neuralEngine.setRealKeyPositions(keyPositions)
        }

        private fun layoutKeys(width: Int, height: Int) {
            keys.clear()

            // Use user's configuration for dimensions
            val keyWidth = width / 10f
            val rowHeight = height / 4f // 4 rows including bottom row
            val verticalMargin = keyVerticalMargin * rowHeight
            val horizontalMargin = keyHorizontalMargin * keyWidth

            // Calculate text size using actual config values
            val characterSizeVal = characterSize
            val labelTextSizeVal = labelTextSize

            // Match the real keyboard's text size calculation
            val baseSize = minOf(
                rowHeight - verticalMargin,
                (keyWidth - horizontalMargin) * 3f / 2f
            )
            val textSize = baseSize * characterSizeVal * labelTextSizeVal
            textPaint.textSize = textSize

            // Layout QWERTY keyboard with 4 rows
            val fullLayout = arrayOf(
                arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                arrayOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
                arrayOf("?123", ",", "space", ".", "enter")
            )

            for (row in fullLayout.indices) {
                val rowKeys = fullLayout[row]

                when (row) {
                    0 -> { // Top row (q-p)
                        for (col in rowKeys.indices) {
                            val key = rowKeys[col]
                            val x = col * keyWidth + horizontalMargin / 2
                            val y = row * rowHeight + verticalMargin / 2

                            val button = KeyButton(key, x, y,
                                keyWidth - horizontalMargin, rowHeight - verticalMargin)
                            keys[key] = button
                        }
                    }
                    1 -> { // Second row (a-l) - offset by half key
                        val rowOffset = keyWidth * 0.5f
                        for (col in rowKeys.indices) {
                            val key = rowKeys[col]
                            val x = rowOffset + col * keyWidth + horizontalMargin / 2
                            val y = row * rowHeight + verticalMargin / 2

                            val button = KeyButton(key, x, y,
                                keyWidth - horizontalMargin, rowHeight - verticalMargin)
                            keys[key] = button
                        }
                    }
                    2 -> { // Third row (shift, z-m, backspace)
                        var currentX = horizontalMargin / 2
                        for (col in rowKeys.indices) {
                            val key = rowKeys[col]
                            var keyW = keyWidth - horizontalMargin

                            // Special keys are wider
                            if (key == "shift" || key == "backspace") {
                                keyW = keyWidth * 1.5f - horizontalMargin
                            }

                            val y = row * rowHeight + verticalMargin / 2

                            val button = KeyButton(key, currentX, y,
                                keyW, rowHeight - verticalMargin)
                            keys[key] = button

                            currentX += keyW + horizontalMargin
                        }
                    }
                    3 -> { // Bottom row (?123, comma, space, period, enter)
                        var currentX = horizontalMargin / 2
                        for (col in rowKeys.indices) {
                            val key = rowKeys[col]
                            var keyW = keyWidth - horizontalMargin

                            // Special key widths
                            when (key) {
                                "space" -> keyW = keyWidth * 5f - horizontalMargin // Space bar is 5 keys wide
                                "?123", "enter" -> keyW = keyWidth * 1.5f - horizontalMargin
                            }

                            val y = row * rowHeight + verticalMargin / 2

                            val button = KeyButton(key, currentX, y,
                                keyW, rowHeight - verticalMargin)
                            keys[key] = button

                            currentX += keyW + horizontalMargin
                        }
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw keys
            for (key in keys.values) {
                key.draw(canvas, keyPaint, keyBorderPaint, textPaint)
            }

            // Draw swipe path
            if (!swipePath.isEmpty) {
                canvas.drawPath(swipePath, swipePaint)
            }

            // Draw overlay path (displayed after swipe completion)
            overlayPath?.let { path ->
                canvas.drawPath(path, overlayPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "🔥 ACTION_DOWN - Starting swipe")
                    swiping = true
                    swipeStartTime = System.currentTimeMillis()
                    swipePath.reset()
                    swipePath.moveTo(x, y)
                    swipePoints.clear()
                    swipePoints.add(PointF(x, y))
                    currentSwipeTimestamps.clear()
                    currentSwipeTimestamps.add(System.currentTimeMillis())
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (swiping) {
                        swipePath.lineTo(x, y)
                        swipePoints.add(PointF(x, y))
                        currentSwipeTimestamps.add(System.currentTimeMillis())
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        swiping = false
                        Log.d(TAG, "🔥 ACTION_UP detected, swipe points: ${swipePoints.size}")
                        if (swipePoints.size > 5) { // Minimum points for valid swipe
                            Log.d(TAG, "🔥 About to call recordSwipe with ${swipePoints.size} points")
                            recordSwipe(ArrayList(swipePoints))
                        }
                        currentSwipeTimestamps.clear()
                    }
                    return true
                }
            }

            return super.onTouchEvent(event)
        }

        fun reset() {
            swipePath.reset()
            swipePoints.clear()
            currentSwipeTimestamps.clear()
            overlayPath = null
            invalidate()
        }

        fun setSwipeOverlay(path: Path) {
            overlayPath = path
            invalidate()
        }

        fun clearOverlay() {
            overlayPath = null
            invalidate()
        }

        fun getKeyAt(x: Float, y: Float): String? {
            for ((keyStr, button) in keys) {
                if (button.contains(x, y)) {
                    return keyStr
                }
            }
            return null
        }

        fun getKeyPositions(): Map<String, KeyButton> {
            return HashMap(keys)
        }

        fun displaySwipeTrace(points: List<PointF>) {
            if (points.isEmpty()) return

            val path = Path()
            if (points.isNotEmpty()) {
                path.moveTo(points[0].x, points[0].y)
            }

            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }

            overlayPath = path
            invalidate()
        }

        fun clearSwipeOverlay() {
            overlayPath = null
            invalidate()
        }
    }

    // Add recordSwipe method needed by keyboard view
    private fun recordSwipe(points: List<PointF>) {
        Log.d(TAG, "🔥 recordSwipe called with ${points.size} points")
        if (points.isEmpty()) return

        val duration = System.currentTimeMillis() - swipeStartTime

        // Create SwipeInput for neural prediction
        var keySequence = ""
        for (p in points) {
            val keyChar = keyboardView.getKeyAt(p.x, p.y)
            if (keyChar != null && keyChar.length == 1) {
                keySequence += keyChar
            }
        }

        val swipeInput = SwipeInput(points, currentSwipeTimestamps, ArrayList())

        // Record ML data
        val mlData = SwipeMLData(currentWord, "neural_calibration",
            screenWidth, screenHeight, keyboardHeight)

        // Add trace points with actual timestamps
        for (i in points.indices) {
            if (i >= currentSwipeTimestamps.size) break
            val p = points[i]
            val timestamp = currentSwipeTimestamps[i]
            mlData.addRawPoint(p.x, p.y, timestamp)

            // Add registered key
            val key = keyboardView.getKeyAt(p.x, p.y)
            if (key != null) {
                mlData.addRegisteredKey(key)
            }
        }

        mlDataStore.storeSwipeData(mlData)

        // Run neural prediction with full logging for performance debugging
        logToResults("🌀 Swipe recorded for '$currentWord': ${points.size} points, ${duration}ms, keys: $keySequence")

        try {
            Log.d(TAG, "🔥 About to call neural prediction")
            val startTime = System.nanoTime()
            val result = neuralEngine.predict(swipeInput)
            val endTime = System.nanoTime()
            Log.d(TAG, "🔥 Neural prediction completed")

            predictionTimes.add(endTime - startTime)
            totalPredictions++

            // Log detailed prediction timing for debugging slow performance
            val predTimeMs = (endTime - startTime) / 1000000
            logToResults("🧠 Neural prediction completed in ${predTimeMs}ms")
            logToResults("   Predictions: ${result.words.size} candidates")

            // Log all predictions to debug quality
            for (i in 0 until minOf(5, result.words.size)) {
                logToResults("   ${i + 1}. ${result.words[i]} (score: ${result.scores[i]})")
            }

            // Check if prediction is correct
            var correct = false
            var rank = -1
            for (i in result.words.indices) {
                if (result.words[i] == currentWord) {
                    correct = true
                    rank = i + 1
                    correctPredictions++
                    logToResults("✅ Correct! Target '$currentWord' found at rank $rank")
                    break
                }
            }

            if (!correct) {
                logToResults("❌ Incorrect. Expected '$currentWord', got: " +
                    if (result.words.isEmpty()) "no predictions" else "'${result.words[0]}'")
            }

            updateBenchmarkDisplay()
        } catch (e: Exception) {
            logToResults("💥 Neural prediction FAILED: ${e.javaClass.simpleName} - ${e.message}")
            Log.e(TAG, "Neural prediction failed", e)
            Toast.makeText(this, "Neural prediction error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // Auto-advance after delay
        Handler(Looper.getMainLooper()).postDelayed({ nextWord() }, 1500)
    }

    /**
     * Key button with proper styling and touch detection
     */
    class KeyButton(
        val label: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        fun draw(canvas: Canvas, keyPaint: Paint, borderPaint: Paint, textPaint: Paint) {
            // Draw key background with rounded corners
            val rect = RectF(x, y, x + width, y + height)
            val cornerRadius = minOf(width, height) * 0.15f // 15% of smallest dimension
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

            // Draw border
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

            // Draw text centered properly (accounting for text metrics)
            val displayLabel = when (label) {
                "space" -> " " // Space bar typically has no label
                "shift" -> "⇧" // Shift arrow symbol
                "backspace" -> "⌫" // Backspace symbol
                "enter" -> "↵" // Enter/return symbol
                "?123" -> "?123" // Keep as is
                else -> label.uppercase() // Regular keys in uppercase
            }

            val textY = y + (height - textPaint.ascent() - textPaint.descent()) / 2f
            canvas.drawText(displayLabel, x + width / 2, textY, textPaint)
        }

        fun contains(px: Float, py: Float): Boolean {
            return px >= x && px <= x + width && py >= y && py <= y + height
        }
    }
}
