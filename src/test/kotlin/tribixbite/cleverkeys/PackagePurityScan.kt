package tribixbite.cleverkeys

import java.io.File

/**
 * Shared substrate for the pure-package PURITY drift guards
 * ([tribixbite.cleverkeys.swipe.geometric.GeoPurityDriftTest] — geometric NFR-3,
 * [tribixbite.cleverkeys.swipe.ctc.CtcPurityDriftTest] — CTC NFR-1).
 *
 * Both packages are contractually pure JVM: they must contain ZERO `android.*` /
 * `androidx.*` usage — imports OR fully-qualified references — so they run unmodified
 * under `runPureTests` and can never accidentally load a stubbed Android class on the JVM.
 *
 * The scan is a token regex over COMMENT-STRIPPED sources rather than an import-line
 * check, because the codebase's own leak pattern is a fully-qualified
 * `android.util.Log.w(...)` with no import line (`Keyboard2View.kt:1124`) — which an
 * import-only scan would miss. Comments are stripped first so a KDoc mentioning "android"
 * in prose does not false-positive.
 *
 * Extracted from `GeoPurityDriftTest` (2026-08-28, ARC-024) so the second guard does not
 * duplicate the string-literal-aware stripper; [stripComments] keeps its own unit test in
 * `GeoPurityDriftTest` (the original owner).
 */
object PackagePurityScan {

    /** Matches an `android.` or `androidx.` token at a word boundary. */
    val ANDROID_TOKEN = Regex("""\bandroidx?\.""")

    /** Every Kotlin source file under [dir], recursively. */
    fun kotlinSources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Remove `//` line comments and block comments (Kotlin allows nesting) so that prose
     * mentioning android does not trip the token scan, while real code references still do.
     *
     * STRING-LITERAL-AWARE: a block- or line-comment opener inside a `"..."` / `"""..."""`
     * literal does NOT open a comment (which would silently exclude following real code from
     * the scan — a false-negative window); string contents are emitted, so a literal
     * `"android."` in engine code is (correctly) still flagged.
     */
    fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        var inLineComment = false
        var inString = false
        var tripleQuoted = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                blockDepth > 0 -> {
                    if (c == '/' && next == '*') { blockDepth++; i += 2 }
                    else if (c == '*' && next == '/') { blockDepth--; i += 2 }
                    else i++
                }
                inLineComment -> {
                    if (c == '\n') { inLineComment = false; out.append(c) }
                    i++
                }
                inString -> {
                    out.append(c)
                    if (!tripleQuoted && c == '\\' && i + 1 < src.length) {
                        // Escaped char inside a normal string (\" does not close it).
                        out.append(src[i + 1]); i += 2
                    } else if (tripleQuoted && c == '"' && i + 2 < src.length &&
                        src[i + 1] == '"' && src[i + 2] == '"'
                    ) {
                        out.append("\"\""); inString = false; i += 3
                    } else if (!tripleQuoted && c == '"') {
                        inString = false; i++
                    } else {
                        i++
                    }
                }
                c == '"' -> {
                    inString = true
                    tripleQuoted = i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"'
                    if (tripleQuoted) { out.append("\"\"\""); i += 3 } else { out.append(c); i++ }
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '/' && next == '/' -> { inLineComment = true; i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The first `android.`/`androidx.` token in [file]'s comment-stripped source, or null
     * when the file is pure.
     */
    fun firstAndroidToken(file: File): MatchResult? =
        ANDROID_TOKEN.find(stripComments(file.readText()))
}
