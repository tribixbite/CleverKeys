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
                KeyboardData.Key(arrayOf(KeyValue.makeCharKey(char[0])))
            }.toMutableList()
            currentLayout.add(keyRow)
        }
        
        layoutCanvas.setLayout(currentLayout)
    }
    
    private fun saveLayout() {
        // TODO: Implement layout saving to preferences
        toast("Layout saved (TODO: Implement persistence)")
    }
    
    private fun loadLayout() {
        // TODO: Implement layout loading from preferences
        toast("Layout loaded (TODO: Implement loading)")
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
                    val label = key.keyValue?.char?.toString() ?: "?"
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