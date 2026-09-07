package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * H-9 (2026-09-06 comprehensive audit): every clickable action control in the clipboard
 * entry row is a bare `View` styled `@style/clipboardEntryButton` with an icon background —
 * TalkBack focuses each (they're clickable) and announces NOTHING, so the whole row's
 * action set (including the media delete affordance added in d3cd8dc6) does not exist for
 * screen-reader users.
 *
 * This drift test pins the layout contract:
 *   1. every `clipboardEntryButton`-styled element carries android:contentDescription;
 *   2. each referenced @string exists in the base strings.xml AND in every locale file
 *      (the project's 22-locale rule for user-visible strings).
 *
 * Pure JVM — parses the layout/resources straight off disk. Runs with the project root as
 * CWD (the TestRunnerListDriftTest convention).
 */
class ClipboardEntryButtonA11yDriftTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element {
        check(file.exists()) { "${file.path} not found — drift test must run with project root as CWD." }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun allElements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            out.add(e)
            val children = e.childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.let { walk(it) }
            }
        }
        walk(root)
        return out
    }

    @Test
    fun everyClipboardEntryActionButtonHasALocalizedContentDescription() {
        val root = parse(File("res/layout/clipboard_history_entry.xml"))
        val buttons = allElements(root).filter {
            it.getAttribute("style") == "@style/clipboardEntryButton"
        }
        assertWithMessage("expected the entry row's action buttons to be present")
            .that(buttons).isNotEmpty()

        val localeFiles = File("res").listFiles { f ->
            f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) &&
                File(f, "strings.xml").exists()
        }!!.map { File(it, "strings.xml").readText() }
        assertWithMessage("expected the full 22-locale strings set").that(localeFiles.size).isEqualTo(22)

        val problems = mutableListOf<String>()
        for (button in buttons) {
            val id = button.getAttributeNS(androidNs, "id").removePrefix("@+id/")
            val descr = button.getAttributeNS(androidNs, "contentDescription")
            if (descr.isEmpty()) {
                problems.add("$id: no android:contentDescription — TalkBack announces nothing")
                continue
            }
            if (!descr.startsWith("@string/")) {
                problems.add("$id: contentDescription must be a @string resource (got '$descr')")
                continue
            }
            val name = descr.removePrefix("@string/")
            val missingIn = localeFiles.count { !it.contains("name=\"$name\"") }
            if (missingIn > 0) {
                problems.add("$id: @string/$name missing from $missingIn locale strings.xml file(s)")
            }
        }
        assertWithMessage(
            "clipboard entry action buttons must be labeled for TalkBack (H-9):\n" +
                problems.joinToString("\n")
        ).that(problems).isEmpty()
    }
}
