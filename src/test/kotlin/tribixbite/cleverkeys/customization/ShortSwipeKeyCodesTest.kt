package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.R

/**
 * Bug A of 2026-10-30 (maintainer screenshots 9/10): Short Swipe Customization captured Enter as
 * the TEXT it types ("\n"), titled the dialog `Customize "` + newline + `" Key`, and stored the
 * mapping under "\n" — a code the keyboard never looks up (it uses the key's glyph, U+E00E), so
 * the mapping never fired or drew. [ShortSwipeKeyCodes] maps typed text to the runtime code and
 * names keys without control or private-use characters.
 */
class ShortSwipeKeyCodesTest {

    /** A resolver that returns a recognisable token per resource id. */
    private val getString: (Int) -> String = { id -> "#$id" }

    private fun glyph(name: String) = KeyValue.getKeyByName(name).getString()

    @Test
    fun typedControlTextMapsToTheKeyThatTypesIt() {
        assertThat(ShortSwipeKeyCodes.canonical("\n")).isEqualTo(glyph("enter"))
        assertThat(ShortSwipeKeyCodes.canonical(" ")).isEqualTo(glyph("space"))
        assertThat(ShortSwipeKeyCodes.canonical("\t")).isEqualTo(glyph("tab"))
    }

    @Test
    fun printableCodesAreLowercasedOnly() {
        assertThat(ShortSwipeKeyCodes.canonical("A")).isEqualTo("a")
        assertThat(ShortSwipeKeyCodes.canonical("é")).isEqualTo("é")
        assertThat(ShortSwipeKeyCodes.canonical("ABC")).isEqualTo("abc")
    }

    @Test
    fun aKeysCodeIsItsMainValueString() {
        assertThat(ShortSwipeKeyCodes.ofKey(KeyValue.getKeyByName("enter"))).isEqualTo(glyph("enter"))
        assertThat(ShortSwipeKeyCodes.ofKey(KeyValue.makeCharKey('q'))).isEqualTo("q")
        assertThat(ShortSwipeKeyCodes.ofKey(null)).isNull()
    }

    @Test
    fun enterIsNamedEnterWhetherCapturedAsTextOrStoredAsItsGlyph() {
        val enter = "#${R.string.key_descr_enter}"
        assertThat(ShortSwipeKeyCodes.displayName("\n", null, getString)).isEqualTo(enter)
        assertThat(ShortSwipeKeyCodes.displayName(glyph("enter"), null, getString)).isEqualTo(enter)
        assertThat(ShortSwipeKeyCodes.displayName(" ", null, getString)).isEqualTo("#${R.string.key_descr_space}")
    }

    @Test
    fun aLetterIsNamedByItsCap() {
        assertThat(ShortSwipeKeyCodes.displayName("a", null, getString)).isEqualTo("A")
        assertThat(ShortSwipeKeyCodes.displayName("a", KeyValue.makeCharKey('a'), getString)).isEqualTo("A")
    }

    @Test
    fun noKeyNameEverContainsAControlOrPrivateUseCharacter() {
        val codes = CommandRegistry.ALL_COMMANDS.mapNotNull { KeyValue.getSpecialKeyByName(it.name)?.getString() } +
            listOf("\n", "\r", "\t", " ", "\u0000", "", "")
        for (code in codes) {
            val name = ShortSwipeKeyCodes.displayName(code, null, getString)
            val label = "name of U+%04X".format(code.firstOrNull()?.code ?: 0)
            assertWithMessage(label).that(name).isNotEmpty()
            assertWithMessage("$label = $name")
                .that(name.any { Character.isISOControl(it) || it.code in 0xE000..0xF8FF }).isFalse()
        }
    }

    @Test
    fun aMappingStoredUnderTypedTextUsesTheRuntimeStorageKey() {
        val m = ShortSwipeMapping("\n", SwipeDirection.NW, "x", ActionType.COMMAND, "voice_input")
        assertThat(m.toStorageKey()).isEqualTo("${glyph("enter")}:NW")
        assertThat(m.withCanonicalKeyCode().keyCode).isEqualTo(glyph("enter"))
    }

    @Test
    fun savedTypedTextCodesLoadUnderTheRuntimeCode() {
        val saved = ShortSwipeCustomizations(
            mappings = mapOf(
                "\n" to mapOf("NW" to DirectionMapping("", "COMMAND", "voice_input", true)),
                "A" to mapOf("SE" to DirectionMapping("@", "TEXT", "@")),
            )
        )
        val codes = saved.toMappingList().map { it.keyCode }.toSet()
        assertThat(codes).containsExactly(glyph("enter"), "a")
    }
}
