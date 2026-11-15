package tribixbite.cleverkeys

import java.io.File

/**
 * Small helpers for the review 2026-11-14 settings-UI drift tests, which pin Activity/Compose
 * wiring that the pure JVM cannot drive (no Robolectric on ARM64). Paths are relative to the
 * project directory, the working directory of runPureTests / runMockTests.
 */
internal object SourceScan {

    fun read(path: String): String = File(path).readText()

    /**
     * The body of the first function whose declaration contains [signature] (for example
     * `"override fun onNewIntent"`), from its opening brace to the matching closing brace.
     * Braces inside string literals are not special-cased; the scanned functions have none
     * that are unbalanced.
     */
    fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "no function matching '$signature'" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "'$signature' has no body" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after '$signature'")
    }

    /** True when `var <name> by rememberSaveable` (with or without arguments) declares [name]. */
    fun declaresSaveable(source: String, name: String): Boolean =
        Regex("""var\s+$name\s+by\s+rememberSaveable\b""").containsMatchIn(source)
}
