package tribixbite.cleverkeys.clipboard

/**
 * The "Clean" bulk action's text transform: removes extra white space and the line breaks that
 * fall mid-sentence (PDF columns, e-mail wrapping, terminal output, a narrow phone screen), while
 * keeping every break a reader would consider deliberate. Pure Kotlin, no Android types.
 *
 * Rules, applied in order (documented for users in docs/specs/clipboard-system.md; reworked to
 * the maintainer's "find any line breaks that are mid-sentence and remove them as well as
 * remove extra spaces and white space", 2026-11-18):
 *
 *  1. Line endings: CRLF, lone CR, NEL (U+0085) and LINE SEPARATOR (U+2028) become `\n`;
 *     PARAGRAPH SEPARATOR (U+2029) becomes a blank line.
 *  2. Trailing whitespace is removed from every line and from the end of the text: spaces,
 *     tabs, no-break and other Unicode spaces (U+00A0, U+2000–U+200A, U+202F, U+205F, U+3000),
 *     and a zero-width no-break space (U+FEFF). Blank lines at the start and end go.
 *  3. Preformatted text keeps ALL its line breaks and its indentation (only rule 2 applies):
 *     any line indented by a tab, a code fence (```/~~~), a line ending in `{` or `\` or
 *     starting with `}`, `//`, slash-star (a block comment), `#!`, `#include`, `<?`, `$ `,
 *     `>>> ` or a markup tag, two or more lines ending in `;`, an assignment (`name = value`),
 *     a space-aligned table (two lines with a gap of 2+ spaces, not after sentence punctuation,
 *     ending at the same column), and any space-indented line that is not a wrap (see 4). The
 *     indent EVERY line shares is ignored for this test (a clip copied from an indented
 *     terminal or chat block is not code for that).
 *  4. A space-indented line is a hanging-indent wrap — terminal output, e-mail quoting tools
 *     and Markdown renderers indent the continuation of a wrapped line — when the line before
 *     it does not end in `:`, `{`, `[`, `(` or `\`, is indented no deeper than it (a list
 *     item's continuation must be indented deeper than its marker), and the break between them
 *     is mid-sentence (as in 5).
 *  5. Otherwise a line break is removed when it is mid-sentence: neither neighbouring line is
 *     blank (a blank line is a paragraph break), the next line is not structural (bullet,
 *     numbered/lettered item, checkbox, Markdown heading, quote, table row or horizontal rule),
 *     no URL touches the break, the line before does not end a sentence (`.`, `!`, `?`, `…`
 *     or their CJK forms, before any closing quote or bracket), and either the next line starts
 *     with a lowercase letter or the line before (as joined so far) is long — at least
 *     [MIN_WRAP_CHARS] characters and [WRAP_RATIO] of the longest prose line — and does not end
 *     in a colon. So addresses, greetings and a sentence-final line keep their break. A list
 *     item joins a mid-sentence continuation; another structural line (heading, quote, table
 *     row, rule) never joins the next line.
 *  6. In prose: leading white space goes (a list item keeps its indent beyond the shared one,
 *     for nesting), a run of blank lines becomes one blank line, and a tab or run of spaces
 *     inside a line becomes one space.
 *  7. A removed break becomes one space, except: a line ending in letter + hyphen followed by a
 *     lowercase letter is a word hyphenated at the line end and is rejoined without the hyphen
 *     ("exam-\nple" → "example"); if the hyphenated token already contains a hyphen, or the next
 *     line starts with an uppercase letter or digit, the hyphen is a real one and is kept
 *     ("state-of-\nthe-art", "Jean-\nPaul"); a soft hyphen (U+00AD) is always dropped; and two
 *     Han/Hiragana/Katakana characters are joined with no space. The next line's leading
 *     whitespace is dropped at the join, so joins never produce double spaces.
 *
 * Known limit: a compound that happens to break at its own hyphen before a lowercase word
 * ("well-\nknown") loses that hyphen — typeset text hyphenates this way far more often.
 * Cleaning a cleaned text normally changes nothing; a break kept by rule 5's length test can
 * still join on a second pass once an earlier join made the line before it long.
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
    private val INNER_GAP = Regex(" {2,}")
    /** A tab, or two or more spaces of any width, inside a prose line (rule 6). */
    private val INNER_WHITESPACE = Regex("""\t[\t \u00A0\u2000-\u200A\u202F\u205F\u3000]*|[ \u00A0\u2000-\u200A\u202F\u205F\u3000]{2,}[\t \u00A0\u2000-\u200A\u202F\u205F\u3000]*""")
    /** `name = value`, `a.b[i] += 1`: an assignment is code, not prose (rule 3). */
    private val ASSIGNMENT = Regex("""^[A-Za-z_][\w.\[\]]*\s*(?:[-+*/%|&^]|<<|>>)?=(?!=)\s*\S""")
    /** Ends a sentence when it is a line's last character before closing quotes and brackets. */
    private val SENTENCE_TERMINALS = charArrayOf('.', '!', '?', '…', '。', '！', '？')
    private val CLOSERS = charArrayOf('"', '\'', ')', ']', '}', '»', '”', '’', '」', '』', '）')
    private val SENTENCE_END = charArrayOf('.', '!', '?', ':', ';', ',')
    private val BLOCK_OPENERS = charArrayOf(':', '{', '[', '(', '\\')
    private val URL = Regex("""^(?:[A-Za-z][A-Za-z0-9+.-]*://|www\.)\S+$""")
    private val CODE_PREFIXES = listOf("}", "//", "/*", "#!", "#include", "<?", "$ ", ">>> ", "```", "~~~")

    /** Clean [text]; returns it unchanged (equal) when no rule applies. */
    fun clean(text: String): String {
        val raw = normalizeLineBreaks(text).split('\n').map(::trimEndWhitespace).toMutableList()
        while (raw.size > 1 && raw.last().isEmpty()) raw.removeAt(raw.lastIndex)
        while (raw.size > 1 && raw.first().isEmpty()) raw.removeAt(0)
        val lines = dedent(raw)
        // Code keeps its own layout, indentation included: only the trailing white space goes.
        if (isPreformattedDedented(lines)) return raw.joinToString("\n")
        // Rule 6. A structural line keeps its indent (list nesting); any other prose line's
        // leading white space is extra (a kept indented continuation makes the text preformatted).
        for (i in lines.indices) {
            lines[i] = collapseInnerWhitespace(if (isStructural(lines[i])) lines[i] else lines[i].trimStart())
        }
        if (lines.size == 1) return lines[0]

        val minimum = minimumWrapLength(lines)
        val out = StringBuilder(text.length)
        out.append(lines[0])
        // Code points of the output line built so far: rule 5's length test is on the line as
        // joined, so a line two short wraps made long is judged like one long wrapped line.
        var joinedLength = length(lines[0])
        for (i in 1 until lines.size) {
            val previous = lines[i - 1]
            val line = lines[i]
            // Rule 6: a run of blank lines is one paragraph break.
            if (line.isEmpty() && previous.isEmpty()) continue
            val join = joinOf(previous, line, minimum, joinedLength)
            val appended = when (join) {
                Join.KEEP -> { out.append('\n'); line }
                Join.SPACE -> { out.append(' '); line.trimStart() }
                Join.TIGHT -> line.trimStart()
                Join.DEHYPHENATE -> {
                    out.setLength(out.length - 1)  // the line-end hyphen (or soft hyphen)
                    joinedLength--
                    line.trimStart()
                }
            }
            out.append(appended)
            joinedLength = if (join == Join.KEEP) length(appended)
                else joinedLength + length(appended) + (if (join == Join.SPACE) 1 else 0)
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

    /**
     * Whether [lines] (trailing whitespace already removed) look like code or other
     * preformatted text whose breaks must all stay: code markers, a tab indent, a space-aligned
     * table, or a space-indented line that is not a hanging-indent wrap.
     */
    fun isPreformatted(lines: List<String>): Boolean = isPreformattedDedented(dedent(lines))

    /** [isPreformatted] for [lines] whose shared indent is already removed. */
    private fun isPreformattedDedented(lines: List<String>): Boolean {
        if (lines.any { it.startsWith("\t") }) return true
        var semicolons = 0
        val codeLike = lines.any { line ->
            val t = line.trim()
            if (t.endsWith(";")) semicolons++
            t.isNotEmpty() && (t.endsWith("{") || t.endsWith("\\") || semicolons >= 2 ||
                CODE_PREFIXES.any(t::startsWith) || MARKUP_TAG.containsMatchIn(t) || ASSIGNMENT.containsMatchIn(t))
        }
        if (codeLike || isSpaceAligned(lines)) return true
        val minimum = minimumWrapLength(lines)
        return (1 until lines.size).any { i ->
            val line = lines[i]
            indentOf(line) > 0 && line.isNotBlank() && !isStructural(line) && lines[i - 1].isNotEmpty() &&
                !isHangingWrap(lines[i - 1], line, minimum)
        }
    }

    /**
     * Whether indented [line] continues [previous] as a hanging-indent wrap: [previous] does not
     * open a block, [line] is indented no less than it (deeper than a list marker), and the break
     * is mid-sentence (rule 5).
     */
    private fun isHangingWrap(previous: String, line: String, minimum: Int): Boolean {
        if (previous.last() in BLOCK_OPENERS) return false
        val p = indentOf(previous)
        val l = indentOf(line)
        val indented = if (isStructural(previous)) isListItem(previous) && l > p else l >= p
        return indented && isMidSentence(previous, line.trimStart(), minimum, length(previous))
    }

    /**
     * Rule 5's sentence test: [previous] (whose output line is [joinedLength] code points so far)
     * does not end a sentence, and either [next] starts lowercase or the line is a long wrap
     * that does not end in a colon.
     */
    private fun isMidSentence(previous: String, next: String, minimum: Int, joinedLength: Int): Boolean {
        val end = previous.trimEnd(*CLOSERS)
        if (end.isEmpty() || end.last() in SENTENCE_TERMINALS) return false
        val first = next.firstOrNull() ?: return false
        if (first.isLowerCase()) return true
        return joinedLength >= minimum && previous.last() != ':'
    }

    /** [lines] without the indent every non-blank line shares (spaces only). */
    private fun dedent(lines: List<String>): MutableList<String> {
        val shared = lines.filter { it.isNotBlank() }.minOfOrNull(::indentOf) ?: 0
        return if (shared == 0) lines.toMutableList()
            else lines.map { if (it.isBlank()) "" else it.substring(shared) }.toMutableList()
    }

    /** The wrap width rule: a joinable line is at least this long (code points). */
    private fun minimumWrapLength(lines: List<String>): Int {
        val wrapWidth = lines.filter { it.isNotEmpty() && !isStructural(it) }.maxOfOrNull(::length) ?: 0
        return maxOf(MIN_WRAP_CHARS, Math.ceil(wrapWidth * WRAP_RATIO).toInt())
    }

    /** Two lines with a gap of 2+ spaces ending at the same column: a space-aligned table. */
    private fun isSpaceAligned(lines: List<String>): Boolean {
        val seen = HashSet<Int>()
        for (line in lines) {
            // A gap after sentence punctuation is double spacing between sentences, not a column.
            val ends = INNER_GAP.findAll(line).filter { it.range.first > indentOf(line) && line[it.range.first - 1] !in SENTENCE_END }
                .map { it.range.last + 1 }.toSet()
            for (end in ends) if (!seen.add(end)) return true
        }
        return false
    }

    /** A tab or run of spaces inside [line] becomes one space; its leading indentation is kept. */
    private fun collapseInnerWhitespace(line: String): String {
        val indent = indentOf(line)
        return line.substring(0, indent) + INNER_WHITESPACE.replace(line.substring(indent), " ")
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart(' ').length

    private fun isListItem(line: String): Boolean {
        val t = line.trimStart()
        return BULLET.containsMatchIn(t) || ENUMERATOR.containsMatchIn(t) ||
            PAREN_ENUMERATOR.containsMatchIn(t) || CHECKBOX.containsMatchIn(t)
    }

    private enum class Join { KEEP, SPACE, TIGHT, DEHYPHENATE }

    private fun joinOf(previous: String, line: String, minimum: Int, joinedLength: Int): Join {
        if (previous.isEmpty() || line.isEmpty()) return Join.KEEP
        if (isStructural(line)) return Join.KEEP
        // Of the structural lines only a list item continues onto the next line.
        if (isStructural(previous) && !isListItem(previous)) return Join.KEEP
        val last = previous.substringAfterLast(' ')
        val first = line.trimStart().substringBefore(' ')
        if (URL.matches(last) || URL.matches(first)) return Join.KEEP
        if (!isMidSentence(previous, line.trimStart(), minimum, joinedLength)) return Join.KEEP
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
