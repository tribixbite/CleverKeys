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
 * layouts. Uses the string overload (same seam SwipeLayoutSupportTest exercises for the
 * underlying QWERTY-Latin predicate).
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

    // ── Mode.fromPref parsing (the pref → enum seam the IME uses) ───────────────────

    @Test
    fun `fromPref parses the three modes case-insensitively`() {
        assertThat(Mode.fromPref("hybrid")).isEqualTo(Mode.HYBRID)
        assertThat(Mode.fromPref("Geometric")).isEqualTo(Mode.GEOMETRIC)
        assertThat(Mode.fromPref("neural")).isEqualTo(Mode.NEURAL)
    }

    @Test
    fun `fromPref falls back to neural on unknown or null values`() {
        assertThat(Mode.fromPref(null)).isEqualTo(Mode.NEURAL)
        assertThat(Mode.fromPref("")).isEqualTo(Mode.NEURAL)
        assertThat(Mode.fromPref("true")).isEqualTo(Mode.NEURAL) // legacy boolean-ish junk
    }
}
