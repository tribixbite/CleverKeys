package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkAll
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.theme.CustomTheme
import tribixbite.cleverkeys.theme.CustomThemeManager
import tribixbite.cleverkeys.theme.EmbeddedThemes
import tribixbite.cleverkeys.theme.KeyboardColorSchemeHelper
import tribixbite.cleverkeys.theme.KeyboardViewThemeStamp
import tribixbite.cleverkeys.theme.LayoutThemeTransfer
import tribixbite.cleverkeys.theme.LookGeometry
import tribixbite.cleverkeys.theme.LookPillKey
import tribixbite.cleverkeys.theme.ThemeColorRole
import tribixbite.cleverkeys.theme.ThemeProvider
import tribixbite.cleverkeys.theme.ThemeSpec
import tribixbite.cleverkeys.theme.ThemeSpecs
import tribixbite.cleverkeys.theme.themeNeonCyberpunk

/**
 * A layout's EMBEDDED `<theme>` end to end (2026-10-28): the layouts preference reports it, Config
 * renders it when the layout has no binding of its own (user binding > XML `theme=` id >
 * embedded > global; "Follow global theme" turns it off), a space-bar switch onto or off such a
 * layout re-inflates the view, ThemeProvider draws it over the global theme (unset fields
 * inherited, light/dark variants by the system mode), and Layout Manager's "Save theme as
 * custom theme" stores it in THE provider's CustomThemeManager without losing a field — and
 * Theme Manager's export writes it back.
 *
 * Mock tier: the layout parser (kxml2 substituted), Theme's runtime constructor (Color statics
 * stubbed) and a CustomThemeManager over in-memory preferences.
 */
class LayoutEmbeddedThemeTest {

    private val objenesis = ObjenesisStd()
    private lateinit var context: Context
    private var nightMode = Configuration.UI_MODE_NIGHT_NO
    private val exampleXml = File("docs/wiki/layouts/examples/harbour.xml").readText()
    private val cyberpunk = "decorative_neon_cyberpunk"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
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
        val metrics = objenesis.newInstance(DisplayMetrics::class.java).apply { density = 2f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.configuration } answers { configuration.apply { uiMode = nightMode } }
        every { resources.displayMetrics } returns metrics
        val prefs = MockSharedPreferences()
        context = mockk(relaxed = true)
        every { context.resources } returns resources
        every { context.applicationContext } returns context
        every { context.createDeviceProtectedStorageContext() } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @After
    fun teardown() {
        Config.themeAvailability = null
        Theme::class.java.getDeclaredField("_key_font").apply { isAccessible = true }.set(null, null)
        unmockkAll()
    }

    private fun provider() = ThemeProvider(context, CustomThemeManager(context))
    private fun example(): ThemeSpec = KeyboardData.load_string_exn(exampleXml).embedded_theme!!
    private fun near(a: Int, b: Int): Boolean =
        (0..24 step 8).all { kotlin.math.abs(((a ushr it) and 0xFF) - ((b ushr it) and 0xFF)) <= 1 }

    // ── the layouts preference and Config ────────────────────────────────────────

    @Test
    fun theLayoutsPreferenceReportsEachLayoutsEmbeddedThemeUnlessItFollowsTheGlobalTheme() {
        val prefs = MockSharedPreferences()
        val editor = prefs.edit()
        LayoutsPreference.saveToPreferences(editor, listOf(
            LayoutsPreference.NamedLayout("latn_qwerty_us"),
            LayoutsPreference.CustomLayout.parse(exampleXml),
            LayoutsPreference.CustomLayout.parse(exampleXml, theme = LayoutThemeBinding.UNBOUND),
            LayoutsPreference.CustomLayout.parse(exampleXml, theme = "cobalt"),
        ))
        editor.commit()
        val loaded = LayoutsPreference.loadLayoutsWithBindings(mockk<Resources>(relaxed = true), prefs)
        assertThat(loaded.embeddedThemes).containsExactly(null, example(), null, example()).inOrder()
        assertThat(loaded.themes).containsExactly(null, null, null, "cobalt").inOrder()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private val styleIdOf: (String) -> Int = { if (it.startsWith(EmbeddedThemes.ID_PREFIX)) 21 else 11 }

    private fun config(bindings: List<String?>, embedded: List<ThemeSpec?>, global: String = "dark"): Config =
        objenesis.newInstance(Config::class.java).also { c ->
            setField(c, "_prefs", MockSharedPreferences())
            c.layouts = bindings.map { null }
            setField(c, "layout_languages", bindings.map { null })
            setField(c, "layout_themes", bindings)
            setField(c, "layout_embedded_themes", embedded)
            setField(c, "user_theme_name", global)
            setField(c, "user_primary_language", "en")
            setField(c, "user_secondary_language", "none")
            c.primary_language = "en"
            c.themeName = global
            c.theme = styleIdOf(global)
            c.swipe_trail_effect = "glow"
            c.swipe_engine_mode = "ctc"
        }

    @Test
    fun configRendersTheEmbeddedThemeOnlyWhereNoBindingBeatsIt() {
        val spec = example()
        val config = config(listOf(null, null, "cobalt"), listOf(null, spec, spec))
        config.recomputeActiveTheme(styleIdOf)
        assertThat(config.themeName).isEqualTo("dark")
        assertThat(config.layout_embedded_theme).isNull()

        config.set_current_layout(1)
        assertThat(config.recomputeActiveTheme(styleIdOf)).isTrue()
        assertThat(config.themeName).isEqualTo(EmbeddedThemes.idFor(spec, "dark"))
        assertThat(config.layout_embedded_theme).isEqualTo(config.themeName)
        assertThat(config.theme).isEqualTo(21)
        assertThat(EmbeddedThemes.parse(config.themeName)).isEqualTo(EmbeddedThemes.Parsed(spec, "dark"))

        config.set_current_layout(2)
        config.recomputeActiveTheme(styleIdOf)
        assertWithMessage("a binding (user or XML theme=) beats the embedded theme").that(config.themeName).isEqualTo("cobalt")
        assertThat(config.layout_embedded_theme).isNull()

        Config.themeAvailability = { it != "cobalt" }
        config.recomputeActiveTheme(styleIdOf)
        assertWithMessage("a missing bound theme falls through to the layout's own")
            .that(config.layout_embedded_theme).isNotNull()
        assertThat(config.layout_missing_theme).isEqualTo("cobalt")
    }

    private class CountingListener : ConfigChangeListener {
        var themeChanges = 0
        override fun onConfigChanged(newConfig: Config) {}
        override fun onThemeChanged(oldTheme: Int, newTheme: Int) { themeChanges++ }
    }

    @Test
    fun aSpaceBarSwitchOntoAndOffAnEmbeddedThemeLayoutAndAGlobalThemeChangeUnderItReinflate() {
        val spy = spyk(config(listOf(null, null), listOf(null, example())))
        every { spy.refresh(any(), any()) } answers { spy.recomputeActiveTheme(styleIdOf); Unit }
        val manager = ConfigurationManager(mockk(relaxed = true), spy, mockk<FoldStateTracker>(relaxed = true))
        val listener = CountingListener().also { manager.registerConfigChangeListener(it) }
        val res = mockk<Resources>(relaxed = true)

        spy.set_current_layout(1); manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(1)
        // The global theme changes while the embedded-theme layout is shown: it inherits from it.
        setField(spy, "user_theme_name", "rosepine")
        manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(2)
        assertThat(EmbeddedThemes.parse(spy.themeName)!!.baseThemeId).isEqualTo("rosepine")
        spy.set_current_layout(0); manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(3)
        assertThat(spy.themeName).isEqualTo("rosepine")
    }

    // ── ThemeProvider: drawn over the global theme ───────────────────────────────

    @Test
    fun anEmbeddedThemeDrawsItsOwnFieldsAndInheritsTheRestFromTheGlobalTheme() {
        val provider = provider()
        val partial = ThemeSpec(
            colors = mapOf(ThemeColorRole.KEY to 0xFF123456.toInt(), ThemeColorRole.ENTER_KEY to 0xFF0A84FF.toInt()),
            dark = mapOf(ThemeColorRole.KEY to 0xFF0F0F0F.toInt()),
            geometry = LookGeometry(cornerRadiusDp = 12f),
            pillKeys = setOf(LookPillKey.ENTER),
        )
        val id = EmbeddedThemes.idFor(partial, cyberpunk)
        assertThat(tribixbite.cleverkeys.prefs.ConfigSnapshot.isRuntimeThemeName(id)).isTrue()
        assertThat(provider.isAvailable(id)).isTrue()
        assertThat(provider.isAvailable("embedded_424242.dark")).isFalse()
        val base = themeNeonCyberpunk().colorScheme

        val light = provider.getTheme(id)
        assertThat(near(light.colorKey, 0xFF123456.toInt())).isTrue()
        assertWithMessage("unset: the global theme's label").that(near(light.labelColor, ThemeSpecs.schemeColors(base).getValue(ThemeColorRole.LABEL))).isTrue()
        assertThat(near(light.colorKeyboardBackground, ThemeSpecs.schemeColors(base).getValue(ThemeColorRole.KEYBOARD_BACKGROUND))).isTrue()
        assertThat(light.colorKeyEnter).isEqualTo(0xFF0A84FF.toInt())
        assertWithMessage("no action colour: the special fill, as every runtime theme").that(light.colorKeyAction).isEqualTo(light.colorKeySpecial)
        assertThat(light.lookGeometry!!.cornerRadiusDp).isEqualTo(12f)
        assertThat(light.lookPillKeys).containsExactly(LookPillKey.ENTER)
        assertWithMessage("no preview and no pop-up colours: the popover keeps its non-look colours").that(light.lookPopupColors).isNull()
        assertThat(provider.getColorScheme(id)).isEqualTo(ThemeSpecs.render(ThemeSpecs.resolve(partial, ThemeSpecs.fromScheme(base), true)).scheme)
        assertThat(Config.builtInThemeStyleId(id, systemLight = true)).isEqualTo(R.style.CleverKeysLight)

        nightMode = Configuration.UI_MODE_NIGHT_YES
        assertWithMessage("dark mode: the <dark> variant").that(near(provider.getTheme(id).colorKey, 0xFF0F0F0F.toInt())).isTrue()
        assertThat(Config.builtInThemeStyleId(id, systemLight = false)).isEqualTo(R.style.CleverKeysDark)
    }

    // ── custom themes: version 2, save from a layout, export back ────────────────

    @Test
    fun saveAsCustomThemeKeepsEveryFieldAndTheExportReadsBackIdentically() {
        val provider = provider()
        val spec = example()
        val saved = LayoutThemeTransfer.saveAsCustomTheme(provider, spec, "Harbour", cyberpunk)!!
        assertWithMessage("stored in THE provider's store, so Theme Manager lists it")
            .that(provider.customThemeManager.customThemes.value.map { it.name }).containsExactly("Harbour")
        assertThat(provider.isAvailable("custom_${saved.id}")).isTrue()
        assertWithMessage("XML → custom theme: nothing lost").that(saved.fullSpec()).isEqualTo(spec)

        val exported = LayoutThemeTransfer.themeXml(provider, "custom_${saved.id}", null)!!
        val reparsed = KeyboardData.load_string_exn("<keyboard>\n$exported\n<row><key c=\"a\"/></row></keyboard>")
        assertThat(reparsed.style_problems).isEmpty()
        assertWithMessage("custom theme → XML: identical spec").that(reparsed.embedded_theme).isEqualTo(spec)

        // Reloaded from its stored JSON (a process restart): still version 2, still the same.
        val reloaded = CustomTheme.fromJson(JSONObject(saved.toJson().toString()))
        assertThat(reloaded).isEqualTo(saved)
        assertThat(saved.toJson().getInt("formatVersion")).isEqualTo(CustomTheme.FORMAT_VERSION_LOOK)
        assertWithMessage("an older build reads the 17 colours of a version-2 theme")
            .that(KeyboardColorSchemeHelper.fromJson(saved.toJson().getJSONObject("colors"))).isEqualTo(saved.colors)
    }

    @Test
    fun aPartialLayoutThemeSavedAsCustomFillsUnsetColoursFromTheGlobalTheme() {
        val provider = provider()
        val partial = ThemeSpec(
            colors = mapOf(ThemeColorRole.KEY to 0xFF123456.toInt(), ThemeColorRole.ACTION_KEY to 0xFF0A84FF.toInt()),
            light = mapOf(ThemeColorRole.LABEL to 0xFF000000.toInt()),
            geometry = LookGeometry(keyGapHorizontal = 0.1f),
        )
        val saved = LayoutThemeTransfer.saveAsCustomTheme(provider, partial, "Mine", cyberpunk)!!
        val full = saved.fullSpec()
        assertWithMessage("every field the layout defined, with its value")
            .that(full.colors.filterKeys { it in partial.colors }).isEqualTo(partial.colors)
        assertThat(full.light).isEqualTo(partial.light)
        assertThat(full.geometry).isEqualTo(partial.geometry)
        val base = ThemeSpecs.schemeColors(themeNeonCyberpunk().colorScheme)
        assertWithMessage("inherited DIY colours are now the theme's own")
            .that(full.colors[ThemeColorRole.LABEL]).isEqualTo(base[ThemeColorRole.LABEL])
        assertThat(full.colors.keys).containsAtLeastElementsIn(ThemeColorRole.DIY)
        assertWithMessage("a version-2 custom theme renders through its spec")
            .that(provider.getTheme("custom_${saved.id}").lookGeometry).isEqualTo(partial.geometry)
    }

    @Test
    fun aVersionOneCustomThemeKeepsItsJsonAndRenderingExactly() {
        val provider = provider()
        val v1 = CustomTheme(id = "abc", name = "Old", colors = themeNeonCyberpunk().colorScheme, createdAt = 1, modifiedAt = 2)
        val json = v1.toJson()
        assertThat(json.has("formatVersion")).isFalse()
        assertThat(json.has("look")).isFalse()
        assertThat(CustomTheme.fromJson(json)).isEqualTo(v1)
        provider.customThemeManager.saveCustomTheme(v1)
        val theme = provider.getTheme("custom_abc")
        assertWithMessage("no extension: the runtime theme as before (no look geometry, no look palette)")
            .that(theme.lookGeometry).isNull()
        assertThat(theme.lookPopupColors).isNull()
        assertThat(provider.appearanceOf("custom_abc")).isNull()
    }

    @Test
    fun anEditOfOnlyGeometryMakesTheKeyboardViewStale() {
        val provider = provider()
        val saved = LayoutThemeTransfer.saveAsCustomTheme(provider, example(), "Harbour", cyberpunk)!!
        val id = "custom_${saved.id}"
        val stamp = { KeyboardViewThemeStamp.of(5, id, { provider.appearanceOf(it) }) { provider.getColorScheme(it) } }
        val before = stamp()
        provider.customThemeManager.saveCustomTheme(
            saved.copy(extension = saved.extension!!.copy(geometry = saved.extension!!.geometry.copy(cornerRadiusDp = 3f)))
        )
        assertThat(stamp().runtimeColors).isEqualTo(before.runtimeColors)
        assertWithMessage("same colours, new radius: still a different view").that(stamp()).isNotEqualTo(before)
    }

    @Test
    fun exportingALayoutBoundToACustomThemeEmbedsItAndKeepsTheBinding() {
        val provider = provider()
        val saved = LayoutThemeTransfer.saveAsCustomTheme(provider, example(), "Harbour", cyberpunk)!!
        val xml = "<keyboard name=\"B\" theme=\"custom_${saved.id}\">\n  <row><key c=\"a\"/></row>\n</keyboard>\n"
        val out = LayoutThemeTransfer.layoutXmlWithCustomTheme(provider, xml, "custom_${saved.id}")
        val parsed = KeyboardData.load_string_exn(out)
        assertThat(parsed.declared_theme).isEqualTo("custom_${saved.id}")
        assertThat(parsed.embedded_theme).isEqualTo(saved.fullSpec())
        assertThat(LayoutThemeTransfer.layoutXmlWithCustomTheme(provider, xml, "rosepine")).isEqualTo(xml)
        assertThat(LayoutThemeTransfer.layoutXmlWithCustomTheme(provider, xml, null)).isEqualTo(xml)
    }
}
