package tribixbite.cleverkeys

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.prefs.LayoutsPreference
import tribixbite.cleverkeys.prefs.ListGroupPreference
import tribixbite.cleverkeys.theme.ThemeProvider

/**
 * Per-layout THEME binding through the real Config, ConfigurationManager, layouts-preference
 * serializer and layout XML parser (mock tier: android.util.Xml is a stub, so the functional
 * kxml2 parser is substituted, exactly as LayoutLanguageBindingConfigTest does).
 */
class LayoutThemeBindingConfigTest {

    private val objenesis = ObjenesisStd()

    /** Stand-ins for R.style ids: distinct per theme name, never 0 (0 = "no theme"). */
    private val styleIds = mapOf("dark" to 11, "rosepine" to 12, "cobalt" to 13, "decorative_neon_cyberpunk" to 11)
    private val styleIdOf: (String) -> Int = { styleIds[it] ?: 99 }

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
    }

    @After
    fun teardown() {
        Config.themeAvailability = null
        unmockkAll()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    /** A Config with no constructor run, seeded with the fields theme resolution reads. */
    private fun config(themeBindings: List<String?>, globalTheme: String = "dark"): Config {
        val config = objenesis.newInstance(Config::class.java)
        setField(config, "_prefs", MockSharedPreferences())
        config.layouts = themeBindings.map { null }
        setField(config, "layout_languages", themeBindings.map { null })
        setField(config, "layout_themes", themeBindings)
        setField(config, "user_theme_name", globalTheme)
        setField(config, "user_primary_language", "en")
        setField(config, "user_secondary_language", "none")
        config.primary_language = "en"
        config.themeName = globalTheme
        config.theme = styleIdOf(globalTheme)
        config.swipe_trail_effect = "glow"
        config.swipe_engine_mode = "ctc"
        return config
    }

    // ── Config: the effective theme follows the current layout ───────────────

    @Test
    fun theEffectiveThemeFollowsTheCurrentLayoutAndNeverRewritesTheGlobalPreference() {
        val config = config(listOf(null, "rosepine"))
        val prefs = MockSharedPreferences()
        setField(config, "_prefs", prefs)
        prefs.putString("theme", "dark")

        config.set_current_layout(1)
        assertWithMessage(
            "set_current_layout must leave the theme to the refresh that follows its preference write — " +
                "otherwise ConfigurationManager sees no change and never re-inflates the keyboard"
        ).that(config.themeName).isEqualTo("dark")

        assertThat(config.recomputeActiveTheme(styleIdOf)).isTrue()
        assertThat(config.themeName).isEqualTo("rosepine")
        assertThat(config.theme).isEqualTo(12)
        assertThat(config.layout_bound_theme).isEqualTo("rosepine")
        assertWithMessage("the binding is an override; the user's global theme preference is untouched")
            .that(prefs.getString("theme", null)).isEqualTo("dark")

        config.set_current_layout(0)
        assertThat(config.recomputeActiveTheme(styleIdOf)).isTrue()
        assertThat(config.themeName).isEqualTo("dark")
        assertThat(config.layout_bound_theme).isNull()
    }

    @Test
    fun aBindingToAMissingDiyThemeRendersTheGlobalThemeAndIsReported() {
        Config.themeAvailability = { it != "custom_gone" }
        val config = config(listOf("custom_gone"), globalTheme = "cobalt")

        config.recomputeActiveTheme(styleIdOf)

        assertThat(config.themeName).isEqualTo("cobalt")
        assertThat(config.layout_bound_theme).isNull()
        assertThat(config.layout_missing_theme).isEqualTo("custom_gone")
    }

    // ── ConfigurationManager: re-inflate exactly when the effective theme changes ──

    private class CountingListener : ConfigChangeListener {
        var themeChanges = 0
        override fun onConfigChanged(newConfig: Config) {}
        override fun onThemeChanged(oldTheme: Int, newTheme: Int) { themeChanges++ }
    }

    /** A manager whose Config.refresh runs only the real theme resolution. */
    private fun manager(config: Config): Pair<Config, ConfigurationManager> {
        val spy = spyk(config)
        every { spy.refresh(any(), any()) } answers { spy.recomputeActiveTheme(styleIdOf); Unit }
        val tracker = mockk<FoldStateTracker>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        return spy to ConfigurationManager(context, spy, tracker)
    }

    @Test
    fun switchingOntoABoundLayoutAndBackReinflatesBothTimes() {
        val (config, manager) = manager(config(listOf(null, "rosepine")))
        val listener = CountingListener().also { manager.registerConfigChangeListener(it) }
        val res = mockk<Resources>(relaxed = true)

        config.set_current_layout(1); manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(1)
        assertThat(config.themeName).isEqualTo("rosepine")

        config.set_current_layout(0); manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(2)
        assertThat(config.themeName).isEqualTo("dark")
    }

    @Test
    fun switchingBetweenLayoutsWithTheSameEffectiveThemeDoesNotReinflate() {
        // Layout 0 follows the global theme "dark", layout 1 is bound to "dark" itself and
        // layouts 2/3 share "rosepine": none of these switches changes what is rendered.
        val (config, manager) = manager(config(listOf(null, "dark", "rosepine", "rosepine")))
        val listener = CountingListener().also { manager.registerConfigChangeListener(it) }
        val res = mockk<Resources>(relaxed = true)

        config.set_current_layout(1); manager.refresh(res)
        assertWithMessage("unbound → bound to the global theme: same effective theme, no re-inflate")
            .that(listener.themeChanges).isEqualTo(0)

        config.set_current_layout(2); manager.refresh(res)
        assertThat(listener.themeChanges).isEqualTo(1)

        config.set_current_layout(3); manager.refresh(res)
        assertWithMessage("two layouts bound to the same theme: no re-inflate")
            .that(listener.themeChanges).isEqualTo(1)
    }

    @Test
    fun aRuntimeThemeSharingTheSameStyleIdStillReinflates() {
        // decorative_* themes share the CleverKeysDark style id with "dark" here; the name differs.
        val (config, manager) = manager(config(listOf(null, "decorative_neon_cyberpunk")))
        val listener = CountingListener().also { manager.registerConfigChangeListener(it) }

        config.set_current_layout(1); manager.refresh(mockk(relaxed = true))

        assertThat(config.theme).isEqualTo(styleIdOf("dark"))
        assertThat(listener.themeChanges).isEqualTo(1)
    }

    // ── serializer: the binding rides in the layouts entry (backup blob) ──────────

    private val serializer = LayoutsPreference.SERIALIZER

    @Test
    fun unboundEntriesSerializeExactlyAsBefore() {
        assertThat(serializer.saveItem(LayoutsPreference.NamedLayout("latn_qwerty_us"))).isEqualTo("latn_qwerty_us")
        assertThat((serializer.saveItem(LayoutsPreference.SystemLayout()) as JSONObject).toString())
            .isEqualTo("{\"kind\":\"system\"}")
        val custom = serializer.saveItem(LayoutsPreference.CustomLayout.parse(THEMED_XML)) as JSONObject
        assertThat(custom.has("theme")).isFalse()
    }

    @Test
    fun themeAndLanguageBindingsRoundTripThroughTheLayoutsPreferenceString() {
        val items = listOf(
            LayoutsPreference.NamedLayout("latn_qwerty_us", theme = "rosepine"),
            LayoutsPreference.CustomLayout.parse(THEMED_XML, language = "fa", theme = "none"),
            LayoutsPreference.SystemLayout(language = "de", theme = "custom_3F2504E0-4F89"),
            LayoutsPreference.NamedLayout("latn_azerty_fr"),
        )
        // This string is exactly what Backup & Restore exports and imports for "layouts".
        val stored = ListGroupPreference.saveToString(items, serializer)
        val restored = ListGroupPreference.loadFromString(stored, serializer)!!

        assertThat(restored.map { it.theme })
            .containsExactly("rosepine", LayoutThemeBinding.UNBOUND, "custom_3F2504E0-4F89", null).inOrder()
        assertThat(restored.map { it.language }).containsExactly(null, "fa", "de", null).inOrder()
        assertThat(restored[3]).isEqualTo(LayoutsPreference.NamedLayout("latn_azerty_fr"))
    }

    @Test
    fun anImportedBackupWithAHostileThemeIsSanitisedNotTrusted() {
        val hostile = """[{"kind":"named","name":"latn_qwerty_us","theme":"x\" onload=\"y"},""" +
            """{"kind":"system","theme":"NONE"}]"""
        val restored = ListGroupPreference.loadFromString(hostile, serializer)!!

        assertWithMessage("an invalid id is dropped, the layout itself survives")
            .that(restored[0]).isEqualTo(LayoutsPreference.NamedLayout("latn_qwerty_us"))
        assertThat(restored[1].theme).isEqualTo(LayoutThemeBinding.UNBOUND)
    }

    @Test
    fun withLanguageKeepsTheThemeAndWithThemeKeepsTheLanguage() {
        val entry = LayoutsPreference.CustomLayout.parse(THEMED_XML, language = "fa", theme = "cobalt")
        assertThat(entry.withLanguage("de").theme).isEqualTo("cobalt")
        assertThat(entry.withTheme("dark").language).isEqualTo("fa")
    }

    // ── layout XML: the `theme` attribute is the default binding ──────────────

    @Test
    fun theXmlThemeAttributeIsTheDefaultBindingAndTheUserChoiceOverridesIt() {
        assertThat(KeyboardData.load_string_exn(THEMED_XML).declared_theme).isEqualTo("rosepine")
        assertThat(KeyboardData.load_string_exn(PLAIN_XML).declared_theme).isNull()

        val prefs = MockSharedPreferences()
        val editor = prefs.edit()
        LayoutsPreference.saveToPreferences(
            editor,
            listOf(
                LayoutsPreference.CustomLayout.parse(THEMED_XML),                   // imported: XML default
                LayoutsPreference.CustomLayout.parse(THEMED_XML, theme = "none"),   // explicit follow-global
                LayoutsPreference.CustomLayout.parse(THEMED_XML, theme = "cobalt"), // override
                LayoutsPreference.CustomLayout.parse(PLAIN_XML),                    // no theme at all
                LayoutsPreference.SystemLayout(),
            )
        )
        editor.commit()

        val loaded = LayoutsPreference.loadLayoutsWithBindings(mockk<Resources>(relaxed = true), prefs)
        assertThat(loaded.layouts).hasSize(5)
        assertThat(loaded.themes).containsExactly("rosepine", null, "cobalt", null, null).inOrder()
        assertWithMessage("the language bindings are unchanged by the theme binding")
            .that(loaded.languages).containsExactly(null, null, null, null, null).inOrder()
    }

    @Test
    fun anInvalidXmlThemeNeverHidesTheLayout() {
        val parsed = KeyboardData.load_string_exn(THEMED_XML.replace("theme=\"rosepine\"", "theme=\"rose pine\""))
        assertThat(parsed.rows).isNotEmpty()
        assertWithMessage("the editor uses this to refuse saving; loading stays lenient")
            .that(LayoutThemeBinding.xmlThemeProblem(parsed.declared_theme)).isTrue()
    }

    // ── drift: the built-in ids availability checks are the Theme Manager's ids ──

    @Test
    fun builtInThemeIdsMatchTheThemeManagerList() {
        assertThat(ThemeProvider.BUILT_IN_THEME_IDS)
            .containsExactlyElementsIn(ThemeSettingsActivity.BUILTIN_THEMES.map { it.id })
    }

    private companion object {
        const val THEMED_XML =
            "<keyboard name=\"Themed test\" script=\"latin\" theme=\"rosepine\">" +
                "<row><key key0=\"a\"/><key key0=\"b\"/></row>" +
                "</keyboard>"
        const val PLAIN_XML =
            "<keyboard name=\"Plain test\" script=\"latin\">" +
                "<row><key key0=\"a\"/></row>" +
                "</keyboard>"
    }
}
