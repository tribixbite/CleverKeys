package tribixbite.cleverkeys.stt

/**
 * Where dictated text goes, as the voice controller classifies the focused editor.
 * Mirrors the keyboard's own routing rules (.claude/skills/ime-key-routing.md).
 */
enum class VoiceFieldKind {
    /** An ordinary app editor: readable, composing text allowed. */
    TEXT,

    /**
     * A terminal or a blind REMOTE field (`TerminalUtils.editorKind` != TEXT): nothing may be
     * rewritten once sent, so only final words are appended and no composing span is used.
     */
    BLIND,

    /**
     * One of the keyboard's own inline fields (clipboard search/editor/tag, emoji or GIF
     * search). They have no composing spans; final words are inserted through the inline-field
     * path, partials are shown in the suggestion bar only.
     */
    INLINE,

    /** A password field: dictation is refused (speech is audible; the field is secret). */
    PASSWORD,
}

/** One edit the controller applies to the field, in order. */
sealed interface VoiceTextOp {
    /** Insert [text] permanently. In a TEXT field it replaces any composing span. */
    data class Commit(val text: String) : VoiceTextOp

    /** Show [text] as the (underlined, replaceable) composing span. TEXT fields only. */
    data class SetComposing(val text: String) : VoiceTextOp

    /** Remove the composing span's text without committing it (cancel, or empty final). */
    data object ClearComposing : VoiceTextOp
}

/**
 * Turns a listening period's hypotheses into editor operations for one field (pure;
 * `VoiceTextPolicyTest`).
 *
 * A period is one or more speech segments (FR-10): each segment's hypotheses come from a fresh
 * engine session and count words from zero; [onSegmentEnd] commits a finished segment and the
 * next starts at the end of what was written. The period's last segment ends with the final
 * hypothesis through [onHypothesis].
 *
 * Conventions applied, all read from the text before the cursor captured when listening
 * started ([textBefore]; null for BLIND fields, which are never read) followed by everything
 * this planner committed since (the keyboard knows its own text, even in a blind field):
 *  - **Spacing**: a leading space when the dictation would otherwise fuse with the preceding
 *    character; words of one segment are joined by single spaces; [trailingSpace] (the user's
 *    "auto space after a suggestion") adds one after each segment's final text in TEXT fields,
 *    so the next segment needs no leading space and none is ever doubled.
 *  - **Capitalisation**: engines capitalise each utterance's (so each segment's) first word.
 *    Mid-sentence (the text before does not end a sentence) that capital is undone when
 *    [matchSentenceCase] is on — except for "I"-forms and words that are not plain capitalised
 *    (acronyms, "iPhone"). At a sentence start (after the engine's own `.`, `?`, `!` too) the
 *    first letter is capitalised. A blind field's first segment keeps the engine's casing (no
 *    context); later segments follow the planner's own text.
 *  - **Stability**: stable words are committed as they arrive; in TEXT fields the pending tail
 *    is composing text. BLIND and INLINE fields receive stable words only (append-only).
 *  - **Cancel** drops the composing tail; committed words stay (they were the user's to keep).
 */
class VoiceTextPlanner(
    private val kind: VoiceFieldKind,
    private val textBefore: String?,
    private val matchSentenceCase: Boolean = true,
    private val trailingSpace: Boolean = false,
) {
    /** Words of the current segment's hypothesis already committed to the field. */
    private var committedWords = 0

    /** True once anything was committed in the current segment. */
    private var segmentSent = false

    /** True while a composing span holds text. */
    private var composing = false

    /** Everything committed this period (the keyboard's own text after [textBefore]). */
    private val written = StringBuilder()

    /** The text before the current segment: null only for a blind field before any commit. */
    private var segmentContext: String? = textBefore

    /** True once the final hypothesis was applied; later calls are no-ops. */
    var finished = false
        private set

    /** The operations for [hypothesis] (a partial or the period's final result). */
    fun onHypothesis(hypothesis: SpeechHypothesis): List<VoiceTextOp> {
        if (finished || kind == VoiceFieldKind.PASSWORD) return emptyList()
        val words = casedWords(hypothesis)
        if (hypothesis.isFinal) {
            finished = true
            return finalOps(words)
        }
        val stable = hypothesis.stableWordCount.coerceIn(0, words.size)
        val ops = ArrayList<VoiceTextOp>(3)
        commitThrough(words, stable, ops)
        if (kind == VoiceFieldKind.TEXT) {
            val pending = words.size - committedWords
            if (pending > 0) {
                ops += VoiceTextOp.SetComposing(joinFrom(words, committedWords, words.size))
                composing = true
            } else if (composing) {
                ops += VoiceTextOp.ClearComposing
                composing = false
            }
        }
        return ops
    }

    /**
     * A segment ended with [hypothesis] (every word final) and the period goes on: commit it,
     * then start the next segment after it.
     */
    fun onSegmentEnd(hypothesis: SpeechHypothesis): List<VoiceTextOp> {
        if (finished || kind == VoiceFieldKind.PASSWORD) return emptyList()
        val ops = finalOps(casedWords(hypothesis))
        committedWords = 0
        segmentSent = false
        segmentContext = if (textBefore == null && written.isEmpty()) null else (textBefore ?: "") + written
        return ops
    }

    /** The operations that abandon the session: the composing tail goes, commits stay. */
    fun onCancel(): List<VoiceTextOp> {
        finished = true
        if (!composing) return emptyList()
        composing = false
        return listOf(VoiceTextOp.ClearComposing)
    }

    private fun casedWords(hypothesis: SpeechHypothesis): MutableList<String> {
        val words = hypothesis.words.toMutableList()
        if (words.isNotEmpty()) words[0] = caseFirstWord(words[0])
        return words
    }

    /** Commit every word of a segment's final, drop a leftover composing span, auto-space. */
    private fun finalOps(words: List<String>): List<VoiceTextOp> {
        val ops = ArrayList<VoiceTextOp>(3)
        commitThrough(words, words.size, ops)
        if (composing) {
            ops += VoiceTextOp.ClearComposing
            composing = false
        }
        if (trailingSpace && kind == VoiceFieldKind.TEXT && segmentSent) commit(" ", ops)
        return ops
    }

    /** Commit words up to [stable] that are not committed yet. */
    private fun commitThrough(words: List<String>, stable: Int, ops: MutableList<VoiceTextOp>) {
        if (stable <= committedWords) return
        commit(joinFrom(words, committedWords, stable), ops)
        committedWords = stable
        segmentSent = true
        composing = false
    }

    private fun commit(text: String, ops: MutableList<VoiceTextOp>) {
        ops += VoiceTextOp.Commit(text)
        written.append(text)
    }

    /**
     * Words [from, to) joined with spaces, with the separator the field needs before them:
     * a space between this segment's pieces, or before its first piece when the text before
     * it ends in a non-space character.
     */
    private fun joinFrom(words: List<String>, from: Int, to: Int): String {
        val body = words.subList(from, to).joinToString(" ")
        val lead = when {
            from > 0 -> " "
            !segmentSent && needsLeadingSpace(body) -> " "
            else -> ""
        }
        return lead + body
    }

    private fun needsLeadingSpace(body: String): Boolean {
        val before = segmentContext ?: return false // BLIND, nothing written yet: never guessed
        if (before.isEmpty() || before.last().isWhitespace()) return false
        // Punctuation that attaches to the previous word ("." "," "?" …) takes no space.
        return body.firstOrNull()?.let { it.isLetterOrDigit() || it in OPENING } ?: false
    }

    private fun caseFirstWord(word: String): String {
        val before = segmentContext ?: return word // BLIND, nothing written yet: keep the engine's casing
        val sentenceStart = SENTENCE_START.containsMatchIn(before) || before.isBlank()
        return when {
            sentenceStart -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            !matchSentenceCase -> word
            isPlainCapitalised(word) && !isFirstPersonI(word) -> word.replaceFirstChar { it.lowercase() }
            else -> word
        }
    }

    companion object {
        /** Characters that open a token and so take a space before them. */
        private const val OPENING = "\"'“‘([{¿¡"

        /** Text before the cursor that ends a sentence (or a line) — capitalise after it. */
        private val SENTENCE_START = Regex("(?:[.!?…]\\s*|\\n\\s*)$")

        /** "Hello", "Hello,", "A" — one leading capital then only non-uppercase letters. */
        fun isPlainCapitalised(word: String): Boolean {
            val letters = word.filter { it.isLetter() }
            return letters.isNotEmpty() && letters[0].isUpperCase() && letters.drop(1).none { it.isUpperCase() }
        }

        /** "I", "I'm", "I'll", "I've", "I'd" (straight or curly apostrophe). */
        fun isFirstPersonI(word: String): Boolean {
            val core = word.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }
            return core == "I" || Regex("^I['’](m|ll|ve|d)$").matches(core)
        }
    }
}
