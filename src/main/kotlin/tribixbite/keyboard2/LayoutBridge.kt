package tribixbite.keyboard2

/**
 * Bridge between CleverKeysService and LayoutManager for layout operations.
 *
 * This class consolidates all layout delegation logic, handling:
 * - Layout retrieval (current, unmodified)
 * - Layout switching (text, special, numeric)
 * - Layout loading from resources
 * - Keyboard view updates after layout changes
 *
 * The bridge pattern simplifies CleverKeysService by centralizing layout management
 * coordination between LayoutManager and Keyboard2View.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.408).
 *
 * @since v1.32.408
 */
class LayoutBridge(
    private val layoutManager: LayoutManager,
    private val keyboardView: Keyboard2View
) {
    /**
     * Get the layout currently visible before it has been modified.
     *
     * @return Unmodified keyboard layout
     */
    fun getCurrentLayoutUnmodified(): KeyboardData {
        return layoutManager.current_layout_unmodified()
    }

    /**
     * Get the layout currently visible (with modifications applied).
     *
     * @return Current keyboard layout
     */
    fun getCurrentLayout(): KeyboardData {
        return layoutManager.current_layout()
    }

    /**
     * Set text layout by index.
     *
     * Updates the layout manager and applies the new layout to the keyboard view.
     *
     * @param layoutIndex Index of the text layout to set
     */
    fun setTextLayout(layoutIndex: Int) {
        val layout = layoutManager.setTextLayout(layoutIndex)
        keyboardView.setKeyboard(layout)
    }

    /**
     * Cycle to next/previous text layout.
     *
     * Updates the layout manager and applies the new layout to the keyboard view.
     *
     * @param delta Direction to cycle (+1 for next, -1 for previous)
     */
    fun incrTextLayout(delta: Int) {
        val layout = layoutManager.incrTextLayout(delta)
        keyboardView.setKeyboard(layout)
    }

    /**
     * Set special layout (numeric, emoji, etc.).
     *
     * Updates the layout manager and applies the new layout to the keyboard view.
     *
     * @param specialLayout The special keyboard layout to set
     */
    fun setSpecialLayout(specialLayout: KeyboardData) {
        val layout = layoutManager.setSpecialLayout(specialLayout)
        keyboardView.setKeyboard(layout)
    }

    /**
     * Load a layout from resources.
     *
     * @param layoutId Resource ID of the layout to load
     * @return Loaded keyboard layout
     */
    fun loadLayout(layoutId: Int): KeyboardData? {
        return layoutManager.loadLayout(layoutId)
    }

    /**
     * Load a layout that contains a numpad.
     *
     * @param layoutId Resource ID of the numpad layout to load
     * @return Loaded numpad keyboard layout
     */
    fun loadNumpad(layoutId: Int): KeyboardData? {
        return layoutManager.loadNumpad(layoutId)
    }

    /**
     * Load a pinentry layout.
     *
     * @param layoutId Resource ID of the pinentry layout to load
     * @return Loaded pinentry keyboard layout
     */
    fun loadPinentry(layoutId: Int): KeyboardData? {
        return layoutManager.loadPinentry(layoutId)
    }

    companion object {
        /**
         * Create a LayoutBridge.
         *
         * @param layoutManager The layout manager
         * @param keyboardView The keyboard view
         * @return A new LayoutBridge instance
         */
        @JvmStatic
        fun create(
            layoutManager: LayoutManager,
            keyboardView: Keyboard2View
        ): LayoutBridge {
            return LayoutBridge(layoutManager, keyboardView)
        }
    }
}
