package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.geometric.GeoTestFixtures
import tribixbite.cleverkeys.swipe.geometric.GeometricEngineConfig
import tribixbite.cleverkeys.swipe.geometric.GeometricSwipeEngine
import tribixbite.cleverkeys.swipe.geometric.GeoLayoutFixtures
import tribixbite.cleverkeys.swipe.geometric.TracePoint
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Stages B-D of the step-5 evidence harness — the actual A/B.
 *
 * See `docs/plans/2026-08-22-context-rescoring-step5-harness.md`. This answers the one question
 * that gates flipping `swipe_context_rescoring` to on: does rescoring fix more decodes than it
 * breaks, and by enough of a margin.
 *
 * ## What it does
 *
 * For each learned bigram `(w1, w2)` that has a real swipe trace for `w2`, it simulates the exact
 * situation the feature targets — *the user committed `w1`, then swiped `w2`* — by decoding the
 * trace and scoring the slate twice: as the engine ordered it, and as [SwipeContextRescorer] would
 * reorder it given `w1` as context. [RescoringMetrics] classifies each pair of outcomes against
 * the TARGET, never against the shape of the change.
 *
 * ## Two engines, and which one decides
 *
 * **CTC is the PRIMARY arm** and the only one that can gate a default flip. It is the default
 * swipe engine, and — decisively — the rank-1 guard is a ratio of ITS scores. Every trace is
 * therefore decoded TWICE, once per engine, over the identical sample, so the two arms differ only
 * in the decoder and are directly comparable.
 *
 * The geometric arm is retained as a SECONDARY reference. The rescorer sits at
 * `SuggestionHandler.handleSwipePredictionResults`, which both engines pass through, so geometric
 * numbers are genuine evidence about the rescorer as a mechanism — they simply do not transfer to
 * the shipping default, because the two slates have very different score distributions.
 *
 * Running CTC in pure JVM is possible because `build.gradle`'s `extractOrtNative` supplies the
 * BIONIC arm64 ONNX natives from the `onnxruntime-android` AAR; the `onnxruntime` JAR's own
 * glibc-linked `.so` cannot load on Termux. See [CtcReplayEngine].
 *
 * ## Gating
 *
 * Skips unless the local corpora exist. They are never committed — one is a record of a person's
 * typing, the other a 552 MB third-party corpus with no stated licence. `Assume` rather than
 * failure is right here: their absence on a fresh checkout is expected, not a defect.
 */
class ContextRescoringReplayTest {

    private val scheduler = ScheduledThreadPoolExecutor(1)

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
    }

    private val cacheDir: File = run {
        val override = System.getenv("CLEVERKEYS_TEST_CACHE")
        if (!override.isNullOrEmpty()) File(override)
        else File(System.getProperty("user.home"), ".cache/cleverkeys-test")
    }
    private val corporaDir = File(System.getProperty("user.home"), ".cache/cleverkeys-corpora")
    private val traceFile = File(cacheDir, "combined_english_swipes.jsonl.gz")

    /**
     * One real swipe trace, carried in BOTH coordinate frames the two engines require.
     *
     * Not a `data class`: it holds arrays, whose generated `equals`/`hashCode` compare by identity
     * and would be quietly wrong if anything ever put a [Row] in a set.
     *
     * @property pts PIXELS on this row's own canvas — what [GeometricSwipeEngine] expects.
     * @property nx,ny,nt the corpus's own NORMALIZED [0,1] coordinates plus millisecond
     *   timestamps — what [CtcReplayEngine] expects, over the golden layout's frame.
     */
    private class Row(
        val word: String,
        val w: Float,
        val h: Float,
        val pts: List<TracePoint>,
        val nx: DoubleArray,
        val ny: DoubleArray,
        val nt: DoubleArray,
    )

    private fun loadTraces(limitPerWord: Int): Map<String, List<Row>> {
        val byWord = HashMap<String, MutableList<Row>>()
        GZIPInputStream(traceFile.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                if (word.isEmpty()) continue
                // AUDIT D2: read WITHOUT creating. `getOrPut` here installed an empty bucket for
                // every distinct word in the file BEFORE the filters below could reject its rows,
                // so `traces.keys` held 4,907 words when only 2,197 have a usable trace. Three
                // consequences, all silent: the printed pool size was 2.2x reality; `pairable`
                // counted bigrams whose word2 has NO trace; and `neighboursOf` drew decoys from
                // those phantom keys, which then contributed nothing through `flatMap` — so the
                // adversarial arm ran below its requested decoy count.
                val existing = byWord[word]
                if (existing != null && existing.size >= limitPerWord) continue
                val w = o.optDouble("w").toFloat()
                val h = o.optDouble("h").toFloat()
                val ptsArr = o.optJSONArray("pts") ?: continue
                val n = ptsArr.length()
                if (n < 3) continue
                val nx = DoubleArray(n); val ny = DoubleArray(n); val nt = DoubleArray(n)
                val pts = ArrayList<TracePoint>(n)
                var malformed = false
                for (i in 0 until n) {
                    val p = ptsArr.optJSONArray(i)
                    if (p == null) { malformed = true; break }
                    nx[i] = p.optDouble(0); ny[i] = p.optDouble(1); nt[i] = p.optDouble(2)
                    // Corpus points are NORMALIZED [x, y, t] in [0,1]; the GEOMETRIC engine wants
                    // PIXELS on the row's own canvas. Passing them raw makes every trace one pixel
                    // wide and decodes nothing — which is exactly what the first run of this test
                    // did. The CTC engine takes the normalized values unchanged.
                    pts.add(TracePoint(nx[i].toFloat() * w, ny[i].toFloat() * h, nt[i].toLong()))
                }
                if (malformed) continue
                // Reject the ~47% of rows whose third column is not a timestamp — they decode
                // to confident nonsense and would silently pad the denominator. This filter was
                // ABSENT from the runs published before 2026-08-23.
                // Create the bucket ONLY once a row has actually survived every filter.
                if (TraceCorpusQuality.hasUsableTimestamps(nt)) {
                    byWord.getOrPut(word) { ArrayList() }.add(Row(word, w, h, pts, nx, ny, nt))
                }
            }
        }
        return byWord
    }

    /** Everything one engine produced, kept per-arm because the safety denominator is per-arm. */
    private class EngineResults(val label: String) {
        val favourable = RescoringMetrics.Tally()
        val adversarial = RescoringMetrics.Tally()

        /** Cases where SOME candidate had context evidence — the only ones rescoring can act on. */
        var favourableExposed = 0
        var adversarialExposed = 0

        /**
         * Exposed "adversarial" cases whose evidence actually points AT their own target (audit
         * S1) — the decoy is itself a learned continuation of the context word. These cannot
         * break, so they are excluded from the honest break-rate denominator.
         */
        var adversarialEvidenceOnTarget = 0

        /** Cases this engine returned a usable (>= 2 candidate) slate for. */
        var decoded = 0

        /** Cases skipped because the slate was empty or a single candidate — nothing to reorder. */
        var unusableSlate = 0

        /**
         * Cases where evidence was MISSED only because the slate carries an a-z surface while the
         * store is keyed on the apostrophe form (`dont` vs `don't`). Bounds the cost of not
         * applying the adapter's contraction overlay — see the report footer.
         */
        var apostropheMissed = 0

        // ── Why an exposed case did or did not become a promotion ───────────────────────
        //
        // A four-way decomposition of the exposed cases, evaluated with the SHIPPED guard's own
        // public predicates ([SwipeContextRescorer.R_MIN], [SwipeContextRescorer.promotableToRankOne])
        // rather than a restatement of them, so the breakdown cannot drift from the real rule.
        //
        // Without this, a run reporting "0 fixed, 0 broken" is indistinguishable between "the
        // feature is inert on this engine" and "the harness never exercised it" — and those have
        // opposite consequences for the ship decision.

        /** Evidence sat ONLY on the engine's own top-1: rescoring reinforces, rank 1 cannot move. */
        var exposedTopOnly = 0

        /** A non-top candidate had evidence, but none reached `R_MIN * top` — un-promotable. */
        var exposedBelowRatio = 0

        /** Cleared the score-ratio guard but failed the strict `NextWordPredictor` floors. */
        var exposedFloorsBlocked = 0

        /** Cleared BOTH rank-1 protections — the guard permits a promotion here. */
        var exposedPromotable = 0

        /** Runner-up / top-1 score ratio per slate: the quantity `R_MIN` is compared against. */
        val runnerUpRatios = ArrayList<Double>()

        /**
         * Cases this engine ALREADY got right with no rescoring, per arm.
         *
         * The fix headroom. A fix requires the engine to have been wrong, so an engine that is
         * already right has nothing for context to repair — and "few fixes" then means "little was
         * broken", not "the mechanism failed". Without this denominator the two readings are
         * indistinguishable, and they point at opposite decisions.
         */
        var favourableBaselineCorrect = 0
        var adversarialBaselineCorrect = 0

        /** A few concrete outcomes, so the report can be sanity-checked rather than trusted. */
        val brokenExamples = ArrayList<String>()
        val fixedExamples = ArrayList<String>()

        /**
         * CONCENTRATION of the outcomes — how many DISTINCT traces and words they came from.
         *
         * The unit of the tally is `(context, trace, arm)`, which is right: context is the
         * independent variable, so the same trace under two contexts is two experiments. But that
         * makes a raw count ambiguous in the other direction — 24 breakages could be 24 unlucky
         * words or ONE word caught under 24 different contexts, and those support very different
         * conclusions. A count without its concentration cannot distinguish them.
         */
        val brokenTraces = HashSet<String>()
        val fixedTraces = HashSet<String>()
        val brokenWords = HashMap<String, Int>()
        val fixedWords = HashMap<String, Int>()

        /**
         * The same concentration correction applied to the EXPOSURE denominators.
         *
         * Without these, a breakage count concentrated on one trace would be compared against an
         * exposure count inflated by context-multiplicity — deflating the apparent rate by exactly
         * the factor the numerator was inflated by, and hiding how little independent evidence
         * there is. Numerator and denominator have to be counted in the same unit.
         */
        val favourableExposedTraces = HashSet<String>()
        val adversarialExposedTraces = HashSet<String>()
        val favourableTraces = HashSet<String>()
        val adversarialTraces = HashSet<String>()

        /**
         * The (WEIGHT, R_MIN) grid, tallied separately on a TUNE and a CONFIRM half.
         *
         * Spec §7.1 asks for exactly this — "tune `W`/`R_MIN` on a tune half, confirm on held-out
         * half" — and it was the one part of step 5 never delivered. The grid is evaluated on the
         * decode that already happened, so N points cost no extra decoding; only the pure
         * log-linear sort re-runs.
         *
         * The split is by TRACE, not by case: every case of one trace lands in the same half, or
         * the same physical swipe would appear in both and the "held-out" half would not be held
         * out at all.
         */
        val sweepTune = HashMap<String, RescoringMetrics.Tally>()
        val sweepConfirm = HashMap<String, RescoringMetrics.Tally>()

        var firstError: String? = null

        val combined: RescoringMetrics.Tally
            get() = RescoringMetrics.Tally(
                fixed = favourable.fixed + adversarial.fixed,
                broken = favourable.broken + adversarial.broken,
                unchanged = favourable.unchanged + adversarial.unchanged,
                wash = favourable.wash + adversarial.wash,
            )
    }

    /**
     * The replay. Prints a report and asserts only that the harness RAN — it does not assert a
     * verdict.
     *
     * That restraint is deliberate: this is a measurement instrument, and pinning a Δtop-1
     * threshold here would turn an experiment into a regression gate on data that is local-only
     * and will differ per machine. The ship decision reads the printed numbers against §7.3;
     * `Tally.meetsShipBar()` encodes the bar and is unit-tested separately.
     */
    @Test
    fun replayContextRescoringOverRealTraces() {
        // OPT-IN. This decodes thousands of traces against the full 98,140-word dictionary and
        // takes many minutes — far too slow to sit in every `runPureTests`. It is a measurement
        // instrument run deliberately, not a regression gate. Same `-PgeoFull` switch the
        // geometric corpus replays use, bridged into a system property by build.gradle.
        //
        //   sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest -PgeoFull=true
        if (System.getProperty("geoFull") != "true") {
            println("[skip] context-rescoring replay — set -PgeoFull=true to run")
            return
        }
        Assume.assumeTrue(
            "no trace pool at ${traceFile.path} — local-only, never committed (skipping)",
            traceFile.exists(),
        )
        // The PRIMARY arm is CTC. Without the bionic ONNX natives there is no primary arm, and a
        // geometric-only report published under this test's name is precisely how the retracted
        // first revision of the eval doc came to exist. Skip rather than silently downgrade.
        Assume.assumeTrue(
            "ONNX natives absent — run via gradle so extractOrtNative + onnxruntime.native.path " +
                "are set. The CTC arm is the primary measurement; refusing to report without it.",
            CtcReplayEngine.ortAvailable(),
        )
        // `-PreplayCorpus=device` selects the maintainer's own export. That arm matters because it
        // is the only one whose ACTIVATION RATE is meaningful: 6,589 pairs sits under
        // BigramStore's 10,000 cap and no word1 exceeds the 20-continuation per-word cap, so
        // NOTHING is evicted (verified 2026-08-23) — unlike the 175,092-row Ubuntu corpus, of
        // which the store discards 46,923 usable rows and whose 225/27,970 survival rate is a
        // harness artefact. It also seeds in seconds (7,815 recordBigram calls vs 1,285,947).
        val preferred = if (corpusPreference == "device") {
            listOf("device_bigrams.json", "ubuntu_bigrams.json")
        } else {
            listOf("ubuntu_bigrams.json", "device_bigrams.json")
        }
        val corpusFile = preferred.asSequence()
            .map { File(corporaDir, it) }
            .firstOrNull { it.exists() }
        Assume.assumeTrue(
            "no bigram corpus in ${corporaDir.path} — derive one with " +
                "scripts/build_ubuntu_bigrams.py (skipping)",
            corpusFile != null,
        )

        val pairs = LearnedBigramCorpus.parse(corpusFile!!, "en")
        val loaded = LearnedBigramCorpus.seed(pairs, "en", scheduler)
        println("[replay] corpus ${corpusFile.name}: $loaded")

        val traces = loadTraces(limitPerWord = tracesPerWord)
        println("[replay] trace pool: ${traces.size} distinct words " +
            "(timestamp-filtered; see TraceCorpusQuality)")

        // Stage B: a pairing needs a usable bigram whose SECOND word has a trace.
        val pairable = pairs.filter { it.usable && traces.containsKey(it.word2) }
        println("[replay] pairable bigrams: ${pairable.size} of ${loaded.usable} usable")
        Assume.assumeTrue("no pairable bigrams — nothing to measure", pairable.isNotEmpty())

        // H1 (audit): `build_ubuntu_bigrams.py` writes rows sorted by DESCENDING frequency, so
        // `take(MAX_PAIRS)` sampled only the extreme head — the pairs with the largest counts and
        // highest probabilities, which pass the promotion floors almost by definition. That
        // flatters the feature and generalises to nobody. Shuffle with a fixed seed instead, so
        // the sample spans the distribution AND a rerun reproduces it.
        // The store caps a language at 10,000 bigrams and prunes by probability, so seeding a
        // larger corpus DISCARDS most of it. Sampling from the corpus therefore measures mostly
        // pairs the store no longer holds — which is what drove exposure to 11 of 6,252 on an
        // earlier run. Sample from what SURVIVED instead, and report both numbers so the
        // capacity constraint stays visible.
        val known = pairable.filter {
            loaded.model.getContextEvidence(it.word2, listOf(it.word1)) != null
        }
        println("[replay] of ${pairable.size} pairable, ${known.size} survived the store's " +
            "${loaded.stored}-entry cap and are actually queryable")
        Assume.assumeTrue("nothing survived seeding — nothing to measure", known.isNotEmpty())

        val rng = java.util.Random(SEED)
        val sampled = known.toMutableList().also { java.util.Collections.shuffle(it, rng) }
            .take(maxPairs)
        println("[replay] sampled ${sampled.size} at random from those (seed=$SEED; " +
            "NOT the frequency-sorted head)")

        // The a-z surface of every apostrophe-bearing store key, used ONLY to bound how much
        // exposure the CTC arm loses by not applying the adapter's contraction overlay. The
        // overlay needs an Android Context; this measures its absence instead of assuming it away.
        val apostropheKeys: Map<String, List<String>> = pairs.asSequence()
            .map { it.word2 }
            .filter { it.indexOf('\'') >= 0 }
            .distinct()
            .groupBy { it.replace("'", "").lowercase() }

        // The GEOMETRIC layout must be built for the CORPUS canvas aspect — `loadShipped` takes
        // one, and the default would put the key grid at a different shape than the traces were
        // drawn on. The CTC layout is the committed golden fixture and takes normalized points.
        // AUDIT D2(d): `traces.values.first().first()` threw if the arbitrary first HashMap bucket
        // was empty — which the pre-fix loader could produce. It worked by luck. Take the first
        // row that exists, and fail with a real message if none does.
        val sampleRow = traces.values.firstOrNull { it.isNotEmpty() }?.first()
        Assume.assumeTrue("no usable traces survived the timestamp filter", sampleRow != null)
        val aspect = sampleRow!!.w / sampleRow.h

        // The geometric arm builds ONE layout for ONE aspect and decodes every trace against it.
        // That is only valid if the corpus really has a single canvas. Measured 2026-08-23: all
        // 8,607 rows are 360x215. Asserted rather than assumed, because a corpus that later mixes
        // canvases would silently decode most traces against the wrong key geometry.
        val canvases = traces.values.flatten().map { it.w to it.h }.toSet()
        assertWithMessage(
            "the geometric arm builds one layout from the first row's aspect, so a multi-canvas " +
                "corpus would decode most traces against the wrong key grid. Found: $canvases"
        ).that(canvases).hasSize(1)
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = aspect)
        val dict = GeoTestFixtures.englishCkdt()
        val geo = GeometricSwipeEngine(GeometricEngineConfig()).also { it.warmUp(layout, dict) }
        println("[replay] canvas ${sampleRow.w}x${sampleRow.h} aspect=%.3f".format(aspect))

        val ctcResults = EngineResults("CTC (default engine)")
        val geoResults = EngineResults("geometric")
        var cases = 0
        val allWords = traces.keys.toList().sorted()
        val seenCases = HashSet<String>()
        val adversarialUses = HashMap<String, Int>()
        val started = System.nanoTime()

        CtcReplayEngine.build("en").use { ctc ->
            for (pair in sampled) {
                // TWO arms, and the second is the one that can actually condemn the feature.
                //
                //  - FAVOURABLE: swipe w2 after committing w1, i.e. the learned context points AT
                //    the target. Only this arm can produce a FIX.
                //  - ADVERSARIAL: swipe some OTHER word after committing w1, i.e. the context
                //    points AWAY from the target. Only this arm can produce a BREAK.
                //
                // Running the favourable arm alone reports 0 breakages BY CONSTRUCTION and would
                // read as proof of safety. It is not — it is proof that the sampling never asked
                // the question.
                //
                // Adversarial decoys are words CONFUSABLE with the continuation, not random ones.
                // Rescoring can only reorder what the engine already returned, never insert, so a
                // breakage needs the learned continuation to be IN the slate competing against the
                // correct answer. A random decoy almost never creates that conjunction (measured:
                // 2,012 random decoys produced context evidence on ~0 traces), which makes a
                // random-decoy arm look safe without having tested anything.
                val decoys = neighboursOf(pair.word2, allWords, decoysPerPair)
                val armed = traces.getValue(pair.word2).map { it to true } +
                    decoys.flatMap { d -> traces.getValue(d).map { it to false } }

                for ((row, favourableArm) in armed) {
                    // H3 (audit), and the fix needs care. Head pairs share `word2` heavily, so the
                    // same physical trace was decoded once per pair ending in that word. But those
                    // are NOT duplicates: "committed w1 then swiped X" and "committed w1' then
                    // swiped X" are different experiments — the context is the independent
                    // variable, and deduping by trace alone would throw away the whole point.
                    //
                    // So the unit is (context, trace, arm). What the audit actually caught was a
                    // LABELLING error: the eval doc called this denominator "swipes", which it is
                    // not. It is context-trace cases, and the report says so.
                    val armTag = if (favourableArm) 'F' else 'A'
                    val caseKey =
                        "$armTag|${pair.word1}|${row.word}|${row.pts.size}|${row.pts.firstOrNull()?.x}"
                    if (!seenCases.add(caseKey)) continue

                    // AUDIT S3 + power. `neighboursOf` is seeded PER WORD, so every pair sharing a
                    // word2 draws the IDENTICAL decoy list — cross-pair decoy diversity is
                    // impossible by construction. With `to` the continuation of 2,086 corpus
                    // pairs, its neighbour `tit` was re-tested under 24 contexts and produced all
                    // 24 breakages of the 2026-08-23 run. That multiplicity is real (a hub
                    // continuation genuinely endangers the same word after many predecessors) but
                    // it buys NO new independent evidence, and the breakage count read as 24
                    // findings when it was one.
                    //
                    // This cap converts multiplicity into distinct traces at the same decode cost.
                    // Default 0 = unlimited, so the published 2026-08-23 baseline stays exactly
                    // reproducible; set -PreplayMaxCtx=N for a power-oriented run.
                    if (!favourableArm && maxContextsPerTrace > 0) {
                        val traceId = "${row.word}|${row.pts.size}|${row.pts.firstOrNull()?.x}"
                        val used = adversarialUses.getOrDefault(traceId, 0)
                        if (used >= maxContextsPerTrace) continue
                        adversarialUses[traceId] = used + 1
                    }
                    cases++

                    // The SAME case through both decoders, so the arms differ only in the engine.
                    score(
                        results = ctcResults,
                        favourableArm = favourableArm,
                        contextWord = pair.word1,
                        row = row,
                        loaded = loaded,
                        apostropheKeys = apostropheKeys,
                    ) {
                        val slate = ctc.decode(row.nx, row.ny, row.nt)
                        slate.words to slate.scores
                    }
                    score(
                        results = geoResults,
                        favourableArm = favourableArm,
                        contextWord = pair.word1,
                        row = row,
                        loaded = loaded,
                        apostropheKeys = apostropheKeys,
                    ) {
                        val r = geo.decode(
                            tribixbite.cleverkeys.swipe.geometric.GeometricSwipeRequest(
                                row.pts, row.w, row.h, layout, dict
                            )
                        )
                        r.words to r.scores
                    }
                }
            }
        }
        val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0

        // Stage D: the report. CTC first, because CTC is what ships.
        println("═══════════════════════════════════════════════════════════════")
        println("  CONTEXT RESCORING REPLAY — en")
        println("  corpus     : ${corpusFile.name} (${loaded.usable} usable of ${loaded.total})")
        println("  sampling   : pairs=${sampled.size} decoys=$decoysPerPair " +
            "tracesPerWord=$tracesPerWord maxCtxPerAdvTrace=" +
            (if (maxContextsPerTrace > 0) "$maxContextsPerTrace" else "unlimited") +
            " seed=$SEED")
        println("  ONNX EP    : ${CtcReplayEngine.executionProvider}")
        println("  cases      : $cases distinct (context, trace, arm) — NOT 'swipes';")
        println("               one trace appears under several contexts, which are separate")
        println("               experiments because context is the independent variable")
        println("  traces     : timestamp-filtered (TraceCorpusQuality) — the ~47% of rows whose")
        println("               third column is not a timestamp are EXCLUDED")
        println("  wall clock : %.1f s for both engines".format(elapsedSec))
        report("PRIMARY", ctcResults)
        report("SECONDARY", geoResults)
        println("  ─────────────────────────────────────────────────────────────")
        println("  ship bar   : Δtop1 > 0 AND breakages < ${RescoringMetrics.SHIP_BAR_ERROR_RATIO}" +
            " of fixes, measured on the PRIMARY (CTC) arm")
        println("  PRIMARY meets bar : ${ctcResults.combined.meetsShipBar()}")
        println("  WARNING: the arm ratio is a SAMPLING choice, not a fact about real usage.")
        println("        These counts are only comparable to reality if favourable and")
        println("        adversarial cases occur in roughly the ratio replayed here.")
        println("═══════════════════════════════════════════════════════════════")

        assertWithMessage(
            "the replay must actually decode something, or it measured nothing. First CTC error: " +
                "${ctcResults.firstError ?: "none"}; first geometric error: " +
                "${geoResults.firstError ?: "none"}"
        ).that(ctcResults.decoded).isGreaterThan(0)
    }

    /**
     * Decode one case with [decode], classify it, and fold it into [results].
     *
     * Takes the decode as a lambda so the two engines share every downstream step — evidence
     * lookup, rank-1 guard, classification. If they did not, a difference in the reported numbers
     * could be a difference in the harness rather than in the decoder, which would make the whole
     * primary/secondary comparison meaningless.
     */
    private fun score(
        results: EngineResults,
        favourableArm: Boolean,
        contextWord: String,
        row: Row,
        loaded: LearnedBigramCorpus.Loaded,
        apostropheKeys: Map<String, List<String>>,
        decode: () -> Pair<List<String>, List<Int>>,
    ) {
        val (words, scores) = try {
            decode()
        } catch (t: Throwable) {
            // Surface the FIRST failure rather than swallowing it. An early run of this test
            // decoded 0 traces and `runCatching{}.getOrNull()` hid the reason.
            if (results.firstError == null) {
                results.firstError = "${t::class.java.simpleName}: ${t.message}"
            }
            return
        }
        // Fewer than two candidates cannot be reordered, so the case carries no information about
        // rescoring either way. Counted separately rather than dropped silently.
        if (words.size < 2 || scores.size != words.size) {
            results.unusableSlate++
            return
        }
        results.decoded++

        val evidence = words.map { w ->
            val cont = loaded.model.getContextEvidence(
                SwipeContextRescorer.storeKey(w), listOf(contextWord)
            )
            if (cont == null) SwipeContextRescorer.Evidence.NONE
            else SwipeContextRescorer.Evidence(
                loaded.model.boostFor(cont).toDouble(), cont.frequency, cont.probability
            )
        }
        val exposed = evidence.any { it.boost > SwipeContextRescorer.NO_BOOST }
        // Trace identity WITHOUT context, so the same trace under many contexts counts once.
        val traceId = "${row.word}|${row.pts.size}|${row.pts.firstOrNull()?.x}"
        (if (favourableArm) results.favourableTraces else results.adversarialTraces).add(traceId)
        if (exposed) {
            (if (favourableArm) results.favourableExposedTraces
             else results.adversarialExposedTraces).add(traceId)
        }
        // The slate's own shape, independent of context: how far behind the runner-up sits is
        // what decides whether ANY promotion is arithmetically reachable.
        if (scores[0] > 0) results.runnerUpRatios.add(scores[1].toDouble() / scores[0])
        // H4 (audit): counted PER ARM. A single global counter forced the safety denominator to be
        // inferred by subtracting across two different runs, which is exactly the number an
        // "underpowered" verdict would rest on.
        if (exposed) {
            if (favourableArm) results.favourableExposed++ else results.adversarialExposed++
            // AUDIT S1: the arm label records the SAMPLING INTENT, not where the evidence points.
            // A decoy word can itself be a stored continuation of w1 (the store holds up to 20 per
            // word), and then the "adversarial" case has evidence pointing AT its own target — it
            // cannot break, yet it inflated the break-rate DENOMINATOR. Split it out so the safety
            // rate is quoted over cases that could actually produce a breakage.
            //
            // Note this error ran AGAINST the feature's favour: correcting it RAISES the measured
            // break rate. It is fixed anyway — a denominator that happens to err in the safe
            // direction is still the wrong denominator.
            if (!favourableArm &&
                loaded.model.getContextEvidence(
                    SwipeContextRescorer.storeKey(row.word), listOf(contextWord)
                ) != null
            ) {
                results.adversarialEvidenceOnTarget++
            }
            // Only a NON-top candidate can displace rank 1, so the decomposition looks at 1..K.
            val contenders = (1 until words.size).filter {
                evidence[it].boost > SwipeContextRescorer.NO_BOOST
            }
            val ratioOk = contenders.filter {
                scores[0] > 0 && scores[it] >= SwipeContextRescorer.R_MIN * scores[0]
            }
            when {
                contenders.isEmpty() -> results.exposedTopOnly++
                ratioOk.isEmpty() -> results.exposedBelowRatio++
                ratioOk.none { SwipeContextRescorer.promotableToRankOne(evidence[it]) } ->
                    results.exposedFloorsBlocked++
                else -> results.exposedPromotable++
            }
        } else if (words.any { w ->
                apostropheKeys[w.lowercase()]?.any { key ->
                    loaded.model.getContextEvidence(key, listOf(contextWord)) != null
                } == true
            }
        ) {
            // Would have been exposed had the slate carried the apostrophe display form.
            results.apostropheMissed++
        }

        val order = SwipeContextRescorer.rescoreOrder(scores, evidence)
        val tally = if (favourableArm) results.favourable else results.adversarial
        val engineTop1 = words.firstOrNull()
        val rescoredTop1 = words.getOrNull(order.first())
        // The fix headroom, recorded BEFORE classification so "few fixes" can be read correctly.
        if (engineTop1.equals(row.word, ignoreCase = true)) {
            if (favourableArm) results.favourableBaselineCorrect++
            else results.adversarialBaselineCorrect++
        }
        val outcome = RescoringMetrics.classify(
            // The target is the word THIS trace actually is, not the bigram's continuation —
            // in the adversarial arm those differ, which is the point.
            target = row.word,
            engineTop1 = engineTop1,
            rescoredTop1 = rescoredTop1,
        )
        tally.record(outcome)
        val sink = when (outcome) {
            RescoringMetrics.Outcome.BROKEN -> results.brokenExamples
            RescoringMetrics.Outcome.FIXED -> results.fixedExamples
            else -> null
        }
        // The (WEIGHT, R_MIN) sweep, on the decode that already happened. Split by TRACE so a
        // physical swipe never appears in both halves.
        val tuneHalf = ((traceId.hashCode() % 2) + 2) % 2 == 0
        val sweep = if (tuneHalf) results.sweepTune else results.sweepConfirm
        for (w in SWEEP_WEIGHTS) {
            for (r in SWEEP_RMINS) {
                val swept = SwipeContextRescorer.rescoreOrder(scores, evidence, w, r)
                sweep.getOrPut(sweepKey(w, r)) { RescoringMetrics.Tally() }.record(
                    RescoringMetrics.classify(
                        target = row.word,
                        engineTop1 = engineTop1,
                        rescoredTop1 = words.getOrNull(swept.first()),
                    )
                )
            }
        }

        if (sink != null) {
            if (sink.size < MAX_EXAMPLES) {
                sink.add("'$contextWord' + swipe('${row.word}'): $engineTop1 -> $rescoredTop1")
            }
            // `traceId` (computed above) drops the context, so repeats of one trace across many
            // contexts collapse to one — that is exactly the concentration being measured.
            if (outcome == RescoringMetrics.Outcome.BROKEN) {
                results.brokenTraces.add(traceId)
                results.brokenWords.merge(row.word, 1, Int::plus)
            } else {
                results.fixedTraces.add(traceId)
                results.fixedWords.merge(row.word, 1, Int::plus)
            }
        }
    }

    private fun report(role: String, r: EngineResults) {
        println("  ─────────────────────────────────────────────────────────────")
        println("  $role — ${r.label}")
        println("     decoded  : ${r.decoded} usable slates (${r.unusableSlate} too short to reorder)")
        println("     exposure : favourable=${r.favourableExposed} adversarial=${r.adversarialExposed}" +
            "  <- the ONLY cases where rescoring could act")
        println("        in DISTINCT traces: favourable=${r.favourableExposedTraces.size}" +
            "/${r.favourableTraces.size} adversarial=${r.adversarialExposedTraces.size}" +
            "/${r.adversarialTraces.size}")
        println("        (a case count is inflated by context-multiplicity in BOTH numerator and")
        println("         denominator; the distinct-trace count is the independent evidence)")
        val canBreak = r.adversarialExposed - r.adversarialEvidenceOnTarget
        println("        of the ${r.adversarialExposed} adversarial-exposed, " +
            "${r.adversarialEvidenceOnTarget} have evidence ON their own target")
        println("         (favourable-in-fact, cannot break) -> honest break denominator = $canBreak")
        println("     baseline : engine top-1 already correct on " +
            "${r.favourableBaselineCorrect}/${r.favourable.total} favourable, " +
            "${r.adversarialBaselineCorrect}/${r.adversarial.total} adversarial")
        println("        (the FIX HEADROOM — a fix needs the engine to have been wrong)")
        println("     favourable : ${r.favourable}")
        println("        (context points AT the target — the only arm that can produce a FIX)")
        println("     adversarial: ${r.adversarial}")
        println("        (context points AWAY — the only arm that can produce a BREAK)")
        println("     COMBINED   : ${r.combined}")
        // The ONLY rates that do not move with the decoy count. The combined Δtop1 and errRatio
        // above are both functions of the favourable:adversarial ratio, which is a sampling knob
        // (`-PreplayDecoys`) and NOT a fact about typing — so a verdict must be read from these.
        val fixRate = if (r.favourableExposed == 0) 0.0
            else 100.0 * r.favourable.fixed / r.favourableExposed
        val breakRate = if (r.adversarialExposed == 0) 0.0
            else 100.0 * r.adversarial.broken / r.adversarialExposed
        println("     PER-EXPOSURE (ratio-independent — quote THESE, not COMBINED):")
        println("        fixes  : ${r.favourable.fixed}/${r.favourableExposed} exposed favourable" +
            " = %.2f%%".format(fixRate))
        println("        breaks : ${r.adversarial.broken}/${r.adversarialExposed} exposed adversarial" +
            " = %.2f%%".format(breakRate))
        if (canBreak > 0 && r.adversarialEvidenceOnTarget > 0) {
            println("        breaks : ${r.adversarial.broken}/$canBreak excluding " +
                "evidence-on-target = %.2f%% (audit S1 — the honest one)"
                    .format(100.0 * r.adversarial.broken / canBreak))
        }
        if (fixRate > 0.0) {
            // Break-even: breaks < SHIP_BAR * fixes, solved for the real-world exposure ratio.
            println("        => to clear the ship bar, real typing must present at least " +
                "%.0f favourable-exposed".format(breakRate / (RescoringMetrics.SHIP_BAR_ERROR_RATIO * fixRate)))
            println("           cases per adversarial-exposed case. This harness CANNOT measure")
            println("           that ratio; only on-device shadow mode (spec 7.2) can.")
        }
        val exposedTotal = r.exposedTopOnly + r.exposedBelowRatio +
            r.exposedFloorsBlocked + r.exposedPromotable
        println("     why rank 1 did or did not move, over all $exposedTotal exposed cases:")
        println("        evidence on engine top-1 only, nothing to promote : ${r.exposedTopOnly}")
        println("        contender below R_MIN=${SwipeContextRescorer.R_MIN} x top-1 " +
            "(un-promotable)   : ${r.exposedBelowRatio}")
        println("        cleared ratio, failed strict evidence floors       : ${r.exposedFloorsBlocked}")
        println("        cleared BOTH rank-1 guards                         : ${r.exposedPromotable}")
        val ratios = r.runnerUpRatios.sorted()
        if (ratios.isNotEmpty()) {
            val median = ratios[ratios.size / 2]
            val p90 = ratios[(ratios.size * 9 / 10).coerceAtMost(ratios.size - 1)]
            val above = ratios.count { it >= SwipeContextRescorer.R_MIN }
            println("     slate shape: runner-up/top-1 ratio median=%.3f p90=%.3f; %d of %d slates"
                .format(median, p90, above, ratios.size))
            println("                  (%.1f%%) have a runner-up within the guard's factor of two"
                .format(100.0 * above / ratios.size))
        }
        // ALWAYS printed, including when zero: a measured zero is evidence that the missing
        // contraction overlay cost nothing here, whereas a silent absence is not.
        println("     apostrophe gap: ${r.apostropheMissed} cases would have been exposed if the")
        println("                slate carried apostrophe display forms (`dont` -> `don't`). The")
        println("                adapter's contraction overlay needs an Android Context and is not")
        println("                applied here; this bounds what that omission costs.")
        println("     concentration: ${r.combined.fixed} fixes from ${r.fixedTraces.size} distinct " +
            "traces / ${r.fixedWords.size} words; ${r.combined.broken} breaks from " +
            "${r.brokenTraces.size} distinct traces / ${r.brokenWords.size} words")
        if (r.brokenWords.isNotEmpty()) {
            val top = r.brokenWords.entries.sortedByDescending { it.value }.take(5)
                .joinToString(", ") { "${it.key} x${it.value}" }
            println("        most-broken words: $top")
        }
        if (r.fixedExamples.isNotEmpty()) {
            println("     example FIXES  : ${r.fixedExamples.joinToString("; ")}")
        }
        if (r.brokenExamples.isNotEmpty()) {
            println("     example BREAKS : ${r.brokenExamples.joinToString("; ")}")
        }
        if (r.firstError != null) println("     FIRST ERROR: ${r.firstError}")
        reportSweep(r)
    }

    /**
     * The spec §7.1 tune/confirm split over the (WEIGHT, R_MIN) grid.
     *
     * Protocol, and the order matters: the operating point is SELECTED on the tune half only, then
     * reported on the held-out confirm half. Reading the confirm column to choose the point would
     * make it a second tune half and the "confirmation" meaningless.
     */
    private fun reportSweep(r: EngineResults) {
        if (r.sweepTune.isEmpty()) return
        println("     ── (WEIGHT, R_MIN) sweep — spec 7.1 tune/confirm, split by TRACE ──")
        println("        TUNE half (selection happens here):")
        val rows = ArrayList<Triple<String, RescoringMetrics.Tally, RescoringMetrics.Tally>>()
        for (w in SWEEP_WEIGHTS) for (rm in SWEEP_RMINS) {
            val k = sweepKey(w, rm)
            val tune = r.sweepTune[k] ?: RescoringMetrics.Tally()
            val conf = r.sweepConfirm[k] ?: RescoringMetrics.Tally()
            rows.add(Triple(k, tune, conf))
            println("          %-16s fixed=%-3d broken=%-3d errRatio=%s"
                .format(k, tune.fixed, tune.broken, ratioText(tune)))
        }
        // Selection rule, stated so it cannot be quietly changed: among points that CLEAR the ship
        // bar on tune, take the most fixes; ties to the lower WEIGHT (less aggressive). If none
        // clears the bar, say so plainly rather than promoting the least-bad point as a winner.
        val clearing = rows.filter { it.second.meetsShipBar() }
        if (clearing.isEmpty()) {
            println("        SELECTED: none — NO grid point clears the ship bar on the tune half.")
            println("          There is nothing to confirm. The parameter has no setting, within")
            println("          this grid, that makes the feature shippable on this engine/corpus.")
            return
        }
        val best = clearing.maxByOrNull { it.second.fixed }!!
        println("        SELECTED on tune: ${best.first} (fixed=${best.second.fixed} " +
            "broken=${best.second.broken} errRatio=${ratioText(best.second)})")
        println("        CONFIRM (held out, NOT used for selection): fixed=${best.third.fixed} " +
            "broken=${best.third.broken} errRatio=${ratioText(best.third)} " +
            "meetsBar=${best.third.meetsShipBar()}")
    }

    private fun ratioText(t: RescoringMetrics.Tally): String =
        if (t.broken == 0) "0.000" else if (t.fixed == 0) "INF" else "%.3f".format(t.promotionErrorRatio)

    /**
     * Words likely to decode to a slate CONTAINING [word] — the traces genuinely at risk from a
     * learned continuation of the preceding word.
     *
     * Cheap proxy for confusability: same length +-1 and a shared first letter, which on both
     * decoders correlates strongly with appearing in each other's top-K. Not exact, but it
     * concentrates the sample on the damage surface instead of spending decodes on traces where
     * rescoring provably cannot act.
     *
     * The candidates are SHUFFLED before truncation, seeded per-word so the choice is reproducible
     * and independent of iteration order. Taking the first N in pool order would repeat H1's
     * mistake one level down — a size cap that is silently a selection, here of whichever
     * neighbours happen to appear early in the corpus file.
     */
    private fun neighboursOf(word: String, pool: List<String>, limit: Int): List<String> {
        if (word.isEmpty() || limit <= 0) return emptyList()
        val candidates = pool.filter {
            it != word && it.isNotEmpty() && it[0] == word[0] &&
                kotlin.math.abs(it.length - word.length) <= 1
        }
        if (candidates.size <= limit) return candidates
        return candidates.toMutableList()
            .also { java.util.Collections.shuffle(it, java.util.Random(SEED + word.hashCode())) }
            .take(limit)
    }

    /**
     * Sampling knobs, overridable per run so a measurement can be scaled without a code edit —
     * `-PreplayPairs=800 -PreplayDecoys=6`, bridged into system properties by build.gradle.
     *
     * They are wall-clock knobs, not statistical ones, and the report prints whatever they were
     * set to so any quoted figure carries its own sample size.
     */
    private val maxPairs: Int get() = intProperty("replayPairs", MAX_PAIRS)
    private val decoysPerPair: Int get() = intProperty("replayDecoys", DECOYS_PER_PAIR)
    private val tracesPerWord: Int get() = intProperty("replayTraces", TRACES_PER_WORD)

    /** 0 = unlimited (the published baseline). N caps adversarial contexts per distinct trace. */
    private val maxContextsPerTrace: Int
        get() = System.getProperty("replayMaxCtx")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** `device` selects the device export; anything else prefers the Ubuntu corpus. */
    private val corpusPreference: String get() = System.getProperty("replayCorpus") ?: "ubuntu"

    private fun intProperty(name: String, fallback: Int): Int =
        System.getProperty(name)?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

    private companion object {
        /** Cap traces per word so one very common word cannot dominate the tally. */
        const val TRACES_PER_WORD = 2

        /**
         * Bounded so the replay stays inside a reasonable budget. Each pair costs up to
         * [TRACES_PER_WORD] decodes per engine against the full dictionary.
         */
        const val MAX_PAIRS = 1500

        /** Adversarial decoys per pair — traces the learned context does NOT predict. */
        const val DECOYS_PER_PAIR = 2

        /** Fixed so a rerun on the same corpora reproduces the same sample. */
        const val SEED = 20260822L

        /** Concrete outcomes kept per engine per class, so the report can be spot-checked. */
        const val MAX_EXAMPLES = 8

        /**
         * The parameter grid for the spec §7.1 tune/confirm split.
         *
         * `WEIGHT` spans half and double the design's 0.5. `R_MIN` only ever tightens from the
         * shipped 0.5: loosening it would admit promotions the auto-commit guard exists to refuse,
         * and no measurement on this corpus could justify that.
         */
        val SWEEP_WEIGHTS = doubleArrayOf(0.25, 0.5, 1.0)
        val SWEEP_RMINS = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9)

        fun sweepKey(weight: Double, rMin: Double): String = "W=%.2f R=%.2f".format(weight, rMin)
    }
}
