package tribixbite.cleverkeys

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for EditorInfoHelper.
 * Covers action extraction from EditorInfo, action label mapping,
 * action resource ID mapping, and enter/action key swap logic.
 */
@RunWith(AndroidJUnit4::class)
class EditorInfoHelperTest {

    private val resources = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources

    // =========================================================================
    // extractActionInfo — custom action label
    // =========================================================================

    @Test
    fun extractActionInfoCustomLabel() {
        val info = EditorInfo().apply {
            actionLabel = "Submit"
            actionId = 42
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertEquals("Submit", result.actionLabel)
        assertEquals(42, result.actionId)
        assertFalse(result.swapEnterActionKey)
    }

    // =========================================================================
    // extractActionInfo — standard IME actions
    // =========================================================================

    @Test
    fun extractActionInfoDone() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_DONE, result.actionId)
    }

    @Test
    fun extractActionInfoSearch() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, result.actionId)
    }

    @Test
    fun extractActionInfoSend() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_SEND, result.actionId)
    }

    @Test
    fun extractActionInfoGo() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_GO, result.actionId)
    }

    @Test
    fun extractActionInfoNext() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_NEXT, result.actionId)
    }

    @Test
    fun extractActionInfoPrevious() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_PREVIOUS
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNotNull(result.actionLabel)
        assertEquals(EditorInfo.IME_ACTION_PREVIOUS, result.actionId)
    }

    // =========================================================================
    // extractActionInfo — unspecified/none
    // =========================================================================

    @Test
    fun extractActionInfoNone() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_NONE
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNull(result.actionLabel)
    }

    @Test
    fun extractActionInfoUnspecified() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNull(result.actionLabel)
    }

    // =========================================================================
    // swapEnterActionKey flag
    // =========================================================================

    @Test
    fun swapEnterActionKeyTrueByDefault() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertTrue("Should swap when NO_ENTER_ACTION not set", result.swapEnterActionKey)
    }

    @Test
    fun swapEnterActionKeyFalseWithNoEnterFlag() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertFalse("Should not swap when NO_ENTER_ACTION set", result.swapEnterActionKey)
    }

    // =========================================================================
    // actionLabelFor
    // =========================================================================

    @Test
    fun actionLabelForDoneReturnsString() {
        val label = EditorInfoHelper.actionLabelFor(EditorInfo.IME_ACTION_DONE, resources)
        assertNotNull(label)
        assertTrue(label!!.isNotEmpty())
    }

    @Test
    fun actionLabelForNoneReturnsNull() {
        assertNull(EditorInfoHelper.actionLabelFor(EditorInfo.IME_ACTION_NONE, resources))
    }

    @Test
    fun actionLabelForUnknownReturnsNull() {
        assertNull(EditorInfoHelper.actionLabelFor(9999, resources))
    }

    // =========================================================================
    // actionResourceIdFor
    // =========================================================================

    @Test
    fun actionResourceIdForDoneReturnsId() {
        val resId = EditorInfoHelper.actionResourceIdFor(EditorInfo.IME_ACTION_DONE)
        assertNotNull(resId)
        assertEquals(R.string.key_action_done, resId)
    }

    @Test
    fun actionResourceIdForSearchReturnsId() {
        assertEquals(R.string.key_action_search,
            EditorInfoHelper.actionResourceIdFor(EditorInfo.IME_ACTION_SEARCH))
    }

    @Test
    fun actionResourceIdForSendReturnsId() {
        assertEquals(R.string.key_action_send,
            EditorInfoHelper.actionResourceIdFor(EditorInfo.IME_ACTION_SEND))
    }

    @Test
    fun actionResourceIdForNoneReturnsNull() {
        assertNull(EditorInfoHelper.actionResourceIdFor(EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun actionResourceIdForUnknownReturnsNull() {
        assertNull(EditorInfoHelper.actionResourceIdFor(9999))
    }

    // =========================================================================
    // All 6 actions have consistent label and resource ID
    // =========================================================================

    @Test
    fun allStandardActionsHaveBothLabelAndResourceId() {
        val actions = listOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND
        )
        for (action in actions) {
            val label = EditorInfoHelper.actionLabelFor(action, resources)
            val resId = EditorInfoHelper.actionResourceIdFor(action)
            assertNotNull("Action $action should have a label", label)
            assertNotNull("Action $action should have a resource ID", resId)
        }
    }
}
