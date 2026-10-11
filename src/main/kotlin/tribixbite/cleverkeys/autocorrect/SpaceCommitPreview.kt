package tribixbite.cleverkeys.autocorrect

import tribixbite.cleverkeys.ProvenanceNote
import tribixbite.cleverkeys.Suggestion
import tribixbite.cleverkeys.SuggestionMeta
import tribixbite.cleverkeys.SuggestionOrigin

/**
 * What pressing SPACE will commit for the tap-typed word being composed (Pixel report
 * 2026-10-11: typing `Helo` highlighted `Hello` in the bar, and space committed `Help`).
 *
 * The suggestion bar used to highlight its first entry — whatever the prediction ranker put
 * there — while space ran a different ranker (`WordPredictor.autoCorrect`). On 2,780 real
 * misspellings the two disagreed 2,053 times (`docs/eval/2026-10-11-dropped-double-autocorrect.md`
 * §2.5). Maintainer decision: the highlighted entry IS what space commits. So both the bar and
 * the space path ask [SpaceCommitResolver.resolve] — one function, one set of guards — and the
 * bar arranges itself around the answer with [BarHighlightPlanner.plan].
 */
data class SpaceCommit(val kind: Kind, val commit: String) {
    enum class Kind {
        /** Space commits the typed word unchanged. */
        KEEP,

        /** Issue #72: an I-word ("i", "im") is re-cased to "I"/"I'm" — checked before autocorrect. */
        CAPITALIZE_I,

        /** Autocorrect replaces the typed word with [commit]. */
        AUTOCORRECT,
    }

    /** True when space will write something other than the typed text. */
    val replaces: Boolean get() = kind != Kind.KEEP
}

/**
 * The single decision for "what does space commit for [typed]". Pure: every editor/predictor
 * dependency is injected, so the bar (worker thread, per keystroke) and the space path (main
 * thread) evaluate exactly the same rule, and JVM tests can drive it with the real dictionary.
 *
 * Guard order mirrors the space path in `SuggestionHandler.handleRegularTyping` as it was
 * before this extraction: I-word capitalization first (works even with autocorrect off), then
 * autocorrect gated on the setting, the terminal-editor exclusion, the non-prose token guard
 * and the unmerged-joiner-stem fragment guard. The predictor's own guards (min length,
 * already-valid word, frequency floor, disabled/custom words, morphology, possessives) live
 * inside `autoCorrect` and are not duplicated here.
 */
object SpaceCommitResolver {

    /**
     * @param typed the tracked word exactly as typed (case preserved).
     * @param autocorrectEnabled `config.autocorrect_enabled`.
     * @param inTerminal the editor is a terminal (autocorrect and I-capitalization are off there).
     * @param stemUnmerged the word is only the tail after an apostrophe/hyphen joiner (W7):
     *   a fragment that must be neither autocorrected nor I-capitalized on its own.
     * @param capitalizeI the I-word re-caser (identity when the setting is off).
     * @param isNonProse lazily reads the editor token at the cursor and reports a URL/email/path
     *   context ([AutocorrectContextGuard.isNonProseContext]). Only invoked when autocorrect
     *   would otherwise replace the word, so the common no-correction keystroke does no editor
     *   read.
     * @param autoCorrect the predictor's correction (returns its input when it would not correct).
     */
    fun resolve(
        typed: String,
        autocorrectEnabled: Boolean,
        inTerminal: Boolean,
        stemUnmerged: Boolean,
        capitalizeI: (String) -> String,
        isNonProse: () -> Boolean,
        autoCorrect: (String) -> String,
    ): SpaceCommit {
        if (typed.isEmpty() || inTerminal || stemUnmerged) return SpaceCommit(SpaceCommit.Kind.KEEP, typed)

        val iWord = capitalizeI(typed)
        if (iWord != typed) return SpaceCommit(SpaceCommit.Kind.CAPITALIZE_I, iWord)

        if (autocorrectEnabled) {
            val corrected = autoCorrect(typed)
            if (corrected != typed && !isNonProse()) {
                // Same post-processing the space path always applied to a correction.
                val committed = capitalizeI(preserveCapitalization(typed, corrected))
                return SpaceCommit(SpaceCommit.Kind.AUTOCORRECT, committed)
            }
        }
        return SpaceCommit(SpaceCommit.Kind.KEEP, typed)
    }

    /**
     * Carry the typed word's capitalization pattern onto a correction: ALL CAPS ("TEH" → "THE"),
     * Title Case ("Teh" → "The"), otherwise the correction as given.
     */
    fun preserveCapitalization(original: String, corrected: String): String {
        if (original.isEmpty() || corrected.isEmpty()) return corrected
        return when {
            original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            original[0].isUpperCase() -> corrected.replaceFirstChar { it.uppercaseChar() }
            else -> corrected
        }
    }
}

/**
 * Arranges the tap-typing bar so its highlighted entry is what space will commit.
 *
 * - A replacement ([SpaceCommit.replaces]) goes to slot 0 and is highlighted: an entry already
 *   listed under any casing is MOVED (keeping its score and provenance) and shown exactly as it
 *   will be committed; an absent one is INSERTED with a [SuggestionOrigin.TYPO_CORRECTION] meta
 *   naming the typed text. Nothing else is reordered or dropped — the prediction ranking is
 *   untouched, only the commit target is pulled forward.
 * - No replacement: the highlight goes to the entry that IS the typed word (exact text), wherever
 *   the ranker put it, or to nothing when the typed word is not listed. A completion such as
 *   `hello` for typed `hel` is never highlighted, because space would not commit it.
 */
object BarHighlightPlanner {

    data class Plan(
        val words: List<String>,
        val scores: List<Int>,
        val metas: List<SuggestionMeta>,
        /** Index of the entry rendered as the "space commits this" item, or null for none. */
        val highlightIndex: Int?,
    )

    /**
     * @param words/scores/metas the bar lists, aligned (same size).
     * @param typed the typed word as tracked (case preserved).
     * @param commit the [SpaceCommitResolver] answer for [typed].
     */
    fun plan(
        words: List<String>,
        scores: List<Int>,
        metas: List<SuggestionMeta>,
        typed: String,
        commit: SpaceCommit,
    ): Plan {
        require(words.size == scores.size && words.size == metas.size) {
            "bar lists must be aligned (words=${words.size} scores=${scores.size} metas=${metas.size})"
        }
        if (!commit.replaces) {
            val idx = words.indexOfFirst { it == typed && isPlainWord(it) }
            return Plan(words, scores, metas, idx.takeIf { it >= 0 })
        }

        val target = commit.commit
        val existing = words.indexOfFirst { isPlainWord(it) && it.equals(target, ignoreCase = true) }
        val outWords = words.toMutableList()
        val outScores = scores.toMutableList()
        val outMetas = metas.toMutableList()
        if (existing >= 0) {
            outWords.removeAt(existing)
            val score = outScores.removeAt(existing)
            val meta = outMetas.removeAt(existing)
            outWords.add(0, target)
            outScores.add(0, score)
            outMetas.add(0, meta)
        } else {
            outWords.add(0, target)
            // Never outranked on the debug-score display by an entry it is placed before.
            outScores.add(0, scores.maxOrNull() ?: 0)
            outMetas.add(
                0,
                if (commit.kind == SpaceCommit.Kind.AUTOCORRECT) {
                    SuggestionMeta(SuggestionOrigin.TYPO_CORRECTION, note = ProvenanceNote.TypoCorrectionOf(typed))
                } else {
                    SuggestionMeta(SuggestionOrigin.DICTIONARY_PREFIX)
                }
            )
        }
        return Plan(outWords, outScores, outMetas, 0)
    }

    /** Protocol entries (add-to-dictionary, exact-add, swipe preference) are never commit targets. */
    private fun isPlainWord(wire: String): Boolean = Suggestion.parse(wire) is Suggestion.Word
}

/**
 * Memo of `autoCorrect(word)` so the space path reuses the result the bar computed for the same
 * word instead of sweeping the dictionary a second time (and so the two cannot diverge through a
 * second evaluation). Keyed on the exact word, the predictor instance and a [fingerprint] of every
 * setting `autoCorrect` reads; any mismatch recomputes. Dictionary edits are covered by the
 * caller clearing the memo at every new word and at every cursor-sync re-read ([clear]).
 *
 * Thread-safe: the bar fills it on the prediction worker, the space path reads it on the main
 * thread; the single entry is an immutable snapshot behind a volatile reference.
 */
class AutocorrectMemo {
    private data class Entry(val word: String, val owner: Any, val fingerprint: Any, val result: String)

    @Volatile private var entry: Entry? = null

    /** Returns the memoized correction for [word], computing (and storing) it on a miss. */
    fun get(word: String, owner: Any, fingerprint: Any, compute: (String) -> String): String {
        entry?.let { e ->
            if (e.word == word && e.owner === owner && e.fingerprint == fingerprint) return e.result
        }
        val result = compute(word)
        entry = Entry(word, owner, fingerprint, result)
        return result
    }

    fun clear() {
        entry = null
    }
}
