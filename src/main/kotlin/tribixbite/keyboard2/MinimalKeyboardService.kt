package tribixbite.keyboard2

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.widget.TextView
import android.graphics.Color

/**
 * Minimal keyboard service for testing basic InputMethodService functionality
 * This strips out all complex features to test if basic keyboard display works
 */
class MinimalKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "MinimalKeyboard"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 MinimalKeyboardService created")
    }

    override fun onStartInput(editorInfo: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        Log.d(TAG, "📝 Input started: package=${editorInfo?.packageName}, restarting=$restarting")

        // CRITICAL FIX: Create and set input view manually
        createAndSetKeyboardView()
    }

    private fun createAndSetKeyboardView() {
        Log.d(TAG, "🎨 Creating minimal keyboard view")

        // Create the simplest possible keyboard view with explicit dimensions
        val view = TextView(this).apply {
            text = "CLEVERKEYS MINIMAL TEST KEYBOARD - VISIBLE!"
            setBackgroundColor(Color.MAGENTA)
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(20, 20, 20, 20)

            // CRITICAL: Set explicit layout parameters with fixed height
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                400 // 400px fixed height
            )
        }

        // CRITICAL: Use setInputView() like Unexpected Keyboard, not onCreateInputView()
        setInputView(view)
        Log.d(TAG, "✅ Keyboard view set with setInputView() - should be visible!")
    }

    // Removed duplicate onStartInput method

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "📝 Input finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔥 MinimalKeyboardService destroyed")
    }
}