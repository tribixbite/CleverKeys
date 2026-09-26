package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipeCorrectionTracker.SwipeRecord

/**
 * The swipe-correction state machine (learning-system audit 2026-09-26, Resolution): which word
 * the user settled on after rejecting a swipe auto-insert, by bar tap or by backspace undo +
 * the next word, and every way the pending-rejection slot is dropped.
 */
class SwipeCorrectionTrackerTest {

    private var now = 1_000L
    private val tracker = SwipeCorrectionTracker { now }

    private fun swipe(word: String, vararg slate: String, trace: String? = null) =
        SwipeRecord(word, if (slate.isEmpty()) listOf(word) else slate.toList(), trace)

    // ------------------------------------------------------------------ bar replace

    @Test
    fun aBarTapOverTheAutoInsertedSwipeIsACorrection() {
        val got = swipe("got", "got", "git", "hit", trace = "t1")
        tracker.onSwipeAutoInserted(got, "fix got ")

        val c = tracker.onSwipeReplacedFromBar("got", "git")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got)))
    }

    @Test
    fun aBarReplaceOfAWordThatWasNotTheLastSwipeReportsNothing() {
        tracker.onSwipeAutoInserted(swipe("got"), "got ")
        assertThat(tracker.onSwipeReplacedFromBar("the", "then")).isNull()
    }

    @Test
    fun aTypedWordBetweenTheSwipeAndTheTapEndsTheSwipe() {
        tracker.onSwipeAutoInserted(swipe("got"), "got ")
        tracker.onWordCommitted("and", "got and ")
        assertThat(tracker.onSwipeReplacedFromBar("got", "git")).isNull()
    }

    // ------------------------------------------------------------------ undo + next word

    @Test
    fun undoThenTypingTheIntendedWordIsACorrection() {
        val got = swipe("got", "got", "for")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        assertThat(tracker.hasPendingRejection).isTrue()

        val c = tracker.onWordCommitted("git", "fix git ")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got)))
        assertThat(tracker.hasPendingRejection).isFalse()
    }

    @Test
    fun theResolvingWordMayCarryOnePunctuationMark() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        assertThat(tracker.onWordCommitted("git", "fix git.")?.chosen).isEqualTo("git")
    }

    @Test
    fun undoAtTheStartOfTheFieldResolvesAgainstAnEmptyAnchor() {
        tracker.onSwipeAutoInserted(swipe("got"), "got ")
        tracker.onSwipeUndone("got", "")
        assertThat(tracker.onWordCommitted("Git", "Git ")?.chosen).isEqualTo("Git")
    }

    @Test
    fun aWordTypedSomewhereElseDoesNotResolveTheUndo() {
        // The cursor moved away after the undo: the editor no longer continues the anchor.
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")

        assertWithMessage("typed into an unrelated place")
            .that(tracker.onWordCommitted("git", "hello world git ")).isNull()
        assertThat(tracker.hasPendingRejection).isFalse()
    }

    @Test
    fun aWordGluedOntoThePreviousWordIsNotTheReplacement() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix")
        assertThat(tracker.onWordCommitted("git", "fixgit ")).isNull()
    }

    @Test
    fun theSecondWordAfterTheUndoCannotResolveIt() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onWordCommitted("the", "fix the ")
        assertThat(tracker.onWordCommitted("git", "fix the git ")).isNull()
    }

    @Test
    fun anUndoLeftUnansweredPastTheTimeoutIsDropped() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        now += SwipeCorrectionTracker.PENDING_TIMEOUT_MS + 1

        assertThat(tracker.onWordCommitted("git", "fix git ")).isNull()
    }

    @Test
    fun aSentenceBoundaryDropsAnUnansweredUndo() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")

        assertThat(tracker.settleOrClear()).isNull()
        assertThat(tracker.onWordCommitted("git", "fix git ")).isNull()
    }

    @Test
    fun aSwipeCanStillBeUndoneAfterASentenceBoundary() {
        // swipe "got", type ".", backspace twice: the #110 undo still removes the swiped word.
        val got = swipe("got")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.settleOrClear()
        tracker.onSwipeUndone("got", "fix ")

        assertThat(tracker.onWordCommitted("git", "fix git ")?.rejected).containsExactly(got)
    }

    @Test
    fun anUndoWithoutAnAnchorCannotBeResolved() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", null)
        assertThat(tracker.onWordCommitted("git", "fix git ")).isNull()
    }

    @Test
    fun undoOfAWordThatWasNotTheLastSwipeOpensNothing() {
        tracker.onSwipeAutoInserted(swipe("got"), "got ")
        tracker.onSwipeUndone("the", "")
        assertThat(tracker.hasPendingRejection).isFalse()
    }

    // ------------------------------------------------------------------ re-swipe after undo

    @Test
    fun aReSwipeAfterTheUndoSettlesWhenTheUserMovesOn() {
        val got = swipe("got", "got", "git")
        val git = swipe("git", "git", "got")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")

        assertWithMessage("the re-swipe is only a candidate")
            .that(tracker.onSwipeAutoInserted(git, "fix git ")).isNull()
        val c = tracker.onWordCommitted("now", "fix git now ")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got)))
    }

    @Test
    fun aReSwipeSettlesAtASentenceBoundary() {
        val got = swipe("got")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onSwipeAutoInserted(swipe("git"), "fix git ")

        assertThat(tracker.settleOrClear()?.chosen).isEqualTo("git")
    }

    @Test
    fun aReSwipeSettlesWhenAnotherSwipeFollows() {
        val got = swipe("got")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onSwipeAutoInserted(swipe("git"), "fix git ")

        val c = tracker.onSwipeAutoInserted(swipe("now"), "fix git now ")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got)))
    }

    @Test
    fun aWrongReSwipeThatIsUndoneAgainGrowsTheChain() {
        val got = swipe("got")
        val forWord = swipe("for")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onSwipeAutoInserted(forWord, "fix for ")
        tracker.onSwipeUndone("for", "fix ")

        val c = tracker.onWordCommitted("git", "fix git ")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got, forWord)))
    }

    @Test
    fun aWrongReSwipeReplacedFromTheBarCarriesTheWholeChain() {
        val got = swipe("got")
        val forWord = swipe("for", "for", "git")
        tracker.onSwipeAutoInserted(got, "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onSwipeAutoInserted(forWord, "fix for ")

        val c = tracker.onSwipeReplacedFromBar("for", "git")

        assertThat(c).isEqualTo(SwipeCorrectionTracker.Correction("git", listOf(got, forWord)))
    }

    @Test
    fun aReSwipeSomewhereElseDropsTheUndo() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.onSwipeUndone("got", "fix ")
        tracker.onSwipeAutoInserted(swipe("git"), "elsewhere git ")

        assertThat(tracker.settleOrClear()).isNull()
    }

    @Test
    fun clearForgetsEverything() {
        tracker.onSwipeAutoInserted(swipe("got"), "fix got ")
        tracker.clear()
        tracker.onSwipeUndone("got", "fix ")
        assertThat(tracker.hasPendingRejection).isFalse()
        assertThat(tracker.onSwipeReplacedFromBar("got", "git")).isNull()
    }

    // ------------------------------------------------------------------ anchor rule

    @Test
    fun editorAnchorRule() {
        val fn = SwipeCorrectionTracker.Companion::editorContinuesAnchor
        assertThat(fn("fix ", "fix git ", "git")).isTrue()
        assertThat(fn("fix ", "fix git", "git")).isTrue()
        assertThat(fn("fix ", "fix git!", "git")).isTrue()
        assertThat(fn("fix ", "fix GIT ", "git")).isTrue()
        assertThat(fn("(", "(git ", "git")).isTrue()
        assertThat(fn("fix ", "fix it git ", "git")).isFalse()
        assertThat(fn("fix ", "fox git ", "git")).isFalse()
        assertThat(fn("", "x git ", "git")).isFalse()
        // A full-window anchor is compared as a suffix of a longer field.
        val long = "a".repeat(SwipeCorrectionTracker.ANCHOR_WINDOW - 4) + " fix "
        assertThat(fn(long, "zz" + long + "git ", "git")).isTrue()
    }
}
