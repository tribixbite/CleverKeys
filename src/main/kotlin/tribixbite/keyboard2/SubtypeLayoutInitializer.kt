package tribixbite.keyboard2

import android.content.res.Resources

/**
 * Handles subtype refresh and LayoutManager/LayoutBridge initialization.
 *
 * This bridge consolidates the logic for:
 * - Lazy initialization of SubtypeManager
 * - Refreshing IME subtype to get default layout
 * - Creating or updating LayoutManager with locale layout
 * - Creating LayoutBridge when LayoutManager is first initialized
 *
 * Extracted from CleverKeysService.refreshSubtypeImm() to reduce main class complexity.
 *
 * @since v1.32.409
 */
class SubtypeLayoutInitializer(
    private val keyboard2: CleverKeysService,
    private val config: Config?,
    private val keyboardView: Keyboard2View
) {
    /**
     * Result of subtype and layout initialization.
     *
     * @property subtypeManager The SubtypeManager (created or existing)
     * @property layoutManager The LayoutManager (created or existing)
     * @property layoutBridge The LayoutBridge (created if LayoutManager was just created, null otherwise)
     */
    data class InitializationResult(
        val subtypeManager: SubtypeManager,
        val layoutManager: LayoutManager?,
        val layoutBridge: LayoutBridge?
    )

    /**
     * Refresh subtype and initialize/update LayoutManager.
     *
     * This method:
     * 1. Creates SubtypeManager if needed (lazy initialization)
     * 2. Refreshes subtype to get default layout for current locale
     * 3. Updates existing LayoutManager with locale layout, OR
     * 4. Creates new LayoutManager and LayoutBridge on first call
     *
     * @param existingSubtypeManager The current SubtypeManager (null on first call)
     * @param existingLayoutManager The current LayoutManager (null on first call)
     * @param resources Resources for loading layouts and subtype data
     * @return InitializationResult containing managers and optional bridge
     */
    fun refreshSubtypeAndLayout(
        existingSubtypeManager: SubtypeManager?,
        existingLayoutManager: LayoutManager?,
        resources: Resources
    ): InitializationResult {
        // Initialize SubtypeManager if needed (lazy initialization)
        val subtypeManager = existingSubtypeManager ?: SubtypeManager(keyboard2)

        // Refresh subtype and get default layout
        val defaultLayout = if (config != null) {
            subtypeManager.refreshSubtype(config, resources)
                ?: KeyboardData.load(resources, R.xml.latn_qwerty_us)
        } else {
            KeyboardData.load(resources, R.xml.latn_qwerty_us)
        }

        // Update or create LayoutManager
        val layoutManager: LayoutManager?
        val layoutBridge: LayoutBridge?

        if (existingLayoutManager != null && defaultLayout != null) {
            // Update existing LayoutManager with locale layout
            existingLayoutManager.setLocaleTextLayout(defaultLayout)
            layoutManager = existingLayoutManager
            layoutBridge = null  // Don't recreate bridge
        } else if (defaultLayout != null && config != null) {
            // First call - initialize LayoutManager with default layout
            layoutManager = LayoutManager(keyboard2, config, defaultLayout)

            // Initialize LayoutBridge
            layoutBridge = LayoutBridge.create(layoutManager, keyboardView)
        } else {
            // defaultLayout is null - return null result
            layoutManager = null
            layoutBridge = null
        }

        return InitializationResult(subtypeManager, layoutManager, layoutBridge)
    }

    companion object {
        /**
         * Create a SubtypeLayoutInitializer.
         *
         * @param keyboard2 The CleverKeysService service
         * @param config The configuration
         * @param keyboardView The keyboard view
         * @return A new SubtypeLayoutInitializer instance
         */
        @JvmStatic
        fun create(
            keyboard2: CleverKeysService,
            config: Config?,
            keyboardView: Keyboard2View
        ): SubtypeLayoutInitializer {
            return SubtypeLayoutInitializer(keyboard2, config, keyboardView)
        }
    }
}
