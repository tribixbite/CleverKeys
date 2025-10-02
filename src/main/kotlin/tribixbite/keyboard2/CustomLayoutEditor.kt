package tribixbite.keyboard2

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
                toast("Layout reset to QWERTY")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun testLayout() {
        // TODO: Open test interface for layout
        toast("Test layout (TODO: Implement test interface)")
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
}