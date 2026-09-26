package tribixbite.cleverkeys.swipe.ctc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.ln
import kotlin.math.min

/**
 * Policy pins for [CtcLearnedPrior] and its [CtcBeamDecoder] seam (learning-system audit
 * 2026-09-26; eval `docs/eval/2026-09-26-learned-unigram-swipe-replay.md`).
 */
class CtcLearnedPriorTest {

    private val lambda = CtcScoringParams.LAMBDA_EN_JSON_SCALE
    private val policy = CtcLearnedPrior.Policy()

    private fun ev(usage: Int, recency: Double = 1.0, sel: Int = 0) =
        CtcLearnedPrior.WordEvidence(usage, recency, sel)

    // ── the formula ──────────────────────────────────────────────────────────────

    @Test
    fun unigramBonusNeverExceedsTheCapOrTheCeilingHeadroom() {
        for (f in listOf(134.0, 150.0, 173.0, 200.0, 221.0, 242.0, 254.0)) {
            val bound = min(CtcLearnedPrior.B_CAP, lambda * ln(CtcLearnedPrior.F_CEIL / f))
            for (n in listOf(3, 4, 6, 10, 20, 50, 100, 1000, 100_000)) {
                val b = policy.bonus(ev(n), ln(f), lambda)
                assertWithMessage("f=$f n=$n bonus=$b bound=$bound")
                    .that(b).isAtMost(bound + 1e-12)
                assertThat(b).isAtLeast(0.0)
                // With selections the total is bounded by the same plus the selection cap.
                val withSel = policy.bonus(ev(n, sel = 50), ln(f), lambda)
                assertThat(withSel).isAtMost(bound + CtcLearnedPrior.SEL_MARGIN + 1e-12)
                assertThat(withSel).isAtMost(policy.maxLift(ln(f), lambda) + 1e-12)
            }
        }
    }

    @Test
    fun zeroBelowTheEffectiveUseThreshold() {
        val lnF = ln(173.0)
        assertThat(policy.bonus(ev(0), lnF, lambda)).isEqualTo(0.0)
        assertThat(policy.bonus(ev(2), lnF, lambda)).isEqualTo(0.0)
        // Recency discounts usage: 5 uses at recency 0.5 is 2.5 effective — below 3.
        assertThat(policy.bonus(ev(5, recency = 0.5), lnF, lambda)).isEqualTo(0.0)
        // Stale (recency 0) usage is worth nothing however large.
        assertThat(policy.bonus(ev(10_000, recency = 0.0), lnF, lambda)).isEqualTo(0.0)
        // At the threshold it fires: 3 uses, or ONE manual selection (weight 3).
        assertThat(policy.bonus(ev(3), lnF, lambda)).isGreaterThan(0.0)
        assertThat(policy.bonus(ev(0, sel = 1), lnF, lambda)).isGreaterThan(0.0)
    }

    @Test
    fun monotoneNonDecreasingInEffectiveUses() {
        for (f in listOf(134.0, 173.0, 221.0)) {
            var prev = -1.0
            for (n in 0..200) {
                val b = policy.bonus(ev(n), ln(f), lambda)
                assertWithMessage("f=$f n=$n").that(b).isAtLeast(prev)
                prev = b
            }
            var prevSel = -1.0
            for (s in 0..40) {
                val b = policy.bonus(ev(0, sel = s), ln(f), lambda)
                assertWithMessage("f=$f sel=$s").that(b).isAtLeast(prevSel)
                prevSel = b
            }
        }
        // Strictly increasing below saturation for a word with headroom under the cap.
        val lnF = ln(221.0)
        assertThat(policy.bonus(ev(6), lnF, lambda)).isGreaterThan(policy.bonus(ev(3), lnF, lambda))
    }

    @Test
    fun wordAtTheCeilingGetsNoUnigramLift() {
        assertThat(policy.bonus(ev(500), ln(CtcLearnedPrior.F_CEIL), lambda)).isEqualTo(0.0)
    }

    @Test
    fun aliasKeysAndUnknownWordsGetZero() {
        val lexicon = setOf("git", "got")
        val prior = CtcLearnedPrior(
            evidence = { w -> if (w == "dont" || w == "git") ev(10_000, sel = 100) else null },
            isLexiconWord = { it in lexicon },
        )
        // `dont` is an injected alias key — not a lexicon member — so even huge evidence is 0.
        assertThat(prior.bonusFor("dont", ln(133.0), lambda)).isEqualTo(0.0)
        // A lexicon word with no evidence.
        assertThat(prior.bonusFor("got", ln(221.0), lambda)).isEqualTo(0.0)
        // The eligible, evidenced word does get one.
        assertThat(prior.bonusFor("git", ln(173.0), lambda)).isGreaterThan(0.0)
    }

    @Test
    fun noneIsInert() {
        assertThat(CtcLearnedPrior.NONE.bonusFor("the", ln(134.0), lambda)).isEqualTo(0.0)
    }

    /** The audit's arithmetic: git (173) lifted by 6 manual picks beats got's (221) own lift. */
    @Test
    fun gitWithSelectionsOvertakesGotsPriorGap() {
        val git = policy.bonus(ev(0, sel = 6), ln(173.0), lambda)
        val got = policy.bonus(ev(98), ln(221.0), lambda)
        val staticGap = lambda * (ln(221.0) - ln(173.0)) // 0.98 nats in got's favour
        assertWithMessage("git=$git got=$got gap=$staticGap").that(git - got).isGreaterThan(0.0)
        assertThat(git - got).isLessThan(staticGap + CtcLearnedPrior.SEL_MARGIN + 1e-9)
    }

    // ── the decoder seam ─────────────────────────────────────────────────────────

    private val az = "abcdefghijklmnopqrstuvwxyz".toCharArray()

    /** Emissions that support BOTH `got` and `git` (i/o split), `got` slightly ahead. */
    private fun gitGotEmissions(): CtcEmissions {
        val blank = az.size
        fun frame(vararg peaks: Pair<Int, Float>): FloatArray =
            FloatArray(az.size + 1) { c -> peaks.firstOrNull { it.first == c }?.second ?: -9f }
        val g = 'g' - 'a'; val i = 'i' - 'a'; val o = 'o' - 'a'; val t = 't' - 'a'
        val rows = listOf(
            frame(g to -0.05f), frame(blank to -0.1f),
            frame(o to -0.6f, i to -0.8f), frame(o to -0.6f, i to -0.8f),
            frame(blank to -0.1f), frame(t to -0.05f), frame(blank to -0.1f),
        )
        val values = FloatArray(rows.size * (az.size + 1))
        rows.forEachIndexed { k, r -> System.arraycopy(r, 0, values, k * r.size, r.size) }
        return CtcEmissions(values, rows.size, az.size + 1)
    }

    private fun gitGotTrie(): CtcLexiconTrie = CtcLexiconTrie(az).also {
        it.insert("got", 221.0); it.insert("git", 173.0); it.insert("gut", 160.0)
    }

    @Test
    fun noneDecodeIsIdenticalToTheDefaultDecode() {
        val params = CtcScoringParams.tunedV2(beamWidth = 50, topK = 3)
        val a = CtcBeamDecoder.decode(gitGotEmissions(), gitGotTrie(), params)
        val b = CtcBeamDecoder.decode(gitGotEmissions(), gitGotTrie(), params, CtcLearnedPrior.NONE)
        assertThat(b).isEqualTo(a)
        assertThat(a.all { it.learnedBonus == 0.0 }).isTrue()
    }

    @Test
    fun priorIsAddedToTheFinalScoreAndCanReorder() {
        val params = CtcScoringParams.tunedV2(beamWidth = 50, topK = 3)
        val base = CtcBeamDecoder.decode(gitGotEmissions(), gitGotTrie(), params)
        assertWithMessage("fixture: got must lead without a prior, was ${base.map { it.word }}")
            .that(base.first().word).isEqualTo("got")
        val prior = CtcLearnedPrior(
            evidence = { w -> if (w == "git") ev(0, sel = 6) else null },
            isLexiconWord = { it in setOf("got", "git", "gut") },
        )
        val lifted = CtcBeamDecoder.decode(gitGotEmissions(), gitGotTrie(), params, prior)
        val git = lifted.first { it.word == "git" }
        val gitBase = base.first { it.word == "git" }
        val expected = prior.bonusFor("git", gitBase.logFreq, params.lambda)
        assertThat(git.learnedBonus).isWithin(1e-12).of(expected)
        assertThat(git.finalScore).isWithin(1e-9).of(gitBase.finalScore + expected)
        assertThat(lifted.first().word).isEqualTo("git")
        // Unlifted words keep their exact scores.
        assertThat(lifted.first { it.word == "got" }).isEqualTo(base.first { it.word == "got" })
    }
}
