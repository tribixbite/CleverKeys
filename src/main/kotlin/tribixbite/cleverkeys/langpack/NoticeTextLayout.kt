package tribixbite.cleverkeys.langpack

/**
 * Turns a language pack's plain-text NOTICE.txt into display blocks that fit a phone dialog.
 *
 * NOTICE.txt (written by `scripts/build_langpack.py` `render_notice`) is laid out for an
 * ~80-column terminal: 72-character `=====` rules around section headings and prose hard-wrapped
 * near column 78. A phone dialog shows roughly 40–50 monospace columns, so rendering the file
 * verbatim soft-wrapped every rule into a second ragged line of `=` and split every prose line
 * into a long and a short fragment (device finding 2026-09-29, fa/hu). Horizontal scrolling
 * would keep the layout but make every prose line pan sideways.
 *
 * Instead the text is parsed into blocks: rule lines become [Block.Rule] (drawn as a divider),
 * hard-wrapped prose is re-flowed into [Block.Paragraph]s that wrap at the dialog width, and
 * indented or bulleted lines are kept verbatim as [Block.Preformatted]. The WORDS and their
 * order are never changed — only line breaks that were artefacts of the 78-column wrap — which
 * matters for licence text.
 *
 * Pure Kotlin (no Android types) so it is covered by `runPureTests`.
 */
object NoticeTextLayout {

    sealed interface Block {
        /** Flowing prose. Contains `\n` only where the source ended a line deliberately. */
        data class Paragraph(val text: String) : Block

        /** Indented/bulleted lines kept exactly as written (one or more, `\n`-joined). */
        data class Preformatted(val text: String) : Block

        /** A horizontal rule (`=====`, `-----`, …) — rendered as a divider, not as text. */
        data object Rule : Block
    }

    /** Five or more of one rule character, alone on the line. */
    private val RULE = Regex("^\\s*([=\\-_*~#])\\1{4,}\\s*$")

    /** Lines that must keep their own line: indented, or list items. */
    private val PREFORMATTED = Regex("^(?:\\s+\\S|[-*•]\\s)")

    /**
     * A prose line at least this long was most likely broken by the source's column wrap, so
     * the next prose line continues its sentence. A shorter line (a title, a URL, a credit on
     * its own line) ended where the author meant it to. NOTICE files wrap at 72–80 columns;
     * 55 sits safely below every wrap width in use while above typical title lengths.
     */
    const val WRAPPED_LINE_MIN_LENGTH = 55

    /** A line that is only a URL always keeps its own line, whatever the neighbour's length. */
    private val BARE_URL = Regex("^\\S+://\\S+$")

    fun parse(notice: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val prose = StringBuilder()
        var lastProseLineLength = 0
        var lastProseLineWasUrl = false
        val pre = mutableListOf<String>()

        fun flushProse() {
            if (prose.isNotEmpty()) blocks += Block.Paragraph(prose.toString())
            prose.setLength(0)
            lastProseLineLength = 0
            lastProseLineWasUrl = false
        }
        fun flushPre() {
            if (pre.isNotEmpty()) blocks += Block.Preformatted(pre.joinToString("\n"))
            pre.clear()
        }

        for (raw in notice.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            when {
                line.isEmpty() -> { flushProse(); flushPre() }
                RULE.matches(line) -> { flushProse(); flushPre(); blocks += Block.Rule }
                PREFORMATTED.containsMatchIn(line) -> { flushProse(); pre += line }
                else -> {
                    flushPre()
                    val isUrl = BARE_URL.matches(line)
                    if (prose.isNotEmpty()) {
                        val continues = lastProseLineLength >= WRAPPED_LINE_MIN_LENGTH &&
                            !isUrl && !lastProseLineWasUrl
                        prose.append(if (continues) ' ' else '\n')
                    }
                    prose.append(line)
                    lastProseLineLength = line.length
                    lastProseLineWasUrl = isUrl
                }
            }
        }
        flushProse()
        flushPre()
        // A rule is only a separator: drop leading/trailing/doubled ones so the dialog never
        // opens or closes on a bare divider or stacks two of them.
        return blocks.filterIndexed { i, b ->
            b != Block.Rule || (i > 0 && i < blocks.lastIndex && blocks[i - 1] != Block.Rule)
        }
    }
}
