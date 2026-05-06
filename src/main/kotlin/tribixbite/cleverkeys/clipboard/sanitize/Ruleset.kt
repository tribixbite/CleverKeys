package tribixbite.cleverkeys.clipboard.sanitize

/**
 * ClearURLs-format provider entry. Each provider matches a host pattern
 * and applies rewrites to URLs that match.
 *
 * Source-of-truth schema: ClearURLs upstream `data.minify.json`
 * (gitlab.com/ClearURLs/Rules). We add an extension to RedirectionRule
 * (nullable replacement template) so the same format covers both the
 * upstream chained-redirect-bypass use case AND embed-enrichment host
 * rewrites without forking the format.
 */
internal data class Provider(
    val urlPattern: Regex,
    val rules: List<String> = emptyList(),         // case-insensitive query-param names to strip
    val rawRules: List<Regex> = emptyList(),       // regex strip from full URL string
    val redirections: List<RedirectionRule> = emptyList(),
    val exceptions: List<Regex> = emptyList(),
    val completeProvider: Boolean = false,         // true → skip ALL processing for matched URL
)

/**
 * A redirection rewrite. ClearURLs upstream encodes these as a flat list
 * of regex strings where the regex is expected to have at least one
 * capture group, and group 1 is treated as the new URL (used for
 * t.co/foo?url=https://... unfurling). We extend the format with an
 * optional explicit `replacement` template so the same data class can
 * represent embed-enrichment (twitter → fxtwitter) where we need to
 * construct a new URL from a captured path.
 *
 * - replacement == null  → upstream-compatible: result = group(1)
 * - replacement != null  → result = pattern.replaceFirst(url, replacement)
 *                           where $1, $2, etc. interpolate captured groups
 */
internal data class RedirectionRule(
    val pattern: Regex,
    val replacement: String? = null,
)

/**
 * Top-level container — flat map of provider name → Provider.
 * "globalRules" is just a regular key with `urlPattern: ".*"`; not a
 * separate field. Iteration order is map iteration order, which for
 * Gson-decoded LinkedHashMap matches JSON declaration order.
 */
internal data class Ruleset(
    val providers: Map<String, Provider>,
)
