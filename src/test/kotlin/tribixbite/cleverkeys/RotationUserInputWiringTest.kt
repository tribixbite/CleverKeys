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

    @Test
    fun touchTypingDisarmsTheShowGuardAndThePaneRestore() {
        val touched = body(Regex("""\bfun\s+onKeyboardTouched\s*\(\s*\)"""))
        assertWithMessage("onKeyboardTouched must disarm the rotation show guard (S3)")
            .that(calls(touched, "_rotationGuard.onUserInput")).isTrue()
        assertWithMessage("onKeyboardTouched must drop a pending pane restore (S3)")
            .that(calls(touched, "_rotationPane.onUserInput")).isTrue()
        assertWithMessage("onKeyboardTouched still drops the carried modifiers")
            .that(calls(touched, "_rotationModifiers.onUserInput")).isTrue()
    }

    @Test
    fun aHardwareKeyDisarmsTheShowGuardAndThePaneRestore() {
        val keyDown = body(Regex("""\boverride\s+fun\s+onKeyDown\s*\("""))
        assertWithMessage("onKeyDown disarms the rotation show guard")
            .that(calls(keyDown, "_rotationGuard.onUserInput")).isTrue()
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
