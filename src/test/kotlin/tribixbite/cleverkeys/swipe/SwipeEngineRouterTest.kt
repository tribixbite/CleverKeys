package tribixbite.cleverkeys.swipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Engine
import tribixbite.cleverkeys.swipe.SwipeEngineRouter.Mode

/**
 * WP9 R-1 step 9 (JVM leg) — routing table for the mode-based [SwipeEngineRouter] (v1.1:
 * the `swipe_engine_mode` selector replaced the boolean flag).
 *
 * Routing is (mode, layout)-based: NEURAL = QWERTY-only swipe (the #9 gate, pre-geo
 * behavior); HYBRID = neural on QWERTY + geometric elsewhere; GEOMETRIC = geometric on ALL
 * layouts; CTC (G5, gate widened 2026-08-15) = CTC trie-beam on ANY Latin-script layout +
 * geometric on non-Latin/unknown scripts. Uses the string overload (same seam
 * SwipeLayoutSupportTest exercises for the underlying QWERTY-Latin predicate).
 */
class SwipeEngineRouterTest {

    // ── QWERTY-Latin ────────────────────────────────────────────────────────────────

    @Test
    fun `qwerty routes neural in neural mode`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.NEURAL))
            .isEqualTo(Engine.NEURAL)
    }

    @Test
    fun `qwerty routes neural in hybrid mode — geometric never steals qwerty in hybrid`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.HYBRID))
            .isEqualTo(Engine.NEURAL)
    }

    @Test
    fun `qwerty routes geometric in geometric mode — full geo opt-in covers qwerty too`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
    }

    // ── Non-QWERTY Latin (Dvorak/AZERTY/QWERTZ) ─────────────────────────────────────

    @Test
    fun `dvorak routes geometric in hybrid mode`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", Mode.HYBRID))
            .isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `dvorak routes none in neural mode — pre-geo behavior preserved`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", Mode.NEURAL))
            .isEqualTo(Engine.NONE)
    }

    @Test
    fun `azerty routes geometric in hybrid and geometric modes`() {
        assertThat(SwipeEngineRouter.route("AZERTY (FR)", "latin", Mode.HYBRID))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("AZERTY (FR)", "latin", Mode.GEOMETRIC))
            .isEqualTo(Engine.GEOMETRIC)
    }

    // ── Non-Latin scripts (incl. the QWERTY-named Greek trap) ───────────────────────

    @Test
    fun `cyrillic jcuken routes geometric in hybrid, none in neural`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.HYBRID))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.NEURAL))
            .isEqualTo(Engine.NONE)
    }

    @Test
    fun `greek qwerty is NOT neural — script wins over the qwerty name`() {
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.HYBRID))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.NEURAL))
            .isEqualTo(Engine.NONE)
    }

    // ── Unknown metadata → conservative: never neural ───────────────────────────────

    @Test
    fun `null name or script never routes neural`() {
        assertThat(SwipeEngineRouter.route(null, "latin", Mode.NEURAL)).isEqualTo(Engine.NONE)
        assertThat(SwipeEngineRouter.route("Dvorak", null, Mode.NEURAL)).isEqualTo(Engine.NONE)
        assertThat(SwipeEngineRouter.route(null, null, Mode.HYBRID)).isEqualTo(Engine.GEOMETRIC)
    }

    // ── Mode.CTC (G5) — CTC on ANY Latin layout, geometric elsewhere, never NONE ────
    // Gate widened 2026-08-15: the CTC encoder is layout-agnostic (key geometry is a
    // model input), validated on dvorak 89.87 / dvorak-app-geometry 88.98 top-1, so
    // Latin non-QWERTY layouts route CTC instead of geometric. Non-Latin/unknown
    // scripts stay geometric (the adapter can't build an a–z CtcLayout from them).

    @Test
    fun `qwerty routes ctc in ctc mode`() {
        assertThat(SwipeEngineRouter.route("QWERTY (US)", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
    }

    @Test
    fun `dvorak and azerty route ctc in ctc mode — the encoder is layout-agnostic`() {
        assertThat(SwipeEngineRouter.route("Dvorak", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
        assertThat(SwipeEngineRouter.route("AZERTY (FR)", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
    }

    @Test
    fun `colemak routes ctc in ctc mode, geometric in hybrid — widening is ctc-only`() {
        assertThat(SwipeEngineRouter.route("Colemak", "latin", Mode.CTC))
            .isEqualTo(Engine.CTC)
        assertThat(SwipeEngineRouter.route("Colemak", "latin", Mode.HYBRID))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("Colemak", "latin", Mode.NEURAL))
            .isEqualTo(Engine.NONE)
    }

    @Test
    fun `cyrillic and greek route geometric in ctc mode`() {
        assertThat(SwipeEngineRouter.route("ЙЦУКЕН", "cyrillic", Mode.CTC))
            .isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route("QWERTY (Ελληνικά)", "greek", Mode.CTC))
            .isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `unknown script routes geometric in ctc mode — never neural, never none`() {
        assertThat(SwipeEngineRouter.route("Dvorak", null, Mode.CTC)).isEqualTo(Engine.GEOMETRIC)
        assertThat(SwipeEngineRouter.route(null, null, Mode.CTC)).isEqualTo(Engine.GEOMETRIC)
    }

    @Test
    fun `unknown name with latin script routes ctc in ctc mode — script decides`() {
        // The name only mattered for the QWERTY predicate; under the widened gate any
        // known-Latin layout is CTC-eligible (dispatch-time supportsLayout guards a
        // letter-incomplete layout back to geometric).
        assertThat(SwipeEngineRouter.route(null, "latin", Mode.CTC)).isEqualTo(Engine.CTC)
    }

    // ── Mode.fromPref parsing (the pref → enum seam the IME uses) ───────────────────

    @Test
    fun `fromPref parses the four modes case-insensitively`() {
        assertThat(Mode.fromPref("hybrid")).isEqualTo(Mode.HYBRID)
        assertThat(Mode.fromPref("Geometric")).isEqualTo(Mode.GEOMETRIC)
        assertThat(Mode.fromPref("neural")).isEqualTo(Mode.NEURAL)
        assertThat(Mode.fromPref("ctc")).isEqualTo(Mode.CTC)
        assertThat(Mode.fromPref("CTC")).isEqualTo(Mode.CTC)
    }

    @Test
    fun `fromPref falls back to neural on unknown or null values`() {
        assertThat(Mode.fromPref(null)).isEqualTo(Mode.NEURAL)
        assertThat(Mode.fromPref("")).isEqualTo(Mode.NEURAL)
        assertThat(Mode.fromPref("true")).isEqualTo(Mode.NEURAL) // legacy boolean-ish junk
    }
}
