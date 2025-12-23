package tribixbite.cleverkeys

import android.view.inputmethod.EditorInfo

object TerminalUtils {
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

    fun isTerminalApp(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        val packageName = editorInfo.packageName ?: return false
        
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
