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

    override fun onCreateInputView(): View {
        Log.d(TAG, "🎨 Creating minimal input view")

        // Create the simplest possible keyboard view
        val view = TextView(this).apply {
            text = "CLEVERKEYS MINIMAL TEST KEYBOARD"
            setBackgroundColor(Color.MAGENTA)
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(20, 20, 20, 20)
            height = 200 // Fixed height
        }

        Log.d(TAG, "✅ Minimal input view created")
        return view
    }

    override fun onStartInput(editorInfo: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        Log.d(TAG, "📝 Input started: package=${editorInfo?.packageName}, restarting=$restarting")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "📝 Input finished")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔥 MinimalKeyboardService destroyed")
    }
}