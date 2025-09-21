package tribixbite.keyboard2

import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/**
 * Main keyboard view for CleverKeys with neural swipe prediction
 *
 * Replaces CGR-based swipe recognition with ONNX neural prediction:
 * - Modern touch handling with coroutines
 * - Neural swipe trajectory processing
 * - Real-time prediction display
 * - Enhanced rendering with GPU acceleration
 */
class Keyboard2View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), View.OnTouchListener, Pointers.IPointerEventHandler {

    private var keyboard: KeyboardData? = null

    // Key references for state management
    private var shiftKeyValue: KeyValue? = null
    private var shiftKey: KeyboardData.Key? = null
    private var composeKeyValue: KeyValue? = null
    private var composeKey: KeyboardData.Key? = null

    private lateinit var pointers: Pointers
    private var modifiers = Pointers.Modifiers.EMPTY

    private lateinit var config: Config
    private lateinit var neuralEngine: NeuralSwipeEngine
    private lateinit var theme: Theme
    private var themeComputed: Theme.Computed? = null

    // Neural swipe state
    private var currentSwipeGesture: SwipeInput? = null
    private var swipeTrajectory = mutableListOf<PointF>()
    private var swipeTimestamps = mutableListOf<Long>()
    private var isSwipeActive = false

    // Rendering dimensions
    private var keyWidth = 0f
    private var mainLabelSize = 0f
    private var subLabelSize = 0f
    private var marginRight = 0f
    private var marginLeft = 0f
    private var marginBottom = 0f
    private var insetsLeft = 0
    private var insetsRight = 0
    private var insetsBottom = 0

    // Paint objects for rendering
    private val swipeTrailPaint = Paint().apply {
        color = 0xFF1976D2.toInt()
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 180
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Parent service reference
    private var keyboardService: CleverKeysService? = null

    init {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        theme = Theme(context, attrs)
        config = Config.globalConfig()
        pointers = Pointers(this, config)

        // Initialize neural engine
        scope.launch {
            try {
                neuralEngine = NeuralSwipeEngine.getInstance()
                android.util.Log.d("Keyboard2View", "Neural engine initialized")
            } catch (e: Exception) {
                android.util.Log.e("Keyboard2View", "Failed to initialize neural engine", e)
            }
        }

        setupNavigationBar()
        setOnTouchListener(this)

        // Load keyboard layout
        val layoutId = attrs?.getAttributeResourceValue(null, "layout", 0) ?: 0
        if (layoutId == 0) {
            reset()
        } else {
            setKeyboard(KeyboardData.load(resources, layoutId))
        }
    }

    fun setKeyboardService(service: CleverKeysService) {
        keyboardService = service
    }

    private fun setupNavigationBar() {
        if (Build.VERSION.SDK_INT < 21) return

        val window = getParentWindow(context)
        window?.navigationBarColor = theme.colorNavBar

        if (Build.VERSION.SDK_INT >= 26) {
            var uiFlags = systemUiVisibility
            if (theme.isLightNavBar) {
                uiFlags = uiFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                uiFlags = uiFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            systemUiVisibility = uiFlags
        }
    }

    private fun getParentWindow(context: Context): Window? {
        return when (context) {
            is InputMethodService -> context.window?.window
            is ContextWrapper -> getParentWindow(context.baseContext)
            else -> null
        }
    }

    fun setKeyboard(keyboardData: KeyboardData) {
        keyboard = keyboardData
        shiftKeyValue = KeyValue.getKeyByName("shift")
        shiftKey = keyboardData.findKeyWithValue(shiftKeyValue)
        composeKeyValue = KeyValue.getKeyByName("compose")
        composeKey = keyboardData.findKeyWithValue(composeKeyValue)

        KeyModifier.set_modmap(keyboardData.modmap)
        reset()
    }

    fun reset() {
        modifiers = Pointers.Modifiers.EMPTY
        pointers.clear()
        clearSwipeState()
        requestLayout()
        invalidate()
    }

    fun clearSwipeState() {
        isSwipeActive = false
        swipeTrajectory.clear()
        swipeTimestamps.clear()
        currentSwipeGesture = null
        invalidate()
    }

    // Fake pointer state management
    fun setFakePointerLatched(key: KeyboardData.Key?, keyValue: KeyValue?, latched: Boolean, lock: Boolean) {
        if (keyboard == null || key == null) return
        pointers.set_fake_pointer_state(key, keyValue, latched, lock)
    }

    fun setShiftState(latched: Boolean, lock: Boolean) {
        setFakePointerLatched(shiftKey, shiftKeyValue, latched, lock)
    }

    fun setComposePending(pending: Boolean) {
        setFakePointerLatched(composeKey, composeKeyValue, pending, false)
    }

    fun setSelectionState(selectionState: Boolean) {
        setFakePointerLatched(
            KeyboardData.Key.EMPTY,
            KeyValue.getKeyByName("selection_mode"),
            selectionState,
            true
        )
    }

    fun modifyKey(keyValue: KeyValue, mods: Pointers.Modifiers): KeyValue {
        return KeyModifier.modify(keyValue, mods)
    }

    // Pointer event handlers
    override fun onPointerDown(keyValue: KeyValue, isSwipe: Boolean) {
        updateFlags()
        config.handler?.key_down(keyValue, isSwipe)
        invalidate()
        vibrate()
    }

    override fun onPointerUp(keyValue: KeyValue, mods: Pointers.Modifiers) {
        config.handler?.key_up(keyValue, mods)
        updateFlags()
        invalidate()
    }

    override fun onPointerHold(keyValue: KeyValue, mods: Pointers.Modifiers) {
        config.handler?.key_up(keyValue, mods)
        updateFlags()
    }

    override fun onPointerFlagsChanged(shouldVibrate: Boolean) {
        updateFlags()
        invalidate()
        if (shouldVibrate) vibrate()
    }

    private fun updateFlags() {
        modifiers = pointers.getModifiers()
        config.handler?.mods_changed(modifiers)
    }

    // Touch event handling
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                pointers.onTouchUp(pointerId)
                handleSwipeEnd()
            }

            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val pointerId = event.getPointerId(pointerIndex)
                val key = getKeyAtPosition(x, y)

                pointers.onTouchDown(x, y, pointerId, key)
                handleSwipeStart(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val x = event.getX(p)
                    val y = event.getY(p)
                    val pointerId = event.getPointerId(p)

                    pointers.onTouchMove(x, y, pointerId)
                    handleSwipeMove(x, y)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pointers.onTouchCancel()
                handleSwipeCancel()
            }
        }
        return true
    }

    // Neural swipe handling
    private fun handleSwipeStart(x: Float, y: Float) {
        if (!config.swipe_typing_enabled) return

        isSwipeActive = true
        swipeTrajectory.clear()
        swipeTimestamps.clear()

        swipeTrajectory.add(PointF(x, y))
        swipeTimestamps.add(System.currentTimeMillis())

        android.util.Log.d("Keyboard2View", "Neural swipe started at ($x, $y)")
    }

    private fun handleSwipeMove(x: Float, y: Float) {
        if (!isSwipeActive || !config.swipe_typing_enabled) return

        swipeTrajectory.add(PointF(x, y))
        swipeTimestamps.add(System.currentTimeMillis())

        // Trigger redraw to show swipe trail
        invalidate()
    }

    private fun handleSwipeEnd() {
        if (!isSwipeActive || !config.swipe_typing_enabled) return

        if (swipeTrajectory.size >= 2) {
            // Create swipe input for neural prediction
            val duration = if (swipeTimestamps.isNotEmpty()) {
                swipeTimestamps.last() - swipeTimestamps.first()
            } else 0L

            currentSwipeGesture = SwipeInput(
                trajectory = ArrayList(swipeTrajectory),
                timestamps = ArrayList(swipeTimestamps),
                duration = duration
            )

            // Process neural prediction asynchronously
            scope.launch {
                try {
                    val prediction = neuralEngine.predictAsync(currentSwipeGesture!!)
                    handleNeuralPrediction(prediction)
                } catch (e: Exception) {
                    android.util.Log.e("Keyboard2View", "Neural prediction failed", e)
                }
            }
        }

        clearSwipeState()
    }

    private fun handleSwipeCancel() {
        if (isSwipeActive) {
            android.util.Log.d("Keyboard2View", "Neural swipe cancelled")
            clearSwipeState()
        }
    }

    private suspend fun handleNeuralPrediction(prediction: PredictionResult) {
        android.util.Log.d("Keyboard2View",
            "Neural prediction: ${prediction.candidates.size} candidates, " +
            "processing time: ${prediction.processingTimeMs}ms")

        // Pass predictions to keyboard service
        keyboardService?.handleSwipePrediction(prediction)
    }

    // Key position calculation
    fun getKeyAtPosition(x: Float, y: Float): KeyboardData.Key? {
        val kbd = keyboard ?: return null
        val tc = themeComputed ?: return null

        var yPos = config.marginTop

        for (row in kbd.rows) {
            val rowTop = yPos + row.shift * tc.row_height
            val rowBottom = rowTop + row.height * tc.row_height

            if (y >= rowTop && y < rowBottom) {
                var xPos = marginLeft

                for (key in row.keys) {
                    xPos += key.shift * keyWidth
                    val keyWidth = this.keyWidth * key.width - tc.horizontal_margin

                    if (x >= xPos && x < xPos + keyWidth) {
                        return key
                    }
                    xPos += this.keyWidth * key.width
                }
                break
            }
            yPos += row.height * tc.row_height
        }
        return null
    }

    fun getRealKeyPositions(): Map<Char, PointF> {
        val keyPositions = mutableMapOf<Char, PointF>()
        val kbd = keyboard ?: return keyPositions
        val tc = themeComputed ?: return keyPositions

        var y = config.marginTop

        for (row in kbd.rows) {
            var x = marginLeft

            for (key in row.keys) {
                val xLeft = x + key.shift * keyWidth
                val xRight = xLeft + key.width * keyWidth
                val yTop = y + row.shift * tc.row_height
                val yBottom = yTop + row.height * tc.row_height

                val centerX = (xLeft + xRight) / 2f
                val centerY = (yTop + yBottom) / 2f

                // Extract character from key
                val keyValue = key.keys[0]
                if (keyValue != null) {
                    val char = keyValue.toString().firstOrNull()
                    if (char != null && char.isLetter()) {
                        keyPositions[char.lowercaseChar()] = PointF(centerX, centerY)
                    }
                }

                x += keyWidth * key.width
            }
            y += row.height * tc.row_height
        }

        return keyPositions
    }

    // Layout and drawing
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val kbd = keyboard
        if (kbd == null) {
            setMeasuredDimension(0, 0)
            return
        }

        val dm = resources.displayMetrics
        val windowWidth = getWindowWidth(dm)

        // Calculate margins and insets
        calculateInsets()
        calculateMargins(windowWidth)

        // Calculate keyboard dimensions
        val keyboardWidth = windowWidth - marginLeft - marginRight - insetsLeft - insetsRight
        keyWidth = keyboardWidth / kbd.keysWidth

        // Create theme computed values
        themeComputed = theme.computeTheme(dm)
        val tc = themeComputed!!

        mainLabelSize = keyWidth * tc.label_text_size
        subLabelSize = keyWidth * tc.sublabel_text_size

        // Calculate total height
        val keyboardHeight = kbd.keysHeight * tc.row_height + config.marginTop + marginBottom

        setMeasuredDimension(
            windowWidth,
            keyboardHeight.toInt() + insetsBottom
        )
    }

    private fun getWindowWidth(dm: DisplayMetrics): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            windowMetrics.bounds.width() - insets.left - insets.right
        } else {
            dm.widthPixels
        }
    }

    private fun calculateInsets() {
        if (Build.VERSION.SDK_INT >= 23) {
            val insets = rootWindowInsets
            if (insets != null) {
                insetsLeft = insets.systemWindowInsetLeft
                insetsRight = insets.systemWindowInsetRight
                insetsBottom = insets.systemWindowInsetBottom
            }
        }
    }

    private fun calculateMargins(windowWidth: Int) {
        marginLeft = config.horizontal_margin
        marginRight = config.horizontal_margin
        marginBottom = config.margin_bottom

        // Ensure minimum margins
        val minMargin = 8f * resources.displayMetrics.density
        if (marginLeft < minMargin) marginLeft = minMargin
        if (marginRight < minMargin) marginRight = minMargin
    }

    override fun onDraw(canvas: Canvas) {
        val kbd = keyboard ?: return
        val tc = themeComputed ?: return

        // Set keyboard background opacity
        background?.alpha = config.keyboardOpacity

        var y = tc.margin_top

        for (row in kbd.rows) {
            y += row.shift * tc.row_height
            var x = marginLeft + tc.margin_left
            val keyHeight = row.height * tc.row_height - tc.vertical_margin

            for (key in row.keys) {
                x += key.shift * keyWidth
                val keyWidth = this.keyWidth * key.width - tc.horizontal_margin
                val isKeyDown = pointers.isKeyDown(key)
                val tcKey = if (isKeyDown) tc.key_activated else tc.key

                drawKeyFrame(canvas, x, y, keyWidth, keyHeight, tcKey)

                // Draw main label
                key.keys[0]?.let { keyValue ->
                    drawLabel(canvas, keyValue, keyWidth / 2f + x, y, keyHeight, isKeyDown, tcKey)
                }

                // Draw sub-labels
                for (i in 1 until 9) {
                    key.keys[i]?.let { keyValue ->
                        drawSubLabel(canvas, keyValue, x, y, keyWidth, keyHeight, i, isKeyDown, tcKey)
                    }
                }

                drawIndication(canvas, key, x, y, keyWidth, keyHeight, tc)
                x += this.keyWidth * key.width
            }
            y += row.height * tc.row_height
        }

        // Draw neural swipe trail
        if (config.swipe_typing_enabled && isSwipeActive) {
            drawSwipeTrail(canvas)
        }
    }

    private fun drawSwipeTrail(canvas: Canvas) {
        if (swipeTrajectory.size < 2) return

        val path = Path()
        val firstPoint = swipeTrajectory[0]
        path.moveTo(firstPoint.x, firstPoint.y)

        for (i in 1 until swipeTrajectory.size) {
            val point = swipeTrajectory[i]
            path.lineTo(point.x, point.y)
        }

        canvas.drawPath(path, swipeTrailPaint)
    }

    private fun drawKeyFrame(
        canvas: Canvas,
        x: Float,
        y: Float,
        keyWidth: Float,
        keyHeight: Float,
        tc: Theme.Computed.Key
    ) {
        val r = tc.border_radius
        val w = tc.border_width
        val padding = w / 2f

        val rect = RectF(x + padding, y + padding, x + keyWidth - padding, y + keyHeight - padding)
        canvas.drawRoundRect(rect, r, r, tc.bg_paint)

        if (w > 0f) {
            val overlap = r - r * 0.85f + w // sin(45°)
            drawBorder(canvas, x, y, x + overlap, y + keyHeight, tc.border_left_paint, tc)
            drawBorder(canvas, x + keyWidth - overlap, y, x + keyWidth, y + keyHeight, tc.border_right_paint, tc)
            drawBorder(canvas, x, y, x + keyWidth, y + overlap, tc.border_top_paint, tc)
            drawBorder(canvas, x, y + keyHeight - overlap, x + keyWidth, y + keyHeight, tc.border_bottom_paint, tc)
        }
    }

    private fun drawBorder(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
        tc: Theme.Computed.Key
    ) {
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, tc.border_radius, tc.border_radius, paint)
    }

    private fun drawLabel(
        canvas: Canvas,
        keyValue: KeyValue,
        x: Float,
        y: Float,
        keyHeight: Float,
        isPressed: Boolean,
        tc: Theme.Computed.Key
    ) {
        val paint = if (isPressed) tc.label_activated_paint else tc.label_paint
        val text = keyValue.toString()
        val textY = y + keyHeight / 2f + paint.textSize / 3f

        canvas.drawText(text, x, textY, paint)
    }

    private fun drawSubLabel(
        canvas: Canvas,
        keyValue: KeyValue,
        x: Float,
        y: Float,
        keyWidth: Float,
        keyHeight: Float,
        position: Int,
        isPressed: Boolean,
        tc: Theme.Computed.Key
    ) {
        val paint = if (isPressed) tc.sublabel_activated_paint else tc.sublabel_paint
        val text = keyValue.toString()

        // Calculate position based on index (1-8 for corners and sides)
        val (textX, textY) = when (position) {
            1 -> Pair(x + keyWidth * 0.2f, y + keyHeight * 0.3f)           // Top-left
            2 -> Pair(x + keyWidth * 0.5f, y + keyHeight * 0.2f)           // Top
            3 -> Pair(x + keyWidth * 0.8f, y + keyHeight * 0.3f)           // Top-right
            4 -> Pair(x + keyWidth * 0.9f, y + keyHeight * 0.5f)           // Right
            5 -> Pair(x + keyWidth * 0.8f, y + keyHeight * 0.8f)           // Bottom-right
            6 -> Pair(x + keyWidth * 0.5f, y + keyHeight * 0.9f)           // Bottom
            7 -> Pair(x + keyWidth * 0.2f, y + keyHeight * 0.8f)           // Bottom-left
            8 -> Pair(x + keyWidth * 0.1f, y + keyHeight * 0.5f)           // Left
            else -> Pair(x + keyWidth * 0.5f, y + keyHeight * 0.5f)        // Center
        }

        canvas.drawText(text, textX, textY, paint)
    }

    private fun drawIndication(
        canvas: Canvas,
        key: KeyboardData.Key,
        x: Float,
        y: Float,
        keyWidth: Float,
        keyHeight: Float,
        tc: Theme.Computed
    ) {
        // Draw additional key indicators (shift state, etc.)
        if (pointers.isKeyLocked(key)) {
            val indicatorSize = keyWidth * 0.1f
            val paint = Paint().apply {
                color = tc.accent_color
                style = Paint.Style.FILL
            }
            canvas.drawCircle(x + keyWidth - indicatorSize, y + indicatorSize, indicatorSize / 2f, paint)
        }
    }

    private fun vibrate() {
        if (config.vibrate_custom) {
            // Trigger haptic feedback
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    // Utility enum for vertical alignment
    enum class Vertical {
        TOP, CENTER, BOTTOM
    }

    companion object {
        private const val TAG = "Keyboard2View"
        private var currentWhat = 0
    }
}