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

    // ── shouldOfferAddToDictionary (UT-2 possessives, UT-3 URLs) ─────────

    /** Tiny dictionary standing in for main-dict + user-dict union. */
    private val known = setOf("ember", "dog", "rivers", "example", "com", "foo")
    private val disabled = mutableSetOf<String>()

    /**
     * Drives the guard the way SuggestionHandler does: the non-prose flag is
     * computed from the editor text before the cursor (token + just-typed
     * space), dictionary state via callbacks.
     */
    private fun prompts(token: String, textBeforeCursor: String = "$token "): Boolean =
        AutocorrectContextGuard.shouldOfferAddToDictionary(
            token = token,
            inNonProseToken = AutocorrectContextGuard.isNonProseContext(textBeforeCursor),
            isKnownWord = { it.lowercase() in known },
            isDisabledWord = { it.lowercase() in disabled },
        )

    @Test
    fun validPossessive_doesNotPrompt() {
        // UT-2: "ember's" — base "ember" is a known word, so the possessive
        // is valid English, not an unknown word.
        assertThat(prompts("ember's")).isFalse()
        assertThat(prompts("dog's")).isFalse()
    }

    @Test
    fun curlyApostrophePossessive_doesNotPrompt() {
        // U+2019 must be treated the same as U+0027 (mirrors autocorrect 1.6).
        assertThat(prompts("ember’s")).isFalse()
    }

    @Test
    fun bareApostrophePluralPossessive_doesNotPrompt() {
        // "rivers'" — plural possessive (empty suffix after apostrophe).
        assertThat(prompts("rivers'")).isFalse()
        assertThat(prompts("rivers’")).isFalse()
    }

    @Test
    fun possessiveOfUnknownBase_stillPrompts() {
        // Chosen behavior: "embeer's" has NO known base, so the token really
        // is unknown → prompt. (In practice autocorrect's AC-4 possessive-base
        // correction usually fires first and the prompt path is never
        // reached; this pins the fallback when autocorrect is disabled.)
        assertThat(prompts("embeer's")).isTrue()
    }

    @Test
    fun possessiveOfDisabledBase_stillPrompts() {
        // Chosen behavior (mirrors autocorrect UT-8): disabling a word
        // removes its possessive protection — the possessive is NOT
        // suppressed as "known" and the prompt is offered.
        disabled.add("ember")
        try {
            assertThat(prompts("ember's")).isTrue()
        } finally {
            disabled.remove("ember")
        }
    }

    @Test
    fun contractionSuffixes_notTreatedAsPossessive() {
        // Only "'s" and bare "'" get base-word protection; "'ll"/"'nt" style
        // suffixes stay on the normal unknown-word path (contraction aliases
        // live in the dictionary itself when valid).
        assertThat(prompts("ember'll")).isTrue()
    }

    @Test
    fun urlToken_doesNotPrompt() {
        // UT-3: the tracker's letters-only word ("flibber") is unknown, but
        // the editor text shows it is a URL fragment → never prompt.
        assertThat(prompts("flibber", textBeforeCursor = "see example.com/flibber ")).isFalse()
    }

    @Test
    fun emailToken_doesNotPrompt() {
        assertThat(prompts("flibber", textBeforeCursor = "mail flibber@example.com ")).isFalse()
    }

    @Test
    fun plainUnknownWord_stillPrompts() {
        // Regression: ordinary unknown prose words must keep prompting.
        assertThat(prompts("flibbertigib")).isTrue()
        assertThat(prompts("flibbertigib", textBeforeCursor = "hello flibbertigib ")).isTrue()
    }

    @Test
    fun knownAndShortTokens_doNotPrompt() {
        // Original gates preserved: known words and <3-char tokens.
        assertThat(prompts("ember")).isFalse()
        assertThat(prompts("ab")).isFalse()
    }
}
