package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.WindowManager

/**
 * Helper class for keyboard dimension calculation.
 *
 * Responsibility: dynamic keyboard height calculation (orientation/foldable-aware) from
 * the user's height preferences.
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
// TODO: calculateDynamicKeyboardHeight and getUserKeyboardHeightPercent have no production
//  caller — CleverKeysService's private wrappers of the same names are themselves never called
//  (found 2026-10-21 while fixing audit L8). The keyboard height the user sees comes from
//  Config.refresh (KeyboardHeightPrefs.percent → Theme). Delete this class and its graph/propagator
//  edges (KeyboardComponentGraph, ConfigPropagator) the way ARC-084/ARC-099 removed its other halves.
class KeyboardDimensionsHelper(
    private val _context: Context,
    private var _config: Config,
    private val foldStateTracker: FoldStateTracker
) {
    private var _keyboardView: Keyboard2View? = null // Updated when view changes

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
            // Reuse the service-owned listener, which CleanupHandler closes on teardown.
            val foldableUnfolded = foldStateTracker.isUnfolded()

            // Check orientation
            val isLandscape = _context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            // Get user height preference (the one rule shared with Config.refresh)
            val prefs = DirectBootAwarePreferences.get_shared_preferences(_context)
            val keyboardHeightPref = KeyboardHeightPrefs.percent(prefs, isLandscape, foldableUnfolded)

            // Calculate dynamic height
            val keyboardHeightPercent = keyboardHeightPref / 100.0f
            metrics.heightPixels * keyboardHeightPercent
        } catch (e: Exception) {
            // Fallback to view height if available
            Log.w(TAG, "Keyboard height calculation failed; using the view height", e)
            _keyboardView?.height?.toFloat() ?: 0f
        }
    }

    /**
     * Get user keyboard height percentage for logging.
     *
     * @return User's keyboard height preference as percentage
     */
    fun getUserKeyboardHeightPercent(): Int {
        val isLandscape = _context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return try {
            // Reuse the service-owned listener, which CleanupHandler closes on teardown.
            val foldableUnfolded = foldStateTracker.isUnfolded()
            val prefs = DirectBootAwarePreferences.get_shared_preferences(_context)
            KeyboardHeightPrefs.percent(prefs, isLandscape, foldableUnfolded)
        } catch (e: Exception) {
            // The default of the CURRENT orientation (it used to be the portrait default even in
            // landscape — audit L8).
            Log.w(TAG, "Keyboard height preference unreadable; using the default", e)
            KeyboardHeightPrefs.default(isLandscape)
        }
    }

    private companion object {
        const val TAG = "KeyboardDimensionsHelper"
    }

    // ARC-084 (2026-08-29): updateCGRPredictions/checkCGRPredictions polled
    // Keyboard2View for CGR results here. Their producer (`storeCGRPredictions`) had no
    // callers, so the polled list was permanently empty and both methods were dead —
    // reachable only through two equally dead CleverKeysService hatches. Deleted with the
    // rest of the chain; pinned by DeadPlumbingDriftTest.

    // ARC-099 (2026-08-30): updateSwipePredictions/completeSwipePredictions/
    // clearSwipePredictions ("legacy methods") pushed a caller-supplied list straight into
    // _suggestionBar. Nothing called them directly and their three CleverKeysService
    // pass-throughs had no callers either, so no list ever reached the bar this way — the
    // live route is InputCoordinator.handlePredictionResults. Same shape as the ARC-084 CGR
    // chain, and invisible to the build for the same reason: proguard-rules.pro keeps this
    // class with a blanket `{ *; }`. Deleted; pinned by DeadPlumbingDriftTest.
    //
    // Residual cleanup (audit L1, 2026-10-20): the write-only `_suggestionBar` /
    // `setSuggestionBar` left by ARC-099, and the debug-mode plumbing (`setDebugMode`,
    // `DebugLogger`, `sendDebugLog`) that had no caller inside this class, were deleted with
    // their SuggestionBarPropagator / DebugModePropagator edges. Pinned by DeadPlumbingDriftTest.
}
