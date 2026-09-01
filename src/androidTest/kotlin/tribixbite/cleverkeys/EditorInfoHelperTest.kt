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
 *
 * ARC-044: strengthened — each standard IME action's label is pinned to the
 * exact localized string from its mapped resource id (the contract of
 * actionLabelFor), instead of merely asserting a label exists.
 */
@RunWith(AndroidJUnit4::class)
class EditorInfoHelperTest {

    private val resources = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources

    /** Pin one standard action: label text, action id, and swap default. */
    private fun assertStandardAction(imeAction: Int, labelResId: Int) {
        val info = EditorInfo().apply { imeOptions = imeAction }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertEquals(
            "Action $imeAction must resolve to its mapped resource string",
            resources.getString(labelResId), result.actionLabel
        )
        assertEquals(
            "Action id must be the masked IME action",
            imeAction, result.actionId
        )
        assertTrue(
            "Standard action without NO_ENTER_ACTION must swap enter/action",
            result.swapEnterActionKey
        )
    }

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
        assertFalse("Custom-label path must never swap enter/action", result.swapEnterActionKey)
    }

    @Test
    fun extractActionInfoCustomLabelWinsOverImeOptions() {
        // actionLabel takes precedence: imeOptions must be ignored entirely
        val info = EditorInfo().apply {
            actionLabel = "Custom"
            actionId = 7
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertEquals("Custom label must win over imeOptions", "Custom", result.actionLabel)
        assertEquals("actionId field must win over the masked action", 7, result.actionId)
    }

    // =========================================================================
    // extractActionInfo — standard IME actions
    // =========================================================================

    @Test
    fun extractActionInfoDone() {
        assertStandardAction(EditorInfo.IME_ACTION_DONE, R.string.key_action_done)
    }

    @Test
    fun extractActionInfoSearch() {
        assertStandardAction(EditorInfo.IME_ACTION_SEARCH, R.string.key_action_search)
    }

    @Test
    fun extractActionInfoSend() {
        assertStandardAction(EditorInfo.IME_ACTION_SEND, R.string.key_action_send)
    }

    @Test
    fun extractActionInfoGo() {
        assertStandardAction(EditorInfo.IME_ACTION_GO, R.string.key_action_go)
    }

    @Test
    fun extractActionInfoNext() {
        assertStandardAction(EditorInfo.IME_ACTION_NEXT, R.string.key_action_next)
    }

    @Test
    fun extractActionInfoPrevious() {
        assertStandardAction(EditorInfo.IME_ACTION_PREVIOUS, R.string.key_action_prev)
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
        assertNull("IME_ACTION_NONE must produce no label", result.actionLabel)
        assertEquals(
            "Action id must still carry the masked NONE action",
            EditorInfo.IME_ACTION_NONE, result.actionId
        )
    }

    @Test
    fun extractActionInfoUnspecified() {
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertNull("IME_ACTION_UNSPECIFIED must produce no label", result.actionLabel)
        assertEquals(
            "Action id must still carry the masked UNSPECIFIED action",
            EditorInfo.IME_ACTION_UNSPECIFIED, result.actionId
        )
    }

    @Test
    fun extractActionInfoMasksNonActionBits() {
        // Flag bits outside IME_MASK_ACTION must not leak into actionId
        val info = EditorInfo().apply {
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val result = EditorInfoHelper.extractActionInfo(info, resources)
        assertEquals(
            "actionId must be masked to the action bits only",
            EditorInfo.IME_ACTION_DONE, result.actionId
        )
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
        assertEquals(
            "NO_ENTER_ACTION must not change the extracted label",
            resources.getString(R.string.key_action_done), result.actionLabel
        )
    }

    // =========================================================================
    // actionLabelFor
    // =========================================================================

    @Test
    fun actionLabelForDoneReturnsString() {
        val label = EditorInfoHelper.actionLabelFor(EditorInfo.IME_ACTION_DONE, resources)
        assertEquals(
            "Done label must be the key_action_done resource string",
            resources.getString(R.string.key_action_done), label
        )
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
            assertNotNull("Action $action should have a resource ID", resId)
            // The two lookups must agree: the label IS the resolved resource
            assertEquals(
                "Action $action label must equal its resource string",
                resources.getString(resId!!), label
            )
        }
    }
}
