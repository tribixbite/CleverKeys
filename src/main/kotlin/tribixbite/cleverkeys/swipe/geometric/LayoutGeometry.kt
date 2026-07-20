package tribixbite.cleverkeys.swipe.geometric

import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * A pure, layout-agnostic geometry model: the swipeable key nodes of the FINAL
 * modified layout (post bottom-row insertion + number-row / loc resolution) in a
 * normalized [0,1]² frame, plus the projection tables the dictionary uses.
 *
 * No `android.*` types appear here (NFR-3): centroids are plain floats, the
 * projection maps are int-keyed. All geometry lives in normalized-u/v space; the
 * physical anisotropy of the key area is carried by [aspect].
 *
 * @param keys all key nodes (letter nodes + alias-host-only nodes), dense ids.
 * @param chars codepoint → key ids (tier-1 single-codepoint-center matches plus
 *   tier-2 multi-codepoint-label component fallbacks). A codepoint may map to
 *   several keys (duplicate labels), hence `IntArray`.
 * @param aliases corner codepoint → host key id (tier-4 alias table: `ё`→`е`-host,
 *   loc-resolved Ukrainian/Arabic corners, etc.).
 * @param aspect keyAreaWidthPx / keyAreaHeightPx at build time (drives `d_kw`).
 * @param meanKeyWidth kw — the mean letter-node hit-box width in normalized-u units.
 */
class LayoutGeometry(
    val keys: List<SwipeKey>,
    val chars: Map<Int, IntArray>,
    val aliases: Map<Int, Int>,
    val aspect: Float,
    val meanKeyWidth: Float,
) {
    init {
        // Fail-fast on geometry that would silently poison every d_kw into NaN/Inf
        // (aspect divides dv; meanKeyWidth divides the whole distance) — the same
        // loud-failure policy GeometricEngineConfig's init applies to its knobs. A
        // WP9 adapter computing aspect from live pixel extents could otherwise feed
        // a 0-height race here and get garbage decodes instead of a clear error.
        require(aspect > 0f && aspect.isFinite()) {
            "aspect must be positive and finite, was $aspect"
        }
        require(meanKeyWidth > 0f && meanKeyWidth.isFinite()) {
            "meanKeyWidth must be positive and finite, was $meanKeyWidth"
        }
    }

    /** Number of letter nodes (excludes alias-host-only nodes). Coverage/DEAD-LAYOUT guard. */
    val letterNodeCount: Int = keys.count { it.isLetterNode }

    /**
     * Physical (isotropic) distance in key-width units between two NORMALIZED
     * points. Vertical deltas are divided by [aspect] so that a key that is
     * physically square contributes symmetric horizontal/vertical cost even when
     * the key area is not square in normalized space.
     *
     *     d_kw(a,b) = sqrt( (Δu)² + (Δv/aspect)² ) / kw
     */
    fun dKw(u1: Float, v1: Float, u2: Float, v2: Float): Float {
        val du = u1 - u2
        val dv = (v1 - v2) / aspect
        return sqrt(du * du + dv * dv) / meanKeyWidth
    }

    /**
     * The [k] key ids nearest to NORMALIZED point (u, v), ranked ascending by
     * [dKw]. Ties break by lower key id (deterministic, NFR-4). Returns fewer than
     * [k] ids only when the layout has fewer keys. Alias-host-only nodes ARE
     * candidates (their centroids are real swipe targets).
     */
    fun nearestKeys(u: Float, v: Float, k: Int): IntArray {
        val n = keys.size
        val take = if (k < n) k else n
        if (take <= 0) return IntArray(0)

        // Small-k partial selection: for the tiny k (2–3) used here, a linear scan
        // keeping a sorted top-k window beats a full sort/heap and allocates
        // nothing beyond the two result arrays.
        val bestId = IntArray(take) { -1 }
        val bestDist = FloatArray(take) { Float.POSITIVE_INFINITY }
        for (key in keys) {
            val d = dKw(u, v, key.cx, key.cy)
            // Insert (d, key.id) into the sorted window if it beats the worst slot,
            // or ties it on distance with a smaller id (stable tie-break).
            var slot = take - 1
            if (d > bestDist[slot] || (d == bestDist[slot] && key.id >= bestId[slot] && bestId[slot] >= 0)) {
                continue
            }
            // Shift larger entries right to make room.
            while (slot > 0 && (d < bestDist[slot - 1] ||
                    (d == bestDist[slot - 1] && key.id < bestId[slot - 1]))) {
                bestDist[slot] = bestDist[slot - 1]
                bestId[slot] = bestId[slot - 1]
                slot--
            }
            bestDist[slot] = d
            bestId[slot] = key.id
        }
        return bestId
    }

    /**
     * GEOMETRY-ONLY fingerprint — a SHA-256 hex digest over a canonical
     * serialization of the key nodes and layout aspect. It contains NO dictionary
     * identity (language/version live only in the cache key), so the same geometry
     * always hashes identically regardless of which dictionary is decoded against.
     *
     * Serialization (per key in (row, col) order): center codepoints, sorted alias
     * codepoints that host to this key, and cx/cy/w/h quantized to a 1/256 grid;
     * then aspect (quantized) and the engine schema version. Quantization makes the
     * hash DPI- and float-jitter-immune while staying orientation-SENSITIVE (aspect
     * is part of the digest — a rotation genuinely changes geometry, so a separate
     * index per orientation is correct).
     */
    fun fingerprint(): String {
        val sb = StringBuilder(keys.size * 24 + 32)
        sb.append("geo-schema-v").append(FINGERPRINT_SCHEMA_VERSION).append('\n')
        // Invert the alias table once so each host lists its incoming aliases
        // (sorted) — the hash must be stable under Map iteration-order changes.
        val aliasesByHost = HashMap<Int, MutableList<Int>>()
        for ((cp, hostId) in aliases) {
            aliasesByHost.getOrPut(hostId) { ArrayList() }.add(cp)
        }
        // (row, col) order is intrinsic to the key list construction (row-major),
        // but sort defensively so re-ordering the input list can never shift the
        // hash silently.
        for (key in keys.sortedWith(compareBy({ it.row }, { it.col }))) {
            sb.append(key.row).append(':').append(key.col).append('|')
            // Center codepoints (already case-folded), in string order.
            var i = 0
            while (i < key.label.length) {
                val cp = key.label.codePointAt(i)
                sb.append(cp).append(',')
                i += Character.charCount(cp)
            }
            sb.append('|')
            aliasesByHost[key.id]?.sorted()?.forEach { sb.append(it).append(',') }
            sb.append('|')
            sb.append(q256(key.cx)).append(',').append(q256(key.cy)).append(',')
              .append(q256(key.w)).append(',').append(q256(key.h))
            sb.append('\n')
        }
        sb.append("aspect=").append(q256(aspect)).append('\n')

        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    companion object {
        /** Bumped when the fingerprint serialization changes → forces cold rebuild. */
        const val FINGERPRINT_SCHEMA_VERSION = 1

        private val HEX = "0123456789abcdef".toCharArray()

        /** Quantize a float to a 1/256 grid (jitter-immune fingerprint field). */
        private fun q256(f: Float): Int = Math.round(f * 256f)
    }

    /**
     * Builds a [LayoutGeometry] from rows of parsed keys, faithfully reproducing
     * `KeyboardData`'s geometry semantics (per-key width/shift, per-row
     * height/shift/scale renormalization) WITHOUT any Android dependency.
     *
     * Coordinate accumulation mirrors `a11y/KeyboardGeometry.computeKeyRects`:
     *  - x within a row: `xLeft = x + shift*kw_px; xRight = xLeft + width*kw_px`.
     *  - y across rows:  `yTop = y + rowShift*rh_px; yBottom = y + (rowShift+rowHeight)*rh_px`.
     * Then everything is normalized by the total keyboard width/height so the
     * result is in [0,1]², matching what the WP9 adapter will feed at runtime.
     *
     * The Builder is agnostic to WHICH keys are letters — the caller (the fixture
     * parser / future adapter) supplies each key's classification and the
     * projection tables; the Builder only does geometry + fingerprint plumbing.
     */
    class Builder(
        /** keyAreaWidthPx / keyAreaHeightPx of the frame this layout is measured in. */
        private val aspect: Float,
    ) {
        /**
         * One raw key as handed to the Builder: its labels, geometry attributes,
         * and projection contributions. The Builder assigns the dense id and
         * computes the normalized centroid.
         *
         * @param centerLabel case-folded center label (may be "" for alias-host-only).
         * @param widthUnits relative width (default 1.0).
         * @param shiftUnits relative left blank space (default 0.0).
         * @param isLetterNode whether this is a letter node.
         * @param charCodepoints codepoints contributing to `chars` (tier-1 + tier-2).
         * @param aliasCodepoints corner codepoints this key hosts (tier-4).
         */
        class RawKey(
            val centerLabel: String,
            val widthUnits: Float,
            val shiftUnits: Float,
            val isLetterNode: Boolean,
            val charCodepoints: IntArray,
            val aliasCodepoints: IntArray,
        )

        /**
         * One raw row: its keys plus per-row geometry attributes. When [scaleUnits]
         * > 0 the row is renormalized so its total key width equals [scaleUnits]
         * (KeyboardData `Row.updateWidth`) — each key's *width* is multiplied by
         * `scaleUnits / rawKeysWidth` (shifts are NOT scaled, matching `scaleWidth`).
         */
        class RawRow(
            val keys: List<RawKey>,
            val heightUnits: Float,
            val shiftUnits: Float,
            val scaleUnits: Float,
        )

        private val rows = ArrayList<RawRow>()

        fun addRow(row: RawRow): Builder {
            rows.add(row)
            return this
        }

        fun build(): LayoutGeometry {
            require(rows.isNotEmpty()) { "cannot build a LayoutGeometry with no rows" }

            // 1) Apply row-scale renormalization (KeyboardData.Row.updateWidth).
            //    updateWidth multiplies each key WIDTH by scale/keysWidth, where
            //    keysWidth = Σ(width + shift); shifts are left untouched.
            val scaledRows = rows.map { row ->
                if (row.scaleUnits > 0f) {
                    val keysWidth = row.keys.fold(0f) { acc, k -> acc + k.widthUnits + k.shiftUnits }
                    val factor = if (keysWidth > 0f) row.scaleUnits / keysWidth else 1f
                    val scaledKeys = row.keys.map { k ->
                        RawKey(k.centerLabel, k.widthUnits * factor, k.shiftUnits,
                            k.isLetterNode, k.charCodepoints, k.aliasCodepoints)
                    }
                    RawRow(scaledKeys, row.heightUnits, row.shiftUnits, 0f)
                } else row
            }

            // 2) Keyboard extents in relative units (KeyboardData semantics):
            //    total width = max row keysWidth; total height = Σ(rowHeight+rowShift).
            var totalWidthUnits = 0f
            var totalHeightUnits = 0f
            for (row in scaledRows) {
                val rowWidth = row.keys.fold(0f) { acc, k -> acc + k.widthUnits + k.shiftUnits }
                if (rowWidth > totalWidthUnits) totalWidthUnits = rowWidth
                totalHeightUnits += row.heightUnits + row.shiftUnits
            }
            require(totalWidthUnits > 0f && totalHeightUnits > 0f) {
                "degenerate layout extents: w=$totalWidthUnits h=$totalHeightUnits"
            }

            // 3) Walk rows/keys accumulating normalized centroids + hit boxes.
            val keys = ArrayList<SwipeKey>()
            val chars = HashMap<Int, MutableList<Int>>()
            val aliases = HashMap<Int, Int>()
            var yUnits = 0f
            var id = 0
            var letterWidthSum = 0f
            var letterCount = 0
            for ((rowIdx, row) in scaledRows.withIndex()) {
                val yTop = yUnits + row.shiftUnits
                val yBottom = yTop + row.heightUnits
                var xUnits = 0f
                var col = 0
                for (raw in row.keys) {
                    val xLeft = xUnits + raw.shiftUnits
                    val xRight = xLeft + raw.widthUnits
                    // Normalize into [0,1]².
                    val cx = ((xLeft + xRight) * 0.5f) / totalWidthUnits
                    val cy = ((yTop + yBottom) * 0.5f) / totalHeightUnits
                    val w = raw.widthUnits / totalWidthUnits
                    val h = row.heightUnits / totalHeightUnits

                    val keyId = id++
                    keys.add(
                        SwipeKey(
                            id = keyId,
                            label = raw.centerLabel,
                            cx = cx, cy = cy, w = w, h = h,
                            row = rowIdx, col = col,
                            isLetterNode = raw.isLetterNode,
                        )
                    )
                    for (cp in raw.charCodepoints) chars.getOrPut(cp) { ArrayList() }.add(keyId)
                    for (cp in raw.aliasCodepoints) {
                        // First writer wins on alias collisions (deterministic:
                        // earlier row/col in row-major order). Real collisions are
                        // rare and the loser is still reachable via tier 1 if it is
                        // itself a center codepoint elsewhere.
                        if (!aliases.containsKey(cp)) aliases[cp] = keyId
                    }
                    if (raw.isLetterNode) {
                        letterWidthSum += w
                        letterCount++
                    }
                    xUnits = xRight
                    col++
                }
                yUnits = yBottom
            }

            // 4) kw = mean LETTER-node hit-box width (normalized-u units). Falls
            //    back to mean over all keys if there are no letter nodes (a dead
            //    layout — decode() will short-circuit anyway, but kw must be finite).
            val meanKeyWidth = when {
                letterCount > 0 -> letterWidthSum / letterCount
                keys.isNotEmpty() -> keys.fold(0f) { a, k -> a + k.w } / keys.size
                else -> 1f
            }

            val frozenChars = HashMap<Int, IntArray>(chars.size)
            for ((cp, ids) in chars) frozenChars[cp] = ids.toIntArray()

            return LayoutGeometry(
                keys = keys,
                chars = frozenChars,
                aliases = aliases,
                aspect = aspect,
                meanKeyWidth = meanKeyWidth,
            )
        }
    }
}
