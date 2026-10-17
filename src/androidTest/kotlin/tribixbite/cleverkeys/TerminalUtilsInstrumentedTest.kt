package tribixbite.cleverkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TerminalUtils with real EditorInfo objects.
 * These tests require the Android framework (EditorInfo is an Android class)
 * and cannot run as pure JVM tests.
 *
 * Covers #113 (Termux paste) terminal app detection logic.
 */
@RunWith(AndroidJUnit4::class)
class TerminalUtilsInstrumentedTest {

    /**
     * An ordinary text field in [pkg]. The input type is explicit because a bare `EditorInfo()`
     * has `inputType == TYPE_NULL`, which `TerminalUtils.editorKind` has classified as a blind
     * REMOTE field since 68d91874 (2026-10-13) — that made every "not a terminal" case here
     * read true on every API level. Package detection must be tested on a real text field.
     */
    private fun textEditor(pkg: String?) = EditorInfo().apply {
        packageName = pkg
        inputType = InputType.TYPE_CLASS_TEXT
    }

    @Test
    fun typeNullEditorInOrdinaryPackageIsBlindRemoteField() {
        val blind = EditorInfo().apply { packageName = "com.android.chrome" }
        assertEquals(InputType.TYPE_NULL, blind.inputType)
        assertEquals(TerminalUtils.EditorKind.REMOTE, TerminalUtils.editorKind(blind))
        assertEquals(TerminalUtils.EditorKind.TEXT, TerminalUtils.editorKind(textEditor("com.android.chrome")))
    }

    @Test
    fun customPackagesRefreshFromRealPreferencesAndRemovalIsImmediate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("terminal_packages_instrumented", android.content.Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putString("custom_terminal_packages", "org.custom.shell,org.custom.shell").commit())
            Config.initGlobalConfig(prefs, context.resources, null, null)
            val config = Config.globalConfig()
            val custom = textEditor("org.custom.shell")
            assertTrue(TerminalUtils.isTerminalApp(custom, config.custom_terminal_packages))
            assertEquals(setOf("org.custom.shell"), config.custom_terminal_packages)
            assertFalse(TerminalUtils.isTerminalApp(textEditor("org.custom.shell.child"), config.custom_terminal_packages))
            assertTrue(prefs.edit().putString("custom_terminal_packages", "").commit())
            config.refresh(context.resources, null)
            assertFalse(TerminalUtils.isTerminalApp(custom, config.custom_terminal_packages))
            assertTrue(TerminalUtils.isTerminalApp(textEditor("com.termux"), config.custom_terminal_packages))
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }

    @Test
    fun malformedOrWrongTypeStoredPackageListDoesNotEnablePartialEntries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("terminal_packages_instrumented", android.content.Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putString("custom_terminal_packages", "org.custom.shell,com.*").commit())
            Config.initGlobalConfig(prefs, context.resources, null, null)
            val config = Config.globalConfig()
            assertTrue(config.custom_terminal_packages.isEmpty())
            assertTrue(prefs.edit().putInt("custom_terminal_packages", 7).commit())
            config.refresh(context.resources, null)
            assertTrue(config.custom_terminal_packages.isEmpty())
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }

    // =========================================================================
    // isTerminalApp — null / empty cases
    // =========================================================================

    @Test
    fun isTerminalApp_nullEditorInfo_returnsFalse() {
        assertFalse("null EditorInfo should not be a terminal app",
            TerminalUtils.isTerminalApp(null))
    }

    @Test
    fun isTerminalApp_nullPackageName_returnsFalse() {
        val info = textEditor(null)
        assertFalse("null packageName should not be a terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_emptyPackageName_returnsFalse() {
        val info = textEditor("")
        assertFalse("empty packageName should not be a terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    // =========================================================================
    // isTerminalApp — known terminal packages (direct match)
    // =========================================================================

    @Test
    fun isTerminalApp_termux_returnsTrue() {
        val info = textEditor("com.termux")
        assertTrue("com.termux should be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_connectBot_returnsTrue() {
        val info = textEditor("org.connectbot")
        assertTrue("org.connectbot should be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_juiceSSH_returnsTrue() {
        val info = textEditor("com.sonelli.juicessh")
        assertTrue("JuiceSSH should be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_termius_returnsTrue() {
        val info = textEditor("com.server.auditor.ssh.client")
        assertTrue("Termius should be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_jackpalAndroidTerm_returnsTrue() {
        val info = textEditor("jackpal.androidterm")
        assertTrue("jackpal.androidterm should be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_androidVirtualization_returnsTrue() {
        val info = textEditor("com.android.virtualization.terminal")
        assertTrue("Android Virtualization Framework terminal should be detected",
            TerminalUtils.isTerminalApp(info))
    }

    // =========================================================================
    // isTerminalApp — ecosystem/heuristic matches
    // =========================================================================

    @Test
    fun isTerminalApp_termuxNix_returnsTrue() {
        val info = textEditor("com.termux.nix")
        assertTrue("com.termux.nix should be detected (in KNOWN set)",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_termuxX11_returnsTrue() {
        // Matches via "termux" substring heuristic
        val info = textEditor("com.termux.x11")
        assertTrue("com.termux.x11 should be detected via termux heuristic",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_termuxTasker_returnsTrue() {
        val info = textEditor("com.termux.tasker")
        assertTrue("com.termux.tasker should be detected via termux heuristic",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_anotherTermRedist_returnsTrue() {
        val info = textEditor("green_green_avk.anotherterm.redist")
        assertTrue("Another Term should be detected (in KNOWN set)",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_anotherTermVariant_returnsTrue() {
        // Matches via "anotherterm" substring heuristic
        val info = textEditor("green_green_avk.anotherterm")
        assertTrue("anotherterm variant should be detected via heuristic",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_packageEndingInTerminal_returnsTrue() {
        // Heuristic: package ends with ".terminal"
        val info = textEditor("com.example.terminal")
        assertTrue("Package ending in .terminal should be detected",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_packageContainingTerminalDot_returnsTrue() {
        // Heuristic: package contains ".terminal."
        val info = textEditor("com.example.terminal.pro")
        assertTrue("Package containing .terminal. should be detected",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_terminalEmulatorPackage_returnsTrue() {
        // Heuristic: package contains ".terminalemulator"
        val info = textEditor("com.example.terminalemulator")
        assertTrue("Package with .terminalemulator should be detected",
            TerminalUtils.isTerminalApp(info))
    }

    // =========================================================================
    // isTerminalApp — negative cases (NOT terminal apps)
    // =========================================================================

    @Test
    fun isTerminalApp_chrome_returnsFalse() {
        val info = textEditor("com.android.chrome")
        assertFalse("Chrome should not be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_gmail_returnsFalse() {
        val info = textEditor("com.google.android.gm")
        assertFalse("Gmail should not be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_slack_returnsFalse() {
        val info = textEditor("com.slack")
        assertFalse("Slack should not be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_systemUI_returnsFalse() {
        val info = textEditor("com.android.systemui")
        assertFalse("SystemUI should not be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    @Test
    fun isTerminalApp_notes_returnsFalse() {
        val info = textEditor("com.google.android.keep")
        assertFalse("Google Keep should not be detected as terminal app",
            TerminalUtils.isTerminalApp(info))
    }

    // =========================================================================
    // #110: backspace_undo_swipe Config integration
    // =========================================================================

    @Test
    fun backspaceUndoSwipe_defaultIsTrue() {
        assertTrue("Default BACKSPACE_UNDO_SWIPE should be true",
            Defaults.BACKSPACE_UNDO_SWIPE)
    }

    @Test
    fun backspaceUndoSwipe_configToggle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Config must initialize before testing its toggle", TestConfigHelper.ensureConfigInitialized(context))
        val config = Config.globalConfig()
        val original = config.backspace_undo_swipe
        try {
            config.backspace_undo_swipe = true
            assertTrue("backspace_undo_swipe should be true after setting", config.backspace_undo_swipe)
            config.backspace_undo_swipe = false
            assertFalse("backspace_undo_swipe should be false after setting", config.backspace_undo_swipe)
        } finally {
            config.backspace_undo_swipe = original
        }
    }

    @Test
    fun backspaceUndoSwipe_prefsKey() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("terminal_undo_instrumented", android.content.Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putBoolean("backspace_undo_swipe", false).commit())
            assertFalse("SharedPreferences should store false value", prefs.getBoolean("backspace_undo_swipe", true))
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }
}
