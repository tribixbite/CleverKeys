package tribixbite.keyboard2

/**
 * Bridge between CleverKeysService and NeuralLayoutHelper for neural engine operations.
 *
 * This class consolidates all neural layout delegation logic, handling:
 * - Dynamic keyboard height calculations
 * - CGR (Character-level Gesture Recognition) prediction updates
 * - Swipe prediction management (legacy methods)
 * - Neural keyboard layout configuration
 *
 * The bridge pattern simplifies CleverKeysService by centralizing neural engine
 * coordination and providing null-safe delegation with sensible defaults.
 *
 * This utility is extracted from CleverKeysService.java as part of Phase 4 refactoring
 * to reduce the main class size (v1.32.407).
 *
 * @since v1.32.407
 */
class NeuralLayoutBridge(
    private val neuralLayoutHelper: NeuralLayoutHelper?,
    private val keyboardView: Keyboard2View?
) {
    /**
     * Calculate dynamic keyboard height based on user settings.
     *
     * Returns the height from NeuralLayoutHelper if available, otherwise
     * falls back to keyboard view height or 0.
     *
     * @return Keyboard height in pixels
     */
    fun calculateDynamicKeyboardHeight(): Float {
        neuralLayoutHelper?.let {
            return it.calculateDynamicKeyboardHeight()
        }
        return keyboardView?.height?.toFloat() ?: 0f
    }

    /**
     * Get user keyboard height percentage for logging.
     *
     * Returns the percentage from NeuralLayoutHelper if available, otherwise
     * returns the default value of 35%.
     *
     * @return Keyboard height as percentage (0-100)
     */
    fun getUserKeyboardHeightPercent(): Int {
        neuralLayoutHelper?.let {
            return it.getUserKeyboardHeightPercent()
        }
        return 35 // Default height percentage
    }

    /**
     * Update swipe predictions by checking keyboard view for CGR results.
     *
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     */
    fun updateCGRPredictions() {
        neuralLayoutHelper?.updateCGRPredictions()
    }

    /**
     * Check and update CGR predictions.
     *
     * Call this periodically or on swipe events to refresh predictions.
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     */
    fun checkCGRPredictions() {
        neuralLayoutHelper?.checkCGRPredictions()
    }

    /**
     * Update swipe predictions in real-time during gesture (legacy method).
     *
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     *
     * @param predictions List of prediction strings
     */
    fun updateSwipePredictions(predictions: List<String>) {
        neuralLayoutHelper?.updateSwipePredictions(predictions)
    }

    /**
     * Complete swipe predictions after gesture ends (legacy method).
     *
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     *
     * @param finalPredictions Final list of prediction strings
     */
    fun completeSwipePredictions(finalPredictions: List<String>) {
        neuralLayoutHelper?.completeSwipePredictions(finalPredictions)
    }

    /**
     * Clear swipe predictions (legacy method).
     *
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     */
    fun clearSwipePredictions() {
        neuralLayoutHelper?.clearSwipePredictions()
    }

    /**
     * Extract key positions from keyboard layout and set them on neural engine.
     *
     * CRITICAL for neural swipe typing - without this, key detection fails completely!
     * Delegates to NeuralLayoutHelper if available. No-op if helper is null.
     */
    fun setNeuralKeyboardLayout() {
        neuralLayoutHelper?.setNeuralKeyboardLayout()
    }

    companion object {
        /**
         * Create a NeuralLayoutBridge.
         *
         * @param neuralLayoutHelper The neural layout helper (nullable)
         * @param keyboardView The keyboard view (nullable, used for fallback height)
         * @return A new NeuralLayoutBridge instance
         */
        @JvmStatic
        fun create(
            neuralLayoutHelper: NeuralLayoutHelper?,
            keyboardView: Keyboard2View?
        ): NeuralLayoutBridge {
            return NeuralLayoutBridge(neuralLayoutHelper, keyboardView)
        }
    }
}
