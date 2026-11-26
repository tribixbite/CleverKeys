package tribixbite.cleverkeys.ui.settings.io

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.LanguageDisplayNames
import tribixbite.cleverkeys.LanguagePrefs
import java.io.File

/**
 * Saga 2026-11-26: deleting the language pack of the current SECONDARY (Russian under a German
 * primary) left `pref_secondary_language = ru`. Settings showed "None" in the dropdown with the
 * secondary block and "Auto-Detect is not available for this pair" still under it, and the
 * keyboard kept Russian as its active secondary (CTC merge, the Auto-Detect pair, the contraction
 * mappings; in a running keyboard the Russian dictionary stayed loaded, since no preference
 * changed). The removal now puts a secondary choice naming the pack back to "none".
 */
class RemovedPackLanguagePrefsTest {

    /** The languages left after the Russian pack was removed. */
    private val stillAvailable = listOf("en", "de", "es", "fr")

    @Test
    fun theSecondaryNamingTheRemovedPackGoesBackToNone() {
        assertThat(RemovedPackLanguagePrefs.resets("ru", stillAvailable, secondary = "ru", secondaryAlt = "none"))
            .containsExactly(LanguagePrefs.SECONDARY, LanguageDisplayNames.NONE)
    }

    @Test
    fun theQuickToggleSecondaryNamingItGoesBackToNoneToo() {
        assertThat(RemovedPackLanguagePrefs.resets("ru", stillAvailable, secondary = "es", secondaryAlt = "ru"))
            .containsExactly(LanguagePrefs.SECONDARY_ALT, LanguageDisplayNames.NONE)
    }

    @Test
    fun otherChoicesAreKept() {
        assertThat(RemovedPackLanguagePrefs.resets("ru", stillAvailable, secondary = "es", secondaryAlt = null)).isEmpty()
    }

    /** A pack whose language is still served (a bundled dictionary under it) changes no choice. */
    @Test
    fun aLanguageStillAvailableKeepsItsChoices() {
        assertThat(RemovedPackLanguagePrefs.resets("de", stillAvailable, secondary = "de", secondaryAlt = "de")).isEmpty()
    }

    /** The reset is written by the removal's work, so it holds when its follow-up is dropped. */
    @Test
    fun theRemovalWritesTheResetsItself() {
        val source = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/io/SettingsLanguagePackHandlers.kt").readText()
        val start = source.indexOf("fun SettingsActivity.deleteLanguagePack(")
        check(start >= 0) { "deleteLanguagePack not found" }
        val next = Regex("\n(internal |private )?(fun|val|object|class) ").find(source, start + 1)
        val body = source.substring(start, next?.range?.first ?: source.length)
        val work = body.substringBefore("followUp {")
        assertWithMessage("deleteLanguagePack's work computes the resets")
            .that(work).contains("RemovedPackLanguagePrefs.resets(")
        assertWithMessage("deleteLanguagePack's work stores them").that(work).contains(".commit()")
        assertWithMessage("the shown secondary follows at once")
            .that(body.substringAfter("followUp {")).contains("secondaryLanguage =")
    }
}
