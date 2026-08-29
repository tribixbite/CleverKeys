package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.WindowManager

/**
 * Helper class for keyboard dimension calculation.
 *
 * This class centralizes logic for:
 * - Calculating dynamic keyboard dimensions based on user preferences
 * - Updating suggestion bar with swipe predictions (legacy methods)
 *
 * Responsibilities:
 * - Dynamic keyboard height calculation (orientation/foldable-aware)
 * - Legacy swipe prediction display methods
 *
 * The CGR (Continuous Gesture Recognition) prediction-display half was deleted on
 * 2026-08-29 (ARC-084): its producer in Keyboard2View had never had a caller, so the two
 * polling methods here could only ever read an empty list.
 *
 * The neural-engine half (reflection-based key-position extraction, QWERTY-bounds and
 * touch-Y-offset configuration) was deleted with that engine on 2026-08-18. CTC and
 * geometric both read key geometry from `Keyboard2View.geometryParams()` directly and
 * never needed this class.
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - View creation and management
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.362). Renamed from `NeuralLayoutHelper` on 2026-08-18 when its
 * neural half was deleted; the `NeuralLayoutBridge` that used to sit between it and
 * CleverKeysService went at the same time (pure delegation, no logic).
 */
class KeyboardDimensionsHelper(
    private val _context: Context,
    private var _config: Config
) {
    private var _keyboardView: Keyboard2View? = null // Updated when view changes
    private var _suggestionBar: SuggestionBar? = null // Updated when suggestion bar changes

    // Debug mode
    private var _debugMode = false
    private var _debugLogger: DebugLogger? = null

    /**
     * Interface for sending debug logs.
     * Implemented by CleverKeysService to bridge to its sendDebugLog method.
     */
    fun interface DebugLogger {
        fun sendDebugLog(message: String)
    }

    /**
     * Updates configuration.
     *
     * @param newConfig Updated configuration
     */
    fun setConfig(newConfig: Config) {
        _config = newConfig
    }

    /**
     * Sets the keyboard view reference.
     *
     * @param keyboardView Keyboard view for dimension and layout access
     */
    fun setKeyboardView(keyboardView: Keyboard2View?) {
        _keyboardView = keyboardView
    }

    /**
     * Sets the suggestion bar reference.
     *
     * @param suggestionBar Suggestion bar for displaying predictions
     */
    fun setSuggestionBar(suggestionBar: SuggestionBar?) {
        _suggestionBar = suggestionBar
    }

    /**
     * Sets debug mode and logger.
     *
     * @param enabled Whether debug mode is enabled
     * @param logger Debug logger implementation
     */
    fun setDebugMode(enabled: Boolean, logger: DebugLogger?) {
        _debugMode = enabled
        _debugLogger = logger
    }

    /**
     * Sends a debug log message if debug mode is enabled.
     */
    private fun sendDebugLog(message: String) {
        if (_debugMode) {
            _debugLogger?.sendDebugLog(message)
        }
    }

    /**
     * Calculate dynamic keyboard height based on user settings (like calibration page).
     * Supports orientation, foldable devices, and user height preferences.
     *
     * @return Calculated keyboard height in pixels
     */
    fun calculateDynamicKeyboardHeight(): Float {
        return try {
            // Get screen dimensions
            val metrics = android.util.DisplayMetrics()
            val wm = _context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getMetrics(metrics)

            // Check foldable state
            val foldTracker = FoldStateTracker(_context)
            val foldableUnfolded = foldTracker.isUnfolded()

            // Check orientation
            val isLandscape = _context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // Get user height preference (same logic as calibration)
            val prefs = DirectBootAwarePreferences.get_shared_preferences(_context)
            val key = if (isLandscape) {
                if (foldableUnfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape"
            } else {
                if (foldableUnfolded) "keyboard_height_unfolded" else "keyboard_height"
            }
            val keyboardHeightPref = prefs.getInt(key, if (isLandscape) 50 else 35)

            // Calculate dynamic height
            val keyboardHeightPercent = keyboardHeightPref / 100.0f
            metrics.heightPixels * keyboardHeightPercent
        } catch (e: Exception) {
            // Fallback to view height if available
            _keyboardView?.height?.toFloat() ?: 0f
        }
    }

    /**
     * Get user keyboard height percentage for logging.
     *
     * @return User's keyboard height preference as percentage
     */
    fun getUserKeyboardHeightPercent(): Int {
        return try {
            val foldTracker = FoldStateTracker(_context)
            val foldableUnfolded = foldTracker.isUnfolded()
            val isLandscape = _context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val prefs = DirectBootAwarePreferences.get_shared_preferences(_context)

            val key = if (isLandscape) {
                if (foldableUnfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape"
            } else {
                if (foldableUnfolded) "keyboard_height_unfolded" else "keyboard_height"
            }
            prefs.getInt(key, if (isLandscape) 50 else 35)
        } catch (e: Exception) {
            35 // Default
        }
    }

    // ARC-084 (2026-08-29): updateCGRPredictions/checkCGRPredictions polled
    // Keyboard2View for CGR results here. Their producer (`storeCGRPredictions`) had no
    // callers, so the polled list was permanently empty and both methods were dead —
    // reachable only through two equally dead CleverKeysService hatches. Deleted with the
    // rest of the chain; pinned by DeadPlumbingDriftTest.

    /**
     * Update swipe predictions in real-time during gesture (legacy method).
     *
     * @param predictions List of prediction strings
     */
    fun updateSwipePredictions(predictions: List<String>?) {
        if (_suggestionBar != null && predictions != null && predictions.isNotEmpty()) {
            _suggestionBar!!.setSuggestions(predictions)
        }
    }

    /**
     * Complete swipe predictions after gesture ends (legacy method).
     *
     * @param finalPredictions Final list of prediction strings
     */
    fun completeSwipePredictions(finalPredictions: List<String>?) {
        if (_suggestionBar != null && finalPredictions != null && finalPredictions.isNotEmpty()) {
            _suggestionBar!!.setSuggestions(finalPredictions)
        }
    }

    /**
     * Clear swipe predictions (legacy method).
     */
    fun clearSwipePredictions() {
        _suggestionBar?.setSuggestions(emptyList())
    }

    companion object {
        private const val TAG = "KbdDimensionsHelper"
    }
}
