package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.swipe.UserJoinerPreference
import tribixbite.cleverkeys.swipe.ctc.CtcScriptProjection

/**
 * Persian half-space (U+200C) at the word-boundary CALL SITES (decision (b), 2026-10-23;
 * research `docs/research/2026-10-21-persian-zwnj.md` §6). Pure JVM.
 *
 * Every scanner must agree: a half-space between two Arabic-script letters is inside the word
 * (`می‌شود` is ONE word), and every other U+200C keeps the classification it had — Latin,
 * Sinhala, an Arabic/Latin mix and word edges stay boundaries, apostrophes and hyphens keep their
 * own rules, and U+200D (emoji ZWJ) is untouched. The predicate itself is pinned in
 * [WordCharsTest]; this class drives the production entry points that call it.
 */
class PersianHalfSpaceBoundaryTest {

    private val zwnj = "‌"
    private val miShavad = "می${zwnj}شود"       // می‌شود "becomes"
    private val sinhala = "ශ${zwnj}ර"            // Sinhala SHA, ZWNJ, RA
    private val latin = "ab${zwnj}cd"
    private val emojiFamily = "👨‍👩" // 👨‍👩 (ZWJ sequence)

    // ── TextActionPolicy.wordAtCursor (Keyboard2View's select-word / text actions) ──────

    @Test
    fun `word at cursor spans the half-space inside a Persian word`() {
        val span = TextActionPolicy.wordAtCursor("سلام می$zwnj", "شود")
        assertWithMessage("backward covers می‌").that(span.backward).isEqualTo(3)
        assertWithMessage("forward covers شود").that(span.forward).isEqualTo(3)
    }

    @Test
    fun `word at cursor keeps a just-typed half-space with the word`() {
        val span = TextActionPolicy.wordAtCursor("سلام می$zwnj", "")
        assertWithMessage("cursor edge: می‌| is one word").that(span.backward).isEqualTo(3)
    }

    @Test
    fun `word at cursor does not join across a non-Arabic half-space`() {
        assertWithMessage("Latin").that(TextActionPolicy.wordAtCursor(latin, "").backward).isEqualTo(2)
        assertWithMessage("Sinhala").that(TextActionPolicy.wordAtCursor(sinhala, "").backward).isEqualTo(1)
        assertWithMessage("Arabic letter, half-space, Latin letter")
            .that(TextActionPolicy.wordAtCursor("می${zwnj}abc", "").backward).isEqualTo(3)
        assertWithMessage("Latin letter, half-space, Arabic letter")
            .that(TextActionPolicy.wordAtCursor("abc${zwnj}شود", "").backward).isEqualTo(3)
        assertWithMessage("half-space before a Latin letter after the cursor")
            .that(TextActionPolicy.wordAtCursor("می$zwnj", "abc").backward).isEqualTo(0)
    }

    @Test
    fun `word at cursor keeps the apostrophe rule`() {
        val span = TextActionPolicy.wordAtCursor("do", "n't")
        assertWithMessage("don't backward").that(span.backward).isEqualTo(2)
        assertWithMessage("don't forward").that(span.forward).isEqualTo(3)
    }

    // ── EditorTextNavigation (word_left / select_word / case cycle) ─────────────────────

    @Test
    fun `editor navigation selects the whole half-space word`() {
        val text = "سلام $miShavad خوب"
        assertWithMessage("select word inside شود").that(EditorTextNavigation.wordBounds(text, 9, 9))
            .isEqualTo(5 until 11)
        assertWithMessage("word left from the end of می‌شود lands on م")
            .that(EditorTextNavigation.previousWordStart(text, 11)).isEqualTo(5)
    }

    @Test
    fun `editor navigation keeps other half-spaces and joiners as they were`() {
        assertWithMessage("Latin ZWNJ is a boundary").that(EditorTextNavigation.wordBounds(latin, 0, 0))
            .isEqualTo(0 until 2)
        assertWithMessage("Sinhala ZWNJ is a boundary").that(EditorTextNavigation.wordBounds(sinhala, 0, 0))
            .isEqualTo(0 until 1)
        assertWithMessage("trailing Persian half-space is not part of the word")
            .that(EditorTextNavigation.wordBounds("می$zwnj ", 0, 0)).isEqualTo(0 until 2)
        assertWithMessage("don't stays one word").that(EditorTextNavigation.wordBounds("we don't go", 5, 5))
            .isEqualTo(3 until 8)
        assertWithMessage("hyphen still splits (navigation rule)")
            .that(EditorTextNavigation.wordBounds("e-mail", 0, 0)).isEqualTo(0 until 1)
        assertWithMessage("emoji ZWJ sequence is not a word").that(EditorTextNavigation.wordBounds(emojiFamily, 0, 0))
            .isNull()
    }

    // ── NextWordPredictor tokeniser (bigram context) ─────────────────────────────────────

    @Test
    fun `next-word context keeps a half-space word whole`() {
        assertWithMessage("کتاب‌ها is one context token")
            .that(NextWordPredictor.contextFromEditorText("این کتاب${zwnj}ها $miShavad "))
            .containsExactly("این", "کتاب${zwnj}ها", miShavad).inOrder()
    }

    @Test
    fun `next-word context is unchanged for other scripts`() {
        assertWithMessage("German hyphen kept").that(NextWordPredictor.contextFromEditorText("die e-mail ist "))
            .containsExactly("die", "e-mail", "ist").inOrder()
        assertWithMessage("English apostrophe kept").that(NextWordPredictor.contextFromEditorText("i don't know "))
            .containsExactly("i", "don't", "know").inOrder()
        assertWithMessage("Latin ZWNJ splits").that(NextWordPredictor.contextFromEditorText("$latin "))
            .containsExactly("ab", "cd").inOrder()
        assertWithMessage("edge half-space dropped").that(NextWordPredictor.contextFromEditorText("می$zwnj "))
            .containsExactly("می")
    }

    // ── Swipe: CTC script projection and the joiner user-word preference ─────────────────

    private val persianAlphabet = "ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی".toCharArray()

    @Test
    fun `CTC projection strips the half-space so the path spells the joined form`() {
        val project = CtcScriptProjection.projectorFor("fa", persianAlphabet)
        assertWithMessage("می‌شود is swipeable as میشود").that(project(miShavad)).isEqualTo("میشود")
        assertWithMessage("an edge half-space is still untypeable").that(project("می$zwnj")).isNull()
    }

    @Test
    fun `CTC projection does not strip a non-Arabic half-space`() {
        val project = CtcScriptProjection.projectorFor("si", "ශරකම".toCharArray())
        assertWithMessage("Sinhala ZWNJ word stays untypeable, as before").that(project(sinhala)).isNull()
    }

    @Test
    fun `a half-space user word is a joiner word with the joined surface`() {
        assertWithMessage("می‌شود is a joiner word").that(UserJoinerPreference.isJoinerWord(miShavad)).isTrue()
        assertWithMessage("its surface is میشود").that(UserJoinerPreference.joinerFree(miShavad)).isEqualTo("میشود")
        assertWithMessage("Sinhala ZWNJ is not a joiner").that(UserJoinerPreference.isJoinerWord(sinhala)).isFalse()
        assertWithMessage("Latin ZWNJ is not a joiner").that(UserJoinerPreference.isJoinerWord(latin)).isFalse()
        assertWithMessage("she'd unchanged").that(UserJoinerPreference.joinerFree("she'd")).isEqualTo("shed")
    }
}
