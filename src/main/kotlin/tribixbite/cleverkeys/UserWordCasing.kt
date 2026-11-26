package tribixbite.cleverkeys

import java.util.Locale

/**
 * The casing a word is STORED in when the user adds it to the personal dictionary from the
 * keyboard (the "Add to dictionary?" prompt, the "+word" chip, the autocorrect undo and the
 * "Prefer … when swiping?" offer all go through it — `SuggestionHandler.userWordStorageForm`).
 *
 * The problem it solves (device report 2026-11-25, Saga / Note10+ / Pixel, release r16): a word
 * typed at the start of a field or sentence carries a capital that auto-capitalisation put there,
 * not the user. Stored as typed (`Grüezi`, `Parcero`), the user-dictionary casing is restored on
 * every later prediction ([WordPredictor.applyUserWordCase], issue #72), so the word came out
 * capitalised mid-sentence (`und Grüezi`, `Parcero Parcero Parcero`).
 *
 * The rule: store the lowercase form when BOTH hold —
 *  1. the word's only capital is its first letter ([hasAutoCapitalShape]): not all caps (`NASA`,
 *     caps lock), not internal capitals (`McDonald`, `iPhone`), at least two characters with a
 *     letter after the first (so `I` and `A1` are left alone);
 *  2. auto-capitalisation would have capitalised a letter typed at the word's position: the
 *     setting is on, the field asks for SENTENCE capitals ([AutoCapMode.SENTENCES]), and the
 *     editor text before the word is a sentence / paragraph start by the same test the editor's
 *     `getCursorCapsMode` applies ([autoCapitalisesAfter]).
 * Everything else keeps the user's casing: a capital typed mid-sentence is the user's Shift (a
 * name, a German noun), and with auto-capitalisation off every capital is the user's.
 *
 * A name field keeps the word as typed (Fable audits 2026-11-26): a CAP_WORDS-only field is one
 * by convention (contacts, addresses), and a `TYPE_TEXT_VARIATION_PERSON_NAME` field is one
 * whatever caps flags it also declares (some contact forms set CAP_SENTENCES too). There the
 * capital auto-capitalisation put on the word is the one the user means; folding it stored
 * `Kowalski` lowercase. [AutoCapMode.of] maps both to [AutoCapMode.NONE].
 *
 * Ambiguity accepted: at a sentence start a deliberate Shift (a name like `Paris`, any German
 * noun) cannot be told from auto-capitalisation's — with auto-capitalisation on, the Shift was
 * already latched there. Such a word is stored lowercase. That stays usable: the tap bar
 * capitalises a suggestion when the typed word starts with a capital, and a swipe commit applies
 * the same auto-capitalisation decision, so the word still comes out capitalised at the next
 * sentence start; only a later mid-sentence use comes out lowercase, and adding it once
 * mid-sentence (where the rule keeps the capital) or editing it in the Dictionary Manager stores
 * the capitalised form. The same rule applies to German: a noun the user adds mid-sentence
 * keeps its capital.
 *
 * When the word cannot be found in the editor text (the read failed, the text moved on, the read
 * window cut the text before it) there is no evidence for rule 2, and the word is stored as given.
 *
 * Pure (no Android types): pinned by `DictionaryAddUndoTest`.
 */
object UserWordCasing {

    /** Which capitals auto-capitalisation puts in the active field. */
    enum class AutoCapMode {
        /**
         * Nothing to fold: auto-capitalisation is off, the field declares no sentence caps, or it
         * is a name field (CAP_WORDS only, or a person-name variation). Every capital is kept.
         */
        NONE,

        /** `TYPE_TEXT_FLAG_CAP_SENTENCES` (with or without CAP_WORDS): sentence and paragraph starts. */
        SENTENCES;

        // The former WORDS mode is gone (2026-11-26): since the first Fable audit nothing folded in
        // a CAP_WORDS-only field, so WORDS meant the same as NONE and only a test reached its
        // branch of [autoCapitalisesAfter].

        companion object {
            /**
             * The mode for the user setting [autocapEnabled] and a field asking for
             * [capSentences] (its `inputType` flag), unless it is a [personName] field
             * (`TYPE_TEXT_VARIATION_PERSON_NAME`), which keeps every capital. CAP_WORDS
             * does not matter: alone it marks a name field (NONE), and with CAP_SENTENCES the
             * editor's `TextUtils.getCapsMode` evaluates the pair as sentence caps.
             */
            fun of(autocapEnabled: Boolean, capSentences: Boolean, personName: Boolean): AutoCapMode = when {
                !autocapEnabled || personName -> NONE
                capSentences -> SENTENCES
                else -> NONE
            }
        }
    }

    /**
     * True when [word]'s only capital is its first letter and a letter follows it: the one
     * shape auto-capitalisation produces from a lowercase word.
     */
    fun hasAutoCapitalShape(word: String): Boolean {
        if (word.length < 2) return false
        val first = word.codePointAt(0)
        if (!Character.isUpperCase(first) && !Character.isTitleCase(first)) return false
        val rest = word.substring(Character.charCount(first))
        var hasLetter = false
        for (ch in rest) {
            if (Character.isUpperCase(ch) || Character.isTitleCase(ch)) return false
            if (Character.isLetter(ch)) hasLetter = true
        }
        return hasLetter
    }

    /**
     * The form to store [word] in, given [textBeforeCursor] (the editor text before the cursor,
     * read after the word was committed; null when the read failed) and the field's [mode].
     *
     * @param readWindowFilled true when the read returned as many characters as were asked for,
     *   so text before the returned window may exist: a word at the window's very start then
     *   has unknown context and is kept.
     * @param locale the dictionary language, for the case mapping (Turkish `İ` → `i`).
     */
    fun storageForm(
        word: String,
        textBeforeCursor: String?,
        mode: AutoCapMode,
        readWindowFilled: Boolean,
        locale: Locale,
    ): String {
        // NONE includes the name fields: their capitals are meant (see the class KDoc).
        if (mode != AutoCapMode.SENTENCES || !hasAutoCapitalShape(word)) return word
        val text = textBeforeCursor ?: return word
        val index = lastStandaloneIndexOf(text, word) ?: return word
        if (index == 0 && readWindowFilled) return word
        if (!autoCapitalisesAfter(text.substring(0, index), mode)) return word
        val first = word.codePointAt(0)
        val head = String(Character.toChars(first)).lowercase(locale)
        return head + word.substring(Character.charCount(first))
    }

    /**
     * The start of the last occurrence of [word] in [text] that stands as a whole word: no
     * letter or digit touches it on either side. Null when there is none — a match inside a
     * longer word says nothing about where [word] was typed.
     */
    private fun lastStandaloneIndexOf(text: String, word: String): Int? {
        var from = text.length
        while (from >= 0) {
            val index = text.lastIndexOf(word, from)
            if (index < 0) return null
            val end = index + word.length
            val beforeOk = index == 0 || !Character.isLetterOrDigit(text[index - 1])
            val afterOk = end >= text.length || !Character.isLetterOrDigit(text[end])
            if (beforeOk && afterOk) return index
            from = index - 1
        }
        return null
    }

    /**
     * Would auto-capitalisation capitalise a letter typed right after [preceding]? A port of
     * `android.text.TextUtils.getCapsMode` for the SENTENCES mode — the test behind the
     * editor's `getCursorCapsMode`, which is what [Autocapitalisation] consults when it latches
     * Shift — so this agrees with what auto-capitalisation did when the word was typed.
     *  - back over opening quotes and brackets (`"`, `'`, START_PUNCTUATION);
     *  - a paragraph start (the field start or a newline, after optional spaces / tabs) always
     *    capitalises;
     *  - CAP_SENTENCES: whitespace, then optional closing quotes / brackets, then `.`, `?` or
     *    `!`, where a `.` closing a dotted abbreviation (`e.g.`) does not count.
     */
    internal fun autoCapitalisesAfter(preceding: CharSequence, mode: AutoCapMode): Boolean {
        if (mode == AutoCapMode.NONE) return false
        var i = preceding.length
        while (i > 0) {
            val c = preceding[i - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
            i--
        }
        var j = i
        while (j > 0 && (preceding[j - 1] == ' ' || preceding[j - 1] == '\t')) j--
        if (j == 0 || preceding[j - 1] == '\n') return true
        if (i == j) return false
        while (j > 0) {
            val c = preceding[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
            j--
        }
        if (j == 0) return false
        val end = preceding[j - 1]
        if (end != '.' && end != '?' && end != '!') return false
        if (end == '.') {
            var k = j - 2
            while (k >= 0) {
                val c = preceding[k]
                if (c == '.') return false
                if (!Character.isLetter(c)) break
                k--
            }
        }
        return true
    }
}
