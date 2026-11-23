package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileInputStream
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * With Ctrl (or Alt, Meta) and Shift or caps lock both on, the letter keys read as capitals.
 * Maintainer 2026-11-23: they read lowercase. [KeyModifier] applies the modifiers highest enum
 * first, so Ctrl turns `a` into the key event KEYCODE_A (label "a") before Shift runs, and Shift
 * left key events alone. That order is needed (Shift first would make `A`, which has no key
 * code, and Ctrl+Shift+A would type a capital A), so Shift now capitalises the LABEL of such a
 * key event and keeps its key code: the app still receives KEYCODE_A with Ctrl and Shift held.
 *
 * Mock tier, like [ReleaseClaimKeyModifierTest]: android.jar's KeyEvent constants, and Shift on a
 * plain letter runs the real compose tables, loaded as [DeadKeyMarksBelowTest] does.
 */
class CtrlShiftLabelTest {

    private val shift = KeyValue.getKeyByName("shift")
    private val ctrl = KeyValue.getKeyByName("ctrl")
    private val alt = KeyValue.getKeyByName("alt")
    private val meta = KeyValue.getKeyByName("meta")

    private fun mods(vararg held: KeyValue): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(*held), held.size)

    @Before
    fun setUp() {
        KeyModifier.set_modmap(null)
        val bin = File("src/main/assets/compose_data.bin")
        assertWithMessage("run generateComposeData first: ${bin.absolutePath}").that(bin.exists()).isTrue()
        val assets = mockk<AssetManager>()
        every { assets.open("compose_data.bin") } answers { FileInputStream(bin) }
        val context = mockk<Context>()
        every { context.assets } returns assets
        ComposeKeyData.initialize(context) // idempotent
    }

    @After
    fun tearDown() = KeyModifier.set_modmap(null)

    @Test
    fun ctrlWithShiftShowsCapitalsAndStillSendsTheLettersKeyCode() {
        for (c in 'a'..'z') {
            val r = KeyModifier.modify(KeyValue.makeCharKey(c), mods(shift, ctrl))!!
            assertWithMessage("Ctrl+Shift on '$c': the label the key draws")
                .that(r.getString()).isEqualTo(c.uppercase())
            assertWithMessage("Ctrl+Shift on '$c' is still a key event (the app gets a shortcut)")
                .that(r.getKind()).isEqualTo(KeyValue.Kind.Keyevent)
            assertWithMessage("Ctrl+Shift on '$c': the key code")
                .that(r.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A + (c - 'a'))
        }
    }

    @Test
    fun theOrderTheyWereLatchedInDoesNotMatter() {
        val a = KeyValue.makeCharKey('a')
        assertThat(KeyModifier.modify(a, mods(ctrl, shift))!!.getString()).isEqualTo("A")
        assertThat(KeyModifier.modify(a, mods(shift, ctrl))!!.getString()).isEqualTo("A")
    }

    @Test
    fun altAndMetaWithShiftShowCapitalsToo() {
        for (m in listOf(alt, meta)) {
            val r = KeyModifier.modify(KeyValue.makeCharKey('q'), mods(shift, m))!!
            assertWithMessage("${m.getString()}+Shift on 'q'").that(r.getString()).isEqualTo("Q")
            assertThat(r.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_Q)
        }
    }

    // ------------------------------------------------------------------ unchanged

    @Test
    fun ctrlAloneStaysLowercase() {
        val r = KeyModifier.modify(KeyValue.makeCharKey('a'), mods(ctrl))!!
        assertThat(r.getString()).isEqualTo("a")
        assertThat(r.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
    }

    @Test
    fun digitsAndPunctuationKeepTheirLabelsAndKeyCodes() {
        val one = KeyModifier.modify(KeyValue.makeCharKey('1'), mods(shift, ctrl))!!
        assertThat(one.getString()).isEqualTo("1")
        assertThat(one.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_1)
        val comma = KeyModifier.modify(KeyValue.makeCharKey(','), mods(shift, ctrl))!!
        assertThat(comma.getString()).isEqualTo(",")
        assertThat(comma.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_COMMA)
    }

    @Test
    fun keyEventKeysThatAreNotLettersKeepTheirLabels() {
        for (name in listOf("esc", "tab", "left", "home")) {
            val k = KeyValue.getKeyByName(name)
            val r = KeyModifier.modify(k, mods(shift, ctrl))!!
            assertWithMessage(name).that(r.getString()).isEqualTo(k.getString())
            assertWithMessage(name).that(r.getKeyevent()).isEqualTo(k.getKeyevent())
        }
    }

    @Test
    fun shiftAloneStillMakesCapitalCharacters() {
        val r = KeyModifier.modify(KeyValue.makeCharKey('a'), mods(shift))!!
        assertThat(r.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(r.getChar()).isEqualTo('A')
    }
}
