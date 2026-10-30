package tribixbite.cleverkeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.IntentDefinition
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.customization.XmlLayoutExporter
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * Export XML → import on the device's own XML stack (maintainer, 2026-10-30): a built-in layout's
 * raw XML with one mapping of every action type written into q, read back by the production
 * parser (`KeyboardData.load_string_exn`, Android's XmlPullParser), has the same actions —
 * including quotes, a backslash, a line break and characters outside the BMP, which the old DOM
 * export garbled. The JVM twin is `XmlLayoutExporterRoundTripTest` (kxml2).
 */
@RunWith(AndroidJUnit4::class)
class XmlExportRoundTripInstrumentedTest {

    @Test
    fun everyActionTypeOnABuiltInLayoutReadsBackOnTheDevice() {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val xml = res.openRawResource(LayoutsPreference.layoutIdOfName(res, "latn_qwerty_us")).use { Utils.read_all_utf8(it) }
        val text = "It's \"C:\\work\\\" <&>\nline2 😀𝕨"
        val json = """{"name":"Send","targetType":"ACTIVITY","action":"android.intent.action.SEND","extras":{"t":"it's 😀"}}"""
        val mappings = listOf(
            ShortSwipeMapping("q", SwipeDirection.NW, "", ActionType.TEXT, text),
            ShortSwipeMapping("q", SwipeDirection.N, "{}", ActionType.TEMPLATE, "Dear {cursor},\n{selection}"),
            ShortSwipeMapping("q", SwipeDirection.NE, "TS", ActionType.TIMESTAMP, "yyyy-MM-dd 'at' HH:mm"),
            ShortSwipeMapping("q", SwipeDirection.W, "INT", ActionType.INTENT, json),
            ShortSwipeMapping("q", SwipeDirection.E, "Tab", ActionType.KEY_EVENT, "61"),
            ShortSwipeMapping("q", SwipeDirection.SW, "", ActionType.COMMAND, "copy"),
        )
        val out = XmlLayoutExporter.injectMappings(xml, mappings)
        assertTrue(out.notWritten.toString(), out.notWritten.isEmpty())
        val q = KeyboardData.load_string_exn(out.xml).rows[0].keys[0]
        fun at(d: SwipeDirection) = q.keys[d.subLabelIndex]!!

        assertEquals(text, at(SwipeDirection.NW).getMacro().single().getString())
        assertEquals("Dear {cursor},\n{selection}", at(SwipeDirection.N).getTemplateFormat().template)
        assertEquals("yyyy-MM-dd 'at' HH:mm", at(SwipeDirection.NE).getTimestampFormat().pattern)
        assertEquals(IntentDefinition.INTENT_PREFIX + json, at(SwipeDirection.W).getMacro().single().getString())
        assertEquals(KeyValue.Kind.Keyevent, at(SwipeDirection.E).getKind())
        assertEquals(61, at(SwipeDirection.E).getKeyevent())
        assertEquals(KeyValue.getKeyByName("copy"), at(SwipeDirection.SW))
        // q's other subkeys and the second key are untouched.
        val before = KeyboardData.load_string_exn(xml).rows[0]
        assertEquals(before.keys[0].keys[SwipeDirection.SE.subLabelIndex], q.keys[SwipeDirection.SE.subLabelIndex])
        assertEquals(before.keys[1], KeyboardData.load_string_exn(out.xml).rows[0].keys[1])
    }
}
