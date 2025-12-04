package tribixbite.cleverkeys

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

/**
 * Custom layout editor for creating and modifying keyboard layouts
 * Kotlin implementation with drag-and-drop key editing
 */
class CustomLayoutEditor : Activity() {
    
    companion object {
        private const val TAG = "CustomLayoutEditor"
    }
    
    private lateinit var layoutCanvas: LayoutCanvas
    private lateinit var keyPalette: KeyPalette
    private var currentLayout: MutableList<MutableList<KeyboardData.Key>> = mutableListOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        initializeDefaultLayout()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    private fun setupUI() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        // Header
        addView(TextView(this@CustomLayoutEditor).apply {
            text = "⌨️ Custom Layout Editor"
            textSize = 20f
            setPadding(16, 16, 16, 8)
        })
        
        // Tools bar
        addView(createToolsBar())
        
        // Main editing area
        addView(LinearLayout(this@CustomLayoutEditor).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            
            // Key palette
            keyPalette = KeyPalette(this@CustomLayoutEditor)
            addView(keyPalette, LinearLayout.LayoutParams(200, LinearLayout.LayoutParams.MATCH_PARENT))
            
            // Layout canvas
            layoutCanvas = LayoutCanvas(this@CustomLayoutEditor)
            addView(layoutCanvas, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        })
        
        setContentView(this)
    }
    
    private fun createToolsBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(8, 8, 8, 8)
        
        addView(createToolButton("💾 Save") { saveLayout() })
        addView(createToolButton("📁 Load") { loadLayout() })
        addView(createToolButton("🔄 Reset") { resetLayout() })
        addView(createToolButton("🧪 Test") { testLayout() })
    }
    
    private fun createToolButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setPadding(8, 8, 8, 8)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(4, 0, 4, 0)
        }
    }
    
    /**
     * Initialize default QWERTY layout
     */
    private fun initializeDefaultLayout() {
        currentLayout.clear()
        
        val qwertyRows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        )
        
        qwertyRows.forEach { row ->
            val keyRow = row.map { char ->
                KeyboardData.Key(
                    keys = arrayOf(KeyValue.makeCharKey(char[0])),
                    width = 1.0f,
                    shift = 0.0f
                )
            }.toMutableList()
            currentLayout.add(keyRow)
        }
        
        layoutCanvas.setLayout(currentLayout)
    }
    
    private fun saveLayout() {
        try {
            val prefs = getSharedPreferences("cleverkeys_prefs", Context.MODE_PRIVATE)
            val layoutJson = serializeLayout(currentLayout)

            prefs.edit()
                .putString("custom_layout_editor_state", layoutJson)
                .apply()

            toast("✅ Layout saved successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save layout", e)
            toast("❌ Failed to save layout: ${e.message}")
        }
    }

    private fun loadLayout() {
        try {
            val prefs = getSharedPreferences("cleverkeys_prefs", Context.MODE_PRIVATE)
            val layoutJson = prefs.getString("custom_layout_editor_state", null)

            if (layoutJson != null) {
                currentLayout = deserializeLayout(layoutJson)
                layoutCanvas.setLayout(currentLayout)
                toast("✅ Layout loaded successfully")
            } else {
                toast("ℹ️ No saved layout found")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load layout", e)
            toast("❌ Failed to load layout: ${e.message}")
        }
    }

    /**
     * Serialize layout to JSON format for persistence.
     * Format: [[{"width":1.0,"shift":0.5,"keys":[...]}, ...], ...]
     */
    private fun serializeLayout(layout: List<List<KeyboardData.Key>>): String {
        val jsonRows = layout.map { row ->
            row.map { key ->
                buildString {
                    append("{")
                    append("\"width\":").append(key.width).append(",")
                    append("\"shift\":").append(key.shift).append(",")
                    append("\"indication\":").append(if (key.indication != null) "\"${escapeJsonString(key.indication)}\"" else "null").append(",")
                    append("\"keys\":[")
                    append(key.keys.joinToString(",") { serializeKeyValue(it) })
                    append("]}")
                }
            }.joinToString(",", "[", "]")
        }.joinToString(",", "[", "]")

        return jsonRows
    }

    /**
     * Serialize a KeyValue to JSON string representation.
     */
    private fun serializeKeyValue(keyValue: KeyValue?): String {
        if (keyValue == null) return "null"

        return when (keyValue) {
            is KeyValue.CharKey -> "\"${escapeJsonString(keyValue.char.toString())}\""
            is KeyValue.StringKey -> "\"${escapeJsonString(keyValue.string)}\""
            is KeyValue.EventKey -> "{\"event\":\"${keyValue.event.name}\"}"
            is KeyValue.ModifierKey -> "{\"modifier\":\"${keyValue.modifier.name}\"}"
            is KeyValue.EditingKey -> "{\"editing\":\"${keyValue.editing.name}\"}"
            is KeyValue.ComposePendingKey -> "{\"compose\":${keyValue.pendingCompose}}"
            is KeyValue.KeyEventKey -> "{\"keycode\":${keyValue.keyCode}}"
            else -> "null" // Handle other key types as null for simplicity
        }
    }

    /**
     * Escape special JSON characters in strings.
     */
    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Deserialize layout from JSON format using Android's built-in JSON parser.
     * Returns empty layout if parsing fails.
     */
    private fun deserializeLayout(json: String): MutableList<MutableList<KeyboardData.Key>> {
        val layout = mutableListOf<MutableList<KeyboardData.Key>>()

        try {
            val jsonArray = org.json.JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val rowArray = jsonArray.getJSONArray(i)
                val row = mutableListOf<KeyboardData.Key>()

                for (j in 0 until rowArray.length()) {
                    val keyObj = rowArray.getJSONObject(j)
                    val keysArray = keyObj.getJSONArray("keys")

                    val keys = Array<KeyValue?>(9) { idx ->
                        if (idx < keysArray.length()) {
                            deserializeKeyValue(keysArray.get(idx))
                        } else {
                            null
                        }
                    }

                    val indication = if (keyObj.isNull("indication")) null else keyObj.getString("indication")

                    val key = KeyboardData.Key(
                        keys = keys,
                        anticircle = null,
                        keysFlags = 0,
                        width = keyObj.getDouble("width").toFloat(),
                        shift = keyObj.getDouble("shift").toFloat(),
                        indication = indication
                    )
                    row.add(key)
                }

                layout.add(row)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to deserialize layout JSON", e)
            // Return empty layout on error
        }

        return layout
    }

    /**
     * Deserialize a KeyValue from JSON object or string.
     */
    private fun deserializeKeyValue(obj: Any?): KeyValue? {
        if (obj == null || obj == org.json.JSONObject.NULL) return null

        return try {
            when (obj) {
                is String -> {
                    // Single character string
                    if (obj.length == 1) {
                        KeyValue.CharKey(obj.first())
                    } else {
                        KeyValue.StringKey(obj)
                    }
                }
                is org.json.JSONObject -> {
                    when {
                        obj.has("event") -> {
                            val eventName = obj.getString("event")
                            val event = KeyValue.Event.valueOf(eventName)
                            KeyValue.EventKey(event, eventName)
                        }
                        obj.has("modifier") -> {
                            val modName = obj.getString("modifier")
                            val mod = KeyValue.Modifier.valueOf(modName)
                            KeyValue.ModifierKey(mod, modName)
                        }
                        obj.has("editing") -> {
                            val editName = obj.getString("editing")
                            val edit = KeyValue.Editing.valueOf(editName)
                            KeyValue.EditingKey(edit, editName)
                        }
                        obj.has("compose") -> {
                            val compose = obj.getInt("compose")
                            KeyValue.ComposePendingKey(compose, "◌")
                        }
                        obj.has("keycode") -> {
                            val keycode = obj.getInt("keycode")
                            KeyValue.KeyEventKey(keycode, "Key")
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to deserialize KeyValue: $obj", e)
            null
        }
    }
    
    private fun resetLayout() {
        AlertDialog.Builder(this)
            .setTitle("Reset Layout")
            .setMessage("Reset to default QWERTY layout?")
            .setPositiveButton("Reset") { _, _ ->
                initializeDefaultLayout()
                Toast.makeText(this, "Layout reset to QWERTY", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show a toast message (helper function).
     */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Test layout in interactive mode with live preview.
     * v2.1 Priority 1 Feature #3: Layout Test Interface
     */
    private fun testLayout() {
        if (currentLayout.isEmpty()) {
            toast("❌ Layout is empty - nothing to test")
            return
        }

        // Create test dialog with full-screen keyboard preview
        val testDialog = AlertDialog.Builder(this)
            .setTitle("🧪 Test Layout - Interactive Mode")
            .setView(createTestView())
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .create()

        testDialog.show()

        // Make dialog wide for better keyboard preview
        testDialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Create interactive test view with live keyboard preview.
     * Shows real-time feedback for key presses.
     */
    private fun createTestView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)

            // Instructions
            addView(TextView(this@CustomLayoutEditor).apply {
                text = "Tap keys to test layout behavior"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })

            // Feedback display (shows last pressed key)
            val feedbackView = TextView(this@CustomLayoutEditor).apply {
                text = "Tap a key to see output..."
                textSize = 18f
                setBackgroundColor(Color.LTGRAY)
                setPadding(16, 24, 16, 24)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    120
                )
            }
            addView(feedbackView)

            // Spacing
            addView(Space(this@CustomLayoutEditor).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    16
                )
            })

            // Interactive keyboard preview
            val testKeyboard = TestKeyboardView(this@CustomLayoutEditor).apply {
                setLayout(currentLayout)
                onKeyPressed = { key ->
                    handleTestKeyPress(key, feedbackView)
                }
            }
            addView(testKeyboard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
            ))

            // Test statistics
            addView(TextView(this@CustomLayoutEditor).apply {
                text = "Layout: ${currentLayout.size} rows, ${currentLayout.sumOf { it.size }} keys"
                textSize = 12f
                setPadding(0, 16, 0, 0)
                setTextColor(Color.DKGRAY)
            })
        }
    }

    /**
     * Handle key press in test mode - show feedback.
     */
    private fun handleTestKeyPress(key: KeyboardData.Key, feedbackView: TextView) {
        val primaryKey = key.keys[0]
        if (primaryKey == null) {
            feedbackView.text = "❌ Empty key"
            return
        }

        val keyLabel = when (primaryKey) {
            is KeyValue.CharKey -> "Char: '${primaryKey.char}'"
            is KeyValue.StringKey -> "String: \"${primaryKey.string}\""
            is KeyValue.EventKey -> "Event: ${primaryKey.event.name}"
            is KeyValue.ModifierKey -> "Modifier: ${primaryKey.modifier.name}"
            else -> "Key: $primaryKey"
        }

        feedbackView.text = "✅ $keyLabel"

        // Vibrate for tactile feedback (if available)
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(20)
        } catch (e: Exception) {
            // Vibration not available
        }
    }
    
    /**
     * Layout canvas for editing
     */
    private class LayoutCanvas(context: Context) : View(context) {
        
        private var layout: List<List<KeyboardData.Key>> = emptyList()
        private val keyPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 24f
            isAntiAlias = true
        }
        
        fun setLayout(newLayout: List<List<KeyboardData.Key>>) {
            layout = newLayout
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val keyWidth = width.toFloat() / 10f
            val keyHeight = height.toFloat() / layout.size
            
            layout.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { colIndex, key ->
                    val x = colIndex * keyWidth
                    val y = rowIndex * keyHeight
                    val rect = RectF(x, y, x + keyWidth, y + keyHeight)
                    
                    // Draw key
                    canvas.drawRect(rect, keyPaint)
                    canvas.drawRect(rect, borderPaint)
                    
                    // Draw label
                    val label = when (val keyValue = key.keys.getOrNull(0)) {
                        is KeyValue.CharKey -> keyValue.char.toString()
                        is KeyValue.StringKey -> keyValue.string
                        else -> "?"
                    }
                    val centerX = rect.centerX()
                    val centerY = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
                    canvas.drawText(label, centerX, centerY, textPaint)
                }
            }
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val keyWidth = width.toFloat() / 10f
                    val keyHeight = height.toFloat() / layout.size
                    val row = (event.y / keyHeight).toInt()
                    val col = (event.x / keyWidth).toInt()
                    
                    if (row in layout.indices && col in layout[row].indices) {
                        editKey(row, col)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
        
        private fun editKey(row: Int, col: Int) {
            // TODO: Open key editing dialog
            android.util.Log.d("CustomLayoutEditor", "Edit key at [$row, $col]")
        }
    }
    
    /**
     * Key palette for available keys
     */
    private class KeyPalette(context: Context) : LinearLayout(context) {
        
        init {
            orientation = VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.LTGRAY)
            
            // Add key categories
            addView(createPaletteSection("Letters", ('a'..'z').map { it.toString() }))
            addView(createPaletteSection("Numbers", ('0'..'9').map { it.toString() }))
            addView(createPaletteSection("Symbols", listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")))
            addView(createPaletteSection("Special", listOf("Space", "Enter", "Backspace", "Shift", "Tab")))
        }
        
        private fun createPaletteSection(title: String, keys: List<String>): LinearLayout {
            return LinearLayout(context).apply {
                orientation = VERTICAL
                
                addView(TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 8, 0, 4)
                })
                
                // Create grid of keys
                val grid = GridLayout(context).apply {
                    columnCount = 2
                }
                
                keys.forEach { key ->
                    grid.addView(Button(context).apply {
                        text = key
                        textSize = 10f
                        setPadding(4, 4, 4, 4)
                        setOnClickListener {
                            // TODO: Add key to layout
                            android.util.Log.d("KeyPalette", "Selected key: $key")
                        }
                    })
                }
                
                addView(grid)
            }
        }
    }

    /**
     * Test keyboard view for interactive layout testing.
     * v2.1 Priority 1 Feature #3: Interactive test mode with touch feedback.
     */
    private class TestKeyboardView(context: Context) : View(context) {

        private var layout: List<List<KeyboardData.Key>> = emptyList()
        private val keys = mutableListOf<KeyRect>()
        var onKeyPressed: ((KeyboardData.Key) -> Unit)? = null

        private val keyPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val keyPressedPaint = Paint().apply {
            color = Color.parseColor("#BDBDBD")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val borderPaint = Paint().apply {
            color = Color.parseColor("#757575")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 20f
            isAntiAlias = true
        }

        private var pressedKeyIndex: Int? = null

        data class KeyRect(
            val rect: RectF,
            val key: KeyboardData.Key,
            val row: Int,
            val col: Int
        )

        fun setLayout(newLayout: List<List<KeyboardData.Key>>) {
            layout = newLayout
            calculateKeyRects()
            invalidate()
        }

        private fun calculateKeyRects() {
            keys.clear()

            if (layout.isEmpty()) return

            val keyHeight = height.toFloat() / layout.size

            layout.forEachIndexed { rowIndex, row ->
                if (row.isEmpty()) return@forEachIndexed

                var xOffset = 0f
                val totalWidth = row.sumOf { it.width.toDouble() }.toFloat()
                val keyUnitWidth = width.toFloat() / totalWidth

                row.forEachIndexed { colIndex, key ->
                    val keyWidth = key.width * keyUnitWidth
                    val x = xOffset + (key.shift * keyUnitWidth)
                    val y = rowIndex * keyHeight

                    val rect = RectF(
                        x,
                        y,
                        x + keyWidth,
                        y + keyHeight
                    )

                    keys.add(KeyRect(rect, key, rowIndex, colIndex))
                    xOffset = x + keyWidth
                }
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            calculateKeyRects()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            keys.forEachIndexed { index, keyRect ->
                // Draw key background (pressed or normal)
                val paint = if (index == pressedKeyIndex) keyPressedPaint else keyPaint
                canvas.drawRoundRect(
                    keyRect.rect,
                    8f, 8f,
                    paint
                )

                // Draw key border
                canvas.drawRoundRect(
                    keyRect.rect,
                    8f, 8f,
                    borderPaint
                )

                // Draw key label
                val primaryKey = keyRect.key.keys[0]
                val label = when (primaryKey) {
                    is KeyValue.CharKey -> primaryKey.char.toString()
                    is KeyValue.StringKey -> primaryKey.string.take(8)
                    is KeyValue.EventKey -> primaryKey.event.name.take(8)
                    is KeyValue.ModifierKey -> primaryKey.modifier.name.take(8)
                    null -> "—"
                    else -> "?"
                }

                val centerX = keyRect.rect.centerX()
                val centerY = keyRect.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2

                canvas.drawText(
                    label,
                    centerX,
                    centerY,
                    textPaint
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Find touched key
                    val touchedKeyIndex = keys.indexOfFirst { keyRect ->
                        keyRect.rect.contains(event.x, event.y)
                    }

                    if (touchedKeyIndex >= 0 && touchedKeyIndex != pressedKeyIndex) {
                        pressedKeyIndex = touchedKeyIndex
                        invalidate()

                        // Trigger callback
                        onKeyPressed?.invoke(keys[touchedKeyIndex].key)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressedKeyIndex = null
                    invalidate()
                }
            }
            return true
        }
    }
}