package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every simultaneous language-SLOT preference key must be WIRED — a slot nothing acts on is
 * a settings row that silently does nothing.
 *
 * Slot keys follow the `pref_<slot>_language[_alt]` naming convention
 * (`pref_primary_language`, `pref_secondary_language_alt`, …). If a 5th slot is added under
 * that convention anywhere in `src/main`, this test fails until it is wired like the others.
 *
 * ## What changed (ARC-079, 2026-08-29)
 *
 * This test used to assert the slot set was read by `DictionaryManager.getConfiguredLanguages()`,
 * because that set was the RETENTION set for a per-language `WordPredictor` cache: a slot key
 * missing from it meant that slot's predictor was evicted and re-loaded on every switch. That
 * cache — and with it `getConfiguredLanguages()` — is deleted (it duplicated the dictionary
 * `PredictionCoordinator` already had resident, for no consumer). The eviction invariant is
 * therefore obsolete, but the slot family is not, so the pin moves to the two seams that
 * survive and that a new slot would still have to join:
 *
 *  1. **live slots** (`pref_primary_language`, `pref_secondary_language`) drive the dictionary
 *     reload + swipe re-warm in `PreferenceUIUpdateHandler`. A live slot missing there changes
 *     the setting without changing what the keyboard predicts (the ARC-014 class of bug).
 *  2. **`_alt` slots** exist only to be SWAPPED into their live counterpart by
 *     `Keyboard2View`'s language toggles. An `_alt` slot with no swap site is inert storage.
 *
 * Backup/restore coverage of the same keys is separately enforced by
 * `backup.SettingsDefaultsDriftTest.everyPrefReadKeyIsClassified`.
 *
 * Same source-scan convention as [LearningWiringDriftTest] (project root as CWD).
 */
class LanguageSlotCoverageDriftTest {

    // Quoted string literals of the slot-key family. Deliberately does NOT match
    // non-slot language prefs (`pref_auto_detect_language`,
    // `pref_language_detection_sensitivity`) — their middle segment contains an
    // underscore / the key doesn't end in `_language(_alt)`.
    private val slotKeyPattern = Regex("\"(pref_[a-z]+_language(?:_alt)?)\"")

    private fun mainSource(relative: String): String {
        val f = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin/$relative")
        check(f.isFile) { "expected $relative at ${f.absolutePath} — run from the project root" }
        return f.readText()
    }

    private fun slotKeysInUse(): Set<String> {
        val mainDir = File(System.getProperty("user.dir") ?: ".", "src/main/kotlin")
        assertThat(mainDir.exists()).isTrue()

        val found = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> slotKeyPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        // Sanity: the scan must at least see the 4 known slots — if the regex
        // rots, this fails loudly instead of vacuously passing.
        assertThat(found).containsAtLeast(
            "pref_primary_language",
            "pref_secondary_language",
            "pref_primary_language_alt",
            "pref_secondary_language_alt"
        )
        return found
    }

    @Test
    fun `every live language slot drives the dictionary reload seam`() {
        val handler = mainSource("tribixbite/cleverkeys/PreferenceUIUpdateHandler.kt")

        for (key in slotKeysInUse().filterNot { it.endsWith("_alt") }) {
            assertWithMessage(
                "'$key' is a live language slot, so a change to it must reach " +
                    "PreferenceUIUpdateHandler — that is where the predictor's dictionary is " +
                    "reloaded and the swipe engine re-warmed. A slot handled nowhere there " +
                    "changes the pref and nothing else."
            ).that(handler).contains("\"$key\"")
        }
    }

    @Test
    fun `every alt language slot has a swap site that writes its live counterpart`() {
        val view = mainSource("tribixbite/cleverkeys/Keyboard2View.kt")

        for (key in slotKeysInUse().filter { it.endsWith("_alt") }) {
            val live = key.removeSuffix("_alt")
            assertWithMessage(
                "'$key' exists only so a keyboard toggle can swap it into '$live'. Without a " +
                    "swap site in Keyboard2View it is storage nothing ever reads back."
            ).that(view).contains("\"$key\"")
            assertWithMessage(
                "the swap for '$key' must also WRITE '$live' — swapping into a slot nobody " +
                    "serves from would leave the keyboard on the old language."
            ).that(view).contains("\"$live\"")
        }
    }

    @Test
    fun `the deleted eviction retention set has not come back`() {
        // ARC-079: `getConfiguredLanguages()` existed solely to decide which cached
        // per-language predictors to keep. Re-introducing it would mean the duplicate
        // full-dictionary residency is back (see LearningWiringDriftTest's residency pin).
        val dictManager = mainSource("tribixbite/cleverkeys/DictionaryManager.kt")
        assertThat(dictManager).doesNotContain("fun getConfiguredLanguages(")
    }
}
