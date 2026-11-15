package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-14 M3: `Context.RECEIVER_NOT_EXPORTED` is honoured only from API 33. On API
 * 26–32 the three-argument `registerReceiver(receiver, filter, flags)` accepts only
 * RECEIVER_VISIBLE_TO_INSTANT_APPS and ignores the flag, so a receiver registered with it there is
 * EXPORTED: any app could turn debug mode on (then text around the cursor is logged) or inject
 * debug-screen entries. Every app-private dynamic receiver goes through [PrivateReceivers], which
 * picks the flag on 33+ and the app's signature permission below.
 *
 * Exempt: `DirectBootManager` listens for ACTION_USER_UNLOCKED, a protected broadcast only the
 * system can send; the permission form would gate the system sender.
 */
class PrivateReceiverDriftTest {

    private val root = File("src/main/kotlin/tribixbite/cleverkeys")
    private val exempt = setOf("PrivateReceivers.kt", "DirectBootManager.kt")

    @Test
    fun onlyThePrivateReceiversHelperPassesTheNotExportedFlag() {
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name !in exempt }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, l) -> "RECEIVER_NOT_EXPORTED" in l && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") }
                    .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
            }.toList()
        assertWithMessage("register app-private receivers through PrivateReceivers.register")
            .that(offenders).isEmpty()
    }

    @Test
    fun noAppPrivateReceiverIsRegisteredWithTheBareTwoArgumentForm() {
        // The plain (receiver, filter) form is exported on every API below 33.
        val bare = Regex("""registerReceiver\(\s*\w+\s*,\s*\w+\s*\)""")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name !in exempt }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, l) -> bare.containsMatchIn(l) && "LocalBroadcastManager" !in l }
                    .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
            }.toList()
        assertWithMessage("bare registerReceiver(receiver, filter) calls").that(offenders).isEmpty()
    }
}
