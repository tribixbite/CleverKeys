package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-2 tests for [TemplateGenerator] / [WordTemplate] variant encoding: duplicate
 * collapse, the loop variant for doubled letters, the SINGLE-KEY loop-primary rule
 * (`её`/`ее` produce a finite-scoring loop template — never a degenerate 1-point
 * template, FR-6), RTL Arabic templates that start at the visual right, and a sweep
 * asserting every top-1000 word of every fixture language yields a finite template or
 * is intentionally skipped (untypeable, per FR-4).
 *
 * "Finite-scoring" is asserted structurally (the scorer lands in Phase 4): every
 * resampled coordinate is a finite float in [0,1], every per-variant path length is
 * finite and > 0, and every template spans a nonzero bbox — the conditions that keep
 * d_shape / d_loc / the length-ratio prior finite downstream.
 */
class TemplateGeneratorTest {

    private val gen = TemplateGenerator(GeometricEngineConfig())

    // ── Structural finiteness helpers ─────────────────────────────────────────

    /** Assert every coordinate of every variant is finite ∈ [0,1] and the span > 0. */
    private fun assertTemplateFinite(t: WordTemplate, label: String) {
        val n2 = t.pointCount * 2
        val buf = FloatArray(n2)
        for (v in 0 until t.variantCount) {
            t.copyVariantInto(v, buf)
            var minU = Float.POSITIVE_INFINITY; var maxU = Float.NEGATIVE_INFINITY
            var minV = Float.POSITIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
            for (k in 0 until t.pointCount) {
                val u = buf[2 * k]; val vv = buf[2 * k + 1]
                assertWithMessage("$label variant $v u finite").that(u.isFinite()).isTrue()
                assertWithMessage("$label variant $v v finite").that(vv.isFinite()).isTrue()
                assertThat(u).isAtLeast(0f); assertThat(u).isAtMost(1f)
                assertThat(vv).isAtLeast(0f); assertThat(vv).isAtMost(1f)
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (vv < minV) minV = vv; if (vv > maxV) maxV = vv
            }
            val span = (maxU - minU) + (maxV - minV)
            assertWithMessage("$label variant $v must span a nonzero bbox").that(span).isGreaterThan(0f)
            val len = t.pathLengthsKw[v]
            assertWithMessage("$label variant $v path length finite").that(len.isFinite()).isTrue()
            assertWithMessage("$label variant $v path length > 0").that(len).isGreaterThan(0f)
        }
    }

    // ── Duplicate collapse + loop variants ────────────────────────────────────

    @Test
    fun plainWord_hasSingleVariant() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // "cat" has no doubled letter → 1 variant, 3 distinct keys.
        val t = gen.generate("cat", 0, geo)!!
        assertThat(t.variantCount).isEqualTo(1)
        assertThat(t.pathLengthsKw).hasLength(1)
        assertTemplateFinite(t, "cat")
    }

    @Test
    fun doubledLetter_producesLoopVariant() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // "fell" has a doubled 'l' → 2 variants (plain + loop). The loop variant is
        // longer (it detours around the doubled key).
        val fell = gen.generate("fell", 0, geo)!!
        assertWithMessage("'fell' must have plain + loop variants").that(fell.variantCount).isEqualTo(2)
        assertWithMessage("loop variant is longer than the plain variant")
            .that(fell.pathLengthsKw[1]).isGreaterThan(fell.pathLengthsKw[0])
        assertTemplateFinite(fell, "fell")

        // "too" (doubled 'o') also gets a loop variant.
        val too = gen.generate("too", 0, geo)!!
        assertThat(too.variantCount).isEqualTo(2)
        assertTemplateFinite(too, "too")
    }

    @Test
    fun collapsedSequence_removesConsecutiveDuplicates() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // "aardvark": the doubled leading 'a' collapses; the plain variant's key path
        // is a..r..d..v..a..r..k (7 distinct steps), not 8.
        val t = gen.generate("aardvark", 0, geo)!!
        assertThat(t.variantCount).isEqualTo(2) // doubled 'a'
        assertTemplateFinite(t, "aardvark")
    }

    // ── Single-key words (loop-primary rule) ──────────────────────────────────

    @Test
    fun singleKeyWord_yo_yo_producesLoopPrimaryTemplate() {
        // On ЙЦУКЕН, ё aliases to the е key: "её" collapses to a SINGLE key. The loop
        // variant becomes the primary (only) template — never a degenerate 1-point one.
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        val yeyo = gen.generate("её", 0, geo)!!
        assertWithMessage("'её' → single-key loop-primary template (1 variant)")
            .that(yeyo.variantCount).isEqualTo(1)
        assertTemplateFinite(yeyo, "её")

        // "ее" (both on the е key) is likewise a single-key loop-primary template.
        val yeye = gen.generate("ее", 0, geo)!!
        assertThat(yeye.variantCount).isEqualTo(1)
        assertTemplateFinite(yeye, "ее")
    }

    @Test
    fun singleKeyWord_hasNonzeroSpanAndLength() {
        // FR-6 / §2: the loop-primary template must have nonzero path length AND bbox
        // so every downstream formula (d_shape scale, length ratio) stays finite.
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // "aaa" collapses to the single 'a' key.
        val t = gen.generate("aaa", 0, geo)!!
        assertThat(t.variantCount).isEqualTo(1)
        assertThat(t.pathLengthsKw[0]).isGreaterThan(0f)
        assertTemplateFinite(t, "aaa")
    }

    // ── RTL Arabic direction ──────────────────────────────────────────────────

    @Test
    fun arabicRtl_templateStartsAtVisualRight() {
        // Logical-order codepoints, visual coordinates, zero bidi (§ RTL). On arab_pc,
        // د (dal) is the rightmost center of row 0 (high u); ش (shin) is the leftmost
        // of row 1 (low u). The word "دش" starts with the visually-RIGHT letter, so its
        // template's first point sits to the right of its last point — which IS the
        // natural finger motion for an RTL word.
        val geo = GeoLayoutFixtures.loadShipped("arab_pc")
        val dal = geo.keys.first { it.label == "د" }
        val shin = geo.keys.first { it.label == "ش" }
        assertWithMessage("د must sit to the visual right of ش").that(dal.cx).isGreaterThan(shin.cx)

        val ids = LayoutProjection.project("دش", geo)!!
        val t = gen.fromKeyIds(ids, 0, geo)
        val buf = FloatArray(t.pointCount * 2)
        t.copyVariantInto(0, buf)
        val firstU = buf[0]
        val lastU = buf[(t.pointCount - 1) * 2]
        assertWithMessage("RTL template must start at the visual right (u decreasing)")
            .that(firstU).isGreaterThan(lastU)
        assertTemplateFinite(t, "دش")
    }

    // ── Full-lexicon sweep: every top-1000 word is finite or skipped ──────────

    @Test
    fun sweep_englishTop1000_allFiniteOrSkipped() {
        sweepLayout(GeoLayoutFixtures.loadShipped("latn_qwerty_us"), GeoTestFixtures.englishCkdt(), "en/QWERTY")
    }

    @Test
    fun sweep_russianTop1000_allFiniteOrSkipped() {
        sweepLayout(GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru"), GeoTestFixtures.russianCkdt(), "ru/JCUKEN")
    }

    @Test
    fun sweep_frenchTop1000_allFiniteOrSkipped() {
        sweepLayout(GeoLayoutFixtures.loadShipped("latn_azerty_fr"), GeoTestFixtures.frenchCkdt(), "fr/AZERTY")
    }

    @Test
    fun sweep_germanTop1000_allFiniteOrSkipped() {
        sweepLayout(GeoLayoutFixtures.loadShipped("latn_qwertz_de"), GeoTestFixtures.germanCkdt(), "de/QWERTZ")
    }

    /**
     * Assert every top-1000 word of [dict] either yields a fully finite template on
     * [layout] or is intentionally skipped (untypeable — the documented FR-4 reason).
     * Also assert the typeable coverage is near-total (≥ 95%): observed coverage is
     * en/de 100%, ru 99.7%, fr 99.8% — the handful of skips are genuine loanword /
     * foreign-character cases. A layout that skipped everything would vacuously pass
     * the finiteness loop, so the coverage floor is the real regression guard (e.g. it
     * catches tier-3 NFD breaking, which would drop every accented word).
     */
    private fun sweepLayout(layout: LayoutGeometry, dict: GeometricDictionary, label: String) {
        val count = minOf(1000, dict.size)
        var typeable = 0
        for (i in 0 until count) {
            val word = dict.word(i)
            val t = gen.generate(word, i, layout)
            if (t == null) continue // intentionally skipped: contains an untypeable codepoint
            typeable++
            assertTemplateFinite(t, "$label '$word'")
        }
        println("[sweep] $label: $typeable / $count top words typeable (${100 * typeable / count}%)")
        assertWithMessage("$label: near-total typeable coverage of the top-1000 (>= 95%)")
            .that(typeable).isAtLeast((count * 95) / 100)
    }
}
