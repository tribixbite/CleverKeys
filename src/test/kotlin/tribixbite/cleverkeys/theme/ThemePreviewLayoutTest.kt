package tribixbite.cleverkeys.theme

import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.KeyboardData

/**
 * Which layout the Theme Creator / Theme Manager preview draws ([ThemeEditorPreview.previewLayout]).
 *
 * Saga 2026-10-27 (todo item 27): the preview drew the first non-System layout, so a layout that
 * styles itself (embedded `<theme>`, row / key colours, per-layout key gaps — the Harbour example)
 * painted its row colours over every theme being edited, and a user whose first layout is System
 * saw some other layout instead. The preview must show the theme alone: the user's first layout
 * only when it is a plain, unstyled layout, otherwise the neutral built-in QWERTY.
 *
 * Mock tier: the real layout parser needs `Xml.newPullParser` (kxml stands in for the stub).
 */
class ThemePreviewLayoutTest {

    private val harbour = File("docs/wiki/layouts/examples/harbour.xml").readText()

    private val plainXml = """<keyboard name="Plain" script="latin">
        <row><key c="a"/><key c="b"/></row>
    </keyboard>"""

    private val neutralXml = """<keyboard name="Neutral" script="latin">
        <row><key c="q"/><key c="w"/></row>
    </keyboard>"""

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
    }

    @After
    fun teardown() = unmockkAll()

    private fun choose(vararg layouts: KeyboardData?): String? {
        val neutral = KeyboardData.load_string_exn(neutralXml)
        return ThemeEditorPreview.previewLayout(layouts.toList()) { neutral }?.name
    }

    @Test
    fun aStyledLayoutNeverReachesTheThemePreview() {
        val styled = KeyboardData.load_string_exn(harbour)
        val plain = KeyboardData.load_string_exn(plainXml)
        assertWithMessage("System first, then a styled layout: the System layout is not resolvable here -> neutral")
            .that(choose(null, styled)).isEqualTo("Neutral")
        assertWithMessage("a styled first layout -> neutral (its row colours would bleed into the theme)")
            .that(choose(styled, plain)).isEqualTo("Neutral")
        assertWithMessage("a plain first layout is kept (the user's own script and arrangement)")
            .that(choose(plain, styled)).isEqualTo("Plain")
        assertWithMessage("no layouts -> neutral").that(choose()).isEqualTo("Neutral")
    }

    @Test
    fun stylingIsDetectedFromEveryStylingSource() {
        assertWithMessage("harbour: <theme>, row/key styles, key_* geometry")
            .that(KeyboardData.load_string_exn(harbour).carriesStyling()).isTrue()
        assertWithMessage("a plain layout").that(KeyboardData.load_string_exn(plainXml).carriesStyling()).isFalse()
        val rowOnly = """<keyboard name="Row" script="latin">
            <row key_background="#336699"><key c="a"/></row>
        </keyboard>"""
        assertWithMessage("a row colour alone").that(KeyboardData.load_string_exn(rowOnly).carriesStyling()).isTrue()
        val gapOnly = """<keyboard name="Gap" script="latin" key_horizontal_gap="3">
            <row><key c="a"/></row>
        </keyboard>"""
        assertWithMessage("a per-layout key gap alone").that(KeyboardData.load_string_exn(gapOnly).carriesStyling()).isTrue()
        val lookBinding = """<keyboard name="Bound" script="latin" theme="look_gboard">
            <row><key c="a"/></row>
        </keyboard>"""
        assertWithMessage("a theme= binding is not styling: the preview draws the edited theme anyway")
            .that(KeyboardData.load_string_exn(lookBinding).carriesStyling()).isFalse()
    }
}
