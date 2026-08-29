package tribixbite.cleverkeys

/**
 * Pure-Kotlin utilities for clipboard search — no Android dependencies,
 * enabling JVM unit testing without Android framework.
 */
object ClipboardSearchUtils {

    /**
     * Expands glob-style shorthand into regex syntax for intuitive search:
     * - Bare `*` (not preceded by `.`) → `.*` (match any characters)
     * - Bare `?` → `.` (match single character)
     * - `\*` and `\?` escape sequences pass through unchanged
     * - All other regex metacharacters (anchors, alternation, classes) pass through as-is
     *
     * This gives casual users familiar glob wildcards while preserving full
     * regex power for users who type `\d+`, `^http`, `a|b`, etc.
     */
    fun expandGlobShorthand(query: String): String {
        val sb = StringBuilder(query.length + 8)
        var i = 0
        while (i < query.length) {
            val c = query[i]
            when {
                // Escaped character — pass through escape + next char verbatim
                c == '\\' && i + 1 < query.length -> {
                    sb.append(c)
                    sb.append(query[i + 1])
                    i += 2
                }
                // Bare * (not preceded by .) → glob wildcard .*
                c == '*' && (i == 0 || query[i - 1] != '.') -> {
                    sb.append(".*")
                    i++
                }
                // Bare ? → single character wildcard .
                c == '?' -> {
                    sb.append('.')
                    i++
                }
                // Everything else passes through (real regex metacharacters work)
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
