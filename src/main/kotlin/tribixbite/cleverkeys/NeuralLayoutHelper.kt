package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Log
import android.view.WindowManager

/**
 * Helper class for neural engine layout configuration and CGR prediction display.
 *
 * This class centralizes logic for:
 * - Calculating dynamic keyboard dimensions based on user preferences
 * - Extracting key positions from keyboard layout for neural engine
 * - Setting up neural engine with real key positions
 * - Managing CGR (Continuous Gesture Recognition) prediction display
 * - Updating suggestion bar with swipe predictions (legacy methods)
 *
 * Responsibilities:
 * - Dynamic keyboard height calculation (orientation/foldable-aware)
 * - Key position extraction via reflection on Keyboard2View
 * - Neural engine configuration with accurate key positions
 * - CGR prediction integration with suggestion bar
 * - Legacy swipe prediction display methods
 *
 * NOT included (remains in CleverKeysService):
 * - InputMethodService lifecycle methods
 * - View creation and management
 * - Configuration management
 *
 * This class is extracted from CleverKeysService.java for better separation of concerns
 * and testability (v1.32.362).
 */
class NeuralLayoutHelper(
    private val _context: Context,
    private var _config: Config,
    private val _predictionCoordinator: PredictionCoordinator
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

    /**
     * Update swipe predictions by checking keyboard view for CGR results.
     */
    fun updateCGRPredictions() {
        if (_suggestionBar != null && _keyboardView != null) {
            val cgrPredictions = _keyboardView!!.getCGRPredictions()
            if (cgrPredictions.isNotEmpty()) {
                _suggestionBar!!.setSuggestions(cgrPredictions)
            }
        }
    }

    /**
     * Check and update CGR predictions (call this periodically or on swipe events).
     */
    fun checkCGRPredictions() {
        if (_keyboardView != null && _suggestionBar != null) {
            // Enable always visible mode to prevent UI flickering
            _suggestionBar!!.setAlwaysVisible(true)

            val cgrPredictions = _keyboardView!!.getCGRPredictions()
            val areFinal = _keyboardView!!.areCGRPredictionsFinal()

            if (cgrPredictions.isNotEmpty()) {
                _suggestionBar!!.setSuggestions(cgrPredictions)
            } else {
                // Show empty suggestions but keep bar visible
                _suggestionBar!!.setSuggestions(emptyList())
            }
        }
    }

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

    /**
     * Extract key positions from keyboard layout and set them on neural engine.
     * CRITICAL for neural swipe typing - without this, key detection fails completely!
     */
    fun setNeuralKeyboardLayout() {
        if (_predictionCoordinator.getNeuralEngine() == null || _keyboardView == null) {
            Log.w(TAG, "Cannot set neural layout - engine or view is null")
            return
        }

        val keyPositions = extractKeyPositionsFromLayout()

        if (keyPositions != null && keyPositions.isNotEmpty()) {
            _predictionCoordinator.getNeuralEngine()!!.setRealKeyPositions(keyPositions)
            Log.d(TAG, "Set ${keyPositions.size} key positions on neural engine")

            // Calculate QWERTY area bounds from key positions
            // Use q (top row) and m (bottom row) to determine the vertical extent
            if (keyPositions.containsKey('q') && keyPositions.containsKey('m')) {
                val qPos = keyPositions['q']!! // Top row center
                val mPos = keyPositions['m']!! // Bottom row center

                // Estimate row height from the distance between rows
                // q is in row 0, a is in row 1, z/m are in row 2
                val aPos = keyPositions.getOrDefault('a', qPos)
                val rowHeight = (mPos.y - qPos.y) / 2.0f // Approximate row height

                // QWERTY bounds: from top of first row to bottom of last row
                // qwertyTop = q.y - rowHeight/2 (top edge of row 0)
                // qwertyHeight = 3 * rowHeight (all 3 rows)
                var qwertyTop = qPos.y - rowHeight / 2.0f
                var qwertyHeight = 3.0f * rowHeight

                // Ensure qwertyTop is non-negative
                if (qwertyTop < 0) {
                    qwertyHeight += qwertyTop // Reduce height by the negative amount
                    qwertyTop = 0f
                }

                // Set bounds on neural engine
                _predictionCoordinator.getNeuralEngine()!!.setQwertyAreaBounds(qwertyTop, qwertyHeight)
                Log.d(
                    TAG,
                    String.format(
                        "Set QWERTY bounds: top=%.0f, height=%.0f (q.y=%.0f, m.y=%.0f)",
                        qwertyTop, qwertyHeight, qPos.y, mPos.y
                    )
                )

                // Touch Y-offset for fat finger compensation
                // RE-ENABLED (v1.32.943): Conservative 12.5% offset for better tap target prediction
                // Previous 37% was too aggressive, 0% had no compensation - 12.5% is balanced
                val touchYOffset = rowHeight * 0.125f // Conservative value within recommended 10-15% range
                _predictionCoordinator.getNeuralEngine()!!.setTouchYOffset(touchYOffset)
                Log.d(
                    TAG,
                    String.format(
                        "Touch Y-offset: %.0f pixels (12.5%% of row height %.0f)",
                        touchYOffset, rowHeight
                    )
                )

                // Debug output only when debug mode is active
                if (_debugMode) {
                    sendDebugLog(String.format(">>> Neural engine: %d key positions set\n", keyPositions.size))
                    sendDebugLog(String.format(">>> QWERTY bounds: top=%.0f, height=%.0f\n", qwertyTop, qwertyHeight))
                    sendDebugLog(String.format(">>> Touch Y-offset: %.0f px (fat finger compensation)\n", touchYOffset))
                    val zPos = keyPositions.getOrDefault('z', mPos)
                    sendDebugLog(
                        String.format(
                            ">>> Samples: q=(%.0f,%.0f) a=(%.0f,%.0f) z=(%.0f,%.0f)\n",
                            qPos.x, qPos.y, aPos.x, aPos.y, zPos.x, zPos.y
                        )
                    )
                }
            } else {
                Log.w(TAG, "Cannot calculate QWERTY bounds - missing q or m key positions")
                // Debug output only when debug mode is active
                if (_debugMode) {
                    sendDebugLog(String.format(">>> Neural engine: %d key positions set\n", keyPositions.size))
                }
            }
        } else {
            Log.e(TAG, "Failed to extract key positions from layout")
        }
    }

    /**
     * Extract character key positions from the keyboard layout using reflection.
     * Returns a map of character -> center point (in pixels), or null on error.
     *
     * @return Map of character to center point, or null on error
     */
    private fun extractKeyPositionsFromLayout(): Map<Char, PointF>? {
        if (_keyboardView == null) {
            Log.w(TAG, "Cannot extract key positions - keyboardView is null")
            return null
        }

        return try {
            // Use reflection to access keyboard data from view
            val keyboardField = _keyboardView!!.javaClass.getDeclaredField("_keyboard")
            keyboardField.isAccessible = true
            val keyboard = keyboardField.get(_keyboardView) as? KeyboardData

            if (keyboard == null) {
                Log.w(TAG, "Keyboard data is null after reflection")
                return null
            }

            // Get view dimensions
            val keyboardWidth = _keyboardView!!.width.toFloat()
            val keyboardHeight = _keyboardView!!.height.toFloat()

            if (keyboardWidth == 0f || keyboardHeight == 0f) {
                Log.w(TAG, "Keyboard dimensions are zero")
                return null
            }

            // Calculate scale factors (layout units -> pixels)
            val scaleX = keyboardWidth / keyboard.keysWidth
            val scaleY = keyboardHeight / keyboard.keysHeight

            // Extract center positions of all character keys
            val keyPositions = mutableMapOf<Char, PointF>()
            var currentY = 0f

            for (row in keyboard.rows) {
                currentY += row.shift * scaleY
                val centerY = currentY + (row.height * scaleY / 2.0f)
                var currentX = 0f

                for (key in row.keys) {
                    currentX += key.shift * scaleX

                    // Only process character keys
                    val kv = key.keys?.getOrNull(0)
                    if (kv != null) {
                        if (kv.getKind() == KeyValue.Kind.Char) {
                            val c = kv.getChar()
                            val centerX = currentX + (key.width * scaleX / 2.0f)
                            keyPositions[c] = PointF(centerX, centerY)
                        }
                    }

                    currentX += key.width * scaleX
                }

                currentY += row.height * scaleY
            }

            keyPositions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract key positions", e)
            null
        }
    }

    companion object {
        private const val TAG = "NeuralLayoutHelper"
    }
}
