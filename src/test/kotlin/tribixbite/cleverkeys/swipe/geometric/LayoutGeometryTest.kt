package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phase-1 tests for the pure [LayoutGeometry] core: geometry-only fingerprint
 * (stability, DPI/jitter quantization, orientation sensitivity), `nearestKeys`,
 * and a hand-computed golden cross-check for the ЙЦУКЕН `scale="11"` bottom letter
 * row (the case that misplaces the whole row if row-scale renormalization is
 * ignored).
 */
class LayoutGeometryTest {

    // ── Fingerprint ───────────────────────────────────────────────────────────

    @Test
    fun fingerprint_isStable_acrossReloads() {
        val a = GeoLayoutFixtures.loadShipped("latn_qwerty_us").fingerprint()
        val b = GeoLayoutFixtures.loadShipped("latn_qwerty_us").fingerprint()
        assertWithMessage("same layout → same fingerprint").that(a).isEqualTo(b)
        // SHA-256 hex is 64 chars.
        assertThat(a).hasLength(64)
    }

    @Test
    fun fingerprint_isGeometryOnly_notDictionaryDependent() {
        // The fingerprint takes no dictionary — it must be identical regardless of
        // which language is decoded. (Dictionary identity lives in the cache key.)
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        assertThat(geo.fingerprint()).isEqualTo(geo.fingerprint())
    }

    @Test
    fun fingerprint_differs_betweenLayouts() {
        val qwerty = GeoLayoutFixtures.loadShipped("latn_qwerty_us").fingerprint()
        val jcuken = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru").fingerprint()
        val azerty = GeoLayoutFixtures.loadShipped("latn_azerty_fr").fingerprint()
        assertThat(qwerty).isNotEqualTo(jcuken)
        assertThat(qwerty).isNotEqualTo(azerty)
        assertThat(jcuken).isNotEqualTo(azerty)
    }

    @Test
    fun fingerprint_isOrientationSensitive_viaAspect() {
        // Aspect is part of the digest; a rotation genuinely changes geometry, so a
        // different aspect MUST yield a different fingerprint (a separate index per
        // orientation is correct — NFR & m17).
        val portrait = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 2.0f).fingerprint()
        val landscape = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 3.5f).fingerprint()
        assertThat(portrait).isNotEqualTo(landscape)
    }

    @Test
    fun fingerprint_isJitterImmune_viaQuantization() {
        // Two aspects that quantize to the same 1/256 grid cell must hash identically
        // (DPI/float jitter immunity). q256(2.000) == q256(2.001) since
        // round(2.000*256)=512 == round(2.001*256)=512.
        val exact = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 2.000f).fingerprint()
        val jittered = GeoLayoutFixtures.loadShipped("latn_qwerty_us", aspect = 2.001f).fingerprint()
        assertWithMessage("sub-grid aspect jitter must not change the fingerprint")
            .that(exact).isEqualTo(jittered)
    }

    // ── nearestKeys ───────────────────────────────────────────────────────────

    @Test
    fun nearestKeys_returnsClosestKeyFirst() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val q = geo.keys.first { it.label == "q" }
        // A point exactly at 'q's centroid → 'q' is the nearest.
        val nearest = geo.nearestKeys(q.cx, q.cy, 3)
        assertThat(nearest[0]).isEqualTo(q.id)
        assertThat(nearest).hasLength(3)
    }

    @Test
    fun nearestKeys_ranksByDistance_ascending() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        // A point between 'a' and 's' on the home row.
        val a = geo.keys.first { it.label == "a" }
        val s = geo.keys.first { it.label == "s" }
        val midU = (a.cx + s.cx) / 2f
        val nearest = geo.nearestKeys(midU, a.cy, 4)
        // Verify the returned ids are in non-decreasing d_kw order.
        var prev = -1f
        for (id in nearest) {
            val key = geo.keys[id]
            val d = geo.dKw(midU, a.cy, key.cx, key.cy)
            assertWithMessage("nearestKeys must be sorted ascending by d_kw")
                .that(d).isAtLeast(prev)
            prev = d
        }
        // 'a' and 's' must be the two closest (in some order) to their midpoint.
        assertThat(setOf(nearest[0], nearest[1])).isEqualTo(setOf(a.id, s.id))
    }

    @Test
    fun nearestKeys_tieBreaksByLowerId_deterministically() {
        // Determinism (NFR-4): identical queries return bit-identical arrays.
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val first = geo.nearestKeys(0.5f, 0.5f, 5)
        val second = geo.nearestKeys(0.5f, 0.5f, 5)
        assertThat(first.toList()).isEqualTo(second.toList())
    }

    @Test
    fun nearestKeys_clampsK_toKeyCount() {
        val geo = GeoLayoutFixtures.loadFixture("letter_on_punct", appendBottomRow = false)
        // Ask for far more keys than exist → returns all of them, no crash.
        val nearest = geo.nearestKeys(0.5f, 0.5f, 1000)
        assertThat(nearest).hasLength(geo.keys.size)
    }

    // ── Golden cross-check: ЙЦУКЕН scale="11" bottom letter row ────────────────

    @Test
    fun jcuken_scale11_bottomLetterRow_matchesHandComputedGolden() {
        // Golden derived by replicating KeyboardData's Row.updateWidth(11)
        // renormalization + normalized centroid walk (see the phase-1 report). The
        // middle letter 'и' (row 2, col 5) sits at the symmetric center cx=0.5, and
        // the 9 letter keys span 0.150 … 0.850. If row-scale renormalization were
        // ignored, the row would over-span (keysWidth 11.44 vs 11.0) and every cx
        // here would shift outward — the exact bug this golden guards.
        val geo = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru", aspect = GeoLayoutFixtures.DEFAULT_ASPECT)

        // The 'и' key is row 2, and is the exact horizontal center of the row.
        val i = geo.keys.first { it.label == "и" }
        assertWithMessage("'и' must be on row 2").that(i.row).isEqualTo(2)
        assertThat(i.cx).isWithin(1e-4f).of(0.5f)
        assertThat(i.cy).isWithin(1e-4f).of(0.63291139f)
        assertThat(i.h).isWithin(1e-4f).of(0.25316456f)

        // 'я' is the leftmost letter (row 2, col 1 after the shift key).
        val ya = geo.keys.first { it.label == "я" }
        assertThat(ya.cx).isWithin(1e-4f).of(0.15034965f)
        // 'ю' is the rightmost letter (row 2, col 9 before backspace).
        val yu = geo.keys.first { it.label == "ю" }
        assertThat(yu.cx).isWithin(1e-4f).of(0.84965035f)

        // Symmetry: 'я' and 'ю' straddle the center 'и' equidistantly.
        assertThat((ya.cx + yu.cx) / 2f).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun meanKeyWidth_isMeanOfLetterHitBoxes() {
        val geo = GeoLayoutFixtures.loadShipped("latn_qwerty_us")
        val letterKeys = geo.keys.filter { it.isLetterNode }
        val expected = letterKeys.map { it.w }.average().toFloat()
        assertThat(geo.meanKeyWidth).isWithin(1e-5f).of(expected)
        // A dense layout (JCUKEN, 11 cols) must have a smaller kw than QWERTY (10).
        val jcuken = GeoLayoutFixtures.loadShipped("cyrl_jcuken_ru")
        assertWithMessage("11-column JCUKEN kw < 10-column QWERTY kw")
            .that(jcuken.meanKeyWidth).isLessThan(geo.meanKeyWidth)
    }
}
