package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.LayoutKeyGeometry.Choice
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference
import tribixbite.cleverkeys.theme.KeyboardLookSchemes
import tribixbite.cleverkeys.theme.LookGeometry
import tribixbite.cleverkeys.theme.LookPalette
import tribixbite.cleverkeys.theme.darkKeyboardColorScheme

/**
 * Per-layout key gaps and border through the real `layouts` serializer, the layout XML parser,
 * Config and [Theme.Computed] (mock tier: android.util.Xml is a stub, so kxml2 is substituted;
 * Theme's runtime constructor needs the Color statics stubbed, as in KeyboardLookRenderTest).
 * Pure rules and precedence: [LayoutKeyGeometryTest]. Spec: docs/specs/layout-system.md
 * "Per-layout key geometry".
 */
class LayoutKeyGeometryConfigTest {

    private lateinit var context: Context
    private val objenesis = ObjenesisStd()
    private val serializer = LayoutsPreference.SERIALIZER

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkStatic(Xml::class)
        every { Xml.newPullParser() } answers { KXmlParser() }
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            (arg<Int>(0) shl 24) or (arg<Int>(1) shl 16) or (arg<Int>(2) shl 8) or arg<Int>(3)
        }
        every { Color.red(any<Int>()) } answers { (firstArg<Int>() shr 16) and 0xFF }
        every { Color.green(any<Int>()) } answers { (firstArg<Int>() shr 8) and 0xFF }
        every { Color.blue(any<Int>()) } answers { firstArg<Int>() and 0xFF }
        every { Color.colorToHSV(any<Int>(), any<FloatArray>()) } just runs
        every { Color.HSVToColor(any<FloatArray>()) } returns 0
        Theme::class.java.getDeclaredField("_key_font").apply { isAccessible = true }.set(null, mockk<Typeface>())
        val configuration = objenesis.newInstance(Configuration::class.java)
        val resources = mockk<Resources>(relaxed = true)
        every { resources.configuration } returns configuration
        context = mockk(relaxed = true)
        every { context.resources } returns resources
        every { context.applicationContext } returns context
    }

    @After
    fun teardown() {
        Theme::class.java.getDeclaredField("_key_font").apply { isAccessible = true }.set(null, null)
        unmockkAll()
    }

    // ── storage: the overrides ride in the layouts entry (and so in the backup blob) ──

    @Test
    fun untouchedEntriesSerializeExactlyAsBefore() {
        assertThat(serializer.saveItem(LayoutsPreference.NamedLayout("latn_qwerty_us"))).isEqualTo("latn_qwerty_us")
        assertThat((serializer.saveItem(LayoutsPreference.SystemLayout()) as JSONObject).toString())
            .isEqualTo("{\"kind\":\"system\"}")
    }

    @Test
    fun keyGeometryRoundTripsThroughTheLayoutsPreferenceString() {
        val geometry = mapOf(
            KeyGeometryField.HORIZONTAL_GAP to Choice.Value(3.5f),
            KeyGeometryField.BORDER to Choice.Value(1f),
            KeyGeometryField.BORDER_RADIUS to Choice.Inherit,
        )
        val items = listOf(
            LayoutsPreference.NamedLayout("latn_qwerty_us", keyGeometry = geometry),
            LayoutsPreference.CustomLayout.parse(GEOMETRY_XML, theme = "rosepine", keyGeometry = geometry),
            LayoutsPreference.SystemLayout(language = "de"),
        )
        val stored = ListGroupPreference.saveToString(items, serializer)
        assertThat(stored).contains("\"key_geometry\":{")
        val restored = ListGroupPreference.loadFromString(stored, serializer)!!
        assertThat(restored.map { it.keyGeometry }).containsExactly(geometry, geometry, null).inOrder()
        assertWithMessage("the other bindings are untouched")
            .that(restored.map { it.theme }).containsExactly(null, "rosepine", null).inOrder()
        assertThat(restored[2].language).isEqualTo("de")
    }

    @Test
    fun anImportedBackupWithHostileGeometryIsSanitisedNotTrusted() {
        val hostile = """[{"kind":"named","name":"latn_qwerty_us","key_geometry":{"horizontal_gap":400,""" +
            """"border_width":-3,"border":"maybe"}},{"kind":"system","key_geometry":"oops"},""" +
            """{"kind":"system","key_geometry":{"vertical_gap":"Inherit","border_radius":15}}]"""
        val restored = ListGroupPreference.loadFromString(hostile, serializer)!!
        assertWithMessage("every invalid value is dropped; the layout itself survives")
            .that(restored[0]).isEqualTo(LayoutsPreference.NamedLayout("latn_qwerty_us"))
        assertThat(restored[1]).isEqualTo(LayoutsPreference.SystemLayout())
        assertThat(restored[2].keyGeometry).containsExactly(
            KeyGeometryField.VERTICAL_GAP, Choice.Inherit, KeyGeometryField.BORDER_RADIUS, Choice.Value(15f),
        )
    }

    @Test
    fun bindingsAreCarriedByEachOthersCopies() {
        val geometry = mapOf(KeyGeometryField.VERTICAL_GAP to Choice.Value(0f))
        val entry = LayoutsPreference.CustomLayout.parse(GEOMETRY_XML, language = "fa", theme = "cobalt", keyGeometry = geometry)
        assertThat(entry.withTheme("dark").keyGeometry).isEqualTo(geometry)
        assertThat(entry.withLanguage("de").keyGeometry).isEqualTo(geometry)
        assertThat(entry.withKeyGeometry(null).theme).isEqualTo("cobalt")
        assertThat(entry.withKeyGeometry(emptyMap()).keyGeometry).isNull()
    }

    // ── layout XML: the key_* root attributes are the layout's defaults ──────────

    @Test
    fun xmlAttributesAreTheDefaultsAndTheUserChoiceOverridesThem() {
        val parsed = KeyboardData.load_string_exn(GEOMETRY_XML)
        assertThat(parsed.declared_key_geometry)
            .containsExactly("key_horizontal_gap", "4", "key_border", "true", "key_border_radius", "12")
        assertWithMessage("layout transformations keep the declaration")
            .that(parsed.mapKeys(KeyboardData.MapKey { it }).declared_key_geometry).isEqualTo(parsed.declared_key_geometry)

        val prefs = MockSharedPreferences()
        val editor = prefs.edit()
        LayoutsPreference.saveToPreferences(
            editor,
            listOf(
                LayoutsPreference.CustomLayout.parse(GEOMETRY_XML),
                LayoutsPreference.CustomLayout.parse(
                    GEOMETRY_XML,
                    keyGeometry = mapOf(
                        KeyGeometryField.HORIZONTAL_GAP to Choice.Value(1f),
                        KeyGeometryField.BORDER to Choice.Inherit,
                    ),
                ),
                LayoutsPreference.SystemLayout(),
            ),
        )
        editor.commit()
        val loaded = LayoutsPreference.loadLayoutsWithBindings(mockk<Resources>(relaxed = true), prefs)
        assertThat(loaded.keyGeometry).containsExactly(
            mapOf(KeyGeometryField.HORIZONTAL_GAP to 4f, KeyGeometryField.BORDER to 1f, KeyGeometryField.BORDER_RADIUS to 12f),
            mapOf(KeyGeometryField.HORIZONTAL_GAP to 1f, KeyGeometryField.BORDER_RADIUS to 12f),
            emptyMap<KeyGeometryField, Float>(),
        ).inOrder()
    }

    @Test
    fun anInvalidXmlAttributeNeverHidesTheLayout() {
        val parsed = KeyboardData.load_string_exn(GEOMETRY_XML.replace("key_border_radius=\"12\"", "key_border_radius=\"x\""))
        assertThat(parsed.rows).isNotEmpty()
        assertThat(LayoutKeyGeometry.xmlProblems(parsed.declared_key_geometry)).containsExactly("key_border_radius")
    }

    // ── rendering: Theme.Computed follows the CURRENT layout's overrides ──────────

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    /** Config with global gaps 3 % × density / 100 = 0.03 / 0.02 fractions (density 1 here). */
    private fun config(overrides: List<Map<KeyGeometryField, Float>>): Config =
        objenesis.newInstance(Config::class.java).apply {
            screenHeightPixels = 2000
            keyboardHeightPercent = 30
            keyOpacity = 255
            keyActivatedOpacity = 255
            key_vertical_margin = 0.02f
            key_horizontal_margin = 0.03f
            borderConfig = false
            customBorderRadius = 0.05f
            customBorderLineWidth = 2f
            layout_key_geometry = overrides
        }

    private fun select(config: Config, index: Int) = setField(config, "current_layout_narrow", index)

    private fun layout(): KeyboardData = objenesis.newInstance(KeyboardData::class.java)

    private val palette = LookPalette(
        keyboardBackground = 0xFFD1D3D9.toInt(), key = 0xFFFFFFFF.toInt(), keyPressed = 0xFFB4B8C1.toInt(),
        keyLocked = 0xFF475D92.toInt(), lockedLabel = 0xFFFAFAFA.toInt(), modifierKey = 0xFFABB0BA.toInt(),
        enterKey = 0xFF9A9FA8.toInt(), enterLabel = 0xFF222222.toInt(), actionKey = 0xFF0A84FF.toInt(),
        label = 0xFF000000.toInt(), modifierLabel = 0xFF111111.toInt(), actionLabel = 0xFFFFFFFF.toInt(),
        hint = 0xFF666666.toInt(), keyShadow = 0xFF898A8D.toInt(), keyBorder = 0xFFFFFFFF.toInt(),
        barBackground = 0xFFD1D3D9.toInt(), barText = 0xFF000000.toInt(), barHighlight = 0xFF000000.toInt(),
        barSeparator = 0xFFB0B3BA.toInt(), swipeTrail = 0xFF0A84FF.toInt(),
    )

    @Test
    fun theCurrentLayoutsOverridesReachTheComputedFramesAndTheGlobalsStay() {
        val theme = Theme(context, darkKeyboardColorScheme(), density = 1f)
        val config = config(
            listOf(
                emptyMap(),
                mapOf(
                    KeyGeometryField.HORIZONTAL_GAP to 5f, KeyGeometryField.VERTICAL_GAP to 0f,
                    KeyGeometryField.BORDER to 1f, KeyGeometryField.BORDER_RADIUS to 20f, KeyGeometryField.BORDER_WIDTH to 3f,
                ),
            ),
        )
        select(config, 0)
        val global = Theme.Computed(theme, config, 100f, layout())
        assertThat(global.horizontal_margin).isWithin(1e-4f).of(3f)
        assertThat(global.key.border_radius).isEqualTo(theme.keyBorderRadius)

        select(config, 1)
        val bound = Theme.Computed(theme, config, 100f, layout())
        assertThat(bound.horizontal_margin).isWithin(1e-4f).of(5f)
        assertThat(bound.vertical_margin).isEqualTo(0f)
        assertThat(bound.key.border_radius).isWithin(1e-4f).of(20f)
        assertThat(bound.key.border_width).isEqualTo(3f)
        assertThat(bound.key_activated.border_width).isEqualTo(3f)

        assertWithMessage("an override never writes the global settings")
            .that(listOf(config.key_horizontal_margin, config.key_vertical_margin, config.customBorderRadius))
            .containsExactly(0.03f, 0.02f, 0.05f).inOrder()
        assertThat(config.borderConfig).isFalse()

        assertWithMessage("an index past the end follows layout 0, as the layout manager does")
            .that(Theme.Computed(theme, config.also { select(it, 7) }, 100f, layout()).horizontal_margin)
            .isWithin(1e-4f).of(3f)
    }

    @Test
    fun aLayoutOverrideBeatsTheEffectiveLook() {
        val look = LookGeometry(keyGapHorizontal = 0.16f, keyGapVertical = 0.22f, cornerRadiusDp = 5f, borderWidthDp = 0f)
        val theme = Theme(
            context, KeyboardLookSchemes.colorScheme(palette), density = 1f,
            lookPalette = palette, lookGeometry = look, lookFunctionChars = "",
        )
        val config = config(listOf(mapOf(KeyGeometryField.HORIZONTAL_GAP to 1f, KeyGeometryField.BORDER_RADIUS to 10f)))
        config.borderConfig = true
        val tc = Theme.Computed(theme, config, 100f, layout())
        assertThat(tc.horizontal_margin).isWithin(1e-4f).of(1f)
        assertWithMessage("the look keeps the fields the layout does not override")
            .that(tc.vertical_margin).isWithin(1e-3f).of(0.22f * tc.row_height)
        assertThat(tc.key.border_radius).isWithin(1e-4f).of(10f)
        assertWithMessage("width not overridden: the global custom width (border on globally)")
            .that(tc.key.border_width).isEqualTo(2f)

        val lookOnly = Theme.Computed(theme, config(emptyList()).apply { borderConfig = true }, 100f, layout())
        assertThat(lookOnly.horizontal_margin).isWithin(1e-3f).of(16f)
        assertThat(lookOnly.key.border_radius).isEqualTo(5f)
    }

    @Test
    fun aLayoutTurningTheBorderOffDrawsTheThemesFrameUnderAGlobalCustomBorder() {
        val theme = Theme(context, darkKeyboardColorScheme(), density = 1f)
        val config = config(listOf(mapOf(KeyGeometryField.BORDER to 0f))).apply { borderConfig = true }
        val tc = Theme.Computed(theme, config, 100f, layout())
        assertThat(tc.key.border_radius).isEqualTo(theme.keyBorderRadius)
        assertThat(tc.key.border_width).isEqualTo(theme.keyBorderWidth)
        assertThat(tc.key_activated.border_width).isEqualTo(theme.keyBorderWidthActivated)
    }

    /**
     * Keyboard2View keys its computed-frame cache on the snapshot version (a live Config read
     * there is forbidden by ConfigSnapshotRatchetTest), so a switch between layouts whose
     * effective gaps/borders differ must publish a new version; one between equal ones need not.
     */
    @Test
    fun aLayoutSwitchThatChangesKeyGeometryPublishesANewSnapshotVersion() {
        val config = config(
            listOf(emptyMap(), mapOf(KeyGeometryField.HORIZONTAL_GAP to 4f), mapOf(KeyGeometryField.HORIZONTAL_GAP to 4f)),
        )
        setField(config, "_prefs", MockSharedPreferences())
        config.layouts = listOf(null, null, null)
        setField(config, "layout_languages", listOf<String?>(null, null, null))
        setField(config, "user_primary_language", "en")
        setField(config, "user_secondary_language", "none")
        config.primary_language = "en"
        config.themeName = "dark"
        config.swipe_trail_effect = "glow"
        config.swipe_engine_mode = "ctc"

        val v0 = config.version
        config.set_current_layout(1)
        assertThat(config.version).isGreaterThan(v0)
        assertThat(config.snapshot.version).isEqualTo(config.version)
        val v1 = config.version
        config.set_current_layout(2)
        assertWithMessage("same effective geometry: no new version").that(config.version).isEqualTo(v1)
        config.set_current_layout(0)
        assertThat(config.version).isGreaterThan(v1)
    }

    /**
     * Saga 144, 2026-10-27 (release acd3b79e, CleverKeys Dark = an XML theme): a layout's 5 %
     * horizontal / 4.25 % vertical gap drew the same ~10 px / ~5 px as the global 2 % / 1.5 %.
     * The XML constructor set [Theme.density] to 1, so an override's percent was scaled by 1
     * where Config scales the global one by the display density (2.625 there). The same number
     * must give the same gap on every theme, as the spec promises.
     */
    @Test
    fun anXmlThemeScalesALayoutsGapsAndWidthByTheDisplayDensityLikeTheGlobalSetting() {
        val metrics = objenesis.newInstance(android.util.DisplayMetrics::class.java).apply { density = 2.625f }
        every { context.resources.displayMetrics } returns metrics
        val theme = Theme(context, null)
        val config = config(
            listOf(
                emptyMap(),
                mapOf(
                    KeyGeometryField.HORIZONTAL_GAP to 2f, KeyGeometryField.VERTICAL_GAP to 1.5f,
                    KeyGeometryField.BORDER to 1f, KeyGeometryField.BORDER_WIDTH to 3f,
                ),
            ),
        ).apply {
            // What Config.refresh stores for the global 2 % / 1.5 % at density 2.625 (get_dip_pref / 100).
            key_horizontal_margin = 2f * 2.625f / 100f
            key_vertical_margin = 1.5f * 2.625f / 100f
        }
        select(config, 0)
        val global = Theme.Computed(theme, config, 100f, layout())
        select(config, 1)
        val bound = Theme.Computed(theme, config, 100f, layout())
        assertWithMessage("a layout's 2 % is the global 2 %")
            .that(bound.horizontal_margin).isWithin(1e-3f).of(global.horizontal_margin)
        assertThat(bound.horizontal_margin).isWithin(1e-3f).of(5.25f)
        assertWithMessage("a layout's 1.5 % is the global 1.5 %")
            .that(bound.vertical_margin).isWithin(1e-3f).of(global.vertical_margin)
        assertWithMessage("a layout's 3 dp line is 3 × density px")
            .that(bound.key.border_width).isWithin(1e-3f).of(3f * 2.625f)
    }

    @Test
    fun anObjenesisConfigHasNoOverrides() {
        val config = objenesis.newInstance(Config::class.java)
        assertThat(config.currentKeyGeometry()).isEmpty()
    }

    companion object {
        const val GEOMETRY_XML =
            "<keyboard name=\"Geometry test\" script=\"latin\" key_horizontal_gap=\"4\" key_border=\"true\" " +
                "key_border_radius=\"12\"><row><key key0=\"a\"/><key key0=\"b\"/></row></keyboard>"
    }
}
