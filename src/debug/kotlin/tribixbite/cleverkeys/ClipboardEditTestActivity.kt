package tribixbite.cleverkeys

import android.app.Activity
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * Minimal test-only Activity that hosts a ClipboardHistoryView for instrumented testing.
 *
 * Provides a real window/layout lifecycle so the ListView performs actual layout passes,
 * calls getView() through the framework (not manually), and handles view recycling.
 * This is critical for reproducing bugs that only manifest through the real adapter lifecycle.
 *
 * Only exists in the androidTest APK — not included in release builds.
 */
class ClipboardEditTestActivity : Activity() {

    lateinit var container: FrameLayout
    var clipboardHistoryView: ClipboardHistoryView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply keyboard theme before super.onCreate so attrs resolve correctly
        setTheme(R.style.Dark)
        super.onCreate(savedInstanceState)

        val themed = ContextThemeWrapper(this, R.style.Dark)

        // ScrollView → FrameLayout → ClipboardHistoryView (matches production layout)
        val scrollView = ScrollView(themed).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        container = FrameLayout(themed).apply {
            id = android.R.id.content
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }
}
