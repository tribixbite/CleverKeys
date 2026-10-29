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
 * Turns a session's hypotheses into editor operations for one field (pure; `VoiceTextPolicyTest`).
 *
 * Conventions applied, all read from the text before the cursor captured when listening
 * started ([textBefore]; null for BLIND fields, which are never read):
 *  - **Spacing**: a leading space when the dictation would otherwise fuse with the preceding
 *    character; words of one session are joined by single spaces; [trailingSpace] (the user's
 *    "auto space after a suggestion") adds one after the final text in TEXT fields.
 *  - **Capitalisation**: engines capitalise each utterance's first word. Mid-sentence (the text
 *    before does not end a sentence) that capital is undone when [matchSentenceCase] is on —
 *    except for "I"-forms and words that are not plain capitalised (acronyms, "iPhone").
 *    At a sentence start the first letter is capitalised.
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
    /** Words of the current hypothesis already committed to the field. */
    private var committedWords = 0

    /** True once anything (committed or composing) was sent this session. */
    private var sentAnything = false

    /** True while a composing span holds text. */
    private var composing = false

    /** True once the final hypothesis was applied; later calls are no-ops. */
    var finished = false
        private set

    /** The operations for [hypothesis] (a partial or the final result). */
    fun onHypothesis(hypothesis: SpeechHypothesis): List<VoiceTextOp> {
        if (finished || kind == VoiceFieldKind.PASSWORD) return emptyList()
        val words = hypothesis.words.toMutableList()
        if (words.isNotEmpty()) words[0] = caseFirstWord(words[0])
        val stable = if (hypothesis.isFinal) words.size else hypothesis.stableWordCount.coerceIn(0, words.size)
        val ops = ArrayList<VoiceTextOp>(3)

        if (stable > committedWords) {
            val text = joinFrom(words, committedWords, stable)
            ops += VoiceTextOp.Commit(text)
            committedWords = stable
            sentAnything = true
            composing = false
        }

        if (hypothesis.isFinal) {
            finished = true
            if (composing) {
                ops += VoiceTextOp.ClearComposing
                composing = false
            }
            if (trailingSpace && kind == VoiceFieldKind.TEXT && committedWords > 0) {
                ops += VoiceTextOp.Commit(" ")
            }
            return ops
        }

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

    /** The operations that abandon the session: the composing tail goes, commits stay. */
    fun onCancel(): List<VoiceTextOp> {
        finished = true
        if (!composing) return emptyList()
        composing = false
        return listOf(VoiceTextOp.ClearComposing)
    }

    /**
     * Words [from, to) joined with spaces, with the separator the field needs before them:
     * a space between this session's pieces, or before the first piece when the text before
     * the cursor ends in a non-space character.
     */
    private fun joinFrom(words: List<String>, from: Int, to: Int): String {
        val body = words.subList(from, to).joinToString(" ")
        val lead = when {
            from > 0 -> " "
            !sentAnything && needsLeadingSpace(body) -> " "
            else -> ""
        }
        return lead + body
    }

    private fun needsLeadingSpace(body: String): Boolean {
        val before = textBefore ?: return false // BLIND: never read, never guessed
        if (before.isEmpty() || before.last().isWhitespace()) return false
        // Punctuation that attaches to the previous word ("." "," "?" …) takes no space.
        return body.firstOrNull()?.let { it.isLetterOrDigit() || it in OPENING } ?: false
    }

    private fun caseFirstWord(word: String): String {
        val before = textBefore ?: return word // BLIND: no context, keep the engine's casing
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
