package tribixbite.cleverkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * #139 option A: "Enter key always inserts a new line".
 *
 * Pins [EditorInfoHelper.shouldSwapEnterActionKey], the pure decision that puts the editor
 * action (Send/Search/Go…) on the Enter position. Pure JVM: the EditorInfo / InputType
 * constants are compile-time ints inlined from android.jar, nothing is instantiated.
 *
 * Contract:
 * - preference OFF → exactly the pre-#139 rule (swap unless IME_FLAG_NO_ENTER_ACTION);
 * - preference ON  → no swap in multi-line text fields (TYPE_TEXT_FLAG_MULTI_LINE), so Enter
 *   inserts a newline; single-line fields, IME_MULTI_LINE-only fields and non-text/blind
 *   (TYPE_NULL) fields keep the editor-driven swap, since they cannot hold a newline.
 */
class EditorInfoHelperEnterSwapTest {

    private val multiLineText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val chatComposer = multiLineText or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val singleLineSearch = InputType.TYPE_CLASS_TEXT
    private val imeMultiLineOnly = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
    private val sendAction = EditorInfo.IME_ACTION_SEND
    private val searchAction = EditorInfo.IME_ACTION_SEARCH

    private fun swap(imeOptions: Int, inputType: Int, pref: Boolean) =
        EditorInfoHelper.shouldSwapEnterActionKey(imeOptions, inputType, pref)

    @Test
    fun prefOff_keepsTheEditorDrivenRule() {
        for (type in listOf(chatComposer, singleLineSearch, imeMultiLineOnly, InputType.TYPE_NULL)) {
            assertWithMessage("OFF, action requested, inputType=$type → swap (pre-#139 behaviour)")
                .that(swap(sendAction, type, pref = false)).isTrue()
            assertWithMessage("OFF, IME_FLAG_NO_ENTER_ACTION, inputType=$type → no swap")
                .that(swap(sendAction or EditorInfo.IME_FLAG_NO_ENTER_ACTION, type, pref = false)).isFalse()
        }
    }

    @Test
    fun prefOn_multiLineField_entersANewlineInsteadOfTheAction() {
        assertWithMessage("WhatsApp-style composer asking for Send must keep Enter as newline")
            .that(swap(sendAction, chatComposer, pref = true)).isFalse()
        assertWithMessage("A multi-line field asking for Search must keep Enter as newline")
            .that(swap(searchAction, multiLineText, pref = true)).isFalse()
    }

    @Test
    fun prefOn_singleLineField_keepsItsAction() {
        assertWithMessage("A single-line search box cannot hold a newline: keep Search on Enter")
            .that(swap(searchAction, singleLineSearch, pref = true)).isTrue()
        assertWithMessage("IME_MULTI_LINE alone marks a single-line field: keep its action")
            .that(swap(sendAction, imeMultiLineOnly, pref = true)).isTrue()
    }

    @Test
    fun prefOn_blindAndNonTextFields_areUnaffected() {
        assertWithMessage("TYPE_NULL (blind REMOTE field) keeps the editor-driven rule")
            .that(swap(sendAction, InputType.TYPE_NULL, pref = true)).isTrue()
        // A multi-line FLAG bit on a non-text class is meaningless and must not count.
        val numberWithStrayFlag = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertWithMessage("Non-text class with a stray multi-line bit keeps the editor-driven rule")
            .that(swap(EditorInfo.IME_ACTION_DONE, numberWithStrayFlag, pref = true)).isTrue()
    }

    @Test
    fun prefOn_noEnterActionFlag_stillNoSwap() {
        assertWithMessage("The editor's own opt-out wins regardless of the preference")
            .that(swap(sendAction or EditorInfo.IME_FLAG_NO_ENTER_ACTION, singleLineSearch, pref = true))
            .isFalse()
    }

    @Test
    fun acceptsNewlines_onlyForMultiLineTextClass() {
        assertWithMessage("multi-line text").that(EditorInfoHelper.acceptsNewlines(multiLineText)).isTrue()
        assertWithMessage("plain text").that(EditorInfoHelper.acceptsNewlines(singleLineSearch)).isFalse()
        assertWithMessage("IME multi-line only").that(EditorInfoHelper.acceptsNewlines(imeMultiLineOnly)).isFalse()
        assertWithMessage("TYPE_NULL").that(EditorInfoHelper.acceptsNewlines(InputType.TYPE_NULL)).isFalse()
    }
}
