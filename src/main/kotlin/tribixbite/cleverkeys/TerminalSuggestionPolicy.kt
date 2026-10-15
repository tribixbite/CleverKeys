package tribixbite.cleverkeys

/**
 * What the suggestion bar may offer, and what a tap may write, in a terminal-style editor
 * ([TerminalUtils.editorKind] is `TERMINAL` or `REMOTE`). Pure, so the rule is pinned by pure
 * JVM tests ([TerminalSuggestionPolicyTest]) and the handler tests replay the device reports.
 *
 * ## Why a tap there never deletes (Saga/Termux 0.118.3, 2026-10-15)
 *
 * In these editors the IME's only record of the word being typed is its own keystroke tracker:
 * Termux clears its `Editable` after every commit (so `getTextBeforeCursor` reads "") and never
 * calls `InputMethodManager.updateSelection`, and a remote field forwards input elsewhere. The
 * app itself can change the line without the IME seeing anything:
 *
 *  - Termux's extra-keys toolbar keys (TAB, arrows, ESC …) go straight to the terminal: shell
 *    completion turned `ec` into `echo ` while the tracker still held `ec`, and the next `x`
 *    tracked `ecx` over a line ending `echo x`.
 *  - Termux's toolbar CTRL is applied INSIDE `commitText` (TerminalView `sendTextToTerminal` →
 *    `inputCodePoint`, which reads `readControlKey()`): the IME committed "c", the shell got ^C
 *    and a fresh prompt, and the tracker held `foocbar` over a line showing `bar`. That commit
 *    succeeds and looks exactly like a typed letter — it cannot be detected from the IME.
 *  - History recall, prompt redraws, a program reading raw keys: the same.
 *
 * Deleting `trackedWord.length` characters on a tap then removes text that may not be the
 * user's word at all (the `foodborne` tap sent seven Backspaces at a three-letter line). Since
 * no observable signal bounds what is still on the line, the safe rule is: **a tap in a
 * terminal-style editor writes only what follows the tracked word, and deletes nothing**.
 *
 *  - The bar offers a word only if it EXTENDS the tracked word (case-insensitive prefix), shown
 *    with the letters the user typed, so the chip is exactly what the line will read.
 *  - A tap commits the remainder ([TapPlan.Append]); with no tracked word it commits the word.
 *  - A word that would need a deletion (a correction, a contraction, a stale chip) is refused
 *    ([TapPlan.Refuse]): nothing is sent.
 *
 * If the tracker is stale the worst case is extra letters after whatever is on the line —
 * visible and removable with one Backspace each — never lost text.
 *
 * Not covered here: replacing a swipe-committed word ([SuggestionHandler.onSuggestionSelected]'s
 * REPLACE branch) and the swipe backspace-undo still delete that one atomic commit; see the
 * terminal integration spec for that residual risk.
 */
object TerminalSuggestionPolicy {

    /** What a tap may write after the tracked word. */
    sealed interface TapPlan {
        /** Commit [suffix] after the tracked word; the line then reads [word] there. */
        data class Append(val suffix: String, val word: String) : TapPlan

        /** The suggestion does not extend the tracked word: send nothing. */
        object Refuse : TapPlan
    }

    /**
     * [candidate] as a tap would leave it after [trackedWord] (the typed letters keep their
     * case), or null when it does not extend [trackedWord].
     */
    fun extension(trackedWord: String, candidate: String): String? {
        if (candidate.length < trackedWord.length) return null
        if (!candidate.regionMatches(0, trackedWord, 0, trackedWord.length, ignoreCase = true)) return null
        return trackedWord + candidate.substring(trackedWord.length)
    }

    /** The write for a tap on [selected] while [trackedWord] is the tracked word. */
    fun planTap(trackedWord: String, selected: String): TapPlan {
        if (trackedWord.isEmpty()) return TapPlan.Append(selected, selected)
        val word = extension(trackedWord, selected) ?: return TapPlan.Refuse
        return TapPlan.Append(word.substring(trackedWord.length), word)
    }

    /** A bar row set: parallel word / score / provenance lists. */
    data class BarRows(val words: List<String>, val scores: List<Int>, val metas: List<SuggestionMeta>)

    /**
     * The bar rows a terminal-style editor may show for [trackedWord]: extensions only, each in
     * the form the tap produces, first occurrence wins (case-insensitively). The exact-add chip is kept — its tap adds
     * the word to the dictionary and commits only a space there.
     */
    fun restrictBar(trackedWord: String, rows: BarRows): BarRows {
        val words = ArrayList<String>()
        val scores = ArrayList<Int>()
        val metas = ArrayList<SuggestionMeta>()
        rows.words.forEachIndexed { i, candidate ->
            val meta = rows.metas.getOrNull(i) ?: SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX)
            val shown = if (meta.origin == SuggestionOrigin.EXACT_ADD) candidate else extension(trackedWord, candidate)
            // Case variants of one extension ("Hello"/"HELLO" after "HEl") produce one chip.
            if (shown == null || words.any { it.equals(shown, ignoreCase = true) }) return@forEachIndexed
            words += shown
            scores += rows.scores.getOrElse(i) { 0 }
            metas += meta
        }
        return BarRows(words, scores, metas)
    }
}
