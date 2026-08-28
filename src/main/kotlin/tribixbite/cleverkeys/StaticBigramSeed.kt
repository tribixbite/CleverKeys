package tribixbite.cleverkeys

import com.google.gson.JsonParser

/**
 * Parsing + ranking core for the SHIPPED static bigram tables
 * (`assets/bigrams/<lang>_bigrams.json`), ARC-010 / ARC-020 (2026-08-28).
 *
 * Pure JVM (no Android imports) so the schema contract and the ranking rule are
 * exercisable in `runPureTests` against the REAL asset files — same split as
 * `CtcEngineAdapter` (Android asset I/O) → `CtcLexiconMerge` (pure policy).
 *
 * ## What the shipped values ARE — and what they are NOT
 *
 * The six assets are JSON objects keyed `"<prev> <next>"` (ONE space, lowercase)
 * with a float value in `[0.75, 0.94]`. Those values are **per-previous-word
 * rank scores, not probabilities**: the 15 continuations of `"i"` in
 * `en_bigrams.json` sum to 12.37, and every group is authored in descending
 * order from ~0.92 down to the 0.75 floor. They are a hand-curated "what
 * usually follows this word" ordering.
 *
 * That makes them exactly right for the ONE thing this class does — ranking the
 * continuations of a single previous word — and WRONG for
 * [BigramModel.getContextualProbability], whose interpolation
 * `λ·P(w|prev) + (1−λ)·P(w)` requires `P(w|prev)` on the same scale as the
 * unigram table (`0.008…0.07`). Dropping a 0.9 rank score into that formula
 * would drive `getContextMultiplier` to its 10× clamp for EVERY listed pair,
 * silently rewriting the live tap ranking. The multiplier path therefore keeps
 * its hardcoded joint-scale table; the assets feed the seed only. See
 * `docs/audit/2026-08-28-archive-verification.md` (ARC-010).
 *
 * ## Merge policy (ARC-010 decision)
 *
 * The assets are the SOURCE OF TRUTH: for `en` they carry 319 pairs against the
 * hardcoded table's 68, and they cover 52 of those 68. The remaining 16 (plus
 * 6 es / 2 fr / 3 de) are kept as GAP FILLERS via [build]'s `fallback`
 * argument — asset wins on conflict, hardcoded fills only what the asset never
 * had, so no curated pair is lost when the assets go live. Gap fillers carry
 * their own (much smaller) scores and therefore sort last inside a group, which
 * is the honest ordering: they are the entries the newer, richer table did not
 * consider worth listing.
 */
object StaticBigramSeed {

    /** Separator in the shipped asset keys: `"the best"`. */
    private const val ASSET_SEPARATOR = ' '

    /** Separator in [BigramModel]'s hardcoded fallback keys: `"the|best"`. */
    private const val TABLE_SEPARATOR = '|'

    /** Asset keys separate on a whitespace RUN, not strictly one space. */
    private val WHITESPACE = Regex("""\s+""")

    /** One ranked continuation of a previous word. */
    data class Continuation(
        /** The continuation word, lowercase. */
        val word: String,
        /**
         * The curated rank score for this pair within its previous word's group.
         * Comparable ONLY against other continuations of the same previous word
         * (see the class doc: these are not probabilities).
         */
        val rank: Float
    )

    /** Immutable previous-word → ranked-continuations lookup. */
    class Index internal constructor(
        private val byPrevWord: Map<String, List<Continuation>>
    ) {
        /** Number of distinct previous words with at least one continuation. */
        val prevWordCount: Int get() = byPrevWord.size

        /** Total pair count across all previous words. */
        val pairCount: Int = byPrevWord.values.sumOf { it.size }

        /**
         * Top continuations of [prevWord], best first.
         *
         * @param maxResults hard cap; `≤ 0` yields an empty list
         */
        fun top(prevWord: String, maxResults: Int): List<Continuation> {
            if (maxResults <= 0) return emptyList()
            val entries = byPrevWord[prevWord.lowercase()] ?: return emptyList()
            return if (entries.size <= maxResults) entries else entries.subList(0, maxResults)
        }

        /** Test/diagnostic helper: does this index hold `prev → next`? */
        fun contains(prevWord: String, nextWord: String): Boolean =
            byPrevWord[prevWord.lowercase()]?.any { it.word == nextWord.lowercase() } == true

        companion object {
            val EMPTY = Index(emptyMap())
        }
    }

    /**
     * Parse a shipped `<lang>_bigrams.json` asset into normalized `"prev next"`
     * → rank entries.
     *
     * Tolerant by construction — this runs on a background thread at IME start
     * and a malformed shipped file must degrade to the hardcoded fallback, not
     * crash the keyboard. Entries are skipped (not fatal) when the key does not
     * hold exactly one separator, either side is blank, or the value is not a
     * finite number in `(0, 1]`. A non-object document throws, which the caller
     * catches.
     *
     * NOTE this is why the never-called `BigramModel.loadFromFile` it replaces
     * could not be used: that parser read whitespace-delimited PLAIN TEXT
     * (`prev next prob` per line) and, run against these JSON files, would have
     * thrown an uncaught `NumberFormatException` on the first entry.
     *
     * @return normalized key → rank; later duplicates of a normalized key win
     */
    fun parseAsset(json: String): Map<String, Float> {
        val root = JsonParser.parseString(json).asJsonObject
        val out = LinkedHashMap<String, Float>(root.size() * 2)
        for ((rawKey, rawValue) in root.entrySet()) {
            val split = splitKey(rawKey) ?: continue
            val rank = try {
                rawValue.asFloat
            } catch (e: RuntimeException) {
                continue // non-numeric value in a shipped file: skip the entry
            }
            if (!rank.isFinite() || rank <= 0f || rank > 1f) continue
            out["${split.first}$ASSET_SEPARATOR${split.second}"] = rank
        }
        return out
    }

    /**
     * Split a bigram key into a lowercase `(prev, next)`, accepting BOTH the
     * asset's whitespace separator and the hardcoded table's pipe separator.
     *
     * Returns null unless the key holds exactly TWO non-blank tokens. That
     * rejects the eight non-bigram entries the shipped files actually contain
     * (`fr`: `"c'est"`, `"il y a"`, `"s'il vous plaît"`; `it`: `"c'è"`, `"nel"`,
     * `"nella"`, `"del"`, `"della"`) — a lone unigram has no previous word to
     * key on, and a three-token phrase does not say which token that is. They
     * are dropped rather than guessed at; the counts are pinned in
     * `StaticBigramSeedTest`.
     *
     * Case is normalized here because `de` and `pt` key their entries with real
     * orthographic capitals (`"guten Tag"`, `"vielen Dank"`) while the lookup
     * always arrives lowercase from the committed-word tracker.
     */
    fun splitKey(key: String): Pair<String, String>? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        val parts = if (trimmed.indexOf(TABLE_SEPARATOR) >= 0) {
            trimmed.split(TABLE_SEPARATOR)
        } else {
            trimmed.split(WHITESPACE)
        }
        if (parts.size != 2) return null
        val prev = parts[0].trim().lowercase()
        val next = parts[1].trim().lowercase()
        if (prev.isEmpty() || next.isEmpty()) return null
        return prev to next
    }

    /**
     * Build the lookup index from a primary table plus an optional gap-filling
     * fallback (see the class doc's merge policy).
     *
     * Within a previous word, continuations are ordered by rank descending with
     * the word itself as the tie-break, so the ordering is fully deterministic
     * regardless of map iteration order.
     *
     * @param primary the shipped asset entries (wins on conflict); may be empty,
     *   which is the PRE-LOAD state — the index then reflects [fallback] alone
     * @param fallback [BigramModel]'s hardcoded pairs for the same language
     */
    fun build(
        primary: Map<String, Float>,
        fallback: Map<String, Float> = emptyMap()
    ): Index {
        if (primary.isEmpty() && fallback.isEmpty()) return Index.EMPTY

        // fallback first, primary second → primary overwrites on conflict.
        val merged = LinkedHashMap<String, Float>((primary.size + fallback.size) * 2)
        for ((key, rank) in fallback) {
            val split = splitKey(key) ?: continue
            merged["${split.first}$ASSET_SEPARATOR${split.second}"] = rank
        }
        for ((key, rank) in primary) {
            val split = splitKey(key) ?: continue
            merged["${split.first}$ASSET_SEPARATOR${split.second}"] = rank
        }

        val grouped = HashMap<String, MutableList<Continuation>>()
        for ((key, rank) in merged) {
            val split = splitKey(key) ?: continue
            grouped.getOrPut(split.first) { ArrayList(4) }
                .add(Continuation(split.second, rank))
        }
        val ordered = HashMap<String, List<Continuation>>(grouped.size * 2)
        for ((prev, continuations) in grouped) {
            continuations.sortWith(
                compareByDescending<Continuation> { it.rank }.thenBy { it.word }
            )
            ordered[prev] = continuations
        }
        return Index(ordered)
    }
}
