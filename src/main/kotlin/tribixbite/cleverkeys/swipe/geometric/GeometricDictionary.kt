package tribixbite.cleverkeys.swipe.geometric

/**
 * A retained, indexed word store the engine decodes against.
 *
 * **Index i IS the ordinal frequency rank r(w)**: implementations MUST emit words
 * in descending frequency order, ties broken deterministically by source order
 * (see [CkdtDictionaryReader] / [FlatJsonDictionaryLoader] in Phase 2). This is
 * what makes the frequency prior `−λ_f·ln(1 + r(w))` well-defined and
 * source-independent (the repo's byte-scores / uint8 ranks have no usable `ln f`;
 * only an ordering is needed).
 *
 * The engine's template index stores ONLY int indices referencing this store —
 * "word strings are shared, not copied" is structurally true, which is why the
 * index retains zero `String` references (NFR-2 memory math).
 *
 * [version] is a generation token: any mutation that changes the word set (a
 * custom word added, a word disabled) MUST bump it so a stale template index is
 * rebuilt on cache miss. Propagating live `DictionaryManager` changes into
 * [version] is an explicit WP9 adapter responsibility (out of scope here).
 */
interface GeometricDictionary {
    /** BCP-47-ish language code (e.g. "en", "ru"); part of the cache key. */
    val language: String

    /** Generation token; bumps on any word-set mutation (part of the cache key). */
    val version: Long

    /** Number of words, == the exclusive upper bound of valid indices. */
    val size: Int

    /**
     * The canonical (accent-preserving) word at ordinal rank [i] ∈ [0, size).
     * @throws IndexOutOfBoundsException if [i] is out of range.
     */
    fun word(i: Int): String
}
