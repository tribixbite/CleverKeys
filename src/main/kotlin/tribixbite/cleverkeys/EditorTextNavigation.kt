package tribixbite.cleverkeys

/**
 * The text the IME read around the editor's selection, in absolute UTF-16 offsets (the unit
 * every InputConnection offset uses, surrogate pairs included).
 *
 * Android-free on purpose: the word/line commands (word_left, select_word, select_line …) and
 * case cycling decide their target here, on the pure JVM, and the same rules run over the
 * inline clipboard editor's own text. Reading the window from an InputConnection lives in
 * [EditorTextWindowReader].
 *
 * @property selStart the selection anchor (`ExtractedText.selectionStart`, made absolute).
 * @property selEnd the selection head — the end that moves when a selection is extended. It may
 *   be smaller than [selStart] for a selection made backwards.
 * @property before up to the requested reach of text before the selection's lower end.
 * @property selected the selected text (empty for a caret).
 * @property after up to the requested reach of text after the selection's upper end.
 * @property beforeComplete [before] reaches the document start (it was not cut at the reach).
 * @property afterComplete [after] reaches the document end.
 */
data class EditorTextWindow(
    val selStart: Int,
    val selEnd: Int,
    val before: String,
    val selected: String,
    val after: String,
    val beforeComplete: Boolean,
    val afterComplete: Boolean,
) {
    init {
        require(selStart >= 0 && selEnd >= 0) { "negative selection" }
        require(selected.length == kotlin.math.abs(selEnd - selStart)) { "selected text does not span the selection" }
        require(before.length <= minOf(selStart, selEnd)) { "more text before the selection than the document holds" }
    }

    /** Lower end of the selection. */
    val min: Int get() = minOf(selStart, selEnd)

    /** Upper end of the selection. */
    val max: Int get() = maxOf(selStart, selEnd)

    /** Absolute offset of [text]'s first character. */
    val base: Int get() = min - before.length

    /** The whole window: before + selected + after. */
    val text: String by lazy(LazyThreadSafetyMode.NONE) { before + selected + after }

    val collapsed: Boolean get() = selStart == selEnd

    /** A window for text the IME owns completely (the inline clipboard editor). */
    companion object {
        fun ofWholeText(text: String, selStart: Int, selEnd: Int): EditorTextWindow {
            val lo = minOf(selStart, selEnd).coerceIn(0, text.length)
            val hi = maxOf(selStart, selEnd).coerceIn(0, text.length)
            val anchor = selStart.coerceIn(0, text.length)
            val head = selEnd.coerceIn(0, text.length)
            return EditorTextWindow(
                anchor, head, text.substring(0, lo), text.substring(lo, hi), text.substring(hi),
                beforeComplete = true, afterComplete = true,
            )
        }
    }
}

/**
 * Word and line boundaries for the editor commands, and the selection each command asks for.
 *
 * A word is a run of letters, digits and combining marks; an apostrophe (`'` or `’`) between
 * two such characters belongs to the word, so `don't` and `qu'est` move and select whole while
 * a quoted `'word'` does not take its quotes. Everything is scanned by code point, so a
 * supplementary letter is never split into half a surrogate pair.
 */
object EditorTextNavigation {

    /** The navigation commands (KeyValue.Editing WORD_LEFT … SELECT_LINE). */
    enum class Command { WORD_LEFT, WORD_RIGHT, SELECT_WORD_LEFT, SELECT_WORD_RIGHT, SELECT_WORD, SELECT_LINE }

    /** What a command should do with the editor's selection. */
    sealed interface Plan {
        /** Set the selection to anchor [start], head [end] (absolute; equal for a caret). */
        data class Select(val start: Int, val end: Int) : Plan

        /** There is nothing to act on (no word at the cursor). */
        object NoTarget : Plan

        /** The target runs past what was read, so its true bounds are unknown: refuse. */
        object Unknown : Plan
    }

    /** How far each side of the selection the editor is read for word and line commands. */
    const val WORD_REACH = 256
    const val LINE_REACH = 4096

    /** The read reach [command] needs. */
    fun reachFor(command: Command): Int = if (command == Command.SELECT_LINE) LINE_REACH else WORD_REACH

    /**
     * The selection [command] produces in [w]:
     * - WORD_LEFT / WORD_RIGHT collapse to a caret at the previous word start (from the
     *   selection's lower end) or the next word end (from its upper end).
     * - SELECT_WORD_LEFT / SELECT_WORD_RIGHT keep the anchor and move the head the same way.
     * - SELECT_WORD / SELECT_LINE select the word(s) / line(s) covering the selection.
     *
     * A move that runs off the read window stops at its edge (a bounded step, still a move in the
     * right direction); a word or line selection that would reach a cut edge is [Plan.Unknown].
     */
    fun plan(command: Command, w: EditorTextWindow): Plan {
        val text = w.text
        val lo = w.min - w.base
        val hi = w.max - w.base
        val head = w.selEnd - w.base
        return when (command) {
            Command.WORD_LEFT -> caret(w, previousWordStart(text, lo))
            Command.WORD_RIGHT -> caret(w, nextWordEnd(text, hi))
            Command.SELECT_WORD_LEFT -> Plan.Select(w.selStart, w.base + previousWordStart(text, head))
            Command.SELECT_WORD_RIGHT -> Plan.Select(w.selStart, w.base + nextWordEnd(text, head))
            Command.SELECT_WORD -> bounded(w, wordBounds(text, lo, hi))
            Command.SELECT_LINE -> bounded(w, lineBounds(text, lo, hi))
        }
    }

    /**
     * A navigation key the editor itself interprets. Used where `setSelection` cannot be used:
     * no readback window, an editor that does not honour it, or a web field. Blink maps these
     * (editing_behavior.cc, non-Mac; on Android a word move right stops at the word END, as only
     * Windows skips the following space), and so do EditText and Compose.
     */
    enum class EditorKey {
        /** Left: collapses a selection to its start (it does not move a collapsed caret here). */
        COLLAPSE_TO_START,
        /** Ctrl+Left: to the start of the word at or before the caret. */
        WORD_START,
        /** Ctrl+Shift+Right: extend the selection head to the end of the next word. */
        EXTEND_TO_WORD_END,
        /** Home: to the start of the line (Blink and EditText: the VISUAL, wrapped line). */
        LINE_START,
        /** Shift+End: extend the selection head to the end of the line (visual, as LINE_START). */
        EXTEND_TO_LINE_END,
    }

    /** What select_word / select_line should send when acting through [EditorKey]s. */
    sealed interface KeyPlan {
        /** Send [keys] in order. */
        data class Send(val keys: List<EditorKey>) : KeyPlan

        /** There is no word to select. */
        object NoTarget : KeyPlan

        /** The keys cannot produce the selection: refuse, sending nothing. */
        object Unknown : KeyPlan
    }

    /** More words than this in a selection are not walked one key event each. */
    const val MAX_KEY_WORDS = 32

    /**
     * The key events that make the editor select what [command] (SELECT_WORD or SELECT_LINE)
     * asks for, decided ONLY from text read around the selection — no absolute offsets needed:
     * [before] is text before the selection's lower end (any length, possibly cut), [selected] the
     * selected text (empty for a caret), [after] text after its upper end.
     *
     * - SELECT_WORD: collapse a selection to its start; Ctrl+Left only when the start is inside or
     *   just after a word (at a word start it would reach the PREVIOUS word); then one
     *   Ctrl+Shift+Right per word the target covers. A cut edge is harmless here: the editor
     *   finds the real word boundary itself.
     * - SELECT_LINE: Home, Shift+End (after collapsing a selection). That is the VISUAL line in
     *   Blink and EditText, where the readback path selects the logical line.
     *
     * Nothing has been sent when this returns, so a [KeyPlan.NoTarget] / [KeyPlan.Unknown]
     * refusal leaves the editor untouched.
     */
    fun keyPlan(command: Command, before: String, selected: String, after: String): KeyPlan {
        require(command == Command.SELECT_WORD || command == Command.SELECT_LINE) { "not a select command: $command" }
        val collapse = if (selected.isEmpty()) emptyList() else listOf(EditorKey.COLLAPSE_TO_START)
        if (command == Command.SELECT_LINE) {
            // TODO: a selection spanning lines (logical, or visual wraps) needs a multi-line key
            //  walk; Home/Shift+End from its start would cover only the first line, so a selection
            //  holding a line break is refused and a soft-wrapped one covers its first visual line.
            if (selected.contains('\n')) return KeyPlan.Unknown
            return KeyPlan.Send(collapse + EditorKey.LINE_START + EditorKey.EXTEND_TO_LINE_END)
        }
        val text = before + selected + after
        val lo = before.length
        val range = wordBounds(text, lo, lo + selected.length) ?: return KeyPlan.NoTarget
        val words = wordStarts(text, range.first, range.last + 1)
        if (words > MAX_KEY_WORDS) return KeyPlan.Unknown
        val toStart = if (range.first < lo) listOf(EditorKey.WORD_START) else emptyList()
        return KeyPlan.Send(collapse + toStart + List(words) { EditorKey.EXTEND_TO_WORD_END })
    }

    /** How many words start in [start, end) — one Ctrl+Shift+Right each. */
    private fun wordStarts(text: CharSequence, start: Int, end: Int): Int {
        var count = 0
        var i = start
        while (i < end) {
            if (isWordAt(text, i) && !isWordBefore(text, i)) count++
            i += Character.charCount(Character.codePointAt(text, i))
        }
        return count
    }

    /**
     * The range case cycling rewrites: the selection when there is one, else the word touching
     * the caret, else the word just before it across spaces or tabs (`hello |` after a swipe's
     * auto-space). [Plan.NoTarget] when there is no such word.
     */
    fun caseTarget(w: EditorTextWindow): Plan {
        if (!w.collapsed) return Plan.Select(w.min, w.max)
        val text = w.text
        val caret = w.min - w.base
        wordBounds(text, caret, caret)?.let { return bounded(w, it) }
        var i = caret
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        if (i == caret) return if (i == 0 && !w.beforeComplete) Plan.Unknown else Plan.NoTarget
        return bounded(w, wordBounds(text, i, i))
    }

    // ------------------------------------------------------------------ boundaries

    /** Start of the word at or before [from]: skip non-word characters back, then the word. */
    fun previousWordStart(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i > 0 && !isWordBefore(text, i)) i -= Character.charCount(Character.codePointBefore(text, i))
        while (i > 0 && isWordBefore(text, i)) i -= Character.charCount(Character.codePointBefore(text, i))
        return i
    }

    /** End of the word at or after [from]: skip non-word characters forward, then the word. */
    fun nextWordEnd(text: CharSequence, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && !isWordAt(text, i)) i += Character.charCount(Character.codePointAt(text, i))
        while (i < text.length && isWordAt(text, i)) i += Character.charCount(Character.codePointAt(text, i))
        return i
    }

    /** The word(s) covering [start, end], or null when no word character touches the range. */
    fun wordBounds(text: CharSequence, start: Int, end: Int): IntRange? {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        while (s > 0 && isWordBefore(text, s)) s -= Character.charCount(Character.codePointBefore(text, s))
        while (e < text.length && isWordAt(text, e)) e += Character.charCount(Character.codePointAt(text, e))
        return if (s == e) null else s until e
    }

    /** The line(s) covering [start, end], without their line breaks. Never null. */
    fun lineBounds(text: CharSequence, start: Int, end: Int): IntRange {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        var lineStart = s
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = e
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        return lineStart until lineEnd
    }

    /** Whether the code point ending at [i] belongs to a word. */
    fun isWordBefore(text: CharSequence, i: Int): Boolean {
        if (i <= 0) return false
        val cp = Character.codePointBefore(text, i)
        if (isApostrophe(cp)) {
            val left = i - 1
            return left > 0 && isWordCodePoint(Character.codePointBefore(text, left)) &&
                i < text.length && isWordCodePoint(Character.codePointAt(text, i))
        }
        return isWordCodePoint(cp)
    }

    /** Whether the code point starting at [i] belongs to a word. */
    fun isWordAt(text: CharSequence, i: Int): Boolean {
        if (i >= text.length) return false
        val cp = Character.codePointAt(text, i)
        if (isApostrophe(cp)) {
            return i > 0 && isWordCodePoint(Character.codePointBefore(text, i)) &&
                i + 1 < text.length && isWordCodePoint(Character.codePointAt(text, i + 1))
        }
        return isWordCodePoint(cp)
    }

    private fun isApostrophe(cp: Int) = cp == '\''.code || cp == '’'.code

    private fun isWordCodePoint(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    // ------------------------------------------------------------------ helpers

    private fun caret(w: EditorTextWindow, local: Int): Plan = (w.base + local).let { Plan.Select(it, it) }

    /** [range] (window-local) as an absolute selection, or Unknown when it reaches a cut edge. */
    private fun bounded(w: EditorTextWindow, range: IntRange?): Plan {
        if (range == null) return Plan.NoTarget
        val start = range.first
        val end = range.last + 1
        if ((start == 0 && !w.beforeComplete) || (end == w.text.length && !w.afterComplete)) return Plan.Unknown
        return Plan.Select(w.base + start, w.base + end)
    }
}
