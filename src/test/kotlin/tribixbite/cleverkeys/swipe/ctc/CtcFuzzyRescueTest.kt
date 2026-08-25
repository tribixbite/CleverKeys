package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CtcFuzzyRescueTest {
    private val rescue = CtcFuzzyRescue.fromFrequencies(linkedMapOf(
        "proximity" to 220.0,
        "property" to 210.0,
        "proximal" to 100.0,
        "hello" to 255.0,
    ))

    @Test
    fun recoversNearestDictionarySurface() {
        assertThat(rescue.find("proxity", emptySet())).contains("proximity")
    }

    @Test
    fun neverReturnsExistingBeamWordOrDifferentPrefix() {
        assertThat(rescue.find("proxity", setOf("proximity"))).doesNotContain("proximity")
        assertThat(rescue.find("jello", emptySet())).isEmpty()
    }

    @Test
    fun editDistanceHonorsCap() {
        assertThat(CtcFuzzyRescue.editDistanceAtMost("proxity", "proximity", 2)).isEqualTo(2)
        assertThat(CtcFuzzyRescue.editDistanceAtMost("abc", "xyz", 1)).isGreaterThan(1)
    }

    @Test
    fun scanBudgetIsDeterministicAndHardBounded() {
        // The two closer-length candidates are visited first; the matching length+2 entry
        // is deterministically the third and remains reachable at an exact budget of three.
        assertThat(rescue.find("proxity", emptySet(), scanBudget = 3))
            .containsExactly("proximity")
        assertThat(rescue.find("proxity", emptySet(), scanBudget = 0)).isEmpty()
    }

    // ── mergeIntoBeam: the CK-150-025 append-below contract ─────────────────────
    //
    // The rescorer's rank-1 promotion guard is a score RATIO
    // (`SwipeContextRescorer.R_MIN = 0.5`, so promotion needs `score >= topScore / 2.0`).
    // These tests pin the two properties that guard depends on: rank one is untouched, and
    // no rescued score can ever clear the threshold.

    private val topK = 8

    /** The threshold a rescued score must stay strictly below. `R_MIN = 0.5` of top-1. */
    private fun promotionThreshold(topScore: Int) = topScore / 2.0

    @Test
    fun mergePreservesRankOneWordAndScoreForEveryNonEmptyBeam() {
        val fixtures = listOf(
            listOf("hello") to listOf(1000),
            listOf("hello", "hallo") to listOf(800, 800),
            listOf("hello", "hallo") to listOf(884, 12),
            listOf("hello", "hallo", "hell") to listOf(913, 60, 27),
            listOf("hello", "hallo", "hell", "help") to listOf(500, 300, 150, 50),
        )
        for ((words, scores) in fixtures) {
            val (merged, mergedScores) = CtcFuzzyRescue.mergeIntoBeam(
                words, scores, listOf("proximity", "property"), topK,
            )
            assertThat(merged.first()).isEqualTo(words.first())
            assertThat(mergedScores.first()).isEqualTo(scores.first())
            // Every real word keeps its exact score AND its exact position.
            assertThat(merged.subList(0, words.size)).isEqualTo(words)
            assertThat(mergedScores.subList(0, scores.size)).isEqualTo(scores)
            // Nothing appended can reach the rank-1 promotion threshold …
            val rescuedScores = mergedScores.drop(scores.size)
            assertThat(rescuedScores).isNotEmpty()
            assertThat(rescuedScores.all { it < promotionThreshold(scores.first()) }).isTrue()
            // … nor outrank the beam's own tail, unless the floor of 1 binds (tail scored 0/1).
            assertThat(rescuedScores.all { it < scores.last() || scores.last() <= 1 }).isTrue()
        }
    }

    @Test
    fun tiedTopScoresNeverYieldARescuedScoreAtOrAboveTheThreshold() {
        // The CK-150-025 reproduction: [800, 800] made the old clamp emit 801 > topScore.
        val (words, scores) = CtcFuzzyRescue.mergeIntoBeam(
            listOf("hello", "hallo"), listOf(800, 800), listOf("proximity", "property"), topK,
        )
        assertThat(words).containsExactly("hello", "hallo", "proximity", "property").inOrder()
        val rescuedScores = scores.drop(2)
        assertThat(rescuedScores).hasSize(2)
        assertThat(rescuedScores.none { it >= 800 }).isTrue()
        assertThat(rescuedScores.none { it >= 400 }).isTrue()
    }

    @Test
    fun rescuedScoresClearTheParityKnifeEdgeInBothDirections() {
        // `topScore / 2` was integer division compared against a float threshold, so promotion
        // eligibility turned on the PARITY of top-1: 884 gave `442 >= 442.0` (promotable) while
        // 913 gave `456 >= 456.5` (blocked). `(topScore - 1) / 2` is below both.
        for (topScore in listOf(884, 913)) {
            val (_, scores) = CtcFuzzyRescue.mergeIntoBeam(
                listOf("hello", "hallo"),
                listOf(topScore, topScore / 2),
                listOf("proximity", "property"),
                topK,
            )
            val rescuedScores = scores.drop(2)
            assertThat(rescuedScores).isNotEmpty()
            for (score in rescuedScores) {
                assertThat(score.toDouble()).isLessThan(promotionThreshold(topScore))
            }
        }
    }

    @Test
    fun aFullSlateGetsNoRescueAtAll() {
        val words = (1..topK).map { "w$it" }
        val scores = (1..topK).map { 1000 - it * 10 }
        val (merged, mergedScores) = CtcFuzzyRescue.mergeIntoBeam(
            words, scores, listOf("proximity", "property"), topK,
        )
        assertThat(merged).isEqualTo(words)
        assertThat(mergedScores).isEqualTo(scores)
    }

    @Test
    fun aThinSlateGetsTheRescueAtRankTwo() {
        val (words, scores) = CtcFuzzyRescue.mergeIntoBeam(
            listOf("hello"), listOf(900), listOf("proximity"), topK,
        )
        assertThat(words).containsExactly("hello", "proximity").inOrder()
        assertThat(scores.first()).isEqualTo(900)
        assertThat(scores[1]).isAtMost((900 - 1) / 2)
        assertThat(scores[1]).isAtLeast(1)
    }

    @Test
    fun anEmptySlateIsFilledDirectly() {
        val (words, scores) = CtcFuzzyRescue.mergeIntoBeam(
            emptyList(), emptyList(), listOf("proximity", "property"), topK,
        )
        assertThat(words).containsExactly("proximity", "property").inOrder()
        assertThat(scores).containsExactly(1000, 500).inOrder()
    }

    /** Three surfaces within edit distance 1 of "houss", so the `limit = 2` cap actually binds. */
    private val crowded = CtcFuzzyRescue.fromFrequencies(linkedMapOf(
        "house" to 300.0,
        "hours" to 200.0,
        "houses" to 100.0,
    ))

    @Test
    fun atMostTwoRescuedWordsAreAppendedEvenWithSpareSlots() {
        // `find`'s default `limit = 2` is the cap, observed through the merge: three candidates
        // are in range and the one-word beam leaves seven free slots, yet only two are appended.
        val candidates = crowded.find("houss", emptySet())
        assertThat(candidates).containsExactly("house", "hours").inOrder()
        val (words, _) = CtcFuzzyRescue.mergeIntoBeam(
            listOf("hello"), listOf(900), candidates, topK,
        )
        assertThat(words).containsExactly("hello", "house", "hours").inOrder()
    }

    @Test
    fun rescuedScoresDescendStrictlyAndSkipBeamDuplicates() {
        val (words, scores) = CtcFuzzyRescue.mergeIntoBeam(
            listOf("hello", "hallo"),
            listOf(600, 200),
            listOf("hallo", "proximity", "property"),
            topK,
        )
        // "hallo" is already in the beam and must not be duplicated.
        assertThat(words).containsExactly("hello", "hallo", "proximity", "property").inOrder()
        val start = minOf(200 - 1, (600 - 1) / 2)
        assertThat(scores.drop(2)).containsExactly(start, start - 1).inOrder()
    }
}
