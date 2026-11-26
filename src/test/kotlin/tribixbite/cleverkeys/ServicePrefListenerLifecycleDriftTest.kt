package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 2026-11-26 (debug-build log, `build/nov26c-saga/logcat-langchange-en.txt`): one primary-language
 * change loaded the typing contractions SIX times in 45 ms, each into a different
 * [ContractionManager] ("dropped 21" every time, so never the same instance), and five of the six
 * reloads logged "Cannot reload dictionary - WordPredictor not initialized". Those were retired
 * keyboard services: `onCreate` registers the service and its [ConfigurationManager] as preference
 * listeners and `onDestroy` never removed them. The framework keeps a retired service reachable
 * (the IME back-callback root of the memory analysis), so SharedPreferences' weakly held listener
 * stayed alive and every retired service refreshed its Config, ran [ActiveLanguageSync] and
 * reloaded its own contractions and dictionaries on every preference write.
 *
 * Source scan: a service lifecycle cannot be driven on the JVM. Pins that `onDestroy` releases
 * exactly what `onCreate` registered, through the one helper, on the preferences instance the
 * registration used.
 */
class ServicePrefListenerLifecycleDriftTest {

    private val service = SourceScan.read("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt")

    @Test
    fun onDestroyReleasesThePreferenceListenersOnCreateRegistered() {
        val onCreate = SourceScan.functionBody(service, "override fun onCreate")
        val onDestroy = SourceScan.functionBody(service, "override fun onDestroy")

        assertWithMessage("onCreate registers the ConfigurationManager as a preference listener")
            .that(onCreate).contains("prefs.registerOnSharedPreferenceChangeListener(_configManager)")
        assertWithMessage("onCreate registers the service itself as a preference listener")
            .that(onCreate).contains("prefs.registerOnSharedPreferenceChangeListener(this)")
        assertWithMessage("onCreate keeps the preferences it registered with, for onDestroy")
            .that(onCreate).contains("_listenerPrefs = prefs")
        assertWithMessage("onDestroy must release the preference listeners")
            .that(onDestroy).contains("releasePreferenceListeners()")

        val release = SourceScan.functionBody(service, "private fun releasePreferenceListeners")
        assertWithMessage("the ConfigurationManager listener is removed")
            .that(release).contains("unregisterOnSharedPreferenceChangeListener(_configManager)")
        assertWithMessage("the service listener is removed")
            .that(release).contains("unregisterOnSharedPreferenceChangeListener(this)")
        assertWithMessage("the service stops receiving Config refreshes (ActiveLanguageSync)")
            .that(release).contains("_configManager.unregisterConfigChangeListener(this)")
    }
}
