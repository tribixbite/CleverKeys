package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Pure JVM tests for [FuzzyPrefixMatcher] — the keyboard-weighted fuzzy PREFIX search behind
 * the typo-tolerant tap-typing bar (2026-09-26 report: "pka"/"pkay" should suggest "play").
 *
 * Covers the cost model (neighbour slip < swap < full edit; accent variants cheapest), that
 * neighbourhood follows the ACTIVE layout geometry, the per-length budgets, the implicit-trie
 * walk against a brute-force oracle, and the search's latency on the real 98k dictionary.
 */
class FuzzyPrefixMatcherTest {

    /** Minimal [FuzzyPrefixMatcher.PrefixSource] shaped exactly like the production indices. */
    private class ListSource(words: Collection<String>) : FuzzyPrefixMatcher.PrefixSource {
        val index = HashMap<String, MutableSet<String>>()
        init {
            for (w in words) for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
        }
        private val chars = FuzzyPrefixMatcher.alphabetOfKeys(index.keys)
        override val indexedDepth = 3
        override fun wordsWithPrefix(prefix: String): Collection<String>? = index[prefix]
        override fun alphabet(): CharArray = chars
    }

    private val small = ListSource(
        listOf(
            "play", "place", "plan", "plant", "please", "pla", "okay", "pack", "part", "pay",
            "the", "then", "there", "thwart", "question", "quest", "schmetterling", "café", "maß", "maßstab"
        )
    )

    @Before
    fun qwerty() = KeyAdjacency.resetLayout()

    @After
    fun restore() = KeyAdjacency.resetLayout()

    // ── cost model ────────────────────────────────────────────────────────

    @Test
    fun substitutionCost_neighbourSlip_isHalfAnEdit_distantIsAFullEdit() {
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'l')).isEqualTo(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST)
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'i')).isEqualTo(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST) // row diagonal
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'u')).isEqualTo(FuzzyPrefixMatcher.EDIT_COST) // second ring
        assertThat(FuzzyPrefixMatcher.substitutionCost('p', 's')).isEqualTo(FuzzyPrefixMatcher.EDIT_COST)
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'k')).isEqualTo(0f)
        assertThat(FuzzyPrefixMatcher.substitutionCost('K', 'k')).isEqualTo(0f)
    }

    @Test
    fun substitutionCost_accentVariant_isCheapestEdit() {
        assertThat(FuzzyPrefixMatcher.substitutionCost('e', 'é')).isEqualTo(FuzzyPrefixMatcher.ACCENT_VARIANT_COST)
        assertThat(FuzzyPrefixMatcher.ACCENT_VARIANT_COST).isLessThan(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST)
    }

    @Test
    fun costOrdering_neighbourSlip_lt_swap_lt_fullEdit() {
        assertThat(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST).isLessThan(FuzzyPrefixMatcher.TRANSPOSITION_COST)
        assertThat(FuzzyPrefixMatcher.TRANSPOSITION_COST).isLessThan(FuzzyPrefixMatcher.EDIT_COST)
        assertThat(FuzzyPrefixMatcher.distance("pkay", "play")).isEqualTo(0.5f)
        assertThat(FuzzyPrefixMatcher.distance("pakc", "pack")).isEqualTo(FuzzyPrefixMatcher.TRANSPOSITION_COST)
        assertThat(FuzzyPrefixMatcher.distance("psay", "play")).isEqualTo(1.0f)
        assertThat(FuzzyPrefixMatcher.distance("plays", "play")).isEqualTo(1.0f)
        assertThat(FuzzyPrefixMatcher.distance("ply", "play")).isEqualTo(1.0f)
    }

    @Test
    fun neighbourhood_followsTheActiveLayoutGeometry() {
        // One-row "layout" where k and l sit at opposite ends: no longer neighbours, while
        // k and z (adjacent in this row) become neighbours.
        val row = "kzqwertyuiopasdfghjxcvbnml"
        KeyAdjacency.setLayout(row.withIndex().associate { (i, c) -> c to (i.toFloat() to 0f) })
        assertThat(KeyAdjacency.areNeighbors('k', 'l')).isFalse()
        assertThat(KeyAdjacency.areNeighbors('k', 'z')).isTrue()
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'l')).isEqualTo(FuzzyPrefixMatcher.EDIT_COST)
        assertThat(FuzzyPrefixMatcher.substitutionCost('k', 'z')).isEqualTo(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST)
        // …and a pixel-unit layout gives the same answer as key-width units (scale-free).
        KeyAdjacency.setLayout(row.withIndex().associate { (i, c) -> c to (i * 96f to 40f) })
        assertThat(KeyAdjacency.areNeighbors('k', 'z')).isTrue()
        assertThat(KeyAdjacency.areNeighbors('k', 'q')).isFalse()
    }

    @Test
    fun defaultQwerty_neighbourRing_isTheSixSurroundingKeys() {
        val ring = ('a'..'z').filter { KeyAdjacency.areNeighbors('k', it) }.toSet()
        // k(7.5,1): j, l (row), i, o (upper diagonals), m (lower diagonal; bottom row has no
        // key at 8). n sits 1.8 away — second ring.
        assertThat(ring).isEqualTo(setOf('j', 'l', 'i', 'o', 'm'))
    }

    // ── budgets ───────────────────────────────────────────────────────────

    @Test
    fun budget_offBelowThreeLetters_andGrowsWithLength() {
        assertThat(FuzzyPrefixMatcher.budgetFor(1)).isEqualTo(0f)
        assertThat(FuzzyPrefixMatcher.budgetFor(2)).isEqualTo(0f)
        assertThat(FuzzyPrefixMatcher.budgetFor(3)).isEqualTo(0.6f)
        assertThat(FuzzyPrefixMatcher.budgetFor(4)).isEqualTo(1.0f)
        assertThat(FuzzyPrefixMatcher.budgetFor(7)).isEqualTo(1.5f)
        assertThat(FuzzyPrefixMatcher.budgetFor(8)).isEqualTo(2.0f)
        assertThat(FuzzyPrefixMatcher.budgetFor(FuzzyPrefixMatcher.MAX_TYPED_LENGTH + 1)).isEqualTo(0f)
    }

    @Test
    fun twoLetters_matchNothing() {
        assertThat(FuzzyPrefixMatcher.findMatches("pk", small)).isEmpty()
    }

    // ── search ────────────────────────────────────────────────────────────

    @Test
    fun pka_reachesThePlaCompletions_atOneNeighbourSlip() {
        val m = FuzzyPrefixMatcher.findMatches("pka", small)
        for (w in listOf("play", "place", "plan", "plant", "pla")) {
            assertThat(m[w]).isEqualTo(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST)
        }
        assertThat(m["okay"]).isEqualTo(FuzzyPrefixMatcher.ADJACENT_SUBSTITUTION_COST) // p↔o slip
        // 3 letters allow ONE slip/swap only — a deletion ("pa…") or distant sub is noise.
        assertThat(m).doesNotContainKey("pack")
        assertThat(m).doesNotContainKey("part")
    }

    @Test
    fun pkay_reachesPlay_andFourLettersAllowOneFullEdit() {
        val m = FuzzyPrefixMatcher.findMatches("pkay", small)
        assertThat(m["play"]).isEqualTo(0.5f)
        assertThat(m["okay"]).isEqualTo(0.5f)
        assertThat(m["pay"]).isEqualTo(1.0f) // the extra k deleted
    }

    @Test
    fun swap_isFound_andExactPrefix_costsZero() {
        assertThat(FuzzyPrefixMatcher.findMatches("pakc", small)["pack"]).isEqualTo(FuzzyPrefixMatcher.TRANSPOSITION_COST)
        assertThat(FuzzyPrefixMatcher.findMatches("teh", small)["the"]).isEqualTo(FuzzyPrefixMatcher.TRANSPOSITION_COST)
        assertThat(FuzzyPrefixMatcher.findMatches("pla", small)["plan"]).isEqualTo(0f)
    }

    @Test
    fun deepWords_areMatchedBelowTheIndexedDepth() {
        assertThat(FuzzyPrefixMatcher.findMatches("schmettw", small)["schmetterling"]).isEqualTo(0.5f)
        assertThat(FuzzyPrefixMatcher.findMatches("qyest", small)["question"]).isEqualTo(0.5f)
    }

    @Test
    fun nonInitialLetter_branchesAtShallowDepth() {
        // ß never starts a word but must still be a branch at depth 3 ("maß").
        assertThat(FuzzyPrefixMatcher.findMatches("naßs", small)["maßstab"]).isEqualTo(0.5f) // n↔m slip
        assertThat(FuzzyPrefixMatcher.findMatches("cafe", small)["café"]).isEqualTo(FuzzyPrefixMatcher.ACCENT_VARIANT_COST)
    }

    @Test
    fun trieWalk_equalsBruteForce_onRandomTypos() {
        val words = realDictionary().entries.sortedByDescending { it.value }.take(3000).map { it.key }
        val source = ListSource(words)
        val rnd = Random(20260926)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        repeat(60) {
            // Typo'd prefixes of real words: one random edit on a 3..8-letter prefix.
            val base = words[rnd.nextInt(words.size)].let { it.take(minOf(it.length, 3 + rnd.nextInt(6))) }
            if (base.length < 3) return@repeat
            val i = rnd.nextInt(base.length)
            val typed = when (rnd.nextInt(3)) {
                0 -> base.substring(0, i) + letters[rnd.nextInt(26)] + base.substring(i + 1)
                1 -> base.substring(0, i) + letters[rnd.nextInt(26)] + base.substring(i)
                else -> base.removeRange(i, i + 1)
            }
            val budget = FuzzyPrefixMatcher.budgetFor(typed.length)
            val fast = FuzzyPrefixMatcher.findMatches(typed, source)
            val brute = HashMap<String, Float>()
            if (budget > 0f) {
                for (w in words) oracleCost(typed, w, budget)?.let { brute[w] = it }
            }
            assertWithMessage("words for '$typed'").that(fast.keys).isEqualTo(brute.keys)
            for ((w, c) in brute) assertWithMessage("cost of '$w' for '$typed'").that(fast[w]).isWithin(1e-4f).of(c)
        }
    }

    /**
     * Independent full-matrix statement of [FuzzyPrefixMatcher.findMatches]'s contract: the
     * minimum over prefix lengths L of the OSA distance, where every depth d ≤ L must keep
     * its cheapest alignment (swap in progress allowed) within the depth budget.
     */
    private fun oracleCost(typed: String, w: String, budget: Float): Float? {
        val n = typed.length
        val e = FuzzyPrefixMatcher.EDIT_COST
        val t = FuzzyPrefixMatcher.TRANSPOSITION_COST
        val m = w.length
        val dm = Array(m + 1) { FloatArray(n + 1) }
        for (j in 0..n) dm[0][j] = j * e
        var best: Float? = null
        for (d in 1..m) {
            dm[d][0] = d * e
            for (j in 1..n) {
                var v = minOf(
                    dm[d - 1][j - 1] + FuzzyPrefixMatcher.substitutionCost(typed[j - 1], w[d - 1]),
                    dm[d - 1][j] + e,
                    dm[d][j - 1] + e
                )
                if (d >= 2 && j >= 2 && w[d - 1] == typed[j - 2] && w[d - 2] == typed[j - 1] && w[d - 1] != typed[j - 1]) {
                    v = minOf(v, dm[d - 2][j - 2] + t)
                }
                dm[d][j] = v
            }
            val rowMin = dm[d].minOrNull()!!
            val bound = if (d >= 2) minOf(rowMin, dm[d - 1].minOrNull()!! + t) else rowMin
            if (bound > FuzzyPrefixMatcher.depthBudget(d, n)) break
            val cost = dm[d][n]
            if (cost <= budget && (best == null || cost < best)) best = cost
        }
        return best
    }

    @Test
    fun depthBudget_capsTheShallowLevels() {
        assertThat(FuzzyPrefixMatcher.depthBudget(1, 8)).isEqualTo(1.0f)
        assertThat(FuzzyPrefixMatcher.depthBudget(4, 8)).isEqualTo(1.0f)
        assertThat(FuzzyPrefixMatcher.depthBudget(5, 8)).isEqualTo(1.5f)
        assertThat(FuzzyPrefixMatcher.depthBudget(8, 8)).isEqualTo(2.0f)
        assertThat(FuzzyPrefixMatcher.depthBudget(20, 8)).isEqualTo(2.0f)
        assertThat(FuzzyPrefixMatcher.depthBudget(1, 3)).isEqualTo(0.6f)
        // A slip plus a distant substitution inside the first letters of a long input is
        // pruned (1.5 > 1.0 by depth 2) even though its full cost fits the 2.0 budget.
        val src = ListSource(listOf("question", "questionnaire"))
        assertThat(FuzzyPrefixMatcher.findMatches("wuestionn", src)).containsKey("questionnaire")
        assertThat(FuzzyPrefixMatcher.findMatches("zyestion", src)).doesNotContainKey("question")
        assertThat(FuzzyPrefixMatcher.distance("zyestion", "question")).isAtMost(FuzzyPrefixMatcher.budgetFor(8))
    }

    @Test
    fun realDictionary_searchLatency() {
        val dict = realDictionary()
        val source = ListSource(dict.keys)
        val inputs = listOf("pka", "pkay", "thw", "wuestion", "becauae", "somethimg", "qqqqzzzz", "xyz", "abcdefghij")
        repeat(20) { inputs.forEach { FuzzyPrefixMatcher.findMatches(it, source) } } // JIT warm-up
        val rounds = 30
        for (typed in inputs) {
            var matches = 0
            val t0 = System.nanoTime()
            repeat(rounds) { matches = FuzzyPrefixMatcher.findMatches(typed, source).size }
            val us = (System.nanoTime() - t0) / rounds / 1000
            println("[fuzzy-matcher] %-11s %6dµs  %5d matches".format(typed, us, matches))
            // Generous per-call ceiling on a shared, loaded dev-phone JVM (a frame is 16ms).
            assertThat(us).isLessThan(10_000L)
        }
    }

    private fun realDictionary(): Map<String, Int> {
        val cached = dictCache
        if (cached != null) return cached
        val type = object : TypeToken<Map<String, Double>>() {}.type
        val raw: Map<String, Double> = File("src/main/assets/dictionaries/en_enhanced.json").reader().use { Gson().fromJson(it, type) }
        return raw.mapValues { it.value.toInt() }.also { dictCache = it }
    }

    private companion object {
        var dictCache: Map<String, Int>? = null
    }
}
