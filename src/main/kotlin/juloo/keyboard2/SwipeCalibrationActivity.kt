package juloo.keyboard2

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import juloo.keyboard2.ml.SwipeMLData
import juloo.keyboard2.ml.SwipeMLDataStore
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*

/**
 * Pure neural swipe calibration with ONNX transformer prediction
 * Kotlin implementation with coroutines and modern Android practices
 */
class SwipeCalibrationActivity : Activity() {
    
    companion object {
        private const val TAG = "NeuralCalibration"
        private const val WORDS_PER_SESSION = 20
    }
    
    // UI Components using Kotlin's lateinit for lifecycle-aware initialization
    private lateinit var instructionText: TextView
    private lateinit var currentWordText: TextView
    private lateinit var progressText: TextView
    private lateinit var benchmarkText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var keyboardView: NeuralKeyboardView
    private lateinit var resultsTextBox: TextView
    
    // Coroutine scope for async operations
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    // Configuration and data
    private lateinit var neuralConfig: NeuralConfig
    private lateinit var neuralEngine: NeuralSwipeTypingEngine
    private lateinit var mlDataStore: SwipeMLDataStore
    
    // Session state
    private var currentIndex = 0
    private var currentWord: String = ""
    private val sessionWords = mutableListOf<String>()
    private val fullVocabulary = mutableListOf<String>()
    private val resultsLog = StringBuilder()
    
    // Gesture tracking
    private val currentSwipePoints = mutableListOf<PointF>()
    private val currentSwipeTimestamps = mutableListOf<Long>()
    private var swipeStartTime = 0L
    
    // Performance metrics
    private val predictionTimes = mutableListOf<Long>()
    private var correctPredictions = 0
    private var totalPredictions = 0
    
    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0
    private var keyboardHeight = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        logD("=== NEURAL CALIBRATION ACTIVITY STARTED ===")
        
        initializeComponents()
        setupScreenDimensions()
        loadVocabulary()
        setupUI()
        setupKeyboard()
        showNextWord()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel() // Clean up coroutines
    }
    
    private fun initializeComponents() {
        // Initialize ML data store
        mlDataStore = SwipeMLDataStore.getInstance(this)
        
        // Initialize configuration
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        Config.initGlobalConfig(prefs, resources, null, false)
        neuralConfig = NeuralConfig(prefs)
        
        // Initialize neural engine
        neuralEngine = NeuralSwipeTypingEngine(this, Config.globalConfig())
        neuralEngine.setDebugLogger { message -> logToResults(message) }
        
        uiScope.launch {
            try {
                neuralEngine.initialize()
                logD("Neural engine initialized successfully")
                logToResults("✅ Neural engine initialized successfully")
            } catch (e: Exception) {
                logE("Failed to initialize neural engine", e)
                logToResults("❌ Neural engine initialization failed: ${e.message}")
                showErrorDialog("Neural models failed to load. Error: ${e.message}")
                return@launch
            }
        }
    }
    
    private fun setupScreenDimensions() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        // Calculate keyboard height using user settings
        val prefs = DirectBootAwarePreferences.get_shared_preferences(this)
        val foldTracker = FoldStateTracker(this)
        val isLandscape = resources.configuration.orientation == 
                         android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        val keyboardHeightPref = when {
            isLandscape && foldTracker.isUnfolded() -> 
                prefs.getInt("keyboard_height_landscape_unfolded", 50)
            isLandscape -> 
                prefs.getInt("keyboard_height_landscape", 50)
            foldTracker.isUnfolded() -> 
                prefs.getInt("keyboard_height_unfolded", 35)
            else -> 
                prefs.getInt("keyboard_height", 35)
        }
        
        keyboardHeight = (screenHeight * keyboardHeightPref / 100f).toInt()
        logD("Calculated keyboard height: ${keyboardHeight}px (${keyboardHeightPref}% of $screenHeight)")
    }
    
    private fun loadVocabulary() {
        uiScope.launch(Dispatchers.IO) {
            try {
                logD("Loading vocabulary from multiple dictionaries...")
                
                val uniqueWords = mutableSetOf<String>()
                val dictFiles = arrayOf("dictionaries/en.txt", "dictionaries/en_enhanced.txt")
                
                dictFiles.forEach { dictFile ->
                    try {
                        assets.open(dictFile).bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val word = line.trim().lowercase()
                                if (word.isNotBlank() && uniqueWords.add(word)) {
                                    fullVocabulary.add(word)
                                }
                            }
                        }
                        logD("Loaded words from $dictFile")
                    } catch (e: Exception) {
                        logW("Failed to load $dictFile: ${e.message}")
                    }
                }
                
                logD("Total vocabulary loaded: ${fullVocabulary.size} unique words")
                
                withContext(Dispatchers.Main) {
                    prepareSessionWords()
                }
            } catch (e: Exception) {
                logE("Failed to load vocabulary", e)
                withContext(Dispatchers.Main) {
                    showErrorDialog("Failed to load vocabulary: ${e.message}")
                }
            }
        }
    }
    
    private fun prepareSessionWords() {
        if (fullVocabulary.size > WORDS_PER_SESSION) {
            // Select completely random words from full vocabulary
            val selectedWords = mutableSetOf<String>()
            
            while (selectedWords.size < WORDS_PER_SESSION) {
                val randomIndex = Random().nextInt(fullVocabulary.size)
                selectedWords.add(fullVocabulary[randomIndex])
            }
            
            sessionWords.clear()
            sessionWords.addAll(selectedWords)
            logD("Selected ${sessionWords.size} random words from ${fullVocabulary.size} vocabulary")
        } else {
            throw RuntimeException("Insufficient vocabulary loaded: ${fullVocabulary.size} words")
        }
    }
    
    private fun setupUI() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        
        // Title with Kotlin's apply scope function
        addView(TextView(this@SwipeCalibrationActivity).apply {
            text = "🧠 Neural Swipe Calibration"
            textSize = 24f
            setTextColor(0xFF00d4ff.toInt())
            setPadding(40, 40, 40, 20)
        })
        
        // Instructions
        instructionText = TextView(this@SwipeCalibrationActivity).apply {
            text = "Swipe the word shown below - auto-advances on completion"
            setTextColor(Color.GRAY)
        }
        addView(instructionText)
        
        // Current word display
        currentWordText = TextView(this@SwipeCalibrationActivity).apply {
            textSize = 32f
            setTextColor(Color.CYAN)
            setPadding(0, 20, 0, 20)
        }
        addView(currentWordText)
        
        // Progress components
        progressText = TextView(this@SwipeCalibrationActivity).apply {
            setTextColor(Color.WHITE)
        }
        addView(progressText)
        
        progressBar = ProgressBar(this@SwipeCalibrationActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = WORDS_PER_SESSION
        }
        addView(progressBar)
        
        // Benchmark display
        benchmarkText = TextView(this@SwipeCalibrationActivity).apply {
            setTextColor(0xFF00d4ff.toInt())
            textSize = 14f
            setPadding(0, 10, 0, 10)
        }
        addView(benchmarkText)
        
        // Control buttons using Kotlin DSL pattern
        addView(createButtonRow())
        
        // Results display
        addView(createResultsSection())
        
        setContentView(this)
    }
    
    private fun createButtonRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
        setPadding(16, 16, 16, 8)
        
        addView(createButton("Skip Word", 0xFFFF5722.toInt()) { skipWord() })
        addView(createButton("Next Word", 0xFF4CAF50.toInt()) { nextWord() })
        addView(createButton("Export Data", 0xFF2196F3.toInt()) { exportTrainingData() })
        addView(createButton("🎮 Playground", 0xFF4CAF50.toInt()) { showNeuralPlayground() })
    }
    
    private fun createButton(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setBackgroundColor(color)
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
        setPadding(8, 8, 8, 8)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(5, 5, 5, 5)
        }
    }
    
    private fun createResultsSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        // Results header with copy button
        addView(LinearLayout(this@SwipeCalibrationActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
            
            addView(TextView(this@SwipeCalibrationActivity).apply {
                text = "🔍 Neural Results Log:"
                textSize = 14f
                setTextColor(0xFF00d4ff.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            
            addView(Button(this@SwipeCalibrationActivity).apply {
                text = "📋"
                textSize = 16f
                setOnClickListener { copyResultsToClipboard() }
                setBackgroundColor(0xFF4CAF50.toInt())
                setTextColor(Color.WHITE)
                setPadding(8, 8, 8, 8)
            })
        })
        
        // Results text box
        resultsTextBox = TextView(this@SwipeCalibrationActivity).apply {
            text = "Neural system starting...\n"
            textSize = 10f
            setPadding(12, 12, 12, 12)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A1A1A.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 8
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        addView(resultsTextBox)
    }
    
    private fun setupKeyboard() {
        keyboardView = NeuralKeyboardView(this)
        
        // Position keyboard at bottom
        val keyboardParams = android.widget.RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight
        ).apply {
            addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        
        keyboardView.layoutParams = keyboardParams
        
        // Add to main layout (convert to RelativeLayout)
        val mainLayout = android.widget.RelativeLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(findViewById<LinearLayout>(android.R.id.content)?.getChildAt(0))
            addView(keyboardView)
        }
        
        setContentView(mainLayout)
        
        // Configure neural engine dimensions
        neuralEngine.setKeyboardDimensions(screenWidth, keyboardHeight)
    }
    
    private fun showNextWord() {
        if (currentIndex >= sessionWords.size) {
            showCompletionMessage()
            return
        }
        
        currentWord = sessionWords[currentIndex]
        currentWordText.text = currentWord.uppercase()
        progressText.text = "Word ${currentIndex + 1} of $WORDS_PER_SESSION"
        progressBar.progress = currentIndex
        
        updateBenchmarkDisplay()
        logD("Showing word: $currentWord")
    }
    
    private fun updateBenchmarkDisplay() {
        benchmarkText.text = if (totalPredictions > 0) {
            val accuracy = (correctPredictions * 100f) / totalPredictions
            val avgTime = predictionTimes.average() / 1_000_000 // Convert to ms
            "📊 Neural Performance: %.1f%% accuracy, %.1fms avg prediction time".format(accuracy, avgTime)
        } else {
            "📊 Neural Performance: No data yet"
        }
    }
    
    private fun nextWord() {
        currentIndex++
        showNextWord()
    }
    
    private fun skipWord() {
        logD("Skipped word: $currentWord")
        currentIndex++
        showNextWord()
    }
    
    private fun exportTrainingData() {
        uiScope.launch(Dispatchers.IO) {
            try {
                val allData = mlDataStore.loadDataBySource("neural_calibration")
                
                // Export in JSON format
                val export = buildString {
                    appendLine("[")
                    allData.forEachIndexed { index, data ->
                        append("  ")
                        append(data.toJson().toString(2))
                        if (index < allData.size - 1) append(",")
                        appendLine()
                    }
                    appendLine("]")
                }
                
                withContext(Dispatchers.Main) {
                    // Copy to clipboard
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Neural Training Data", export)
                    clipboard.setPrimaryClip(clip)
                    longToast("Training data exported to clipboard")
                }
            } catch (e: Exception) {
                logE("Export failed", e)
                withContext(Dispatchers.Main) {
                    toast("Export failed: ${e.message}")
                }
            }
        }
    }
    
    private fun showNeuralPlayground() {
        AlertDialog.Builder(this).apply {
            setTitle("🧠 Neural Parameters Playground")
            
            val layout = LinearLayout(this@SwipeCalibrationActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
                
                // Beam width control
                addSliderControl("Beam Width", neuralConfig.beamWidth, 1, 16) { value ->
                    neuralConfig.beamWidth = value
                }
                
                // Max length control
                addSliderControl("Max Length", neuralConfig.maxLength, 10, 50) { value ->
                    neuralConfig.maxLength = value
                }
                
                // Confidence threshold control
                addFloatSliderControl("Confidence Threshold", neuralConfig.confidenceThreshold, 0f, 1f) { value ->
                    neuralConfig.confidenceThreshold = value
                }
            }
            
            setView(layout)
            setPositiveButton("Apply") { _, _ ->
                neuralEngine.setConfig(Config.globalConfig())
                toast("Neural parameters updated and saved")
            }
            setNegativeButton("Cancel", null)
            show()
        }
    }
    
    private fun LinearLayout.addSliderControl(
        name: String, 
        currentValue: Int, 
        min: Int, 
        max: Int, 
        setter: (Int) -> Unit
    ) {
        val label = TextView(this@SwipeCalibrationActivity).apply {
            text = "$name: $currentValue"
            setTextColor(Color.WHITE)
        }
        addView(label)
        
        val slider = SeekBar(this@SwipeCalibrationActivity).apply {
            this.max = max - min
            progress = currentValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress
                    label.text = "$name: $value"
                    setter(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        addView(slider)
    }
    
    private fun LinearLayout.addFloatSliderControl(
        name: String,
        currentValue: Float,
        min: Float,
        max: Float,
        setter: (Float) -> Unit
    ) {
        val label = TextView(this@SwipeCalibrationActivity).apply {
            text = "$name: %.3f".format(currentValue)
            setTextColor(Color.WHITE)
        }
        addView(label)
        
        val slider = SeekBar(this@SwipeCalibrationActivity).apply {
            max = 1000 // Fine granularity
            progress = ((currentValue - min) * 1000 / (max - min)).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + (progress / 1000f) * (max - min)
                    label.text = "$name: %.3f".format(value)
                    setter(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        addView(slider)
    }
    
    private fun showCompletionMessage() {
        instructionText.text = "🎉 Neural Calibration Complete!"
        currentWordText.apply {
            text = "✓"
            setTextColor(0xFF4CAF50.toInt())
        }
        progressBar.progress = progressBar.max
        updateBenchmarkDisplay()
        logD("=== NEURAL CALIBRATION COMPLETE ===")
    }
    
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Neural Engine Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
    
    private fun recordSwipe(points: List<PointF>) {
        if (points.isEmpty()) return
        
        logD("🔥 recordSwipe called with ${points.size} points")
        val duration = System.currentTimeMillis() - swipeStartTime
        
        // Create SwipeInput for neural prediction
        val keySequence = points.mapNotNull { p ->
            keyboardView.getKeyAt(p.x, p.y)?.takeIf { it.length == 1 }
        }.joinToString("")
        
        val swipeInput = SwipeInput(points, currentSwipeTimestamps.toList(), emptyList())
        
        // Record ML data
        val mlData = SwipeMLData(
            targetWord = currentWord,
            collectionSource = "neural_calibration",
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
            keyboardHeightPx = keyboardHeight
        )
        
        // Add trace points with timestamps
        points.zip(currentSwipeTimestamps) { point, timestamp ->
            mlData.addRawPoint(point.x, point.y, timestamp)
        }
        
        // Add registered keys
        points.forEach { point ->
            keyboardView.getKeyAt(point.x, point.y)?.let { key ->
                mlData.addRegisteredKey(key)
            }
        }
        
        mlDataStore.storeSwipeData(mlData)
        
        // Run neural prediction with coroutines
        logToResults("🌀 Swipe recorded for '$currentWord': ${points.size} points, ${duration}ms, keys: $keySequence")
        
        uiScope.launch {
            try {
                logD("🔥 About to call neural prediction")
                val (result, predTime) = measureTimeNanos {
                    neuralEngine.predict(swipeInput)
                }
                logD("🔥 Neural prediction completed")
                
                predictionTimes.add(predTime)
                totalPredictions++
                
                val predTimeMs = predTime / 1_000_000
                logToResults("🧠 Neural prediction completed in ${predTimeMs}ms")
                logToResults("   Predictions: ${result.size} candidates")
                
                // Log predictions
                result.predictions.take(5).forEachIndexed { index, (word, score) ->
                    logToResults("   ${index + 1}. $word (score: $score)")
                }
                
                // Check correctness
                val correctRank = result.words.indexOf(currentWord)
                if (correctRank >= 0) {
                    correctPredictions++
                    logToResults("✅ Correct! Target '$currentWord' found at rank ${correctRank + 1}")
                } else {
                    logToResults("❌ Incorrect. Expected '$currentWord', got: ${result.topPrediction ?: "no predictions"}")
                }
                
                updateBenchmarkDisplay()
                
                // Auto-advance after delay
                Handler(Looper.getMainLooper()).postDelayed({ nextWord() }, 1500)
                
            } catch (e: Exception) {
                logToResults("💥 Neural prediction FAILED: ${e.javaClass.simpleName} - ${e.message}")
                logE("Neural prediction failed", e)
                toast("Neural prediction error: ${e.message}")
            }
        }
    }
    
    private fun logToResults(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        resultsLog.append(logEntry)
        
        resultsTextBox?.let { textBox ->
            textBox.text = resultsLog.toString()
            // Auto-scroll to bottom
            textBox.post {
                textBox.layout?.let { layout ->
                    val scrollAmount = layout.getLineTop(textBox.lineCount) - textBox.height
                    if (scrollAmount > 0) {
                        textBox.scrollTo(0, scrollAmount)
                    }
                }
            }
        }
    }
    
    private fun copyResultsToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Neural Results Log", resultsLog.toString())
        clipboard.setPrimaryClip(clip)
        toast("Results copied to clipboard")
    }
    
    /**
     * Custom keyboard view with QWERTY layout and touch handling
     * Kotlin implementation with modern patterns
     */
    inner class NeuralKeyboardView(context: android.content.Context) : View(context) {
        
        private val keyPaint = Paint().apply {
            color = 0xFF2B2B2B.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        private val keyBorderPaint = Paint().apply {
            color = 0xFF1A1A1A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isSubpixelText = true
        }
        
        private val swipePaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 8f
            style = Paint.Style.STROKE
            alpha = 180
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        
        private val keys = mutableMapOf<String, KeyButton>()
        private val swipePath = Path()
        private var overlayPath: Path? = null
        private val swipePoints = mutableListOf<PointF>()
        private var swiping = false
        
        init {
            setBackgroundColor(Color.BLACK)
        }
        
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            layoutKeys(w, h)
            
            // Update neural engine with key positions
            neuralEngine.setKeyboardDimensions(w, h)
            val keyPositions = keys.mapNotNull { (keyStr, button) ->
                if (keyStr.length == 1) {
                    keyStr[0] to PointF(button.x + button.width/2, button.y + button.height/2)
                } else null
            }.toMap()
            neuralEngine.setRealKeyPositions(keyPositions)
        }
        
        private fun layoutKeys(width: Int, height: Int) {
            keys.clear()
            
            val keyWidth = width / 10f
            val rowHeight = height / 4f
            
            // QWERTY layout with modern Kotlin collection operations
            val layout = arrayOf(
                arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                arrayOf("shift", "z", "x", "c", "v", "b", "n", "m", "backspace"),
                arrayOf("?123", ",", "space", ".", "enter")
            )
            
            layout.forEachIndexed { row, rowKeys ->
                var currentX = 0f
                rowKeys.forEach { key ->
                    val keyW = when (key) {
                        "shift", "backspace", "?123", "enter" -> keyWidth * 1.5f
                        "space" -> keyWidth * 5f
                        else -> keyWidth
                    }
                    
                    val offsetX = if (row == 1) keyWidth * 0.5f else 0f
                    
                    keys[key] = KeyButton(
                        label = key,
                        x = offsetX + currentX,
                        y = row * rowHeight,
                        width = keyW,
                        height = rowHeight
                    )
                    
                    currentX += keyW
                }
            }
            
            // Set text size
            val baseSize = minOf(rowHeight, keyWidth * 1.5f)
            textPaint.textSize = baseSize * 0.4f
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Draw keys
            keys.values.forEach { key ->
                key.draw(canvas, keyPaint, keyBorderPaint, textPaint)
            }
            
            // Draw swipe path
            if (!swipePath.isEmpty) {
                canvas.drawPath(swipePath, swipePaint)
            }
            
            // Draw overlay path
            overlayPath?.let { path ->
                canvas.drawPath(path, swipePaint.apply { color = Color.GREEN })
            }
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    logD("🔥 ACTION_DOWN - Starting swipe")
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
                        logD("🔥 ACTION_UP detected, swipe points: ${swipePoints.size}")
                        if (swipePoints.size > 5) { // Minimum points for valid swipe
                            logD("🔥 About to call recordSwipe with ${swipePoints.size} points")
                            recordSwipe(swipePoints.toList())
                        }
                        currentSwipeTimestamps.clear()
                    }
                    return true
                }
            }
            
            return super.onTouchEvent(event)
        }
        
        fun getKeyAt(x: Float, y: Float): String? {
            return keys.entries.find { it.value.contains(x, y) }?.key
        }
        
        fun reset() {
            swipePath.reset()
            swipePoints.clear()
            currentSwipeTimestamps.clear()
            overlayPath = null
            invalidate()
        }
        
    }
    
    /**
     * Key button with modern Kotlin implementation
     */
    data class KeyButton(
        val label: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        fun draw(canvas: Canvas, keyPaint: Paint, borderPaint: Paint, textPaint: Paint) {
            val rect = RectF(x, y, x + width, y + height)
            val cornerRadius = minOf(width, height) * 0.15f
            
            // Draw key background and border
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
            
            // Draw text with proper symbol mapping
            val displayLabel = when (label) {
                "space" -> " "
                "shift" -> "⇧"
                "backspace" -> "⌫"
                "enter" -> "↵"
                "?123" -> "?123"
                else -> label.uppercase()
            }
            
            val textY = y + (height - textPaint.ascent() - textPaint.descent()) / 2f
            canvas.drawText(displayLabel, x + width / 2, textY, textPaint)
        }
        
        fun contains(px: Float, py: Float): Boolean {
            return px in x..(x + width) && py in y..(y + height)
        }
    }
}