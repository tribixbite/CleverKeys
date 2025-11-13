package tribixbite.keyboard2

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent

/**
 * Manages automatic key repetition when keys are held down.
 *
 * Provides continuous key input when a key is pressed and held, similar to
 * standard keyboard behavior. Includes configurable initial delay and repeat rate.
 *
 * Features:
 * - Configurable initial delay before repeat starts
 * - Configurable repeat rate (interval between repeats)
 * - Key-specific repeat behavior (some keys don't repeat)
 * - Automatic cancellation on touch release
 * - Thread-safe operation
 * - Memory-efficient single-key tracking
 *
 * Bug #330 - HIGH: Complete implementation of missing KeyRepeatHandler.java
 *
 * @param enabled Initial enabled state (default: true)
 * @param initialDelay Delay before repeat starts in milliseconds (default: 400ms)
 * @param repeatInterval Interval between repeats in milliseconds (default: 50ms)
 */
class KeyRepeatHandler(
    private var enabled: Boolean = true,
    private var initialDelay: Long = 400L,
    private var repeatInterval: Long = 50L
) {
    companion object {
        private const val TAG = "KeyRepeatHandler"

        // Default timing values (milliseconds)
        private const val DEFAULT_INITIAL_DELAY = 400L  // Delay before first repeat
        private const val DEFAULT_REPEAT_INTERVAL = 50L  // Time between repeats

        // Timing constraints
        private const val MIN_INITIAL_DELAY = 100L
        private const val MAX_INITIAL_DELAY = 1000L
        private const val MIN_REPEAT_INTERVAL = 20L
        private const val MAX_REPEAT_INTERVAL = 200L
    }

    /**
     * Callback interface for key repeat events.
     */
    interface Callback {
        /**
         * Called when a key should be repeated.
         *
         * @param key The KeyValue being repeated
         */
        fun onKeyRepeat(key: KeyValue)
    }

    // Handler for scheduling repeats
    private val handler = Handler(Looper.getMainLooper())

    // Current repeat state
    private var currentKey: KeyValue? = null
    private var callback: Callback? = null
    private var isRepeating = false
    private var repeatCount = 0

    // Runnables for initial delay and repeat
    private var initialDelayRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null

    init {
        logD("Initializing KeyRepeatHandler (enabled=$enabled, initialDelay=${initialDelay}ms, repeatInterval=${repeatInterval}ms)")
    }

    /**
     * Start key repeat for a pressed key.
     *
     * @param key The KeyValue being pressed
     * @param callback Callback to receive repeat events
     */
    fun startRepeat(key: KeyValue, callback: Callback) {
        if (!enabled) {
            return
        }

        // Check if this key should repeat
        if (!shouldKeyRepeat(key)) {
            logD("Key does not support repeat: $key")
            return
        }

        // Cancel any existing repeat
        stopRepeat()

        // Store current state
        this.currentKey = key
        this.callback = callback
        this.repeatCount = 0
        this.isRepeating = false

        // Schedule initial delay before first repeat
        initialDelayRunnable = Runnable {
            startRepeating()
        }.also {
            handler.postDelayed(it, initialDelay)
        }

        logD("Started repeat for key: $key (initialDelay=${initialDelay}ms)")
    }

    /**
     * Start the repeating phase after initial delay.
     */
    private fun startRepeating() {
        if (!enabled || currentKey == null) {
            return
        }

        isRepeating = true

        // Create repeat runnable
        repeatRunnable = object : Runnable {
            override fun run() {
                if (isRepeating && currentKey != null) {
                    // Trigger repeat callback
                    currentKey?.let { key ->
                        callback?.onKeyRepeat(key)
                        repeatCount++
                    }

                    // Schedule next repeat
                    handler.postDelayed(this, repeatInterval)
                }
            }
        }

        // Trigger first repeat immediately
        currentKey?.let { key ->
            callback?.onKeyRepeat(key)
            repeatCount++
        }

        // Schedule subsequent repeats
        repeatRunnable?.let {
            handler.postDelayed(it, repeatInterval)
        }

        logD("Repeating started (interval=${repeatInterval}ms)")
    }

    /**
     * Stop key repeat.
     * Call this when the key is released or touch is cancelled.
     */
    fun stopRepeat() {
        // Cancel pending callbacks
        initialDelayRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable?.let { handler.removeCallbacks(it) }

        initialDelayRunnable = null
        repeatRunnable = null

        val wasRepeating = isRepeating
        val count = repeatCount

        // Clear state
        currentKey = null
        callback = null
        isRepeating = false
        repeatCount = 0

        if (wasRepeating) {
            logD("Stopped repeat after $count repetitions")
        }
    }

    /**
     * Check if a key should support auto-repeat.
     * Most keys repeat, but some (like modifiers) do not.
     *
     * @param key The KeyValue to check
     * @return true if key should repeat, false otherwise
     */
    private fun shouldKeyRepeat(key: KeyValue): Boolean {
        return when (key) {
            // Character keys always repeat
            is KeyValue.CharKey -> true

            // String keys repeat
            is KeyValue.StringKey -> true

            // Key event keys (like backspace, delete, arrows) repeat
            is KeyValue.KeyEventKey -> {
                when (key.keyCode) {
                    android.view.KeyEvent.KEYCODE_DEL,              // Backspace
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL,      // Delete
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,        // Left arrow
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,       // Right arrow
                    android.view.KeyEvent.KEYCODE_DPAD_UP,          // Up arrow
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,        // Down arrow
                    android.view.KeyEvent.KEYCODE_SPACE,            // Space
                    android.view.KeyEvent.KEYCODE_ENTER,            // Enter
                    android.view.KeyEvent.KEYCODE_TAB -> true       // Tab
                    else -> false
                }
            }

            // Event keys - cursor movement events repeat
            is KeyValue.EventKey -> {
                when (key.event) {
                    KeyValue.Event.CURSOR_LEFT,
                    KeyValue.Event.CURSOR_RIGHT,
                    KeyValue.Event.CURSOR_WORD_LEFT,
                    KeyValue.Event.CURSOR_WORD_RIGHT,
                    KeyValue.Event.CURSOR_LINE_START,
                    KeyValue.Event.CURSOR_LINE_END,
                    KeyValue.Event.CURSOR_DOC_START,
                    KeyValue.Event.CURSOR_DOC_END -> true
                    else -> false
                }
            }

            // Editing keys - delete repeats
            is KeyValue.EditingKey -> {
                when (key.editing) {
                    KeyValue.Editing.DELETE_WORD,
                    KeyValue.Editing.FORWARD_DELETE_WORD -> true
                    else -> false
                }
            }

            // Modifier keys don't repeat
            is KeyValue.ModifierKey -> false

            // Compose keys don't repeat
            is KeyValue.ComposePendingKey -> false

            // Unknown keys don't repeat
            else -> false
        }
    }

    /**
     * Check if a key is currently repeating.
     *
     * @return true if a key is repeating, false otherwise
     */
    fun isRepeating(): Boolean = isRepeating

    /**
     * Get the current repeat count.
     *
     * @return Number of times the key has repeated (0 if not repeating)
     */
    fun getRepeatCount(): Int = repeatCount

    /**
     * Get the key that is currently repeating.
     *
     * @return Current key, or null if not repeating
     */
    fun getCurrentKey(): KeyValue? = currentKey

    /**
     * Enable or disable key repeat.
     *
     * @param enabled true to enable repeat, false to disable
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled

        if (!enabled) {
            stopRepeat()
        }

        logD("Key repeat ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if key repeat is enabled.
     *
     * @return true if enabled, false if disabled
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Set initial delay before repeat starts.
     *
     * @param delay Delay in milliseconds
     */
    fun setInitialDelay(delay: Long) {
        this.initialDelay = delay.coerceIn(MIN_INITIAL_DELAY, MAX_INITIAL_DELAY)
        logD("Initial delay set to: ${this.initialDelay}ms")
    }

    /**
     * Get current initial delay.
     *
     * @return Delay in milliseconds
     */
    fun getInitialDelay(): Long = initialDelay

    /**
     * Set repeat interval.
     *
     * @param interval Interval in milliseconds
     */
    fun setRepeatInterval(interval: Long) {
        this.repeatInterval = interval.coerceIn(MIN_REPEAT_INTERVAL, MAX_REPEAT_INTERVAL)
        logD("Repeat interval set to: ${this.repeatInterval}ms")
    }

    /**
     * Get current repeat interval.
     *
     * @return Interval in milliseconds
     */
    fun getRepeatInterval(): Long = repeatInterval

    /**
     * Set both initial delay and repeat interval.
     *
     * @param initialDelay Initial delay in milliseconds
     * @param repeatInterval Repeat interval in milliseconds
     */
    fun setTiming(initialDelay: Long, repeatInterval: Long) {
        this.initialDelay = initialDelay.coerceIn(MIN_INITIAL_DELAY, MAX_INITIAL_DELAY)
        this.repeatInterval = repeatInterval.coerceIn(MIN_REPEAT_INTERVAL, MAX_REPEAT_INTERVAL)
        logD("Timing updated (initialDelay=${this.initialDelay}ms, repeatInterval=${this.repeatInterval}ms)")
    }

    /**
     * Handle touch event for repeat control.
     * Automatically stops repeat on ACTION_UP or ACTION_CANCEL.
     *
     * @param event The MotionEvent
     * @return true if event was handled, false otherwise
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (isRepeating) {
                    stopRepeat()
                    return true
                }
            }
        }
        return false
    }

    /**
     * Reset repeat handler to initial state.
     * Stops any active repeat and clears all state.
     */
    fun reset() {
        stopRepeat()
        logD("Reset to initial state")
    }

    /**
     * Release all resources and cleanup.
     * Must be called when the handler is no longer needed.
     */
    fun release() {
        logD("Releasing KeyRepeatHandler resources...")

        try {
            stopRepeat()
            handler.removeCallbacksAndMessages(null)
            logD("✅ KeyRepeatHandler resources released")
        } catch (e: Exception) {
            logE("Error releasing key repeat handler resources", e)
        }
    }

    // Logging helpers
    private fun logD(message: String) = Log.d(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
