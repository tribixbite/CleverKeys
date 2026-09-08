package tribixbite.cleverkeys.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.graphics.Color as ComposeColor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.Theme

/**
 * Audit 2026-09-06, H-4 (maintainer fork resolved: WIRE THEM UP): the nine Theme-Creator
 * fields that were editable and persisted but consumed by NOTHING must surface in the
 * rendered theme. The nine: keyLocked, keyModifier, keySpecial, keyBorderActivated,
 * ripple, suggestionText, suggestionBackground, suggestionHighConfidence,
 * keyboardSurface. (swipeTrail was the one field that already escaped; its active-edit
 * staleness was W7's mechanical half — CustomThemePrefPolicyTest.)
 *
 * Red (pre-fix), from [activatedKey_borderPaintUsesTheSchemesKeyBorderActivated]:
 * the activated key's border paint carried the DEFAULT border colour (keyBorder),
 * because Theme.Computed.Key fed `theme.keyBorderColorTop` to init_border_paint for
 * both states — `expected: 1193046 (0x123456) but was: 1052688 (0x101010)`.
 * The remaining eight fields had no Theme representation at all (their wiring tests
 * did not compile pre-fix — unresolved references — the compile failure is the red).
 *
 * Mock-tier: [Theme]'s runtime constructor touches android.graphics.Color statics and
 * the cached key font (stubbed as in ThemeProviderFallbackTest); the colour mapping
 * itself is pure math and paints run on the functional android.graphics.Paint shadow.
 */
class ThemeCreatorFieldWiringTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        // Theme's private toArgb/adjustLight/isColorLight go through android.graphics.Color
        // statics, which the android.jar stubs throw on. Re-implement the pure ones; HSV is
        // not asserted on (greyedLabelColor only) so it may collapse to 0.
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0

        // Pre-seed the process-lifetime key-font cache so the runtime constructor's
        // getKeyFont() never reaches Typeface.createFromAsset.
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, mockk<Typeface>())

        context = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, null)
        unmockkAll()
    }

    /**
     * A scheme carrying a DISTINCT opaque sentinel in every one of the nine dead fields
     * (opaque so the composite-over-keyDefault blend for the key backgrounds is identity).
     */
    private fun sentinelScheme(): KeyboardColorScheme = darkKeyboardColorScheme().copy(
        keyDefault = ComposeColor(0xFF202020),
        keyBorder = ComposeColor(0xFF101010),
        keyLocked = ComposeColor(0xFF111AAA),
        keyModifier = ComposeColor(0xFF22B2B2),
        keySpecial = ComposeColor(0xFF33C3C3),
        keyBorderActivated = ComposeColor(0xFF123456),
        ripple = ComposeColor(0xFF44D4D4),
        suggestionText = ComposeColor(0xFF55E5E5),
        suggestionBackground = ComposeColor(0xFF66F6F6),
        suggestionHighConfidence = ComposeColor(0xFF770707),
        keyboardSurface = ComposeColor(0xFF881818),
    )

    private fun bareConfig(): Config = ObjenesisStd().newInstance(Config::class.java)

    // -------------------------------------------------------- apply: activated border

    /**
     * The one wiring test that COMPILES pre-fix (Key's 4-arg constructor and the scheme
     * field both exist): the activated key frame's border must render the theme's
     * "Border Activated" colour, not the default border colour.
     */
    @Test
    fun activatedKey_borderPaintUsesTheSchemesKeyBorderActivated() {
        val theme = Theme(context, sentinelScheme(), density = 1f)
        val key = Theme.Computed.Key(theme, bareConfig(), 100f, true)

        assertWithMessage(
            "the ACTIVATED key border must use the scheme's keyBorderActivated (audit " +
                "H-4) — pre-fix both states fed keyBorderColorTop to init_border_paint"
        ).that(key.border_paint.color and 0xFFFFFF).isEqualTo(0x123456)
    }

    /** The non-activated frame keeps the default border colour. */
    @Test
    fun defaultKey_borderPaintKeepsTheDefaultBorderColour() {
        val theme = Theme(context, sentinelScheme(), density = 1f)
        val key = Theme.Computed.Key(theme, bareConfig(), 100f, false)

        assertThat(key.border_paint.color and 0xFFFFFF).isEqualTo(0x101010)
    }

    // -------------------------------------------------------- apply: theme surface

    /** Every one of the nine fields must surface on the constructed [Theme]. */
    @Test
    fun runtimeScheme_surfacesAllNineFieldsOnTheme() {
        val theme = Theme(context, sentinelScheme(), density = 1f)

        // Opaque sentinels → the composite-over-keyDefault blend is identity.
        assertThat(theme.colorKeyLocked).isEqualTo(0xFF111AAA.toInt())
        assertThat(theme.colorKeyModifier).isEqualTo(0xFF22B2B2.toInt())
        assertThat(theme.colorKeySpecial).isEqualTo(0xFF33C3C3.toInt())
        assertThat(theme.keyBorderColorActivated).isEqualTo(0xFF123456.toInt())
        assertThat(theme.rippleColor).isEqualTo(0xFF44D4D4.toInt())
        assertThat(theme.suggestionTextColor).isEqualTo(0xFF55E5E5.toInt())
        assertThat(theme.suggestionBackgroundColor).isEqualTo(0xFF66F6F6.toInt())
        assertThat(theme.suggestionHighConfidenceColor).isEqualTo(0xFF770707.toInt())
        assertThat(theme.colorKeyboardSurface).isEqualTo(0xFF881818.toInt())
    }

    /** Translucent role tints composite over keyDefault (keyOpacity owns paint alpha). */
    @Test
    fun translucentRoleTint_compositesOverTheDefaultKeyColour() {
        val scheme = sentinelScheme().copy(
            keyDefault = ComposeColor(0xFF000000),
            // 50%-alpha white over black → mid grey.
            keyLocked = ComposeColor(0x80FFFFFF),
        )

        val theme = Theme(context, scheme, density = 1f)

        assertThat((theme.colorKeyLocked ushr 24) and 0xFF).isEqualTo(0xFF)
        val r = (theme.colorKeyLocked shr 16) and 0xFF
        assertWithMessage("50% white over black must land near mid grey, not opaque white (r=$r)")
            .that(r in 0x70..0x90).isTrue()
    }

    // -------------------------------------------------------- apply: computed frames

    /** The per-role computed key frames carry the role background colours. */
    @Test
    fun computedFrames_carryLockedModifierSpecialBackgrounds() {
        val theme = Theme(context, sentinelScheme(), density = 1f)
        val config = bareConfig()
        val layout = ObjenesisStd().newInstance(tribixbite.cleverkeys.KeyboardData::class.java)

        val tc = Theme.Computed(theme, config, 100f, layout)

        assertThat(tc.key_locked.bg_paint.color and 0xFFFFFF).isEqualTo(0x111AAA)
        assertThat(tc.key_modifier.bg_paint.color and 0xFFFFFF).isEqualTo(0x22B2B2)
        assertThat(tc.key_special.bg_paint.color and 0xFFFFFF).isEqualTo(0x33C3C3)
        // The plain frames keep the default/activated fills.
        assertThat(tc.key.bg_paint.color and 0xFFFFFF).isEqualTo(0x202020)
        // keyForRole maps every role onto its frame.
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.LOCKED)).isSameInstanceAs(tc.key_locked)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.MODIFIER)).isSameInstanceAs(tc.key_modifier)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.SPECIAL)).isSameInstanceAs(tc.key_special)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.NORMAL)).isSameInstanceAs(tc.key)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.ACTIVATED)).isSameInstanceAs(tc.key_activated)
    }

    /** The pure role mapping the draw loop feeds with kind + pointer state. */
    @Test
    fun roleOf_mapsKindAndPointerStateToFrameRoles() {
        val roleOf = { kind: KeyValue.Kind?, down: Boolean, locked: Boolean ->
            Theme.Computed.roleOf(kind, down, locked)
        }

        // Pressed state wins; locked beats activated.
        assertThat(roleOf(KeyValue.Kind.Modifier, true, true))
            .isEqualTo(Theme.Computed.KeyRole.LOCKED)
        assertThat(roleOf(KeyValue.Kind.Char, true, false))
            .isEqualTo(Theme.Computed.KeyRole.ACTIVATED)
        // At rest: modifiers, action keys, plain keys.
        assertThat(roleOf(KeyValue.Kind.Modifier, false, false))
            .isEqualTo(Theme.Computed.KeyRole.MODIFIER)
        assertThat(roleOf(KeyValue.Kind.Keyevent, false, false))
            .isEqualTo(Theme.Computed.KeyRole.SPECIAL)
        assertThat(roleOf(KeyValue.Kind.Event, false, false))
            .isEqualTo(Theme.Computed.KeyRole.SPECIAL)
        assertThat(roleOf(KeyValue.Kind.Editing, false, false))
            .isEqualTo(Theme.Computed.KeyRole.SPECIAL)
        assertThat(roleOf(KeyValue.Kind.Char, false, false))
            .isEqualTo(Theme.Computed.KeyRole.NORMAL)
        assertThat(roleOf(null, false, false))
            .isEqualTo(Theme.Computed.KeyRole.NORMAL)
    }

    // -------------------------------------------------------- apply: consumer wiring

    /**
     * The view-layer consumers cannot be driven on the JVM (LinearLayout/Canvas), so —
     * like CustomThemePrefPolicyTest pins its composable call sites — the wiring is
     * pinned by source scan: each themed colour must be read where it renders.
     */
    @Test
    fun viewConsumers_readTheThemedColours() {
        val keyboardView =
            File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()
        assertWithMessage(
            "Keyboard2View's draw loop must select the key frame by role (audit H-4)"
        ).that(keyboardView.contains("tc.keyForRole(")).isTrue()

        val bar = File("src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt").readText()
        for (field in listOf(
            "suggestionTextColor",
            "suggestionBackgroundColor",
            "suggestionHighConfidenceColor",
            "rippleColor",
            "colorKeyboardSurface",
        )) {
            assertWithMessage(
                "SuggestionBar must consume Theme.$field (audit H-4 — the field was " +
                    "collected and persisted but rendered nowhere)"
            ).that(bar.contains(field)).isTrue()
        }
    }

    /**
     * Editing the ACTIVE theme must reach the live keyboard without a process restart:
     * the editor mutates ThemeProvider's OWN store instance (not a stale twin) and the
     * active-save path fires the theme-changed broadcast the service already listens to.
     */
    @Test
    fun activeThemeEdits_reachTheLiveKeyboard() {
        val activity =
            File("src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt")
                .readText()
        assertWithMessage(
            "the editor must use ThemeProvider's shared CustomThemeManager — a private " +
                "instance leaves the provider's in-memory theme list stale until the " +
                "process dies"
        ).that(activity.contains("ThemeProvider.getInstance(context).customThemeManager"))
            .isTrue()
        assertWithMessage("a private CustomThemeManager(context) must be gone from the screen")
            .that(activity.contains("remember { CustomThemeManager(context) }")).isFalse()
        assertWithMessage(
            "an active-theme save must notify the IME (ACTION_THEME_CHANGED) so the view " +
                "rebuilds with the edited colours"
        ).that(activity.contains("if (savedActive) notifyKeyboardThemeChanged(context)"))
            .isTrue()
    }

    // -------------------------------------------------------- persist: JSON round-trip

    /**
     * Round-trip pin (this half was already sound pre-fix): every one of the nine
     * fields survives CustomTheme JSON serialization — reopening the editor shows the
     * saved values.
     */
    @Test
    fun customThemeJson_roundTripsAllNineFields() {
        val saved = CustomTheme(id = "rt", name = "roundtrip", colors = sentinelScheme())

        val reloaded = CustomTheme.fromJson(JSONObject(saved.toJson().toString()))

        val c = reloaded.colors
        assertThat(c.keyLocked).isEqualTo(ComposeColor(0xFF111AAA))
        assertThat(c.keyModifier).isEqualTo(ComposeColor(0xFF22B2B2))
        assertThat(c.keySpecial).isEqualTo(ComposeColor(0xFF33C3C3))
        assertThat(c.keyBorderActivated).isEqualTo(ComposeColor(0xFF123456))
        assertThat(c.ripple).isEqualTo(ComposeColor(0xFF44D4D4))
        assertThat(c.suggestionText).isEqualTo(ComposeColor(0xFF55E5E5))
        assertThat(c.suggestionBackground).isEqualTo(ComposeColor(0xFF66F6F6))
        assertThat(c.suggestionHighConfidence).isEqualTo(ComposeColor(0xFF770707))
        assertThat(c.keyboardSurface).isEqualTo(ComposeColor(0xFF881818))
    }
}
