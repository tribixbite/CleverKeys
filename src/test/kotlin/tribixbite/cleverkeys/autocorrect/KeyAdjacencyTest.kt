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
        // q↔w are 1 unit apart horizontally; MAX_DISTANCE = 9.0 (q↔p,
        // same row, opposite ends). Expected: 1 / 9 ≈ 0.111.
        KeyAdjacency.resetLayout()
        val qw = KeyAdjacency.keyDistance('q', 'w')
        assertThat(qw).isWithin(0.01f).of(0.111f)
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
        // q at (0,0) ↔ p at (9,0) — opposite ends of the top row. This
        // is the max-distance pair (9.0), NOT q↔m (7.28) as the earlier
        // hardcoded MAX_DISTANCE assumed. q↔m normalizes to 7.28/9 ≈ 0.81.
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('q', 'p')).isWithin(0.001f).of(1.0f)
        assertThat(KeyAdjacency.keyDistance('q', 'm')).isWithin(0.01f).of(0.809f)
    }

    @Test
    fun nonLetterChar_distance_isMaximumOne() {
        // Numeric / punctuation chars are NOT in POSITIONS → distance 1.
        // Note: accented Latin chars (é, ñ, ü, etc.) DO have positions
        // now (mapped to their unaccented base), so they're excluded
        // from this test — see `accent_*_sharesUnaccentedPosition` for
        // their separate coverage.
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('1', 'a')).isEqualTo(1f)
        assertThat(KeyAdjacency.keyDistance('a', '!')).isEqualTo(1f)
        assertThat(KeyAdjacency.keyDistance('а', 'a')).isEqualTo(1f)  // Cyrillic 'а' (U+0430), NOT Latin
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
        // a (0.5,1) ↔ e (2,0) → √(1.5² + 1²) ≈ 1.803.
        // Normalized by max 9.0 (q↔p): 1.803/9 ≈ 0.200.
        KeyAdjacency.resetLayout()
        val ae = KeyAdjacency.keyDistance('a', 'e')
        assertThat(ae).isWithin(0.02f).of(0.200f)
    }

    // ── substitutionScore ─────────────────────────────────────────────

    @Test
    fun substitutionScore_sameChar_isOne() {
        assertThat(KeyAdjacency.substitutionScore('a', 'a')).isEqualTo(1f)
    }

    @Test
    fun substitutionScore_adjacent_isHigh() {
        // ≈ 1 - 0.111 = 0.889 (max distance is now q↔p = 9.0, not q↔m = 7.28)
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.substitutionScore('q', 'w')).isWithin(0.01f).of(0.889f)
    }

    @Test
    fun substitutionScore_farthestPair_isZero() {
        // True farthest pair is q↔p (same row, opposite ends).
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.substitutionScore('q', 'p')).isWithin(0.001f).of(0f)
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
        // "wuestion" → "question": substitute w→q (cost ≈ 1/9 ≈ 0.111
        // under the corrected MAX_DISTANCE = 9.0 = q↔p), 7 perfect.
        KeyAdjacency.resetLayout()
        val d = KeyAdjacency.weightedEditDistance("wuestion", "question")
        assertThat(d).isWithin(0.01f).of(0.111f)
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

    // ── Accented-letter coverage ──────────────────────────────────────
    // Tier-C extension: common Latin diacritics share their unaccented
    // base's position so de/fr/es/it/pt/sv autocorrect benefits.

    @Test
    fun accent_e_acute_sharesUnaccentedPosition() {
        // é (e-acute) maps to the position of e — so `cafe → café` has
        // zero substitution cost on the accent position. Reset layout
        // first to ensure we're on the default QWERTY+accents table
        // (preceding tests might have injected a custom layout).
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('e', 'é')).isEqualTo(0f)
        assertThat(KeyAdjacency.keyDistance('é', 'e')).isEqualTo(0f)  // symmetric
    }

    @Test
    fun accent_n_tilde_sharesUnaccentedPosition() {
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('n', 'ñ')).isEqualTo(0f)
    }

    @Test
    fun accent_c_cedilla_sharesUnaccentedPosition() {
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('c', 'ç')).isEqualTo(0f)
    }

    @Test
    fun accent_u_umlaut_sharesUnaccentedPosition() {
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('u', 'ü')).isEqualTo(0f)
    }

    @Test
    fun accent_s_szet_sharesS_position() {
        // German ß. Mapping is to s (closest unaccented phonetic match).
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('s', 'ß')).isEqualTo(0f)
    }

    @Test
    fun accent_yIsCovered_yAcuteSharesY() {
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('y', 'ý')).isEqualTo(0f)
    }

    @Test
    fun accent_diaeresis_isCovered_aDiaeresis() {
        KeyAdjacency.resetLayout()
        assertThat(KeyAdjacency.keyDistance('a', 'ä')).isEqualTo(0f)
    }

    // ── Layout injection (Tier B) ─────────────────────────────────────

    @Test
    fun setLayout_replacesDefaultPositions() {
        // Build a contrived 2-key layout: 'a' at origin, 'z' at (10, 0).
        // After injection, distance(a, z) should be 1.0 (they're the
        // only pair → max distance), and ALL chars not in the map
        // (including 'b', 'c', 'q', etc.) return 1.0 (unmapped).
        try {
            KeyAdjacency.setLayout(mapOf('a' to (0f to 0f), 'z' to (10f to 0f)))
            assertThat(KeyAdjacency.keyDistance('a', 'z')).isEqualTo(1f)
            // q is no longer in the table:
            assertThat(KeyAdjacency.keyDistance('a', 'q')).isEqualTo(1f)
            // Same char still 0:
            assertThat(KeyAdjacency.keyDistance('a', 'a')).isEqualTo(0f)
        } finally {
            KeyAdjacency.resetLayout()
        }
    }

    @Test
    fun setLayout_azertySwap_qAndAAreAdjacent() {
        // AZERTY swaps q↔a vs QWERTY. On AZERTY, q is at the top-left
        // and a is one row below it — they're VERTICALLY adjacent (one
        // row apart, same column). Build a stub AZERTY-like layout: q
        // at (0, 0), a at (0, 1). Distance should be much smaller than
        // QWERTY's q↔a (which is 1.118 → ~0.154 normalized).
        try {
            val azertyPositions = buildMap<Char, Pair<Float, Float>> {
                // Stub top + home rows in AZERTY order
                listOf('a', 'z', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
                    .forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
                listOf('q', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'm')
                    .forEachIndexed { i, c -> put(c, i.toFloat() to 1f) }
                // Bottom row with w in different position
                listOf('w', 'x', 'c', 'v', 'b', 'n')
                    .forEachIndexed { i, c -> put(c, (i + 1f) to 2f) }
            }
            KeyAdjacency.setLayout(azertyPositions)
            // On AZERTY, a (0,0) and q (0,1) are 1 unit apart vertically.
            // Max distance in this layout: a (0,0) ↔ n (6,2) ≈ 6.32.
            // Normalized: 1/6.32 ≈ 0.158
            val qaDist = KeyAdjacency.keyDistance('q', 'a')
            assertThat(qaDist).isLessThan(0.2f)
            // And q↔w on AZERTY (q at (0,1), w at (1,2)): diagonal,
            // distance √2 ≈ 1.414. Still relatively close. The point
            // is they're NOT at QWERTY positions.
            val qwDist = KeyAdjacency.keyDistance('q', 'w')
            // QWERTY q↔w is horizontal-adjacent (0.137). AZERTY q↔w is
            // diagonal-1-key. Different value confirms the layout swap
            // actually changed scoring.
            assertThat(qwDist).isNotEqualTo(0.137f)
        } finally {
            KeyAdjacency.resetLayout()
        }
    }

    @Test
    fun setLayout_emptyMap_revertsToDefault() {
        // Verify that passing an empty map (no-op call) falls back to
        // the default QWERTY+accents table rather than leaving an
        // empty positions map (which would make ALL keyDistance calls
        // return 1.0).
        try {
            KeyAdjacency.setLayout(mapOf('a' to (0f to 0f)))  // 1-key layout
            assertThat(KeyAdjacency.keyDistance('q', 'w')).isEqualTo(1f)  // q not mapped
            KeyAdjacency.setLayout(emptyMap())  // revert
            assertThat(KeyAdjacency.keyDistance('q', 'w')).isLessThan(0.2f)  // default
        } finally {
            KeyAdjacency.resetLayout()
        }
    }

    @Test
    fun setLayout_caseInsensitive() {
        // Upper-case keys in the input map should be normalized to
        // lower-case so subsequent lookups (which lowercase the input)
        // match. This protects against accidental case-sensitivity bugs
        // when callers pass mixed-case maps.
        try {
            KeyAdjacency.setLayout(mapOf('A' to (0f to 0f), 'Z' to (10f to 0f)))
            assertThat(KeyAdjacency.keyDistance('a', 'z')).isEqualTo(1f)
        } finally {
            KeyAdjacency.resetLayout()
        }
    }
}
