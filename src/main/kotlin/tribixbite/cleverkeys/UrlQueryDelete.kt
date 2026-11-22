package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection

/**
 * Backward word deletion in text made of `key=value` pairs: one press removes exactly one pair, so
 * a long tracking link is cleaned pair by pair (maintainer requests 2026-11-22: first for links,
 * then "any text strings containing &key=value instead of requiring https or www"). Without it the
 * app's Ctrl+Backspace removed `email`, then `=`, then `utm_medium` … and `delete_last_word`
 * removed back to the last space, capped at 50 characters: an arbitrary slice of the link.
 * Settings → Input Behavior → "Delete one key=value pair at a time" (`delete_word_url_pairs`,
 * default on) turns it off; the callers check it.
 *
 * The run is the non-space text at the caret. It qualifies when it is a link (it holds a scheme and
 * `://`, as `https://` or `market://`, or `www.`) or when it holds a pair after `?` or `&`
 * (`a=1&b=2`, `example.com/?q=red`, `mailto:x@y?subject=hi&body=yo`); `Tom&Jerry` and `x=1` do
 * not. Its pairs start after its first `?`, or without one at the run's start (past an opening
 * bracket or quote; for a link, at its scheme), end at its first `#` and are separated by `&`. A
 * press removes the pair holding the caret, the text after the caret included, or the pair just
 * before it when the caret sits right after a `&`. The removal keeps a link valid:
 * - a pair with a later pair after it goes with the `&` after it (`?a=1&b=2` → `?b=2`);
 * - the last pair goes with the `&` or `?` before it (`?a=1&b=2` → `?a=1`, `?a=1` → no query).
 * Without a `?`, what comes before the first `&` is a pair only when it holds a `=` (`a=1&b=2`, not
 * `example.com/path&x=1`). A caret in the fragment removes the whole fragment from its `#`. Spaces
 * between the run's end and the caret go with the deletion, as a word delete takes the spaces
 * after a word. Sentence punctuation and an unbalanced closing bracket or quote after the run are
 * not the run's (`(see https://x.example/p?a=1)`), as linkifiers trim them; a caret after them is
 * outside it.
 *
 * Android-free (offsets are window-local UTF-16 indices); [EditorUrlQueryDelete] applies it to an
 * app's editor, [InlineWordDelete] to the inline clipboard fields.
 */
object UrlQueryDelete {

    /** How far each side of the caret the editor is read: long tracking links fit. */
    const val REACH = 4096

    /** A URL's start: a scheme and `://`, or `www.`, not inside a longer scheme-like run. */
    private val URL_START = Regex("(?i)(?<![a-z0-9+.\\-])(?:[a-z][a-z0-9+.\\-]*://|www\\.)")

    /** A pair after a delimiter: `?` or `&`, a key without spaces or delimiters, then `=`. */
    private val PAIR_MARK = Regex("[?&][^\\s?&#=]+=")

    /** What may open a run of pairs in running text without being part of its first pair. */
    private const val OPENERS = "([{<\"'“‘"

    /**
     * The range [start, end) of [text] one backward word delete at [caret] removes, or null when
     * the caret is not in (or just after) a run of pairs or a link's fragment: the caller then
     * deletes a word its usual way. Also null when the run reaches a cut edge of what was read
     * ([beforeComplete] / [afterComplete] false), as its start or its next pair is then unknown.
     */
    fun range(text: CharSequence, caret: Int, beforeComplete: Boolean = true, afterComplete: Boolean = true): IntRange? {
        val at = caret.coerceIn(0, text.length)
        // Spaces after the run go with it; the run then ends where they start.
        var end = at
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return null
        val spaced = end < at
        var tokenStart = end
        while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) tokenStart--
        if (tokenStart == 0 && !beforeComplete) return null
        // With spaces before the caret the run ends at them; otherwise it may go on after the caret.
        var tokenEnd = end
        if (!spaced) {
            while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
            if (tokenEnd == text.length && !afterComplete) return null
        }
        // A link starts at its scheme or `www.`; other text at its first character past openers.
        val link = URL_START.find(text.subSequence(tokenStart, tokenEnd))
        var runStart = tokenStart
        if (link != null) runStart += link.range.first
        else while (runStart < tokenEnd && text[runStart] in OPENERS) runStart++
        if (runStart >= end) return null
        val bodyStart = if (link != null) tokenStart + link.range.last + 1 else runStart
        val runEnd = trimTrailing(text, bodyStart, tokenEnd)
        if (end > runEnd) return null
        if (link == null && !PAIR_MARK.containsMatchIn(text.subSequence(runStart, runEnd))) return null
        // The first '#' ends the pairs; a '?' or '&' after it belongs to the fragment.
        val hash = indexOf(text, '#', bodyStart, runEnd)
        val pairsEnd = if (hash < 0) runEnd else hash
        if (hash in 0 until end) return hash until if (spaced) at else runEnd
        val question = indexOf(text, '?', bodyStart, pairsEnd)
        if (question >= end) return null
        // Delimiters: the '?' — or, without one, the position just before the run, so its first
        // pair starts the run — then every '&'; pair i runs from delimiters[i] + 1 to the next
        // delimiter (or the end of the pairs).
        val first = if (question >= 0) question else runStart - 1
        val delimiters = ArrayList<Int>()
        delimiters += first
        for (i in first + 1 until pairsEnd) if (text[i] == '&') delimiters += i
        fun pairEnd(i: Int): Int = if (i + 1 < delimiters.size) delimiters[i + 1] else pairsEnd
        // The pair holding the caret: the last delimiter before it starts that pair.
        var target = delimiters.indexOfLast { it < end }
        // Right after a '&' with a pair following, the caret is between two pairs: the one before goes.
        if (target >= 1 && end == delimiters[target] + 1 && end < pairEnd(target)) target--
        // Without a '?', the text before the first '&' is a pair only when it holds a '=', and a
        // run of a single segment has no pair to remove.
        if (question < 0 && target == 0 &&
            (delimiters.size == 1 || indexOf(text, '=', runStart, delimiters[1]) < 0)
        ) return null
        val removed = if (target + 1 < delimiters.size) {
            // A later pair exists: this pair and the '&' after it.
            (delimiters[target] + 1) until (delimiters[target + 1] + 1)
        } else {
            // The last pair: the '&' or '?' before it and the pair.
            delimiters[target] until pairsEnd
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
