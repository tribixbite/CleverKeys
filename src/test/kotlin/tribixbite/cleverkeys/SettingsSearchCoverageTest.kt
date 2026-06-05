package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Drift detection for Settings SEARCH coverage.
 *
 * The in-app settings search (`SettingsActivity.getFilteredSettings`) matches a query
 * against a hand-maintained `searchableSettings` index (titles + keywords). Because that
 * index is parallel to the actual UI controls, it is easy to add a `SettingsSwitch` /
 * `SettingsSlider` / `SettingsDropdown` and forget the matching search entry — the setting
 * then becomes invisible to search (the user types its name and gets ZERO results). That is
 * exactly what happened to the URL-handling toggles ("Sanitize tracking parameters" → no
 * results despite being on screen).
 *
 * This test scans `SettingsActivity.kt` at build time and asserts that EVERY significant
 * word in EVERY literal-titled control appears somewhere in the search index (a searchable
 * title or keyword). If a user can type a word from a setting's visible name, they must get
 * at least one result that leads to it.
 *
 * **When this test fails**, make the flagged word searchable — either add a new
 * `SearchableSetting` for the control, or add the missing word as a `keyword` on the most
 * relevant existing entry. Do NOT silence it by adding to [stopwords] unless the word is
 * genuinely a non-distinguishing filler ("enable", "show", ...).
 *
 * Controls whose title is a `stringResource(...)` (not a string literal) cannot be scanned
 * here and are skipped — those resolve from res/values/strings.xml at runtime.
 */
class SettingsSearchCoverageTest {

    private val settingsFile = File("src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt")

    // Filler words that carry no search intent — a user won't type these to find a specific
    // setting, so they don't need their own index coverage.
    private val stopwords = setOf(
        "enable", "enabled", "disable", "disabled", "show", "hide", "use", "using",
        "for", "the", "and", "or", "with", "off", "your", "you", "mode",
        "settings", "setting", "this", "when", "auto", "only"
    )

    // Composables that render a user-facing, searchable setting. The title is the first
    // `title =` argument inside the call; a lazy window reaches it without crossing into the
    // next composable.
    private val controlRegex = Regex(
        """\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\([\s\S]{0,400}?title\s*=\s*("([^"]*)"|stringResource)"""
    )

    /** All literal control titles found in the UI (skips `stringResource` titles). */
    private fun controlTitles(text: String): List<String> =
        controlRegex.findAll(text)
            .map { it.groupValues[3] }
            .filter { it.isNotEmpty() }
            .toList()

    /** The search corpus: every quoted string in the `searchableSettings` block
     *  (titles + keywords + section names + ids), lowercased. */
    private fun searchCorpus(text: String): List<String> {
        val start = text.indexOf("val searchableSettings")
        check(start >= 0) { "Could not locate the searchableSettings block." }
        val end = text.indexOf("private fun isGateEnabled", start)
        check(end > start) { "Could not locate the end of the searchableSettings block." }
        val block = text.substring(start, end)
        return Regex("\"([^\"]*)\"").findAll(block)
            .map { it.groupValues[1].lowercase() }
            .toList()
    }

    private fun significantWords(title: String): List<String> =
        title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopwords && !it.all(Char::isDigit) }

    @Test
    fun everyControlNameWordIsSearchable() {
        check(settingsFile.exists()) {
            "SettingsActivity.kt not found at ${settingsFile.absolutePath} — " +
                "test must run with the project root as CWD."
        }
        val text = settingsFile.readText()

        val corpus = searchCorpus(text)
        check(corpus.isNotEmpty()) { "Search corpus is empty — the parser is broken, not a real pass." }

        val titles = controlTitles(text)
        check(titles.isNotEmpty()) { "No control titles found — the parser is broken, not a real pass." }

        // word -> control titles whose name contains that (currently unsearchable) word.
        val uncovered = sortedMapOf<String, MutableSet<String>>()
        for (title in titles) {
            for (word in significantWords(title)) {
                val covered = corpus.any { it.contains(word) }
                if (!covered) uncovered.getOrPut(word) { sortedSetOf() }.add(title)
            }
        }

        assertThat(uncovered).isEmpty()
    }
}
