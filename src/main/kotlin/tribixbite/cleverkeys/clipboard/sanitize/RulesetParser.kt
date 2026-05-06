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

    fun fromJson(jsonString: String): Ruleset {
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
            catch (_: Exception) { return null }

        val rules = obj.getAsJsonArray("rules")?.mapNotNull { it.asString } ?: emptyList()

        val rawRules = obj.getAsJsonArray("rawRules")?.mapNotNull {
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
            try { Regex(it.asString, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
        } ?: emptyList()

        val completeProvider = obj.get("completeProvider")?.asBoolean ?: false

        return Provider(name, urlPattern, rules, rawRules, redirections, exceptions, completeProvider)
    }

    private fun parseRedirection(pattern: String, replacement: String?): RedirectionRule? {
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { return null }
        return RedirectionRule(regex, replacement)
    }
}
