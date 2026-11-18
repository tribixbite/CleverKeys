package tribixbite.cleverkeys.swipe

import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.ctc.CtcFeaturizer
import tribixbite.cleverkeys.swipe.ctc.CtcLayout

/**
 * Builds the [CtcLayout] the CTC encoder decodes against from a board's key rects — the pure core
 * of `CtcEngineAdapter.buildMappedLayout`, kept Android-free so a pure-JVM replay can drive the
 * SHIPPED mapping over a real layout file instead of a test's copy of it.
 *
 * One emission slot per alphabet letter, at that letter's first centre key (row-major); null when
 * any alphabet letter is not a centre key (the routing gate `CtcEngineAdapter.supportsLayout`).
 *
 * **Alias keys** (maintainer report 2026-10-25: bépo `été` swiped over the `é` key decoded `tp`).
 * With a [project]ion, every other single-letter centre key whose projection is ONE alphabet
 * letter (`é`→`e`, `ü`→`u`, `å`→`a`, `ñ`→`n`, `ø`→`o`) becomes an alias slot at its own centre
 * ([CtcLayout.aliasTargets]); a letter that projects to several (`ß`→`ss`) or to none (`ı`, `þ`)
 * stays slotless as before. The model frame is then the box of the alphabet AND alias keys' rects,
 * so a path over QWERTZ `ü` (right of `p`) is no longer clipped to the alphabet box's edge. A board
 * with no such key gets a byte-identical layout and frame. Measured on real human FUTO traces
 * (`docs/eval/2026-10-25-ctc-accent-keys.md`): German umlaut-key layout top-1 81.46 → 83.75 %.
 */
internal object CtcMappedLayoutBuilder {

    /**
     * The layout plus the affine mapping view px → the model frame (`(raw − origin) · inv`).
     *
     * @property letterRowCount distinct centre-y values of the alphabet keys (FingerOcclusion).
     */
    class Built(
        val layout: CtcLayout,
        val originX: Float,
        val originY: Float,
        val invW: Float,
        val invH: Float,
        val letterRowCount: Int,
    )

    /**
     * @param project the language's word projection onto [alphabet] (`CtcAzProjection.project`
     *   for the Latin family); null builds no alias slots.
     */
    fun build(
        rects: List<KeyboardGeometry.KeyRect>,
        alphabet: CharArray,
        project: ((String) -> String?)? = null,
    ): Built? {
        if (rects.isEmpty()) return null
        val k = alphabet.size
        val slotOf = HashMap<Char, Int>(k * 2)
        for (i in 0 until k) slotOf[alphabet[i]] = i
        val cx = FloatArray(k); val cy = FloatArray(k); val seen = BooleanArray(k)
        // Alias keys: raw centres + target slot, first occurrence of each letter only.
        val aliasLetters = HashSet<Char>()
        val aliasTargets = ArrayList<Int>()
        val aliasX = ArrayList<Float>()
        val aliasY = ArrayList<Float>()
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        fun include(b: KeyboardGeometry.KeyBounds) {
            if (b.left < left) left = b.left
            if (b.top < top) top = b.top
            if (b.right > right) right = b.right
            if (b.bottom > bottom) bottom = b.bottom
        }
        for (rect in rects) {
            val letter = KeyLetter.centreLetterOf(rect.kv) ?: continue
            val i = slotOf[letter]
            if (i == null) {
                if (project == null || letter in aliasLetters) continue
                if (k + aliasTargets.size >= CtcFeaturizer.MAX_KEYS) continue
                val target = project(letter.toString())?.singleOrNull()?.let { slotOf[it] } ?: continue
                aliasLetters.add(letter)
                aliasTargets.add(target)
                aliasX.add((rect.bounds.left + rect.bounds.right) / 2f)
                aliasY.add((rect.bounds.top + rect.bounds.bottom) / 2f)
                include(rect.bounds)
                continue
            }
            if (seen[i]) continue
            seen[i] = true
            cx[i] = (rect.bounds.left + rect.bounds.right) / 2f
            cy[i] = (rect.bounds.top + rect.bounds.bottom) / 2f
            include(rect.bounds)
        }
        if (seen.any { !it }) return null // the board does not expose this alphabet
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return null

        val invW = 1f / w
        val invH = 1f / h
        val layout = CtcLayout(
            alphabet.copyOf(),
            FloatArray(k) { (cx[it] - left) * invW },
            FloatArray(k) { (cy[it] - top) * invH },
            aliasTargets.toIntArray(),
            FloatArray(aliasX.size) { (aliasX[it] - left) * invW },
            FloatArray(aliasY.size) { (aliasY[it] - top) * invH },
        )
        // Row count from the geometry: every letter key in one row shares a row rect, so the
        // distinct centre-y values ARE the rows. Three on every board the adapter serves today.
        val letterRowCount = cy.toSortedSet().size.coerceAtLeast(1)
        return Built(layout, left, top, invW, invH, letterRowCount)
    }
}
