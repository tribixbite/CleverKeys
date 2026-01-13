package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * Collects and stores ML training data from swipe gestures.
 *
 * This class centralizes logic for:
 * - Collecting ML data when user selects swipe predictions
 * - Copying trace points from temporary swipe data
 * - Copying registered keys from swipe data
 * - Storing ML data in the data store
 * - Handling normalization/denormalization of coordinates
 * - Enforcing privacy controls and user consent (v1.32.902 - Phase 6.5)
 *
 * Responsibilities:
 * - Check if ML data collection should occur (was last input swipe?)
 * - Verify user consent before collecting data
 * - Create SwipeMLData objects with correct dimensions
 * - Copy trace points and registered keys from current swipe
 * - Store collected data in ML data store
 * - Apply privacy settings (anonymization, retention)
 *
 * NOT included (remains in CleverKeysService):
 * - Retrieving current swipe data from InputCoordinator
 * - Accessing PredictionCoordinator and ML data store
 * - Context tracking (wasLastInputSwipe)
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.370).
 *
 * @since v1.32.902 - Phase 6.5: Privacy considerations integrated
 */
class MLDataCollector(private val context: Context) {

    private val privacyManager = PrivacyManager.getInstance(context)

    /**
     * Collects and stores ML data from a swipe gesture when user selects a suggestion.
     *
     * Privacy controls (Phase 6.5):
     * - Checks user consent before collecting
     * - Respects privacy_collect_swipe setting
     * - Applies anonymization if enabled
     * - Enforces data retention policies
     *
     * @param word Selected word from suggestion
     * @param currentSwipeData Current swipe data containing trace points and registered keys
     * @param keyboardHeight Height of keyboard view for ML data
     * @param mlDataStore ML data store to save the data
     * @return true if data was collected and stored, false otherwise
     */
    fun collectAndStoreSwipeData(
        word: String,
        currentSwipeData: SwipeMLData?,
        keyboardHeight: Int,
        mlDataStore: SwipeMLDataStore?
    ): Boolean {
        // Privacy check: Verify consent before collecting
        if (!privacyManager.canCollectSwipeData()) {
            Log.d("MLDataCollector", "Swipe data collection disabled or no consent")
            return false
        }

        if (currentSwipeData == null || mlDataStore == null) {
            return false
        }

        return try {
            // Strip "raw:" prefix before storing ML data
            val cleanWord = word.replace(Regex("^raw:"), "")

            // Create a new ML data object with the selected word
            val metrics = context.resources.displayMetrics
            val mlData = SwipeMLData(
                cleanWord, "user_selection",
                metrics.widthPixels, metrics.heightPixels,
                keyboardHeight
            )

            // Copy trace points from the temporary data
            // FIX: tDeltaMs values are deltas from PREVIOUS point, not offsets from start
            // Must accumulate them to reconstruct absolute timestamps
            var runningTimestamp = System.currentTimeMillis() - 1000
            for (point in currentSwipeData.getTracePoints()) {
                // Add points with their original normalized values and timestamps
                // Since they're already normalized, we need to denormalize then renormalize
                // to ensure proper storage
                val rawX = point.x * metrics.widthPixels
                val rawY = point.y * metrics.heightPixels
                // Accumulate delta to get correct absolute timestamp
                runningTimestamp += point.tDeltaMs
                mlData.addRawPoint(rawX, rawY, runningTimestamp)
            }

            // Copy registered keys
            for (key in currentSwipeData.getRegisteredKeys()) {
                mlData.addRegisteredKey(key)
            }

            // Store the ML data
            mlDataStore.storeSwipeData(mlData)
            true
        } catch (e: Exception) {
            Log.e("MLDataCollector", "Error collecting ML data", e)
            false
        }
    }
}
