package tribixbite.cleverkeys.theme

import tribixbite.cleverkeys.Suggestion

/**
 * How a keyboard LOOK arranges the suggestion bar (2026-10-27, research
 * `docs/research/2026-10-27-familiar-keyboard-looks.md` §10).
 *
 * The four products show a fixed row of [slots] equal-width slots (three) with the best candidate
 * in the CENTRE (Gboard, SwiftKey — "press the spacebar to insert the middle prediction" —,
 * Samsung, iOS). The typed word, when it is not the best candidate, takes the slot left of the
 * centre (SwiftKey's "verbatim" slot, iOS's leading suggestion), and iOS wraps it in quotation
 * marks ([quoteTyped]). Separators between slots follow the look's bar-separator colour
 * ([LookPalette.barSeparator]; alpha 0 = none), as they always did.
 *
 * Every non-look theme has no [LookBar] and keeps CleverKeys' scrolling chips (best first).
 * Drawing only: the bar's data (`SuggestionBar.getCurrentSuggestions`, the highlight index, what
 * space commits, the commit path) stays in ranking order; [LookBarArrangement] only decides which
 * entry each visual slot shows.
 *
 * @property slots number of visual slots, [MIN_SLOTS]..[MAX_SLOTS].
 * @property centreBest whether the best candidate takes the centre slot (otherwise slots fill
 *   left to right in ranking order).
 * @property quoteTyped whether the typed word is shown in quotation marks when it is not the best
 *   candidate (iOS).
 * @property emphasis how the slots are inked ([BarEmphasis]).
 *
 * On every look the typed word's slot shows the plain word, never CleverKeys' "+word" chip: the
 * real Gboard and SwiftKey show an unknown prefix as an ordinary candidate (`tomorr | tomorrow |
 * tomorrow's`, Pixel 8 Pro 2026-10-27) and learn a word tapped there. The slot still taps the
 * tap-to-add entry, so a tap commits the word as typed and adds it to the dictionary, as before.
 */
data class LookBar(
    val slots: Int = DEFAULT_SLOTS,
    val centreBest: Boolean = true,
    val quoteTyped: Boolean = false,
    val emphasis: BarEmphasis = BarEmphasis.COMMIT,
) {
    init {
        require(slots in MIN_SLOTS..MAX_SLOTS) { "bar slots $slots outside $MIN_SLOTS..$MAX_SLOTS" }
    }

    companion object {
        const val DEFAULT_SLOTS = 3
        const val MIN_SLOTS = 1
        const val MAX_SLOTS = 5
    }
}

/**
 * How a look's bar inks its slots (device references, Pixel 8 Pro 2026-10-27,
 * `build/oct27-night-pixel/`).
 */
enum class BarEmphasis {
    /**
     * Gboard (also iOS, Samsung): every slot in the text colour; the centre is bold in the
     * highlight colour only while it is what space commits — a pending correction — and plain for
     * next-word predictions (the real Gboard: `at | morning | and`, nothing bold).
     */
    COMMIT,

    /**
     * SwiftKey: the centre always in the text colour at regular weight, both sides dimmed
     * ([LookBarArrangement.sideColor]: real dark #FFFFFF / #939393, light #292929 / #777777).
     */
    CENTRE,
}

/** Which colour a filled slot's text takes. */
enum class SlotInk {
    /** The bar's highlight (commit-on-space) colour. */
    HIGHLIGHT,

    /** The bar's text colour. */
    TEXT,

    /** The text colour dimmed toward the bar background ([LookBarArrangement.sideColor]). */
    SIDE,
}

/** How one filled slot of a look's bar is drawn: [ink] and weight. */
data class SlotPaint(val ink: SlotInk, val bold: Boolean)

/**
 * One visual slot of an arranged bar.
 *
 * @property entry index into the bar's entry list (ranking order) shown here, or null for an
 *   empty slot (fewer candidates than slots).
 * @property quoted whether the entry is shown in quotation marks ([LookBar.quoteTyped]).
 * @property centre whether this is the centre slot (the best candidate's place).
 */
data class BarSlot(val entry: Int?, val quoted: Boolean = false, val centre: Boolean = false)

/** Which entry each visual slot of a look's bar shows. Pure; `SuggestionBarArrangementTest`. */
object LookBarArrangement {

    /**
     * The visual slots, left to right, for [entries] (the bar's list in ranking order), or null
     * when the bar keeps its plain layout: no look bar ([bar] null), nothing to show, or a prompt
     * on the bar ("Add to dictionary?", "Prefer … when swiping?" and its "Don't ask"), which is a
     * question rather than candidates and is shown as before.
     *
     * - The BEST entry is [highlightIndex] (what space commits, `BarHighlightPlanner`) when that is
     *   a word, else the first word. It takes the centre slot (`(slots - 1) / 2`).
     * - The TYPED entry — the word equal to [typedWord], or the "+word" tap-to-add chip (always the
     *   typed word) — takes the slot left of the centre when it is not the best, so typing an
     *   unknown word always leaves a way to keep it. Quoted under [LookBar.quoteTyped].
     * - The rest follow in ranking order, alternating right / left of the centre outwards.
     * - Entries that do not fit are not shown (the products show three); they stay in the data.
     */
    fun arrange(entries: List<Suggestion>, highlightIndex: Int?, typedWord: String?, bar: LookBar?): List<BarSlot>? {
        if (bar == null || entries.isEmpty()) return null
        if (entries.any { it.isPrompt() }) return null

        val best = highlightIndex?.takeIf { it in entries.indices && entries[it] is Suggestion.Word }
            ?: entries.indexOfFirst { it is Suggestion.Word }.takeIf { it >= 0 }
            ?: 0
        val typed = entries.indices.firstOrNull { i ->
            i != best && when (val e = entries[i]) {
                is Suggestion.ExactAdd -> true
                is Suggestion.Word -> typedWord != null && e.text == typedWord
                else -> false
            }
        }
        val order = ArrayList<Int>(entries.size)
        order.add(best)
        typed?.let { order.add(it) }
        for (i in entries.indices) if (i != best && i != typed) order.add(i)

        val positions = positions(bar)
        val slots = arrayOfNulls<BarSlot>(bar.slots)
        for ((k, pos) in positions.withIndex()) {
            val entry = order.getOrNull(k)
            slots[pos] = BarSlot(entry, quoted = bar.quoteTyped && entry != null && entry == typed, centre = k == 0)
        }
        return slots.map { it ?: BarSlot(null) }
    }

    /**
     * The text a slot shows for [entry]: the word (the tap-to-add chip's word without its "+"),
     * in [open] / [close] quotation marks when [quoted].
     */
    fun slotText(entry: Suggestion, quoted: Boolean, open: String, close: String): String {
        val word = when (entry) {
            is Suggestion.ExactAdd -> entry.word
            is Suggestion.Word -> entry.text
            else -> entry.wire
        }
        return if (quoted) quote(word, open, close) else word
    }

    /**
     * The entry whose slot is emphasised as the space commit: [highlightIndex] (what space
     * commits, `BarHighlightPlanner`), or none for next-word predictions — after a completed word
     * space inserts a space and commits none of them.
     */
    fun commitIndex(highlightIndex: Int?, nextWordOnly: Boolean): Int? = highlightIndex.takeUnless { nextWordOnly }

    /** How a filled slot is drawn under [bar]'s [LookBar.emphasis]. */
    fun paint(bar: LookBar, centre: Boolean, commit: Boolean): SlotPaint = when (bar.emphasis) {
        BarEmphasis.COMMIT ->
            if (commit) SlotPaint(SlotInk.HIGHLIGHT, bold = true) else SlotPaint(SlotInk.TEXT, bold = false)
        BarEmphasis.CENTRE ->
            if (centre) SlotPaint(SlotInk.TEXT, bold = false) else SlotPaint(SlotInk.SIDE, bold = false)
    }

    /**
     * SwiftKey's dimmed side-slot colour: [text] at [SIDE_ALPHA] over [background], opaque. One
     * fraction for both modes: 0.54 matches dark exactly and 0.61 light; 0.58 lands 9 and 6
     * levels from the real #939393 / #777777.
     */
    fun sideColor(text: Int, background: Int): Int {
        fun mix(shift: Int): Int {
            val t = (text ushr shift) and 0xFF
            val b = (background ushr shift) and 0xFF
            return (b + (t - b) * SIDE_ALPHA + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** See [sideColor]. */
    const val SIDE_ALPHA = 0.58f

    /**
     * A slot's smallest auto-sized text (sp) before its word is elided in the middle; the bar's
     * text is 16 sp. Real SwiftKey draws a long side candidate at about 0.75 of its centre size.
     */
    const val SLOT_MIN_TEXT_SP = 11

    /** A slot's horizontal padding (dp): three slots share the bar, so less than a chip's 12. */
    const val SLOT_PADDING_DP = 4

    /**
     * The visual position of each place in ranking order: centre first, then left, right, left…
     * outwards for a centre-first bar (so the second place — the typed word when shown — is left
     * of the centre); plain left-to-right otherwise.
     */
    fun positions(bar: LookBar): List<Int> {
        if (!bar.centreBest) return (0 until bar.slots).toList()
        val centre = (bar.slots - 1) / 2
        val out = ArrayList<Int>(bar.slots)
        out.add(centre)
        var d = 1
        while (out.size < bar.slots) {
            if (centre - d >= 0) out.add(centre - d)
            if (centre + d < bar.slots && out.size < bar.slots) out.add(centre + d)
            d++
        }
        return out
    }

    /** [word] in the locale's quotation marks ([open] / [close], e.g. “ ” or « »). */
    fun quote(word: String, open: String, close: String): String = open + word + close

    /** Whether a separator is drawn between two neighbouring slots: only between two filled slots. */
    fun separatorBetween(left: BarSlot, right: BarSlot): Boolean = left.entry != null && right.entry != null

    private fun Suggestion.isPrompt(): Boolean = when (this) {
        is Suggestion.AddToDictionary, is Suggestion.PreferSwipeWord, is Suggestion.DeclineSwipePreference -> true
        is Suggestion.Word, is Suggestion.ExactAdd -> false
    }
}
