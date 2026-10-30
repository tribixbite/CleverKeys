package tribixbite.cleverkeys.customization

/**
 * Edits one subkey of one key in a layout's XML text, addressed by position: the [keyIndex]-th
 * `<key>` of the [rowIndex]-th `<row>` of the root `<keyboard>` — the same order
 * `KeyboardData.parse_keyboard` reads them in, so `KeyboardData.rows[r].keys[c]` is that element
 * (`LayoutSubkeyBuiltInLayoutsTest` checks every built-in layout).
 *
 * Used by the per-key customization dialog to write a LAYOUT-defined subkey into the user's
 * modified copy of a layout (docs/specs/subkey-customization-current.md "Layout subkeys and
 * modified copies"). Pure JVM, so it is unit-testable.
 *
 * The edit is made on the TEXT: only the one start tag changes, everything else (comments,
 * formatting, characters) stays byte for byte. A DOM round trip was tried first and rejected:
 * the JDK serializer wrote characters outside the BMP (`𝕨` in latn_qwerty_bqn) as numeric
 * references, which the kxml2 parser Android uses reads back truncated to 16 bits (`함`).
 *
 * A direction has two synonymous attributes (`nw` / `key1` …) and the parser rejects a key
 * carrying both, so a write always removes both before setting the short name.
 */
object LayoutSubkeyXml {

    /** The short attribute name of [direction] (`nw`, `n` …). */
    fun shortAttribute(direction: SwipeDirection): String = direction.name.lowercase()

    /** The numbered synonym of [direction] (`key1` … `key8`, `KeyboardData.Key.parse`). */
    fun numberedAttribute(direction: SwipeDirection): String = "key${direction.subLabelIndex}"

    /**
     * [xml] with the [direction] subkey of key ([rowIndex], [keyIndex]) set to [value] (a layout
     * key description such as `voice_input` or `…:'hello'`), or removed when [value] is null.
     *
     * @throws IllegalArgumentException when the XML has no such key.
     */
    fun setSubkey(xml: String, rowIndex: Int, keyIndex: Int, direction: SwipeDirection, value: String?): String {
        val tag = structure(xml).keys.getOrNull(rowIndex)?.getOrNull(keyIndex)
            ?: throw IllegalArgumentException("No key $keyIndex in row $rowIndex")
        var text = xml.substring(tag.start, tag.end)
        text = withoutAttribute(text, shortAttribute(direction))
        text = withoutAttribute(text, numberedAttribute(direction))
        if (value != null) text = withAttribute(text, shortAttribute(direction), value)
        return xml.substring(0, tag.start) + text + xml.substring(tag.end)
    }

    /** [xml] with the root `name` attribute (the layout's display name) set to [name]. */
    fun rename(xml: String, name: String): String {
        val root = structure(xml).root ?: throw IllegalArgumentException("No root element")
        val text = withAttribute(withoutAttribute(xml.substring(root.start, root.end), "name"), "name", name)
        return xml.substring(0, root.start) + text + xml.substring(root.end)
    }

    /**
     * The main value (`key0`, or its synonym `c`) of every `<key>` of each `<row>` of [xml], in
     * the parser's order, with XML references decoded (`&amp;` → `&`); null for a key without one.
     *
     * @throws IllegalArgumentException when the markup is unterminated.
     */
    fun mainValues(xml: String): List<List<String?>> = structure(xml).keys.map { row ->
        row.map { tag ->
            val text = xml.substring(tag.start, tag.end)
            attributeValue(text, "key0") ?: attributeValue(text, "c")
        }
    }

    /** The decoded value of attribute [name] in start-tag [tag] text, or null. */
    private fun attributeValue(tag: String, name: String): String? {
        val m = Regex("""\s${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(tag) ?: return null
        return unescape(m.groups[1]?.value ?: m.groups[2]?.value ?: "")
    }

    /** Decode the five predefined entities and numeric character references of an attribute. */
    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(value) { m ->
            when (val ref = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val cp = if (ref.startsWith("#x")) ref.substring(2).toInt(16) else ref.substring(1).toInt()
                    String(Character.toChars(cp))
                }
            }
        }
    }

    /** Number of `<key>` elements in each `<row>` of [xml], in order. */
    fun keyCounts(xml: String): List<Int> = structure(xml).keys.map { it.size }

    /** A start tag in the text: [start] at `<`, [end] just past `>`. */
    private data class StartTag(val start: Int, val end: Int, val name: String, val selfClosing: Boolean)

    /** The root start tag and, per `<row>` child of the root, its `<key>` children. */
    private class Structure(val root: StartTag?, val keys: List<List<StartTag>>)

    /**
     * Scan the start and end tags of [xml] (skipping comments, CDATA, processing instructions
     * and declarations) and collect the root, its `<row>` children and their `<key>` children.
     */
    private fun structure(xml: String): Structure {
        var root: StartTag? = null
        val rows = ArrayList<MutableList<StartTag>>()
        // Element names from the root down to the current element.
        val stack = ArrayList<String>()
        var i = 0
        while (true) {
            i = xml.indexOf('<', i)
            if (i < 0) break
            when {
                xml.startsWith("<!--", i) -> i = skipPast(xml, i, "-->")
                xml.startsWith("<![CDATA[", i) -> i = skipPast(xml, i, "]]>")
                xml.startsWith("<?", i) -> i = skipPast(xml, i, "?>")
                xml.startsWith("<!", i) -> i = skipPast(xml, i, ">")
                xml.startsWith("</", i) -> {
                    i = tagEnd(xml, i)
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                else -> {
                    val end = tagEnd(xml, i)
                    val name = tagName(xml, i + 1)
                    val selfClosing = xml[end - 2] == '/'
                    val tag = StartTag(i, end, name, selfClosing)
                    when {
                        stack.isEmpty() && root == null -> root = tag
                        stack.size == 1 && name == "row" -> rows.add(ArrayList())
                        stack.size == 2 && name == "key" && stack[1] == "row" -> rows.last().add(tag)
                    }
                    if (!selfClosing) stack.add(name)
                    i = end
                }
            }
        }
        return Structure(root, rows)
    }

    private fun skipPast(xml: String, from: Int, terminator: String): Int {
        val j = xml.indexOf(terminator, from)
        require(j >= 0) { "Unterminated markup at $from" }
        return j + terminator.length
    }

    /** Index just past the `>` closing the tag that starts at [from] (quotes respected). */
    private fun tagEnd(xml: String, from: Int): Int {
        var quote: Char? = null
        var i = from + 1
        while (i < xml.length) {
            val c = xml[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i + 1
            }
            i++
        }
        throw IllegalArgumentException("Unterminated tag at $from")
    }

    private fun tagName(xml: String, from: Int): String {
        var i = from
        while (i < xml.length && !xml[i].isWhitespace() && xml[i] != '/' && xml[i] != '>') i++
        return xml.substring(from, i)
    }

    /** [tag] text without attribute [name] (and the whitespace before it). */
    private fun withoutAttribute(tag: String, name: String): String =
        Regex("""\s+${Regex.escape(name)}\s*=\s*("[^"]*"|'[^']*')""").replace(tag, "")

    /** [tag] text with ` name="value"` added before its closing `>` or `/>`. */
    private fun withAttribute(tag: String, name: String, value: String): String {
        val close = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        var insertAt = close
        while (insertAt > 0 && tag[insertAt - 1].isWhitespace()) insertAt--
        return tag.substring(0, insertAt) + " $name=\"${escape(value)}\"" + tag.substring(insertAt)
    }

    /** Escape [value] for a double-quoted attribute; line breaks and tabs as references. */
    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(c)
            }
        }
    }
}
