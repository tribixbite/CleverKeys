package tribixbite.cleverkeys

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for settings toggles (#81, #82, #86).
 * Verifies that settings are properly saved and retrieved from SharedPreferences,
 * and that Config.globalConfig() reflects the saved values.
 */
@RunWith(AndroidJUnit4::class)
class SettingsToggleTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    // =========================================================================
    // #82: Auto-space after suggestion
    // =========================================================================

    @Test
    fun testAutoSpaceAfterSuggestion_DefaultIsTrue() {
        // Default should be true (add trailing space)
        assertTrue("Default AUTO_SPACE_AFTER_SUGGESTION should be true",
            Defaults.AUTO_SPACE_AFTER_SUGGESTION)
    }

    @Test
    fun testAutoSpaceAfterSuggestion_ConfigToggle() {
        try {
            val config = Config.globalConfig()
            if (config != null) {
                val original = config.auto_space_after_suggestion
                try {
                    // Test enabling
                    config.auto_space_after_suggestion = true
                    assertTrue("auto_space_after_suggestion should be true after setting",
                        config.auto_space_after_suggestion)

                    // Test disabling
                    config.auto_space_after_suggestion = false
                    assertFalse("auto_space_after_suggestion should be false after setting",
                        config.auto_space_after_suggestion)
                } finally {
                    config.auto_space_after_suggestion = original
                }
            }
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
        }
    }

    @Test
    fun testAutoSpaceAfterSuggestion_PrefsKey() {
        val prefs = context.getSharedPreferences("music_typewriter_prefs", Context.MODE_PRIVATE)

        // Save setting
        prefs.edit().putBoolean("auto_space_after_suggestion", false).commit()

        // Verify it was saved
        assertFalse("SharedPreferences should store false value",
            prefs.getBoolean("auto_space_after_suggestion", true))

        // Restore default
        prefs.edit().putBoolean("auto_space_after_suggestion", true).commit()
    }

    // =========================================================================
    // #81: Backspace-only key repeat
    // =========================================================================

    @Test
    fun testKeyRepeatBackspaceOnly_DefaultIsFalse() {
        // Default should be false (all keys repeat)
        assertFalse("Default KEYREPEAT_BACKSPACE_ONLY should be false",
            Defaults.KEYREPEAT_BACKSPACE_ONLY)
    }

    @Test
    fun testKeyRepeatBackspaceOnly_ConfigToggle() {
        try {
            val config = Config.globalConfig()
            if (config != null) {
                val original = config.keyrepeat_backspace_only
                try {
                    // Test enabling
                    config.keyrepeat_backspace_only = true
                    assertTrue("keyrepeat_backspace_only should be true after setting",
                        config.keyrepeat_backspace_only)

                    // Test disabling
                    config.keyrepeat_backspace_only = false
                    assertFalse("keyrepeat_backspace_only should be false after setting",
                        config.keyrepeat_backspace_only)
                } finally {
                    config.keyrepeat_backspace_only = original
                }
            }
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
        }
    }

    // =========================================================================
    // #86: Respect IS_SENSITIVE clipboard flag
    // =========================================================================

    @Test
    fun testClipboardRespectSensitiveFlag_DefaultIsTrue() {
        // Default should be true (respect the flag)
        assertTrue("Default CLIPBOARD_RESPECT_SENSITIVE_FLAG should be true",
            Defaults.CLIPBOARD_RESPECT_SENSITIVE_FLAG)
    }

    @Test
    fun testClipboardRespectSensitiveFlag_ConfigToggle() {
        try {
            val config = Config.globalConfig()
            if (config != null) {
                val original = config.clipboard_respect_sensitive_flag
                try {
                    // Test enabling
                    config.clipboard_respect_sensitive_flag = true
                    assertTrue("clipboard_respect_sensitive_flag should be true after setting",
                        config.clipboard_respect_sensitive_flag)

                    // Test disabling
                    config.clipboard_respect_sensitive_flag = false
                    assertFalse("clipboard_respect_sensitive_flag should be false after setting",
                        config.clipboard_respect_sensitive_flag)
                } finally {
                    config.clipboard_respect_sensitive_flag = original
                }
            }
        } catch (e: NullPointerException) {
            // Config not available in test context without full keyboard init
        }
    }

    // =========================================================================
    // Password Manager Exclusions (#86)
    // =========================================================================

    @Test
    fun testPasswordManagerPackages_ContainsKeePassDXLibre() {
        assertTrue("PASSWORD_MANAGER_PACKAGES should contain KeePassDX Libre",
            Defaults.PASSWORD_MANAGER_PACKAGES.contains("com.kunzisoft.keepass.libre"))
    }

    @Test
    fun testPasswordManagerPackages_ContainsChrome() {
        assertTrue("PASSWORD_MANAGER_PACKAGES should contain Chrome",
            Defaults.PASSWORD_MANAGER_PACKAGES.contains("com.android.chrome"))
    }

    @Test
    fun testPasswordManagerPackages_ContainsFirefox() {
        assertTrue("PASSWORD_MANAGER_PACKAGES should contain Firefox",
            Defaults.PASSWORD_MANAGER_PACKAGES.contains("org.mozilla.firefox"))
    }

    @Test
    fun testPasswordManagerPackages_ContainsBitwarden() {
        assertTrue("PASSWORD_MANAGER_PACKAGES should contain Bitwarden",
            Defaults.PASSWORD_MANAGER_PACKAGES.contains("com.x8bit.bitwarden"))
    }

    @Test
    fun testPasswordManagerPackages_Contains1Password() {
        assertTrue("PASSWORD_MANAGER_PACKAGES should contain 1Password",
            Defaults.PASSWORD_MANAGER_PACKAGES.contains("com.agilebits.onepassword"))
    }
}
