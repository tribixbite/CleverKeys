package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Audit E-10 (2026-09-06) — Toast is not a feedback surface inside the IME.
 *
 * Toasts from an IME context are suppressed on Android 13+ (the project's own #156
 * pattern; KeyboardReceiver.insertGif documents it inline and uses
 * `showSuggestionBarMessage`). The GIF long-press popup's "URL copied"/"GIF copied"/
 * "Keywords copied" actions still used Toast — invisible feedback on modern Android.
 *
 * Ratchet: no `Toast` usage in the IME-context files this wave owns. (Cross-cutting
 * ratchet #4 from the audit; the clipboard-pane files are W4's half and can be added to
 * [BANNED_FILES] when converted.)
 *
 * RED (2026-09-06, pre-fix): KeyboardReceiver.kt matched 3 Toast.makeText call sites
 * (showGifPopup's copy actions).
 */
class ImeToastBanDriftTest {

    @Test
    fun imeContextFilesNeverUseToast() {
        val offenders = BANNED_FILES.flatMap { path ->
            val file = File(path)
            check(file.isFile) { "Drift test must run from the project root ($path missing)" }
            file.readLines().withIndex()
                .filter { (_, line) -> TOAST_USE.containsMatchIn(line) }
                .map { (i, _) -> "$path:${i + 1}" }
        }
        assertWithMessage(
            "Toast is IME-suppressed on Android 13+ — feedback from IME-context code goes " +
                "through the suggestion bar (showSuggestionBarMessage, #156 pattern / audit E-10)."
        ).that(offenders).isEmpty()
    }

    private companion object {
        /** IME-context sources owned by the pane/routing wave. Extend as files convert. */
        val BANNED_FILES = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt",
            "src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt",
        )

        /** Both the call and the import — either one is how the pattern creeps back. */
        val TOAST_USE = Regex("""Toast\.makeText|import\s+android\.widget\.Toast""")
    }
}
