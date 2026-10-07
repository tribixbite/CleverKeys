package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.ContractionManager
import tribixbite.cleverkeys.StaticContextLm
import tribixbite.cleverkeys.StaticLmLanguageData
import tribixbite.cleverkeys.swipe.ContractionContextChooser.Evidence
import tribixbite.cleverkeys.swipe.ContractionContextChooser.Params
import tribixbite.cleverkeys.swipe.ctc.CtcLexiconMerge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The evaluation behind [ContractionContextChooser] — `docs/eval/2026-10-07-apostrophe-context.md`
 * (pre-registered there, commit `df5cc6b5`, before any number was computed). A measurement
 * instrument: it prints the DEV grid, the selected cells and the TEST tables with the
 * pre-registered verdict, and asserts only that it measured something and that the structural
 * condition B5 holds (no slate without a same-surface pair at slots 0–1 is ever changed).
 *
 * OPT-IN and local-only:
 *
 *     scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ApostropheContextEvalTest -PgeoFull=true
 *
 * Stage 2 (the independent confirmation of the frozen arm C on Common Voice
 * `~/.cache/cleverkeys-corpora/commonvoice/sentence-collector.en.txt`): prefix the same command
 * with `APOSTROPHE_EVAL_STAGE=2`.
 *
 * Inputs (written by `scripts/build_static_lm.py` to `~/.cache/cleverkeys-corpora/static-lm-eval/`,
 * never committed): `heldout_en.txt` (the LM's 10 % held-out split — never in training),
 * `ood_dev_en.txt` / `ood_test_en.txt` (UD English-EWT surface text). Assume-skips without them.
 *
 * The English swipe display mappings are rebuilt here from the shipped assets exactly as
 * `ContractionManager.loadSwipeDisplayMappings("en")` holds them — `contractions.bin` (non-paired
 * map + DERIVED paired bases), then `contraction_pairings.json` (variants appended
 * earlier-wins, frequencies earlier-wins), paired bases reclassified out of the non-paired map,
 * then `contractions_en.json` skipping paired bases — and the overlay runs with the CTC adapter's
 * ordinals and base frequencies (`CtcLexiconMerge` over `en_enhanced.json`,
 * `PairingBaseFrequencies.select`). Decoding is an ORACLE: the surface is the confident rank-0
 * candidate (900 vs a 100 filler), isolating the display-form decision.
 */
class ApostropheContextEvalTest {

    private val corpora = File(System.getProperty("user.home"), ".cache/cleverkeys-corpora/static-lm-eval")
    private val dictDir = File("src/main/assets/dictionaries")

    // ── shipped English swipe display mappings (mirror of ContractionManager, see class doc) ──

    private class Mappings(
        val paired: Map<String, List<String>>,
        val pairedFrequency: Map<String, Map<String, Int>>,
        val nonPaired: Map<String, String>,
    )

    private fun loadMappings(): Mappings {
        val buf = ByteBuffer.wrap(File(dictDir, "contractions.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun readString(): String {
            val bytes = ByteArray(buf.short.toInt() and 0xFFFF)
            buf.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
        buf.position(8) // magic + version
        val nonPairedCount = buf.int
        val pairedCount = buf.int
        val nonPaired = LinkedHashMap<String, String>()
        repeat(nonPairedCount) { val k = readString(); nonPaired[k] = readString() }
        val known = LinkedHashSet<String>(nonPaired.values)
        repeat(pairedCount) { known += readString() }

        val paired = LinkedHashMap<String, MutableList<String>>()
        val nonPairedValues = nonPaired.values.toSet()
        for (contraction in known) {
            if (contraction !in nonPairedValues) {
                paired.getOrPut(contraction.replace("'", "")) { mutableListOf() }.add(contraction)
            }
        }
        val frequencies = HashMap<String, MutableMap<String, Int>>()
        for ((base, variants) in ContractionManager.parsePairings(File(dictDir, "contraction_pairings.json").readText())) {
            for (v in variants) {
                val list = paired.getOrPut(base) { mutableListOf() }
                if (v.contraction !in list) list.add(v.contraction)
                v.frequency?.let { frequencies.getOrPut(base) { mutableMapOf() }.putIfAbsent(v.contraction, it) }
            }
        }
        for (base in paired.keys) nonPaired.remove(base)
        val en = JSONObject(File(dictDir, "contractions_en.json").readText())
        for (k in en.keys()) {
            val key = k.lowercase(Locale.ROOT)
            if (key !in paired && key !in nonPaired) nonPaired[key] = en.getString(k).lowercase(Locale.ROOT)
        }
        return Mappings(paired, frequencies, nonPaired)
    }

    // ── corpus tokens ────────────────────────────────────────────────────────────────────

    /** One token as WRITTEN (lowercased), and where it starts in its line. */
    private class Token(val form: String, val start: Int)

    /**
     * Tokens of [line] — runs of letters, apostrophes and hyphens, as the device tokenizer splits
     * them — keeping the written form: leading apostrophes/hyphens and trailing hyphens trimmed;
     * a trailing apostrophe kept only after `s` (the plural possessive `girls'`), else trimmed
     * (a closing quote). Tokens without a letter are dropped.
     */
    private fun tokens(line: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (!(c.isLetter() || c == '\'' || c == '-')) { i++; continue }
            val runStart = i
            while (i < line.length && (line[i].isLetter() || line[i] == '\'' || line[i] == '-')) i++
            var s = runStart
            var e = i
            while (s < e && (line[s] == '\'' || line[s] == '-')) s++
            while (e > s && line[e - 1] == '-') e--
            // Trailing apostrophes: strip the whole run, then restore ONE after a final `s`.
            var bare = e
            while (bare > s && line[bare - 1] == '\'') bare--
            e = if (bare < e && bare > s && line[bare - 1].lowercaseChar() == 's') bare + 1 else bare
            if (e > s) {
                val form = line.substring(s, e).lowercase(Locale.ROOT)
                if (form.any { it.isLetter() }) out.add(Token(form, s))
            }
        }
        return out
    }

    private class Occurrence(
        val sentence: Int,
        val prev: String?,
        val gold: String,
        val surface: String,
        val slate: List<String>,
        val heldout: Boolean,
    )

    // ── the run ──────────────────────────────────────────────────────────────────────────

    @Test
    fun evaluateApostropheContextChoice() {
        if (System.getProperty("geoFull") != "true") {
            println("[skip] apostrophe context eval — set -PgeoFull=true to run")
            return
        }
        val held = File(corpora, "heldout_en.txt")
        val oodDev = File(corpora, "ood_dev_en.txt")
        val oodTest = File(corpora, "ood_test_en.txt")
        Assume.assumeTrue("local eval files missing (run scripts/build_static_lm.py)",
            held.isFile && oodDev.isFile && oodTest.isFile)

        // Lexicon: ordinals + base frequencies exactly as the CTC adapter derives them.
        val root = JsonParser.parseString(File(dictDir, "en_enhanced.json").readText()).asJsonObject
        val base = ArrayList<Pair<String, Double>>(root.size())
        for ((w, f) in root.entrySet()) base.add(w to f.asDouble)
        val merged = CtcLexiconMerge.merge(base, emptyList(), emptySet())
        val ordinals = CtcLexiconMerge.ordinals(merged)
        val maps = loadMappings()
        val baseFrequencies = PairingBaseFrequencies.select(merged, maps.pairedFrequency.keys)

        val lm = StaticContextLm.parse(File("src/main/assets/lm/en.cklm").readBytes())
            .withReplaceAliases(StaticLmLanguageData.replaceAliases("en"))
        val model = ContractionContextChooser.forStaticLm(lm)

        // Overlaid slate per surface (cached): the surface decoded as the confident rank 0.
        val slates = HashMap<String, List<String>>()
        fun slateFor(surface: String): List<String> = slates.getOrPut(surface) {
            ContractionOverlay.apply(
                listOf(surface, FILLER), listOf(900, 100),
                pairedVariants = { maps.paired[it] },
                nonPairedMapping = { maps.nonPaired[it] },
                wordOrdinal = { ordinals[it] },
                pairedVariantFrequency = { b, v -> maps.pairedFrequency[b]?.get(v) },
                baseFrequency = { baseFrequencies[it] },
            ).first.map { it.lowercase(Locale.ROOT) }.filter { it != FILLER }
        }
        fun ambiguous(slate: List<String>) = slate.size >= 2 && ContractionContextChooser.sameSurface(slate[0], slate[1])

        // Collect occurrences, plus the B5 structural check on EVERY token of every sentence.
        val dev = ArrayList<Occurrence>()
        val test = ArrayList<Occurrence>()
        val notOffered = HashMap<String, Int>()
        val replaceBare = HashMap<String, IntArray>() // REPLACE key → [written bare, written as display]
        var sentenceId = 0
        var tokensSeen = 0L
        var structuralChanges = 0L
        var structuralChecked = 0L
        val structuralParams = Params(0.0, Evidence.ANY, promotePossessives = true)

        fun scan(file: File, toDev: (Int) -> Boolean, heldout: Boolean) {
            var lineNo = 0
            file.forEachLine { raw ->
                val line = raw.replace('’', '\'').replace('‘', '\'')
                val id = sentenceId++
                val dest = if (toDev(lineNo++)) dev else test
                for (t in tokens(line)) {
                    tokensSeen++
                    if ('-' in t.form) continue
                    val surface = t.form.replace("'", "")
                    if (surface.isEmpty()) continue
                    maps.nonPaired[surface]?.let { display ->
                        if (surface !in maps.paired) {
                            val counts = replaceBare.getOrPut(surface) { IntArray(2) }
                            if (t.form == surface) counts[0]++ else if (t.form == display) counts[1]++
                        }
                    }
                    val slate = slateFor(surface)
                    if (!ambiguous(slate)) {
                        // B5: nothing without a same-surface pair at slots 0–1 may change, whatever the context.
                        if (slate.size >= 2) {
                            structuralChecked++
                            val prev = ContractionContextChooser.previousWord(line.substring(0, t.start))
                            val out = ContractionContextChooser.choose(slate, List(slate.size) { 900 }, "en", prev,
                                model, { false }, structuralParams).first
                            if (out !== slate) structuralChanges++
                        }
                        continue
                    }
                    if (t.form !in slate) {
                        notOffered.merge("${surface}→${t.form}", 1, Int::plus)
                        continue
                    }
                    val prev = ContractionContextChooser.previousWord(line.substring(0, t.start))
                    dest.add(Occurrence(id, prev, t.form, surface, slate, heldout))
                }
            }
        }
        fun top(o: Occurrence, params: Params?): String {
            if (params == null) return o.slate[0]
            return ContractionContextChooser.choose(o.slate, List(o.slate.size) { 900 }, "en", o.prev,
                model, { false }, params).first[0]
        }

        // Stage 2 (pre-registered in the eval doc §2): the FIXED arm-C hypothesis on Common Voice,
        // a population neither the LM builder nor stage 1 used. No grid, no tuning.
        if (System.getenv("APOSTROPHE_EVAL_STAGE") == "2") {
            val cv = File(corpora.parentFile, "commonvoice/sentence-collector.en.txt")
            Assume.assumeTrue("Common Voice sentences missing", cv.isFile)
            scan(cv, toDev = { false }, heldout = false)
            println("[apostrophe-eval stage2] tokens=$tokensSeen occurrences=${test.size} " +
                "B5 checked=$structuralChecked changed=$structuralChanges")
            report("C-fixed", STAGE2_ARM_C, test) { o, p -> top(o, p) }
            println("[stage2] sentence-start positions (unchanged by construction): ${test.count { it.prev == null }}")
            assertThat(structuralChanges).isEqualTo(0L)
            assertThat(test.size).isGreaterThan(0)
            return
        }

        scan(held, toDev = { it % 2 == 0 }, heldout = true)
        scan(oodDev, toDev = { true }, heldout = false)
        scan(oodTest, toDev = { false }, heldout = false)
        println("[apostrophe-eval] tokens=$tokensSeen dev=${dev.size} test=${test.size} " +
            "B5 checked=$structuralChecked changed=$structuralChanges")

        // DEV grid → selected cell per arm (max accuracy; ties → larger τ, then LISTED).
        val taus = listOf(0.0, ln(1.5), ln(2.0), ln(4.0))
        val grid = taus.flatMap { tau -> listOf(Evidence.ANY, Evidence.LISTED).map { tau to it } }
        val devCtx = dev.filter { it.prev != null }
        val devBase = devCtx.count { top(it, null) == it.gold }
        println("[dev] positions with context=${devCtx.size} current correct=$devBase (%.2f%%)".format(pct(devBase, devCtx.size)))
        fun select(possessives: Boolean, arm: String): Params {
            var best: Params? = null
            var bestHits = -1
            for ((tau, ev) in grid) { // ascending τ, ANY before LISTED: ">=" keeps the later (more conservative) cell on ties
                val p = Params(tau, ev, possessives)
                val hits = devCtx.count { top(it, p) == it.gold }
                println("[dev] arm=$arm tau=%.4f evidence=$ev correct=$hits (%.2f%%)".format(tau, pct(hits, devCtx.size)))
                if (hits >= bestHits) { bestHits = hits; best = p }
            }
            println("[dev] arm=$arm SELECTED $best")
            return best!!
        }
        val armB = select(possessives = true, arm = "B")
        val armC = select(possessives = false, arm = "C")

        // TEST: scored once per arm.
        for ((name, params) in listOf("B" to armB, "C" to armC)) report(name, params, test) { o, p -> top(o, p) }

        println("[test] sentence-start positions (no previous word, unchanged by construction): " +
            "${test.count { it.prev == null }}, current correct ${test.count { it.prev == null && it.slate[0] == it.gold }}")
        println("[notOffered] gold forms the overlay does not show (top 15): " +
            notOffered.entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key}=${it.value}" })
        println("[replace] REPLACE keys written BARE vs as the display form (keys with ≥ 20 bare): " +
            replaceBare.entries.filter { it.value[0] >= 20 }.sortedByDescending { it.value[0] }
                .joinToString { "${it.key} bare=${it.value[0]} display=${it.value[1]}" })

        assertThat(structuralChanges).isEqualTo(0L)
        assertThat(test.size).isGreaterThan(0)
    }

    /** Prints the TEST tables for one arm and its pre-registered verdict. */
    private fun report(
        arm: String,
        params: Params,
        all: List<Occurrence>,
        top: (Occurrence, Params?) -> String,
    ) {
        val ctx = all.filter { it.prev != null }
        class Cell { var n = 0; var cur = 0; var new = 0; var win = 0; var loss = 0; var top2 = 0
            val sentences = HashSet<Int>(); val pairs = HashSet<String>() }
        fun cellOf(list: List<Occurrence>): Cell {
            val c = Cell()
            for (o in list) {
                val a = top(o, null) == o.gold
                val b = top(o, params) == o.gold
                c.n++; if (a) c.cur++; if (b) c.new++
                if (b && !a) c.win++; if (a && !b) c.loss++
                if (o.gold in o.slate.take(2)) c.top2++
                c.sentences += o.sentence; c.pairs += "${o.prev}|${o.gold}"
            }
            return c
        }
        fun line(label: String, c: Cell) = println(
            "[test] arm=$arm %-28s n=%6d sent=%6d pairs=%5d cur=%6.2f%% new=%6.2f%% Δ=%+6.2f win=%5d loss=%5d top2=%6.2f%%"
                .format(label, c.n, c.sentences.size, c.pairs.size, pct(c.cur, c.n), pct(c.new, c.n),
                    pct(c.new, c.n) - pct(c.cur, c.n), c.win, c.loss, pct(c.top2, c.n)))
        println("[test] arm=$arm params=$params")
        val pooled = cellOf(ctx)
        val ood = cellOf(ctx.filter { !it.heldout })
        val inDomain = cellOf(ctx.filter { it.heldout })
        fun possessiveClass(o: Occurrence) = o.slate.take(2).any { ContractionOverlay.isPossessive(it) }
        val poss = cellOf(ctx.filter(::possessiveClass))
        val nonPoss = cellOf(ctx.filterNot(::possessiveClass))
        line("POOLED", pooled); line("OOD (EWT test)", ood); line("in-domain held-out", inDomain)
        line("class non-possessive", nonPoss); line("class possessive", poss)
        val bySurface = ctx.groupBy { it.surface }.mapValues { cellOf(it.value) }
        for ((s, c) in bySurface.entries.sortedByDescending { it.value.n }) if (c.n >= 20) line("surface $s", c)
        val changedSurfaces = bySurface.entries.filter { it.value.win + it.value.loss > 0 && it.value.n < 20 }
        println("[test] arm=$arm surfaces <20 occurrences with any change: " +
            changedSurfaces.joinToString { "${it.key}(n=${it.value.n},+${it.value.win}/-${it.value.loss})" })

        val b1 = pct(pooled.new, pooled.n) - pct(pooled.cur, pooled.n) >= 1.0 &&
            (pooled.win - pooled.loss) >= 2 * sqrt((pooled.win + pooled.loss).toDouble())
        val b2 = ood.new >= ood.cur
        val b3 = nonPoss.new >= nonPoss.cur && poss.new >= poss.cur
        val b4Fail = bySurface.filter { it.value.n >= 50 && pct(it.value.new, it.value.n) - pct(it.value.cur, it.value.n) < -1.0 }
        println("[verdict] arm=$arm B1=${pass(b1)} B2=${pass(b2)} B3=${pass(b3)} B4=${pass(b4Fail.isEmpty())}" +
            (if (b4Fail.isEmpty()) "" else " (failing: ${b4Fail.keys})"))
    }

    private fun pass(b: Boolean) = if (b) "PASS" else "FAIL"

    private fun pct(k: Int, n: Int) = if (n == 0) 0.0 else 100.0 * k / n

    private companion object {
        /** Runner-up filler: not a word, not a contraction key — it only makes the top confident. */
        const val FILLER = "zzfiller"

        /** Stage 1's DEV-selected arm C, frozen for the stage-2 confirmation. */
        val STAGE2_ARM_C = Params(minLogOdds = 0.0, evidence = Evidence.ANY, promotePossessives = false)
    }
}
