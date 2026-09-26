package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.SwipeCorrectionTracker.SwipeRecord

/**
 * The correction plausibility rule and the offer threshold ([SwipeCorrectionPolicy]).
 * The rule exists because changed-mind "corrections" poisoned the correction-prior replay
 * (docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md §5a).
 */
class SwipeCorrectionPolicyTest {

    private val lexicon = setOf("git", "got", "for", "fix", "fox", "hello", "hi", "help", "id", "son", "soon", "gits", "gifts")
    private val isReal: (String) -> Boolean = { it in lexicon }

    private fun plausible(x: String, y: String, vararg slate: String) =
        SwipeCorrectionPolicy.isPlausible(SwipeRecord(x, slate.toList(), null), y, isReal)

    @Test
    fun theReportedCaseIsPlausibleWithOrWithoutTheSlate() {
        assertThat(plausible("got", "git", "got", "git")).isTrue()
        assertWithMessage("same endpoints, same length").that(plausible("got", "git")).isTrue()
    }

    @Test
    fun aSlateMemberIsPlausibleEvenWithDifferentEndpoints() {
        assertWithMessage("for→git (in the slate)").that(plausible("for", "git", "for", "got", "git")).isTrue()
        assertWithMessage("for→git (no slate)").that(plausible("for", "git")).isFalse()
    }

    @Test
    fun aChangedMindRetypeIsFiltered() {
        assertWithMessage("hello undone, hi typed").that(plausible("hello", "hi", "hello", "help")).isFalse()
        assertWithMessage("same start, different end").that(plausible("fix", "for")).isFalse()
        assertWithMessage("length differs by 2").that(plausible("got", "gifts")).isFalse()
    }

    @Test
    fun endpointsRuleAllowsOneLetterOfLengthDifference() {
        assertThat(plausible("gits", "git")).isFalse() // ends differ (s vs t)
        assertThat(plausible("soon", "son")).isTrue() // the residue the offer step exists for
    }

    @Test
    fun theSameWordIsNotACorrection() {
        assertThat(plausible("git", "GIT", "git")).isFalse()
    }

    @Test
    fun onlyRealLettersOnlyWordsCount() {
        assertWithMessage("typo").that(plausible("got", "gxt", "gxt")).isFalse()
        assertWithMessage("apostrophe form").that(plausible("id", "i'd", "id", "i'd")).isFalse()
        assertWithMessage("single letter").that(plausible("at", "a", "a")).isFalse()
    }

    @Test
    fun aPromotedContractionReplacedByItsBaseIsPlausible() {
        // "I'd" auto-inserted for an "id" swipe; the base compares on the variant's letters.
        assertThat(plausible("I'd", "id", "I'd", "id")).isTrue()
        assertThat(plausible("I'd", "id")).isTrue()
    }

    @Test
    fun plausibleRejectionsKeepsOnlyThePlausiblePairs() {
        val got = SwipeRecord("got", listOf("got"), "t1")
        val hello = SwipeRecord("hello", listOf("hello"), "t2")
        val kept = SwipeCorrectionPolicy.plausibleRejections(
            SwipeCorrectionTracker.Correction("git", listOf(hello, got)), isReal
        )
        assertThat(kept).containsExactly(got)
    }

    @Test
    fun offerThreshold() {
        val min = SwipeCorrectionPolicy.OFFER_MIN_CORRECTIONS
        assertThat(min).isEqualTo(2)
        assertThat(SwipeCorrectionPolicy.shouldOffer(min - 1, false, false)).isFalse()
        assertThat(SwipeCorrectionPolicy.shouldOffer(min, false, false)).isTrue()
        assertThat(SwipeCorrectionPolicy.shouldOffer(min + 3, false, false)).isTrue()
        assertWithMessage("already a personal-dictionary word")
            .that(SwipeCorrectionPolicy.shouldOffer(min, true, false)).isFalse()
        assertWithMessage("declined before").that(SwipeCorrectionPolicy.shouldOffer(min, false, true)).isFalse()
    }
}
