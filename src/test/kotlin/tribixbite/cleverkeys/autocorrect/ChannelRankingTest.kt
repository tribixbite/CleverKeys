package tribixbite.cleverkeys.autocorrect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tribixbite.cleverkeys.Defaults

/**
 * Rule C's edit cost and ranking (`ChannelRanking.kt`, eval
 * `docs/eval/2026-10-18-channel-autocorrect-ranking.md` §1.1). Pure JVM on the built-in QWERTY
 * table.
 */
class ChannelRankingTest {

    private val w = ChannelWeights(distanceCost = 1f, indelCost = 1f, doubleCost = 0.6f, transpositionCost = 0.9f)
    private val eps = 1e-4f

    @Before
    fun qwerty() {
        KeyAdjacency.setLayoutLetters(emptyMap())
        KeyAdjacency.resetLayout()
    }

    @After
    fun restore() = KeyAdjacency.resetLayout()

    @Test
    fun identicalWordsCostNothing() {
        assertEquals(0f, ChannelEditCost.cost("device", "device", w), eps)
    }

    @Test
    fun substitutionCostGrowsWithKeyDistance() {
        // x→c are neighbours; x→p is across the board.
        val near = ChannelEditCost.cost("devixe", "device", w)
        assertEquals(1f + KeyAdjacency.keyDistance('x', 'c'), near, eps)
        val far = ChannelEditCost.cost("devipe", "device", w)
        assertTrue("far $far must exceed near $near", far > near)
    }

    @Test
    fun oneWrongKeyIsCheaperThanTwo() {
        // The maintainer's report: devixe must price device (1 sub) below decide (2 subs).
        val device = ChannelEditCost.cost("devixe", "device", w)
        val decide = ChannelEditCost.cost("devixe", "decide", w)
        assertTrue("device $device < decide $decide", device < decide)
        assertTrue(decide > 2f)
    }

    @Test
    fun transpositionCostsTau() {
        assertEquals(0.9f, ChannelEditCost.cost("teh", "the", w), eps)
        assertEquals(0.9f, ChannelEditCost.cost("recieve", "receive", w), eps)
    }

    @Test
    fun doubledLetterInsertionAndDeletionUseTheDoubleCost() {
        assertEquals(0.6f, ChannelEditCost.cost("helo", "hello", w), eps)      // dropped double
        assertEquals(0.6f, ChannelEditCost.cost("gamees", "games", w), eps)    // elongation
        assertEquals(1f, ChannelEditCost.cost("quesion", "question", w), eps)  // plain missing letter
        assertEquals(1f, ChannelEditCost.cost("questiom", "question", w) -
            KeyAdjacency.keyDistance('m', 'n'), eps)                             // one neighbour sub
    }

    @Test
    fun alignmentFeaturesPriceToTheDpCost() {
        val pairs = listOf(
            "devixe" to "device", "devixe" to "decide", "teh" to "the", "helo" to "hello",
            "gamees" to "games", "questin" to "question", "quuestion" to "quotation", "thsi" to "this",
            "shated" to "shared", "shated" to "states", "becuase" to "because", "tfe" to "tfw",
            "abc" to "", "" to "abc", "wuestion" to "question",
        )
        val weights = listOf(w, ChannelWeights(0f, 0.8f, 0.3f, 0.6f), ChannelWeights(4f, 1.5f, 1f, 1.2f))
        for ((t, c) in pairs) for (ww in weights) {
            val f = ChannelEditCost.alignmentFeatures(t, c, ww)
            assertEquals("$t → $c under $ww", ChannelEditCost.cost(t, c, ww), ChannelEditCost.priced(f, ww), eps)
        }
    }

    @Test
    fun rankStepsUseTheShippedBinScale() {
        assertEquals(0f, ChannelRanker.rankSteps(1_000_000, 1_000_000), eps)
        // .bin V2: frequency = 1 000 000 − rank · 3 900, while one step is maxFreq / 255 =
        // 3 921.6 (the DD-M unit), so rank 115 is 115 · 3 900 / 3 921.6 = 114.37 steps.
        assertEquals(114.37f, ChannelRanker.rankSteps(1_000_000 - 115 * 3_900, 1_000_000), 0.01f)
        assertEquals(0f, ChannelRanker.rankSteps(5, 0), eps)
    }

    @Test
    fun pickTradesFrequencyAgainstEdits() {
        // devixe: device rank 115, decide rank 111 (shipped .bin).
        val max = 1_000_000
        fun f(rank: Int) = max - rank * 3_900
        val cands = listOf(ChannelCandidate("decide", f(111), false), ChannelCandidate("device", f(115), false))
        val costs = floatArrayOf(
            ChannelEditCost.cost("devixe", "decide", w), ChannelEditCost.cost("devixe", "device", w))
        assertEquals("device", cands[ChannelRanker.pick(cands, costs, max, 0.02f)].word)
        // A large enough rarity gap still lets the common word win: tfe → the over tfw.
        val tfe = listOf(ChannelCandidate("tfw", f(220), false), ChannelCandidate("the", f(0), false))
        val tfeCosts = floatArrayOf(ChannelEditCost.cost("tfe", "tfw", w), ChannelEditCost.cost("tfe", "the", w))
        assertEquals("the", tfe[ChannelRanker.pick(tfe, tfeCosts, max, 0.02f)].word)
    }

    @Test
    fun tiesGoToAliasThenFrequencyThenWord() {
        val zero = floatArrayOf(1f, 1f)
        val alias = listOf(ChannelCandidate("done", 900, false), ChannelCandidate("dont", 900, true))
        assertEquals("dont", alias[ChannelRanker.pick(alias, zero, 1000, 0f)].word)
        val freq = listOf(ChannelCandidate("aaa", 500, false), ChannelCandidate("bbb", 600, false))
        assertEquals("bbb", freq[ChannelRanker.pick(freq, zero, 1000, 0f)].word)
        val word = listOf(ChannelCandidate("bbb", 600, false), ChannelCandidate("aaa", 600, false))
        assertEquals("aaa", word[ChannelRanker.pick(word, zero, 1000, 0f)].word)
        assertEquals(-1, ChannelRanker.pick(emptyList(), FloatArray(0), 1000, 0.02f))
    }

    @Test
    fun doubleReductionIsStructural() {
        assertTrue(isDoubleReduction("helo", "hello"))
        assertTrue(isDoubleReduction("spaming", "spamming"))
        assertTrue(isDoubleReduction("acomodate", "accommodate"))   // two doubles reduced
        assertTrue(!isDoubleReduction("shated", "shared"))          // substitution
        assertTrue(!isDoubleReduction("helo", "help"))              // substitution
        assertTrue(!isDoubleReduction("hel", "hello"))              // non-doubled letter missing
        assertTrue(!isDoubleReduction("hello", "hello"))            // identical
        assertTrue(!isDoubleReduction("hello", "helo"))             // wrong direction
    }

    @Test
    fun aliasBonusTipsAnEqualEditTowardTheContraction() {
        // donr: dont (alias) and done are both one r→t / r→e neighbour slip.
        val max = 1_000_000
        val cands = listOf(ChannelCandidate("done", max - 60 * 3_900, false), ChannelCandidate("dont", max - 70 * 3_900, true))
        val costs = floatArrayOf(ChannelEditCost.cost("donr", "done", w), ChannelEditCost.cost("donr", "dont", w))
        assertEquals("done", cands[ChannelRanker.pick(cands, costs, max, 0.005f, aliasBonus = 0f)].word)
        assertEquals("dont", cands[ChannelRanker.pick(cands, costs, max, 0.005f, aliasBonus = 0.25f)].word)
        assertEquals(1f - 0.25f, ChannelRanker.totalCost(1f, max, max, 0.005f, isAlias = true, aliasBonus = 0.25f), eps)
    }

    @Test
    fun inflectionCostTopOfRangeMeansAlwaysKeep() {
        assertEquals(Float.POSITIVE_INFINITY, ChannelWeights.inflectionCostFromPref(ChannelWeights.INFLECTION_ALWAYS_KEEP), 0f)
        assertEquals(Float.POSITIVE_INFINITY, ChannelWeights.inflectionCostFromPref(Float.NaN), 0f)
        assertEquals(1.5f, ChannelWeights.inflectionCostFromPref(1.5f), 0f)
        assertEquals(0f, ChannelWeights.inflectionCostFromPref(-3f), 0f)
        assertEquals(ChannelWeights.INFLECTION_ALWAYS_KEEP,
            AutocorrectTuning.Keys.CHANNEL_INFLECTION_COST.range.endInclusive, 0f)
    }

    @Test
    fun everyDefaultLiesInsideItsRange() {
        for (k in AutocorrectTuning.Keys.FLOATS) assertTrue("${k.key}=${k.default} ∉ ${k.range}", k.default in k.range)
        for (k in AutocorrectTuning.Keys.INTS) assertTrue("${k.key}=${k.default} ∉ ${k.range}", k.default in k.range)
        for (k in AutocorrectTuning.Keys.STRINGS) assertTrue("${k.key}=${k.default} ∉ ${k.values}", k.default in k.values)
        assertEquals(AutocorrectTuning.Keys.ALL_KEYS.size, AutocorrectTuning.Keys.ALL_KEYS.toSet().size)
        assertTrue(AutocorrectTuning.Keys.ALL_KEYS.all { it.startsWith("autocorrect_") })
    }

    @Test
    fun prefStringsRoundTrip() {
        for (r in AutocorrectRanking.entries) assertEquals(r, AutocorrectRanking.fromPref(r.prefValue))
        assertEquals(AutocorrectRanking.fromPref(Defaults.AUTOCORRECT_RANKING), AutocorrectRanking.fromPref("bogus"))
        for (v in tribixbite.cleverkeys.WordPredictor.NeighbourTiebreak.entries) {
            assertEquals(v, neighbourTiebreakFromPref(v.name.lowercase()))
        }
    }
}
