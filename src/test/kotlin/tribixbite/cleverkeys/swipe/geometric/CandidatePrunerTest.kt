package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Test

/**
 * Phase-3 tests for [TemplateIndex] (SoA build + CSR extremity buckets),
 * [TemplateCache] (two-tier LRU, honest `estimatedBytes()`), and [CandidatePruner]
 * (extremity → length-ratio → bbox + best-K cap).
 *
 * These construct [ProcessedGesture] DIRECTLY (no Phase-4 preprocessor dependency):
 * an "ideal" gesture is exactly the plain-variant template polyline, so a
 * correctly-built pipeline MUST keep the true word through every stage.
 *
 * Asserted (per the spec's Phase-3 checklist):
 *  - ideal-trace recall ≥ 99.5% through all prune stages (en/QWERTY + ru/JCUKEN);
 *  - deterministic best-`maxCandidatesScored` cap (|ln ratio| asc, tie → lower ordinal);
 *  - cut-ratio bound at 98k (mean pre-cap survivors < 5% of the lexicon);
 *  - cache eviction + fingerprint-miss + dictVersion-miss cold rebuild;
 *  - a 4-thread concurrency stress test (no exceptions; single-thread rerun identical);
 *  - structural memory ≤ 2.5 MB / index @ 98k.
 */
class CandidatePrunerTest {

    private val config = GeometricEngineConfig()
    private val gen = TemplateGenerator(config)
    private val pruner = CandidatePruner(config)

    // ── Ideal-gesture construction ────────────────────────────────────────────

    /**
     * Build a [ProcessedGesture] that is the exact plain-variant template polyline of
     * [word] — the "ideal trace" (no noise). Its extremity neighbors are the true
     * nearest keys to the first/last polyline point, so the word's own extremity
     * bucket is guaranteed present.
     */
    private fun idealGesture(word: String, wordIndex: Int, layout: LayoutGeometry): ProcessedGesture? {
        val t = gen.generate(word, wordIndex, layout) ?: return null
        val n = t.pointCount
        val pts = FloatArray(n * 2)
        t.copyVariantInto(0, pts)

        var minU = Float.POSITIVE_INFINITY; var minV = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
        for (k in 0 until n) {
            val u = pts[2 * k]; val v = pts[2 * k + 1]
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        // Enough neighbors that a dense-layout 3-widening still has data.
        val kNeighbors = 4
        val start = layout.nearestKeys(pts[0], pts[1], kNeighbors)
        val end = layout.nearestKeys(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], kNeighbors)
        return ProcessedGesture(
            points = pts,
            pathLengthKw = t.pathLengthsKw[0],
            bbox = floatArrayOf(minU, minV, maxU, maxV),
            cornerIndices = IntArray(0),
            startNearest = start,
            endNearest = end,
            // Ideal-trace gesture: no endpoint noise, so the inset anchors coincide with
            // the raw endpoints (Step 1a no-op) — the pruner union stays bit-identical.
            startNearestInset = start,
            endNearestInset = end,
        )
    }

    // ── Ideal-trace recall ≥ 99.5% ────────────────────────────────────────────

    @Test
    fun idealTrace_recall_atLeast99_5pct_english() {
        recallSweep(GeoLayoutFixtures.loadShipped("latn_qwerty_us"), GeoTestFixtures.englishCkdt(), "en/QWERTY")
    }

    @Test
    fun idealTrace_recall_atLeast99_5pct_russian() {
        recallSweep(GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru"), GeoTestFixtures.russianCkdt(), "ru/JCUKEN")
    }

    /**
     * For the top-500 typeable words, decode the ideal trace and assert the true word
     * survives all prune stages (present in the pruner output). Recall must be ≥ 99.5%.
     * Also accumulates the pre-cap survivor count to bound the cut ratio.
     */
    private fun recallSweep(layout: LayoutGeometry, dict: GeometricDictionary, label: String) {
        val index = TemplateIndex.build(layout, dict, config, gen)
        var tested = 0
        var recovered = 0
        var survivorTotal = 0L
        val sample = minOf(500, dict.size)
        for (i in 0 until sample) {
            val gesture = idealGesture(dict.word(i), i, layout) ?: continue
            tested++
            val survivors = pruner.prune(gesture, index, layout)
            var found = false
            for (s in survivors) if (s == i) { found = true; break }
            if (found) recovered++
            survivorTotal += survivors.size
        }
        val recall = recovered.toDouble() / tested
        val meanSurvivors = survivorTotal.toDouble() / tested
        val cutRatio = meanSurvivors / dict.size
        println("[recall] $label: recall=${"%.4f".format(recall)} tested=$tested " +
            "meanSurvivors=${"%.0f".format(meanSurvivors)} cutRatio=${"%.4f".format(cutRatio)}")
        assertWithMessage("$label ideal-trace recall must be >= 99.5%")
            .that(recall).isAtLeast(0.995)
        // Cut ratio: the shortlist the scorer sees is a tiny fraction of the lexicon.
        assertWithMessage("$label pruner output must be < 5% of the lexicon")
            .that(cutRatio).isLessThan(0.05)
    }

    // ── Deterministic best-K cap ──────────────────────────────────────────────

    @Test
    fun prune_isDeterministic_sameInputSameOutput() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, dict, config, gen)
        val gesture = idealGesture("keyboard", 0, layout)!!
        val a = pruner.prune(gesture, index, layout)
        val b = pruner.prune(gesture, index, layout)
        assertWithMessage("prune must be deterministic (NFR-4)")
            .that(a.toList()).isEqualTo(b.toList())
    }

    @Test
    fun prune_capSelectsBestByLnRatio_tieByLowerOrdinal() {
        // A tiny config with a hard cap of 3 forces the best-K selection to be visible.
        val cfg = config.copy(maxCandidatesScored = 3)
        val prunerCapped = CandidatePruner(cfg)
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, dict, cfg, gen)
        val gesture = idealGesture("the", 0, layout)!!

        val survivors = prunerCapped.prune(gesture, index, layout)
        assertWithMessage("cap must bound the output to maxCandidatesScored")
            .that(survivors.size).isAtMost(3)

        // The survivors must be the exact best-3 by |ln ratio| (tie → lower ordinal),
        // reproduced here from the Tier-A metadata over the SAME extremity buckets.
        val expected = expectedBestK(gesture, index, layout, cfg)
        assertWithMessage("cap selection must be the deterministic best-K by |ln ratio|")
            .that(survivors.toList()).isEqualTo(expected)
    }

    /** Recompute the pruner's best-K selection independently from Tier-A metadata. */
    private fun expectedBestK(
        gesture: ProcessedGesture, index: TemplateIndex, layout: LayoutGeometry, cfg: GeometricEngineConfig,
    ): List<Int> {
        val neighbors = if (layout.meanKeyWidth < cfg.denseLayoutKwThreshold)
            maxOf(3, cfg.extremityNeighbors) else cfg.extremityNeighbors
        val starts = gesture.startNearest.take(neighbors)
        val ends = gesture.endNearest.take(neighbors)
        val cand = ArrayList<Int>()
        for (s in starts) for (e in ends) {
            val w = index.bucket(s, e)
            for (p in w.from until w.toExclusive) cand.add(w.entries[p])
        }
        val gLen = gesture.pathLengthKw
        data class C(val ord: Int, val key: Float)
        val survivors = ArrayList<C>()
        for (ord in cand) {
            val tLen = index.idealPathLenKwOf(ord)
            if (tLen <= 0f) continue
            val ratio = gLen / tLen
            if (ratio < cfg.lengthRatioMin || ratio > cfg.lengthRatioMax) continue
            if (index.bboxMaxU(ord) < gesture.bbox[0] || index.bboxMinU(ord) > gesture.bbox[2]) continue
            if (index.bboxMaxV(ord) < gesture.bbox[1] || index.bboxMinV(ord) > gesture.bbox[3]) continue
            survivors.add(C(ord, abs(ln(ratio.toDouble())).toFloat()))
        }
        return survivors.sortedWith(compareBy({ it.key }, { it.ord }))
            .take(cfg.maxCandidatesScored).map { it.ord }
    }

    // ── SoA correctness + untypeable sentinel ─────────────────────────────────

    @Test
    fun index_encodesExtremities_lengths_and_untypeableSentinel() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val dict = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, dict, config, gen)

        // typeableCount + deadLayout coverage.
        assertWithMessage("QWERTY types the vast majority of English")
            .that(index.typeableCount).isGreaterThan((dict.size * 90) / 100)
        assertThat(index.deadLayout).isFalse()

        // A concrete word: "the" — first key 't', last key 'e', collapsedLen 3.
        val theIdx = (0 until dict.size).first { dict.word(it) == "the" }
        val tKey = layout.keys.first { it.label == "t" }.id
        val eKey = layout.keys.first { it.label == "e" }.id
        assertThat(index.isTypeable(theIdx)).isTrue()
        assertThat(index.firstKeyOf(theIdx)).isEqualTo(tKey)
        assertThat(index.lastKeyOf(theIdx)).isEqualTo(eKey)
        assertThat(index.collapsedLenOf(theIdx)).isEqualTo(3)
        // Its Tier-A path length matches the freshly-generated template (within u16).
        val fresh = gen.generate("the", theIdx, layout)!!.pathLengthsKw[0]
        assertThat(index.idealPathLenKwOf(theIdx)).isWithin(0.01f).of(fresh)
        // Its extremity bucket contains it.
        val w = index.bucket(tKey, eKey)
        var inBucket = false
        for (p in w.from until w.toExclusive) if (w.entries[p] == theIdx) { inBucket = true; break }
        assertWithMessage("'the' must live in its (t,e) extremity bucket").that(inBucket).isTrue()
    }

    @Test
    fun index_untypeableWords_haveZeroCollapsedLen_andNoBucket() {
        // The vowel-only layout makes nearly every English word untypeable.
        val layout = vowelLayout()
        val dict = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, dict, config, gen)
        // Coverage collapses → DEAD_LAYOUT flagged.
        assertWithMessage("vowel-only layout must be flagged dead").that(index.deadLayout).isTrue()
        // "the" is untypeable here (no t/h) → sentinel 0, excluded from buckets.
        val theIdx = (0 until dict.size).first { dict.word(it) == "the" }
        assertThat(index.isTypeable(theIdx)).isFalse()
        assertThat(index.collapsedLenOf(theIdx)).isEqualTo(0)
    }

    @Test
    fun index_bucketEntries_areOrdinalAscendingWithinBucket() {
        // CSR entries within one bucket must be ordinal-ascending (deterministic scan
        // order; common words first). Verify across every non-empty bucket.
        val layout = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val dict = GeoTestFixtures.russianCkdt()
        val index = TemplateIndex.build(layout, dict, config, gen)
        val kc = index.keyCount
        for (f in 0 until kc) for (l in 0 until kc) {
            val w = index.bucket(f, l)
            var prev = -1
            for (p in w.from until w.toExclusive) {
                assertWithMessage("bucket ($f,$l) entries must be ordinal-ascending")
                    .that(w.entries[p]).isGreaterThan(prev)
                prev = w.entries[p]
            }
        }
    }

    // ── u16 path-length range does not clamp real words ───────────────────────

    @Test
    fun pathLengthQuantRange_clearsRealDictionaryMax_noClamping() {
        // The German lexicon holds the longest real word ("nationalsozialistischen",
        // ~85.6 kw); the spec's draft 64 kw range would have clamped it. On the
        // fixture layouts the widened 128 kw range must exclude NOTHING — the
        // over-length graceful-degradation path is for pathological dense layouts
        // only (see index_overLengthWord_gracefullyExcluded below).
        val layout = GeoLayoutFixtures.loadShipped("latn_qwertz_de")
        val dict = GeoTestFixtures.germanCkdt()
        val index = TemplateIndex.build(layout, dict, config, gen)
        assertThat(index.typeableCount).isGreaterThan(0)
        assertWithMessage("no real German word may be over-length-excluded at 128 kw")
            .that(index.overLengthExcludedCount).isEqualTo(0)
        // The longest word's stored length must not saturate at the range ceiling.
        var maxStored = 0f
        for (i in 0 until dict.size) if (index.isTypeable(i)) {
            val l = index.idealPathLenKwOf(i)
            if (l > maxStored) maxStored = l
        }
        assertWithMessage("longest stored length must not clamp at the u16 ceiling")
            .that(maxStored).isLessThan(TemplateIndex.PATH_LEN_QUANT_MAX)
        assertWithMessage("longest German template really does exceed the old 64 kw draft range")
            .that(maxStored).isGreaterThan(64f)
    }

    // ── Long-word robustness: no fixed scratch bound in the Tier-A build ──────

    @Test
    fun index_longCollapsedWords_neverCrash_andRecordCorrectExtremities() {
        // Regression guard for the removed fixed-size (64) collapse scratch buffer:
        // it silently mis-recorded lastKeyId at exactly 65 collapsed keys and threw
        // ArrayIndexOutOfBoundsException at 66+ — reachable from decode()'s
        // synchronous-fallback build with a caller-supplied dictionary (e.g. a pasted
        // 70-letter custom word), violating the never-throws Error-Handling contract.
        val letters = ('a'..'z') + ('а'..'я') + ('α'..'μ') // 26 + 32 + 12 = 70 distinct
        check(letters.size == 70)
        val layout = singleRowLayout(letters)
        // Three shapes: 65 collapsed keys (the old silent-wrong-last case), 66 keys via
        // a 2-key alternation (the old minimal crash case), and 70 distinct keys.
        val w65 = letters.take(65).joinToString("")
        val w66alt = "ab".repeat(33) // 66 collapsed keys from only 2 distinct letters
        val w70 = letters.joinToString("")
        val dict = ArrayBackedDictionary("xx", 1L, arrayOf(w65, w66alt, w70))
        val index = TemplateIndex.build(layout, dict, config, gen) // must not throw

        assertThat(index.isTypeable(0)).isTrue()
        assertThat(index.collapsedLenOf(0)).isEqualTo(65)
        assertWithMessage("65-collapsed-key word must record its TRUE last key (old code clamped to slot 63)")
            .that(index.lastKeyOf(0)).isEqualTo(64)

        assertThat(index.isTypeable(1)).isTrue()
        assertThat(index.collapsedLenOf(1)).isEqualTo(66)
        assertThat(index.firstKeyOf(1)).isEqualTo(0)
        assertThat(index.lastKeyOf(1)).isEqualTo(1)

        assertThat(index.isTypeable(2)).isTrue()
        assertThat(index.collapsedLenOf(2)).isEqualTo(70)
        assertThat(index.firstKeyOf(2)).isEqualTo(0)
        assertThat(index.lastKeyOf(2)).isEqualTo(69)
        // Each word must live in its own (first,last) extremity bucket.
        val w = index.bucket(0, 69)
        var found = false
        for (p in w.from until w.toExclusive) if (w.entries[p] == 2) { found = true; break }
        assertWithMessage("the 70-key word must be findable via its extremity bucket").that(found).isTrue()
    }

    @Test
    fun index_overLengthWord_gracefullyExcluded_notThrown() {
        // A legitimate DENSE layout (kw scales template lengths as 1/meanKeyWidth) can
        // push a long word past PATH_LEN_QUANT_MAX. The build must NOT throw (it runs
        // under decode()'s synchronous fallback — never-throws contract); the word is
        // excluded (untypeable) and surfaced via overLengthExcludedCount.
        val rows = listOf("abcdefghijklmnopqrst", "uvwxyzабвгдежзийклмн") // 2 × 20 dense
        val layout = denseGridLayout(rows)
        // A triangle-cycling word (three mutually distant keys) whose template length
        // exceeds the quant range without fold-cancellation under resampling.
        val a = 'a'; val far = rows[1][19]; val mid = 'k'
        val longWord = buildString { repeat(6) { append(a); append(far); append(mid) } }
        val shortWord = "ak"
        // Precondition: this word's plain template really is beyond the quant range.
        val pre = LayoutProjection.project(longWord, layout)!!
        val plainLen = gen.fromKeyIds(pre, 0, layout).pathLengthsKw[0]
        assertWithMessage("precondition: synthetic dense-layout word must exceed PATH_LEN_QUANT_MAX")
            .that(plainLen).isGreaterThan(TemplateIndex.PATH_LEN_QUANT_MAX)

        val dict = ArrayBackedDictionary("xx", 1L, arrayOf(longWord, shortWord))
        val index = TemplateIndex.build(layout, dict, config, gen) // must not throw
        assertWithMessage("over-length word must be excluded, not aliased")
            .that(index.isTypeable(0)).isFalse()
        assertThat(index.overLengthExcludedCount).isEqualTo(1)
        assertWithMessage("normal words on the same layout stay typeable")
            .that(index.isTypeable(1)).isTrue()
    }

    // ── Cache: eviction, fingerprint miss, dictVersion miss ───────────────────

    @Test
    fun cache_lru_evictsBeyondCapacity() {
        // Capacity 2: warming three DISTINCT (layout, dict) keys evicts the eldest.
        val cache = TemplateCache(config.copy(indexCacheCapacity = 2))
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val azerty = GeoLayoutFixtures.loadShipped("latn_azerty_fr")
        val jcuken = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val en = GeoTestFixtures.englishCkdt()
        val fr = GeoTestFixtures.frenchCkdt()
        val ru = GeoTestFixtures.russianCkdt()

        cache.warmUp(qwerty, en)
        cache.warmUp(azerty, fr)
        assertThat(cache.indexCount()).isEqualTo(2)
        assertThat(cache.contains(qwerty.fingerprint(), "en", en.version)).isTrue()

        // Warming a third key evicts the least-recently-used (qwerty/en).
        cache.warmUp(jcuken, ru)
        assertThat(cache.indexCount()).isEqualTo(2)
        assertWithMessage("eldest (qwerty/en) must have been evicted at capacity")
            .that(cache.contains(qwerty.fingerprint(), "en", en.version)).isFalse()
        assertThat(cache.contains(jcuken.fingerprint(), "ru", ru.version)).isTrue()

        // Test hygiene: clear() drops everything (also exercises the API).
        cache.clear()
        assertThat(cache.indexCount()).isEqualTo(0)
    }

    @Test
    fun cache_evict_removesByFingerprintAndLanguage() {
        val cache = TemplateCache(config)
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        cache.warmUp(qwerty, en)
        assertThat(cache.contains(qwerty.fingerprint(), "en", en.version)).isTrue()
        cache.evict(qwerty.fingerprint(), "en")
        assertWithMessage("evict must drop the matching index")
            .that(cache.contains(qwerty.fingerprint(), "en", en.version)).isFalse()
    }

    @Test
    fun cache_fingerprintMiss_and_dictVersionMiss_rebuildCold() {
        val cache = TemplateCache(config)
        val en = GeoTestFixtures.englishCkdt()
        // A rotation (different aspect) changes the geometry-only fingerprint → miss.
        val portrait = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 2.0f)
        val landscape = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 3.5f)
        assertThat(portrait.fingerprint()).isNotEqualTo(landscape.fingerprint())
        cache.warmUp(portrait, en)
        val hitPortrait = cache.contains(portrait.fingerprint(), "en", en.version)
        val hitLandscape = cache.contains(landscape.fingerprint(), "en", en.version)
        assertWithMessage("portrait index cached").that(hitPortrait).isTrue()
        assertWithMessage("landscape is a fingerprint miss (not yet built)").that(hitLandscape).isFalse()
        cache.warmUp(landscape, en)
        assertThat(cache.contains(landscape.fingerprint(), "en", en.version)).isTrue()

        // A dictVersion bump (custom word mutation) is a distinct cache key → cold rebuild.
        val bumped = bumpVersion(en, 2L)
        assertWithMessage("v2 dictionary is a cache miss against the v1 index")
            .that(cache.contains(portrait.fingerprint(), "en", bumped.version)).isFalse()
        cache.warmUp(portrait, bumped)
        assertThat(cache.contains(portrait.fingerprint(), "en", 2L)).isTrue()
        // The old v1 index is still present (distinct key) unless evicted by capacity.
        assertThat(cache.contains(portrait.fingerprint(), "en", 1L)).isTrue()
    }

    @Test
    fun cache_warmUp_isIdempotent_and_reportsCoverage() {
        val cache = TemplateCache(config)
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        val first = cache.warmUp(qwerty, en)
        val second = cache.warmUp(qwerty, en) // cache hit → cheap, same coverage
        assertThat(cache.indexCount()).isEqualTo(1)
        assertThat(second.typeableWordCount).isEqualTo(first.typeableWordCount)
        assertThat(second.typeableFraction).isEqualTo(first.typeableFraction)
        assertThat(second.deadLayout).isEqualTo(first.deadLayout)
        assertWithMessage("QWERTY covers well above the dead threshold")
            .that(first.typeableFraction).isGreaterThan(0.90f)
        assertThat(first.deadLayout).isFalse()
        // Idempotent second warmUp does not spend build time.
        assertThat(second.buildMillis).isEqualTo(0L)
    }

    @Test
    fun cache_getOrBuild_populatesMemoLazily() {
        val cache = TemplateCache(config)
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        val cached = cache.getOrBuild(layout, en)
        assertThat(cached.memoSize()).isEqualTo(0) // nothing materialized yet
        val theIdx = (0 until en.size).first { en.word(it) == "the" }
        val t = cached.template(theIdx)!!
        assertThat(t.wordIndex).isEqualTo(theIdx)
        assertThat(cached.memoSize()).isEqualTo(1) // now memoized
        // A repeat lookup returns the SAME memoized instance.
        assertThat(cached.template(theIdx)).isSameInstanceAs(t)
    }

    // ── Structural memory ≤ 2.5 MB / index @ 98k ──────────────────────────────

    @Test
    fun memory_tierAIndex_underNfr2Budget_at98k() {
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, en, config, gen)
        val bytes = index.estimatedBytes()
        val mb = bytes / (1024.0 * 1024.0)
        println("[memory] Tier-A index @ ${en.size} words = ${"%.2f".format(mb)} MB")
        assertWithMessage("Tier-A structural footprint must be under NFR-2's 2.5 MB/index")
            .that(mb).isLessThan(2.5)
    }

    @Test
    fun memory_fullCacheWithFilledMemo_underNfr2Budget() {
        // Fill the Tier-B memo to capacity and assert the WHOLE index (Tier A + memo)
        // stays under 2.5 MB — the honest steady-state number (NFR-2).
        val cache = TemplateCache(config)
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        val cached = cache.getOrBuild(layout, en)
        // Materialize up to the memo capacity worth of distinct typeable words.
        var filled = 0
        var i = 0
        while (filled < config.templateMemoCapacity && i < en.size) {
            if (cached.template(i) != null) filled++
            i++
        }
        assertWithMessage("memo must reach capacity for the honest steady-state estimate")
            .that(cached.memoSize()).isEqualTo(config.templateMemoCapacity)
        val mb = cache.estimatedBytes() / (1024.0 * 1024.0)
        println("[memory] Tier-A + full memo (${cached.memoSize()} templates) = ${"%.2f".format(mb)} MB")
        assertWithMessage("Tier A + full Tier-B memo must stay under NFR-2's 2.5 MB/index")
            .that(mb).isLessThan(2.5)
    }

    // ── Concurrency stress ────────────────────────────────────────────────────

    @Test
    fun concurrency_warmUp_getOrBuild_evict_prune_noExceptions_andDeterministic() {
        val cache = TemplateCache(config)
        val layout = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val en = GeoTestFixtures.englishCkdt()
        val index = TemplateIndex.build(layout, en, config, gen)
        val gesture = idealGesture("keyboard", 0, layout)!!

        // Single-threaded reference output for the bit-identical rerun check (NFR-4).
        val reference = pruner.prune(gesture, index, layout).toList()

        val threads = 4
        val iterations = 40
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val workers = (0 until threads).map { tid ->
            Thread {
                try {
                    start.await()
                    for (it in 0 until iterations) {
                        when ((tid + it) % 4) {
                            0 -> cache.warmUp(layout, en)
                            1 -> {
                                val c = cache.getOrBuild(layout, en)
                                c.template((it * 7) % en.size)
                            }
                            2 -> cache.evict(layout.fingerprint(), "en")
                            else -> {
                                // Prune must be bit-identical every time (pure, no shared state).
                                val out = pruner.prune(gesture, index, layout).toList()
                                if (out != reference) {
                                    error.compareAndSet(null,
                                        AssertionError("prune output diverged under concurrency"))
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
        workers.forEach { it.join(5_000) }

        assertWithMessage("no thread may throw under interleaved warmUp/getOrBuild/evict/prune")
            .that(error.get()).isNull()
        // Post-stress single-threaded rerun must reproduce the reference exactly.
        assertWithMessage("single-threaded rerun must be bit-identical (NFR-4)")
            .that(pruner.prune(gesture, index, layout).toList()).isEqualTo(reference)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A minimal 5-key layout typing only the vowels a/e/i/o/u (dead for English). */
    private fun vowelLayout(): LayoutGeometry = denseGridLayout(listOf("aeiou"))

    /** One row of unit-width letter keys, one per char of [letters] (dense ids 0..n-1). */
    private fun singleRowLayout(letters: List<Char>): LayoutGeometry =
        denseGridLayout(listOf(letters.joinToString("")))

    /** A grid of unit-width letter keys from [rows] (one key per char, row-major ids). */
    private fun denseGridLayout(rows: List<String>): LayoutGeometry {
        val builder = LayoutGeometry.Builder(aspect = 2.0f)
        for (row in rows) {
            builder.addRow(
                LayoutGeometry.Builder.RawRow(
                    keys = row.map { c ->
                        LayoutGeometry.Builder.RawKey(
                            centerLabel = c.toString(), widthUnits = 1f, shiftUnits = 0f,
                            isLetterNode = true, charCodepoints = intArrayOf(c.code),
                            aliasCodepoints = IntArray(0),
                        )
                    },
                    heightUnits = 1f, shiftUnits = 0f, scaleUnits = 0f,
                )
            )
        }
        return builder.build()
    }

    /** A version-bumped view of [base] (models a custom-word mutation → new cache key). */
    private fun bumpVersion(base: GeometricDictionary, newVersion: Long): GeometricDictionary =
        object : GeometricDictionary {
            override val language: String get() = base.language
            override val version: Long get() = newVersion
            override val size: Int get() = base.size
            override fun word(i: Int): String = base.word(i)
        }
}
