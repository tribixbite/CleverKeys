package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Test
import tribixbite.cleverkeys.swipe.UserSwipePriorityBonus

/**
 * User swipe priority on the GEOMETRIC engine (2026-10-08,
 * `docs/eval/2026-10-08-user-swipe-priority.md`): the merged dictionary carries the bonus only
 * for raised user words, the engine adds it after pruning only, and — behind `-PgeoFull=true` —
 * the level sweep that chose [UserSwipePriorityBonus.GEO_HIGH] / [UserSwipePriorityBonus.GEO_HIGHEST]
 * (synthetic target traces + collateral on the local real-swipe corpus).
 */
class GeoUserSwipePriorityTest {

    private val config = GeometricEngineConfig()
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")

    private fun base(vararg words: String): GeometricDictionary =
        ArrayBackedDictionary("en", 1L, arrayOf(*words))

    // ── merge ───────────────────────────────────────────────────────────────────────

    @Test
    fun aDictionaryWithNothingRaisedCarriesNoBonus() {
        val merged = GeometricUserWordMerge.merge(base("as", "and"), listOf("ad" to 255), emptySet(), "en", 2L)
        for (i in 0 until merged.size) assertThat(merged.swipeBonus(i)).isEqualTo(0f)
        assertThat((0 until merged.size).map { merged.word(it) }).containsExactly("ad", "as", "and").inOrder()
    }

    @Test
    fun raisedUserWordsCarryTheirBonusAtTheirOrdinalOnly() {
        val merged = GeometricUserWordMerge.merge(
            base("as", "and", "wet"),
            listOf("ad" to 255, "wet" to 200),
            emptySet(), "en", 2L,
            bonusByWord = mapOf("ad" to 0.7f, "and" to 9f /* not a user word: ignored */),
        )
        val words = (0 until merged.size).map { merged.word(it) }
        assertThat(words).containsExactly("ad", "wet", "as", "and").inOrder()
        assertThat(merged.swipeBonus(words.indexOf("ad"))).isEqualTo(0.7f)
        assertThat(merged.swipeBonus(words.indexOf("wet"))).isEqualTo(0f)
        assertThat(merged.swipeBonus(words.indexOf("and"))).isEqualTo(0f)
    }

    // ── engine ──────────────────────────────────────────────────────────────────────

    @Test
    fun theBonusReRanksSurvivorsAndNeverSurfacesAPrunedWord() {
        val gen = TemplateGenerator(config)
        // `aid` shares `and`'s start and end keys, so it is in the same pruner bucket as the
        // ideal `and` trace; `quiz` (q → z) is not.
        val trace = GeoIdealTrace.of("and", qwerty, gen)!!
        fun decode(bonus: Map<String, Float>): List<String> {
            val dict = GeometricUserWordMerge.merge(
                base("and", "as", "sad"), listOf("aid" to 255, "quiz" to 255), emptySet(), "en", 7L, bonus)
            return GeometricSwipeEngine(config).decode(GeoIdealTrace.request(trace, qwerty, dict)).words
        }
        val plain = decode(emptyMap())
        assertWithMessage("fixture: the ideal `and` trace decodes `and`, was $plain").that(plain.first()).isEqualTo("and")
        assertWithMessage("fixture: `quiz` is pruned for an `and` trace").that(plain).doesNotContain("quiz")

        assertThat(decode(mapOf("aid" to 50f)).first()).isEqualTo("aid")
        assertWithMessage("a raised word the trace did not reach stays absent")
            .that(decode(mapOf("quiz" to 1_000f))).isEqualTo(plain)
    }

    // ── level sweep (instrument; -PgeoFull=true) ────────────────────────────────────

    /**
     * For each target: the bonus its synthetic traces need (S(top other) − S(target)), then at
     * each swept level how many real local-corpus swipes of OTHER words the raised target
     * would take (distinct traces / distinct words). All four targets are prepended as user
     * words in one merged dictionary; the bonus is applied to one target at a time, then all.
     */
    @Test
    fun geoPrioritySweepDiagnostic() {
        if (System.getProperty("geoFull") != "true") {
            println("[skip] geometric priority sweep — set -PgeoFull=true to run")
            return
        }
        Assume.assumeTrue("local corpus cache absent", GeoLocalCorpus.cacheFile.exists())
        val rows = GeoLocalCorpus.load()
        val aspect = rows.first().w / rows.first().h
        val layout = GeoLocalCorpus.buildQwertyEnglishLayout(aspect)
        val userWords = TARGETS.map { it to 255 }
        val dict = GeometricUserWordMerge.merge(GeoTestFixtures.englishCkdt(), userWords, emptySet(), "en", 99L)
        val cache = TemplateCache(config)
        val cached = cache.getOrBuild(layout, dict)
        val prep = GesturePreprocessor(config)
        val pruner = CandidatePruner(config)
        val targetOrdinal = TARGETS.associateWith { t -> (0 until dict.size).first { dict.word(it) == t } }

        /** Survivor ordinal → S(w), the engine's pre-bonus score. */
        fun scores(trace: List<TracePoint>, w: Float, h: Float): Map<Int, Float> {
            val g = prep.process(trace, w, h, layout)
            if (g.pathLengthKw <= 0f) return emptyMap()
            val scorer = PathScorer(config)
            val out = HashMap<Int, Float>()
            for (o in pruner.prune(g, cached.index, layout)) {
                val t = cached.template(o) ?: continue
                val s = scorer.score(g, t, layout, o)
                if (s.isFinite()) out[o] = s
            }
            return out
        }

        // 1) Targets on synthetic traces (ideal + TYPICAL seeds) on the corpus layout.
        val synth = GeoTraceSynthesizer(config)
        val gen = TemplateGenerator(config)
        for (t in TARGETS) {
            val ideal = GeoIdealTrace.of(t, layout, gen)!!
                .map { TracePoint(it.x / GeoIdealTrace.WIDTH_PX * GeoLocalCorpus.CANVAS_W,
                    it.y / GeoIdealTrace.HEIGHT_PX * GeoLocalCorpus.CANVAS_H, it.tMillis) }
            val traces = listOf("ideal" to ideal) + (0 until 10).map { seed ->
                "typical#$seed" to synth.synthesize(t, layout, GeoLocalCorpus.CANVAS_W, GeoLocalCorpus.CANVAS_H,
                    GeoTraceSynthesizer.Tier.TYPICAL, seed = 1000L + seed)!!
            }
            for ((label, tr) in traces) {
                val sc = scores(tr, GeoLocalCorpus.CANVAS_W, GeoLocalCorpus.CANVAS_H)
                val own = sc[targetOrdinal.getValue(t)]
                val best = sc.filterKeys { it != targetOrdinal.getValue(t) }.maxByOrNull { it.value }
                val need = if (own == null || best == null) Float.NaN else best.value - own
                println("[GEOPRIO] target %-10s %-10s needs=%7.3f top-other=%s".format(
                    t, label, need, best?.let { dict.word(it.key) }))
            }
        }

        // 2) Collateral on the real corpus.
        val words = HashSet<String>()
        for (i in 0 until dict.size) words.add(dict.word(i))
        val decoded = ArrayList<Pair<String, Map<Int, Float>>>()
        for (r in rows) {
            if (r.word !in words || r.word in TARGETS) continue
            val sc = scores(GeoLocalCorpus.toTrace(r), r.w, r.h)
            if (sc.isNotEmpty()) decoded += r.word to sc
        }
        val correct = decoded.filter { (w, sc) -> dict.word(sc.maxByOrNull { it.value }!!.key) == w }
        println("[GEOPRIO] corpus: ${decoded.size} decoded non-target traces, ${correct.size} correct at top-1")
        for (set in TARGETS.map { listOf(it) } + listOf(TARGETS)) {
            val ords = set.map { targetOrdinal.getValue(it) }.toSet()
            for (b in LEVELS) {
                val lost = HashMap<String, Int>()
                for ((w, sc) in correct) {
                    val top = sc.maxByOrNull { (o, s) -> s + if (o in ords) b else 0f }!!.key
                    if (top in ords) lost[w] = (lost[w] ?: 0) + 1
                }
                println("[GEOPRIO] boost %-28s b=%.2f lost %d traces / %d words %s".format(
                    set.joinToString("+"), b, lost.values.sum(), lost.size,
                    lost.entries.sortedByDescending { it.value }.take(8).map { "${it.key}:${it.value}" }))
            }
        }

        // 3) Generic per-word cost, mirroring the CTC note's §3 table: every word among the
        //    100 best survivors below top-1 of a correct trace (the CTC beam is ≤ 100 wide) is
        //    raised in turn; count the correct traces it would take.
        val margins = HashMap<Int, MutableList<Float>>()
        for ((_, sc) in correct) {
            val ranked = sc.entries.sortedByDescending { it.value }
            val top = ranked.first().value
            for (e in ranked.drop(1).take(100)) margins.getOrPut(e.key) { ArrayList() }.add(top - e.value)
        }
        for (b in LEVELS) {
            val steals = margins.values.map { ms -> ms.count { it < b } }.sortedDescending()
            val worst = margins.entries.map { (o, ms) -> ms.count { it < b } to dict.word(o) }
                .sortedByDescending { it.first }.take(5)
            println("[GEOPRIO] generic b=%.2f steals/word mean %.3f p90 %d max %d words>=1 %d/%d worst %s".format(
                b, steals.average(), steals[steals.size / 10], steals.first(), steals.count { it > 0 },
                steals.size, worst))
        }
    }

    private companion object {
        val TARGETS = listOf("ad", "wet", "adb", "somethings")
        val LEVELS = listOf(0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f)
    }
}
