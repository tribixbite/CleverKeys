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
 * change: a mark with no base, a mark after a Latin letter or a digit, and the marks of other
 * scripts (Persian harakat, Hebrew niqqud, Latin combining accents, Devanagari) keep the
 * classification they had. The test words are entries of WM Keyboard's `bn.txt`.
 */
class BanglaSpellingMarkBoundaryTest {

    private val ami = "আমি"          // "I": আ ম + ি (Mc vowel sign)
    private val kukur = "কুকুর"       // "dog": ু (Mn u-sign) twice
    private val khoma = "ক্ষমা"       // "forgiveness": ক + ্ (hasanta) + ষ, a conjunct
    private val bangla = "বাংলা"      // "Bangla": া ং (anusvara) া
    private val hindi = "किताब"       // Devanagari: ि is a mark of a script without the rule (yet)
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
        assertWithMessage("Devanagari vowel sign").that(WordChars.isWordCharAt(hindi, 1)).isFalse()
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
        assertWithMessage("Devanagari still splits at its sign")
            .that(TextActionPolicy.wordAtCursor(hindi, "").backward).isEqualTo(1)
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
        assertWithMessage("Devanagari still splits at its sign, as before")
            .that(NextWordPredictor.contextFromEditorText("$hindi "))
            .containsExactly("क", "त", "ब").inOrder()
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
}
