package tribixbite.cleverkeys

import java.util.Locale

/**
 * The case_cycle command's rewrite: lower → Title → UPPER → lower.
 *
 * Pure (no Android types), locale-aware through [Locale] — Turkish `i` uppercases to `İ` —
 * and length-agnostic: a case mapping may change the UTF-16 length (`ß` → `SS`), which the
 * caller accounts for when it re-selects the result.
 */
object CaseCycle {

    /** The case a text is in, as far as the cycle is concerned. */
    enum class Form { LOWER, TITLE, UPPER, MIXED, UNCASED }

    fun formOf(text: String, locale: Locale): Form {
        val lower = text.lowercase(locale)
        val upper = text.uppercase(locale)
        return when {
            lower == upper -> Form.UNCASED
            // Upper first: a single capital ("I") is both UPPER and Title, and UPPER → lower
            // keeps the cycle moving.
            text == upper -> Form.UPPER
            text == titleCase(text, locale) -> Form.TITLE
            text == lower -> Form.LOWER
            else -> Form.MIXED
        }
    }

    /**
     * The next form of [text]: lower → Title, Title → UPPER, UPPER → lower, and anything mixed
     * (`iPhone`, `hELLO`) → lower so the next press starts the cycle. Text without letter case
     * (digits, CJK) is returned unchanged; the caller reports that nothing could change.
     */
    fun next(text: String, locale: Locale): String = when (formOf(text, locale)) {
        Form.LOWER -> titleCase(text, locale)
        Form.TITLE -> text.uppercase(locale)
        Form.UPPER, Form.MIXED -> text.lowercase(locale)
        Form.UNCASED -> text
    }

    /**
     * Every word's first letter in title case, the rest lower case. Word boundaries are
     * [EditorTextNavigation]'s, so `don't` → `Don't` (not `Don'T`) and `o'clock` likewise.
     */
    fun titleCase(text: String, locale: Locale): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val piece = text.substring(i, i + n)
            val startsWord = EditorTextNavigation.isWordAt(text, i) && !EditorTextNavigation.isWordBefore(text, i)
            out.append(if (startsWord) titleCaseCodePoint(piece, locale) else piece.lowercase(locale))
            i += n
        }
        return out.toString()
    }

    /** Title case of one code point: Char.titlecase handles digraphs (ǆ → ǅ) and Turkish i. */
    private fun titleCaseCodePoint(piece: String, locale: Locale): String =
        if (piece.length == 1) piece[0].titlecase(locale) else piece.uppercase(locale)
}
