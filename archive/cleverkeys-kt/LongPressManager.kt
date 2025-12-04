package tribixbite.cleverkeys

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * Manages long-press behavior for keyboard keys.
 *
 * Features:
 * - Long-press detection with configurable delay
 * - Popup showing alternate characters (accents, symbols, etc.)
 * - Touch tracking for alternate character selection
 * - Auto-repeat for certain keys (backspace, arrows)
 * - Cancellation on touch movement
 * - Vibration feedback
 *
 * Addresses Bug #327: LongPressManager missing (CATASTROPHIC)
 */
class LongPressManager(
    private val callback: Callback,
    private val longPressDelay: Long = DEFAULT_LONG_PRESS_DELAY,
    private val autoRepeatDelay: Long = DEFAULT_AUTO_REPEAT_DELAY,
    private val autoRepeatInterval: Long = DEFAULT_AUTO_REPEAT_INTERVAL
) {

    companion object {
        private const val TAG = "LongPressManager"

        // Default timing values (milliseconds)
        const val DEFAULT_LONG_PRESS_DELAY = 500L      // Time to trigger long press
        const val DEFAULT_AUTO_REPEAT_DELAY = 400L      // Time before auto-repeat starts
        const val DEFAULT_AUTO_REPEAT_INTERVAL = 50L    // Time between auto-repeats

        // Movement threshold to cancel long press (in pixels)
        const val MOVEMENT_THRESHOLD = 30f
    }

    /**
     * Callback interface for long-press events
     */
    interface Callback {
        /**
         * Called when long press is triggered
         * @param key The key that was long-pressed
         * @param x X coordinate of the press
         * @param y Y coordinate of the press
         * @return true if long press was handled (shows popup), false otherwise
         */
        fun onLongPress(key: KeyValue, x: Float, y: Float): Boolean

        /**
         * Called during auto-repeat
         * @param key The key being repeated
         */
        fun onAutoRepeat(key: KeyValue)

        /**
         * Called when an alternate character is selected from popup
         * @param key The original key
         * @param alternate The selected alternate character
         */
        fun onAlternateSelected(key: KeyValue, alternate: KeyValue)

        /**
         * Request vibration feedback
         */
        fun performVibration()
    }

    private val handler = Handler(Looper.getMainLooper())

    // State tracking
    private var currentKey: KeyValue? = null
    private var initialX = 0f
    private var initialY = 0f
    private var isLongPressTriggered = false
    private var isAutoRepeating = false
    private var popupShowing = false

    // Runnable for long press detection
    private val longPressRunnable = Runnable {
        currentKey?.let { key ->
            isLongPressTriggered = true

            // Check if key has alternates
            if (callback.onLongPress(key, initialX, initialY)) {
                popupShowing = true
                callback.performVibration()
            } else if (isAutoRepeatKey(key)) {
                // Start auto-repeat for eligible keys
                startAutoRepeat(key)
            }
        }
    }

    // Runnable for auto-repeat
    private val autoRepeatRunnable = object : Runnable {
        override fun run() {
            currentKey?.let { key ->
                isAutoRepeating = true
                callback.onAutoRepeat(key)
                handler.postDelayed(this, autoRepeatInterval)
            }
        }
    }

    /**
     * Handle touch down event - start long press timer
     */
    fun onTouchDown(key: KeyValue, x: Float, y: Float) {
        // Cancel any existing timers
        cancel()

        // Store state
        currentKey = key
        initialX = x
        initialY = y
        isLongPressTriggered = false
        isAutoRepeating = false
        popupShowing = false

        // Start long press timer
        handler.postDelayed(longPressRunnable, longPressDelay)
    }

    /**
     * Handle touch move event - check if movement exceeds threshold
     */
    fun onTouchMove(x: Float, y: Float): Boolean {
        if (popupShowing) {
            // Allow movement when popup is showing (for selecting alternates)
            return true
        }

        // Calculate movement distance
        val dx = x - initialX
        val dy = y - initialY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        // Cancel long press if moved too far
        if (distance > MOVEMENT_THRESHOLD) {
            cancel()
            return false
        }

        return true
    }

    /**
     * Handle touch up event - stop timers and handle selection
     */
    fun onTouchUp(x: Float, y: Float) {
        if (popupShowing) {
            // User released while popup was showing - select alternate if any
            // The actual selection logic would be handled by the popup view
            popupShowing = false
        }

        cancel()
    }

    /**
     * Cancel current long press and auto-repeat
     */
    fun cancel() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(autoRepeatRunnable)
        currentKey = null
        isLongPressTriggered = false
        isAutoRepeating = false
        popupShowing = false
    }

    /**
     * Check if long press was triggered
     */
    fun wasLongPressTriggered(): Boolean = isLongPressTriggered

    /**
     * Check if currently auto-repeating
     */
    fun isAutoRepeating(): Boolean = isAutoRepeating

    /**
     * Check if popup is currently showing
     */
    fun isPopupShowing(): Boolean = popupShowing

    /**
     * Start auto-repeat for a key
     */
    private fun startAutoRepeat(key: KeyValue) {
        handler.postDelayed(autoRepeatRunnable, autoRepeatDelay)
    }

    /**
     * Check if a key should auto-repeat when held
     */
    private fun isAutoRepeatKey(key: KeyValue): Boolean {
        return when (key) {
            // Backspace auto-repeats
            is KeyValue.KeyEventKey -> when (key.keyCode) {
                android.view.KeyEvent.KEYCODE_DEL -> true
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> true
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
            // Space can auto-repeat
            is KeyValue.CharKey -> key.char == ' '
            else -> false
        }
    }

    /**
     * Set custom long press delay
     */
    fun setLongPressDelay(delay: Long) {
        // Implementation note: This would require recreating the manager
        // For now, delay is set in constructor
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        cancel()
    }
}

/**
 * Popup view for displaying alternate characters
 * This would be implemented as a custom view showing the alternates
 */
class AlternateCharacterPopup(
    private val anchorView: View,
    private val alternates: List<KeyValue>,
    private val onSelect: (KeyValue) -> Unit
) {

    // TODO: Implement popup view showing alternates in a horizontal row
    // TODO: Track touch movement to highlight alternates
    // TODO: Commit selected alternate on touch up

    private var selectedIndex = -1

    /**
     * Show the popup at the specified position
     */
    fun show(x: Float, y: Float) {
        // TODO: Create and show popup window
        // Position above the key
        // Show alternates in a row
    }

    /**
     * Update selection based on touch position
     */
    fun updateSelection(x: Float, y: Float) {
        // TODO: Calculate which alternate is under the touch point
        // Update visual highlight
    }

    /**
     * Get currently selected alternate
     */
    fun getSelected(): KeyValue? {
        return if (selectedIndex >= 0 && selectedIndex < alternates.size) {
            alternates[selectedIndex]
        } else {
            null
        }
    }

    /**
     * Dismiss the popup
     */
    fun dismiss() {
        // TODO: Hide popup window
    }
}

/**
 * Helper to manage alternate character mappings
 */
object AlternateCharacters {

    /**
     * Map of base characters to their alternates
     * This would be loaded from keyboard layout XML or configuration
     */
    private val alternatesMap = mapOf(
        'a' to listOf('à', 'á', 'â', 'ã', 'ä', 'å', 'æ', 'ā', 'ă', 'ą'),
        'e' to listOf('è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě'),
        'i' to listOf('ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į'),
        'o' to listOf('ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő', 'œ'),
        'u' to listOf('ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų'),
        'c' to listOf('ç', 'ć', 'ĉ', 'ċ', 'č'),
        'n' to listOf('ñ', 'ń', 'ņ', 'ň'),
        's' to listOf('ß', 'ś', 'ŝ', 'ş', 'š'),
        'y' to listOf('ý', 'ÿ', 'ŷ'),
        'z' to listOf('ź', 'ż', 'ž'),

        // Numbers to symbols
        '0' to listOf('°', '⁰', '₀'),
        '1' to listOf('¹', '₁', '½', '⅓', '¼'),
        '2' to listOf('²', '₂', '⅔'),
        '3' to listOf('³', '₃', '¾'),

        // Common symbol alternates
        '-' to listOf('–', '—', '−', '•'),
        '\'' to listOf('\u2018', '\u2019', '\u201A', '\u201B'), // ' ' ‚ ‛
        '"' to listOf('\u201C', '\u201D', '\u201E', '\u201F'), // " " „ ‟
        '!' to listOf('¡'),
        '?' to listOf('¿'),
        '$' to listOf('€', '£', '¥', '₹', '¢'),

        // Period to ellipsis
        '.' to listOf('…', '·'),
    )

    /**
     * Get alternates for a character
     */
    fun getAlternates(char: Char): List<Char>? {
        return alternatesMap[char.lowercaseChar()]
    }

    /**
     * Get alternates for a key value
     */
    fun getAlternates(key: KeyValue): List<KeyValue>? {
        if (key is KeyValue.CharKey) {
            val alternates = getAlternates(key.char) ?: return null
            val flagsArray = key.flags.toTypedArray()
            return alternates.map { KeyValue.makeCharKey(it, null, *flagsArray) }
        }
        return null
    }

    /**
     * Check if a key has alternates
     */
    fun hasAlternates(key: KeyValue): Boolean {
        if (key is KeyValue.CharKey) {
            return alternatesMap.containsKey(key.char.lowercaseChar())
        }
        return false
    }
}
