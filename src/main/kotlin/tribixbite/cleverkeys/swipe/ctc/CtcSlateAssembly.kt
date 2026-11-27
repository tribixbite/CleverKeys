package tribixbite.cleverkeys.swipe.ctc

import tribixbite.cleverkeys.UserWordFrequency
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * What the slate assembly needs to know about ONE lexicon's personal dictionary. Built once per
 * trie build by `CtcEngineAdapter.lexiconFor` from the same inputs [CtcLexiconMerge.merge]
 * consumed, so the two can never disagree about which words were the user's.
 *
 * @property excluded lowercase words this lexicon must never show: the DISABLED words the user
 *   did not add back as a user word — exactly the set [CtcLexiconMerge.merge] drops from the base
 *   (custom overrides disabled). The trie no longer holds them as words, but other steps can put
 *   one back (alias-key injection re-inserts a disabled contraction key such as `world` at the
 *   floor), so the finished slate is filtered against this set too ([CtcSlateAssembly]).
 * @property storedFrequencies lowercase user spelling → the STORED 1..255 frequency (the larger one
 *   for a duplicate), for every custom and platform user word of this language.
 * @property calibrationFloor the base floor this lexicon maps a stored frequency onto
 *   ([CtcLexiconMerge.baseFloor]: 134 for the en JSON scale, 1 for CKDT sources).
 * @property joinerSurfaces lowercase JOINER user word (`l'une`, `co-op`, `o'brien`) → the lowercase
 *   decoded word the contraction overlay shows it for (`lune`, `coop`, `obrien`): the adapter's
 *   `UserJoinerPreference` table read backwards. No engine spells a joiner, so such a word reaches
 *   the slate only through the overlay and has no beam candidate under its own spelling; this is
 *   how [CtcSlateAssembly] finds the candidate whose path evidence it was shown for.
 */
class CtcUserWords(
    val excluded: Set<String>,
    val storedFrequencies: Map<String, Int>,
    val calibrationFloor: Double,
    val joinerSurfaces: Map<String, String> = emptyMap(),
) {
    companion object {
        /** No user words and nothing disabled: the slate passes through unchanged. */
        val NONE = CtcUserWords(emptySet(), emptyMap(), CtcLexiconMerge.MIN_FREQ)

        /**
         * Build from the merge's own inputs: [userWords] (custom + platform, stored 1..255, the
         * `userWordPairs` the adapter passes to [CtcLexiconMerge.merge]), [disabled] (the set it
         * passes, already Swiss-spelling folded) and the base floor of the same merge.
         * [preferredForms] is the overlay's preference table (lowercase decoded word → the joiner
         * user word shown for it), stored backwards as [joinerSurfaces].
         */
        fun of(
            userWords: Iterable<Pair<String, Int>>,
            disabled: Set<String>,
            calibrationFloor: Double,
            preferredForms: Map<String, String> = emptyMap(),
        ): CtcUserWords {
            val frequencies = HashMap<String, Int>()
            for ((word, stored) in userWords) {
                if (word.isBlank()) continue // the merge skips these too
                val lower = word.lowercase(Locale.ROOT)
                val previous = frequencies[lower]
                if (previous == null || stored > previous) frequencies[lower] = stored
            }
            val excluded = if (disabled.isEmpty()) {
                emptySet()
            } else {
                disabled.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }.apply { removeAll(frequencies.keys) }
            }
            val joinerSurfaces = if (preferredForms.isEmpty()) {
                emptyMap()
            } else {
                HashMap<String, String>(preferredForms.size * 2).apply {
                    for ((decoded, form) in preferredForms) {
                        putIfAbsent(form.lowercase(Locale.ROOT), decoded.lowercase(Locale.ROOT))
                    }
                }
            }
            return CtcUserWords(excluded, frequencies, calibrationFloor, joinerSurfaces)
        }
    }
}

/**
 * One language's decoded swipe, as `CtcEngineAdapter.decodeAsync` hands it to [CtcSlateAssembly].
 *
 * @property candidates the beam's candidates after [CtcSurfaceForms.expand] (display words, best
 *   first) — the list the display scores were normalised over.
 * @property words the display slate (after the fuzzy rescue and the contraction overlay).
 * @property scores parallel 0..1000 engine-relative scores ([CtcSlateAssembly.softmaxPermille]).
 * @property params the preset this lexicon was decoded at.
 */
class CtcDecodedSlate(
    val language: String,
    val candidates: List<CtcCandidate>,
    val words: List<String>,
    val scores: List<Int>,
    val params: CtcScoringParams,
    val userWords: CtcUserWords,
)

/**
 * Turns one or two per-language decodes into the slate the shared pipeline receives. Pure JVM.
 *
 * Two rules, both from the Note10+ release r20 run (2026-11-26):
 *
 *  1. **No slate offers a disabled word (F3).** [CtcLexiconMerge.merge] drops a disabled word, but
 *     [CtcContractionKeys.inject] then inserts every contraction alias key the trie lacks — and a
 *     disabled PAIRING key is exactly such a key (`world` → `world's`, `you` → `you're`), so it came
 *     back at the floor and the bar offered it among the alternates. Every display slate is
 *     filtered against its lexicon's [CtcUserWords.excluded]; a SECONDARY slate also against the
 *     primary's, which is the tap path's rule (`WordPredictor.isSecondaryWordDisabled`: a
 *     secondary candidate is hidden when the primary language disabled it or its own did). The
 *     match is exact (lowercase): `world's` is a pairing entry of its own and stays, as it does on
 *     tap. The key is still injected, so a disabled `well` keeps `we'll` reachable.
 *  2. **A user word ranks the same whichever list holds it (F1).** [CtcRankMerger] caps a
 *     secondary word at 920 against the primary's 1000, so `parcero` in the Spanish list lost every
 *     swipe to the English `parcel`, while the same word in the English list won. A secondary
 *     display word that is one of its list's user words now earns the rank the PRIMARY list would
 *     give it: [primaryEquivalentScore] (the candidate's own emission evidence under the primary's
 *     preset and frequency scale, plus its swipe-priority bonus) ranked against the primary's beam
 *     words by their exact final scores ([rankInPrimarySlate]). It goes ahead of the primary word at
 *     that rank, and never below its plain secondary slot. Ordinary secondary words keep the rank
 *     merge exactly (ARC-018).
 */
object CtcSlateAssembly {

    /** Within-slate softmax over [finalScores] × 1000, rounded — the engine-relative bar scale. */
    fun softmaxPermille(finalScores: List<Double>): List<Int> {
        if (finalScores.isEmpty()) return emptyList()
        val max = finalScores.max()
        val exps = finalScores.map { exp(it - max) }
        val sum = exps.sum()
        return exps.map { ((it / sum) * 1000.0).roundToInt().coerceIn(0, 1000) }
    }

    /**
     * The final score [primary] would give the secondary-lexicon candidate [candidate] had its user
     * word (stored frequency [storedFrequency]) been in the primary list: [CtcBeamDecoder]'s
     * formula over the candidate's own emission evidence (both decodes read one emission matrix),
     * the primary's γ/β/λ, and the stored frequency calibrated onto the primary base scale
     * ([primaryCalibrationFloor]) as [CtcLexiconMerge.merge] would have filed it. The learned and
     * swipe-priority bonuses are added unchanged.
     */
    fun primaryEquivalentScore(
        candidate: CtcCandidate,
        storedFrequency: Int,
        primary: CtcScoringParams,
        primaryCalibrationFloor: Double,
    ): Double {
        val frequency = UserWordFrequency.scaleOnto(storedFrequency, primaryCalibrationFloor, CtcLexiconMerge.MAX_FREQ)
        val l = if (candidate.length > 1) candidate.length else 1
        return candidate.ctcScore / Math.pow(l.toDouble(), primary.gamma) +
            primary.beta * candidate.length + primary.lambda * ln(frequency + 1e-10) +
            candidate.learnedBonus + candidate.priorityBonus
    }

    /** The single-language slate: [slate]'s display words and scores less its excluded words. */
    fun single(slate: CtcDecodedSlate): Pair<List<String>, List<Int>> =
        withoutExcluded(slate.words, slate.scores, slate.userWords.excluded, emptySet())

    /**
     * The dual-language slate, at most [limit] words: both slates filtered (rule 1), then merged by
     * rank with each secondary user word at the rank it earns in the primary slate (rule 2).
     */
    fun dual(primary: CtcDecodedSlate, secondary: CtcDecodedSlate, limit: Int): List<CtcRankMerger.Item> {
        val (primaryWords, primaryScores) =
            withoutExcluded(primary.words, primary.scores, primary.userWords.excluded, emptySet())
        val (secondaryWords, _) = withoutExcluded(
            secondary.words, secondary.scores, secondary.userWords.excluded, primary.userWords.excluded,
        )
        val earned = earnedPrimaryRanks(primary, primaryWords, primaryScores, secondary, secondaryWords)
        return CtcRankMerger.merge(
            primary.language, primaryWords, secondary.language, secondaryWords, limit, earned,
        )
    }

    /**
     * [words]/[scores] without every word whose lowercase form is in [excluded] or [alsoExcluded].
     * Removing entries keeps the scores non-increasing, which the pipeline's rescorer relies on.
     */
    internal fun withoutExcluded(
        words: List<String>,
        scores: List<Int>,
        excluded: Set<String>,
        alsoExcluded: Set<String>,
    ): Pair<List<String>, List<Int>> {
        if (excluded.isEmpty() && alsoExcluded.isEmpty()) return words to scores
        fun isExcluded(word: String): Boolean {
            val lower = word.lowercase(Locale.ROOT)
            return lower in excluded || lower in alsoExcluded
        }
        if (words.none(::isExcluded)) return words to scores
        val keptWords = ArrayList<String>(words.size)
        val keptScores = ArrayList<Int>(words.size)
        for ((i, word) in words.withIndex()) {
            if (isExcluded(word)) continue
            keptWords.add(word)
            keptScores.add(scores.getOrElse(i) { 0 })
        }
        return keptWords to keptScores
    }

    /**
     * Secondary slate index → the rank its word earns in the primary slate, for every word of
     * [secondaryWords] (the filtered secondary slate) that is a user word of the secondary list and
     * one of its decoded [CtcDecodedSlate.candidates], directly or, for a joiner word, through
     * [CtcUserWords.joinerSurfaces] (a fuzzy-rescue entry has no emission evidence of its own and
     * keeps its plain slot). [primaryWords]/[primaryScores] are the filtered
     * primary slate.
     */
    private fun earnedPrimaryRanks(
        primary: CtcDecodedSlate,
        primaryWords: List<String>,
        primaryScores: List<Int>,
        secondary: CtcDecodedSlate,
        secondaryWords: List<String>,
    ): Map<Int, Int> {
        val stored = secondary.userWords.storedFrequencies
        if (stored.isEmpty()) return emptyMap()
        var byWord: HashMap<String, CtcCandidate>? = null
        var earned: HashMap<Int, Int>? = null
        for ((index, word) in secondaryWords.withIndex()) {
            val lower = word.lowercase(Locale.ROOT)
            val frequency = stored[lower] ?: continue
            // Built only when the slate holds a user word at all; the best candidate per word wins.
            val candidates = byWord ?: HashMap<String, CtcCandidate>(secondary.candidates.size * 2).also { map ->
                for (c in secondary.candidates) map.putIfAbsent(c.word.lowercase(Locale.ROOT), c)
                byWord = map
            }
            // A joiner user word (`l'une`, `O'Brien`) is shown through the overlay for a decoded
            // word spelled without the joiner; its path evidence is that candidate's (audit C6).
            val candidate = candidates[lower]
                ?: secondary.userWords.joinerSurfaces[lower]?.let { candidates[it] }
                ?: continue
            val score = primaryEquivalentScore(candidate, frequency, primary.params, primary.userWords.calibrationFloor)
            val ranks = earned ?: HashMap<Int, Int>().also { earned = it }
            ranks[index] = rankInPrimarySlate(score, primary.candidates, primaryWords, primaryScores)
        }
        return earned ?: emptyMap()
    }

    /**
     * How many entries of the primary display slate ([primaryWords], parallel [primaryScores]) rank
     * above a word with final score [finalScore] — the rank the primary list would give it.
     *
     * A beam word of the primary ([primaryCandidates], matched by its display spelling) is compared
     * on its exact final score, never on its bar score: the bar score is a ROUNDED permille, so an
     * alternate below 0.5 permille shows 0 and read as "not above" any word, which put a custom
     * word with near-zero path evidence second on the bar (review of 6e474b0df). The final scores
     * are also immune to the softmax underflowing for a far-behind word. An entry that is not a
     * beam word (a contraction variant, a fuzzy-rescue fill) has only its bar score, which is
     * compared with [finalScore] on that slate's own unrounded scale ([softmaxPermille] over
     * [primaryCandidates]). An empty primary beam has nothing to rank against: 0, since the primary
     * list would have put the word in its beam, above any rescue fill.
     */
    internal fun rankInPrimarySlate(
        finalScore: Double,
        primaryCandidates: List<CtcCandidate>,
        primaryWords: List<String>,
        primaryScores: List<Int>,
    ): Int {
        if (primaryCandidates.isEmpty()) return 0
        val beamScores = HashMap<String, Double>(primaryCandidates.size * 2)
        for (c in primaryCandidates) beamScores.putIfAbsent(c.word.lowercase(Locale.ROOT), c.finalScore)
        val max = primaryCandidates.maxOf { it.finalScore }
        var sum = 0.0
        for (c in primaryCandidates) sum += exp(c.finalScore - max)
        val onSlateScale = exp(finalScore - max) / sum * 1000.0 // +Infinity sorts above every entry
        var above = 0
        for ((i, word) in primaryWords.withIndex()) {
            val beamScore = beamScores[word.lowercase(Locale.ROOT)]
            val isAbove = if (beamScore != null) {
                beamScore > finalScore
            } else {
                primaryScores.getOrElse(i) { 0 } > onSlateScale
            }
            if (isAbove) above++
        }
        return above
    }
}
