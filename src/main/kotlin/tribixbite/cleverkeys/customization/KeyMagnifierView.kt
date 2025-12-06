package tribixbite.cleverkeys.customization

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.Theme
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * A magnified view of a single keyboard key showing all 8 short swipe directions.
 *
 * This view renders a zoomed (200%+) version of a key that:
 * - Shows the exact same styling as the real keyboard (theme, colors, fonts)
 * - Displays all currently mapped sub-labels in their correct positions (1-8)
 * - Provides 8 tappable direction zones for customization
 * - Shows visual indicators for mapped vs unmapped directions
 * - Mirrors the EXACT appearance from the actual keyboard
 *
 * The 8 directions correspond to sub-label indices in KeyboardData.Key.keys[]:
 * - 1 = NW (top-left)
 * - 2 = NE (top-right)
 * - 3 = SW (bottom-left)
 * - 4 = SE (bottom-right)
 * - 5 = W (center-left)
 * - 6 = E (center-right)
 * - 7 = N (top-center)
 * - 8 = S (bottom-center)
 */
class KeyMagnifierView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyMagnifierView"

        /** Scale factor for magnification (200% = 2.0) */
        const val MAGNIFICATION_SCALE = 2.0f

        /** Direction index to position mapping - matches Keyboard2View layout */
        val DIRECTION_POSITIONS = mapOf(
            1 to SwipeDirection.NW,   // Top-left
            2 to SwipeDirection.NE,   // Top-right
            3 to SwipeDirection.SW,   // Bottom-left
            4 to SwipeDirection.SE,   // Bottom-right
            5 to SwipeDirection.W,    // Center-left
            6 to SwipeDirection.E,    // Center-right
            7 to SwipeDirection.N,    // Top-center
            8 to SwipeDirection.S     // Bottom-center
        )

        /** Reverse mapping from SwipeDirection to index */
        val DIRECTION_TO_INDEX = DIRECTION_POSITIONS.entries.associate { it.value to it.key }
    }

    // Key and mapping data
    private var key: KeyboardData.Key? = null
    private var customMappings: Map<SwipeDirection, ShortSwipeMapping> = emptyMap()

    // Key dimensions for proper aspect ratio
    private var keyWidthUnits: Float = 1.0f
    private var rowHeightUnits: Float = 1.0f

    // Theme and styling
    private var theme: Theme? = null
    private var config: Config? = null

    // Paints for rendering
    private val keyBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val mainLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val directionZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val directionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val mappedIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val unmappedIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    // Touch handling
    private var hoveredDirection: SwipeDirection? = null
    var onDirectionTapped: ((SwipeDirection) -> Unit)? = null

    // Cached bounds for hit testing
    private val directionBounds = mutableMapOf<SwipeDirection, RectF>()

    init {
        loadTheme()
        // Enable touch events - required for AndroidView in Compose
        isClickable = true
        isFocusable = true
    }

    /**
     * Load the current theme from ThemeProvider.
     */
    private fun loadTheme() {
        try {
            config = Config.globalConfig()
            val themeName = config?.themeName ?: "cleverkeysdark"
            theme = ThemeProvider.getInstance(context).getTheme(themeName)
            updateColors()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load theme", e)
            setDefaultColors()
        }
    }

    /**
     * Update paint colors based on theme.
     */
    private fun updateColors() {
        val t = theme
        if (t != null) {
            keyBackgroundPaint.color = t.colorKey
            keyBorderPaint.color = t.activatedColor
            mainLabelPaint.color = t.labelColor
            subLabelPaint.color = t.subLabelColor
            mappedIndicatorPaint.color = t.activatedColor
            unmappedIndicatorPaint.color = t.subLabelColor
            directionZonePaint.color = Color.argb(30, 255, 255, 255)
            directionBorderPaint.color = Color.argb(60, 255, 255, 255)
        } else {
            setDefaultColors()
        }
    }

    /**
     * Set default dark theme colors if theme loading fails.
     */
    private fun setDefaultColors() {
        keyBackgroundPaint.color = Color.parseColor("#2D2D2D")
        keyBorderPaint.color = Color.parseColor("#9B59B6")
        mainLabelPaint.color = Color.WHITE
        subLabelPaint.color = Color.parseColor("#BBBBBB")
        mappedIndicatorPaint.color = Color.parseColor("#9B59B6")
        unmappedIndicatorPaint.color = Color.parseColor("#666666")
        directionZonePaint.color = Color.argb(30, 255, 255, 255)
        directionBorderPaint.color = Color.argb(60, 255, 255, 255)
    }

    /**
     * Set the key to display.
     *
     * @param key The KeyboardData.Key to magnify
     * @param customMappings Custom short swipe mappings for this key
     * @param rowHeight The height of the row containing this key (in keyboard units).
     *                  If not provided, defaults to 1.0 which produces a wider key.
     *                  For typical keyboards where keys are taller than wide, this should
     *                  be greater than key.width (e.g., 1.2 for a 1.0 width key).
     */
    fun setKey(
        key: KeyboardData.Key,
        customMappings: Map<SwipeDirection, ShortSwipeMapping> = emptyMap(),
        rowHeight: Float = 1.0f
    ) {
        this.key = key
        this.customMappings = customMappings
        this.keyWidthUnits = key.width
        this.rowHeightUnits = rowHeight
        Log.d(TAG, "setKey: keyWidth=${key.width}, rowHeight=$rowHeight, aspectRatio=${key.width / rowHeight}")
        requestLayout() // Force re-measure for new aspect ratio
        invalidate()
    }

    /**
     * Update custom mappings for the current key.
     */
    fun updateMappings(mappings: Map<SwipeDirection, ShortSwipeMapping>) {
        this.customMappings = mappings
        invalidate()
    }

    /**
     * Clear the current key display.
     */
    fun clear() {
        this.key = null
        this.customMappings = emptyMap()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        // Get key aspect ratio from the stored dimensions
        val k = key
        if (k != null && rowHeightUnits > 0) {
            // Calculate aspect ratio: width / height
            // For keys that are taller than wide, aspectRatio < 1.0
            // For keys that are wider than tall, aspectRatio > 1.0
            val aspectRatio = keyWidthUnits / rowHeightUnits

            Log.d(TAG, "onMeasure: keyWidthUnits=$keyWidthUnits, rowHeightUnits=$rowHeightUnits, aspectRatio=$aspectRatio")
            Log.d(TAG, "onMeasure: available=${availableWidth}x${availableHeight}")

            // Fit within available space while maintaining aspect ratio
            val width: Int
            val height: Int
            if (availableWidth / aspectRatio <= availableHeight) {
                // Limited by width - key will fill width, height calculated
                width = availableWidth
                height = (availableWidth / aspectRatio).toInt()
            } else {
                // Limited by height - key will fill height, width calculated
                height = availableHeight
                width = (availableHeight * aspectRatio).toInt()
            }

            Log.d(TAG, "onMeasure: result=${width}x${height}")
            setMeasuredDimension(width, height)
        } else {
            // No key set - use square
            val size = minOf(availableWidth, availableHeight)
            setMeasuredDimension(size, size)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val k = key ?: run {
            drawEmptyState(canvas)
            return
        }

        // Calculate key bounds (centered with padding)
        val padding = width * 0.1f
        val keyRect = RectF(padding, padding, width - padding, height - padding)
        val cornerRadius = 12f * resources.displayMetrics.density

        // Draw key background
        canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyBackgroundPaint)

        // Draw key border
        canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, keyBorderPaint)

        // Draw direction zones and indicators
        calculateDirectionBounds(keyRect)
        drawDirectionZones(canvas, keyRect)

        // Draw main label (center)
        drawMainLabel(canvas, k, keyRect)

        // Draw sub-labels (corners and edges)
        drawSubLabels(canvas, k, keyRect)
    }

    /**
     * Calculate bounds for each direction zone.
     */
    private fun calculateDirectionBounds(keyRect: RectF) {
        directionBounds.clear()

        val keyW = keyRect.width()
        val keyH = keyRect.height()
        val thirdW = keyW / 3
        val thirdH = keyH / 3

        // Top row
        directionBounds[SwipeDirection.NW] = RectF(
            keyRect.left, keyRect.top,
            keyRect.left + thirdW, keyRect.top + thirdH
        )
        directionBounds[SwipeDirection.N] = RectF(
            keyRect.left + thirdW, keyRect.top,
            keyRect.right - thirdW, keyRect.top + thirdH
        )
        directionBounds[SwipeDirection.NE] = RectF(
            keyRect.right - thirdW, keyRect.top,
            keyRect.right, keyRect.top + thirdH
        )

        // Middle row
        directionBounds[SwipeDirection.W] = RectF(
            keyRect.left, keyRect.top + thirdH,
            keyRect.left + thirdW, keyRect.bottom - thirdH
        )
        // Center is for main label, not a direction
        directionBounds[SwipeDirection.E] = RectF(
            keyRect.right - thirdW, keyRect.top + thirdH,
            keyRect.right, keyRect.bottom - thirdH
        )

        // Bottom row
        directionBounds[SwipeDirection.SW] = RectF(
            keyRect.left, keyRect.bottom - thirdH,
            keyRect.left + thirdW, keyRect.bottom
        )
        directionBounds[SwipeDirection.S] = RectF(
            keyRect.left + thirdW, keyRect.bottom - thirdH,
            keyRect.right - thirdW, keyRect.bottom
        )
        directionBounds[SwipeDirection.SE] = RectF(
            keyRect.right - thirdW, keyRect.bottom - thirdH,
            keyRect.right, keyRect.bottom
        )
    }

    /**
     * Draw the 8 direction zones - only showing hover highlight.
     * No dot indicators since users can see mapped labels directly.
     */
    private fun drawDirectionZones(canvas: Canvas, keyRect: RectF) {
        for ((direction, bounds) in directionBounds) {
            val isHovered = direction == hoveredDirection

            // Draw zone background (subtle highlight if hovered)
            if (isHovered) {
                directionZonePaint.alpha = 80
                canvas.drawRect(bounds, directionZonePaint)
            }
        }
    }

    /**
     * Check if a direction has a mapping (either built-in or custom).
     */
    private fun isMapped(direction: SwipeDirection): Boolean {
        // Check custom mappings first
        if (customMappings.containsKey(direction)) return true

        // Check built-in sub-keys
        val index = DIRECTION_TO_INDEX[direction] ?: return false
        return key?.keys?.getOrNull(index) != null
    }

    /**
     * Draw the main label in the center of the key.
     */
    private fun drawMainLabel(canvas: Canvas, key: KeyboardData.Key, keyRect: RectF) {
        val mainKv = key.keys[0] ?: return

        mainLabelPaint.textSize = keyRect.height() * 0.35f
        mainLabelPaint.isFakeBoldText = true

        val label = mainKv.getString()
        val cx = keyRect.centerX()
        val cy = keyRect.centerY() - (mainLabelPaint.descent() + mainLabelPaint.ascent()) / 2

        canvas.drawText(label, cx, cy, mainLabelPaint)
    }

    /**
     * Draw sub-labels in their respective positions.
     */
    private fun drawSubLabels(canvas: Canvas, key: KeyboardData.Key, keyRect: RectF) {
        subLabelPaint.textSize = keyRect.height() * 0.14f

        for ((index, direction) in DIRECTION_POSITIONS) {
            // Check for custom mapping first
            val customMapping = customMappings[direction]
            if (customMapping != null) {
                drawSubLabelForDirection(canvas, keyRect, direction, customMapping.displayText, true)
                continue
            }

            // Check for built-in sub-key
            val subKv = key.keys.getOrNull(index)
            if (subKv != null) {
                val label = subKv.getString().take(4)
                drawSubLabelForDirection(canvas, keyRect, direction, label, false)
            }
        }
    }

    /**
     * Draw a sub-label at the appropriate position for a direction.
     */
    private fun drawSubLabelForDirection(
        canvas: Canvas,
        keyRect: RectF,
        direction: SwipeDirection,
        label: String,
        isCustom: Boolean
    ) {
        val bounds = directionBounds[direction] ?: return

        // Use different color for custom mappings
        subLabelPaint.color = if (isCustom) {
            theme?.activatedColor ?: Color.parseColor("#9B59B6")
        } else {
            theme?.subLabelColor ?: Color.parseColor("#BBBBBB")
        }

        // Set text alignment based on position
        when (direction) {
            SwipeDirection.NW, SwipeDirection.W, SwipeDirection.SW -> {
                subLabelPaint.textAlign = Paint.Align.LEFT
            }
            SwipeDirection.NE, SwipeDirection.E, SwipeDirection.SE -> {
                subLabelPaint.textAlign = Paint.Align.RIGHT
            }
            SwipeDirection.N, SwipeDirection.S -> {
                subLabelPaint.textAlign = Paint.Align.CENTER
            }
        }

        val padding = 8f * resources.displayMetrics.density
        val x: Float
        val y: Float

        when (direction) {
            SwipeDirection.NW -> { x = bounds.left + padding; y = bounds.top + padding - subLabelPaint.ascent() }
            SwipeDirection.N -> { x = bounds.centerX(); y = bounds.top + padding - subLabelPaint.ascent() }
            SwipeDirection.NE -> { x = bounds.right - padding; y = bounds.top + padding - subLabelPaint.ascent() }
            SwipeDirection.W -> { x = bounds.left + padding; y = bounds.centerY() - (subLabelPaint.descent() + subLabelPaint.ascent()) / 2 }
            SwipeDirection.E -> { x = bounds.right - padding; y = bounds.centerY() - (subLabelPaint.descent() + subLabelPaint.ascent()) / 2 }
            SwipeDirection.SW -> { x = bounds.left + padding; y = bounds.bottom - padding - subLabelPaint.descent() }
            SwipeDirection.S -> { x = bounds.centerX(); y = bounds.bottom - padding - subLabelPaint.descent() }
            SwipeDirection.SE -> { x = bounds.right - padding; y = bounds.bottom - padding - subLabelPaint.descent() }
        }

        canvas.drawText(label.take(4), x, y, subLabelPaint)
    }

    /**
     * Draw empty state when no key is set.
     */
    private fun drawEmptyState(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 16f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Tap a key to customize",
            width / 2f,
            height / 2f,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        Log.d(TAG, "onTouchEvent: action=${event.action}, x=$x, y=$y, bounds=${directionBounds.size}")

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                hoveredDirection = findDirectionAt(x, y)
                Log.d(TAG, "ACTION_DOWN: hoveredDirection=$hoveredDirection")
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val newHover = findDirectionAt(x, y)
                if (newHover != hoveredDirection) {
                    hoveredDirection = newHover
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val direction = findDirectionAt(x, y)
                Log.d(TAG, "ACTION_UP: direction=$direction, callback=${onDirectionTapped != null}")
                if (direction != null && direction == hoveredDirection) {
                    Log.d(TAG, "Invoking callback for $direction")
                    onDirectionTapped?.invoke(direction)
                }
                hoveredDirection = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hoveredDirection = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Find which direction zone contains the given point.
     */
    private fun findDirectionAt(x: Float, y: Float): SwipeDirection? {
        for ((direction, bounds) in directionBounds) {
            if (bounds.contains(x, y)) {
                return direction
            }
        }
        return null
    }

    /**
     * Get the current sub-key label for a direction (built-in or custom).
     */
    fun getLabelForDirection(direction: SwipeDirection): String? {
        // Custom mapping takes precedence
        customMappings[direction]?.let { return it.displayText }

        // Check built-in sub-key
        val index = DIRECTION_TO_INDEX[direction] ?: return null
        return key?.keys?.getOrNull(index)?.getString()?.take(4)
    }

    /**
     * Get all currently mapped directions (both built-in and custom).
     */
    fun getMappedDirections(): Set<SwipeDirection> {
        val mapped = mutableSetOf<SwipeDirection>()

        // Add custom mappings
        mapped.addAll(customMappings.keys)

        // Add built-in sub-keys
        DIRECTION_POSITIONS.forEach { (index, direction) ->
            if (key?.keys?.getOrNull(index) != null) {
                mapped.add(direction)
            }
        }

        return mapped
    }
}
