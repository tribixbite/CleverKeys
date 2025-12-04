package tribixbite.cleverkeys

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build.VERSION
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import tribixbite.cleverkeys.theme.ThemeProvider
import java.util.ArrayList

/**
 * Custom View that renders and manages the keyboard interface.
 *
 * This view is responsible for:
 * - **Rendering**: Draws keyboard keys, labels, modifiers, and visual feedback
 * - **Touch Handling**: Processes multi-touch input via [Pointers] system
 * - **Swipe Gestures**: Recognizes swipe typing gestures through [EnhancedSwipeGestureRecognizer]
 * - **Visual Feedback**: Shows swipe trails, key press animations, and modifier states
 * - **Layout Management**: Positions keys based on screen size and [KeyboardData] layout
 * - **Theme Support**: Applies colors and styles from [Theme] configuration
 * - **Modifier States**: Displays and manages Shift, Fn, Ctrl, Alt, and Meta key states
 *
 * ## Touch Processing Flow
 * 1. [onTouch] receives raw MotionEvent
 * 2. [Pointers] tracks individual finger positions and gestures
 * 3. [EnhancedSwipeGestureRecognizer] detects swipe typing patterns
 * 4. [IPointerEventHandler] callbacks notify parent ([CleverKeysService]) of key events
 *
 * ## Swipe Typing
 * When swipe typing is enabled, the view:
 * - Captures touch trajectory as [List]<[PointF]> coordinates
 * - Visualizes swipe trail in real-time during gesture
 * - Sends completed gesture to [WordPredictor] for neural network prediction
 * - Clears trail and resets state after prediction
 *
 * ## Performance Optimizations
 * - Reuses [Path] objects for swipe trail rendering (zero allocation during draw)
 * - Caches [Theme.Computed] instances in [LruCache] for fast theme lookups
 * - Batches canvas draw calls for efficient rendering
 * - Handles system insets (notches, nav bars) for proper key positioning
 *
 * @param context Android context for accessing resources and system services
 * @param attrs XML attributes for view inflation (nullable for programmatic creation)
 * @since v1.0 (migrated to Kotlin in v1.32.874)
 */
class Keyboard2View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), View.OnTouchListener, Pointers.IPointerEventHandler {

    private var _keyboard: KeyboardData? = null

    /** The key holding the shift key is used to set shift state from autocapitalisation. */
    private var _shift_kv: KeyValue? = null
    private var _shift_key: KeyboardData.Key? = null

    /** Used to add fake pointers. */
    private var _compose_kv: KeyValue? = null
    private var _compose_key: KeyboardData.Key? = null

    private lateinit var _pointers: Pointers

    private var _mods: Pointers.Modifiers = Pointers.Modifiers.EMPTY

    private lateinit var _config: Config

    private var _swipeRecognizer: EnhancedSwipeGestureRecognizer? = null
    private var _swipeTrailPaint: Paint? = null
    // Reusable Path object for swipe trail rendering (to avoid allocation every frame)
    private val _swipeTrailPath = Path()

    // Swipe typing integration
    private var _wordPredictor: WordPredictor? = null

    // CGR prediction storage
    private val _cgrPredictions = ArrayList<String>()
    private var _cgrFinalPredictions = false
    private var _keyboard2: CleverKeysService? = null

    private var _keyWidth = 0f
    private var _mainLabelSize = 0f
    private var _subLabelSize = 0f
    private var _marginRight = 0f
    private var _marginLeft = 0f
    private var _marginBottom = 0f
    private var _insets_left = 0
    private var _insets_right = 0
    private var _insets_bottom = 0

    private lateinit var _theme: Theme
    private var _tc: Theme.Computed? = null
    private lateinit var _themeCache: LruCache<String, Theme.Computed>

    enum class Vertical {
        TOP, CENTER, BOTTOM
    }

    init {
        _config = Config.globalConfig()
        // Load theme: use ThemeProvider for runtime themes (decorative/custom),
        // otherwise use XML-based Theme constructor
        _theme = if (_config.isRuntimeTheme()) {
            ThemeProvider.getInstance(context).getTheme(_config.themeName)
        } else {
            Theme(getContext(), attrs)
        }
        _pointers = Pointers(this, _config, getContext())
        _swipeRecognizer = _pointers._swipeRecognizer // Share the recognizer
        _themeCache = LruCache(5)

        initSwipeTrailPaint()
        refresh_navigation_bar(context)
        setOnTouchListener(this)
        val layout_id = attrs?.getAttributeResourceValue(null, "layout", 0) ?: 0
        if (layout_id != 0) {
            val kw = KeyboardData.load(resources, layout_id)
            if (kw != null)
                setKeyboard(kw)
        } else {
            reset()
        }
    }

    private fun initSwipeTrailPaint() {
        val config = _config
        val density = resources.displayMetrics.density

        _swipeTrailPaint = Paint().apply {
            color = config?.swipe_trail_color ?: 0xFF9B59B6.toInt()
            strokeWidth = (config?.swipe_trail_width ?: 8.0f) * density
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND

            // Apply effect based on setting
            when (config?.swipe_trail_effect ?: "glow") {
                "glow" -> {
                    // GPU-efficient glow using blur mask filter
                    // Use SOLID blur type for crisp center with soft edges
                    val glowRadius = (config?.swipe_trail_glow_radius ?: 8.0f) * density * 0.5f
                    maskFilter = android.graphics.BlurMaskFilter(
                        glowRadius.coerceAtLeast(1f),
                        android.graphics.BlurMaskFilter.Blur.SOLID // SOLID gives crisp center
                    )
                    alpha = 240 // More opaque for better visibility
                }
                "solid" -> {
                    maskFilter = null
                    alpha = 255 // Fully opaque
                }
                "fade" -> {
                    maskFilter = null
                    alpha = 140 // More transparent
                }
                "rainbow" -> {
                    // Rainbow uses shader - will cycle colors during draw
                    maskFilter = null
                    alpha = 200
                }
                "none" -> {
                    alpha = 0 // Invisible
                }
                else -> {
                    maskFilter = null
                    alpha = 180
                }
            }
        }
    }

    private fun getParentWindow(context: Context): Window? {
        return when (context) {
            is InputMethodService -> context.window?.window
            is ContextWrapper -> getParentWindow(context.baseContext)
            else -> null
        }
    }

    fun refresh_navigation_bar(context: Context) {
        if (VERSION.SDK_INT < 21)
            return
        // The intermediate Window is a [Dialog].
        val w = getParentWindow(context)
        w?.navigationBarColor = _theme.colorNavBar
        if (VERSION.SDK_INT < 26)
            return
        var uiFlags = systemUiVisibility
        uiFlags = if (_theme.isLightNavBar)
            uiFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else
            uiFlags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        systemUiVisibility = uiFlags
    }

    fun setKeyboard(kw: KeyboardData) {
        _keyboard = kw
        val shiftKv = KeyValue.getKeyByName("shift")
        _shift_kv = shiftKv
        _shift_key = kw.findKeyWithValue(shiftKv)
        val composeKv = KeyValue.getKeyByName("compose")
        _compose_kv = composeKv
        _compose_key = kw.findKeyWithValue(composeKv)
        kw.modmap?.let { KeyModifier.set_modmap(it) }

        // CRITICAL FIX: Pre-calculate key width based on screen width
        // This ensures getKeyAtPosition works immediately for swipes before onMeasure() runs
        // Always recalculate because layout or screen dims might have changed (view reuse)
        run {
            val dm = resources.displayMetrics
            val screenWidth = dm.widthPixels
            val marginLeft = maxOf(_config.horizontal_margin.toFloat(), _insets_left.toFloat())
            val marginRight = maxOf(_config.horizontal_margin.toFloat(), _insets_right.toFloat())
            _keyWidth = (screenWidth - marginLeft - marginRight) / kw.keysWidth

            // Ensure theme cache is initialized for key detection
            val cacheKey = "${kw.name ?: ""}_$_keyWidth"
            _tc = _themeCache.get(cacheKey) ?: run {
                val computed = Theme.Computed(_theme, _config, _keyWidth, kw)
                _themeCache.put(cacheKey, computed)
                computed
            }
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("Keyboard2View", "Pre-calculated keyWidth=$_keyWidth for immediate touch handling")
            }
        }

        // Initialize swipe recognizer if not already created
        if (_swipeRecognizer == null) {
            _swipeRecognizer = EnhancedSwipeGestureRecognizer()
        }

        // ENABLE PROBABILISTIC DETECTION:
        // Pass the keyboard layout to the recognizer so it can find "nearest keys"
        // instead of failing on gaps/misalignment (crucial for startup race condition)
        _swipeRecognizer?.let { recognizer ->
            val estimatedWidth = _keyWidth * kw.keysWidth + _marginLeft + _marginRight
            val estimatedHeight = _tc?.row_height?.times(kw.keysHeight) ?: 0f
            recognizer.setKeyboard(kw, estimatedWidth, estimatedHeight)
        }

        reset()
    }

    fun reset() {
        _mods = Pointers.Modifiers.EMPTY
        _pointers.clear()
        requestLayout()
        invalidate()
    }

    /**
     * Clear swipe typing state after suggestion selection
     */
    fun clearSwipeState() {
        // Clear any ongoing swipe gestures
        _pointers.clear()
        invalidate()
    }

    fun set_fake_ptr_latched(key: KeyboardData.Key?, kv: KeyValue?, latched: Boolean, lock: Boolean) {
        if (_keyboard == null || key == null || kv == null)
            return
        _pointers.set_fake_pointer_state(key, kv, latched, lock)
    }

    /** Called by auto-capitalisation. */
    fun set_shift_state(latched: Boolean, lock: Boolean) {
        val shiftKv = _shift_kv
        set_fake_ptr_latched(_shift_key, shiftKv, latched, lock)
    }

    /**
     * Clears latched modifier state (e.g., shift) after swipe word insertion.
     * Called from InputCoordinator when a swipe word is committed.
     */
    fun clearLatchedModifiers() {
        _pointers.clearLatched()
        _mods = _pointers.getModifiers()
        _config.handler?.mods_changed(_mods)
        invalidate()
    }

    /**
     * Check if shift is currently locked (caps lock mode, not just latched).
     * Used for ALL CAPS swipe typing: caps lock + swipe = ENTIRE WORD UPPERCASE.
     * @return true if shift is locked (caps lock), false if just latched or not active
     */
    fun isShiftLocked(): Boolean {
        val shiftKv = _shift_kv ?: return false
        val flags = _pointers.getKeyFlags(shiftKv)
        return flags != -1 && (flags and Pointers.FLAG_P_LOCKED) != 0
    }

    /** Called from [KeyEventHandler]. */
    fun set_compose_pending(pending: Boolean) {
        val composeKv = _compose_kv
        set_fake_ptr_latched(_compose_key, composeKv, pending, false)
    }

    /** Called from [CleverKeysService.onUpdateSelection]. */
    fun set_selection_state(selection_state: Boolean) {
        val selectionModeKv = KeyValue.getKeyByName("selection_mode")
        set_fake_ptr_latched(
            KeyboardData.Key.EMPTY,
            selectionModeKv,
            selection_state,
            true
        )
    }

    override fun modifyKey(k: KeyValue?, mods: Pointers.Modifiers): KeyValue? {
        return KeyModifier.modify(k, mods)
    }

    override fun onPointerDown(k: KeyValue?, isSwipe: Boolean) {
        updateFlags()
        _config.handler?.key_down(k, isSwipe)
        invalidate()
        vibrate()
    }

    override fun onPointerUp(k: KeyValue?, mods: Pointers.Modifiers) {
        // [key_up] must be called before [updateFlags]. The latter might disable flags.
        _config.handler?.key_up(k, mods)
        updateFlags()
        invalidate()
    }

    override fun onPointerHold(k: KeyValue, mods: Pointers.Modifiers) {
        _config.handler?.key_up(k, mods)
        updateFlags()
    }

    override fun onPointerFlagsChanged(shouldVibrate: Boolean) {
        updateFlags()
        invalidate()
        if (shouldVibrate)
            vibrate()
    }

    private fun updateFlags() {
        _mods = _pointers.getModifiers()
        _config.handler?.mods_changed(_mods)
    }

    override fun onSwipeMove(x: Float, y: Float, recognizer: ImprovedSwipeGestureRecognizer) {
        val key = getKeyAtPosition(x, y)
        recognizer.addPoint(x, y, key)
        // Always invalidate to show visual trail, even before swipe typing confirmed
        invalidate()
    }

    override fun onSwipeEnd(recognizer: ImprovedSwipeGestureRecognizer) {
        if (recognizer.isSwipeTyping()) {
            val result = recognizer.endSwipe()
            if (_keyboard2 != null && result.keys != null && result.keys.isNotEmpty() &&
                result.path != null && result.timestamps != null) {
                // v1.32.926: Check if shift is active for capitalize first letter
                val wasShiftActive = _mods.has(KeyValue.Modifier.SHIFT)
                // v1.33.8: Check if shift is LOCKED (caps lock) for ALL CAPS swipe typing
                val wasShiftLocked = isShiftLocked()

                // Pass full swipe data for ML collection
                _keyboard2!!.handleSwipeTyping(result.keys, result.path, result.timestamps, wasShiftActive, wasShiftLocked)
            }
        } else {
            recognizer.endSwipe() // Clean up even if not swipe typing
        }
        recognizer.reset()
        invalidate() // Clear the trail
    }

    override fun isPointWithinKey(x: Float, y: Float, key: KeyboardData.Key): Boolean {
        return isPointWithinKeyWithTolerance(x, y, key, 0.0f)
    }

    override fun isPointWithinKeyWithTolerance(x: Float, y: Float, key: KeyboardData.Key, tolerance: Float): Boolean {
        if (_keyboard == null) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("Keyboard2View", "isPointWithinKeyWithTolerance: key or keyboard is null")
            }
            return false
        }

        // Find the row containing this key
        var targetRow: KeyboardData.Row? = null
        for (row in _keyboard!!.rows) {
            for (k in row.keys) {
                if (k == key) {
                    targetRow = row
                    break
                }
            }
            if (targetRow != null) break
        }

        if (targetRow == null) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("Keyboard2View", "isPointWithinKeyWithTolerance: targetRow not found")
            }
            return false
        }

        // Calculate key bounds
        var keyX = _marginLeft
        for (k in targetRow.keys) {
            if (k == key) {
                val xLeft = keyX + key.shift * _keyWidth
                val xRight = xLeft + key.width * _keyWidth

                // Calculate row bounds - MUST use _tc.row_height to scale like rendering does
                val tc = _tc
                if (tc == null) {
                    if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                        android.util.Log.d("Keyboard2View", "isPointWithinKeyWithTolerance: _tc is null")
                    }
                    return false
                }

                var rowTop = _config.marginTop.toFloat()
                for (row in _keyboard!!.rows) {
                    if (row == targetRow) break
                    rowTop += (row.height + row.shift) * tc.row_height
                }
                val rowBottom = rowTop + targetRow.height * tc.row_height

                // FIXED: Use radial (circular) tolerance instead of rectangular
                val keyWidth = key.width * _keyWidth
                val keyHeight = targetRow.height * tc.row_height

                // Calculate key center
                val keyCenterX = (xLeft + xRight) / 2
                val keyCenterY = (rowTop + rowBottom) / 2

                // Calculate distance from touch point to key center
                val dx = x - keyCenterX
                val dy = y - keyCenterY
                val distanceFromCenter = kotlin.math.sqrt(dx * dx + dy * dy)

                // Calculate max allowed distance
                val maxHorizontal = keyWidth * (0.5f + tolerance)
                val maxVertical = keyHeight * (0.5f + tolerance)
                val maxDistance = kotlin.math.sqrt(maxHorizontal * maxHorizontal + maxVertical * maxVertical)

                return distanceFromCenter <= maxDistance
            }
            keyX += k.width * _keyWidth
        }

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("Keyboard2View", "isPointWithinKeyWithTolerance: key not found in targetRow")
        }
        return false
    }

    override fun getKeyHypotenuse(key: KeyboardData.Key): Float {
        val keyboard = _keyboard ?: return 0f

        // Find the row containing this key to get height
        var keyHeight = 0f
        for (row in keyboard.rows) {
            for (k in row.keys) {
                if (k == key) {
                    keyHeight = row.height
                    break
                }
            }
            if (keyHeight > 0) break
        }

        if (keyHeight == 0f) return 0f

        // Calculate hypotenuse: sqrt(width^2 + height^2)
        val keyWidth = key.width * _keyWidth
        return kotlin.math.sqrt(keyWidth * keyWidth + keyHeight * keyHeight)
    }

    override fun getKeyWidth(key: KeyboardData.Key): Float {
        return key.width * _keyWidth
    }

    fun setSwipeTypingComponents(predictor: WordPredictor?, keyboard2: CleverKeysService?) {
        _wordPredictor = predictor
        _keyboard2 = keyboard2
    }

    /**
     * Extract real key positions for accurate coordinate mapping
     * Returns map of character to actual center coordinates
     */
    fun getRealKeyPositions(): Map<Char, PointF> {
        val keyPositions = mutableMapOf<Char, PointF>()

        val keyboard = _keyboard
        val tc = _tc
        if (keyboard == null || tc == null) {
            android.util.Log.w("Keyboard2View", "Cannot extract key positions - layout not ready")
            return keyPositions
        }

        var y = _config.marginTop.toFloat()

        for (row in keyboard.rows) {
            var x = _marginLeft

            for (key in row.keys) {
                val xLeft = x + key.shift * _keyWidth
                val xRight = xLeft + key.width * _keyWidth
                val yTop = y + row.shift * tc.row_height
                val yBottom = yTop + row.height * tc.row_height

                // Calculate center coordinates
                val centerX = (xLeft + xRight) / 2f
                val centerY = (yTop + yBottom) / 2f

                // Extract character from key (if alphabetic)
                try {
                    val keyString = key.toString()
                    if (keyString.length == 1 && Character.isLetter(keyString[0])) {
                        val keyChar = keyString.toLowerCase()[0]
                        keyPositions[keyChar] = PointF(centerX, centerY)
                    }
                } catch (e: Exception) {
                    // Skip keys that can't be extracted
                }

                x = xRight
            }

            y += (row.shift + row.height) * tc.row_height
        }

        return keyPositions
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false

        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                _pointers.onTouchUp(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val p = event.actionIndex
                val tx = event.getX(p)
                val ty = event.getY(p)
                val key = getKeyAtPosition(tx, ty)
                if (key != null)
                    _pointers.onTouchDown(tx, ty, event.getPointerId(p), key)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount)
                    _pointers.onTouchMove(event.getX(p), event.getY(p), event.getPointerId(p))
            }
            MotionEvent.ACTION_CANCEL -> {
                _pointers.onTouchCancel()
            }
            else -> return false
        }
        return true
    }

    private fun getRowAtPosition(ty: Float): KeyboardData.Row? {
        val keyboard = _keyboard ?: return null
        val tc = _tc ?: return null

        var y = _config.marginTop.toFloat()

        if (ty < y) {
            return null
        }

        for (row in keyboard.rows) {
            val rowBottom = y + (row.shift + row.height) * tc.row_height

            if (ty < rowBottom) {
                return row
            }
            y = rowBottom
        }

        return null
    }

    private fun getKeyAtPosition(tx: Float, ty: Float): KeyboardData.Key? {
        val row = getRowAtPosition(ty)
        // CRITICAL FIX: Calculate margin dynamically to avoid stale _marginLeft from delayed onMeasure
        val currentMarginLeft = maxOf(_config.horizontal_margin.toFloat(), _insets_left.toFloat())
        var x = currentMarginLeft

        if (row == null) {
            android.util.Log.e("SWIPE_LAG_DEBUG", "❌ No row found for y=$ty (marginTop=${_config.marginTop})")
            return null
        }

        // Check if this row contains 'a' and 'l' keys (middle letter row in QWERTY)
        val hasAAndLKeys = rowContainsAAndL(row)
        var aKey: KeyboardData.Key? = null
        var lKey: KeyboardData.Key? = null

        if (hasAAndLKeys) {
            // Find the 'a' and 'l' keys in this row
            for (key in row.keys) {
                if (isCharacterKey(key, 'a')) aKey = key
                if (isCharacterKey(key, 'l')) lKey = key
            }
        }

        // Check if touch is before the first key and we have 'a' key - extend its touch zone
        if (tx < x && aKey != null) {
            return aKey
        }

        if (tx < x) {
            return null
        }

        for (key in row.keys) {
            val xLeft = x + key.shift * _keyWidth
            val xRight = xLeft + key.width * _keyWidth

            // GAP FIX: If touch is in the gap before this key (xLeft),
            // consider it part of this key for swiping purposes.
            if (tx < xRight) {
                return key
            }
            x = xRight
        }

        // GAP FIX: If we reached here, tx > last key's right edge.
        // Return the last key in the row to handle right-margin slop.
        if (row.keys.isNotEmpty()) {
            return row.keys[row.keys.size - 1]
        }

        return null
    }

    /**
     * Check if this row contains both 'a' and 'l' keys (the middle QWERTY row)
     */
    private fun rowContainsAAndL(row: KeyboardData.Row): Boolean {
        var hasA = false
        var hasL = false
        for (key in row.keys) {
            if (isCharacterKey(key, 'a')) hasA = true
            if (isCharacterKey(key, 'l')) hasL = true
            if (hasA && hasL) return true
        }
        return false
    }

    /**
     * Check if a key represents the specified character
     */
    private fun isCharacterKey(key: KeyboardData.Key, character: Char): Boolean {
        val kv = key.keys[0] ?: return false
        return kv.getKind() == KeyValue.Kind.Char && kv.getChar() == character
    }

    private fun vibrate() {
        VibratorCompat.vibrate(this, _config)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val keyboard = _keyboard
        if (keyboard == null) {
            // CRITICAL: Must call setMeasuredDimension even when keyboard is null
            // to prevent IllegalStateException crash during theme changes
            setMeasuredDimension(0, 0)
            return
        }

        var width = MeasureSpec.getSize(wSpec)

        // CRITICAL FIX: If measure returns 0, preserve existing valid keyWidth
        if (width == 0 && _keyWidth > 0) {
            // Reconstruct width from existing keyWidth
            width = (_keyWidth * keyboard.keysWidth + _marginLeft + _marginRight).toInt()
        }

        _marginLeft = maxOf(_config.horizontal_margin.toFloat(), _insets_left.toFloat())
        _marginRight = maxOf(_config.horizontal_margin.toFloat(), _insets_right.toFloat())
        _marginBottom = _config.margin_bottom + _insets_bottom.toFloat()

        // Only recalculate keyWidth if we have a valid new width
        if (width > 0) {
            _keyWidth = (width - _marginLeft - _marginRight) / keyboard.keysWidth
        }

        val cacheKey = "${keyboard.name ?: ""}_$_keyWidth"
        _tc = _themeCache.get(cacheKey) ?: run {
            val computed = Theme.Computed(_theme, _config, _keyWidth, keyboard)
            _themeCache.put(cacheKey, computed)
            computed
        }

        val tc = _tc!!

        // Compute the size of labels
        val labelBaseSize = minOf(
            tc.row_height - tc.vertical_margin,
            (width / 10 - tc.horizontal_margin) * 3 / 2
        ) * _config.characterSize
        _mainLabelSize = labelBaseSize * _config.labelTextSize
        _subLabelSize = labelBaseSize * _config.sublabelTextSize

        val height = (tc.row_height * keyboard.keysHeight + _config.marginTop + _marginBottom).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!changed)
            return
        if (VERSION.SDK_INT >= 29) {
            // Disable the back-gesture on the keyboard area
            val keyboard_area = Rect(
                left + _marginLeft.toInt(),
                top + _config.marginTop.toInt(),
                right - _marginRight.toInt(),
                bottom - _marginBottom.toInt()
            )
            systemGestureExclusionRects = listOf(keyboard_area)
        }
    }

    override fun onApplyWindowInsets(wi: WindowInsets?): WindowInsets? {
        if (wi == null || VERSION.SDK_INT < 35)
            return wi
        val insets_types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val insets = wi.getInsets(insets_types)
        _insets_left = insets.left
        _insets_right = insets.right
        _insets_bottom = insets.bottom
        return WindowInsets.CONSUMED
    }

    override fun onDraw(canvas: Canvas) {
        val keyboard = _keyboard ?: return
        val tc = _tc ?: return

        // Set keyboard background opacity
        background?.alpha = _config.keyboardOpacity
        var y = tc.margin_top

        for (row in keyboard.rows) {
            y += row.shift * tc.row_height
            var x = _marginLeft + tc.margin_left
            val keyH = row.height * tc.row_height - tc.vertical_margin

            for (k in row.keys) {
                x += k.shift * _keyWidth
                val keyW = _keyWidth * k.width - tc.horizontal_margin
                val isKeyDown = _pointers.isKeyDown(k)
                val tc_key = if (isKeyDown) tc.key_activated else tc.key

                drawKeyFrame(canvas, x, y, keyW, keyH, tc_key)
                if (k.keys[0] != null)
                    drawLabel(canvas, k.keys[0]!!, keyW / 2f + x, y, keyH, isKeyDown, tc_key)
                for (i in 1..8) {
                    if (k.keys[i] != null)
                        drawSubLabel(canvas, k.keys[i]!!, x, y, keyW, keyH, i, isKeyDown, tc_key)
                }
                drawIndication(canvas, k, x, y, keyW, keyH, tc)
                x += _keyWidth * k.width
            }
            y += row.height * tc.row_height
        }

        // Draw swipe trail if swipe typing is enabled and active
        if (_config.swipe_typing_enabled && _swipeRecognizer != null && _swipeRecognizer!!.isSwipeTyping()) {
            drawSwipeTrail(canvas)
        }
    }

    /**
     * Draw swipe trail without allocations.
     * Reuses _swipeTrailPath and directly accesses swipe path to avoid copying.
     * Supports glow, solid, fade, rainbow, and none effects.
     */
    private fun drawSwipeTrail(canvas: Canvas) {
        // Check if trail is enabled
        if (_config?.swipe_trail_enabled == false) return

        val recognizer = _swipeRecognizer ?: return
        val swipePath = recognizer.getSwipePath()
        if (swipePath.size < 2)
            return

        // Reuse the path object - reset it instead of allocating new one
        _swipeTrailPath.rewind()

        val firstPoint = swipePath[0]
        _swipeTrailPath.moveTo(firstPoint.x, firstPoint.y)

        for (i in 1 until swipePath.size) {
            val point = swipePath[i]
            _swipeTrailPath.lineTo(point.x, point.y)
        }

        val paint = _swipeTrailPaint ?: return

        // Handle rainbow effect with cycling colors
        if (_config?.swipe_trail_effect == "rainbow") {
            val hue = (System.currentTimeMillis() % 3600) / 10f // 0-360 cycling
            paint.color = android.graphics.Color.HSVToColor(200, floatArrayOf(hue, 0.8f, 1.0f))
        }

        canvas.drawPath(_swipeTrailPath, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    /** Draw borders and background of the key. */
    private fun drawKeyFrame(canvas: Canvas, x: Float, y: Float, keyW: Float, keyH: Float, tc: Theme.Computed.Key) {
        val r = tc.border_radius
        val w = tc.border_width
        val padding = w / 2f
        _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding)
        canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint)
        if (w > 0f) {
            canvas.drawRoundRect(_tmpRect, r, r, tc.border_paint)
        }
    }

    private fun labelColor(k: KeyValue, isKeyDown: Boolean, sublabel: Boolean): Int {
        if (isKeyDown) {
            val flags = _pointers.getKeyFlags(k)
            if (flags != -1) {
                if ((flags and Pointers.FLAG_P_LOCKED) != 0)
                    return _theme.lockedColor
                return _theme.activatedColor
            }
        }
        if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY or KeyValue.FLAG_GREYED)) {
            if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
                return _theme.greyedLabelColor
            return _theme.secondaryLabelColor
        }
        return if (sublabel) _theme.subLabelColor else _theme.labelColor
    }

    private fun drawLabel(canvas: Canvas, kv: KeyValue, x: Float, y: Float, keyH: Float, isKeyDown: Boolean, tc: Theme.Computed.Key) {
        val modifiedKv = modifyKey(kv, _mods) ?: return
        val textSize = scaleTextSize(modifiedKv, true)
        val p = tc.label_paint(modifiedKv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(modifiedKv, isKeyDown, false), textSize)
        canvas.drawText(modifiedKv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p)
    }

    private fun drawSubLabel(canvas: Canvas, kv: KeyValue, x: Float, y: Float, keyW: Float, keyH: Float, sub_index: Int, isKeyDown: Boolean, tc: Theme.Computed.Key) {
        val a = LABEL_POSITION_H[sub_index]
        val v = LABEL_POSITION_V[sub_index]
        val modifiedKv = modifyKey(kv, _mods) ?: return
        val textSize = scaleTextSize(modifiedKv, false)
        val p = tc.sublabel_paint(modifiedKv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(modifiedKv, isKeyDown, true), textSize, a)
        val subPadding = _config.keyPadding
        var yPos = y
        var xPos = x

        yPos += when (v) {
            Vertical.CENTER -> (keyH - p.ascent() - p.descent()) / 2f
            Vertical.TOP -> subPadding - p.ascent()
            Vertical.BOTTOM -> keyH - subPadding - p.descent()
        }

        xPos += when (a) {
            Paint.Align.CENTER -> keyW / 2f
            Paint.Align.LEFT -> subPadding
            Paint.Align.RIGHT -> keyW - subPadding
        }

        val label = modifiedKv.getString()
        var label_len = label.length
        // Limit the label of string keys to 3 characters
        if (label_len > 3 && modifiedKv.getKind() == KeyValue.Kind.String)
            label_len = 3
        canvas.drawText(label, 0, label_len, xPos, yPos, p)
    }

    private fun drawIndication(canvas: Canvas, k: KeyboardData.Key, x: Float, y: Float, keyW: Float, keyH: Float, tc: Theme.Computed) {
        if (k.indication.isNullOrEmpty())
            return
        val p = tc.indication_paint
        p.textSize = _subLabelSize
        canvas.drawText(k.indication, 0, k.indication.length,
            x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4 / 5 + y, p)
    }

    private fun scaleTextSize(k: KeyValue, main_label: Boolean): Float {
        val smaller_font = if (k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)) 0.75f else 1f
        val label_size = if (main_label) _mainLabelSize else _subLabelSize
        return label_size * smaller_font
    }

    fun getTheme(): Theme {
        return _theme
    }

    /**
     * Find the key at the given coordinates
     */
    private fun getKeyAt(x: Float, y: Float): KeyboardData.Key? {
        val keyboard = _keyboard ?: return null
        val tc = _tc ?: return null

        var yPos = tc.margin_top
        for (row in keyboard.rows) {
            yPos += row.shift * tc.row_height
            val keyH = row.height * tc.row_height - tc.vertical_margin

            // Check if y coordinate is within this row
            if (y >= yPos && y < yPos + keyH) {
                var xPos = _marginLeft + tc.margin_left
                for (key in row.keys) {
                    xPos += key.shift * _keyWidth
                    val keyW = _keyWidth * key.width - tc.horizontal_margin

                    // Check if x coordinate is within this key
                    if (x >= xPos && x < xPos + keyW) {
                        return key
                    }
                    xPos += _keyWidth * key.width
                }
                break // Y is in this row but X didn't match any key
            }
            yPos += row.height * tc.row_height
        }
        return null
    }

    /**
     * CGR Prediction Support Methods
     */

    /**
     * Store CGR predictions and immediately display them
     */
    private fun storeCGRPredictions(predictions: List<String>?, isFinal: Boolean) {
        _cgrPredictions.clear()
        if (predictions != null) {
            _cgrPredictions.addAll(predictions)
        }
        _cgrFinalPredictions = isFinal

        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("Keyboard2View", "Stored ${_cgrPredictions.size} CGR predictions (final: $isFinal): $_cgrPredictions")
        }

        // Immediately trigger display update
        post {
            try {
                // Find the parent CleverKeysService service and update predictions
                var context: Context = getContext()
                while (context is ContextWrapper && context !is CleverKeysService) {
                    context = context.baseContext
                }
                if (context is CleverKeysService) {
                    context.checkCGRPredictions()
                }
            } catch (e: Exception) {
                android.util.Log.e("Keyboard2View", "Failed to update CGR predictions: ${e.message}")
            }
        }
    }

    /**
     * Clear CGR predictions
     */
    private fun clearCGRPredictions() {
        _cgrPredictions.clear()
        _cgrFinalPredictions = false
        if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
            android.util.Log.d("Keyboard2View", "Cleared CGR predictions")
        }
    }

    /**
     * Get current CGR predictions (for access by keyboard service)
     */
    fun getCGRPredictions(): List<String> {
        return ArrayList(_cgrPredictions)
    }

    /**
     * Check if CGR predictions are final (persisting)
     */
    fun areCGRPredictionsFinal(): Boolean {
        return _cgrFinalPredictions
    }

    companion object {
        private var _currentWhat = 0
        private val _tmpRect = RectF()

        /** Horizontal and vertical position of the 9 indexes. */
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
}
