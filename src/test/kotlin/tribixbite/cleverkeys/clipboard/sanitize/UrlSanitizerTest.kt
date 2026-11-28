package tribixbite.cleverkeys.clipboard.sanitize

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class UrlSanitizerTest {

    private val emptyRuleset = Ruleset(emptyMap())

    @Test
    fun emptyRuleset_returnsInputUnchanged() {
        val sanitizer = RulesetUrlSanitizer(emptyRuleset)
        val input = "Check out https://example.com/path?utm_source=newsletter"
        assertThat(sanitizer.process(input)).isEqualTo(input)
    }

    @Test
    fun noUrls_returnsInputUnchanged() {
        val sanitizer = RulesetUrlSanitizer(emptyRuleset)
        val input = "Just some plain text without any URLs."
        assertThat(sanitizer.process(input)).isEqualTo(input)
    }

    @Test
    fun globalRules_stripsTrackingParam() {
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["utm_source", "fbclid"]
                }
            }
        }""".trimIndent())
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://example.com/page?utm_source=foo&keep=bar&fbclid=baz"
        val output = sanitizer.process(input)
        assertThat(output).isEqualTo("https://example.com/page?keep=bar")
    }

    @Test
    fun aliexpress_allKnownTrackingStripped() {
        // The user's own example URL — all 8 query params should be stripped.
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "aliexpress": {
                    "urlPattern": "^https?://(?:[^/?#]+\\.)?aliexpress\\.(?:com|us|ru|de|fr|es|it|pl|nl|co\\.kr|co\\.jp)",
                    "rules": ["spm","browser_id","aff_platform","m_page_id",
                              "pdp_ext_f","pdp_npi","algo_pvid","utparam-url",
                              "aff_trace_key","scm","scm-url","scm_id","acm",
                              "algo_exp_id","wh_pid"]
                }
            }
        }""".trimIndent())
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://www.aliexpress.us/item/3256807058505746.html?spm=a2g0n.productlist.0.0.29b4&browser_id=3a6b5e&aff_platform=msite&m_page_id=qwhi&pdp_ext_f=%7B%22order%22%3A%2254%22%7D&pdp_npi=6&algo_pvid=9fc4&utparam-url=scene"
        val output = sanitizer.process(input)
        assertThat(output).isEqualTo("https://www.aliexpress.us/item/3256807058505746.html")
    }

    @Test
    fun urlInsideText_otherTextPreserved() {
        val rs = RulesetParser.fromJson("""{
            "providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "Hey check this https://example.com/path?utm_source=foo cool right?"
        assertThat(sanitizer.process(input))
            .isEqualTo("Hey check this https://example.com/path cool right?")
    }

    @Test
    fun multipleUrls_eachSanitized() {
        val rs = RulesetParser.fromJson("""{
            "providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://a.com?utm_source=x and https://b.com?utm_source=y"
        assertThat(sanitizer.process(input))
            .isEqualTo("https://a.com and https://b.com")
    }

    @Test
    fun nonHttpScheme_leftAlone() {
        // URL_REGEX only matches http(s)://... — mailto: is never scanned, so the
        // `?utm_source=…` survives untouched as part of the surrounding text.
        val rs = RulesetParser.fromJson("""{
            "providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "mailto:foo@example.com?utm_source=ignored"
        assertThat(sanitizer.process(input)).isEqualTo(input)
    }

    @Test
    fun rule_isRegexNotLiteral_clearUrlsSpmPattern() {
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["(?:%3F)?spm"]
                }
            }
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://www.aliexpress.us/item/3256807058505746.html?spm=a2g0o.detail.0.0.foo"
        assertThat(sanitizer.process(input))
            .isEqualTo("https://www.aliexpress.us/item/3256807058505746.html")
    }

    @Test
    fun rule_isRegexNotLiteral_clearUrlsUtmFamily() {
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["(?:%3F)?utm(?:_[a-z_]*)?"]
                }
            }
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://example.com/x?utm_source=a&utm_medium=b&utm_campaign=c&keep=yes"
        assertThat(sanitizer.process(input))
            .isEqualTo("https://example.com/x?keep=yes")
    }

    @Test
    fun rule_isRegexNotLiteral_clearUrlsScmFamily() {
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "aliexpress": {
                    "urlPattern": "^https?://(?:[^/?#]+\\.)?aliexpress\\.",
                    "rules": ["scm[_a-z-]*"]
                }
            }
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://www.aliexpress.us/item/x.html?scm=foo&scm-url=bar&scm_id=baz&keep=yes"
        assertThat(sanitizer.process(input))
            .isEqualTo("https://www.aliexpress.us/item/x.html?keep=yes")
    }

    @Test
    fun malformedRulePattern_skippedGracefullyOthersStillApply() {
        val rs = RulesetParser.fromJson("""{
            "providers": {
                "globalRules": {
                    "urlPattern": ".*",
                    "rules": ["[invalid", "fbclid"]
                }
            }
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://example.com/x?fbclid=foo&keep=yes"
        assertThat(sanitizer.process(input))
            .isEqualTo("https://example.com/x?keep=yes")
    }

    @Test
    fun idempotent_runTwiceSameAsOnce() {
        val rs = RulesetParser.fromJson("""{
            "providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}
        }""")
        val sanitizer = RulesetUrlSanitizer(rs)
        val input = "https://example.com/page?utm_source=foo&keep=bar"
        val once = sanitizer.process(input)
        val twice = sanitizer.process(once)
        assertThat(twice).isEqualTo(once)
    }

    // The bundled clearurls.json must strip the reddit/rxddit `embed_host_url` tracking
    // param so shared reddit and rxddit links don't leak the embedding host. Loads the
    // REAL shipped ruleset (exposed to JVM tests via resources.srcDirs += src/main/assets).
    private val bundledClearUrls by lazy {
        val stream = javaClass.getResourceAsStream("/url_rules/clearurls.json")
            ?: error("clearurls.json missing from test classpath")
        RulesetUrlSanitizer(RulesetParser.fromJson(stream.bufferedReader().use { it.readText() }))
    }

    @Test
    fun clearUrls_stripsEmbedHostUrl_fromRedditLink() {
        val out = bundledClearUrls.process(
            "https://www.reddit.com/r/x/comments/1/title/?embed_host_url=https%3A%2F%2Fe.com&keep=1"
        )
        assertThat(out).doesNotContain("embed_host_url")
        assertThat(out).contains("keep=1")
        assertThat(out).startsWith("https://www.reddit.com/r/x/comments/1/title/")
    }

    @Test
    fun clearUrls_stripsEmbedHostUrl_fromRxdditLink() {
        val out = bundledClearUrls.process(
            "https://rxddit.com/r/x/comments/1/title/?embed_host_url=foo"
        )
        assertThat(out).doesNotContain("embed_host_url")
        assertThat(out).startsWith("https://rxddit.com/r/x/comments/1/title/")
    }

    @Test
    fun clearUrls_cleansAliexpressUsListingLink() {
        // Real-world AliExpress share link: affiliate/tracking junk + a pdp_ext_f value that
        // contains literal `"` characters (which used to truncate URL matching). Should reduce
        // to the bare product page.
        val input = "https://www.aliexpress.us/item/3256807058505746.html" +
            "?spm=a2g0n.productlist.0.0.29b4124aDE5jw1" +
            "&browser_id=3a6b5ec51a5a43e1880fcdc43b82ba5a&aff_platform=msite" +
            "&m_page_id=qwhiintprycacoom19df446aff757fb06e120b7ee2" +
            "&pdp_ext_f=%7B\"order\"%3A\"54\"%2C\"eval\"%3A\"1\"%2C\"fromPage\"%3A\"search\"%7D" +
            "&pdp_npi=6%40dis!USD!18.92!13.89!!!128.43!94.30!%40210328c017779197664987980e1b6f" +
            "!12000039930916669!sea!US!0!ABX!1!0!n_tag%3A-29910%3Bd%3Ae7eef248" +
            "&algo_pvid=9fc4d04f-5375-4727-b893-8bb8783fc5eb" +
            "&utparam-url=scene%3Asearch%7Cquery_from%3A%7Cx_object_id%3A1005007244820498"
        assertThat(bundledClearUrls.process(input))
            .isEqualTo("https://www.aliexpress.us/item/3256807058505746.html")
    }

    // ─── Redirection percent-decoding (upstream ClearURLs decodeURL parity). The bundled
    //     rules capture percent-ENCODED groups (e.g. facebook `u=(https?%3A%2F%2F[^&]*)`),
    //     so the captured redirect target MUST be percent-decoded or the "cleaned" clip is
    //     a broken `https%3A%2F%2F...` string. ───────────────────────────────────────────

    @Test
    fun clearUrls_facebookRedirector_yieldsDecodedTargetUrl() {
        // Real-world l.facebook.com share wrapper. Upstream ClearURLs decodes the capture;
        // the result must be a *valid* URL for the wrapped target.
        val out = bundledClearUrls.process(
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fpage%3Fid%3D7&h=AT1x"
        )
        assertThat(out).startsWith("https://example.com/")
        assertThat(out).doesNotContain("%3A%2F%2F")
    }

    @Test
    fun clearUrls_googleRedirector_yieldsDecodedTargetUrl() {
        val out = bundledClearUrls.process(
            "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fdoc&sa=D"
        )
        assertThat(out).startsWith("https://example.com/doc")
    }

    @Test
    fun redirection_doubleEncodedCapture_decodesUntilStable() {
        // decodeURL upstream loops until fixpoint — a doubly-encoded target must fully decode.
        val rs = RulesetParser.fromJson("""{
            "providers":{"wrap":{"urlPattern":"^https?://wrap\\.example",
                "redirections":["wrap\\.example/\\?u=(.*)"]}}
        }""")
        val out = RulesetUrlSanitizer(rs).process(
            "https://wrap.example/?u=https%253A%252F%252Fexample.com%252Fx"
        )
        assertThat(out).isEqualTo("https://example.com/x")
    }

    @Test
    fun redirection_malformedPercentSequence_fallsBackToRawCapture() {
        // An invalid escape (%zz) must not throw — keep the raw capture verbatim.
        val rs = RulesetParser.fromJson("""{
            "providers":{"wrap":{"urlPattern":"^https?://wrap\\.example",
                "redirections":["wrap\\.example/\\?u=(.*)"]}}
        }""")
        val out = RulesetUrlSanitizer(rs).process("https://wrap.example/?u=https://e.com/%zz")
        assertThat(out).isEqualTo("https://e.com/%zz")
    }

    // ─── systemClipboardRewrite: decide whether to push the cleaned text back to the
    //     Android system clipboard (so pastes from ANY app, not just CleverKeys' panel,
    //     deliver the sanitized URL). Returns the string to write, or null to leave the
    //     system clipboard untouched. ──────────────────────────────────────────────────

    @Test
    fun systemClipboardRewrite_enabledAndChanged_returnsSanitized() {
        // Sanitizer cleaned the URL AND the user opted in → push the clean form back.
        assertThat(
            systemClipboardRewrite(
                original = "https://x.com/a?utm_source=foo",
                processed = "https://x.com/a",
                enabled = true,
            )
        ).isEqualTo("https://x.com/a")
    }

    @Test
    fun systemClipboardRewrite_enabledButUnchanged_returnsNull() {
        // Nothing was stripped (already clean, or no rule matched) → don't churn the
        // system clipboard. This is also what breaks the re-entrant listener loop:
        // re-sanitizing an already-clean value yields original == processed → null.
        val clean = "https://x.com/a"
        assertThat(
            systemClipboardRewrite(original = clean, processed = clean, enabled = true)
        ).isNull()
    }

    @Test
    fun systemClipboardRewrite_disabled_returnsNullEvenWhenChanged() {
        // User has the "also clean system clipboard" toggle off → never touch it,
        // even though the in-history copy was sanitized.
        assertThat(
            systemClipboardRewrite(
                original = "https://x.com/a?utm_source=foo",
                processed = "https://x.com/a",
                enabled = false,
            )
        ).isNull()
    }

    // ─── One Matcher per clip (low audit item, 2026-11-28) ─────────────────────────────
    // Kotlin's `Regex.replace(text) { }` walks matches through `MatchResult.next()`, which
    // builds a NEW java.util.regex.Matcher over the whole input for every match. On ART each
    // Matcher is ICU4C with its own native copy of the input, freed only when the GC runs its
    // cleaner (CLAUDE.md "Kotlin Regex and native memory on ART", a471782fd), so a large clip
    // with many links held one native copy of the clip per link. HotSpot cannot see that cost;
    // these tests pin the behaviour across the rewrite and the shape of the scan.

    private val utmOnly = """{"providers":{"globalRules":{"urlPattern":".*","rules":["utm_source"]}}}"""

    @Test
    fun largeManyLinkClip_everyUrlSanitized_proseAndPunctuationKept() {
        val sanitizer = RulesetUrlSanitizer(RulesetParser.fromJson(utmOnly))
        val input = StringBuilder()
        val expected = StringBuilder()
        // ~5,000 links in ~300 KB: trailing punctuation, quotes, parens and a final URL at the end.
        for (i in 0 until 5_000) {
            val tail = when (i % 4) { 0 -> ", "; 1 -> ". "; 2 -> ") "; else -> "\" " }
            input.append("item $i (https://e$i.com/p?utm_source=x&k=$i$tail")
            expected.append("item $i (https://e$i.com/p?k=$i$tail")
        }
        input.append("https://end.com/?utm_source=z")
        expected.append("https://end.com/")
        assertThat(sanitizer.process(input.toString())).isEqualTo(expected.toString())
    }

    @Test
    fun manyLinkClip_matchesTheRegexReplaceReference() {
        // Reference = the old implementation's shape (Regex.replace over the same pattern, each
        // match sanitized alone). A standalone match re-matches as exactly itself, so
        // process(match) is the per-URL transform. Mixed separators exercise the URL boundaries.
        val sanitizer = RulesetUrlSanitizer(RulesetParser.fromJson(utmOnly))
        val pattern = Regex("https?://[^\\s<>]+[^\\s<>\".,;:!?)]")
        val pieces = listOf(
            "https://a.com?utm_source=1", " ", "http://b.org/x?y=2&utm_source=3", "\n",
            "<https://c.net/?utm_source=4>", "\t", "\"https://d.io/q?utm_source=5\"", "!? ",
            "https://e.com/a\"b?utm_source=6&z=7;", "  ", "https://", " x ", "https://f.com).",
        )
        val input = buildString { repeat(400) { i -> append(pieces[i % pieces.size]); append(pieces[(i * 7) % pieces.size]) } }
        val reference = pattern.replace(input) { sanitizer.process(it.value) }
        assertThat(sanitizer.process(input)).isEqualTo(reference)
    }

    @Test
    fun processScansTheClipWithOneMatcher() {
        val source = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizer.kt"
        ).readText()
        val body = source.substringAfter("override fun process(text: String): String")
            .substringBefore("private fun sanitizeOne(")
        assertWithMessage("process() must not walk matches with Regex.replace/findAll/next() (one Matcher per match)")
            .that(Regex("""\.replace\(text\)|findAll\(|\.next\(\)""").containsMatchIn(body)).isFalse()
        assertWithMessage("process() scans with one java.util.regex.Matcher and an explicit find loop")
            .that(body).contains(".matcher(text)")
    }
}
