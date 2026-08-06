package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Enforces the (formerly TODO) invariant on `DictionaryManager.getConfiguredLanguages()`:
 * every simultaneous language-SLOT preference key used anywhere in production
 * code must be read there — the predictor-eviction logic in `setLanguage()`
 * retains only predictors whose language appears in that set, so a slot key
 * missing from it would silently evict (and re-load) that slot's predictor.
 *
 * Slot keys follow the `pref_<slot>_language[_alt]` naming convention
 * (`pref_primary_language`, `pref_secondary_language_alt`, …). If a 5th slot is
 * added under that convention anywhere in `src/main`, this test fails until
 * `getConfiguredLanguages()` reads it too.
 *
 * Same source-scan convention as [LearningWiringDriftTest] (project root as CWD).
 */
class LanguageSlotCoverageDriftTest {

    // Quoted string literals of the slot-key family. Deliberately does NOT match
    // non-slot language prefs (`pref_auto_detect_language`,
    // `pref_language_detection_sensitivity`) — their middle segment contains an
    // underscore / the key doesn't end in `_language(_alt)`.
    private val slotKeyPattern = Regex("\"(pref_[a-z]+_language(?:_alt)?)\"")

    @Test
    fun `every language-slot pref key in production code is covered by getConfiguredLanguages`() {
        val mainDir = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin")
        assertThat(mainDir.exists()).isTrue()

        val slotKeysInUse = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> slotKeyPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        // Sanity: the scan must at least see the 4 known slots — if the regex
        // rots, this fails loudly instead of vacuously passing.
        assertThat(slotKeysInUse).containsAtLeast(
            "pref_primary_language",
            "pref_secondary_language",
            "pref_primary_language_alt",
            "pref_secondary_language_alt"
        )

        val dictionaryManager = File(
            System.getProperty("user.dir") ?: ".",
            "src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt"
        ).readText()
        val configuredLanguagesBody = Regex(
            """(?s)private fun getConfiguredLanguages\(\).*?\.toSet\(\)"""
        ).find(dictionaryManager)?.value
        assertThat(configuredLanguagesBody).isNotNull()

        for (key in slotKeysInUse) {
            assertThat(configuredLanguagesBody).contains("\"$key\"")
        }
    }
}
