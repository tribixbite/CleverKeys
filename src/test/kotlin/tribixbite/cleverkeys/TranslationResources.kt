package tribixbite.cleverkeys

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Shared reader for the per-locale `strings.xml` files used by the translation tests
 * ([TranslationGlossaryTest], [TranslationLengthTest]). Runs with the project root as CWD,
 * like [TranslationCoverageDriftTest].
 */
internal object TranslationResources {

    /** The default (English) resource directory. */
    val defaultDir = File("res/values")

    /** Every translated locale directory (qualifier dirs such as `values-night` excluded). */
    val localeDirs: List<File> by lazy {
        File("res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .filter { it.name != "values-night" && it.name != "values-v29" }
            .sortedBy { it.name }
    }

    /** `values-zh-rCN` -> `zh-rCN`. */
    fun localeOf(dir: File): String = dir.name.removePrefix("values-")

    /**
     * Name -> flattened text of every `<string>` in [dir]/strings.xml. `<plurals>` and
     * `<string-array>` are skipped: none of the translation checks key on them, and their
     * flattened text would mix quantity items. Text is returned as stored (Android escapes
     * such as `\'` intact); callers that measure or match text use [unescape].
     */
    fun strings(dir: File): Map<String, String> = cache.getOrPut(dir.path) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = factory.newDocumentBuilder().parse(File(dir, "strings.xml"))
        val out = LinkedHashMap<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName != "string") continue
            val name = node.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            out[name] = node.textContent.orEmpty()
        }
        out
    }

    private val cache = HashMap<String, Map<String, String>>()

    /** Resolves the Android string-resource escapes that affect visible text. */
    fun unescape(text: String): String = text
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\t", " ")
        .trim()
}
