package tribixbite.cleverkeys.clipboard.sanitize

import com.google.common.truth.Truth.assertThat
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
}
