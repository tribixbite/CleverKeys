package tribixbite.cleverkeys.stt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.stt.VoiceTextOp.ClearComposing
import tribixbite.cleverkeys.stt.VoiceTextOp.Commit
import tribixbite.cleverkeys.stt.VoiceTextOp.SetComposing

/**
 * [VoiceTextPlanner]: what each field kind receives. A small editor model applies the
 * operations the way `InputConnection` does (commit replaces the composing span), so the tests
 * assert the resulting field text as well as the operations.
 */
class VoiceTextPolicyTest {

    /** commitText / setComposingText semantics on a caret-at-end buffer. */
    private class FakeEditor(initial: String) {
        var committed = initial
        var composing = ""
        val text get() = committed + composing

        fun apply(ops: List<VoiceTextOp>) {
            for (op in ops) when (op) {
                is Commit -> { committed += op.text; composing = "" }
                is SetComposing -> composing = op.text
                ClearComposing -> composing = ""
            }
        }
    }

    private fun partial(text: String, stable: Int) = SpeechHypothesis(text, stable, isFinal = false)
    private fun final(text: String) = SpeechHypothesis(text, SpeechHypothesis.splitWords(text).size, isFinal = true)

    @Test
    fun textFieldsShowThePendingTailAsComposingAndCommitStableWords() {
        val ed = FakeEditor("")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, textBefore = "")
        val ops1 = p.onHypothesis(partial("Hello", 0))
        assertThat(ops1).containsExactly(SetComposing("Hello"))
        ed.apply(ops1)
        val ops2 = p.onHypothesis(partial("Hello world", 1))
        assertThat(ops2).containsExactly(Commit("Hello"), SetComposing(" world")).inOrder()
        ed.apply(ops2)
        assertThat(ed.committed).isEqualTo("Hello")
        assertThat(ed.composing).isEqualTo(" world")
        ed.apply(p.onHypothesis(final("Hello world.")))
        assertThat(ed.text).isEqualTo("Hello world.")
        assertThat(ed.composing).isEmpty()
        assertThat(p.finished).isTrue()
    }

    @Test
    fun aLeadingSpaceSeparatesDictationFromTheWordBefore() {
        val ed = FakeEditor("I said")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, textBefore = "I said")
        ed.apply(p.onHypothesis(final("Hello there")))
        assertThat(ed.text).isEqualTo("I said hello there")
        // After a space nothing is added; punctuation attaches.
        val ed2 = FakeEditor("I said ")
        ed2.apply(VoiceTextPlanner(VoiceFieldKind.TEXT, "I said ").onHypothesis(final("Hello")))
        assertThat(ed2.text).isEqualTo("I said hello")
        val ed3 = FakeEditor("wait")
        ed3.apply(VoiceTextPlanner(VoiceFieldKind.TEXT, "wait").onHypothesis(final(", what?")))
        assertThat(ed3.text).isEqualTo("wait, what?")
    }

    @Test
    fun sentenceCaseFollowsTheTextBefore() {
        fun run(before: String, said: String, match: Boolean = true): String {
            val ed = FakeEditor(before)
            ed.apply(VoiceTextPlanner(VoiceFieldKind.TEXT, before, matchSentenceCase = match).onHypothesis(final(said)))
            return ed.text
        }
        assertThat(run("", "hello there")).isEqualTo("Hello there")
        assertThat(run("Done. ", "next one")).isEqualTo("Done. Next one")
        assertThat(run("line\n", "new line")).isEqualTo("line\nNew line")
        assertThat(run("and then", "The dog ran")).isEqualTo("and then the dog ran")
        assertThat(run("and then", "A dog")).isEqualTo("and then a dog")
        assertWithMessageI(run("and then", "I left"), "and then I left")
        assertWithMessageI(run("and then", "I'm here"), "and then I'm here")
        assertThat(run("and then", "NASA called")).isEqualTo("and then NASA called")
        assertThat(run("and then", "iPhone")).isEqualTo("and then iPhone")
        assertThat(run("and then", "The dog", match = false)).isEqualTo("and then The dog")
    }

    private fun assertWithMessageI(actual: String, expected: String) = assertThat(actual).isEqualTo(expected)

    @Test
    fun trailingSpaceIsAddedAfterTheFinalTextWhenAutoSpaceIsOn() {
        val ed = FakeEditor("")
        ed.apply(VoiceTextPlanner(VoiceFieldKind.TEXT, "", trailingSpace = true).onHypothesis(final("Hi")))
        assertThat(ed.text).isEqualTo("Hi ")
        val empty = VoiceTextPlanner(VoiceFieldKind.TEXT, "", trailingSpace = true)
        assertThat(empty.onHypothesis(final(""))).isEmpty()
    }

    @Test
    fun cancelDropsTheComposingTailButKeepsCommittedWords() {
        val ed = FakeEditor("")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, "")
        ed.apply(p.onHypothesis(partial("one two three", 2)))
        assertThat(ed.composing).isEqualTo(" three")
        ed.apply(p.onCancel())
        assertThat(ed.text).isEqualTo("One two")
        assertThat(p.onHypothesis(partial("one two three four", 4))).isEmpty()
    }

    @Test
    fun blindFieldsGetAppendOnlyStableWordsAndNoComposing() {
        val p = VoiceTextPlanner(VoiceFieldKind.BLIND, textBefore = null)
        assertThat(p.onHypothesis(partial("ls minus", 0))).isEmpty()
        assertThat(p.onHypothesis(partial("ls minus la", 2))).containsExactly(Commit("ls minus"))
        // A revision of the pending tail sends nothing.
        assertThat(p.onHypothesis(partial("ls minus al", 2))).isEmpty()
        assertThat(p.onHypothesis(final("ls minus al"))).containsExactly(Commit(" al"))
        // Every op is a Commit: nothing ever rewrites or deletes.
        val all = VoiceTextPlanner(VoiceFieldKind.BLIND, null).let { b ->
            listOf(partial("a", 0), partial("a b", 1), partial("a c", 1), final("a c d")).flatMap { b.onHypothesis(it) } + b.onCancel()
        }
        assertThat(all.all { it is Commit }).isTrue()
    }

    @Test
    fun blindFieldsKeepTheEnginesCasingAndAddNoLeadingSpace() {
        val p = VoiceTextPlanner(VoiceFieldKind.BLIND, null)
        assertThat(p.onHypothesis(final("Hello."))).containsExactly(Commit("Hello."))
    }

    @Test
    fun inlineFieldsGetStableWordsOnlyWithSpacingFromTheField() {
        val p = VoiceTextPlanner(VoiceFieldKind.INLINE, textBefore = "cat")
        assertThat(p.onHypothesis(partial("Videos of", 1))).containsExactly(Commit(" videos"))
        assertThat(p.onHypothesis(final("Videos of dogs"))).containsExactly(Commit(" of dogs"))
        val trailing = VoiceTextPlanner(VoiceFieldKind.INLINE, "", trailingSpace = true)
        assertThat(trailing.onHypothesis(final("dog"))).containsExactly(Commit("Dog"))
    }

    @Test
    fun passwordFieldsReceiveNothing() {
        val p = VoiceTextPlanner(VoiceFieldKind.PASSWORD, "")
        assertThat(p.onHypothesis(partial("secret", 1))).isEmpty()
        assertThat(p.onHypothesis(final("secret"))).isEmpty()
        assertThat(p.onCancel()).isEmpty()
    }

    @Test
    fun anEmptyFinalAfterPartialsClearsTheComposingSpan() {
        val ed = FakeEditor("")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, "")
        ed.apply(p.onHypothesis(partial("uh", 0)))
        val ops = p.onHypothesis(final(""))
        assertThat(ops).containsExactly(ClearComposing)
        ed.apply(ops)
        assertThat(ed.text).isEmpty()
    }

    // ---- segments (endpointing, FR-10) ----------------------------------------------------

    /** Feed whole segments: partials then a segment final, the period's final last. */
    private fun dictate(p: VoiceTextPlanner, ed: FakeEditor?, vararg segments: String): List<VoiceTextOp> {
        val all = ArrayList<VoiceTextOp>()
        segments.forEachIndexed { i, seg ->
            val words = SpeechHypothesis.splitWords(seg)
            all += p.onHypothesis(partial(seg, maxOf(0, words.size - 1))).also { ed?.apply(it) }
            val last = i == segments.lastIndex
            all += (if (last) p.onHypothesis(final(seg)) else p.onSegmentEnd(final(seg))).also { ed?.apply(it) }
        }
        return all
    }

    @Test
    fun segmentsJoinWithOneSpaceAndCapitaliseAfterASentenceEnd() {
        val ed = FakeEditor("")
        dictate(VoiceTextPlanner(VoiceFieldKind.TEXT, ""), ed, "Hello there.", "How are you?", "Fine")
        assertThat(ed.text).isEqualTo("Hello there. How are you? Fine")
        assertThat(ed.composing).isEmpty()
    }

    @Test
    fun aSegmentThatContinuesASentenceIsNotCapitalised() {
        // The engine capitalises every segment's first word; a cut at a pause mid-sentence
        // must not leave "the dog ran And then".
        val ed = FakeEditor("")
        dictate(VoiceTextPlanner(VoiceFieldKind.TEXT, ""), ed, "The dog ran", "And then it stopped.", "I think")
        assertThat(ed.text).isEqualTo("The dog ran and then it stopped. I think")
    }

    @Test
    fun autoSpaceBetweenSegmentsNeverDoubles() {
        val ed = FakeEditor("Notes. ")
        dictate(VoiceTextPlanner(VoiceFieldKind.TEXT, "Notes. ", trailingSpace = true), ed, "One.", "Two", "three.")
        assertThat(ed.text).isEqualTo("Notes. One. Two three. ")
        assertThat(ed.text).doesNotContain("  ")
    }

    @Test
    fun aSegmentFinalCommitsItsComposingTailAndTheNextStartsFresh() {
        val ed = FakeEditor("")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, "")
        ed.apply(p.onHypothesis(partial("first part", 1)))
        assertThat(ed.composing).isEqualTo(" part")
        val end = p.onSegmentEnd(final("first part."))
        assertThat(end).containsExactly(Commit(" part."))
        ed.apply(end)
        assertThat(ed.composing).isEmpty()
        assertThat(p.finished).isFalse()
        // The next segment's hypotheses count words from zero again.
        ed.apply(p.onHypothesis(partial("Second", 0)))
        assertThat(ed.composing).isEqualTo(" Second")
        ed.apply(p.onHypothesis(final("Second part.")))
        assertThat(ed.text).isEqualTo("First part. Second part.")
        assertThat(p.finished).isTrue()
    }

    @Test
    fun anEmptySegmentChangesNothing() {
        val ed = FakeEditor("")
        val p = VoiceTextPlanner(VoiceFieldKind.TEXT, "", trailingSpace = true)
        ed.apply(p.onHypothesis(partial("uh", 0)))
        ed.apply(p.onSegmentEnd(final("")))
        assertThat(ed.text).isEmpty()
        ed.apply(p.onHypothesis(final("Okay.")))
        assertThat(ed.text).isEqualTo("Okay. ")
    }

    @Test
    fun blindFieldsSeparateSegmentsWithASpaceAndStayAppendOnly() {
        // The keyboard never reads a blind field, but it knows what it wrote itself.
        val ops = dictate(VoiceTextPlanner(VoiceFieldKind.BLIND, textBefore = null), null, "Hello.", "Ls minus la", "and grep")
        assertThat(ops.all { it is Commit }).isTrue()
        assertThat(ops.joinToString("") { (it as Commit).text }).isEqualTo("Hello. Ls minus la and grep")
    }

    @Test
    fun inlineFieldsTakeSegmentsLikeText() {
        val p = VoiceTextPlanner(VoiceFieldKind.INLINE, textBefore = "cat")
        val ops = dictate(p, null, "Videos.", "Of dogs")
        assertThat(ops.joinToString("") { (it as Commit).text }).isEqualTo(" videos. Of dogs")
    }

    @Test
    fun passwordFieldsIgnoreSegmentsToo() {
        assertThat(VoiceTextPlanner(VoiceFieldKind.PASSWORD, "").onSegmentEnd(final("secret"))).isEmpty()
    }

    @Test
    fun firstPersonIRecognition() {
        assertThat(VoiceTextPlanner.isFirstPersonI("I")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("I’ll")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("I've,")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("It")).isFalse()
    }
}
