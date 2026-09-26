package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.ctc.CtcCandidate
import tribixbite.cleverkeys.swipe.ctc.CtcLearnedPrior
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Replay evaluation of the LEARNED-UNIGRAM swipe prior (learning-system audit 2026-09-26,
 * report 2: the user swipes `git` constantly and still gets `got`/`for`).
 *
 * Eval doc: `docs/eval/2026-09-26-learned-unigram-swipe-replay.md`. The discipline mirrors
 * `docs/eval/2026-08-22-context-rescoring-first-replay.md`: fixed/broken judged against the
 * target only ([RescoringMetrics.classify]), distinct-trace concentration beside every raw
 * count, a tune/confirm split by trace hash, and numbers pinned to a commit.
 *
 * ## Stage 0 — headroom (the kill switch)
 *
 * Every usable trace is decoded ONCE with `topK = beamWidth`, so the whole final beam is
 * visible. A learned prior is an additive final-score term, so it can only ever:
 *  - FIX an error whose target is IN the beam and trails top-1 by no more than the target's
 *    own maximum lift (`min(λ·ln(F_CEIL/f), B_CAP) + SEL_MARGIN`), and
 *  - BREAK a correct decode whose some other candidate is within ITS maximum lift.
 * If the fixable share is below [KILL_SWITCH_FRACTION] of traces, the feature cannot pay
 * for itself on this engine and the evaluation stops there.
 *
 * Gated like the other corpus replays: `-PgeoFull=true`, the local (never committed) trace
 * pool, and the bionic ONNX natives. Absent any of them it skips, so CI stays green.
 */
class LearnedUnigramReplayTest {

    private val cacheDir: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
    }
    private val traceFile = File(cacheDir, "combined_english_swipes.jsonl.gz")
    private val corporaDir = File(System.getProperty("user.home"), ".cache/cleverkeys-corpora")

    /** One usable real trace in the CTC engine's normalized frame. */
    private class Row(val word: String, val x: DoubleArray, val y: DoubleArray, val t: DoubleArray) {
        /** Trace identity without context — the unit of the tune/confirm split. */
        val id: String = "$word|${x.size}|${x.firstOrNull()}"
        val tuneHalf: Boolean get() = ((id.hashCode() % 2) + 2) % 2 == 0
    }

    /** EVERY usable trace (no per-word cap): Stage 0 asks about the whole pool. */
    private fun loadAllTraces(): List<Row> {
        val rows = ArrayList<Row>()
        GZIPInputStream(traceFile.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                if (word.isEmpty()) continue
                val pts = o.optJSONArray("pts") ?: continue
                val n = pts.length()
                if (n < 3) continue
                val x = DoubleArray(n); val y = DoubleArray(n); val t = DoubleArray(n)
                var malformed = false
                for (i in 0 until n) {
                    val p = pts.optJSONArray(i)
                    if (p == null) { malformed = true; break }
                    x[i] = p.optDouble(0); y[i] = p.optDouble(1); t[i] = p.optDouble(2)
                }
                if (malformed || !TraceCorpusQuality.hasUsableTimestamps(t)) continue
                rows.add(Row(word, x, y, t))
            }
        }
        return rows
    }

    private fun gate(): Boolean {
        if (System.getProperty("geoFull") != "true") {
            println("[skip] learned-unigram replay — set -PgeoFull=true to run")
            return false
        }
        Assume.assumeTrue("no trace pool at ${traceFile.path} (local-only)", traceFile.isFile)
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so onnxruntime.native.path is set",
            CtcReplayEngine.ortAvailable(),
        )
        return true
    }

    /**
     * Upper bound on the bonus a candidate can receive — [CtcLearnedPrior.Policy.maxLift]
     * with the design cap and the given selection margin.
     */
    private fun maxLift(c: CtcCandidate, lambda: Double, selMargin: Double): Double =
        CtcLearnedPrior.Policy(bCap = B_CAP, selMargin = selMargin).maxLift(c.logFreq, lambda)

    private fun baseFreq(c: CtcCandidate): Double = Math.exp(c.logFreq)

    @Test
    fun stage0Headroom() {
        if (!gate()) return
        val rows = loadAllTraces()
        println("[LU0] usable traces=${rows.size} distinct words=${rows.map { it.word }.toSet().size}")
        CtcReplayEngine.build("en").use { engine ->
            val lambda = engine.scoringParams.lambda
            val decoder = engine.fullBeamDecoder()
            var decoded = 0; var empty = 0; var nonAz = 0
            var correct = 0; var errors = 0; var errInBeam = 0
            var fixableUni = 0; var fixableSel = 0
            var atRiskUni = 0; var atRiskSel = 0; var atRiskRunnerUpSel = 0
            val fixableWords = HashSet<String>(); val atRiskWords = HashSet<String>()
            val errWords = HashSet<String>()
            val deficits = ArrayList<Double>()
            val examples = ArrayList<String>()
            val started = System.nanoTime()
            for (row in rows) {
                if (!row.word.all { it in 'a'..'z' }) { nonAz++; continue }
                val cands = decoder.decode(row.x, row.y, row.t)
                decoded++
                if (cands.isEmpty()) { empty++; errors++; errWords.add(row.word); continue }
                val top = cands[0]
                if (top.word == row.word) {
                    correct++
                    // At risk: some OTHER eligible candidate within its own max lift.
                    var uni = false; var sel = false
                    for (c in cands.drop(1)) {
                        if (!engine.isLexiconWord(c.word)) continue
                        val gap = top.finalScore - c.finalScore
                        if (gap <= maxLift(c, lambda, 0.0)) uni = true
                        if (gap <= maxLift(c, lambda, SEL_MARGIN_MAX)) sel = true
                    }
                    val ru = cands.getOrNull(1)
                    if (ru != null && engine.isLexiconWord(ru.word) &&
                        top.finalScore - ru.finalScore <= maxLift(ru, lambda, SEL_MARGIN_MAX)
                    ) atRiskRunnerUpSel++
                    if (uni) atRiskUni++
                    if (sel) { atRiskSel++; atRiskWords.add(row.word) }
                } else {
                    errors++; errWords.add(row.word)
                    val t = cands.firstOrNull { it.word == row.word } ?: continue
                    errInBeam++
                    val deficit = top.finalScore - t.finalScore
                    deficits.add(deficit)
                    if (!engine.isLexiconWord(t.word)) continue
                    val f = baseFreq(t)
                    if (deficit <= maxLift(t, lambda, 0.0)) fixableUni++
                    if (deficit <= maxLift(t, lambda, SEL_MARGIN_MAX)) {
                        fixableSel++; fixableWords.add(row.word)
                        if (examples.size < 12) {
                            examples.add("%s<-%s d=%.2f f=%.0f".format(row.word, top.word, deficit, f))
                        }
                    }
                }
            }
            val sec = (System.nanoTime() - started) / 1e9
            val n = decoded.toDouble()
            fun pct(k: Int) = "%d (%.2f%%)".format(k, 100.0 * k / n)
            val ds = deficits.sorted()
            println("[LU0] ═══ STAGE 0 — headroom, EP=${CtcReplayEngine.executionProvider} " +
                "beamWidth=${engine.scoringParams.beamWidth} λ=$lambda F_CEIL=$F_CEIL B_CAP=$B_CAP")
            println("[LU0] decoded=$decoded (non-a-z targets skipped=$nonAz, empty beams=$empty) " +
                "wall=%.0fs".format(sec))
            println("[LU0] top-1 correct=${pct(correct)} errors=${pct(errors)} " +
                "(distinct error words=${errWords.size})")
            println("[LU0] errors with target IN beam=${pct(errInBeam)}")
            if (ds.isNotEmpty()) {
                println("[LU0]   in-beam deficit median=%.2f p25=%.2f p75=%.2f".format(
                    ds[ds.size / 2], ds[ds.size / 4], ds[ds.size * 3 / 4]))
            }
            println("[LU0] ORACLE-FIXABLE (unigram lift only)       = ${pct(fixableUni)}")
            println("[LU0] ORACLE-FIXABLE (+SEL_MARGIN $SEL_MARGIN_MAX)          = ${pct(fixableSel)} " +
                "distinct words=${fixableWords.size}")
            println("[LU0] AT-RISK correct (any cand, unigram only) = ${pct(atRiskUni)}")
            println("[LU0] AT-RISK correct (any cand, +SEL)         = ${pct(atRiskSel)} " +
                "distinct words=${atRiskWords.size}")
            println("[LU0] AT-RISK correct (runner-up only, +SEL)   = ${pct(atRiskRunnerUpSel)}")
            println("[LU0] kill switch: fixable(+SEL)/traces = %.4f vs %.3f -> %s".format(
                fixableSel / n, KILL_SWITCH_FRACTION,
                if (fixableSel / n < KILL_SWITCH_FRACTION) "STOP" else "PROCEED"))
            println("[LU0] examples: $examples")
            assertWithMessage("the replay must decode something").that(decoded).isGreaterThan(0)
        }
    }

    // ══ Stage 1 — profile replay ═══════════════════════════════════════════════════

    /** One final-beam candidate as the re-rank needs it. */
    private class Cand(val word: String, val finalScore: Double, val logFreq: Double, val eligible: Boolean)

    /** One decoded trace: the full NONE beam, in decoder order. */
    private class Decoded(val row: Row, val cands: List<Cand>)

    /** Top-1 word after adding [bonus] to every candidate; ties keep decoder order. */
    private fun rerankTop1(cands: List<Cand>, bonus: (Cand) -> Double): String? {
        var best: Cand? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in cands) {
            val s = c.finalScore + bonus(c)
            if (s > bestScore) { best = c; bestScore = s }
        }
        return best?.word
    }

    /** Outcome tally with concentration: raw cases beside distinct traces and words. */
    private class Arm {
        val tally = RescoringMetrics.Tally()
        var exposed = 0
        val exposedTraces = HashSet<String>()
        val fixedTraces = HashSet<String>()
        val brokenTraces = HashSet<String>()
        val fixedWords = HashSet<String>()
        val brokenWords = HashSet<String>()
        val brokenExamples = ArrayList<String>()
        val fixedExamples = ArrayList<String>()

        fun record(d: Decoded, exposedCase: Boolean, top1After: String?, label: String) {
            val before = d.cands.firstOrNull()?.word
            val outcome = RescoringMetrics.classify(d.row.word, before, top1After)
            tally.record(outcome)
            if (exposedCase) { exposed++; exposedTraces.add(d.row.id) }
            when (outcome) {
                RescoringMetrics.Outcome.FIXED -> {
                    fixedTraces.add(d.row.id); fixedWords.add(d.row.word)
                    if (fixedExamples.size < 6) fixedExamples.add("$label ${d.row.word}: $before->$top1After")
                }
                RescoringMetrics.Outcome.BROKEN -> {
                    brokenTraces.add(d.row.id); brokenWords.add(d.row.word)
                    if (brokenExamples.size < 6) brokenExamples.add("$label ${d.row.word}: $before->$top1After")
                }
                else -> Unit
            }
        }

        /** Distinct broken traces per distinct exposed trace — the adversarial ship-bar unit. */
        val breakRateDistinct: Double
            get() = if (exposedTraces.isEmpty()) 0.0 else brokenTraces.size.toDouble() / exposedTraces.size

        override fun toString(): String =
            "$tally exposed=$exposed cases/${exposedTraces.size} traces; " +
                "fixed ${fixedTraces.size} traces/${fixedWords.size} words; " +
                "broken ${brokenTraces.size} traces/${brokenWords.size} words " +
                "(break/exposed distinct=%.4f)".format(breakRateDistinct)
    }

    /** Every arm of one grid point on one half. */
    private class Point {
        val real = Arm()
        val realTargetUsed = Arm()      // P_real, target itself has ≥ threshold uses
        val realTargetUnused = Arm()    // P_real, target not in (eligible) profile
        val fav = HashMap<String, Arm>() // "use3", "use6", "use20", "sel1", "sel2", "sel6"
        val advUse = Arm()              // {neighbour: usage 20}
        val advSel = Arm()              // {neighbour: 7 selections}
        val advRunnerUp = Arm()         // {runner-up: usage 20} — worst case, informational

        fun meetsBar(): Boolean =
            real.tally.meetsShipBar() &&
                advUse.breakRateDistinct <= ADV_BREAK_BAR &&
                advSel.breakRateDistinct <= ADV_BREAK_BAR

        val favFixed: Int get() = fav.values.sumOf { it.tally.fixed }
    }

    @Test
    fun stage1ProfileReplay() {
        if (!gate()) return
        val exportFile = File(corporaDir, "device_bigrams.json")
        Assume.assumeTrue("no device export at ${exportFile.path} (local-only)", exportFile.isFile)
        val profile = LearnedProfileCorpus.parse(exportFile)
        println("[LU1] P_real: ${profile.total} vocabulary entries, ${profile.eligible} at " +
            "n_eff ≥ ${CtcLearnedPrior.MIN_EFFECTIVE_USES} (recency at export instant); " +
            "manual selections are NOT in the export -> sel=0 throughout")

        val rows = loadAllTraces().filter { r -> r.word.all { it in 'a'..'z' } }
        val poolWords = rows.map { it.word }.distinct().sorted()
        CtcReplayEngine.build("en").use { engine ->
            val lambda = engine.scoringParams.lambda
            val decoder = engine.fullBeamDecoder()
            val started = System.nanoTime()
            val decoded = rows.map { row ->
                Decoded(row, decoder.decode(row.x, row.y, row.t).map {
                    Cand(it.word, it.finalScore, it.logFreq, engine.isLexiconWord(it.word))
                })
            }.filter { it.cands.isNotEmpty() }
            val sec = (System.nanoTime() - started) / 1e9
            println("[LU1] decoded ${decoded.size} traces (${poolWords.size} distinct words) " +
                "in %.0fs, EP=${CtcReplayEngine.executionProvider}".format(sec))

            // FIDELITY: the re-rank must equal a real prior decode. Checked on a slice with the
            // real profile at the default policy — if this ever fails, every number below is
            // a statement about the harness, not the decoder.
            val realPrior = CtcLearnedPrior(
                evidence = { w -> profile.lookup(w) },
                isLexiconWord = engine::isLexiconWord,
            )
            val priorDecoder = engine.fullBeamDecoder(realPrior)
            for (d in decoded.take(FIDELITY_SAMPLE)) {
                val direct = priorDecoder.decode(d.row.x, d.row.y, d.row.t).firstOrNull()?.word
                val rerank = rerankTop1(d.cands) { c ->
                    realPrior.bonusFor(c.word, c.logFreq, lambda)
                }
                assertWithMessage("re-rank must equal the prior decode for '${d.row.word}'")
                    .that(rerank).isEqualTo(direct)
            }

            val grid = LinkedHashMap<String, Pair<Point, Point>>() // key -> (tune, confirm)
            for (nSat in N_SAT_GRID) for (sm in SEL_MARGIN_GRID) {
                val policy = CtcLearnedPrior.Policy(nSat = nSat, selMargin = sm)
                val tune = Point(); val confirm = Point()
                grid[key(nSat, sm)] = tune to confirm
                for (d in decoded) {
                    val p = if (d.row.tuneHalf) tune else confirm
                    replayOne(d, p, policy, lambda, profile, poolWords)
                }
            }
            reportStage1(grid)
        }
    }

    /** Every profile arm for one decoded trace under one policy. */
    private fun replayOne(
        d: Decoded,
        p: Point,
        policy: CtcLearnedPrior.Policy,
        lambda: Double,
        profile: LearnedProfileCorpus.Profile,
        poolWords: List<String>,
    ) {
        fun bonusWith(ev: (String) -> CtcLearnedPrior.WordEvidence?): (Cand) -> Double = { c ->
            if (!c.eligible) 0.0 else ev(c.word)?.let { policy.bonus(it, c.logFreq, lambda) } ?: 0.0
        }
        val target = d.row.word

        // P_real — the maintainer's vocabulary applied to every trace.
        val realBonus = bonusWith { profile.lookup(it) }
        val realExposed = d.cands.any { realBonus(it) > 0.0 }
        val realTop = rerankTop1(d.cands, realBonus)
        p.real.record(d, realExposed, realTop, "real")
        val targetUsed = profile.lookup(target)?.let {
            it.effectiveUses >= policy.minEffectiveUses
        } == true
        (if (targetUsed) p.realTargetUsed else p.realTargetUnused).record(d, realExposed, realTop, "real")

        // P_synth-fav — the user uses exactly the target.
        val targetInBeam = d.cands.any { it.word == target && it.eligible }
        for ((label, ev) in FAV_PROFILES) {
            val top = rerankTop1(d.cands, bonusWith { w -> if (w == target) ev else null })
            p.fav.getOrPut(label) { Arm() }.record(d, targetInBeam, top, label)
        }

        // P_synth-adv — the user heavily uses a CONFUSABLE neighbour of the target.
        for (c in neighboursOf(target, poolWords, DECOYS_PER_TRACE)) {
            val inBeam = d.cands.any { it.word == c && it.eligible }
            p.advUse.record(d, inBeam,
                rerankTop1(d.cands, bonusWith { w -> if (w == c) ADV_USE else null }), "adv:$c")
            p.advSel.record(d, inBeam,
                rerankTop1(d.cands, bonusWith { w -> if (w == c) ADV_SEL else null }), "advSel:$c")
        }
        // Worst case: the user heavily uses exactly the runner-up.
        val ru = d.cands.getOrNull(1)
        if (ru != null && ru.word != target) {
            p.advRunnerUp.record(d, ru.eligible,
                rerankTop1(d.cands, bonusWith { w -> if (w == ru.word) ADV_USE else null }), "ru:${ru.word}")
        }
    }

    private fun reportStage1(grid: Map<String, Pair<Point, Point>>) {
        println("[LU1] ═══ STAGE 1 — tune half (selection happens here) ═══")
        for ((k, v) in grid) printPoint("TUNE    $k", v.first)
        val clearing = grid.entries.filter { it.value.first.meetsBar() }
        if (clearing.isEmpty()) {
            println("[LU1] SELECTED: none — no grid point clears the ship bar on the tune half")
            for ((k, v) in grid) printPoint("CONFIRM $k (not selected)", v.second)
            return
        }
        // Rule: max P_real net (fixed − broken); then max synthetic-favourable fixes; then the
        // LESS aggressive point (larger N_SAT, smaller SEL_MARGIN) — grid order handles that.
        val best = clearing.sortedWith(
            compareByDescending<Map.Entry<String, Pair<Point, Point>>> {
                it.value.first.real.tally.fixed - it.value.first.real.tally.broken
            }.thenByDescending { it.value.first.favFixed }
                .thenByDescending { nSatOf(it.key) }
                .thenBy { selOf(it.key) }
        ).first()
        println("[LU1] SELECTED on tune: ${best.key}")
        println("[LU1] ═══ CONFIRM half (held out) ═══")
        printPoint("CONFIRM ${best.key}", best.value.second)
        println("[LU1] CONFIRM meets ship bar: ${best.value.second.meetsBar()} " +
            "(P_real errRatio < ${RescoringMetrics.SHIP_BAR_ERROR_RATIO} AND Δtop1 > 0; " +
            "adv breaks ≤ ${ADV_BREAK_BAR * 100}% of exposed distinct traces)")
        println("[LU1] all confirm points, for the record (NOT used for selection):")
        for ((k, v) in grid) printPoint("CONFIRM $k", v.second)
    }

    private fun printPoint(label: String, p: Point) {
        println("[LU1] $label")
        println("[LU1]   P_real            ${p.real}")
        println("[LU1]     target used     ${p.realTargetUsed}")
        println("[LU1]     target unused   ${p.realTargetUnused}")
        for ((l, a) in p.fav.toSortedMap()) println("[LU1]   fav $l ${" ".repeat(10 - l.length)}$a")
        println("[LU1]   adv use20         ${p.advUse}")
        println("[LU1]   adv sel7          ${p.advSel}")
        println("[LU1]   adv runner-up20   ${p.advRunnerUp}   (worst case, informational)")
        if (p.real.brokenExamples.isNotEmpty()) println("[LU1]   real breaks: ${p.real.brokenExamples}")
        if (p.advUse.brokenExamples.isNotEmpty()) println("[LU1]   adv breaks : ${p.advUse.brokenExamples}")
        println("[LU1]   meetsBar=${p.meetsBar()}")
    }

    /**
     * Confusable neighbours of [word] — same first letter, length ±1 — from the trace-pool
     * vocabulary, shuffled with a per-word seed. The ContextRescoringReplayTest rule (H6: a
     * seeded shuffle, never file order).
     */
    private fun neighboursOf(word: String, pool: List<String>, limit: Int): List<String> {
        val candidates = pool.filter {
            it != word && it.isNotEmpty() && it[0] == word[0] && kotlin.math.abs(it.length - word.length) <= 1
        }
        if (candidates.size <= limit) return candidates
        return candidates.toMutableList()
            .also { java.util.Collections.shuffle(it, java.util.Random(SEED + word.hashCode())) }
            .take(limit)
    }

    private fun key(nSat: Double, sm: Double) = "N_SAT=%.0f SEL=%.2f".format(nSat, sm)
    private fun nSatOf(k: String) = k.substringAfter("N_SAT=").substringBefore(' ').toDouble()
    private fun selOf(k: String) = k.substringAfter("SEL=").toDouble()

    // ══ The audit's own scenario, pinned ═══════════════════════════════════════════

    /**
     * PIN — the reported `git` → `got` case (audit 2026-09-26, report 2) on the deterministic
     * [CtcTraceShapes] generator, under the SHIPPED constants: with the profile
     * `{git: 6 manual selections, got: 98 uses}` the prior must put `git` at rank 1 on a
     * straight trace and within the top 2 on both wobbles. Self-contained (no local corpus);
     * skips only when the ORT natives are absent. Baseline (NONE) ranks are printed beside.
     */
    @Test
    fun gitPinUnderTheShippedConstants() {
        Assume.assumeTrue("ONNX natives absent", CtcReplayEngine.ortAvailable())
        CtcReplayEngine.build("en").use { engine ->
            val layout = engine.layoutGeometry
            val profile = mapOf(
                "git" to CtcLearnedPrior.WordEvidence(0, 1.0, 6),
                "got" to CtcLearnedPrior.WordEvidence(98, 1.0, 0),
            )
            val prior = CtcLearnedPrior({ profile[it] }, engine::isLexiconWord)
            val base = engine.fullBeamDecoder()
            val lifted = engine.fullBeamDecoder(prior)
            val shapes = listOf(
                Triple("straight", CtcTraceShapes.straight("git", layout), 1),
                Triple("wobble 0.02", CtcTraceShapes.wobbled("git", layout, 0.02, 5.0), 2),
                Triple("wobble 0.035", CtcTraceShapes.wobbled("git", layout, 0.035, 4.0), 2),
            )
            for ((label, shape, maxRank) in shapes) {
                val (x, y, t) = shape
                val before = base.decode(x, y, t)
                val after = lifted.decode(x, y, t)
                val rankBefore = before.indexOfFirst { it.word == "git" } + 1
                val rankAfter = after.indexOfFirst { it.word == "git" } + 1
                println("[LU-pin] $label git rank NONE=$rankBefore prior=$rankAfter " +
                    "top3 NONE=${before.take(3).map { it.word }} prior=${after.take(3).map { it.word }}")
                assertWithMessage("$label: git must reach rank ≤ $maxRank (was $rankAfter; " +
                    "slate ${after.take(4).map { it.word }})")
                    .that(rankAfter).isIn(1..maxRank)
            }
        }
    }

    private companion object {
        /** en_enhanced byte-scale ceiling (the lift target). */
        const val F_CEIL = 255.0

        /** Design cap on the unigram part of the bonus. */
        const val B_CAP = 2.5

        /** Largest SEL_MARGIN in the design grid — Stage 0 bounds with it. */
        const val SEL_MARGIN_MAX = 0.5

        /** Stage-0 kill switch: below this share of oracle-fixable traces, stop. */
        const val KILL_SWITCH_FRACTION = 0.015

        /** Re-rank vs real prior decode equivalence check, on the first N traces. */
        const val FIDELITY_SAMPLE = 60

        /** Design grids. */
        val N_SAT_GRID = doubleArrayOf(10.0, 20.0, 40.0)
        val SEL_MARGIN_GRID = doubleArrayOf(0.0, 0.25, 0.5)

        /** Synthetic favourable profiles: the user uses exactly the target. */
        val FAV_PROFILES: List<Pair<String, CtcLearnedPrior.WordEvidence>> = listOf(
            "use3" to CtcLearnedPrior.WordEvidence(3, 1.0, 0),
            "use6" to CtcLearnedPrior.WordEvidence(6, 1.0, 0),
            "use20" to CtcLearnedPrior.WordEvidence(20, 1.0, 0),
            "sel1" to CtcLearnedPrior.WordEvidence(0, 1.0, 1),
            "sel2" to CtcLearnedPrior.WordEvidence(0, 1.0, 2),
            "sel6" to CtcLearnedPrior.WordEvidence(0, 1.0, 6),
        )

        /** Adversary: a confusable neighbour used 20 times / picked 7 times (n_eff 21). */
        val ADV_USE = CtcLearnedPrior.WordEvidence(20, 1.0, 0)
        val ADV_SEL = CtcLearnedPrior.WordEvidence(0, 1.0, 7)

        /** Neighbours drawn per trace for the adversarial arm. */
        const val DECOYS_PER_TRACE = 3

        /** Ship bar: adversarial breaks ≤ 1% of exposed distinct traces. */
        const val ADV_BREAK_BAR = 0.01

        /** Same seed family as ContextRescoringReplayTest. */
        const val SEED = 20260926L
    }
}
