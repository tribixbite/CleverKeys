package tribixbite.cleverkeys

/**
 * The ONE rule for when U+200C ZERO WIDTH NON-JOINER (the Persian half-space, نیم‌فاصله) is
 * part of a word. Pure JVM; no state.
 *
 * Persian writes many single words with a half-space inside them: the verb prefix `می‌`
 * (`می‌شود`), the plural `‌ها` (`کتاب‌ها`), `‌تر`/`‌ترین`, `‌ای` after a silent ه. A dictionary that
 * stores those compounds as one word is useless if word scanning splits them at the joiner, and
 * the shipped scanners disagreed: the composing-word tracker kept U+200C everywhere while word
 * selection, editor navigation, suffix ownership and the bigram tokeniser all ended the word
 * there (research `docs/research/2026-10-21-persian-zwnj.md` §6; maintainer decision (b),
 * 2026-10-23).
 *
 * ## The rule
 *
 * A half-space is a word character only when the nearest base character on BOTH sides is an
 * Arabic-script letter ([isArabicScriptLetter]; combining marks such as harakat are skipped).
 * Every other U+200C — between Latin, Cyrillic, Greek, Hebrew, Indic or Sinhala letters, at the
 * edge of a word, next to a digit or punctuation, or between an Arabic and a Latin letter — is a
 * boundary, exactly as the letter/digit scanners treated it before. U+200D (ZWJ, emoji
 * sequences and Indic conjuncts) is not covered: callers keep whatever they did with it.
 *
 * ## The cursor edge
 *
 * While a word is being composed the half-space has just been typed and nothing follows it yet
 * (`می‌|`). Mirroring the apostrophe rule in [PredictionContextTracker] (`don'|t`), a half-space
 * immediately before the cursor still belongs to the word when the letter before it is
 * Arabic-script and the character after the cursor is an Arabic-script letter or not a
 * letter/digit at all (end of text, a space, punctuation): see [isComposingZwnj]. A Latin letter
 * or a digit right after the cursor ends the word as usual.
 *
 * Callers keep their own base rule (letters/digits, apostrophes, marks) and OR one of these in.
 */
object WordChars {

    /** U+200C ZERO WIDTH NON-JOINER — the Persian half-space. */
    const val ZWNJ: Char = '‌'

    /**
     * True for a letter of the Arabic script (Persian, Arabic, Kurdish, Urdu letters in the
     * Arabic blocks and presentation forms). Arabic-Indic digits, harakat, punctuation such as
     * `،` and the tatweel are not letters of the script here (`Character.isLetter` is false for
     * them, or their script is COMMON/INHERITED).
     */
    fun isArabicScriptLetter(c: Char?): Boolean =
        c != null && Character.isLetter(c) &&
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.ARABIC

    /** Strict rule: a half-space between two Arabic-script letters joins one word. */
    fun isJoiningZwnj(prev: Char?, next: Char?): Boolean =
        isArabicScriptLetter(prev) && isArabicScriptLetter(next)

    /**
     * Cursor-edge rule (class KDoc): a half-space typed right before the cursor after an
     * Arabic-script letter; [next] is the first character after the cursor, null at the end.
     */
    fun isComposingZwnj(prev: Char?, next: Char?): Boolean =
        isArabicScriptLetter(prev) &&
            (next == null || isArabicScriptLetter(next) || !next.isLetterOrDigit())

    /**
     * The nearest base character before index [i] of [text], skipping combining marks
     * (`کتابِ‌ها`: the kasra is skipped and ب is returned). Null at the start.
     */
    fun baseCharBefore(text: CharSequence, i: Int): Char? {
        var j = i - 1
        while (j >= 0 && isCombiningMark(text[j])) j--
        return if (j >= 0) text[j] else null
    }

    /** True when [text]`[i]` is a half-space joining two Arabic-script letters within [text]. */
    fun isJoiningZwnjAt(text: CharSequence, i: Int): Boolean =
        i in text.indices && text[i] == ZWNJ &&
            isJoiningZwnj(baseCharBefore(text, i), text.getOrNull(i + 1))

    /**
     * [isJoiningZwnjAt] for text BEFORE the cursor: the last character of [before] is judged by
     * the cursor-edge rule against [after] (the text after the cursor; empty or null = nothing
     * known after it), every other index by the strict rule.
     */
    fun isZwnjWordCharBeforeCursor(before: CharSequence, i: Int, after: CharSequence?): Boolean {
        if (i !in before.indices || before[i] != ZWNJ) return false
        if (i < before.length - 1) return isJoiningZwnjAt(before, i)
        return isComposingZwnj(baseCharBefore(before, i), after?.firstOrNull())
    }

    /**
     * [isJoiningZwnjAt] for text AFTER the cursor: index 0 takes its left neighbour from the end
     * of [before] (marks skipped).
     */
    fun isZwnjWordCharAfterCursor(after: CharSequence, i: Int, before: CharSequence): Boolean {
        if (i !in after.indices || after[i] != ZWNJ) return false
        if (i > 0) return isJoiningZwnjAt(after, i)
        return isJoiningZwnj(baseCharBefore(before, before.length), after.getOrNull(1))
    }

    /**
     * The letters/digits scanners' rule with the half-space clause: a letter or digit, or a
     * joining half-space ([isJoiningZwnjAt]). Exactly `isLetterOrDigit` for any text without an
     * Arabic-script half-space.
     */
    fun isWordCharAt(text: CharSequence, i: Int): Boolean =
        i in text.indices && (text[i].isLetterOrDigit() || isJoiningZwnjAt(text, i))

    /** Number of U+200C in [word] (any position). */
    fun zwnjCount(word: CharSequence): Int {
        var n = 0
        for (c in word) if (c == ZWNJ) n++
        return n
    }

    /**
     * [word] without the half-spaces that join Arabic-script letters — the spelling a swipe path
     * or a user who skipped the half-space produces (`می‌شود` → `میشود`). Edge and non-Arabic
     * U+200C are kept. Returns [word] itself when nothing is removed.
     */
    fun stripJoiningZwnj(word: String): String {
        if (word.indexOf(ZWNJ) < 0) return word
        val sb = StringBuilder(word.length)
        for (i in word.indices) {
            if (isJoiningZwnjAt(word, i)) continue
            sb.append(word[i])
        }
        return if (sb.length == word.length) word else sb.toString()
    }

    /**
     * True when replacing [typed] with [candidate] would ADD, REMOVE or MOVE a half-space —
     * a different number of U+200C, or the same letters with the half-space elsewhere
     * (`نامه‌ای` vs `نام‌های`). Autocorrect never crosses the half-space (decision (d)):
     * the joined forms are real words, and the joined string cannot tell where a split belongs.
     */
    fun crossesHalfSpace(typed: String, candidate: String): Boolean {
        val typedHas = typed.indexOf(ZWNJ) >= 0
        val candidateHas = candidate.indexOf(ZWNJ) >= 0
        if (!typedHas && !candidateHas) return false
        if (zwnjCount(typed) != zwnjCount(candidate)) return true
        return typed != candidate && typed.replace(ZWNJ.toString(), "") == candidate.replace(ZWNJ.toString(), "")
    }

    /** True when [word] starts or ends with U+200C (a dangling half-space, never part of a word). */
    fun hasEdgeZwnj(word: CharSequence): Boolean =
        word.isNotEmpty() && (word[0] == ZWNJ || word[word.length - 1] == ZWNJ)

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
