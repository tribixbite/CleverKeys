package tribixbite.cleverkeys

import android.os.Handler
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.io.File

/**
 * Audit A-1 (2026-09-06) — the documented bridge-gap bug class, third instance.
 *
 * `KeyEventHandler.IReceiver` methods default to no-ops; `KeyEventReceiverBridge` must
 * override EVERY one, because KeyEventHandler's `recv` IS the bridge — a method the bridge
 * forgets falls through to the interface default and the feature silently half-works
 * (`#41 v7` emoji routing, the GIF routing gap, and now `showPrivateCopyFeedback`:
 * every in-IME `copy_private` feedback message — success, "no selection", dispatch
 * failures — was dropped, making a failed private copy indistinguishable from success).
 *
 * Pins:
 *  1. Behavioral: `showPrivateCopyFeedback` reaches the receiver through the bridge.
 *  2. Ratchet: the bridge source `override fun`s every method the IReceiver interface
 *     declares, so a FOURTH instance of this class cannot land. Source-scan rather than
 *     reflection because Kotlin's DefaultImpls mode makes the compiler emit a
 *     default-delegating stub in the class for every non-overridden method —
 *     `getDeclaredMethod` cannot tell that stub from a real override.
 *
 * RED (2026-09-06, pre-fix): both pins failed — verify: `showPrivateCopyFeedback("copied")
 * was not called`; ratchet: missing = [showPrivateCopyFeedback].
 */
class KeyEventReceiverBridgeDelegationTest {

    @Test
    fun showPrivateCopyFeedbackReachesTheReceiver() {
        val bridge = KeyEventReceiverBridge(mockk<CleverKeysService>(relaxed = true), mockk<Handler>(relaxed = true))
        val receiver = mockk<KeyboardReceiver>(relaxed = true)
        bridge.setReceiver(receiver)

        // Dispatch through the interface — exactly how KeyEventHandler.handlePrivateCopy calls it.
        (bridge as KeyEventHandler.IReceiver).showPrivateCopyFeedback("copied")

        verify(exactly = 1) { receiver.showPrivateCopyFeedback("copied") }
    }

    @Test
    fun bridgeOverridesEveryIReceiverMethod() {
        val handlerSrc = File("src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt")
        val bridgeSrc = File("src/main/kotlin/tribixbite/cleverkeys/wiring/KeyEventReceiverBridge.kt")
        check(handlerSrc.isFile && bridgeSrc.isFile) {
            "Drift test must run with the project root as CWD (KeyEventHandler.kt / bridge not found)."
        }

        val interfaceBody = extractInterfaceBody(handlerSrc.readText(), "interface IReceiver")
        val declared = Regex("""\bfun\s+(\w+)\s*\(""").findAll(interfaceBody)
            .map { it.groupValues[1] }
            .toSortedSet()
        check(declared.size > 20) {
            "IReceiver parse degenerated (found only $declared) — fix the extractor, not the assertion."
        }

        val overridden = Regex("""\boverride\s+fun\s+(\w+)\s*\(""").findAll(bridgeSrc.readText())
            .map { it.groupValues[1] }
            .toSortedSet()

        val missing = declared - overridden
        assertWithMessage(
            "KeyEventReceiverBridge is KeyEventHandler's ONLY receiver — an IReceiver method " +
                "without a bridge override silently no-ops (bug class: emoji routing #41 v7, " +
                "GIF routing, showPrivateCopyFeedback A-1). Add the missing override(s)."
        ).that(missing).isEmpty()
    }

    /** Returns the brace-balanced body of the named declaration. */
    private fun extractInterfaceBody(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "'$marker' not found in KeyEventHandler.kt" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        error("Unbalanced braces after '$marker'")
    }
}
