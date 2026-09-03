package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Release-record guards for the two [KeyModifier] claims.
 *
 * | version | published note |
 * |---|---|
 * | v1.2.6 / v1.2.8 | "Space key types a space when text is selected (#1142)" |
 * | v1.2.8 | "Greek/Math disabled in the numeric layer unless enabled in extra keys (#77)" |
 *
 * **#1142** — selection mode used to rewrite the space key into `selection_cancel`, so pressing
 * space with a selection merely dropped the selection instead of replacing it (`8e50153d`).
 * Every *character* now passes through untouched; only non-character keys keep their
 * selection-mode meaning.
 *
 * **#77** — the Fn layer used to rewrite the numeric-layout switch into the Greek/Math switch
 * unconditionally, so users who had never enabled Greek/Math could not reach the numeric layer.
 * The rewrite is now conditional on the Greek/Math key being present in the extra keys.
 *
 * Mock tier: `applyFnEvent` reads `Config.globalConfig()`, and `Config` itself reaches
 * `SharedPreferences`/`Resources`, so `android.jar` must be on the classpath.
 */
class ReleaseClaimKeyModifierTest {

    private lateinit var config: Config

    @Before
    fun setUp() {
        // Config's fields are @JvmField vars, so they are written directly rather than stubbed.
        config = mockk(relaxed = true)
        config.extra_keys_param = emptyMap()
        config.extra_keys_custom = emptyMap()
        setGlobalConfig(config)
    }

    @After
    fun tearDown() {
        setGlobalConfig(null)
    }

    // ================================================================= #1142 space + selection

    @Test
    fun `space passes through selection mode unchanged`() {
        val space = KeyValue.makeCharKey(' ')
        val result = KeyModifier.modify(space, KeyValue.Modifier.SELECTION_MODE)

        assertWithMessage(
            "#1142: selection mode used to map space to 'selection_cancel', so space merely " +
                "dropped the selection. Space must now reach the editor and replace it."
        ).that(result).isSameInstanceAs(space)
        assertThat(result.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(result.getChar()).isEqualTo(' ')
    }

    @Test
    fun `the layout's space key also passes through selection mode`() {
        val space = KeyValue.getKeyByName("space")
        assertThat(space.getKind()).isEqualTo(KeyValue.Kind.Char)
        assertThat(space.getChar()).isEqualTo(' ')

        val result = KeyModifier.modify(space, KeyValue.Modifier.SELECTION_MODE)
        assertThat(result).isSameInstanceAs(space)
        assertThat(result.getChar()).isEqualTo(' ')
    }

    @Test
    fun `every character key replaces the selection rather than acting on it`() {
        for (c in listOf(' ', 'a', 'Z', '1', '.', '\'', 'é')) {
            val key = KeyValue.makeCharKey(c)
            assertWithMessage("char '$c' must survive selection mode")
                .that(KeyModifier.modify(key, KeyValue.Modifier.SELECTION_MODE))
                .isSameInstanceAs(key)
        }
    }

    @Test
    fun `escape still cancels the selection`() {
        // The #1142 fix must not have disarmed the keys selection mode is FOR.
        val esc = KeyValue.getKeyByName("esc")
        val result = KeyModifier.modify(esc, KeyValue.Modifier.SELECTION_MODE)

        assertThat(result).isNotSameInstanceAs(esc)
        assertThat(result).isEqualTo(KeyValue.getKeyByName("selection_cancel"))
    }

    @Test
    fun `cursor sliders still extend the selection`() {
        val left = KeyValue.sliderKey(KeyValue.Slider.Cursor_left, 1)
        assertThat(KeyModifier.modify(left, KeyValue.Modifier.SELECTION_MODE))
            .isEqualTo(KeyValue.getKeyByName("selection_cursor_left"))

        val right = KeyValue.sliderKey(KeyValue.Slider.Cursor_right, 1)
        assertThat(KeyModifier.modify(right, KeyValue.Modifier.SELECTION_MODE))
            .isEqualTo(KeyValue.getKeyByName("selection_cursor_right"))
    }

    @Test
    fun `unrelated key events are untouched by selection mode`() {
        val enter = KeyValue.getKeyByName("enter")
        assertThat(KeyModifier.modify(enter, KeyValue.Modifier.SELECTION_MODE))
            .isSameInstanceAs(enter)
    }

    // ============================================================ #77 Greek/Math in the Fn layer

    @Test
    fun `Fn leaves the numeric switch alone when Greek-Math is not an extra key`() {
        val numeric = KeyValue.getKeyByName("switch_numeric")
        assertThat(numeric.getEvent()).isEqualTo(KeyValue.Event.SWITCH_NUMERIC)

        val result = KeyModifier.modify(numeric, KeyValue.Modifier.FN)

        assertWithMessage(
            "#77: with Greek/Math not enabled, Fn+numeric must still reach the NUMERIC layer"
        ).that(result).isSameInstanceAs(numeric)
        assertThat(result.getEvent()).isEqualTo(KeyValue.Event.SWITCH_NUMERIC)
    }

    @Test
    fun `Fn maps the numeric switch to Greek-Math when it is a parameter extra key`() {
        val greekMath = KeyValue.getKeyByName("switch_greekmath")
        config.extra_keys_param = mapOf(greekMath to KeyboardData.PreferredPos.DEFAULT)

        val result = KeyModifier.modify(
            KeyValue.getKeyByName("switch_numeric"), KeyValue.Modifier.FN
        )

        assertThat(result).isEqualTo(greekMath)
        assertThat(result.getEvent()).isEqualTo(KeyValue.Event.SWITCH_GREEKMATH)
    }

    @Test
    fun `Fn maps the numeric switch to Greek-Math when it is a custom extra key`() {
        val greekMath = KeyValue.getKeyByName("switch_greekmath")
        config.extra_keys_custom = mapOf(greekMath to KeyboardData.PreferredPos.DEFAULT)

        val result = KeyModifier.modify(
            KeyValue.getKeyByName("switch_numeric"), KeyValue.Modifier.FN
        )

        assertThat(result).isEqualTo(greekMath)
    }

    @Test
    fun `some other extra key does not enable Greek-Math`() {
        config.extra_keys_param = mapOf(
            KeyValue.getKeyByName("voice_typing") to KeyboardData.PreferredPos.DEFAULT
        )
        config.extra_keys_custom = mapOf(
            KeyValue.getKeyByName("compose") to KeyboardData.PreferredPos.DEFAULT
        )

        val numeric = KeyValue.getKeyByName("switch_numeric")
        assertWithMessage("only the Greek/Math key itself may unlock the Fn rewrite")
            .that(KeyModifier.modify(numeric, KeyValue.Modifier.FN))
            .isSameInstanceAs(numeric)
    }

    @Test
    fun `the Greek-Math gate is re-evaluated, not cached across settings changes`() {
        val numeric = KeyValue.getKeyByName("switch_numeric")
        val greekMath = KeyValue.getKeyByName("switch_greekmath")

        assertThat(KeyModifier.modify(numeric, KeyValue.Modifier.FN)).isSameInstanceAs(numeric)

        config.extra_keys_param = mapOf(greekMath to KeyboardData.PreferredPos.DEFAULT)
        assertThat(KeyModifier.modify(numeric, KeyValue.Modifier.FN)).isEqualTo(greekMath)

        config.extra_keys_param = emptyMap()
        assertThat(KeyModifier.modify(numeric, KeyValue.Modifier.FN)).isSameInstanceAs(numeric)
    }

    // ------------------------------------------------------------------------------- helpers

    /** Same reflection seam AutocapitalisationTest uses; Config's setter is private. */
    private fun setGlobalConfig(value: Config?) {
        try {
            val companion = Config::class.java.getDeclaredField("Companion").get(null)
            val field = companion.javaClass.getDeclaredField("_globalConfig")
            field.isAccessible = true
            field.set(companion, value)
        } catch (_: Exception) {
            val field = Config::class.java.getDeclaredField("_globalConfig")
            field.isAccessible = true
            field.set(null, value)
        }
    }
}
