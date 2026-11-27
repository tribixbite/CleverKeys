package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Language-pack operations from Settings (todo 2026-11-26, S2 leftover):
 * - a pack REMOVAL ran in the activity's `lifecycleScope`, so a rotation cut it like the imports
 *   were cut before S2 (files gone, list and toast skipped). It goes through
 *   [SettingsImports.languagePack] now, like the import;
 * - a second operation started while one runs was refused with a log line only. The user is told
 *   (`multilang_pack_busy`).
 *
 * Source scan: the handlers are `SettingsActivity` extensions with an Android pack manager behind
 * them; the tracker itself is exercised by [ImportTrackerTest].
 */
class LanguagePackOperationScopeDriftTest {

    private val source =
        File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io/SettingsLanguagePackHandlers.kt").readText()

    /** The body of `fun SettingsActivity.<name>(` up to the next top-level declaration. */
    private fun body(name: String): String {
        val start = source.indexOf("fun SettingsActivity.$name(")
        check(start >= 0) { "$name not found" }
        val next = Regex("\n(internal |private )?(fun|val|sealed|class|object) ").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    @Test
    fun aPackRemovalOutlivesTheActivity() {
        val delete = body("deleteLanguagePack")
        assertWithMessage("deleteLanguagePack must not run in lifecycleScope")
            .that(delete).doesNotContain("lifecycleScope")
        assertThat(delete).contains("SettingsImports.languagePack.start(")
    }

    @Test
    fun aRefusedSecondOperationIsShownToTheUser() {
        assertThat(body("showLanguagePackBusy")).contains("R.string.multilang_pack_busy")
        for (name in listOf("performLanguagePackImport", "deleteLanguagePack")) {
            val refused = body(name).substringAfter("if (!started)", missingDelimiterValue = "")
            assertWithMessage("$name tells the user when another pack operation runs")
                .that(refused).contains("showLanguagePackBusy()")
        }
    }

    /**
     * Fable audit D4 (2026-11-26): a removal the pack manager refused (it returned false, no
     * exception) showed nothing. Its follow-up says the delete failed.
     */
    @Test
    fun aRefusedRemovalSaysTheDeleteFailed() {
        val delete = body("deleteLanguagePack")
        val open = delete.indexOf('{', delete.indexOf("followUp {"))
        var depth = 0
        var end = open
        while (end < delete.length) {
            when (delete[end]) { '{' -> depth++; '}' -> depth-- }
            end++
            if (depth == 0) break
        }
        val resultFollowUp = delete.substring(open, end)
        assertWithMessage("the follow-up of a finished removal covers deleted == false")
            .that(resultFollowUp).contains("R.string.common_delete_failed_detail")
    }
}
