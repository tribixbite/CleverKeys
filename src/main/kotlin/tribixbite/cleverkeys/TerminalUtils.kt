package tribixbite.cleverkeys

import android.view.inputmethod.EditorInfo
import java.util.Collections

object TerminalUtils {
    const val MAX_CUSTOM_PACKAGES = 100
    const val MAX_PACKAGE_LENGTH = 255
    const val MAX_PACKAGE_INPUT_LENGTH = 32768
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    enum class PackageListError { INVALID_PACKAGE, TOO_MANY_PACKAGES, INPUT_TOO_LONG }

    sealed interface PackageListResult {
        data class Valid(val packages: Set<String>) : PackageListResult
        data class Invalid(val reason: PackageListError, val entry: String = "") : PackageListResult
    }

    /** Parse an additive, exact-match package list. Reject the whole draft on an error. */
    fun parseCustomPackages(input: String): PackageListResult {
        if (input.length > MAX_PACKAGE_INPUT_LENGTH) {
            return PackageListResult.Invalid(PackageListError.INPUT_TOO_LONG)
        }
        val packages = linkedSetOf<String>()
        for (token in input.split(",", "\n", "\r")) {
            val name = token.trim()
            if (name.isEmpty()) continue
            if (name.length > MAX_PACKAGE_LENGTH || !PACKAGE_NAME.matches(name)) {
                return PackageListResult.Invalid(PackageListError.INVALID_PACKAGE, name.take(MAX_PACKAGE_LENGTH))
            }
            packages.add(name)
            if (packages.size > MAX_CUSTOM_PACKAGES) {
                return PackageListResult.Invalid(PackageListError.TOO_MANY_PACKAGES)
            }
        }
        // Publish an immutable snapshot; hot-path readers never parse preferences or query installed apps.
        return PackageListResult.Valid(Collections.unmodifiableSet(packages))
    }

    private val KNOWN_TERMINAL_PACKAGES = setOf(
        "com.termux",
        "com.termux.nix",
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client", // Termius
        "jackpal.androidterm",
        "com.magicandroidapps.bettertermpro",
        "com.rbrq.terminal",
        "com.android.virtualization.terminal", // Android Virtualization Framework
        "com.rk.terminal",
        "green_green_avk.anotherterm.redist"
    )

    fun isTerminalApp(editorInfo: EditorInfo?, customPackages: Set<String>? = emptySet()): Boolean {
        if (editorInfo == null) return false
        val packageName = editorInfo.packageName ?: return false
        
        // User additions are exact and case-sensitive; built-in detection remains additive.
        if (customPackages?.contains(packageName) == true) return true

        // 1. Direct package match
        if (KNOWN_TERMINAL_PACKAGES.contains(packageName)) return true
        
        // 2. Termux ecosystem (includes x11, tasker, etc.)
        // This is safe as these apps generally benefit from terminal-style input
        if (packageName.contains("termux")) return true
        
        // 3. Another Term ecosystem
        if (packageName.contains("anotherterm")) return true
        
        // 4. Heuristic for apps with "terminal" in the package name
        // We check for common patterns to avoid false positives with general apps
        if (packageName.endsWith(".terminal") || 
            packageName.contains(".terminal.") ||
            packageName.contains(".terminalemulator")) {
            return true
        }
        
        return false
    }
}
