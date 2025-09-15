package tribixbite.keyboard2

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Modern Kotlin keyboard view with gesture recognition and neural prediction
 * Replaces complex Java touch handling with clean, type-safe implementation
 */
class CleverKeysView(
    context: Context,
    private val config: Config
) : View(context) {
    
    companion object {
        private const val TAG = "CleverKeysView"
    }
    
    // Callback for swipe completion
    var onSwipeCompleted: ((CleverKeysService.SwipeGestureData) -> Unit)? = null
    var onKeyPressed: ((KeyValue) -> Unit)? = null
    
    // Touch state management
    private var isGestureActive = false
    private val gesturePoints = mutableListOf<PointF>()
    private val gestureTimestamps = mutableListOf<Long>()
    private var gestureStartTime = 0L
    
    // Keyboard layout and rendering
    private val keys = mutableMapOf<String, KeyboardKey>()
    private var currentLayout: KeyboardData? = null
    
    // Paint objects for rendering
    private val keyPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val keyBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isSubpixelText = true
    }
    
    private val swipeTrailPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    
    // Gesture path for visual feedback
    private val gesturePath = Path()
    
    /**
     * Set keyboard layout data
     */
    fun setLayout(layout: KeyboardData) {
        currentLayout = layout
        layoutKeys()
        invalidate()
    }
    
    /**
     * Layout keyboard keys based on current configuration
     */
    private fun layoutKeys() {
        val layout = currentLayout ?: return
        keys.clear()
        
        // Apply theme colors
        updatePaintColors()
        
        // Layout keys according to keyboard data
        // This would integrate with the existing KeyboardData system
        // For now, create a simple QWERTY layout
        createQwertyLayout()
    }
    
    private fun updatePaintColors() {
        val theme = Theme.get_current()
        
        keyPaint.color = theme.keyColor
        keyBorderPaint.color = theme.keyBorderColor
        textPaint.color = theme.labelColor
        textPaint.textSize = theme.labelTextSize
    }
    
    private fun createQwertyLayout() {
        // Simplified QWERTY layout for demonstration
        val rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        )
        
        val keyWidth = width / 10f
        val keyHeight = height / rows.size.toFloat()
        
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { keyIndex, keyLabel ->
                val x = keyIndex * keyWidth
                val y = rowIndex * keyHeight
                
                keys[keyLabel] = KeyboardKey(
                    label = keyLabel,
                    bounds = RectF(x, y, x + keyWidth, y + keyHeight),
                    keyValue = KeyValue.makeStringKey(keyLabel)
                )
            }
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw keyboard keys
        keys.values.forEach { key ->
            key.draw(canvas, keyPaint, keyBorderPaint, textPaint)
        }
        
        // Draw gesture trail
        if (!gesturePath.isEmpty) {
            canvas.drawPath(gesturePath, swipeTrailPaint)
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startGesture(x, y)
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isGestureActive) {
                    continueGesture(x, y)
                }
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                if (isGestureActive) {
                    endGesture(x, y)
                }
                return true
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    private fun startGesture(x: Float, y: Float) {
        logD("🔥 Gesture started at ($x, $y)")
        
        isGestureActive = true
        gestureStartTime = System.currentTimeMillis()
        
        gesturePoints.clear()
        gestureTimestamps.clear()
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(gestureStartTime)
        
        gesturePath.reset()
        gesturePath.moveTo(x, y)
        
        invalidate()
    }
    
    private fun continueGesture(x: Float, y: Float) {
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(System.currentTimeMillis())
        
        gesturePath.lineTo(x, y)
        invalidate()
    }
    
    private fun endGesture(x: Float, y: Float) {
        logD("🔥 Gesture ended with ${gesturePoints.size} points")
        
        isGestureActive = false
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(System.currentTimeMillis())
        
        // Determine if this is a swipe or tap
        val gestureLength = gesturePoints.pathLength()
        val gestureDuration = (gestureTimestamps.last() - gestureTimestamps.first()) / 1000f
        
        if (gestureLength > 50f && gestureDuration > 0.1f && gesturePoints.size > 10) {
            // Process as swipe gesture
            handleSwipeGesture()
        } else {
            // Process as key tap
            handleKeyTap(x, y)
        }
        
        // Clear gesture trail after delay
        postDelayed({ 
            gesturePath.reset()
            invalidate()
        }, 1000)
    }
    
    private fun handleSwipeGesture() {
        val swipeData = CleverKeysService.SwipeGestureData(
            path = gesturePoints.toList(),
            timestamps = gestureTimestamps.toList(),
            detectedKeys = gesturePoints.mapNotNull { point ->
                getKeyAt(point.x, point.y)
            }
        )
        
        logD("🌀 Swipe detected: ${swipeData.path.size} points, ${swipeData.detectedKeys.size} keys")
        onSwipeCompleted?.invoke(swipeData)
    }
    
    private fun handleKeyTap(x: Float, y: Float) {
        val key = getKeyAt(x, y)
        if (key != null) {
            val keyValue = keys[key]?.keyValue
            keyValue?.let { kv ->
                logD("🔘 Key tapped: $key")
                onKeyPressed?.invoke(kv)
                
                // Visual feedback
                highlightKey(key)
            }
        }
    }
    
    private fun getKeyAt(x: Float, y: Float): String? {
        return keys.entries.find { (_, key) ->
            key.bounds.contains(x, y)
        }?.key
    }
    
    private fun highlightKey(keyLabel: String) {
        // Visual feedback for key press
        // Could implement ripple effect or color change
        postDelayed({ invalidate() }, 100)
    }
    
    /**
     * Update suggestion bar - COMPLETE implementation
     */
    fun updateSuggestions(words: List<String>) {
        // Find parent container that includes suggestion bar
        val parent = parent as? android.view.ViewGroup
        val suggestionBar = parent?.findViewById<SuggestionBar>(android.R.id.text1)
            ?: parent?.findViewWithTag<SuggestionBar>("suggestion_bar")

        suggestionBar?.setSuggestions(words) ?: run {
            // Create suggestion bar if it doesn't exist
            createAndAttachSuggestionBar(words)
        }

        logD("Updated suggestions: $words")
    }

    /**
     * Clear suggestions - COMPLETE implementation
     */
    fun clearSuggestions() {
        val parent = parent as? android.view.ViewGroup
        val suggestionBar = parent?.findViewById<SuggestionBar>(android.R.id.text1)
            ?: parent?.findViewWithTag<SuggestionBar>("suggestion_bar")

        suggestionBar?.clearSuggestions()
        logD("Cleared suggestions")
    }

    /**
     * Create and attach suggestion bar if missing
     */
    private fun createAndAttachSuggestionBar(words: List<String>) {
        val parent = parent as? android.view.ViewGroup ?: return

        val suggestionBar = SuggestionBar(context).apply {
            tag = "suggestion_bar"
            setSuggestions(words)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Add suggestion bar above keyboard
        parent.addView(suggestionBar, 0)
        logD("Created and attached suggestion bar")
    }
    
    /**
     * Update theme for all UI components
     */
    fun updateTheme() {
        updatePaintColors()
        invalidate()
        logD("Theme updated for keyboard view")
    }
    
    /**
     * Keyboard key representation
     */
    data class KeyboardKey(
        val label: String,
        val bounds: RectF,
        val keyValue: KeyValue
    ) {
        fun draw(canvas: Canvas, keyPaint: Paint, borderPaint: Paint, textPaint: Paint) {
            // Draw key background
            canvas.drawRoundRect(bounds, 8f, 8f, keyPaint)
            canvas.drawRoundRect(bounds, 8f, 8f, borderPaint)
            
            // Draw key label
            val centerX = bounds.centerX()
            val centerY = bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(label, centerX, centerY, textPaint)
        }
    }
}