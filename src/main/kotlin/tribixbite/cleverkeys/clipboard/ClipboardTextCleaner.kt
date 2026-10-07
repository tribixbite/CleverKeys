package tribixbite.cleverkeys.clipboard

/**
 * The "Clean" bulk action's text transform: removes trailing whitespace and the line breaks a
 * source inserted inside paragraphs (PDF columns, e-mail wrapping, terminal output), while
 * keeping every break a reader would consider deliberate. Pure Kotlin, no Android types.
 *
 * Rules, applied in order (documented for users in docs/specs/clipboard-system.md):
 *
 *  1. Line endings: CRLF, lone CR, NEL (U+0085) and LINE SEPARATOR (U+2028) become `\n`;
 *     PARAGRAPH SEPARATOR (U+2029) becomes a blank line.
 *  2. Trailing whitespace is removed from every line and from the end of the text: spaces,
 *     tabs, no-break and other Unicode spaces (U+00A0, U+2000–U+200A, U+202F, U+205F, U+3000),
 *     and a zero-width no-break space (U+FEFF). Leading whitespace is never touched.
 *  3. Preformatted text keeps ALL its line breaks (conservative): any line indented by a tab or
 *     two or more spaces, a code fence (```/~~~), a line ending in `{`, `;` or `\` or starting
 *     with `}`, `//`, slash-star (a block comment), `#!`, `#include`, `<?`, `$ `, `>>> ` or a markup tag.
 *  4. Otherwise a single line break is "inserted" — and is replaced — only when all hold:
 *     neither neighbouring line is blank (a blank line is a paragraph break); neither line is
 *     structural (bullet, numbered/lettered item, checkbox, Markdown heading, quote, table row
 *     or horizontal rule); the line before is long — at least [MIN_WRAP_CHARS] characters and
 *     [WRAP_RATIO] of the longest prose line, so short lines (addresses, poems, greetings,
 *     last lines of paragraphs) keep their break; and no URL touches the break.
 *  5. A replaced break becomes one space, except: a line ending in letter + hyphen followed by a
 *     lowercase letter is a word hyphenated at the line end and is rejoined without the hyphen
 *     ("exam-\nple" → "example"); if the hyphenated token already contains a hyphen, or the next
 *     line starts with an uppercase letter or digit, the hyphen is a real one and is kept
 *     ("state-of-\nthe-art", "Jean-\nPaul"); a soft hyphen (U+00AD) is always dropped; and two
 *     Han/Hiragana/Katakana characters are joined with no space. The next line's leading
 *     whitespace is dropped at the join, so joins never produce double spaces.
 *
 * Known limit: a compound that happens to break at its own hyphen before a lowercase word
 * ("well-\nknown") loses that hyphen — typeset text hyphenates this way far more often.
 * The transform is idempotent: cleaning a cleaned text changes nothing.
 */
object ClipboardTextCleaner {

    /** A line shorter than this never has its break joined (addresses, greetings, poems). */
    const val MIN_WRAP_CHARS = 20

    /** A joinable line is at least this fraction of the longest prose line (the wrap width). */
    const val WRAP_RATIO = 0.6

    private val BULLET = Regex("""^[-*+•‣◦▪▫●○■□–—·]\s""")
    // Item labels: 1-3 digits, one letter, or a short roman numeral (i-xx) in one case.
    private const val LABEL = """(\d{1,3}|[A-Za-z]|[ivx]{1,4}|[IVX]{1,4})"""
    private val ENUMERATOR = Regex("""^$LABEL[.)]\s""")
    private val PAREN_ENUMERATOR = Regex("""^\($LABEL\)\s""")
    private val CHECKBOX = Regex("""^\[[ xX]]\s""")
    private val HEADING = Regex("""^#{1,6}\s""")
    private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
    private val MARKUP_TAG = Regex("""^</?[A-Za-z][^>]*>""")
    private val URL = Regex("""^(?:[A-Za-z][A-Za-z0-9+.-]*://|www\.)\S+$""")
    private val CODE_PREFIXES = listOf("}", "//", "/*", "#!", "#include", "<?", "$ ", ">>> ", "```", "~~~")

    /** Clean [text]; returns it unchanged (equal) when no rule applies. */
    fun clean(text: String): String {
        val lines = normalizeLineBreaks(text).split('\n').map(::trimEndWhitespace).toMutableList()
        while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.size == 1 || isPreformatted(lines)) return lines.joinToString("\n")

        val wrapWidth = lines.filter { it.isNotEmpty() && !isStructural(it) }.maxOfOrNull(::length) ?: 0
        val minimum = maxOf(MIN_WRAP_CHARS, Math.ceil(wrapWidth * WRAP_RATIO).toInt())
        val out = StringBuilder(text.length)
        out.append(lines[0])
        for (i in 1 until lines.size) {
            val previous = lines[i - 1]
            val line = lines[i]
            when (joinOf(previous, line, minimum)) {
                Join.KEEP -> out.append('\n').append(line)
                Join.SPACE -> out.append(' ').append(line.trimStart())
                Join.TIGHT -> out.append(line.trimStart())
                Join.DEHYPHENATE -> {
                    out.setLength(out.length - 1)  // the line-end hyphen (or soft hyphen)
                    out.append(line.trimStart())
                }
            }
        }
        return out.toString()
    }

    /** Whether [line] is a list item, checkbox, heading, quote, table row or rule. */
    fun isStructural(line: String): Boolean {
        val t = line.trimStart()
        return BULLET.containsMatchIn(t) || ENUMERATOR.containsMatchIn(t) ||
            PAREN_ENUMERATOR.containsMatchIn(t) || CHECKBOX.containsMatchIn(t) ||
            HEADING.containsMatchIn(t) || t.startsWith(">") || t.startsWith("|") || RULE.matches(t)
    }

    /** Whether [lines] look like code or other preformatted text whose breaks must all stay. */
    fun isPreformatted(lines: List<String>): Boolean = lines.any { line ->
        if (line.startsWith("\t") || line.startsWith("  ")) return@any true
        val t = line.trim()
        t.isNotEmpty() && (t.endsWith("{") || t.endsWith(";") || t.endsWith("\\") ||
            CODE_PREFIXES.any(t::startsWith) || MARKUP_TAG.containsMatchIn(t))
    }

    private enum class Join { KEEP, SPACE, TIGHT, DEHYPHENATE }

    private fun joinOf(previous: String, line: String, minimum: Int): Join {
        if (previous.isEmpty() || line.isEmpty()) return Join.KEEP
        if (isStructural(previous) || isStructural(line)) return Join.KEEP
        if (length(previous) < minimum) return Join.KEEP
        val last = previous.substringAfterLast(' ')
        val first = line.trimStart().substringBefore(' ')
        if (URL.matches(last) || URL.matches(first)) return Join.KEEP
        val next = line.trimStart().first()
        if (previous.last() == SOFT_HYPHEN) return Join.DEHYPHENATE
        if (previous.length >= 2 && previous.last() in HYPHENS && previous[previous.length - 2].isLetter()) {
            val compound = last.dropLast(1).any { it in HYPHENS } || first.any { it in HYPHENS }
            return if (!compound && next.isLowerCase()) Join.DEHYPHENATE else Join.TIGHT
        }
        if (isTightScript(previous.last()) && isTightScript(next)) return Join.TIGHT
        return Join.SPACE
    }

    private fun normalizeLineBreaks(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace('\u0085', '\n').replace(' ', '\n').replace(" ", "\n\n")

    private fun trimEndWhitespace(line: String): String = line.trimEnd(::isTrailingSpace)

    private fun isTrailingSpace(c: Char): Boolean =
        c.isWhitespace() || Character.isSpaceChar(c) || c == '\uFEFF'

    private fun length(line: String): Int = line.codePointCount(0, line.length)

    /** Scripts written without spaces between words; a wrap inside them must not add one. */
    private fun isTightScript(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> c in '　'..'〿' || c in '＀'..'￯'  // CJK and fullwidth punctuation
    }

    private const val SOFT_HYPHEN = '­'
    private val HYPHENS = charArrayOf('-', '‐')
}
