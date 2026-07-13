package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [AutocorrectContextGuard] — the URL/email/path context
 * detector that suppresses autocorrect in non-prose tokens (2026-07-13 fix).
 * The instrumented end-to-end coverage lives in AutocorrectUrlGuardTest.
 */
class AutocorrectContextGuardTest {

    private fun nonProse(s: String) = AutocorrectContextGuard.isNonProseContext(s)

    // ── must flag (skip autocorrect) ─────────────────────────────────────

    @Test
    fun dottedDomain_flagged() {
        assertThat(nonProse("visit foo.teh ")).isTrue()
        assertThat(nonProse("foo.teh ")).isTrue()
    }

    @Test
    fun url_flagged() {
        assertThat(nonProse("see https://teh.example ")).isTrue()
        assertThat(nonProse("www.teh ")).isTrue()
    }

    @Test
    fun email_flagged() {
        assertThat(nonProse("mail user@teh ")).isTrue()
    }

    @Test
    fun path_flagged() {
        assertThat(nonProse("open /etc/teh ")).isTrue()
        assertThat(nonProse("C:\\temp\\teh ")).isTrue()
    }

    @Test
    fun queryAndFragment_flagged() {
        assertThat(nonProse("x?q=teh ")).isTrue()
        assertThat(nonProse("page#teh ")).isTrue()
    }

    @Test
    fun digitBearingToken_flagged() {
        assertThat(nonProse("v1teh ")).isTrue()
        assertThat(nonProse("abc123 ")).isTrue()
    }

    // ── must NOT flag (autocorrect proceeds) ─────────────────────────────

    @Test
    fun plainProse_notFlagged() {
        assertThat(nonProse("visit teh ")).isFalse()
        assertThat(nonProse("teh ")).isFalse()
    }

    @Test
    fun priorSentencePunctuation_notFlagged() {
        // The '.' belongs to the PREVIOUS token; the current token is clean.
        assertThat(nonProse("done. teh ")).isFalse()
    }

    @Test
    fun hyphenAndApostrophe_notFlagged() {
        // Compounds and contractions must keep autocorrecting.
        assertThat(nonProse("well-knwon ")).isFalse()
        assertThat(nonProse("dont ")).isFalse()
    }

    @Test
    fun nullAndBlank_notFlagged() {
        assertThat(AutocorrectContextGuard.isNonProseContext(null)).isFalse()
        assertThat(AutocorrectContextGuard.isNonProseContext("")).isFalse()
        assertThat(AutocorrectContextGuard.isNonProseContext(" ")).isFalse()
    }
}
