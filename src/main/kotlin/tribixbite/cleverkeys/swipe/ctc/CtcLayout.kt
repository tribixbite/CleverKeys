package tribixbite.cleverkeys.swipe.ctc

/**
 * Geometry of a keyboard layout as the CTC encoder consumes it: the active alphabet in
 * emission-column order plus each key's center in the model's [0,1] frame.
 *
 * The ordering contract is the crux (integration study §2, "Layout inputs"): emission
 * class column `c` in a [CtcEmissions] matrix corresponds to `alphabet[c]`, and
 * `keyCentersX[c]`/`keyCentersY[c]` are that same key's center. The same ordering feeds
 * the trie ([CtcLexiconTrie]) so `get_char_idx` and the emission columns agree. For every
 * layout the shipped adapter builds today the ordering is alphabetical a..z
 * (`CtcEngineAdapter.ALPHABET`), but this type is deliberately alphabet-generic — any
 * characters, any K up to [CtcFeaturizer.MAX_KEYS] — because the encoder takes key
 * geometry as an INPUT and has no alphabet of its own. Extending to another script is
 * therefore a matter of handing a different `alphabet` + centers in, not of re-baking a
 * layout into the model (`docs/specs/ctc-architecture-and-multiscript-guide.md` §1).
 *
 * ## Alias keys (2026-10-25)
 *
 * A board can carry a letter the alphabet has no column for as a CENTRE key of its own — bépo's
 * `é`, QWERTZ's `ü ö ä`, Swedish `å`, Spanish `ñ`. Without a slot the encoder sees the finger
 * travel over a spot where no key exists and reads the nearest alphabet keys instead (bépo
 * `été` decoded as `tp`). Such a key is an ALIAS: it occupies an encoder slot after the K
 * alphabet slots, at its own centre, and its emission column is folded (log-sum-exp) into its
 * base letter's column ([aliasTargets]) — so to the beam and the trie, a path over `é` is an
 * `e`, and the lexicon's display map restores `été`. The encoder is slot-invariant (its key
 * embedding is a function of the centre only), which is what makes the extra slots meaningful.
 *
 * @property alphabet active keys in emission-column order (length K).
 * @property keyCentersX per-key center x in [0,1] (length K, aligned to [alphabet]).
 * @property keyCentersY per-key center y in [0,1] (length K, aligned to [alphabet]).
 * @property aliasTargets per alias key, the [alphabet] index its emission folds into (length A).
 * @property aliasCentersX per alias key, center x in the same [0,1] frame (length A).
 * @property aliasCentersY per alias key, center y in the same [0,1] frame (length A).
 */
class CtcLayout(
    val alphabet: CharArray,
    val keyCentersX: FloatArray,
    val keyCentersY: FloatArray,
    val aliasTargets: IntArray = IntArray(0),
    val aliasCentersX: FloatArray = FloatArray(0),
    val aliasCentersY: FloatArray = FloatArray(0),
) {
    init {
        require(alphabet.isNotEmpty()) { "layout alphabet must be non-empty" }
        require(alphabet.size == keyCentersX.size && alphabet.size == keyCentersY.size) {
            "alphabet/centers length mismatch: ${alphabet.size}/${keyCentersX.size}/${keyCentersY.size}"
        }
        require(alphabet.toHashSet().size == alphabet.size) {
            "layout alphabet has duplicate keys (the engine forbids duplicate letters)"
        }
        require(aliasTargets.size == aliasCentersX.size && aliasTargets.size == aliasCentersY.size) {
            "alias targets/centers length mismatch: ${aliasTargets.size}/${aliasCentersX.size}/${aliasCentersY.size}"
        }
        require(aliasTargets.all { it in alphabet.indices }) { "alias target outside the alphabet" }
    }

    companion object {
        /**
         * Build a [CtcLayout] from parallel key lists (the shape of FUTO's `en_qwerty.json`
         * `keys` array: one `(letter, cx, cy)` per entry, already in emission-column order).
         *
         * @param letters key characters in emission-column order.
         * @param cx per-key center x in [0,1].
         * @param cy per-key center y in [0,1].
         */
        fun of(letters: List<Char>, cx: List<Float>, cy: List<Float>): CtcLayout {
            require(letters.size == cx.size && letters.size == cy.size) {
                "letters/cx/cy length mismatch: ${letters.size}/${cx.size}/${cy.size}"
            }
            return CtcLayout(letters.toCharArray(), cx.toFloatArray(), cy.toFloatArray())
        }
    }
}
