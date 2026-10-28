package tribixbite.cleverkeys.theme

import java.util.Locale

/**
 * The editor action a field asks for (`EditorInfo.imeOptions and IME_MASK_ACTION`), as the
 * editor-action key shows it. Pure: the platform constants are spelled out ([id]) so the mapping
 * runs in `runPureTests`.
 */
enum class ActionKind(val id: Int, val xmlName: String) {
    GO(2, "go"),            // EditorInfo.IME_ACTION_GO
    SEARCH(3, "search"),    // EditorInfo.IME_ACTION_SEARCH
    SEND(4, "send"),        // EditorInfo.IME_ACTION_SEND
    NEXT(5, "next"),        // EditorInfo.IME_ACTION_NEXT
    DONE(6, "done"),        // EditorInfo.IME_ACTION_DONE
    PREVIOUS(7, "previous"), // EditorInfo.IME_ACTION_PREVIOUS
    /** Plain Enter (a multi-line field's new line), not an IME action: [of] never returns it. */
    ENTER(-1, "enter"),
    ;

    companion object {
        /** The kind for an IME action id; null for unspecified (0), none (1) or a custom label. */
        fun of(imeAction: Int): ActionKind? = entries.firstOrNull { it.id == imeAction && it != ENTER }

        fun byXmlName(name: String): ActionKind? = entries.firstOrNull { it.xmlName == name }
    }
}

/** CleverKeys' own line icons for the editor-action key (drawn as paths, no product artwork). */
enum class ActionGlyph(val xmlName: String) {
    /** → */
    ARROW("arrow"),
    /** Magnifier. */
    SEARCH("search"),
    /** Filled paper plane (Gboard). */
    SEND("send"),
    /** Outlined paper plane (SwiftKey). */
    SEND_OUTLINE("send_outline"),
    /** →| */
    NEXT("next"),
    /** |← */
    PREVIOUS("previous"),
    /** ✓ */
    DONE("done"),
    /** ↵ */
    RETURN("return"),
    /** ↑ (iOS send). */
    ARROW_UP("arrow_up"),
    ;

    companion object {
        fun byXmlName(name: String): ActionGlyph? = entries.firstOrNull { it.xmlName == name }
    }
}

/** One stroke of an [ActionGlyph] in a unit box (0..1, y down). */
sealed interface IconPart {
    /** A polyline; [closed] joins the last point to the first; [filled] paints its inside. */
    data class Path(val points: List<Pair<Float, Float>>, val closed: Boolean = false, val filled: Boolean = false) : IconPart

    /** A stroked circle. */
    data class Circle(val cx: Float, val cy: Float, val r: Float) : IconPart
}

/**
 * How a look draws its editor-action key (the Enter replacement in Go / Search / Send / Next /
 * Done / Previous fields).
 *
 * @property icons the icon per action; an action without one shows its text label, as before.
 * @property accent the actions drawn with the look's action colours (`LookPalette.actionKey` /
 *   `actionLabel`: iOS's blue prominent return); the others take the Enter key's colours. null =
 *   every action (the behaviour without a style).
 * @property lowercase whether a text label is shown in lower case (iOS 18: "go", "search").
 */
data class LookActionStyle(
    val icons: Map<ActionKind, ActionGlyph> = emptyMap(),
    val accent: Set<ActionKind>? = null,
    val lowercase: Boolean = false,
)

/** Pure decisions for a look's editor-action key; `LookActionKeysTest`. */
object LookActionKeys {

    /** The icon for [kind] under [style]; null = draw the text label (no style, no kind, no icon). */
    fun glyph(style: LookActionStyle?, kind: ActionKind?): ActionGlyph? =
        if (style == null || kind == null) null else style.icons[kind]

    /** Whether [kind] takes the look's action colours rather than the Enter key's. */
    fun usesActionFill(style: LookActionStyle?, kind: ActionKind?): Boolean {
        val accent = style?.accent ?: return true
        return kind != null && kind in accent
    }

    /** The text label shown when there is no icon: [label], lower-cased on a [LookActionStyle.lowercase] look. */
    fun text(style: LookActionStyle?, label: String, locale: Locale): String =
        if (style?.lowercase == true) label.lowercase(locale) else label

    /** Stroke width as a fraction of the icon box. */
    const val STROKE = 0.09f

    /**
     * Icon box side as a fraction of the key's MAIN label size — the same for the action key
     * (whose text is drawn at the smaller size) and plain Enter, so both icons match.
     */
    const val BOX_SCALE = 0.79f

    private fun p(vararg xy: Float, closed: Boolean = false, filled: Boolean = false) =
        IconPart.Path(xy.toList().chunked(2) { it[0] to it[1] }, closed, filled)

    /** The strokes of [glyph] in a unit box. */
    fun parts(glyph: ActionGlyph): List<IconPart> = when (glyph) {
        ActionGlyph.ARROW -> listOf(p(0.12f, 0.5f, 0.86f, 0.5f), p(0.56f, 0.2f, 0.86f, 0.5f, 0.56f, 0.8f))
        ActionGlyph.ARROW_UP -> listOf(p(0.5f, 0.88f, 0.5f, 0.14f), p(0.2f, 0.44f, 0.5f, 0.14f, 0.8f, 0.44f))
        ActionGlyph.NEXT -> listOf(
            p(0.1f, 0.5f, 0.7f, 0.5f), p(0.44f, 0.24f, 0.7f, 0.5f, 0.44f, 0.76f), p(0.88f, 0.2f, 0.88f, 0.8f),
        )
        ActionGlyph.PREVIOUS -> listOf(
            p(0.9f, 0.5f, 0.3f, 0.5f), p(0.56f, 0.24f, 0.3f, 0.5f, 0.56f, 0.76f), p(0.12f, 0.2f, 0.12f, 0.8f),
        )
        ActionGlyph.DONE -> listOf(p(0.14f, 0.52f, 0.4f, 0.78f, 0.88f, 0.26f))
        ActionGlyph.RETURN -> listOf(
            p(0.86f, 0.18f, 0.86f, 0.62f, 0.16f, 0.62f), p(0.4f, 0.38f, 0.16f, 0.62f, 0.4f, 0.86f),
        )
        ActionGlyph.SEARCH -> listOf(IconPart.Circle(0.42f, 0.42f, 0.27f), p(0.62f, 0.62f, 0.88f, 0.88f))
        ActionGlyph.SEND -> listOf(
            p(0.12f, 0.14f, 0.9f, 0.5f, 0.12f, 0.86f, 0.12f, 0.58f, 0.56f, 0.5f, 0.12f, 0.42f, closed = true, filled = true),
        )
        ActionGlyph.SEND_OUTLINE -> listOf(
            p(0.12f, 0.14f, 0.9f, 0.5f, 0.12f, 0.86f, 0.28f, 0.5f, closed = true), p(0.28f, 0.5f, 0.9f, 0.5f),
        )
    }

    /** `<theme action_icons>`: space-separated `kind:glyph` pairs. */
    fun formatIcons(icons: Map<ActionKind, ActionGlyph>): String =
        ActionKind.entries.filter { it in icons }.joinToString(" ") { "${it.xmlName}:${icons.getValue(it).xmlName}" }

    /** Parse [formatIcons]'s form; null when any pair is malformed. */
    fun parseIcons(raw: String): Map<ActionKind, ActionGlyph>? {
        val out = LinkedHashMap<ActionKind, ActionGlyph>()
        for (pair in raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val (k, g) = pair.split(':').takeIf { it.size == 2 } ?: return null
            out[ActionKind.byXmlName(k) ?: return null] = ActionGlyph.byXmlName(g) ?: return null
        }
        return out
    }

    /** `<theme action_accent>`: space-separated kinds. */
    fun formatKinds(kinds: Set<ActionKind>): String = ActionKind.entries.filter { it in kinds }.joinToString(" ") { it.xmlName }

    /** Parse [formatKinds]'s form (empty = none); null when a name is unknown. */
    fun parseKinds(raw: String): Set<ActionKind>? {
        val out = LinkedHashSet<ActionKind>()
        for (n in raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) out.add(ActionKind.byXmlName(n) ?: return null)
        return out
    }
}
