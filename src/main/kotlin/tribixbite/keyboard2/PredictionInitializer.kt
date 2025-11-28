package tribixbite.keyboard2

/**
 * Initializes prediction components during onCreate().
 *
 * This class handles initialization of prediction engines when word prediction
 * or swipe typing is enabled:
 * - Initializes PredictionCoordinator
 * - Sets swipe typing components on keyboard view if available
 *
 * The initializer pattern simplifies onCreate() by consolidating prediction
 * initialization into a single, testable operation.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.405).
 *
 * @since v1.32.405
 */
class PredictionInitializer(
    private val config: Config?,
    private val predictionCoordinator: PredictionCoordinator?,
    private val keyboardView: Keyboard2View,
    private val keyboard2: CleverKeysService
) {
    /**
     * Initialize prediction components if enabled.
     *
     * OPTIMIZATION v1.32.529: Load models synchronously to ensure first swipe works
     * Models stay loaded permanently via singleton pattern (236ms load, instant after)
     *
     * Checks configuration and:
     * 1. Initializes PredictionCoordinator if predictions/swipe enabled (synchronous)
     * 2. Sets swipe typing components on keyboard view if swipe is available
     *
     * Note: 236ms synchronous load is acceptable for keyboard startup to guarantee
     * first swipe works immediately. Singleton persists, so subsequent loads are instant.
     */
    fun initializeIfEnabled() {
        if (config?.word_prediction_enabled == true || config?.swipe_typing_enabled == true) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                android.util.Log.d("PredictionInitializer", "Starting model initialization (synchronous)...")
            }
            val startTime = System.currentTimeMillis()

            // Load models synchronously to guarantee first swipe works
            // Singleton persists, so this only happens once per app lifecycle
            predictionCoordinator?.initialize()

            val loadTime = System.currentTimeMillis() - startTime
            android.util.Log.i("PredictionInitializer", "✅ Models loaded in ${loadTime}ms (ready for swipes)")

            // Set swipe typing components on keyboard view if swipe is enabled
            if (config?.swipe_typing_enabled == true && predictionCoordinator?.isSwipeTypingAvailable() == true) {
                if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                    android.util.Log.d(
                        "CleverKeysService",
                        "Neural engine initialized - dimensions and key positions will be set after layout"
                    )
                }
                keyboardView.setSwipeTypingComponents(
                    predictionCoordinator.getWordPredictor(),
                    keyboard2
                )
            }
        }
    }

    companion object {
        /**
         * Create a PredictionInitializer.
         *
         * @param config The configuration
         * @param predictionCoordinator The prediction coordinator
         * @param keyboardView The keyboard view
         * @param keyboard2 The CleverKeysService service
         * @return A new PredictionInitializer instance
         */
        @JvmStatic
        fun create(
            config: Config?,
            predictionCoordinator: PredictionCoordinator?,
            keyboardView: Keyboard2View,
            keyboard2: CleverKeysService
        ): PredictionInitializer {
            return PredictionInitializer(
                config,
                predictionCoordinator,
                keyboardView,
                keyboard2
            )
        }
    }
}
