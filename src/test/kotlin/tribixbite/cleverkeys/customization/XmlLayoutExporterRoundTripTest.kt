package tribixbite.cleverkeys.customization

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * Export XML → import (maintainer, 2026-10-30): a layout file written by
 * [XmlLayoutExporter.injectMappings] and read back by the layout parser has, in every mapped
 * slot, the same action as the custom mapping it came from — for every action type, with quotes,
 * backslashes, line breaks and characters outside the BMP.
 *
 * Before: TEXT, INTENT, TEMPLATE, TIMESTAMP and KEY_EVENT were written in a form that imports as a
 * key typing its quoted source, and the DOM serializer wrote `𝕨` / emoji as numeric references
 * that kxml2 (Android's parser) reads back truncated to 16 bits.
 *
 * Mock tier: kxml2 stands in for android.util.Xml, as production parsing uses it on the device.
 */
class XmlLayoutExporterRoundTripTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<!-- A comment and a character outside the BMP that must survive: 𝕨 -->
<keyboard name="Round trip" script="latin">
  <row>
    <key key0="a"/>
    <key c="b" nw="1" key3="3"/>
    <key key0="&amp;" key1="!"/>
    <key key0="𝕨"/>
  </row>
  <row>
    <key key0="enter" key2="action"/>
  </row>
</keyboard>
"""

    private fun m(key: String, dir: SwipeDirection, type: ActionType, value: String, label: String = "") =
        ShortSwipeMapping(key, dir, label, type, value)

    private val intentJson = """{"name":"Send \"it's\"","targetType":"ACTIVITY","action":"android.intent.action.SEND",""" +
        """"type":"text/plain","extras":{"android.intent.extra.TEXT":"C:\\work\n😀 <&>"}}"""

    /** One mapping of every action type, each with characters the XML or the parser must escape. */
    private val everyType = listOf(
        m("a", SwipeDirection.NW, ActionType.TEXT, "It's \"C:\\work\\\" <&>\nline2\t😀𝕨"),
        m("a", SwipeDirection.N, ActionType.TEMPLATE, "Dear {cursor},\n— it's {selection}\\", "{}"),
        m("a", SwipeDirection.NE, ActionType.TIMESTAMP, "yyyy-MM-dd 'at' HH:mm", "TS"),
        m("a", SwipeDirection.W, ActionType.INTENT, intentJson, "INT"),
        m("a", SwipeDirection.E, ActionType.KEY_EVENT, "61", "Tab"),
        m("a", SwipeDirection.SW, ActionType.COMMAND, "copy"),
        m("a", SwipeDirection.S, ActionType.COMMAND, "SELECT_ALL"),
        m("a", SwipeDirection.SE, ActionType.TEXT, "😀"),
    )

    private fun slot(layout: KeyboardData, r: Int, c: Int, dir: SwipeDirection): KeyValue? =
        layout.rows[r].keys[c].keys.getOrNull(dir.subLabelIndex)

    @Test
    fun everyActionTypeImportsAsTheSameAction() {
        val out = XmlLayoutExporter.injectMappings(xml, everyType)
        assertThat(out.notWritten).isEmpty()
        val parsed = KeyboardData.load_string_exn(out.xml)
        for (mapping in everyType) {
            val value = slot(parsed, 0, 0, mapping.direction)
            assertWithMessage("${mapping.direction} ${mapping.actionType}: ${out.xml}").that(value).isNotNull()
            val expected = if (mapping.actionValue == "SELECT_ALL") ActionType.COMMAND to KeyValue.getKeyByName("selectAll")
            else XmlActionRoundTripFixtures.expectedActionOf(mapping)
            assertWithMessage("${mapping.direction} ${mapping.actionType}")
                .that(XmlActionRoundTripFixtures.actionOf(value!!)).isEqualTo(expected)
        }
    }

    @Test
    fun theRestOfTheFileIsUnchanged() {
        val out = XmlLayoutExporter.injectMappings(xml, listOf(m("b", SwipeDirection.NE, ActionType.TEXT, "x")))
        // Only the mapped key's start tag changed: comments, the declaration, other keys and
        // characters outside the BMP stay byte for byte.
        val changed = xml.replace("""<key c="b" nw="1" key3="3"/>""", """<key c="b" nw="1" key3="3" ne="x:'x'"/>""")
        assertThat(out.xml).isEqualTo(changed)
        val parsed = KeyboardData.load_string_exn(out.xml)
        assertThat(parsed.rows[0].keys[3].keys[0]?.getString()).isEqualTo("𝕨")
    }

    @Test
    fun aRemovalBlanksTheSlotInEitherSpelling() {
        val out = XmlLayoutExporter.injectMappings(xml, listOf(
            ShortSwipeMapping.removal("b", SwipeDirection.NW),
            ShortSwipeMapping.removal("&", SwipeDirection.NW),
        ))
        val parsed = KeyboardData.load_string_exn(out.xml)
        assertThat(slot(parsed, 0, 1, SwipeDirection.NW)).isNull()
        assertThat(slot(parsed, 0, 1, SwipeDirection.SW)).isEqualTo(KeyValue.getKeyByName("3"))
        assertThat(slot(parsed, 0, 2, SwipeDirection.NW)).isNull()
    }

    @Test
    fun anActionALayoutCannotHoldIsReportedAndTheSlotKept() {
        val pinned = m("b", SwipeDirection.NW, ActionType.COMMAND, "paste_pinned_1")
        val out = XmlLayoutExporter.injectMappings(xml, listOf(pinned, m("zz", SwipeDirection.N, ActionType.TEXT, "q")))
        // The key "zz" is not in this layout: nothing to write, nothing to report.
        assertThat(out.notWritten).containsExactly(pinned)
        assertThat(slot(KeyboardData.load_string_exn(out.xml), 0, 1, SwipeDirection.NW)).isEqualTo(KeyValue.getKeyByName("1"))
    }

    @Test
    fun keysAreMatchedByTheirRuntimeCode() {
        val enterCode = KeyValue.getKeyByName("enter").getString()
        val out = XmlLayoutExporter.injectMappings(xml, listOf(
            m(enterCode, SwipeDirection.NE, ActionType.TEXT, "sent"),
            m("A", SwipeDirection.NW, ActionType.TEXT, "upper-case code"),
            m("&", SwipeDirection.S, ActionType.TEXT, "amp"),
        ))
        val parsed = KeyboardData.load_string_exn(out.xml)
        assertThat(XmlActionRoundTripFixtures.actionOf(slot(parsed, 1, 0, SwipeDirection.NE)!!)).isEqualTo(ActionType.TEXT to "sent")
        assertThat(XmlActionRoundTripFixtures.actionOf(slot(parsed, 0, 0, SwipeDirection.NW)!!)).isEqualTo(ActionType.TEXT to "upper-case code")
        assertThat(XmlActionRoundTripFixtures.actionOf(slot(parsed, 0, 2, SwipeDirection.S)!!)).isEqualTo(ActionType.TEXT to "amp")
    }

    @Test
    fun noMappingsReturnsTheFileUnchanged() {
        assertThat(XmlLayoutExporter.injectMappings(xml, emptyList()).xml).isEqualTo(xml)
    }
}
