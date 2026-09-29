package tribixbite.cleverkeys

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Pure-JVM [ResultText] that resolves `R.string` / `R.plurals` ids to the ENGLISH text in
 * `res/values/` — the same text a device with an English locale shows.
 *
 * Why: the backup/restore result builders and the import-preview diff renderers take a
 * [ResultText] so they stay free of `android.content.Context`. Tests that used to pin their
 * English output keep pinning it through this resolver, so a copy edit or a dropped resource
 * argument still fails a test — while production resolves the same ids per locale.
 *
 * Resolution: the id is mapped back to its resource NAME by reflection over the generated
 * `R.string` / `R.plurals` classes, then looked up in every `res/values/` XML file (strings.xml plus
 * any temporary sweep shim). Plurals follow English rules (`one` for 1, else `other`). Android's
 * string escapes (`\'`, `\"`, `\n`, `\t`, surrounding quotes) are undone, and arguments are
 * formatted with `String.format(Locale.US, …)` like `Resources.getString(id, args)`.
 * Runs with the project root as CWD, like [TranslationResources].
 */
internal object EnglishResourceText : ResultText {

    private data class Tables(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val tables: Tables by lazy {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        File("res/values").listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val root = factory.newDocumentBuilder().parse(file).documentElement
                val children = root.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i) as? Element ?: continue
                    val name = node.getAttribute("name").orEmpty()
                    if (name.isEmpty()) continue
                    when (node.tagName) {
                        "string" -> strings[name] = unescape(node.textContent.orEmpty())
                        "plurals" -> {
                            val items = LinkedHashMap<String, String>()
                            val itemNodes = node.getElementsByTagName("item")
                            for (j in 0 until itemNodes.length) {
                                val item = itemNodes.item(j) as Element
                                items[item.getAttribute("quantity")] = unescape(item.textContent.orEmpty())
                            }
                            plurals[name] = items
                        }
                    }
                }
            }
        Tables(strings, plurals)
    }

    /** Resource id -> name for a generated R inner class, e.g. `R.string`. */
    private fun namesOf(rClass: Class<*>): Map<Int, String> =
        rClass.fields.associate { it.getInt(null) to it.name }

    private val stringNames by lazy { namesOf(R.string::class.java) }
    private val pluralNames by lazy { namesOf(R.plurals::class.java) }

    /** Undo the Android string-resource escapes that change the rendered text. */
    private fun unescape(raw: String): String {
        val trimmed = raw.trim()
        val body = if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                val n = body[i + 1]
                if (n == 'u' && i + 6 <= body.length) {
                    out.append(body.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 6
                    continue
                }
                when (n) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(n) // \' \" \\ \@ \?
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun format(text: String, args: Array<out Any>): String =
        if (args.isEmpty()) text else String.format(Locale.US, text, *args)

    override fun string(id: Int, vararg args: Any): String {
        val name = stringNames[id] ?: error("no R.string field has id $id")
        val text = tables.strings[name] ?: error("R.string.$name has no English text in res/values")
        return format(text, args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val name = pluralNames[id] ?: error("no R.plurals field has id $id")
        val items = tables.plurals[name] ?: error("R.plurals.$name has no English text in res/values")
        val text = (if (count == 1) items["one"] else null) ?: items.getValue("other")
        return format(text, args)
    }
}
