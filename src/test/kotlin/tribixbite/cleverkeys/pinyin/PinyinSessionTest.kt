package tribixbite.cleverkeys.pinyin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Pins the pinyin composing state machine: normalization, deterministic candidate
 * assembly (exact → completions → greedy segmentation), and the buffer edit rules.
 * Pure JVM — the pack/table bytes are the in-memory [CkpyPhraseTable.Table] the reader
 * produces (whose own byte layout is pinned by [CkpyPhraseTableTest]).
 */
class PinyinSessionTest {

    // --------------------------------------------------------------------- fixtures

    /** One key with `text to rank` candidates, assembled in-memory (no file needed). */
    private fun entry(key: String, vararg candidates: Pair<String, Int>): CkpyPhraseTable.Entry =
        CkpyPhraseTable.Entry(key, candidates.map { CkpyPhraseTable.Candidate(it.first, it.second) })

    /** A table with keys sorted ascending, as the CKPY reader guarantees. */
    private fun table(vararg entries: CkpyPhraseTable.Entry): CkpyPhraseTable.Table =
        CkpyPhraseTable.Table("zh", entries.sortedBy { it.key })

    private fun session(vararg entries: CkpyPhraseTable.Entry): PinyinSession =
        PinyinSession(table(*entries))

    // ------------------------------------------------------------------ normalization

    @Test
    fun appendLetterNormalizesCaseApostrophesAndUmlaut() {
        val s = session(entry("nv", "\u5973" to 0), entry("xi'an", "\u897F\u5B89" to 0))

        assertWithMessage("uppercase keys arrive with shift on; the buffer is lowercase")
            .that(s.appendLetter('N')).isTrue()
        assertThat(s.composingText).isEqualTo("n")
        assertThat(s.appendLetter('V')).isTrue()
        assertWithMessage("v spells ü (女 = nv)")
            .that(s.composingText).isEqualTo("nv")
        assertThat(s.appendLetter('\u00fc')).isTrue() // ü
        assertThat(s.composingText).isEqualTo("nvv")

        s.clear()
        assertWithMessage("curly apostrophes normalize to the ASCII key separator")
            .that(s.appendLetter('\u2019')).isTrue()
        assertThat(s.composingText).isEqualTo("'")

        assertWithMessage("digits are not pinyin input — the caller commits them normally")
            .that(s.appendLetter('3')).isFalse()
        assertThat(s.appendLetter(' ')).isFalse()
        assertThat(s.appendLetter('.')).isFalse()
    }

    @Test
    fun appendSurfaceKeepsOnlyPinyinAndReturnsWhatWasAppended() {
        val s = session(entry("nihao", "\u4F60\u597D" to 0))

        val appended = s.appendSurface("Ni3hao!")

        assertThat(appended).isEqualTo("nihao")
        assertThat(s.composingText).isEqualTo("nihao")
    }

    @Test
    fun bufferIsCappedAtTheConfiguredLength() {
        val s = session(entry("a", "\u554A" to 0))
        repeat(PinyinSession.MAX_BUFFER_LENGTH) {
            assertWithMessage("append $it must fit").that(s.appendLetter('a')).isTrue()
        }
        assertThat(s.isFull).isTrue()
        assertThat(s.appendLetter('a')).isFalse()
        assertThat(s.appendSurface("aaa")).isEmpty()
    }

    // -------------------------------------------------------------- candidate assembly

    @Test
    fun candidatesPutTheExactKeyFirstThenCompletionKeysInTableOrder() {
        val s = session(
            entry("ni", "\u4F60" to 0, "\u5C3C" to 1),
            entry("nian", "\u5E74" to 0),
            entry("niang", "\u5A18" to 0),
            entry("nihao", "\u4F60\u597D" to 0),
        )
        s.appendSurface("ni")

        assertThat(s.candidates().map { it.text }).containsExactly(
            "\u4F60", "\u5C3C", "\u5E74", "\u5A18", "\u4F60\u597D"
        ).inOrder()
    }

    @Test
    fun aPartialSyllableShowsCompletionCandidates() {
        val s = session(
            entry("nihao", "\u4F60\u597D" to 0),
            entry("ni", "\u4F60" to 0),
        )
        s.appendSurface("nih")

        assertThat(s.candidates().map { it.text }).containsExactly("\u4F60\u597D")
    }

    @Test
    fun duplicateCandidateTextIsOfferedOnce() {
        val s = session(
            entry("ni", "\u4F60" to 0),
            entry("nian", "\u4F60" to 0), // same 你 under a completion key
        )
        s.appendSurface("ni")

        assertThat(s.candidates().map { it.text }).containsExactly("\u4F60")
    }

    @Test
    fun anExactMultiSyllableKeyWinsOverSegmentation() {
        val s = session(
            entry("xian", "\u5148" to 0),
            entry("xi", "\u897F" to 0),
            entry("an", "\u5B89" to 0),
        )
        s.appendSurface("xian")

        assertThat(s.topCandidate()).isEqualTo("\u5148")
    }

    @Test
    fun candidatesAreBoundedAndScoredDescending() {
        val many = (0 until 20).map { "\u5B57$it" to it }
        val s = session(entry("a", *many.toTypedArray()))
        s.appendSurface("a")

        val candidates = s.candidates()
        assertThat(candidates).hasSize(PinyinSession.MAX_CANDIDATES)
        for (i in 1 until candidates.size) {
            assertWithMessage("rank order must survive the score projection")
                .that(candidates[i].score).isAtMost(candidates[i - 1].score)
        }
    }

    // ------------------------------------------------------------------- segmentation

    @Test
    fun segmentationJoinsKnownSyllablesWhenNoSingleKeyMatches() {
        val s = session(
            entry("wo", "\u6211" to 0),
            entry("ai", "\u7231" to 0),
            entry("ni", "\u4F60" to 0),
        )
        s.appendSurface("woaini")

        assertWithMessage("五 我 + 爱 + 你 joins to 我爱你")
            .that(s.topCandidate()).isEqualTo("\u6211\u7231\u4F60")
    }

    @Test
    fun segmentationUsesGreedyLongestMatch() {
        val s = session(
            entry("fang", "\u623F" to 0),
            entry("fan", "\u996D" to 0),
            entry("an", "\u5B89" to 0),
        )
        s.appendSurface("fangan")

        assertWithMessage("longest match at position 0 is 房 (fang), not 饭 (fan)")
            .that(s.topCandidate()).isEqualTo("\u623F\u5B89")
    }

    @Test
    fun segmentationOffersSecondCandidateVariantsForTrailingSegments() {
        val s = session(
            entry("wo", "\u6211" to 0, "\u63E1" to 1),
            entry("ai", "\u7231" to 0),
            entry("ni", "\u4F60" to 0, "\u5C3C" to 1),
        )
        s.appendSurface("woaini")

        val texts = s.candidates().map { it.text }
        assertThat(texts).contains("\u6211\u7231\u4F60")     // base join
        assertThat(texts).contains("\u6211\u7231\u5C3C")     // trailing segment's runner-up
        assertThat(texts).contains("\u63E1\u7231\u4F60")     // leading segment's runner-up
    }

    @Test
    fun unsegmentableInputOffersNothing() {
        val s = session(entry("ni", "\u4F60" to 0))
        s.appendSurface("niqwerty")

        assertThat(s.candidates()).isEmpty()
        assertThat(s.composingText).isEqualTo("niqwerty")
    }

    // ---------------------------------------------------------------------- editing

    @Test
    fun backspaceShortensUntilEmptyThenRefuses() {
        val s = session(entry("ni", "\u4F60" to 0))
        s.appendSurface("ni")

        assertThat(s.backspace()).isTrue()
        assertThat(s.composingText).isEqualTo("n")
        assertThat(s.backspace()).isTrue()
        assertThat(s.isEmpty).isTrue()
        assertWithMessage("an empty buffer hands backspace back to the normal path")
            .that(s.backspace()).isFalse()
    }

    @Test
    fun clearDropsTheWholeRun() {
        val s = session(entry("ni", "\u4F60" to 0))
        s.appendSurface("ni")
        s.clear()

        assertThat(s.isEmpty).isTrue()
        assertThat(s.candidates()).isEmpty()
    }

    @Test
    fun emptyBufferNeverProducesCandidates() {
        val s = session(entry("ni", "\u4F60" to 0))
        assertThat(s.candidates()).isEmpty()
        assertThat(s.topCandidate()).isNull()
    }
}
