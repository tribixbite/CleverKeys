package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-6 doubled-letter suite (spec Testing Strategy: "Double letters: `fell` beats
 * `fel`, `too` beats `to`-with-loop per LOOP/DWELL/NONE; `её` decodable in all modes").
 *
 * The engine encodes a doubled letter TWO ways (`WordTemplate` variantCount == 2):
 *  - variant 0 = the plain collapsed polyline (the doubled letter passed straight
 *    through), and
 *  - variant 1 = a small square LOOP at the doubled key.
 * The scorer takes whichever variant scores better, so a doubled word is decodable from
 * a straight swipe (variant 0) OR a looped swipe (variant 1). A single-key word ("её")
 * has the loop as its PRIMARY and only template (FR-6).
 *
 * These tests synthesize traces in each [GeoTraceSynthesizer.DoubleLetterMode]:
 *  - **LOOP** — the finger circles the doubled key. The doubled word (`fell`, `too`)
 *    must decode to itself: its loop variant matches the looped trace, so it beats the
 *    single-letter collision partner (`fel`, `to`) which has no loop.
 *  - **DWELL** — the finger lingers on the doubled key (extra collinear samples). The
 *    doubled word must still be reachable (in top-K).
 *  - **NONE** — the finger swipes straight through (no double marked). Here the doubled
 *    and single words share the plain template — a genuine ambiguity — so BOTH must
 *    appear, ordered by frequency (the spec's deterministic tie-break, not a failure).
 *  - **её** — a whole word collapsing to one key on ЙЦУКЕН (ё aliases to е). Its
 *    loop-primary template must decode it with no NaN in every mode (FR-6).
 *
 * Decode is ALWAYS against the FULL dictionary. Determinism (NFR-4): fixed seeds.
 */
class GeoDoubleLetterTest {

    private val config = GeometricEngineConfig()
    private val synth = GeoTraceSynthesizer(config)
    private val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
    private val jcuken = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
    private val en = GeoTestFixtures.englishCkdt()
    private val ru = GeoTestFixtures.russianCkdt()

    private val engineEn = GeometricSwipeEngine(config).also { it.warmUp(qwerty, en) }
    private val engineRu = GeometricSwipeEngine(config).also { it.warmUp(jcuken, ru) }

    // ── LOOP mode: the doubled word wins over its single-letter collision partner ──

    @Test
    fun loopMode_doubledWord_beatsSingleLetterCollisionPartner() {
        // (doubled, single) pairs that collide on the PLAIN template (double-l/o collapse)
        // but differ on the LOOP template. Only pairs where both are in the dictionary.
        val pairs = listOf("fell" to "fel", "too" to "to", "all" to "al", "off" to "of")
        var checked = 0
        for ((doubled, single) in pairs) {
            if (GeoIdealTrace.ordinalOf(doubled, en) < 0) continue
            // Synthesize a LOOP trace of the doubled word (the finger circles the double).
            val trace = synth.synthesize(
                doubled, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                GeoTraceSynthesizer.Tier.CLEAN, GeoTraceSynthesizer.DoubleLetterMode.LOOP, seed = 71L,
            ) ?: continue
            val result = engineEn.decode(GeoIdealTrace.request(trace, qwerty, en))
            val rDoubled = GeoIdealTrace.rankOf(doubled, result)
            val rSingle = GeoIdealTrace.rankOf(single, result)
            println("[loop] '$doubled'@$rDoubled vs '$single'@$rSingle top=${result.words.take(4)}")
            assertWithMessage("LOOP trace of '$doubled' must decode it (in top-3)").that(rDoubled).isIn(0..2)
            // The looped double must rank at or above its single-letter partner (the loop
            // variant matches the loop trace; the single word has no loop to match it).
            if (rSingle >= 0) {
                assertWithMessage("looped '$doubled' (@$rDoubled) must rank at/above '$single' (@$rSingle)")
                    .that(rDoubled).isAtMost(rSingle)
            }
            checked++
        }
        assertWithMessage("at least two doubled/single pairs must be present").that(checked).isAtLeast(2)
    }

    // ── DWELL mode: the doubled word stays reachable ──────────────────────────────

    @Test
    fun dwellMode_doubledWord_isReachable() {
        val words = listOf("fell", "too", "all", "book", "need", "good")
        var checked = 0
        for (word in words) {
            if (GeoIdealTrace.ordinalOf(word, en) < 0) continue
            val trace = synth.synthesize(
                word, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                GeoTraceSynthesizer.Tier.CLEAN, GeoTraceSynthesizer.DoubleLetterMode.DWELL, seed = 33L,
            ) ?: continue
            val result = engineEn.decode(GeoIdealTrace.request(trace, qwerty, en))
            val rank = GeoIdealTrace.rankOf(word, result)
            println("[dwell] '$word'@$rank top=${result.words.take(3)}")
            assertWithMessage("DWELL trace of '$word' must keep it in top-K (got ${result.words.take(3)})")
                .that(rank).isIn(0 until config.maxResults)
            checked++
        }
        assertWithMessage("at least three dwell words must be present").that(checked).isAtLeast(3)
    }

    // ── NONE mode: both the doubled and single words appear, frequency-ordered ─────

    @Test
    fun noneMode_collidingPair_bothAppear_frequencyOrdered() {
        // A straight swipe (no loop) collapses the double, so the doubled and single
        // words share the plain template — both must appear, more-frequent first.
        val pairs = listOf("of" to "off", "all" to "al", "as" to "ass")
        var checked = 0
        for ((freq, other) in pairs) {
            val oFreq = GeoIdealTrace.ordinalOf(freq, en)
            val oOther = GeoIdealTrace.ordinalOf(other, en)
            if (oFreq < 0 || oOther < 0) continue
            val trace = synth.synthesize(
                freq, qwerty, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                GeoTraceSynthesizer.Tier.CLEAN, GeoTraceSynthesizer.DoubleLetterMode.NONE, seed = 12L,
            ) ?: continue
            val result = engineEn.decode(GeoIdealTrace.request(trace, qwerty, en))
            val rFreq = GeoIdealTrace.rankOf(freq, result)
            val rOther = GeoIdealTrace.rankOf(other, result)
            println("[none] $freq(#$oFreq)@$rFreq vs $other(#$oOther)@$rOther top=${result.words.take(4)}")
            assertWithMessage("'$freq' must be in top-3").that(rFreq).isIn(0..2)
            assertWithMessage("'$other' must also appear (collision ambiguity)").that(rOther).isAtLeast(0)
            // Lower ordinal (more frequent) ranks first on the shared template.
            val lowerOrdinalFirst = if (oFreq < oOther) rFreq < rOther else rOther < rFreq
            assertWithMessage("the more-frequent word must rank first ($freq#$oFreq@$rFreq / $other#$oOther@$rOther)")
                .that(lowerOrdinalFirst).isTrue()
            checked++
        }
        assertWithMessage("at least two colliding pairs must be present").that(checked).isAtLeast(2)
    }

    // ── её: single-key word decodable in all modes (FR-6, no NaN) ─────────────────

    @Test
    fun singleKeyWord_yoyo_decodableInAllModes_noNaN() {
        // "её" collapses to a single key on ЙЦУКЕН (ё aliases to е) → loop-primary
        // template. Every double-letter mode must yield a finite decode that surfaces it.
        val hasYoyo = GeoIdealTrace.ordinalOf("её", ru) >= 0
        assertWithMessage("'её' must be in the ru dictionary").that(hasYoyo).isTrue()
        for (mode in GeoTraceSynthesizer.DoubleLetterMode.values()) {
            val trace = synth.synthesize(
                "её", jcuken, GeoIdealTrace.WIDTH_PX, GeoIdealTrace.HEIGHT_PX,
                GeoTraceSynthesizer.Tier.CLEAN, mode, seed = 5L,
            )
            assertWithMessage("mode=$mode: 'её' trace must not be null (typeable)").that(trace).isNotNull()
            val result = engineRu.decode(GeoIdealTrace.request(trace!!, jcuken, ru))
            assertWithMessage("mode=$mode: 'её' decode must produce candidates").that(result.words).isNotEmpty()
            // No NaN escapes — every emitted score is a finite 0..1000 int.
            for (s in result.scores) {
                assertWithMessage("mode=$mode: 'её' scores must be finite 0..1000").that(s in 0..1000).isTrue()
            }
            val rank = GeoIdealTrace.rankOf("её", result)
            println("[её] mode=$mode rank=$rank top=${result.words.take(3)}")
            // It should be reachable in top-K in every mode (the loop-primary rule).
            assertWithMessage("mode=$mode: 'её' must be reachable in top-K").that(rank).isIn(0 until config.maxResults)
        }
    }
}
