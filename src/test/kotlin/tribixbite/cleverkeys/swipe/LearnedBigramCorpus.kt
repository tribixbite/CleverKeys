package tribixbite.cleverkeys.swipe

import org.json.JSONArray
import org.json.JSONObject
import tribixbite.cleverkeys.contextaware.BigramStore
import tribixbite.cleverkeys.contextaware.ContextModel
import tribixbite.cleverkeys.contextaware.TrigramStore
import tribixbite.cleverkeys.persist.InMemoryLearnedStorage
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor

/**
 * Stage A of the step-5 harness: load real learned bigrams into a throwaway [ContextModel].
 *
 * See `docs/plans/2026-08-22-context-rescoring-step5-harness.md`.
 *
 * ## Where the data comes from
 *
 * A dictionary export produced by the app itself — Settings → Backup & Restore → export
 * dictionaries — which writes `learned_bigrams_by_language` as
 * `{lang: [{word1, word2, frequency, probability}, …]}`. Those are the same four fields
 * [SwipeContextRescorer.Evidence] consumes, so no lossy adaptation is needed.
 *
 * **The path is always supplied by the caller and the file is NEVER committed.** It is a record
 * of one person's typing; this repo is public. A harness that reads a checked-in fixture of real
 * learned pairs would be publishing personal data by accident.
 *
 * ## The usable fraction, and why it is reported rather than hidden
 *
 * The stores ignore hapax legomena: [BigramStore.DEFAULT_MIN_FREQUENCY] is 2, so a bigram seen
 * once contributes **exactly zero** boost. Measured on the maintainer's 2026-08-21 export, that
 * is not a rounding detail — **5,947 of 6,589 English pairs (90.3%) are frequency 1**, leaving
 * ~642 that can influence anything.
 *
 * So [Loaded.usable] is part of the result, not a debug aside. A harness that seeds 6,589 pairs
 * and reports "6,589 loaded" overstates its own statistical power by an order of magnitude, and
 * any conclusion drawn from it inherits that error.
 */
object LearnedBigramCorpus {

    /** One `{word1, word2, frequency, probability}` row of an export. */
    data class Pair(
        val word1: String,
        val word2: String,
        val frequency: Int,
        val probability: Float,
    ) {
        /** Can this pair influence ranking at all? Below the store floor it cannot. */
        val usable: Boolean get() = frequency >= BigramStore.DEFAULT_MIN_FREQUENCY
    }

    /** What a load produced, including the honesty numbers. */
    data class Loaded(
        val model: ContextModel,
        val language: String,
        /** Every pair in the export for this language. */
        val total: Int,
        /** Those clearing the store's min-frequency floor — the ones that can do anything. */
        val usable: Int,
        /** Those additionally clearing the stricter rank-1 promotion floors. */
        val promotable: Int,
        /**
         * How many bigrams the store ACTUALLY HOLDS after seeding.
         *
         * `BigramStore` caps a language at `MAX_TOTAL_BIGRAMS` (10,000) and prunes by keeping the
         * highest-probability entries. Seeding a corpus larger than that silently discards most of
         * it, so a harness that samples from the CORPUS rather than from what SURVIVED measures
         * mostly pairs the store no longer knows. Reported so that can never go unnoticed again.
         */
        val stored: Int,
    ) {
        val usableFraction: Double get() = if (total == 0) 0.0 else usable.toDouble() / total

        override fun toString(): String =
            ("lang=%s total=%d usable=%d (%.1f%%) promotable=%d STORED=%d" +
                if (stored < usable) "  <-- store cap discarded %d".format(usable - stored) else "")
                .format(language, total, usable, usableFraction * 100, promotable, stored,
                    maxOf(0, usable - stored))
    }

    /**
     * Parse `learned_bigrams_by_language` for one language out of an export file.
     *
     * Returns an empty list rather than throwing when the language is absent — an export from a
     * device that never typed French legitimately has no `fr` key, and that is a fact to report,
     * not a crash.
     */
    fun parse(exportFile: File, language: String): List<Pair> {
        require(exportFile.isFile) {
            "no export at ${exportFile.path} — produce one via Settings → Backup & Restore → " +
                "export dictionaries. It is never committed; pass its path explicitly."
        }
        val root = JSONObject(exportFile.readText())
        val byLanguage = root.optJSONObject("learned_bigrams_by_language")
            ?: return emptyList()
        val arr: JSONArray = byLanguage.optJSONArray(language) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Pair(
                word1 = o.getString("word1"),
                word2 = o.getString("word2"),
                frequency = o.getInt("frequency"),
                probability = o.getDouble("probability").toFloat(),
            )
        }
    }

    /**
     * Seed a fresh in-memory [ContextModel] from [pairs].
     *
     * **Replays `recordBigram` `frequency` times rather than writing counts directly.** That is
     * deliberate and it costs a little speed: going through the real learn path means the store's
     * own floors, caps and probability recomputation apply exactly as they do on a device. Writing
     * the numbers in directly would let the harness measure a store state the app can never
     * actually reach — a harness that flatters the feature by construction.
     *
     * The caller owns [scheduler] and must shut it down; the storage is in-memory, so nothing
     * touches the device's real learned data.
     */
    fun seed(
        pairs: List<Pair>,
        language: String,
        scheduler: ScheduledThreadPoolExecutor,
    ): Loaded {
        val bigramStore = BigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val trigramStore = TrigramStore(InMemoryLearnedStorage(), 60_000, 120_000, scheduler)
        val model = ContextModel(bigramStore, trigramStore, language)

        for (p in pairs) {
            repeat(p.frequency) { bigramStore.recordBigram(language, p.word1, p.word2) }
        }

        return Loaded(
            model = model,
            language = language,
            total = pairs.size,
            usable = pairs.count { it.usable },
            promotable = pairs.count {
                it.usable && it.probability >= tribixbite.cleverkeys.NextWordPredictor.MIN_LEARNED_PROBABILITY
            },
            stored = bigramStore.getTotalBigramCount(language),
        )
    }

    /**
     * The trigram store is deliberately left EMPTY by [seed], and callers must know why.
     *
     * Trigrams are excluded from the app's export on purpose — they re-learn quickly, so shipping
     * them in a backup buys little. A device-seeded replay therefore exercises the **bigram
     * backoff path only**, never the sharper trigram branch that `getContextEvidence` prefers when
     * two words of context exist.
     *
     * Consequence for the results: any measured gain is a **floor, not a ceiling**. Say so when
     * reporting, or the number will be read as the feature's full potential.
     */
    const val TRIGRAMS_ABSENT_FROM_EXPORT = true
}

/**
 * The local English trace corpus is HETEROGENEOUS, and half of it is unusable as raw traces.
 *
 * Measured 2026-08-23 over `combined_english_swipes.jsonl.gz` (8,607 rows):
 *
 *  - 4,543 rows are raw traces — variable length (50-380 points), third column a millisecond
 *    timestamp, monotonically non-decreasing.
 *  - 4,064 rows are exactly 128 points with a third column in ~0..40 that is **not monotonic**
 *    and therefore not a timestamp. They look pre-resampled by some other pipeline.
 *
 * Feeding the second kind to a decoder produces garbage: the CTC smoke test decoded `boolean` as
 * "gh" and `ensure` as "we" — short nonsense with a confident-looking score. They do not fail
 * loudly; they land in `unchanged` and quietly inflate a replay's denominator.
 *
 * Any replay reading this corpus must filter on [hasUsableTimestamps].
 */
object TraceCorpusQuality {

    /** True when the third column is a plausible monotonic timestamp series. */
    fun hasUsableTimestamps(t: DoubleArray): Boolean {
        if (t.size < 3) return false
        for (i in 1 until t.size) if (t[i] < t[i - 1]) return false
        // A monotonic run that never advances is equally unusable.
        return t.last() > t.first()
    }
}
