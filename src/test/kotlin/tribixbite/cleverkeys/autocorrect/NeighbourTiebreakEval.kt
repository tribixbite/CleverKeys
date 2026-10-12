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
import tribixbite.cleverkeys.WordPredictor.NeighbourTiebreak
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * On-demand MEASUREMENT (not a regression test) for
 * `docs/eval/2026-10-12-neighbour-substitution-tiebreak.md`: inside the ±0.10 tie band, does a
 * neighbour-key substitution candidate beat a distant-key one regardless of frequency (rule NB,
 * variants N1/NA)? Outputs come from the REAL production sweep through the
 * `autoCorrect(word, margin, variant)` seam; baseline = [NeighbourTiebreak.OFF].
 *
 * Not a `*Test` class: never runs in CI (the corpora are local-only). Run:
 *
 * ```
 * CK_NB_PHASE=dev GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.NeighbourTiebreakEval
 * CK_NB_PHASE=test CK_NB_VARIANT=<N1|NA> GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.NeighbourTiebreakEval
 * ```
 *
 * Inputs: `$CK_MISSPELL_DIR` (default `~/.cache/cleverkeys-test/misspell`, same files as
 * `DroppedDoubleMarginEval`); rows/summary go to `$CK_EVAL_OUT` (default
 * `build/eval/neighbour-tiebreak`). The DEV phase never computes a TEST outcome.
 */
class NeighbourTiebreakEval {

    /** One misspelling → intended-word pair (both lowercase); [occ] = distinct commits (GTC). */
    private data class P(val typed: String, val target: String, val occ: Int = 1)

    /** Outcome tallies in raw pairs, distinct target words and commit occurrences. */
    private class Tally {
        val fixes = mutableListOf<P>()
        val breaks = mutableListOf<P>()
        val swaps = mutableListOf<P>()
        var eligible = 0
        var baseOk = 0
        var candOk = 0
        fun d(list: List<P>) = list.mapTo(HashSet()) { it.target }.size
        fun occ(list: List<P>) = list.sumOf { it.occ }
        val netDistinct get() = d(fixes) - d(breaks)
        fun line(name: String, total: Int, targets: Int): String =
            "%-10s pairs=%d targets=%d eligible=%d (accuracy there %d→%d) fixes=%d (%d; occ %d) breaks=%d (%d; occ %d) swaps=%d (%d) net=%d"
                .format(name, total, targets, eligible, baseOk, candOk, fixes.size, d(fixes), occ(fixes),
                    breaks.size, d(breaks), occ(breaks), swaps.size, d(swaps), netDistinct)
    }

    /** A predictor over one dictionary, with a per-(variant, input) output cache. */
    private class Lang(val predictor: WordPredictor, val dict: Map<String, Int>, val aliases: Map<String, String>) {
        private val cache = ConcurrentHashMap<Pair<NeighbourTiebreak, String>, String>()
        fun out(t: String, v: NeighbourTiebreak): String =
            cache.getOrPut(v to t) { predictor.autoCorrect(t, DD_MARGIN, v) }
        fun ok(out: String, target: String) = out.equals(target, true) || out == aliases[target]

        /** Dictionary words grouped by length, for the [mayFire] pre-filter. */
        private val byLength: Map<Int, List<String>> = dict.keys.groupBy { it.length }

        /**
         * Necessary condition for variant [v] to change the sweep's output on [t]: the
         * dictionary holds two same-length candidates that pass the sweep's gate 1 (≥ 50 %
         * exact positions, ≤ 2 substitutions; exactly 1 for N1), one with only neighbour
         * substitutions and one with a distant substitution. Otherwise the NB rule never
         * compares two participating candidates and the output equals OFF by construction,
         * so the expensive sweep is skipped. Reads the ACTIVE KeyAdjacency layout.
         */
        fun mayFire(t: String, v: NeighbourTiebreak): Boolean {
            if (v == NeighbourTiebreak.OFF) return false
            val cap = if (v == NeighbourTiebreak.N1) 1 else 2
            var nb = false
            var ds = false
            for (w in byLength[t.length] ?: return false) {
                var subs = 0
                var allNb = true
                for (i in t.indices) {
                    if (t[i] == w[i]) continue
                    if (++subs > cap) break
                    if (allNb && !KeyAdjacency.areNeighbors(t[i], w[i])) allNb = false
                }
                if (subs == 0 || subs > cap || (t.length - subs).toFloat() / t.length < 0.5f) continue
                if (allNb) nb = true else ds = true
                if (nb && ds) return true
            }
            return false
        }
    }

    companion object {
        /** The shipped DD-M margin (`DROPPED_DOUBLE_MARGIN_STEPS`); NB is measured on top of it. */
        const val DD_MARGIN = 64

        private lateinit var en: Lang
        private lateinit var he: Lang
        private lateinit var heGrid: Map<Char, Pair<Float, Float>>

        private val corpusDir = File(
            System.getenv("CK_MISSPELL_DIR")
                ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/misspell"
        )
        private val outDir = File(System.getenv("CK_EVAL_OUT") ?: "build/eval/neighbour-tiebreak")

        /** Doc §1.5 T5, English: typo → required output. */
        val NAMED_EN = linkedMapOf(
            "bery" to "very", "grom" to "from", "ghe" to "the", "sed" to "see", "kne" to "one",
            "nany" to "many", "helo" to "hello",
            "donr" to "don't", "thier" to "their", "thsi" to "this", "tge" to "the", "tfe" to "the",
            "quuestion" to "question", "questin" to "question", "teh" to "the",
        )

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
            set("config", shippedConfig())
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

        /** Deterministic split of a string key (S-adj base word): DEV when the FNV-1a hash is even. */
        fun isDevWord(w: String): Boolean {
            var h = 0x811C9DC5.toInt()
            for (c in w) { h = h xor c.code; h *= 0x01000193 }
            return (h and 1) == 0
        }
    }

    // ── Outcomes ──────────────────────────────────────────────────────────────

    /** Computes every input's outputs in parallel (the sweep is read-only), then tallies. */
    private fun tally(lang: Lang, pairs: List<P>, v: NeighbourTiebreak, rows: MutableList<String>?, set: String): Tally {
        val eligible = pairs.map { it.typed }.distinct().parallelStream()
            .filter { lang.mayFire(it, v) }.collect(java.util.stream.Collectors.toSet())
        eligible.parallelStream().forEach { lang.out(it, NeighbourTiebreak.OFF); lang.out(it, v) }
        val t = Tally()
        for (p in pairs) {
            if (p.typed !in eligible) continue
            t.eligible++
            val base = lang.out(p.typed, NeighbourTiebreak.OFF)
            val cand = lang.out(p.typed, v)
            val baseOk = lang.ok(base, p.target)
            val candOk = lang.ok(cand, p.target)
            if (baseOk) t.baseOk++
            if (candOk) t.candOk++
            val kind = when {
                !baseOk && candOk -> { t.fixes += p; "fix" }
                baseOk && !candOk -> { t.breaks += p; "break" }
                !baseOk && !candOk && base != cand -> { t.swaps += p; "swap" }
                else -> continue
            }
            rows?.add("$set\t$v\t${p.typed}\t${p.target}\t${p.occ}\t$base\t$cand\t$kind\t${typoClass(p)}")
        }
        return t
    }

    /** Named cases that do not give their required output under [v] (empty = all hold). */
    private fun namedFailures(v: NeighbourTiebreak): List<String> {
        val bad = mutableListOf<String>()
        KeyAdjacency.setLayoutLetters(emptyMap())
        for ((typo, want) in NAMED_EN) {
            val out = en.out(typo, v)
            if (out != want) bad += "$typo → $out (want $want)"
        }
        KeyAdjacency.setLayoutLetters(heGrid)
        try {
            for ((typo, want) in NAMED_HE) {
                val out = he.predictor.autoCorrect(typo, DD_MARGIN, v)
                if (out != want) bad += "$typo → $out (want $want)"
            }
        } finally {
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        return bad
    }

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

    // ── Phases ────────────────────────────────────────────────────────────────

    @Test
    fun measure() {
        val phase = System.getenv("CK_NB_PHASE") ?: return
        assumeTrue("gtc-pairs.tsv missing in $corpusDir", File(corpusDir, "gtc-pairs.tsv").isFile)
        outDir.mkdirs()
        val report = StringBuilder()
        fun say(s: String) { println(s); report.appendLine(s) }
        when (phase) {
            "dev" -> devPhase(::say)
            "test" -> testPhase(::say)
            else -> error("CK_NB_PHASE must be dev or test")
        }
        File(outDir, "summary-$phase.txt").writeText(report.toString())
    }

    private fun devPhase(say: (String) -> Unit) {
        KeyAdjacency.setLayoutLetters(emptyMap())
        val gtc = readGtc("DEV")
        val adj = sAdj(dev = true)
        val adjWords = adj.map { it.target }.toSet().size
        say("GTC-DEV pairs=${gtc.size} targets=${gtc.map { it.target }.toSet().size}; S-adj-DEV pairs=${adj.size} words=$adjWords")
        say("named failures under OFF (baseline): ${namedFailures(NeighbourTiebreak.OFF)}")
        // Pre-filter soundness: on a fixed sample of inputs it rules out, NA (the wider variant)
        // must equal OFF exactly. Any mismatch voids the run.
        val ruledOut = (gtc + adj).map { it.typed }.distinct().filter { !en.mayFire(it, NeighbourTiebreak.NA) }
        val sample = ruledOut.shuffled(java.util.Random(20261012)).take(400)
        val unsound = sample.filter { en.out(it, NeighbourTiebreak.NA) != en.out(it, NeighbourTiebreak.OFF) }
        say("pre-filter check: ${sample.size} ruled-out inputs, mismatches=${unsound.size} $unsound")
        check(unsound.isEmpty()) { "mayFire pre-filter is unsound" }
        val rows = mutableListOf("set\tvariant\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        var best: NeighbourTiebreak? = null
        var bestNet = Int.MIN_VALUE
        for (v in listOf(NeighbourTiebreak.N1, NeighbourTiebreak.NA)) {
            val tg = tally(en, gtc, v, rows, "GTC-DEV")
            val ta = tally(en, adj, v, rows, "S-adj-DEV")
            val named = namedFailures(v)
            val c1 = tg.netDistinct > 0 && tg.d(tg.breaks) <= 0.25 * tg.d(tg.fixes)
            val c2 = ta.netDistinct > 0 && ta.breaks.size <= 0.008 * adj.size && ta.d(ta.breaks) <= 0.008 * adjWords
            val c3 = named.isEmpty()
            val pass = c1 && c2 && c3
            say("$v: ${tg.line("GTC-DEV", gtc.size, 0)}")
            say("    ${classes(tg)}")
            say("    ${ta.line("S-adj-DEV", adj.size, adjWords)}")
            say("    named failures: $named")
            say("    C1=$c1 C2=$c2 C3=$c3 → ${if (pass) "admissible" else "-"}")
            if (pass && tg.netDistinct > bestNet) { best = v; bestNet = tg.netDistinct }
        }
        File(outDir, "rows-dev.tsv").writeText(rows.joinToString("\n") + "\n")
        say("\nDEV choice (doc §1.4): ${best ?: "NONE — no admissible variant, nothing ships"}")
    }

    private fun testPhase(say: (String) -> Unit) {
        val v = NeighbourTiebreak.valueOf(System.getenv("CK_NB_VARIANT") ?: error("CK_NB_VARIANT required"))
        KeyAdjacency.setLayoutLetters(emptyMap())
        val rows = mutableListOf("set\tvariant\ttyped\ttarget\tocc\tbaseline\tcandidate\tkind\tclass")
        val gtc = readGtc("TEST")
        val devTypos = readGtc("DEV").mapTo(HashSet()) { it.typed }
        say("GTC-TEST pairs whose typo also occurs in DEV: ${gtc.count { it.typed in devTypos }}")
        val typed = readDat("wikipedia.dat") + readDat("aspell.dat")
        val hand = readDat("missp.dat") + readDat("holbrook-missp.dat")
        val adj = sAdj(dev = false)
        val adjWords = adj.map { it.target }.toSet().size
        val oov = File(corpusDir, "words_alpha.txt").readLines().asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 3..20 && letters.matches(it) && !en.dict.containsKey(it) && it !in en.aliases }
            .distinct().map { P(it, it) }.toList()

        val tg = tally(en, gtc, v, rows, "GTC-TEST")
        val tt = tally(en, typed, v, rows, "R-typed")
        val th = tally(en, hand, v, rows, "R-hand")
        val ta = tally(en, adj, v, rows, "S-adj-TEST")
        val to = tally(en, oov, v, rows, "C-oov")
        val named = namedFailures(v)

        KeyAdjacency.setLayoutLetters(heGrid)
        val heTally: Tally
        val hePairs: List<P>
        try {
            hePairs = heAdj()
            heTally = tally(he, hePairs, v, rows, "HE-adj")
        } finally {
            KeyAdjacency.setLayoutLetters(emptyMap())
        }
        val heWords = hePairs.map { it.target }.toSet().size

        say("\n=== TEST, variant $v ===")
        say(tg.line("GTC-TEST", gtc.size, gtc.map { it.target }.toSet().size))
        say("    ${classes(tg)}")
        say(tt.line("R-typed", typed.size, typed.map { it.target }.toSet().size))
        say(th.line("R-hand", hand.size, hand.map { it.target }.toSet().size))
        val uFix = (tt.fixes + th.fixes).mapTo(HashSet()) { it.target }
        val uBreak = (tt.breaks + th.breaks).mapTo(HashSet()) { it.target }
        say("R-union distinct: fixes=${uFix.size} breaks=${uBreak.size} net=${uFix.size - uBreak.size}")
        say(ta.line("S-adj-TEST", adj.size, adjWords))
        say("C-oov words=${oov.size} newChanges=${to.breaks.size} (${"%.3f".format(100.0 * to.breaks.size / oov.size)}%) swaps=${to.swaps.size}")
        say(heTally.line("HE-adj", hePairs.size, heWords))
        say("named failures: $named")

        val t1 = tg.netDistinct > 0 && tg.d(tg.breaks) <= 0.25 * tg.d(tg.fixes)
        val t2 = uFix.size - uBreak.size >= 0
        val t3 = ta.netDistinct > 0 && ta.breaks.size <= 0.01 * adj.size && ta.d(ta.breaks) <= 0.01 * adjWords
        val t4 = to.breaks.size <= 0.005 * oov.size
        val t5 = named.isEmpty()
        val t6 = heTally.d(heTally.breaks) <= 0.01 * heWords && heTally.netDistinct >= 0
        say("GATE $v: T1=$t1 T2=$t2 T3=$t3 T4=$t4 T5=$t5 T6=$t6 → " +
            if (t1 && t2 && t3 && t4 && t5 && t6) "PASS" else "FAIL")
        File(outDir, "rows-test.tsv").writeText(rows.joinToString("\n") + "\n")
    }
}
