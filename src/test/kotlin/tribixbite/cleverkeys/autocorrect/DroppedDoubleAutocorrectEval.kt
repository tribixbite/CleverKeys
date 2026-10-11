package tribixbite.cleverkeys.autocorrect

import android.os.Trace
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.AfterClass
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
 * `docs/eval/2026-10-11-dropped-double-autocorrect.md`: does a "dropped doubled letter"
 * insertion rule (`helo` → `hello`, `tomorow` → `tomorrow`) help real misspellings without
 * stealing adjacent-key slips (`helo` meant as `help`) or valid out-of-dictionary words?
 *
 * Deliberately named without a `Test` suffix: `TestRunnerListDriftTest` only tracks `*Test`
 * classes, and this must NOT run in CI (the corpora are local-only, never committed). Run:
 *
 * ```
 * GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests \
 *     -PtestClass=autocorrect.DroppedDoubleAutocorrectEval
 * ```
 *
 * Corpora are read from `$CK_MISSPELL_DIR` (default `~/.cache/cleverkeys-test/misspell`):
 * Birkbeck/Mitton `wikipedia.dat`, `aspell.dat`, `missp.dat`, `holbrook-missp.dat` and dwyl
 * `words_alpha.txt`. Absent corpora skip the eval (JUnit assumption), never fail it.
 * Per-item rows go to `$CK_EVAL_OUT` (default `build/eval/dropped-double`).
 *
 * Harness = `AutoCorrectEndToEndTest`'s (real 98k dictionary on the device frequency scale,
 * real contraction aliases, shipped autocorrect defaults) plus a prefix index so the
 * tap-typing bar (`predictWordsWithContext`) can be compared with the space-bar commit.
 *
 * Candidate variants are SIMULATED exactly (see the doc §1.3): the rule can only change an
 * outcome when `autoCorrect` reaches the new step without an earlier return AND the insertion
 * yields a target; everywhere else candidate == baseline by construction. When the rule is
 * implemented in production, [productionMatchesSimulation] checks the claim directly.
 */
class DroppedDoubleAutocorrectEval {

    /** One misspelling → intended-word pair from a corpus (both lowercase). */
    private data class Pair2(val typed: String, val target: String)

    /** Outcome tallies for one (set, variant), in raw pairs and distinct target words. */
    private class Tally {
        val fixes = mutableListOf<Pair2>()
        val breaks = mutableListOf<Pair2>()
        val swaps = mutableListOf<Pair2>()
        var eligible = 0
        val eligibleTargets = HashSet<String>()

        fun distinct(list: List<Pair2>) = list.mapTo(HashSet()) { it.target }.size

        fun line(name: String, total: Int, totalTargets: Int): String =
            "%-10s pairs=%d (targets=%d) eligible=%d (%d) fixes=%d (%d) breaks=%d (%d) swaps=%d (%d) netDistinct=%d"
                .format(
                    name, total, totalTargets, eligible, eligibleTargets.size,
                    fixes.size, distinct(fixes), breaks.size, distinct(breaks),
                    swaps.size, distinct(swaps), distinct(fixes) - distinct(breaks)
                )
    }

    companion object {
        private lateinit var predictor: WordPredictor
        private lateinit var config: Config
        private lateinit var dict: HashMap<String, Int>
        private lateinit var aliases: Map<String, String>
        private val corpusDir = File(
            System.getenv("CK_MISSPELL_DIR")
                ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/misspell"
        )
        private val outDir = File(System.getenv("CK_EVAL_OUT") ?: "build/eval/dropped-double")

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
    }

    // ── Simulation of the pre-registered rule (doc §1.1) ─────────────────────

    private val letters = Regex("^[a-z]+$")

    /** Most frequent dictionary word made by duplicating one letter of [t] in place, or null. */
    private fun insertionTarget(t: String): String? {
        if (t.length <= config.autocorrect_min_word_length || config.autocorrect_max_length_diff < 1) return null
        val floor = FrequencyFloor.effective(config.autocorrect_confidence_min_frequency, dict.values.max())
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

    /** True when production step 1.4 (doubled-letter collapse) would return for [t]. */
    private fun collapseFires(t: String): Boolean {
        if (t.length <= config.autocorrect_min_word_length || config.autocorrect_max_length_diff < 1) return false
        for (i in 0 until t.length - 1) {
            if (t[i] == t[i + 1] && t[i].isLetter() && dict.containsKey(t.removeRange(i, i + 1))) return true
        }
        return false
    }

    private fun morphologyFreezes(t: String): Boolean =
        Morphology.inflectionStems(t).any { it.length >= 4 && dict.containsKey(it) }

    /** Does autoCorrect reach the new step for [t]? (No custom/disabled words in this harness.) */
    private fun reachesStep(t: String, post: Boolean): Boolean {
        if (t in aliases) return false          // step 0
        if (dict.containsKey(t)) return false   // step 1
        if (collapseFires(t)) return false      // step 1.4
        if (post && morphologyFreezes(t)) return false
        return true
    }

    /** Candidate output for lowercase [t], or null when the rule does not fire (→ baseline). */
    private fun ruleOutput(t: String, post: Boolean): String? {
        if (!reachesStep(t, post)) return null
        val target = insertionTarget(t) ?: return null
        val alias = aliases[target] ?: return target
        return if (alias.startsWith("i'")) alias.replaceFirstChar { it.uppercase() } else alias
    }

    private val baselineCache = HashMap<String, String>()
    private fun baseline(t: String): String = baselineCache.getOrPut(t) { predictor.autoCorrect(t) }

    // ── Corpora ──────────────────────────────────────────────────────────────

    /** Mitton `.dat`: `$target` lines followed by misspelling lines (holbrook adds a count). */
    private fun readDat(name: String): List<Pair2> {
        val f = File(corpusDir, name)
        val out = LinkedHashSet<Pair2>()
        var target: String? = null
        f.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("$")) { target = line.substring(1).lowercase(); return@forEachLine }
            val tgt = target ?: return@forEachLine
            out += Pair2(line.split(Regex("\\s+"))[0].lowercase(), tgt)
        }
        return out.toList()
    }

    /** Doc §1.2 pair filter; returns kept pairs and the count of excluded real-word errors. */
    private fun filterPairs(pairs: List<Pair2>): kotlin.Pair<List<Pair2>, Int> {
        var realWord = 0
        val kept = pairs.filter { p ->
            if (!letters.matches(p.typed) || !letters.matches(p.target)) return@filter false
            if (p.typed == p.target || !dict.containsKey(p.target)) return@filter false
            if (dict.containsKey(p.typed) || p.typed in aliases) { realWord++; return@filter false }
            true
        }
        return kept to realWord
    }

    private fun isDroppedDouble(p: Pair2): Boolean =
        p.target.length == p.typed.length + 1 &&
            (0 until p.target.length - 1).any { i ->
                p.target[i] == p.target[i + 1] && p.target.removeRange(i, i + 1) == p.typed
            }

    private fun tally(pairs: List<Pair2>, post: Boolean, rows: MutableList<String>, set: String): Tally {
        val t = Tally()
        for (p in pairs) {
            val cand = ruleOutput(p.typed, post) ?: continue
            t.eligible++
            t.eligibleTargets += p.target
            val base = baseline(p.typed)
            val baseOk = base.equals(p.target, ignoreCase = true) || base == aliases[p.target]
            val candOk = cand.equals(p.target, ignoreCase = true) || cand == aliases[p.target]
            val kind = when {
                !baseOk && candOk -> { t.fixes += p; "fix" }
                baseOk && !candOk -> { t.breaks += p; "break" }
                !baseOk && !candOk && base != cand -> { t.swaps += p; "swap" }
                else -> "same"
            }
            rows += "$set\t${if (post) "A-post" else "A"}\t${p.typed}\t${p.target}\t$base\t$cand\t$kind"
        }
        return t
    }

    @Test
    fun measure() {
        assumeTrue("corpora not present in $corpusDir", File(corpusDir, "wikipedia.dat").isFile)
        outDir.mkdirs()
        val rows = mutableListOf("set\tvariant\ttyped\ttarget\tbaseline\tcandidate\tkind")
        val report = StringBuilder()
        fun say(s: String) { println(s); report.appendLine(s) }

        val (typed, typedRw) = filterPairs(readDat("wikipedia.dat") + readDat("aspell.dat"))
        val (hand, handRw) = filterPairs(readDat("missp.dat") + readDat("holbrook-missp.dat"))
        say("R-typed: ${typed.size} pairs, ${typed.map { it.target }.toSet().size} targets, " +
            "${typed.map { it.typed }.toSet().size} distinct misspellings; real-word errors excluded=$typedRw; " +
            "dropped-double pairs=${typed.count(::isDroppedDouble)}")
        say("R-hand:  ${hand.size} pairs, ${hand.map { it.target }.toSet().size} targets, " +
            "${hand.map { it.typed }.toSet().size} distinct misspellings; real-word errors excluded=$handRw; " +
            "dropped-double pairs=${hand.count(::isDroppedDouble)}")

        // Baseline accuracy on all of R-typed (full autoCorrect on every pair).
        val typedBaseOk = typed.count { baseline(it.typed).equals(it.target, true) || baseline(it.typed) == aliases[it.target] }
        val typedDd = typed.filter(::isDroppedDouble)
        val typedDdBaseOk = typedDd.count { baseline(it.typed).equals(it.target, true) }
        say("R-typed baseline accuracy: $typedBaseOk/${typed.size}; dropped-double subset $typedDdBaseOk/${typedDd.size}")

        // C-oov: valid English words absent from the dictionary.
        val oov = File(corpusDir, "words_alpha.txt").readLines().asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 3..20 && letters.matches(it) && !dict.containsKey(it) && it !in aliases }
            .distinct().map { Pair2(it, it) }.toList()

        // S-adj: one QWERTY-neighbour substitution in each of the top-10k words (len >= 3).
        val top = dict.entries.filter { it.key.length >= 3 && letters.matches(it.key) && it.key !in aliases }
            .sortedByDescending { it.value }.take(10_000).map { it.key }
        val sAdj = LinkedHashSet<Pair2>()
        for (w in top) for (i in w.indices) for (c in 'a'..'z') {
            if (c == w[i] || !KeyAdjacency.areNeighbors(c, w[i])) continue
            val typo = w.substring(0, i) + c + w.substring(i + 1)
            if (!dict.containsKey(typo) && typo !in aliases) sAdj += Pair2(typo, w)
        }
        // S-dd: drop one letter of a doubled pair in the top-10k words that have one.
        val dd = dict.entries.filter { e -> letters.matches(e.key) && e.key.zipWithNext().any { it.first == it.second } }
            .sortedByDescending { it.value }.take(10_000).map { it.key }
        val sDd = LinkedHashSet<Pair2>()
        for (w in dd) for (i in 0 until w.length - 1) {
            if (w[i] != w[i + 1]) continue
            val typo = w.removeRange(i, i + 1)
            if (!dict.containsKey(typo) && typo !in aliases) sDd += Pair2(typo, w)
        }

        for (post in listOf(false, true)) {
            val v = if (post) "A-post" else "A"
            say("\n=== Variant $v ===")
            val tT = tally(typed, post, rows, "R-typed")
            val tH = tally(hand, post, rows, "R-hand")
            say(tT.line("R-typed", typed.size, typed.map { it.target }.toSet().size))
            say(tH.line("R-hand", hand.size, hand.map { it.target }.toSet().size))
            val unionFix = (tT.fixes + tH.fixes).mapTo(HashSet()) { it.target }
            val unionBreak = (tT.breaks + tH.breaks).mapTo(HashSet()) { it.target }
            say("R-union distinct: fixes=${unionFix.size} breaks=${unionBreak.size} net=${unionFix.size - unionBreak.size}")
            val tA = tally(sAdj.toList(), post, rows, "S-adj")
            say(tA.line("S-adj", sAdj.size, sAdj.map { it.target }.toSet().size))
            val tD = tally(sDd.toList(), post, rows, "S-dd")
            say(tD.line("S-dd", sDd.size, sDd.map { it.target }.toSet().size))
            val tO = tally(oov, post, rows, "C-oov")
            // For C-oov the target is the word itself: "break" == new change of a word the
            // baseline left alone; "swap" == baseline already changed it, differently.
            say("C-oov      words=${oov.size} eligible=${tO.eligible} newChanges=${tO.breaks.size} " +
                "(${"%.3f".format(100.0 * tO.breaks.size / oov.size)}%) alreadyChangedByBaseline=${tO.swaps.size}")

            val g1 = tT.distinct(tT.fixes) - tT.distinct(tT.breaks) > 0 && tH.distinct(tH.fixes) - tH.distinct(tH.breaks) > 0
            val g2 = unionBreak.size <= 0.10 * unionFix.size
            val g3 = tA.breaks.size <= 0.01 * sAdj.size &&
                tA.distinct(tA.breaks) <= 0.01 * sAdj.map { it.target }.toSet().size
            val g4 = tO.breaks.size <= 0.005 * oov.size
            say("GATE $v: G1=$g1 G2=$g2 G3=$g3 G4=$g4 → ${if (g1 && g2 && g3 && g4) "PASS" else "FAIL"}")
        }

        // Bar agreement on R-typed: space-bar commit vs the tap-typing bar's first entry.
        var barDiffBase = 0; var barDiffA = 0; var barDiffPost = 0; var barTopRight = 0
        for (p in typed) {
            val barTop = predictor.predictWordsWithContext(p.typed, emptyList()).words.firstOrNull() ?: p.typed
            if (barTop.equals(p.target, true)) barTopRight++
            val b = baseline(p.typed)
            if (!b.equals(barTop, true)) barDiffBase++
            if (!(ruleOutput(p.typed, false) ?: b).equals(barTop, true)) barDiffA++
            if (!(ruleOutput(p.typed, true) ?: b).equals(barTop, true)) barDiffPost++
            rows += "BAR\t-\t${p.typed}\t${p.target}\t$b\t$barTop\tbar"
        }
        say("\nBar (R-typed, ${typed.size} pairs): barTop==target $barTopRight; commit≠barTop baseline=$barDiffBase " +
            "A=$barDiffA A-post=$barDiffPost")

        // Named examples from the device finding / maintainer ask.
        for (w in listOf("helo", "tomorow", "ocasion", "begining", "adress", "sucess")) {
            say("example $w: baseline=${baseline(w)} A=${ruleOutput(w, false) ?: baseline(w)} " +
                "A-post=${ruleOutput(w, true) ?: baseline(w)}")
        }

        File(outDir, "rows.tsv").writeText(rows.joinToString("\n") + "\n")
        File(outDir, "summary.txt").writeText(report.toString())
    }

    /**
     * After implementation: production autoCorrect must equal the simulated variant chosen
     * ([CK_EVAL_VARIANT] = `A` or `A-post`) on every eligible R-typed / R-hand / S-adj / C-oov
     * input — the eval is void otherwise (doc §1.3). Skipped until the variable is set.
     */
    @Test
    fun productionMatchesSimulation() {
        val variant = System.getenv("CK_EVAL_VARIANT") ?: return
        assumeTrue(File(corpusDir, "wikipedia.dat").isFile)
        val post = variant == "A-post"
        val inputs = LinkedHashSet<String>()
        (readDat("wikipedia.dat") + readDat("aspell.dat") + readDat("missp.dat") + readDat("holbrook-missp.dat"))
            .forEach { inputs += it.typed }
        File(corpusDir, "words_alpha.txt").forEachLine { inputs += it.trim().lowercase() }
        var checked = 0
        val mismatches = mutableListOf<String>()
        for (t in inputs) {
            if (!letters.matches(t)) continue
            val expected = ruleOutput(t, post) ?: continue
            checked++
            val actual = predictor.autoCorrect(t)
            if (actual != expected) mismatches += "$t: production=$actual simulated=$expected"
        }
        println("productionMatchesSimulation($variant): checked=$checked mismatches=${mismatches.size}")
        mismatches.take(50).forEach(::println)
        org.junit.Assert.assertTrue("production diverges from the simulated rule", mismatches.isEmpty())
    }
}
