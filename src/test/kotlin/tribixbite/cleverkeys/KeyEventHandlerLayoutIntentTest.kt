package tribixbite.cleverkeys

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.IntentDefinition
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.customization.XmlAttributeMapper

/**
 * A layout key written as `label:intent:'json'` (Export XML of an INTENT short swipe, 2026-10-30)
 * is parsed into a string key carrying [IntentDefinition.INTENT_PREFIX]. Before, nothing ran it:
 * pressing the key TYPED `__intent__:{…}` into the app. Now KeyEventHandler starts the intent, the
 * same way the custom short swipe it was exported from did, and types nothing.
 */
class KeyEventHandlerLayoutIntentTest {

    private lateinit var recv: KeyEventHandler.IReceiver
    private lateinit var conn: InputConnection
    private lateinit var handler: KeyEventHandler
    private val ran = mutableListOf<String>()

    private val json = """{"name":"Share","targetType":"ACTIVITY","action":"android.intent.action.SEND","extras":{"k":"it's"}}"""

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        conn = mockk(relaxed = true)
        recv = mockk(relaxed = true)
        every { recv.getHandler() } returns mockk<Handler>(relaxed = true)
        every { recv.getCurrentInputConnection() } returns conn
        every { recv.getContext() } returns mockk<Context>(relaxed = true)
        handler = KeyEventHandler(recv)
        handler.layoutIntentRunner = { _, payload -> ran += payload; true }
    }

    @After
    fun teardown() = unmockkStatic(Log::class)

    @Test
    fun anIntentKeyStartsTheIntentAndTypesNothing() {
        handler.key_up(KeyValue.makeStringKey(IntentDefinition.INTENT_PREFIX + json), Pointers.Modifiers.EMPTY, false)
        assertThat(ran).containsExactly(json)
        verify(exactly = 0) { conn.commitText(any(), any()) }
    }

    @Test
    fun anExportedIntentSubkeyStartsTheSameIntent() {
        // The exported value, parsed exactly as a layout file's subkey attribute is.
        val exported = XmlAttributeMapper.layoutValue(
            ShortSwipeMapping("a", SwipeDirection.NE, "INT", ActionType.INTENT, json)
        )!!
        handler.key_up(KeyValueParser.parse(exported), Pointers.Modifiers.EMPTY, false)
        assertThat(ran).containsExactly(json)
        verify(exactly = 0) { conn.commitText(any(), any()) }
    }
}
