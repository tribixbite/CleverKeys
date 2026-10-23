package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The shared half-space rule ([WordChars]) and the half-space spelling index
 * ([HalfSpaceVariants]) — the predicate matrix behind decision (b) and the offer behind (d)
 * (2026-10-23, research `docs/research/2026-10-21-persian-zwnj.md` §6–§7). Pure JVM.
 */
class WordCharsTest {

    private val z = "‌"

    // ── the predicate matrix ─────────────────────────────────────────────────────────────

    @Test
    fun `half-space between Persian letters is a word character`() {
        val text = "می${z}شود"
        assertWithMessage("می‌شود index 2").that(WordChars.isJoiningZwnjAt(text, 2)).isTrue()
        assertWithMessage("letters stay word chars").that(WordChars.isWordCharAt(text, 0)).isTrue()
        assertWithMessage("کتاب‌ها").that(WordChars.isJoiningZwnjAt("کتاب${z}ها", 4)).isTrue()
    }

    @Test
    fun `combining marks before the half-space are skipped`() {
        // ب + kasra (U+0650) + ZWNJ + ه: the mark is not the neighbour, ب is.
        val text = "کتابِ${z}ها"
        assertWithMessage("kasra skipped").that(WordChars.isJoiningZwnjAt(text, 5)).isTrue()
    }

    @Test
    fun `half-space at a word edge is a boundary`() {
        assertWithMessage("leading").that(WordChars.isJoiningZwnjAt("${z}شود", 0)).isFalse()
        assertWithMessage("trailing").that(WordChars.isJoiningZwnjAt("می$z", 2)).isFalse()
        assertWithMessage("before a space").that(WordChars.isJoiningZwnjAt("می$z شود", 2)).isFalse()
        assertWithMessage("between digits").that(WordChars.isJoiningZwnjAt("۲${z}۳", 1)).isFalse()
        assertWithMessage("edge flag").that(WordChars.hasEdgeZwnj("می$z")).isTrue()
        assertWithMessage("inner is not an edge").that(WordChars.hasEdgeZwnj("می${z}شود")).isFalse()
    }

    @Test
    fun `cursor edge keeps a just-typed half-space with its word`() {
        assertWithMessage("nothing after the cursor")
            .that(WordChars.isZwnjWordCharBeforeCursor("می$z", 2, "")).isTrue()
        assertWithMessage("space after the cursor")
            .that(WordChars.isZwnjWordCharBeforeCursor("می$z", 2, " بعد")).isTrue()
        assertWithMessage("Arabic letter after the cursor")
            .that(WordChars.isZwnjWordCharBeforeCursor("می$z", 2, "شود")).isTrue()
        assertWithMessage("Latin letter after the cursor ends the word")
            .that(WordChars.isZwnjWordCharBeforeCursor("می$z", 2, "abc")).isFalse()
        assertWithMessage("digit after the cursor ends the word")
            .that(WordChars.isZwnjWordCharBeforeCursor("می$z", 2, "3")).isFalse()
        assertWithMessage("after a Latin letter it is no word char")
            .that(WordChars.isZwnjWordCharBeforeCursor("ab$z", 2, "")).isFalse()
        assertWithMessage("after the cursor: left neighbour from before")
            .that(WordChars.isZwnjWordCharAfterCursor("${z}شود", 0, "می")).isTrue()
        assertWithMessage("after the cursor, Latin before")
            .that(WordChars.isZwnjWordCharAfterCursor("${z}شود", 0, "ab")).isFalse()
    }

    @Test
    fun `other scripts and joiners are unchanged`() {
        assertWithMessage("Latin ZWNJ").that(WordChars.isWordCharAt("ab${z}cd", 2)).isFalse()
        assertWithMessage("Cyrillic ZWNJ").that(WordChars.isWordCharAt("да${z}нет", 2)).isFalse()
        assertWithMessage("Greek ZWNJ").that(WordChars.isWordCharAt("να${z}ναι", 2)).isFalse()
        assertWithMessage("Hebrew ZWNJ").that(WordChars.isWordCharAt("של${z}ום", 2)).isFalse()
        assertWithMessage("Sinhala ZWNJ").that(WordChars.isWordCharAt("ශ${z}ර", 1)).isFalse()
        assertWithMessage("Devanagari ZWNJ").that(WordChars.isWordCharAt("क्${z}ष", 2)).isFalse()
        assertWithMessage("Arabic then Latin").that(WordChars.isWordCharAt("می${z}abc", 2)).isFalse()
        assertWithMessage("Latin then Arabic").that(WordChars.isWordCharAt("abc${z}شود", 3)).isFalse()
        // English apostrophe and German hyphen are not this rule's business: isWordCharAt is the
        // letters/digits rule, which never included them; callers keep their own clauses.
        assertWithMessage("apostrophe").that(WordChars.isWordCharAt("don't", 3)).isFalse()
        assertWithMessage("hyphen").that(WordChars.isWordCharAt("E-Mail", 1)).isFalse()
        val family = "👨‍👩"
        assertWithMessage("emoji ZWJ").that(WordChars.isWordCharAt(family, 2)).isFalse()
        assertWithMessage("ZWJ is never treated as a half-space").that(WordChars.isJoiningZwnjAt("می‍شود", 2)).isFalse()
    }

    @Test
    fun `arabic-script letter test`() {
        assertWithMessage("Persian yeh").that(WordChars.isArabicScriptLetter('ی')).isTrue()
        assertWithMessage("Kurdish ae").that(WordChars.isArabicScriptLetter('ە')).isTrue()
        assertWithMessage("presentation form").that(WordChars.isArabicScriptLetter('ﻡ')).isTrue()
        assertWithMessage("Arabic comma").that(WordChars.isArabicScriptLetter('،')).isFalse()
        assertWithMessage("Persian digit").that(WordChars.isArabicScriptLetter('۲')).isFalse()
        assertWithMessage("kasra").that(WordChars.isArabicScriptLetter('ِ')).isFalse()
        assertWithMessage("Latin").that(WordChars.isArabicScriptLetter('a')).isFalse()
        assertWithMessage("null").that(WordChars.isArabicScriptLetter(null)).isFalse()
    }

    // ── stripping and the crossing rule ──────────────────────────────────────────────────

    @Test
    fun `stripping removes only joining half-spaces`() {
        assertWithMessage("می‌شود").that(WordChars.stripJoiningZwnj("می${z}شود")).isEqualTo("میشود")
        assertWithMessage("two").that(WordChars.stripJoiningZwnj("می${z}کتاب${z}ها")).isEqualTo("میکتابها")
        assertWithMessage("edge kept").that(WordChars.stripJoiningZwnj("می$z")).isEqualTo("می$z")
        assertWithMessage("Sinhala kept").that(WordChars.stripJoiningZwnj("ශ${z}ර")).isEqualTo("ශ${z}ර")
        val plain = "hello"
        assertWithMessage("same instance without U+200C").that(WordChars.stripJoiningZwnj(plain)).isSameInstanceAs(plain)
    }

    @Test
    fun `crossing the half-space`() {
        assertWithMessage("joined → half-space").that(WordChars.crossesHalfSpace("میشود", "می${z}شود")).isTrue()
        assertWithMessage("half-space → joined").that(WordChars.crossesHalfSpace("می${z}شود", "میشود")).isTrue()
        assertWithMessage("moved").that(WordChars.crossesHalfSpace("نامه${z}ای", "نام${z}های")).isTrue()
        assertWithMessage("typo fix keeping it").that(WordChars.crossesHalfSpace("می${z}شوذ", "می${z}شود")).isFalse()
        assertWithMessage("no half-space at all").that(WordChars.crossesHalfSpace("helo", "hello")).isFalse()
        assertWithMessage("identical").that(WordChars.crossesHalfSpace("می${z}شود", "می${z}شود")).isFalse()
    }

    // ── HalfSpaceVariants: the offer index ───────────────────────────────────────────────

    @Test
    fun `variants offer the other spelling in both directions`() {
        val dict = setOf("می${z}شود", "میشود", "کتاب", "نامه${z}ای", "نام${z}های")
        val index = HalfSpaceVariants.build(dict)
        assertWithMessage("indexed half-space words").that(index.size).isEqualTo(3)
        assertWithMessage("joined typed → half-space form").that(index.variantsOf("میشود") { it in dict })
            .containsExactly("می${z}شود")
        assertWithMessage("half-space typed → joined form").that(index.variantsOf("می${z}شود") { it in dict })
            .containsExactly("میشود")
        assertWithMessage("ambiguous joined → both readings").that(index.variantsOf("نامهای") { it in dict })
            .containsExactly("نامه${z}ای", "نام${z}های")
        assertWithMessage("one reading typed → the other").that(index.variantsOf("نامه${z}ای") { it in dict })
            .containsExactly("نام${z}های")
        assertWithMessage("no variants for a plain word").that(index.variantsOf("کتاب") { it in dict }).isEmpty()
    }

    @Test
    fun `a dictionary without half-spaces indexes nothing`() {
        val index = HalfSpaceVariants.build(listOf("hello", "don't", "e-mail", "ශ${z}ර"))
        assertWithMessage("empty").that(index).isSameInstanceAs(HalfSpaceVariants.EMPTY)
        assertWithMessage("no offers").that(index.variantsOf("hello") { true }).isEmpty()
    }
}
