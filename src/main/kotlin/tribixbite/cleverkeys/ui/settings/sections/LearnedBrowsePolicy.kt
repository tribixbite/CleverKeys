package tribixbite.cleverkeys.ui.settings.sections

import tribixbite.cleverkeys.ui.settings.SettingsSearchMatch

/**
 * Search and paging rules for the Learning & Data browsers ("Browse words", "Browse phrases").
 *
 * Until 2026-10-26 each browser showed only the 200 highest-ranked rows with no search, so a
 * learned word beyond rank 200 (the vocabulary holds up to 20,000) could be neither found nor
 * deleted. Now the whole snapshot is searchable and the list grows a page at a time as it is
 * scrolled, so composition stays bounded however large the store is.
 *
 * Pure (`runPureTests`): no Android or Compose types. Matching uses [SettingsSearchMatch.fold],
 * the settings search's fold — case, diacritics (NFD without combining marks), zero-width
 * joiners and the Arabic yeh/kaf variants are ignored — so "cafe" finds "Café" here exactly as
 * it does in the settings search.
 */
internal object LearnedBrowsePolicy {

    /** Rows added to the list per page. */
    const val PAGE_SIZE = 100

    /** Load the next page once the last visible row is this close to the end of the loaded rows. */
    const val PREFETCH_DISTANCE = 20

    /** A browser row with its search text folded once, at load time, instead of per keystroke. */
    class Indexed<out T>(val item: T, val folded: String)

    /**
     * Folds each row's searchable [fields] (joined by a space, so a phrase query "of the"
     * spans both words of a pair) for [filter].
     */
    fun <T> index(rows: List<T>, fields: (T) -> List<String>): List<Indexed<T>> =
        rows.map { Indexed(it, SettingsSearchMatch.fold(fields(it).joinToString(" "))) }

    /**
     * The rows whose folded text contains the folded [query], in their original (ranked) order;
     * every row when the query folds to nothing (empty, spaces, a lone joiner).
     */
    fun <T> filter(rows: List<Indexed<T>>, query: String): List<T> {
        val q = SettingsSearchMatch.fold(query)
        if (q.isEmpty()) return rows.map { it.item }
        return rows.filter { it.folded.contains(q) }.map { it.item }
    }

    /** How many of [matchCount] rows the list shows after [pagesLoaded] pages (at least one page). */
    fun visibleCount(matchCount: Int, pagesLoaded: Int): Int {
        val pages = pagesLoaded.coerceAtLeast(1)
        val cap = if (pages > Int.MAX_VALUE / PAGE_SIZE) Int.MAX_VALUE else pages * PAGE_SIZE
        return minOf(matchCount.coerceAtLeast(0), cap)
    }

    /**
     * The page count after the list reports [lastVisibleIndex] (-1 when nothing is laid out):
     * one more page when that row is within [PREFETCH_DISTANCE] of the end of the shown rows
     * and more matches remain; otherwise [pagesLoaded] unchanged.
     */
    fun pagesAfterScroll(lastVisibleIndex: Int, matchCount: Int, pagesLoaded: Int): Int {
        val shown = visibleCount(matchCount, pagesLoaded)
        if (lastVisibleIndex < 0 || shown >= matchCount) return pagesLoaded.coerceAtLeast(1)
        return if (lastVisibleIndex >= shown - PREFETCH_DISTANCE) pagesLoaded.coerceAtLeast(1) + 1
        else pagesLoaded.coerceAtLeast(1)
    }

    /** The count line above the list: the total alone, or "N of M" while a query filters. */
    sealed interface CountLine {
        /** No active query: [total] rows. */
        data class Total(val total: Int) : CountLine

        /** A query matches [matches] of [total] rows. */
        data class Filtered(val matches: Int, val total: Int) : CountLine
    }

    /** [CountLine] for [query]: [CountLine.Filtered] whenever the query folds to something. */
    fun countLine(query: String, matches: Int, total: Int): CountLine =
        if (SettingsSearchMatch.fold(query).isEmpty()) CountLine.Total(total)
        else CountLine.Filtered(matches, total)
}
