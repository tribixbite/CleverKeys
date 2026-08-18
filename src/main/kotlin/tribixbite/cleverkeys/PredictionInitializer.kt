package tribixbite.cleverkeys

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
        // Wire the view's service handle FIRST and UNCONDITIONALLY — outside every config
        // check, before any model loading, and with no engine-readiness gate.
        //
        // Despite the name, `setSwipeTypingComponents` is what gives `Keyboard2View` its
        // `_keyboard2` reference, and that handle is load-bearing far beyond word swipes:
        // custom short swipes (`onCustomShortSwipe` returns early with "no service reference"
        // without it), suggestion-bar messages, the selection menu, and the primary/secondary
        // language toggles all go through it. **No subkey behaviour may depend on prediction
        // or swipe-typing settings** — a user with both switched off still gets their custom
        // subkey gestures, which is the whole point of the Short Swipe Customization feature.
        //
        // Three wrong gates lived here, each narrower than the last was assumed to be:
        //  1. `isSwipeTypingAvailable()`, which is literally `neuralEngine != null` — the wrong
        //     dependency twice over, since this call passes the word predictor and the service
        //     handle and touches the neural engine not at all. It only ever worked because
        //     something always built the neural engine eagerly.
        //  2. `swipe_typing_enabled`, which still stranded users who keep swipe typing off.
        //  3. the enclosing `word_prediction_enabled || swipe_typing_enabled`, which stranded
        //     users who have BOTH off.
        // When the neural build became conditional on routing (`shouldPreloadNeuralEngine`),
        // gate 1 went permanently false in CTC mode, the call stopped happening, and
        // `_keyboard2` stayed null — killing word swipes and user-created subkey short swipes
        // together, while layout-defined subkeys kept working because they never take this path.
        //
        // Null-tolerant by signature, so passing a not-yet-built predictor is safe: the
        // predictor is re-read per gesture, the service handle is not.
        keyboardView.setSwipeTypingComponents(
            predictionCoordinator?.getWordPredictor(),
            keyboard2
        )

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

            // Re-push the predictor now that it exists; the handle above was wired before the
            // synchronous load, so on a cold start the first call passed null for it.
            keyboardView.setSwipeTypingComponents(
                predictionCoordinator?.getWordPredictor(),
                keyboard2
            )
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
