package tribixbite.cleverkeys.langpack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the language-pack delete confirmation (device finding 2026-09-29: one tap on a pack's
 * Delete button removed the installed pack with no confirmation, unlike every other
 * destructive action in settings). The card's Delete button may only arm the dialog; the
 * actual `deleteLanguagePack` call must live in that dialog's confirm button.
 */
class LanguagePackDeleteConfirmationTest {

    private val source by lazy {
        File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/MultiLanguageSection.kt").readText()
    }

    @Test fun packDeleteGoesThroughAConfirmationDialog() {
        val calls = Regex("\\bdeleteLanguagePack\\(").findAll(source).map { it.range.first }.toList()
        assertEquals("deleteLanguagePack must be called from exactly one place", 1, calls.size)
        val dialogStart = source.indexOf("R.string.multilang_pack_delete_title")
        assertTrue("the delete confirmation dialog is missing", dialogStart > 0)
        val confirmStart = source.indexOf("confirmButton", dialogStart)
        val dismissStart = source.indexOf("dismissButton", confirmStart)
        assertTrue("the confirmation dialog needs a confirm and a dismiss button",
            confirmStart > 0 && dismissStart > confirmStart)
        assertTrue(
            "deleteLanguagePack must be invoked from the confirmation dialog's confirm button",
            calls.single() in confirmStart until dismissStart
        )
    }

    @Test fun dialogExplainsWhatIsRemovedAndKept() {
        // The body must exist (it states what is deleted vs kept — the learned data survives).
        assertTrue("R.string.multilang_pack_delete_body" in source)
    }
}
