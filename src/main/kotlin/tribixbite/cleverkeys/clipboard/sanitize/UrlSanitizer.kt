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

    override fun process(text: String): String {
        if (ruleset.providers.isEmpty() || text.isEmpty()) return text
        // Filled in at Task 2.3 when the URL-scan + per-provider matching lands.
        return text
    }
}
