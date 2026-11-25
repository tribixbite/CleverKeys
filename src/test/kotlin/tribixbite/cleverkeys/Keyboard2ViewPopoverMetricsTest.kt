package tribixbite.cleverkeys

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import org.xmlpull.v1.XmlPullParser
import tribixbite.cleverkeys.popover.SubkeyPopoverGeometry
import java.io.StringReader

/**
 * The subkey popover is clamped inside the keyboard view, and the view reaches under the
 * navigation bar (its bottom inset, `_insets_bottom`). A bottom-row key's grid therefore drew its
 * lowest cells behind the system's navigation buttons (Saga, release r6, 2026-11-24: holding ABC on
 * the phone pad put the 123+ cell under Back). The popover's area ends where the navigation bar
 * begins.
 *
 * Harness: the [Keyboard2ViewCustomSwipeDispatchTest] idiom, a real [Keyboard2View] allocated
 * without its constructor and spied for its size; the layout through production parsing.
 */
class Keyboard2ViewPopoverMetricsTest {

    private val objenesis = ObjenesisStd()

    private companion object {
        const val VIEW_W = 1080
        const val VIEW_H = 900
        const val NAV_BAR = 126
        const val ROW_H = 150f
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic(Log::class)

    private fun parseLayout(xml: String): KeyboardData {
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val method = KeyboardData.Companion::class.java.getDeclaredMethod(
            "parse_keyboard", XmlPullParser::class.java
        ).apply { isAccessible = true }
        return method.invoke(KeyboardData.Companion, parser) as KeyboardData
    }

    @Test
    fun aBottomRowKeysGrid_endsAboveTheNavigationBar() {
        val keyboard = parseLayout(
            "<keyboard bottom_row=\"false\"><row><key key0=\"1\"/></row>" +
                "<row><key key0=\"switch_text\" key1=\"*\"/></row></keyboard>"
        )
        val computed = objenesis.newInstance(Theme.Computed::class.java).apply { setField("row_height", ROW_H) }
        val view = spyk(objenesis.newInstance(Keyboard2View::class.java))
        every { view.width } returns VIEW_W
        every { view.height } returns VIEW_H
        view.setField("_keyboard", keyboard)
        view.setField("_tc", computed)
        view.setField("_keyWidth", 270f)
        view.setField("_insets_bottom", NAV_BAR)

        val abc = keyboard.rows.last().keys.first()
        val metrics = requireNotNull(view.subkeyPopoverMetrics(abc))
        // The finger rests on the bottom row, just above the navigation bar.
        val centreY = SubkeyPopoverGeometry.gridCentre(VIEW_H - NAV_BAR - ROW_H / 2, metrics.cellHeight, metrics.viewHeight)

        assertTrue(
            "the grid's bottom edge (${centreY + 1.5f * metrics.cellHeight}) must not pass the " +
                "navigation bar's top (${VIEW_H - NAV_BAR})",
            centreY + 1.5f * metrics.cellHeight <= VIEW_H - NAV_BAR,
        )
    }

    private fun Any.setField(name: String, value: Any?) {
        var cls: Class<*>? = javaClass
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            cls = cls.superclass
        }
        throw AssertionError("field $name not found on ${javaClass.name}")
    }
}
