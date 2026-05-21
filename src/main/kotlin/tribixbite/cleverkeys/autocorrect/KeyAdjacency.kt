package tribixbite.cleverkeys.autocorrect

import kotlin.math.hypot

/**
 * Keyboard-physical-adjacency model used by autocorrect to score
 * character substitutions by physical distance on a QWERTY layout.
 *
 * # Why
 *
 * Users mistype keys most often by hitting an ADJACENT key — fat-finger
 * errors, thumb drift, etc. A correction candidate that requires
 * "wuestion → question" (one substitution, `w↔q` are adjacent on
 * QWERTY) is far more likely to be the intended word than one that
 * requires "wuestion → westion" (also one substitution, but `q↔w` and
 * `m↔n` are different distances). Position-only matching can't tell
 * these apart.
 *
 * # How
 *
 * Hardcoded US-QWERTY layout. Each letter is positioned by its visual
 * grid coordinate (row offsets are conventional: middle row indented
 * half a key, bottom row indented one full key).
 *
 *   Row 0:   q  w  e  r  t  y  u  i  o  p     (x = 0..9, y = 0)
 *   Row 1:    a  s  d  f  g  h  j  k  l       (x = 0.5..8.5, y = 1)
 *   Row 2:     z  x  c  v  b  n  m            (x = 1, 2, 3, 4, 5, 6, 7; y = 2)
 *
 * `keyDistance(a, b)` returns a value in `[0, 1]`:
 *   - 0.0 = same key
 *   - ~0.13 = adjacent keys (one key-width apart)
 *   - 1.0 = farthest pair (`q` ↔ `m`, ~7.28 key-widths)
 *
 * `substitutionScore(a, b) = 1 - keyDistance(a, b)`.
 *
 * # Future extension
 *
 * The current QWERTY table is hardcoded — it correctly handles the
 * default English layout. A future Tier-B improvement would accept a
 * runtime `Map<Char, PointF>` from `Keyboard2View.keyPositions` (already
 * produced + already wired into the swipe engine) to support Dvorak /
 * AZERTY / custom layouts. The shape of this API anticipates that
 * extension: replace the `POSITIONS` table with a setter-injected one.
 */
object KeyAdjacency {

    // Each entry: char → (x, y) on a uniform-key-width grid.
    // Distances computed via Euclidean on these coordinates.
    private val POSITIONS: Map<Char, Pair<Float, Float>> = buildMap {
        // Top row
        listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
            .forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
        // Home row — half-key indent
        listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
            .forEachIndexed { i, c -> put(c, (i + 0.5f) to 1f) }
        // Bottom row — one full key indent on left; right end of row is shorter
        listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
            .forEachIndexed { i, c -> put(c, (i + 1f) to 2f) }
    }

    /**
     * Farthest pair on the layout: `q` at (0,0) ↔ `m` at (7,2)
     * → euclidean = √(7² + 2²) ≈ 7.28. Used as the normalization
     * constant so all `keyDistance` outputs land in `[0, 1]`.
     */
    private val MAX_DISTANCE: Float = run {
        val q = POSITIONS['q']!!
        val m = POSITIONS['m']!!
        hypot(q.first - m.first, q.second - m.second)
    }

    /**
     * Normalized euclidean distance between two characters' centers on
     * the QWERTY grid. Both chars are lowercased.
     *
     *   - Same char → 0.0
     *   - Adjacent keys → ≈ 0.137 (1.0 / MAX_DISTANCE)
     *   - Diagonal one-key-away → ≈ 0.158
     *   - Distant corners → 1.0
     *
     * For characters NOT in the layout (digits, punctuation, accented
     * letters), returns 1.0 — i.e. "as different as possible." This
     * makes autocorrect refuse to bridge a non-letter typo to a letter
     * correction, which is the safer behavior.
     */
    fun keyDistance(a: Char, b: Char): Float {
        if (a == b) return 0f
        val pa = POSITIONS[a.lowercaseChar()] ?: return 1f
        val pb = POSITIONS[b.lowercaseChar()] ?: return 1f
        val d = hypot(pa.first - pb.first, pa.second - pb.second)
        return (d / MAX_DISTANCE).coerceIn(0f, 1f)
    }

    /**
     * Substitution score = 1 - keyDistance. Returned value:
     *   - Same char → 1.0
     *   - Adjacent → ≈ 0.86
     *   - Distant  → 0.0
     *
     * Designed to be summed across positions in a same-length word
     * comparison: `score = sum(substitutionScore(a[i], b[i])) / length`,
     * landing in `[0, 1]` for the overall match quality.
     */
    fun substitutionScore(a: Char, b: Char): Float = 1f - keyDistance(a, b)

    /**
     * Weighted Levenshtein distance: insertion / deletion cost 1.0,
     * substitution cost = `keyDistance(a, b)`. Adjacent-key substitutions
     * cost less than distant-key ones.
     *
     * Used for ±length-diff candidates: when the typed word and the
     * candidate differ in length by 1+ chars, position-wise comparison
     * can't apply, so we fall through to this edit-distance metric.
     */
    fun weightedEditDistance(a: String, b: String): Float {
        val n = a.length
        val m = b.length
        if (n == 0) return m.toFloat()
        if (m == 0) return n.toFloat()
        // Rolling two-row DP.
        var prev = FloatArray(m + 1) { it.toFloat() }
        val curr = FloatArray(m + 1)
        for (i in 1..n) {
            curr[0] = i.toFloat()
            val ai = a[i - 1]
            for (j in 1..m) {
                val bj = b[j - 1]
                val subCost = keyDistance(ai, bj)
                val sub = prev[j - 1] + subCost
                val ins = curr[j - 1] + 1f
                val del = prev[j] + 1f
                curr[j] = minOf(sub, ins, del)
            }
            prev = curr.copyOf()
        }
        return prev[m]
    }
}
