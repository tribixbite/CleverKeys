package tribixbite.cleverkeys

import android.content.Context
import android.text.InputType
import android.util.LruCache
import android.view.inputmethod.EditorInfo

/**
 * Manages keyboard layout selection, switching, and loading.
 *
 * This class centralizes logic for:
 * - Current layout tracking (text layouts and special layouts)
 * - Layout switching (text, numeric, emoji, clipboard, etc.)
 * - Layout navigation (forward/backward cycling through text layouts)
 * - Layout loading with modifiers (numpad, pinentry)
 * - Special layout determination based on input type
 *
 * Responsibilities:
 * - Track current text layout and special layout state
 * - Provide current layout (with or without modifiers)
 * - Load layouts from resources with appropriate modifications
 * - Determine special layouts based on EditorInfo input type
 * - Navigate between text layouts
 *
 * NOT included (remains in CleverKeysService):
 * - View updates (setting keyboard on Keyboard2View)
 * - InputMethodService lifecycle methods
 * - Configuration management (reads from Config but doesn't modify)
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.363).
 */
class LayoutManager(
    private val context: Context,
    private var config: Config,
    private var localeTextLayout: KeyboardData
) {
    // Layout state
    private var currentSpecialLayout: KeyboardData? = null
    private val keyboardDataCache = LruCache<Int, KeyboardData>(10)

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        config = newConfig
    }

    /**
     * Sets the locale text layout (default layout for typing).
     *
     * @param layout Locale-specific text layout
     */
    fun setLocaleTextLayout(layout: KeyboardData) {
        localeTextLayout = layout
    }

    /**
     * Gets the current special layout (or null if showing text layout).
     *
     * @return Current special layout, or null
     */
    fun getCurrentSpecialLayout(): KeyboardData? = currentSpecialLayout

    /**
     * Layout currently visible before it has been modified.
     *
     * @return Unmodified current layout
     */
    fun current_layout_unmodified(): KeyboardData {
        currentSpecialLayout?.let { return it }

        var layout_i = config.get_current_layout()
        if (layout_i >= config.layouts.size) {
            layout_i = 0
        }

        val layout = if (layout_i < config.layouts.size) {
            config.layouts[layout_i]
        } else {
            null
        }

        return layout ?: localeTextLayout
    }

    /**
     * Layout currently visible (with modifiers applied).
     *
     * @return Current layout with modifications
     */
    fun current_layout(): KeyboardData {
        currentSpecialLayout?.let { return it }
        return LayoutModifier.modify_layout(current_layout_unmodified())
    }

    /**
     * Sets the current text layout index and clears special layout.
     *
     * @param layoutIndex Index of text layout in config.layouts
     * @return The new current layout (for updating view)
     */
    fun setTextLayout(layoutIndex: Int): KeyboardData {
        config.set_current_layout(layoutIndex)
        currentSpecialLayout = null
        return current_layout()
    }

    /**
     * Cycles to next/previous text layout.
     *
     * @param delta +1 for forward, -1 for backward
     * @return The new current layout (for updating view)
     */
    fun incrTextLayout(delta: Int): KeyboardData {
        val s = config.layouts.size
        val newIndex = (config.get_current_layout() + delta + s) % s
        return setTextLayout(newIndex)
    }

    /**
     * Sets a special layout (numeric, emoji, etc.).
     *
     * @param layout Special layout to display
     * @return The special layout (for updating view)
     */
    fun setSpecialLayout(layout: KeyboardData): KeyboardData {
        currentSpecialLayout = layout
        return layout
    }

    /**
     * Clears special layout and returns to text layout.
     *
     * @return The current text layout (for updating view)
     */
    fun clearSpecialLayout(): KeyboardData {
        currentSpecialLayout = null
        return current_layout()
    }

    /**
     * Load a layout from resources.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded layout
     */
    fun loadLayout(layoutId: Int): KeyboardData? {
        return keyboardDataCache.get(layoutId) ?: run {
            val keyboardData = KeyboardData.load(context.resources, layoutId)
            keyboardDataCache.put(layoutId, keyboardData)
            keyboardData
        }
    }

    /**
     * Load a layout that contains a numpad, modified with current layout keys.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded and modified numpad layout
     */
    fun loadNumpad(layoutId: Int): KeyboardData? {
        val layout = loadLayout(layoutId)
        return if (layout != null) {
            LayoutModifier.modify_numpad(layout, current_layout_unmodified())
        } else {
            null
        }
    }

    /**
     * Load a pinentry layout, modified with current layout keys.
     *
     * @param layoutId Resource ID of layout XML
     * @return Loaded and modified pinentry layout
     */
    fun loadPinentry(layoutId: Int): KeyboardData? {
        val layout = loadLayout(layoutId)
        return if (layout != null) {
            LayoutModifier.modify_pinentry(layout, current_layout_unmodified())
        } else {
            null
        }
    }

    /**
     * Determine special layout based on input type (number, phone, datetime).
     * Returns null if no special layout is needed.
     *
     * @param info EditorInfo from input field
     * @return Special layout, or null for normal text input
     */
    fun refresh_special_layout(info: EditorInfo): KeyboardData? {
        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> {
                return when (config.selected_number_layout) {
                    NumberLayout.PIN -> {
                        val pinId = context.resources.getIdentifier("pin", "raw", context.packageName)
                        loadPinentry(pinId)
                    }
                    NumberLayout.NUMBER -> {
                        val numId = context.resources.getIdentifier("numeric", "raw", context.packageName)
                        loadNumpad(numId)
                    }
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * Gets current text layout index.
     *
     * @return Current layout index
     */
    fun getCurrentLayoutIndex(): Int = config.get_current_layout()

    /**
     * Gets total number of text layouts.
     *
     * @return Number of layouts
     */
    fun getLayoutCount(): Int = config.layouts.size

    companion object {
        private const val TAG = "LayoutManager"
    }
}
