package tribixbite.keyboard2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlin.math.sqrt

/**
 * Mouse Keys Emulation for severely disabled users
 * Allows keyboard-based cursor control and clicking
 * Fix for Bug #375 (CATASTROPHIC): NO mouse keys - violates ADA/WCAG 2.1 AAA
 *
 * Provides:
 * - Keyboard-based cursor movement (arrow keys, numpad, WASD)
 * - Click emulation (left, right, double-click)
 * - Drag and drop support
 * - Adjustable speed and acceleration
 * - Visual cursor overlay with high-contrast design
 * - Accessibility event notifications
 * - Multiple movement modes (normal, precision, quick)
 */
class MouseKeysEmulation(
    private val context: Context,
    private val targetView: View
) {
    companion object {
        private const val TAG = "MouseKeysEmulation"

        // Movement parameters
        private const val BASE_VELOCITY = 10f  // pixels per update
        private const val ACCELERATION_FACTOR = 1.5f
        private const val PRECISION_MULTIPLIER = 0.3f
        private const val QUICK_MULTIPLIER = 3.0f
        private const val DIAGONAL_FACTOR = 0.707f  // sqrt(2)/2

        // Update interval
        private const val UPDATE_INTERVAL_MS = 16L  // ~60 FPS

        // Cursor visual parameters
        private const val CURSOR_SIZE = 48f
        private const val CURSOR_STROKE_WIDTH = 4f
        private const val CURSOR_COLOR = Color.RED
        private const val CURSOR_CLICK_COLOR = Color.GREEN
        private const val CURSOR_DRAG_COLOR = Color.BLUE

        // Click timing
        private const val DOUBLE_CLICK_DELAY_MS = 300L
        private const val LONG_PRESS_DELAY_MS = 500L

        // Acceleration threshold
        private const val ACCELERATION_THRESHOLD_MS = 500L
    }

    /**
     * Cursor movement direction
     */
    enum class Direction {
        UP, DOWN, LEFT, RIGHT,
        UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT,
        NONE
    }

    /**
     * Click mode state
     */
    enum class ClickMode {
        NORMAL,        // Single click on action
        DRAG,          // Click and hold (drag mode)
        RIGHT_DRAG,    // Right-click and hold
        SCROLL         // Scroll wheel mode
    }

    /**
     * Movement speed mode
     */
    enum class SpeedMode {
        NORMAL,     // Base velocity
        PRECISION,  // Slow for precise positioning
        QUICK       // Fast for quick navigation
    }

    // Configuration
    private var enabled = false
    private var useNumpad = true
    private var useArrowKeys = true
    private var useWASD = true

    // State
    private val cursorPosition = PointF(0f, 0f)
    private var currentDirection = Direction.NONE
    private var speedMode = SpeedMode.NORMAL
    private var clickMode = ClickMode.NORMAL
    private var currentVelocity = BASE_VELOCITY
    private var keyHeldStartTime = 0L
    private var isClicking = false
    private var isDragging = false

    // Click tracking
    private var lastClickTime = 0L
    private var clickCount = 0

    // Active keys
    private val activeKeys = mutableSetOf<Int>()

    // UI components
    private val handler = Handler(Looper.getMainLooper())
    private val cursorPaint = Paint().apply {
        color = CURSOR_COLOR
        style = Paint.Style.STROKE
        strokeWidth = CURSOR_STROKE_WIDTH
        isAntiAlias = true
    }

    // Movement update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (enabled && currentDirection != Direction.NONE) {
                updateCursorPosition()
                targetView.invalidate()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    // Accessibility integration
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    /**
     * Enable mouse keys emulation
     */
    fun enable() {
        Log.d(TAG, "Enabling mouse keys emulation")

        enabled = true

        // Initialize cursor to center of view
        cursorPosition.x = targetView.width / 2f
        cursorPosition.y = targetView.height / 2f

        targetView.invalidate()
        announceAccessibility("Mouse keys enabled. Use arrow keys to move cursor.")
    }

    /**
     * Disable mouse keys emulation
     */
    fun disable() {
        Log.d(TAG, "Disabling mouse keys emulation")

        enabled = false
        stopMovement()
        activeKeys.clear()

        announceAccessibility("Mouse keys disabled")
    }

    /**
     * Toggle mouse keys on/off
     */
    fun toggle() {
        if (enabled) {
            disable()
        } else {
            enable()
        }
    }

    /**
     * Handle key down event
     */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!enabled) return false

        // Prevent key repeat spam
        if (activeKeys.contains(keyCode)) return true

        activeKeys.add(keyCode)
        keyHeldStartTime = SystemClock.elapsedRealtime()

        return when {
            isMovementKey(keyCode) -> {
                updateDirection()
                startMovement()
                true
            }
            isClickKey(keyCode) -> {
                handleClick(keyCode)
                true
            }
            isModifierKey(keyCode) -> {
                handleModifier(keyCode, true)
                true
            }
            else -> false
        }
    }

    /**
     * Handle key up event
     */
    fun onKeyUp(keyCode: Int): Boolean {
        if (!enabled) return false

        activeKeys.remove(keyCode)

        return when {
            isMovementKey(keyCode) -> {
                updateDirection()
                if (currentDirection == Direction.NONE) {
                    stopMovement()
                }
                true
            }
            isModifierKey(keyCode) -> {
                handleModifier(keyCode, false)
                true
            }
            else -> false
        }
    }

    /**
     * Check if key is a movement key
     */
    private fun isMovementKey(keyCode: Int): Boolean {
        return when (keyCode) {
            // Arrow keys
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> useArrowKeys

            // Numpad
            KeyEvent.KEYCODE_NUMPAD_8, // Up
            KeyEvent.KEYCODE_NUMPAD_2, // Down
            KeyEvent.KEYCODE_NUMPAD_4, // Left
            KeyEvent.KEYCODE_NUMPAD_6, // Right
            KeyEvent.KEYCODE_NUMPAD_7, // Up-Left
            KeyEvent.KEYCODE_NUMPAD_9, // Up-Right
            KeyEvent.KEYCODE_NUMPAD_1, // Down-Left
            KeyEvent.KEYCODE_NUMPAD_3 -> useNumpad // Down-Right

            // WASD
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_D -> useWASD

            else -> false
        }
    }

    /**
     * Check if key is a click key
     */
    private fun isClickKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE,        // Left click
            KeyEvent.KEYCODE_NUMPAD_5,     // Left click
            KeyEvent.KEYCODE_ENTER,        // Right click
            KeyEvent.KEYCODE_NUMPAD_ENTER, // Right click
            KeyEvent.KEYCODE_NUMPAD_0,     // Hold/drag
            KeyEvent.KEYCODE_NUMPAD_DOT    // Release drag
                -> true
            else -> false
        }
    }

    /**
     * Check if key is a modifier key
     */
    private fun isModifierKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT -> true
            else -> false
        }
    }

    /**
     * Update current movement direction based on active keys
     */
    private fun updateDirection() {
        var up = false
        var down = false
        var left = false
        var right = false

        for (keyCode in activeKeys) {
            when (keyCode) {
                // Arrow keys
                KeyEvent.KEYCODE_DPAD_UP -> up = true
                KeyEvent.KEYCODE_DPAD_DOWN -> down = true
                KeyEvent.KEYCODE_DPAD_LEFT -> left = true
                KeyEvent.KEYCODE_DPAD_RIGHT -> right = true

                // Numpad
                KeyEvent.KEYCODE_NUMPAD_8 -> up = true
                KeyEvent.KEYCODE_NUMPAD_2 -> down = true
                KeyEvent.KEYCODE_NUMPAD_4 -> left = true
                KeyEvent.KEYCODE_NUMPAD_6 -> right = true
                KeyEvent.KEYCODE_NUMPAD_7 -> { up = true; left = true }
                KeyEvent.KEYCODE_NUMPAD_9 -> { up = true; right = true }
                KeyEvent.KEYCODE_NUMPAD_1 -> { down = true; left = true }
                KeyEvent.KEYCODE_NUMPAD_3 -> { down = true; right = true }

                // WASD
                KeyEvent.KEYCODE_W -> up = true
                KeyEvent.KEYCODE_S -> down = true
                KeyEvent.KEYCODE_A -> left = true
                KeyEvent.KEYCODE_D -> right = true
            }
        }

        currentDirection = when {
            up && left -> Direction.UP_LEFT
            up && right -> Direction.UP_RIGHT
            down && left -> Direction.DOWN_LEFT
            down && right -> Direction.DOWN_RIGHT
            up -> Direction.UP
            down -> Direction.DOWN
            left -> Direction.LEFT
            right -> Direction.RIGHT
            else -> Direction.NONE
        }
    }

    /**
     * Handle modifier key press/release
     */
    private fun handleModifier(keyCode: Int, pressed: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                speedMode = if (pressed) SpeedMode.PRECISION else SpeedMode.NORMAL
                Log.d(TAG, "Speed mode: $speedMode")
            }
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT -> {
                speedMode = if (pressed) SpeedMode.QUICK else SpeedMode.NORMAL
                Log.d(TAG, "Speed mode: $speedMode")
            }
        }
    }

    /**
     * Handle click action
     */
    private fun handleClick(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_NUMPAD_5 -> {
                performLeftClick()
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                performRightClick()
            }
            KeyEvent.KEYCODE_NUMPAD_0 -> {
                startDrag()
            }
            KeyEvent.KEYCODE_NUMPAD_DOT -> {
                stopDrag()
            }
        }
    }

    /**
     * Perform left click
     */
    private fun performLeftClick() {
        val currentTime = SystemClock.elapsedRealtime()

        // Check for double-click
        if (currentTime - lastClickTime < DOUBLE_CLICK_DELAY_MS) {
            clickCount++
            if (clickCount == 2) {
                performDoubleClick()
                clickCount = 0
                return
            }
        } else {
            clickCount = 1
        }

        lastClickTime = currentTime

        // Send click event
        sendClickEvent(MotionEvent.BUTTON_PRIMARY)
        announceAccessibility("Left click at ${cursorPosition.x.toInt()}, ${cursorPosition.y.toInt()}")

        Log.d(TAG, "Left click at (${cursorPosition.x}, ${cursorPosition.y})")
    }

    /**
     * Perform right click
     */
    private fun performRightClick() {
        sendClickEvent(MotionEvent.BUTTON_SECONDARY)
        announceAccessibility("Right click")

        Log.d(TAG, "Right click at (${cursorPosition.x}, ${cursorPosition.y})")
    }

    /**
     * Perform double-click
     */
    private fun performDoubleClick() {
        sendClickEvent(MotionEvent.BUTTON_PRIMARY)
        handler.postDelayed({
            sendClickEvent(MotionEvent.BUTTON_PRIMARY)
        }, 50)

        announceAccessibility("Double click")
        Log.d(TAG, "Double click at (${cursorPosition.x}, ${cursorPosition.y})")
    }

    /**
     * Start drag operation
     */
    private fun startDrag() {
        isDragging = true
        cursorPaint.color = CURSOR_DRAG_COLOR
        targetView.invalidate()

        announceAccessibility("Drag started")
        Log.d(TAG, "Drag started")
    }

    /**
     * Stop drag operation
     */
    private fun stopDrag() {
        isDragging = false
        cursorPaint.color = CURSOR_COLOR
        targetView.invalidate()

        announceAccessibility("Drag stopped")
        Log.d(TAG, "Drag stopped")
    }

    /**
     * Send click/tap event
     */
    private fun sendClickEvent(button: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        // Send ACTION_DOWN
        val downEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_DOWN,
            cursorPosition.x,
            cursorPosition.y,
            0
        )
        targetView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        // Send ACTION_UP
        val upEvent = MotionEvent.obtain(
            downTime,
            eventTime + 50,
            MotionEvent.ACTION_UP,
            cursorPosition.x,
            cursorPosition.y,
            0
        )
        targetView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    /**
     * Start cursor movement
     */
    private fun startMovement() {
        handler.removeCallbacks(updateRunnable)
        currentVelocity = BASE_VELOCITY
        handler.post(updateRunnable)
    }

    /**
     * Stop cursor movement
     */
    private fun stopMovement() {
        handler.removeCallbacks(updateRunnable)
        currentVelocity = BASE_VELOCITY
    }

    /**
     * Update cursor position based on current direction and velocity
     */
    private fun updateCursorPosition() {
        // Apply acceleration for long holds
        val holdDuration = SystemClock.elapsedRealtime() - keyHeldStartTime
        if (holdDuration > ACCELERATION_THRESHOLD_MS) {
            currentVelocity = BASE_VELOCITY * ACCELERATION_FACTOR
        }

        // Apply speed mode multiplier
        val effectiveVelocity = currentVelocity * when (speedMode) {
            SpeedMode.PRECISION -> PRECISION_MULTIPLIER
            SpeedMode.QUICK -> QUICK_MULTIPLIER
            SpeedMode.NORMAL -> 1.0f
        }

        // Calculate movement delta
        val (dx, dy) = when (currentDirection) {
            Direction.UP -> Pair(0f, -effectiveVelocity)
            Direction.DOWN -> Pair(0f, effectiveVelocity)
            Direction.LEFT -> Pair(-effectiveVelocity, 0f)
            Direction.RIGHT -> Pair(effectiveVelocity, 0f)
            Direction.UP_LEFT -> Pair(
                -effectiveVelocity * DIAGONAL_FACTOR,
                -effectiveVelocity * DIAGONAL_FACTOR
            )
            Direction.UP_RIGHT -> Pair(
                effectiveVelocity * DIAGONAL_FACTOR,
                -effectiveVelocity * DIAGONAL_FACTOR
            )
            Direction.DOWN_LEFT -> Pair(
                -effectiveVelocity * DIAGONAL_FACTOR,
                effectiveVelocity * DIAGONAL_FACTOR
            )
            Direction.DOWN_RIGHT -> Pair(
                effectiveVelocity * DIAGONAL_FACTOR,
                effectiveVelocity * DIAGONAL_FACTOR
            )
            Direction.NONE -> Pair(0f, 0f)
        }

        // Update cursor position with clamping
        cursorPosition.x = (cursorPosition.x + dx).coerceIn(0f, targetView.width.toFloat())
        cursorPosition.y = (cursorPosition.y + dy).coerceIn(0f, targetView.height.toFloat())

        // Send move event if dragging
        if (isDragging) {
            sendMoveEvent()
        }
    }

    /**
     * Send move event (for drag operations)
     */
    private fun sendMoveEvent() {
        val eventTime = SystemClock.uptimeMillis()
        val moveEvent = MotionEvent.obtain(
            eventTime,
            eventTime,
            MotionEvent.ACTION_MOVE,
            cursorPosition.x,
            cursorPosition.y,
            0
        )
        targetView.dispatchTouchEvent(moveEvent)
        moveEvent.recycle()
    }

    /**
     * Draw cursor overlay on canvas
     */
    fun drawCursor(canvas: Canvas) {
        if (!enabled) return

        // Update paint color based on state
        cursorPaint.color = when {
            isDragging -> CURSOR_DRAG_COLOR
            isClicking -> CURSOR_CLICK_COLOR
            else -> CURSOR_COLOR
        }

        // Draw crosshair cursor
        val size = CURSOR_SIZE / 2

        // Horizontal line
        canvas.drawLine(
            cursorPosition.x - size, cursorPosition.y,
            cursorPosition.x + size, cursorPosition.y,
            cursorPaint
        )

        // Vertical line
        canvas.drawLine(
            cursorPosition.x, cursorPosition.y - size,
            cursorPosition.x, cursorPosition.y + size,
            cursorPaint
        )

        // Center circle
        canvas.drawCircle(
            cursorPosition.x,
            cursorPosition.y,
            8f,
            cursorPaint
        )
    }

    /**
     * Get current cursor position
     */
    fun getCursorPosition(): PointF = PointF(cursorPosition.x, cursorPosition.y)

    /**
     * Set cursor position
     */
    fun setCursorPosition(x: Float, y: Float) {
        cursorPosition.x = x.coerceIn(0f, targetView.width.toFloat())
        cursorPosition.y = y.coerceIn(0f, targetView.height.toFloat())
        targetView.invalidate()
    }

    /**
     * Configure key bindings
     */
    fun configureKeyBindings(numpad: Boolean = true, arrowKeys: Boolean = true, wasd: Boolean = true) {
        useNumpad = numpad
        useArrowKeys = arrowKeys
        useWASD = wasd

        Log.d(TAG, "Key bindings: numpad=$numpad, arrows=$arrowKeys, wasd=$wasd")
    }

    /**
     * Check if mouse keys is enabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Announce text for accessibility
     */
    private fun announceAccessibility(announcement: String) {
        accessibilityManager?.sendAccessibilityEvent(
            AccessibilityEvent.obtain().apply {
                eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
                this.text.add(announcement)
                className = MouseKeysEmulation::class.java.name
                packageName = context.packageName
            }
        )
        Log.d(TAG, "Announced: $announcement")
    }
}
