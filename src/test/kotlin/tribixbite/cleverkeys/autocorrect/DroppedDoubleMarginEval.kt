package tribixbite.cleverkeys.autocorrect

import android.os.Trace
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * On-demand MEASUREMENT (not a regression test) for
 * `docs/eval/2026-10-11-dropped-double-margin.md`: the frequency-MARGIN variant of the
 * dropped-doubled-letter rule. The unconditional rule (`DroppedDoubleAutocorrectEval`, doc
 * `2026-10-11-dropped-double-autocorrect.md`) hijacked one-key slips of common words
 * (`bery` → berry, not very); here the inserted word D only replaces the word W the sweep
 * would commit when `freq(D) ≥ freq(W) − M · maxFreq / 255`, i.e. D may be at most M byte-rank
 * steps rarer than W. One parameter, M, tuned on DEV only.
 *
 * Not a `*Test` class: never runs in CI (the corpora are local-only, never committed). Run:
 *
 * ```
 * CK_DD_PHASE=dev  GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.DroppedDoubleMarginEval
 * CK_DD_PHASE=test CK_DD_MARGIN=<M> GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.DroppedDoubleMarginEval
 * ```
 *
 * DEV = GitHub Typo Corpus pairs from even-hash commits (`scripts/gtc_extract_pairs.py`) +
 * the S-adj adjacent-slip control on even-hash base words. TEST = odd-hash GTC + odd-hash
 * S-adj, plus the untouched Birkbeck sets and the C-oov valid-word control. The DEV phase
 * never computes a TEST outcome. Inputs live in `$CK_MISSPELL_DIR` (default
 * `~/.cache/cleverkeys-test/misspell`); rows go to `$CK_EVAL_OUT` (default
 * `build/eval/dropped-double-margin`).
 *
 * The rule is SIMULATED exactly (doc §1.3). Before implementing, `CK_DD_DUMP=<M>` writes the
 * simulated output of every eligible input; afterwards `CK_DD_VERIFY=<M> CK_DD_EXPECTED=<dump>`
 * checks production against that dump ([productionMatchesSimulation]).
 */
class DroppedDoubleMarginEval {

    /** One misspelling → intended-word pair (both lowercase); [occ] = distinct commits (GTC). */
    private data class P(val typed: String, val target: String, val occ: Int = 1)

    /** Per-input facts that every margin value is evaluated from (computed once). */
    private class Facts(
        val d: String?,         // insertion target (null → rule cannot fire)
        val dFreq: Int,
        val wOut: String?,      // what the sweep would commit ignoring the morphology freeze; null = nothing
        val wFreq: Int,
    )

    /** Outcome tallies in raw pairs, distinct target words and commit occurrences. */
    private class Tally {
        val fixes = mutableListOf<P>()
        val breaks = mutableListOf<P>()
        val swaps = mutableListOf<P>()
        var eligible = 0
        fun d(list: List<P>) = list.mapTo(HashSet()) { it.target }.size
        fun occ(list: List<P>) = list.sumOf { it.occ }
        val netDistinct get() = d(fixes) - d(breaks)
        fun line(name: String, total: Int, targets: Int): String =
            "%-9s pairs=%d targets=%d eligible=%d fixes=%d (%d; occ %d) breaks=%d (%d; occ %d) swaps=%d (%d) net=%d"
                .format(name, total, targets, eligible, fixes.size, d(fixes), occ(fixes),
                    breaks.size, d(breaks), occ(breaks), swaps.size, d(swaps), netDistinct)
    }

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config
        private lateinit var dict: HashMap<String, Int>
        private lateinit var aliases: Map<String, String>
        private lateinit var aliasValueToKeys: Map<String, List<String>>
        private var maxFreq = 0
        private var floor = 0

        /** When true, the mocked Morphology reports no stems, so autoCorrect skips the freeze. */
        @Volatile private var bypassMorphology = false

        private val corpusDir = File(
            System.getenv("CK_MISSPELL_DIR")
                ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/misspell"
        )
        private val outDir = File(System.getenv("CK_EVAL_OUT") ?: "build/eval/dropped-double-margin")

        /** The six named hijack cases of the previous eval and the word each must stay. */
        val HIJACK = linkedMapOf(
            "bery" to "very", "grom" to "from", "ghe" to "the",
            "sed" to "see", "kne" to "one", "nany" to "many",
        )

        /** Pre-registered DEV grid (doc §1.4). NEVER = only when the sweep commits nothing; ALWAYS = old variant A. */
        const val NEVER = Int.MIN_VALUE
        const val ALWAYS = Int.MAX_VALUE
        val GRID: List<Int> = listOf(NEVER) + (-20..60 step 2).toList() + ALWAYS

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.d(any(), any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            mockkStatic(Trace::class)
            every { Trace.beginSection(any()) } just runs
            every { Trace.endSection() } just runs
            mockkObject(Morphology)
            every { Morphology.inflectionStems(any()) } answers {
                if (bypassMorphology) emptyList() else callOriginal()
            }
            KeyAdjacency.resetLayout()

            val gson = Gson()
            val mapType = object : TypeToken<Map<String, Double>>() {}.type
            val stringMapType = object : TypeToken<Map<String, String>>() {}.type
            val rawDict: Map<String, Double> = File("src/main/assets/dictionaries/en_enhanced.json")
                .reader().use { gson.fromJson(it, mapType) }
            dict = HashMap(rawDict.size * 2)
            for ((word, byteFreq) in rawDict) dict[word] = 1_000_000 - (255 - byteFreq.toInt()) * 3900

            // Contraction aliases exactly as AutoCorrectEndToEndTest / loadContractionKeysAsync.
            val contractions: Map<String, String> = File("src/main/assets/dictionaries/contractions_en.json")
                .reader().use { gson.fromJson(it, stringMapType) }
            val realWordBases = readCompanionSet("REAL_WORD_CONTRACTION_BASES")
            val a = mutableMapOf<String, String>()
            for ((bareRaw, apoRaw) in contractions) {
                val bare = bareRaw.lowercase()
                val apo = apoRaw.lowercase()
                if (bare in realWordBases) continue
                a[bare] = apo
                dict[bare] = dict[apo] ?: dict[bare] ?: 5000
            }
            aliases = a
            aliasValueToKeys = a.entries.groupBy({ it.value }, { it.key })
            maxFreq = dict.values.max()

            config = org.objenesis.ObjenesisStd().newInstance(Config::class.java)
            config.autocorrect_enabled = true
            config.autocorrect_min_word_length = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
            config.autocorrect_char_match_threshold = Defaults.AUTOCORRECT_CHAR_MATCH_THRESHOLD
            config.autocorrect_max_length_diff = Defaults.AUTOCORRECT_MAX_LENGTH_DIFF
            config.autocorrect_confidence_min_frequency = Defaults.AUTOCORRECT_MIN_FREQUENCY
            config.autocorrect_prefix_length = Defaults.AUTOCORRECT_PREFIX_LENGTH
            config.swipe_debug_detailed_logging = false
            config.prediction_frequency_scale = Defaults.PREDICTION_FREQUENCY_SCALE
            config.prediction_context_boost = Defaults.PREDICTION_CONTEXT_BOOST
            config.context_source = Defaults.CONTEXT_SOURCE
            config.personalization_weight = Defaults.PERSONALIZATION_WEIGHT
            config.secondary_prediction_weight = Defaults.SECONDARY_PREDICTION_WEIGHT
            floor = FrequencyFloor.effective(config.autocorrect_confidence_min_frequency, maxFreq)

            predictor = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            setField("dictionary", AtomicReference<MutableMap<String, Int>>(dict))
            val index = HashMap<String, MutableSet<String>>()
            for (w in dict.keys) {
                for (len in 1..minOf(3, w.length)) index.getOrPut(w.substring(0, len)) { HashSet() }.add(w)
            }
            setField("prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(index))
            setField("contractionAliases", aliases)
            setField("customAndUserWords", emptySet<String>())
            setField("disabledWords", mutableSetOf<String>())
            setField("userWordOriginalCase", ConcurrentHashMap<String, String>())
            setField("config", config)
            setField("cachedMaxFreqForSize", -1)
            setField("secondaryIndex", null)
            setField("secondaryLanguageCode", "none")
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            unmockkObject(Morphology)
            unmockkStatic(Log::class)
            unmockkStatic(Trace::class)
        }

        private fun setField(name: String, value: Any?) {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(predictor, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun readCompanionSet(name: String): Set<String> {
            val field = WordPredictor::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as Set<String>
        }

        /** Deterministic split of a string key (S-adj base word): DEV when the FNV-1a hash is even. */
        fun isDevWord(w: String): Boolean {
            var h = 0x811C9DC5.toInt()
            for (c in w) { h = h xor c.code; h *= 0x01000193 }
            return (h and 1) == 0
        }
    }

    // ── Rule simulation (doc §1.1) ────────────────────────────────────────────

    private val letters = Regex("^[a-z]+$")

    /** Most frequent dictionary word made by duplicating one letter of [t] in place, or null. */
    private fun insertionTarget(t: String): String? {
        if (t.length <= config.autocorrect_min_word_length || config.autocorrect_max_length_diff < 1) return null
        var best: String? = null
        var bestFreq = -1
        for (i in t.indices) {
            if (!t[i].isLetter()) continue
            val inserted = StringBuilder(t).insert(i, t[i]).toString()
            val f = dict[inserted] ?: continue
            if (f < floor) continue
            if (f > bestFreq) { best = inserted; bestFreq = f }
        }
        return best
    }

    private fun collapseFires(t: String): Boolean {
        if (t.length <= config.autocorrect_min_word_length || config.autocorrect_max_length_diff < 1) return false
        for (i in 0 until t.length - 1) {
            if (t[i] == t[i + 1] && t[i].isLetter() && dict.containsKey(t.removeRange(i, i + 1))) return true
        }
        return false
    }

    private fun morphologyFreezes(t: String): Boolean =
        Morphology.inflectionStems(t).any { it.length >= 4 && dict.containsKey(it) }

    /** Step 1.45 is reached (before the morphology guard, as variant A of the previous eval). */
    private fun reachesStep(t: String): Boolean =
        t !in aliases && !dict.containsKey(t) && !collapseFires(t)

    /** Dictionary frequency of an autoCorrect output word (alias outputs map back to their key). */
    private fun outputFreq(out: String): Int {
        val lower = out.lowercase()
        dict[lower]?.let { return it }
        return aliasValueToKeys[lower]?.maxOf { dict[it] ?: 0 } ?: 0
    }

    private fun aliasRoute(word: String): String {
        val alias = aliases[word] ?: return word
        return if (alias.startsWith("i'")) alias.replaceFirstChar { it.uppercase() } else alias
    }

    private val baselineCache = HashMap<String, String>()

    /** Production autoCorrect at HEAD (cached; only computed where an outcome can differ). */
    private fun baseline(t: String): String = baselineCache.getOrPut(t) { predictor.autoCorrect(t) }

    private val noRule = Facts(null, 0, null, 0)
    private val factsCache = HashMap<String, Facts>()

    /** Rule facts for [t]; [noRule] when step 1.45 is not reached or has no insertion target. */
    private fun facts(t: String): Facts = factsCache.getOrPut(t) {
        if (!reachesStep(t)) return@getOrPut noRule
        val d = insertionTarget(t) ?: return@getOrPut noRule
        val base = baseline(t)
        // W = what the sweep commits ignoring the morphology freeze (variant-A placement).
        val sweep = if (morphologyFreezes(t)) {
            bypassMorphology = true
            try { predictor.autoCorrect(t) } finally { bypassMorphology = false }
        } else base
        val wOut = if (sweep == t) null else sweep
        Facts(d, dict.getValue(d), wOut, wOut?.let(::outputFreq) ?: 0)
    }

    /** Candidate output under margin [m], or null when the rule does not change the outcome path. */
    private fun ruleOutput(t: String, m: Int): String? {
        val f = facts(t)
        val d = f.d ?: return null
        val dWins = when {
            f.wOut == null -> true
            m == NEVER -> false
            m == ALWAYS -> true
            // Production: freq(D) >= freq(W) − M · maxFreq / 255 (Long math: no overflow).
            else -> f.dFreq.toLong() * 255 >= f.wFreq.toLong() * 255 - m.toLong() * maxFreq
        }
        return if (dWins) aliasRoute(d) else null
    }

    private fun ok(out: String, target: String) = out.equals(target, true) || out == aliases[target]

    private fun tally(pairs: List<P>, m: Int, rows: MutableList<String>?, set: String): Tally {
        val t = Tally()
        for (p in pairs) {
            val cand = ruleOutput(p.typed, m) ?: continue
            t.eligible++
            val base = baseline(p.typed)
            val baseOk = ok(base, p.target)
            val candOk = ok(cand, p.target)
            val kind = when {
                !baseOk && candOk -> { t.fixes += p; "fix" }
                baseOk && !candOk -> { t.breaks += p; "break" }
                !baseOk && !candOk && base != cand -> { t.swaps += p; "swap" }
                else -> "same"
            }
            rows?.add("$set\t$m\t${p.typed}\t${p.target}\t${p.occ}\t$base\t$cand\t$kind")
        }
        return t
    }

    // ── Corpora ───────────────────────────────────────────────────────────────

    /** Doc §1.2 dictionary filter shared by every real-pair set. */
    private fun keep(p: P) = letters.matches(p.typed) && letters.matches(p.target) &&
        p.typed != p.target && dict.containsKey(p.target) && !dict.containsKey(p.typed) && p.typed !in aliases

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

    /** S-adj: one QWERTY-neighbour substitution in each top-10k word, split by base-word hash. */
    private fun sAdj(dev: Boolean): List<P> {
        val top = dict.entries.filter { it.key.length >= 3 && letters.matches(it.key) && it.key !in aliases }
            .sortedByDescending { it.value }.take(10_000).map { it.key }
        val out = LinkedHashSet<P>()
        for (w in top) {
            if (isDevWord(w) != dev) continue
            for (i in w.indices) for (c in 'a'..'z') {
                if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
                val typo = w.substring(0, i) + c + w.substring(i + 1)
                if (!dict.containsKey(typo) && typo !in aliases) out += P(typo, w)
            }
        }
        return out.toList()
    }

    /** Typo class of a (typo, target) pair — descriptive only (doc §2.1). */
    private fun typoClass(p: P): String {
        val a = p.typed
        val b = p.target
        if (b.length == a.length + 1) {
            for (i in 0 until b.length - 1) if (b[i] == b[i + 1] && b.removeRange(i, i + 1) == a) return "dropped-double"
            for (i in b.indices) if (b.removeRange(i, i + 1) == a) return "other-deletion"
            return "multi-edit"
        }
        if (a.length == b.length + 1) {
            for (i in 0 until a.length - 1) if (a[i] == a[i + 1] && a.removeRange(i, i + 1) == b) return "extra-double"
            for (i in a.indices) if (a.removeRange(i, i + 1) == b) return "other-insertion"
            return "multi-edit"
        }
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            if (diff.size == 1) {
                val i = diff[0]
                return if (KeyAdjacency.areNeighbors(a[i], b[i])) "adjacent-sub" else "non-adjacent-sub"
            }
            if (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]) {
                return "transposition"
            }
        }
        return "multi-edit"
    }

    // ── Phases ────────────────────────────────────────────────────────────────

    @Test
    fun measure() {
        val phase = System.getenv("CK_DD_PHASE") ?: return
        assumeTrue("gtc-pairs.tsv missing in $corpusDir", File(corpusDir, "gtc-pairs.tsv").isFile)
        outDir.mkdirs()
        val report = StringBuilder()
        fun say(s: String) { println(s); report.appendLine(s) }
        when (phase) {
            "dev" -> devPhase(::say)
            "test" -> testPhase(::say)
            else -> error("CK_DD_PHASE must be dev or test")
        }
        File(outDir, "summary-$phase.txt").writeText(report.toString())
    }

    private fun describe(name: String, pairs: List<P>, say: (String) -> Unit) {
        say("$name: pairs=${pairs.size} targets=${pairs.map { it.target }.toSet().size} " +
            "typos=${pairs.map { it.typed }.toSet().size} occurrences=${pairs.sumOf { it.occ }}")
        val byClass = pairs.groupBy(::typoClass)
        for ((cls, ps) in byClass.entries.sortedByDescending { it.value.size }) {
            say("  class %-17s pairs=%6d (%5.1f%%) targets=%5d occ=%6d (%5.1f%%)".format(
                cls, ps.size, 100.0 * ps.size / pairs.size, ps.map { it.target }.toSet().size,
                ps.sumOf { it.occ }, 100.0 * ps.sumOf { it.occ } / pairs.sumOf { it.occ }))
        }
    }

    private fun devPhase(say: (String) -> Unit) {
        val gtc = readGtc("DEV")
        describe("GTC-DEV", gtc, say)
        val adj = sAdj(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("S-adj-DEV: pairs=${adj.size} base words=$adjWords")
        val rows = mutableListOf("set\tM\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind")
        val hijackBase = HIJACK.keys.associateWith { predictor.autoCorrect(it) }
        say("hijack baseline: $hijackBase")
        var best: Int? = null
        var bestNet = Int.MIN_VALUE
        for (m in GRID) {
            val tg = tally(gtc, m, null, "GTC-DEV")
            val ta = tally(adj, m, null, "S-adj-DEV")
            val hijackOk = HIJACK.all { (typo, want) -> (ruleOutput(typo, m) ?: hijackBase.getValue(typo)) == want }
            val c1 = tg.netDistinct > 0 && tg.d(tg.breaks) <= 0.10 * tg.d(tg.fixes)
            val c2 = ta.breaks.size <= 0.008 * adj.size && ta.d(ta.breaks) <= 0.008 * adjWords
            val pass = c1 && c2 && hijackOk
            val name = when (m) { NEVER -> "NEVER"; ALWAYS -> "ALWAYS"; else -> m.toString() }
            say("M=%-6s %s | %s | hijackOk=%s helo=%s → %s".format(
                name, tg.line("GTC", gtc.size, 0), ta.line("S-adj", adj.size, adjWords), hijackOk,
                ruleOutput("helo", m) ?: predictor.autoCorrect("helo"), if (pass) "admissible" else "-"))
            if (pass && tg.netDistinct > bestNet) { best = m; bestNet = tg.netDistinct }
        }
        // Rows for the chosen value (or ALWAYS when none), for inspection of DEV only.
        val m = best ?: ALWAYS
        tally(gtc, m, rows, "GTC-DEV"); tally(adj, m, rows, "S-adj-DEV")
        File(outDir, "rows-dev.tsv").writeText(rows.joinToString("\n") + "\n")
        say("\nDEV choice (doc §1.4): ${best ?: "NONE — no admissible M, nothing ships"}")
    }

    private fun testPhase(say: (String) -> Unit) {
        val m = System.getenv("CK_DD_MARGIN")?.toInt() ?: error("CK_DD_MARGIN required for the test phase")
        val rows = mutableListOf("set\tM\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind")
        val gtc = readGtc("TEST")
        describe("GTC-TEST", gtc, say)
        val devTypos = readGtc("DEV").mapTo(HashSet()) { it.typed }
        say("GTC-TEST pairs whose typo also occurs in DEV: ${gtc.count { it.typed in devTypos }}")
        val typed = readDat("wikipedia.dat") + readDat("aspell.dat")
        val hand = readDat("missp.dat") + readDat("holbrook-missp.dat")
        val adj = sAdj(dev = false)
        val adjWords = adj.map { it.target }.toSet().size
        val oov = File(corpusDir, "words_alpha.txt").readLines().asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 3..20 && letters.matches(it) && !dict.containsKey(it) && it !in aliases }
            .distinct().map { P(it, it) }.toList()

        // GTC baseline accuracy on its dropped-double class (context for the fix count).
        val gtcDd = gtc.filter { typoClass(it) == "dropped-double" }
        say("GTC-TEST dropped-double baseline accuracy: ${gtcDd.count { ok(baseline(it.typed), it.target) }}/${gtcDd.size}")

        val tg = tally(gtc, m, rows, "GTC-TEST")
        val tt = tally(typed, m, rows, "R-typed")
        val th = tally(hand, m, rows, "R-hand")
        val ta = tally(adj, m, rows, "S-adj-TEST")
        val to = tally(oov, m, rows, "C-oov")
        say("\n=== TEST, M=$m ===")
        say(tg.line("GTC-TEST", gtc.size, gtc.map { it.target }.toSet().size))
        say(tt.line("R-typed", typed.size, typed.map { it.target }.toSet().size))
        say(th.line("R-hand", hand.size, hand.map { it.target }.toSet().size))
        val uFix = (tt.fixes + th.fixes).mapTo(HashSet()) { it.target }
        val uBreak = (tt.breaks + th.breaks).mapTo(HashSet()) { it.target }
        say("R-union distinct: fixes=${uFix.size} breaks=${uBreak.size}")
        say(ta.line("S-adj", adj.size, adjWords))
        say("C-oov words=${oov.size} eligible=${to.eligible} newChanges=${to.breaks.size} " +
            "(${"%.3f".format(100.0 * to.breaks.size / oov.size)}%) alreadyChangedByBaseline=${to.swaps.size}")
        val hijack = HIJACK.mapValues { (typo, _) -> ruleOutput(typo, m) ?: predictor.autoCorrect(typo) }
        say("hijack outputs: $hijack")
        val helo = ruleOutput("helo", m) ?: predictor.autoCorrect("helo")
        say("helo → $helo")
        for (w in listOf("tomorow", "ocasion", "begining", "adress", "sucess", "occured", "succesful")) {
            say("example $w: baseline=${baseline(w)} candidate=${ruleOutput(w, m) ?: baseline(w)}")
        }

        val t1 = tg.netDistinct > 0 && tg.d(tg.breaks) <= 0.10 * tg.d(tg.fixes)
        val t2 = tt.netDistinct > 0 && th.netDistinct > 0 && uBreak.size <= 0.10 * uFix.size
        val t3 = ta.breaks.size <= 0.01 * adj.size && ta.d(ta.breaks) <= 0.01 * adjWords
        val t4 = to.breaks.size <= 0.005 * oov.size
        val t5 = HIJACK.all { (typo, want) -> hijack[typo] == want }
        say("GATE M=$m: T1(GTC)=$t1 T2(Birkbeck)=$t2 T3(S-adj)=$t3 T4(C-oov)=$t4 T5(hijack)=$t5 → " +
            if (t1 && t2 && t3 && t4 && t5) "PASS" else "FAIL")
        File(outDir, "rows-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }

    /**
     * After implementation: production autoCorrect must equal the simulated rule at margin
     * `CK_DD_VERIFY` on every eligible input of every set (doc §1.3); the eval is void otherwise.
     */
    @Test
    fun productionMatchesSimulation() {
        val m = System.getenv("CK_DD_VERIFY")?.toInt() ?: return
        assumeTrue(File(corpusDir, "gtc-pairs.tsv").isFile)
        // Production now contains the rule, so the expectation comes from a dump made at the
        // pre-implementation commit (dumpSimulation), never from this run's own baseline.
        val inputs = LinkedHashSet<String>()
        (readGtc("DEV") + readGtc("TEST") + readDat("wikipedia.dat") + readDat("aspell.dat") +
            readDat("missp.dat") + readDat("holbrook-missp.dat") + sAdj(true) + sAdj(false)).forEach { inputs += it.typed }
        File(corpusDir, "words_alpha.txt").forEachLine { inputs += it.trim().lowercase() }
        val expected = File(System.getenv("CK_DD_EXPECTED") ?: error("CK_DD_EXPECTED (baseline rows file) required"))
        val want = HashMap<String, String>()
        expected.forEachLine { line -> val c = line.split('\t'); if (c.size == 2) want[c[0]] = c[1] }
        var checked = 0
        val mismatches = mutableListOf<String>()
        for (t in inputs) {
            val w = want[t] ?: continue
            checked++
            val actual = predictor.autoCorrect(t)
            if (actual != w) mismatches += "$t: production=$actual simulated=$w"
        }
        println("productionMatchesSimulation(M=$m): checked=$checked mismatches=${mismatches.size}")
        mismatches.take(50).forEach(::println)
        assertTrue("production diverges from the simulated rule", mismatches.isEmpty() && checked > 0)
    }

    /**
     * Before implementation: write the simulated output for every input at margin `CK_DD_DUMP`
     * (`typed\toutput`), the expectation file [productionMatchesSimulation] later checks against.
     */
    @Test
    fun dumpSimulation() {
        val m = System.getenv("CK_DD_DUMP")?.toInt() ?: return
        assumeTrue(File(corpusDir, "gtc-pairs.tsv").isFile)
        val inputs = LinkedHashSet<String>()
        (readGtc("DEV") + readGtc("TEST") + readDat("wikipedia.dat") + readDat("aspell.dat") +
            readDat("missp.dat") + readDat("holbrook-missp.dat") + sAdj(true) + sAdj(false)).forEach { inputs += it.typed }
        File(corpusDir, "words_alpha.txt").forEachLine { inputs += it.trim().lowercase() }
        outDir.mkdirs()
        val out = StringBuilder()
        var n = 0
        for (t in inputs) {
            if (!letters.matches(t)) continue
            // Every input that reaches step 1.45 with an insertion target: the rule decides
            // between D and the unchanged baseline path there; elsewhere production is untouched.
            if (facts(t).d == null) continue
            val sim = ruleOutput(t, m) ?: baseline(t)
            out.append(t).append('\t').append(sim).append('\n'); n++
        }
        File(outDir, "simulated-M$m.tsv").writeText(out.toString())
        println("dumpSimulation(M=$m): $n rule-decided inputs written")
    }
}
