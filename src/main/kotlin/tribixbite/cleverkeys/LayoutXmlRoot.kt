package tribixbite.cleverkeys

/**
 * Locates the `<keyboard …>` root start tag of a layout XML document as text, for code that edits
 * or reads root attributes without the XML pull parser (theme binding, key geometry, the backup
 * preview, Export XML's embedded theme).
 *
 * Review 2026-11-14 (low): each of those used `Regex("<keyboard(\\s[^>]*)?>")`, which matches the
 * first textual `<keyboard` ANYWHERE — including a sample inside a header comment, as in the worked
 * example layout — and stops at a `>` inside a quoted attribute value (`name="a>b"`). Export then
 * rewrote the comment instead of the root, and the preview read the sample's attributes.
 *
 * This scanner skips comments, CDATA sections, processing instructions and the DOCTYPE, takes the
 * document's FIRST element (a root that is not `<keyboard>` yields null) and ends the tag at the
 * first `>` outside quotes.
 */
object LayoutXmlRoot {

    /**
     * The root start tag: [range] covers `<keyboard` through `>`, and [attributes] is the text in
     * between (leading whitespace and a self-closing `/` included), as `groupValues[1]` was.
     */
    data class Tag(val range: IntRange, val attributes: String)

    private const val NAME = "keyboard"

    /** The root `<keyboard>` start tag of [xml], or null when the first element is something else. */
    fun find(xml: String): Tag? {
        var i = 0
        while (true) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) return null
            i = when {
                xml.startsWith("<!--", lt) -> skipPast(xml, lt + 4, "-->") ?: return null
                xml.startsWith("<![CDATA[", lt) -> skipPast(xml, lt + 9, "]]>") ?: return null
                xml.startsWith("<?", lt) -> skipPast(xml, lt + 2, "?>") ?: return null
                xml.startsWith("<!", lt) -> skipDeclaration(xml, lt + 2) ?: return null
                else -> return rootTag(xml, lt)
            }
        }
    }

    /** The first element starts at [lt]: the tag when it is `<keyboard`, else null. */
    private fun rootTag(xml: String, lt: Int): Tag? {
        val nameEnd = lt + 1 + NAME.length
        if (!xml.startsWith(NAME, lt + 1)) return null
        // `<keyboardx` is another element; the name must end at whitespace, `>` or `/`.
        if (nameEnd < xml.length && !(xml[nameEnd].isWhitespace() || xml[nameEnd] == '>' || xml[nameEnd] == '/')) {
            return null
        }
        var quote: Char? = null
        var j = nameEnd
        while (j < xml.length) {
            val c = xml[j]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return Tag(lt..j, xml.substring(nameEnd, j))
            }
            j++
        }
        return null // unterminated start tag
    }

    /** Index just past [end], searching from [from]; null when it never occurs. */
    private fun skipPast(xml: String, from: Int, end: String): Int? =
        xml.indexOf(end, from).takeIf { it >= 0 }?.let { it + end.length }

    /** Past a `<!DOCTYPE …>` declaration, allowing one level of `[ … ]` internal subset. */
    private fun skipDeclaration(xml: String, from: Int): Int? {
        var depth = 0
        var j = from
        while (j < xml.length) {
            when (xml[j]) {
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                '>' -> if (depth == 0) return j + 1
            }
            j++
        }
        return null
    }
}
