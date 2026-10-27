package tribixbite.cleverkeys.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.Theme

/**
 * Keyboard looks reach the renderer (2026-10-27): a look's geometry, role fills, role labels and
 * shadow surface on [Theme.Computed]; a non-look theme's computed frames are exactly what they
 * were before looks existed; looks follow the system light/dark mode through ThemeProvider and
 * the base-style map, so a mode switch re-inflates the keyboard view.
 *
 * Mock tier: [Theme]'s runtime constructor touches android.graphics.Color statics and the cached
 * key font (stubbed as in ThemeCreatorFieldWiringTest); paints run on the functional
 * android.graphics.Paint test-source shadow.
 */
class KeyboardLookRenderTest {

    private lateinit var context: Context
    private var nightMode = Configuration.UI_MODE_NIGHT_NO

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, mockk<Typeface>())

        // android.jar's Configuration() constructor is a stub: allocate it without one.
        val configuration = ObjenesisStd().newInstance(Configuration::class.java)
        val resources = mockk<Resources>(relaxed = true)
        every { resources.configuration } answers { configuration.apply { uiMode = nightMode } }
        context = mockk(relaxed = true)
        every { context.resources } returns resources
        every { context.applicationContext } returns context
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font")
            .apply { isAccessible = true }
            .set(null, null)
        unmockkAll()
    }

    /** A Config with the geometry inputs Computed reads: 2000 px screen, 30 % height. */
    private fun config(): Config = ObjenesisStd().newInstance(Config::class.java).apply {
        screenHeightPixels = 2000
        keyboardHeightPercent = 30
        keyOpacity = 255
        keyActivatedOpacity = 255
        key_vertical_margin = 0.02f
        key_horizontal_margin = 0.03f
    }

    /** Theme converts through Compose float channels and truncates: allow one unit per channel. */
    private fun near(a: Int, b: Int): Boolean =
        (0..24 step 8).all { kotlin.math.abs(((a ushr it) and 0xFF) - ((b ushr it) and 0xFF)) <= 1 }

    private fun layout(): KeyboardData = ObjenesisStd().newInstance(KeyboardData::class.java)

    private val look = LookGeometry(
        keyGapHorizontal = 0.16f, keyGapVertical = 0.22f, cornerRadiusDp = 5f,
        borderWidthDp = 0f, shadowDyDp = 1f, labelScale = 1.1f,
    )
    private val palette = LookPalette(
        keyboardBackground = 0xFFD1D3D9.toInt(), key = 0xFFFFFFFF.toInt(), keyPressed = 0xFFB4B8C1.toInt(),
        keyLocked = 0xFF475D92.toInt(), lockedLabel = 0xFFFAFAFA.toInt(), modifierKey = 0xFFABB0BA.toInt(),
        enterKey = 0xFF9A9FA8.toInt(), enterLabel = 0xFF222222.toInt(), actionKey = 0xFF0A84FF.toInt(),
        label = 0xFF000000.toInt(), modifierLabel = 0xFF111111.toInt(), actionLabel = 0xFFFFFFFF.toInt(),
        hint = 0xFF666666.toInt(), keyShadow = 0xFF898A8D.toInt(), keyBorder = 0xFFFFFFFF.toInt(),
        barBackground = 0xFFD1D3D9.toInt(), barText = 0xFF000000.toInt(), barHighlight = 0xFF000000.toInt(),
        barSeparator = 0xFFB0B3BA.toInt(), swipeTrail = 0xFF0A84FF.toInt(),
    )

    private fun lookTheme(): Theme = Theme(
        context, KeyboardLookSchemes.colorScheme(palette), density = 2f,
        lookPalette = palette, lookGeometry = look, lookFunctionChars = ",.",
    )

    @Test
    fun aLooksGeometryAndRoleColoursReachTheComputedFrames() {
        val tc = Theme.Computed(lookTheme(), config(), 100f, layout())

        assertThat(tc.horizontal_margin).isWithin(1e-3f).of(16f)
        assertThat(tc.vertical_margin).isWithin(1e-3f).of(0.22f * tc.row_height)
        assertThat(tc.label_scale).isEqualTo(1.1f)
        for (frame in listOf(tc.key, tc.key_modifier, tc.key_special, tc.key_enter, tc.key_action, tc.key_activated)) {
            assertThat(frame.border_radius).isEqualTo(10f)
            assertThat(frame.border_width).isEqualTo(0f)
            assertThat(frame.shadow_dy).isEqualTo(2f)
            assertThat(frame.shadow_paint!!.color and 0xFFFFFF).isEqualTo(0x898A8D)
        }
        assertThat(near(tc.key.bg_paint.color, 0xFFFFFFFF.toInt())).isTrue()
        assertThat(near(tc.key_modifier.bg_paint.color, 0xFFABB0BA.toInt())).isTrue()
        assertThat(near(tc.key_special.bg_paint.color, 0xFFABB0BA.toInt())).isTrue()
        assertWithMessage("plain Enter gets the look's Enter fill")
            .that(tc.key_enter.bg_paint.color and 0xFFFFFF).isEqualTo(0x9A9FA8)
        assertThat(tc.key_enter.labelColorOverride).isEqualTo(0xFF222222.toInt())
        assertWithMessage("the editor-action key gets the look's action fill")
            .that(tc.key_action.bg_paint.color and 0xFFFFFF).isEqualTo(0x0A84FF)
        assertThat(tc.key_action.labelColorOverride).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(near(tc.key_locked.bg_paint.color, 0xFF475D92.toInt())).isTrue()
        assertThat(tc.key_modifier.labelColorOverride).isEqualTo(0xFF111111.toInt())
        assertThat(tc.key.labelColorOverride).isEqualTo(0)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.ACTION)).isSameInstanceAs(tc.key_action)
        assertThat(tc.keyForRole(Theme.Computed.KeyRole.ENTER)).isSameInstanceAs(tc.key_enter)
        val theme = lookTheme()
        assertWithMessage("a look's Caps Lock label").that(theme.lockedColor).isEqualTo(0xFFFAFAFA.toInt())
        assertThat(theme.functionChars).isEqualTo(",.")
        assertThat(theme.swipeTrailColor).isEqualTo(0xFF0A84FF.toInt())
        assertThat(theme.suggestionSeparatorColor).isEqualTo(0xFFB0B3BA.toInt())
    }

    @Test
    fun aThemeWithoutALookComputesExactlyTheUsersGeometry() {
        val theme = Theme(context, darkKeyboardColorScheme(), density = 2f)
        val config = config()
        val tc = Theme.Computed(theme, config, 100f, layout())

        assertThat(tc.horizontal_margin).isEqualTo(0.03f * 100f)
        assertThat(tc.vertical_margin).isEqualTo(0.02f * tc.row_height)
        assertThat(tc.label_scale).isEqualTo(1f)
        assertThat(tc.key.border_radius).isEqualTo(theme.keyBorderRadius)
        assertThat(tc.key.border_width).isEqualTo(theme.keyBorderWidth)
        assertThat(tc.key_activated.border_width).isEqualTo(theme.keyBorderWidthActivated)
        assertThat(tc.key.shadow_paint).isNull()
        assertWithMessage("no look: the Enter and action frames have the special fill (render as before)")
            .that(tc.key_action.bg_paint.color).isEqualTo(tc.key_special.bg_paint.color)
        assertThat(tc.key_enter.bg_paint.color).isEqualTo(tc.key_special.bg_paint.color)
        assertThat(tc.key_action.labelColorOverride).isEqualTo(0)
        assertThat(tc.key_enter.labelColorOverride).isEqualTo(0)
        assertThat(theme.functionChars).isEmpty()
        assertThat(theme.swipeTrailColor).isEqualTo(0)

        // The user's custom border (border_config) still applies to non-look themes.
        config.borderConfig = true
        config.customBorderRadius = 0.1f
        config.customBorderLineWidth = 4f
        val custom = Theme.Computed(theme, config, 100f, layout())
        assertThat(custom.key.border_radius).isWithin(1e-4f).of(10f)
        assertThat(custom.key.border_width).isEqualTo(4f)
        // …and a look overrides it while effective.
        assertThat(Theme.Computed(lookTheme(), config, 100f, layout()).key.border_radius).isEqualTo(10f)
        assertThat(Theme.Computed(lookTheme(), config, 100f, layout()).key.border_width).isEqualTo(0f)
    }

    @Test
    fun enterTheEditorActionKeyAndALooksFunctionCharactersGetTheirRolesAtRest() {
        val roleOf = { kv: KeyValue, down: Boolean -> Theme.Computed.roleOf(kv, down, false) }
        assertThat(roleOf(KeyValue.getKeyByName("enter"), false)).isEqualTo(Theme.Computed.KeyRole.ENTER)
        assertWithMessage("Go / Search / Send fields: LayoutModifier replaces Enter with an action key")
            .that(roleOf(KeyValue.makeActionKey("Go"), false)).isEqualTo(Theme.Computed.KeyRole.ACTION)
        assertThat(roleOf(KeyValue.getKeyByName("action"), false)).isEqualTo(Theme.Computed.KeyRole.ACTION)
        // Gboard draws comma and period as function keys; other themes pass no characters.
        assertThat(Theme.Computed.roleOf(KeyValue.getKeyByName(","), false, false, ",."))
            .isEqualTo(Theme.Computed.KeyRole.MODIFIER)
        assertThat(roleOf(KeyValue.getKeyByName(","), false)).isEqualTo(Theme.Computed.KeyRole.NORMAL)
        assertThat(Theme.Computed.roleOf(KeyValue.getKeyByName("a"), false, false, ",."))
            .isEqualTo(Theme.Computed.KeyRole.NORMAL)
        assertWithMessage("pressed Enter draws the pressed frame")
            .that(roleOf(KeyValue.getKeyByName("enter"), true)).isEqualTo(Theme.Computed.KeyRole.ACTIVATED)
        assertThat(roleOf(KeyValue.getKeyByName("backspace"), false)).isEqualTo(Theme.Computed.KeyRole.SPECIAL)
        assertThat(roleOf(KeyValue.getKeyByName("shift"), false)).isEqualTo(Theme.Computed.KeyRole.MODIFIER)
        assertThat(roleOf(KeyValue.getKeyByName("space"), false)).isEqualTo(Theme.Computed.KeyRole.NORMAL)
        assertThat(roleOf(KeyValue.getKeyByName("a"), false)).isEqualTo(Theme.Computed.KeyRole.NORMAL)
    }

    @Test
    fun looksFollowTheSystemModeInThemeProviderAndTheBaseStyleMap() {
        val provider = ThemeProvider(context, mockk(relaxed = true))
        for (id in KeyboardLooks.IDS) {
            assertThat(provider.isAvailable(id)).isTrue()
            assertThat(provider.isRuntimeTheme(id)).isTrue()
            assertThat(Config.builtInThemeStyleId(id, systemLight = true)).isEqualTo(R.style.CleverKeysLight)
            assertThat(Config.builtInThemeStyleId(id, systemLight = false)).isEqualTo(R.style.CleverKeysDark)

            val look = KeyboardLooks.byId(id)!!
            nightMode = Configuration.UI_MODE_NIGHT_NO
            assertWithMessage("$id light").that(near(provider.getTheme(id).colorKeyboardBackground, look.light.keyboardBackground)).isTrue()
            assertThat(provider.getColorScheme(id)).isEqualTo(KeyboardLookSchemes.colorScheme(look.light))
            nightMode = Configuration.UI_MODE_NIGHT_YES
            assertWithMessage("$id dark").that(near(provider.getTheme(id).colorKeyboardBackground, look.dark.keyboardBackground)).isTrue()
            assertThat(provider.getTheme(id).lookGeometry).isEqualTo(look.geometry)
        }
        assertThat(provider.isAvailable("look_missing")).isFalse()
        assertWithMessage("an unknown look id must not crash the keyboard view (getTheme is total)")
            .that(provider.getTheme("look_missing")).isNotNull()
        assertThat(provider.getAllThemeIds()["Looks"]).containsExactlyElementsIn(KeyboardLooks.IDS).inOrder()
    }
}
