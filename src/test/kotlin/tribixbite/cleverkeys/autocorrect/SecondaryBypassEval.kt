package tribixbite.cleverkeys.autocorrect

import android.content.Context
import android.content.res.AssetManager
import android.os.Trace
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import tribixbite.cleverkeys.BinaryDictionaryLoader
import tribixbite.cleverkeys.Config
import tribixbite.cleverkeys.Defaults
import tribixbite.cleverkeys.NormalizedPrefixIndex
import tribixbite.cleverkeys.ShippedTapDictionary
import tribixbite.cleverkeys.WordPredictor
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * On-demand MEASUREMENT for `docs/eval/2026-11-26-secondary-bypass-gate.md` (not a `*Test`: the
 * corpora are local only). Autocorrect step 1 keeps a word of the secondary language as typed; the
 * rule under test lets the primary correction E replace it when E is more than M byte-rank steps
 * more common. Run:
 *
 * ```
 * CK_SB_PHASE=dev  GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m scripts/gradle-guard.sh \
 *     runMockTests -PtestClass=autocorrect.SecondaryBypassEval
 * CK_SB_PHASE=test CK_SB_MARGIN=<M> …same…
 * ```
 *
 * The rule is simulated from one no-bypass run per input (E, and `d = bE − bS`) for every M of the
 * grid; the TEST phase also checks production (`autoCorrect(…, secondaryBypassMargin = M)`) against
 * the simulation on every eligible input. Output: stdout and `$CK_EVAL_OUT`
 * (default `build/eval/secondary-bypass`).
 */
class SecondaryBypassEval {

    /** One misspelling → intended word (both lowercase). */
    private data class Pair2(val typo: String, val target: String)

    /** Per-input facts every M is evaluated from: E (no bypass) and d (null: E never wins). */
    private class Facts(val e: String, val d: Long?)

    companion object {
        private lateinit var config: Config
        private lateinit var enDict: HashMap<String, Int>
        private lateinit var enAliases: Map<String, String>
        private lateinit var enPrefix: HashMap<String, MutableSet<String>>
        private lateinit var context: Context

        private val corpusDir = File(
            System.getenv("CK_MISSPELL_DIR") ?: "${System.getProperty("user.home")}/.cache/cleverkeys-test/misspell"
        )
        private val heldoutDir = File(
            System.getenv("CK_HELDOUT_DIR")
                ?: "${System.getProperty("user.home")}/.cache/cleverkeys-corpora/static-lm-eval"
        )
        private val outDir = File(System.getenv("CK_EVAL_OUT") ?: "build/eval/secondary-bypass")

        const val NEVER = Int.MIN_VALUE   // never bypass (pre-aa9b57ba)
        const val ALWAYS = Int.MAX_VALUE  // always bypass (current)
        val GRID: List<Int> = listOf(NEVER) + (-64..248 step 8).toList() + ALWAYS

        val MUST_KEEP = mapOf(
            "es" to listOf("pero", "como", "para", "pues", "porque", "hasta", "nada", "mucho", "entonces", "todo"),
            "de" to listOf("und", "nicht", "auch", "sehr", "doch", "schon", "aber", "noch", "wenn", "oder"),
        )
        val MUST_FIX = mapOf("de" to mapOf("thier" to "their", "wich" to "which"))

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.d(any(), any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0
            every { Log.e(any(), any()) } returns 0
            every { Log.e(any(), any(), any()) } returns 0
            mockkStatic(Trace::class)
            every { Trace.beginSection(any()) } just runs
            every { Trace.endSection() } just runs
            KeyAdjacency.resetLayout()

            val loaded = ShippedTapDictionary.withContractionAliases()
            enDict = loaded.dictionary
            enAliases = loaded.aliases
            enPrefix = loaded.prefixIndex

            val assets = mockk<AssetManager>()
            every { assets.open(any()) } answers { FileInputStream(File("src/main/assets", firstArg<String>())) }
            context = mockk(relaxed = true)
            every { context.assets } returns assets

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
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() = unmockkAll()

        private fun set(predictor: WordPredictor, name: String, value: Any?) {
            val f = WordPredictor::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(predictor, value)
        }

        /** A secondary index built as `WordPredictor.buildSecondaryDictionary` does (no custom words). */
        fun secondaryIndex(lang: String): NormalizedPrefixIndex {
            val index = NormalizedPrefixIndex(lang)
            check(BinaryDictionaryLoader.loadIntoNormalizedIndex(context, "dictionaries/${lang}_enhanced.bin", index)) {
                "$lang secondary did not load"
            }
            return index
        }

        /** A predictor over a primary map + prefix index with [secondaryLang]'s index as secondary. */
        fun predictor(
            primaryLang: String,
            dict: HashMap<String, Int>,
            prefix: HashMap<String, MutableSet<String>>,
            aliases: Map<String, String>,
            secondaryLang: String,
        ): WordPredictor {
            val p = org.objenesis.ObjenesisStd().newInstance(WordPredictor::class.java)
            set(p, "dictionary", AtomicReference<MutableMap<String, Int>>(dict))
            set(p, "prefixIndex", AtomicReference<MutableMap<String, MutableSet<String>>>(prefix))
            set(p, "contractionAliases", aliases)
            set(p, "customAndUserWords", emptySet<String>())
            set(p, "disabledWords", mutableSetOf<String>())
            set(p, "userWordOriginalCase", ConcurrentHashMap<String, String>())
            set(p, "config", config)
            set(p, "cachedMaxFreqForSize", -1)
            set(p, "currentLanguage", primaryLang)
            set(p, "dictionaryLanguageOrNull", primaryLang)
            set(p, "secondaryIndex", secondaryIndex(secondaryLang))
            set(p, "secondaryLanguageCode", secondaryLang)
            set(p, "secondaryDisabledWords", emptySet<String>())
            return p
        }

        /** Deterministic split key (DD-M convention): DEV when the FNV-1a hash is even. */
        fun isDev(w: String): Boolean {
            var h = 0x811C9DC5.toInt()
            for (c in w) { h = h xor c.code; h *= 0x01000193 }
            return (h and 1) == 0
        }
    }

    private val phase = System.getenv("CK_SB_PHASE") ?: "dev"
    private val tuning = AutocorrectTuning.DEFAULT
    private val minLen = Defaults.AUTOCORRECT_MIN_WORD_LENGTH
    private val out = StringBuilder()

    private fun say(line: String) {
        println(line)
        out.append(line).append('\n')
    }

    // ── Inputs ────────────────────────────────────────────────────────────────

    /** Birkbeck-format pairs (`$target` then misspellings, an optional count after a space). */
    private fun birkbeck(file: File): List<Pair2> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<Pair2>()
        var target: String? = null
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("$")) {
                target = line.substring(1).lowercase()
            } else {
                val typo = line.substringBefore(' ').lowercase()
                target?.let { out.add(Pair2(typo, it)) }
            }
        }
        return out
    }

    /** Every typo pair of [split] ("DEV" / "TEST"), distinct. */
    private fun typoPairs(split: String): Set<Pair2> {
        val out = LinkedHashSet<Pair2>()
        for (name in listOf("missp.dat", "holbrook-missp.dat", "aspell.dat", "wikipedia.dat")) {
            for (p in birkbeck(File(corpusDir, name))) {
                if ((split == "DEV") == isDev(p.typo)) out.add(p)
            }
        }
        val gtc = File(corpusDir, "gtc-pairs.tsv")
        if (gtc.isFile) gtc.useLines { lines ->
            for (line in lines.drop(1)) {
                val cols = line.split('\t')
                if (cols.size < 3 || cols[0] != split) continue
                out.add(Pair2(cols[1].lowercase(), cols[2].lowercase()))
            }
        }
        return out
    }

    /** Lowercase `[\p{L}']+` token counts of the held-out sentences of [split] for [lang]. */
    private fun realTokens(lang: String, split: String): Map<String, Int> {
        val file = File(heldoutDir, "heldout_$lang.txt")
        assumeTrue("missing ${file.path}", file.isFile)
        val counts = HashMap<String, Int>()
        val token = Regex("[\\p{L}']+")
        file.useLines { lines ->
            lines.forEachIndexed { id, line ->
                if ((id % 2 == 0) != (split == "DEV")) return@forEachIndexed
                for (m in token.findAll(line)) counts.merge(m.value.lowercase(), 1, Int::plus)
            }
        }
        return counts
    }

    // ── Rule facts ────────────────────────────────────────────────────────────

    private val exactMethod = WordPredictor::class.java.getDeclaredMethod("isInSecondaryLexiconExactly", String::class.java)
        .apply { isAccessible = true }
    private val rankMethod = WordPredictor::class.java.getDeclaredMethod("secondaryExactRank", String::class.java)
        .apply { isAccessible = true }

    /** The inputs the bypass protects today: not primary, not an alias, an exact secondary word. */
    private fun bypassed(p: WordPredictor, dict: Map<String, Int>, aliases: Map<String, String>, t: String): Boolean =
        t.length >= minLen && !dict.containsKey(t) && t !in aliases && exactMethod.invoke(p, t) as Boolean

    private fun facts(p: WordPredictor, dict: Map<String, Int>, t: String): Facts {
        val e = p.autoCorrect(t, tuning, NEVER)
        if (e.equals(t, ignoreCase = true)) return Facts(e, null)
        val rank = rankMethod.invoke(p, t) as Int? ?: return Facts(e, null)
        val maxFreq = dict.values.max()
        val key = e.lowercase()
        val freq = dict[key] ?: dict[key.filterNot { it == '\'' || it == '’' }] ?: dict[key.replace("ß", "ss")]
            ?: return Facts(e, null)
        return Facts(e, freq.toLong() * 255 / maxFreq - (255 - rank))
    }

    private fun simulate(t: String, f: Facts, m: Int): String = when {
        f.e.equals(t, ignoreCase = true) -> t
        m == ALWAYS -> t
        m == NEVER -> f.e
        f.d != null && f.d > m -> f.e
        else -> t
    }

    // ── Run ───────────────────────────────────────────────────────────────────

    @Test
    fun measure() {
        assumeTrue("missing corpora in $corpusDir", corpusDir.isDirectory)
        val split = if (phase == "test") "TEST" else "DEV"
        val margin = System.getenv("CK_SB_MARGIN")?.let { if (it == "inf") ALWAYS else it.toInt() }
        val typos = typoPairs(split)
        say("phase=$phase split=$split typo pairs=${typos.size} grid=${GRID.size}")

        val perPair = LinkedHashMap<String, Map<Int, IntArray>>() // lang → M → [fixes, wrong, eligTypos, tokBreaks, tokElig, topBreaks]
        for (lang in listOf("de", "es")) {
            val p = predictor("en", enDict, enPrefix, enAliases, lang)

            val typoElig = typos.filter { bypassed(p, enDict, enAliases, it.typo) && it.typo != it.target && enDict.containsKey(it.target) }
            val typoFacts = typoElig.associateWith { facts(p, enDict, it.typo) }

            val tokens = realTokens(lang, split).filterKeys { bypassed(p, enDict, enAliases, it) }
            val wordFacts = tokens.keys.associateWith { facts(p, enDict, it) }
            val top = tokens.entries.sortedByDescending { it.value }.take(300).map { it.key }
            val tokTotal = tokens.values.sum()
            say("[$lang] eligible typo pairs=${typoElig.size} (distinct typos ${typoElig.map { it.typo }.toSet().size}); " +
                "eligible real words=${tokens.size} tokens=$tokTotal")

            val table = LinkedHashMap<Int, IntArray>()
            for (m in GRID) {
                var fixes = 0; var wrong = 0
                for ((pair, f) in typoFacts) {
                    val o = simulate(pair.typo, f, m)
                    if (o.lowercase() == pair.target) fixes++ else if (o != pair.typo) wrong++
                }
                var tokBreaks = 0
                for ((w, f) in wordFacts) if (simulate(w, f, m) != w) tokBreaks += tokens.getValue(w)
                val topBreaks = top.count { simulate(it, wordFacts.getValue(it), m) != it }
                table[m] = intArrayOf(fixes, wrong, typoElig.size, tokBreaks, tokTotal, topBreaks)
                say("[$lang] M=%-11s fixes=%d (%.1f%%) wrong=%d tokenBreaks=%d retention=%.3f%% top300Breaks=%d".format(
                    label(m), fixes, 100.0 * fixes / maxOf(1, typoElig.size), wrong, tokBreaks,
                    100.0 * (tokTotal - tokBreaks) / maxOf(1, tokTotal), topBreaks))
            }
            perPair[lang] = table

            // Words the chosen/selected M would change, for the record.
            val show = margin ?: ALWAYS
            if (show != ALWAYS) {
                val broken = wordFacts.filter { (w, f) -> simulate(w, f, show) != w }.keys.sortedByDescending { tokens[it] }
                say("[$lang] real words changed at M=${label(show)} (${broken.size}): " +
                    broken.take(60).joinToString { "$it→${wordFacts.getValue(it).e}(${tokens[it]})" })
                val fixed = typoFacts.filter { (pr, f) -> simulate(pr.typo, f, show).lowercase() == pr.target }.keys
                say("[$lang] typos fixed at M=${label(show)} (${fixed.size}): " + fixed.take(60).joinToString { "${it.typo}→${it.target}" })
                val wrongs = typoFacts.filter { (pr, f) -> val o = simulate(pr.typo, f, show); o != pr.typo && o.lowercase() != pr.target }
                say("[$lang] wrong corrections at M=${label(show)} (${wrongs.size}): " +
                    wrongs.entries.take(60).joinToString { "${it.key.typo}→${it.value.e} (meant ${it.key.target})" })
                if (phase == "test") {
                    // Production equals the simulation on every eligible input.
                    var mismatches = 0
                    for ((pr, f) in typoFacts) if (p.autoCorrect(pr.typo, tuning, show) != simulate(pr.typo, f, show)) mismatches++
                    for ((w, f) in wordFacts) if (p.autoCorrect(w, tuning, show) != simulate(w, f, show)) mismatches++
                    say("[$lang] production vs simulation mismatches at M=${label(show)}: $mismatches")
                    assertTrue("production differs from the simulation", mismatches == 0)
                }
            }

            // Named words.
            for (w in MUST_KEEP.getValue(lang)) {
                if (!bypassed(p, enDict, enAliases, w)) { say("[$lang] named keep '$w': not a bypassed word, skipped"); continue }
                val f = facts(p, enDict, w)
                say("[$lang] named keep '$w': E=${f.e} d=${f.d} " + GRID.filter { simulate(w, f, it) != w }.let {
                    if (it.isEmpty()) "kept at every M" else "corrected at M ≤ ${label(it.last())}" })
            }
            for ((w, target) in MUST_FIX[lang].orEmpty()) {
                val elig = bypassed(p, enDict, enAliases, w)
                val f = facts(p, enDict, w)
                say("[$lang] named fix '$w'→'$target': bypassed=$elig E=${f.e} d=${f.d} " +
                    GRID.filter { simulate(w, f, it).lowercase() == target }.let {
                        if (it.isEmpty()) "never fixed" else "fixed at M ≤ ${label(it.last())}" })
            }
        }

        // de + en: German primary, English secondary (`fuss` must stay).
        run {
            val deDict = HashMap<String, Int>()
            val dePrefix = HashMap<String, MutableSet<String>>()
            check(BinaryDictionaryLoader.loadDictionaryWithPrefixIndex(context, "dictionaries/de_enhanced.bin", deDict, dePrefix))
            val p = predictor("de", deDict, dePrefix, emptyMap(), "en")
            val w = "fuss"
            val elig = bypassed(p, deDict, emptyMap(), w)
            val f = facts(p, deDict, w)
            say("[de+en] named keep '$w': bypassed=$elig E=${f.e} d=${f.d} " + GRID.filter { simulate(w, f, it) != w }.let {
                if (it.isEmpty()) "kept at every M" else "corrected at M ≤ ${label(it.last())}" })
        }

        // DEV selection rule (doc §Selection).
        if (phase != "test") {
            val chosen = GRID.firstOrNull { m ->
                perPair.values.all { t -> val r = t.getValue(m); 1.0 * (r[4] - r[3]) / maxOf(1, r[4]) >= 0.995 && r[5] <= 1 }
            }
            say("DEV selection: smallest M meeting retention ≥ 99.5% and top-300 breaks ≤ 1 in both pairs = ${chosen?.let { label(it) }}")
        }

        outDir.mkdirs()
        File(outDir, "$phase${margin?.let { "-M$it" } ?: ""}.txt").writeText(out.toString())
    }

    private fun label(m: Int) = when (m) { NEVER -> "-inf"; ALWAYS -> "+inf"; else -> m.toString() }
}
