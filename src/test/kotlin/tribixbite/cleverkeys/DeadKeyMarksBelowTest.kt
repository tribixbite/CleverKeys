package tribixbite.cleverkeys

import android.content.Context
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileInputStream
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.prefs.ExtraKeysPreference

/**
 * gh #187: `accent_macron_below` (U+0331) and `accent_ring_below` (U+0325) dead keys, plus
 * their `combining_*` keys, for ISO 15919 transliteration (ṯ ḻ ṉ ṟ, vocalic r̥ / l̥).
 *
 * The dead keys run through the REAL compose state machine: `ComposeKeyData` is initialized
 * from the `compose_data.bin` that `generateComposeData` builds from `srcs/compose` (it runs
 * in `preBuild`, so it exists before this class compiles). That is the only off-device way to
 * prove the new `accent_*_below.json` tables and the regenerated state offsets in
 * `ComposeKeyData.kt` agree; a stale bin would shift every state after `accent_macron`.
 *
 * Mock tier: initialization goes through `Context.getAssets()`, so android.jar is needed.
 */
class DeadKeyMarksBelowTest {

    @Before
    fun loadComposeData() {
        val bin = File("src/main/assets/compose_data.bin")
        assertWithMessage("run generateComposeData first: ${bin.absolutePath}").that(bin.exists()).isTrue()
        val assets = mockk<AssetManager>()
        every { assets.open("compose_data.bin") } answers { FileInputStream(bin) }
        val context = mockk<Context>()
        every { context.assets } returns assets
        // Idempotent: a second call in the same JVM is a no-op.
        ComposeKeyData.initialize(context)
    }

    private fun macronBelow(c: Char): KeyValue =
        KeyModifier.modify(KeyValue.makeCharKey(c), KeyValue.Modifier.MACRON_BELOW)

    private fun ringBelow(c: Char): KeyValue =
        KeyModifier.modify(KeyValue.makeCharKey(c), KeyValue.Modifier.RING_BELOW)

    private fun shift(k: KeyValue): KeyValue = KeyModifier.modify(k, KeyValue.Modifier.SHIFT)

    /** The text a Char or String key commits. */
    private fun text(k: KeyValue): String = when (k.getKind()) {
        KeyValue.Kind.Char -> k.getChar().toString()
        KeyValue.Kind.String -> k.getString()
        else -> error("not a text key: $k")
    }

    // ── Key definitions ─────────────────────────────────────────────────────

    @Test
    fun deadKeysAreLatchingModifiersWithTextLabels() {
        for ((name, mod, label) in listOf(
            Triple("accent_macron_below", KeyValue.Modifier.MACRON_BELOW, "◌̱"),
            Triple("accent_ring_below", KeyValue.Modifier.RING_BELOW, "◌̥"),
        )) {
            val k = KeyValue.getKeyByName(name)
            assertThat(k.getKind()).isEqualTo(KeyValue.Kind.Modifier)
            assertThat(k.getModifier()).isEqualTo(mod)
            assertThat(k.hasFlagsAny(KeyValue.FLAG_LATCH)).isTrue()
            // special_font.ttf has no glyph for these marks: a FLAG_KEY_FONT label would be tofu.
            assertWithMessage("$name must render with the system font")
                .that(k.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isFalse()
            assertThat(k.getString()).isEqualTo(label)
        }
    }

    @Test
    fun combiningKeysTypeTheBareMark() {
        for ((name, mark) in listOf("combining_macron_below" to '̱', "combining_ring_below" to '̥')) {
            val k = KeyValue.getKeyByName(name)
            assertThat(k.getKind()).isEqualTo(KeyValue.Kind.Char)
            assertThat(k.getChar()).isEqualTo(mark)
            assertThat(k.hasFlagsAny(KeyValue.FLAG_KEY_FONT)).isFalse()
            assertThat(k.getString()).isEqualTo("◌$mark")
        }
    }

    // ── Macron below ────────────────────────────────────────────────────────

    @Test
    fun macronBelowComposesEveryPrecomposedLetterAndItsCapital() {
        val expected = mapOf(
            'b' to ("ḇ" to "Ḇ"), 'd' to ("ḏ" to "Ḏ"), 'k' to ("ḵ" to "Ḵ"), 'l' to ("ḻ" to "Ḻ"),
            'n' to ("ṉ" to "Ṉ"), 'r' to ("ṟ" to "Ṟ"), 't' to ("ṯ" to "Ṯ"), 'z' to ("ẕ" to "Ẕ"),
        )
        for ((base, forms) in expected) {
            val (lower, upper) = forms
            val composed = macronBelow(base)
            assertWithMessage("$base + macron below").that(text(composed)).isEqualTo(lower)
            assertWithMessage("shift of $lower").that(text(shift(composed))).isEqualTo(upper)
        }
    }

    @Test
    fun hWithLineBelowHasNoPrecomposedCapitalSoShiftUsesTheCombiningMark() {
        val composed = macronBelow('h')
        assertThat(text(composed)).isEqualTo("ẖ") // ẖ
        // Unicode has no capital ẖ; Character.toUpperCase would return ẖ unchanged.
        assertThat(text(shift(composed))).isEqualTo("H̱")
    }

    @Test
    fun macronBelowFallsBackToTheCombiningMarkOnOtherLetters() {
        for (c in listOf('m', 's', 'g', 'a', 'y')) {
            assertWithMessage("$c + macron below").that(text(macronBelow(c))).isEqualTo("$c̱")
        }
        assertThat(text(shift(macronBelow('m')))).isEqualTo("M̱")
    }

    // ── Ring below ──────────────────────────────────────────────────────────

    @Test
    fun ringBelowComposesAWithRingBelow() {
        val composed = ringBelow('a')
        assertThat(text(composed)).isEqualTo("ḁ") // ḁ
        assertThat(text(shift(composed))).isEqualTo("Ḁ") // Ḁ
    }

    @Test
    fun ringBelowTypesVocalicRAndLWithTheCombiningMark() {
        // The reporter's r̥: Unicode has no precomposed r or l with ring below.
        assertThat(text(ringBelow('r'))).isEqualTo("r̥")
        assertThat(text(ringBelow('l'))).isEqualTo("l̥")
        assertThat(text(shift(ringBelow('r')))).isEqualTo("R̥")
    }

    // ── Non-letters ─────────────────────────────────────────────────────────

    @Test
    fun nonLetterKeysAreLeftUnchanged() {
        for (c in listOf('1', '.', ' ', '-', '\'')) {
            val key = KeyValue.makeCharKey(c)
            assertWithMessage("'$c' + macron below")
                .that(KeyModifier.modify(key, KeyValue.Modifier.MACRON_BELOW)).isSameInstanceAs(key)
            assertWithMessage("'$c' + ring below")
                .that(KeyModifier.modify(key, KeyValue.Modifier.RING_BELOW)).isSameInstanceAs(key)
        }
        val enter = KeyValue.getKeyByName("enter")
        assertThat(KeyModifier.modify(enter, KeyValue.Modifier.RING_BELOW)).isSameInstanceAs(enter)
    }

    // ── The existing tables still resolve after the state offsets moved ─────

    @Test
    fun accentsAfterTheInsertionPointStillCompose() {
        // accent_ogonek .. accent_trema, compose, fn and shift all moved when the two tables
        // were inserted; a stale compose_data.bin would make these miss.
        assertThat(text(KeyModifier.modify(KeyValue.makeCharKey('a'), KeyValue.Modifier.OGONEK))).isEqualTo("ą")
        assertThat(text(KeyModifier.modify(KeyValue.makeCharKey('u'), KeyValue.Modifier.RING))).isEqualTo("ů")
        assertThat(text(KeyModifier.modify(KeyValue.makeCharKey('a'), KeyValue.Modifier.MACRON))).isEqualTo("ā")
        assertThat(text(ComposeKey.apply(ComposeKeyData.compose, "'e")!!)).isEqualTo("é")
        assertThat(text(shift(KeyValue.makeCharKey('ẘ')))).isEqualTo("W̊")
    }

    // ── Settings and command palette ────────────────────────────────────────

    @Test
    fun allFourKeysAreOfferedAsExtraKeysInTheirCategories() {
        val groups = ExtraKeysPreference.categorizedKeys(ExtraKeysPreference.EXTRA_KEYS.toList())
        val accents = groups.getValue(R.string.extra_keys_group_accents)
        val combining = groups.getValue(R.string.extra_keys_group_combining_characters)
        assertThat(accents).containsAtLeast("accent_macron_below", "accent_ring_below")
        assertThat(combining).containsAtLeast("combining_macron_below", "combining_ring_below")
    }

    @Test
    fun allFourKeysAreCommandPaletteCommands() {
        val names = CommandRegistry.ALL_COMMANDS.map { it.name }
        assertThat(names).containsAtLeast(
            "accent_macron_below", "accent_ring_below",
            "combining_macron_below", "combining_ring_below",
        )
    }
}
