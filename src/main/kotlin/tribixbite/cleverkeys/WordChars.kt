package tribixbite.cleverkeys

/**
 * The shared word-segmentation rules every scanner ORs into its own base rule: when U+200C ZERO
 * WIDTH NON-JOINER (the Persian half-space, نیم‌فاصله) is part of a word, when an Indic spelling
 * mark or a Bengali U+200D ZWJ is, and which characters end a sentence. Pure JVM; no state.
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
 * boundary, exactly as the letter/digit scanners treated it before. U+200D (ZWJ) has its own,
 * narrower rule below (Bengali only).
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
 *
 * ## Spelling marks (Bangla 2026-11-04; the other shipped Indic layouts 2026-11-06)
 *
 * In the Indic scripts the dependent vowel signs (Bengali `া ি ী ু ূ ৃ ে ৈ ো ৌ`), the virama
 * (Bengali hasanta U+09CD, Tamil pulli U+0BCD, Sinhala al-lakuna U+0DCA — it spells every
 * conjunct), nukta, candrabindu, anusvara and visarga are combining marks (Mn/Mc), not letters,
 * so an `isLetterOrDigit` scanner cut `আমি` into `আম` + `ি` and `किताब` into `क` `त` `ब`. A
 * [isSpellingMark] is a word character when its nearest base (other marks skipped, and a ZWJ in
 * a [ZWJ_JOINING_SCRIPTS] script) is a letter of the SAME script: [isAttachedSpellingMarkAt] for
 * one text, [isSpellingMarkWordCharAfterCursor] for text after the cursor, whose base may sit
 * before it. This matches WM Keyboard (maintained by a native Bangla speaker): its word scanners
 * (`WordContext.isWordChar`) count letters, digits and combining marks as one word.
 *
 * [SPELLING_MARK_SCRIPTS] holds every Indic script with a layout in `srcs/layouts`: Bengali
 * (`beng_*`), Devanagari (`deva_*`), Gujarati (`guj_*`), Kannada (`kann_*`), Tamil (`tamil_*`)
 * and Sinhala (`sinhala_*`). Gurmukhi, Oriya, Telugu and Malayalam have no layout and are left
 * out until one ships (each needs its own test word then). Persian harakat, Hebrew niqqud and
 * Latin combining accents keep each caller's existing behaviour, since those are optional
 * vowel/accent marks that the lookup folds strip.
 *
 * ## ZWJ inside a Bengali, Sinhala, Kannada or Devanagari word (2026-11-06)
 *
 * U+200D ZERO WIDTH JOINER spells the ra-phala after ra (`র‍্যাব` = র + ZWJ + ্ + য + া + ব) and
 * explicit half forms. Same strict both-sides shape as the half-space: a ZWJ is a word character
 * only when the nearest base before it (marks skipped) is a letter of a [ZWJ_JOINING_SCRIPTS]
 * script and the character after it is a letter or combining mark of that same script
 * ([isJoiningZwjAt]); at the cursor edge a just-typed ZWJ stays with its word
 * ([isZwjWordCharBeforeCursor], the twin of [isComposingZwnj]). Every other ZWJ — emoji
 * sequences, Latin, an edge, between two scripts — keeps each caller's behaviour. WM Keyboard
 * lists ZWJ among its `WORD_JOINERS` (allowed between two letters of one word).
 * Sinhala and Kannada joined too (2026-11-06): both shipped layouts carry a ZWJ key, and Unicode
 * spells Sinhala's rakaransaya and yansaya as virama + ZWJ + ra / ya (`ශ්‍රී` = ශ ් ZWJ ර ී, the
 * Unicode Sinhala FAQ) and Kannada's explicit half forms as virama + ZWJ. The rule skips marks,
 * so the ZWJ after a virama finds the letter before it.
 * Devanagari joined too (2026-11-06): its layouts carry no ZWJ key, but pasted and field text
 * do, and the Unicode Standard (§12.1) spells the eyelash ra as र + ् + ZWJ and the explicit half
 * form as virama + ZWJ; splitting there cut Marathi `दऱ्या` written that way in two.
 *
 * ## Sentence ends
 *
 * [isSentenceEnd] is the ONE set of characters that end a sentence for next-word context
 * (`NextWordPredictor.contextFromEditorText`) and for the learning window
 * (`SuggestionHandler` → `WordPredictor.onSentenceBoundary`): `.` `?` `!` plus the danda `।`
 * (U+0964, the Bengali dari and the Devanagari full stop) and the double danda `॥` (U+0965).
 * Both paths read this one predicate so they cannot disagree. Autocapitalisation is deliberately
 * NOT given the danda: the Indic scripts have no letter case, so it would change nothing there,
 * and keeping `Autocapitalisation` on its own list avoids touching the cap-mode logic for a no-op.
 */
object WordChars {

    /** Scripts whose combining marks are spelling (see the class KDoc). */
    private val SPELLING_MARK_SCRIPTS: Set<Character.UnicodeScript> = setOf(
        Character.UnicodeScript.BENGALI,
        Character.UnicodeScript.DEVANAGARI,
        Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.KANNADA,
        Character.UnicodeScript.TAMIL,
        Character.UnicodeScript.SINHALA,
    )

    /** Scripts in which a ZWJ between letters/marks is part of the word (class KDoc). */
    private val ZWJ_JOINING_SCRIPTS: Set<Character.UnicodeScript> = setOf(
        Character.UnicodeScript.BENGALI, Character.UnicodeScript.SINHALA, Character.UnicodeScript.KANNADA,
        Character.UnicodeScript.DEVANAGARI,
    )

    /**
     * How many characters before an index a caller with a short editor read should add so the
     * base letter of a mark stack there is inside the text it judges (`ক্ষ্ম` stacks two marks;
     * eight leaves room for a vowel sign, a nukta and a candrabindu on top). A mark whose base
     * is still out of reach counts as unattached, which is each caller's old behaviour.
     */
    const val MARK_BASE_LOOKBACK: Int = 8

    /** U+200D ZERO WIDTH JOINER. */
    const val ZWJ: Char = '\u200D'

    /** U+0964 DEVANAGARI DANDA — the Bengali dari and the Devanagari full stop. */
    const val DANDA: Char = '\u0964'

    /** U+0965 DEVANAGARI DOUBLE DANDA — ends a verse or paragraph. */
    const val DOUBLE_DANDA: Char = '\u0965'

    /**
     * True for a character that ends a sentence: `.` `?` `!`, the danda and the double danda
     * (class KDoc). The next-word context and the learning window both use it.
     */
    fun isSentenceEnd(c: Char): Boolean =
        c == '.' || c == '?' || c == '!' || c == DANDA || c == DOUBLE_DANDA

    /** True for a combining mark (Mn, Mc or Me) of a [SPELLING_MARK_SCRIPTS] script. */
    fun isSpellingMark(c: Char?): Boolean =
        c != null && isCombiningMark(c) && Character.UnicodeScript.of(c.code) in SPELLING_MARK_SCRIPTS

    /** True when [base] is a letter of the same script as the spelling mark [mark]. */
    private fun carriesSpellingMark(base: Char?, mark: Char): Boolean =
        base != null && Character.isLetter(base) &&
            Character.UnicodeScript.of(base.code) == Character.UnicodeScript.of(mark.code)

    /**
     * The base a spelling mark at [i] attaches to: [baseCharBefore], except that in a
     * [ZWJ_JOINING_SCRIPTS] script a ZWJ is skipped too, so the hasanta of `র‍্য` finds র.
     */
    private fun spellingBaseBefore(text: CharSequence, i: Int, mark: Char): Char? {
        val skipZwj = Character.UnicodeScript.of(mark.code) in ZWJ_JOINING_SCRIPTS
        var j = i - 1
        while (j >= 0 && (isCombiningMark(text[j]) || (skipZwj && text[j] == ZWJ))) j--
        return if (j >= 0) text[j] else null
    }

    /**
     * True when [text]`[i]` is a spelling mark on a letter of its script within [text]
     * (`আমি`: `ি` at index 2). A mark with no base, or on a digit or a Latin letter, is not.
     */
    fun isAttachedSpellingMarkAt(text: CharSequence, i: Int): Boolean =
        i in text.indices && isSpellingMark(text[i]) &&
            carriesSpellingMark(spellingBaseBefore(text, i, text[i]), text[i])

    /**
     * [isAttachedSpellingMarkAt] for text AFTER the cursor: when only marks precede index [i]
     * in [after], the base is the last base character of [before] (`আম|ি`).
     */
    fun isSpellingMarkWordCharAfterCursor(after: CharSequence, i: Int, before: CharSequence): Boolean {
        if (i !in after.indices || !isSpellingMark(after[i])) return false
        val mark = after[i]
        val base = spellingBaseBefore(after, i, mark) ?: spellingBaseBefore(before, before.length, mark)
        return carriesSpellingMark(base, mark)
    }

    /** The script of [prevBase] when it is a letter of a [ZWJ_JOINING_SCRIPTS] script, else null. */
    private fun zwjScriptOf(prevBase: Char?): Character.UnicodeScript? {
        if (prevBase == null || !Character.isLetter(prevBase)) return null
        val script = Character.UnicodeScript.of(prevBase.code)
        return if (script in ZWJ_JOINING_SCRIPTS) script else null
    }

    /** True when [next] is a letter or combining mark of [script]. */
    private fun continuesZwjScript(next: Char?, script: Character.UnicodeScript): Boolean =
        next != null && (Character.isLetter(next) || isCombiningMark(next)) &&
            Character.UnicodeScript.of(next.code) == script

    /** Strict rule: a ZWJ whose left base and right neighbour belong to one ZWJ-joining script. */
    fun isJoiningZwj(prevBase: Char?, next: Char?): Boolean {
        val script = zwjScriptOf(prevBase) ?: return false
        return continuesZwjScript(next, script)
    }

    /**
     * Cursor-edge rule for a ZWJ typed right before the cursor (`র‍|`): the left base is a
     * letter of a ZWJ-joining script and [next] — the first character after the cursor, null at
     * the end — is of that script or not a letter/digit at all. Mirrors [isComposingZwnj].
     */
    fun isComposingZwj(prevBase: Char?, next: Char?): Boolean {
        val script = zwjScriptOf(prevBase) ?: return false
        return next == null || continuesZwjScript(next, script) || !next.isLetterOrDigit()
    }

    /** True when [text]`[i]` is a ZWJ joining two parts of one word of a [ZWJ_JOINING_SCRIPTS] script. */
    fun isJoiningZwjAt(text: CharSequence, i: Int): Boolean =
        i in text.indices && text[i] == ZWJ && isJoiningZwj(baseCharBefore(text, i), text.getOrNull(i + 1))

    /**
     * [isJoiningZwjAt] for text BEFORE the cursor: the last character of [before] is judged by
     * the cursor-edge rule against [after] (null or empty = nothing known after it), every other
     * index by the strict rule.
     */
    fun isZwjWordCharBeforeCursor(before: CharSequence, i: Int, after: CharSequence?): Boolean {
        if (i !in before.indices || before[i] != ZWJ) return false
        if (i < before.length - 1) return isJoiningZwjAt(before, i)
        return isComposingZwj(baseCharBefore(before, i), after?.firstOrNull())
    }

    /**
     * [isJoiningZwjAt] for text AFTER the cursor: index 0 takes its left base from the end of
     * [before] (marks skipped).
     */
    fun isZwjWordCharAfterCursor(after: CharSequence, i: Int, before: CharSequence): Boolean {
        if (i !in after.indices || after[i] != ZWJ) return false
        if (i > 0) return isJoiningZwjAt(after, i)
        return isJoiningZwj(baseCharBefore(before, before.length), after.getOrNull(1))
    }

    /** True when [word] starts or ends with U+200D (a dangling ZWJ, never part of a word). */
    fun hasEdgeZwj(word: CharSequence): Boolean =
        word.isNotEmpty() && (word[0] == ZWJ || word[word.length - 1] == ZWJ)

    /**
     * True when every character of [word] is a letter, an attached spelling mark
     * ([isAttachedSpellingMarkAt]) or a joining ZWJ ([isJoiningZwjAt]) — the "all letters" test
     * of the typo-tolerant search and the dropped-double autocorrect step, which must accept
     * `কুকুর` and `किताब`. Exactly `all { isLetter() }` for any word without such a mark or
     * joiner (Latin, Cyrillic, Greek, Arabic; a Persian half-space still fails it, as before).
     */
    fun isSpelledWithLetters(word: CharSequence): Boolean {
        for (i in word.indices) {
            if (Character.isLetter(word[i])) continue
            if (isAttachedSpellingMarkAt(word, i) || isJoiningZwjAt(word, i)) continue
            return false
        }
        return true
    }

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
     * The letters/digits scanners' rule with the half-space, spelling-mark and ZWJ clauses: a
     * letter or digit, a joining half-space ([isJoiningZwnjAt]), an attached spelling mark
     * ([isAttachedSpellingMarkAt]) or a joining Bengali ZWJ ([isJoiningZwjAt]). Exactly
     * `isLetterOrDigit` for any text without an Arabic-script half-space, an Indic mark or a
     * Bengali ZWJ.
     */
    fun isWordCharAt(text: CharSequence, i: Int): Boolean =
        i in text.indices &&
            (text[i].isLetterOrDigit() || isJoiningZwnjAt(text, i) || isAttachedSpellingMarkAt(text, i) ||
                isJoiningZwjAt(text, i))

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
