package tribixbite.cleverkeys.customization

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Keyboard2View
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * A FrameLayout wrapper that hosts an actual Keyboard2View instance for preview purposes.
 *
 * This component:
 * - Creates and manages a real Keyboard2View with the user's actual theme and layout
 * - Intercepts touch events to detect key taps without triggering input
 * - Provides callbacks when a key is tapped for customization UI
 * - Displays the EXACT same keyboard the user types on in other apps
 *
 * Usage in Compose via AndroidView:
 * ```kotlin
 * AndroidView(
 *     factory = { ctx ->
 *         KeyboardPreviewHost(ctx).apply {
 *             onKeyTapped = { key -> selectedKey = key }
 *         }
 *     }
 * )
 * ```
 */
class KeyboardPreviewHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyboardPreviewHost"
    }

    /** The actual keyboard view that renders keys */
    private var keyboardView: Keyboard2View? = null

    /** Current keyboard layout data */
    private var keyboardData: KeyboardData? = null

    /** Callback invoked when a key is tapped. Includes the key and its row height for proper aspect ratio. */
    var onKeyTapped: ((KeyboardData.Key) -> Unit)? = null

    /** Extended callback with row height for aspect ratio calculation */
    var onKeyTappedWithRowHeight: ((key: KeyboardData.Key, rowHeight: Float) -> Unit)? = null

    /** Callback invoked when a specific key code is tapped (for simpler integration) */
    var onKeyCodeTapped: ((String) -> Unit)? = null

    /** Whether preview mode is enabled (touch detection without input) */
    var previewMode: Boolean = true

    /** Track touch down position for tap detection */
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    /** Maximum distance for tap detection (in pixels) */
    private val tapThreshold = 20f

    /** Maximum time for tap detection (in milliseconds) */
    private val tapTimeThreshold = 300L

    init {
        // Initialize global config if not already set
        // This is needed because Keyboard2View depends on Config.globalConfig()
        ensureConfigInitialized()

        // Create the actual keyboard view
        createKeyboardView()
    }

    /**
     * Ensure Config.globalConfig() is initialized.
     * Keyboard2View requires this to be set before instantiation.
     */
    private fun ensureConfigInitialized() {
        try {
            // Try to access existing config - if it throws, we need to init
            Config.globalConfig()
            Log.d(TAG, "Config already initialized")
        } catch (e: Exception) {
            Log.d(TAG, "Initializing global config for preview")
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val res = context.resources
            Config.initGlobalConfig(prefs, res, null, null)
        }
    }

    /**
     * Create and configure the actual Keyboard2View instance.
     * This loads the user's real theme and layout configuration.
     */
    private fun createKeyboardView() {
        try {
            // Create the keyboard view
            // Use MATCH_PARENT for both to fill the preview host completely
            keyboardView = Keyboard2View(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
            }

            // Load the user's configured layout
            loadUserLayout()

            // Add keyboard view to this host
            keyboardView?.let { addView(it) }

            Log.d(TAG, "Created KeyboardPreviewHost with actual Keyboard2View")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create keyboard view", e)
        }
    }

    /**
     * Load the user's configured keyboard layout.
     * Uses the same loading mechanism as CleverKeysService.
     */
    private fun loadUserLayout() {
        try {
            val config = Config.globalConfig()
            Log.d(TAG, "loadUserLayout: config=$config")

            // Get the first layout from user's configuration
            val layouts = config.layouts
            Log.d(TAG, "loadUserLayout: layouts.size=${layouts.size}")
            if (layouts.isNotEmpty()) {
                keyboardData = layouts[0]
                keyboardView?.setKeyboard(layouts[0])
                Log.d(TAG, "Loaded user layout: ${layouts[0].name}, rows=${layouts[0].rows.size}, keysWidth=${layouts[0].keysWidth}")
            } else {
                // Fallback to QWERTY if no layouts configured
                Log.d(TAG, "No layouts in config, loading fallback")
                loadFallbackLayout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user layout", e)
            loadFallbackLayout()
        }
    }

    /**
     * Load fallback QWERTY layout if user config isn't available.
     */
    private fun loadFallbackLayout() {
        try {
            // Default to QWERTY
            val resources = context.resources
            val resId = resources.getIdentifier("latn_qwerty_us", "raw", context.packageName)
            val layout = KeyboardData.load(resources, resId)
            if (layout != null) {
                keyboardData = layout
                keyboardView?.setKeyboard(layout)
                Log.d(TAG, "Loaded fallback QWERTY layout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fallback layout", e)
        }
    }

    /**
     * Refresh the keyboard view with current configuration.
     * Call this when theme or layout changes.
     */
    fun refresh() {
        removeAllViews()
        createKeyboardView()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // In preview mode, intercept all touch events to prevent keyboard input
        return previewMode
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!previewMode) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDownTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - touchDownX
                val deltaY = event.y - touchDownY
                val deltaTime = System.currentTimeMillis() - touchDownTime

                // Check if this qualifies as a tap
                val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
                if (distance < tapThreshold && deltaTime < tapTimeThreshold) {
                    handleTap(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Handle a tap event and find the key at the tap position.
     */
    private fun handleTap(x: Float, y: Float) {
        val keyboard = keyboardData ?: return
        val keyWithRowHeight = findKeyAtPositionWithRowHeight(x, y, keyboard)

        if (keyWithRowHeight != null) {
            val (key, rowHeight) = keyWithRowHeight
            Log.d(TAG, "Key tapped: ${getKeyCode(key)}, rowHeight=$rowHeight, keyWidth=${key.width}")

            // Notify via extended callback with row height
            onKeyTappedWithRowHeight?.invoke(key, rowHeight)

            // Notify via key object callback (for backwards compatibility)
            onKeyTapped?.invoke(key)

            // Notify via key code callback (simpler interface)
            val keyCode = getKeyCode(key)
            if (keyCode != null) {
                onKeyCodeTapped?.invoke(keyCode)
            }
        } else {
            Log.d(TAG, "No key found at position ($x, $y)")
        }
    }

    /**
     * Find the key at the given position and return it along with the row height.
     * Returns a Pair of (key, rowHeight in keyboard units) or null if no key found.
     */
    private fun findKeyAtPositionWithRowHeight(x: Float, y: Float, keyboard: KeyboardData): Pair<KeyboardData.Key, Float>? {
        val kbView = keyboardView ?: return null
        val config = Config.globalConfig()

        // Get the theme computed values
        val keyWidth = kbView.width.toFloat() / keyboard.keysWidth
        val totalRowHeight = kbView.height.toFloat() - config.marginTop - config.margin_bottom
        val rowHeight = totalRowHeight / keyboard.keysHeight

        var currentY = config.marginTop

        // Find the row at this Y position
        for (row in keyboard.rows) {
            currentY += row.shift * rowHeight
            val rowBottom = currentY + row.height * rowHeight

            if (y >= currentY && y < rowBottom) {
                // Found the row, now find the key
                var currentX = config.horizontal_margin

                for (key in row.keys) {
                    currentX += key.shift * keyWidth
                    val keyRight = currentX + key.width * keyWidth

                    if (x >= currentX && x < keyRight) {
                        // Return both the key and the row height (in keyboard units)
                        return key to row.height
                    }
                    currentX = keyRight
                }
                break
            }
            currentY = rowBottom
        }
        return null
    }

    /**
     * Find the key at the given position.
     * This replicates the logic from Keyboard2View.getKeyAtPosition().
     */
    private fun findKeyAtPosition(x: Float, y: Float, keyboard: KeyboardData): KeyboardData.Key? {
        val kbView = keyboardView ?: return null
        val config = Config.globalConfig()

        // Get the theme computed values
        // We need to calculate row heights based on keyboard dimensions
        val keyWidth = kbView.width.toFloat() / keyboard.keysWidth
        val totalRowHeight = kbView.height.toFloat() - config.marginTop - config.margin_bottom
        val rowHeight = totalRowHeight / keyboard.keysHeight

        var currentY = config.marginTop

        // Find the row at this Y position
        for (row in keyboard.rows) {
            currentY += row.shift * rowHeight
            val rowBottom = currentY + row.height * rowHeight

            if (y >= currentY && y < rowBottom) {
                // Found the row, now find the key
                var currentX = config.horizontal_margin

                for (key in row.keys) {
                    currentX += key.shift * keyWidth
                    val keyRight = currentX + key.width * keyWidth

                    if (x >= currentX && x < keyRight) {
                        return key
                    }
                    currentX = keyRight
                }
                break
            }
            currentY = rowBottom
        }
        return null
    }

    /**
     * Get the key code string from a KeyboardData.Key.
     * Returns the lowercase letter for character keys, or a descriptive name for special keys.
     */
    private fun getKeyCode(key: KeyboardData.Key): String? {
        val mainKey = key.keys[0] ?: return null

        return when (mainKey.getKind()) {
            tribixbite.cleverkeys.KeyValue.Kind.Char -> {
                mainKey.getChar().lowercaseChar().toString()
            }
            tribixbite.cleverkeys.KeyValue.Kind.String -> {
                mainKey.getString().lowercase()
            }
            tribixbite.cleverkeys.KeyValue.Kind.Event -> {
                mainKey.getEvent().name.lowercase()
            }
            tribixbite.cleverkeys.KeyValue.Kind.Keyevent -> {
                "keyevent_${mainKey.getKeyevent()}"
            }
            tribixbite.cleverkeys.KeyValue.Kind.Modifier -> {
                mainKey.getModifier().name.lowercase()
            }
            tribixbite.cleverkeys.KeyValue.Kind.Editing -> {
                mainKey.getEditing().name.lowercase()
            }
            else -> mainKey.getString().lowercase().ifEmpty { null }
        }
    }

    /**
     * Get all keys from the current keyboard layout.
     * Useful for building a complete key list in the customization UI.
     */
    fun getAllKeys(): List<KeyboardData.Key> {
        val keyboard = keyboardData ?: return emptyList()
        return keyboard.rows.flatMap { row -> row.keys }
    }

    /**
     * Get a map of key codes to their corresponding KeyboardData.Key objects.
     */
    fun getKeyCodeMap(): Map<String, KeyboardData.Key> {
        return getAllKeys().mapNotNull { key ->
            getKeyCode(key)?.let { code -> code to key }
        }.toMap()
    }

    /**
     * Get the bounds of a specific key for magnification.
     * Returns a RectF with the key's screen coordinates.
     */
    fun getKeyBounds(key: KeyboardData.Key): RectF? {
        val keyboard = keyboardData ?: return null
        val kbView = keyboardView ?: return null
        val config = Config.globalConfig()

        val keyWidth = kbView.width.toFloat() / keyboard.keysWidth
        val totalRowHeight = kbView.height.toFloat() - config.marginTop - config.margin_bottom
        val rowHeight = totalRowHeight / keyboard.keysHeight

        var currentY = config.marginTop

        for (row in keyboard.rows) {
            currentY += row.shift * rowHeight

            var currentX = config.horizontal_margin
            for (k in row.keys) {
                currentX += k.shift * keyWidth
                val keyRight = currentX + k.width * keyWidth
                val keyBottom = currentY + row.height * rowHeight

                if (k == key) {
                    return RectF(currentX, currentY, keyRight, keyBottom)
                }
                currentX = keyRight
            }
            currentY += row.height * rowHeight
        }
        return null
    }

    /**
     * Get all sub-key mappings for a given key.
     * Returns a map of direction index (1-8) to KeyValue for currently mapped corners.
     */
    fun getSubKeyMappings(key: KeyboardData.Key): Map<Int, tribixbite.cleverkeys.KeyValue> {
        return (1..8).mapNotNull { index ->
            key.keys[index]?.let { kv -> index to kv }
        }.toMap()
    }

    /**
     * Check if the keyboard view is ready and has valid dimensions.
     */
    fun isReady(): Boolean {
        val kbView = keyboardView ?: return false
        return kbView.width > 0 && kbView.height > 0 && keyboardData != null
    }

    /**
     * Get the underlying Keyboard2View for advanced operations.
     * Use with caution - this breaks encapsulation but is needed for magnification.
     */
    fun getKeyboardView(): Keyboard2View? = keyboardView

    /**
     * Get the current keyboard data.
     */
    fun getKeyboardData(): KeyboardData? = keyboardData
}
