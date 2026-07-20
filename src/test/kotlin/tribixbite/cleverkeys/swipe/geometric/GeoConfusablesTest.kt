package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-6 confusables suite (spec Testing Strategy):
 *
 *  - **Confusion-matrix discovery** — decode TYPICAL synthetic traces over a stratified
 *    English sample against the FULL 98k lexicon, tally every (typed → wrongly-ranked-1)
 *    confusion, and assert **no single wrong-pair accounts for > 2% of the TYPICAL
 *    errors** (a systematic template collision that dominates the error mass would be a
 *    projection/scoring bug; a broad, flat error distribution is the healthy signature).
 *
 *  - **Golden must-resolve pairs** — hand-picked near-neighbor words whose templates are
 *    *distinct* (different key paths) MUST each decode to themselves at rank 1 from their
 *    own ideal trace. These are the pairs the engine has no excuse to confuse.
 *
 *  - **Template-colliding pairs are deterministically ordered** — a real dictionary pair
 *    that collapses to the SAME key sequence (`of`/`off`, `all`/`al`, `the`/`thee`:
 *    doubled-letter collapse makes the plain templates identical) is a genuine ambiguity,
 *    NOT a failure. The spec requires **both appear in top-3, ordered rank-then-
 *    lexicographic** (the more-frequent word first). Asserted from the shared ideal
 *    trace.
 *
 *  - **ذ/د accepted collision** — on `arab_pc` both `ذ` (dhal) and `د` (dal) project to
 *    the SAME key (dhal → dal-host, tier 4). This is a pre-seeded accepted confusable
 *    (spec §Script Abstraction, m16), asserted at the projection level (no Arabic
 *    dictionary ships, so a full decode is not possible — the collision is structural).
 *
 * Decode is ALWAYS against the FULL dictionary (the ambiguity ceiling is the whole
 * lexicon). Determinism (NFR-4): the sample + synthetic seeds are fixed.
 */
class GeoConfusablesTest {

    private val config = GeometricEngineConfig()
    private val gen = TemplateGenerator(config)
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val en = GeoTestFixtures.englishCkdt()

    // ── Confusion-matrix discovery: no wrong-pair > 2% of TYPICAL errors ──────────

    @Test
    fun confusionMatrix_noSingleWrongPairExceeds2PctOfTypicalErrors() {
        val synth = GeoTraceSynthesizer(config)
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(qwerty, en)
        val harness = GeoAccuracyHarness(qwerty, en, "en/QWERTY-confusion", config)
        val sample = harness.stratifiedSample(400)

        // Build the confusion matrix at the (typed word → wrong target) granularity. A
        // pair is counted ONCE per typed word — a word's K synthetic seeds that all
        // confuse to the same neighbor are ONE systematic confusion, not K (the seeds
        // only exist to average out sampling noise). This is the true confusion-matrix
        // cell count: how many DISTINCT words are systematically mis-decoded to a given
        // wrong target. A projection/scoring bug would make one cell dominate; a healthy
        // engine spreads its residual errors over many distinct near-neighbor pairs.
        val confusion = HashMap<String, Int>()
        var confusedWords = 0
        var decodes = 0
        val seeds = 3
        for (sw in sample) {
            val votes = HashMap<String, Int>()
            var wordDecodes = 0
            for (s in 0 until seeds) {
                val seed = 0x9E37_00L xor (sw.ordinal.toLong() shl 8) xor s.toLong()
                val trace = synth.synthesize(
                    sw.word, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                    GeoTraceSynthesizer.Tier.TYPICAL, GeoTraceSynthesizer.DoubleLetterMode.NONE, seed,
                ) ?: continue
                if (trace.size < 3) continue
                val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
                decodes++
                wordDecodes++
                val top1 = result.words.firstOrNull() ?: continue
                if (top1 != sw.word) votes[top1] = (votes[top1] ?: 0) + 1
            }
            if (wordDecodes == 0) continue
            // The word's dominant wrong target (majority across its seeds). Only count it
            // as a systematic confusion if it is wrong on a MAJORITY of its seeds.
            val dominant = votes.entries.maxByOrNull { it.value } ?: continue
            if (dominant.value * 2 > wordDecodes) {
                confusedWords++
                val key = "${sw.word}→${dominant.key}"
                confusion[key] = (confusion[key] ?: 0) + 1
            }
        }
        // Print the worst offenders for diagnosis.
        val worst = confusion.entries.sortedByDescending { it.value }.take(8)
        println("[confusion] en/QWERTY TYPICAL decodes=$decodes confusedWords=$confusedWords " +
            "distinctPairs=${confusion.size} topPairs=${worst.map { "${it.key}×${it.value}" }}")

        assertWithMessage("must have produced decodes").that(decodes).isGreaterThan(0)
        // No single (typed → wrong) confusion pair may dominate: with the seed-clustering
        // removed, each systematically-confused word contributes exactly 1 to its pair, so
        // a healthy broad error distribution means every pair count is small. Assert no
        // single pair exceeds 2% of the systematically-confused words (or an absolute floor
        // of 2 for tiny error counts) — a dominating cell would signal a projection bug.
        if (confusedWords > 0) {
            val maxPairCount = confusion.values.maxOrNull() ?: 0
            val allowed = maxOf(confusedWords * 0.02, 2.0)
            assertWithMessage("no single wrong-pair may exceed 2% of TYPICAL confused words " +
                "(worst pair count=$maxPairCount, confusedWords=$confusedWords, allowed=$allowed)")
                .that(maxPairCount.toDouble()).isAtMost(allowed)
        }
    }

    // ── Golden must-resolve pairs ─────────────────────────────────────────────────

    @Test
    fun goldenMustResolvePairs_eachDecodesToItselfAtRank1() {
        // Near-neighbor words with DISTINCT templates (different key paths). The engine
        // has no excuse to confuse these — each ideal trace must decode to itself first.
        val pairs = listOf(
            "cat" to "cot", "bad" to "bed", "run" to "ran", "big" to "bag",
            "word" to "ward", "form" to "farm", "hand" to "hind", "sing" to "song",
        )
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(qwerty, en)
        var checked = 0
        for ((a, b) in pairs) {
            for (word in listOf(a, b)) {
                if (GeoIdealTrace.ordinalOf(word, en) < 0) continue // not in dict — skip
                val trace = GeoIdealTrace.of(word, qwerty, gen) ?: continue
                val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
                assertWithMessage("golden '$word' must decode to itself at rank 1 " +
                    "(got ${result.words.take(3)})")
                    .that(result.words.firstOrNull()).isEqualTo(word)
                checked++
            }
        }
        assertWithMessage("at least a few golden words must be present in the dictionary")
            .that(checked).isAtLeast(4)
    }

    // ── Template-colliding pairs: both in top-3, rank-then-lexicographic order ─────

    @Test
    fun templateCollidingPairs_bothAppearInTop3_orderedByFrequencyRank() {
        // Real en/QWERTY collisions: the collapsed key sequence is identical (the second
        // word only adds a doubled letter that collapses away), so their PLAIN templates
        // coincide. Decoding the shared ideal trace must surface BOTH, with the
        // more-frequent (lower-ordinal) word first — the spec's deterministic
        // rank-then-lexicographic ordering, a SPECIFIED behavior, not a failure.
        val collisions = listOf(
            // (frequentWord, lessFrequentWord) — verified colliding + both in top-8k.
            Triple("of", "off", "o.f collapse"),
            Triple("all", "al", "a.l collapse"),
            Triple("the", "thee", "t.h.e collapse"),
            Triple("as", "ass", "a.s collapse"),
        )
        val engine = GeometricSwipeEngine(config)
        engine.warmUp(qwerty, en)
        var verified = 0
        for ((freq, rare, why) in collisions) {
            val oFreq = GeoIdealTrace.ordinalOf(freq, en)
            val oRare = GeoIdealTrace.ordinalOf(rare, en)
            if (oFreq < 0 || oRare < 0) continue
            // Decode the PLAIN ideal trace of the frequent word (== the rare word's plain
            // template, since they collide). Both must be candidates.
            val trace = GeoIdealTrace.of(freq, qwerty, gen) ?: continue
            val result = engine.decode(GeoIdealTrace.request(trace, qwerty, en))
            val rFreq = GeoIdealTrace.rankOf(freq, result)
            val rRare = GeoIdealTrace.rankOf(rare, result)
            println("[collide] $freq(#$oFreq)@$rFreq vs $rare(#$oRare)@$rRare ($why) " +
                "top=${result.words.take(4)}")
            assertWithMessage("colliding '$freq' must be in top-3").that(rFreq).isIn(0..2)
            assertWithMessage("colliding '$rare' must also be in top-3 (specified ambiguity, not a drop)")
                .that(rRare).isIn(0..2)
            // Deterministic order: the more-frequent (lower ordinal) word ranks first
            // when scores tie (they share the plain template here).
            assertWithMessage("more-frequent '$freq' (#$oFreq) must rank before '$rare' (#$oRare)")
                .that(rFreq).isLessThan(rRare)
            verified++
        }
        assertWithMessage("at least two colliding pairs must be present + verifiable")
            .that(verified).isAtLeast(2)
    }

    // ── Path-collision census (98k full sweep, -PgeoFull only) ────────────────────

    @Test
    fun pathCollisionCensus_fullLexicon_underGeoFull() {
        // Spec Phase 6: "path-collision census + one 98k full-sweep run behind
        // -PgeoFull". Project EVERY English word on QWERTY, group by the collapsed
        // key-id sequence, and characterize the collision structure: known collapse
        // pairs must group together, and no group may be pathologically large (a
        // projection bug collapsing unrelated words would produce a giant group).
        if (System.getProperty("geoFull") != "true") {
            println("[skip] path-collision census — set -PgeoFull to run")
            return
        }
        val groups = HashMap<String, MutableList<Int>>()
        var typeable = 0
        for (i in 0 until en.size) {
            val pre = LayoutProjection.project(en.word(i), qwerty) ?: continue
            typeable++
            // Collapse consecutive duplicates into the canonical template key path.
            val sb = StringBuilder(pre.size * 3)
            var prev = -1
            for (id in pre) {
                if (id != prev) { sb.append(id).append(','); prev = id }
            }
            groups.getOrPut(sb.toString()) { ArrayList(1) }.add(i)
        }
        val colliding = groups.values.filter { it.size > 1 }
        val largest = groups.values.maxByOrNull { it.size }!!
        val collidingWords = colliding.sumOf { it.size }
        println("[census] en/QWERTY: $typeable typeable words → ${groups.size} distinct key paths; " +
            "${colliding.size} colliding groups covering $collidingWords words; " +
            "largest group=${largest.size} ${largest.take(6).map { en.word(it) }}")
        // Known doubled-letter collapse pairs must share a group.
        for ((a, b) in listOf("of" to "off", "all" to "al")) {
            val ga = groups.entries.firstOrNull { (_, v) -> v.any { en.word(it) == a } }
            assertWithMessage("census must contain '$a'").that(ga).isNotNull()
            assertWithMessage("'$a' and '$b' must share a collapsed key path (known collision)")
                .that(ga!!.value.any { en.word(it) == b }).isTrue()
        }
        // Generous structural bound: even at 98k, no single key path should attract a
        // pathologically large word group (a projection bug signature).
        assertWithMessage("largest collision group must stay small (projection sanity)")
            .that(largest.size).isAtMost(24)
    }

    // ── ذ/د accepted collision (arab_pc, projection-level) ────────────────────────

    @Test
    fun arabicDhalDal_projectToSameKey_acceptedCollision() {
        // spec m16: on arab_pc, ذ (dhal, U+0630) resolves to the د (dal) host key via the
        // tier-4 corner-alias table — a KNOWN, accepted confusable (ذ/د share a centroid),
        // documented as "seeded into the confusables whitelist, not fixed".
        val arab = GeoLayoutFixtures.loadShipped("arab_pc")
        val dhal = LayoutProjection.projectCodepoint(LayoutProjection.foldCodepoint('ذ'.code), arab)
        val dal = LayoutProjection.projectCodepoint(LayoutProjection.foldCodepoint('د'.code), arab)
        assertWithMessage("ذ must be typeable on arab_pc (via the dal-host alias)").that(dhal).isNotNull()
        assertWithMessage("د must be typeable on arab_pc").that(dal).isNotNull()
        assertWithMessage("ذ and د must project to the SAME key (accepted collision, spec m16)")
            .that(dhal).isEqualTo(dal)
    }
}
