package tribixbite.cleverkeys.clipboard.sanitize

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure JSON → Ruleset parser. ClearURLs format with our RedirectionRule
 * extension. No Android deps; suitable for pure JVM tests.
 *
 * Parsing rules (matches ClearURLs upstream behavior):
 *   - Missing field → use Provider's default (empty list / false)
 *   - Field present but malformed → skip just that entry, continue with rest
 *   - Top-level `providers` key MUST be present
 *   - "globalRules" treated as a normal provider entry (not a separate field)
 */
object RulesetParser {

    internal fun fromJson(jsonString: String): Ruleset {
        val root = JsonParser.parseString(jsonString).asJsonObject
        val providersObj = root.getAsJsonObject("providers")
            ?: return Ruleset(emptyMap())

        val out = LinkedHashMap<String, Provider>()
        for ((name, providerEl) in providersObj.entrySet()) {
            if (!providerEl.isJsonObject) continue
            val p = parseProvider(name, providerEl.asJsonObject) ?: continue
            out[name] = p
        }
        return Ruleset(out)
    }

    private fun parseProvider(name: String, obj: JsonObject): Provider? {
        val urlPatternStr = obj.get("urlPattern")?.asString ?: return null
        val urlPattern = try { Regex(urlPatternStr, RegexOption.IGNORE_CASE) }
            // Malformed urlPattern regex — drop entire provider; no useful matching possible.
            catch (_: Exception) { return null }

        val rules = obj.getAsJsonArray("rules")?.mapNotNull { it.asString } ?: emptyList()

        val rawRules = obj.getAsJsonArray("rawRules")?.mapNotNull {
            // Malformed rawRule regex — drop just this entry, keep the rest of the provider.
            try { Regex(it.asString) } catch (_: Exception) { null }
        } ?: emptyList()

        val redirections = obj.getAsJsonArray("redirections")?.mapNotNull {
            // Two encodings allowed: bare string (upstream) or {pattern, replacement}.
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isString ->
                    parseRedirection(it.asString, replacement = null)
                it.isJsonObject -> {
                    val redirObj = it.asJsonObject
                    parseRedirection(
                        pattern = redirObj.get("pattern")?.asString ?: return@mapNotNull null,
                        replacement = redirObj.get("replacement")?.asString,
                    )
                }
                else -> null
            }
        } ?: emptyList()

        val exceptions = obj.getAsJsonArray("exceptions")?.mapNotNull {
            // Malformed exception regex — drop just this entry, keep the rest of the provider.
            try { Regex(it.asString, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
        } ?: emptyList()

        val completeProvider = obj.get("completeProvider")?.asBoolean ?: false

        return Provider(name, urlPattern, rules, rawRules, redirections, exceptions, completeProvider)
    }

    private fun parseRedirection(pattern: String, replacement: String?): RedirectionRule? {
        // Malformed redirection pattern — drop the rule, caller filters via mapNotNull.
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { return null }
        return RedirectionRule(regex, replacement)
    }

    /**
     * Per-provider, field-level overlay. New providers in `overlay` are added
     * as-is; existing providers have their list fields appended.
     * `completeProvider: true` from overlay can disable a base provider; once
     * `true`, never reverts to false.
     */
    internal fun merge(base: Ruleset, overlay: Ruleset): Ruleset {
        val out = LinkedHashMap(base.providers)
        for ((name, op) in overlay.providers) {
            val bp = out[name]
            if (bp == null) {
                out[name] = op
                continue
            }
            out[name] = bp.copy(
                rules = bp.rules + op.rules,
                rawRules = bp.rawRules + op.rawRules,
                redirections = bp.redirections + op.redirections,
                exceptions = bp.exceptions + op.exceptions,
                completeProvider = bp.completeProvider || op.completeProvider,
                // urlPattern: overlay wins if its raw pattern was not the wildcard sentinel
                // (Regex equality is identity-only, so we compare via toString).
                urlPattern = if (op.urlPattern.pattern != bp.urlPattern.pattern)
                    op.urlPattern else bp.urlPattern,
            )
        }
        return Ruleset(out)
    }
}
