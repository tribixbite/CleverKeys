package tribixbite.cleverkeys

import android.content.res.Resources
import android.util.Log
import android.util.Xml
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.objenesis.ObjenesisStd
import tribixbite.cleverkeys.prefs.ListGroupPreference
import tribixbite.cleverkeys.prefs.LayoutsPreference

/**
 * GH #186 / GH #61 — per-layout language binding through the real Config, layouts-preference
 * serializer and layout XML parser (mock tier: android.util.Xml is a stub, so the functional
 * kxml2 parser is substituted, the same way NumpadKeySizeTest drives production parsing).
 */
class LayoutLanguageBindingConfigTest {

    private val objenesis = ObjenesisStd()

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
    fun teardown() = unmockkAll()

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    /**
     * A Config with no constructor run, seeded with exactly the fields the language
     * resolution and the snapshot publication read.
     */
    private fun config(
        bindings: List<String?>,
        userPrimary: String = "en",
        multilang: Boolean = true,
        userSecondary: String = "fa",
    ): Config {
        val config = objenesis.newInstance(Config::class.java)
        setField(config, "_prefs", MockSharedPreferences())
        config.layouts = bindings.map { null }
        setField(config, "layout_languages", bindings)
        setField(config, "user_primary_language", userPrimary)
        setField(config, "user_secondary_language", userSecondary)
        config.enable_multilang = multilang
        config.primary_language = userPrimary
        config.themeName = "dark"
        config.swipe_trail_effect = "glow"
        config.swipe_engine_mode = "ctc"
        return config
    }

    // ── Config: the layout switch is what changes the active language ─────────────

    @Test
    fun switchingToALayoutBoundToFaMakesFaThePredictionAndAutocorrectLanguage() {
        // #186 setup: EN primary + FA secondary, a Latin layout and a Persian layout.
        val config = config(bindings = listOf(null, "fa"))

        config.set_current_layout(1) // what switch_forward does through LayoutManager

        assertWithMessage(
            "the active primary is the language WordPredictor loads as its main dictionary — " +
                "the only one autoCorrect consults — so it must be fa on the Persian layout"
        ).that(config.primary_language).isEqualTo("fa")
        assertThat(config.active_secondary_language).isNull()
        assertThat(config.layout_bound_language).isEqualTo("fa")
        assertWithMessage("gesture hot paths read the snapshot; it must not lag the switch")
            .that(config.snapshot.primary_language).isEqualTo("fa")
        assertThat(config.activeLanguages()).isEqualTo(ActiveLanguages("fa", null, "fa"))
    }

    @Test
    fun switchingBackToAnUnboundLayoutRestoresThePreferenceLanguages() {
        val config = config(bindings = listOf(null, "fa"))
        config.set_current_layout(1)

        config.set_current_layout(0)

        assertThat(config.activeLanguages()).isEqualTo(ActiveLanguages("en", "fa", null))
        assertThat(config.snapshot.primary_language).isEqualTo("en")
    }

    @Test
    fun unboundLayoutsKeepTheCurrentLanguages() {
        val config = config(bindings = listOf(null, null), multilang = false)

        config.set_current_layout(1)

        assertThat(config.activeLanguages()).isEqualTo(ActiveLanguages("en", null, null))
    }

    // ── serializer: backup export/import of the binding ────────────────────────

    private val serializer = LayoutsPreference.SERIALIZER

    @Test
    fun unboundEntriesSerializeExactlyAsBefore() {
        assertThat(serializer.saveItem(LayoutsPreference.NamedLayout("latn_qwerty_us")))
            .isEqualTo("latn_qwerty_us")
        val system = serializer.saveItem(LayoutsPreference.SystemLayout()) as JSONObject
        assertThat(system.toString()).isEqualTo("{\"kind\":\"system\"}")
        val custom = serializer.saveItem(LayoutsPreference.CustomLayout.parse(PERSIAN_XML)) as JSONObject
        assertThat(custom.has("language")).isFalse()
    }

    @Test
    fun bindingsRoundTripThroughTheLayoutsPreferenceString() {
        val items = listOf(
            LayoutsPreference.NamedLayout("latn_qwerty_us", language = "en"),
            LayoutsPreference.CustomLayout.parse(PERSIAN_XML, language = "fa"),
            LayoutsPreference.SystemLayout(language = "de"),
            LayoutsPreference.NamedLayout("latn_azerty_fr"),
        )
        // This string is exactly what Backup & Restore exports and imports for "layouts".
        val stored = ListGroupPreference.saveToString(items, serializer)
        val restored = ListGroupPreference.loadFromString(stored, serializer)!!

        assertThat(restored.map { it.language }).containsExactly("en", "fa", "de", null).inOrder()
        assertThat((restored[0] as LayoutsPreference.NamedLayout).name).isEqualTo("latn_qwerty_us")
        assertThat((restored[1] as LayoutsPreference.CustomLayout).xml).isEqualTo(PERSIAN_XML)
        assertThat(restored[3]).isEqualTo(LayoutsPreference.NamedLayout("latn_azerty_fr"))
    }

    @Test
    fun anImportedBackupWithAnInvalidLanguageIsSanitisedNotTrusted() {
        val hostile = """[{"kind":"named","name":"latn_qwerty_us","language":"../../x"},""" +
            """{"kind":"system","language":"NONE"}]"""
        val restored = ListGroupPreference.loadFromString(hostile, serializer)!!

        assertWithMessage("an invalid code is dropped, the layout itself survives")
            .that(restored[0]).isEqualTo(LayoutsPreference.NamedLayout("latn_qwerty_us"))
        assertThat(restored[1].language).isEqualTo(LayoutLanguageBinding.UNBOUND)
    }

    // ── layout XML: the `language` attribute is the default binding ─────────────

    @Test
    fun theXmlLanguageAttributeIsTheDefaultBindingAndTheUserChoiceOverridesIt() {
        val parsed = KeyboardData.load_string_exn(PERSIAN_XML)
        assertThat(parsed.declared_language).isEqualTo("FA")

        val prefs = MockSharedPreferences()
        val editor = prefs.edit()
        LayoutsPreference.saveToPreferences(
            editor,
            listOf(
                LayoutsPreference.CustomLayout.parse(PERSIAN_XML),                  // XML default
                LayoutsPreference.CustomLayout.parse(PERSIAN_XML, language = "none"), // explicit off
                LayoutsPreference.CustomLayout.parse(PERSIAN_XML, language = "ar"),   // override
                LayoutsPreference.SystemLayout(),                                     // unbound
            )
        )
        editor.commit()

        val (layouts, bindings) = LayoutsPreference.loadLayoutsWithBindings(mockk<Resources>(relaxed = true), prefs)
        assertThat(layouts).hasSize(4)
        assertThat(bindings).containsExactly("fa", null, "ar", null).inOrder()
    }

    @Test
    fun anInvalidXmlLanguageNeverHidesTheLayout() {
        val xml = PERSIAN_XML.replace("language=\"FA\"", "language=\"persian\"")
        val parsed = KeyboardData.load_string_exn(xml)

        assertThat(parsed.rows).isNotEmpty()
        assertThat(parsed.declared_language).isEqualTo("persian")
        assertWithMessage("the editor uses this to refuse saving; loading stays lenient")
            .that(LayoutLanguageBinding.xmlLanguageProblem(parsed.declared_language)).isTrue()
        assertThat(LayoutLanguageBinding.xmlLanguageProblem(KeyboardData.load_string_exn(PERSIAN_XML).declared_language)).isFalse()
    }

    private companion object {
        const val PERSIAN_XML =
            "<keyboard name=\"Persian test\" script=\"persian\" language=\"FA\">" +
                "<row><key key0=\"ض\"/><key key0=\"ص\"/><key key0=\"ث\"/></row>" +
                "</keyboard>"
    }
}
