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

    /**
     * Decides whether the "Add to dictionary?" prompt should be offered for a
     * word the user just completed with a space (UT-2/UT-3, 2026-07-15).
     *
     * Pure decision function — dictionary state is injected via callbacks so
     * the logic is JVM-testable without Android.
     *
     * @param token the completed word as tracked by the word tracker (may
     *   contain apostrophes when it was rebuilt via cursor sync, e.g.
     *   "ember's").
     * @param inNonProseToken result of [isNonProseContext] on the editor text
     *   at the cursor — when true the token is a URL/email/path fragment and
     *   must never prompt (UT-3).
     * @param isKnownWord true when the given word is in the main dictionary
     *   or the user dictionary (caller folds both, mirroring the original
     *   `isInDictionary || isUserWord` gate).
     * @param isDisabledWord true when the word is user-disabled. A possessive
     *   of a DISABLED base is not "known" — it still prompts (consistent with
     *   the UT-8 autocorrect rule: disabling a word removes its possessive
     *   protection too).
     * @return true → show the prompt; false → the token is known/non-prose.
     */
    fun shouldOfferAddToDictionary(
        token: String,
        inNonProseToken: Boolean,
        isKnownWord: (String) -> Boolean,
        isDisabledWord: (String) -> Boolean = { false },
    ): Boolean {
        // Original gate: short tokens never prompt, known tokens never prompt.
        if (token.length < 3) return false
        // UT-3: URL/email/path fragments are not prose words — never prompt.
        if (inNonProseToken) return false
        if (isKnownWord(token)) return false
        // UT-2: possessive whose BASE is a known word is valid English, not an
        // unknown word. Mirrors WordPredictor.autoCorrect step 1.6 exactly:
        // last apostrophe (straight U+0027 or curly U+2019) at index >= 2,
        // suffix "s" (singular possessive) or empty (plural possessive,
        // "rivers'"). Other suffixes ("'ll", "'nt") stay on the unknown path.
        // A DISABLED base gives no protection (UT-8 parity) — still prompts.
        val apostropheIdx = token.indexOfLast { it == '\'' || it == '’' }
        if (apostropheIdx >= 2) {
            val suffix = token.substring(apostropheIdx + 1).lowercase()
            if (suffix == "s" || suffix.isEmpty()) {
                val base = token.substring(0, apostropheIdx)
                if (!isDisabledWord(base) && isKnownWord(base)) return false
            }
        }
        return true
    }
}
