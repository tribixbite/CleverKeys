package tribixbite.cleverkeys.autocorrect

import android.os.Trace
import android.util.Log
import android.util.Xml
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.kxml2.io.KXmlParser
import tribixbite.cleverkeys.BinaryDictionaryLoader
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.ShippedTapDictionary
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * On-demand MEASUREMENT (not a regression test) for
 * `docs/eval/2026-10-18-channel-autocorrect-ranking.md`: rule C (noisy-channel ranking) and
 * guard G against the shipped band ranking.
 *
 * Not a `*Test` class: never runs in CI (the corpora are local-only). Run:
 *
 * ```
 * CK_CH_PHASE=dev GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m scripts/gradle-guard.sh \
 *     runMockTests -PtestClass=autocorrect.ChannelRankingEval
 * CK_CH_PHASE=test CK_CH_CONFIG=<s1,g,g2,tau,lambda,gamma> GRADLE_GUARD_XMX=1536m \
 *     GRADLE_GUARD_METASPACE=384m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.ChannelRankingEval
 * ```
 *
 * `gamma` may be `inf`. Inputs: `$CK_MISSPELL_DIR` (default `~/.cache/cleverkeys-test/misspell`,
 * the same files as the NB and DD-M evals); rows/summary go to `$CK_EVAL_OUT` (default
 * `build/eval/channel-ranking`). The DEV phase never computes a TEST outcome.
 *
 * **How the grid is evaluated.** Each DEV input runs ONCE through production `autoCorrect` in
 * channel mode with a [WordPredictor.ChannelProbe] attached, which captures the gated candidate
 * list, the guard-G stem, the frequency floor and the dictionary maximum. Each candidate's
 * optimal alignment is extracted with `ChannelEditCost.alignmentFeatures` at the 16 corners and
 * the centre of the edit-weight grid; a weight vector prices a candidate as the minimum of
 * `ChannelEditCost.priced` over those alignments (the DP's optimum is the minimum over all
 * alignments, so this is exact whenever the optimum under that vector is one of the stored
 * ones). The decision itself is `ChannelRanker.pick` plus the guard/floor/finalization rules of
 * `WordPredictor.channelDecide`, replicated in [decide]. The pre-registered soundness check
 * compares [decide] with full production `autoCorrect` on 2,000 seeded inputs at the chosen
 * configuration.
 */
class ChannelRankingEval {

    /** One misspelling → intended-word pair (both lowercase); [occ] = distinct commits (GTC). */
    private data class P(val typed: String, val target: String, val occ: Int = 1)

    /** What the channel path saw for one input; [fixed] is set when it returned before the sweep. */
    private class Probed(
        val typed: String,
        val fixed: String?,
        val candidates: List<ChannelCandidate>,
        /** Per candidate: k stored alignments × [ChannelEditCost.FEATURE_COUNT] floats. */
        val features: Array<FloatArray>,
        val guardStemRank: Float?,
        val floor: Int,
        val maxFrequency: Int,
    )

    /** One rule-C configuration (eval doc §1.1; β from round 2, §3.1). */
    private data class Cfg(
        val s1: Float, val g: Float, val g2: Float, val tau: Float, val lambda: Float,
        val beta: Float, val gamma: Float,
        /** δ, the short-word deletion cost (round 3, §7.1); 0 = rule C2. */
        val delta: Float = 0f,
        /** κ, the maximum correction cost (round 5, §13.1); ∞ = off. */
        val kappa: Float = Float.POSITIVE_INFINITY,
    ) {
        // ρ off: rounds 1–5 measured, and their replica models, the stack before round 6.
        val weights get() = ChannelWeights(s1, g, g2, tau, lambda, aliasBonus = beta,
            shortWordDeletionCost = delta, maxCorrectionCost = kappa, inflectionCost = gamma,
            completionCost = Float.POSITIVE_INFINITY)
        override fun toString() = "s1=$s1 g=$g g2=$g2 tau=$tau lambda=$lambda beta=$beta gamma=" +
            (if (gamma == Float.POSITIVE_INFINITY) "inf" else "$gamma") + " delta=$delta kappa=$kappa"
        /** The `CK_CH_CONFIG` spelling: `s1,g,g2,tau,lambda,beta,gamma[,delta]`. */
        val spec get() = "$s1,$g,$g2,$tau,$lambda,$beta," +
            (if (gamma == Float.POSITIVE_INFINITY) "inf" else "$gamma") + ",$delta"

        companion object {
            /** Parses [spec] (7 or 8 fields; `gamma` may be `inf`; a missing δ is 0). */
            fun parse(spec: String): Cfg {
                val v = spec.split(',')
                require(v.size == 7 || v.size == 8) { "config needs s1,g,g2,tau,lambda,beta,gamma[,delta]: $spec" }
                return Cfg(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(),
                    v[5].toFloat(), if (v[6] == "inf") Float.POSITIVE_INFINITY else v[6].toFloat(),
                    if (v.size == 8) v[7].toFloat() else 0f)
            }

            /**
             * The round-3 baseline: shipped weights with δ = 0 (rule C2). Rounds 3–4 measured
             * against this; it is no longer the shipped default.
             */
            val SHIPPED: Cfg get() = AutocorrectTuning.DEFAULT.channel.let {
                Cfg(it.distanceCost, it.indelCost, it.doubleCost, it.transpositionCost, it.frequencyWeight,
                    it.aliasBonus, it.inflectionCost, 0f)
            }

            /** The shipped default at round 5 (rule C2 + Hebrew-scoped δ, κ off): its baseline. */
            val SHIPPED5: Cfg get() = AutocorrectTuning.DEFAULT.channel.let {
                Cfg(it.distanceCost, it.indelCost, it.doubleCost, it.transpositionCost, it.frequencyWeight,
                    it.aliasBonus, it.inflectionCost, it.shortWordDeletionCost, it.maxCorrectionCost)
            }
        }
    }

    /** One pre-registered grid: round 1 (§1.4, β = 0, γ staged) or round 2 (§3.2, joint). */
    private class Grid(
        val s1: FloatArray, val g: FloatArray, val g2: FloatArray, val tau: FloatArray,
        val lambda: FloatArray, val beta: FloatArray, val gamma: FloatArray,
        val delta: FloatArray = floatArrayOf(0f),
        val kappa: FloatArray = floatArrayOf(Float.POSITIVE_INFINITY),
    )

    /** Outcome tallies in raw pairs, distinct target words and commit occurrences. */
    private class Tally {
        val fixes = mutableListOf<P>()
        val breaks = mutableListOf<P>()
        val swaps = mutableListOf<P>()
        var baseOk = 0
        var candOk = 0
        fun d(list: List<P>) = list.mapTo(HashSet()) { it.target }.size
        fun occ(list: List<P>) = list.sumOf { it.occ }
        val netDistinct get() = d(fixes) - d(breaks)
        fun line(name: String, total: Int, targets: Int): String =
            "%-10s pairs=%d targets=%d accuracy %d→%d fixes=%d (%d; occ %d) breaks=%d (%d; occ %d) swaps=%d (%d) net=%d"
                .format(name, total, targets, baseOk, candOk, fixes.size, d(fixes), occ(fixes),
                    breaks.size, d(breaks), occ(breaks), swaps.size, d(swaps), netDistinct)
    }

    /** A predictor over one dictionary. */
    private class Lang(val predictor: WordPredictor, val dict: Map<String, Int>, val aliases: Map<String, String>) {
        private val baseCache = ConcurrentHashMap<String, String>()
        fun base(t: String): String = baseCache.getOrPut(t) { predictor.autoCorrect(t, BAND) }
        fun ok(out: String, target: String) = out.equals(target, true) || out == aliases[target]

        /** Production channel run with the probe attached (γ finite so guard G defers). */
        fun probe(t: String): Probed {
            var seen: WordPredictor.ChannelProbe? = null
            val out = predictor.autoCorrect(t, PROBE_TUNING) { seen = it }
            val p = seen ?: return Probed(t, out, emptyList(), emptyArray(), null, 0, 0)
            val kept = contenders(p.lowerTypedWord, p.candidates, p.maxFrequency)
            val feats = Array(kept.size) { k -> alignments(p.lowerTypedWord, kept[k].word) }
            val stemRank = p.guardStem?.let { ChannelRanker.rankSteps(dict[it] ?: 0, p.maxFrequency) }
            return Probed(t, null, kept, feats, stemRank, p.frequencyFloor, p.maxFrequency)
        }
    }

    companion object {
        private lateinit var en: Lang
        /** The shipped English tap dictionary [en] was built from, for extra predictors (prefix phase). */
        private lateinit var enLoaded: ShippedTapDictionary.Loaded
        private lateinit var he: Lang
        private lateinit var heGrid: Map<Char, Pair<Float, Float>>

        private val corpusDir = File(
            System.getenv("CK_MISSPELL_DIR")
                ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/misspell"
        )
        private val outDir = File(System.getenv("CK_EVAL_OUT") ?: "build/eval/channel-ranking")

        /** The shipped baseline: band ranking with every default. */
        val BAND = AutocorrectTuning.DEFAULT.copy(ranking = AutocorrectRanking.BAND)

        /** Probe tuning: channel, finite γ (so guard G defers and reports its stem). */
        val PROBE_TUNING = AutocorrectTuning.DEFAULT.copy(
            ranking = AutocorrectRanking.CHANNEL,
            channel = AutocorrectTuning.DEFAULT.channel.copy(inflectionCost = 1f, shortWordDeletionCost = 0f,
                completionCost = Float.POSITIVE_INFINITY),
        )

        /** The shipped default before round 6 (ρ off): round 6's baseline (§16.2) and the stack rounds 1–5 measured. */
        val BASE6 = AutocorrectTuning.DEFAULT.copy(
            channel = AutocorrectTuning.DEFAULT.channel.copy(completionCost = Float.POSITIVE_INFINITY))

        /** Round 1 grid (doc §1.4): γ is chosen in a second stage, β is always 0. */
        private val ROUND1 = Grid(
            s1 = floatArrayOf(0f, 0.5f, 1f, 2f, 4f), g = floatArrayOf(0.8f, 1.0f, 1.25f, 1.5f),
            g2 = floatArrayOf(0.3f, 0.6f, 1.0f), tau = floatArrayOf(0.6f, 0.9f, 1.2f),
            lambda = floatArrayOf(0.005f, 0.0075f, 0.01f, 0.015f, 0.02f, 0.03f, 0.04f, 0.06f),
            beta = floatArrayOf(0f), gamma = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f),
        )

        /** Round 2 grid (doc §3.2), fully joint, γ including ∞. */
        private val ROUND2 = Grid(
            s1 = floatArrayOf(0.5f, 1f, 2f, 4f), g = floatArrayOf(1.25f, 1.5f, 2.0f),
            g2 = floatArrayOf(0.3f, 0.45f, 0.6f, 1.0f), tau = floatArrayOf(0.9f, 1.2f, 1.5f),
            lambda = floatArrayOf(0.0025f, 0.005f, 0.0075f, 0.01f, 0.015f),
            beta = floatArrayOf(0f, 0.25f, 0.5f),
            gamma = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, Float.POSITIVE_INFINITY),
        )

        /** Round 3 (§7.1): every weight at its shipped value, only δ varies (0 = the baseline). */
        private val ROUND3: Grid = Cfg.SHIPPED.let { c ->
            Grid(floatArrayOf(c.s1), floatArrayOf(c.g), floatArrayOf(c.g2), floatArrayOf(c.tau),
                floatArrayOf(c.lambda), floatArrayOf(c.beta), floatArrayOf(c.gamma),
                delta = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f))
        }

        /** `CK_CH_ROUND` (default 2) selects the grid the DEV phase searches and the pre-filter covers. */
        private val ROUND: Int = System.getenv("CK_CH_ROUND")?.toInt() ?: 2
        /** Round 5 (§13.1): every weight shipped (incl. Hebrew δ), only κ varies. */
        private val ROUND5: Grid = Cfg.SHIPPED5.let { c ->
            Grid(floatArrayOf(c.s1), floatArrayOf(c.g), floatArrayOf(c.g2), floatArrayOf(c.tau),
                floatArrayOf(c.lambda), floatArrayOf(c.beta), floatArrayOf(c.gamma),
                delta = floatArrayOf(c.delta),
                kappa = floatArrayOf(1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 3.0f, 3.5f, 4.0f))
        }
        // Round 6 replays the shipped default exactly (no edit-weight grid): its pre-filter grid is round 5's.
        private val GRID: Grid = when (ROUND) { 1 -> ROUND1; 3 -> ROUND3; 5, 6 -> ROUND5; else -> ROUND2 }
        private val LAMBDA get() = GRID.lambda

        /** The 16 corners of the edit-weight grid plus its centre: the alignment references. */
        private val REFERENCE_WEIGHTS: List<ChannelWeights> = buildList {
            for (s1 in floatArrayOf(GRID.s1.first(), GRID.s1.last())) for (g in floatArrayOf(GRID.g.first(), GRID.g.last()))
                for (g2 in floatArrayOf(GRID.g2.first(), GRID.g2.last())) for (tau in floatArrayOf(GRID.tau.first(), GRID.tau.last()))
                    add(ChannelWeights(s1, g, g2, tau))
            add(ChannelWeights(GRID.s1[GRID.s1.size / 2], GRID.g[GRID.g.size / 2], GRID.g2[GRID.g2.size / 2], GRID.tau[GRID.tau.size / 2]))
        }

        /** The grid's cheapest and dearest edit weights (every feature is non-negative). */
        private val W_MIN = ChannelWeights(GRID.s1.min(), GRID.g.min(), GRID.g2.min(), GRID.tau.min(),
            shortWordDeletionCost = GRID.delta.min())
        private val W_MAX = ChannelWeights(GRID.s1.max(), GRID.g.max(), GRID.g2.max(), GRID.tau.max(),
            shortWordDeletionCost = GRID.delta.max())
        private val BETA_MAX = GRID.beta.max()

        /**
         * The candidates that can win under SOME grid configuration (harness pre-filter, added
         * after the pre-registration; it changes no outcome). Every alignment's cost is
         * non-decreasing in each edit weight, so a candidate's optimal edit cost under any grid
         * weights lies in [E(W_MIN), E(W_MAX)]. Candidate c is dropped when, for every λ in the
         * grid, `E_c(W_MIN) + λ·r_c` exceeds the best `E_d(W_MAX) + λ·r_d` by more than the tie
         * tolerance: then some d beats c under every configuration and c never wins. Input
         * order is kept, so ties resolve exactly as in production. Round 2: an alias key's lower
         * bound also subtracts the grid's largest β (the upper bounds use β = 0, which is valid
         * because β only lowers a cost).
         */
        private fun contenders(t: String, cands: List<ChannelCandidate>, maxFrequency: Int): List<ChannelCandidate> {
            if (cands.size <= 1) return cands
            val lo = FloatArray(cands.size) {
                ChannelEditCost.cost(t, cands[it].word, W_MIN) - if (cands[it].isAlias) BETA_MAX else 0f
            }
            val hi = FloatArray(cands.size) { ChannelEditCost.cost(t, cands[it].word, W_MAX) }
            val r = FloatArray(cands.size) { ChannelRanker.rankSteps(cands[it].frequency, maxFrequency) }
            val upper = FloatArray(LAMBDA.size) { li -> cands.indices.minOf { hi[it] + LAMBDA[li] * r[it] } }
            return cands.filterIndexed { k, _ ->
                LAMBDA.indices.any { li -> lo[k] + LAMBDA[li] * r[k] <= upper[li] + 1e-3f }
            }
        }

        /** Distinct optimal alignments of [t] → [c] over [REFERENCE_WEIGHTS], flattened. */
        private fun alignments(t: String, c: String): FloatArray {
            val distinct = LinkedHashSet<List<Float>>()
            for (w in REFERENCE_WEIGHTS) distinct += ChannelEditCost.alignmentFeatures(t, c, w).toList()
            return distinct.flatten().toFloatArray()
        }

        /** Doc §1.5 T5, English: typo → required output (`shated` only when γ is finite). */
        val NAMED_EN = linkedMapOf(
            "bery" to "very", "grom" to "from", "ghe" to "the", "sed" to "see", "kne" to "one",
            "nany" to "many", "helo" to "hello",
            "donr" to "don't", "thier" to "their", "thsi" to "this", "tge" to "the", "tfe" to "the",
            "quuestion" to "question", "questin" to "question", "teh" to "the",
            "devixe" to "device",
        )
        const val SHATED = "shated"

        /** Round 6 (§16.3): the pre-registered grid of ρ and of its minimum typed length. */
        val RHO6 = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
        val MIN_LENGTH6 = intArrayOf(3, 4, 5)

        /** The 2026-10-27 Pixel report (§16), reported under every configuration. */
        val REPORT6 = listOf("tomorr", "caf", "resta", "becau", "restau", "tomo", "beautif")

        /** Doc §1.5 T5, Hebrew: the HebrewLayoutAutoCorrectTest cases plus the item-20 case. */
        val NAMED_HE = linkedMapOf(
            "החא" to "הוא", "אהי" to "אני", "ביא" to "היא", "רחד" to "אחד", "אתכ" to "אתה",
            "סאת" to "זאת", "ךמה" to "למה", "שםך" to "שלך", "זנ" to "זה", "טש" to "יש",
            "אתנול" to "אתמול", "תושה" to "תודה",
        )

        private fun shippedConfig(): Config =
            org.objenesis.ObjenesisStd().newInstance(Config::class.java).apply {
                autocorrect_enabled = true
                autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
                autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
                autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
                autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
                autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH
                swipe_debug_detailed_logging = false
                prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
                prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
                context_source = Defaults.CONTEXT_SOURCE
                personalization_weight = Defaults.PERSONALIZATION_WEIGHT
                secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
            }

        private fun newPredictor(
            dict: HashMap<String, Int>,
            index: HashMap<String, MutableSet<String>>,
            aliases: Map<String, String>,
            config: Config = shippedConfig(),
        ): WordPredictor {
            val p = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            fun set(name: String, value: Any?) {
                val f = WordPredictor::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(p, value)
            }
            set("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
            set("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(index))
            set("contractionAliases", aliases)
            set("customAndUserWords", emptySet<String>())
            set("disabledWords", mutableSetOf<String>())
            set("userWordOriginalCase", ConcurrentHashMap<String, String>())
            set("config", config)
            set("cachedMaxFreqForSize", -1)
            set("secondaryIndex", null)
            set("secondaryLanguageCode", "none")
            return p
        }

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class, Trace::class, Xml::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.d(any(), any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            every { Trace.beginSection(any()) } just runs
            every { Trace.endSection() } just runs
            every { Xml.newPullParser() } answers { KXmlParser() }
            KeyAdjacency.setLayoutLetters(emptyMap())
            KeyAdjacency.resetLayout()

            val loaded = ShippedTapDictionary.withContractionAliases()
            enLoaded = loaded
            en = Lang(newPredictor(loaded.dictionary, loaded.prefixIndex, loaded.aliases), loaded.dictionary, loaded.aliases)

            val bin = File.createTempFile("langpack-he-dictionary", ".bin").apply { deleteOnExit() }
            ZipFile("scripts/dictionaries/langpack-he.zip").use { zip ->
                zip.getInputStream(zip.getEntry("dictionary.bin")).use { input ->
                    bin.outputStream().use { input.copyTo(it) }
                }
            }
            val heDict = HashMap<String, Int>()
            val heIndex = HashMap<String, MutableSet<String>>()
            check(BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile(bin, heDict, heIndex))
            he = Lang(newPredictor(heDict, heIndex, emptyMap()), heDict, emptyMap())
            heGrid = LayoutLetterGrid.of(
                KeyboardData.load_string_exn(File("src/main/layouts/hebr_1_il.xml").readText())
            )
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            KeyAdjacency.setLayoutLetters(emptyMap())
            KeyAdjacency.resetLayout()
            unmockkAll()
        }

        /** FNV-1a of a string key. */
        fun fnv(w: String): Int {
            var h = 0x811C9DC5.toInt()
            for (c in w) { h = h xor c.code; h *= 0x01000193 }
            return h
        }

        /** S-adj / C-oov split: DEV when the FNV-1a hash is even. */
        fun isDevWord(w: String): Boolean = (fnv(w) and 1) == 0
    }

    // ── Decision replica (WordPredictor.channelDecide over stored alignments) ─────────────────

    /** Rule C's output for [p] under [cfg]; [lang] supplies alias finalization. */
    private fun decide(lang: Lang, p: Probed, cfg: Cfg, scratch: FloatArray): String {
        p.fixed?.let { return it }
        if (p.guardStemRank != null && cfg.gamma == Float.POSITIVE_INFINITY) return p.typed
        val w = cfg.weights
        val costs = if (scratch.size >= p.candidates.size) scratch else FloatArray(p.candidates.size)
        for (k in p.candidates.indices) {
            val f = p.features[k]
            var best = Float.POSITIVE_INFINITY
            var o = 0
            while (o < f.size) {
                // Inline ChannelEditCost.priced over one stored alignment (no copy per call).
                val v = f[o + ChannelEditCost.F_SUBSTITUTIONS] + w.distanceCost * f[o + ChannelEditCost.F_KEY_DISTANCE] +
                    w.indelCost * f[o + ChannelEditCost.F_INDELS] + w.doubleCost * f[o + ChannelEditCost.F_DOUBLES] +
                    w.transpositionCost * f[o + ChannelEditCost.F_SWAPS] +
                    w.shortWordDeletionCost * f[o + ChannelEditCost.F_SHORT_DELETION]
                if (v < best) best = v
                o += ChannelEditCost.FEATURE_COUNT
            }
            costs[k] = best
        }
        val i = ChannelRanker.pick(p.candidates, costs, p.maxFrequency, cfg.lambda, cfg.beta)
        if (i < 0) return p.typed
        val winner = p.candidates[i]
        val winnerCost = ChannelRanker.totalCost(costs[i], winner.frequency, p.maxFrequency, cfg.lambda,
            winner.isAlias, cfg.beta)
        if (p.guardStemRank != null && !isDoubleReduction(p.typed, winner.word) &&
            cfg.gamma + cfg.lambda * p.guardStemRank <= winnerCost
        ) return p.typed
        if (costs[i] > cfg.kappa) return p.typed
        if (winner.frequency < p.floor) return p.typed
        val alias = lang.aliases[winner.word] ?: return winner.word
        return if (alias.startsWith("i'")) alias.replaceFirstChar { it.uppercase() } else alias
    }

    private fun prodTuning(cfg: Cfg) = AutocorrectTuning.DEFAULT.copy(ranking = AutocorrectRanking.CHANNEL, channel = cfg.weights)

    // ── Corpora ───────────────────────────────────────────────────────────────

    private val letters = Regex("^[a-z]+$")

    /** The DD-M doc §1.2 dictionary filter shared by every real-pair set. */
    private fun keep(p: P) = letters.matches(p.typed) && letters.matches(p.target) &&
        p.typed != p.target && en.dict.containsKey(p.target) && !en.dict.containsKey(p.typed) &&
        p.typed !in en.aliases

    private fun readGtc(split: String): List<P> {
        val out = ArrayList<P>()
        File(corpusDir, "gtc-pairs.tsv").useLines { lines ->
            lines.drop(1).forEach { line ->
                val c = line.split('\t')
                if (c[0] == split) out += P(c[1], c[2], c[3].toInt())
            }
        }
        return out.filter(::keep)
    }

    private fun readDat(name: String): List<P> {
        val out = LinkedHashSet<P>()
        var target: String? = null
        File(corpusDir, name).forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("$")) { target = line.substring(1).lowercase(); return@forEachLine }
            val tgt = target ?: return@forEachLine
            out += P(line.split(Regex("\\s+"))[0].lowercase(), tgt)
        }
        return out.filter(::keep)
    }

    /** S-adj: every one-QWERTY-neighbour substitution of each top-10k word, split by base-word hash. */
    private fun sAdj(dev: Boolean): List<P> {
        val top = en.dict.entries.filter { it.key.length >= 3 && letters.matches(it.key) && it.key !in en.aliases }
            .sortedByDescending { it.value }.take(10_000).map { it.key }
        val out = LinkedHashSet<P>()
        for (w in top) {
            if (isDevWord(w) != dev) continue
            for (i in w.indices) for (c in 'a'..'z') {
                if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
                val typo = w.substring(0, i) + c + w.substring(i + 1)
                if (!en.dict.containsKey(typo) && typo !in en.aliases) out += P(typo, w)
            }
        }
        return out.toList()
    }

    /** C-oov (doc §1.2): valid OOV words, split by hash, a quarter of each half. */
    private fun cOov(dev: Boolean): List<P> =
        File(corpusDir, "words_alpha.txt").readLines().asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 3..20 && letters.matches(it) && !en.dict.containsKey(it) && it !in en.aliases }
            .distinct()
            .filter { isDevWord(it) == dev && (fnv(it) ushr 1) % 4 == 0 }
            .map { P(it, it) }.toList()

    /** HE-adj: every one-`hebr_1_il`-neighbour substitution of each top-500 pack word (grid installed). */
    private fun heAdj(): List<P> {
        val top = he.dict.entries.filter { it.key.length >= 2 && it.key.all { c -> c in heGrid } }
            .sortedByDescending { it.value }.take(500).map { it.key }
        val out = LinkedHashSet<P>()
        for (w in top) for (i in w.indices) for (c in heGrid.keys) {
            if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
            val typo = w.substring(0, i) + c + w.substring(i + 1)
            if (!he.dict.containsKey(typo)) out += P(typo, w)
        }
        return out.toList()
    }

    /** Typo class of a (typo, target) pair — descriptive only. */
    private fun typoClass(p: P): String {
        val a = p.typed
        val b = p.target
        if (a.length != b.length) return "length-change"
        val diff = a.indices.filter { a[it] != b[it] }
        if (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]) {
            return "transposition"
        }
        if (diff.isEmpty()) return "same"
        val allNb = diff.all { KeyAdjacency.areNeighbors(a[it], b[it]) }
        return (if (diff.size == 1) "1-sub-" else "multi-sub-") + if (allNb) "adjacent" else "distant"
    }

    private fun classes(t: Tally): String {
        fun g(l: List<P>) = l.groupingBy(::typoClass).eachCount().toSortedMap().toString()
        return "fixes by class ${g(t.fixes)}; breaks by class ${g(t.breaks)}"
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    /** Tally of [pairs] given the baseline and candidate output per typed word. */
    private fun tally(lang: Lang, pairs: List<P>, base: Map<String, String>, cand: Map<String, String>,
                      rows: MutableList<String>?, set: String, label: String): Tally {
        val t = Tally()
        for (p in pairs) {
            val b = base.getValue(p.typed)
            val c = cand.getValue(p.typed)
            val bOk = lang.ok(b, p.target)
            val cOk = lang.ok(c, p.target)
            if (bOk) t.baseOk++
            if (cOk) t.candOk++
            val kind = when {
                !bOk && cOk -> { t.fixes += p; "fix" }
                bOk && !cOk -> { t.breaks += p; "break" }
                !bOk && !cOk && b != c -> { t.swaps += p; "swap" }
                else -> continue
            }
            rows?.add("$set\t$label\t${p.typed}\t${p.target}\t${p.occ}\t$b\t$c\t$kind\t${typoClass(p)}")
        }
        return t
    }

    /** Baseline and probe for every distinct typed word of [pairs], in parallel (read-only sweeps). */
    private fun prepare(lang: Lang, pairs: List<P>, label: String, say: (String) -> Unit): Pair<Map<String, String>, Map<String, Probed>> {
        val typed = pairs.map { it.typed }.distinct()
        val base = ConcurrentHashMap<String, String>()
        val probed = ConcurrentHashMap<String, Probed>()
        val done = AtomicInteger()
        val start = System.nanoTime()
        typed.parallelStream().forEach { t ->
            base[t] = lang.base(t)
            probed[t] = lang.probe(t)
            val n = done.incrementAndGet()
            if (n % 20_000 == 0) println("  $label prepared $n/${typed.size} in ${(System.nanoTime() - start) / 1_000_000_000}s")
        }
        say("$label: ${typed.size} distinct inputs prepared in ${(System.nanoTime() - start) / 1_000_000_000}s; " +
            "mean contending candidates after the pre-filter ${"%.2f".format(probed.values.filter { it.fixed == null }.map { it.candidates.size }.average())}")
        return base to probed
    }

    private fun outputs(lang: Lang, probed: Map<String, Probed>, cfg: Cfg): Map<String, String> {
        val scratch = FloatArray(4096)
        return probed.mapValues { decide(lang, it.value, cfg, scratch) }
    }

    /** Named cases that fail under [cfg], through FULL production `autoCorrect` (doc §1.5 T5). */
    private fun namedFailures(cfg: Cfg, includeShated: Boolean): List<String> {
        val bad = mutableListOf<String>()
        val tuning = prodTuning(cfg)
        KeyAdjacency.setLayoutLetters(emptyMap())
        val en2 = if (includeShated) NAMED_EN + (SHATED to "shared") else NAMED_EN
        for ((typo, want) in en2) {
            val out = en.predictor.autoCorrect(typo, tuning)
            if (out != want) bad += "$typo → $out (want $want)"
        }
        KeyAdjacency.setLayoutLetters(heGrid)
        try {
            for ((typo, want) in NAMED_HE) {
                val out = he.predictor.autoCorrect(typo, tuning)
                if (out != want) bad += "$typo → $out (want $want)"
            }
        } finally {
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        return bad
    }

    /** Named cases under [cfg] through the replica (the grid's C3 screen). */
    private fun namedFailuresReplica(cfg: Cfg, enNamed: Map<String, Probed>, heNamed: Map<String, Probed>, includeShated: Boolean): Int {
        val scratch = FloatArray(4096)
        var bad = 0
        for ((typo, want) in NAMED_EN) if (decide(en, enNamed.getValue(typo), cfg, scratch) != want) bad++
        if (includeShated && decide(en, enNamed.getValue(SHATED), cfg, scratch) != "shared") bad++
        for ((typo, want) in NAMED_HE) if (decide(he, heNamed.getValue(typo), cfg, scratch) != want) bad++
        return bad
    }

    // ── Phases ────────────────────────────────────────────────────────────────

    @Test
    fun measure() {
        val phase = System.getenv("CK_CH_PHASE") ?: return
        assumeTrue("gtc-pairs.tsv missing in $corpusDir", File(corpusDir, "gtc-pairs.tsv").isFile)
        outDir.mkdirs()
        val report = StringBuilder()
        fun say(s: String) { println(s); report.appendLine(s); File(outDir, "summary-$phase.txt").writeText(report.toString()) }
        when (phase) {
            "dev" -> when (ROUND) { 1 -> devPhase(::say); 3 -> devPhase3(::say); 5 -> devPhase5(::say); 6 -> devPhase6(::say); else -> devPhase2(::say) }
            "test" -> when (ROUND) { 3 -> testPhase3(::say); 4 -> testPhase4(::say); 5 -> testPhase5(::say); 6 -> testPhase6(::say); else -> testPhase(::say) }
            "inspect" -> inspectPhase(::say)
            "prefix" -> prefixPhase(::say)
            "neighbour" -> neighbourPhase(::say)
            else -> error("CK_CH_PHASE must be dev, test, inspect, prefix or neighbour")
        }
    }

    private fun devPhase(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val oov = cOov(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("GTC-DEV pairs=${gtc.size} targets=${gtc.map { it.target }.toSet().size}; " +
            "S-adj-DEV pairs=${adj.size} words=$adjWords; C-oov-DEV words=${oov.size}")
        say("named failures under the band baseline: " +
            NAMED_EN.filter { (t, w) -> en.base(t) != w }.map { (t, w) -> "$t → ${en.base(t)} (want $w)" } +
            " shated → ${en.base(SHATED)}")

        val enNamed = (NAMED_EN.keys + SHATED).associateWith { en.probe(it) }
        KeyAdjacency.setLayoutLetters(heGrid)
        val heNamed = NAMED_HE.keys.associateWith { he.probe(it) }
        KeyAdjacency.setLayoutLetters(emptyMap())

        val (gBase, gProbe) = prepare(en, gtc, "GTC-DEV", say)
        val (aBase, aProbe) = prepare(en, adj, "S-adj-DEV", say)

        // Grid stage, γ = ∞ (doc §1.4 C1–C3).
        data class Row(val cfg: Cfg, val gFix: Int, val gBreak: Int, val aFix: Int, val aBreakRaw: Int,
                       val aBreak: Int, val named: Int, val admissible: Boolean)
        val cfgs = buildList {
            for (s1 in GRID.s1) for (g in GRID.g) for (g2 in GRID.g2) for (tau in GRID.tau) for (l in GRID.lambda)
                add(Cfg(s1, g, g2, tau, l, 0f, Float.POSITIVE_INFINITY))
        }
        val start = System.nanoTime()
        val rowsByIndex = arrayOfNulls<Row>(cfgs.size)
        cfgs.indices.toList().parallelStream().forEach { idx ->
            val cfg = cfgs[idx]
            val scratch = FloatArray(4096)
            fun counts(pairs: List<P>, base: Map<String, String>, probe: Map<String, Probed>): IntArray {
                val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0
                val memo = HashMap<String, String>()
                for (p in pairs) {
                    val c = memo.getOrPut(p.typed) { decide(en, probe.getValue(p.typed), cfg, scratch) }
                    val bOk = en.ok(base.getValue(p.typed), p.target)
                    val cOk = en.ok(c, p.target)
                    if (!bOk && cOk) fix += p.target
                    if (bOk && !cOk) { brk += p.target; brkRaw++ }
                }
                return intArrayOf(fix.size, brk.size, brkRaw)
            }
            val gc = counts(gtc, gBase, gProbe)
            val ac = counts(adj, aBase, aProbe)
            val named = namedFailuresReplica(cfg, enNamed, heNamed, includeShated = false)
            val c1 = gc[0] - gc[1] > 0 && gc[1] <= 0.5 * gc[0]
            val c2 = ac[0] - ac[1] > 0 && ac[2] <= 0.008 * adj.size && ac[1] <= 0.008 * adjWords
            rowsByIndex[idx] = Row(cfg, gc[0], gc[1], ac[0], ac[2], ac[1], named, c1 && c2 && named == 0)
        }
        val grid = rowsByIndex.map { it!! }
        say("grid: ${grid.size} configurations in ${(System.nanoTime() - start) / 1_000_000_000}s; " +
            "admissible ${grid.count { it.admissible }}")
        File(outDir, "grid-dev.tsv").writeText(
            "s1\tg\tg2\ttau\tlambda\tgtcFix\tgtcBreak\tgtcNet\tadjFix\tadjBreakRaw\tadjBreak\tnamedFail\tadmissible\n" +
                grid.joinToString("\n") { r ->
                    "${r.cfg.s1}\t${r.cfg.g}\t${r.cfg.g2}\t${r.cfg.tau}\t${r.cfg.lambda}\t${r.gFix}\t${r.gBreak}\t" +
                        "${r.gFix - r.gBreak}\t${r.aFix}\t${r.aBreakRaw}\t${r.aBreak}\t${r.named}\t${r.admissible}"
                } + "\n")
        for (r in grid.sortedByDescending { it.gFix - it.gBreak }.take(15)) {
            say("  top-net ${r.cfg}: GTC fixes=${r.gFix} breaks=${r.gBreak} net=${r.gFix - r.gBreak}; " +
                "S-adj fixes=${r.aFix} breaks=${r.aBreak} (raw ${r.aBreakRaw}); named fail=${r.named}; admissible=${r.admissible}")
        }
        // Choice: max GTC net; ties → fewer S-adj distinct breaks; then grid order (list order).
        val chosen = grid.withIndex().filter { it.value.admissible }
            .sortedWith(compareByDescending<IndexedValue<Row>> { it.value.gFix - it.value.gBreak }
                .thenBy { it.value.aBreak }.thenBy { it.index })
            .firstOrNull()?.value
        if (chosen == null) {
            say("\nDEV choice (doc §1.4): NONE — no admissible configuration; nothing ships as default, TEST not run")
            return
        }
        say("\nDEV choice (grid, γ = ∞): ${chosen.cfg}")
        val named = namedFailures(chosen.cfg, includeShated = false)
        say("  production named failures at the choice: $named")

        // Soundness (doc §1.3): replica vs full production on 2,000 seeded DEV inputs.
        val sample = (gProbe.keys + aProbe.keys).sorted().shuffled(java.util.Random(20261018)).take(2_000)
        val scratch = FloatArray(4096)
        val mismatches = sample.filter { t ->
            val probe = gProbe[t] ?: aProbe.getValue(t)
            decide(en, probe, chosen.cfg, scratch) != en.predictor.autoCorrect(t, prodTuning(chosen.cfg))
        }
        say("  soundness: ${sample.size} inputs, mismatches=${mismatches.size} ${mismatches.take(20)}")
        check(mismatches.isEmpty()) { "replica disagrees with production: run void" }

        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        val tg = tally(en, gtc, gBase, outputs(en, gProbe, chosen.cfg), rows, "GTC-DEV", chosen.cfg.toString())
        val ta = tally(en, adj, aBase, outputs(en, aProbe, chosen.cfg), rows, "S-adj-DEV", chosen.cfg.toString())
        say("  ${tg.line("GTC-DEV", gtc.size, gtc.map { it.target }.toSet().size)}")
        say("      ${classes(tg)}")
        say("  ${ta.line("S-adj-DEV", adj.size, adjWords)}")

        // γ stage (doc §1.4 "Then γ").
        val (oBase, oProbe) = prepare(en, oov, "C-oov-DEV", say)
        val to = tally(en, oov, oBase, outputs(en, oProbe, chosen.cfg), null, "C-oov-DEV", "")
        say("  C-oov-DEV at γ=∞: new changes=${to.breaks.size} (${"%.3f".format(100.0 * to.breaks.size / oov.size)}%)")
        val netInf = tg.netDistinct
        var bestGamma: Float? = null
        var bestNet = Int.MIN_VALUE
        for (gamma in ROUND1.gamma) {
            val cfg = chosen.cfg.copy(gamma = gamma)
            val g = tally(en, gtc, gBase, outputs(en, gProbe, cfg), rows, "GTC-DEV", cfg.toString())
            val a = tally(en, adj, aBase, outputs(en, aProbe, cfg), null, "S-adj-DEV", "")
            val o = tally(en, oov, oBase, outputs(en, oProbe, cfg), rows, "C-oov-DEV", cfg.toString())
            val nf = namedFailures(cfg, includeShated = true)
            val oovOk = o.breaks.size <= 0.005 * oov.size
            val netOk = g.netDistinct >= netInf
            val c2 = a.netDistinct > 0 && a.breaks.size <= 0.008 * adj.size && a.d(a.breaks) <= 0.008 * adjWords
            val adm = oovOk && netOk && c2 && nf.isEmpty()
            say("  γ=$gamma: GTC net=${g.netDistinct} (fixes ${g.d(g.fixes)}, breaks ${g.d(g.breaks)}); " +
                "S-adj net=${a.netDistinct} breaks=${a.d(a.breaks)} (raw ${a.breaks.size}); " +
                "C-oov new changes=${o.breaks.size} (${"%.3f".format(100.0 * o.breaks.size / oov.size)}%); " +
                "named failures=$nf → ${if (adm) "admissible" else "-"}")
            // Ties go to the larger γ: GAMMA ascends, so a later equal net replaces.
            if (adm && g.netDistinct >= bestNet) { bestGamma = gamma; bestNet = g.netDistinct }
        }
        File(outDir, "rows-dev.tsv").writeText(rows.joinToString("\n") + "\n")
        val final = chosen.cfg.copy(gamma = bestGamma ?: Float.POSITIVE_INFINITY)
        say("\nDEV choice (doc §1.4): $final")
        say("CK_CH_CONFIG=${final.spec}")
    }

    /**
     * Round 2 DEV (doc §3): the joint grid over (s₁, g, g₂, τ, λ, β, γ) with C1–C4, the choice,
     * the soundness check and the chosen configuration's tallies.
     */
    private fun devPhase2(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val oov = cOov(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("ROUND 2. GTC-DEV pairs=${gtc.size} targets=${gtc.map { it.target }.toSet().size}; " +
            "S-adj-DEV pairs=${adj.size} words=$adjWords; C-oov-DEV words=${oov.size}")

        val enNamed = (NAMED_EN.keys + SHATED).associateWith { en.probe(it) }
        KeyAdjacency.setLayoutLetters(heGrid)
        val heNamed = NAMED_HE.keys.associateWith { he.probe(it) }
        KeyAdjacency.setLayoutLetters(emptyMap())

        val (gBase, gProbe) = prepare(en, gtc, "GTC-DEV", say)
        val (aBase, aProbe) = prepare(en, adj, "S-adj-DEV", say)
        val (oBase, oProbe) = prepare(en, oov, "C-oov-DEV", say)

        data class Row(val cfg: Cfg, val index: Int, val gFix: Int, val gBreak: Int, val aFix: Int, val aBreakRaw: Int,
                       val aBreak: Int, val oNew: Int, val named: Int, val c1: Boolean, val c2: Boolean,
                       val c4: Boolean) {
            val gNet get() = gFix - gBreak
            val admissible get() = c1 && c2 && named == 0 && c4
        }
        val cfgs = buildList {
            for (s1 in GRID.s1) for (g in GRID.g) for (g2 in GRID.g2) for (tau in GRID.tau)
                for (l in GRID.lambda) for (b in GRID.beta) for (gm in GRID.gamma)
                    add(Cfg(s1, g, g2, tau, l, b, gm))
        }
        val start = System.nanoTime()
        val done = AtomicInteger()
        val rowsByIndex = arrayOfNulls<Row>(cfgs.size)
        cfgs.indices.toList().parallelStream().forEach { idx ->
            val cfg = cfgs[idx]
            val scratch = FloatArray(4096)
            fun counts(pairs: List<P>, base: Map<String, String>, probe: Map<String, Probed>): IntArray {
                val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0
                val memo = HashMap<String, String>()
                for (p in pairs) {
                    val c = memo.getOrPut(p.typed) { decide(en, probe.getValue(p.typed), cfg, scratch) }
                    val bOk = en.ok(base.getValue(p.typed), p.target)
                    val cOk = en.ok(c, p.target)
                    if (!bOk && cOk) fix += p.target
                    if (bOk && !cOk) { brk += p.target; brkRaw++ }
                }
                return intArrayOf(fix.size, brk.size, brkRaw)
            }
            val gc = counts(gtc, gBase, gProbe)
            val ac = counts(adj, aBase, aProbe)
            val oc = counts(oov, oBase, oProbe)
            val named = namedFailuresReplica(cfg, enNamed, heNamed, includeShated = true)
            val c1 = gc[0] - gc[1] > 0 && gc[1] <= 0.5 * gc[0]
            val c2 = ac[0] - ac[1] > 0 && ac[1] <= 0.05 * ac[0] && ac[2] <= 0.008 * adj.size
            val c4 = oc[2] <= 0.005 * oov.size
            rowsByIndex[idx] = Row(cfg, idx, gc[0], gc[1], ac[0], ac[2], ac[1], oc[2], named, c1, c2, c4)
            val n = done.incrementAndGet()
            if (n % 1_000 == 0) println("  grid $n/${cfgs.size} in ${(System.nanoTime() - start) / 1_000_000_000}s")
        }
        val grid = rowsByIndex.map { it!! }
        say("grid: ${grid.size} configurations in ${(System.nanoTime() - start) / 1_000_000_000}s; " +
            "C1 ${grid.count { it.c1 }}, C2 ${grid.count { it.c2 }}, C3 ${grid.count { it.named == 0 }}, " +
            "C4 ${grid.count { it.c4 }}, admissible ${grid.count { it.admissible }}")
        File(outDir, "grid2-dev.tsv").writeText(
            "s1\tg\tg2\ttau\tlambda\tbeta\tgamma\tgtcFix\tgtcBreak\tgtcNet\tadjFix\tadjBreakRaw\tadjBreak\toovNew\tnamedFail\tadmissible\n" +
                grid.joinToString("\n") { r ->
                    "${r.cfg.s1}\t${r.cfg.g}\t${r.cfg.g2}\t${r.cfg.tau}\t${r.cfg.lambda}\t${r.cfg.beta}\t${r.cfg.gamma}\t" +
                        "${r.gFix}\t${r.gBreak}\t${r.gNet}\t${r.aFix}\t${r.aBreakRaw}\t${r.aBreak}\t${r.oNew}\t" +
                        "${r.named}\t${r.admissible}"
                } + "\n")
        for (r in grid.filter { it.admissible }.sortedByDescending { it.gNet }.take(10)) {
            say("  admissible ${r.cfg}: GTC fixes=${r.gFix} breaks=${r.gBreak} net=${r.gNet}; " +
                "S-adj fixes=${r.aFix} breaks=${r.aBreak} (raw ${r.aBreakRaw}); C-oov new=${r.oNew}")
        }
        for (r in grid.filter { !it.admissible }.sortedByDescending { it.gNet }.take(5)) {
            say("  top-net (inadmissible) ${r.cfg}: GTC net=${r.gNet}; S-adj fixes=${r.aFix} breaks=${r.aBreak} " +
                "(raw ${r.aBreakRaw}); C-oov new=${r.oNew}; named fail=${r.named}; C1=${r.c1} C2=${r.c2} C4=${r.c4}")
        }
        // Choice (§3.3): max GTC net; ties → fewer S-adj distinct breaks, larger γ, grid order.
        val order = compareByDescending<Row> { it.gNet }.thenBy { it.aBreak }
            .thenByDescending { it.cfg.gamma }.thenBy { it.index }
        val chosen = grid.filter { it.admissible }.sortedWith(order).firstOrNull()
        // Opt-in fallback (§3.4): pass C2, C3, C4 (any C1), most GTC net.
        val fallback = grid.filter { it.c2 && it.named == 0 && it.c4 }.sortedWith(order).firstOrNull()
        say("opt-in fallback (§3.4): ${fallback?.cfg?.spec ?: "none — keep the round-1 grid centre"}")
        if (chosen == null) {
            say("\nDEV choice (doc §3.3): NONE — no admissible configuration; band stays the default, TEST not run")
            return
        }
        say("\nDEV choice (doc §3.3): ${chosen.cfg}")
        say("  production named failures at the choice: ${namedFailures(chosen.cfg, includeShated = true)}")
        val sample = (gProbe.keys + aProbe.keys + oProbe.keys).sorted().shuffled(java.util.Random(20261018)).take(2_000)
        val scratch = FloatArray(4096)
        val mismatches = sample.filter { t ->
            val probe = gProbe[t] ?: aProbe[t] ?: oProbe.getValue(t)
            decide(en, probe, chosen.cfg, scratch) != en.predictor.autoCorrect(t, prodTuning(chosen.cfg))
        }
        say("  soundness: ${sample.size} inputs, mismatches=${mismatches.size} ${mismatches.take(20)}")
        check(mismatches.isEmpty()) { "replica disagrees with production: run void" }
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        val tg = tally(en, gtc, gBase, outputs(en, gProbe, chosen.cfg), rows, "GTC-DEV", chosen.cfg.toString())
        val ta = tally(en, adj, aBase, outputs(en, aProbe, chosen.cfg), rows, "S-adj-DEV", chosen.cfg.toString())
        val to = tally(en, oov, oBase, outputs(en, oProbe, chosen.cfg), rows, "C-oov-DEV", chosen.cfg.toString())
        say("  ${tg.line("GTC-DEV", gtc.size, gtc.map { it.target }.toSet().size)}")
        say("      ${classes(tg)}")
        say("  ${ta.line("S-adj-DEV", adj.size, adjWords)}")
        say("  C-oov-DEV new changes=${to.breaks.size} (${"%.3f".format(100.0 * to.breaks.size / oov.size)}%)")
        File(outDir, "rows2-dev.tsv").writeText(rows.joinToString("\n") + "\n")
        say("CK_CH_CONFIG=${chosen.cfg.spec}")
    }

    /** HE-adj2 (§7.2): neighbour slips of pack words ranked 501–2,500, split by base-word hash. */
    private fun heAdj2(dev: Boolean): List<P> = heAdjRanks(500, 2_500) { isDevWord(it) == dev }

    /** HE-adj3 (§11): neighbour slips of pack words ranked 2,501–6,000 (fresh; no split). */
    private fun heAdj3(): List<P> = heAdjRanks(2_500, 6_000) { true }

    /** Neighbour slips of Hebrew pack words in frequency ranks (from, to] whose base passes [keep]. */
    private fun heAdjRanks(from: Int, to: Int, keep: (String) -> Boolean): List<P> {
        val ranked = he.dict.entries.filter { it.key.length >= 2 && it.key.all { c -> c in heGrid } }
            .sortedByDescending { it.value }.map { it.key }
        val out = LinkedHashSet<P>()
        for (w in ranked.subList(from, minOf(to, ranked.size))) {
            if (!keep(w)) continue
            for (i in w.indices) for (c in heGrid.keys) {
                if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
                val typo = w.substring(0, i) + c + w.substring(i + 1)
                if (!he.dict.containsKey(typo)) out += P(typo, w)
            }
        }
        return out.toList()
    }

    /** Distinct (fixes, breaks, raw breaks) of [cfg] against the δ = 0 baseline over [pairs]. */
    private fun counts3(lang: Lang, pairs: List<P>, probe: Map<String, Probed>, cfg: Cfg): IntArray {
        val scratch = FloatArray(4096)
        val base = Cfg.SHIPPED
        val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0
        val memo = HashMap<String, Pair<String, String>>()
        for (p in pairs) {
            val (b, c) = memo.getOrPut(p.typed) {
                val pr = probe.getValue(p.typed)
                decide(lang, pr, base, scratch) to decide(lang, pr, cfg, scratch)
            }
            val bOk = lang.ok(b, p.target)
            val cOk = lang.ok(c, p.target)
            if (!bOk && cOk) fix += p.target
            if (bOk && !cOk) { brk += p.target; brkRaw++ }
        }
        return intArrayOf(fix.size, brk.size, brkRaw)
    }

    /** Probes [pairs] (one production sweep each); the δ = 0 decision is the shipped baseline. */
    private fun probeAll(lang: Lang, pairs: List<P>, label: String, say: (String) -> Unit): Map<String, Probed> {
        val typed = pairs.map { it.typed }.distinct()
        val out = ConcurrentHashMap<String, Probed>()
        val start = System.nanoTime()
        typed.parallelStream().forEach { out[it] = lang.probe(it) }
        say("$label: ${typed.size} distinct inputs probed in ${(System.nanoTime() - start) / 1_000_000_000}s")
        return out
    }

    /** Round 3 DEV (§7.3): δ against the shipped rule C2 on fresh HE-adj2-DEV plus English no-harm sets. */
    private fun devPhase3(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(heGrid)
        val he2 = heAdj2(dev = true)
        val heProbe = probeAll(he, he2, "HE-adj2-DEV", say)
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val oov = cOov(dev = true)
        say("ROUND 3. HE-adj2-DEV pairs=${he2.size} words=${he2.map { it.target }.toSet().size}; " +
            "GTC-DEV ${gtc.size}; S-adj-DEV ${adj.size}; C-oov-DEV ${oov.size}")
        val gProbe = probeAll(en, gtc, "GTC-DEV", say)
        val aProbe = probeAll(en, adj, "S-adj-DEV", say)
        val oProbe = probeAll(en, oov, "C-oov-DEV", say)
        val enNamed = (NAMED_EN.keys + SHATED).associateWith { en.probe(it) }
        KeyAdjacency.setLayoutLetters(heGrid)
        val heNamed = NAMED_HE.keys.associateWith { he.probe(it) }
        KeyAdjacency.setLayoutLetters(emptyMap())

        var chosen: Cfg? = null
        var bestNet = Int.MIN_VALUE
        for (delta in GRID.delta.filter { it > 0f }) {
            val cfg = Cfg.SHIPPED.copy(delta = delta)
            val h = counts3(he, he2, heProbe, cfg)
            val g = counts3(en, gtc, gProbe, cfg)
            val a = counts3(en, adj, aProbe, cfg)
            val o = counts3(en, oov, oProbe, cfg)
            val named = namedFailuresReplica(cfg, enNamed, heNamed, includeShated = true)
            val h1 = h[0] - h[1] > 0 && h[1] <= 0.25 * h[0]
            val e1 = g[0] - g[1] >= 0 && a[0] - a[1] >= 0 && o[2] <= 0.001 * oov.size
            val adm = h1 && e1 && named == 0
            say("  δ=$delta: HE-adj2-DEV fixes=${h[0]} breaks=${h[1]} (raw ${h[2]}) net=${h[0] - h[1]}; " +
                "GTC net=${g[0] - g[1]} (${g[0]}/${g[1]}); S-adj net=${a[0] - a[1]} (${a[0]}/${a[1]}); " +
                "C-oov new=${o[2]}; named fail=$named; H1=$h1 E1=$e1 → ${if (adm) "admissible" else "-"}")
            if (adm && h[0] - h[1] > bestNet) { chosen = cfg; bestNet = h[0] - h[1] }   // ties → smaller δ (ascending)
        }
        val c = chosen
        if (c == null) {
            say("\nDEV choice (§7.3): NONE — δ stays 0, nothing ships, TEST not run")
            return
        }
        say("\nDEV choice (§7.3): δ=${c.delta}")
        say("  production named failures: ${namedFailures(c, includeShated = true)}")
        val sample = (heProbe.keys + gProbe.keys + aProbe.keys + oProbe.keys).sorted()
            .shuffled(java.util.Random(20261019)).take(2_000)
        val scratch = FloatArray(4096)
        val mismatches = sample.filter { t ->
            val inHe = t in heProbe
            if (inHe) KeyAdjacency.setLayoutLetters(heGrid) else KeyAdjacency.setLayoutLetters(emptyMap())
            try {
                val lang = if (inHe) he else en
                val probe = heProbe[t] ?: gProbe[t] ?: aProbe[t] ?: oProbe.getValue(t)
                decide(lang, probe, c, scratch) != lang.predictor.autoCorrect(t, prodTuning(c)) ||
                    decide(lang, probe, Cfg.SHIPPED, scratch) != lang.predictor.autoCorrect(t, BASE6)
            } finally {
                KeyAdjacency.setLayoutLetters(emptyMap())
            }
        }
        say("  soundness: ${sample.size} inputs (chosen δ and the δ = 0 baseline), mismatches=${mismatches.size} ${mismatches.take(20)}")
        check(mismatches.isEmpty()) { "replica disagrees with production: run void" }
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        val heBase = heProbe.mapValues { decide(he, it.value, Cfg.SHIPPED, scratch) }
        val heCand = heProbe.mapValues { decide(he, it.value, c, scratch) }
        val th = tally(he, he2, heBase, heCand, rows, "HE-adj2-DEV", c.toString())
        say("  ${th.line("HE-adj2-DEV", he2.size, he2.map { it.target }.toSet().size)}")
        say("  HE-adj2-DEV breaks: " + th.breaks.joinToString(", ") { "${it.typed}→${heBase[it.typed]}|${heCand[it.typed]} (want ${it.target})" })
        File(outDir, "rows3-dev.tsv").writeText(rows.joinToString("\n") + "\n")
        say("CK_CH_CONFIG=${c.spec}")
    }

    /** Round 3 TEST (§7.4): production baseline (shipped C2) against production with the chosen δ. */
    private fun testPhase3(say: (String) -> Unit) {
        val cfg = Cfg.parse(System.getenv("CK_CH_CONFIG") ?: error("CK_CH_CONFIG required"))
        val tuning = prodTuning(cfg)
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        say("ROUND 3 TEST, $cfg")
        fun run(lang: Lang, pairs: List<P>, set: String): Tally {
            val inputs = pairs.map { it.typed }.distinct()
            val base = ConcurrentHashMap<String, String>()
            val cand = ConcurrentHashMap<String, String>()
            val checkpoint = File(outDir, "test3-$set.tsv")
            val header = "# ${cfg.spec}"
            if (checkpoint.isFile && checkpoint.useLines { it.firstOrNull() } == header) {
                checkpoint.useLines { lines -> lines.drop(1).forEach { l -> val c = l.split('\t'); base[c[0]] = c[1]; cand[c[0]] = c[2] } }
                check(base.keys.containsAll(inputs)) { "checkpoint $checkpoint is incomplete" }
            } else {
                inputs.parallelStream().forEach { t ->
                    base[t] = lang.predictor.autoCorrect(t, BASE6)
                    cand[t] = lang.predictor.autoCorrect(t, tuning)
                }
                checkpoint.writeText(header + "\n" + inputs.joinToString("\n") { "$it\t${base[it]}\t${cand[it]}" } + "\n")
            }
            val t = tally(lang, pairs, base, cand, rows, set, cfg.toString())
            say("  ${t.line(set, pairs.size, pairs.map { it.target }.toSet().size)}")
            return t
        }
        KeyAdjacency.setLayoutLetters(heGrid)
        val he2 = heAdj2(dev = false)
        val h2 = run(he, he2, "HE-adj2-TEST")
        val hePairs = heAdj()
        val h1 = run(he, hePairs, "HE-adj")
        say("  HE-adj2-TEST breaks: " + h2.breaks.joinToString(", ") { "${it.typed} (want ${it.target})" })
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("TEST")
        val adj = sAdj(dev = false)
        val oov = cOov(dev = false)
        val tg = run(en, gtc, "GTC-TEST")
        val ta = run(en, adj, "S-adj-TEST")
        val to = run(en, oov, "C-oov-TEST")
        val tt = run(en, readDat("wikipedia.dat") + readDat("aspell.dat"), "R-typed")
        val named = namedFailures(cfg, includeShated = true)
        val r1 = h2.netDistinct > 0 && h2.d(h2.breaks) <= 0.25 * h2.d(h2.fixes)
        val r2 = tg.netDistinct >= 0 && ta.netDistinct >= 0
        val r3 = to.breaks.size <= 0.001 * oov.size
        val r4 = named.isEmpty()
        say("named failures: $named; HE-adj (seen) net=${h1.netDistinct}; R-typed net=${tt.netDistinct}")
        say("GATE: R1=$r1 R2=$r2 R3=$r3 R4=$r4 → " + if (r1 && r2 && r3 && r4) "PASS" else "FAIL")
        File(outDir, "rows3-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /**
     * Round 4 TEST (§11): δ = 0.5 scoped to Hebrew script against the shipped C2, production for
     * both arms. Q1 fresh HE-adj3, Q2 named cases, Q3 exact English invariance.
     */
    private fun testPhase4(say: (String) -> Unit) {
        val cfg = Cfg.SHIPPED.copy(delta = 0.5f)   // default scope: Hebrew script
        val tuning = prodTuning(cfg)
        check(tuning.channel.shortDeletionScope == ShortDeletionScope.HEBREW)
        say("ROUND 4 TEST, $cfg scope=${tuning.channel.shortDeletionScope}")
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        KeyAdjacency.setLayoutLetters(heGrid)
        val he3 = heAdj3()
        val inputs = he3.map { it.typed }.distinct()
        val base = ConcurrentHashMap<String, String>()
        val cand = ConcurrentHashMap<String, String>()
        val start = System.nanoTime()
        inputs.parallelStream().forEach { t ->
            base[t] = he.predictor.autoCorrect(t, BASE6)
            cand[t] = he.predictor.autoCorrect(t, tuning)
        }
        say("  HE-adj3: ${inputs.size} inputs in ${(System.nanoTime() - start) / 1_000_000_000}s")
        val th = tally(he, he3, base, cand, rows, "HE-adj3", cfg.toString())
        say("  ${th.line("HE-adj3", he3.size, he3.map { it.target }.toSet().size)}")
        say("  HE-adj3 breaks: " + th.breaks.joinToString(", ") { "${it.typed}→${base[it.typed]}|${cand[it.typed]} (want ${it.target})" })
        KeyAdjacency.setLayoutLetters(emptyMap())
        val named = namedFailures(cfg, includeShated = true)
        // Q3: English outputs identical under C4 and the shipped C2.
        val english = (readGtc("TEST").map { it.typed }.distinct() +
            sAdj(dev = false).map { it.typed }.distinct().shuffled(java.util.Random(20261020)).take(20_000)).distinct()
        val diffs = english.parallelStream().filter { t ->
            en.predictor.autoCorrect(t, tuning) != en.predictor.autoCorrect(t, BASE6)
        }.collect(java.util.stream.Collectors.toList())
        say("  English invariance: ${english.size} inputs, differences=${diffs.size} ${diffs.take(20)}")
        val q1 = th.netDistinct > 0 && th.d(th.breaks) <= 0.25 * th.d(th.fixes)
        val q2 = named.isEmpty()
        val q3 = diffs.isEmpty()
        say("named failures: $named")
        say("GATE: Q1=$q1 Q2=$q2 Q3=$q3 → " + if (q1 && q2 && q3) "PASS" else "FAIL")
        File(outDir, "rows4-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /** Distinct targets of [pairs] whose output under [cfg] is right. */
    private fun correctTargets(lang: Lang, pairs: List<P>, probe: Map<String, Probed>, cfg: Cfg): Set<String> {
        val scratch = FloatArray(4096)
        val memo = HashMap<String, String>()
        return pairs.filter { p -> lang.ok(memo.getOrPut(p.typed) { decide(lang, probe.getValue(p.typed), cfg, scratch) }, p.target) }
            .mapTo(HashSet()) { it.target }
    }

    /** Round 5 DEV (§13.3): κ against the shipped default (rule C2 + Hebrew δ). */
    private fun devPhase5(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val oov = cOov(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("ROUND 5. GTC-DEV ${gtc.size}; S-adj-DEV ${adj.size} ($adjWords words); C-oov-DEV ${oov.size}")
        val gProbe = probeAll(en, gtc, "GTC-DEV", say)
        val aProbe = probeAll(en, adj, "S-adj-DEV", say)
        val oProbe = probeAll(en, oov, "C-oov-DEV", say)
        val enNamed = (NAMED_EN.keys + SHATED).associateWith { en.probe(it) }
        KeyAdjacency.setLayoutLetters(heGrid)
        val heNamed = NAMED_HE.keys.associateWith { he.probe(it) }
        KeyAdjacency.setLayoutLetters(emptyMap())
        val base = Cfg.SHIPPED5
        val gRight = correctTargets(en, gtc, gProbe, base).size
        val scratch = FloatArray(4096)
        val oChanged = oov.count { decide(en, oProbe.getValue(it.typed), base, scratch) != it.typed }
        say("baseline: GTC-DEV distinct targets right=$gRight; C-oov-DEV changed by baseline=$oChanged")
        var chosen: Cfg? = null
        var bestProtected = -1
        for (kappa in GRID.kappa) {
            val cfg = base.copy(kappa = kappa)
            // counts3 measures against Cfg.SHIPPED (δ = 0); round 5 needs its own baseline.
            fun vs(lang: Lang, pairs: List<P>, probe: Map<String, Probed>): IntArray {
                val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0
                val memo = HashMap<String, Pair<String, String>>()
                for (p in pairs) {
                    val (b, c) = memo.getOrPut(p.typed) {
                        val pr = probe.getValue(p.typed); decide(lang, pr, base, scratch) to decide(lang, pr, cfg, scratch)
                    }
                    val bOk = lang.ok(b, p.target); val cOk = lang.ok(c, p.target)
                    if (!bOk && cOk) fix += p.target
                    if (bOk && !cOk) { brk += p.target; brkRaw++ }
                }
                return intArrayOf(fix.size, brk.size, brkRaw)
            }
            val g = vs(en, gtc, gProbe)
            val a = vs(en, adj, aProbe)
            val o = vs(en, oov, oProbe)
            val named = namedFailuresReplica(cfg, enNamed, heNamed, includeShated = true)
            val k1 = g[1] <= 0.015 * gRight
            val k2 = a[1] <= 0.005 * adjWords
            val adm = k1 && k2 && named == 0
            say("  κ=$kappa: GTC-DEV breaks=${g[1]} (limit ${"%.1f".format(0.015 * gRight)}) fixes=${g[0]}; " +
                "S-adj-DEV breaks=${a[1]} (limit ${"%.1f".format(0.005 * adjWords)}); C-oov-DEV protected=${o[0]} " +
                "of $oChanged (${"%.1f".format(100.0 * o[0] / oChanged)}%); named fail=$named → ${if (adm) "admissible" else "-"}")
            // GRID.kappa ascends; ties go to the larger κ, so an equal later count replaces.
            if (adm && o[0] >= bestProtected) { chosen = cfg; bestProtected = o[0] }
        }
        val c = chosen
        if (c == null) {
            say("\nDEV choice (§13.3): NONE — κ stays off, TEST not run")
            return
        }
        say("\nDEV choice (§13.3): κ=${c.kappa}")
        say("  production named failures: ${namedFailures(c, includeShated = true)}")
        val sample = (gProbe.keys + aProbe.keys + oProbe.keys).sorted().shuffled(java.util.Random(20261020)).take(2_000)
        val mismatches = sample.filter { t ->
            val probe = gProbe[t] ?: aProbe[t] ?: oProbe.getValue(t)
            decide(en, probe, c, scratch) != en.predictor.autoCorrect(t, prodTuning(c)) ||
                decide(en, probe, base, scratch) != en.predictor.autoCorrect(t, BASE6)
        }
        say("  soundness: ${sample.size} inputs, mismatches=${mismatches.size} ${mismatches.take(20)}")
        check(mismatches.isEmpty()) { "replica disagrees with production: run void" }
        say("CK_CH_CONFIG=${c.spec},${c.kappa}")
    }

    /** Round 5 TEST (§13.4): production shipped default against production with κ. */
    private fun testPhase5(say: (String) -> Unit) {
        val kappa = (System.getenv("CK_CH_KAPPA") ?: error("CK_CH_KAPPA required")).toFloat()
        val cfg = Cfg.SHIPPED5.copy(kappa = kappa)
        val tuning = prodTuning(cfg)
        say("ROUND 5 TEST, $cfg")
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("TEST")
        val adj = sAdj(dev = false)
        val oov = cOov(dev = false)
        val adjWords = adj.map { it.target }.toSet().size
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        fun run(pairs: List<P>, set: String): Triple<Tally, Map<String, String>, Map<String, String>> {
            val inputs = pairs.map { it.typed }.distinct()
            val base = ConcurrentHashMap<String, String>()
            val cand = ConcurrentHashMap<String, String>()
            val checkpoint = File(outDir, "test5-$set.tsv")
            val header = "# kappa=$kappa"
            if (checkpoint.isFile && checkpoint.useLines { it.firstOrNull() } == header) {
                checkpoint.useLines { l -> l.drop(1).forEach { x -> val c = x.split('\t'); base[c[0]] = c[1]; cand[c[0]] = c[2] } }
                check(base.keys.containsAll(inputs)) { "checkpoint $checkpoint is incomplete" }
            } else {
                inputs.parallelStream().forEach { t ->
                    base[t] = en.predictor.autoCorrect(t, BASE6)
                    cand[t] = en.predictor.autoCorrect(t, tuning)
                }
                checkpoint.writeText(header + "\n" + inputs.joinToString("\n") { "$it\t${base[it]}\t${cand[it]}" } + "\n")
            }
            val t = tally(en, pairs, base, cand, rows, set, cfg.toString())
            say("  ${t.line(set, pairs.size, pairs.map { it.target }.toSet().size)}")
            return Triple(t, base, cand)
        }
        val (tg, gBase, _) = run(gtc, "GTC-TEST")
        val (ta, _, _) = run(adj, "S-adj-TEST")
        val (to, oBase, _) = run(oov, "C-oov-TEST")
        val gRight = gtc.filter { en.ok(gBase.getValue(it.typed), it.target) }.mapTo(HashSet()) { it.target }.size
        val oChanged = oov.count { oBase.getValue(it.typed) != it.typed }
        val named = namedFailures(cfg, includeShated = true)
        val l1 = tg.d(tg.breaks) <= 0.015 * gRight
        val l2 = ta.d(ta.breaks) <= 0.005 * adjWords
        val l3 = to.d(to.fixes) >= 0.25 * oChanged
        val l4 = named.isEmpty()
        say("GTC-TEST distinct breaks=${tg.d(tg.breaks)} of $gRight right; S-adj-TEST distinct breaks=${ta.d(ta.breaks)} of $adjWords; " +
            "C-oov-TEST protected=${to.d(to.fixes)} of $oChanged changed (${"%.1f".format(100.0 * to.d(to.fixes) / oChanged)}%); named=$named")
        say("GATE: L1=$l1 L2=$l2 L3=$l3 L4=$l4 → " + if (l1 && l2 && l3 && l4) "PASS" else "FAIL")
        File(outDir, "rows5-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    // ── Round 6 (§16): the unfinished-word cost ρ ────────────────────────────────────────────

    /**
     * What the shipped default does with one input and what rule ρ needs to replay it: the
     * shipped output, whether ρ can apply at all (the channel path produced a correction whose
     * winner does not extend the typed word, and the typed word has a completion), the winner's
     * total cost and the completion's rank steps.
     */
    private class Completion(val typed: String, val base: String, val eligible: Boolean,
                             val winnerCost: Float, val completionRank: Float)

    /** Shipped output plus ρ's inputs for [t], from production with a probe attached. */
    private fun completionProbe(lang: Lang, t: String): Completion {
        var seen: WordPredictor.ChannelProbe? = null
        val base = lang.predictor.autoCorrect(t, BASE6) { seen = it }
        val p = seen
        if (p == null || base == t) return Completion(t, base, false, 0f, 0f)
        val w = AutocorrectTuning.DEFAULT.channel
        val costs = FloatArray(p.candidates.size) { ChannelEditCost.cost(p.lowerTypedWord, p.candidates[it].word, w) }
        val i = ChannelRanker.pick(p.candidates, costs, p.maxFrequency, w.frequencyWeight, w.aliasBonus)
        if (i < 0) return Completion(t, base, false, 0f, 0f)
        val winner = p.candidates[i]
        val completion = lang.predictor.bestCompletionFrequency(p.lowerTypedWord)
        val eligible = completion > 0 && !winner.word.startsWith(p.lowerTypedWord)
        return Completion(t, base, eligible,
            ChannelRanker.totalCost(costs[i], winner.frequency, p.maxFrequency, w.frequencyWeight, winner.isAlias, w.aliasBonus),
            ChannelRanker.rankSteps(completion, p.maxFrequency))
    }

    /** Rule ρ's output for [c] under (ρ, minimum length): the replica of `WordPredictor.channelDecide`'s ρ step. */
    private fun decide6(c: Completion, rho: Float, minLength: Int): String =
        if (c.eligible && c.typed.length >= minLength &&
            rho + AutocorrectTuning.DEFAULT.channel.frequencyWeight * c.completionRank <= c.winnerCost
        ) c.typed else c.base

    private fun tuning6(rho: Float, minLength: Int) = BASE6.copy(
        channel = BASE6.channel.copy(completionCost = rho, completionMinLength = minLength))

    /**
     * T-trunc (§16.2): the unfinished words. Every prefix of 3+ letters, one letter or more short,
     * of each top-10,000 word (letters only, not an alias key) that is itself neither a
     * dictionary word nor an alias key; its target is the most frequent top-10,000 word it
     * starts. Split by the TARGET's hash, so a target's prefixes never straddle DEV and TEST.
     */
    private fun tTrunc(dev: Boolean): List<P> {
        val top = en.dict.entries.filter { it.key.length >= 4 && letters.matches(it.key) && it.key !in en.aliases }
            .sortedByDescending { it.value }.take(10_000).map { it.key }
        val target = LinkedHashMap<String, String>()
        for (w in top) for (n in 3 until w.length) {
            val p = w.substring(0, n)
            if (!en.dict.containsKey(p) && p !in en.aliases && p !in target) target[p] = w
        }
        return target.entries.filter { isDevWord(it.value) == dev }.map { P(it.key, it.value) }
    }

    /** Production shipped output + ρ inputs for every distinct typed word of [pairs] that has a completion. */
    private fun probe6(pairs: List<P>, label: String, say: (String) -> Unit, all: Boolean = false): Map<String, Completion> {
        val typed = pairs.map { it.typed }.distinct()
        val out = ConcurrentHashMap<String, Completion>()
        val start = System.nanoTime()
        typed.parallelStream().forEach { t ->
            out[t] = if (all || en.predictor.bestCompletionFrequency(t) > 0) completionProbe(en, t)
                else Completion(t, "", false, 0f, 0f)
        }
        say("$label: ${typed.size} distinct inputs, ${out.values.count { it.eligible }} eligible for ρ, " +
            "probed in ${(System.nanoTime() - start) / 1_000_000_000}s")
        return out
    }

    /** Distinct targets broken / fixed by (ρ, L) against the shipped output, plus raw breaks. */
    private fun vs6(pairs: List<P>, probe: Map<String, Completion>, rho: Float, minLength: Int,
                    rows: MutableList<String>? = null, set: String = ""): IntArray {
        val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0
        for (p in pairs) {
            val c = probe.getValue(p.typed)
            if (!c.eligible) continue
            val b = c.base; val n = decide6(c, rho, minLength)
            if (b == n) continue
            val bOk = en.ok(b, p.target); val nOk = en.ok(n, p.target)
            if (!bOk && nOk) fix += p.target
            if (bOk && !nOk) { brk += p.target; brkRaw++ }
            rows?.add("$set\trho=$rho,L=$minLength\t${p.typed}\t${p.target}\t${p.occ}\t$b\t$n\t" +
                (if (bOk && !nOk) "break" else if (!bOk && nOk) "fix" else "kept") + "\t${typoClass(p)}")
        }
        return intArrayOf(fix.size, brk.size, brkRaw)
    }

    /** T-trunc / C-oov "protected": the shipped default changed the word into a non-completion, ρ keeps it. */
    private fun protected6(pairs: List<P>, probe: Map<String, Completion>, rho: Float, minLength: Int): Pair<Int, Int> {
        val targets = HashSet<String>(); var raw = 0
        for (p in pairs) {
            val c = probe.getValue(p.typed)
            if (c.eligible && decide6(c, rho, minLength) == p.typed) { targets += p.target; raw++ }
        }
        return targets.size to raw
    }

    /** Inputs the shipped default rewrites into a word that does not extend them (T-trunc "hijacked"). */
    private fun hijacked6(pairs: List<P>, probe: Map<String, Completion>): Pair<Int, Int> {
        val hit = pairs.filter { probe.getValue(it.typed).eligible }
        return hit.mapTo(HashSet()) { it.target }.size to hit.size
    }

    /** Named cases through FULL production under (ρ, L): §1.5 T5 + `shated`, Hebrew included. */
    private fun namedFailures6(rho: Float, minLength: Int): List<String> {
        val bad = mutableListOf<String>()
        val tuning = tuning6(rho, minLength)
        KeyAdjacency.setLayoutLetters(emptyMap())
        for ((typo, want) in NAMED_EN + (SHATED to "shared")) {
            val out = en.predictor.autoCorrect(typo, tuning)
            if (out != want) bad += "$typo → $out (want $want)"
        }
        KeyAdjacency.setLayoutLetters(heGrid)
        try {
            for ((typo, want) in NAMED_HE) {
                val out = he.predictor.autoCorrect(typo, tuning)
                if (out != want) bad += "$typo → $out (want $want)"
            }
        } finally {
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        return bad
    }

    /** The report cases (§16.1), reported under each configuration, not gated. */
    private fun reportCases6(rho: Float, minLength: Int): String {
        val tuning = tuning6(rho, minLength)
        return REPORT6.joinToString(" ") { "$it→${en.predictor.autoCorrect(it, tuning)}" }
    }

    /** Round 6 DEV (§16.3). */
    private fun devPhase6(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val oov = cOov(dev = true)
        val trunc = tTrunc(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("ROUND 6 DEV. GTC-DEV ${gtc.size}; S-adj-DEV ${adj.size} ($adjWords words); C-oov-DEV ${oov.size}; " +
            "T-trunc-DEV ${trunc.size} (${trunc.map { it.target }.toSet().size} targets)")
        val gProbe = probe6(gtc, "GTC-DEV", say, all = true)
        val aProbe = probe6(adj, "S-adj-DEV", say)
        val oProbe = probe6(oov, "C-oov-DEV", say)
        val tProbe = probe6(trunc, "T-trunc-DEV", say)
        val gRight = gtc.filter { en.ok(gProbe.getValue(it.typed).base, it.target) }.mapTo(HashSet()) { it.target }.size
        val (hijackD, hijackR) = hijacked6(trunc, tProbe)
        say("baseline (shipped): GTC-DEV distinct targets right=$gRight; T-trunc-DEV hijacked=$hijackD targets ($hijackR prefixes)")
        say("report cases, shipped: ${reportCases6(Float.POSITIVE_INFINITY, 3)}")
        var chosen: Pair<Float, Int>? = null
        var best = -1
        for (rho in RHO6) for (minLength in MIN_LENGTH6) {
            val g = vs6(gtc, gProbe, rho, minLength)
            val a = vs6(adj, aProbe, rho, minLength)
            val (tp, tpRaw) = protected6(trunc, tProbe, rho, minLength)
            val (op, _) = protected6(oov, oProbe, rho, minLength)
            val named = namedFailures6(rho, minLength)
            val q1 = g[1] <= 0.005 * gRight
            val q2 = a[1] <= 0.005 * adjWords
            val adm = q1 && q2 && named.isEmpty()
            say("  ρ=$rho L=$minLength: GTC-DEV breaks=${g[1]} (${g[2]} pairs; limit ${"%.1f".format(0.005 * gRight)}); " +
                "S-adj-DEV breaks=${a[1]} (${a[2]} pairs; limit ${"%.1f".format(0.005 * adjWords)}); " +
                "T-trunc-DEV protected=$tp of $hijackD targets ($tpRaw prefixes); C-oov-DEV protected=$op; " +
                "named=$named; ${reportCases6(rho, minLength)} → ${if (adm) "admissible" else "-"}")
            // RHO6 and MIN_LENGTH6 ascend; ties go to the larger ρ, then the larger L: an equal later count replaces.
            if (adm && tp >= best) { chosen = rho to minLength; best = tp }
        }
        val c = chosen
        if (c == null) {
            say("\nDEV choice (§16.3): NONE — ρ stays off, TEST not run")
            return
        }
        say("\nDEV choice (§16.3): ρ=${c.first} L=${c.second}")
        val all = gProbe + aProbe.filterValues { it.eligible } + tProbe + oProbe.filterValues { it.eligible }
        val sample = all.keys.sorted().shuffled(java.util.Random(20261027)).take(2_000)
        val tuning = tuning6(c.first, c.second)
        val mismatches = sample.filter { t ->
            val p = all.getValue(t)
            val base = en.predictor.autoCorrect(t, BASE6)
            (p.base.isNotEmpty() && p.base != base) || (p.eligible && decide6(p, c.first, c.second) != en.predictor.autoCorrect(t, tuning))
        }
        say("  soundness: ${sample.size} inputs, mismatches=${mismatches.size} ${mismatches.take(20)}")
        check(mismatches.isEmpty()) { "replica disagrees with production: run void" }
        say("CK_CH_RHO=${c.first} CK_CH_RHO_MIN_LENGTH=${c.second}")
    }

    /** Round 6 TEST (§16.4): production shipped default against production with (ρ, L); one evaluation. */
    private fun testPhase6(say: (String) -> Unit) {
        val rho = (System.getenv("CK_CH_RHO") ?: error("CK_CH_RHO required")).toFloat()
        val minLength = (System.getenv("CK_CH_RHO_MIN_LENGTH") ?: error("CK_CH_RHO_MIN_LENGTH required")).toInt()
        val tuning = tuning6(rho, minLength)
        say("ROUND 6 TEST, ρ=$rho L=$minLength")
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("TEST")
        val adj = sAdj(dev = false)
        val oov = cOov(dev = false)
        val trunc = tTrunc(dev = false)
        val birkbeck = (readDat("wikipedia.dat") + readDat("aspell.dat") + readDat("missp.dat") + readDat("holbrook-missp.dat")).distinct()
        val adjWords = adj.map { it.target }.toSet().size
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        /** Shipped and candidate output through production for every input that has a completion. */
        fun run(pairs: List<P>, set: String, all: Boolean): Pair<Map<String, String>, Map<String, String>> {
            val inputs = pairs.map { it.typed }.distinct()
            val base = ConcurrentHashMap<String, String>()
            val cand = ConcurrentHashMap<String, String>()
            val start = System.nanoTime()
            inputs.parallelStream().forEach { t ->
                if (all || en.predictor.bestCompletionFrequency(t) > 0) {
                    base[t] = en.predictor.autoCorrect(t, BASE6)
                    cand[t] = en.predictor.autoCorrect(t, tuning)
                }
            }
            say("  $set: ${inputs.size} inputs, ${base.size} run in ${(System.nanoTime() - start) / 1_000_000_000}s")
            return base to cand
        }
        fun outcome(pairs: List<P>, base: Map<String, String>, cand: Map<String, String>, set: String): IntArray {
            val fix = HashSet<String>(); val brk = HashSet<String>(); var brkRaw = 0; var fixRaw = 0
            for (p in pairs) {
                val b = base[p.typed] ?: continue
                val n = cand.getValue(p.typed)
                if (b == n) continue
                val bOk = en.ok(b, p.target); val nOk = en.ok(n, p.target)
                if (!bOk && nOk) { fix += p.target; fixRaw++ }
                if (bOk && !nOk) { brk += p.target; brkRaw++ }
                rows.add("$set\trho=$rho,L=$minLength\t${p.typed}\t${p.target}\t${p.occ}\t$b\t$n\t" +
                    (if (bOk && !nOk) "break" else if (!bOk && nOk) "fix" else "kept") + "\t${typoClass(p)}")
            }
            return intArrayOf(fix.size, fixRaw, brk.size, brkRaw)
        }
        val (gb, gc) = run(gtc, "GTC-TEST", all = true)
        val (ab, ac) = run(adj, "S-adj-TEST", all = false)
        val (ob, oc) = run(oov, "C-oov-TEST", all = false)
        val (tb, tc) = run(trunc, "T-trunc-TEST", all = false)
        val (rb, rc) = run(birkbeck, "R-union", all = false)
        val gRight = gtc.filter { en.ok(gb.getValue(it.typed), it.target) }.mapTo(HashSet()) { it.target }.size
        val g = outcome(gtc, gb, gc, "GTC-TEST")
        val a = outcome(adj, ab, ac, "S-adj-TEST")
        val r = outcome(birkbeck, rb, rc, "R-union")
        outcome(oov, ob, oc, "C-oov-TEST"); outcome(trunc, tb, tc, "T-trunc-TEST")
        val hijacked = trunc.filter { p -> tb[p.typed]?.let { it != p.typed && !it.lowercase().startsWith(p.typed) } == true }
        val protectedT = hijacked.filter { tc.getValue(it.typed) == it.typed }
        val oProtected = oov.filter { p -> ob[p.typed]?.let { it != p.typed } == true && oc.getValue(p.typed) == p.typed }
        val hijackedD = hijacked.mapTo(HashSet()) { it.target }.size
        val protectedD = protectedT.mapTo(HashSet()) { it.target }.size
        val named = namedFailures6(rho, minLength)
        say("GTC-TEST distinct breaks=${g[2]} (${g[3]} pairs) of $gRight right; fixes=${g[0]}")
        say("S-adj-TEST distinct breaks=${a[2]} (${a[3]} pairs) of $adjWords base words")
        say("T-trunc-TEST hijacked by shipped=$hijackedD targets (${hijacked.size} prefixes); protected=$protectedD (${protectedT.size})")
        say("C-oov-TEST protected=${oProtected.size} of ${oov.size}; R-union breaks=${r[2]} (${r[3]}) fixes=${r[0]} (${r[1]})")
        say("named failures=$named; report: ${reportCases6(rho, minLength)}")
        val r1 = g[2] <= 0.005 * gRight
        val r2 = a[2] <= 0.005 * adjWords
        val r3 = protectedD >= 0.25 * hijackedD
        val r4 = named.isEmpty()
        say("GATE: R1=$r1 R2=$r2 R3=$r3 R4=$r4 → " + if (r1 && r2 && r3 && r4) "PASS" else "FAIL")
        File(outDir, "rows6-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /**
     * todo item 16 (descriptive, decides nothing): what `autocorrect_prefix_length` 1 and 2 cost
     * against the shipped 0 under the shipped ranking (channel). Every set here is already SEEN,
     * so this tunes nothing and moves no default; it informs the setting's description. Baseline
     * = [en] (prefix 0); fixes/breaks are the prefix run against it, on GTC DEV+TEST, S-adj and
     * C-oov (each DEV+TEST). Checkpointed per set and prefix like the TEST phases.
     */
    private fun prefixPhase(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val sets = linkedMapOf(
            "GTC" to readGtc("DEV") + readGtc("TEST"),
            "S-adj" to sAdj(dev = true) + sAdj(dev = false),
            "C-oov" to cOov(dev = true) + cOov(dev = false),
        )
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        say("PREFIX-LENGTH (todo 16), shipped tuning, baseline prefix ${Defaults.AUTOCORRECT_PREFIX_LENGTH}")
        for (prefix in listOf(1, 2)) {
            val predictor = newPredictor(enLoaded.dictionary, enLoaded.prefixIndex, enLoaded.aliases,
                shippedConfig().apply { autocorrect_prefix_length = prefix })
            for ((name, pairs) in sets) {
                val inputs = pairs.map { it.typed }.distinct()
                val base = ConcurrentHashMap<String, String>()
                val cand = ConcurrentHashMap<String, String>()
                val checkpoint = File(outDir, "prefix$prefix-$name.tsv")
                val header = "# prefix=$prefix"
                if (checkpoint.isFile && checkpoint.useLines { it.firstOrNull() } == header) {
                    checkpoint.useLines { l -> l.drop(1).forEach { x -> val c = x.split('\t'); base[c[0]] = c[1]; cand[c[0]] = c[2] } }
                    check(base.keys.containsAll(inputs)) { "checkpoint $checkpoint is incomplete" }
                } else {
                    inputs.parallelStream().forEach { t ->
                        base[t] = en.predictor.autoCorrect(t, AutocorrectTuning.DEFAULT)
                        cand[t] = predictor.autoCorrect(t, AutocorrectTuning.DEFAULT)
                    }
                    checkpoint.writeText(header + "\n" + inputs.joinToString("\n") { "$it\t${base[it]}\t${cand[it]}" } + "\n")
                }
                val t = tally(en, pairs, base, cand, rows, name, "prefix=$prefix")
                say("  prefix=$prefix ${t.line(name, pairs.size, pairs.map { it.target }.toSet().size)}")
            }
        }
        File(outDir, "rows-prefix.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /**
     * Diagnostic (DEV data only, never TEST): for each configuration in `CK_CH_CONFIGS`
     * (`;`-separated `s1,g,g2,tau,lambda,gamma`), the named-case failures through production and
     * production outcomes on a seeded 3,000-pair sample of GTC-DEV and of S-adj-DEV, with every
     * break listed. Used to explain a DEV result; it selects nothing.
     */
    private fun inspectPhase(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val cfgs = (System.getenv("CK_CH_CONFIGS") ?: error("CK_CH_CONFIGS required")).split(';').map { c ->
            Cfg.parse(c)
        }
        val rnd = java.util.Random(20261018)
        val gtc = readGtc("DEV").shuffled(rnd).take(3_000)
        val adj = sAdj(dev = true).shuffled(rnd).take(3_000)
        val base = ConcurrentHashMap<String, String>()
        (gtc + adj).map { it.typed }.distinct().parallelStream().forEach { base[it] = en.base(it) }
        say("baseline accuracy: GTC-DEV sample ${gtc.count { en.ok(base.getValue(it.typed), it.target) }}/${gtc.size}, " +
            "S-adj-DEV sample ${adj.count { en.ok(base.getValue(it.typed), it.target) }}/${adj.size}")
        for (cfg in cfgs) {
            val tuning = prodTuning(cfg)
            val cand = ConcurrentHashMap<String, String>()
            (gtc + adj).map { it.typed }.distinct().parallelStream().forEach { cand[it] = en.predictor.autoCorrect(it, tuning) }
            say("\n=== $cfg ===")
            say("named failures (with shated): ${namedFailures(cfg, includeShated = true)}")
            for ((name, pairs) in listOf("GTC-DEV sample" to gtc, "S-adj-DEV sample" to adj)) {
                val t = tally(en, pairs, base, cand, null, name, "")
                say(t.line(name, pairs.size, pairs.map { it.target }.toSet().size))
                say("  breaks (typed → baseline | channel): " + t.breaks.joinToString(", ") {
                    "${it.typed}→${base.getValue(it.typed)}|${cand.getValue(it.typed)}" })
                say("  fixes (first 40): " + t.fixes.take(40).joinToString(", ") {
                    "${it.typed}→${base.getValue(it.typed)}|${cand.getValue(it.typed)}" })
            }
        }
    }

    // ── Neighbour-substitution re-measure (todo 13, 2026-10-25; descriptive, selects nothing) ──

    /**
     * English words the 2026-10-12 neighbour-tiebreak doc names (§1.5 T5 and §2.1): typo → the
     * intended word. The §2.1 GTC examples are DISTANT one-letter misspellings that a
     * "neighbour beats distant" rule broke; the S-adj examples are neighbour slips it fixed.
     */
    private val NB_NAMED_EN = linkedMapOf(
        "compatability" to "compatibility", "releated" to "related", "tipically" to "typically",
        "unter" to "under", "startet" to "started", "seprate" to "separate", "thet" to "that",
        "hava" to "have", "choise" to "choice", "zime" to "time",
        "hac" to "had", "haf" to "had", "har" to "had", "tham" to "than", "dould" to "could",
    )

    /** English neighbour slips of words ranked (from, to] (same construction as [sAdj], no split). */
    private fun sAdjRanks(from: Int, to: Int): List<P> {
        val ranked = en.dict.entries.filter { it.key.length >= 3 && letters.matches(it.key) && it.key !in en.aliases }
            .sortedByDescending { it.value }.map { it.key }
        val out = LinkedHashSet<P>()
        for (w in ranked.subList(from, minOf(to, ranked.size))) for (i in w.indices) for (c in 'a'..'z') {
            if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
            val typo = w.substring(0, i) + c + w.substring(i + 1)
            if (!en.dict.containsKey(typo) && typo !in en.aliases) out += P(typo, w)
        }
        return out.toList()
    }

    /** Positions where [a] and [b] (same length) differ; null when the lengths differ. */
    private fun diffs(a: String, b: String): List<Int>? =
        if (a.length != b.length) null else a.indices.filter { a[it] != b[it] }

    /** [o] is a one-letter substitution of [t] on a non-neighbour key (the item-13 failure). */
    private fun isDistantOneSub(t: String, o: String): Boolean =
        diffs(t, o)?.let { it.size == 1 && !KeyAdjacency.areNeighbors(t[it[0]], o[it[0]]) } == true

    /** [o] is a one-letter substitution of [t] on a neighbour key. */
    private fun isNeighbourOneSub(t: String, o: String): Boolean =
        diffs(t, o)?.let { it.size == 1 && KeyAdjacency.areNeighbors(t[it[0]], o[it[0]]) } == true

    /**
     * Whether [lang]'s dictionary holds a distant one-letter substitution of [t] other than
     * [target]. Without one, no ranking can commit the item-13 failure on [t] (harness
     * pre-filter, exact by construction: the output must be a dictionary word to be one).
     */
    private fun hasDistantOneSub(lang: Lang, t: String, target: String, alphabet: Collection<Char>): Boolean {
        for (i in t.indices) for (c in alphabet) {
            if (c == t[i] || KeyAdjacency.areNeighbors(c, t[i])) continue
            val w = t.substring(0, i) + c + t.substring(i + 1)
            if (w != target && lang.dict.containsKey(w)) return true
        }
        return false
    }

    /** Production outputs (band, shipped default) for [inputs], checkpointed to [name]. */
    private fun bandAndDefault(lang: Lang, inputs: List<String>, name: String, say: (String) -> Unit): Pair<Map<String, String>, Map<String, String>> {
        val band = ConcurrentHashMap<String, String>()
        val dflt = ConcurrentHashMap<String, String>()
        val checkpoint = File(outDir, "nb-$name.tsv")
        val header = "# band|default ${inputs.size}"
        if (checkpoint.isFile && checkpoint.useLines { it.firstOrNull() } == header) {
            checkpoint.useLines { l -> l.drop(1).forEach { x -> val c = x.split('\t'); band[c[0]] = c[1]; dflt[c[0]] = c[2] } }
            check(band.keys.containsAll(inputs)) { "checkpoint $checkpoint is incomplete" }
            say("  $name: ${inputs.size} inputs from checkpoint")
        } else {
            val start = System.nanoTime()
            inputs.parallelStream().forEach { t ->
                band[t] = lang.base(t)
                dflt[t] = lang.predictor.autoCorrect(t, AutocorrectTuning.DEFAULT)
            }
            checkpoint.writeText(header + "\n" + inputs.joinToString("\n") { "$it\t${band[it]}\t${dflt[it]}" } + "\n")
            say("  $name: ${inputs.size} inputs in ${(System.nanoTime() - start) / 1_000_000_000}s")
        }
        return band to dflt
    }

    /**
     * Outcome counts of [pairs] (raw pairs / distinct targets): right, the item-13 failure
     * (a distant one-letter substitution committed), a neighbour one-letter substitution
     * committed (wrong), kept as typed, other wrong.
     */
    private fun nbOutcomes(lang: Lang, pairs: List<P>, out: Map<String, String>): String {
        val buckets = linkedMapOf<String, MutableList<P>>("right" to mutableListOf(), "distant-1sub" to mutableListOf(),
            "neighbour-1sub" to mutableListOf(), "kept" to mutableListOf(), "other" to mutableListOf())
        for (p in pairs) {
            val o = out.getValue(p.typed)
            val k = when {
                lang.ok(o, p.target) -> "right"
                isDistantOneSub(p.typed, o) -> "distant-1sub"
                isNeighbourOneSub(p.typed, o) -> "neighbour-1sub"
                o == p.typed -> "kept"
                else -> "other"
            }
            buckets.getValue(k) += p
        }
        return buckets.entries.joinToString(" ") { (k, l) -> "$k=${l.size} (${l.mapTo(HashSet()) { it.target }.size})" }
    }

    /** Pairs of [pairs] where the output is a distant one-letter substitution, as `typo→out (want)`. */
    private fun nbFailures(lang: Lang, pairs: List<P>, out: Map<String, String>): List<String> =
        pairs.filter { val o = out.getValue(it.typed); !lang.ok(o, it.target) && isDistantOneSub(it.typed, o) }
            .map { "${it.typed}→${out.getValue(it.typed)} (want ${it.target})" }

    /** One named case through production: band output, default output, and the default's top-3 by E + λ·rank. */
    private fun nbNamed(lang: Lang, typo: String, want: String): String {
        val band = lang.base(typo)
        var seen: WordPredictor.ChannelProbe? = null
        val dflt = lang.predictor.autoCorrect(typo, AutocorrectTuning.DEFAULT) { seen = it }
        val w = AutocorrectTuning.DEFAULT.channel
        val top = seen?.let { p ->
            p.candidates.map { c ->
                val e = ChannelEditCost.cost(p.lowerTypedWord, c.word, w)
                Triple(c.word, e, e + w.frequencyWeight * ChannelRanker.rankSteps(c.frequency, p.maxFrequency))
            }.sortedBy { it.third }.take(3).joinToString(", ") { "%s E=%.2f total=%.2f".format(it.first, it.second, it.third) }
        } ?: "(decided before the sweep)"
        val mark = if (lang.ok(dflt, want)) "OK" else "FAIL"
        return "$typo (want $want): band→$band  default→$dflt [$mark]  top: $top"
    }

    /**
     * Todo item 13 re-measure (2026-10-25): does the shipped default ranking (channel) still
     * commit a distant one-letter substitution where a neighbour one was meant? Descriptive
     * only. Sets with a cap are a seeded sample (seed 20261025) of their eligible pairs, to keep
     * one run under ~25 minutes. `CK_NB2_SETS` (comma list, default all): `named`, `gtc`, `he`, `hefresh`, `sadj`,
     * `sadjfresh`. Outputs are checkpointed per set (`nb-<set>.tsv` in `$CK_EVAL_OUT`).
     */
    private fun neighbourPhase(say: (String) -> Unit) {
        val sets = (System.getenv("CK_NB2_SETS") ?: "named,gtc,he,hefresh,sadj,sadjfresh").split(',').map { it.trim() }.toSet()
        val rows = mutableListOf("set\ttyped\ttarget\tocc\tband\tdefault\tclass")
        fun rowsOf(set: String, pairs: List<P>, band: Map<String, String>, dflt: Map<String, String>) {
            for (p in pairs) rows += "$set\t${p.typed}\t${p.target}\t${p.occ}\t${band[p.typed]}\t${dflt[p.typed]}\t${typoClass(p)}"
        }
        fun report(lang: Lang, name: String, pairs: List<P>, band: Map<String, String>, dflt: Map<String, String>) {
            say("  $name band:    ${nbOutcomes(lang, pairs, band)}")
            say("  $name default: ${nbOutcomes(lang, pairs, dflt)}")
            say("  $name default distant-1sub failures (first 40): ${nbFailures(lang, pairs, dflt).take(40)}")
            rowsOf(name, pairs, band, dflt)
        }
        say("NEIGHBOUR-SUBSTITUTION RE-MEASURE (todo 13), default ranking=${AutocorrectTuning.DEFAULT.ranking} " +
            "channel=${AutocorrectTuning.DEFAULT.channel}")

        if ("named" in sets) {
            KeyAdjacency.setLayoutLetters(emptyMap())
            say("named, English (10-12 §1.5 T5 and §2.1):")
            for ((t, w) in NAMED_EN + NB_NAMED_EN) say("  " + nbNamed(en, t, w))
            KeyAdjacency.setLayoutLetters(heGrid)
            say("named, Hebrew (hebr_1_il installed):")
            for ((t, w) in NAMED_HE) say("  " + nbNamed(he, t, w))
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        if ("gtc" in sets) {
            // Seen (DEV and TEST both used by the channel rounds): descriptive only.
            KeyAdjacency.setLayoutLetters(emptyMap())
            val gtc = (readGtc("DEV") + readGtc("TEST")).filter { typoClass(it).startsWith("1-sub-") }
            val (band, dflt) = bandAndDefault(en, gtc.map { it.typed }.distinct(), "gtc-1sub", say)
            for (cls in listOf("1-sub-adjacent", "1-sub-distant")) {
                val ps = gtc.filter { typoClass(it) == cls }
                report(en, "GTC $cls (seen; ${ps.size} pairs, ${ps.map { it.target }.toSet().size} targets)", ps, band, dflt)
            }
        }
        fun neighbourSet(lang: Lang, name: String, pairs: List<P>, alphabet: Collection<Char>, cap: Int?) {
            val elig = pairs.filter { hasDistantOneSub(lang, it.typed, it.target, alphabet) }
            val use = if (cap != null && elig.size > cap) elig.shuffled(java.util.Random(20261025)).take(cap) else elig
            say("$name: ${pairs.size} pairs (${pairs.map { it.target }.toSet().size} words); with a distant 1-sub competitor " +
                "${elig.size} (${elig.map { it.target }.toSet().size})" + if (use !== elig) "; seeded sample ${use.size}" else "")
            val (band, dflt) = bandAndDefault(lang, use.map { it.typed }.distinct(), name.substringBefore(' '), say)
            report(lang, name.substringBefore(' '), use, band, dflt)
        }
        if ("he" in sets) {
            KeyAdjacency.setLayoutLetters(heGrid)
            neighbourSet(he, "HE-adj (seen, ranks 1-500)", heAdj(), heGrid.keys, null)
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        if ("hefresh" in sets) {
            KeyAdjacency.setLayoutLetters(heGrid)
            neighbourSet(he, "HE-adj4 (fresh, ranks 6,001-8,000)", heAdjRanks(6_000, 8_000) { true }, heGrid.keys, 6_000)
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        if ("sadj" in sets) {
            KeyAdjacency.setLayoutLetters(emptyMap())
            neighbourSet(en, "S-adj (seen, DEV+TEST)", sAdj(dev = true) + sAdj(dev = false), ('a'..'z').toList(), 4_000)
        }
        if ("sadjfresh" in sets) {
            KeyAdjacency.setLayoutLetters(emptyMap())
            neighbourSet(en, "S-adj2 (fresh, ranks 10,001-12,000)", sAdjRanks(10_000, 12_000), ('a'..'z').toList(), 6_000)
        }
        File(outDir, "rows-neighbour-${sets.sorted().joinToString("+")}.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    private fun testPhase(say: (String) -> Unit) {
        val spec = (System.getenv("CK_CH_CONFIG") ?: error("CK_CH_CONFIG required")).split(',')
        val cfg = Cfg.parse(spec.joinToString(","))
        val tuning = prodTuning(cfg)
        KeyAdjacency.setLayoutLetters(emptyMap())
        val rows = mutableListOf("set\tconfig\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        val gtc = readGtc("TEST")
        val devTypos = readGtc("DEV").mapTo(HashSet()) { it.typed }
        say("config: $cfg")
        say("GTC-TEST pairs whose typo also occurs in DEV: ${gtc.count { it.typed in devTypos }}")
        val typed = readDat("wikipedia.dat") + readDat("aspell.dat")
        val hand = readDat("missp.dat") + readDat("holbrook-missp.dat")
        val adj = sAdj(dev = false)
        val adjWords = adj.map { it.target }.toSet().size
        val oov = cOov(dev = false)

        /**
         * Production baseline and production rule C for every distinct input (TEST never uses the
         * replica). Each set's outputs are checkpointed to `test-<set>.tsv` the moment they are
         * computed and reused by a rerun of the SAME configuration: a process killed mid-run (the
         * first round-2 TEST run was SIGKILLed by Android's low-memory killer after five sets,
         * before printing anything) loses at most one set and never changes an output.
         */
        fun run(lang: Lang, pairs: List<P>, set: String): Tally {
            val inputs = pairs.map { it.typed }.distinct()
            val base = ConcurrentHashMap<String, String>()
            val cand = ConcurrentHashMap<String, String>()
            val checkpoint = File(outDir, "test-$set.tsv")
            val header = "# ${cfg.spec}"
            if (checkpoint.isFile && checkpoint.useLines { it.firstOrNull() } == header) {
                checkpoint.useLines { lines ->
                    lines.drop(1).forEach { l -> val c = l.split('\t'); base[c[0]] = c[1]; cand[c[0]] = c[2] }
                }
                check(base.keys.containsAll(inputs)) { "checkpoint $checkpoint is incomplete" }
                println("  $set: ${inputs.size} inputs from checkpoint")
            } else {
                val start = System.nanoTime()
                inputs.parallelStream().forEach { t ->
                    base[t] = lang.base(t)
                    cand[t] = lang.predictor.autoCorrect(t, tuning)
                }
                println("  $set: ${inputs.size} inputs in ${(System.nanoTime() - start) / 1_000_000_000}s")
                checkpoint.writeText(header + "\n" + inputs.joinToString("\n") { "$it\t${base[it]}\t${cand[it]}" } + "\n")
            }
            val t = tally(lang, pairs, base, cand, rows, set, cfg.toString())
            say("  ${t.line(set, pairs.size, pairs.map { it.target }.toSet().size)}")
            return t
        }

        val tg = run(en, gtc, "GTC-TEST")
        val tt = run(en, typed, "R-typed")
        val th = run(en, hand, "R-hand")
        val ta = run(en, adj, "S-adj-TEST")
        val to = run(en, oov, "C-oov-TEST")
        val named = namedFailures(cfg, includeShated = cfg.gamma != Float.POSITIVE_INFINITY)

        KeyAdjacency.setLayoutLetters(heGrid)
        val heTally: Tally
        val hePairs: List<P>
        try {
            hePairs = heAdj()
            heTally = run(he, hePairs, "HE-adj")
        } finally {
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        val heWords = hePairs.map { it.target }.toSet().size

        say("\n=== TEST, $cfg ===")
        say(tg.line("GTC-TEST", gtc.size, gtc.map { it.target }.toSet().size))
        say("    ${classes(tg)}")
        say(tt.line("R-typed", typed.size, typed.map { it.target }.toSet().size))
        say(th.line("R-hand", hand.size, hand.map { it.target }.toSet().size))
        val uFix = (tt.fixes + th.fixes).mapTo(HashSet()) { it.target }
        val uBreak = (tt.breaks + th.breaks).mapTo(HashSet()) { it.target }
        say("R-union distinct: fixes=${uFix.size} breaks=${uBreak.size} net=${uFix.size - uBreak.size}")
        say(ta.line("S-adj-TEST", adj.size, adjWords))
        say("C-oov-TEST words=${oov.size} newChanges=${to.breaks.size} (${"%.3f".format(100.0 * to.breaks.size / oov.size)}%) swaps=${to.swaps.size}")
        say(heTally.line("HE-adj", hePairs.size, heWords))
        say("named failures: $named")

        val t1 = tg.netDistinct > 0 && tg.d(tg.breaks) <= 0.5 * tg.d(tg.fixes)
        val t2 = uFix.size - uBreak.size >= 0
        // T3: round 1 (§1.5) absolute distinct ceiling; round 2 (§3.4) breaks ≤ 0.05 × fixes.
        val t3 = ta.netDistinct > 0 && ta.breaks.size <= 0.01 * adj.size &&
            if (ROUND == 1) ta.d(ta.breaks) <= 0.01 * adjWords else ta.d(ta.breaks) <= 0.05 * ta.d(ta.fixes)
        val t4 = to.breaks.size <= 0.005 * oov.size
        val t5 = named.isEmpty()
        val t6 = heTally.d(heTally.breaks) <= 0.01 * heWords && heTally.netDistinct >= 0
        say("GATE: T1=$t1 T2=$t2 T3=$t3 T4=$t4 T5=$t5 T6=$t6 → " +
            if (t1 && t2 && t3 && t4 && t5 && t6) "PASS" else "FAIL")
        File(outDir, "rows-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }
}
