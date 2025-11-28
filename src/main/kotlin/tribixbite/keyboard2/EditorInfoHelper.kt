package tribixbite.keyboard2

import android.content.res.Resources
import android.view.inputmethod.EditorInfo

/**
 * Utility class for extracting information from EditorInfo.
 *
 * This class centralizes logic for:
 * - Extracting action labels from IME options
 * - Mapping IME actions to string resources
 * - Determining Enter/Action key swap behavior
 *
 * Responsibilities:
 * - Parse EditorInfo.actionLabel and EditorInfo.imeOptions
 * - Map IME action constants to display strings
 * - Extract action ID and swap behavior flags
 *
 * NOT included (remains in CleverKeysService):
 * - Config object modification
 * - Keyboard view updates
 *
 * This utility is extracted from CleverKeysService.java for better code organization
 * and testability (v1.32.379).
 *
 * @since v1.32.379
 */
object EditorInfoHelper {

    /**
     * Data class holding extracted action information from EditorInfo.
     *
     * @property actionLabel The display label for the action key (may be null)
     * @property actionId The IME action ID to perform
     * @property swapEnterActionKey Whether to swap Enter and Action keys
     */
    data class EditorActionInfo(
        val actionLabel: String?,
        val actionId: Int,
        val swapEnterActionKey: Boolean
    )

    /**
     * Extract action information from EditorInfo.
     *
     * This method:
     * 1. First checks info.actionLabel for custom action label
     * 2. Falls back to info.imeOptions for standard IME actions
     * 3. Maps IME actions to string resources
     * 4. Determines Enter/Action key swap behavior
     *
     * @param info The EditorInfo from the input field
     * @param resources Android Resources for string lookup
     * @return EditorActionInfo containing action label, ID, and swap flag
     */
    @JvmStatic
    fun extractActionInfo(info: EditorInfo, resources: Resources): EditorActionInfo {
        // First try to look at 'info.actionLabel', if it isn't set, look at 'imeOptions'
        if (info.actionLabel != null) {
            return EditorActionInfo(
                actionLabel = info.actionLabel.toString(),
                actionId = info.actionId,
                swapEnterActionKey = false
            )
        } else {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val actionLabel = actionLabelFor(action, resources)
            val swapEnterActionKey =
                (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0

            return EditorActionInfo(
                actionLabel = actionLabel,
                actionId = action,
                swapEnterActionKey = swapEnterActionKey
            )
        }
    }

    /**
     * Get the action label string for an IME action constant.
     *
     * Maps IME action constants to their corresponding string resources:
     * - IME_ACTION_NEXT → "Next"
     * - IME_ACTION_DONE → "Done"
     * - IME_ACTION_GO → "Go"
     * - IME_ACTION_PREVIOUS → "Previous"
     * - IME_ACTION_SEARCH → "Search"
     * - IME_ACTION_SEND → "Send"
     * - Other actions → null
     *
     * @param action The IME action constant (e.g., EditorInfo.IME_ACTION_DONE)
     * @param resources Android Resources for string lookup
     * @return The localized action label string, or null if no label is appropriate
     */
    @JvmStatic
    fun actionLabelFor(action: Int, resources: Resources): String? {
        val resId = when (action) {
            EditorInfo.IME_ACTION_NEXT -> R.string.key_action_next
            EditorInfo.IME_ACTION_DONE -> R.string.key_action_done
            EditorInfo.IME_ACTION_GO -> R.string.key_action_go
            EditorInfo.IME_ACTION_PREVIOUS -> R.string.key_action_prev
            EditorInfo.IME_ACTION_SEARCH -> R.string.key_action_search
            EditorInfo.IME_ACTION_SEND -> R.string.key_action_send
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE -> return null
            else -> return null
        }
        return resources.getString(resId)
    }

    /**
     * Get the string resource ID for an IME action constant.
     *
     * Similar to actionLabelFor(), but returns the resource ID instead of the string.
     * Useful for testing or when you need the resource ID directly.
     *
     * @param action The IME action constant
     * @return The string resource ID, or null if no label is appropriate
     */
    @JvmStatic
    fun actionResourceIdFor(action: Int): Int? {
        return when (action) {
            EditorInfo.IME_ACTION_NEXT -> R.string.key_action_next
            EditorInfo.IME_ACTION_DONE -> R.string.key_action_done
            EditorInfo.IME_ACTION_GO -> R.string.key_action_go
            EditorInfo.IME_ACTION_PREVIOUS -> R.string.key_action_prev
            EditorInfo.IME_ACTION_SEARCH -> R.string.key_action_search
            EditorInfo.IME_ACTION_SEND -> R.string.key_action_send
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE -> null
            else -> null
        }
    }
}
