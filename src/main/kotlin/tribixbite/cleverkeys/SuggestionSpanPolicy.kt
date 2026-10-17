package tribixbite.cleverkeys

import android.text.InputType

/**
 * Which alternatives ride along with a committed swipe / autocorrect word as an
 * `android.text.style.SuggestionSpan`, so the editor's own "Replace" popup — and the
 * replaceTextMenu command, which opens it — offer them
 * (docs/research/2026-10-15-ime-editor-actions.md §1.2, §4.2 item 6).
 *
 * Pure: the span itself is built by [SuggestionSpanCommit]. InputType constants are
 * compile-time ints, so no Android class loads here.
 *
 * Bounded cost: at most [MAX_SUGGESTIONS] alternatives (the framework's
 * `SuggestionSpan.SUGGESTIONS_MAX_SIZE`), words of at most [MAX_WORD_LENGTH] chars, one span per
 * commit, and nothing at all when there is no alternative.
 */
object SuggestionSpanPolicy {

    /** `SuggestionSpan.SUGGESTIONS_MAX_SIZE`: the framework keeps no more than this. */
    const val MAX_SUGGESTIONS = 5

    /** Longer "words" are not prose and are never decorated or offered. */
    const val MAX_WORD_LENGTH = 48

    /** Span [start]..[end] (UTF-16, within the committed text) offering [suggestions]. */
    data class Plan(val start: Int, val end: Int, val suggestions: List<String>)

    /**
     * Whether an editor of [inputType] gets spans at all: a text field that allows suggestions
     * (no TYPE_TEXT_FLAG_NO_SUGGESTIONS) and is not a password, URI, e-mail address or filter
     * field — the same fields TextView's own spell checker and Replace popup serve. Terminal-
     * style (blind) editors and password fields are excluded by the caller before this.
     */
    fun fieldAccepts(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /**
     * The span for [word] inside [committed] (the word plus any auto-spaces), offering the
     * distinct [candidates] other than the word itself, in order. Special bar entries
     * ("Add to dictionary", "+word" …) and decoder `raw:` prefixes are dropped; null when no
     * alternative remains or the word cannot be located.
     */
    fun plan(committed: String, word: String, candidates: List<String>): Plan? {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return null
        val start = committed.indexOf(word)
        if (start < 0) return null
        val suggestions = LinkedHashSet<String>()
        for (candidate in candidates) {
            val parsed = Suggestion.parse(candidate) as? Suggestion.Word ?: continue
            val alternative = parsed.text.removePrefix("raw:").trim()
            if (alternative.isEmpty() || alternative.length > MAX_WORD_LENGTH) continue
            if (alternative.any { it.isWhitespace() } || alternative == word) continue
            suggestions += alternative
            if (suggestions.size == MAX_SUGGESTIONS) break
        }
        return if (suggestions.isEmpty()) null else Plan(start, start + word.length, suggestions.toList())
    }
}
