package tribixbite.cleverkeys.theme

/**
 * Per-key marks a keyboard look draws (2026-10-30, research §13): a product's own small legend
 * on a particular key ([LookKeyLegend], SwiftKey's mic over the comma and ",!?" over the period)
 * and a filled Shift arrow while shifted ([LookShiftGlyph], Gboard). Drawing only: gestures,
 * TalkBack labels and accessibility actions are unchanged. Pure; `LookKeyMarksTest`.
 */

/** A legend drawn as one of CleverKeys' own key-font icons, not as text. */
enum class LookLegendGlyph(val xmlName: String, val fontChar: Char) {
    /** The microphone of CleverKeys' `voice_typing` key (key font U+E015). */
    MIC("mic", ''),
    ;

    companion object {
        fun byXmlName(name: String): LookLegendGlyph? = entries.firstOrNull { it.xmlName == name }
    }
}

/**
 * The legend a look draws at the top centre of the key whose main character is [key]: either
 * [text] (e.g. ",!?") or a [glyph] (the mic), in the look's hint colour and hint size.
 *
 * A [LookLegendGlyph.MIC] legend is a promise that the key opens voice input, so it is drawn only
 * when one of the key's swipes carries a voice key; a text legend only when the key has at least
 * one swipe ([LookKeyLegends.legendFor]).
 */
data class LookKeyLegend(val key: Char, val text: String? = null, val glyph: LookLegendGlyph? = null) {
    init {
        require((text == null) != (glyph == null)) { "a key legend is either text or a glyph" }
        require(text == null || (text.isNotEmpty() && text.length <= MAX_TEXT && text.none { it.isWhitespace() } && !text.startsWith("@"))) {
            "key legend text \"$text\" must be 1..$MAX_TEXT characters without spaces, not starting with @"
        }
        require(!key.isWhitespace()) { "a key legend's key cannot be whitespace" }
    }

    companion object {
        /** Longest legend text (a short hint, not a label). */
        const val MAX_TEXT = 4
    }
}

/** Which key legend is drawn, and the `<theme key_legends>` text form. */
object LookKeyLegends {

    /**
     * The legend drawn on a key whose main character is [mainChar] (null = not a character key),
     * or null. Legends replace the gesture hints, so none is drawn while every corner label is
     * ([effective] = [LookLegends.ALL]: no look, or "Show gesture hints on familiar looks" on).
     * A mic legend needs a voice key on the key ([hasVoiceSwipe]); a text legend any swipe
     * ([hasSwipe]).
     */
    fun legendFor(
        legends: List<LookKeyLegend>,
        effective: LookLegends,
        mainChar: Char?,
        hasVoiceSwipe: Boolean,
        hasSwipe: Boolean,
    ): LookKeyLegend? {
        if (legends.isEmpty() || mainChar == null || effective == LookLegends.ALL) return null
        val legend = legends.firstOrNull { it.key == mainChar } ?: return null
        val shown = when (legend.glyph) {
            LookLegendGlyph.MIC -> hasVoiceSwipe
            null -> hasSwipe
        }
        return legend.takeIf { shown }
    }

    /** `<theme key_legends>`: space-separated `key:text` or `key:@glyph` entries (`,:@mic .:,!?`). */
    fun format(legends: List<LookKeyLegend>): String = legends.joinToString(" ") { l ->
        "${l.key}:" + (l.glyph?.let { "@${it.xmlName}" } ?: l.text!!)
    }

    /** Parse [format]'s form (empty = none); null when an entry is malformed. */
    fun parse(raw: String): List<LookKeyLegend>? {
        val out = ArrayList<LookKeyLegend>()
        for (entry in raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            if (entry.length < 3 || entry[1] != ':') return null
            val value = entry.substring(2)
            // "@name" is always a glyph (a text legend cannot start with "@").
            val legend = if (value.startsWith("@")) {
                LookLegendGlyph.byXmlName(value.substring(1))?.let { LookKeyLegend(entry[0], glyph = it) } ?: return null
            } else {
                runCatching { LookKeyLegend(entry[0], text = value) }.getOrNull() ?: return null
            }
            if (out.any { it.key == legend.key }) return null
            out.add(legend)
        }
        return out
    }
}

/** Shift's state as drawn. */
enum class ShiftDrawState {
    /** Not engaged: CleverKeys' outline glyph. */
    REST,

    /** One-shot or auto-capitalised. */
    SHIFTED,

    /** Caps Lock. */
    LOCKED,
}

/**
 * CleverKeys' own filled Shift icon for a look with [KeyboardLook.shiftFilled] (Gboard): a
 * filled arrow while shifted, the same arrow over a bar in Caps Lock, and nothing (the key
 * font's outline glyph) at rest. Unit box, y down; drawn with the editor-action key's icon
 * painter.
 */
object LookShiftGlyph {

    /** Icon box side as a fraction of the main label size (the outline glyph's visual size). */
    const val BOX_SCALE = 0.78f

    /** The state of a Shift key drawn [down] (pressed or latched) with [locked] (Caps Lock). */
    fun state(down: Boolean, locked: Boolean): ShiftDrawState = when {
        locked -> ShiftDrawState.LOCKED
        down -> ShiftDrawState.SHIFTED
        else -> ShiftDrawState.REST
    }

    /** The parts to draw for [state] under a [filled] look, or null to draw the font glyph. */
    fun parts(filled: Boolean, state: ShiftDrawState): List<IconPart>? {
        if (!filled) return null
        return when (state) {
            ShiftDrawState.REST -> null
            ShiftDrawState.SHIFTED -> listOf(arrow(bottom = 0.93f))
            ShiftDrawState.LOCKED -> listOf(arrow(bottom = 0.72f), bar())
        }
    }

    /**
     * The arrow: apex at the top, head to 0.5, stem down to [bottom]; closed and filled. Its
     * 0.8 × 0.87 extent matches the key font's outline glyph (43 × 47 px at 1080 px wide,
     * emulator.wtf 2026-10-30), so the key does not change size when Shift engages.
     */
    private fun arrow(bottom: Float) = IconPart.Path(
        listOf(
            0.5f to 0.06f, 0.9f to 0.5f, 0.68f to 0.5f, 0.68f to bottom,
            0.32f to bottom, 0.32f to 0.5f, 0.1f to 0.5f,
        ),
        closed = true, filled = true,
    )

    /** Caps Lock's bar under the shortened arrow. */
    private fun bar() = IconPart.Path(
        listOf(0.32f to 0.81f, 0.68f to 0.81f, 0.68f to 0.93f, 0.32f to 0.93f),
        closed = true, filled = true,
    )
}
