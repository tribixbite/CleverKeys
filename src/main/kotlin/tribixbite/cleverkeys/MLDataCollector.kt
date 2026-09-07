package tribixbite.cleverkeys

import android.content.Context
import android.util.Log
import tribixbite.cleverkeys.BuildConfig
import tribixbite.cleverkeys.ml.SwipeMLData
import tribixbite.cleverkeys.ml.SwipeMLDataStore

/**
 * Collects and stores ML training data from swipe gestures.
 *
 * This class centralizes logic for:
 * - Collecting ML data when user selects swipe predictions
 * - Copying the captured trace (points, keys, provenance, enrichment) verbatim
 * - Storing ML data in the data store
 * - Enforcing privacy controls and user consent (v1.32.902 - Phase 6.5)
 * - Enforcing the data-retention policy (I-2, comprehensive audit 2026-09-06):
 *   after a successful store, a daily-throttled cleanup deletes rows older than
 *   `PrivacyManager.getDataRetentionCutoff()` (when auto-delete is enabled, its
 *   default) and enforces a hard row cap — the swipe-ML DB is bounded.
 *
 * NOT provided (the old KDoc over-promised): anonymization. PrivacyManager's
 * anonymization surface has no engine behind it and nothing here consults it.
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

    companion object {
        /**
         * Hard backstop on stored rows (I-2). Enriched rows carry the full trace,
         * 27-key geometry and candidate JSON (tens of KB each), so the cap bounds the
         * DB to a few hundred MB even if the user sets an extreme retention period.
         * Oldest rows beyond the cap are deleted by the same daily cleanup.
         */
        const val MAX_STORED_ROWS = 10_000
    }

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
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) {
                Log.d("MLDataCollector", "Swipe data collection disabled or no consent")
            }
            return false
        }

        if (currentSwipeData == null || mlDataStore == null) {
            return false
        }

        return try {
            // Strip "raw:" prefix before storing ML data
            val cleanWord = word.replace(Regex("^raw:"), "")

            // I-6 (comprehensive audit 2026-09-06): copy via SwipeMLData.copyWith — the
            // method that exists for exactly this selection-time-copy case. It carries
            // points (normalized values AND deltas verbatim), registered keys, screen/
            // keyboard dimensions, provenance (layout + engine, audit n-2) and the
            // playground enrichment (key geometry, candidates, decode latency) under the
            // new word and source. The previous manual denormalize/renormalize loop
            // anchored `runningTimestamp = now − 1000` against a fresh object whose
            // last-timestamp anchor was `now`, skewing the stored FIRST delta by exactly
            // −1000 ms versus the playground row of the very same swipe.
            // (keyboardHeight param retained for call-site stability; the capture already
            // carries the height the trace was normalized against.)
            val mlData = currentSwipeData.copyWith(cleanWord, "user_selection")

            // Store the ML data
            mlDataStore.storeSwipeData(mlData)

            // I-2: retention enforcement — the only write path into the store is the
            // right place to keep the DB bounded. Daily-throttled via PrivacyManager.
            maybePerformRetentionCleanup(mlDataStore)
            true
        } catch (e: Exception) {
            Log.e("MLDataCollector", "Error collecting ML data", e)
            false
        }
    }

    /**
     * I-2: delete expired rows and enforce the row cap when a cleanup is due.
     *
     * `shouldPerformCleanup()` is true at most once per day and only while auto-delete
     * is enabled (its default). The timestamp is recorded BEFORE the store call so a
     * failing cleanup cannot re-trigger on every subsequent swipe; the store runs the
     * actual deletes on its own single-threaded executor, off this thread.
     */
    private fun maybePerformRetentionCleanup(mlDataStore: SwipeMLDataStore) {
        try {
            if (!privacyManager.shouldPerformCleanup()) return
            privacyManager.recordCleanupPerformed()
            mlDataStore.performRetentionCleanup(
                privacyManager.getDataRetentionCutoff(), MAX_STORED_ROWS
            )
        } catch (e: Exception) {
            Log.e("MLDataCollector", "Retention cleanup scheduling failed", e)
        }
    }
}
