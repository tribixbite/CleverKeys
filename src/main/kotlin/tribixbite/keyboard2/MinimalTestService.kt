package tribixbite.keyboard2

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View

/**
 * Minimal test service to verify basic InputMethodService can instantiate.
 * This has ZERO dependencies, interfaces, or complex initialization.
 * If this doesn't work, the problem is deeper than our code.
 */
class MinimalTestService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        Log.d("MinimalTest", "✅ MinimalTestService onCreate() SUCCESS!")
    }

    override fun onCreateInputView(): View? {
        Log.d("MinimalTest", "✅ onCreateInputView() called")
        return null // Return null to avoid needing a view
    }

    override fun onDestroy() {
        Log.d("MinimalTest", "onDestroy() called")
        super.onDestroy()
    }
}
