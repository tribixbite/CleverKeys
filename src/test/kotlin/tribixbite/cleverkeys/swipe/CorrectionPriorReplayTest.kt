package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.LearnedPriorTracePool.Row
import tribixbite.cleverkeys.swipe.ctc.CtcLearnedPrior
import java.util.SplittableRandom

/**
 * Replay evaluation of the CORRECTION-DRIVEN swipe prior — the second attempt at the
 * learning-system audit's `git` → `got` report, after the usage-driven prior failed its ship
 * bar (`docs/eval/2026-09-26-learned-unigram-swipe-replay.md`: lifting every word the user
 * typed broke ~2× as many untyped-target swipes as it fixed).
 *
 * Eval doc: `docs/eval/2026-09-26-correction-driven-swipe-prior-replay.md`.
 *
 * ## The simulation
 *
 * Only words the decoder demonstrably FAILED on, and the user corrected to, are lifted. The
 * replay models that directly on the real trace pool, split by the shared trace-hash rule
 * ([LearnedPriorTracePool.Row.tuneHalf]):
 *
 *  - **Phase A — history.** Decode one half with NO prior. Every top-1 error whose target is a
 *    lexicon word is a correction `c(target) += 1`, kept with probability `rate` (users do not
 *    correct every error; the per-trace coin is fixed, so a lower rate's corrections are a
 *    SUBSET of a higher rate's). Optional **noise**: with probability `noise`, a CORRECT decode
 *    is "corrected" to its runner-up (a changed-mind bar tap — a wrong correction).
 *  - **Phase B — future.** Decode the OTHER half with the prior built from phase A's counts,
 *    and classify each trace against the no-prior decode with [RescoringMetrics.classify].
 *
 * Words reappear across halves (~2 traces per word), which is exactly what lets fixes show.
 *
 * **Directions.** `A=confirm → B=tune` is the TUNE direction: its phase-B outcomes are
 * tune-half traces, and the policy is selected on it alone. `A=tune → B=confirm` is the
 * CONFIRM direction the ship bar is judged on. Both are always printed.
 *
 * ## Exactness
 *
 * Each trace is decoded ONCE with `topK = beamWidth`; every policy/evidence combination is a
 * re-rank of that final beam, which is exact because the prior is an additive final-score
 * term. A fidelity check asserts the re-rank equals a real prior decode on a slice.
 *
 * ## Units
 *
 * Every trace appears once per arm, so fixed/broken counts ARE distinct traces; distinct
 * words are printed beside them. Blast radius is per LIFTED WORD: of the phase-B traces that
 * carry the word in-beam as a non-target, how many it took rank 1 from the target.
 *
 * Gated like the other corpus replays: `-PgeoFull=true`, the local (never committed) trace
 * pool and the ORT natives. [gitFlipsAfterAtMostThreeCorrections] needs only the natives.
 */
class CorrectionPriorReplayTest {

    private fun gate(): Boolean {
        if (System.getProperty("geoFull") != "true") {
            println("[skip] correction-prior replay — set -PgeoFull=true to run")
            return false
        }
        val f = LearnedPriorTracePool.traceFile
        Assume.assumeTrue("no trace pool at ${f.path} (local-only)", f.isFile)
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so onnxruntime.native.path is set",
            CtcReplayEngine.ortAvailable(),
        )
        return true
    }

    /** One final-beam candidate as the re-rank needs it. */
    private class Cand(val word: String, val finalScore: Double, val logFreq: Double, val eligible: Boolean)

    /** One decoded trace: the full NONE beam in decoder order (empty = the decoder gave up). */
    private class Decoded(val row: Row, val cands: List<Cand>, val targetIsLexicon: Boolean) {
        val top1: String? get() = cands.firstOrNull()?.word
    }

    /** Top-1 after adding [bonus] to every candidate; ties keep decoder order. */
    private fun rerankTop1(cands: List<Cand>, bonus: (Cand) -> Double): String? {
        var best: Cand? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in cands) {
            val s = c.finalScore + bonus(c)
            if (s > bestScore) { best = c; bestScore = s }
        }
        return best?.word
    }

    /** A deterministic uniform in [0, 1) per (trace, salt) — the correction / noise coins. */
    private fun coin(d: Decoded, salt: Long): Double =
        SplittableRandom(SEED xor (d.row.id.hashCode().toLong() * GOLDEN) xor salt)
            .nextDouble()

    // ── phase A ──────────────────────────────────────────────────────────────────

    /**
     * Phase-A evidence. [unigram] is `c(Y)`; [pairs] is `c(X→Y)` keyed by [pairKey], where X is
     * the word the swipe auto-inserted (the no-prior top-1) and Y the word the user replaced it
     * with. An empty-beam error yields a unigram correction but no pair (nothing was inserted).
     */
    private class Evidence(val unigram: Map<String, Double>, val pairs: Map<String, Double>)

    private fun pairKey(rejected: String, chosen: String) = "$rejected\u0000$chosen"

    /**
     * Correction evidence from the [history] traces: each lexicon-target error is corrected with
     * probability [rate]; with probability [noise] a CORRECT decode is wrongly "corrected" to
     * its eligible runner-up (a changed-mind bar tap).
     */
    private fun correctionsFrom(history: List<Decoded>, rate: Double, noise: Double): Evidence {
        val c = HashMap<String, Double>()
        val pairs = HashMap<String, Double>()
        for (d in history) {
            val target = d.row.word
            val top = d.top1
            if (top != target) {
                if (d.targetIsLexicon && coin(d, SALT_CORRECT) < rate) {
                    c.merge(target, 1.0, Double::plus)
                    if (top != null) pairs.merge(pairKey(top, target), 1.0, Double::plus)
                }
            } else if (noise > 0.0 && coin(d, SALT_NOISE) < noise) {
                val ru = d.cands.getOrNull(1)
                if (ru != null && ru.eligible) {
                    c.merge(ru.word, 1.0, Double::plus)
                    pairs.merge(pairKey(target, ru.word), 1.0, Double::plus)
                }
            }
        }
        return Evidence(c, pairs)
    }

    // ── phase B ──────────────────────────────────────────────────────────────────

    /** Phase-B outcome of one (direction, policy, evidence) point, with concentration. */
    private class Outcome {
        val tally = RescoringMetrics.Tally()
        val corrected = RescoringMetrics.Tally()   // target itself carries evidence
        val uncorrected = RescoringMetrics.Tally() // target carries none
        var liftedWords = 0
        val exposedTraces = HashSet<String>()
        val fixedTraces = HashSet<String>(); val fixedWords = HashSet<String>()
        val brokenTraces = HashSet<String>(); val brokenWords = HashSet<String>()
        /** Lifted word → phase-B traces carrying it in-beam as a NON-target. */
        val carry = HashMap<String, Int>()
        /** Lifted word → traces it broke (it became top-1 over a correct target). */
        val breaksBy = HashMap<String, Int>()
        val fixedExamples = ArrayList<String>(); val brokenExamples = ArrayList<String>()

        /** (word, breaks, carry) sorted worst ratio first. */
        val blast: List<Triple<String, Int, Int>>
            get() = breaksBy.map { (w, b) -> Triple(w, b, carry[w] ?: 0) }
                .sortedWith(compareByDescending<Triple<String, Int, Int>> {
                    it.second.toDouble() / it.third.coerceAtLeast(1)
                }.thenByDescending { it.second })

        val worstBlast: Double
            get() = blast.firstOrNull()?.let { it.second.toDouble() / it.third.coerceAtLeast(1) } ?: 0.0

        val wordsOverBlastBar: Int
            get() = blast.count { it.second.toDouble() / it.third.coerceAtLeast(1) > BLAST_BAR }

        val carryTotal: Int get() = carry.values.sum()

        fun meetsBar(): Boolean = tally.meetsShipBar() && worstBlast <= BLAST_BAR

        fun line(): String {
            val t = tally
            val worst = blast.firstOrNull()?.let { "${it.second}/${it.third}" } ?: "-"
            return "n=${t.total} lifted=$liftedWords exposed=${exposedTraces.size} " +
                "fixed=${fixedTraces.size}tr/${fixedWords.size}w broken=${brokenTraces.size}tr/" +
                "${brokenWords.size}w wash=${t.wash} Δtop1=%+.4f errRatio=%.3f ".format(t.deltaTop1, t.promotionErrorRatio) +
                "worstBlast=$worst(%.3f) ".format(worstBlast) +
                "over1pct=$wordsOverBlastBar pooledBlast=${brokenTraces.size}/$carryTotal " +
                "| corrected-target f/b=${corrected.fixed}/${corrected.broken} " +
                "uncorrected-target f/b=${uncorrected.fixed}/${uncorrected.broken} meetsBar=${meetsBar()}"
        }
    }

    /** The UNIGRAM arm: `c(Y)` lifts Y in every beam. */
    private fun evaluateUnigram(
        future: List<Decoded>,
        ev: Evidence,
        policy: CtcLearnedPrior.Policy,
        lambda: Double,
    ): Outcome {
        val bonus: (Cand) -> Double = { c ->
            if (!c.eligible) 0.0
            else ev.unigram[c.word]?.let { policy.bonus(CtcLearnedPrior.WordEvidence.corrections(it), c.logFreq, lambda) } ?: 0.0
        }
        return evaluate(
            future,
            lifted = ev.unigram.count { (_, n) -> n > 0.0 && n >= policy.minEffectiveUses },
            bonusFor = { bonus },
            targetCorrected = { d -> (ev.unigram[d.row.word] ?: 0.0) >= policy.minEffectiveUses },
        )
    }

    /**
     * The PAIR-GATED arm: `c(X→Y)` lifts Y only in a beam whose no-prior top-1 IS X — "the
     * decoder is about to make the mistake the user already corrected".
     */
    private fun evaluatePair(
        future: List<Decoded>,
        ev: Evidence,
        policy: CtcLearnedPrior.Policy,
        lambda: Double,
    ): Outcome = evaluate(
        future,
        lifted = ev.pairs.count { (_, n) -> n > 0.0 && n >= policy.minEffectiveUses },
        bonusFor = { d ->
            val x = d.top1
            { c ->
                if (!c.eligible || x == null || c.word == x) 0.0
                else ev.pairs[pairKey(x, c.word)]?.let {
                    policy.bonus(CtcLearnedPrior.WordEvidence.corrections(it), c.logFreq, lambda)
                } ?: 0.0
            }
        },
        targetCorrected = { d ->
            val x = d.top1
            x != null && (ev.pairs[pairKey(x, d.row.word)] ?: 0.0) >= policy.minEffectiveUses
        },
    )

    private fun evaluate(
        future: List<Decoded>,
        lifted: Int,
        bonusFor: (Decoded) -> ((Cand) -> Double),
        targetCorrected: (Decoded) -> Boolean,
    ): Outcome {
        val out = Outcome()
        out.liftedWords = lifted
        for (d in future) {
            if (d.cands.isEmpty()) { out.tally.record(RescoringMetrics.Outcome.UNCHANGED); continue }
            val target = d.row.word
            val bonus = bonusFor(d)
            var exposed = false
            for (c in d.cands) {
                if (bonus(c) > 0.0) {
                    exposed = true
                    if (c.word != target) out.carry.merge(c.word, 1, Int::plus)
                }
            }
            if (exposed) out.exposedTraces.add(d.row.id)
            val before = d.top1
            val after = if (exposed) rerankTop1(d.cands, bonus) else before
            val o = RescoringMetrics.classify(target, before, after)
            out.tally.record(o)
            (if (targetCorrected(d)) out.corrected else out.uncorrected).record(o)
            when (o) {
                RescoringMetrics.Outcome.FIXED -> {
                    out.fixedTraces.add(d.row.id); out.fixedWords.add(target)
                    if (out.fixedExamples.size < 8) out.fixedExamples.add("$target: $before->$after")
                }
                RescoringMetrics.Outcome.BROKEN -> {
                    out.brokenTraces.add(d.row.id); out.brokenWords.add(target)
                    // Only a lifted word can take rank 1 from an unlifted-or-less-lifted target.
                    out.breaksBy.merge(after!!, 1, Int::plus)
                    if (out.brokenExamples.size < 8) out.brokenExamples.add("$target: $before->$after")
                }
                else -> Unit
            }
        }
        return out
    }

    // ── the replay ───────────────────────────────────────────────────────────────

    private data class GridKey(val cMin: Double, val nSat: Double, val bCap: Double) {
        fun policy() = CtcLearnedPrior.Policy(nSat = nSat, bCap = bCap, minEffectiveUses = cMin)
        override fun toString() = "cMin=%.0f N_SAT=%.0f B_CAP=%.2f".format(cMin, nSat, bCap)
    }

    @Test
    fun correctionDrivenReplay() {
        if (!gate()) return
        val rows = LearnedPriorTracePool.loadAll().filter { r -> r.word.all { it in 'a'..'z' } }
        CtcReplayEngine.build("en").use { engine ->
            val lambda = engine.scoringParams.lambda
            val decoder = engine.fullBeamDecoder()
            val started = System.nanoTime()
            val decoded = rows.map { row ->
                Decoded(row, decoder.decode(row.x, row.y, row.t).map {
                    Cand(it.word, it.finalScore, it.logFreq, engine.isLexiconWord(it.word))
                }, engine.isLexiconWord(row.word))
            }
            val sec = (System.nanoTime() - started) / 1e9
            val tuneHalf = decoded.filter { it.row.tuneHalf }
            val confirmHalf = decoded.filterNot { it.row.tuneHalf }
            println("[CP] decoded ${decoded.size} traces (${rows.map { it.word }.toSet().size} words) " +
                "in %.0fs EP=${CtcReplayEngine.executionProvider} λ=$lambda; tune=${tuneHalf.size} confirm=${confirmHalf.size}".format(sec))
            for ((label, half) in listOf("tune" to tuneHalf, "confirm" to confirmHalf)) {
                val err = half.filter { it.top1 != it.row.word }
                println("[CP] $label half: errors=${err.size} (lexicon targets=${err.count { it.targetIsLexicon }}, " +
                    "distinct words=${err.map { it.row.word }.toSet().size}, target in beam=" +
                    "${err.count { d -> d.cands.any { it.word == d.row.word } }})")
            }
            val tuneWords = tuneHalf.map { it.row.word }.toSet()
            val confirmWords = confirmHalf.map { it.row.word }.toSet()
            println("[CP] words in both halves=${tuneWords.intersect(confirmWords).size} " +
                "(tune-only=${(tuneWords - confirmWords).size}, confirm-only=${(confirmWords - tuneWords).size})")

            // Directions: (label, history, future). TUNE selects; CONFIRM carries the bar.
            val dirTune = Triple("TUNE    (A=confirm→B=tune)", confirmHalf, tuneHalf)
            val dirConfirm = Triple("CONFIRM (A=tune→B=confirm)", tuneHalf, confirmHalf)

            // FIDELITY: re-rank must equal a real prior decode (default policy, r = 1).
            val fidEvidence = correctionsFrom(dirConfirm.second, 1.0, 0.0).unigram
            val fidPrior = CtcLearnedPrior(
                evidence = { w -> fidEvidence[w]?.let { CtcLearnedPrior.WordEvidence.corrections(it) } },
                isLexiconWord = engine::isLexiconWord,
            )
            val priorDecoder = engine.fullBeamDecoder(fidPrior)
            var fidChanged = 0
            val fidSlice = dirConfirm.third.filter { d -> d.cands.any { fidEvidence.containsKey(it.word) } }
                .take(FIDELITY_SAMPLE)
            for (d in fidSlice) {
                val direct = priorDecoder.decode(d.row.x, d.row.y, d.row.t).firstOrNull()?.word
                val rerank = rerankTop1(d.cands) { c -> fidPrior.bonusFor(c.word, c.logFreq, lambda) }
                if (direct != d.top1) fidChanged++
                assertWithMessage("re-rank must equal the prior decode for '${d.row.word}'")
                    .that(rerank).isEqualTo(direct)
            }
            println("[CP] fidelity: ${fidSlice.size} exposed traces re-ranked == prior decode ($fidChanged changed top-1)")

            // Evidence summary per direction and rate.
            for ((label, hist, _) in listOf(dirTune, dirConfirm)) for (rate in RATES) {
                val ev = correctionsFrom(hist, rate, 0.0)
                val hist2 = ev.unigram.values.groupingBy { it.toInt() }.eachCount().toSortedMap()
                val histP = ev.pairs.values.groupingBy { it.toInt() }.eachCount().toSortedMap()
                println("[CP] evidence $label r=$rate: ${ev.unigram.size} corrected words, " +
                    "${ev.unigram.values.sum().toInt()} corrections, count histogram=$hist2; " +
                    "${ev.pairs.size} (X→Y) pairs, histogram=$histP")
            }

            val grid = ArrayList<GridKey>()
            for (cMin in C_MIN_GRID) for (nSat in N_SAT_GRID) for (bCap in B_CAP_INFO + B_CAP_GRID) {
                grid.add(GridKey(cMin, nSat, bCap))
            }

            for (arm in ARMS) runArm(arm, grid, dirTune, dirConfirm, lambda)

            // ADVERSARIAL (b) — single-word blast radius over the WHOLE pool: every eligible
            // word that sits in ≥ HUB_MIN_CARRY beams as a non-target, lifted ALONE at a
            // saturated correction count under the default policy. "What if the user's one
            // correction were this word" — the UNIGRAM rule's worst case.
            hubBlast(decoded, CtcLearnedPrior.Policy(), lambda)
            // Same, pair-gated: every (correct top-1 X, in-beam Y) pair lifted alone — "the user
            // once replaced X with Y but also genuinely swipes X".
            pairBlast(decoded, CtcLearnedPrior.Policy(), lambda)
        }
    }

    private fun evalArm(arm: String, fut: List<Decoded>, ev: Evidence, p: CtcLearnedPrior.Policy, lambda: Double) =
        if (arm == ARM_PAIR) evaluatePair(fut, ev, p, lambda) else evaluateUnigram(fut, ev, p, lambda)

    /** The full grid × rates for one evidence arm, then selection (tune) and the bar (confirm). */
    private fun runArm(
        arm: String,
        grid: List<GridKey>,
        dirTune: Triple<String, List<Decoded>, List<Decoded>>,
        dirConfirm: Triple<String, List<Decoded>, List<Decoded>>,
        lambda: Double,
    ) {
        // results[rate][key] = (tuneOutcome, confirmOutcome)
        val results = LinkedHashMap<Double, LinkedHashMap<GridKey, Pair<Outcome, Outcome>>>()
        for (rate in RATES) {
            val evT = correctionsFrom(dirTune.second, rate, 0.0)
            val evC = correctionsFrom(dirConfirm.second, rate, 0.0)
            val m = LinkedHashMap<GridKey, Pair<Outcome, Outcome>>()
            for (k in grid) {
                val p = k.policy()
                m[k] = evalArm(arm, dirTune.third, evT, p, lambda) to evalArm(arm, dirConfirm.third, evC, p, lambda)
            }
            results[rate] = m
        }
        for ((rate, m) in results) {
            println("[CP] ═══ $arm r=$rate ═══")
            for ((k, v) in m) {
                println("[CP] $arm TUNE    r=$rate $k ${v.first.line()}")
                println("[CP] $arm CONFIRM r=$rate $k ${v.second.line()}")
            }
        }

        // SELECTION — tune direction, r = SELECT_RATE, the pre-registered grid only. Rule fixed
        // before looking: among points clearing the bar, max (fixed − broken); then the less
        // aggressive point (smaller B_CAP, larger cMin, larger N_SAT).
        val sel = results.getValue(SELECT_RATE).filterKeys { k -> B_CAP_GRID.any { it == k.bCap } }
        val clearing = sel.entries.filter { it.value.first.meetsBar() }
        val order = compareByDescending<Map.Entry<GridKey, Pair<Outcome, Outcome>>> {
            it.value.first.tally.fixed - it.value.first.tally.broken
        }.thenBy { it.key.bCap }.thenByDescending { it.key.cMin }.thenByDescending { it.key.nSat }
        val chosen = clearing.sortedWith(order).firstOrNull()
        // If nothing clears, report the best tune net (same ordering) for the record.
        val reported = chosen?.key ?: sel.entries.sortedWith(order).first().key
        println("[CP] ═══ $arm SELECTION (tune direction, r=$SELECT_RATE) ═══")
        println("[CP] $arm clearing on tune: ${clearing.map { it.key }}")
        println("[CP] $arm SELECTED: ${chosen?.key ?: "none — reporting best tune net: $reported"}")
        for (rate in RATES) {
            val (t, c) = results.getValue(rate).getValue(reported)
            println("[CP] $arm SEL r=$rate TUNE    ${t.line()}")
            println("[CP] $arm SEL r=$rate CONFIRM ${c.line()}")
            println("[CP]   confirm fixes : ${c.fixedExamples}")
            println("[CP]   confirm breaks: ${c.brokenExamples}")
            println("[CP]   tune fixes    : ${t.fixedExamples}")
            println("[CP]   tune breaks   : ${t.brokenExamples}")
            println("[CP]   confirm blast (word breaks/carry, worst first): ${c.blast.take(10).map { "${it.first} ${it.second}/${it.third}" }}")
            println("[CP]   tune    blast: ${t.blast.take(10).map { "${it.first} ${it.second}/${it.third}" }}")
        }
        val (_, confirmSel) = results.getValue(SELECT_RATE).getValue(reported)
        println("[CP] $arm CONFIRM ship bar at $reported: errRatio<${RescoringMetrics.SHIP_BAR_ERROR_RATIO} AND Δ>0 -> " +
            "${confirmSel.tally.meetsShipBar()}; per-word blast ≤ ${BLAST_BAR * 100}% -> " +
            "${confirmSel.worstBlast <= BLAST_BAR}; overall ${confirmSel.meetsBar()}")

        // ADVERSARIAL (a) — wrong corrections: changed-mind taps on correct decodes.
        val p = reported.policy()
        for (noise in NOISE_RATES) for ((label, hist, fut) in listOf(dirTune, dirConfirm)) {
            val ev = correctionsFrom(hist, SELECT_RATE, noise)
            val o = evalArm(arm, fut, ev, p, lambda)
            println("[CP] $arm ADV noise=$noise $label ${o.line()}")
            println("[CP]   blast: ${o.blast.take(8).map { "${it.first} ${it.second}/${it.third}" }}")
        }
    }

    /**
     * Adversarial arm (b), pair-gated: for every CORRECT decode, every eligible in-beam Y is a
     * hypothetical pair (top-1 → Y) at a saturated count, lifted alone. Reports how often Y
     * then takes rank 1 — the break rate for a user who once replaced X with Y but also
     * genuinely swipes X. Pooled over (X, Y) pairs with at least [HUB_MIN_CARRY] carriers.
     */
    private fun pairBlast(decoded: List<Decoded>, policy: CtcLearnedPrior.Policy, lambda: Double) {
        val saturated = maxOf(policy.nSat, policy.minEffectiveUses)
        val carry = HashMap<String, Int>(); val breaks = HashMap<String, Int>()
        var correctTraces = 0; var tracesWithAnyBreak = 0
        for (d in decoded) {
            val x = d.top1 ?: continue
            if (x != d.row.word) continue
            correctTraces++
            var any = false
            val top = d.cands[0]
            for (c in d.cands.drop(1)) {
                if (!c.eligible) continue
                val k = pairKey(x, c.word)
                carry.merge(k, 1, Int::plus)
                val b = policy.bonus(CtcLearnedPrior.WordEvidence.corrections(saturated), c.logFreq, lambda)
                if (c.finalScore + b > top.finalScore) { breaks.merge(k, 1, Int::plus); any = true }
            }
            if (any) tracesWithAnyBreak++
        }
        val totalCarry = carry.values.sum(); val totalBreaks = breaks.values.sum()
        println("[CP] ADV pair blast @ saturated c=$saturated: $correctTraces correct traces; " +
            "$tracesWithAnyBreak (%.2f%%) have ≥1 in-beam Y that one saturated (top1→Y) pair would promote; ".format(
                100.0 * tracesWithAnyBreak / correctTraces.coerceAtLeast(1)) +
            "pooled (X,Y) carriers $totalBreaks/$totalCarry = %.3f%%".format(100.0 * totalBreaks / totalCarry.coerceAtLeast(1)))
        val frequent = carry.filterValues { it >= PAIR_MIN_CARRY }
        val rows = frequent.map { (k, n) -> Triple(k.replace('\u0000', '→'), breaks[k] ?: 0, n) }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second.toDouble() / it.third }.thenByDescending { it.third })
        println("[CP]   pairs with ≥$PAIR_MIN_CARRY carriers: ${rows.size}; exceeding 1%: " +
            "${rows.count { it.second.toDouble() / it.third > BLAST_BAR }}; worst: " +
            "${rows.take(12).map { "${it.first} ${it.second}/${it.third}" }}")
    }

    /** Adversarial arm (b): per-word saturated single lift over every trace carrying it. */
    private fun hubBlast(decoded: List<Decoded>, policy: CtcLearnedPrior.Policy, lambda: Double) {
        val carriers = HashMap<String, MutableList<Decoded>>()
        for (d in decoded) for (c in d.cands) {
            if (c.eligible && c.word != d.row.word) carriers.getOrPut(c.word) { ArrayList() }.add(d)
        }
        val saturated = maxOf(policy.nSat, policy.minEffectiveUses)
        val rows = ArrayList<Triple<String, Int, Int>>()
        var totalBreaks = 0; var totalCarry = 0
        for ((w, list) in carriers) {
            if (list.size < HUB_MIN_CARRY) continue
            var breaks = 0
            for (d in list) {
                if (d.top1 != d.row.word) continue
                val after = rerankTop1(d.cands) { c ->
                    if (c.word == w) policy.bonus(CtcLearnedPrior.WordEvidence.corrections(saturated), c.logFreq, lambda) else 0.0
                }
                if (after == w) breaks++
            }
            rows.add(Triple(w, breaks, list.size))
            totalBreaks += breaks; totalCarry += list.size
        }
        val over = rows.count { it.second.toDouble() / it.third > BLAST_BAR }
        val anyBreak = rows.count { it.second > 0 }
        println("[CP] ADV hub blast @ saturated c=$saturated ($policy): ${rows.size} words carried in ≥$HUB_MIN_CARRY " +
            "beams; $anyBreak break ≥1 trace; $over exceed ${BLAST_BAR * 100}% of their carriers; " +
            "pooled $totalBreaks/$totalCarry")
        val worst = rows.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second.toDouble() / it.third }
            .thenByDescending { it.second }).take(15)
        println("[CP]   worst ratio: ${worst.map { "${it.first} ${it.second}/${it.third}" }}")
        val most = rows.sortedByDescending { it.second }.take(15)
        println("[CP]   most breaks: ${most.map { "${it.first} ${it.second}/${it.third}" }}")
        val hubs = rows.sortedByDescending { it.third }.take(10)
        println("[CP]   biggest hubs: ${hubs.map { "${it.first} ${it.second}/${it.third}" }}")
    }

    // ══ The audit's own case, pinned ═══════════════════════════════════════════════

    /**
     * PIN — under the DEFAULT (correction-driven) policy, the ambiguous-middle-key `git` trace
     * (i/o split, where the shipped decoder gives `got`) flips to `git` after at most three
     * corrections to `git` and nothing else. Self-contained; skips only without ORT natives.
     */
    @Test
    fun gitFlipsAfterAtMostThreeCorrections() {
        Assume.assumeTrue("ONNX natives absent", CtcReplayEngine.ortAvailable())
        CtcReplayEngine.build("en").use { engine ->
            val base = engine.fullBeamDecoder()
            var ambiguous = 0
            for (towardO in LearnedPriorTracePool.AMBIGUOUS_FRACTIONS) {
                val (x, y, t) = LearnedPriorTracePool.ambiguousGit(engine.layoutGeometry, towardO)
                val none = base.decode(x, y, t)
                var flipAt = -1
                for (k in 1..MAX_PIN_CORRECTIONS) {
                    val prior = CtcLearnedPrior(
                        evidence = { w -> if (w == "git") CtcLearnedPrior.WordEvidence.corrections(k.toDouble()) else null },
                        isLexiconWord = engine::isLexiconWord,
                    )
                    if (engine.fullBeamDecoder(prior).decode(x, y, t).firstOrNull()?.word == "git") { flipAt = k; break }
                }
                println("[CP-pin] i/o=%.2f NONE top3=%s -> git rank 1 after %s corrections".format(
                    towardO, none.take(3).map { it.word }, if (flipAt < 0) ">$MAX_PIN_CORRECTIONS" else flipAt))
                if (none.firstOrNull()?.word == "git") continue
                ambiguous++
                assertWithMessage("i/o=$towardO: git must reach rank 1 within 3 corrections (flipAt=$flipAt)")
                    .that(flipAt).isIn(1..3)
            }
            assertWithMessage("fixture: at least one ambiguous shape must decode as non-git without a prior")
                .that(ambiguous).isGreaterThan(0)
        }
    }

    private companion object {
        /** Fidelity: re-rank vs real prior decode on this many exposed confirm traces. */
        const val FIDELITY_SAMPLE = 60

        /** Correction rates (share of lexicon-target errors the user corrects). */
        val RATES = doubleArrayOf(1.0, 0.7, 0.5)

        /** Selection happens at this rate (the upper bound on exposure). */
        const val SELECT_RATE = 1.0

        /** Policy grid. */
        val C_MIN_GRID = doubleArrayOf(1.0, 2.0, 3.0)
        val N_SAT_GRID = doubleArrayOf(2.0, 3.0, 5.0)
        val B_CAP_GRID = doubleArrayOf(1.0, 1.5, 2.5)

        /** Informational smaller caps (Stage-0 median in-beam deficit is 0.52 nats); never selected. */
        val B_CAP_INFO = doubleArrayOf(0.5, 0.75)

        /** Evidence arms: the pre-registered unigram hypothesis and the pair-gated variant. */
        const val ARM_UNIGRAM = "UNIGRAM"
        const val ARM_PAIR = "PAIR"
        val ARMS = listOf(ARM_UNIGRAM, ARM_PAIR)

        /** Pair-blast report: (X, Y) pairs with at least this many correct-X carriers. */
        const val PAIR_MIN_CARRY = 5

        /** Changed-mind "wrong correction" rates on correct decodes (adversarial arm a). */
        val NOISE_RATES = doubleArrayOf(0.02, 0.05)

        /** Ship bar: no lifted word breaks more than 1% of the traces carrying it in-beam. */
        const val BLAST_BAR = 0.01

        /** Adversarial arm (b): words carried as non-target in at least this many beams. */
        const val HUB_MIN_CARRY = 20

        /** Pin search bound (the bar is ≤ 3). */
        const val MAX_PIN_CORRECTIONS = 6

        const val SEED = 20260926L

        /** 2^64/φ — spreads the 32-bit trace hash across the seed. */
        const val GOLDEN = -7046029254386353131L
        const val SALT_CORRECT = 0x436F7272L
        const val SALT_NOISE = 0x4E6F6973L
    }
}
