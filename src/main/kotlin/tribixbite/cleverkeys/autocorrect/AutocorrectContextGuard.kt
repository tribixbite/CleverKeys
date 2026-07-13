package tribixbite.cleverkeys.autocorrect

/**
 * Detects non-prose typing contexts — URLs, emails, file paths, query strings,
 * version numbers — where autocorrect must not fire (reported 2026-07-13:
 * typing/editing URLs produced corrections like `foo.teh ` → `foo.the `,
 * breaking domains).
 *
 * The word tracker accumulates only LETTERS, so a completed word like `teh`
 * carries no knowledge of the glue characters around it. The signal lives in
 * the editor text: at autocorrect time the word and its trailing separator are
 * already committed, so the whitespace-delimited token that ends at the cursor
 * reveals the true context (`foo.teh`, `https://teh.example`, `user@teh`).
 *
 * Rule: skip autocorrect when that token contains a URL/path/identifier
 * character (`. / : @ # ? & = % ~ \`) or a digit. Apostrophes and hyphens are
 * deliberately NOT flagged — `well-knwon` and contraction typing should keep
 * autocorrecting.
 */
object AutocorrectContextGuard {

    private const val NON_PROSE_CHARS = "./:@#?&=%~\\"

    /**
     * @param textBeforeCursor editor text ending at the cursor, i.e. ending
     *   with the completed word plus the just-typed separator (usually a
     *   single space). Null/blank → not non-prose (fail open: autocorrect
     *   proceeds and other gates apply).
     * @return true when the token the cursor just left looks like a URL,
     *   email, path, or other non-prose identifier — autocorrect should skip.
     */
    fun isNonProseContext(textBeforeCursor: CharSequence?): Boolean {
        if (textBeforeCursor.isNullOrEmpty()) return false
        // Strip the single just-typed separator (space) if present.
        var end = textBeforeCursor.length
        if (textBeforeCursor[end - 1] == ' ') end--
        if (end == 0) return false
        // Walk back to the previous whitespace to isolate the token.
        var start = end
        while (start > 0 && !textBeforeCursor[start - 1].isWhitespace()) start--
        for (i in start until end) {
            val c = textBeforeCursor[i]
            if (c.isDigit() || c in NON_PROSE_CHARS) return true
        }
        return false
    }
}
