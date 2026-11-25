package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The phone pad (`pin.xml`, Input Behavior → Pin Entry Layout, on by default) is what a phone,
 * number or date field opens; the 123+ pane (`numeric.xml`) is a different layout.
 *
 * Maintainer 2026-11-24: make it "have an ABC key instead of * (with * as a subkey for it)". Before,
 * the bottom-left key typed `*` and ABC was only its NW corner, so getting back to the letters took a
 * corner swipe. [NumpadKeySizeTest] pins the pad's shape (four keys a row, no gap).
 *
 * Project root as CWD (same convention as [BackspaceWordDeleteLayoutTest]).
 */
class PinLayoutKeysTest {

    private val pin = File("src/main/layouts/pin.xml")

    /** Attributes of every `<key>`, row by row. */
    private fun rows(): List<List<Map<String, String>>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pin)
        val rows = doc.getElementsByTagName("row")
        return (0 until rows.length).map { r ->
            val keys = (rows.item(r) as Element).getElementsByTagName("key")
            (0 until keys.length).map { k ->
                val attrs = (keys.item(k) as Element).attributes
                (0 until attrs.length).associate { attrs.item(it).nodeName to attrs.item(it).nodeValue }
            }
        }
    }

    /** `key1`…`key8`: what a short swipe or the subkey popover reaches. */
    private fun subkeys(key: Map<String, String>): List<String> =
        key.filterKeys { it.startsWith("key") && it != "key0" }.values.toList()

    @Test
    fun theBottomLeftKeyIsAbc_withStarAndTheNumberPaneAsSubkeys() {
        val bottomLeft = rows().last().first()

        assertWithMessage("a tap returns to the letters").that(bottomLeft["key0"]).isEqualTo("switch_text")
        assertThat(subkeys(bottomLeft)).containsAtLeast("*", "switch_numeric")
    }

    /** Every character a dialer needs stays on the pad: the digits, `*`, `#` and `+`. */
    @Test
    fun everyDialCharacterIsStillOnThePad() {
        val keys = rows().flatten()
        val reachable = keys.flatMap { listOfNotNull(it["key0"]) + subkeys(it) }
            .map { if (it == "\\#") "#" else it }

        for (c in (0..9).map(Int::toString) + listOf("*", "#", "+")) {
            assertWithMessage("'$c' on the phone pad").that(reachable).contains(c)
        }
        assertWithMessage("# keeps its own key").that(keys.map { it["key0"] }).contains("\\#")
    }

    @Test
    fun abcAppearsOnce() {
        val all = rows().flatten().flatMap { listOfNotNull(it["key0"]) + subkeys(it) }
        assertThat(all.count { it == "switch_text" }).isEqualTo(1)
    }
}
