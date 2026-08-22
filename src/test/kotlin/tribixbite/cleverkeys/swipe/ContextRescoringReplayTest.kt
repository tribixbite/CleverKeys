package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.contextaware.ContextModel
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
 * trace once and scoring the slate twice: as the engine ordered it, and as
 * [SwipeContextRescorer] would reorder it given `w1` as context. [RescoringMetrics] classifies
 * each pair of outcomes against the TARGET, never against the shape of the change.
 *
 * ## Why the GEOMETRIC engine
 *
 * Not preference — necessity, and the limitation must be stated wherever these numbers are.
 * The CTC encoder is ONNX, and the desktop ONNX runtime **cannot load on this device**:
 * `libonnxruntime.so` needs `libdl.so.2`, a glibc library, and Termux is bionic (probed
 * 2026-08-22). So a real CTC decode is only possible in an INSTRUMENTED run.
 *
 * The rescorer itself is engine-agnostic — it sits at
 * `SuggestionHandler.handleSwipePredictionResults`, which both engines pass through — so the
 * geometric arm is genuine evidence about the rescorer. But CTC is the DEFAULT engine and its
 * slate has a different score distribution (a softmax scaled to 0..1000), and the rank-1 guard is
 * expressed as a ratio of those scores. **A CTC arm is owed before any default flip.**
 *
 * ## Gating
 *
 * Skips unless the local corpora exist. They are never committed — one is a record of a person's
 * typing, the other is a 552 MB third-party corpus with no stated licence. `Assume` rather than
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

    /** One real swipe trace: the word, its canvas, and the points. */
    private data class Row(val word: String, val w: Float, val h: Float, val pts: List<TracePoint>)

    private fun loadTraces(limitPerWord: Int): Map<String, List<Row>> {
        val byWord = HashMap<String, MutableList<Row>>()
        GZIPInputStream(traceFile.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: continue
                val word = o.optString("word").lowercase()
                if (word.isEmpty()) continue
                val bucket = byWord.getOrPut(word) { ArrayList() }
                if (bucket.size >= limitPerWord) continue
                val w = o.optDouble("w").toFloat()
                val h = o.optDouble("h").toFloat()
                val ptsArr = o.optJSONArray("pts") ?: continue
                val pts = ArrayList<TracePoint>(ptsArr.length())
                for (i in 0 until ptsArr.length()) {
                    val p = ptsArr.optJSONArray(i) ?: continue
                    // Corpus points are NORMALIZED [x, y, t] in [0,1]; the engine wants PIXELS on
                    // the row's own canvas. Passing them raw makes every trace one pixel wide and
                    // decodes nothing — which is exactly what the first run of this test did.
                    pts.add(TracePoint(
                        p.optDouble(0).toFloat() * w,
                        p.optDouble(1).toFloat() * h,
                        p.optLong(2),
                    ))
                }
                if (pts.size >= 3) bucket.add(Row(word, w, h, pts))
            }
        }
        return byWord
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
        // takes ~15 minutes — far too slow to sit in every `runPureTests`. It is a measurement
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
        val corpusFile = sequenceOf(
            File(corporaDir, "ubuntu_bigrams.json"),
            File(corporaDir, "device_bigrams.json"),
        ).firstOrNull { it.exists() }
        Assume.assumeTrue(
            "no bigram corpus in ${corporaDir.path} — derive one with " +
                "scripts/build_ubuntu_bigrams.py (skipping)",
            corpusFile != null,
        )

        val pairs = LearnedBigramCorpus.parse(corpusFile!!, "en")
        val loaded = LearnedBigramCorpus.seed(pairs, "en", scheduler)
        println("[replay] corpus ${corpusFile.name}: $loaded")

        val traces = loadTraces(limitPerWord = TRACES_PER_WORD)
        println("[replay] trace pool: ${traces.size} distinct words")

        // Stage B: a pairing needs a usable bigram whose SECOND word has a trace.
        val pairable = pairs.filter { it.usable && traces.containsKey(it.word2) }
        println("[replay] pairable bigrams: ${pairable.size} of ${loaded.usable} usable")
        Assume.assumeTrue("no pairable bigrams — nothing to measure", pairable.isNotEmpty())

        // The layout must be built for the CORPUS canvas aspect — `loadShipped` takes one, and
        // the default would put the key grid at a different shape than the traces were drawn on.
        val sampleRow = traces.values.first().first()
        val aspect = sampleRow.w / sampleRow.h
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = aspect)
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(GeometricEngineConfig()).also { it.warmUp(layout, dict) }
        println("[replay] canvas ${sampleRow.w}x${sampleRow.h} aspect=%.3f".format(aspect))

        // TWO arms, and the second is the one that can actually condemn the feature.
        //
        //  - FAVOURABLE: swipe w2 after committing w1, i.e. the learned context points AT the
        //    target. Only this arm can produce a FIX.
        //  - ADVERSARIAL: swipe some OTHER word after committing w1, i.e. the context points
        //    AWAY from the target. Only this arm can produce a BREAK.
        //
        // Running the favourable arm alone reports 0 breakages BY CONSTRUCTION and would read as
        // proof of safety. It is not — it is proof that the sampling never asked the question.
        val favourable = RescoringMetrics.Tally()
        val adversarial = RescoringMetrics.Tally()
        var decoded = 0
        var favourableEvidence = 0
        var adversarialEvidence = 0
        var decodeError: String? = null
        val allWords = traces.keys.toList()
        val rng = java.util.Random(SEED)
        val seenCases = HashSet<String>()

        // H1 (audit): `build_ubuntu_bigrams.py` writes rows sorted by DESCENDING frequency, so
        // `take(MAX_PAIRS)` sampled only the extreme head — the pairs with the largest counts and
        // highest probabilities, which pass the promotion floors almost by definition. That
        // flatters the feature and generalises to nobody. Shuffle with a fixed seed instead, so
        // the sample spans the distribution AND a rerun reproduces it.
        // The store caps a language at 10,000 bigrams and prunes by probability, so seeding a
        // larger corpus DISCARDS most of it. Sampling from the corpus therefore measures mostly
        // pairs the store no longer holds — which is what drove exposure to 11 of 6,252 on the
        // previous run. Sample from what SURVIVED instead, and report both numbers so the
        // capacity constraint stays visible.
        val known = pairable.filter {
            loaded.model.getContextEvidence(it.word2, listOf(it.word1)) != null
        }
        println("[replay] of ${pairable.size} pairable, ${known.size} survived the store's " +
            "${loaded.stored}-entry cap and are actually queryable")
        Assume.assumeTrue("nothing survived seeding — nothing to measure", known.isNotEmpty())

        val sampled = known.toMutableList().also { java.util.Collections.shuffle(it, rng) }
            .take(MAX_PAIRS)
        println("[replay] sampled ${sampled.size} at random from those (seed=$SEED; " +
            "NOT the frequency-sorted head)")

        for (pair in sampled) {
            // Arm 1: the learned continuation itself.
            //
            // Arm 2: words CONFUSABLE with the continuation — not random ones. Rescoring can only
            // reorder what the engine already returned, never insert, so a breakage needs the
            // learned continuation to be IN the slate competing against the correct answer. A
            // random decoy almost never creates that conjunction (measured: 2,012 random decoys
            // produced context evidence on ~0 traces), which makes a random-decoy arm look safe
            // without having tested anything. Near-neighbours of w2 are the traces actually at
            // risk, so this is where the damage would show up if it exists.
            val decoys = neighboursOf(pair.word2, allWords, DECOYS_PER_PAIR)
            val cases = traces.getValue(pair.word2).map { it to favourable } +
                decoys.flatMap { d -> traces.getValue(d).map { it to adversarial } }

            for ((row, tally) in cases) {
                // H3 (audit), and the fix needs care. Head pairs share `word2` heavily, so the
                // same physical trace was decoded once per pair ending in that word. But those
                // are NOT duplicates: "committed w1 then swiped X" and "committed w1' then swiped
                // X" are different experiments — the context is the independent variable, and
                // deduping by trace alone would throw away the whole point.
                //
                // So the unit is (context, trace, arm). What the audit actually caught was a
                // LABELLING error: the eval doc called this denominator "swipes", which it is
                // not. It is context-trace cases, and the report says so.
                val armTag = if (tally === favourable) 'F' else 'A'
                val caseKey = "$armTag|${pair.word1}|${row.word}|${row.pts.size}|${row.pts.firstOrNull()?.x}"
                if (!seenCases.add(caseKey)) continue
                val result = try {
                    engine.decode(
                        tribixbite.cleverkeys.swipe.geometric.GeometricSwipeRequest(
                            row.pts, row.w, row.h, layout, dict
                        )
                    )
                } catch (t: Throwable) {
                    // Surface the FIRST failure rather than swallowing it. The first run of this
                    // test decoded 0 traces and `runCatching{}.getOrNull()` hid the reason.
                    if (decodeError == null) decodeError = "${t::class.java.simpleName}: ${t.message}"
                    continue
                }
                val words = result.words
                if (words.size < 2) continue
                decoded++

                val scores = result.scores.map { it }
                if (scores.size != words.size) continue

                // Stage C: control = engine order; treatment = rescored with w1 as context.
                val evidence = words.map { w ->
                    val cont = loaded.model.getContextEvidence(
                        SwipeContextRescorer.storeKey(w), listOf(pair.word1)
                    )
                    if (cont == null) SwipeContextRescorer.Evidence.NONE
                    else SwipeContextRescorer.Evidence(
                        loaded.model.boostFor(cont).toDouble(), cont.frequency, cont.probability
                    )
                }
                if (evidence.any { it.boost > SwipeContextRescorer.NO_BOOST }) {
                    // H4 (audit): counted PER ARM. A single global counter forced the safety
                    // denominator to be inferred by subtracting across two different runs, which
                    // is exactly the number the "underpowered" verdict rests on.
                    if (tally === favourable) favourableEvidence++ else adversarialEvidence++
                }

                val order = SwipeContextRescorer.rescoreOrder(scores, evidence)
                tally.record(
                    RescoringMetrics.classify(
                        // The target is the word THIS trace actually is, not the bigram's
                        // continuation — in the adversarial arm those differ, which is the point.
                        target = row.word,
                        engineTop1 = words.firstOrNull(),
                        rescoredTop1 = words.getOrNull(order.first()),
                    )
                )
            }
        }
        val combined = RescoringMetrics.Tally(
            fixed = favourable.fixed + adversarial.fixed,
            broken = favourable.broken + adversarial.broken,
            unchanged = favourable.unchanged + adversarial.unchanged,
            wash = favourable.wash + adversarial.wash,
        )

        // Stage D: the report.
        println("═══════════════════════════════════════════════════════════════")
        println("  CONTEXT RESCORING REPLAY — geometric engine, en")
        println("  corpus     : ${corpusFile.name} (${loaded.usable} usable of ${loaded.total})")
        println("  decoded    : $decoded distinct (context, trace, arm) cases — NOT 'swipes';")
        println("               one trace appears under several contexts, which are separate")
        println("               experiments because context is the independent variable")
        println("  exposure   : favourable=$favourableEvidence adversarial=$adversarialEvidence " +
            "had context evidence — the ONLY cases where rescoring could act")
        println("  favourable : $favourable")
        println("     (context points AT the target — the only arm that can produce a FIX)")
        println("  adversarial: $adversarial")
        println("     (context points AWAY — the only arm that can produce a BREAK)")
        println("  COMBINED   : $combined")
        println("  ship bar   : Δtop1 > 0 AND breakages < ${RescoringMetrics.SHIP_BAR_ERROR_RATIO} of fixes")
        println("  meets bar  : ${combined.meetsShipBar()}")
        println("  WARNING: the arm ratio is a SAMPLING choice, not a fact about real usage.")
        println("        These counts are only comparable to reality if favourable and")
        println("        adversarial cases occur in roughly the ratio replayed here.")
        println("  NOTE: geometric engine only — a CTC arm is owed before any default flip,")
        println("        because CTC is the default and its slate scores have a different shape.")
        println("═══════════════════════════════════════════════════════════════")

        assertWithMessage(
            "the replay must actually decode something, or it measured nothing. " +
                "First decode error: ${decodeError ?: "none — the engine returned empty slates"}"
        ).that(decoded).isGreaterThan(0)
    }

    /**
     * Words likely to decode to a slate CONTAINING [word] — the traces genuinely at risk from a
     * learned continuation of the preceding word.
     *
     * Cheap proxy for confusability: same length +-1 and a shared first letter, which on a
     * geometric decoder correlates strongly with appearing in each other's top-K. Not exact, but
     * it concentrates the sample on the damage surface instead of spending decodes on traces
     * where rescoring provably cannot act.
     */
    private fun neighboursOf(word: String, pool: List<String>, limit: Int): List<String> {
        if (word.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        for (candidate in pool) {
            if (candidate == word || candidate.isEmpty()) continue
            if (candidate[0] != word[0]) continue
            if (kotlin.math.abs(candidate.length - word.length) > 1) continue
            out.add(candidate)
            if (out.size >= limit) break
        }
        return out
    }

    private companion object {
        /** Cap traces per word so one very common word cannot dominate the tally. */
        const val TRACES_PER_WORD = 2

        /**
         * Bounded so the replay stays inside a reasonable `runPureTests` budget. Each pair costs
         * up to [TRACES_PER_WORD] geometric decodes against the full 98,140-word dictionary, so
         * this is a wall-clock knob, not a statistical one — raise it for a serious measurement
         * run and report the value alongside the numbers.
         */
        const val MAX_PAIRS = 1500

        /** Adversarial decoys per pair — traces the learned context does NOT predict. */
        const val DECOYS_PER_PAIR = 2

        /** Fixed so a rerun on the same corpora reproduces the same sample. */
        const val SEED = 20260822L
    }
}
