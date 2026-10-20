package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
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
     * @param enterAlwaysNewline The #139 "Enter always inserts a new line" preference; see
     *   [shouldSwapEnterActionKey] for exactly which fields it affects.
     * @return EditorActionInfo containing action label, ID, and swap flag
     */
    @JvmStatic
    @JvmOverloads
    fun extractActionInfo(
        info: EditorInfo,
        resources: Resources,
        enterAlwaysNewline: Boolean = false
    ): EditorActionInfo {
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
                shouldSwapEnterActionKey(info.imeOptions, info.inputType, enterAlwaysNewline)

            return EditorActionInfo(
                actionLabel = actionLabel,
                actionId = action,
                swapEnterActionKey = swapEnterActionKey
            )
        }
    }

    /**
     * Decide whether the Enter key shows the editor action (Send, Search, Go, ...) instead of
     * Enter. When it does, LayoutModifier puts the action on the Enter position and Enter on
     * the `action` subkey; when it does not, Enter stays a newline key and the action stays
     * reachable on the Enter key's `action` swipe.
     *
     * - The editor opts out with `IME_FLAG_NO_ENTER_ACTION`: never swap (unchanged behaviour).
     * - [enterAlwaysNewline] (#139) is ON and the field is multi-line ([acceptsNewlines]):
     *   do not swap, so Enter inserts a newline in chat composers such as WhatsApp's that ask
     *   for Send. The action is one swipe away.
     * - [enterAlwaysNewline] is ON but the field is single-line (a search box, a URL bar, a
     *   login field): still swap. Such a field cannot hold a newline — TextView turns an Enter
     *   key event in a single-line field into its editor action anyway, or drops it — so
     *   forcing Enter there would only remove the visible Search/Go label and make the key's
     *   result less predictable, not produce a newline. Blind fields (`TYPE_NULL`, e.g.
     *   terminals) are not TYPE_CLASS_TEXT and are likewise unaffected by the preference.
     *
     * Pure function of the EditorInfo bits so it is unit-testable without Android
     * (`EditorInfoHelperEnterSwapTest`).
     *
     * @param imeOptions `EditorInfo.imeOptions` of the focused field
     * @param inputType `EditorInfo.inputType` of the focused field
     * @param enterAlwaysNewline the user's #139 preference
     * @return true to show the editor action on the Enter key
     */
    @JvmStatic
    fun shouldSwapEnterActionKey(imeOptions: Int, inputType: Int, enterAlwaysNewline: Boolean): Boolean {
        val editorWantsAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        if (!editorWantsAction) return false
        if (enterAlwaysNewline && acceptsNewlines(inputType)) return false
        return true
    }

    /**
     * True when the field itself accepts newline characters: a text-class field with
     * `TYPE_TEXT_FLAG_MULTI_LINE`. `TYPE_TEXT_FLAG_IME_MULTI_LINE` alone does NOT count — per
     * the platform contract it marks a single-line field whose full-screen extract editor
     * may wrap, so the field still rejects a newline.
     */
    @JvmStatic
    fun acceptsNewlines(inputType: Int): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

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
