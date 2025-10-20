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

    // Config will be set by CleverKeysService via setViewConfig()
    private var config: Config? = null
    private lateinit var theme: Theme
    private var themeComputed: Theme.Computed? = null

    // Neural swipe state
    private var currentSwipeGesture: SwipeInput? = null
    private var swipeTrajectory = mutableListOf<PointF>()
    private var swipeTimestamps = mutableListOf<Long>()
    private var isSwipeActive = false
    private var swipePointerId = -1  // Track which pointer is doing the swipe

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

    // Keyboard height control
    private var keyboardHeightPercent = 35 // Default 35%
    private var calculatedHeight = 0

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

        // Pointers will be initialized when config is set via setViewConfig()

        setupNavigationBar()
        setOnTouchListener(this)

        // Load keyboard layout
        val layoutId = attrs?.getAttributeResourceValue(null, "layout", 0) ?: 0
        if (layoutId == 0) {
            reset()
        } else {
            val keyboardData = KeyboardData.load(resources, layoutId)
            if (keyboardData != null) {
                setKeyboard(keyboardData)
            } else {
                reset()
            }
        }
    }

    fun setKeyboardService(service: CleverKeysService) {
        keyboardService = service
    }

    /**
     * Set keyboard height percentage (0-100)
     */
    fun setKeyboardHeightPercent(percent: Int) {
        keyboardHeightPercent = percent.coerceIn(10, 100)
        requestLayout() // Trigger remeasure
        android.util.Log.d("Keyboard2View", "Keyboard height set to $keyboardHeightPercent%")
    }

    /**
     * Set configuration for the view - must be called before view becomes active
     */
    fun setViewConfig(cfg: Config) {
        config = cfg
        // Initialize pointers now that config is available
        if (!::pointers.isInitialized) {
            try {
                pointers = Pointers(this, cfg)
                android.util.Log.d("Keyboard2View", "Pointers initialized with config")
            } catch (e: Exception) {
                android.util.Log.e("Keyboard2View", "Failed to initialize pointers", e)
            }
        }
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
        shiftKey = shiftKeyValue?.let { keyboardData.findKeyWithValue(it) }
        composeKeyValue = KeyValue.getKeyByName("compose")
        composeKey = composeKeyValue?.let { keyboardData.findKeyWithValue(it) }

        KeyModifier.set_modmap(keyboardData.modmap)
        reset()
    }

    fun reset() {
        modifiers = Pointers.Modifiers.EMPTY
        // Check if pointers is initialized before clearing (prevents crash during initialization)
        if (::pointers.isInitialized) {
            pointers.clear()
        }
        clearSwipeState()
        requestLayout()
        invalidate()
    }

    fun clearSwipeState() {
        isSwipeActive = false
        swipePointerId = -1
        swipeTrajectory.clear()
        swipeTimestamps.clear()
        currentSwipeGesture = null
        invalidate()
    }

    // Fake pointer state management
    fun setFakePointerLatched(key: KeyboardData.Key?, keyValue: KeyValue?, latched: Boolean, lock: Boolean) {
        if (keyboard == null || key == null || keyValue == null) return
        pointers.setFakePointerState(key, keyValue, latched, lock)
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

    private fun modifyKeyInternal(keyValue: KeyValue, mods: Pointers.Modifiers): KeyValue {
        return KeyModifier.modify(keyValue, mods)
    }

    // Pointer event handlers
    override fun onPointerDown(keyValue: KeyValue, isSwipe: Boolean) {
        updateFlags()
        config?.handler?.key_down(keyValue, isSwipe)
        invalidate()
        vibrate()
    }

    override fun onPointerUp(keyValue: KeyValue, mods: Pointers.Modifiers) {
        config?.handler?.key_up(keyValue, mods)
        updateFlags()
        invalidate()
    }

    override fun onPointerHold(keyValue: KeyValue, mods: Pointers.Modifiers) {
        config?.handler?.key_up(keyValue, mods)
        updateFlags()
    }

    override fun onPointerFlagsChanged(shouldVibrate: Boolean) {
        updateFlags()
        invalidate()
        if (shouldVibrate) vibrate()
    }

    override fun onSwipeMove(x: Float, y: Float, recognizer: EnhancedSwipeGestureRecognizer) {
        // Handle swipe trajectory updates for neural prediction
        recognizer.addPoint(x, y)
        invalidate()
    }

    override fun onSwipeEnd(recognizer: EnhancedSwipeGestureRecognizer) {
        // Process complete swipe gesture - handled by handleSwipeEnd() instead
        invalidate()
    }

    private fun updateFlags() {
        modifiers = pointers.getModifiers()
        config?.handler?.mods_changed(modifiers)
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

                if (key != null) {
                    pointers.onTouchDown(x, y, pointerId, key)
                }

                // Only start swipe if not already active (first finger down wins)
                if (!isSwipeActive) {
                    handleSwipeStart(x, y, pointerId)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val x = event.getX(p)
                    val y = event.getY(p)
                    val pointerId = event.getPointerId(p)

                    pointers.onTouchMove(x, y, pointerId)

                    // Only record coordinates from the pointer that started the swipe
                    if (isSwipeActive && pointerId == swipePointerId) {
                        handleSwipeMove(x, y)
                    }
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
    private fun handleSwipeStart(x: Float, y: Float, pointerId: Int) {
        if (config?.swipe_typing_enabled != true) return

        isSwipeActive = true
        swipePointerId = pointerId  // Remember which pointer is swiping
        swipeTrajectory.clear()
        swipeTimestamps.clear()

        swipeTrajectory.add(PointF(x, y))
        swipeTimestamps.add(System.currentTimeMillis())

        android.util.Log.d("Keyboard2View", "Neural swipe started at ($x, $y) with pointer $pointerId")
    }

    private fun handleSwipeMove(x: Float, y: Float) {
        if (!isSwipeActive || config?.swipe_typing_enabled != true) return

        swipeTrajectory.add(PointF(x, y))
        swipeTimestamps.add(System.currentTimeMillis())

        // Trigger redraw to show swipe trail
        invalidate()
    }

    private fun handleSwipeEnd() {
        if (!isSwipeActive || config?.swipe_typing_enabled != true) {
            clearSwipeState()
            return
        }

        if (swipeTrajectory.size >= 2) {
            // Calculate path length to distinguish taps from swipes
            var pathLength = 0f
            for (i in 1 until swipeTrajectory.size) {
                val prev = swipeTrajectory[i - 1]
                val curr = swipeTrajectory[i]
                val dx = curr.x - prev.x
                val dy = curr.y - prev.y
                pathLength += kotlin.math.sqrt(dx * dx + dy * dy)
            }

            // Only process as swipe if path is long enough (minimum 10 pixels)
            // Short paths are taps, not swipes
            if (pathLength >= 10f) {
                // Create swipe input for neural prediction
                val duration = if (swipeTimestamps.isNotEmpty()) {
                    swipeTimestamps.last() - swipeTimestamps.first()
                } else 0L

                currentSwipeGesture = SwipeInput(
                    coordinates = ArrayList(swipeTrajectory),
                    timestamps = ArrayList(swipeTimestamps),
                    touchedKeys = emptyList()
                )

                // Process neural prediction asynchronously via service
                currentSwipeGesture?.let { gesture ->
                    android.util.Log.d("Keyboard2View", "Swipe gesture completed: ${gesture.coordinates.size} points, length: $pathLength px")
                    // Pass gesture data to service for neural prediction
                    val gestureData = CleverKeysService.SwipeGestureData(
                        path = gesture.coordinates,
                        timestamps = gesture.timestamps
                    )
                    keyboardService?.handleSwipeGesture(gestureData)
                }
            } else {
                android.util.Log.d("Keyboard2View", "Gesture too short ($pathLength px), treating as tap")
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

    // Key position calculation
    fun getKeyAtPosition(x: Float, y: Float): KeyboardData.Key? {
        val kbd = keyboard ?: return null
        val tc = themeComputed ?: return null

        var yPos = config?.marginTop ?: 0f

        for (row in kbd.rows) {
            val rowTop = yPos + row.shift * tc.rowHeight
            val rowBottom = rowTop + row.height * tc.rowHeight

            if (y >= rowTop && y < rowBottom) {
                var xPos = marginLeft

                for (key in row.keys) {
                    xPos += key.shift * keyWidth
                    val keyWidth = this.keyWidth * key.width - tc.horizontalMargin

                    if (x >= xPos && x < xPos + keyWidth) {
                        return key
                    }
                    xPos += this.keyWidth * key.width
                }
                break
            }
            yPos += row.height * tc.rowHeight
        }
        return null
    }

    fun getRealKeyPositions(): Map<Char, PointF> {
        val keyPositions = mutableMapOf<Char, PointF>()
        val kbd = keyboard ?: return keyPositions
        val tc = themeComputed ?: return keyPositions

        var y = config?.marginTop ?: 0f

        for (row in kbd.rows) {
            var x = marginLeft

            for (key in row.keys) {
                val xLeft = x + key.shift * keyWidth
                val xRight = xLeft + key.width * keyWidth
                val yTop = y + row.shift * tc.rowHeight
                val yBottom = yTop + row.height * tc.rowHeight

                val centerX = (xLeft + xRight) / 2f
                val centerY = (yTop + yBottom) / 2f

                // Extract character from key
                val keyValue = key.keys[0]
                if (keyValue != null) {
                    // Type-safe character extraction from CharKey
                    val char = (keyValue as? KeyValue.CharKey)?.char
                    if (char != null && char.isLetter()) {
                        keyPositions[char.lowercaseChar()] = PointF(centerX, centerY)
                    }
                }

                x += keyWidth * key.width
            }
            y += row.height * tc.rowHeight
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
        val tc = config?.let { Theme.Computed(theme, it, keyWidth, kbd) } ?: return
        themeComputed = tc

        // Fix #53: Dynamic text size calculation matching Java implementation
        // labelBaseSize = min(row_height - vertical_margin, (width/10 - horizontal_margin) * 3/2) * characterSize
        val cfg = config ?: return
        val labelBaseSize = minOf(
            tc.rowHeight - tc.verticalMargin,
            (keyWidth - tc.horizontalMargin) * 1.5f
        ) * cfg.characterSize

        mainLabelSize = labelBaseSize * cfg.labelTextSize
        subLabelSize = labelBaseSize * cfg.sublabelTextSize

        // Calculate total height
        val naturalHeight = kbd.keysHeight * tc.rowHeight + (config?.marginTop ?: 0f) + marginBottom

        // Apply user-configured height percentage
        val screenHeight = dm.heightPixels
        calculatedHeight = (screenHeight * keyboardHeightPercent / 100f).toInt()

        // Use user preference if set, otherwise use natural keyboard height
        val finalHeight = if (keyboardHeightPercent != 35) { // 35 is default
            calculatedHeight
        } else {
            naturalHeight.toInt() + insetsBottom
        }

        android.util.Log.d("Keyboard2View", "onMeasure: natural=${naturalHeight.toInt()}, calculated=$calculatedHeight (${keyboardHeightPercent}%), using=$finalHeight")

        // Notify service of keyboard dimensions for neural prediction
        keyboardService?.updateKeyboardDimensions(windowWidth, finalHeight)

        setMeasuredDimension(
            windowWidth,
            finalHeight
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
        marginLeft = 8f // Default horizontal margin
        marginRight = 8f // Default horizontal margin
        marginBottom = config?.margin_bottom ?: 0f

        // Ensure minimum margins
        val minMargin = 8f * resources.displayMetrics.density
        if (marginLeft < minMargin) marginLeft = minMargin
        if (marginRight < minMargin) marginRight = minMargin
    }

    override fun onDraw(canvas: Canvas) {
        val kbd = keyboard ?: return
        val tc = themeComputed ?: return

        // Set keyboard background opacity
        background?.alpha = config?.keyboardOpacity ?: 255

        var y = tc.marginTop

        for (row in kbd.rows) {
            y += row.shift * tc.rowHeight
            var x = marginLeft + tc.marginLeft
            val keyHeight = row.height * tc.rowHeight - tc.verticalMargin

            for (key in row.keys) {
                x += key.shift * keyWidth
                val keyWidth = this.keyWidth * key.width - tc.horizontalMargin
                val isKeyDown = pointers.isKeyDown(key)
                val tcKey = if (isKeyDown) tc.keyActivated else tc.key

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
            y += row.height * tc.rowHeight
        }

        // Draw neural swipe trail
        if (config?.swipe_typing_enabled == true && isSwipeActive) {
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
        val r = tc.borderRadius
        val w = tc.borderWidth
        val padding = w / 2f

        val rect = RectF(x + padding, y + padding, x + keyWidth - padding, y + keyHeight - padding)
        canvas.drawRoundRect(rect, r, r, tc.bgPaint)

        if (w > 0f) {
            val overlap = r - r * 0.85f + w // sin(45°)
            drawBorder(canvas, x, y, x + overlap, y + keyHeight, tc.borderLeftPaint, tc)
            drawBorder(canvas, x + keyWidth - overlap, y, x + keyWidth, y + keyHeight, tc.borderRightPaint, tc)
            drawBorder(canvas, x, y, x + keyWidth, y + overlap, tc.borderTopPaint, tc)
            drawBorder(canvas, x, y + keyHeight - overlap, x + keyWidth, y + keyHeight, tc.borderBottomPaint, tc)
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
        canvas.drawRoundRect(rect, tc.borderRadius, tc.borderRadius, paint)
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
        // Apply modifiers like shift/compose
        val modifiedKey = modifyKey(keyValue, pointers.getModifiers()) ?: return

        val textSize = scaleTextSize(modifiedKey, true)
        val color = labelColor(modifiedKey, isPressed, false)
        val paint = tc.labelPaint(modifiedKey.hasFlag(KeyValue.Flag.KEY_FONT), color, textSize)

        // Proper vertical centering using paint metrics
        val textY = (keyHeight - paint.ascent() - paint.descent()) / 2f + y

        canvas.drawText(modifiedKey.displayString, x, textY, paint)
    }

    enum class Vertical { TOP, CENTER, BOTTOM }

    companion object {
        val LABEL_POSITION_H = arrayOf(
            Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
            Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
            Paint.Align.CENTER, Paint.Align.CENTER
        )

        val LABEL_POSITION_V = arrayOf(
            Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
            Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
            Vertical.BOTTOM
        )
    }

    private fun drawSubLabel(
        canvas: Canvas,
        keyValue: KeyValue,
        x: Float,
        y: Float,
        keyWidth: Float,
        keyHeight: Float,
        subIndex: Int,
        isPressed: Boolean,
        tc: Theme.Computed.Key
    ) {
        val align = LABEL_POSITION_H[subIndex]
        val vertical = LABEL_POSITION_V[subIndex]

        // Apply modifiers
        val modifiedKey = modifyKey(keyValue, pointers.getModifiers()) ?: return

        val textSize = scaleTextSize(modifiedKey, false)
        val color = labelColor(modifiedKey, isPressed, true)
        val paint = tc.subLabelPaint(
            modifiedKey.hasFlag(KeyValue.Flag.KEY_FONT),
            color,
            textSize,
            align
        )

        val subPadding = config?.keyPadding ?: 5f

        // Calculate vertical position
        var textY = y
        textY += when (vertical) {
            Vertical.CENTER -> (keyHeight - paint.ascent() - paint.descent()) / 2f
            Vertical.TOP -> subPadding - paint.ascent()
            Vertical.BOTTOM -> keyHeight - subPadding - paint.descent()
        }

        // Calculate horizontal position
        var textX = x
        textX += when (align) {
            Paint.Align.CENTER -> keyWidth / 2f
            Paint.Align.LEFT -> subPadding
            Paint.Align.RIGHT -> keyWidth - subPadding
            else -> keyWidth / 2f
        }

        // Limit string keys to 3 characters
        val label = modifiedKey.displayString
        val labelLen = if (label.length > 3 && modifiedKey is KeyValue.StringKey) 3 else label.length

        canvas.drawText(label, 0, labelLen, textX, textY, paint)
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
        // Draw additional key indicators (shift state, locked keys, etc.)
        key.keys.getOrNull(0)?.let { keyValue ->
            val isLocked = pointers.isKeyLocked(keyValue)
            val isLatched = pointers.isKeyLatched(keyValue)

            if (isLocked || isLatched) {
                val indicatorSize = keyWidth * 0.1f
                val paint = Paint().apply {
                    color = if (isLocked) theme.activatedColor else theme.secondaryLabelColor
                    style = Paint.Style.FILL
                    alpha = if (isLocked) 255 else 180
                }
                // Draw indicator dot in top-right corner
                canvas.drawCircle(
                    x + keyWidth - indicatorSize * 1.5f,
                    y + indicatorSize * 1.5f,
                    indicatorSize / 2f,
                    paint
                )
            }
        }
    }

    private fun vibrate() {
        if (config?.vibrate_custom == true) {
            // Trigger haptic feedback
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun scaleTextSize(keyValue: KeyValue, isMainLabel: Boolean): Float {
        val smallerFont = if (keyValue.hasFlag(KeyValue.Flag.SMALLER_FONT)) 0.75f else 1f
        val labelSize = if (isMainLabel) mainLabelSize else subLabelSize
        return labelSize * smallerFont
    }

    private fun labelColor(keyValue: KeyValue, isKeyDown: Boolean, isSubLabel: Boolean): Int {
        if (isKeyDown) {
            val flags = pointers.getKeyFlags(keyValue)
            if (flags != -1) {
                if ((flags and Pointers.FLAG_P_LOCKED) != 0) {
                    return theme.lockedColor
                }
                return theme.activatedColor
            }
        }

        if (keyValue.hasFlag(KeyValue.Flag.SECONDARY) || keyValue.hasFlag(KeyValue.Flag.GREYED)) {
            if (keyValue.hasFlag(KeyValue.Flag.GREYED)) {
                return theme.greyedLabelColor
            }
            return theme.secondaryLabelColor
        }

        return if (isSubLabel) theme.subLabelColor else theme.labelColor
    }

    override fun modifyKey(keyValue: KeyValue?, modifiers: Pointers.Modifiers): KeyValue {
        return if (keyValue != null) {
            modifyKeyInternal(keyValue, modifiers)
        } else {
            KeyValue.CharKey(' ')
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}