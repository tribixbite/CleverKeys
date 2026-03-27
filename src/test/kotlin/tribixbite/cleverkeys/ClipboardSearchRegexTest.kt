package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.regex.PatternSyntaxException

/**
 * Pure JVM tests for clipboard regex search functionality.
 *
 * Tests the glob-to-regex shorthand expansion and regex matching behavior
 * without requiring Android framework (operates on ClipboardSearchUtils
 * pure-Kotlin object and standard Kotlin/Java regex).
 */
class ClipboardSearchRegexTest {

    // =========================================================================
    // expandGlobShorthand() — shorthand expansion
    // =========================================================================

    @Test
    fun `bare star becomes dot-star wildcard`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello*world"))
            .isEqualTo("hello.*world")
    }

    @Test
    fun `bare question mark becomes dot wildcard`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("he?lo"))
            .isEqualTo("he.lo")
    }

    @Test
    fun `dot-star passes through unchanged`() {
        // .* is already valid regex — don't double-expand to ..*
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello.*world"))
            .isEqualTo("hello.*world")
    }

    @Test
    fun `escaped star passes through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello\\*world"))
            .isEqualTo("hello\\*world")
    }

    @Test
    fun `escaped question mark passes through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello\\?world"))
            .isEqualTo("hello\\?world")
    }

    @Test
    fun `anchors pass through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("^start"))
            .isEqualTo("^start")
        assertThat(ClipboardSearchUtils.expandGlobShorthand("end$"))
            .isEqualTo("end$")
    }

    @Test
    fun `alternation passes through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("cat|dog"))
            .isEqualTo("cat|dog")
    }

    @Test
    fun `escape sequences pass through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("\\d+"))
            .isEqualTo("\\d+")
    }

    @Test
    fun `character classes pass through unchanged`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("[abc]"))
            .isEqualTo("[abc]")
    }

    @Test
    fun `mixed glob and regex`() {
        // "find*[0-9]?" → "find.*[0-9]."
        assertThat(ClipboardSearchUtils.expandGlobShorthand("find*[0-9]?"))
            .isEqualTo("find.*[0-9].")
    }

    @Test
    fun `empty string returns empty`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("")).isEqualTo("")
    }

    @Test
    fun `only star becomes dot-star`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("*")).isEqualTo(".*")
    }

    @Test
    fun `double star becomes double dot-star`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("**")).isEqualTo(".*.*")
    }

    @Test
    fun `star at start and end`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("*hello*"))
            .isEqualTo(".*hello.*")
    }

    @Test
    fun `question mark at start`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("?ello"))
            .isEqualTo(".ello")
    }

    @Test
    fun `no metacharacters — literal passthrough`() {
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello"))
            .isEqualTo("hello")
    }

    @Test
    fun `trailing backslash passes through`() {
        // Edge case: backslash at end with nothing following — just append it
        assertThat(ClipboardSearchUtils.expandGlobShorthand("hello\\"))
            .isEqualTo("hello\\")
    }

    // =========================================================================
    // Regex matching — end-to-end via compiled patterns
    // =========================================================================

    /** Helper: simulates regex mode search matching an entry */
    private fun regexMatches(pattern: String, content: String): Boolean {
        val expanded = ClipboardSearchUtils.expandGlobShorthand(pattern)
        return Regex(expanded, RegexOption.IGNORE_CASE).containsMatchIn(content)
    }

    @Test
    fun `glob wildcard matches across words`() {
        assertThat(regexMatches("hello*world", "hello beautiful world")).isTrue()
    }

    @Test
    fun `glob wildcard matches adjacent`() {
        assertThat(regexMatches("hello*world", "helloworld")).isTrue()
    }

    @Test
    fun `question mark matches single character`() {
        assertThat(regexMatches("he?lo", "hello")).isTrue()
        assertThat(regexMatches("he?lo", "helo")).isFalse()
    }

    @Test
    fun `case insensitive matching`() {
        assertThat(regexMatches("HELLO", "hello world")).isTrue()
        assertThat(regexMatches("hello", "HELLO WORLD")).isTrue()
    }

    @Test
    fun `anchor caret matches start only`() {
        assertThat(regexMatches("^http", "https://example.com")).isTrue()
        assertThat(regexMatches("^http", "not https://example.com")).isFalse()
    }

    @Test
    fun `anchor dollar matches end only`() {
        assertThat(regexMatches("com$", "https://example.com")).isTrue()
        assertThat(regexMatches("com$", "https://example.com/path")).isFalse()
    }

    @Test
    fun `alternation matches either branch`() {
        assertThat(regexMatches("cat|dog", "I have a cat")).isTrue()
        assertThat(regexMatches("cat|dog", "I have a dog")).isTrue()
        assertThat(regexMatches("cat|dog", "I have a fish")).isFalse()
    }

    @Test
    fun `character class matches`() {
        assertThat(regexMatches("[0-9]+", "order 42 placed")).isTrue()
        assertThat(regexMatches("[0-9]+", "no numbers here")).isFalse()
    }

    @Test
    fun `digit escape sequence`() {
        assertThat(regexMatches("\\d{3}", "code 123 done")).isTrue()
        assertThat(regexMatches("\\d{3}", "code 12 done")).isFalse()
    }

    @Test
    fun `dot-star passthrough works as regex wildcard`() {
        assertThat(regexMatches("hello.*world", "hello cruel world")).isTrue()
    }

    // =========================================================================
    // Invalid regex — error handling
    // =========================================================================

    @Test
    fun `unclosed bracket throws PatternSyntaxException`() {
        val expanded = ClipboardSearchUtils.expandGlobShorthand("[unclosed")
        var caught = false
        try {
            Regex(expanded, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            caught = true
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `unclosed paren throws PatternSyntaxException`() {
        val expanded = ClipboardSearchUtils.expandGlobShorthand("(unclosed")
        var caught = false
        try {
            Regex(expanded, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            caught = true
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `dangling quantifier throws PatternSyntaxException`() {
        val expanded = ClipboardSearchUtils.expandGlobShorthand("+")
        var caught = false
        try {
            Regex(expanded, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            caught = true
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun `valid patterns do not throw`() {
        // Ensure common patterns compile without error
        val patterns = listOf("hello", "hello*world", "^http", "a|b", "\\d+", "[abc]", "he?lo")
        for (p in patterns) {
            val expanded = ClipboardSearchUtils.expandGlobShorthand(p)
            // Should not throw
            Regex(expanded, RegexOption.IGNORE_CASE)
        }
    }
}
