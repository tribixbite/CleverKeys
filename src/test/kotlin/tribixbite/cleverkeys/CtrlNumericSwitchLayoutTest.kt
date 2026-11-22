package tribixbite.cleverkeys

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

/**
 * Maintainer 2026-11-22: "for any letter layout, shouldnt the '123+' numpad toggle display for the
 * North subkey of Ctrl instead of 'ABC'?"
 *
 * The shared bottom row had `switch_back_clipboard` ("ABC") on Ctrl's swipe-up edge and
 * `switch_numeric` ("123+") on its SE corner. "ABC" only closes an open clipboard, emoji or GIF
 * pane: with none open (the usual case on a letter layout) it does nothing, and every pane already
 * closes with its own close button or by its opener key again. So swipe up on Ctrl now opens the
 * number pane, on the shared row and on the layouts that carry their own bottom row (Persian PC,
 * Kurdish-Persian, Neo 2), and no letter layout's Ctrl key shows "ABC".
 *
 * Mock tier: `android.util.Xml` is a stub off-device, so kxml2 is substituted and every file goes
 * through the real production parser ([KeyboardData.load_string_exn]), which maps both `key7=` and
 * `n=` to slot 7.
 */
class CtrlNumericSwitchLayoutTest {

    private companion object {
        val LAYOUT_DIR = File("src/main/layouts")
        val SHARED_BOTTOM_ROW = File("res/xml/bottom_row.xml")

        /** Slot 7 is the swipe-up (North) edge: key1=nw, key2=ne, key3=sw, key4=se, … key7=n. */
        const val NORTH = 7
        val SLOTS = 0..8

        /** A layout that carries its own `<key …>` whose centre is Ctrl. */
        val OWN_CTRL_KEY = Regex("""\b(?:key0|c)="ctrl"""")
    }

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

    private fun key(name: String): KeyValue = KeyValue.getKeyByName(name)

    /** The shared bottom row, parsed by the same parser inside a one-row keyboard. */
    private fun sharedBottomRow(): KeyboardData.Row {
        val rowXml = SHARED_BOTTOM_ROW.readText().replace(Regex("""<\?xml[^>]*\?>"""), "")
        return KeyboardData.load_string_exn("<keyboard bottom_row=\"false\">$rowXml</keyboard>").rows.single()
    }

    private fun ctrlKeys(rows: List<KeyboardData.Row>): List<KeyboardData.Key> =
        rows.flatMap { it.keys }.filter { it.getKeyValue(0) == key("ctrl") }

    /** Every Ctrl key a letter board can show, labelled by where it comes from. */
    private fun everyCtrlKey(): List<Pair<String, KeyboardData.Key>> {
        val shared = ctrlKeys(listOf(sharedBottomRow())).map { "bottom_row.xml" to it }
        val own = LAYOUT_DIR.listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }
            .filter { OWN_CTRL_KEY.containsMatchIn(it.readText()) }
            .flatMap { f -> ctrlKeys(KeyboardData.load_string_exn(f.readText()).rows).map { f.name to it } }
        return shared + own
    }

    @Test
    fun swipeUpOnCtrlOpensTheNumberPaneOnEveryLetterLayout() {
        val ctrls = everyCtrlKey()
        // The shared row plus Persian PC, Kurdish-Persian and Neo 2.
        assertWithMessage("Ctrl keys found").that(ctrls.size).isAtLeast(4)
        for ((source, ctrl) in ctrls) {
            assertWithMessage("$source: Ctrl's swipe-up edge").that(ctrl.getKeyValue(NORTH))
                .isEqualTo(key("switch_numeric"))
            assertWithMessage("$source: 123+ is not gated by an extra-keys checkbox")
                .that(ctrl.keyHasFlag(NORTH, KeyboardData.Key.F_LOC)).isFalse()
            assertWithMessage("$source: 123+ appears once on Ctrl")
                .that(SLOTS.count { ctrl.getKeyValue(it) == key("switch_numeric") }).isEqualTo(1)
        }
    }

    @Test
    fun noLetterLayoutsCtrlKeyShowsAbc() {
        // Both draw "ABC"; on a letter board neither has anywhere to return to.
        val abc = setOf(key("switch_back_clipboard"), key("switch_text"))
        for ((source, ctrl) in everyCtrlKey()) {
            val shown = SLOTS.mapNotNull { ctrl.getKeyValue(it) }.filter { it in abc }
            assertWithMessage("$source: ABC keys on Ctrl").that(shown).isEmpty()
        }
    }

    @Test
    fun everyPaneStillClosesFromTheKeyThatOpenedIt() {
        // KeyboardReceiver: SWITCH_CLIPBOARD / SWITCH_EMOJI / SWITCH_GIF close their own pane when
        // it is open, and each pane has a close button, so dropping "ABC" loses no way out.
        val values = sharedBottomRow().keys.flatMap { k -> SLOTS.mapNotNull { k.getKeyValue(it) } }
        for (opener in listOf("switch_clipboard", "switch_emoji", "switch_gif")) {
            assertWithMessage("bottom row keeps $opener").that(values).contains(key(opener))
        }
    }
}
