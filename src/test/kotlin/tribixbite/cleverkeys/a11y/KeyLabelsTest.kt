package tribixbite.cleverkeys.a11y

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue

/**
 * Pure-JVM tests for [KeyLabels.describe]. The resolver is injected as a fake
 * `(Int)->String` so we never touch Android `Resources`; we assert on the
 * SHAPE of the output (non-empty, not-a-PUA-glyph, correct kind mapping) rather
 * than exact localized strings.
 *
 * The fake resolver returns a stable sentinel `"str#<id>"` per string id. Two
 * keys mapping to the same sentinel means they resolved to the same R.string —
 * distinct keys with distinct meanings must NOT collide.
 */
class KeyLabelsTest {

    // Fake resolver: id -> deterministic non-empty sentinel. Never PUA/empty.
    private val res: (Int) -> String = { id -> "str#$id" }

    private fun describe(kv: KeyValue) = KeyLabels.describe(kv, res)

    /** True if any char in [s] is a Private Use Area codepoint. */
    private fun hasPua(s: String): Boolean = s.any { it.code in 0xE000..0xF8FF }

    // The special keys present on the default layout(s), built via the pure
    // KeyValue.getKeyByName factory (no Android runtime needed).
    private val defaultSpecialKeys: List<Pair<String, KeyValue>> = listOf(
        "shift", "ctrl", "alt", "meta", "fn",
        "backspace", "enter", "esc", "tab", "space",
        "compose", "capslock", "config",
        "up", "down", "left", "right",
        "home", "end", "page_up", "page_down",
        "switch_text", "switch_numeric", "switch_emoji", "switch_gif",
        "switch_clipboard", "switch_back_clipboard", "switch_greekmath",
        "switch_forward", "switch_backward", "change_method", "voice_typing",
        "cursor_left", "cursor_right",
        "copy", "paste", "cut", "selectAll", "undo", "redo",
        "nbsp", "nnbsp",
    ).map { it to KeyValue.getKeyByName(it) }

    // ── coverage: every default special key is speakable ──────────────────────

    @Test
    fun everyDefaultSpecialKeyIsNonEmptyAndNonPua() {
        for ((name, kv) in defaultSpecialKeys) {
            val label = describe(kv)
            assertThat(label).isNotEmpty()
            // Fail loudly with the offending key if a PUA glyph leaks through.
            check(!hasPua(label)) {
                "key '$name' (kind=${kv.getKind()}) label='$label' must not contain a PUA glyph"
            }
        }
    }

    // ── the shift PUA glyph must never leak through ───────────────────────────

    @Test
    fun shiftIsNotDescribedAsItsGlyph() {
        val shift = KeyValue.getKeyByName("shift")
        // The shift key renders as a PUA glyph (0xE00A). describe() must resolve
        // it to a real word, never echo the glyph.
        val glyph = shift.getString()
        assertThat(hasPua(glyph)).isTrue()          // sanity: it really is PUA
        val label = describe(shift)
        assertThat(label).isNotEqualTo(glyph)
        assertThat(hasPua(label)).isFalse()
    }

    @Test
    fun fontKeyWithNoMappingNeverEchoesPuaGlyph() {
        // A dead-key accent: FLAG_KEY_FONT, PUA glyph, and (for the accents we
        // don't special-case) falls through the modifier branch to "dead key".
        val accent = KeyValue.getKeyByName("accent_aigu")
        assertThat(hasPua(accent.getString())).isTrue()
        val label = describe(accent)
        assertThat(label).isNotEmpty()
        assertThat(hasPua(label)).isFalse()
    }

    // ── char path (bypasses the resolver) ─────────────────────────────────────

    @Test
    fun plainLetterAnnouncedAsItself() {
        assertThat(describe(KeyValue.makeCharKey('a'))).isEqualTo("a")
        assertThat(describe(KeyValue.makeCharKey('z'))).isEqualTo("z")
    }

    @Test
    fun shiftedLetterAnnouncedAsUppercase() {
        // Mirrors the live wiring: describe(modifyKey(kv, mods)) where a latched
        // Shift has already transformed 'a' -> 'A'. describe of the uppercased
        // key must speak "A".
        assertThat(describe(KeyValue.makeCharKey('A'))).isEqualTo("A")
    }

    @Test
    fun digitAnnouncedAsItself() {
        assertThat(describe(KeyValue.makeCharKey('7'))).isEqualTo("7")
    }

    // ── distinct meanings resolve to distinct strings ─────────────────────────

    @Test
    fun distinctModifiersDoNotCollapseToSameString() {
        val shift = describe(KeyValue.getKeyByName("shift"))
        val ctrl = describe(KeyValue.getKeyByName("ctrl"))
        val alt = describe(KeyValue.getKeyByName("alt"))
        val fn = describe(KeyValue.getKeyByName("fn"))
        assertThat(setOf(shift, ctrl, alt, fn)).hasSize(4)
    }

    @Test
    fun panelSwitchesResolveToDistinctStrings() {
        val numeric = describe(KeyValue.getKeyByName("switch_numeric"))
        val emoji = describe(KeyValue.getKeyByName("switch_emoji"))
        val gif = describe(KeyValue.getKeyByName("switch_gif"))
        assertThat(setOf(numeric, emoji, gif)).hasSize(3)
    }

    // ── payload fallbacks (String/Macro) echo the user text ───────────────────

    @Test
    fun stringKeyEchoesItsPayload() {
        // A multi-char string key is not FLAG_KEY_FONT and its symbol IS the
        // text it inserts — echo it verbatim.
        val kv = KeyValue.makeStringKey("lol")
        assertThat(describe(kv)).isEqualTo("lol")
    }

    @Test
    fun plainNonFontSymbolKeyEchoesSymbol() {
        // '€' via getKeyByName is a plain char key — announced as the symbol.
        val euro = KeyValue.getKeyByName("€")
        val label = describe(euro)
        assertThat(label).isNotEmpty()
        assertThat(hasPua(label)).isFalse()
    }

    // ── editing keys use readable resolver strings, never a glyph ─────────────

    @Test
    fun editingKeysAreSpeakable() {
        for (name in listOf("copy", "paste", "cut", "selectAll", "undo", "redo", "copy_private")) {
            val label = describe(KeyValue.getKeyByName(name))
            assertThat(label).isNotEmpty()
            assertThat(hasPua(label)).isFalse()
        }
    }
}
