package tribixbite.cleverkeys

import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * MockK-based tests for TerminalUtils (#70 Termux mode).
 *
 * Tests terminal app detection logic: direct package matching,
 * ecosystem wildcard matching, pattern-based heuristics, and edge cases.
 */
class TerminalUtilsTest {

    @Test
    fun `custom packages match exactly and retain built-ins`() {
        val packages = (TerminalUtils.parseCustomPackages(" com.example.Remote ,\r\ncom.example.Remote\norg.my_shell.app,")
            as TerminalUtils.PackageListResult.Valid).packages
        assertThat(packages).containsExactly("com.example.Remote", "org.my_shell.app")
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.Remote"), packages)).isTrue()
        for (name in listOf("com.example.remote", "com.example.Remote.child", "com.example")) {
            assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage(name), packages)).isFalse()
        }
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("org.connectbot"), packages)).isTrue()
        assertThat(TerminalUtils.isTerminalApp(null, packages)).isFalse()
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage(null), packages)).isFalse()
    }

    @Test
    fun `invalid package rejects whole list without wildcard or partial matching`() {
        for (bad in listOf("termux", "com.*", "com..app", ".com.app", "com.app.", "com.1app", "com.foo-bar", "com.foo app")) {
            val result = TerminalUtils.parseCustomPackages("org.valid.app,$bad")
            assertThat(result).isInstanceOf(TerminalUtils.PackageListResult.Invalid::class.java)
        }
    }

    @Test
    fun `package input enforces count length and payload boundaries`() {
        val hundred = (1..100).joinToString("\n") { "org.app.p$it" }
        assertThat(TerminalUtils.parseCustomPackages(hundred)).isInstanceOf(TerminalUtils.PackageListResult.Valid::class.java)
        assertThat(TerminalUtils.parseCustomPackages("$hundred,org.extra.app")).isInstanceOf(TerminalUtils.PackageListResult.Invalid::class.java)
        val longest = "a." + "b".repeat(253)
        assertThat(TerminalUtils.parseCustomPackages(longest)).isInstanceOf(TerminalUtils.PackageListResult.Valid::class.java)
        assertThat(TerminalUtils.parseCustomPackages(longest + "b")).isInstanceOf(TerminalUtils.PackageListResult.Invalid::class.java)
        assertThat(TerminalUtils.parseCustomPackages(" ".repeat(TerminalUtils.MAX_PACKAGE_INPUT_LENGTH))).isInstanceOf(TerminalUtils.PackageListResult.Valid::class.java)
        assertThat(TerminalUtils.parseCustomPackages(" ".repeat(TerminalUtils.MAX_PACKAGE_INPUT_LENGTH + 1))).isInstanceOf(TerminalUtils.PackageListResult.Invalid::class.java)
    }

    @Test
    fun `empty list restores built-in behavior and snapshots cannot mutate`() {
        val empty = (TerminalUtils.parseCustomPackages(" , \r\n") as TerminalUtils.PackageListResult.Valid).packages
        assertThat(empty).isEmpty()
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.Remote"), empty)).isFalse()
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux"), empty)).isTrue()
        val packages = (TerminalUtils.parseCustomPackages("com.example.Remote") as TerminalUtils.PackageListResult.Valid).packages
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
            (packages as MutableSet<String>).add("org.other.app")
        }
    }

    private fun editorInfoWithPackage(pkg: String?): EditorInfo {
        // EditorInfo.packageName is a public Java FIELD, not a Kotlin property.
        // MockK's every{} only works for methods/getters, so we set the field directly.
        val ei = mockk<EditorInfo>(relaxed = true)
        ei.packageName = pkg
        return ei
    }

    // =========================================================================
    // Direct package matching
    // =========================================================================

    @Test
    fun `detects Termux as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux"))).isTrue()
    }

    @Test
    fun `detects ConnectBot as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("org.connectbot"))).isTrue()
    }

    @Test
    fun `detects JuiceSSH as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.sonelli.juicessh"))).isTrue()
    }

    @Test
    fun `detects Termius as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.server.auditor.ssh.client"))).isTrue()
    }

    @Test
    fun `detects jackpal terminal as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("jackpal.androidterm"))).isTrue()
    }

    @Test
    fun `detects Android Virtualization terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.android.virtualization.terminal"))).isTrue()
    }

    @Test
    fun `detects AnotherTerm redist as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("green_green_avk.anotherterm.redist"))).isTrue()
    }

    @Test
    fun `detects rk terminal as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.rk.terminal"))).isTrue()
    }

    // =========================================================================
    // Termux ecosystem wildcard matching
    // =========================================================================

    @Test
    fun `detects Termux Nix as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux.nix"))).isTrue()
    }

    @Test
    fun `detects Termux X11 as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux.x11"))).isTrue()
    }

    @Test
    fun `detects Termux Tasker as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux.tasker"))).isTrue()
    }

    @Test
    fun `detects Termux API as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.termux.api"))).isTrue()
    }

    @Test
    fun `detects any package containing termux`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("org.custom.termux.fork"))).isTrue()
    }

    // =========================================================================
    // AnotherTerm ecosystem
    // =========================================================================

    @Test
    fun `detects AnotherTerm ecosystem packages`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("green_green_avk.anotherterm"))).isTrue()
    }

    @Test
    fun `detects AnotherTerm plugin packages`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.anotherterm.plugin"))).isTrue()
    }

    // =========================================================================
    // Pattern-based terminal detection
    // =========================================================================

    @Test
    fun `detects package ending with dot terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.terminal"))).isTrue()
    }

    @Test
    fun `detects package with dot terminal dot in middle`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.terminal.pro"))).isTrue()
    }

    @Test
    fun `detects package with terminalemulator`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.terminalemulator"))).isTrue()
    }

    // =========================================================================
    // Non-terminal apps (false positive prevention)
    // =========================================================================

    @Test
    fun `does not detect Chrome as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.android.chrome"))).isFalse()
    }

    @Test
    fun `does not detect Gmail as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.google.android.gm"))).isFalse()
    }

    @Test
    fun `does not detect settings as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.android.settings"))).isFalse()
    }

    @Test
    fun `does not detect notes app as terminal`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.notes"))).isFalse()
    }

    @Test
    fun `does not detect generic app with terminal in name only`() {
        // "terminal" is in the PACKAGE name but not at a boundary position
        // "deterministic" contains "termin" but shouldn't match
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.deterministic"))).isFalse()
    }

    @Test
    fun `does not detect app with termination in name`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage("com.example.termination"))).isFalse()
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `null EditorInfo returns false`() {
        assertThat(TerminalUtils.isTerminalApp(null)).isFalse()
    }

    @Test
    fun `null package name returns false`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage(null))).isFalse()
    }

    @Test
    fun `empty package name returns false`() {
        assertThat(TerminalUtils.isTerminalApp(editorInfoWithPackage(""))).isFalse()
    }

    // =========================================================================
    // Config defaults (#70)
    // =========================================================================

    @Test
    fun `termux mode enabled by default`() {
        assertThat(Defaults.TERMUX_MODE_ENABLED).isTrue()
    }
}
