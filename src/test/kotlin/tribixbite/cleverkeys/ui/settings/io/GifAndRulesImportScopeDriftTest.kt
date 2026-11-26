package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Three Settings file-operation leftovers of the S2 rotation work (2.1, todo 2026-11-26):
 * - the clipboard custom-rules import ran in the activity's `lifecycleScope`, so a rotation cut
 *   it between its writes (rules file copied but URI, status and the rules-changed broadcast
 *   skipped). It runs in [SettingsImports.clipboardRules] now, with every write and the
 *   broadcast inside the work and only the shown status in the follow-up;
 * - a second GIF pack import while one runs was refused with a log line only; the user is told
 *   (`gif_import_already_running`);
 * - the GIF "replace pack?" prompt was an `AlertDialog.Builder` on the activity, lost on a
 *   rotation. It is DATA in [tribixbite.cleverkeys.SettingsViewModel] (`pendingGifReplace`),
 *   rendered by the Settings content, like `BackupRestoreViewModel.pendingPassphraseImport`.
 *
 * Source scan, like [LanguagePackOperationScopeDriftTest]: the handlers are `SettingsActivity`
 * extensions over Android managers; the tracker itself is exercised by [ImportTrackerTest].
 */
class GifAndRulesImportScopeDriftTest {

    private fun read(path: String) = File("src/main/kotlin/tribixbite/cleverkeys/$path").readText()

    private val clipboard = read("ui/settings/io/SettingsClipboardHandlers.kt")
    private val gif = read("ui/settings/io/SettingsGifHandlers.kt")
    private val imports = read("ui/settings/io/SettingsImports.kt")
    private val activity = read("activities/SettingsActivity.kt")
    private val viewModel = read("SettingsViewModel.kt")

    /** The body of `fun SettingsActivity.<name>(` in [source] up to the next top-level declaration. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun SettingsActivity.$name(")
        check(start >= 0) { "$name not found" }
        val next = Regex("\n(internal |private )?(fun|val|sealed|class|object|data) ").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    /** [code] with every `followUp { … }` block cut out (brace-matched): what the work itself runs. */
    private fun withoutFollowUps(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (true) {
            val at = code.indexOf("followUp {", i)
            if (at < 0) return out.append(code, i, code.length).toString()
            out.append(code, i, at)
            var depth = 0
            var j = code.indexOf('{', at)
            do {
                when (code[j]) { '{' -> depth++; '}' -> depth-- }
                j++
            } while (depth > 0)
            i = j
        }
    }

    @Test
    fun customRulesImportOutlivesTheActivity() {
        val picked = body(clipboard, "handleCustomRulesPicked")
        assertWithMessage("handleCustomRulesPicked must not run in lifecycleScope")
            .that(picked).doesNotContain("lifecycleScope")
        assertThat(picked).contains("SettingsImports.clipboardRules.start(")
        assertThat(picked).contains("catchingFailures(")
        // The work persists the URI and broadcasts the change itself: a follow-up is dropped when
        // the user has left Settings, and the service must still see the new rules.
        val work = withoutFollowUps(picked)
        assertWithMessage("the URI is stored by the work, not only by a follow-up")
            .that(work).contains("putString(\"clipboard_custom_rules_uri\"")
        assertWithMessage("the rules-changed broadcast is sent by the work")
            .that(work).contains("notifySanitizationRulesChanged()")
        // The tracker is registered with every Settings activity and its follow-up is delivered.
        assertThat(imports).contains("val clipboardRules = ImportTracker<Unit, SettingsFollowUp>()")
        assertThat(imports.substringAfter("private val all")).contains("clipboardRules")
        assertThat(imports.substringAfter("fun SettingsActivity.observeSettingsImports"))
            .contains("SettingsImports.clipboardRules.consumeEvent()")
    }

    @Test
    fun aRefusedSecondGifImportIsShownToTheUser() {
        val refused = body(gif, "performGifPackImport").substringAfter("} else {", missingDelimiterValue = "")
        assertWithMessage("performGifPackImport tells the user when another pack import runs")
            .that(refused).contains("R.string.gif_import_already_running")
    }
}
