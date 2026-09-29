package tribixbite.cleverkeys

import java.io.File

/**
 * Shared helpers for the pure-JVM source-scan drift tests ([HardcodedUiStringTest],
 * [RtlMirroringDriftTest]). Runs with the project root as CWD.
 */
internal object KotlinSourceScan {

    val sourceRoot = File("src/main/kotlin/tribixbite/cleverkeys")

    /** Every main-source Kotlin file as (path relative to [sourceRoot], contents). */
    fun kotlinFiles(): Sequence<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }

    /**
     * Blanks comments while keeping every newline, so line numbers computed on the result
     * match the file. Only block comments that OPEN a line are removed: a comment opener inside
     * a string literal (a MIME wildcard such as `image/` + `*`) never starts a line, so string
     * contents are never eaten. (Spelled out: Kotlin block comments nest, so the two characters
     * written together in this KDoc would open a nested comment.)
     */
    fun stripComments(src: String): String {
        fun blank(m: MatchResult) = m.value.replace(Regex("[^\\n]"), " ")
        val noBlocks = Regex("(?ms)^[ \\t]*/\\*.*?\\*/").replace(src, ::blank)
        return Regex("(?m)^[ \\t]*//.*$").replace(noBlocks, ::blank)
    }
}
