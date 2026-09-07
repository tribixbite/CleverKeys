package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Audit 2026-09-06, H-5: `LayoutModifier.modify_layout`'s cache was keyed on the NULLABLE
 * layout `name` (`"${kw.name ?: ""}_version_size"`), not on the layout itself.
 *
 * Two enabled layouts sharing a name collide: two unnamed custom layouts both key as
 * `""`, and the new-custom-layout dialog is pre-seeded with latn_qwerty_us.xml — which
 * SHIPS `name="QWERTY (US)"` — so the default flow duplicates the stock QWERTY's name.
 * On switch, `modify_layout(kwB)` runs under the same config version (the pref-listener
 * version bump is asynchronous), hits kwA's cache entry, and the view renders the WRONG
 * board; the later listener-driven refresh only re-lays-out the same stale keyboard.
 *
 * Contract pinned here: the cache key includes the layout's IDENTITY, so distinct
 * KeyboardData instances can never be served each other's modified boards, while
 * re-modifying the SAME instance in the same epoch still cache-hits.
 *
 * Red (pre-fix): `modify_layout(kwB)` returns kwA's board (contains 'a', not 'b').
 *
 * Mock-tier: KeyboardData rows are built directly (the string loader needs the
 * android.util.Xml stub); Config is Objenesis-allocated with only the fields
 * modify_layout reads; the production cache runs on the functional
 * `android.util.LruCache` test-source shadow, so the red is the wrong-board
 * assertion rather than a stub crash.
 */
class LayoutModifierCacheKeyTest {

    private val objenesis = ObjenesisStd()

    @Before
    fun setup() {
        setModifierConfig(configWith())
    }

    @After
    fun teardown() {
        setModifierConfig(null)
    }

    // ------------------------------------------------------------------ fixtures

    /** A minimal one-key layout; [name] mirrors the custom-layout XML `name` attribute. */
    private fun layoutOf(name: String?, c: Char): KeyboardData {
        val key = KeyboardData.Key(
            listOf(KeyValue.makeCharKey(c), null, null, null, null, null, null, null, null),
            null, 0, 1f, 0f, null
        )
        val row = KeyboardData.Row(listOf(key), 1f, 0f)
        val ctor = KeyboardData::class.java.declaredConstructors
            .first { it.parameterCount == 10 }
        ctor.isAccessible = true
        return ctor.newInstance(
            listOf(row),
            /* keysWidth */ 1f,
            /* keysHeight */ 1f,
            /* modmap */ null,
            /* script */ null,
            /* numpad_script */ null,
            /* name */ name,
            /* bottom_row */ false,
            /* embedded_number_row */ false,
            /* locale_extra_keys */ false,
        ) as KeyboardData
    }

    /** Only the fields modify_layout reads; everything else at JVM defaults. */
    private fun configWith(version: Int = 7): Config {
        val config = objenesis.newInstance(Config::class.java)
        config.version = version
        config.layouts = emptyList()
        config.extra_keys_param = emptyMap()
        config.extra_keys_custom = emptyMap()
        config.extra_keys_subtype = null
        config.show_numpad = false
        config.add_number_row = false
        config.actionLabel = null
        config.swapEnterActionKey = false
        config.switch_input_immediate = false
        config.shouldOfferVoiceTyping = false
        return config
    }

    private fun setModifierConfig(config: Config?) {
        val field = LayoutModifier::class.java.getDeclaredField("globalConfig")
        field.isAccessible = true
        field.set(LayoutModifier, config)
    }

    private fun KeyboardData.hasChar(c: Char): Boolean =
        getKeys().keys.contains(KeyValue.makeCharKey(c))

    // ------------------------------------------------------------------ the collision

    @Test
    fun twoUnnamedLayouts_underOneConfigVersion_eachGetTheirOwnBoard() {
        val kwA = layoutOf(name = null, c = 'a')
        val kwB = layoutOf(name = null, c = 'b')

        val modA = LayoutModifier.modify_layout(kwA)
        val modB = LayoutModifier.modify_layout(kwB)

        assertThat(modA.hasChar('a')).isTrue()
        assertWithMessage(
            "modify_layout(kwB) served kwA's cached board — the cache key must include " +
                "layout identity, not just the nullable name (audit H-5)"
        ).that(modB.hasChar('b')).isTrue()
        assertThat(modB.hasChar('a')).isFalse()
    }

    @Test
    fun twoLayoutsSharingAName_eachGetTheirOwnBoard() {
        // The default custom-layout flow: the seed XML ships name="QWERTY (US)", so a
        // saved custom layout duplicates the stock layout's name.
        val stock = layoutOf(name = "QWERTY (US)", c = 'a')
        val custom = layoutOf(name = "QWERTY (US)", c = 'b')

        LayoutModifier.modify_layout(stock)
        val modCustom = LayoutModifier.modify_layout(custom)

        assertWithMessage("same-named custom layout must not be served the stock board")
            .that(modCustom.hasChar('b')).isTrue()
    }

    // ------------------------------------------------------------------ caching still works

    @Test
    fun sameInstanceSameEpoch_isStillServedFromCache() {
        val kw = layoutOf(name = null, c = 'a')

        val first = LayoutModifier.modify_layout(kw)
        val second = LayoutModifier.modify_layout(kw)

        assertWithMessage("identity-keyed caching must still hit for the same instance")
            .that(second).isSameInstanceAs(first)
    }
}
