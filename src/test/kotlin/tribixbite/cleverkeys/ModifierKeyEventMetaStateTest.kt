package tribixbite.cleverkeys

import android.util.Log
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.lang.reflect.Field
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * The key events that tell the editor a modifier went down or up carry the meta state a hardware
 * keyboard gives them: a modifier's DOWN includes its own bit, its UP no longer does.
 *
 * Maintainer 2026-11-23, RustDesk: after Shift or Ctrl was used once, the remote machine saw it
 * held for all later input. The UP for KEYCODE_CTRL_LEFT went out with META_CTRL_ON still set.
 * Flutter (RustDesk's UI) keeps its modifier state in step with each event's meta state: an UP of
 * Left-Ctrl whose meta state still says "Ctrl" made it synthesise a press of Right-Ctrl, which
 * nothing released while the typing arrived as text (Flutter `KeyEmbedderResponder`
 * synchronizePressingKey; Flutter added a virtual-keyboard exception on 2026-04-29, #185292,
 * after RustDesk's builds). A physical keyboard clears the bit on the UP; so do we now.
 *
 * The modifier's own events are not edits, so auto-capitalisation is not told about them: with
 * the bit cleared, the Shift key's UP would otherwise read as typing and latch Shift again.
 *
 * Mock tier: [KeyEventHandler] allocated without its constructor, the private `postKeyEvent`
 * (the one place a KeyEvent is built and sent) replaced by a recorder (android.jar's KeyEvent
 * constructor is a stub).
 */
class ModifierKeyEventMetaStateTest {

    private data class Sent(val action: Int, val code: Int, val meta: Int)

    private val sent = mutableListOf<Sent>()
    private lateinit var handler: KeyEventHandler
    private val autocap = mockk<Autocapitalisation>(relaxed = true)

    private val shift = KeyValue.getKeyByName("shift")
    private val ctrl = KeyValue.getKeyByName("ctrl")
    private val alt = KeyValue.getKeyByName("alt")
    private val meta = KeyValue.getKeyByName("meta")

    private fun mods(vararg held: KeyValue): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(*held), held.size)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        handler = spyk(ObjenesisStd().newInstance(KeyEventHandler::class.java).apply {
            setField("mods", Pointers.Modifiers.EMPTY)
            setField("autocap", autocap)
        })
        every { handler["postKeyEvent"](any<Int>(), any<Int>(), any<Int>()) } answers {
            sent += Sent(arg(0), arg(1), arg(2))
            true
        }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun up(code: Int) = sent.single { it.action == KeyEvent.ACTION_UP && it.code == code }
    private fun down(code: Int) = sent.single { it.action == KeyEvent.ACTION_DOWN && it.code == code }

    @Test
    fun ctrlsUpNoLongerSaysCtrlIsHeld() {
        handler.mods_changed(mods(ctrl))
        handler.mods_changed(Pointers.Modifiers.EMPTY)

        assertWithMessage("Ctrl's DOWN carries Ctrl")
            .that(down(KeyEvent.KEYCODE_CTRL_LEFT).meta and KeyEvent.META_CTRL_ON).isEqualTo(KeyEvent.META_CTRL_ON)
        assertWithMessage("Ctrl's UP must not: a Flutter app then presses Right-Ctrl for good")
            .that(up(KeyEvent.KEYCODE_CTRL_LEFT).meta and CTRL_BITS).isEqualTo(0)
    }

    @Test
    fun shiftAltAndMetaGoUpTheSameWay() {
        for ((kv, code, bits) in listOf(
            Triple(shift, KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT_BITS),
            Triple(alt, KeyEvent.KEYCODE_ALT_LEFT, ALT_BITS),
            Triple(meta, KeyEvent.KEYCODE_META_LEFT, META_BITS),
        )) {
            sent.clear()
            handler.mods_changed(mods(kv))
            handler.mods_changed(Pointers.Modifiers.EMPTY)
            assertWithMessage("${kv.getString()} DOWN").that(down(code).meta and bits).isNotEqualTo(0)
            assertWithMessage("${kv.getString()} UP").that(up(code).meta and bits).isEqualTo(0)
        }
    }

    @Test
    fun releasingCtrlWhileShiftStaysKeepsShiftInTheUp() {
        handler.mods_changed(mods(shift, ctrl))
        handler.mods_changed(mods(shift))

        val ctrlUp = up(KeyEvent.KEYCODE_CTRL_LEFT)
        assertThat(ctrlUp.meta and CTRL_BITS).isEqualTo(0)
        assertWithMessage("Shift is still held").that(ctrlUp.meta and KeyEvent.META_SHIFT_ON).isEqualTo(KeyEvent.META_SHIFT_ON)
    }

    @Test
    fun aLetterTypedWithCtrlHeldStillCarriesCtrl() {
        handler.mods_changed(mods(ctrl))
        handler.send_key_down_up(KeyEvent.KEYCODE_A)
        val a = sent.filter { it.code == KeyEvent.KEYCODE_A }
        assertThat(a).hasSize(2)
        assertThat(a.all { (it.meta and KeyEvent.META_CTRL_ON) != 0 }).isTrue()
    }

    @Test
    fun autoCapitalisationHearsTheLetterButNotTheModifier() {
        handler.mods_changed(mods(shift))
        handler.send_key_down_up(KeyEvent.KEYCODE_A)
        handler.mods_changed(Pointers.Modifiers.EMPTY)
        verify(exactly = 1) { autocap.event_sent(KeyEvent.KEYCODE_A, any()) }
        verify(exactly = 0) { autocap.event_sent(KeyEvent.KEYCODE_SHIFT_LEFT, any()) }
    }

    private fun Any.setField(name: String, value: Any?) {
        var type: Class<*>? = javaClass
        var field: Field? = null
        while (type != null && field == null) {
            field = type.declaredFields.firstOrNull { it.name == name }
            type = type.superclass
        }
        requireNotNull(field) { "no field $name on ${javaClass.name}" }.apply { isAccessible = true }.set(this, value)
    }

    private companion object {
        const val CTRL_BITS = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT_BITS = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        const val ALT_BITS = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        const val META_BITS = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    }
}
