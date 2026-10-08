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

    private val lexicon = setOf(
        "git", "got", "for", "fix", "fox", "hello", "hi", "help", "id", "son", "soon", "gits", "gifts",
        "shed", "she'd", "i'd", "coop", "co-op",
    )
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
    fun onlyRealWordsOfTwoOrMoreLettersCount() {
        assertWithMessage("typo").that(plausible("got", "gxt", "gxt")).isFalse()
        assertWithMessage("single letter").that(plausible("at", "a", "a")).isFalse()
        assertWithMessage("digits are not joiners").that(plausible("got", "g0t", "g0t")).isFalse()
    }

    /** Since 2026-09-29 a joiner word is offerable: its dictionary entry is a display preference. */
    @Test
    fun apostropheAndHyphenWordsCount() {
        assertWithMessage("bar tap").that(plausible("id", "i'd", "id", "i'd")).isTrue()
        assertWithMessage("typed after undo: endpoints on the letters").that(plausible("shed", "she'd")).isTrue()
        assertWithMessage("hyphen word").that(plausible("coop", "co-op")).isTrue()
        assertWithMessage("a joiner alone is not a word").that(plausible("id", "'", "'")).isFalse()
        assertWithMessage("one letter plus a joiner").that(plausible("at", "a'", "a'")).isFalse()
        assertWithMessage("leading/trailing hyphen").that(plausible("coop", "-coop", "-coop")).isFalse()
        assertWithMessage("an unknown joiner form is still a typo").that(plausible("shed", "sh'ed", "sh'ed")).isFalse()
    }

    @Test
    fun joinerSurfaceIsTheLettersOfAJoinerWordOnly() {
        assertThat(SwipeCorrectionPolicy.joinerSurface("She'd")).isEqualTo("shed")
        assertThat(SwipeCorrectionPolicy.joinerSurface("co-op")).isEqualTo("coop")
        assertThat(SwipeCorrectionPolicy.joinerSurface("l’une")).isEqualTo("lune")
        assertThat(SwipeCorrectionPolicy.joinerSurface("git")).isNull()
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
        val add = SwipeCorrectionPolicy.offerLevel(null)
        assertThat(SwipeCorrectionPolicy.shouldOffer(min - 1, add, false)).isFalse()
        assertThat(SwipeCorrectionPolicy.shouldOffer(min, add, false)).isTrue()
        assertThat(SwipeCorrectionPolicy.shouldOffer(min + 3, add, false)).isTrue()
        assertWithMessage("nothing left to offer")
            .that(SwipeCorrectionPolicy.shouldOffer(min, null, false)).isFalse()
        assertWithMessage("declined before").that(SwipeCorrectionPolicy.shouldOffer(min, add, true)).isFalse()
    }

    /**
     * User swipe priority (2026-10-08): the offer's step ladder. A new word is ADDED (NORMAL);
     * a personal-dictionary word still corrected toward is RAISED to HIGH; the bar never offers
     * HIGHEST (Dictionary Manager only — its collateral is explained there).
     */
    @Test
    fun offerLevelLadder() {
        assertThat(SwipeCorrectionPolicy.offerLevel(null)).isEqualTo(SwipePriority.NORMAL)
        assertThat(SwipeCorrectionPolicy.offerLevel(SwipePriority.NORMAL)).isEqualTo(SwipePriority.HIGH)
        assertThat(SwipeCorrectionPolicy.offerLevel(SwipePriority.HIGH)).isNull()
        assertThat(SwipeCorrectionPolicy.offerLevel(SwipePriority.HIGHEST)).isNull()
        val min = SwipeCorrectionPolicy.OFFER_MIN_CORRECTIONS
        assertWithMessage("a NORMAL user word is offered the raise")
            .that(SwipeCorrectionPolicy.shouldOffer(min, SwipeCorrectionPolicy.offerLevel(SwipePriority.NORMAL), false))
            .isTrue()
        assertWithMessage("a HIGH user word is not offered anything")
            .that(SwipeCorrectionPolicy.shouldOffer(min, SwipeCorrectionPolicy.offerLevel(SwipePriority.HIGH), false))
            .isFalse()
    }
}
