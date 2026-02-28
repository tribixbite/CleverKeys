package tribixbite.cleverkeys

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for SuggestionBar autofill/password mode padding and visibility.
 *
 * Regression test for #109: autofill chips were cut off because SuggestionBar's
 * 8dp padding consumed 16dp of the 40dp container, leaving only 24dp for content.
 * Fix: remove padding in password/autofill mode, restore when clearing.
 *
 * These tests require a real Android context (SuggestionBar extends LinearLayout).
 */
@RunWith(AndroidJUnit4::class)
class SuggestionBarAutofillTest {

    private lateinit var context: Context
    private lateinit var suggestionBar: SuggestionBar

    // 8dp in pixels for the test device
    private var paddingPx = 0

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Must create views on main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            suggestionBar = SuggestionBar(context)
        }
        paddingPx = dpToPx(context, 8)
    }

    // ==================== Normal Mode Padding ====================

    @Test
    fun normalMode_hasPadding() {
        // SuggestionBar should have 8dp padding by default
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals("Top padding should be 8dp", paddingPx, suggestionBar.paddingTop)
            assertEquals("Bottom padding should be 8dp", paddingPx, suggestionBar.paddingBottom)
            assertEquals("Left padding should be 8dp", paddingPx, suggestionBar.paddingLeft)
            assertEquals("Right padding should be 8dp", paddingPx, suggestionBar.paddingRight)
        }
    }

    // ==================== Password Mode Padding ====================

    @Test
    fun passwordMode_removesPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Enter password mode
            suggestionBar.setPasswordMode(true)

            // Padding should be zero for full bar height
            assertEquals("Top padding should be 0 in password mode", 0, suggestionBar.paddingTop)
            assertEquals("Bottom padding should be 0 in password mode", 0, suggestionBar.paddingBottom)
        }
    }

    @Test
    fun passwordMode_exitRestoresPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Enter then exit password mode
            suggestionBar.setPasswordMode(true)
            suggestionBar.setPasswordMode(false)

            // Padding should be restored
            assertEquals("Top padding should be restored", paddingPx, suggestionBar.paddingTop)
            assertEquals("Bottom padding should be restored", paddingPx, suggestionBar.paddingBottom)
        }
    }

    // ==================== Autofill Mode Padding ====================

    @Test
    fun autofillView_removesPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Set an inline autofill view (use a simple FrameLayout as mock)
            val mockAutofillView = FrameLayout(context)
            suggestionBar.setInlineAutofillView(mockAutofillView)

            // Padding should be zero for full chip display
            assertEquals("Top padding should be 0 in autofill mode", 0, suggestionBar.paddingTop)
            assertEquals("Bottom padding should be 0 in autofill mode", 0, suggestionBar.paddingBottom)
            assertEquals("Left padding should be 0 in autofill mode", 0, suggestionBar.paddingLeft)
            assertEquals("Right padding should be 0 in autofill mode", 0, suggestionBar.paddingRight)
        }
    }

    @Test
    fun autofillView_ensuresVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Simulate the bar being hidden (empty suggestions could set GONE)
            suggestionBar.visibility = View.GONE

            // Set autofill view
            val mockAutofillView = FrameLayout(context)
            suggestionBar.setInlineAutofillView(mockAutofillView)

            // Bar must be VISIBLE for autofill chips to show
            assertEquals("Bar should be VISIBLE when autofill is set",
                View.VISIBLE, suggestionBar.visibility)
        }
    }

    @Test
    fun autofillView_clearRestoresPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Set then clear autofill view
            val mockAutofillView = FrameLayout(context)
            suggestionBar.setInlineAutofillView(mockAutofillView)
            suggestionBar.setInlineAutofillView(null) // null clears

            // Padding should be restored
            assertEquals("Top padding should be restored after clear", paddingPx, suggestionBar.paddingTop)
            assertEquals("Bottom padding should be restored after clear", paddingPx, suggestionBar.paddingBottom)
        }
    }

    // ==================== Password + Autofill Interaction ====================

    @Test
    fun passwordThenAutofill_removesPadding() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Enter password mode first
            suggestionBar.setPasswordMode(true)
            assertEquals(0, suggestionBar.paddingTop)

            // Then set autofill view (this is the real-world flow)
            val mockAutofillView = FrameLayout(context)
            suggestionBar.setInlineAutofillView(mockAutofillView)

            // Padding should still be zero
            assertEquals("Padding should be 0 after password→autofill",
                0, suggestionBar.paddingTop)
            // Bar should be visible
            assertEquals(View.VISIBLE, suggestionBar.visibility)
        }
    }

    @Test
    fun autofillViewAdded_hasMatchParentLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val mockAutofillView = FrameLayout(context)
            suggestionBar.setInlineAutofillView(mockAutofillView)

            // Autofill view should be a child of the suggestion bar
            assertEquals("Autofill view should be added as child",
                1, suggestionBar.childCount)

            // Verify layout params are MATCH_PARENT
            val params = mockAutofillView.layoutParams as LinearLayout.LayoutParams
            assertEquals("Width should be MATCH_PARENT",
                LinearLayout.LayoutParams.MATCH_PARENT, params.width)
            assertEquals("Height should be MATCH_PARENT",
                LinearLayout.LayoutParams.MATCH_PARENT, params.height)
        }
    }

    // ==================== Password Field Detection ====================

    @Test
    fun isPasswordField_detectsTextPassword() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertTrue("TYPE_TEXT_VARIATION_PASSWORD should be detected",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_detectsWebPassword() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        assertTrue("TYPE_TEXT_VARIATION_WEB_PASSWORD should be detected",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_detectsVisiblePassword() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        assertTrue("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD should be detected",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_detectsNumericPin() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        assertTrue("TYPE_NUMBER_VARIATION_PASSWORD should be detected",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_rejectsNormalText() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        assertFalse("Normal text should not be detected as password",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_rejectsEmail() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        assertFalse("Email should not be detected as password",
            SuggestionBar.isPasswordField(info))
    }

    @Test
    fun isPasswordField_rejectsNull() {
        assertFalse("Null EditorInfo should not be password",
            SuggestionBar.isPasswordField(null))
    }

    // ==================== Utilities ====================

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
