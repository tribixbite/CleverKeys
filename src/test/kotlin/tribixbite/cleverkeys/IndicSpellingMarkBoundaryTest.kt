package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Indic spelling-mark rule beyond Bangla, the shared sentence-end predicate and the
 * "spelled with letters" gate of the typo-tolerant search (2026-11-06; kit
 * `ml/rtx-kits/19-bangla-pack` §3). Pure JVM.
 *
 * Every Indic script with a layout in `srcs/layouts` (Devanagari `deva_*`, Gujarati `guj_*`,
 * Kannada `kann_*`, Tamil `tamil_*`, Sinhala `sinhala_*`) gets the [WordChars] rule Bangla got
 * on 2026-11-04: a vowel sign or virama on a letter of its script is inside the word. One real
 * word per script, each with at least one mark, is checked at the predicate and at the
 * selection and next-word call sites. Bangla's own call-site coverage is
 * [BanglaSpellingMarkBoundaryTest].
 *
 * Sentence ends: the danda `।` and double danda `॥` end a sentence for next-word context
 * exactly as `.` does — the learn path uses the same [WordChars.isSentenceEnd]
 * (`BanglaSpellingMarkTypingTest` drives that side).
 */
class IndicSpellingMarkBoundaryTest {

    /** One word per shipped Indic layout script; every index of each must be a word character. */
    private val words = mapOf(
        "Devanagari" to "किताब",   // "book": ि ा
        "Gujarati" to "ગુજરાતી",   // "Gujarati": ુ ા ી
        "Kannada" to "ಕನ್ನಡ",      // "Kannada": ್ (virama) spells the ನ್ನ conjunct
        "Tamil" to "தமிழ்",        // "Tamil": ி and the final pulli ்
        "Sinhala" to "සිංහල",      // "Sinhala": ි and ං (anusvara)
    )

    @Test
    fun `every mark of a word in each shipped Indic script is a word character`() {
        for ((script, word) in words) {
            for (i in word.indices) {
                assertWithMessage("$script $word[$i] U+${"%04X".format(word[i].code)}")
                    .that(WordChars.isWordCharAt(word, i)).isTrue()
            }
        }
    }

    @Test
    fun `a mark on a letter of another script is not attached`() {
        // A Tamil pulli after a Devanagari letter, a Devanagari sign after a Latin letter.
        assertWithMessage("Tamil mark on Devanagari").that(WordChars.isAttachedSpellingMarkAt("क\u0BCD", 1)).isFalse()
        assertWithMessage("Devanagari sign on Latin").that(WordChars.isAttachedSpellingMarkAt("a\u093F", 1)).isFalse()
        assertWithMessage("Sinhala mark with no base").that(WordChars.isAttachedSpellingMarkAt("\u0DD2", 0)).isFalse()
    }

    @Test
    fun `each Indic word selects whole and is one next-word token`() {
        for ((script, word) in words) {
            assertWithMessage("$script select backward").that(TextActionPolicy.wordAtCursor("x $word", "").backward)
                .isEqualTo(word.length)
            assertWithMessage("$script next-word token").that(NextWordPredictor.contextFromEditorText("$word "))
                .containsExactly(word.lowercase()).inOrder()
        }
    }

    // ── Sentence ends ────────────────────────────────────────────────────────────────────

    @Test
    fun `the danda and double danda are sentence ends, other marks are not`() {
        for (c in listOf('.', '?', '!', '।', '॥')) {
            assertWithMessage("U+${"%04X".format(c.code)}").that(WordChars.isSentenceEnd(c)).isTrue()
        }
        for (c in listOf(',', ';', ':', ' ', '\n', 'ি', '্', 'a', '১')) {
            assertWithMessage("U+${"%04X".format(c.code)}").that(WordChars.isSentenceEnd(c)).isFalse()
        }
    }

    @Test
    fun `next-word context never spans a danda`() {
        assertWithMessage("parked right after a danda: empty context")
            .that(NextWordPredictor.contextFromEditorText("আমি ভাত খাই। ")).isEmpty()
        assertWithMessage("only the words after the danda")
            .that(NextWordPredictor.contextFromEditorText("আমি ভাত খাই। তুমি "))
            .containsExactly("তুমি").inOrder()
        assertWithMessage("Hindi danda").that(NextWordPredictor.contextFromEditorText("मैं घर गया। किताब "))
            .containsExactly("किताब").inOrder()
        assertWithMessage("double danda").that(NextWordPredictor.contextFromEditorText("আমি॥ কুকুর "))
            .containsExactly("কুকুর").inOrder()
        assertWithMessage("English period unchanged").that(NextWordPredictor.contextFromEditorText("i went. you "))
            .containsExactly("you").inOrder()
    }

    // ── ZWJ in Sinhala and Kannada (both layouts carry a ZWJ key) ─────────────────────────

    @Test
    fun `a Sinhala or Kannada ZWJ after the virama joins the word`() {
        val zwj = "\u200D"
        // ශ්‍රී ("Sri"): ශ + ් + ZWJ + ර + ී, the rakaransaya (Unicode Sinhala FAQ).
        val sri = "ශ්${zwj}රී"
        for (i in sri.indices) {
            assertWithMessage("ශ්‍රී[$i]").that(WordChars.isWordCharAt(sri, i)).isTrue()
        }
        assertWithMessage("one token in next-word context")
            .that(NextWordPredictor.contextFromEditorText("$sri ලංකා ")).containsExactly(sri, "ලංකා").inOrder()
        // Kannada explicit half form: ಕ + ್ + ZWJ + ಷ (structure only; the ZWJ follows the virama).
        val half = "ಕ್${zwj}ಷ"
        assertWithMessage("Kannada ZWJ").that(WordChars.isJoiningZwjAt(half, 2)).isTrue()
        assertWithMessage("Sinhala then Latin stays a boundary").that(WordChars.isJoiningZwjAt("ශ්${zwj}a", 2)).isFalse()
    }

    // ── ZWJ in Devanagari (pasted or field text: deva_* layouts carry no ZWJ key) ─────────

    @Test
    fun `a Devanagari ZWJ after the virama joins the word`() {
        val zwj = "\u200D"
        // The Unicode Standard §12.1 spellings: the eyelash ra is र + ् + ZWJ (Marathi दऱ्या
        // written with it), the explicit half form क + ् + ZWJ + ष. Structure only.
        val eyelash = "दर्${zwj}या"
        for (i in eyelash.indices) {
            assertWithMessage("दर्‍या[$i]").that(WordChars.isWordCharAt(eyelash, i)).isTrue()
        }
        assertWithMessage("one token in next-word context")
            .that(NextWordPredictor.contextFromEditorText("$eyelash आणि ")).containsExactly(eyelash, "आणि").inOrder()
        assertWithMessage("half form").that(WordChars.isJoiningZwjAt("क्${zwj}ष", 2)).isTrue()
        assertWithMessage("Devanagari then Latin stays a boundary").that(WordChars.isJoiningZwjAt("क्${zwj}a", 2)).isFalse()
        assertWithMessage("Devanagari then Bengali stays a boundary").that(WordChars.isJoiningZwjAt("क्${zwj}ক", 2)).isFalse()
    }

    // ── The typo-tolerant search gate ─────────────────────────────────────────────────────

    @Test
    fun `marked Indic words count as spelled with letters`() {
        for ((script, word) in words) {
            assertWithMessage(script).that(WordChars.isSpelledWithLetters(word)).isTrue()
        }
        assertWithMessage("Bangla").that(WordChars.isSpelledWithLetters("কুকুর")).isTrue()
        assertWithMessage("Bangla with a ZWJ").that(WordChars.isSpelledWithLetters("র‍্যাব")).isTrue()
    }

    @Test
    fun `the letters gate is all-isLetter for every other word`() {
        // Byte-identical to the old `all { isLetter() }` outside the Indic marks and Bengali ZWJ.
        for (word in listOf("hello", "straße", "привет", "كتاب", "שלום", "")) {
            assertWithMessage(word).that(WordChars.isSpelledWithLetters(word)).isEqualTo(word.all { it.isLetter() })
        }
        for (word in listOf("don't", "co-op", "abc1", "می‌شود", "café", "ি", "a‍b")) {
            assertWithMessage(word).that(WordChars.isSpelledWithLetters(word)).isFalse()
        }
    }
}
