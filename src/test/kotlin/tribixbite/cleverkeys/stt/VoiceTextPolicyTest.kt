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

    @Test
    fun firstPersonIRecognition() {
        assertThat(VoiceTextPlanner.isFirstPersonI("I")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("I’ll")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("I've,")).isTrue()
        assertThat(VoiceTextPlanner.isFirstPersonI("It")).isFalse()
    }
}
