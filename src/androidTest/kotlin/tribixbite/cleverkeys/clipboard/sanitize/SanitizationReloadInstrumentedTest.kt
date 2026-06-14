package tribixbite.cleverkeys.clipboard.sanitize

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.DirectBootAwarePreferences

/**
 * Regression test for the "clipboard URL sanitizer does nothing until restart" bug.
 *
 * Root cause: toggling "Sanitize tracking parameters" persists the pref and broadcasts a
 * rules-changed signal that drops the cached sanitizer, but the in-memory [Config] field was
 * never refreshed — so [SanitizationConfig.build] re-read the stale startup value (`false`)
 * and rebuilt a no-op sanitizer. It only worked after a full keyboard restart (when
 * `Config.refresh()` re-read prefs).
 *
 * Fix: the rules-changed receiver in `ClipboardHistoryService` now calls
 * [Config.reloadSanitizationSettings] before `rebuild()`. This test exercises that exact
 * path (reload → rebuild → build) against a real SharedPreferences-backed Config and the
 * real bundled ClearURLs ruleset, asserting sanitization applies mid-session.
 *
 * (The one-line receiver call that invokes the reload is verified by inspection, consistent
 * with the convention documented in `SanitizationConfigBuildTest`.)
 */
@RunWith(AndroidJUnit4::class)
class SanitizationReloadInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        // Re-init the global Config from the app's real prefs so we don't leave a
        // test-prefs-backed singleton behind for other instrumented tests in the suite.
        Config.initGlobalConfig(
            DirectBootAwarePreferences.get_shared_preferences(context),
            context.resources, null, null
        )
    }

    @Test
    fun togglingSanitizeOnMidSession_appliesSanitizationWithoutRestart() {
        val prefs = context.getSharedPreferences("sanitize_reload_test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("clipboard_sanitize_links_enabled", false)
            .commit()

        // Simulate app startup with the toggle OFF.
        Config.initGlobalConfig(prefs, context.resources, null, null)
        assertFalse(
            "Precondition: sanitize toggle starts OFF",
            Config.globalConfig().clipboard_sanitize_links_enabled
        )

        val sanitizer = SanitizationConfig(context)
        val url = "https://example.com/p?utm_source=foo&keep=bar"

        // OFF → URL passes through unchanged (no-op sanitizer).
        assertEquals(url, sanitizer.sanitizer().process(url))

        // User flips the toggle ON in settings (persists pref); the rules-changed receiver
        // then fires. Reproduce exactly that receiver path:
        prefs.edit().putBoolean("clipboard_sanitize_links_enabled", true).commit()
        Config.globalConfig().reloadSanitizationSettings()
        sanitizer.rebuild()

        // ON → tracking param stripped, real param retained, WITHOUT a keyboard restart.
        val result = sanitizer.sanitizer().process(url)
        assertFalse("utm_source must be stripped once the toggle is on: $result",
            result.contains("utm_source"))
        assertTrue("non-tracking params must be preserved: $result",
            result.contains("keep=bar"))
        assertTrue("base URL must be preserved: $result",
            result.startsWith("https://example.com/p"))
    }

    @Test
    fun systemClipboardRewrite_defaultsOn_andReloadsMidSession() {
        val prefs = context.getSharedPreferences("sanitize_sysclip_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Fresh install (key never written) → default ON, matching SettingsDefaults + Config.kt.
        Config.initGlobalConfig(prefs, context.resources, null, null)
        assertTrue(
            "clipboard_sanitize_system_clipboard must default to true",
            Config.globalConfig().clipboard_sanitize_system_clipboard
        )

        // User turns the toggle OFF in settings (persists pref); the rules-changed receiver
        // path then reloads the live Config without a keyboard restart.
        prefs.edit().putBoolean("clipboard_sanitize_system_clipboard", false).commit()
        Config.globalConfig().reloadSanitizationSettings()
        assertFalse(
            "reloadSanitizationSettings must pick up the persisted OFF value mid-session",
            Config.globalConfig().clipboard_sanitize_system_clipboard
        )
    }
}
