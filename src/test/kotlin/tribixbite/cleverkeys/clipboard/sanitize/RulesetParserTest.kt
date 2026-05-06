package tribixbite.cleverkeys.clipboard.sanitize

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulesetParserTest {

    @Test
    fun emptyProviders_parsesToEmptyMap() {
        val json = """{"providers":{}}"""
        val rs = RulesetParser.fromJson(json)
        assertThat(rs.providers).isEmpty()
    }

    @Test
    fun singleProvider_parsesAllFields() {
        val json = """{
            "providers": {
                "twitter": {
                    "urlPattern": "^https?://(?:www\\.)?(?:twitter|x)\\.com",
                    "rules": ["s", "ref_src", "ref_url"],
                    "rawRules": ["/photo/\\d+$"],
                    "redirections": [
                        "^https?://t\\.co/.*[?&]url=([^&]+)"
                    ],
                    "exceptions": ["^https?://twitter\\.com/i/api/"],
                    "completeProvider": false
                }
            }
        }""".trimIndent()

        val rs = RulesetParser.fromJson(json)
        val p = rs.providers["twitter"]!!

        assertThat(p.name).isEqualTo("twitter")
        assertThat(p.rules).containsExactly("s", "ref_src", "ref_url").inOrder()
        assertThat(p.rawRules).hasSize(1)
        assertThat(p.redirections).hasSize(1)
        // Bare-string redirection → replacement is null (upstream semantics)
        assertThat(p.redirections[0].replacement).isNull()
        assertThat(p.exceptions).hasSize(1)
        assertThat(p.completeProvider).isFalse()
    }

    @Test
    fun extendedRedirection_objectFormWithReplacement() {
        // Our extension: {pattern, replacement} object form for embed enrich.
        val json = """{
            "providers": {
                "twitter_embed": {
                    "urlPattern": "^https?://(?:www\\.)?(?:twitter|x)\\.com/.+",
                    "redirections": [
                        {
                            "pattern": "^https?://(?:www\\.)?(?:twitter|x)\\.com/(.+)$",
                            "replacement": "https://fxtwitter.com/${'$'}1"
                        }
                    ]
                }
            }
        }""".trimIndent()

        val rs = RulesetParser.fromJson(json)
        val redir = rs.providers["twitter_embed"]!!.redirections.single()
        assertThat(redir.replacement).isEqualTo("https://fxtwitter.com/${'$'}1")
    }

    @Test
    fun missingUrlPattern_skipsProvider() {
        val json = """{"providers":{"bad":{"rules":["foo"]}}}"""
        assertThat(RulesetParser.fromJson(json).providers).isEmpty()
    }

    @Test
    fun malformedRegexInUrlPattern_skipsProvider() {
        val json = """{"providers":{"bad":{"urlPattern":"[unclosed"}}}"""
        assertThat(RulesetParser.fromJson(json).providers).isEmpty()
    }

    @Test
    fun malformedRegexInRawRule_dropsThatRuleKeepsProvider() {
        val json = """{
            "providers": {
                "p": {
                    "urlPattern": ".*",
                    "rawRules": ["[unclosed", "/valid$"]
                }
            }
        }""".trimIndent()
        val p = RulesetParser.fromJson(json).providers["p"]!!
        // Bad regex skipped; good one survives.
        assertThat(p.rawRules).hasSize(1)
    }

    @Test
    fun completeProviderTrue_persists() {
        val json = """{"providers":{"p":{"urlPattern":".*","completeProvider":true}}}"""
        assertThat(RulesetParser.fromJson(json).providers["p"]!!.completeProvider).isTrue()
    }

    @Test
    fun globalRules_isJustARegularProvider() {
        // ClearURLs upstream encodes globals as a provider with urlPattern ".*"
        val json = """{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["utm_source", "fbclid"]
                }
            }
        }""".trimIndent()
        val p = RulesetParser.fromJson(json).providers["globalRules"]!!
        assertThat(p.urlPattern.matches("https://example.com")).isTrue()
        assertThat(p.rules).containsExactly("utm_source", "fbclid")
    }

    @Test
    fun merge_overlayAddsNewProvider() {
        val base = RulesetParser.fromJson("""{"providers":{}}""")
        val overlay = RulesetParser.fromJson("""{"providers":{"new":{"urlPattern":".*","rules":["x"]}}}""")
        val merged = RulesetParser.merge(base, overlay)
        assertThat(merged.providers["new"]!!.rules).containsExactly("x")
    }

    @Test
    fun merge_overlayAppendsRulesToExistingProvider() {
        val base = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":".*","rules":["a","b"]}}}"""
        )
        val overlay = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":".*","rules":["c"]}}}"""
        )
        val merged = RulesetParser.merge(base, overlay)
        assertThat(merged.providers["p"]!!.rules).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun merge_overlayCanForceCompleteProvider() {
        val base = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":".*","completeProvider":false,"rules":["a"]}}}"""
        )
        val overlay = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":".*","completeProvider":true}}}"""
        )
        val merged = RulesetParser.merge(base, overlay)
        // Overlay's completeProvider:true wins → all processing skipped for this provider.
        assertThat(merged.providers["p"]!!.completeProvider).isTrue()
        // But rules survived the merge (provider is just disabled at apply-time).
        assertThat(merged.providers["p"]!!.rules).containsExactly("a")
    }

    @Test
    fun merge_overlayWithExceptionsDisablesBundledRule() {
        // The user-disable-bundled-rule pattern: overlay supplies exceptions: [".*"]
        // for a provider, which appends to bundled exceptions and shorts every match
        // at apply-time.
        val base = RulesetParser.fromJson(
            """{"providers":{"twitter":{"urlPattern":"^https?://twitter\\.com","rules":["s"]}}}"""
        )
        val overlay = RulesetParser.fromJson(
            """{"providers":{"twitter":{"urlPattern":"^https?://twitter\\.com","exceptions":[".*"]}}}"""
        )
        val merged = RulesetParser.merge(base, overlay)
        assertThat(merged.providers["twitter"]!!.exceptions).hasSize(1)
        assertThat(merged.providers["twitter"]!!.rules).containsExactly("s")
    }

    @Test
    fun merge_overlayUrlPatternOverridesBaseWhenDifferent() {
        // Overlay supplies a stricter urlPattern; merged Provider uses the overlay's pattern.
        val base = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":".*","rules":["a"]}}}"""
        )
        val overlay = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":"^https?://example\\.com","rules":["b"]}}}"""
        )
        val merged = RulesetParser.merge(base, overlay)
        // The new pattern is the overlay's specific one — verify by source-string equality.
        assertThat(merged.providers["p"]!!.urlPattern.pattern).isEqualTo("^https?://example\\.com")
        // List fields still merged (append).
        assertThat(merged.providers["p"]!!.rules).containsExactly("a", "b").inOrder()
    }

    @Test
    fun merge_overlayUrlPatternIdenticalKeepsBase() {
        // Identical urlPattern strings → merged keeps base instance (no override).
        val base = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":"^https?://example\\.com","rules":["a"]}}}"""
        )
        val overlay = RulesetParser.fromJson(
            """{"providers":{"p":{"urlPattern":"^https?://example\\.com","rules":["b"]}}}"""
        )
        val merged = RulesetParser.merge(base, overlay)
        // Pattern source-string is preserved (still the same shape regardless of which
        // instance won — but the merge code path took the "no diff → keep base" branch).
        assertThat(merged.providers["p"]!!.urlPattern.pattern).isEqualTo("^https?://example\\.com")
        assertThat(merged.providers["p"]!!.rules).containsExactly("a", "b").inOrder()
    }
}
