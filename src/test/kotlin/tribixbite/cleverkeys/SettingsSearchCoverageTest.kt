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
        """\b(SettingsSwitch|SettingsSlider|SettingsDropdown)\s*\([\s\S]{0,400}?title\s*=\s*("([^"]*)"|stringResource\(R\.string\.([A-Za-z0-9_]+)\))"""
    )

    // name -> text map from res/values/strings.xml, mirroring the generator's resolution
    // (after the 2026-07-20 WP8 extraction, control titles are stringResource refs).
    private val stringResources: Map<String, String> by lazy {
        val xml = File("res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }
    }

    /** Control titles found in the UI source — literal or stringResource-resolved,
     *  mirroring generate_settings_search_index.py. */
    private fun literalControlTitles(text: String): List<String> =
        controlRegex.findAll(text)
            .mapNotNull { m ->
                m.groupValues[3].ifEmpty { stringResources[m.groupValues[4]] }
            }
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

    /**
     * The controls inside the collapsible "Advanced Prediction Settings" sub-panel need their
     * search entries to OPEN that panel, or the target is never composed, never registers a
     * scroll position, and the search result silently lands nowhere (audit 2026-08-26 — bit
     * all eight panel entries, found via the next-word toggle). `SettingsActivity` keeps the
     * panel's slugs in `WORD_PREDICTION_ADVANCED_SLUGS`; this pins that hand-kept set to the
     * panel's ACTUAL contents, both directions, so moving a control in or out of the panel
     * without updating the set fails here instead of silently breaking its search entry.
     */
    @Test
    fun advancedPanelSlugSetMatchesThePanelContents() {
        val section = File(sectionsDir, "InputBehaviorSection.kt").readText()
        // The panel body: from its AnimatedVisibility to the first control after it.
        val start = section.indexOf("AnimatedVisibility(visible = wordPredictionAdvancedExpanded)")
        val end = section.indexOf("settings_auto_capitalization_title")
        check(start in 0 until end) {
            "Panel markers not found in InputBehaviorSection.kt — the slicer needs updating, " +
                "not a real pass."
        }
        val panelSlugs = literalControlTitles(section.substring(start, end))
            .map { it.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') } // = settingSlug
            .toSortedSet()
        check(panelSlugs.isNotEmpty()) { "No controls found inside the panel — scanner broken." }

        val activitySrc = settingsFile.readText()
        val setBody = activitySrc
            .substringAfter("WORD_PREDICTION_ADVANCED_SLUGS = setOf(", "")
            .substringBefore(")")
        val declaredSlugs = Regex("\"([a-z0-9_]+)\"").findAll(setBody)
            .map { it.groupValues[1] }.toSortedSet()
        check(declaredSlugs.isNotEmpty()) {
            "WORD_PREDICTION_ADVANCED_SLUGS not found in SettingsActivity.kt — renamed?"
        }

        assertThat(declaredSlugs).isEqualTo(panelSlugs)
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

    /**
     * Every `gatedBy = "X"` used in `searchableSettings` must have a matching
     * `"X" ->` branch in `SettingsSearch.isGateEnabled()`, otherwise the gate
     * falls through to `else -> true` and never fires: searching for a gated
     * control while its gate is OFF expands the section and tries to scroll to
     * an unrendered control (silent no-op) instead of redirecting the user to
     * the enable toggle. (`gif_enabled` regression, 2026-07.)
     */
    @Test
    fun everyGatedByHasAnIsGateEnabledCase() {
        val activitySrc = settingsFile.readText()
        val searchSrc = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt").readText()
        val gateIds = Regex("""gatedBy\s*=\s*"([a-z_]+)"""").findAll(activitySrc)
            .map { it.groupValues[1] }.toSortedSet()
        check(gateIds.isNotEmpty()) { "No gatedBy values found — scanner broken." }
        // isGateEnabled body: collect the `"X" ->` case labels.
        val gateBody = Regex("""fun\s+SettingsActivity\.isGateEnabled[\s\S]*?\n    \}""").find(searchSrc)!!.value
        val handled = Regex(""""([a-z_]+)"\s*->""").findAll(gateBody).map { it.groupValues[1] }.toSet()
        val missing = gateIds.filter { it !in handled }
        assertThat(missing).isEmpty()
    }

    /**
     * `collapseAllSections()` must reset EVERY top-level section expand flag, or
     * a search-result click (which collapses-all then expands the target) can
     * leave two sections open. `testKeyboardExpanded` was omitted (2026-07).
     */
    @Test
    fun collapseAllSections_resetsEveryTopLevelSectionFlag() {
        val searchSrc = File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt").readText()
        val body = Regex("""fun\s+SettingsActivity\.collapseAllSections[\s\S]*?\n\}""").find(searchSrc)!!.value
        val reset = Regex("""(\w+)\s*=\s*false""").findAll(body).map { it.groupValues[1] }.toSet()
        // Every top-level CollapsibleSettingsSection expand var lives in SettingsActivity.kt as
        // `internal var <name>(Section)?Expanded by mutableStateOf`. Each must be reset here.
        // (AnimatedVisibility sub-toggles like wordPredictionAdvancedExpanded are NOT top-level
        // sections and are intentionally excluded — they are nested inside a section body.)
        val topLevel = setOf(
            "activitiesSectionExpanded", "multiLangSectionExpanded", "privacySectionExpanded",
            "swipeTypingSectionExpanded", "appearanceSectionExpanded", "swipeTrailSectionExpanded",
            "inputSectionExpanded", "swipeCorrectionsSectionExpanded", "gestureTuningSectionExpanded",
            "accessibilitySectionExpanded", "clipboardSectionExpanded", "gifSectionExpanded",
            "backupRestoreSectionExpanded", "advancedSectionExpanded", "infoSectionExpanded",
            "helpSectionExpanded", "testKeyboardExpanded"
        )
        assertThat(topLevel.filter { it !in reset }).isEmpty()
    }
}
