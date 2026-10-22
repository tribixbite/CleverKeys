package tribixbite.cleverkeys.ui.settings

import java.text.Normalizer

/**
 * Pure matching rules for the settings search (unit-tested in `runPureTests`).
 *
 * Until 2026-09-30 the search matched only the English titles and keywords, and each control
 * registered its scroll position under a slug of its VISIBLE title (`[^a-z0-9]` → `_`), which is
 * empty or partial for Persian, Japanese, Russian, … titles. Search results under those UI
 * languages opened the right section but never scrolled to the control.
 *
 * Now a result matches the query against the title in the UI language first, then the English
 * title and the English keywords (users who know the English names still find settings), and
 * scroll targets are the locale-independent setting ids from the generated index (the title's
 * string-resource name), looked up by the control's visible title via [idsByTitle].
 */
internal object SettingsSearchMatch {

    /** Combining marks (accents, harakat, dakuten after NFD) — ignored when matching. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Folds text for matching: NFD without combining marks (so "rezges" finds "Rezgés"), lower
     * case, zero-width joiners removed (Persian users type ZWNJ inconsistently), and the Arabic
     * yeh/kaf that Arabic keyboard layouts produce mapped to the Persian letters.
     */
    fun fold(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase()
            .replace("\u200C", "").replace("\u200D", "")
            .replace('\u064A', '\u06CC') // ARABIC YEH -> FARSI YEH
            .replace('\u0649', '\u06CC') // ALEF MAKSURA -> FARSI YEH
            .replace('\u0643', '\u06A9') // ARABIC KAF -> KEHEH
            .trim()

    /** True when [query] occurs in the localized [title], the [englishTitle] or a [keywords] entry. */
    fun matches(query: String, title: String, englishTitle: String, keywords: List<String>): Boolean {
        val q = fold(query)
        if (q.isEmpty()) return false
        return fold(title).contains(q) || fold(englishTitle).contains(q) ||
            keywords.any { fold(it).contains(q) }
    }

    /**
     * Visible (localized) title -> the setting ids of the index entries with that title. A control
     * registers its scroll position under every id its title maps to, so two settings whose
     * titles happen to translate identically both stay reachable.
     */
    fun idsByTitle(entries: List<Pair<String, String>>): Map<String, List<String>> =
        entries.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct() }

    /**
     * One poll of a search result's scroll wait: the id to scroll to, or null to keep waiting.
     * [previous] and [current] are the registered positions of the last two polls (absent = the
     * control is not composed).
     *
     * The [target] wins once its position is registered and unchanged across two polls (the
     * expand animation reports in-flight offsets until then). A control whose revealing state is
     * off is never composed, so while the target is absent the first REGISTERED of [gates] (the
     * controls that reveal it, innermost first) is chosen once it too is settled. The gates
     * compose in the same frame as the target would, so a settled gate with no target means
     * the target is hidden.
     */
    fun settledScrollTarget(
        target: String,
        gates: List<String>,
        previous: Map<String, Int>,
        current: Map<String, Int>,
    ): String? {
        val position = current[target]
        if (position != null) return target.takeIf { previous[target] == position }
        val gate = gates.firstOrNull { current[it] != null } ?: return null
        return gate.takeIf { previous[gate] == current[gate] }
    }

    /** Where to scroll when the wait times out: the target if registered, else its first
     *  registered gate, else the target (scrolling to an unregistered id does nothing). */
    fun timedOutScrollTarget(target: String, gates: List<String>, current: Map<String, Int>): String =
        if (current[target] != null) target else gates.firstOrNull { current[it] != null } ?: target
}
