package tribixbite.cleverkeys.customization

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * A keyboard preview view that renders the actual keyboard layout.
 *
 * This view uses [KeyboardData] to render keys exactly as they appear
 * on the actual keyboard, but without IME functionality. It:
 * - Loads the current layout from resources
 * - Renders keys with proper sizing and positioning
 * - Shows short swipe mapping indicators on key corners
 * - Reports key taps to a listener for customization
 *
 * @since CleverKeys v2.0 (Short Swipe Customization feature)
 */
class KeyboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Callback when a key is tapped
    var onKeyTap: ((keyName: String, keyBounds: RectF) -> Unit)? = null

    // Reference to manager for showing mapped corners
    var customizationManager: ShortSwipeCustomizationManager? = null

    // Keyboard data loaded from layout resource
    private var _keyboard: KeyboardData? = null

    // Key positions calculated during layout
    private val _keyRects = mutableMapOf<String, RectF>()

    // Paints for drawing
    private val _keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val _keyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val _labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val _cornerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Theme colors
    private var _keyBackgroundColor = 0xFF2D2D2D.toInt()
    private var _keyBorderColor = 0xFF444444.toInt()
    private var _keyLabelColor = 0xFFFFFFFF.toInt()
    private var _mappedCornerColor = 0xFF9B59B6.toInt() // Purple for mapped corners
    private var _unmappedCornerColor = 0x40FFFFFF.toInt() // Semi-transparent white for unmapped

    // Layout metrics
    private var _keyWidth = 0f
    private var _keyHeight = 0f
    private var _keySpacing = 4f
    private var _cornerRadius = 8f
    private var _cornerIndicatorSize = 6f

    // Standard QWERTY layout fallback
    private val QWERTY_ROWS = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    init {
        // Load theme colors
        loadThemeColors()
        // Try to load actual keyboard layout
        loadKeyboardLayout()
    }

    private fun loadThemeColors() {
        try {
            val config = Config.globalConfig()
            val themeName = config?.themeName ?: "jewel"
            val themeProvider = ThemeProvider.getInstance(context)
            val theme = themeProvider.getTheme(themeName)

            // Use correct Theme property names
            _keyBackgroundColor = theme.colorKey and 0xFFFFFFFF.toInt()
            _keyLabelColor = theme.labelColor and 0xFFFFFFFF.toInt()
            _mappedCornerColor = theme.subLabelColor and 0xFFFFFFFF.toInt()
        } catch (e: Exception) {
            // Use defaults if theme loading fails
        }
    }

    private fun loadKeyboardLayout() {
        try {
            // Try to load the main QWERTY layout
            _keyboard = KeyboardData.load(resources, R.xml.latn_qwerty_us)
        } catch (e: Exception) {
            // Will fall back to QWERTY_ROWS
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyPositions(w, h)
    }

    private fun calculateKeyPositions(viewWidth: Int, viewHeight: Int) {
        _keyRects.clear()

        val padding = 8f * resources.displayMetrics.density
        val availableWidth = viewWidth - (2 * padding)
        val availableHeight = viewHeight - (2 * padding)

        Log.d(TAG, "calculateKeyPositions: viewWidth=$viewWidth, viewHeight=$viewHeight")

        // Use actual keyboard data if available, otherwise fall back to QWERTY
        val keyboard = _keyboard
        if (keyboard != null && keyboard.rows.isNotEmpty()) {
            Log.d(TAG, "Using actual keyboard data with ${keyboard.rows.size} rows")
            calculateFromKeyboardData(keyboard, padding, availableWidth, availableHeight)
        } else {
            Log.d(TAG, "Using QWERTY fallback")
            calculateFromQwertyFallback(padding, availableWidth, availableHeight)
        }
    }

    private fun calculateFromKeyboardData(
        keyboard: KeyboardData,
        padding: Float,
        availableWidth: Float,
        availableHeight: Float
    ) {
        val rowCount = keyboard.rows.size
        _keyHeight = (availableHeight - (_keySpacing * (rowCount - 1))) / rowCount

        keyboard.rows.forEachIndexed { rowIndex, row ->
            val keyCount = row.keys.size
            if (keyCount == 0) return@forEachIndexed

            _keyWidth = (availableWidth - (_keySpacing * (keyCount - 1))) / keyCount
            val rowY = padding + rowIndex * (_keyHeight + _keySpacing)

            row.keys.forEachIndexed { keyIndex, key ->
                val keyName = getKeyName(key)
                if (keyName.isNotEmpty()) {
                    val keyX = padding + keyIndex * (_keyWidth + _keySpacing)
                    _keyRects[keyName] = RectF(keyX, rowY, keyX + _keyWidth, rowY + _keyHeight)
                }
            }
        }

        Log.d(TAG, "KeyboardData loaded ${_keyRects.size} keys: ${_keyRects.keys.take(15)}...")
    }

    private fun calculateFromQwertyFallback(
        padding: Float,
        availableWidth: Float,
        availableHeight: Float
    ) {
        val density = resources.displayMetrics.density
        _keySpacing = 4f * density
        _cornerRadius = 8f * density
        _cornerIndicatorSize = 6f * density

        val rowCount = QWERTY_ROWS.size
        _keyHeight = (availableHeight - (_keySpacing * (rowCount - 1))) / rowCount

        // Get max keys in any row for width calculation
        val maxKeysInRow = QWERTY_ROWS.maxOf { it.size }
        _keyWidth = (availableWidth - (_keySpacing * (maxKeysInRow - 1))) / maxKeysInRow

        Log.d(TAG, "calculateFromQwertyFallback: padding=$padding, width=$availableWidth, height=$availableHeight")
        Log.d(TAG, "keyWidth=$_keyWidth, keyHeight=$_keyHeight, spacing=$_keySpacing")

        QWERTY_ROWS.forEachIndexed { rowIndex, rowKeys ->
            val keyCount = rowKeys.size
            val rowY = padding + rowIndex * (_keyHeight + _keySpacing)

            // Center shorter rows
            val rowWidth = keyCount * _keyWidth + (keyCount - 1) * _keySpacing
            val rowXOffset = (availableWidth - rowWidth) / 2 + padding

            rowKeys.forEachIndexed { keyIndex, keyName ->
                val keyX = rowXOffset + keyIndex * (_keyWidth + _keySpacing)
                _keyRects[keyName] = RectF(keyX, rowY, keyX + _keyWidth, rowY + _keyHeight)
            }
        }

        Log.d(TAG, "QWERTY fallback loaded ${_keyRects.size} keys: ${_keyRects.keys}")
    }

    private fun getKeyName(key: KeyboardData.Key): String {
        // Extract the main key value (index 0 is center) from the key data
        val kv0 = key.getKeyValue(0)
        return kv0?.getString()?.lowercase() ?: ""
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val manager = customizationManager
        val mappings = manager?.getMappingsForKey("") ?: emptyMap() // Placeholder

        // Draw each key
        _keyRects.forEach { (keyName, rect) ->
            drawKey(canvas, keyName, rect, manager)
        }
    }

    private fun drawKey(canvas: Canvas, keyName: String, rect: RectF, manager: ShortSwipeCustomizationManager?) {
        // Draw key background
        _keyPaint.color = _keyBackgroundColor
        canvas.drawRoundRect(rect, _cornerRadius, _cornerRadius, _keyPaint)

        // Draw key border
        _keyStrokePaint.color = _keyBorderColor
        canvas.drawRoundRect(rect, _cornerRadius, _cornerRadius, _keyStrokePaint)

        // Draw key label
        _labelPaint.color = _keyLabelColor
        _labelPaint.textSize = _keyHeight * 0.5f
        val centerX = rect.centerX()
        val centerY = rect.centerY() - (_labelPaint.descent() + _labelPaint.ascent()) / 2
        canvas.drawText(keyName.uppercase(), centerX, centerY, _labelPaint)

        // Draw corner indicators for mapped directions
        if (manager != null) {
            drawCornerIndicators(canvas, keyName, rect, manager)
        }
    }

    private fun drawCornerIndicators(
        canvas: Canvas,
        keyName: String,
        rect: RectF,
        manager: ShortSwipeCustomizationManager
    ) {
        // Use SwipeDirection enum values: N, NE, E, SE, S, SW, W, NW
        val directions = listOf(
            SwipeDirection.N to PointPosition(rect.centerX(), rect.top + _cornerIndicatorSize),
            SwipeDirection.NE to PointPosition(rect.right - _cornerIndicatorSize, rect.top + _cornerIndicatorSize),
            SwipeDirection.E to PointPosition(rect.right - _cornerIndicatorSize, rect.centerY()),
            SwipeDirection.SE to PointPosition(rect.right - _cornerIndicatorSize, rect.bottom - _cornerIndicatorSize),
            SwipeDirection.S to PointPosition(rect.centerX(), rect.bottom - _cornerIndicatorSize),
            SwipeDirection.SW to PointPosition(rect.left + _cornerIndicatorSize, rect.bottom - _cornerIndicatorSize),
            SwipeDirection.W to PointPosition(rect.left + _cornerIndicatorSize, rect.centerY()),
            SwipeDirection.NW to PointPosition(rect.left + _cornerIndicatorSize, rect.top + _cornerIndicatorSize)
        )

        for ((direction, pos) in directions) {
            val mapping = manager.getMapping(keyName, direction)
            if (mapping != null) {
                _cornerIndicatorPaint.color = _mappedCornerColor
                canvas.drawCircle(pos.x, pos.y, _cornerIndicatorSize * 0.6f, _cornerIndicatorPaint)
            }
            // Don't draw unmapped indicators - keeps the view clean
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Must return true on ACTION_DOWN to receive subsequent events
                Log.d(TAG, "ACTION_DOWN at ($x, $y), keyRects count: ${_keyRects.size}")
                return true
            }
            MotionEvent.ACTION_UP -> {
                Log.d(TAG, "ACTION_UP at ($x, $y)")

                // Find which key was tapped
                for ((keyName, rect) in _keyRects) {
                    if (rect.contains(x, y)) {
                        Log.d(TAG, "Key tapped: $keyName at rect $rect")
                        onKeyTap?.invoke(keyName, rect)
                        return true
                    }
                }
                Log.d(TAG, "No key found at tap location")
            }
        }
        return true
    }

    companion object {
        private const val TAG = "KeyboardPreviewView"
    }

    /**
     * Force redraw when mappings change
     */
    fun refreshMappings() {
        invalidate()
    }

    /**
     * Get bounds for a specific key (for dialog positioning)
     */
    fun getKeyBounds(keyName: String): RectF? = _keyRects[keyName]

    /**
     * Get all key names in the preview
     */
    fun getAllKeyNames(): List<String> = _keyRects.keys.toList()

    private data class PointPosition(val x: Float, val y: Float)
}
