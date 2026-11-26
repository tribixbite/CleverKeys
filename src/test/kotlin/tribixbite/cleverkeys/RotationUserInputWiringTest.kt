package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Pins how `CleverKeysService` feeds user input to the rotation guards (pre-tag review S3,
 * 2026-11-25). [RotationShowGuard] and [RotationPaneRestore] are pure and tested on their own;
 * the service around them cannot be constructed in a JVM test (its lifecycle callbacks call
 * into android.jar stubs), so this reads its source instead, with comments stripped and every
 * call anchored at word boundaries so a renamed or commented-out call does not pass.
 *
 * The contract: a user hide OR user input disarms the show guard. Touch typing is the common
 * input, yet `onKeyboardTouched` only cleared the modifier carry, so an app recreated by the
 * rotation that hid the keyboard right after the user typed and submitted (a search) could have
 * it shown again, up to twice. A pane the user closed after the rotation could also come back.
 */
class RotationUserInputWiringTest {

    private val service: String by lazy {
        val f = File(KotlinSourceScan.sourceRoot, "CleverKeysService.kt")
        assertWithMessage("expected ${f.path} (run from the project root)").that(f.isFile).isTrue()
        KotlinSourceScan.stripComments(f.readText())
    }

    /**
     * The body of the function declared by [signature] (comments already stripped): from its
     * opening brace to the matching closing brace.
     */
    private fun body(signature: Regex): String {
        val m = signature.find(service)
        assertWithMessage("declaration $signature in CleverKeysService.kt").that(m).isNotNull()
        val open = service.indexOf('{', m!!.range.last)
        var depth = 0
        for (i in open until service.length) {
            when (service[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return service.substring(open + 1, i)
            }
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    private fun calls(body: String, call: String): Boolean =
        Regex("""(?<![\w.])${Regex.escape(call)}\s*\(""").containsMatchIn(body)

    /**
     * A key touch is reported as [RotationShowGuard.UserInput.KEY_TOUCH], which keeps the guard
     * armed (typing while the phone rotates still gets the keyboard back); it still drops the
     * carried modifiers and the pane restore. Pre-tag follow-up 2026-11-25 to S3.
     */
    @Test
    fun touchTypingReportsAKeyTouchAndDropsThePaneRestore() {
        val touched = body(Regex("""\bfun\s+onKeyboardTouched\s*\(\s*\)"""))
        assertWithMessage("onKeyboardTouched reports a KEY_TOUCH to the show guard")
            .that(touched).containsMatch("""(?<![\w.])_rotationGuard\.onUserInput\s*\(\s*RotationShowGuard\.UserInput\.KEY_TOUCH\s*\)""")
        assertWithMessage("onKeyboardTouched must not report anything else to the show guard")
            .that(Regex("""(?<![\w.])_rotationGuard\.""").findAll(touched).count()).isEqualTo(1)
        assertWithMessage("onKeyboardTouched must drop a pending pane restore (S3)")
            .that(calls(touched, "_rotationPane.onUserInput")).isTrue()
        assertWithMessage("onKeyboardTouched still drops the carried modifiers")
            .that(calls(touched, "_rotationModifiers.onUserInput")).isTrue()
    }

    /** Enter and the action key reach the guard as a SUBMIT, which disarms it (S3's case). */
    @Test
    fun aSubmitDisarmsTheShowGuard() {
        val submit = body(Regex("""\bfun\s+onEditorSubmit\s*\(\s*\)"""))
        assertWithMessage("onEditorSubmit reports a SUBMIT to the show guard")
            .that(submit).containsMatch("""(?<![\w.])_rotationGuard\.onUserInput\s*\(\s*RotationShowGuard\.UserInput\.SUBMIT\s*\)""")
        val receiver = KotlinSourceScan.stripComments(File(KotlinSourceScan.sourceRoot, "KeyboardReceiver.kt").readText())
        assertWithMessage("the action key (performEditorAction) reports a submit")
            .that(receiver).containsMatch("""performEditorAction\(keyboard2\.actionId\)[\s\S]{0,200}?keyboard2\.onEditorSubmit\s*\(""")
        assertWithMessage("the receiver forwards the key handler's Enter submit")
            .that(receiver).containsMatch("""override\s+fun\s+onEditorSubmit\s*\(\s*\)[^{]*\{[^}]*keyboard2\.onEditorSubmit\s*\(""")
        val bridge = KotlinSourceScan.stripComments(File(KotlinSourceScan.sourceRoot, "wiring/KeyEventReceiverBridge.kt").readText())
        assertWithMessage("the key handler's receiver bridge forwards the submit")
            .that(bridge).containsMatch("""override\s+fun\s+onEditorSubmit\s*\(\s*\)[^{]*\{[^}]*receiver\?\.onEditorSubmit\s*\(""")
        val handler = KotlinSourceScan.stripComments(File(KotlinSourceScan.sourceRoot, "KeyEventHandler.kt").readText())
        assertWithMessage("an Enter key sent to the app reports a submit")
            .that(handler).containsMatch("""KEYCODE_ENTER\)\s*\{[^}]*recv\.onEditorSubmit\s*\(""")
    }

    @Test
    fun aHardwareKeyDisarmsTheShowGuardAndThePaneRestore() {
        val keyDown = body(Regex("""\boverride\s+fun\s+onKeyDown\s*\("""))
        assertWithMessage("onKeyDown disarms the rotation show guard")
            .that(keyDown).containsMatch("""(?<![\w.])_rotationGuard\.onUserInput\s*\(\s*RotationShowGuard\.UserInput\.HARDWARE_KEY\s*\)""")
        assertWithMessage("onKeyDown drops a pending pane restore")
            .that(calls(keyDown, "_rotationPane.onUserInput")).isTrue()
    }

    /**
     * The pane restore tells the system's close (the hide finishing the input view) from the
     * user's own: it must see the open pane BEFORE `resetContentPaneState` closes it.
     */
    @Test
    fun theInputViewFinishReportsTheOpenPaneBeforeClosingIt() {
        val finish = body(Regex("""\boverride\s+fun\s+onFinishInputView\s*\("""))
        val report = Regex("""(?<![\w.])_rotationPane\.onInputViewFinishing\s*\(""").find(finish)
        val reset = Regex("""(?<![\w.])_receiver\?\.resetContentPaneState\s*\(""").find(finish)
        assertWithMessage("onFinishInputView reports the open pane to the pane restore").that(report).isNotNull()
        assertWithMessage("onFinishInputView still resets the content pane").that(reset).isNotNull()
        assertWithMessage("the report comes before the reset closes the pane")
            .that(report!!.range.first).isLessThan(reset!!.range.first)
    }
}
