package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.UserJoinerPreference

/**
 * Bangla spelling marks at the word-boundary predicate and its CALL SITES (maintainer approval
 * 2026-11-04; kit `ml/rtx-kits/19-bangla-pack` §3). Pure JVM.
 *
 * A Bengali vowel sign, hasanta, nukta, candrabindu, anusvara or visarga is a combining mark,
 * not a letter, so the `isLetterOrDigit` scanners cut `আমি` into `আম` + `ি`. Every scanner must
 * now agree that a mark on a letter of its script is inside the word, and nothing else may
 * change: a mark with no base, a mark after a Latin letter or a digit, and the marks of
 * non-Indic scripts (Persian harakat, Hebrew niqqud, Latin combining accents) keep the
 * classification they had. The test words are entries of WM Keyboard's `bn.txt`.
 *
 * Since 2026-11-06 Devanagari, Gujarati, Kannada, Tamil and Sinhala get the same rule
 * ([IndicSpellingMarkBoundaryTest]); the assertions below that pinned Devanagari as UNCHANGED now
 * pin it whole. ZWJ inside a Bengali word (`র‍্যাব`) is covered at the end of this class.
 */
class BanglaSpellingMarkBoundaryTest {

    private val ami = "আমি"          // "I": আ ম + ি (Mc vowel sign)
    private val kukur = "কুকুর"       // "dog": ু (Mn u-sign) twice
    private val khoma = "ক্ষমা"       // "forgiveness": ক + ্ (hasanta) + ষ, a conjunct
    private val bangla = "বাংলা"      // "Bangla": া ং (anusvara) া
    private val hindi = "किताब"       // Devanagari "book": ि and ा, marks of a rule script since 2026-11-06
    private val zwj = "\u200D"
    private val rab = "র${zwj}্যাব"     // "RAB": র + ZWJ + ্ + য (the ra-phala), া, ব
    private val fathaWord = "كَتَبَ"     // Arabic with harakat (Mn)
    private val decomposedCafe = "café"

    // ── WordChars predicates ────────────────────────────────────────────────────────────

    @Test
    fun `every mark of a Bangla word is a word character`() {
        for (word in listOf(ami, kukur, khoma, bangla)) {
            for (i in word.indices) {
                assertWithMessage("$word[$i] U+${"%04X".format(word[i].code)}")
                    .that(WordChars.isWordCharAt(word, i)).isTrue()
            }
        }
    }

    @Test
    fun `a Bengali mark without a Bengali letter base is not a word character`() {
        assertWithMessage("mark at the start").that(WordChars.isAttachedSpellingMarkAt("ি", 0)).isFalse()
        assertWithMessage("mark after a space").that(WordChars.isAttachedSpellingMarkAt("আম ি", 3)).isFalse()
        assertWithMessage("mark after a Latin letter").that(WordChars.isAttachedSpellingMarkAt("aি", 1)).isFalse()
        assertWithMessage("mark after a Bengali digit").that(WordChars.isAttachedSpellingMarkAt("১ি", 1)).isFalse()
    }

    @Test
    fun `marks of other scripts keep their old classification`() {
        assertWithMessage("Devanagari vowel sign: now a rule script").that(WordChars.isWordCharAt(hindi, 1)).isTrue()
        assertWithMessage("Arabic fatha").that(WordChars.isWordCharAt(fathaWord, 1)).isFalse()
        assertWithMessage("Latin combining acute").that(WordChars.isWordCharAt(decomposedCafe, 4)).isFalse()
    }

    @Test
    fun `a mark right after the cursor takes its base from the text before it`() {
        assertWithMessage("আম|ি").that(WordChars.isSpellingMarkWordCharAfterCursor("ি", 0, "আম")).isTrue()
        assertWithMessage("ক|্ষ").that(WordChars.isSpellingMarkWordCharAfterCursor("্ষ", 0, "ক")).isTrue()
        assertWithMessage("nothing before the cursor")
            .that(WordChars.isSpellingMarkWordCharAfterCursor("ি", 0, "")).isFalse()
        assertWithMessage("a space before the cursor")
            .that(WordChars.isSpellingMarkWordCharAfterCursor("ি", 0, "আম ")).isFalse()
    }

    // ── TextActionPolicy.wordAtCursor (select word / text actions) ──────────────────────

    @Test
    fun `word at cursor spans the marks of a Bangla word`() {
        val inside = TextActionPolicy.wordAtCursor("আমি কু", "কুর")
        assertWithMessage("backward covers কু").that(inside.backward).isEqualTo(2)
        assertWithMessage("forward covers কুর").that(inside.forward).isEqualTo(3)
        val beforeMark = TextActionPolicy.wordAtCursor("ক্ষমা আম", "ি")
        assertWithMessage("a cursor between a letter and its sign: আম|ি").that(beforeMark.backward).isEqualTo(2)
        assertWithMessage("the sign after the cursor belongs to the word").that(beforeMark.forward).isEqualTo(1)
        val afterMark = TextActionPolicy.wordAtCursor("তুমি $khoma", "")
        assertWithMessage("the conjunct word ending at the cursor").that(afterMark.backward).isEqualTo(khoma.length)
    }

    @Test
    fun `word at cursor is unchanged for other scripts`() {
        assertWithMessage("Devanagari selects whole since 2026-11-06")
            .that(TextActionPolicy.wordAtCursor(hindi, "").backward).isEqualTo(hindi.length)
        assertWithMessage("apostrophe rule kept").that(TextActionPolicy.wordAtCursor("we don't", "").backward)
            .isEqualTo(5)
    }

    // ── EditorTextNavigation and VerifiedSuffixEdit (already mark-aware: pinned, not changed) ──

    @Test
    fun `editor navigation selects a whole Bangla word`() {
        val text = "$ami $khoma"
        assertWithMessage("select inside ক্ষমা").that(EditorTextNavigation.wordBounds(text, 6, 6))
            .isEqualTo(4 until text.length)
        assertWithMessage("word left from the end lands on ক")
            .that(EditorTextNavigation.previousWordStart(text, text.length)).isEqualTo(4)
    }

    @Test
    fun `an owned suffix does not split a Bangla word at a mark`() {
        assertWithMessage("a sign before the tail continues the word")
            .that(VerifiedSuffixEdit.isWordContinuationAt(ami, 2)).isTrue()
    }

    // ── NextWordPredictor tokeniser (bigram context) ─────────────────────────────────────

    @Test
    fun `next-word context keeps Bangla words whole`() {
        assertWithMessage("each word is one token")
            .that(NextWordPredictor.contextFromEditorText("$ami $kukur $khoma "))
            .containsExactly(ami, kukur, khoma).inOrder()
    }

    @Test
    fun `next-word context is unchanged for other scripts`() {
        assertWithMessage("English apostrophe kept").that(NextWordPredictor.contextFromEditorText("i don't know "))
            .containsExactly("i", "don't", "know").inOrder()
        assertWithMessage("Devanagari is one token since 2026-11-06")
            .that(NextWordPredictor.contextFromEditorText("$hindi "))
            .containsExactly(hindi).inOrder()
    }

    // ── Shared isWordCharAt callers: emoji search token, swipe-correction joining ─────────

    @Test
    fun `the shared word rule spans a Bangla word from its end`() {
        // EmojiSearchManager and SwipeCorrectionTracker walk back with isWordCharAt.
        val text = "আমি $bangla"
        var start = text.length
        while (start > 0 && WordChars.isWordCharAt(text, start - 1)) start--
        assertWithMessage("the walk-back stops at the space").that(text.substring(start)).isEqualTo(bangla)
    }

    // ── Swipe: user joiner words ─────────────────────────────────────────────────────────

    @Test
    fun `a hyphenated Bangla user word keeps its marks in the joiner-free surface`() {
        assertWithMessage("আমি-তুমি").that(UserJoinerPreference.joinerFree("$ami-তুমি")).isEqualTo("আমিতুমি")
        assertWithMessage("a mark straight after the hyphen has no base")
            .that(UserJoinerPreference.joinerFree("আম-ি")).isNull()
    }

    // ── ZWJ inside a Bengali word (2026-11-06) ───────────────────────────────────────────

    @Test
    fun `every character of a Bengali word with a ZWJ is a word character`() {
        for (i in rab.indices) {
            assertWithMessage("$rab[$i] U+${"%04X".format(rab[i].code)}")
                .that(WordChars.isWordCharAt(rab, i)).isTrue()
        }
        assertWithMessage("the hasanta finds র across the ZWJ")
            .that(WordChars.isAttachedSpellingMarkAt(rab, 2)).isTrue()
    }

    @Test
    fun `a ZWJ outside a Bengali word is not a word character`() {
        assertWithMessage("between Latin letters").that(WordChars.isJoiningZwjAt("a${zwj}b", 1)).isFalse()
        assertWithMessage("emoji sequence").that(WordChars.isWordCharAt("\uD83D\uDC68$zwj\uD83D\uDC69", 2)).isFalse()
        assertWithMessage("trailing").that(WordChars.isJoiningZwjAt("র$zwj", 1)).isFalse()
        assertWithMessage("leading").that(WordChars.isJoiningZwjAt("${zwj}র", 0)).isFalse()
        assertWithMessage("before a space").that(WordChars.isJoiningZwjAt("র$zwj য", 1)).isFalse()
        assertWithMessage("Bengali then Latin").that(WordChars.isJoiningZwjAt("র${zwj}a", 1)).isFalse()
        assertWithMessage("after a Bengali digit").that(WordChars.isJoiningZwjAt("১${zwj}্য", 1)).isFalse()
        assertWithMessage("Devanagari is not a ZWJ script (TODO in WordChars)")
            .that(WordChars.isJoiningZwjAt("र्${zwj}य", 2)).isFalse()
        assertWithMessage("edge flag").that(WordChars.hasEdgeZwj("র$zwj")).isTrue()
        assertWithMessage("inner is not an edge").that(WordChars.hasEdgeZwj(rab)).isFalse()
    }

    @Test
    fun `a ZWJ just typed before the cursor stays with its Bengali word`() {
        assertWithMessage("nothing after").that(WordChars.isZwjWordCharBeforeCursor("র$zwj", 1, "")).isTrue()
        assertWithMessage("hasanta after").that(WordChars.isZwjWordCharBeforeCursor("র$zwj", 1, "্যাব")).isTrue()
        assertWithMessage("space after").that(WordChars.isZwjWordCharBeforeCursor("র$zwj", 1, " ")).isTrue()
        assertWithMessage("Latin letter after").that(WordChars.isZwjWordCharBeforeCursor("র$zwj", 1, "ab")).isFalse()
        assertWithMessage("after a Latin letter").that(WordChars.isZwjWordCharBeforeCursor("a$zwj", 1, "")).isFalse()
        assertWithMessage("after the cursor: base from before")
            .that(WordChars.isZwjWordCharAfterCursor("$zwj্যাব", 0, "র")).isTrue()
        assertWithMessage("a mark after a ZWJ after the cursor finds its base before it")
            .that(WordChars.isSpellingMarkWordCharAfterCursor("$zwj্যাব", 1, "র")).isTrue()
    }

    @Test
    fun `a Bengali word with a ZWJ is whole at every call site`() {
        val span = TextActionPolicy.wordAtCursor("$ami র$zwj", "্যাব")
        assertWithMessage("select: backward র + ZWJ").that(span.backward).isEqualTo(2)
        assertWithMessage("select: forward ্যাব").that(span.forward).isEqualTo(4)
        assertWithMessage("next-word token").that(NextWordPredictor.contextFromEditorText("$ami $rab "))
            .containsExactly(ami, rab).inOrder()
        val text = "$ami $rab"
        assertWithMessage("navigation selects the word").that(EditorTextNavigation.wordBounds(text, 6, 6))
            .isEqualTo(4 until text.length)
        assertWithMessage("an owned suffix does not split at the ZWJ")
            .that(VerifiedSuffixEdit.isWordContinuationAt(rab, 1)).isTrue()
        assertWithMessage("a Latin ZWJ is still no continuation")
            .that(VerifiedSuffixEdit.isWordContinuationAt("a${zwj}b", 1)).isFalse()
        assertWithMessage("joiner-free surface drops the ZWJ, keeps the marks")
            .that(UserJoinerPreference.joinerFree("$rab-x")).isEqualTo("র্যাবx")
    }
}
