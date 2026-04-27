package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * RED tests for issue #118 — emoji panel renders all emojis as "..." on
 * high-DPI / custom-font devices (Pixel 8 Pro 595 DPI + OpenDyslexic font).
 *
 * Root cause: `EmojiGridView.EmojiView.init { ellipsize = TruncateAt.END }`
 * is set unconditionally for every cell, including real Unicode emojis.
 * When a glyph's measured width (paint.measureText) exceeds the cell width,
 * TextView replaces it with the ellipsis "…" instead of clipping. This
 * affects every cell at once when the font fallback chain reports wider
 * metrics than the column width allows.
 *
 * Fix contract:
 *   1. The unconditional `ellipsize = TruncateAt.END` line in `init {}` must
 *      be removed (or guarded so it doesn't apply to real emojis).
 *   2. The regular-emoji branch in `setEmoji()` must explicitly clear
 *      ellipsize (`ellipsize = null`) so that wider-than-cell glyphs clip
 *      rather than collapse to "…".
 *   3. The emoticon branch may still set ellipsize (long ASCII strings like
 *      `¯\_(ツ)_/¯` legitimately need truncation), but the regular path
 *      must not.
 *
 * Why source-pattern: rendering emoji to a real Canvas requires a
 * Robolectric-or-instrumented fixture that loads the system emoji font.
 * That belongs in an instrumented test (added separately). These pure
 * tests pin the contract so the fix isn't reverted.
 */
class Issue118EmojiOverflowTest {

    private val srcDir = File("src/main/kotlin/tribixbite/cleverkeys")

    private fun readSource(filename: String): String {
        val file = File(srcDir, filename)
        require(file.exists()) { "Source file not found: ${file.absolutePath}" }
        return file.readText()
    }

    /**
     * Locate the body of `EmojiView.init {}` so assertions don't accidentally
     * pick up matches from `setEmoji()` or other methods. The init block is
     * delimited by `init {` and the next top-level `}` at the same indent.
     */
    private fun emojiViewInitBlock(source: String): String {
        val viewClassStart = source.indexOf("class EmojiView(context: Context)")
        require(viewClassStart >= 0) { "EmojiView class not found in EmojiGridView.kt" }
        val classSlice = source.substring(viewClassStart)
        val initStart = classSlice.indexOf("init {")
        require(initStart >= 0) { "init {} block not found in EmojiView" }
        val afterInit = classSlice.substring(initStart + "init {".length)
        var depth = 1
        var idx = 0
        while (idx < afterInit.length && depth > 0) {
            when (afterInit[idx]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) break
            idx++
        }
        return afterInit.substring(0, idx)
    }

    /**
     * Locate the regular-emoji branch (the `else { ... }` of the
     * `if (isTextEmoticon) ... else ...` in `setEmoji()`).
     */
    private fun regularEmojiBranch(source: String): String {
        val setEmojiIdx = source.indexOf("fun setEmoji(emoji: Emoji)")
        require(setEmojiIdx >= 0) { "setEmoji() not found in EmojiGridView.kt" }
        val tail = source.substring(setEmojiIdx)
        val emoticonIdx = tail.indexOf("if (isTextEmoticon)")
        require(emoticonIdx >= 0) { "isTextEmoticon branch not found in setEmoji()" }
        val elseIdx = tail.indexOf("else", emoticonIdx)
        require(elseIdx >= 0) { "else branch (regular emoji path) not found in setEmoji()" }
        val openBrace = tail.indexOf('{', elseIdx)
        require(openBrace >= 0) { "regular-emoji branch opening brace not found" }
        val body = tail.substring(openBrace + 1)
        var depth = 1
        var idx = 0
        while (idx < body.length && depth > 0) {
            when (body[idx]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) break
            idx++
        }
        return body.substring(0, idx)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RED #1 — `init {}` must not unconditionally ellipsize every emoji cell
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun emojiView_init_doesNotSetUnconditionalEllipsize() {
        val source = readSource("EmojiGridView.kt")
        val initBody = emojiViewInitBlock(source)
        // The buggy line that affects every cell — must be gone or moved to the
        // emoticon-only branch in setEmoji().
        val hasUnconditionalTruncate = initBody.contains("ellipsize") &&
            initBody.contains("TruncateAt.END")
        assertThat(hasUnconditionalTruncate).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RED #2 — regular-emoji branch must clear ellipsize so wider-than-cell
    //          glyphs clip instead of collapsing to "…"
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun emojiView_setEmoji_clearsEllipsizeForRegularEmoji() {
        val source = readSource("EmojiGridView.kt")
        val regularBranch = regularEmojiBranch(source)
        // Either explicit `ellipsize = null` or any sentinel that clears the
        // truncation behavior for real emojis.
        val clearsEllipsize = regularBranch.contains("ellipsize = null") ||
            regularBranch.contains("ellipsize=null")
        assertThat(clearsEllipsize).isTrue()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GREEN — emoticon detection logic should still classify a real Unicode
    //         emoji (e.g. 😀 = U+1F600) as NOT-an-emoticon, so the regular
    //         branch handles it. This guards against a regression that
    //         "fixes" the overflow by routing real emojis through the
    //         scaling code path.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun emojiView_isEmoticon_classifiesRealEmojiAsNonEmoticon() {
        val source = readSource("EmojiGridView.kt")
        val funIdx = source.indexOf("private fun isEmoticon(str: String): Boolean")
        assertThat(funIdx).isAtLeast(0)
        // Sanity-check that the heuristic still uses the surrogate-pair /
        // high-codepoint counter (asciiCount > emojiCount → emoticon).
        val tail = source.substring(funIdx)
        val openBrace = tail.indexOf('{')
        val body = tail.substring(openBrace + 1, tail.indexOf("\n    }", openBrace))
        assertThat(body).contains("isHighSurrogate")
        assertThat(body).contains("0x2600")
        assertThat(body).contains("asciiCount > emojiCount")
    }
}
