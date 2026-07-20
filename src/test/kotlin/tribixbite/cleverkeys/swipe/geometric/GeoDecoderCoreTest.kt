package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Test

/**
 * Phase-4 tests for the decoder core:
 * [GesturePreprocessor], [PathScorer], [CandidateRanker], and the
 * [GeometricSwipeEngine] facade wired end-to-end.
 *
 * Coverage (per the spec's Phase-4 checklist):
 *  - **Resampler equidistance** — the preprocessor's arc-length resample places N
 *    points at equal path-length spacing.
 *  - **Shape invariance** — `d_shape` is (near) zero under a pure scale + translation
 *    of the same path (SHARK2 Eq. 1 scale/translation invariance).
 *  - **Shape-scale clamp** — a degenerate 2-letter straight-line span produces a
 *    FINITE shape distance and a finite score (no div-by-0).
 *  - **α-weight symmetry** — the location channel's end weights are symmetric about
 *    the midpoint, sum to 1, and are minimal at the center.
 *  - **Ideal-trace top-1** — feeding a word's own ideal template polyline back through
 *    the FULL engine (real preprocessor → prune → score → rank) decodes the word at
 *    rank 1 for hand-picked words on QWERTY-en, JCUKEN-ru, AZERTY-fr.
 *  - **Softmax mapping + dedupe** — scores map through a fixed-temperature softmax to
 *    0–1000 (a single candidate → 1000; an all-equal set → flat mid scores), and
 *    lowercase-collapsing duplicates keep the best.
 *  - **Single-key word end-to-end** — `её` (single-key loop-primary) decodes with NO
 *    NaN anywhere; `S(w).isFinite()` asserted across the pipeline.
 *  - **Error handling** — < 3 points / empty dictionary / dead layout → empty result.
 *
 * The ideal-trace helper reconstructs raw [TracePoint]s (in key-area pixels) from a
 * word's plain-variant template, so the trace flows through the REAL preprocessor —
 * proving the whole pipeline, not just the scorer in isolation.
 */
class GeoDecoderCoreTest {

    private val config = GeometricEngineConfig()
    private val gen = TemplateGenerator(config)
    private val preprocessor = GesturePreprocessor(config)

    // Key-area pixel frame used for every ideal trace. Aspect 2.0 matches the fixture
    // loader's DEFAULT_ASPECT, so px→norm→px round-trips the layout geometry.
    private val keyAreaWidthPx = 1000f
    private val keyAreaHeightPx = 500f

    // ── Ideal-trace construction ──────────────────────────────────────────────────

    /**
     * Reconstruct a raw [TracePoint] trace (key-area pixels) from [word]'s plain-variant
     * template polyline on [layout]. The trace, run back through the preprocessor, is the
     * word's own "ideal swipe" — the strongest possible signal a decode can get.
     */
    private fun idealTrace(word: String, layout: LayoutGeometry): List<TracePoint>? {
        val t = gen.generate(word, 0, layout) ?: return null
        val n = t.pointCount
        val buf = FloatArray(n * 2)
        t.copyVariantInto(0, buf)
        val out = ArrayList<TracePoint>(n)
        for (k in 0 until n) {
            val u = buf[2 * k]; val v = buf[2 * k + 1]
            out.add(TracePoint(u * keyAreaWidthPx, v * keyAreaHeightPx, k.toLong()))
        }
        return out
    }

    private fun request(
        trace: List<TracePoint>, layout: LayoutGeometry, dict: GeometricDictionary,
    ) = GeometricSwipeRequest(trace, keyAreaWidthPx, keyAreaHeightPx, layout, dict)

    // ── Resampler equidistance ────────────────────────────────────────────────────

    @Test
    fun preprocessor_resamplesToEquidistantPoints() {
        // A straight diagonal raw trace with unevenly-spaced input samples: the
        // arc-length resampler must place N output points at EQUAL spacing along the
        // path regardless of input spacing. (On a single straight segment arc length ==
        // Euclidean distance, so equidistance is exact — the corner case where a
        // resampled point straddles a vertex is a piecewise-linear artifact of the arc,
        // not a resampler defect, so a straight path is the clean equidistance probe.)
        val raw = listOf(
            TracePoint(0f, 0f, 0), TracePoint(50f, 25f, 5),      // dense at the start
            TracePoint(80f, 40f, 8), TracePoint(800f, 400f, 40), // sparse at the end
        )
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val g = preprocessor.process(raw, keyAreaWidthPx, keyAreaHeightPx, layout)
        assertThat(g.pointCount).isEqualTo(config.resamplePoints)

        // Segment lengths in NORMALIZED space must be uniform (arc-length resample).
        val pts = g.points
        val segLens = FloatArray(g.pointCount - 1)
        var total = 0f
        for (i in 1 until g.pointCount) {
            val dx = pts[2 * i] - pts[2 * (i - 1)]
            val dy = pts[2 * i + 1] - pts[2 * (i - 1) + 1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            segLens[i - 1] = d
            total += d
        }
        val mean = total / segLens.size
        var maxDev = 0f
        for (d in segLens) { val dev = abs(d - mean); if (dev > maxDev) maxDev = dev }
        // Straight path → equidistance is exact up to float slop (< 1% of the mean).
        assertWithMessage("resampled segments must be uniform (arc-length) on a straight path")
            .that(maxDev / mean).isLessThan(0.01f)
    }

    // ── Shape invariance under scale + translation ────────────────────────────────

    @Test
    fun pathScorer_shapeDistance_isScaleAndTranslationInvariant() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val scorer = PathScorer(config)
        // Base polyline: a diagonal + turn (interleaved u,v, N points).
        val base = resampledPath(
            floatArrayOf(0.1f, 0.2f, 0.5f, 0.3f, 0.7f, 0.8f), config.resamplePoints,
        )
        // Same shape scaled 0.5× about origin and translated by (+0.2, +0.1).
        val transformed = FloatArray(base.size)
        for (i in 0 until config.resamplePoints) {
            transformed[2 * i] = base[2 * i] * 0.5f + 0.2f
            transformed[2 * i + 1] = base[2 * i + 1] * 0.5f + 0.1f
        }
        val d = scorer.shapeDistance(base, transformed, layout)
        assertWithMessage("shape distance must be ~0 under scale + translation (SHARK2 Eq. 1)")
            .that(d).isWithin(1e-4f).of(0f)
    }

    // ── Shape-scale clamp on degenerate spans ─────────────────────────────────────

    @Test
    fun pathScorer_shapeScaleClamp_keepsDegenerateSpansFinite() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val scorer = PathScorer(config)
        // A 2-letter straight span with a TINY extent (below minShapeScaleKw·kw) — the
        // clamp must prevent division blow-up. Both paths hug the same short segment.
        val tiny = FloatArray(config.resamplePoints * 2)
        for (i in 0 until config.resamplePoints) {
            val f = i.toFloat() / (config.resamplePoints - 1)
            tiny[2 * i] = 0.5f + 0.001f * f // near-coincident
            tiny[2 * i + 1] = 0.5f
        }
        val other = FloatArray(config.resamplePoints * 2)
        for (i in 0 until config.resamplePoints) {
            val f = i.toFloat() / (config.resamplePoints - 1)
            other[2 * i] = 0.5f + 0.002f * f
            other[2 * i + 1] = 0.5f
        }
        val d = scorer.shapeDistance(tiny, other, layout)
        assertWithMessage("degenerate-span shape distance must be finite (clamped scale)")
            .that(d.isFinite()).isTrue()
        assertThat(d).isAtLeast(0f)
    }

    @Test
    fun engine_twoLetterStraightLineWord_producesFiniteScores() {
        // "to" is 2 letters, both on the top row of QWERTY → a near-horizontal span. It
        // must decode to finite scores (the clamp + short-word fade keep it finite).
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        val trace = idealTrace("to", layout)!!
        val result = engine.decode(request(trace, layout, dict))
        assertWithMessage("2-letter word must yield some candidates").that(result.words).isNotEmpty()
        for (s in result.scores) {
            assertWithMessage("every emitted score must be a finite 0..1000 int").that(s in 0..1000).isTrue()
        }
    }

    // ── α-weight symmetry ─────────────────────────────────────────────────────────

    @Test
    fun pathScorer_alphaWeights_areSymmetric_sumToOne_minAtMiddle() {
        val n = config.resamplePoints
        val alpha = PathScorer.buildAlpha(n)
        // Sum to 1.
        var sum = 0f
        for (a in alpha) sum += a
        assertWithMessage("Σα must be 1").that(sum).isWithin(1e-5f).of(1f)
        // Symmetric about the midpoint: α(i) == α(N-1-i).
        for (i in 0 until n) {
            assertWithMessage("α must be symmetric at index $i")
                .that(alpha[i]).isWithin(1e-6f).of(alpha[n - 1 - i])
        }
        // Minimum at (near) the middle; endpoints are the maxima.
        val mid = n / 2
        for (i in 0 until n) {
            assertWithMessage("midpoint weight must be the minimum")
                .that(alpha[mid]).isAtMost(alpha[i] + 1e-6f)
        }
        assertWithMessage("endpoints must be the maxima").that(alpha[0]).isAtLeast(alpha[mid])
        assertThat(alpha[n - 1]).isAtLeast(alpha[mid])
    }

    // ── Ideal-trace top-1 per fixture layout ──────────────────────────────────────

    @Test
    fun engine_idealTrace_top1_english() {
        idealTop1(
            GeoLayoutFixtures.loadShipped("latn_qwerty_us"), GeoTestFixtures.englishCkdt(),
            // Distinctive templates (avoid short/collision-prone words like "hello",
            // whose double-l collapse ends adjacent to "help" on the o/p keys).
            listOf("keyboard", "world", "program", "language", "computer", "because"), "en/QWERTY",
        )
    }

    @Test
    fun engine_idealTrace_top1_russian() {
        idealTop1(
            GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru"), GeoTestFixtures.russianCkdt(),
            listOf("привет", "спасибо", "клавиатура", "русский"), "ru/JCUKEN",
        )
    }

    @Test
    fun engine_idealTrace_top1_french() {
        idealTop1(
            GeoLayoutFixtures.loadShipped("latn_azerty_fr"), GeoTestFixtures.frenchCkdt(),
            listOf("bonjour", "clavier", "français", "merci"), "fr/AZERTY",
        )
    }

    /**
     * For each [words] entry (that is typeable and present in [dict]), decode its ideal
     * trace against the FULL dictionary and assert the word decodes at rank 1. The
     * ideal trace is exactly the word's own template, so the true word should score best
     * (ties to a template-colliding neighbor are broken by ordinal — a more-frequent
     * collider could legitimately win, so we require top-1 for the picked common words
     * whose templates are distinctive).
     */
    private fun idealTop1(
        layout: LayoutGeometry, dict: GeometricDictionary, words: List<String>, label: String,
    ) {
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        // Map word → ordinal (only test words actually in the dictionary + typeable).
        val ordinals = HashMap<String, Int>()
        for (i in 0 until dict.size) {
            val w = dict.word(i)
            if (w in words && w !in ordinals) ordinals[w] = i
        }
        var tested = 0
        for (word in words) {
            if (word !in ordinals) continue // not in this dictionary — skip
            val trace = idealTrace(word, layout) ?: continue
            val result = engine.decode(request(trace, layout, dict))
            assertWithMessage("$label '$word': decode must return candidates")
                .that(result.words).isNotEmpty()
            assertWithMessage("$label '$word': ideal trace must decode to the word at rank 1 " +
                "(got ${result.words.take(3)})")
                .that(result.words[0]).isEqualTo(word)
            tested++
        }
        assertWithMessage("$label: at least one test word must have been in the dictionary")
            .that(tested).isGreaterThan(0)
    }

    // ── Softmax mapping + dedupe ───────────────────────────────────────────────────

    @Test
    fun ranker_softmax_singleCandidateMapsTo1000() {
        val ranker = CandidateRanker(config)
        val out = ranker.rank(listOf(CandidateRanker.Scored(0, "the", -1.5f)))
        assertThat(out.words).containsExactly("the")
        assertThat(out.scores).containsExactly(1000)
    }

    @Test
    fun ranker_softmax_allEqualScoresMapToFlatMidScores() {
        // The anti-fake-confidence property: an all-tie decode must NOT emit a 1000.
        val ranker = CandidateRanker(config)
        val scored = listOf(
            CandidateRanker.Scored(0, "aaa", -2f),
            CandidateRanker.Scored(1, "bbb", -2f),
            CandidateRanker.Scored(2, "ccc", -2f),
            CandidateRanker.Scored(3, "ddd", -2f),
        )
        val out = ranker.rank(scored)
        assertThat(out.words).hasSize(4)
        for (s in out.scores) {
            assertWithMessage("all-equal scores must be flat mid (~250), never a fake 1000")
                .that(s).isLessThan(1000)
        }
        // Roughly 1000/4 each.
        for (s in out.scores) assertThat(abs(s - 250)).isAtMost(1)
    }

    @Test
    fun ranker_deduplicates_lowercaseKeepingBest() {
        val ranker = CandidateRanker(config)
        // Two entries collapsing to the same lowercase key: keep the best-scoring.
        val scored = listOf(
            CandidateRanker.Scored(5, "Cafe", -3f),
            CandidateRanker.Scored(2, "cafe", -1f), // better score, lower ordinal
            CandidateRanker.Scored(9, "dog", -2f),
        )
        val out = ranker.rank(scored)
        // "cafe" (best) and "dog" survive; the duplicate "Cafe" is dropped.
        assertThat(out.words).containsExactly("cafe", "dog").inOrder()
        // Scores descending.
        assertThat(out.scores[0]).isAtLeast(out.scores[1])
    }

    @Test
    fun ranker_orders_scoreDesc_thenOrdinalAsc() {
        val ranker = CandidateRanker(config)
        val scored = listOf(
            CandidateRanker.Scored(7, "b", -1f),
            CandidateRanker.Scored(3, "a", -1f), // tie on score → lower ordinal first
            CandidateRanker.Scored(1, "z", -5f), // worse score → last despite lowest ordinal
        )
        val out = ranker.rank(scored)
        assertThat(out.words).containsExactly("a", "b", "z").inOrder()
    }

    // ── Single-key word end-to-end (no NaN) ───────────────────────────────────────

    @Test
    fun engine_singleKeyWord_decodesWithNoNaN() {
        // "её" collapses to a single key on ЙЦУКЕН → loop-primary template. The whole
        // pipeline (preprocess → prune → score → rank) must stay finite (FR-6).
        val layout = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val dict = GeoTestFixtures.russianCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)

        val trace = idealTrace("её", layout)!!
        val result = engine.decode(request(trace, layout, dict))
        assertWithMessage("single-key word must produce candidates").that(result.words).isNotEmpty()
        for (s in result.scores) {
            assertWithMessage("no NaN/negative in single-key decode scores").that(s in 0..1000).isTrue()
        }
    }

    @Test
    fun pathScorer_singleKeyTemplate_scoreIsFinite() {
        // Assert S(w) itself is finite for a single-key loop-primary template scored
        // against its own ideal trace (the loop-primary rule's finiteness guarantee).
        val layout = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val scorer = PathScorer(config)
        val template = gen.generate("её", 42, layout)!!
        assertThat(template.variantCount).isEqualTo(1) // loop-primary
        val trace = idealTrace("её", layout)!!
        val gesture = preprocessor.process(trace, keyAreaWidthPx, keyAreaHeightPx, layout)
        val s = scorer.score(gesture, template, layout, 42)
        assertWithMessage("S(w) must be finite for the single-key loop-primary path")
            .that(s.isFinite()).isTrue()
    }

    // ── Error handling ────────────────────────────────────────────────────────────

    @Test
    fun engine_tooFewPoints_returnsEmpty() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        val short = listOf(TracePoint(10f, 10f, 0), TracePoint(20f, 20f, 1)) // 2 points
        val result = engine.decode(request(short, layout, dict))
        assertWithMessage("< 3 points must yield an empty result (Error Handling)")
            .that(result.words).isEmpty()
        assertThat(result.scores).isEmpty()
    }

    @Test
    fun engine_deadLayout_returnsEmpty() {
        // A vowel-only layout is DEAD for English (typeable fraction below threshold).
        val layout = vowelLayout()
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        val trace = listOf(
            TracePoint(100f, 250f, 0), TracePoint(300f, 250f, 1),
            TracePoint(500f, 250f, 2), TracePoint(700f, 250f, 3),
        )
        val result = engine.decode(request(trace, layout, dict))
        assertWithMessage("dead layout must yield an empty result (FR-4)").that(result.words).isEmpty()
    }

    @Test
    fun engine_scoresAllFinite_acrossSample() {
        // Cross-pipeline finiteness sweep: decode ideal traces of the top-50 typeable
        // English words and assert every emitted score is a valid 0..1000 int (no NaN
        // can escape the scorer — clamped normalizations + loop-primary rule).
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        var decoded = 0
        var i = 0
        while (decoded < 50 && i < dict.size) {
            val word = dict.word(i); i++
            val trace = idealTrace(word, layout) ?: continue
            val result = engine.decode(request(trace, layout, dict))
            for (s in result.scores) {
                assertWithMessage("'$word' produced an out-of-range/NaN score").that(s in 0..1000).isTrue()
            }
            decoded++
        }
        assertThat(decoded).isGreaterThan(0)
    }

    @Test
    fun engine_nonFiniteCoordinates_returnEmpty_neverThrow() {
        // A single NaN/Inf coordinate used to defeat every downstream guard (NaN
        // comparisons are all false → the resampler and nearestKeys silently pass a
        // poisoned polyline through to a negative-key-id CSR lookup → AIOOBE out of
        // decode). The contract is empty result, NEVER throws.
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        val nanMid = listOf(
            TracePoint(100f, 250f, 0), TracePoint(Float.NaN, 250f, 1),
            TracePoint(500f, 250f, 2), TracePoint(700f, 250f, 3),
        )
        val infFirst = listOf(
            TracePoint(Float.POSITIVE_INFINITY, 250f, 0), TracePoint(300f, 250f, 1),
            TracePoint(500f, 250f, 2), TracePoint(700f, 250f, 3),
        )
        for ((label, trace) in listOf("NaN mid-trace" to nanMid, "Inf first point" to infFirst)) {
            val result = engine.decode(request(trace, layout, dict))
            assertWithMessage("$label must yield an empty result (Error Handling, never throws)")
                .that(result.words).isEmpty()
        }
    }

    // ── Corner feature liveness (positive path) ───────────────────────────────────

    @Test
    fun preprocessor_detectsCorners_onSharpTurn_andNoneOnStraight() {
        // The corner-anchor bonus is only alive if the preprocessor actually emits
        // corners for a sharp turn (and none for a straight trace) — the ablation's
        // "does not regress" assertion would also pass with corner detection dead.
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // A hairpin (~170° direction reversal): even when the vertex falls BETWEEN two
        // resampled points (splitting the turn across two indices — a piecewise-linear
        // artifact, see the Phase-4 equidistance note), each half still clears the 55°
        // threshold. A plain 90° L can split into 2×45° and legitimately detect nothing.
        val hairpin = listOf(
            TracePoint(100f, 100f, 0), TracePoint(400f, 120f, 1), TracePoint(700f, 140f, 2),
            TracePoint(400f, 180f, 3), TracePoint(100f, 200f, 4),
        )
        val straight = listOf(
            TracePoint(100f, 100f, 0), TracePoint(300f, 200f, 1),
            TracePoint(500f, 300f, 2), TracePoint(700f, 400f, 3),
        )
        val gSharp = preprocessor.process(hairpin, keyAreaWidthPx, keyAreaHeightPx, layout)
        val gStraight = preprocessor.process(straight, keyAreaWidthPx, keyAreaHeightPx, layout)
        assertWithMessage("a hairpin turn must produce at least one detected corner")
            .that(gSharp.cornerIndices.size).isAtLeast(1)
        assertWithMessage("a straight trace must produce no corners")
            .that(gStraight.cornerIndices).isEmpty()
    }

    @Test
    fun pathScorer_cornerBonus_isPositive_forCorneredWordIdealTrace() {
        // Liveness of the full bonus path: a cornered word's OWN ideal trace must earn
        // a strictly positive, capped bonus against its own template (template corners
        // detected + index-window matching fires), and the two corner detectors
        // (gesture-side and template-side) must agree on the same polyline.
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val scorer = PathScorer(config)
        val word = "vim" // v→i is a long up-right stroke, i→m a sharp down-left turn
        val trace = idealTrace(word, layout)!!
        val gesture = preprocessor.process(trace, keyAreaWidthPx, keyAreaHeightPx, layout)
        assertWithMessage("'$word' ideal trace must have detectable corners")
            .that(gesture.cornerIndices.size).isAtLeast(1)
        val t = gen.generate(word, 0, layout)!!
        val buf = FloatArray(t.pointCount * 2)
        t.copyVariantInto(0, buf)
        // Pin: the template-side detector must equal the gesture-side detector on the
        // SAME polyline (corner matching is index-proximity-based — asymmetric edits
        // would silently kill the bonus).
        assertWithMessage("template/gesture corner detectors must agree on the same polyline")
            .that(scorer.templateCornerIndices(buf).toList())
            .isEqualTo(preprocessor.detectCorners(buf).toList())
        val bonus = scorer.cornerBonus(gesture, buf, layout)
        assertWithMessage("cornered word's ideal trace must earn a positive corner bonus")
            .that(bonus).isGreaterThan(0f)
        assertWithMessage("corner bonus must stay capped at cornerAnchorBonus")
            .that(bonus).isAtMost(config.cornerAnchorBonus)
    }

    // ── Engine-level concurrency stress (spec threading contract / NFR-4) ─────────

    @Test
    fun engine_concurrency_decode_warmUp_evict_noExceptions_andBitIdentical() {
        // The spec's named stress test: 4 threads interleaving decode/warmUp/evict on
        // ONE engine — exercising decode racing evict (synchronous-fallback rebuild),
        // decode racing warmUp's outside-the-lock build + last-writer-wins install,
        // and concurrent Tier-B memo materialization. No exceptions allowed, every
        // concurrent decode must be bit-identical to the single-threaded reference,
        // and a post-stress rerun must reproduce it exactly (NFR-4).
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(layout, dict)
        val traces = listOf("the", "keyboard", "hello").map { idealTrace(it, layout)!! }
        val reference = traces.map { engine.decode(request(it, layout, dict)) }
        val fingerprint = layout.fingerprint()

        val threads = 4
        val iterations = 12
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        val workers = (0 until threads).map { tid ->
            Thread {
                try {
                    start.await()
                    for (it in 0 until iterations) {
                        when ((tid + it) % 4) {
                            0 -> engine.warmUp(layout, dict)
                            1 -> engine.evict(fingerprint, dict.language)
                            else -> {
                                val ti = (tid + it) % traces.size
                                val out = engine.decode(request(traces[ti], layout, dict))
                                if (out.words != reference[ti].words ||
                                    out.scores != reference[ti].scores
                                ) {
                                    error.compareAndSet(
                                        null,
                                        AssertionError(
                                            "decode diverged under concurrency: " +
                                                "${out.words.take(3)} vs ${reference[ti].words.take(3)}"
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        done.await()
        workers.forEach { it.join(10_000) }
        assertWithMessage("no thread may throw under interleaved decode/warmUp/evict")
            .that(error.get()).isNull()
        // Post-stress single-threaded rerun: bit-identical words AND scores (NFR-4).
        for (ti in traces.indices) {
            val out = engine.decode(request(traces[ti], layout, dict))
            assertWithMessage("post-stress decode of trace $ti must be bit-identical (NFR-4)")
                .that(out.words).isEqualTo(reference[ti].words)
            assertThat(out.scores).isEqualTo(reference[ti].scores)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Arc-length resample a short interleaved polyline to [target] via the preprocessor. */
    private fun resampledPath(poly: FloatArray, target: Int): FloatArray =
        preprocessor.resampleArcLength(poly, target)

    /** A minimal 5-key vowel-only layout (dead for English). */
    private fun vowelLayout(): LayoutGeometry {
        val builder = LayoutGeometry.Builder(aspect = 2.0f)
        builder.addRow(
            LayoutGeometry.Builder.RawRow(
                keys = "aeiou".map { c ->
                    LayoutGeometry.Builder.RawKey(
                        centerLabel = c.toString(), widthUnits = 1f, shiftUnits = 0f,
                        isLetterNode = true, charCodepoints = intArrayOf(c.code),
                        aliasCodepoints = IntArray(0),
                    )
                },
                heightUnits = 1f, shiftUnits = 0f, scaleUnits = 0f,
            )
        )
        return builder.build()
    }
}
