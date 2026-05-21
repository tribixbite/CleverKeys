package tribixbite.cleverkeys.autocorrect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the keyboard-adjacency scoring model used by
 * autocorrect. Verifies the QWERTY position table, normalized distances,
 * substitution scores, and weighted edit distance.
 */
class KeyAdjacencyTest {

    // ── keyDistance ───────────────────────────────────────────────────

    @Test
    fun sameChar_distance_isZero() {
        assertThat(KeyAdjacency.keyDistance('a', 'a')).isEqualTo(0f)
        assertThat(KeyAdjacency.keyDistance('Q', 'q')).isEqualTo(0f)  // case-insensitive
        assertThat(KeyAdjacency.keyDistance('m', 'M')).isEqualTo(0f)
    }

    @Test
    fun horizontallyAdjacent_distance_isOneKeyWidthNormalized() {
        // q↔w are 1 unit apart horizontally; MAX_DISTANCE ≈ 7.28 (q↔m).
        // Expected: 1 / 7.28 ≈ 0.137
        val qw = KeyAdjacency.keyDistance('q', 'w')
        assertThat(qw).isWithin(0.01f).of(0.137f)
        // Symmetric
        assertThat(KeyAdjacency.keyDistance('w', 'q')).isEqualTo(qw)
    }

    @Test
    fun diagonallyAdjacent_distance_isSlightlyHigherThanHorizontal() {
        // q at (0,0), s at (1.5,1) → √(1.5² + 1²) = 1.803
        // q↔a is √(0.5² + 1²) = 1.118
        // Verify diagonal is greater than horizontal.
        assertThat(KeyAdjacency.keyDistance('q', 's'))
            .isGreaterThan(KeyAdjacency.keyDistance('q', 'w'))
    }

    @Test
    fun farthestPair_distance_isOne() {
        // q at (0,0) ↔ m at (7,2) → max-distance pair.
        assertThat(KeyAdjacency.keyDistance('q', 'm')).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun nonLetterChar_distance_isMaximumOne() {
        // Numeric / punctuation / accented chars not in POSITIONS table.
        assertThat(KeyAdjacency.keyDistance('1', 'a')).isEqualTo(1f)
        assertThat(KeyAdjacency.keyDistance('a', '!')).isEqualTo(1f)
        assertThat(KeyAdjacency.keyDistance('é', 'e')).isEqualTo(1f)
    }

    @Test
    fun homeRow_adjacencyChain_isSymmetric() {
        // a↔s, s↔d, d↔f all 1 unit horizontally (same y, x+1).
        val asD = KeyAdjacency.keyDistance('a', 's')
        val sd = KeyAdjacency.keyDistance('s', 'd')
        val df = KeyAdjacency.keyDistance('d', 'f')
        assertThat(asD).isEqualTo(sd)
        assertThat(sd).isEqualTo(df)
    }

    @Test
    fun specificAdjacencyClaim_wuestionQuestion() {
        // w↔q distance must be relatively small (adjacent) — supports
        // "wuestion → question" being a high-scoring correction.
        val wq = KeyAdjacency.keyDistance('w', 'q')
        assertThat(wq).isLessThan(0.2f)
    }

    @Test
    fun specificAdjacencyClaim_yiuYou() {
        // i↔o distance must be small (adjacent) — supports "yiu → you"
        // being a high-scoring correction.
        val io = KeyAdjacency.keyDistance('i', 'o')
        assertThat(io).isLessThan(0.2f)
    }

    @Test
    fun specificAdjacencyClaim_tgeThe() {
        // g↔h distance must be small (adjacent) — supports "tge → the"
        // being a high-scoring correction (g and h are adjacent home-row
        // keys).
        val gh = KeyAdjacency.keyDistance('g', 'h')
        assertThat(gh).isLessThan(0.2f)
    }

    @Test
    fun specificAdjacencyClaim_natNet() {
        // The user's example: a↔e is approximately 1.5 key-widths.
        // Both are home-row-adjacent-ish (a is home-row left edge; e is
        // top row position 2). Expect "1.5 key-widths" mentioned in the
        // user's spec.
        val ae = KeyAdjacency.keyDistance('a', 'e')
        // (0.5,1) ↔ (2,0) → √(1.5² + 1²) ≈ 1.803 → /7.28 ≈ 0.248
        assertThat(ae).isWithin(0.02f).of(0.248f)
    }

    // ── substitutionScore ─────────────────────────────────────────────

    @Test
    fun substitutionScore_sameChar_isOne() {
        assertThat(KeyAdjacency.substitutionScore('a', 'a')).isEqualTo(1f)
    }

    @Test
    fun substitutionScore_adjacent_isHigh() {
        // ≈ 1 - 0.137 = 0.863
        assertThat(KeyAdjacency.substitutionScore('q', 'w')).isWithin(0.01f).of(0.863f)
    }

    @Test
    fun substitutionScore_farthestPair_isZero() {
        assertThat(KeyAdjacency.substitutionScore('q', 'm')).isWithin(0.001f).of(0f)
    }

    // ── weightedEditDistance ──────────────────────────────────────────

    @Test
    fun weightedEditDistance_identicalStrings_isZero() {
        assertThat(KeyAdjacency.weightedEditDistance("question", "question")).isEqualTo(0f)
    }

    @Test
    fun weightedEditDistance_emptyString_returnsLengthOfOther() {
        assertThat(KeyAdjacency.weightedEditDistance("", "abc")).isEqualTo(3f)
        assertThat(KeyAdjacency.weightedEditDistance("xy", "")).isEqualTo(2f)
    }

    @Test
    fun weightedEditDistance_singleAdjacentSub_isLessThanOne() {
        // "wuestion" → "question": substitute w→q (cost ≈ 0.137), 7 perfect.
        val d = KeyAdjacency.weightedEditDistance("wuestion", "question")
        assertThat(d).isWithin(0.01f).of(0.137f)
    }

    @Test
    fun weightedEditDistance_singleDistantSub_isMuchHigherThanAdjacent() {
        // "muestion" → "question": substitute m→q (cost 1.0, farthest pair).
        val adjacent = KeyAdjacency.weightedEditDistance("wuestion", "question")
        val distant = KeyAdjacency.weightedEditDistance("muestion", "question")
        assertThat(distant).isGreaterThan(adjacent * 5f)
    }

    @Test
    fun weightedEditDistance_insertion_costsOne() {
        // "questin" → "question": insert 'o' before final 'n' = 1.0.
        val d = KeyAdjacency.weightedEditDistance("questin", "question")
        assertThat(d).isWithin(0.01f).of(1f)
    }

    @Test
    fun weightedEditDistance_deletion_costsOne() {
        // "quuestion" → "question": delete one 'u' = 1.0.
        val d = KeyAdjacency.weightedEditDistance("quuestion", "question")
        assertThat(d).isWithin(0.01f).of(1f)
    }

    @Test
    fun weightedEditDistance_adjacentSubBeatsDeletion() {
        // For words of the same length, adjacent-sub should be much
        // cheaper than insert+delete. Sanity: "wuestion" vs "question"
        // (sub) should cost less than treating it as one insert + one
        // delete (which would cost 2.0).
        val d = KeyAdjacency.weightedEditDistance("wuestion", "question")
        assertThat(d).isLessThan(1f)
    }
}
