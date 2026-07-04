package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Verifies the AUTO-GENERATED settings search index (`GENERATED_SEARCH_ENTRIES`, produced
 * by `scripts/generate_settings_search_index.py` and wired in via the
 * `generateSettingsSearchIndex` Gradle task) actually covers every control in
 * `SettingsActivity.kt` and the per-section composable files.
 *
 * The index is generated FROM the control titles, so coverage is structural — but this test
 * independently re-scans the source (a separate implementation from the generator) so a
 * generator gap is caught loudly: a control shape the parser misses, a title that tokenizes
 * to nothing (unfindable), or a control added with no resulting entry.
 *
 * Controls whose title is a `stringResource(...)` are skipped here (can't be scanned without
 * resolving res/values/strings.xml); the generator resolves those, so they still appear in
 * the index — they're simply not asserted from this side.
 *
 * NOTE: during the 2026-07 settings decomposition, controls were moved from SettingsActivity.kt
 * into individual files under ui/settings/sections/. Both paths are scanned below.
 */
class SettingsSearchCoverageTest {

    private val settingsFile = File("src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt")
    // Controls were moved here during the 2026-07 decomposition (mirrors generate_settings_search_index.py)
    private val sectionsDir = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections")

    // Filler words that carry no search intent (kept in sync with the generator's stopwords).
    private val stopwords = setOf(
        "enable", "enabled", "disable", "disabled", "show", "hide", "use", "using",
        "for", "the", "and", "or", "with", "off", "your", "you", "mode",
        "settings", "setting", "this", "when", "auto", "only"
    )

    private val controlRegex = Regex(
        """\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\([\s\S]{0,400}?title\s*=\s*("([^"]*)"|stringResource)"""
    )

    /** Literal control titles found in the UI source (skips `stringResource` titles). */
    private fun literalControlTitles(text: String): List<String> =
        controlRegex.findAll(text)
            .map { it.groupValues[3] }
            .filter { it.isNotEmpty() }
            .toList()

    private fun significantWords(title: String): List<String> =
        title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopwords && !it.all(Char::isDigit) }

    /**
     * Collects literal control titles from SettingsActivity.kt AND all section files under
     * ui/settings/sections/ (sorted by name). Mirrors the multi-file scan added to
     * generate_settings_search_index.py during the 2026-07 decomposition.
     */
    private fun allControlTitles(): List<String> {
        val titles = mutableListOf<String>()
        if (settingsFile.exists()) titles += literalControlTitles(settingsFile.readText())
        if (sectionsDir.isDirectory) {
            sectionsDir.listFiles { f -> f.extension == "kt" }
                ?.sortedBy { it.name }
                ?.forEach { titles += literalControlTitles(it.readText()) }
        }
        return titles
    }

    @Test
    fun everyLiteralControlHasAGeneratedEntry() {
        check(settingsFile.exists()) {
            "SettingsActivity.kt not found at ${settingsFile.absolutePath} — run with project root as CWD."
        }
        val titles = allControlTitles()
        check(titles.isNotEmpty()) { "No control titles found — the scanner is broken, not a real pass." }

        val generatedTitles = GENERATED_SEARCH_ENTRIES.map { it.title }.toSet()
        check(generatedTitles.isNotEmpty()) {
            "GENERATED_SEARCH_ENTRIES is empty — the generateSettingsSearchIndex task did not run."
        }
        val missing = titles.filterNot { it in generatedTitles }.toSortedSet()
        assertThat(missing).isEmpty()
    }

    @Test
    fun everyGeneratedEntryIsSearchable() {
        // An entry with no keywords would be invisible to search.
        val unfindable = GENERATED_SEARCH_ENTRIES.filter { it.keywords.isEmpty() }
            .map { it.title }.toSortedSet()
        assertThat(unfindable).isEmpty()
    }

    @Test
    fun everyControlNameWordIsSearchable() {
        val corpus = GENERATED_SEARCH_ENTRIES
            .flatMap { listOf(it.title) + it.keywords }
            .map { it.lowercase() }
        check(corpus.isNotEmpty()) { "Generated corpus empty — generator did not run." }

        val uncovered = sortedMapOf<String, MutableSet<String>>()
        for (title in allControlTitles()) {
            for (word in significantWords(title)) {
                if (corpus.none { it.contains(word) }) uncovered.getOrPut(word) { sortedSetOf() }.add(title)
            }
        }
        assertThat(uncovered).isEmpty()
    }
}
