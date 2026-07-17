package tribixbite.cleverkeys.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.clipboard.PrivateCopyIntentParser.Reason
import tribixbite.cleverkeys.clipboard.PrivateCopyIntentParser.Result

/**
 * #156 pure-JVM tests for [PrivateCopyIntentParser] — the ONLY intent-reading code for the
 * exported PROCESS_TEXT activity (entry point B).
 *
 * SECURITY NOTE (design §6.2 row 5): the hostile-extra matrix (clipData / EXTRA_STREAM / URIs) is
 * moot BY CONSTRUCTION — [PrivateCopyIntentParser.parse] takes only an action String, a text
 * CharSequence, and a byte cap; it has no `Intent` parameter, so it cannot receive any other extra.
 * That API shape is asserted here by compilation: these tests can only pass primitives + text.
 */
class PrivateCopyIntentParserTest {

    private val action = PrivateCopyIntentParser.ACTION_PROCESS_TEXT
    private val cap = 512 * 1024  // 512 KB-class per-item cap (mirrors the service)

    @Test
    fun accept_normalText() {
        val r = PrivateCopyIntentParser.parse(action, "hello world", cap)
        assertThat(r).isInstanceOf(Result.Accept::class.java)
        assertThat((r as Result.Accept).text).isEqualTo("hello world")
    }

    @Test
    fun accept_trimsSurroundingWhitespace() {
        val r = PrivateCopyIntentParser.parse(action, "  padded  ", cap)
        assertThat((r as Result.Accept).text).isEqualTo("padded")
    }

    @Test
    fun accept_readonlyIsIrrelevant_sameActionSameResult() {
        // READONLY is a separate extra the ACTIVITY reads for logging only; the parser never sees
        // it and behavior is identical. Copying from a read-only view is a primary use case.
        val r = PrivateCopyIntentParser.parse(action, "read only selection", cap)
        assertThat((r as Result.Accept).text).isEqualTo("read only selection")
    }

    @Test
    fun accept_exactlyAtCap() {
        // 100 ASCII bytes, cap = 100 → accepted (boundary is `> cap` rejects).
        val text = "a".repeat(100)
        val r = PrivateCopyIntentParser.parse(action, text, 100)
        assertThat(r).isInstanceOf(Result.Accept::class.java)
    }

    @Test
    fun accept_capDisabledWhenNonPositive() {
        // maxBytes <= 0 disables the cap check (mirrors the service's `maxSizeKb > 0` guard).
        val big = "z".repeat(10_000)
        val r = PrivateCopyIntentParser.parse(action, big, 0)
        assertThat(r).isInstanceOf(Result.Accept::class.java)
    }

    @Test
    fun reject_wrongAction() {
        val r = PrivateCopyIntentParser.parse("android.intent.action.VIEW", "text", cap)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.WRONG_ACTION)
    }

    @Test
    fun reject_nullAction() {
        val r = PrivateCopyIntentParser.parse(null, "text", cap)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.WRONG_ACTION)
    }

    @Test
    fun reject_nullExtra() {
        val r = PrivateCopyIntentParser.parse(action, null, cap)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.NO_EXTRA)
    }

    @Test
    fun reject_emptyText() {
        val r = PrivateCopyIntentParser.parse(action, "", cap)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.BLANK)
    }

    @Test
    fun reject_whitespaceOnly() {
        val r = PrivateCopyIntentParser.parse(action, "   \n\t  ", cap)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.BLANK)
    }

    @Test
    fun reject_overCap_ascii() {
        val text = "a".repeat(101)
        val r = PrivateCopyIntentParser.parse(action, text, 100)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.OVER_CAP)
    }

    @Test
    fun overCap_countsUtf8Bytes_notCharacters() {
        // "é" is 1 char but 2 UTF-8 bytes. 60 of them = 60 chars / 120 bytes.
        // cap = 100 bytes → the byte count (120) exceeds it even though char count (60) does not.
        val multibyte = "é".repeat(60)
        assertThat(multibyte.length).isEqualTo(60)
        val r = PrivateCopyIntentParser.parse(action, multibyte, 100)
        assertThat((r as Result.Reject).reason).isEqualTo(Reason.OVER_CAP)
    }

    @Test
    fun multibyteUnderCap_accepted() {
        // 40 "é" = 80 bytes, under a 100-byte cap → accepted.
        val multibyte = "é".repeat(40)
        val r = PrivateCopyIntentParser.parse(action, multibyte, 100)
        assertThat(r).isInstanceOf(Result.Accept::class.java)
    }

    @Test
    fun charSequenceExtra_toStringed() {
        // The extra arrives as a CharSequence (SpannableString etc.); we store the plain text.
        val cs: CharSequence = StringBuilder("builder text")
        val r = PrivateCopyIntentParser.parse(action, cs, cap)
        assertThat((r as Result.Accept).text).isEqualTo("builder text")
    }
}
