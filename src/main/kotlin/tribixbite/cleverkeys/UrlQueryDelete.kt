package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection

/**
 * Backward word deletion inside a URL: one press removes exactly one key/value pair of the query,
 * so a long tracking link is cleaned pair by pair (maintainer request 2026-11-22). Without it the
 * app's Ctrl+Backspace removed `email`, then `=`, then `utm_medium` … and `delete_last_word`
 * removed back to the last space, capped at 50 characters: an arbitrary slice of the link.
 *
 * A URL is a run of non-space characters holding a scheme and `://` (`https://`, `market://`) or
 * `www.`; text that is only `a=1&b=2` is not one. Its query starts at the first `?` after that
 * start and ends at the first `#`; pairs are separated by `&`. A press in the query removes the
 * pair holding the caret, the text after the caret included, or the pair just before it when the
 * caret sits right after a `&`. The removal keeps the link valid:
 * - a pair with a later pair after it goes with the `&` after it (`?a=1&b=2` → `?b=2`);
 * - the last pair goes with the `&` or `?` before it (`?a=1&b=2` → `?a=1`, `?a=1` → no query).
 * A caret in the fragment removes the whole fragment from its `#`. Spaces between the link's end
 * and the caret go with the deletion, as a word delete takes the spaces after a word. Sentence
 * punctuation and an unbalanced closing bracket or quote after the link are not the link's
 * (`(see https://x.example/p?a=1)`), as linkifiers trim them; a caret after them is outside it.
 *
 * Android-free (offsets are window-local UTF-16 indices); [EditorUrlQueryDelete] applies it to an
 * app's editor, [InlineWordDelete] to the inline clipboard fields.
 */
object UrlQueryDelete {

    /** How far each side of the caret the editor is read: long tracking links fit. */
    const val REACH = 4096

    /** A URL's start: a scheme and `://`, or `www.`, not inside a longer scheme-like run. */
    private val URL_START = Regex("(?i)(?<![a-z0-9+.\\-])(?:[a-z][a-z0-9+.\\-]*://|www\\.)")

    /**
     * The range [start, end) of [text] one backward word delete at [caret] removes, or null when
     * the caret is not in (or just after) a URL's query or fragment: the caller then deletes a
     * word its usual way. Also null when the link runs past a cut edge of what was read
     * ([beforeComplete] / [afterComplete] false), as its start or its next pair is then unknown.
     */
    fun range(text: CharSequence, caret: Int, beforeComplete: Boolean = true, afterComplete: Boolean = true): IntRange? {
        val at = caret.coerceIn(0, text.length)
        // Spaces after the link go with it; the link then ends where they start.
        var end = at
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return null
        val spaced = end < at
        var tokenStart = end
        while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) tokenStart--
        if (tokenStart == 0 && !beforeComplete) return null
        // With spaces before the caret the link ends at them; otherwise it may run on after the caret.
        var tokenEnd = end
        if (!spaced) {
            while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
            if (tokenEnd == text.length && !afterComplete) return null
        }
        val match = URL_START.find(text.subSequence(tokenStart, tokenEnd)) ?: return null
        val urlStart = tokenStart + match.range.first
        if (urlStart >= end) return null
        val afterScheme = tokenStart + match.range.last + 1
        val urlEnd = trimTrailing(text, afterScheme, tokenEnd)
        if (end > urlEnd) return null
        // The first '#' ends the query; a '?' after it belongs to the fragment.
        val hash = indexOf(text, '#', afterScheme, urlEnd)
        val queryEnd = if (hash < 0) urlEnd else hash
        if (hash in 0 until end) return hash until if (spaced) at else urlEnd
        val question = indexOf(text, '?', afterScheme, queryEnd)
        if (question < 0 || question >= end) return null
        // Delimiters: the '?' then every '&' of the query; pair i runs from delimiters[i] + 1 to
        // the next delimiter (or the query's end).
        val delimiters = ArrayList<Int>()
        delimiters += question
        for (i in question + 1 until queryEnd) if (text[i] == '&') delimiters += i
        fun pairEnd(i: Int): Int = if (i + 1 < delimiters.size) delimiters[i + 1] else queryEnd
        // The pair holding the caret: the last delimiter before it starts that pair.
        var target = delimiters.indexOfLast { it < end }
        // Right after a '&' with a pair following, the caret is between two pairs: the one before goes.
        if (target >= 1 && end == delimiters[target] + 1 && end < pairEnd(target)) target--
        val removed = if (target + 1 < delimiters.size) {
            // A later pair exists: this pair and the '&' after it.
            (delimiters[target] + 1) until (delimiters[target + 1] + 1)
        } else {
            // The last pair: the '&' or '?' before it and the pair.
            delimiters[target] until queryEnd
        }
        return if (spaced) removed.first until at else removed
    }

    /** [range] over an editor window, as absolute offsets; null for a selection. */
    fun plan(w: EditorTextWindow): IntRange? {
        if (!w.collapsed) return null
        val local = range(w.text, w.before.length, w.beforeComplete, w.afterComplete) ?: return null
        return (w.base + local.first)..(w.base + local.last)
    }

    /** What running text puts after a link that is not the link's: sentence punctuation and quotes. */
    private const val TRAILING = ".,;:!'\"“”‘’"

    /** Closing brackets, each trimmed only when the link holds fewer of its opener. */
    private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')

    /** Where the link in [text] from [from] to [to] really ends, before what [TRAILING] / [CLOSERS] trim. */
    private fun trimTrailing(text: CharSequence, from: Int, to: Int): Int {
        var e = to
        while (e > from) {
            val c = text[e - 1]
            val opener = CLOSERS[c]
            val trim = if (opener != null) {
                (from until e).count { text[it] == c } > (from until e).count { text[it] == opener }
            } else {
                c in TRAILING
            }
            if (!trim) break
            e--
        }
        return e
    }

    private fun indexOf(text: CharSequence, c: Char, from: Int, to: Int): Int {
        for (i in from until to) if (text[i] == c) return i
        return -1
    }
}

/**
 * [UrlQueryDelete] applied to an app's editor, for the `delete_word` key and `delete_last_word`
 * (the backspace swipe). Kept out of the pure object so pure tests never load InputConnection.
 *
 * The edit follows CLAUDE.md "Explicit editor edits and composition": the window is read, the
 * composition finished, and the window must read back unchanged before the one
 * `deleteSurroundingText`. Callers keep terminal-style editors away: their text cannot be read.
 */
internal object EditorUrlQueryDelete {

    /**
     * Delete one URL query pair at the caret. True when it was deleted; false when the caret is not
     * in a URL query, the editor could not be read, or it changed under the read: the caller then
     * deletes a word its usual way.
     */
    fun apply(conn: InputConnection): Boolean = runCatching {
        val window = EditorTextWindowReader.capture(conn, UrlQueryDelete.REACH) ?: return@runCatching false
        val range = UrlQueryDelete.plan(window) ?: return@runCatching false
        if (!conn.finishComposingText() || EditorTextWindowReader.capture(conn, UrlQueryDelete.REACH) != window) {
            return@runCatching false
        }
        val caret = window.min
        conn.deleteSurroundingText(caret - range.first, range.last + 1 - caret)
    }.getOrDefault(false)
}
