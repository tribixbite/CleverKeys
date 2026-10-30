package tribixbite.cleverkeys.customization

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
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
 * Export XML (`XmlLayoutExporter.injectMappings`, found while building modified layout copies on
 * 2026-10-30): a key written with the NUMBERED attribute (`key1="1"`, as the built-in layouts
 * are) that received a mapping for the same direction carried both `key1` and `nw`, which the
 * layout parser rejects — the exported file could not be imported. And a mapping on Enter,
 * stored under Enter's runtime code, never matched `key0="enter"`.
 *
 * Mock tier: kxml2 stands in for android.util.Xml to read the exported XML back.
 */
class XmlLayoutExporterSlotTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private val xml = """<keyboard name="T"><row><key key0="q" key1="1"/><key key0="enter" key2="action"/></row></keyboard>"""

    @Test
    fun aMappedSlotWrittenAsKeyNIsReplacedNotDuplicated() {
        val out = XmlLayoutExporter.injectMappings(
            xml, listOf(ShortSwipeMapping("q", SwipeDirection.NW, "c", ActionType.COMMAND, "copy"))
        )
        val parsed = KeyboardData.load_string_exn(out)
        assertThat(parsed.rows[0].keys[0].keys[SwipeDirection.NW.subLabelIndex]).isEqualTo(KeyValue.getKeyByName("copy"))
    }

    @Test
    fun aMappingOnEnterIsExportedOntoTheEnterKey() {
        val enterCode = KeyValue.getKeyByName("enter").getString()
        val out = XmlLayoutExporter.injectMappings(
            xml, listOf(ShortSwipeMapping(enterCode, SwipeDirection.NE, "", ActionType.COMMAND, "voice_input", true))
        )
        val parsed = KeyboardData.load_string_exn(out)
        assertThat(parsed.rows[0].keys[1].keys[SwipeDirection.NE.subLabelIndex]).isEqualTo(KeyValue.getKeyByName("voice_input"))
    }
}
