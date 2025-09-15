package juloo.keyboard2

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.*

/**
 * Complete keyboard view implementation with full layout rendering
 * Kotlin implementation with modern touch handling and drawing
 */
class Keyboard2View(
    context: Context,
    private val config: Config
) : View(context) {
    
    companion object {
        private const val TAG = "Keyboard2View"
    }
    
    // Keyboard data and layout
    private var currentLayout: KeyboardData? = null
    private val keys = mutableListOf<DrawnKey>()
    private var keyWidth = 0f
    private var keyHeight = 0f
    
    // Touch handling
    private val pointers = Pointers(object : Pointers.IPointerEventHandler {
        override fun onPointerDown(pointerId: Int, keyValue: KeyValue?) {
            handleKeyDown(keyValue)
        }
        override fun onPointerUp(pointerId: Int, keyValue: KeyValue?) {
            handleKeyUp(keyValue)
        }
        override fun onPointerSwipe(pointerId: Int, direction: Int) {
            handleSwipe(direction)
        }
    }, config)
    
    // ONNX-only: Direct neural processing without intermediate gesture recognition
    
    // Drawing paints
    private val keyPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val keyBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    
    private val swipeTrailPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = Color.CYAN
        alpha = 180
    }
    
    // Gesture tracking
    private val gesturePoints = mutableListOf<PointF>()
    private val gestureTimestamps = mutableListOf<Long>()
    private val gesturePath = Path()
    private var isGestureActive = false
    
    // Callbacks
    var onKeyPressed: ((KeyValue) -> Unit)? = null
    var onSwipeCompleted: ((SwipeInput) -> Unit)? = null
    
    /**
     * Set keyboard layout
     */
    fun setLayout(layout: KeyboardData) {
        currentLayout = layout
        layoutKeys()
        invalidate()
    }
    
    /**
     * Layout keyboard keys
     */
    private fun layoutKeys() {
        val layout = currentLayout ?: return
        keys.clear()
        
        val theme = Theme.get_current()
        updatePaintColors(theme)
        
        keyWidth = width.toFloat() / 10f // Standard 10-column layout
        keyHeight = height.toFloat() / layout.height
        
        layout.keys.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, key ->
                val x = colIndex * keyWidth
                val y = rowIndex * keyHeight
                
                val drawnKey = DrawnKey(
                    keyValue = key.keyValue ?: KeyValue.makeCharKey('?'),
                    bounds = RectF(x, y, x + keyWidth * key.width, y + keyHeight),
                    label = key.keyValue?.string ?: key.keyValue?.char?.toString() ?: "?",
                    shift = key.shift
                )
                
                keys.add(drawnKey)
            }
        }
        
        logD("Laid out ${keys.size} keys")
    }
    
    /**
     * Update paint colors from theme
     */
    private fun updatePaintColors(theme: Theme.ThemeData) {
        keyPaint.color = theme.keyColor
        keyBorderPaint.color = theme.keyBorderColor
        labelPaint.color = theme.labelColor
        labelPaint.textSize = theme.labelTextSize
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw keyboard background
        canvas.drawColor(Theme.get_current().backgroundColor)
        
        // Draw keys
        keys.forEach { key ->
            key.draw(canvas, keyPaint, keyBorderPaint, labelPaint)
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
    
    /**
     * Start gesture tracking
     */
    private fun startGesture(x: Float, y: Float) {
        isGestureActive = true
        gesturePoints.clear()
        gestureTimestamps.clear()
        
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(System.currentTimeMillis())
        
        gesturePath.reset()
        gesturePath.moveTo(x, y)
        
        // ONNX-only: No continuous recognition - process complete gestures only
        
        invalidate()
    }
    
    /**
     * Continue gesture
     */
    private fun continueGesture(x: Float, y: Float) {
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(System.currentTimeMillis())
        gesturePath.lineTo(x, y)
        
        // ONNX-only: No intermediate recognition updates - await complete gesture
        
        invalidate()
    }
    
    /**
     * End gesture and process
     */
    private fun endGesture(x: Float, y: Float) {
        gesturePoints.add(PointF(x, y))
        gestureTimestamps.add(System.currentTimeMillis())
        
        isGestureActive = false
        
        // ONNX-only: Gesture complete - ready for neural processing
        
        // ONNX-only: Direct neural processing of complete gesture
        val swipeInput = SwipeInput(
            coordinates = gesturePoints.toList(),
            timestamps = gestureTimestamps.toList(),
            touchedKeys = emptyList() // ONNX neural handles key detection internally
        )

        // Simple threshold-based gesture/tap distinction for ONNX processing
        if (swipeInput.pathLength > 50f && swipeInput.duration > 0.1f && swipeInput.coordinates.size > 5) {
            logD("Gesture completed: ${swipeInput.pathLength}px, ${swipeInput.duration}s - processing with ONNX neural")
            onSwipeCompleted?.invoke(swipeInput)
        } else {
            // Handle as key tap without gesture classification
            val keyValue = getKeyAt(x, y)
            if (keyValue != null) {
                logD("Key tap: $keyValue")
                onKeyPressed?.invoke(keyValue)
            }
        }
        
        // Clear gesture trail after delay
        postDelayed({
            gesturePath.reset()
            invalidate()
        }, 1000)
    }
    
    /**
     * Get key at coordinates
     */
    private fun getKeyAt(x: Float, y: Float): KeyValue? {
        return keys.find { it.bounds.contains(x, y) }?.keyValue
    }
    
    /**
     * Handle key down event
     */
    private fun handleKeyDown(keyValue: KeyValue?) {
        keyValue?.let { onKeyPressed?.invoke(it) }
    }
    
    /**
     * Handle key up event
     */
    private fun handleKeyUp(keyValue: KeyValue?) {
        // Key up handling if needed
    }
    
    /**
     * Handle swipe gesture
     */
    private fun handleSwipe(direction: Int) {
        logD("Swipe direction: $direction")
    }
    
    /**
     * Cleanup - ONNX only
     */
    fun cleanup() {
        pointers.cleanup()
    }
    
    /**
     * Drawn key representation
     */
    data class DrawnKey(
        val keyValue: KeyValue,
        val bounds: RectF,
        val label: String,
        val shift: Float = 0f
    ) {
        fun draw(canvas: Canvas, keyPaint: Paint, borderPaint: Paint, labelPaint: Paint) {
            // Draw key background
            canvas.drawRoundRect(bounds, 8f, 8f, keyPaint)
            canvas.drawRoundRect(bounds, 8f, 8f, borderPaint)
            
            // Draw label
            val centerX = bounds.centerX()
            val centerY = bounds.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            canvas.drawText(label, centerX, centerY, labelPaint)
        }
    }
}