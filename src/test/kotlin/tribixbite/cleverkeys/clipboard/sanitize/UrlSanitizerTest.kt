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
}
