package tribixbite.cleverkeys

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * 2026-11-26: a stored secondary whose dictionary is not on this phone (a restored backup naming
 * a pack never installed here) must be no secondary for the keyboard, resolved at READ time
 * through [Config.languageAvailability]: the preference is never written back, and once the pack
 * is installed the language is active again with no setting change.
 *
 * Mock tier, through the real Config resolution (`set_current_layout`, which every layout switch
 * and the preference refresh reach), seeded like [LayoutLanguageBindingConfigTest].
 */
class ConfigUnavailableSecondaryTest {

    private val objenesis = ObjenesisStd()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        Config.languageAvailability = null
        unmockkAll()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        Config::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun config(prefs: MockSharedPreferences, bindings: List<String?> = listOf(null)): Config {
        val config = objenesis.newInstance(Config::class.java)
        setField(config, "_prefs", prefs)
        config.layouts = bindings.map { null }
        setField(config, "layout_languages", bindings)
        setField(config, "user_primary_language", "de")
        setField(config, "user_secondary_language", "ru")
        config.enable_multilang = true
        config.primary_language = "de"
        // As resolved before the check: the stored secondary active (a Config built before the
        // availability was installed, or before the pack went missing).
        config.active_secondary_language = "ru"
        config.themeName = "dark"
        config.swipe_trail_effect = "glow"
        config.swipe_engine_mode = "ctc"
        return config
    }

    @Test
    fun aSecondaryWithNoDictionaryIsNoSecondaryAndThePreferenceIsKept() {
        val installed = mutableSetOf("en", "de")
        Config.languageAvailability = { it in installed }
        val prefs = MockSharedPreferences()
        val config = config(prefs)

        config.set_current_layout(0)

        assertThat(config.activeLanguages()).isEqualTo(ActiveLanguages("de", null, null))
        assertWithMessage("gesture hot paths read the snapshot").that(config.snapshot.active_secondary_language).isNull()
        assertWithMessage("resolved at read time: the stored choice is not written back")
            .that(prefs.getString(LanguagePrefs.SECONDARY, null)).isNull()
    }

    @Test
    fun thePackInstalledLaterMakesTheLanguageActiveWithNoSettingChange() {
        val installed = mutableSetOf("en", "de")
        Config.languageAvailability = { it in installed }
        val config = config(MockSharedPreferences())
        config.set_current_layout(0)

        installed += "ru"
        config.set_current_layout(0) // the next resolution (any refresh or layout switch)

        assertThat(config.activeLanguages()).isEqualTo(ActiveLanguages("de", "ru", null))
    }

    @Test
    fun withNoAvailabilityInstalledEveryLanguageCounts() {
        val config = config(MockSharedPreferences())

        config.set_current_layout(0)

        assertThat(config.active_secondary_language).isEqualTo("ru")
    }

    @Test
    fun aBoundLayoutsPurgeDoesNotWaitForAnUnavailableSecondary() {
        Config.languageAvailability = { it != "ru" }
        val config = config(MockSharedPreferences(), bindings = listOf("fa", null))
        config.set_current_layout(0)

        assertWithMessage("the primary's store would wait for a lexicon that cannot load")
            .that(config.purgeSecondaryFor("de")).isNull()
    }
}
