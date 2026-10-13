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

    /**
     * Apps whose text field is BLIND: what the IME commits is forwarded to a remote screen and
     * the field cannot be read back or edited in place (2026-10-13, maintainer requirement).
     * Only packages whose input field was read in source are listed; any TYPE_NULL editor is
     * classified the same way without a list entry (AVNC and bVNC both use TYPE_NULL), and the
     * user's custom terminal list covers the rest.
     *
     * RustDesk (`com.carriez.flutter_hbb`, master 0b5a3d74): the remote-desktop view forwards a
     * LENGTH-ONLY diff of a hidden multiline field pre-filled with 1024 '1' characters — any
     * shrink sends ONE Backspace however many chars were removed, a same-length rewrite sends
     * nothing — and its remote terminal resets an empty field after every commit, so only
     * KEYCODE_DEL key events delete there (flutter/lib/mobile/pages/remote_page.dart L28,
     * L328-L372; xterm 4.0.0 custom_text_edit.dart).
     */
    private val KNOWN_REMOTE_INPUT_PACKAGES = setOf(
        "com.carriez.flutter_hbb", // RustDesk
    )

    /** How the IME may treat an editor's text (see [editorKind]). */
    enum class EditorKind {
        /** An ordinary editor: readable, editable in place with InputConnection calls. */
        TEXT,

        /**
         * A terminal (pty behind the field): no document to rewrite. Deletion is KEYCODE_DEL,
         * delete-last-word is the line editor's Ctrl+W (ARC-007).
         */
        TERMINAL,

        /**
         * A blind field that forwards input elsewhere (remote desktop/VNC, or any TYPE_NULL
         * editor that is not a known terminal). Everything [TERMINAL] gets, except Ctrl+W:
         * on a remote GUI that closes the window, so delete-last-word is Ctrl+Backspace.
         */
        REMOTE,
    }

    /**
     * The ONE blind-field detector (2026-10-13). Order: the user's list (exact, case-sensitive,
     * always a terminal), built-in terminals and their heuristics, built-in remote-input apps,
     * then any editor declaring `InputType.TYPE_NULL` — by the platform contract a field that
     * is not a text editor and expects key events. A readback probe is deliberately NOT used:
     * an empty ordinary field reads "" too, so a probe would misclassify every new message box.
     */
    fun editorKind(editorInfo: EditorInfo?, customPackages: Set<String>? = emptySet()): EditorKind {
        if (editorInfo == null) return EditorKind.TEXT
        val packageName = editorInfo.packageName
        if (packageName != null) {
            if (customPackages?.contains(packageName) == true) return EditorKind.TERMINAL
            if (isBuiltInTerminalPackage(packageName)) return EditorKind.TERMINAL
            if (packageName in KNOWN_REMOTE_INPUT_PACKAGES) return EditorKind.REMOTE
        }
        // TYPE_NULL is 0: no class, no variation, no flags.
        if (editorInfo.inputType == android.text.InputType.TYPE_NULL) return EditorKind.REMOTE
        return EditorKind.TEXT
    }

    /**
     * True for every editor that must get terminal-style input — no in-place rewrites
     * (autocorrect, I-capitalization, suggestion replacement by deleteSurroundingText), deletion
     * by KEYCODE_DEL, paste by committing the clip text — i.e. [EditorKind.TERMINAL] and
     * [EditorKind.REMOTE]. Every no-rewrite branch in the IME asks this one predicate.
     */
    fun isTerminalApp(editorInfo: EditorInfo?, customPackages: Set<String>? = emptySet()): Boolean =
        editorKind(editorInfo, customPackages) != EditorKind.TEXT

    /** Built-in terminal detection by package name (exact list plus ecosystem heuristics). */
    private fun isBuiltInTerminalPackage(packageName: String): Boolean {
        // 1. Direct package match
        if (KNOWN_TERMINAL_PACKAGES.contains(packageName)) return true

        // 2. Termux ecosystem (includes x11, tasker, etc.)
        // This is safe as these apps generally benefit from terminal-style input
        if (packageName.contains("termux")) return true

        // 3. Another Term ecosystem
        if (packageName.contains("anotherterm")) return true

        // 4. Heuristic for apps with "terminal" in the package name
        // We check for common patterns to avoid false positives with general apps
        return packageName.endsWith(".terminal") ||
            packageName.contains(".terminal.") ||
            packageName.contains(".terminalemulator")
    }
}
