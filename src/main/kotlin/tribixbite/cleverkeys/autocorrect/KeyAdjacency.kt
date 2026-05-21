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

    /**
     * Default US-QWERTY position table. Each entry: char → (x, y) on a
     * uniform-key-width grid. Used when no runtime layout has been
     * injected via [setLayout]. Common accented Latin chars are mapped
     * to the position of their unaccented base so de/fr/es/it/pt/sv
     * users get adjacency credit on `é`, `ñ`, `ü`, etc.
     */
    private val DEFAULT_POSITIONS: Map<Char, Pair<Float, Float>> = buildMap {
        // Top row
        listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
            .forEachIndexed { i, c -> put(c, i.toFloat() to 0f) }
        // Home row — half-key indent
        listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
            .forEachIndexed { i, c -> put(c, (i + 0.5f) to 1f) }
        // Bottom row — one full key indent on left; right end of row is shorter
        listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
            .forEachIndexed { i, c -> put(c, (i + 1f) to 2f) }

        // Accented Latin chars share their unaccented base's position. This
        // means typos like `cafe → café` cost ~0 for the e↔é position (both
        // map to (2,0)), letting adjacency-aware autocorrect bridge accent
        // omissions on languages where the user's physical layout doesn't
        // include the accented form. When the user IS on a layout with a
        // dedicated key for the accent (German with ü, French with é etc.),
        // `setLayout` overrides this default with the real key position.
        listOf('á', 'à', 'â', 'ä', 'ã', 'å').forEach { put(it, get('a')!!) }
        listOf('é', 'è', 'ê', 'ë').forEach { put(it, get('e')!!) }
        listOf('í', 'ì', 'î', 'ï').forEach { put(it, get('i')!!) }
        listOf('ó', 'ò', 'ô', 'ö', 'õ', 'ø').forEach { put(it, get('o')!!) }
        listOf('ú', 'ù', 'û', 'ü').forEach { put(it, get('u')!!) }
        listOf('ñ').forEach { put(it, get('n')!!) }
        listOf('ç').forEach { put(it, get('c')!!) }
        listOf('ß').forEach { put(it, get('s')!!) }
        listOf('ý', 'ÿ').forEach { put(it, get('y')!!) }
    }

    /**
     * Currently-active position table — defaults to QWERTY, replaced by
     * the most recent [setLayout] call. `@Volatile` so calls from the UI
     * thread (`Keyboard2View.onLayout`) are visible to autocorrect calls
     * on the prediction thread without locking.
     */
    @Volatile
    private var positions: Map<Char, Pair<Float, Float>> = DEFAULT_POSITIONS

    /**
     * Normalization constant for [keyDistance]. Recomputed when the
     * layout changes; for the default QWERTY this is `q ↔ p = 9.0`
     * (both ends of the top row — farther than q↔m's √53 ≈ 7.28, the
     * value previously hardcoded). Computed via [computeMaxDistance]
     * so accent additions and runtime layouts can't drift out of sync.
     */
    @Volatile
    private var maxDistance: Float = computeMaxDistance(DEFAULT_POSITIONS)

    /**
     * Inject a runtime layout. Coordinates can be in any unit (pixels,
     * key-widths, anything); only relative distances matter. Each
     * char-to-position mapping replaces the default. Call from
     * `Keyboard2View.onLayout` (or equivalent) whenever the keyboard's
     * physical layout changes — orientation flip, layout swap, custom
     * layout import, etc.
     *
     * Pass an empty map (or call [resetLayout]) to revert to the
     * default US-QWERTY + accents table. This is also the safe state
     * when the layout isn't yet known (boot, layout load failure).
     */
    @JvmStatic
    fun setLayout(layoutPositions: Map<Char, Pair<Float, Float>>) {
        if (layoutPositions.isEmpty()) {
            positions = DEFAULT_POSITIONS
            maxDistance = computeMaxDistance(DEFAULT_POSITIONS)
            return
        }
        // Lower-case keys for case-insensitive lookup.
        val normalized = layoutPositions.mapKeys { it.key.lowercaseChar() }
        positions = normalized
        maxDistance = computeMaxDistance(normalized).coerceAtLeast(1e-6f)
    }

    /** Revert to the default US-QWERTY position table (plus accents). */
    @JvmStatic
    fun resetLayout() {
        positions = DEFAULT_POSITIONS
        maxDistance = computeMaxDistance(DEFAULT_POSITIONS)
    }

    /**
     * Farthest pair distance in the position table. Used as the
     * normalization denominator so all distances land in [0, 1].
     * Computed by full O(n²) pairwise comparison since the table is
     * tiny (~30 entries) and recomputed only on layout change.
     */
    private fun computeMaxDistance(p: Map<Char, Pair<Float, Float>>): Float {
        val values = p.values.toList()
        var max = 0f
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                val d = hypot(
                    values[i].first - values[j].first,
                    values[i].second - values[j].second
                )
                if (d > max) max = d
            }
        }
        return max
    }

    /**
     * Normalized euclidean distance between two characters' centers on
     * the active position table. Both chars are lowercased.
     *
     *   - Same char → 0.0
     *   - Adjacent keys → ≈ 0.137 (QWERTY default)
     *   - Distant corners → 1.0
     *
     * For characters NOT in the active table (digits, punctuation,
     * chars not on the user's layout), returns 1.0 — "as different as
     * possible." Safer than guessing a position for unknown chars.
     */
    fun keyDistance(a: Char, b: Char): Float {
        if (a == b) return 0f
        val p = positions  // local snapshot to dodge mid-call layout swap
        val pa = p[a.lowercaseChar()] ?: return 1f
        val pb = p[b.lowercaseChar()] ?: return 1f
        val d = hypot(pa.first - pb.first, pa.second - pb.second)
        return (d / maxDistance).coerceIn(0f, 1f)
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
