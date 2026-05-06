package tribixbite.cleverkeys.clipboard.sanitize

/**
 * Stateless URL sanitization engine. Pure JVM — no Android deps.
 *
 * Scans input text for HTTP/HTTPS URLs, runs each through the matching
 * providers in the ruleset, and returns the input with each URL replaced
 * in-place.
 *
 * Idempotency: process(process(s)) == process(s) for any input s.
 */
interface UrlSanitizer {
    fun process(text: String): String
}

class RulesetUrlSanitizer(private val ruleset: Ruleset) : UrlSanitizer {

    private companion object {
        // Hand-rolled URL regex: HTTP/HTTPS only, until first whitespace or string end.
        // Pure JVM-compatible — does NOT depend on android.util.Patterns.
        // Trailing punctuation (.,;:!?) is excluded so "see https://x.com." → the trailing
        // dot is left outside the URL match.
        private val URL_REGEX = Regex(
            "https?://[^\\s<>\"]+[^\\s<>\".,;:!?)]"
        )
    }

    override fun process(text: String): String {
        if (ruleset.providers.isEmpty() || text.isEmpty()) return text

        return URL_REGEX.replace(text) { match ->
            sanitizeOne(match.value)
        }
    }

    private fun sanitizeOne(url: String): String {
        var current = url

        // Iterate providers in declaration order. globalRules (if present) matches first.
        // Each matching provider applies its full rule set; we don't break — multiple
        // providers can chain (matches ClearURLs upstream behavior).
        for (provider in ruleset.providers.values) {
            if (!provider.urlPattern.containsMatchIn(current)) continue
            if (provider.exceptions.any { it.containsMatchIn(current) }) continue
            if (provider.completeProvider) continue   // explicit disable

            // Apply redirections first (host swap / chained redirect bypass)
            for (redir in provider.redirections) {
                val match = redir.pattern.find(current) ?: continue
                current = if (redir.replacement != null) {
                    // Our extension: explicit replacement template
                    redir.pattern.replaceFirst(current, redir.replacement)
                } else {
                    // Upstream: group(1) is the new URL
                    match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: current
                }
            }

            // Apply rawRules (regex strip from URL string)
            for (raw in provider.rawRules) {
                current = raw.replace(current, "")
            }

            // Apply rules (case-insensitive query-param strip)
            current = stripQueryParams(current, provider.rules)
        }

        return current
    }

    private fun stripQueryParams(url: String, paramsToStrip: List<String>): String {
        if (paramsToStrip.isEmpty()) return url
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url

        // Lowercase the strip list once for case-insensitive matching.
        val stripSet = paramsToStrip.map { it.lowercase() }.toHashSet()

        // Split off the fragment (#anchor) — preserve verbatim.
        val fragIdx = url.indexOf('#', startIndex = qIdx)
        val fragment = if (fragIdx >= 0) url.substring(fragIdx) else ""
        val pathAndQuery = if (fragIdx >= 0) url.substring(0, fragIdx) else url

        val base = pathAndQuery.substring(0, qIdx)
        val query = pathAndQuery.substring(qIdx + 1)

        val keptParams = query.split('&').filter { kv ->
            val key = kv.substringBefore('=').lowercase()
            key.isNotEmpty() && key !in stripSet
        }

        return when {
            keptParams.isEmpty() -> base + fragment
            else -> base + "?" + keptParams.joinToString("&") + fragment
        }
    }
}
