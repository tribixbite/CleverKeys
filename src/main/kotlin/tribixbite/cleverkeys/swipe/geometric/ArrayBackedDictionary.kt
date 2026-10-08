package tribixbite.cleverkeys.swipe.geometric

/**
 * The canonical retained [GeometricDictionary] implementation: a single
 * frequency-ordered `Array<String>` the engine indexes into. Index i IS the ordinal
 * frequency rank r(w) (0 = most frequent) — the invariant the loaders establish and
 * the frequency prior `−λ_f·ln(1 + r(w))` depends on.
 *
 * The engine's template index stores ONLY int indices into this array, so the word
 * strings are shared (not copied) into the index — the structural basis of the NFR-2
 * memory budget (the index retains zero `String` references).
 *
 * @param language BCP-47-ish code (cache key).
 * @param version generation token (cache key); callers bump on any word-set change.
 * @param words words in descending frequency order, deterministic tie-break already
 *   applied by the loader.
 * @param bonuses optional per-ordinal swipe-priority bonus ([GeometricDictionary.swipeBonus]),
 *   aligned with [words]; null (every base dictionary) means 0 everywhere. Any change to it must
 *   come with a new [version], like a word-set change.
 */
class ArrayBackedDictionary(
    override val language: String,
    override val version: Long,
    private val words: Array<String>,
    private val bonuses: FloatArray? = null,
) : GeometricDictionary {

    init {
        require(bonuses == null || bonuses.size == words.size) {
            "bonuses (${bonuses?.size}) must align with words (${words.size})"
        }
    }

    override val size: Int get() = words.size

    override fun word(i: Int): String = words[i]

    override fun swipeBonus(i: Int): Float = bonuses?.get(i) ?: 0f
}
