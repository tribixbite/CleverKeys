package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * The phone pad's (`pin.xml`) send key showed the word "Action" in every field (Saga, release r6,
 * 2026-11-24: a Chrome phone field whose letters layout showed "Next"). The key worked; its label
 * did not. `LayoutModifier.modify_pinentry` only mapped the digits to the layout's script, so the
 * layout's `action` placeholder never got the editor's label, and the key was written
 * `key0="action" key2="enter"` where every other Enter key is `key0="enter" key2="action"` (the
 * field's action takes Enter's place only when the editor asks for it, [EditorInfoHelper]).
 *
 * Now the pad goes through the same pane modifier as the 123+ pane (without its digit inversion):
 * the action key shows the editor's label, and Enter keeps the key where the field wants newlines
 * or names no action.
 *
 * Mock tier: production parsing with the functional pull parser (as [NumpadKeySizeTest]); Config is
 * Objenesis-allocated with only the fields the pane modifier reads (as [LayoutModifierCacheKeyTest]).
 */
class PinLayoutActionKeyTest {

    private val objenesis = ObjenesisStd()

    @After
    fun teardown() = setModifierConfig(null)

    private fun parseLayout(xml: String): KeyboardData {
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val method = KeyboardData.Companion::class.java.getDeclaredMethod(
            "parse_keyboard", XmlPullParser::class.java
        ).apply { isAccessible = true }
        return method.invoke(KeyboardData.Companion, parser) as KeyboardData
    }

    private val pin by lazy { parseLayout(File("src/main/layouts/pin.xml").readText()) }

    /** The letters layout the pad's digits follow (Latin: no digit mapping). */
    private val letters by lazy { parseLayout("<keyboard><row><key key0=\"a\"/></row></keyboard>") }

    /** Only the fields the pane modifier and `modify_key` read; everything else at JVM defaults. */
    private fun configWith(actionLabel: String?, swap: Boolean, inverseNumpad: Boolean = false): Config {
        val config = objenesis.newInstance(Config::class.java)
        config.layouts = emptyList()
        config.extra_keys_param = emptyMap()
        config.extra_keys_custom = emptyMap()
        config.actionLabel = actionLabel
        config.swapEnterActionKey = swap
        config.switch_input_immediate = false
        config.shouldOfferVoiceTyping = false
        config.inverse_numpad = inverseNumpad
        return config
    }

    private fun setModifierConfig(config: Config?) {
        val field = LayoutModifier::class.java.getDeclaredField("globalConfig")
        field.isAccessible = true
        field.set(LayoutModifier, config)
    }

    /** The pad as a field shows it: bottom-right key's centre and NE corner. */
    private fun sendKey(actionLabel: String?, swap: Boolean): Pair<KeyValue?, KeyValue?> {
        setModifierConfig(configWith(actionLabel, swap))
        val key = LayoutModifier.modify_pinentry(pin, letters).rows.last().keys.last()
        return key.keys[0] to key.keys[2]
    }

    /** A single-line field with an action (a phone number box: "Next", "Go"…) swaps it in. */
    @Test
    fun aFieldWithAnAction_showsItsLabelOnThePad() {
        val (centre, corner) = sendKey(actionLabel = "Next", swap = true)

        assertWithMessage("the pad's send key names the field's action").that(centre)
            .isEqualTo(KeyValue.makeActionKey("Next"))
        assertThat(corner).isEqualTo(KeyValue.getKeyByName("enter"))
    }

    /** A field that wants newlines (or opts out of the Enter action) keeps Enter, the action on its corner. */
    @Test
    fun aFieldThatKeepsEnter_hasTheActionOnTheCorner() {
        val (centre, corner) = sendKey(actionLabel = "Send", swap = false)

        assertThat(centre).isEqualTo(KeyValue.getKeyByName("enter"))
        assertThat(corner).isEqualTo(KeyValue.makeActionKey("Send"))
    }

    /** A field that names no action keeps a plain Enter, never the word "Action". */
    @Test
    fun aFieldWithNoAction_keepsAPlainEnter() {
        val (centre, corner) = sendKey(actionLabel = null, swap = false)

        assertThat(centre).isEqualTo(KeyValue.getKeyByName("enter"))
        assertThat(corner).isNull()
    }

    /** The pad's digits run 1-2-3 from the top like a phone's, even with Inverse Numpad on. */
    @Test
    fun thePadNeverInvertsItsDigits() {
        setModifierConfig(configWith(actionLabel = "Next", swap = true, inverseNumpad = true))
        val firstRow = LayoutModifier.modify_pinentry(pin, letters).rows.first().keys.map { it.keys[0] }

        assertThat(firstRow.take(3)).containsExactly(
            KeyValue.makeCharKey('1'), KeyValue.makeCharKey('2'), KeyValue.makeCharKey('3'),
        ).inOrder()
    }
}
